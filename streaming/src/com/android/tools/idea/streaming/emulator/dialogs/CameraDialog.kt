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
import com.android.emulation.control.incubating.PosterList
import com.android.io.readImage
import com.android.tools.adtui.ImageUtils.scale
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
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.border.LineBorder
import kotlin.math.min
import kotlin.math.roundToInt
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

  private var animationsEnabled: Boolean = true
  private var animationsModified: Boolean = false

  private val animationStateChannel =
    createStateChannel(
      getState = EmulatorController::getAnimationState,
      onStateLoaded = { state ->
        if (!animationsModified) {
          animationsEnabled = state.tvOn
          updateUi()
        }
      },
      setState = EmulatorController::setAnimationState,
    )

  init {
    val initialStateJob = coroutineScope.launch {
      try {
        val posterList = emulator.listPosters()
        withContext(uiContext) {
          updateFromPosterList(posterList)
        }
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
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
  }

  private fun setAnimationsEnabledFromUi(enabled: Boolean) {
    if (animationsEnabled != enabled) {
      animationsEnabled = enabled
      animationsModified = true
      stateModified = true
      clearError()
      updateUi()
      animationStateChannel.trySend(AnimationState.newBuilder().setTvOn(enabled).build())
    }
  }

  private fun updateFromPosterList(posterList: PosterList) {
    for (poster in posterList.postersList) {
      when (poster.name) {
        WALL_POSTER -> wallPosterControl.updateFromProto(poster)
        TABLE_POSTER -> tablePosterControl.updateFromProto(poster)
      }
    }
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

    var previewImage: BufferedImage? = null
      private set

    private var modified: Boolean = false
    private val posterChannel: SendChannel<Poster> = createPosterChannel()

    private lateinit var imagePreviewLabel: JBLabel
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

    private fun createPosterChannel(): SendChannel<Poster> {
      val channel = Channel<Poster>(Channel.CONFLATED)
      onDispose { channel.close() }
      coroutineScope.launch {
        for (poster in channel) {
          try {
            emulator.setPoster(poster)
          } catch (t: Throwable) {
            t.throwIfCancellation()
            // Error is already logged by EmulatorController.
          }
        }
      }
      return channel
    }

    fun updateFromProto(poster: Poster) {
      if (modified) {
        return
      }
      if (poster.minWidth > 0f && poster.maxWidth > poster.minWidth) {
        minWidth = poster.minWidth
        maxWidth = poster.maxWidth
      }
      val minScale = if (maxWidth > 0f) minWidth / maxWidth else 0f
      scale = if (poster.scale > 0f) poster.scale.coerceIn(minScale, 1.0f) else 1.0f

      val path = if (poster.hasFileName()) poster.fileName.value.takeIf { it.isNotEmpty() } else null
      val loadedImage =
        when {
          poster.hasImage() && !poster.image.value.isEmpty -> readBufferedImage(poster.image.value.toByteArray())
          path != null -> readBufferedImage(Path.of(path))
          else -> null
        }
      if (path != null && loadedImage != null) {
        filePath = path
        previewImage = loadedImage
      } else {
        filePath = null
        previewImage = null
      }
    }

    fun createUi(row: Row) {
      imagePreviewLabel =
        JBLabel(message("camera.dialog.no.image.applied"), SwingConstants.CENTER).apply {
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
              slider(
                  (minWidth * SLIDER_STEPS_PER_METER).roundToInt(),
                  (maxWidth * SLIDER_STEPS_PER_METER).roundToInt(),
                  0,
                  0,
                )
                .label(label, LabelPosition.TOP)
                .accessibleName(label.removeSuffix(":"))
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                  name = "${posterName}SizeSlider"
                  paintLabels = false
                  paintTicks = false
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
      refreshComponents()
    }

    private fun refreshComponents() {
      if (!::imagePreviewLabel.isInitialized) {
        return
      }
      val img = previewImage
      val path = filePath
      if (img != null && path != null) {
        val scaledImage = scaleToFit(img, JBUIScale.scale(PREVIEW_WIDTH) - 2, JBUIScale.scale(PREVIEW_HEIGHT) - 2)
        imagePreviewLabel.icon = JBImageIcon(scaledImage)
        imagePreviewLabel.text = null
        fileNameField.text = Path.of(path).fileName?.toString() ?: path
        fileNameField.setToolTipText(HtmlChunk.text(path))
        fileNameField.setExtensions(browseExtension, removeExtension)
        sizeRow.visible(true)
      } else {
        imagePreviewLabel.icon = null
        imagePreviewLabel.text = message("camera.dialog.no.image.applied")
        fileNameField.text = ""
        fileNameField.toolTipText = null
        fileNameField.setExtensions(browseExtension)
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
      val image = readBufferedImage(path)
      if (image == null) {
        showError(message("camera.dialog.error.invalid.image", path.fileName ?: path))
        return
      }
      val pathString = path.toString()
      filePath = pathString
      previewImage = image
      modified = true
      stateModified = true
      updateUi()
      sendPosterUpdate(includeFileName = true)
    }

    private fun removePosterImage() {
      clearError()
      if (filePath == null && previewImage == null) {
        return
      }
      filePath = null
      previewImage = null
      modified = true
      stateModified = true
      updateUi()
      sendPosterUpdate(includeFileName = true)
    }

    private fun onSliderChanged(sliderValue: Int) {
      clearError()
      val newMeters = (sliderValue.toFloat() / SLIDER_STEPS_PER_METER).coerceIn(minWidth, maxWidth)
      val newScale = if (maxWidth > 0f) (newMeters / maxWidth).coerceIn(minWidth / maxWidth, 1.0f) else 1.0f
      if (scale != newScale) {
        scale = newScale
        modified = true
        stateModified = true
        updateUi()
        sendPosterUpdate(includeFileName = false)
      }
    }

    private fun sendPosterUpdate(includeFileName: Boolean) {
      val builder = Poster.newBuilder().setName(posterName).setScale(scale)
      if (includeFileName) {
        builder.fileName = StringValue.of(filePath ?: "")
      }
      posterChannel.trySend(builder.build())
    }

    private fun installDropTarget(component: JComponent) {
      val disposable = Disposer.newDisposable()
      onDispose { Disposer.dispose(disposable) }
      DnDSupport.createBuilder(component)
        .enableAsNativeTarget()
        .setTargetChecker { event ->
          val possible = getDroppedImageFile(event) != null
          event.isDropPossible = possible
          !possible
        }
        .setDisposableParent(disposable)
        .setDropHandler { event ->
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

    private fun readBufferedImage(path: Path): BufferedImage? {
      return try {
        path.readImage()
      } catch (_: Exception) {
        null
      }
    }

    private fun readBufferedImage(bytes: ByteArray): BufferedImage? {
      return try {
        ImageIO.read(ByteArrayInputStream(bytes))
      } catch (_: Exception) {
        null
      }
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
