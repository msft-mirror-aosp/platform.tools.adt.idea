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
package com.android.tools.idea.streaming.emulator.dialogs

import com.android.emulator.control.SensorSession.SensorRecord
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.CloseDialogAction
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.SLIDER_MAX
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.formatTimeProgress
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.positionToSliderValue
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.setToolTipText
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ItemEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.VisibleForTesting

/** Extended controls dialog for replaying recorded Automotive sensor, GPS, and Vehicle HAL (VHAL) sessions. */
internal class SensorReplayDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("sensor.replay.dialog.title", emulator.emulatorId.avdName)) {

  private val dialogJob = SupervisorJob(coroutineScope.coroutineContext[Job])
  private val dialogScope = CoroutineScope(coroutineScope.coroutineContext + dialogJob)

  private val savedSessions: MutableList<SensorSessionItem> = getOrCreateSavedSessions(emulator)

  @VisibleForTesting
  var sessionLoader: (Path, () -> Unit) -> SensorSessionItem = { path, checkCancelled ->
    loadSensorSession(path, checkCancelled = checkCancelled)
  }

  @VisibleForTesting var recordSenderOverride: (suspend (SensorRecord, SessionMetadata) -> Unit)? = null

  @VisibleForTesting
  var fileChooserCreator: (FileChooserDescriptor, Component?, Project?) -> FileChooserDialog = { descriptor, parentComponent, project ->
    if (parentComponent != null) FileChooserDialogImpl(descriptor, parentComponent, project) else FileChooserDialogImpl(descriptor, project)
  }

  private var dictionary: VhalPropertyDictionary = VhalPropertyDictionary.DEFAULT

  private val player =
    SensorSessionPlayer(
      scope = dialogScope,
      uiContext = uiContext,
      dispatchRecord = ::dispatchSensorRecord,
      isSliderAdjusting = { ::playbackSlider.isInitialized && playbackSlider.valueIsAdjusting },
      onProgressChanged = ::onPlaybackProgressChanged,
      onPlaybackStateChanged = ::updateControlsEnabledState,
      onStateModified = { stateModified = true },
      onClearError = ::clearError,
      onError = ::showError,
    )

  private val selectedSession: SensorSessionItem?
    get() = player.selectedSession

  private val isPlaying: Boolean
    get() = player.isPlaying

  private val savedSessionsTableModel = SavedSessionsTableModel()
  private val savedSessionsTable =
    ExtendedControlsTable(savedSessionsTableModel, "savedSessionsTable", message("sensor.replay.dialog.empty.files")).apply {
      accessibleContext.accessibleName = message("sensor.replay.dialog.label.saved.files")
    }

  private val timelineTableModel = TimelineTableModel()
  private val timelineTable =
    ExtendedControlsTable(timelineTableModel, "timelineTable", message("sensor.replay.dialog.empty.timeline")).apply {
      selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
      accessibleContext.accessibleName = message("sensor.replay.dialog.label.timeline").removeSuffix(":")
    }

  private val detailsCardLayout = CardLayout()
  private val detailsCardPanel = JBPanel<JBPanel<*>>(detailsCardLayout).apply { name = "detailsCardPanel" }

  private lateinit var fileNameValueLabel: JBLabel
  private lateinit var appVersionValueLabel: JBLabel
  private lateinit var totalEventsValueLabel: JBLabel
  private lateinit var sensorsPreviewLabel: JBLabel
  private lateinit var sensorsDetailLink: ActionLink
  private lateinit var playbackSlider: ProgressSlider
  private lateinit var playbackTimeLabel: JBLabel
  private lateinit var playPauseButton: JButton
  private lateinit var playbackSpeedComboBox: ComboBox<SensorReplaySpeed>

  init {
    onDispose {
      player.cancel()
      dialogJob.cancel()
    }

    savedSessionsTable.selectionModel.addListSelectionListener { event ->
      if (!event.valueIsAdjusting && !isUpdatingUi && !isPlaying) {
        onSelectedSessionChanged()
      }
    }

    dialogScope.launch {
      val loaded =
        withContext(Dispatchers.IO) {
          // TODO: Replace reading from system image by accessing all needed information through gRPC once available (b/579958152).
          // TODO: Remove EmulatorConfig.systemImageDir once no longer used.
          VhalPropertyDictionary.loadFromDirectories(listOf(emulator.emulatorConfig.systemImageDir, emulator.emulatorId.avdFolder))
        }
      withContext(uiContext) {
        dictionary = loaded
        selectedSession?.let(::updateSensorsPreview)
      }
    }
  }

  override fun createPanel(): DialogPanel {
    detailsCardPanel.removeAll()
    detailsCardPanel.add(createEmptyStatePanel(), EMPTY_CARD)
    detailsCardPanel.add(createSessionDetailsPanel(), DETAILS_CARD)

    val dialogPanel = panel {
      row {
        panel {
            row {
              label(message("sensor.replay.dialog.label.saved.files"))
            }

            row {
              cell(createSavedSessionsTablePanel()).align(Align.FILL)
            }
              .resizableRow()
          }
          .align(Align.FILL)
          .resizableColumn()

        cell(detailsCardPanel).align(Align.FILL).resizableColumn()
      }
        .resizableRow()
    }

    val persistedPaths = getPersistedSensorFiles()
    val persistedSet = persistedPaths.toSet()
    val staleItems = savedSessions.filter { it.path !in persistedSet }
    if (staleItems.isNotEmpty()) {
      savedSessions.removeAll(staleItems)
      for (item in staleItems) {
        item.close()
      }
    }
    if (savedSessions.size > 1) {
      val orderMap = persistedPaths.withIndex().associate { (index, path) -> path to index }
      val sorted = savedSessions.sortedBy { orderMap[it.path] ?: Int.MAX_VALUE }
      if (sorted != savedSessions) {
        savedSessions.clear()
        savedSessions.addAll(sorted)
      }
    }

    updateUi {
      savedSessionsTableModel.items = savedSessions.toList()
      val initialSession =
        if (savedSessions.isNotEmpty()) {
          savedSessionsTable.selectModelRow(0)
          savedSessions[0]
        } else {
          null
        }
      player.selectSession(initialSession)
      refreshDetailsPanel()
      updateControlsEnabledState()
    }

    val missingPaths = persistedPaths.filter { path -> savedSessions.none { it.path == path } }
    if (missingPaths.isNotEmpty()) {
      restorePersistedSessions(missingPaths)
    }

    return dialogPanel
  }

  private fun restorePersistedSessions(pathsToRestore: List<Path>) {
    dialogScope.launch {
      for (path in pathsToRestore) {
        val shouldLoad =
          withContext(uiContext) {
            path in getPersistedSensorFiles() && savedSessions.none { it.path == path }
          }
        if (!shouldLoad) continue

        val sessionItem =
          try {
            withContext(Dispatchers.IO) {
              if (!Files.isRegularFile(path)) {
                null
              } else {
                sessionLoader(path) { coroutineContext.ensureActive() }
              }
            }
          } catch (_: EmptySensorSessionException) {
            thisLogger().warn("Skipping empty persisted sensor file '$path'")
            null
          } catch (e: IOException) {
            thisLogger().warn("Unable to restore persisted sensor file '$path'", e)
            null
          }

        if (sessionItem == null) {
          withContext(uiContext) {
            removePersistedSensorFiles(setOf(path))
          }
          continue
        }

        try {
          withContext(uiContext) {
            val currentPersisted = getPersistedSensorFiles()
            if (path !in currentPersisted || savedSessions.any { it.path == path }) {
              sessionItem.close()
              return@withContext
            }

            val orderMap = currentPersisted.withIndex().associate { (index, p) -> p to index }
            val targetOrder = orderMap[path] ?: Int.MAX_VALUE
            val insertIndex =
              savedSessions
                .indexOfFirst { (orderMap[it.path] ?: Int.MAX_VALUE) > targetOrder }
                .let {
                  if (it >= 0) it else savedSessions.size
                }
            savedSessions.add(insertIndex, sessionItem)

            val previousSelected = selectedSession
            if (previousSelected == null && !isPlaying) {
              updateUi {
                savedSessionsTableModel.items = savedSessions.toList()
                savedSessionsTable.selectModelRow(0)
              }
              onSelectedSessionChanged()
            } else {
              updateUi {
                savedSessionsTableModel.items = savedSessions.toList()
                val selectedIndex = if (previousSelected != null) savedSessions.indexOf(previousSelected) else -1
                if (selectedIndex >= 0) {
                  savedSessionsTable.selectModelRow(selectedIndex)
                }
              }
            }
          }
        } catch (t: Throwable) {
          if (sessionItem !in savedSessions) {
            sessionItem.close()
          }
          throw t
        }
      }
    }
  }

  private fun createEmptyStatePanel(): JPanel {
    return BorderLayoutPanelWithPreferredSize(360, 320).apply {
      name = "emptyStatePanel"
      val emptyLabel =
        JBLabel(message("sensor.replay.dialog.empty.selection"), SwingConstants.CENTER).apply {
          name = "emptySelectionLabel"
          foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
      add(emptyLabel, BorderLayout.CENTER)
    }
  }

  private fun createSessionDetailsPanel(): DialogPanel {
    return panel {
      row(message("sensor.replay.dialog.label.file")) {
        fileNameValueLabel = JBLabel().apply { name = "fileNameValueLabel" }
        cell(fileNameValueLabel)
      }

      row(message("sensor.replay.dialog.label.app.version")) {
        appVersionValueLabel = JBLabel().apply { name = "appVersionValueLabel" }
        cell(appVersionValueLabel)
      }

      row(message("sensor.replay.dialog.label.total.events")) {
        totalEventsValueLabel = JBLabel().apply { name = "totalEventsValueLabel" }
        cell(totalEventsValueLabel)
      }

      row(message("sensor.replay.dialog.label.sensors.in.session")) {
        sensorsPreviewLabel = JBLabel().apply { name = "sensorsPreviewLabel" }
        cell(sensorsPreviewLabel)

        sensorsDetailLink =
          ActionLink(message("sensor.replay.dialog.link.more")) { showSensorsDetailDialog() }.apply { name = "sensorsDetailLink" }
        cell(sensorsDetailLink)
      }

      row {
        label(message("sensor.replay.dialog.label.timeline"))
      }

      row {
        val timelineScrollPane =
          JBScrollPane(timelineTable).apply {
            name = "timelineScrollPane"
            preferredSize = JBUI.size(360, 180)
          }
        cell(timelineScrollPane).align(Align.FILL)
      }
        .resizableRow()

      row {
        playbackSlider =
          ProgressSlider(0, SLIDER_MAX, 0).apply {
            name = "playbackSlider"
            accessibleContext.accessibleName = message("sensor.replay.dialog.accessible.playback.progress")
            addChangeListener { onPlaybackSliderChanged() }
          }
        cell(playbackSlider).align(AlignX.FILL).resizableColumn()

        playbackTimeLabel =
          JBLabel(formatTimeProgress(Duration.ZERO, Duration.ZERO)).apply {
            name = "playbackTimeLabel"
          }
        cell(playbackTimeLabel)
      }

      row {
        val playLabel = message("sensor.replay.dialog.button.play")
        playPauseButton =
          IconButton(AllIcons.Actions.Execute, playLabel) { player.togglePlayback() }
            .apply {
              name = "playPauseButton"
            }
        cell(playPauseButton).accessibleName(playLabel)

        placeholder().resizableColumn()

        val speedLabel = message("sensor.replay.dialog.label.playback.speed")
        comboBox(
            DefaultComboBoxModel(SensorReplaySpeed.entries.toTypedArray()),
            renderer = textListCellRenderer { it?.toString() ?: "" },
          )
          .label(speedLabel, LabelPosition.LEFT)
          .accessibleName(speedLabel.removeSuffix(":"))
          .applyToComponent {
            name = "playbackSpeedComboBox"
            playbackSpeedComboBox = this
            selectedItem = player.playbackSpeed
            addItemListener { event ->
              if (!isUpdatingUi && (event.stateChange == ItemEvent.SELECTED)) {
                (event.item as? SensorReplaySpeed)?.let(player::setSpeed)
              }
            }
          }
      }
    }
      .apply { name = "sessionDetailsPanel" }
  }

  private fun createSavedSessionsTablePanel(): JPanel {
    return BorderLayoutPanelWithPreferredSize(240, 280, minimumWidth = 150).apply {
      name = "savedSessionsTablePanel"
      add(
        ToolbarDecorator.createDecorator(savedSessionsTable)
          .setAddAction { chooseAndImportSensorFile() }
          .setRemoveAction { removeSelectedSessions() }
          .setMoveDownAction(null)
          .setMoveUpAction(null)
          .setAddActionUpdater { !isPlaying }
          .setRemoveActionUpdater { !isPlaying && savedSessionsTable.selectedObjects.isNotEmpty() }
          .setToolbarPosition(ActionToolbarPosition.BOTTOM)
          .createPanel()
      )
    }
  }

  private fun chooseAndImportSensorFile() {
    if (isPlaying) return
    val descriptor = createSensorFileChooserDescriptor()
    val parentComponent =
      (parent ?: dialogWrapper?.window ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow)?.takeIf { it.isShowing }
    val virtualFile = fileChooserCreator(descriptor, parentComponent, project).choose(project).firstOrNull() ?: return
    importSensorFile(virtualFile.toNioPath())
  }

  private fun importSensorFile(file: Path) {
    if (isPlaying) return
    clearError()
    val normalizedPath = file.toAbsolutePath().normalize()
    val fileName = normalizedPath.fileName?.toString() ?: file.toString()
    if (savedSessions.any { it.path == normalizedPath }) {
      showError(message("sensor.replay.dialog.error.duplicate.file", fileName))
      return
    }

    dialogScope.launch {
      val sessionItem =
        try {
          withContext(Dispatchers.IO) {
            sessionLoader(normalizedPath) { coroutineContext.ensureActive() }
          }
        } catch (_: EmptySensorSessionException) {
          withContext(uiContext) {
            showError(message("sensor.replay.dialog.error.empty.file", fileName))
          }
          return@launch
        } catch (e: IOException) {
          thisLogger().warn("Unable to parse sensor file '$normalizedPath'", e)
          withContext(uiContext) {
            showError(message("sensor.replay.dialog.error.invalid.file", fileName))
          }
          return@launch
        }

      try {
        withContext(uiContext) {
          if (savedSessions.any { it.path == normalizedPath }) {
            sessionItem.close()
            showError(message("sensor.replay.dialog.error.duplicate.file", fileName))
            return@withContext
          }
          val previousSelected = selectedSession
          savedSessions.add(sessionItem)
          addPersistedSensorFile(normalizedPath)
          if (isPlaying) {
            updateUi {
              savedSessionsTableModel.items = savedSessions.toList()
              val selectedIndex = if (previousSelected != null) savedSessions.indexOf(previousSelected) else -1
              if (selectedIndex >= 0) {
                savedSessionsTable.selectModelRow(selectedIndex)
              }
            }
          } else {
            updateUi {
              savedSessionsTableModel.items = savedSessions.toList()
              savedSessionsTable.selectModelRow(savedSessions.lastIndex)
            }
            onSelectedSessionChanged()
          }
        }
      } catch (t: Throwable) {
        if (sessionItem !in savedSessions) {
          sessionItem.close()
        }
        throw t
      }
    }
  }

  private fun removeSelectedSessions() {
    if (isPlaying) return
    val selectedItems = savedSessionsTable.selectedObjects.toSet()
    if (selectedItems.isEmpty()) return
    clearError()
    val previousMinIndex = savedSessionsTable.selectionModel.minSelectionIndex
    savedSessions.removeAll(selectedItems)
    removePersistedSensorFiles(selectedItems.mapTo(HashSet()) { it.path })
    updateUi {
      savedSessionsTableModel.items = savedSessions.toList()
      savedSessionsTable.selectRowAfterRemoval(previousMinIndex)
    }
    onSelectedSessionChanged()
    for (item in selectedItems) {
      item.close()
    }
  }

  private fun onSelectedSessionChanged() {
    val selected =
      if (savedSessionsTable.selectedRowCount == 1) savedSessionsTable.selectedObject else savedSessionsTable.selectedObjects.firstOrNull()
    player.selectSession(selected)
    refreshDetailsPanel()
    updateControlsEnabledState()
  }

  private fun refreshDetailsPanel() {
    val session = selectedSession
    if (session == null) {
      detailsCardLayout.show(detailsCardPanel, EMPTY_CARD)
      timelineTableModel.items = emptyList()
      return
    }

    fileNameValueLabel.text = StringUtil.shortenTextWithEllipsis(session.fileName, MAX_FILENAME_CHARS, 12)
    fileNameValueLabel.setToolTipText(HtmlChunk.text(session.path.toString()))
    appVersionValueLabel.text = session.appVersion
    totalEventsValueLabel.text = session.totalEvents.toString()
    updateSensorsPreview(session)
    timelineTableModel.items = session.timeline
    updateUi {
      playbackSlider.value = 0
      playbackTimeLabel.text = formatTimeProgress(Duration.ZERO, session.duration)
    }
    detailsCardLayout.show(detailsCardPanel, DETAILS_CARD)
  }

  private fun updateSensorsPreview(session: SensorSessionItem) {
    if (!::sensorsPreviewLabel.isInitialized) return
    val sensors = session.getAvailableSensors(dictionary)
    if (sensors.isEmpty()) {
      sensorsPreviewLabel.text = message("sensor.replay.dialog.value.none")
      sensorsPreviewLabel.setToolTipText(HtmlChunk.empty())
      sensorsDetailLink.isVisible = false
    } else {
      val joined = sensors.joinToString("; ")
      sensorsPreviewLabel.text = StringUtil.shortenTextWithEllipsis(joined, MAX_SENSORS_PREVIEW_CHARS, 0)
      sensorsPreviewLabel.setToolTipText(HtmlChunk.text(joined))
      sensorsDetailLink.isVisible = true
    }
  }

  private fun showSensorsDetailDialog() {
    val session = selectedSession ?: return
    val sensors = session.getAvailableSensors(dictionary)
    if (sensors.isEmpty()) return
    val list =
      JBList(sensors).apply {
        name = "sensorsDetailList"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
      }
    val detailPanel = panel {
      row {
        val scrollPane =
          JBScrollPane(list).apply {
            preferredSize = JBUI.size(380, 240)
          }
        cell(scrollPane).align(Align.FILL)
      }
        .resizableRow()
    }
    dialog(
        title = message("sensor.replay.dialog.sensors.detail.title"),
        resizable = true,
        panel = detailPanel,
        project = project,
        parent = dialogWrapper?.contentPane ?: parent,
        createActions = { listOf(CloseDialogAction(detailPanel)) },
      )
      .show()
  }

  private fun onPlaybackSliderChanged() {
    if (isUpdatingUi) return
    player.onSliderChanged(playbackSlider.value, playbackSlider.valueIsAdjusting)
  }

  private fun onPlaybackProgressChanged(position: Duration, duration: Duration, updateSliderValue: Boolean) {
    if (!::playbackSlider.isInitialized || !::playbackTimeLabel.isInitialized) return
    if (updateSliderValue) {
      updateUi {
        playbackSlider.value = positionToSliderValue(position, duration)
        playbackTimeLabel.text = formatTimeProgress(position, duration)
      }
    } else {
      playbackTimeLabel.text = formatTimeProgress(position, duration)
    }
  }

  private fun updateControlsEnabledState() {
    val playing = isPlaying
    val session = selectedSession
    if (::playPauseButton.isInitialized) {
      val buttonLabel = if (playing) message("sensor.replay.dialog.button.pause") else message("sensor.replay.dialog.button.play")
      playPauseButton.icon = if (playing) AllIcons.Actions.Pause else AllIcons.Actions.Execute
      playPauseButton.setToolTipText(HtmlChunk.text(buttonLabel))
      playPauseButton.accessibleContext.accessibleName = buttonLabel
      playPauseButton.isEnabled = session != null && !session.hasNoEvents
    }
    if (::playbackSlider.isInitialized) {
      playbackSlider.isEnabled = session != null && session.duration.isPositive()
    }
    if (::playbackSpeedComboBox.isInitialized) {
      playbackSpeedComboBox.isEnabled = session != null
    }
    savedSessionsTable.isEnabled = !playing
    ActivityTracker.getInstance().inc()
  }

  private suspend fun dispatchSensorRecord(record: SensorRecord, metadata: SessionMetadata) {
    val override = recordSenderOverride
    if (override != null) {
      override(record, metadata)
    } else {
      sendSensorRecordToEmulator(emulator, record, metadata)
    }
  }

  private class SavedSessionsTableModel :
    ListTableModel<SensorSessionItem>(
      object : TextColumnInfo<SensorSessionItem>(message("sensor.replay.dialog.column.file.name")) {
        override fun valueOf(item: SensorSessionItem): String = item.fileName

        override fun getCellTooltipText(item: SensorSessionItem): String = item.path.toString()
      }
    )

  private class TimelineTableModel :
    ListTableModel<TimelineEntry>(
      object : TextColumnInfo<TimelineEntry>(message("sensor.replay.dialog.column.time.range")) {
        override fun valueOf(item: TimelineEntry): String = item.timeRange
      },
      object : TextColumnInfo<TimelineEntry>(message("sensor.replay.dialog.column.event.count")) {
        override fun valueOf(item: TimelineEntry): String = item.eventCount.toString()
      },
    )

  companion object {
    @NonNls @VisibleForTesting const val SAVED_SENSOR_FILES_PROPERTY = "com.android.tools.idea.streaming.emulator.sensor.replay.saved.files"

    private val SAVED_SESSIONS_KEY =
      Key.create<MutableList<SensorSessionItem>>("com.android.tools.idea.streaming.emulator.dialogs.SensorReplayDialog.savedSessions")

    private val SUPPORTED_FILE_EXTENSIONS = setOf("bin", "pb", "textproto")

    private const val EMPTY_CARD = "EMPTY"
    private const val DETAILS_CARD = "DETAILS"

    private const val MAX_FILENAME_CHARS = 36
    private const val MAX_SENSORS_PREVIEW_CHARS = 48

    private fun createSensorFileChooserDescriptor(): FileChooserDescriptor =
      FileChooserDescriptorFactory.singleFile()
        .withTitle(message("sensor.replay.dialog.import.chooser.title"))
        .withDescription(message("sensor.replay.dialog.import.chooser.description"))
        .withFileFilter(::isSupportedSensorFile)

    private fun isSupportedSensorFile(file: VirtualFile): Boolean {
      val name = file.name
      val dotIndex = name.lastIndexOf('.')
      if (dotIndex <= 0 || dotIndex == name.lastIndex) {
        return true
      }
      val suffix = name.substring(dotIndex + 1)
      val isExtension = suffix.all { it.isLetterOrDigit() } && suffix.any { it.isLetter() }
      return !isExtension || suffix.lowercase(Locale.ROOT) in SUPPORTED_FILE_EXTENSIONS
    }

    private fun getPersistedSensorFiles(): List<Path> {
      val rawPaths = PropertiesComponent.getInstance().getList(SAVED_SENSOR_FILES_PROPERTY) ?: return emptyList()
      val result = ArrayList<Path>(rawPaths.size)
      val seen = HashSet<Path>(rawPaths.size)
      for (raw in rawPaths) {
        if (raw.isBlank()) continue
        try {
          val normalized = Path.of(raw).toAbsolutePath().normalize()
          if (seen.add(normalized)) {
            result.add(normalized)
          }
        } catch (_: InvalidPathException) {
          // Ignore malformed path entry in persisted properties.
        }
      }
      return result
    }

    private fun setPersistedSensorFiles(paths: List<Path>) {
      val properties = PropertiesComponent.getInstance()
      if (paths.isEmpty()) {
        properties.setList(SAVED_SENSOR_FILES_PROPERTY, null)
      } else {
        properties.setList(SAVED_SENSOR_FILES_PROPERTY, paths.distinct().map { FileUtil.toSystemIndependentName(it.toString()) })
      }
    }

    private fun addPersistedSensorFile(path: Path) {
      val current = getPersistedSensorFiles()
      if (path !in current) {
        setPersistedSensorFiles(
          buildList(current.size + 1) {
            addAll(current)
            add(path)
          }
        )
      }
    }

    private fun removePersistedSensorFiles(pathsToRemove: Set<Path>) {
      if (pathsToRemove.isEmpty()) return
      val current = getPersistedSensorFiles()
      val updated = current.filterNot { it in pathsToRemove }
      if (updated.size != current.size) {
        setPersistedSensorFiles(updated)
      }
    }

    private fun getOrCreateSavedSessions(emulator: EmulatorController): MutableList<SensorSessionItem> {
      emulator.getUserData(SAVED_SESSIONS_KEY)?.let {
        return it
      }
      val newList = CopyOnWriteArrayList<SensorSessionItem>()
      val installed = emulator.putUserDataIfAbsent(SAVED_SESSIONS_KEY, newList)
      if (installed === newList) {
        val cleanup = Disposable {
          val items = newList.toList()
          newList.clear()
          for (item in items) {
            item.close()
          }
        }
        if (!Disposer.tryRegister(emulator, cleanup)) {
          Disposer.dispose(cleanup)
        }
      }
      return installed
    }
  }
}
