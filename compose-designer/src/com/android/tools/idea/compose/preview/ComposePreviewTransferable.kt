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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.AbstractPainter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.MouseDragHelper
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.MultiResolutionImageProvider
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragGestureEvent
import java.awt.dnd.DragGestureListener
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceAdapter
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragSourceDropEvent
import java.awt.dnd.InvalidDnDOperationException
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.job

private const val MAX_DRAG_THUMBNAIL_SIZE_PX = 160
private const val DRAG_THUMBNAIL_ALPHA = 0.75f
private const val DRAG_THUMBNAIL_CURSOR_OFFSET_DP = 12

/**
 * Intra-JVM drag payload representing a Compose Preview dragged from the preview surface.
 *
 * @property previewElement The [PsiComposePreviewElementInstance] being dragged.
 * @property renderedImage A copy of the preview's current rendered image, or `null` if unavailable.
 */
data class ComposePreviewDragData(
  val previewElement: PsiComposePreviewElementInstance,
  val renderedImage: BufferedImage?,
) {
  companion object {
    /**
     * Uses [DataFlavor.javaJVMLocalObjectMimeType] so AWT's `TransferableProxy` passes the live object reference directly within the JVM
     * without attempting `Serializable` round-tripping.
     */
    @JvmField
    val DATA_FLAVOR: DataFlavor =
      DataFlavor(
        "${DataFlavor.javaJVMLocalObjectMimeType};class=${ComposePreviewDragData::class.java.name}",
        "Compose Preview",
        ComposePreviewDragData::class.java.classLoader,
      )
  }
}

/** [Transferable] wrapper for [ComposePreviewDragData]. */
class ComposePreviewTransferable(private val data: ComposePreviewDragData) : Transferable {
  override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(ComposePreviewDragData.DATA_FLAVOR)

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = ComposePreviewDragData.DATA_FLAVOR == flavor

  override fun getTransferData(flavor: DataFlavor): Any {
    if (!isDataFlavorSupported(flavor)) {
      throw UnsupportedFlavorException(flavor)
    }
    return data
  }
}

/**
 * Registers an AWT drag gesture recognizer on [component] when running in a graphical environment.
 *
 * Uses [DragSource] directly (rather than IntelliJ's `DnDManager.registerSource`) with an explicit [DragSource.DefaultCopyDrop] initial
 * cursor so AWT keeps `DragSourceContext.useCustomCursor = true` across the entire IDE window without `DnDManagerImpl` resetting the cursor
 * to a reject cursor over non-drop chrome.
 *
 * When [targetSceneView] is `null`, the dragged preview is resolved from the drag origin on [surface]; when non-null (for example on a
 * preview's header label), [targetSceneView] is used directly.
 *
 * When [scope] is provided (for example on a transient `SceneViewPeerPanel` header label whose `CoroutineScope` cancels before
 * [parentDisposable] is disposed), the recognizer is detached as soon as either [scope] completes or [parentDisposable] is disposed.
 */
internal fun installComposePreviewDnDSource(
  component: JComponent,
  surface: DesignSurface<LayoutlibSceneManager>,
  parentDisposable: Disposable,
  targetSceneView: SceneView? = null,
  scope: CoroutineScope? = null,
  dragSourceProvider: () -> DragSource? = ::defaultAwtDragSource,
) {
  if (Disposer.isDisposed(parentDisposable)) return
  val effectiveDisposable =
    if (scope != null) {
      createScopeBoundDisposable(parentDisposable, scope) ?: return
    } else {
      parentDisposable
    }
  val dragSource =
    dragSourceProvider()
      ?: run {
        if (effectiveDisposable !== parentDisposable) {
          Disposer.dispose(effectiveDisposable)
        }
        return
      }
  val handler =
    ComposePreviewDragHandler(
      surface = surface,
      targetSceneView = targetSceneView,
      sourceComponent = component,
      parentDisposable = effectiveDisposable,
    )
  val recognizer =
    dragSource.createDefaultDragGestureRecognizer(
      component,
      DnDConstants.ACTION_COPY_OR_MOVE,
      handler,
    )
  Disposer.register(effectiveDisposable) {
    recognizer?.component = null
    handler.dispose()
  }
}

private fun defaultAwtDragSource(): DragSource? = if (GraphicsEnvironment.isHeadless()) null else DragSource.getDefaultDragSource()

private fun createScopeBoundDisposable(parentDisposable: Disposable, scope: CoroutineScope): Disposable? {
  val job = scope.coroutineContext.job
  if (!job.isActive) return null
  val childDisposable = Disposer.newDisposable(parentDisposable, "ComposePreviewDnDScope")
  var completionHandle: DisposableHandle? = null
  Disposer.register(childDisposable) { completionHandle?.dispose() }
  completionHandle = job.invokeOnCompletion { Disposer.dispose(childDisposable) }
  if (Disposer.isDisposed(childDisposable)) {
    completionHandle.dispose()
    return null
  }
  return childDisposable
}

/**
 * Initiates AWT drags for Compose Previews from static preview modes (`Default`, `Focus`, and `UiCheck`) without interfering with panning,
 * Focus-mode resize handles, Interactive mode, or Animation Inspection mode.
 */
internal class ComposePreviewDragHandler(
  private val surface: DesignSurface<LayoutlibSceneManager>,
  private val targetSceneView: SceneView? = null,
  private val sourceComponent: JComponent? = null,
  private val parentDisposable: Disposable? = null,
) : DragGestureListener {

  private var activeGlassPaneDragImage: ComposePreviewGlassPaneDragImage? = null

  private fun resolveSceneView(dragOrigin: Point): SceneView? = targetSceneView ?: surface.getSceneViewAt(dragOrigin.x, dragOrigin.y)

  internal fun canStartDragging(dragOrigin: Point): Boolean {
    if (surface.guiInputHandler.isPanning || surface.guiInputHandler.isInteractionInProgress) {
      return false
    }
    val sceneView = resolveSceneView(dragOrigin) ?: return false
    val dataProvider = sceneView.sceneManager.model.dataProvider ?: return false
    val mode = dataProvider.getData(PreviewModeManager.KEY)?.mode?.value
    if (!mode.isStaticPreviewMode()) {
      return false
    }
    return dataProvider.getData(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) != null
  }

  internal fun createDragData(dragOrigin: Point): ComposePreviewDragData? {
    val sceneView = resolveSceneView(dragOrigin) ?: return null
    val previewElement = sceneView.sceneManager.model.dataProvider?.getData(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) ?: return null
    val renderedImage = (sceneView.sceneManager as? LayoutlibSceneManager)?.renderResult?.renderedImage?.copy
    return ComposePreviewDragData(previewElement, renderedImage)
  }

  override fun dragGestureRecognized(event: DragGestureEvent) {
    val triggerMouseEvent = event.triggerEvent as? MouseEvent
    if (triggerMouseEvent != null && !SwingUtilities.isLeftMouseButton(triggerMouseEvent)) return
    if (ApplicationManager.getApplication() != null && !MouseDragHelper.checkModifiers(event.triggerEvent)) return
    if (!canStartDragging(event.dragOrigin)) return
    val dragData = createDragData(event.dragOrigin) ?: return
    val transferable = ComposePreviewTransferable(dragData)
    val thumbnail = dragData.renderedImage?.let(::createDragThumbnail)
    val nativeDragImageSupported = DragSource.isDragImageSupported()
    val nativeDragImage =
      if (nativeDragImageSupported && thumbnail != null) {
        if (SystemInfo.isMac) MultiResolutionImageProvider.convertFromJBImage(thumbnail) ?: thumbnail else thumbnail
      } else {
        null
      }

    dispose()
    if (!nativeDragImageSupported && thumbnail != null && sourceComponent != null && parentDisposable != null) {
      val initialScreenLocation =
        if (sourceComponent.isShowing) {
          Point(event.dragOrigin).also { SwingUtilities.convertPointToScreen(it, sourceComponent) }
        } else {
          null
        }
      activeGlassPaneDragImage =
        ComposePreviewGlassPaneDragImage(
          sourceComponent = sourceComponent,
          parentDisposable = parentDisposable,
          dragImage = thumbnail,
          dragSource = event.dragSource,
          initialScreenLocation = initialScreenLocation,
        )
    }

    try {
      event.startDrag(
        DragSource.DefaultCopyDrop,
        nativeDragImage,
        if (nativeDragImage != null) Point(0, 0) else null,
        transferable,
        null,
      )
    } catch (e: InvalidDnDOperationException) {
      dispose()
      thisLogger().info(e)
    }
  }

  fun dispose() {
    activeGlassPaneDragImage?.dispose()
    activeGlassPaneDragImage = null
  }
}

internal fun createDragThumbnail(image: BufferedImage): BufferedImage? {
  if (image.width <= 0 || image.height <= 0) return null
  val scale = minOf(1.0, MAX_DRAG_THUMBNAIL_SIZE_PX.toDouble() / maxOf(image.width, image.height))
  val thumbWidth = maxOf(1, (image.width * scale).roundToInt())
  val thumbHeight = maxOf(1, (image.height * scale).roundToInt())
  val thumbnail = ImageUtil.createImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_ARGB)
  val graphics = thumbnail.createGraphics()
  try {
    graphics.composite = AlphaComposite.SrcOver.derive(DRAG_THUMBNAIL_ALPHA)
    graphics.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    graphics.drawImage(image, 0, 0, thumbWidth, thumbHeight, null)
  } finally {
    graphics.dispose()
  }
  return thumbnail
}

/**
 * Paints [dragImage] on the window's [IdeGlassPane] following the pointer until the drag ends on platforms where native AWT drag images are
 * unsupported (such as X11 on Linux, where `DragSource.isDragImageSupported()` is `false`).
 *
 * Self-contained: registers and unregisters both [DragSource.addDragSourceListener] (for `dragDropEnd` cleanup) and
 * [DragSource.addDragSourceMotionListener] (for pointer tracking) directly on [dragSource] so callers do not need to thread listeners into
 * `DragGestureEvent.startDrag`.
 */
internal class ComposePreviewGlassPaneDragImage(
  private val sourceComponent: Component,
  parentDisposable: Disposable,
  private val dragImage: Image,
  private val dragSource: DragSource? = defaultAwtDragSource(),
  private val glassPane: IdeGlassPane? = runCatching { IdeGlassPaneUtil.find(sourceComponent) }.getOrNull(),
  initialScreenLocation: Point? = null,
) {
  private val sessionDisposable: Disposable = Disposer.newDisposable(parentDisposable, "ComposePreviewGlassPaneDragImage")
  private val glassComponent: Component? = glassPane as? Component
  private var currentImageBounds: Rectangle? = null
  @Volatile private var disposed: Boolean = false

  internal val dragSourceAdapter: DragSourceAdapter =
    object : DragSourceAdapter() {
      override fun dragEnter(event: DragSourceDragEvent) = onDragMotion(event)

      override fun dragOver(event: DragSourceDragEvent) = onDragMotion(event)

      override fun dragMouseMoved(event: DragSourceDragEvent) = onDragMotion(event)

      override fun dragDropEnd(event: DragSourceDropEvent) {
        if (event.dragSourceContext.component === sourceComponent) {
          dispose()
        }
      }
    }

  private val painter: AbstractPainter? =
    if (glassPane != null) {
      object : AbstractPainter() {
        override fun needsRepaint(): Boolean = !disposed && currentImageBounds != null

        override fun executePaint(container: Component, g: Graphics2D) {
          val bounds = currentImageBounds ?: return
          if (disposed) return
          UIUtil.drawImage(g, dragImage, bounds.x, bounds.y, null)
        }
      }
    } else {
      null
    }

  init {
    dragSource?.addDragSourceListener(dragSourceAdapter)
    dragSource?.addDragSourceMotionListener(dragSourceAdapter)
    if (painter != null && glassPane != null) {
      glassPane.addPainter(null, painter, sessionDisposable)
    }
    Disposer.register(sessionDisposable) {
      disposed = true
      dragSource?.removeDragSourceListener(dragSourceAdapter)
      dragSource?.removeDragSourceMotionListener(dragSourceAdapter)
      clearPaintedBounds()
    }
    if (initialScreenLocation != null) {
      updateDragImageScreenLocation(initialScreenLocation)
    }
  }

  private fun onDragMotion(event: DragSourceDragEvent) {
    if (disposed || event.dragSourceContext.component !== sourceComponent) return
    event.location?.let(::updateDragImageScreenLocation)
  }

  internal fun updateDragImageScreenLocation(screenLocation: Point) {
    val targetComponent = glassComponent ?: return
    if (disposed || !targetComponent.isShowing) return

    val width = ImageUtil.getUserWidth(dragImage)
    val height = ImageUtil.getUserHeight(dragImage)
    if (width <= 0 || height <= 0) return

    val localPoint = Point(screenLocation)
    SwingUtilities.convertPointFromScreen(localPoint, targetComponent)
    val offset = JBUI.scale(DRAG_THUMBNAIL_CURSOR_OFFSET_DP)
    val newBounds = Rectangle(localPoint.x + offset, localPoint.y + offset, width, height)
    val oldBounds = currentImageBounds
    if (newBounds == oldBounds) return

    currentImageBounds = newBounds
    val dirty = if (oldBounds != null) oldBounds.union(newBounds) else newBounds
    targetComponent.repaint(dirty.x - 2, dirty.y - 2, dirty.width + 4, dirty.height + 4)
  }

  private fun clearPaintedBounds() {
    val oldBounds = currentImageBounds ?: return
    currentImageBounds = null
    glassComponent?.repaint(oldBounds.x - 2, oldBounds.y - 2, oldBounds.width + 4, oldBounds.height + 4)
  }

  fun dispose() {
    if (!disposed) {
      Disposer.dispose(sessionDisposable)
    }
  }
}

/**
 * Returns `true` when preview drag-and-drop should be active: static preview modes (`Default`, `Focus`, `UiCheck`) or surfaces that do not
 * install a [PreviewModeManager] (`null`), while suppressing drags during live user input modes (`Interactive`, `AnimationInspection`).
 */
private fun PreviewMode?.isStaticPreviewMode(): Boolean = this == null || isNormal || this is PreviewMode.UiCheck
