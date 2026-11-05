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

import com.android.emulation.control.incubating.AnimationState
import com.android.emulation.control.incubating.Poster
import com.android.io.readImage
import com.android.tools.adtui.ImageUtils.scale
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.protobuf.StringValue
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.utils.throwIfCancellation
import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.setToolTipText
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooser.chooseFile
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.border.LineBorder
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls

/** Dialog for configuring emulator virtual scene camera settings, similar to the Camera page in extended controls. */
internal class CameraDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("camera.dialog.title", emulator.emulatorId.avdName)) {

  private val wallPosterControl = PosterControl(WALL_POSTER, DEFAULT_WALL_MIN_WIDTH, DEFAULT_WALL_MAX_WIDTH)
  private val tablePosterControl = PosterControl(TABLE_POSTER, DEFAULT_TABLE_MIN_WIDTH, DEFAULT_TABLE_MAX_WIDTH)

  private var postersInitialized: Boolean = false
  private var animationsEnabled: Boolean = true

  private val animationStateChannel =
    createStateChannel(
      getState = EmulatorController::getAnimationState,
      onStateLoaded = { state ->
        animationsEnabled = state.tvOn
        updateUi()
      },
      setState = EmulatorController::setAnimationState,
    )

  init {
    val initialStateJob = coroutineScope.launch {
      try {
        val posterList = emulator.callWithRetry(EmulatorController::listPosters)
        val decodedPosters =
          withContext(Dispatchers.IO) {
            posterList.postersList.mapNotNull { poster ->
              when (poster.name) {
                WALL_POSTER -> poster to if (wallPosterControl.modified) null else decodePosterPreview(poster)
                TABLE_POSTER -> poster to if (tablePosterControl.modified) null else decodePosterPreview(poster)
                else -> null
              }
            }
          }
        withContext(uiContext) {
          updateFromDecodedPosters(decodedPosters)
        }
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
        withContext(uiContext) {
          postersInitialized = true
          updateUi()
          showError(message("camera.dialog.error.load.posters"))
        }
      }
    }
    onDispose { initialStateJob.cancel() }
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    return panel {
      group(message("camera.dialog.label.wall"), indent = false) {
        row {
          wallPosterControl.createUi(this)
        }
      }

      group(message("camera.dialog.label.table"), indent = false) {
        row {
          tablePosterControl.createUi(this)
        }
      }

      row {
        checkBox(message("camera.dialog.checkbox.enable.animations")).applyToComponent {
          name = "enableAnimationsCheckBox"
          isSelected = animationsEnabled
          addActionListener {
            if (!isUpdatingUi) {
              setAnimationsEnabledFromUi(isSelected)
            }
          }
          registerUiUpdater {
            if (isSelected != animationsEnabled) {
              isSelected = animationsEnabled
            }
          }
        }
      }
    }
      .also { updateUi() }
  }

  private fun setAnimationsEnabledFromUi(enabled: Boolean) {
    if (animationsEnabled != enabled) {
      animationsEnabled = enabled
      stateModified = true
      clearError()
      updateUi()
      animationStateChannel.trySend(AnimationState.newBuilder().setTvOn(enabled).build())
    }
  }

  private fun updateFromDecodedPosters(decodedPosters: List<Pair<Poster, JBImageIcon?>>) {
    for ((poster, previewIcon) in decodedPosters) {
      when (poster.name) {
        WALL_POSTER -> wallPosterControl.updateFromProto(poster, previewIcon)
        TABLE_POSTER -> tablePosterControl.updateFromProto(poster, previewIcon)
      }
    }
    postersInitialized = true
    updateUi()
  }

  /** Manages the state and UI components of a single virtual scene poster position (e.g., "wall" or "table"). */
  private inner class PosterControl(private val posterName: String, defaultMinWidth: Float, defaultMaxWidth: Float) {
    var minWidth: Float = defaultMinWidth
      private set

    var maxWidth: Float = defaultMaxWidth
      private set

    var scale: Float = 1.0f
      private set

    var filePath: String? = null
      private set

    var previewIcon: JBImageIcon? = null
      private set

    @Volatile
    var modified: Boolean = false
      private set

    private var imageLoadJob: Job? = null
    private val pendingPoster = AtomicReference<Poster?>()
    private val posterUpdateSignal: SendChannel<Unit> = createPosterUpdateChannel()

    init {
      onDispose { imageLoadJob?.cancel() }
    }

    private lateinit var imagePreviewLabel: JBLabel
    private lateinit var fileNameRow: Row
    private lateinit var fileNameField: ExtendableTextField
    private lateinit var sizeRow: Row
    private lateinit var sizeSlider: JSlider
    private lateinit var sizeField: JTextField

    private val browseExtension =
      ExtendableTextComponent.Extension.create(
        AllIcons.General.OpenDisk,
        AllIcons.General.OpenDiskHover,
        message("camera.dialog.button.browse.image"),
        true,
        ::openFileChooser,
      )

    private val removeExtension =
      ExtendableTextComponent.Extension.create(
        AllIcons.Actions.Close,
        AllIcons.Actions.CloseHovered,
        message("camera.dialog.button.remove.image"),
        true,
        ::removePosterImage,
      )

    val sizeInMeters: Float
      get() = (scale * maxWidth).coerceIn(minWidth, maxWidth)

    private fun createPosterUpdateChannel(): SendChannel<Unit> {
      val channel = Channel<Unit>(Channel.CONFLATED)
      onDispose { channel.close() }
      coroutineScope.launch {
        while (channel.receiveCatching().isSuccess) {
          val poster = pendingPoster.getAndSet(null) ?: continue
          try {
            emulator.callWithRetry { setPoster(poster) }
          } catch (t: Throwable) {
            t.throwIfCancellation()
            // Error is already logged by EmulatorController.
          }
        }
      }
      return channel
    }

    fun updateFromProto(poster: Poster, loadedIcon: JBImageIcon?) {
      if (poster.minWidth > 0f && poster.maxWidth > poster.minWidth) {
        minWidth = poster.minWidth
        maxWidth = poster.maxWidth
      }
      if (modified) {
        return
      }
      val minScale = if (maxWidth > 0f) minWidth / maxWidth else 0f
      scale = if (poster.scale > 0f) poster.scale.coerceIn(minScale, 1.0f) else 1.0f

      val path = if (poster.hasFileName()) poster.fileName.value.takeIf { it.isNotEmpty() } else null
      filePath = if (loadedIcon != null) path else null
      previewIcon = loadedIcon
    }

    fun createUi(row: Row) {
      imagePreviewLabel =
        JBLabel("", SwingConstants.CENTER).apply {
          name = "${posterName}ImagePreview"
          isOpaque = true
          background = PREVIEW_BACKGROUND
          border = LineBorder(JBColor.border(), 1)
          val previewSize = JBUI.size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
          preferredSize = previewSize
          minimumSize = previewSize
          maximumSize = previewSize
        }

      val controlsContainer = panel {
        fileNameRow =
          row {
              val label = message("camera.dialog.label.file.name")
              cell(ExtendableTextField())
                .label(label, LabelPosition.TOP)
                .accessibleName(label.removeSuffix(":"))
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                  name = "${posterName}FileNameField"
                  isEditable = false
                  columns = 14
                  fileNameField = this
                }
            }
            .layout(RowLayout.PARENT_GRID)

        sizeRow =
          row {
              val label = message("camera.dialog.label.size")
              val minSteps = (minWidth * SLIDER_STEPS_PER_METER).roundToInt()
              val maxSteps = (maxWidth * SLIDER_STEPS_PER_METER).roundToInt()
              slider(minSteps, maxSteps, 0, 0)
                .label(label, LabelPosition.TOP)
                .accessibleName(label.removeSuffix(":"))
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                  name = "${posterName}SizeSlider"
                  paintLabels = false
                  paintTicks = false
                  value = (sizeInMeters * SLIDER_STEPS_PER_METER).roundToInt().coerceIn(minSteps, maxSteps)
                  preferredSize = Dimension(JBUIScale.scale(SLIDER_WIDTH), preferredSize.height)
                  putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(left = 11, right = 11))
                  addChangeListener {
                    if (!isUpdatingUi) {
                      onSliderChanged(value)
                    }
                  }
                  sizeSlider = this
                }
              textField().applyToComponent {
                name = "${posterName}SizeField"
                border = JBUI.Borders.empty()
                isOpaque = false
                isEditable = false
                isFocusable = false
                horizontalAlignment = SwingConstants.RIGHT
                columns = 5
                sizeField = this
              }
            }
            .layout(RowLayout.PARENT_GRID)
      }
        .apply {
          name = "${posterName}ControlsContainer"
          minimumSize = preferredSize
        }

      installDropTarget(imagePreviewLabel)
      installDropTarget(controlsContainer)

      row.cell(imagePreviewLabel).align(AlignY.TOP)
      row.cell(controlsContainer).align(AlignX.FILL).align(AlignY.TOP).resizableColumn()

      registerUiUpdater { refreshComponents() }
    }

    private fun refreshComponents() {
      if (!::imagePreviewLabel.isInitialized) {
        return
      }
      imagePreviewLabel.isEnabled = postersInitialized
      fileNameRow.enabled(postersInitialized)
      sizeRow.enabled(postersInitialized)

      val icon = previewIcon
      val path = filePath
      if (icon != null) {
        if (imagePreviewLabel.icon !== icon) {
          imagePreviewLabel.icon = icon
        }
        if (imagePreviewLabel.text != null) {
          imagePreviewLabel.text = null
        }
        val fileName = path?.let { Path.of(it).fileName?.toString() ?: it }.orEmpty()
        if (fileNameField.text != fileName) {
          fileNameField.text = fileName
        }
        fileNameField.setToolTipText(if (path != null) HtmlChunk.text(path) else HtmlChunk.empty())
        val extensions = listOf(browseExtension, removeExtension)
        if (fileNameField.extensions != extensions) {
          fileNameField.setExtensions(extensions)
        }
        sizeRow.visible(true)
      } else {
        if (imagePreviewLabel.icon != null) {
          imagePreviewLabel.icon = null
        }
        val noImageText = if (postersInitialized) message("camera.dialog.no.image.applied") else null
        if (imagePreviewLabel.text != noImageText) {
          imagePreviewLabel.text = noImageText
        }
        if (fileNameField.text.isNotEmpty()) {
          fileNameField.text = ""
        }
        fileNameField.setToolTipText(HtmlChunk.empty())
        val extensions = listOf(browseExtension)
        if (fileNameField.extensions != extensions) {
          fileNameField.setExtensions(extensions)
        }
        sizeRow.visible(false)
      }

      val minSteps = (minWidth * SLIDER_STEPS_PER_METER).roundToInt()
      val maxSteps = (maxWidth * SLIDER_STEPS_PER_METER).roundToInt()
      if (sizeSlider.minimum != minSteps) {
        sizeSlider.minimum = minSteps
      }
      if (sizeSlider.maximum != maxSteps) {
        sizeSlider.maximum = maxSteps
      }
      val sliderVal = (sizeInMeters * SLIDER_STEPS_PER_METER).roundToInt().coerceIn(minSteps, maxSteps)
      if (sizeSlider.value != sliderVal) {
        sizeSlider.value = sliderVal
      }
      val formattedSize = formatMeters(sizeInMeters)
      if (sizeField.text != formattedSize) {
        sizeField.text = formattedSize
      }
    }

    private fun openFileChooser() {
      if (!postersInitialized) {
        return
      }
      clearError()
      val descriptor =
        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
          .withExtensionFilter(message("camera.dialog.file.chooser.filter"), *SUPPORTED_EXTENSIONS)
          .withTitle(message("camera.dialog.file.chooser.title"))
          .withDescription(message("camera.dialog.file.chooser.description"))
      val initialDir =
        PropertiesComponent.getInstance().getValue(LAST_POSTER_DIRECTORY_PROPERTY)?.let {
          LocalFileSystem.getInstance().findFileByPath(it)
        }
      val virtualFile = chooseFile(descriptor, project, initialDir) ?: return
      virtualFile.parent?.path?.let { PropertiesComponent.getInstance().setValue(LAST_POSTER_DIRECTORY_PROPERTY, it) }
      applyImageFile(Path.of(virtualFile.path))
    }

    private fun applyImageFile(path: Path) {
      clearError()
      imageLoadJob?.cancel()
      imageLoadJob = coroutineScope.launch {
        val icon = withContext(Dispatchers.IO) { readPreviewIcon(path) }
        withContext(uiContext) {
          if (icon == null) {
            showError(message("camera.dialog.error.invalid.image", path.fileName ?: path))
            return@withContext
          }
          filePath = path.toString()
          previewIcon = icon
          modified = true
          updateUi()
          sendPosterUpdate(includeFileName = true)
        }
      }
    }

    private fun removePosterImage() {
      if (!postersInitialized) {
        return
      }
      clearError()
      imageLoadJob?.cancel()
      if (previewIcon == null) {
        return
      }
      filePath = null
      previewIcon = null
      modified = true
      updateUi()
      sendPosterUpdate(includeFileName = true)
    }

    private fun onSliderChanged(sliderValue: Int) {
      if (!postersInitialized) {
        return
      }
      clearError()
      val newMeters = (sliderValue.toFloat() / SLIDER_STEPS_PER_METER).coerceIn(minWidth, maxWidth)
      val newScale = if (maxWidth > 0f) (newMeters / maxWidth).coerceIn(minWidth / maxWidth, 1.0f) else 1.0f
      if (scale != newScale) {
        scale = newScale
        modified = true
        updateUi()
        sendPosterUpdate(includeFileName = false)
      }
    }

    private fun sendPosterUpdate(includeFileName: Boolean) {
      val currentScale = scale
      val currentFilePath = filePath
      pendingPoster.updateAndGet { previous ->
        val builder = Poster.newBuilder().setName(posterName).setScale(currentScale)
        if (includeFileName) {
          builder.fileName = StringValue.of(currentFilePath ?: "")
        } else if (previous != null && previous.hasFileName()) {
          builder.fileName = previous.fileName
        }
        builder.build()
      }
      posterUpdateSignal.trySend(Unit)
    }

    private fun installDropTarget(component: JComponent) {
      val disposable = Disposer.newDisposable()
      onDispose { Disposer.dispose(disposable) }
      DnDSupport.createBuilder(component)
        .enableAsNativeTarget()
        .setTargetChecker { event ->
          val possible = postersInitialized && getDroppedImageFile(event) != null
          event.isDropPossible = possible
          !possible
        }
        .setDisposableParent(disposable)
        .setDropHandler { event ->
          if (!postersInitialized) {
            return@setDropHandler
          }
          val file = getDroppedImageFile(event) ?: return@setDropHandler
          file.parent?.toString()?.let { PropertiesComponent.getInstance().setValue(LAST_POSTER_DIRECTORY_PROPERTY, it) }
          applyImageFile(file)
        }
        .install()
    }
  }

  companion object {
    @NonNls internal const val WALL_POSTER = "wall"
    @NonNls internal const val TABLE_POSTER = "table"
    @NonNls internal const val LAST_POSTER_DIRECTORY_PROPERTY = "com.android.tools.idea.streaming.camera.dialog.last.dir"

    @NonNls private val SUPPORTED_EXTENSIONS = arrayOf("png", "jpg", "jpeg")

    private const val DEFAULT_WALL_MIN_WIDTH = 0.2f
    private const val DEFAULT_WALL_MAX_WIDTH = 2.0f
    private const val DEFAULT_TABLE_MIN_WIDTH = 0.2f
    private const val DEFAULT_TABLE_MAX_WIDTH = 1.0f

    private const val PREVIEW_WIDTH = 160
    private const val PREVIEW_HEIGHT = 120
    private const val SLIDER_WIDTH = 150
    internal const val SLIDER_STEPS_PER_METER = 100

    private val PREVIEW_BACKGROUND = JBColor(0xE8EAED, 0x3C3F41)

    private fun formatMeters(meters: Float): String = String.format(Locale.US, "%.2f m", meters)

    private fun decodePosterPreview(poster: Poster): JBImageIcon? {
      val path = if (poster.hasFileName()) poster.fileName.value.takeIf { it.isNotEmpty() } else null
      return when {
        poster.hasImage() && !poster.image.value.isEmpty -> readPreviewIcon(poster.image.value)
        path != null -> readPreviewIcon(path)
        else -> null
      }
    }

    private fun readPreviewIcon(path: String): JBImageIcon? {
      return try {
        readPreviewIcon(Path.of(path))
      } catch (e: Exception) {
        e.throwIfCancellation()
        null
      }
    }

    private fun readPreviewIcon(path: Path): JBImageIcon? {
      return try {
        createPreviewIcon(path.readImage())
      } catch (e: Exception) {
        e.throwIfCancellation()
        null
      }
    }

    private fun readPreviewIcon(bytes: ByteString): JBImageIcon? {
      return try {
        ImageIO.read(bytes.newInput())?.let(::createPreviewIcon)
      } catch (e: Exception) {
        e.throwIfCancellation()
        null
      }
    }

    private fun createPreviewIcon(image: BufferedImage): JBImageIcon {
      val scaledImage = scaleToFit(image, JBUIScale.scale(PREVIEW_WIDTH) - 2, JBUIScale.scale(PREVIEW_HEIGHT) - 2)
      return JBImageIcon(scaledImage)
    }

    private fun scaleToFit(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
      if (image.width <= 0 || image.height <= 0 || maxWidth <= 0 || maxHeight <= 0) {
        return image
      }
      val scaleFactor = min(maxWidth.toDouble() / image.width, maxHeight.toDouble() / image.height)
      return if (scaleFactor == 1.0) image else scale(image, scaleFactor)
    }

    internal fun getDroppedImageFile(event: DnDEvent): Path? {
      if (!FileCopyPasteUtil.isFileListFlavorAvailable(event)) {
        return null
      }
      val files = FileCopyPasteUtil.getFileListFromAttachedObject(event.attachedObject)
      if (files.size != 1) {
        return null
      }
      val file = files[0]
      val extension = file.extension.lowercase(Locale.ROOT)
      val path = file.toPath()
      return if (extension in SUPPORTED_EXTENSIONS && Files.isRegularFile(path)) path else null
    }
  }
}
