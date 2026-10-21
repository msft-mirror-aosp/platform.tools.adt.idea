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
package com.android.tools.idea.compose.preview.resize

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_LAYOUT_HEIGHT
import com.android.SdkConstants.ATTR_LAYOUT_WIDTH
import com.android.SdkConstants.VALUE_MATCH_PARENT
import com.android.SdkConstants.VALUE_WRAP_CONTENT
import com.android.tools.configurations.Configuration
import com.android.tools.configurations.ConfigurationListener
import com.android.tools.configurations.DeviceSize
import com.android.tools.configurations.deviceSizePx
import com.android.tools.idea.compose.preview.util.previewElement
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.preview.ComposePreviewElementInstance
import com.android.tools.preview.dimensionToString
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import java.awt.Dimension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Threshold to trigger a zoom-to-fit operation. It means that if the current scale is more than double the required scale to fit the
 * content, or if the required scale is more than double the current scale, then a zoom-to-fit operation will be performed.
 */
private const val ZOOM_TO_FIT_RESCALE_THRESHOLD = 2.0

/**
 * Returns the `layout_width` and `layout_height` to use for the root tag of this preview's XML.
 *
 * A custom-sized preview (WxH text fields or drag handles in the [ResizePanel]) without System UI fills the custom size, like
 * `@Preview(widthDp, heightDp)`. Otherwise, the preview's configured dimensions are used (`wrap_content` by default), like `@Preview(device
 * = ...)`, so that preset devices don't make the preview fill the whole device screen.
 */
internal fun ComposePreviewElementInstance<*>.layoutSize(isCustomSized: Boolean): Pair<String, String> =
  if (isCustomSized && !displaySettings.showDecoration) {
    VALUE_MATCH_PARENT to VALUE_MATCH_PARENT
  } else {
    dimensionToString(configuration.width, VALUE_WRAP_CONTENT) to dimensionToString(configuration.height, VALUE_WRAP_CONTENT)
  }

/**
 * Invokes rendering of the scene when the device size changes in [Configuration]. The lifecycle is tied to corresponding [SceneManger]
 *
 * @param sceneManager SceneManager
 * @param configuration Configuration
 * @param defaultDispatcher CoroutineDispatcher
 * @param isCustomSized Whether the preview is currently resized to custom dimensions, see [ResizePanel.isCustomSized]. Used to update the
 *   root tag's layout dimensions, see [layoutSize].
 */
class ConfigurationResizeListener(
  private val sceneManager: LayoutlibSceneManager,
  private val configuration: Configuration,
  defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val isCustomSized: () -> Boolean = { false },
) : ConfigurationListener, CheckedDisposable {
  private val logger = Logger.getInstance(ConfigurationResizeListener::class.java)

  private val scope = CoroutineScope(defaultDispatcher + SupervisorJob() + CoroutineName(javaClass.simpleName))

  private val deviceSizeChangedFlow = MutableSharedFlow<DeviceSize>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  init {
    if (Disposer.tryRegister(sceneManager, this)) {
      scope.launch {
        // A refresh builds the preview XML before ResizePanel.applyOverrides runs, and applyOverrides can drop the resize (e.g. when the
        // @Preview device is changed in code). The XML then has the layout size of the dropped resize and no configuration change follows,
        // so check the root tag once when the listener is attached after the refresh and re-render if it had to be fixed.
        try {
          requestRender(configuration.deviceSizePx().let { Dimension(it.width, it.height) }, onlyIfLayoutSizeChanged = true)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.warn("Error while syncing the preview layout size: ", e)
        }
      }
      scope.launch {
        deviceSizeChangedFlow.collectLatest { (width, height) ->
          try {
            requestRender(Dimension(width, height))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            logger.warn("Error inside requestRender: ", e)
          }
        }
      }
    } else {
      // The sceneManager is already disposed, so this listener would never be disposed with it. Dispose it right away so that it is not
      // attached to the configuration, see [isDisposed].
      Disposer.dispose(this)
    }
  }

  /**
   * Whether this listener is disposed, e.g. because its [sceneManager] was already disposed when it was created. A disposed listener must
   * not be added to the [configuration].
   */
  override fun isDisposed(): Boolean = !scope.isActive

  override fun changed(changeType: Int): Boolean {
    if (changeType and ConfigurationListener.CFG_DEVICE != 0) {
      deviceSizeChangedFlow.tryEmit(configuration.deviceSizePx())
    }
    return true
  }

  /**
   * Re-inflates and renders the preview with [newDeviceSize], after updating the root tag's layout size.
   *
   * @param onlyIfLayoutSizeChanged if true, the preview is only rendered when the root tag's layout size had to be updated.
   */
  private suspend fun requestRender(newDeviceSize: Dimension, onlyIfLayoutSizeChanged: Boolean = false) {
    // If the preview has never been rendered there is nothing showing the old size, and its first render will already use the new
    // configuration, so don't trigger a render from here.
    if (sceneManager.viewObject == null && sceneManager.renderResult == null) return

    val layoutSizeChanged = updateLayoutDimensionsIfNeeded()
    if (onlyIfLayoutSizeChanged && !layoutSizeChanged) return
    sceneManager.sceneRenderConfiguration.needsInflation?.set(true)
    sceneManager.requestRenderWithNewSize(newDeviceSize.width, newDeviceSize.height)

    val surface = sceneManager.designSurface

    if (!surface.isCanvasResizing) {
      // After a resize, we might need to rescale the canvas to fit the new content. This is only
      // done when the user is not resizing the canvas by dragging, to avoid abrupt zoom changes.
      withContext(Dispatchers.EDT) {
        val requiredScale = surface.zoomController.getFitScale()
        val currentScale = surface.zoomController.scale
        if (
          requiredScale > 0.0 &&
            currentScale > 0.0 &&
            !requiredScale.isNaN() &&
            !requiredScale.isInfinite() &&
            (currentScale > requiredScale * ZOOM_TO_FIT_RESCALE_THRESHOLD || // Preview is more than double the viewport
              requiredScale > currentScale * ZOOM_TO_FIT_RESCALE_THRESHOLD) // Preview is too small
        ) {
          // Since the preview has been resized, we reset the zoom-to-fit settings to reopen the mask
          // without waiting for another design surface layout creation or resize event.
          surface.zoomController.resetZoomToFitSettings(shouldWaitForResize = false, shouldWaitForLayoutCreated = false)
          surface.zoomController.zoomToFit()
        }
      }
    }
  }

  /**
   * Sets the root tag's `layout_width` and `layout_height` to [layoutSize] for the current resize state.
   *
   * @return true if the root tag was updated.
   */
  private suspend fun updateLayoutDimensionsIfNeeded(): Boolean {
    val model = sceneManager.model ?: return false

    return withContext(Dispatchers.EDT) {
      val (layoutWidth, layoutHeight) =
        runReadAction {
          val file = model.file
          if (!file.isValid) return@runReadAction null
          val previewElement = model.dataProvider?.previewElement() ?: return@runReadAction null
          val layoutSize = previewElement.layoutSize(isCustomSized())
          layoutSize.takeUnless {
            val rootTag = file.rootTag ?: return@runReadAction null
            rootTag.getAttributeValue(ATTR_LAYOUT_WIDTH, ANDROID_URI) == layoutSize.first &&
              rootTag.getAttributeValue(ATTR_LAYOUT_HEIGHT, ANDROID_URI) == layoutSize.second
          }
        } ?: return@withContext false
      var updated = false
      // Undo-transparent so these internal preview XML changes don't end up in the user's undo history.
      CommandProcessor.getInstance().runUndoTransparentAction {
        runWriteAction {
          // Re-check the tag, since the PSI may have changed between the read and the write action.
          val rootTag = model.file.takeIf { it.isValid }?.rootTag?.takeIf { it.isValid } ?: return@runWriteAction
          rootTag.setAttribute(ATTR_LAYOUT_WIDTH, ANDROID_URI, layoutWidth)
          rootTag.setAttribute(ATTR_LAYOUT_HEIGHT, ANDROID_URI, layoutHeight)
          updated = true
        }
      }
      updated
    }
  }

  override fun dispose() {
    scope.cancel()
    configuration.removeListener(this)
  }
}
