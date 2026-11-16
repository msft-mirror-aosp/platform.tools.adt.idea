/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.visual.visuallint

import com.android.annotations.concurrency.GuardedBy
import com.android.sdklib.devices.Device
import com.android.tools.idea.common.error.IssueModel
import com.android.tools.idea.common.error.IssueProviderListener
import com.android.tools.idea.common.model.ModelListener
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.idea.rendering.createHtmlLogger
import com.android.tools.idea.rendering.errors.ui.RenderErrorModel
import com.android.tools.idea.rendering.parsers.PsiXmlFile
import com.android.tools.idea.rendering.taskBuilder
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.idea.uibuilder.scene.NlModelHierarchyUpdater.updateHierarchy
import com.android.tools.idea.uibuilder.visual.WearDeviceModelsProvider
import com.android.tools.idea.uibuilder.visual.WindowSizeModelsProvider
import com.android.tools.idea.uibuilder.visual.analytics.VisualLintUsageTracker
import com.android.tools.idea.uibuilder.visual.visuallint.VisualLintRenderIssue.Companion.createVisualLintRenderIssue
import com.android.tools.rendering.RenderAsyncActionExecutor.RenderingTopic
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderTask
import com.android.tools.res.LocalResourceRepository
import com.android.tools.visuallint.ViewInfoProvider
import com.android.tools.visuallint.VisualLintAnalyzer
import com.android.tools.visuallint.VisualLintBaseConfigIssues
import com.android.tools.visuallint.analyzers.AtfAnalyzer
import com.android.tools.visuallint.analyzers.BottomAppBarAnalyzer
import com.android.tools.visuallint.analyzers.BottomNavAnalyzer
import com.android.tools.visuallint.analyzers.BoundsAnalyzer
import com.android.tools.visuallint.analyzers.ButtonSizeAnalyzer
import com.android.tools.visuallint.analyzers.LocaleAnalyzer
import com.android.tools.visuallint.analyzers.LongTextAnalyzer
import com.android.tools.visuallint.analyzers.OverlapAnalyzer
import com.android.tools.visuallint.analyzers.SystemUiAnalyzer
import com.android.tools.visuallint.analyzers.TextFieldSizeAnalyzer
import com.android.tools.visuallint.analyzers.WearMarginAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.waitForSmartMode
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.serviceContainer.AlreadyDisposedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.TestOnly

/** Dispatcher to trigger background visual linting analysis using only 1 thread */
@OptIn(ExperimentalCoroutinesApi::class) private val visualLintSingleThreadedDispatcher = Dispatchers.Default.limitedParallelism(1)
/** Dispatcher to run all the visual linting analyzers triggered from one analysis using only 1 thread */
@OptIn(ExperimentalCoroutinesApi::class) private val visualLintAnalyzerSingleThreadedDispatcher = Dispatchers.Default.limitedParallelism(1)
/** Time out for visual lint analysis. Use a longer one for testing to ensure it always completes then. */
private val visualLintTimeout: Long = if (ApplicationManager.getApplication().isUnitTestMode) 30 else 5
private const val MAX_RETRIES = 3

private val LOG = Logger.getInstance(VisualLintService::class.java)

/** Service that runs visual lints. */
@Service(Service.Level.PROJECT)
class VisualLintService
private constructor(
  val project: Project,
  val coroutineScope: CoroutineScope,
  val visualLintDispatcher: CoroutineDispatcher,
  val visualLintAnalyzerDispatcher: CoroutineDispatcher,
) : Disposable {

  constructor(
    project: Project,
    coroutineScope: CoroutineScope,
  ) : this(
    project = project,
    coroutineScope = coroutineScope,
    visualLintDispatcher = visualLintSingleThreadedDispatcher,
    visualLintAnalyzerDispatcher = visualLintAnalyzerSingleThreadedDispatcher,
  )

  companion object {
    @JvmStatic
    fun getInstance(project: Project): VisualLintService {
      return project.getService(VisualLintService::class.java)
    }

    @TestOnly
    fun getInstanceForTest(
      project: Project,
      parentDisposable: Disposable,
      coroutineScope: CoroutineScope,
      visualLintDispatcher: CoroutineDispatcher,
      visualLintAnalyzerDispatcher: CoroutineDispatcher,
    ): VisualLintService {
      return VisualLintService(
          project = project,
          coroutineScope = coroutineScope,
          visualLintDispatcher = visualLintDispatcher,
          visualLintAnalyzerDispatcher = visualLintAnalyzerDispatcher,
        )
        .apply { Disposer.register(parentDisposable, this) }
    }
  }

  val issueModel: VisualLintIssueModel

  private val basicAnalyzers = listOf(BoundsAnalyzer, OverlapAnalyzer, AtfAnalyzer)
  private val adaptiveAnalyzers =
    listOf(BottomNavAnalyzer, BottomAppBarAnalyzer, TextFieldSizeAnalyzer, LongTextAnalyzer, ButtonSizeAnalyzer, SystemUiAnalyzer)
  private val wearAnalyzers = listOf(WearMarginAnalyzer)

  private val providerLock = Any()
  @GuardedBy("providerLock") private var listenerRemovalDisposable: IssueProviderRegistration? = null
  private val currentAnalysisJob = AtomicReference<Job?>(null)
  private val analysisMutex = Mutex()

  /**
   * Removes [provider] from [issueModel] when its parent is disposed, unless a newer run or [removeAllIssueProviders] already replaced it.
   * Idempotent, because Disposer can call dispose() twice outside debug mode.
   */
  private inner class IssueProviderRegistration(private val provider: VisualLintIssueProvider) : Disposable {
    @GuardedBy("providerLock")
    var isDisposed = false
      private set

    override fun dispose() {
      val wasCurrent =
        synchronized(providerLock) {
          isDisposed = true
          (listenerRemovalDisposable === this).also { if (it) listenerRemovalDisposable = null }
        }
      if (wasCurrent) {
        issueModel.removeIssueProvider(provider)
      }
    }
  }

  init {
    // We pass the VisualLintService as the IssueModel parent disposable. We need to initialize it
    // only in the end of this constructor to
    // prevent Project leaks if the constructor throws an exception sooner, because that could make
    // the IssueModel never to be disposed,
    // since it will be registered as the child of a broken VisualLintService object.
    issueModel = VisualLintIssueModel(this, project)
    ViewInfoProvider.setCustomProvider(CustomVisualLintViewInfoProvider)
  }

  /**
   * Runs visual lint analysis in a pooled thread and adds the issues found to the [IssueModel]. For models in [modelsForBackgroundRun], it
   * creates related configurations to analyze. For renders in [renderResultsForAnalysis], it simply analyzes the given results.
   */
  fun runVisualLintAnalysis(
    parentDisposable: Disposable,
    issueProvider: VisualLintIssueProvider,
    modelsForBackgroundRun: List<NlModel>,
    renderResultsForAnalysis: Map<RenderResult, NlModel>,
  ): Job {
    var previousJob: Job? = null
    val job =
      coroutineScope.launch(visualLintDispatcher, start = CoroutineStart.LAZY) {
        if (previousJob != null) {
          withContext(NonCancellable) { previousJob?.join() }
        }
        currentCoroutineContext().ensureActive()

        analysisMutex.withLock {
          currentCoroutineContext().ensureActive()

          if (isDisposedOrInvalid(modelsForBackgroundRun, renderResultsForAnalysis)) {
            return@withLock
          }

          if (isDumbProject()) {
            project.waitForSmartMode()
          }
          if (isDisposedOrInvalid(modelsForBackgroundRun, renderResultsForAnalysis)) {
            return@withLock
          }

          val runJob = currentCoroutineContext().job
          val registration = IssueProviderRegistration(issueProvider)
          if (!Disposer.tryRegister(parentDisposable, registration)) return@withLock
          var staleRegistration: IssueProviderRegistration? = null
          val registered =
            synchronized(providerLock) {
              if (!runJob.isActive || registration.isDisposed) {
                false
              } else {
                staleRegistration = listenerRemovalDisposable
                listenerRemovalDisposable = registration
                issueModel.removeAllIssueProviders()
                issueProvider.clear()
                issueModel.addIssueProvider(issueProvider, false)
                issueModel.uiCheckInstanceId = issueProvider.uiCheckInstanceId
                true
              }
            }
          staleRegistration?.let { Disposer.dispose(it) } // No longer current: only unhooks it from its parent.
          if (!registered) {
            Disposer.dispose(registration)
            return@withLock // Superseded, removeAllIssueProviders() or service dispose cancelled us, or the parent was disposed.
          }
          issueModel.updateErrorsList()

          val dumbService = DumbService.getInstance(project)
          val projectRootManager = ProjectRootManager.getInstance(project)
          for (attempt in 1..MAX_RETRIES) {
            if (isDisposedOrInvalid(modelsForBackgroundRun, renderResultsForAnalysis)) {
              return@withLock
            }

            val dumbModCountBefore = dumbService.modificationTracker.modificationCount
            val rootModCountBefore = projectRootManager.modificationCount
            val appResourceStatesBefore = captureAppResourceStates(modelsForBackgroundRun)
            val modelModCountsBefore = modelsForBackgroundRun.map { it.modificationCount }

            val visualLintBaseConfigIssues = VisualLintBaseConfigIssues()
            var complete = modelsForBackgroundRun.all { runBackgroundVisualLinting(it, issueProvider, visualLintBaseConfigIssues) }
            if (complete && renderResultsForAnalysis.isNotEmpty()) {
              complete = runOnPreviewVisualLinting(renderResultsForAnalysis, issueProvider, visualLintBaseConfigIssues)
            }

            val stateChanged =
              modelsForBackgroundRun.isNotEmpty() &&
                (dumbService.modificationTracker.modificationCount != dumbModCountBefore ||
                  projectRootManager.modificationCount != rootModCountBefore ||
                  isDumbProject() ||
                  captureAppResourceStates(modelsForBackgroundRun) != appResourceStatesBefore ||
                  modelsForBackgroundRun.map { it.modificationCount } != modelModCountsBefore)

            if ((!complete || (modelsForBackgroundRun.isNotEmpty() && stateChanged)) && attempt < MAX_RETRIES) {
              issueProvider.clear()
              if (isDumbProject()) {
                project.waitForSmartMode()
              }
              continue
            }
            if (!complete || (modelsForBackgroundRun.isNotEmpty() && stateChanged)) {
              // Cancelled/evicted renders, timeout, or unresolved state changes after retries: never publish stale/partial results.
              issueProvider.clear()
              LOG.warn("Visual Lint analysis incomplete or stale after $attempt attempt(s); not publishing partial results")
              return@withLock
            }

            if (modelsForBackgroundRun.isNotEmpty()) {
              issueModel.updateErrorsList(IssueProviderListener.TOPIC)
              LOG.debug(
                "Visual Lint analysis finished, ${issueModel.issueCount} ${if (issueModel.issueCount > 1) "errors" else "error"} found"
              )
            }
            if (renderResultsForAnalysis.isNotEmpty() || modelsForBackgroundRun.isEmpty()) {
              issueModel.updateErrorsList(IssueProviderListener.UI_CHECK)
              LOG.debug(
                "Visual Lint analysis finished, ${issueModel.issueCount} ${if (issueModel.issueCount > 1) "errors" else "error"} found"
              )
            }
            break
          }
        }
      }
    previousJob = currentAnalysisJob.getAndSet(job)
    if (previousJob != null) {
      previousJob.cancel()
      if (!previousJob.isCompleted) {
        RenderService.getRenderAsyncActionExecutor().cancelActionsByTopic(listOf(RenderingTopic.VISUAL_LINT), false)
      }
    }
    job.invokeOnCompletion { currentAnalysisJob.compareAndSet(job, null) }
    job.start()
    return job
  }

  private fun isDisposedOrInvalid(
    modelsForBackgroundRun: List<NlModel>,
    renderResultsForAnalysis: Map<RenderResult, NlModel>,
  ): Boolean = project.isDisposed || modelsForBackgroundRun.any { it.isDisposed } || renderResultsForAnalysis.values.any { it.isDisposed }

  private fun isDumbProject(): Boolean {
    return ApplicationManager.getApplication().runReadAction(Computable { DumbService.isDumb(project) })
  }

  private fun captureAppResourceStates(models: List<NlModel>): List<Pair<LocalResourceRepository<*>?, Long?>> {
    return models.map { model ->
      if (model.isDisposed || model.facet.isDisposed) {
        null to null
      } else {
        try {
          val repo = StudioResourceRepositoryManager.getInstance(model.facet).appResources
          repo to repo.modificationCount
        } catch (_: AlreadyDisposedException) {
          null to null
        }
      }
    }
  }

  /** Creates configurations based on the [baseModel] and run Visual Lint analysis on those. */
  private suspend fun runBackgroundVisualLinting(
    baseModel: NlModel,
    issueProvider: VisualLintIssueProvider,
    visualLintBaseConfigIssues: VisualLintBaseConfigIssues,
  ): Boolean {
    val modelModCountBefore = baseModel.modificationCount
    val listener =
      object : ModelListener {
        override fun modelChanged(model: NlModel) {
          val numberOfCancelledActions =
            RenderService.getRenderAsyncActionExecutor().cancelActionsByTopic(listOf(RenderingTopic.VISUAL_LINT), false)
          if (numberOfCancelledActions > 0) {
            VisualLintUsageTracker.getInstance().trackCancelledBackgroundAnalysis()
          }
        }
      }
    baseModel.addListener(listener)
    val modelsToAnalyze =
      try {
        if (Device.isWear(baseModel.configuration.device)) {
          WearDeviceModelsProvider.createNlModels(baseModel, baseModel.file, baseModel.buildTarget)
        } else {
          WindowSizeModelsProvider.createNlModels(baseModel, baseModel.file, baseModel.buildTarget)
        }
      } catch (t: Throwable) {
        baseModel.removeListener(listener)
        throw t
      }
    try {
      return try {
        withTimeout(visualLintTimeout.seconds) {
          for (model in modelsToAnalyze) {
            if (baseModel.modificationCount != modelModCountBefore) return@withTimeout false
            val runAtfChecks = AtfAnalyzer.shouldRun(project, true)
            val renderFuture = createRenderResult(model, runAtfChecks)
            val result =
              try {
                renderFuture.await()
              } catch (t: Throwable) {
                // await() unwraps CompletionException/ExecutionException, but stay defensive so a wrapped render cancellation never aborts
                // an active job via Logger.shouldRethrow.
                val cause = if (t is ExecutionException || t is CompletionException) t.cause ?: t else t
                if (cause is CancellationException) {
                  renderFuture.cancel(false)
                  if (!currentCoroutineContext().isActive) throw t // This job was cancelled or withTimeout fired.
                  return@withTimeout false // Only the render was cancelled: this attempt is incomplete.
                }
                if (Logger.shouldRethrow(cause)) throw cause
                if (cause !is AlreadyDisposedException) LOG.warn(cause)
                null
              }
            if (result == null) {
              Disposer.dispose(model)
              continue
            }
            if (result.renderResult.exception is CancellationException || baseModel.modificationCount != modelModCountBefore) {
              // F1: inflate() turns a cancelled/evicted render into an error RenderResult; analyzing it would publish a partial count.
              return@withTimeout false
            }
            withContext(visualLintAnalyzerDispatcher) {
              try {
                updateHierarchy(result, model)
                analyzeAfterModelUpdate(issueProvider, result, model, visualLintBaseConfigIssues, true)
              } finally {
                Disposer.dispose(model)
              }
            }
          }
          true
        }
      } catch (e: TimeoutCancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        LOG.warn("Visual lint analysis timed out", e)
        false
      }
    } finally {
      baseModel.removeListener(listener)
      modelsToAnalyze.forEach { model ->
        if (!model.isDisposed) {
          Disposer.dispose(model)
        }
      }
    }
  }

  /** Runs Visual Lint analysis on the given [RenderResult]s */
  private suspend fun runOnPreviewVisualLinting(
    renderResultsForAnalysis: Map<RenderResult, NlModel>,
    issueProvider: VisualLintIssueProvider,
    visualLintBaseConfigIssues: VisualLintBaseConfigIssues,
  ): Boolean {
    return try {
      withTimeout(visualLintTimeout.seconds) {
        renderResultsForAnalysis.forEach { (result, model) ->
          withContext(visualLintAnalyzerDispatcher) { analyzeAfterModelUpdate(issueProvider, result, model, visualLintBaseConfigIssues) }
        }
        true
      }
    } catch (e: TimeoutCancellationException) {
      if (!currentCoroutineContext().isActive) throw e
      LOG.warn("Visual lint analysis timed out", e)
      false
    }
  }

  /** Collects in [issueProvider] all the [RenderErrorModel.Issue] found when analyzing the given [RenderResult] after model is updated. */
  fun analyzeAfterModelUpdate(
    targetIssueProvider: VisualLintIssueProvider,
    result: RenderResult,
    model: NlModel,
    baseConfigIssues: VisualLintBaseConfigIssues,
    runningInBackground: Boolean = false,
  ) {
    runAnalyzers(targetIssueProvider, basicAnalyzers, result, model, runningInBackground)
    if (Device.isWear(model.configuration.device)) {
      runAnalyzers(targetIssueProvider, wearAnalyzers, result, model, runningInBackground)
    } else {
      runAnalyzers(targetIssueProvider, adaptiveAnalyzers, result, model, runningInBackground)
      runAnalyzers(targetIssueProvider, listOf(LocaleAnalyzer(baseConfigIssues)), result, model, runningInBackground)
    }
  }

  private fun runAnalyzers(
    targetIssueProvider: VisualLintIssueProvider,
    analyzers: List<VisualLintAnalyzer>,
    result: RenderResult,
    model: NlModel,
    runningInBackground: Boolean,
  ) {
    analyzers
      .filter { it.shouldRun(project, runningInBackground) }
      .forEach { analyzer ->
        val profile = InspectionProfileManager.getInstance(project).currentProfile
        val tools = profile.getToolsOrNull(analyzer.type.shortName, project) ?: return@forEach
        if (!tools.isEnabled) {
          return@forEach
        }
        val inspection = tools.getInspectionTool(null).tool as? VisualLintInspection ?: return@forEach
        if (runningInBackground && !inspection.runInBackground) {
          return@forEach
        }
        val issues = analyzer.analyze(result.toVisualLintRenderResult()).map { createVisualLintRenderIssue(it, model, analyzer.type) }
        targetIssueProvider.addAllIssues(issues)
      }
  }

  fun removeAllIssueProviders() {
    currentAnalysisJob.get()?.let { job ->
      job.cancel() // Keep it in currentAnalysisJob so the next run still joins it; invokeOnCompletion clears it.
      if (!job.isCompleted) {
        RenderService.getRenderAsyncActionExecutor().cancelActionsByTopic(listOf(RenderingTopic.VISUAL_LINT), false)
      }
    }
    clearIssueProviders()
    issueModel.updateErrorsList()
  }

  private fun clearIssueProviders() {
    val stale =
      synchronized(providerLock) {
        issueModel.removeAllIssueProviders()
        listenerRemovalDisposable.also { listenerRemovalDisposable = null }
      }
    stale?.let { Disposer.dispose(it) }
  }

  override fun dispose() {
    currentAnalysisJob.getAndSet(null)?.cancel()
    clearIssueProviders()
    ViewInfoProvider.setCustomProvider(null)
  }
}

/** Inflates a model, then returns the completable future with render result. */
fun createRenderResult(model: NlModel, runAtfChecks: Boolean): CompletableFuture<RenderResult> {
  val renderService = StudioRenderService.getInstance(model.project)
  val logger = renderService.createHtmlLogger(model.project)
  val buildDisposable = Disposer.newCheckedDisposable("VisualLintRenderTaskBuilder")
  val taskDisposed = AtomicBoolean(false)
  fun disposeOnce(task: RenderTask) {
    if (taskDisposed.compareAndSet(false, true)) {
      task.disposeAsync()
    }
  }

  val renderTaskFuture =
    renderService
      .taskBuilder(model.buildTarget, model.configuration, logger)
      .withPsiFile(PsiXmlFile(model.file))
      .withLayoutScanner(runAtfChecks)
      .withTopic(RenderingTopic.VISUAL_LINT)
      .withQuality(0.25f)
      .build(buildDisposable)

  val renderResultFuture: CompletableFuture<RenderResult> = renderTaskFuture.thenCompose { newTask ->
    if (newTask == null) {
      logger.error("INFLATE", "Error inflating view for visual lint on background. No RenderTask Created.", null, null, null)
      return@thenCompose CompletableFuture.failedFuture(IllegalArgumentException())
    }

    if (buildDisposable.isDisposed || model.isDisposed) {
      disposeOnce(newTask)
      return@thenCompose CompletableFuture.failedFuture(AlreadyDisposedException("NlModel was already disposed"))
    }

    // TODO: Potentially save this task for future?
    return@thenCompose newTask.inflate().whenComplete { result, inflateException ->
      val exception: Throwable? = inflateException ?: result?.renderResult?.exception
      disposeOnce(newTask)
      if (exception != null || result == null) {
        logger.error("INFLATE", "Error inflating views for visual lint on background", exception, null, null)
      }
    }
  }

  renderResultFuture.whenComplete { _, throwable ->
    Disposer.dispose(buildDisposable)
    if (throwable is CancellationException || renderResultFuture.isCancelled) {
      renderTaskFuture.thenAccept { task -> task?.let(::disposeOnce) }
    }
  }
  return renderResultFuture
}

private fun VisualLintAnalyzer.shouldRun(project: Project, runningInBackground: Boolean): Boolean {
  val profile = InspectionProfileManager.getInstance(project).currentProfile
  val tools = profile.getToolsOrNull(this.type.shortName, project) ?: return false
  if (!tools.isEnabled) {
    return false
  }
  if (!runningInBackground) {
    return true
  }
  val inspection = tools.getInspectionTool(null).tool as? VisualLintInspection ?: return false
  return inspection.runInBackground
}

class VisualLintIssueModel(parentDisposable: Disposable, project: Project) : IssueModel(parentDisposable, project) {

  /** If using in UI Check mode, represents the Compose Preview instance being checked. */
  var uiCheckInstanceId: String? = null
}

enum class VisualLintMode {
  DISABLED,
  RUN_ON_PREVIEW_ONLY,
  RUN_IN_BACKGROUND,
}
