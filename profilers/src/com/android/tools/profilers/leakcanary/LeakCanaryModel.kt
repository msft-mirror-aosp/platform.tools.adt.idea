/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profilers.leakcanary

import com.android.tools.adtui.model.Range
import com.android.tools.adtui.model.filter.Filter
import com.android.tools.adtui.model.updater.Updatable
import com.android.tools.idea.codenavigation.CodeLocation
import com.android.tools.idea.transport.poller.TransportEventListener
import com.android.tools.leakcanarylib.data.Analysis
import com.android.tools.leakcanarylib.data.AnalysisFailure
import com.android.tools.leakcanarylib.data.AnalysisSuccess
import com.android.tools.leakcanarylib.data.AnalysisUpdate
import com.android.tools.leakcanarylib.data.Leak
import com.android.tools.leakcanarylib.data.LeakType
import com.android.tools.leakcanarylib.data.LeakingStatus
import com.android.tools.leakcanarylib.data.Node
import com.android.tools.profiler.proto.Commands
import com.android.tools.profiler.proto.Commands.Command.CommandType.STOP_LEAKCANARY_OBJECT_COUNT_TRACKING
import com.android.tools.profiler.proto.Commands.StartLeakCanaryTaskData
import com.android.tools.profiler.proto.Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_APP_CONTEXT_NULL
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_LOGCAT_PARSING_FAILURE
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED
import com.android.tools.profiler.proto.Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_UNSUPPORTED_VERSION
import com.android.tools.profiler.proto.Transport
import com.android.tools.profilers.ModelStage
import com.android.tools.profilers.Notification
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.cpu.config.LeakCanaryConfiguration
import com.android.tools.profilers.cpu.config.LeakCanaryMode
import com.android.tools.profilers.tasks.analytics.LeakCanaryLeakAnalysis
import com.android.tools.profilers.tasks.analytics.LeakCanaryProcessingErrorCode
import com.android.tools.profilers.tasks.analytics.LeakCanaryStartErrorCode
import com.android.tools.profilers.tasks.analytics.LeakCanaryUiAction
import com.android.tools.profilers.tasks.analytics.TaskFinishedState
import com.android.tools.profilers.tasks.analytics.TaskProcessingFailedMetadata
import com.android.tools.profilers.tasks.analytics.TaskStartFailedMetadata
import com.android.tools.profilers.tasks.analytics.TaskTracker
import com.google.common.annotations.VisibleForTesting
import com.google.wireless.android.sdk.stats.AndroidProfilerEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NotNull

/**
 * The hardcoded version of the Shark Analyzer library bundled with Android Studio. This must be updated manually whenever the prebuilt
 * Shark dependencies in the BUILD file are updated.
 */
private const val ON_HOST_SHARK_VERSION = "2.14"

private const val METADATA_KEY_LEAKCANARY_MODE = "leakcanary_mode"
private const val METADATA_KEY_SHARK_VERSION = "shark_version"

/**
 * How long Studio waits for progress, a result or an error from the device after Force dump, or after Stop when Studio forces a heap dump
 * or an analysis is running. Every progress update starts the wait again.
 */
private val ON_DEVICE_ANSWER_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60)

private const val UNSUPPORTED_VERSION_MESSAGE =
  "Android Studio doesn't support this LeakCanary version. If you added LeakCanary yourself, use the version from studio-leakcanary instead."
private const val ON_DEVICE_HEAP_DUMP_FAILED_MESSAGE =
  "Heap dump failed on the device, so this heap dump has no leak results. Check that the device has enough free storage."
private const val ON_DEVICE_SAVE_FAILED_MESSAGE =
  "Couldn't save the LeakCanary analysis result on the device. Check that the device has enough free storage."
private const val ON_DEVICE_RESULT_READ_FAILED_MESSAGE =
  "Couldn't get the LeakCanary analysis result from the device, so this heap dump has no leak results."
private const val LEAKCANARY_WARNING_TITLE = "LeakCanary Warning"

/** Represents the top-level scope filter options for LeakCanary analysis. */
enum class LeakFilterScope {
  ALL,
  APP,
  LIBRARY,
}

class LeakCanaryModel(
  @NotNull private val profilers: StudioProfilers,
  heapDumper: LeakCanaryHeapDumper? = null,
  coroutineContext: CoroutineContext = Dispatchers.Main,
) : ModelStage(profilers), Updatable {

  private lateinit var statusListener: TransportEventListener
  private lateinit var objectCountListener: TransportEventListener
  private lateinit var analysisProgressListener: TransportEventListener
  private lateinit var deviceErrorListener: TransportEventListener

  val isLeakCanaryStudioBotEnabled: Boolean
    get() = profilers.ideServices.featureConfig.isLeakCanaryStudioBotEnabled

  fun analyzeLeakWithStudioBot(leak: Leak) {
    profilers.ideServices.analyzeLeakWithStudioBot(leak.toString(), leak)
  }

  private val logger: Logger = Logger.getInstance(LeakCanaryModel::class.java)
  private var sessionData = profilers.session
  private val heapDumper: LeakCanaryHeapDumper
  private var lastAnalysisTimestampMs: Long = 0

  init {
    this.heapDumper =
      heapDumper
        ?: LeakCanaryHeapDumper(profilers).apply {
          onHostAnalysisFinished = { analysis, size, duration, analysisDuration ->
            handleLeakAnalysis(analysis, size, duration, analysisDuration)
          }
          onAnalysisProgress = { progress -> setAnalysisProgress(progress) }
          onFatalError = { error, message -> handleLeakCanaryFatalError(error, message) }
          onResetRetainedObjectCount = { profilers.ideServices.mainExecutor.execute { setObjectRetainedCount(0) } }
        }
  }

  private var _retainedObjectThreshold = MutableStateFlow(5)
  val retainedObjectThreshold = _retainedObjectThreshold.asStateFlow()
  private val _leaks = MutableStateFlow(listOf<Leak>())
  val leaks = _leaks.asStateFlow()
  private val _selectedLeak = MutableStateFlow<Leak?>(null)
  val selectedLeak = _selectedLeak.asStateFlow()
  private val _selectedLeakOccurrenceIndex = MutableStateFlow(0)
  val selectedLeakOccurrenceIndex = _selectedLeakOccurrenceIndex.asStateFlow()
  private val _isRecording = MutableStateFlow(false)
  val isRecording = _isRecording.asStateFlow()
  private val _elapsedNs = MutableStateFlow(0L)
  val elapsedNs = _elapsedNs.asStateFlow()
  private val _objectRetainedCount = MutableStateFlow(0)
  val objectRetainedCount = _objectRetainedCount.asStateFlow()
  private val _analysisProgress = MutableStateFlow(0)
  val analysisProgress = _analysisProgress.asStateFlow()

  private val _isStopping = MutableStateFlow(false)
  val isStopping = _isStopping.asStateFlow()
  private val scope = CoroutineScope(coroutineContext + SupervisorJob())
  val insightModel = LeakInsightModel(profilers.ideServices, scope, ::trackUiAction)
  private val _isForceDumpExecuting = MutableStateFlow(false)
  val isForceDumpExecuting = _isForceDumpExecuting.asStateFlow()
  private val isSharkVersionEmitted = AtomicBoolean(false)

  /**
   * True when this recording gets the count, progress, result and errors from perfa (ON_DEVICE with the occurrences flag on). Set when the
   * recording starts.
   */
  @VisibleForTesting var usesOnDeviceEvents = false

  // True when the recording started with the occurrences flag on. Then every automatic result is structured, so a text result can only be
  // a LeakCanary:Manual trace from Logcat.
  private var textResultsAreManualTraces = false

  /** Time since Force dump, Stop or the last progress update while Studio waits for an answer, or null when not waiting. */
  private var onDeviceAnswerWaitNs: Long? = null

  /**
   * Event time of the last perfa result, failed heap dump or failed result save. Progress with an earlier time was sent before it, so it is
   * ignored.
   */
  private var lastOnDeviceResultTimestampNs = Long.MIN_VALUE

  @VisibleForTesting var leakcanaryMode = StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE

  private val _isBannerVisible = MutableStateFlow(false)
  val isBannerVisible = _isBannerVisible.asStateFlow()

  /**
   * The app's studio-leakcanary version when it is older than [MIN_STUDIO_LEAKCANARY_VERSION], or null. While it is set, the outdated
   * library banner shows and the config banner is hidden.
   */
  private val _outdatedLibraryVersion = MutableStateFlow<String?>(null)
  val outdatedLibraryVersion = _outdatedLibraryVersion.asStateFlow()

  /** True when the outdated library banner can show the "Open build file" link. */
  private val _canOpenBuildFile = MutableStateFlow(false)
  val canOpenBuildFile = _canOpenBuildFile.asStateFlow()

  val isOccurrencesEnabled: Boolean
    get() = studioProfilers.ideServices.featureConfig.isLeakCanaryOccurrencesEnabled

  private val _filterScope = MutableStateFlow(LeakFilterScope.ALL)
  val filterScope = _filterScope.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  val searchQuery = _searchQuery.asStateFlow()

  private val _matchCase = MutableStateFlow(false)
  val matchCase = _matchCase.asStateFlow()

  private val _useRegex = MutableStateFlow(false)
  val useRegex = _useRegex.asStateFlow()

  /** Re-evaluates and stores the final filtered leak list, debouncing search input to avoid stutter. */
  @OptIn(FlowPreview::class)
  private val debouncedQueryFlow =
    combine(_searchQuery, _useRegex) { query, isRegex -> Pair(query, isRegex) }
      .debounce(SEARCH_DEBOUNCE_DELAY_MS)
      .onStart { emit(Pair(_searchQuery.value, _useRegex.value)) }

  /** A filtered list of leaks to display, updated automatically when the user changes any search or filter settings. */
  val filteredLeaks: StateFlow<List<Leak>> =
    combine(_leaks, _filterScope, debouncedQueryFlow, _matchCase) { leaks, scope, (query, isRegex), matchCase ->
        filterLeaks(leaks, scope, query, matchCase, isRegex)
      }
      .stateIn(this.scope, SharingStarted.Eagerly, emptyList())

  init {
    scope.launch {
      filteredLeaks.collect { currentFiltered ->
        val currentSelected = _selectedLeak.value
        if (currentSelected !in currentFiltered) {
          if (currentSelected != null) {
            logger.info("Selected leak is no longer visible in filtered leaks; auto-selecting first available leak.")
          }
          onLeakSelection(currentFiltered.firstOrNull())
        }
      }
    }
  }

  /** Sets the current LeakCanary mode (e.g., ON_DEVICE or ON_HOST) and updates the banner visibility accordingly. */
  fun setLeakCanaryMode(mode: StartLeakCanaryTaskData.LeakCanaryMode) {
    logger.info("LeakCanary running in ${mode.name} mode")
    leakcanaryMode = mode
    updateBannerVisibility()
  }

  /**
   * Reads the current LeakCanary configuration from the profiler settings and updates the running mode and threshold. If the user has
   * explicitly modified the settings from their defaults, it will also dismiss the new feature banner permanently.
   */
  fun updateModeFromSettings() {
    val featureLevel = profilers.device?.featureLevel ?: 0
    val configs = profilers.ideServices.getTaskCpuProfilerConfigs(featureLevel)
    val config = configs.filterIsInstance<LeakCanaryConfiguration>().firstOrNull()
    if (config != null) {
      if (!isRecording.value) {
        setLeakCanaryMode(config.mode)
        if (config.source == LeakCanaryMode.STUDIO) {
          _retainedObjectThreshold.value = config.threshold
          logger.info("Setting retained object threshold to ${config.threshold}")
        }
      }
      updateBannerVisibility()
    }
  }

  /**
   * Evaluates all conditions to determine if the educational feature banner should be displayed.
   *
   * The banner is only shown if ALL the following conditions are met:
   * 1. The user has not permanently suppressed the banner (by explicitly selecting "Don't show again").
   * 2. The current mode is Studio mode (ON_HOST).
   */
  private fun shouldShowEducationalBanner(): Boolean {
    val doNotShowAgain = profilers.ideServices.persistentProfilerPreferences.getBoolean(KEY_LEAKCANARY_BANNER_DO_NOT_SHOW, false)

    val featureLevel = profilers.device?.featureLevel ?: 0
    val configs = profilers.ideServices.getTaskCpuProfilerConfigs(featureLevel)
    val config = configs.filterIsInstance<LeakCanaryConfiguration>().firstOrNull()
    val isStudioMode = config?.source == LeakCanaryMode.STUDIO

    if (!isStudioMode || doNotShowAgain) {
      return false
    }
    return true
  }

  /** Updates whether the feature banner should be displayed to the user. Only one banner shows: the outdated library banner comes first. */
  private fun updateBannerVisibility() {
    _isBannerVisible.value = _outdatedLibraryVersion.value == null && shouldShowEducationalBanner()
  }

  /** Temporarily dismisses the feature banner for the current session. */
  fun dismissBanner() {
    logger.info("Dismissing LeakCanary banner.")
    _isBannerVisible.value = false
  }

  /** Permanently hides the feature banner across all sessions by updating user preferences. */
  fun setBannerDoNotShowAgain() {
    logger.info("Permanently hiding LeakCanary banner.")
    profilers.ideServices.persistentProfilerPreferences.setBoolean(KEY_LEAKCANARY_BANNER_DO_NOT_SHOW, true)
    updateBannerVisibility()
  }

  /** Hides the outdated library banner for this recording. */
  fun dismissOutdatedLibraryBanner() {
    logger.info("Dismissing the outdated studio-leakcanary banner.")
    _outdatedLibraryVersion.value = null
    updateBannerVisibility()
  }

  /** Opens the build file where studio-leakcanary is declared, so the user can update it. */
  fun openBuildFile() {
    logger.info("Opening the build file to update studio-leakcanary.")
    profilers.ideServices.openStudioLeakCanaryDeclaration()
  }

  /** Checks the studio-leakcanary version saved for this app process. Only with the occurrences flag on. */
  private fun checkSavedStudioLeakCanaryVersion() {
    val version =
      if (isOccurrencesEnabled) {
        loadStudioLeakCanaryVersion(profilers.ideServices.temporaryProfilerPreferences, profilers.session.streamId, profilers.session.pid)
      } else {
        null
      }
    showOutdatedLibraryBannerIfNeeded(version)
  }

  /** Shows the outdated library banner if [version] is older than [MIN_STUDIO_LEAKCANARY_VERSION]. A null [version] hides it. */
  private fun showOutdatedLibraryBannerIfNeeded(version: String?) {
    val outdatedVersion = version?.let { findOutdatedStudioLeakCanaryVersion(it) }
    _outdatedLibraryVersion.value = outdatedVersion
    _canOpenBuildFile.value = false
    updateBannerVisibility()
    if (outdatedVersion != null) {
      logger.info("studio-leakcanary $outdatedVersion is older than $MIN_STUDIO_LEAKCANARY_VERSION. Showing the outdated library banner.")
      // The link opens the build file of the selected run configuration, so only show it when that is the profiled app.
      if (!isProjectProcess(profilers.preferredProcessName, profilers.process?.name.orEmpty())) {
        logger.info("The profiled app is not the app of the selected run configuration, so there is no Open build file link.")
        return
      }
      // Looking for the build file reads files, so it runs off the main thread.
      profilers.ideServices.poolExecutor.execute {
        val canOpen = profilers.ideServices.hasStudioLeakCanaryBuildFile()
        profilers.ideServices.mainExecutor.execute {
          // Skip the result if the banner was closed or changed in the meantime.
          if (_outdatedLibraryVersion.value == outdatedVersion) {
            _canOpenBuildFile.value = canOpen
          }
        }
      }
    }
  }

  override fun onEnter() {
    sessionData = profilers.session
    // If we are entering this stage for a past recording (i.e., the session is not live),
    // we need to tell the TransportService to use task specific database to query.
    // For a new, live recording, this is handled by TransportService when the session starts.
    if (!profilers.sessionsManager.isSessionAlive) {
      profilers.sessionsManager.setTaskDb(sessionData)
    }
    updateBannerVisibility()
  }

  override fun onExit() {
    profilers.sessionsManager.unsetTaskDb(sessionData)
    scope.cancel()
  }

  fun startListening() {
    logger.info("Starting LeakCanary tracking.")
    isSharkVersionEmitted.set(false)
    updateModeFromSettings()
    usesOnDeviceEvents = leakcanaryMode == StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE && isOccurrencesEnabled
    textResultsAreManualTraces = isOccurrencesEnabled
    checkSavedStudioLeakCanaryVersion()
    onDeviceAnswerWaitNs = null
    lastOnDeviceResultTimestampNs = Long.MIN_VALUE
    _isForceDumpExecuting.value = false
    profilers.updater.register(this)
    setIsRecording(true)
    checkPresenceAndFetchThreshold()
    setObjectRetainedCount(0)
    setAnalysisProgress(0)
    resetFilters()
    registerLeakCanaryListeners()
    toggleLeakCanaryTracking(profilers.session, enable = true)
    saveModeAndHostSharkVersion()
  }

  fun requestStopRecording() {
    logger.info("Requesting to stop LeakCanary task.")
    _isStopping.value = true
    if (objectRetainedCount.value > 0 && analysisProgress.value == 0) {
      forceHeapDump()
    } else if (analysisProgress.value == 0) {
      stopListening()
    } else if (usesOnDeviceEvents) {
      // An analysis is already running on the device. Stop finishes when its result comes, or when the device stays silent too long.
      startWaitingForOnDeviceAnswer()
    }
  }

  fun stopListening(isUserInitiated: Boolean = true) {
    logger.info("Stopping LeakCanary task. isUserInitiated: $isUserInitiated")
    if (isUserInitiated) {
      myTaskTracker.trackLeakCanaryUiAction(LeakCanaryUiAction.STOP_RECORDING_CLICKED)
      if (heapDumper.isHeapDumpInProgress() || _analysisProgress.value in 1..99) {
        myTaskTracker.trackLeakCanaryUiAction(LeakCanaryUiAction.CANCELLED_DURING_ANALYSIS)
      }

      // Track the successful completion of the user-initiated leakCanary recording task.
      myTaskTracker.trackTaskFinished(TaskFinishedState.COMPLETED)
    }

    _isStopping.value = false
    onDeviceAnswerWaitNs = null
    setIsRecording(false)
    toggleLeakCanaryTracking(profilers.session, enable = false)
    deregisterLeakCanaryListeners()
    sendEndSessionCommand()
    profilers.updater.unregister(this)
  }

  /**
   * Sends the END_SESSION command to the on-device transport daemon (perfd). perfd handles this command to formally close the active
   * historical recording session and update the session database state.
   */
  private fun sendEndSessionCommand() {
    val endSessionCommand =
      Commands.Command.newBuilder()
        .setStreamId(profilers.session.streamId)
        .setPid(profilers.session.pid)
        .setSessionId(profilers.session.sessionId)
        .setType(Commands.Command.CommandType.END_SESSION)
        .setEndSession(Commands.EndSession.newBuilder().setSessionId(profilers.session.sessionId))
        .build()

    profilers.ideServices.poolExecutor.execute {
      try {
        profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(endSessionCommand).build())
        logger.info(
          "Sent END_SESSION command to transport. streamId: ${endSessionCommand.streamId}, pid: ${endSessionCommand.pid}, sessionId: ${endSessionCommand.sessionId}"
        )
      } catch (e: Exception) {
        logger.warn(
          "Failed to execute END_SESSION command. streamId: ${endSessionCommand.streamId}, pid: ${endSessionCommand.pid}, sessionId: ${endSessionCommand.sessionId}",
          e,
        )
      }
    }
  }

  /**
   * Forces a heap dump based on the current mode.
   *
   * In ON_DEVICE mode, it sends a command to the device to trigger LeakCanary's internal heap dumper. In ON_HOST mode, it triggers the
   * Studio-side heap dumper (LeakCanaryHeapDumper).
   */
  fun forceHeapDump(isUserInitiated: Boolean = false) {
    if (isUserInitiated) {
      logger.info("User requested force heap dump.")
      myTaskTracker.trackLeakCanaryUiAction(LeakCanaryUiAction.FORCE_DUMP_CLICKED)
    } else {
      logger.info("Automatically triggered heap dump.")
    }
    _isForceDumpExecuting.value = true // Disable the Force Dump button
    if (leakcanaryMode == StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE) {
      if (usesOnDeviceEvents) {
        startWaitingForOnDeviceAnswer()
      }
      profilers.ideServices.poolExecutor.execute {
        val forceDumpCommand =
          Commands.Command.newBuilder()
            .setStreamId(sessionData.streamId)
            .setPid(sessionData.pid)
            .setSessionId(sessionData.sessionId)
            .setType(Commands.Command.CommandType.FORCE_DUMP_LEAKCANARY_ON_DEVICE)
            .build()

        try {
          profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(forceDumpCommand).build())
          logger.info(
            "Sent FORCE_DUMP_LEAKCANARY_ON_DEVICE command to transport. streamId: ${forceDumpCommand.streamId}, pid: ${forceDumpCommand.pid}, sessionId: ${forceDumpCommand.sessionId}"
          )
        } catch (e: Exception) {
          logger.warn(
            "Failed to execute FORCE_DUMP_LEAKCANARY_ON_DEVICE command. streamId: ${forceDumpCommand.streamId}, pid: ${forceDumpCommand.pid}, sessionId: ${forceDumpCommand.sessionId}",
            e,
          )
          _isForceDumpExecuting.value = false // Re-enable the Force Dump button if transport fails immediately
        }
      }
    } else {
      profilers.ideServices.poolExecutor.execute {
        if (!heapDumper.triggerAndAnalyze()) {
          // The click was ignored because a dump was already running.
          // Re-enable the UI button so it accurately reflects the system state.
          _isForceDumpExecuting.value = false
        }
      }
    }
  }

  fun setIsRecording(isRecording: Boolean) {
    _isRecording.value = isRecording
  }

  fun setIsStopping(isStopping: Boolean) {
    _isStopping.value = isStopping
  }

  fun setObjectRetainedCount(objectRetainedCount: Int) {
    logger.info("Setting retained object count: $objectRetainedCount")
    _objectRetainedCount.value = objectRetainedCount
  }

  fun setAnalysisProgress(analysisProgress: Int) {
    _analysisProgress.value = analysisProgress
  }

  @VisibleForTesting
  fun clearLeaks() {
    logger.info("Cleared LeakCanary leaks.")
    _leaks.value = listOf()
    insightModel.clearInsights()
    onLeakSelection(null)
  }

  /** Sets whether to show all leaks, only app leaks, or only library leaks. */
  fun setFilterScope(scope: LeakFilterScope) {
    logger.info("Setting LeakCanary filter scope: $scope")
    _filterScope.value = scope
  }

  /** Sets the search query text used to filter leaks by class or variable name. */
  fun setSearchQuery(query: String) {
    logger.info("Setting LeakCanary search query: '$query'")
    _searchQuery.value = query
  }

  /** Toggles whether the search query matching should be case-sensitive. */
  fun setMatchCase(matchCase: Boolean) {
    logger.info("Setting LeakCanary filter matchCase: $matchCase")
    _matchCase.value = matchCase
  }

  /** Toggles whether the search query should be evaluated as a regular expression. */
  fun setUseRegex(useRegex: Boolean) {
    logger.info("Setting LeakCanary filter useRegex: $useRegex")
    _useRegex.value = useRegex
  }

  /** Resets all LeakCanary filter settings to their defaults ([LeakFilterScope.ALL], empty query, Match Case disabled, Regex disabled). */
  fun resetFilters() {
    logger.info("Resetting LeakCanary filter settings.")
    _filterScope.value = LeakFilterScope.ALL
    _searchQuery.value = ""
    _matchCase.value = false
    _useRegex.value = false
  }

  /**
   * Filters the detected [leaks] based on [scope], [query], [matchCase], and [isRegex]. Uses [Filter] to evaluate text or regular
   * expression matching against the leak class name.
   */
  private fun filterLeaks(leaks: List<Leak>, scope: LeakFilterScope, query: String, matchCase: Boolean, isRegex: Boolean): List<Leak> {
    val scopeFiltered =
      when (scope) {
        LeakFilterScope.ALL -> leaks
        LeakFilterScope.APP -> leaks.filter { it.type == LeakType.APPLICATION_LEAKS }
        LeakFilterScope.LIBRARY -> leaks.filter { it.type == LeakType.LIBRARY_LEAKS }
      }

    if (query.isEmpty()) {
      return scopeFiltered
    }

    val filter =
      try {
        Filter(query, matchCase, isRegex)
      } catch (e: PatternSyntaxException) {
        return emptyList()
      }
    return scopeFiltered.filter { leak ->
      val className = getLeakClassName(leak)
      filter.matches(className)
    }
  }

  fun onLeakSelection(newLeak: Leak?) {
    val className = newLeak?.displayedLeakTrace?.firstOrNull()?.nodes?.lastOrNull()?.className ?: "Unknown"
    logger.info("Leak selected in UI: $className")
    if (newLeak != null && _selectedLeak.value != newLeak) {
      myTaskTracker.trackLeakCanaryUiAction(LeakCanaryUiAction.NEW_LEAK_SELECTED)
    }
    _selectedLeakOccurrenceIndex.value = 0 // Reset occurrence index on new leak selection
    _selectedLeak.value = newLeak
    insightModel.onLeakSelection(newLeak)
  }

  /** Navigates to the next occurrence of the currently selected leak, if available. */
  fun selectNextOccurrence() {
    val leak = _selectedLeak.value ?: return
    if (_selectedLeakOccurrenceIndex.value < leak.displayedLeakTrace.size - 1) {
      _selectedLeakOccurrenceIndex.value += 1
      logger.info("Navigated to next leak occurrence: index " + _selectedLeakOccurrenceIndex.value)
    } else {
      logger.debug("Cannot navigate to next leak occurrence; already at the last index.")
    }
  }

  /** Navigates to the previous occurrence of the currently selected leak, if available. */
  fun selectPreviousOccurrence() {
    val leak = _selectedLeak.value ?: return
    if (_selectedLeakOccurrenceIndex.value > 0) {
      _selectedLeakOccurrenceIndex.value -= 1
      logger.info("Navigated to previous leak occurrence: index " + _selectedLeakOccurrenceIndex.value)
    } else {
      logger.debug("Cannot navigate to previous leak occurrence; already at the first index.")
    }
  }

  private fun checkPresenceAndFetchThreshold() {
    if (leakcanaryMode == StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE) {

      val thresholdValue = profilers.ideServices.temporaryProfilerPreferences.getInt("LEAKCANARY_THRESHOLD", -1)
      if (thresholdValue != -1) {
        _retainedObjectThreshold.value = thresholdValue
        logger.info("Setting retained object threshold to $thresholdValue")
      }

      if (thresholdValue == -1) {
        fetchRetainedVisibleThreshold()
      }
      // Reset the state to -1
      profilers.ideServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", -1)
    }
  }

  /** Creates and registers transport event listeners that run from the start of the session until the end. */
  private fun registerLeakCanaryListeners() {
    val startTime = profilers.session.startTimestamp
    lastAnalysisTimestampMs = TimeUnit.NANOSECONDS.toMillis(startTime)

    if (leakcanaryMode == StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE || textResultsAreManualTraces) {
      // Analysis results. In ON_DEVICE, they come from Logcat and, when usesOnDeviceEvents is true, from perfa. With the occurrences flag
      // on, Logcat only sends LeakCanary:Manual traces, in both modes.
      statusListener =
        TransportEventListener(
          eventKind = Common.Event.Kind.LEAKCANARY_ANALYSIS,
          executor = profilers.ideServices.mainExecutor,
          streamId = { profilers.session.streamId },
          processId = { profilers.session.pid },
          startTime = { startTime },
          callback = { event -> false.also { onAnalysisResult(event) } },
        )
      profilers.transportPoller.registerListener(statusListener)

      if (usesOnDeviceEvents) {
        // Progress of the analysis that LeakCanary runs on the device.
        analysisProgressListener =
          TransportEventListener(
            eventKind = Common.Event.Kind.LEAKCANARY_ANALYSIS_PROGRESS,
            executor = profilers.ideServices.mainExecutor,
            streamId = { profilers.session.streamId },
            processId = { profilers.session.pid },
            startTime = { startTime },
            callback = { event -> false.also { onDeviceAnalysisProgress(event) } },
          )
        profilers.transportPoller.registerListener(analysisProgressListener)
      }
    }

    if (leakcanaryMode == ON_HOST || usesOnDeviceEvents) {
      // The retained object count from the studio leakcanary integration library. In ON_HOST mode, Studio dumps the heap when the count
      // reaches the threshold. In ON_DEVICE mode, the count is only shown, because LeakCanary dumps the heap by itself.
      objectCountListener =
        TransportEventListener(
          eventKind = Common.Event.Kind.LEAKCANARY_OBJECT_COUNT,
          executor = profilers.ideServices.mainExecutor,
          streamId = { profilers.session.streamId },
          processId = { profilers.session.pid },
          startTime = { startTime },
          callback = { event ->
            val count = event.leakcanaryObjectCount.count
            setObjectRetainedCount(count)
            if (leakcanaryMode == ON_HOST && count >= _retainedObjectThreshold.value) {
              forceHeapDump()
            }
            false
          },
        )
      profilers.transportPoller.registerListener(objectCountListener)
    }

    deviceErrorListener =
      TransportEventListener(
        eventKind = Common.Event.Kind.LEAKCANARY_DEVICE_ERROR,
        executor = profilers.ideServices.mainExecutor,
        streamId = { profilers.session.streamId },
        processId = { profilers.session.pid },
        startTime = { startTime },
        callback = { event ->
          val errorType = event.leakcanaryDeviceError.errorType
          logger.warn("Received LEAKCANARY_DEVICE_ERROR event. Error: $errorType")
          when (errorType) {
            LEAKCANARY_ERROR_APP_CONTEXT_NULL -> {
              myTaskTracker.trackStartTaskFailed(TaskStartFailedMetadata(leakCanaryStartStatus = LeakCanaryStartErrorCode.APP_CONTEXT_NULL))
              handleLeakCanaryFatalError(
                LeakCanaryProcessingErrorCode.UNKNOWN_ERROR,
                "Failed to get Application Context on device.",
                isProcessingError = false,
              )
            }
            LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED -> {
              handleLeakCanaryFatalError(LeakCanaryProcessingErrorCode.BROADCAST_DELIVERY_FAILED, "Failed to deliver broadcast to the app.")
            }
            LEAKCANARY_ERROR_LOGCAT_PARSING_FAILURE -> {
              handleLeakCanaryFatalError(LeakCanaryProcessingErrorCode.PARSING_FAILURE, "Failed to parse LeakCanary logcat trace.")
            }
            LEAKCANARY_ERROR_UNSUPPORTED_VERSION -> {
              if (usesOnDeviceEvents) {
                // TODO(b/585456083): Use a dedicated error code once studio_stats.proto has one.
                handleLeakCanaryFatalError(LeakCanaryProcessingErrorCode.UNKNOWN_ERROR, UNSUPPORTED_VERSION_MESSAGE)
              }
            }
            LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED -> {
              if (usesOnDeviceEvents) {
                handleOnDeviceWarning(
                  LeakCanaryProcessingErrorCode.HEAP_DUMP_GENERATION_FAILED,
                  ON_DEVICE_HEAP_DUMP_FAILED_MESSAGE,
                  event.timestamp,
                )
              }
            }
            LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED -> {
              if (usesOnDeviceEvents) {
                // TODO(b/585456083): Use a dedicated error code once studio_stats.proto has one.
                handleOnDeviceWarning(LeakCanaryProcessingErrorCode.UNKNOWN_ERROR, ON_DEVICE_SAVE_FAILED_MESSAGE, event.timestamp)
              }
            }
            LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED -> {
              if (usesOnDeviceEvents) {
                // TODO(b/585456083): Use a dedicated error code once studio_stats.proto has one.
                handleOnDeviceWarning(LeakCanaryProcessingErrorCode.UNKNOWN_ERROR, ON_DEVICE_RESULT_READ_FAILED_MESSAGE, event.timestamp)
              }
            }
            else -> {}
          }
          false
        },
      )
    profilers.transportPoller.registerListener(deviceErrorListener)
  }

  private fun fetchRetainedVisibleThreshold() {
    val fetchThresholdCommand =
      Commands.Command.newBuilder()
        .setStreamId(profilers.session.streamId)
        .setPid(profilers.session.pid)
        .setSessionId(profilers.session.sessionId)
        .setType(Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD)
        .build()

    profilers.ideServices.poolExecutor.execute {
      val commandIdFuture = CompletableFuture<Int>()
      val listener =
        TransportEventListener(
          eventKind = Common.Event.Kind.LEAKCANARY_THRESHOLD,
          executor = profilers.ideServices.poolExecutor,
          streamId = { profilers.session.streamId },
          filter = { event ->
            val targetCommandId = commandIdFuture.getNow(-1)
            targetCommandId != -1 && event.commandId == targetCommandId
          },
          processId = { profilers.session.pid },
          callback = { event ->
            val threshold = event.leakcanaryThreshold.threshold
            profilers.ideServices.mainExecutor.execute {
              _retainedObjectThreshold.value = threshold
              if (threshold > 0 && isOccurrencesEnabled) {
                val version = event.leakcanaryThreshold.studioLeakcanaryVersion
                saveStudioLeakCanaryVersion(
                  profilers.ideServices.temporaryProfilerPreferences,
                  profilers.session.streamId,
                  profilers.session.pid,
                  version,
                )
                showOutdatedLibraryBannerIfNeeded(version)
              }
            }
            true // Unregister listener
          },
        )
      profilers.transportPoller.registerListener(listener)

      try {
        val response =
          profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(fetchThresholdCommand).build())
        logger.info(
          "Sent GET_LEAKCANARY_THRESHOLD command to transport. streamId: ${fetchThresholdCommand.streamId}, pid: ${fetchThresholdCommand.pid}, sessionId: ${fetchThresholdCommand.sessionId}"
        )
        commandIdFuture.complete(response.commandId)
      } catch (e: Exception) {
        logger.warn(
          "Failed to fetch retained visible threshold. streamId: ${fetchThresholdCommand.streamId}, pid: ${fetchThresholdCommand.pid}, sessionId: ${fetchThresholdCommand.sessionId}",
          e,
        )
        profilers.transportPoller.unregisterListener(listener)
      }
    }
  }

  private fun deregisterLeakCanaryListeners() {
    if (::statusListener.isInitialized) {
      profilers.transportPoller.unregisterListener(statusListener)
    }
    if (::objectCountListener.isInitialized) {
      profilers.transportPoller.unregisterListener(objectCountListener)
    }
    if (::analysisProgressListener.isInitialized) {
      profilers.transportPoller.unregisterListener(analysisProgressListener)
    }
    if (::deviceErrorListener.isInitialized) {
      profilers.transportPoller.unregisterListener(deviceErrorListener)
    }
  }

  fun addLeaks(newLeaks: List<Leak>) {
    val uniqueNewLeaks = newLeaks.filter { it !in _leaks.value }
    if (uniqueNewLeaks.isNotEmpty()) {
      _leaks.value = _leaks.value + uniqueNewLeaks
    }
  }

  /** Handles an analysis result event. */
  private fun onAnalysisResult(event: Common.Event) {
    val proto = event.leakcanaryAnalysis
    val isStructured = proto.hasSuccess() || proto.hasFailure()
    when {
      !textResultsAreManualTraces -> leakDetected(event)
      !isStructured -> addManualTraceLeaks(event)
      usesOnDeviceEvents -> leakDetected(event)
      // In ON_HOST, Studio shows its own results directly. Their saved copies come here too, so they are skipped.
      else -> Unit
    }
  }

  /**
   * Shows the leaks from a LeakCanary:Manual trace. It doesn't change Force dump, progress, the wait or Stop, so a heap dump or analysis
   * that is running isn't affected.
   */
  private fun addManualTraceLeaks(event: Common.Event) {
    val analysis = deserializeAnalysisEvent(event)
    if (analysis !is AnalysisSuccess) {
      logger.warn("Ignoring a LeakCanary:Manual trace that is not a successful analysis.")
      return
    }
    logger.info("Adding ${analysis.leaks.size} leaks from a LeakCanary:Manual trace.")
    addLeaks(analysis.leaks)
    selectFirstLeakIfNoneSelected()
    trackLeakAnalysisTelemetry(analysis, null, null, null, System.currentTimeMillis() - lastAnalysisTimestampMs)
  }

  /**
   * Gets the parsed logcat message and adds to list of leaks.
   *
   * @param event: The LeakCanary logcat event.
   */
  private fun leakDetected(event: Common.Event) {
    val proto = event.leakcanaryAnalysis
    if (usesOnDeviceEvents && (proto.hasSuccess() || proto.hasFailure())) {
      lastOnDeviceResultTimestampNs = maxOf(lastOnDeviceResultTimestampNs, event.timestamp)
    }
    val analysis = deserializeAnalysisEvent(event) ?: return
    saveDeviceSharkVersion(analysis)
    if (handleRetainedObject(analysis)) return
    if (handleAnalysisProgress(analysis)) return
    if (!usesOnDeviceEvents) {
      // With perfa, the library sends the real count right after the result, so it is kept.
      setObjectRetainedCount(0)
    }
    handleLeakAnalysis(analysis)
  }

  /** Shows the progress of the analysis on the device. */
  private fun onDeviceAnalysisProgress(event: Common.Event) {
    // A result (or a failed heap dump or save) can be handled before the last progress that was sent before it. That progress is old, so
    // it must not show an analysis as running again.
    if (event.timestamp <= lastOnDeviceResultTimestampNs) {
      logger.info("Ignoring LeakCanary analysis progress sent before the last result or failure.")
      return
    }
    setAnalysisProgress(event.leakcanaryAnalysisProgress.percent)
    if (onDeviceAnswerWaitNs != null) {
      startWaitingForOnDeviceAnswer()
    }
  }

  /** Starts, or starts again, the wait for an answer from the device. */
  private fun startWaitingForOnDeviceAnswer() {
    onDeviceAnswerWaitNs = 0
  }

  /** Ends the wait when nothing came from the device for too long. */
  private fun checkOnDeviceAnswerTimeout(elapsedNs: Long) {
    val waitNs = (onDeviceAnswerWaitNs ?: return) + elapsedNs
    if (waitNs < ON_DEVICE_ANSWER_TIMEOUT_NS) {
      onDeviceAnswerWaitNs = waitNs
      return
    }
    onDeviceAnswerWaitNs = null
    if (_isStopping.value) {
      logger.warn("No LeakCanary result or progress for 60 seconds after Stop. Stopping the recording.")
      stopListening()
    } else {
      logger.warn("No LeakCanary result or progress for 60 seconds after the forced heap dump. Enabling Force dump again.")
      // Progress may have stopped partway, so clear it. Otherwise Force dump stays grey.
      setAnalysisProgress(0)
      _isForceDumpExecuting.value = false
    }
  }

  /** Saves the ON_DEVICE LeakCanary version extracted from the parsed logcat Analysis metadata to the .asdb database. */
  private fun saveDeviceSharkVersion(analysis: Analysis) {
    if (!isSharkVersionEmitted.get() && analysis is AnalysisSuccess) {
      analysis.metadata["LeakCanary version"]?.let { deviceVersion ->
        profilers.ideServices.poolExecutor.execute { sendSharkVersionMetadataUpdate(deviceVersion) }
      }
    }
  }

  private fun handleRetainedObject(analysis: Analysis): Boolean {
    if (analysis !is AnalysisUpdate) return false
    val retainedObjectsRegex = """Found (\d+) objects? retained""".toRegex()
    return retainedObjectsRegex.find(analysis.message)?.let { matchResult ->
      matchResult.groupValues.getOrNull(1)?.toIntOrNull()?.let { count ->
        logger.info("LeakCanary: $count objects retained.")
        setObjectRetainedCount(count)
      }
      true
    } ?: false
  }

  private fun handleAnalysisProgress(analysis: Analysis): Boolean {
    if (analysis !is AnalysisUpdate) return false
    val analysisProgressRegex = """Analysis in progress, (\d+)% done""".toRegex()
    return analysisProgressRegex.find(analysis.message)?.let { matchResult ->
      matchResult.groupValues.getOrNull(1)?.toIntOrNull()?.let { progress ->
        logger.info("LeakCanary: Analysis is $progress% done.")
        setAnalysisProgress(progress)
      }
      true
    } ?: false
  }

  private fun handleLeakAnalysis(
    analysis: Analysis?,
    hprofFileSizeBytes: Long? = null,
    downloadDurationMs: Long? = null,
    heapDumpAnalysisTimeMs: Long? = null,
  ) {
    _isForceDumpExecuting.value = false // Safely re-enable the Force Dump button
    onDeviceAnswerWaitNs = null // An answer came, so stop waiting for one.

    if (analysis == null) {
      myTaskTracker.trackProcessingTaskFailed(
        TaskProcessingFailedMetadata(leakCanaryProcessingStatus = LeakCanaryProcessingErrorCode.PARSING_FAILURE)
      )
      return
    }
    setAnalysisProgress(0)

    if (analysis is AnalysisSuccess) {
      addLeaks(analysis.leaks)

      val totalRecordingTimeMs = System.currentTimeMillis() - lastAnalysisTimestampMs
      trackLeakAnalysisTelemetry(analysis, hprofFileSizeBytes, downloadDurationMs, heapDumpAnalysisTimeMs, totalRecordingTimeMs)
      lastAnalysisTimestampMs = System.currentTimeMillis()
    } else if (analysis is AnalysisFailure) {
      // There is failure in leak analysis.
      logger.warn("Leak analysis failure", analysis.exception)
    }

    selectFirstLeakIfNoneSelected()

    if (_isStopping.value) {
      stopListening()
    }
  }

  private fun selectFirstLeakIfNoneSelected() {
    // The first leak is selected, so its leakTrace is displayed by default in UI.
    if (_selectedLeak.value == null && _leaks.value.isNotEmpty()) {
      val validFirstLeak =
        filterLeaks(_leaks.value, _filterScope.value, _searchQuery.value, _matchCase.value, _useRegex.value).firstOrNull()
      onLeakSelection(validFirstLeak)
    }
  }

  /**
   * Starts or stops LeakCanary logcat tracking & retained object count tracking.
   *
   * @param session: The profiler session.
   * @param enable: true to start tracking, false to stop tracking.
   */
  private fun toggleLeakCanaryTracking(session: Common.Session, enable: Boolean) {
    logger.info("Sending ${if (enable) "START" else "STOP"} LeakCanary tracking command to device.")

    profilers.ideServices.poolExecutor.execute {
      if (enable) {
        // First, configure the LeakCanary mode on the device (ON_HOST vs ON_DEVICE) so the helper library
        // knows whether to run Shark locally or rely on Android Studio. Also send the occurrences flag, so
        // perfa knows which updates to forward.
        val setModeData =
          Commands.StudioLeakCanaryModeData.newBuilder()
            .setMode(leakcanaryMode)
            .setProfilerLeakcanaryOccurrences(isOccurrencesEnabled)
            .build()
        val setModeCommand =
          Commands.Command.newBuilder()
            .setStreamId(session.streamId)
            .setPid(session.pid)
            .setSessionId(session.sessionId)
            .setType(Commands.Command.CommandType.SET_STUDIO_LEAKCANARY_MODE)
            .setSetStudioLeakcanaryMode(setModeData)
            .build()

        try {
          profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(setModeCommand).build())
          logger.info(
            "Sent SET_STUDIO_LEAKCANARY_MODE command to transport. streamId: ${setModeCommand.streamId}, pid: ${setModeCommand.pid}, sessionId: ${setModeCommand.sessionId}"
          )
        } catch (e: Exception) {
          logger.warn(
            "Failed to execute SET_STUDIO_LEAKCANARY_MODE command. streamId: ${setModeCommand.streamId}, pid: ${setModeCommand.pid}, sessionId: ${setModeCommand.sessionId}",
            e,
          )
          logger.warn("PROFILER: Mode setup failed. Skipping START_LEAKCANARY_TASK command.")
          return@execute
        }

        // Then, initiate the LeakCanary task which triggers the logcat handler to start parsing
        // logs and sends the analysis status event to the transport layer for the past recordings UI.
        val startLeakCanaryTaskData = StartLeakCanaryTaskData.newBuilder().setMode(leakcanaryMode).build()
        val startTaskCommand =
          Commands.Command.newBuilder()
            .setStreamId(session.streamId)
            .setPid(session.pid)
            .setSessionId(session.sessionId)
            .setType(Commands.Command.CommandType.START_LEAKCANARY_TASK)
            .setStartLeakcanaryTask(startLeakCanaryTaskData)
            .build()

        try {
          profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(startTaskCommand).build())
          logger.info(
            "Sent START_LEAKCANARY_TASK command to transport. streamId: ${startTaskCommand.streamId}, pid: ${startTaskCommand.pid}, sessionId: ${startTaskCommand.sessionId}"
          )
        } catch (e: Exception) {
          logger.warn(
            "Failed to execute START_LEAKCANARY_TASK command. streamId: ${startTaskCommand.streamId}, pid: ${startTaskCommand.pid}, sessionId: ${startTaskCommand.sessionId}",
            e,
          )
        }
      } else {
        // Tell perfa to stop forwarding the retained object count and the analysis progress.
        val stopObjectCountCommand =
          Commands.Command.newBuilder()
            .setStreamId(session.streamId)
            .setPid(session.pid)
            .setSessionId(session.sessionId)
            .setType(STOP_LEAKCANARY_OBJECT_COUNT_TRACKING)
            .build()

        try {
          profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(stopObjectCountCommand).build())
          logger.info(
            "Sent STOP_LEAKCANARY_OBJECT_COUNT_TRACKING command to transport. streamId: ${stopObjectCountCommand.streamId}, pid: ${stopObjectCountCommand.pid}, sessionId: ${stopObjectCountCommand.sessionId}"
          )
        } catch (e: Exception) {
          logger.warn(
            "Failed to execute STOP_LEAKCANARY_OBJECT_COUNT_TRACKING command. streamId: ${stopObjectCountCommand.streamId}, pid: ${stopObjectCountCommand.pid}, sessionId: ${stopObjectCountCommand.sessionId}",
            e,
          )
        }

        // Finally, stop the specific LeakCanary task. This tells the logcat handler to stop parsing
        // logs and send the final analysis status event to the transport layer for the past recordings UI.
        // NOTE: This only stops LeakCanary-specific operations. The underlying recording session
        // is terminated later by a separate END_SESSION command (managed by perfd).
        val stopTaskCommand =
          Commands.Command.newBuilder()
            .setStreamId(session.streamId)
            .setPid(session.pid)
            .setSessionId(session.sessionId)
            .setType(Commands.Command.CommandType.STOP_LEAKCANARY_TASK)
            .build()

        try {
          profilers.client.transportClient.execute(Transport.ExecuteRequest.newBuilder().setCommand(stopTaskCommand).build())
          logger.info(
            "Sent STOP_LEAKCANARY_TASK command to transport. streamId: ${stopTaskCommand.streamId}, pid: ${stopTaskCommand.pid}, sessionId: ${stopTaskCommand.sessionId}"
          )
        } catch (e: Exception) {
          logger.warn(
            "Failed to execute STOP_LEAKCANARY_TASK command. streamId: ${stopTaskCommand.streamId}, pid: ${stopTaskCommand.pid}, sessionId: ${stopTaskCommand.sessionId}",
            e,
          )
        }
      }
    }
  }

  // Setting it to UNKNOWN_STAGE since stage usage is avoided in task-based ux.
  override fun getStageType(): AndroidProfilerEvent.Stage = AndroidProfilerEvent.Stage.UNKNOWN_STAGE

  fun loadFromPastSession(startTimestamp: Long, endTimeStamp: Long, session: Common.Session) {
    resetFilters()
    // Get all LeakCanary events from start time to end time.
    val analysisEvents = getAllLeakCanaryEvents(session, startTimestamp, endTimeStamp)
    logger.info("Loaded past LeakCanary session with ${analysisEvents.size} analysis events.")
    analysisEvents.forEach { analysis ->
      if (analysis is AnalysisSuccess) {
        addLeaks(analysis.leaks)
      }
    }

    // The first leak is selected, so its leakTrace is displayed by default in UI.
    if (_leaks.value.isNotEmpty()) {
      val validFirstLeak =
        filterLeaks(_leaks.value, _filterScope.value, _searchQuery.value, _matchCase.value, _useRegex.value).firstOrNull()
      onLeakSelection(validFirstLeak)
    }

    // Track the successful completion of loading a past Leak Canary session.
    myTaskTracker.trackTaskFinished(TaskFinishedState.COMPLETED)
  }

  private fun getAllLeakCanaryEvents(session: Common.Session, startTimestamp: Long, endTimeStamp: Long): List<Analysis> {
    val eventList = getLeaksFromRange(profilers.client, session, Range(startTimestamp.toDouble(), endTimeStamp.toDouble()))
    return eventList.mapNotNull { event -> deserializeAnalysisEvent(event) }
  }

  /**
   * Deserializes a [Common.Event] containing a LeakCanaryAnalysisData payload.
   *
   * If the event contains a structured Protobuf payload (`hasSuccess()` or `hasFailure()`), it uses [LeakCanaryProtoAdapter.fromProto] to
   * reconstruct the [Analysis] directly without string parsing, and clamps `displayedLeakTrace` to a single occurrence if
   * `isOccurrencesEnabled` is currently false. Otherwise (for sessions recorded when the feature flag was off, imported legacy `.asdb`
   * files, or legacy Logcat-based `ON_DEVICE` events), it falls back to [Analysis.fromString].
   */
  private fun deserializeAnalysisEvent(event: Common.Event): Analysis? {
    val proto = event.leakcanaryAnalysis
    return if (proto.hasSuccess() || proto.hasFailure()) {
      logger.debug("Deserializing LeakCanary event using structured Protobuf payload.")
      val analysis = LeakCanaryProtoAdapter.fromProto(proto)
      if (!isOccurrencesEnabled && analysis is AnalysisSuccess) {
        analysis.copy(leaks = analysis.leaks.map { it.copy(displayedLeakTrace = it.displayedLeakTrace.take(1)) })
      } else {
        analysis
      }
    } else {
      logger.debug("Deserializing LeakCanary event using legacy string parser.")
      Analysis.fromString(proto.data)
    }
  }

  private fun trackLeakAnalysisTelemetry(
    analysis: AnalysisSuccess,
    hprofFileSizeBytes: Long?,
    downloadDurationMs: Long?,
    heapDumpAnalysisTimeMs: Long?,
    totalRecordingTimeMs: Long,
  ) {
    var totalRetainedBytes = 0L
    var noCount = 0
    var maybeCount = 0
    var yesCount = 0
    var occurrences = 0
    var libraryLeak = false

    for (leak in analysis.leaks) {
      occurrences += leak.leakTraceCount
      totalRetainedBytes += leak.retainedByteSize.toLong().coerceAtLeast(0L)
      if (leak.type == LeakType.LIBRARY_LEAKS) {
        libraryLeak = true
      }

      leak.displayedLeakTrace.firstOrNull()?.nodes?.forEach { node ->
        when (node.leakingStatus) {
          LeakingStatus.NO -> noCount++
          LeakingStatus.UNKNOWN -> maybeCount++
          LeakingStatus.YES -> yesCount++
          else -> {}
        }
      }
    }

    val leakAnalysisPayload =
      LeakCanaryLeakAnalysis(
        retainedObjectsCount = analysis.leaks.size,
        occurrencesCount = occurrences,
        estimatedMemoryLeakedBytes = totalRetainedBytes,
        leakingNoRows = noCount,
        leakingMaybeRows = maybeCount,
        leakingYesRows = yesCount,
        heapDumpAnalysisTimeMs = heapDumpAnalysisTimeMs,
        hprofFileSizeBytes = hprofFileSizeBytes,
        hprofDownloadDurationMs = downloadDurationMs,
        totalRecordingTimeMs = totalRecordingTimeMs,
        isLibraryLeak = libraryLeak,
      )
    myTaskTracker.trackLeakCanaryAnalysis(leakAnalysisPayload)
  }

  fun goToDeclaration(node: Node) {
    logger.info("User clicked go to declaration for: ${node.className}")
    myTaskTracker.trackLeakCanaryUiAction(LeakCanaryUiAction.GO_TO_DECLARATION_CLICKED)
    val codeLocation = CodeLocation.Builder(node.className.removeSuffix("[]")).build()
    this.studioProfilers.ideServices.codeNavigator.navigate(codeLocation)
  }

  fun isDeclarationAvailableAsync(node: Node): CompletableFuture<Boolean> {
    val codeLocationSupplier: CodeLocation = CodeLocation.Builder(node.className.removeSuffix("[]")).build()
    val navigator = this.studioProfilers.ideServices.codeNavigator
    return navigator.isNavigatableAsync(codeLocationSupplier)
  }

  /**
   * Shows the error and stops the recording. Sends "processing failed" telemetry only when [isProcessingError] is true. For other kinds of
   * errors, like a start failure, set it to false. The caller then sends the matching telemetry itself.
   */
  fun handleLeakCanaryFatalError(error: LeakCanaryProcessingErrorCode, message: String, isProcessingError: Boolean = true) {
    logger.error("LeakCanary Fatal Error ($error): $message")
    _isForceDumpExecuting.value = false // Re-enable the Force Dump button on fatal error
    if (isProcessingError) {
      myTaskTracker.trackProcessingTaskFailed(TaskProcessingFailedMetadata(leakCanaryProcessingStatus = error))
    }

    // Show IDE balloon notification
    profilers.ideServices.showNotification(
      Notification(Notification.Severity.ERROR, "LeakCanary Task Failed", escapeXmlEntities(message), null)
    )

    // Safely tear down the task
    stopListening(isUserInitiated = false)
  }

  /**
   * Shows a warning and sends telemetry for a failed heap dump or result on the device. The recording keeps running. Like a result, it ends
   * the wait after Force dump or Stop, enables Force dump again, finishes a pending Stop, and makes Studio ignore progress sent before it.
   */
  private fun handleOnDeviceWarning(error: LeakCanaryProcessingErrorCode, message: String, eventTimestampNs: Long) {
    logger.warn("LeakCanary warning ($error): $message")
    lastOnDeviceResultTimestampNs = maxOf(lastOnDeviceResultTimestampNs, eventTimestampNs)
    myTaskTracker.trackProcessingTaskFailed(TaskProcessingFailedMetadata(leakCanaryProcessingStatus = error))
    profilers.ideServices.showNotification(
      Notification(Notification.Severity.WARNING, LEAKCANARY_WARNING_TITLE, escapeXmlEntities(message), null)
    )
    _isForceDumpExecuting.value = false
    onDeviceAnswerWaitNs = null
    setAnalysisProgress(0)
    if (_isStopping.value) {
      stopListening()
    }
  }

  fun trackUiAction(action: LeakCanaryUiAction) {
    logger.info("Tracked LeakCanary UI action: $action")
    myTaskTracker.trackLeakCanaryUiAction(action)
  }

  /** Saves the LeakCanary mode and, if running ON_HOST, the hardcoded Shark version to the .asdb */
  private fun saveModeAndHostSharkVersion() {
    profilers.ideServices.poolExecutor.execute {
      try {
        val request =
          Transport.AddTaskDbMetadataRequest.newBuilder()
            .setSessionId(sessionData.sessionId)
            .putMetadata(METADATA_KEY_LEAKCANARY_MODE, leakcanaryMode.name)
            .build()
        profilers.client.transportClient.addTaskDbMetadata(request)
        logger.info("Saved LeakCanary mode to .asdb _metadata (Mode: ${leakcanaryMode.name})")
      } catch (e: Exception) {
        logger.warn("Failed to save LeakCanary mode to .asdb _metadata", e)
      }

      if (leakcanaryMode == ON_HOST) {
        sendSharkVersionMetadataUpdate(ON_HOST_SHARK_VERSION)
      }
    }
  }

  /** Synchronously sends the AddTaskDbMetadata RPC to save the given Shark version. */
  private fun sendSharkVersionMetadataUpdate(version: String) {
    if (isSharkVersionEmitted.getAndSet(true)) return

    try {
      val request =
        Transport.AddTaskDbMetadataRequest.newBuilder()
          .setSessionId(sessionData.sessionId)
          .putMetadata(METADATA_KEY_SHARK_VERSION, version)
          .build()
      profilers.client.transportClient.addTaskDbMetadata(request)
      logger.info("Saved Shark version to .asdb _metadata (Version: $version)")
    } catch (e: Exception) {
      logger.warn("Failed to save Shark version to .asdb _metadata", e)
    }
  }

  companion object {
    /** Aligned with ProfilerLayout.FILTER_TEXT_FIELD_TRIGGER_DELAY_MS */
    const val SEARCH_DEBOUNCE_DELAY_MS = 250L

    /**
     * Fetches all LeakCanary logcat dump events within a given session. It returns leaks that are within a given range, which is provided
     * by the logcat end status events fetched by `getLeakCanaryLogcatInfo`.
     */
    fun getLeaksFromRange(profilerClient: ProfilerClient, session: Common.Session, range: Range): List<Common.Event> {
      return profilerClient.transportClient
        .getEventGroups(
          Transport.GetEventGroupsRequest.newBuilder()
            .setStreamId(session.streamId)
            .setPid(session.pid)
            .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS)
            .setFromTimestamp(range.min.toLong())
            .setToTimestamp(range.max.toLong())
            .build()
        )
        .groupsList
        .flatMap { group -> group.eventsList.toList() }
    }

    /**
     * Fetches all LeakCanary logcat dump events within a given session. It returns leaks that are within a given range, which is the
     * session start and end time provided by `getSessionArtifacts`.
     */
    fun getLeakCanaryAnalysisInfo(profilerClient: ProfilerClient, session: Common.Session, range: Range): List<Common.Event> {
      return profilerClient.transportClient
        .getEventGroups(
          Transport.GetEventGroupsRequest.newBuilder()
            .setStreamId(session.streamId)
            .setPid(session.pid)
            .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS_STATUS)
            .setFromTimestamp(range.min.toLong())
            .setToTimestamp(range.max.toLong())
            .build()
        )
        .groupsList
        .flatMap { group -> group.eventsList.toList() }
        .filter { event -> event.isEnded }
    }

    /**
     * Extracts the class name from a Leak object, prioritizing the leaking class if available. If the full class path is long, it shortens
     * it to the last two segments.
     *
     * @param leak The Leak object to extract the class name from.
     * @return The extracted class name or an empty string if no leak or class name is found.
     */
    @JvmStatic
    @JvmOverloads
    fun getLeakClassName(leak: Leak?, occurrenceIndex: Int = 0): String {
      if (leak?.displayedLeakTrace == null || leak.displayedLeakTrace.isEmpty()) {
        return ""
      }
      val leakTrace = leak.displayedLeakTrace.getOrNull(occurrenceIndex) ?: leak.displayedLeakTrace.first()
      val suspectNodeList =
        leakTrace.nodes.filterIndexed { index, node ->
          when (node.leakingStatus) {
            LeakingStatus.UNKNOWN -> true
            LeakingStatus.NO -> index == leakTrace.nodes.lastIndex || leakTrace.nodes[index + 1].leakingStatus != LeakingStatus.NO
            else -> false
          }
        }
      return suspectNodeList.firstOrNull()?.let { node ->
        val referenceField = node.referencingField
        "${referenceField?.className ?: ""}.${referenceField?.referenceName ?: ""}"
      } ?: leakTrace.nodes.last().className
    }

    private const val KEY_LEAKCANARY_BANNER_DO_NOT_SHOW = "leakcanary.banner.donotshow"
  }

  override fun update(elapsedNs: Long) {
    _elapsedNs.value += elapsedNs
    checkOnDeviceAnswerTimeout(elapsedNs)
  }

  /** Tracks a user interaction within an active LeakCanary task (e.g. clicking "Force Dump"). */
  private fun TaskTracker.trackLeakCanaryUiAction(uiAction: LeakCanaryUiAction) {
    profilers.ideServices.featureTracker.trackLeakCanaryEvent(this.taskMetadata, uiAction)
  }

  /** Tracks the completion of a LeakCanary memory analysis, logging the specific leak metrics. */
  private fun TaskTracker.trackLeakCanaryAnalysis(leakAnalysis: LeakCanaryLeakAnalysis) {
    profilers.ideServices.featureTracker.trackLeakCanaryEvent(this.taskMetadata, leakAnalysis)
  }
}
