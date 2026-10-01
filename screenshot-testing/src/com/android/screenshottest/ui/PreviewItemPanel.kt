/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.screenshottest.ui

import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.util.ScreenshotTestUtils
import com.android.tools.idea.testartifacts.instrumented.testsuite.util.logScreenshotTestEvent
import com.android.tools.idea.testartifacts.instrumented.testsuite.view.ScreenshotViewType
import com.google.wireless.android.sdk.stats.ScreenshotTestComposePreviewEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBImageIcon
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * A UI panel that displays a single screenshot test preview image.
 *
 * The panel never touches the disk on the EDT: file existence comes from [PreviewDetails] and thumbnails come from [thumbnailLoader].
 */
class PreviewItemPanel(
  var previewData: PreviewDetails,
  private val project: Project? = null,
  private val showDetails: Boolean = true,
  private val thumbnailLoader: ThumbnailLoader = ThumbnailLoader(),
  private val logger: Logger = Logger.getInstance(PreviewItemPanel::class.java),
) : JPanel() {
  private var currentImagePath: String = ""
  private var currentTestId: String = previewData.testId
  private val imagePanel: ImagePanel
  private lateinit var previewNameLabel: JBLabel
  private lateinit var matchLabelContainer: JPanel

  var isLoadedSuccessfully: Boolean = false
    private set

  init {
    // Use GridBagLayout to stack components vertically without forcing them to the same width.
    layout = GridBagLayout()
    isOpaque = false

    val c = GridBagConstraints()
    c.gridx = 0
    c.anchor = GridBagConstraints.WEST // Pin components to the left.
    c.fill = GridBagConstraints.NONE // Do not allow components to stretch.
    c.weightx = 0.0

    imagePanel = ImagePanel()
    c.gridy = 0
    add(imagePanel, c)

    if (showDetails) {
      val detailsPanel =
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.Y_AXIS)
          border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
          isOpaque = false
        }

      matchLabelContainer =
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.X_AXIS)
          isOpaque = false
          alignmentX = LEFT_ALIGNMENT
        }
      previewNameLabel = JBLabel(previewData.previewName).apply { alignmentX = LEFT_ALIGNMENT }

      detailsPanel.add(matchLabelContainer)
      detailsPanel.add(previewNameLabel)
      // TODO: Add Composable link

      c.gridy = 1
      add(detailsPanel, c)
      updateDetails(previewData)
    }
  }

  fun updateData(
    newData: PreviewDetails,
    viewType: ScreenshotViewType,
    onImageLoaded: (() -> Unit)? = null,
  ) {
    this.previewData = newData
    if (showDetails) {
      updateDetails(newData)
    }
    showImageForView(viewType, onImageLoaded)
  }

  private fun updateDetails(previewData: PreviewDetails) {
    previewNameLabel.text = previewData.previewName
    matchLabelContainer.removeAll()
    matchLabelContainer.add(createMatchPercentageLabel(previewData))
    matchLabelContainer.revalidate()
    matchLabelContainer.repaint()
  }

  private fun createMatchPercentageLabel(previewData: PreviewDetails): JPanel {
    val hasReferenceImage =
      previewData.destImageExists || previewData.diffPercent != null || previewData.testResult == AndroidTestCaseResult.PASSED

    if (!hasReferenceImage) {
      return JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        add(
          JBLabel(NEW_TAG_TEXT).apply {
            foreground = JBColor.GREEN.darker()
            font = font.deriveFont(Font.BOLD)
          }
        )
      }
    }

    val diffDouble = previewData.diffPercent?.toDoubleOrNull()
    val matchPercentage = ScreenshotTestUtils.calculateMatchPercentage(diffDouble)
    val percentageText = matchPercentage ?: DEFAULT_MATCH_PERCENTAGE

    val color =
      if (previewData.testResult == AndroidTestCaseResult.PASSED) {
        JBColor.GREEN.darker()
      } else {
        JBColor.RED
      }

    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      isOpaque = false
      alignmentX = LEFT_ALIGNMENT

      add(JBLabel("Match: "))
      add(
        JBLabel(percentageText).apply {
          foreground = color
          font = font.deriveFont(Font.BOLD)
        }
      )
    }
  }

  fun showError(message: String) {
    currentImagePath = ""
    isLoadedSuccessfully = false
    imagePanel.showText(message)
  }

  private fun showPlaceholder(message: String, color: JBColor) {
    currentImagePath = ""
    imagePanel.showText(message, color)
  }

  fun showImageForView(viewType: ScreenshotViewType, onImageLoaded: (() -> Unit)? = null) {
    when (viewType) {
      ScreenshotViewType.ALL -> {}
      ScreenshotViewType.NEW -> {
        previewData.srcImagePath?.let { loadImage(it, previewData.testId, onImageLoaded) }
          ?: run {
            // Log the SCREENSHOT_DIALOG_RENDER_FAILURE event
            logScreenshotTestEvent(
              ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_RENDER_FAILURE,
              project,
            )
            showError(NO_NEW_IMAGE_TEXT)
          }
      }
      ScreenshotViewType.DIFF -> {
        val diffPath = previewData.diffImagePath
        if (diffPath != null && previewData.diffImageExists) {
          loadImage(diffPath, previewData.testId, onImageLoaded)
        } else {
          if (previewData.testResult == AndroidTestCaseResult.PASSED) {
            showPlaceholder(NO_DIFFERENCE_TEXT, JBColor.GREEN)
          } else {
            showPlaceholder(NO_DIFF_IMAGE_TEXT, JBColor.RED)
          }
        }
      }
      ScreenshotViewType.REFERENCE -> {
        val refPath = previewData.destImagePath
        if (refPath != null && previewData.destImageExists) {
          loadImage(refPath, previewData.testId, onImageLoaded)
        } else {
          showPlaceholder(NO_REF_IMAGE_TEXT, JBColor.RED)
        }
      }
    }
  }

  /**
   * Shows the thumbnail at [newPath].
   *
   * Decoded and failed thumbnails are applied synchronously so the panel can be used as a list cell renderer. [onImageLoaded] is invoked
   * only when a thumbnail finishes decoding in the background, which is when a list that painted the loading state needs to repaint.
   */
  fun loadImage(newPath: String, testId: String, onImageLoaded: (() -> Unit)? = null) {
    if (currentImagePath == newPath && currentTestId == testId) {
      return
    }
    // Update tracking fields immediately to ensure subsequent calls can detect if this request
    // becomes stale.
    currentImagePath = newPath
    currentTestId = testId

    val cachedImage = thumbnailLoader.getCached(newPath)
    if (cachedImage != null) {
      showImage(cachedImage)
      return
    }
    if (thumbnailLoader.hasFailed(newPath)) {
      showLoadFailure()
      return
    }

    isLoadedSuccessfully = false
    imagePanel.showLoading()

    thumbnailLoader.load(newPath) { image ->
      // The panel may have been reused for another item while the image was decoding.
      if (currentImagePath == newPath && currentTestId == testId) {
        if (image != null) {
          showImage(image)
        } else {
          logger.warn("Couldn't load image from path: $newPath")
          // Log the SCREENSHOT_DIALOG_RENDER_FAILURE event
          logScreenshotTestEvent(
            ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_RENDER_FAILURE,
            project,
          )
          showLoadFailure()
        }
      }
      // Notify even for stale requests so that the parent list repaints and picks the decoded
      // thumbnail from the loader's cache.
      onImageLoaded?.invoke()
    }
  }

  private fun showImage(image: JBImageIcon) {
    imagePanel.setImage(image)
    isLoadedSuccessfully = true
    revalidate()
    repaint()
  }

  private fun showLoadFailure() {
    isLoadedSuccessfully = false
    imagePanel.showText(COULD_NOT_LOAD_IMAGE_TEXT)
  }

  /** A self-contained panel that handles its own sizing and rendering to prevent distortion. */
  private class ImagePanel : JPanel(GridBagLayout()) {
    private var image: JBImageIcon? = null
    private val loadingIcon = AsyncProcessIcon(WAITING_FOR_IMAGE_TEXT)
    private val initialSize: Dimension
      get() = Dimension(MAX_THUMBNAIL_SIZE, MAX_THUMBNAIL_SIZE)

    init {
      // Set an initial fixed size for the loading state.
      preferredSize = initialSize
      maximumSize = initialSize
      border = BorderFactory.createLineBorder(JBColor.border())
      add(loadingIcon)
    }

    fun setImage(newImage: JBImageIcon) {
      loadingIcon.suspend()
      this.image = newImage
      removeAll() // Remove loading icon or text labels

      // Lock the panel's size to the image's size. This is the key to preventing distortion.
      val newSize = Dimension(newImage.iconWidth, newImage.iconHeight)
      preferredSize = newSize
      maximumSize = newSize

      revalidate()
      repaint()
    }

    fun showLoading() {
      loadingIcon.resume()
      this.image = null
      removeAll()
      add(loadingIcon)
      preferredSize = initialSize
      maximumSize = initialSize
      revalidate()
      repaint()
    }

    fun showText(message: String, color: JBColor = JBColor.RED) {
      loadingIcon.suspend()
      this.image = null
      removeAll()
      add(JBLabel(message).apply { foreground = color })

      // Reset to the initial size to ensure the placeholder text is not clipped.
      preferredSize = initialSize
      maximumSize = initialSize

      revalidate()
      repaint()
    }

    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      // Manually paint the image to ensure it's centered and not scaled by the layout manager.
      image?.let {
        val x = (width - it.iconWidth) / 2
        val y = (height - it.iconHeight) / 2
        it.paintIcon(this, g, x, y)
      }
    }
  }
}
