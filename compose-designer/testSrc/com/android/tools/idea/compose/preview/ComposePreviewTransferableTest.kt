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

import com.android.ide.common.rendering.api.RecyclableImage
import com.android.tools.idea.common.model.NlDataProvider
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.GuiInputHandler
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.android.tools.idea.preview.modes.UiCheckInstance
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.rendering.RenderResult
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.Painter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.util.ui.ImageUtil
import java.awt.Color
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragGestureEvent
import java.awt.dnd.DragGestureRecognizer
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceContext
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragSourceDropEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ComposePreviewTransferableTest {

  private lateinit var surface: DesignSurface<LayoutlibSceneManager>
  private lateinit var guiInputHandler: GuiInputHandler
  private lateinit var sceneView: SceneView
  private lateinit var sceneManager: LayoutlibSceneManager
  private lateinit var model: NlModel
  private lateinit var modeFlow: MutableStateFlow<PreviewMode>
  private lateinit var previewElement: PsiComposePreviewElementInstance

  @Before
  fun setUp() {
    surface = mock()
    guiInputHandler = mock()
    sceneView = mock()
    sceneManager = mock()
    model = mock()

    previewElement =
      SingleComposePreviewElementInstance(
        methodFqn = "com.example.MyPreviewsKt.MyPreview",
        displaySettings =
          PreviewDisplaySettings(
            name = "MyPreview",
            baseName = "MyPreview",
            parameterName = null,
            group = null,
            showDecoration = false,
            background = PreviewDisplaySettings.Background.None,
            organizationGroup = "com.example.MyPreviewsKt.MyPreview",
          ),
        previewElementDefinition = null,
        previewBody = null,
        configuration = PreviewConfiguration.cleanAndGet(),
      )

    modeFlow = MutableStateFlow(PreviewMode.Default())
    val modeManager = mock<PreviewModeManager>()
    whenever(modeManager.mode).thenReturn(modeFlow)

    val dataProvider =
      object : NlDataProvider(PreviewModeManager.KEY, PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? =
          when (dataId) {
            PreviewModeManager.KEY.name -> modeManager
            PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name -> previewElement
            else -> null
          }
      }

    whenever(surface.guiInputHandler).thenReturn(guiInputHandler)
    whenever(guiInputHandler.isPanning).thenReturn(false)
    whenever(guiInputHandler.isInteractionInProgress).thenReturn(false)
    whenever(surface.getSceneViewAt(10, 20)).thenReturn(sceneView)
    whenever(sceneView.sceneManager).thenReturn(sceneManager)
    whenever(sceneManager.model).thenReturn(model)
    whenever(model.dataProvider).thenReturn(dataProvider)
  }

  @Test
  fun dataFlavor_isJvmLocalObjectRef_notSerializedObject() {
    assertThat(ComposePreviewDragData.DATA_FLAVOR.isFlavorSerializedObjectType).isFalse()
    assertThat(ComposePreviewDragData.DATA_FLAVOR.representationClass).isEqualTo(ComposePreviewDragData::class.java)
  }

  @Test
  fun transferable_returnsDragDataAndRejectsUnsupportedFlavor() {
    val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
    val dragData = ComposePreviewDragData(previewElement, image)
    val transferable = ComposePreviewTransferable(dragData)

    assertThat(transferable.transferDataFlavors).asList().containsExactly(ComposePreviewDragData.DATA_FLAVOR)
    assertThat(transferable.isDataFlavorSupported(ComposePreviewDragData.DATA_FLAVOR)).isTrue()
    assertThat(transferable.isDataFlavorSupported(DataFlavor.stringFlavor)).isFalse()
    assertThat(transferable.getTransferData(ComposePreviewDragData.DATA_FLAVOR)).isSameAs(dragData)
    assertThrows(UnsupportedFlavorException::class.java) {
      transferable.getTransferData(DataFlavor.stringFlavor)
    }
  }

  @Test
  fun canStartDragging_allowedInStaticPreviewModesOnly() {
    val handler = ComposePreviewDragHandler(surface)
    val origin = Point(10, 20)

    modeFlow.value = PreviewMode.Default()
    assertThat(handler.canStartDragging(origin)).isTrue()

    modeFlow.value = PreviewMode.Focus(previewElement)
    assertThat(handler.canStartDragging(origin)).isTrue()

    modeFlow.value = PreviewMode.UiCheck(UiCheckInstance(previewElement, isWearPreview = false))
    assertThat(handler.canStartDragging(origin)).isTrue()

    modeFlow.value = PreviewMode.Interactive(previewElement)
    assertThat(handler.canStartDragging(origin)).isFalse()

    modeFlow.value = PreviewMode.AnimationInspection(previewElement)
    assertThat(handler.canStartDragging(origin)).isFalse()
  }

  @Test
  fun canStartDragging_blockedDuringPanningResizeOrOutsideSceneView() {
    val handler = ComposePreviewDragHandler(surface)
    val origin = Point(10, 20)

    assertThat(handler.canStartDragging(Point(500, 500))).isFalse()

    whenever(guiInputHandler.isPanning).thenReturn(true)
    assertThat(handler.canStartDragging(origin)).isFalse()

    whenever(guiInputHandler.isPanning).thenReturn(false)
    whenever(guiInputHandler.isInteractionInProgress).thenReturn(true)
    assertThat(handler.canStartDragging(origin)).isFalse()
  }

  @Test
  fun canStartDragging_withExplicitTargetSceneView_resolvesFromLabelRegardlessOfCoordinates() {
    val labelHandler = ComposePreviewDragHandler(surface, targetSceneView = sceneView)
    assertThat(labelHandler.canStartDragging(Point(2, 2))).isTrue()
  }

  @Test
  fun createDragData_andCreateDragThumbnail_packagesPreviewAndScalesThumbnail() {
    val rendered = BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB)
    val recyclableImage = mock<RecyclableImage>()
    whenever(recyclableImage.copy).thenReturn(rendered)
    val renderResult = mock<RenderResult>()
    whenever(renderResult.renderedImage).thenReturn(recyclableImage)
    whenever(sceneManager.renderResult).thenReturn(renderResult)

    val handler = ComposePreviewDragHandler(surface)
    val dragData = requireNotNull(handler.createDragData(Point(10, 20)))

    assertThat(dragData.previewElement).isSameAs(previewElement)
    assertThat(dragData.renderedImage).isSameAs(rendered)

    val thumbnail = requireNotNull(createDragThumbnail(rendered))
    assertThat(ImageUtil.getUserWidth(thumbnail)).isEqualTo(160)
    assertThat(ImageUtil.getUserHeight(thumbnail)).isEqualTo(80)
  }

  @Test
  fun createDragData_withoutRenderedImage_stillAttachesPreviewElement() {
    whenever(sceneManager.renderResult).thenReturn(null)
    val handler = ComposePreviewDragHandler(surface)
    val dragData = requireNotNull(handler.createDragData(Point(10, 20)))

    assertThat(dragData.previewElement).isSameAs(previewElement)
    assertThat(dragData.renderedImage).isNull()
  }

  @Test
  fun dragGestureRecognized_startsDragWithCopyCursorOnPrimaryButton_andIgnoresSecondaryButton() {
    whenever(sceneManager.renderResult).thenReturn(null)
    val panel = JPanel()
    val handler = ComposePreviewDragHandler(surface)

    val rightClickTrigger =
      MouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 0L, InputEvent.BUTTON3_DOWN_MASK, 10, 20, 1, false, MouseEvent.BUTTON3)
    val rightDragEvent = mock<DragGestureEvent>()
    whenever(rightDragEvent.triggerEvent).thenReturn(rightClickTrigger)
    handler.dragGestureRecognized(rightDragEvent)
    verify(rightDragEvent, never()).startDrag(anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull())

    val leftClickTrigger =
      MouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 0L, InputEvent.BUTTON1_DOWN_MASK, 10, 20, 1, false, MouseEvent.BUTTON1)
    val leftDragEvent = mock<DragGestureEvent>()
    whenever(leftDragEvent.triggerEvent).thenReturn(leftClickTrigger)
    whenever(leftDragEvent.dragOrigin).thenReturn(Point(10, 20))
    handler.dragGestureRecognized(leftDragEvent)

    val transferableCaptor = argumentCaptor<Transferable>()
    verify(leftDragEvent).startDrag(eq(DragSource.DefaultCopyDrop), anyOrNull(), anyOrNull(), transferableCaptor.capture(), eq(null))
    val dragData = transferableCaptor.firstValue.getTransferData(ComposePreviewDragData.DATA_FLAVOR) as ComposePreviewDragData
    assertThat(dragData.previewElement).isSameAs(previewElement)
  }

  @Test
  fun installComposePreviewDnDSource_detachesRecognizerOnScopeCancelOrParentDispose() {
    val parentDisposable = Disposer.newDisposable("installDnDTest")
    try {
      val surfacePanel = JPanel()
      val labelPanel = JPanel()
      val surfaceRecognizer = mock<DragGestureRecognizer>()
      val labelRecognizer = mock<DragGestureRecognizer>()
      val dragSource = mock<DragSource>()
      whenever(
          dragSource.createDefaultDragGestureRecognizer(
            eq(surfacePanel),
            eq(DnDConstants.ACTION_COPY_OR_MOVE),
            any(),
          )
        )
        .thenReturn(surfaceRecognizer)
      whenever(
          dragSource.createDefaultDragGestureRecognizer(
            eq(labelPanel),
            eq(DnDConstants.ACTION_COPY_OR_MOVE),
            any(),
          )
        )
        .thenReturn(labelRecognizer)

      val cancelledScope = CoroutineScope(SupervisorJob()).apply { cancel() }
      installComposePreviewDnDSource(
        component = labelPanel,
        surface = surface,
        parentDisposable = parentDisposable,
        targetSceneView = sceneView,
        scope = cancelledScope,
        dragSourceProvider = { dragSource },
      )
      verify(dragSource, never()).createDefaultDragGestureRecognizer(eq(labelPanel), any(), any())

      val labelScope = CoroutineScope(SupervisorJob())
      installComposePreviewDnDSource(
        component = labelPanel,
        surface = surface,
        parentDisposable = parentDisposable,
        targetSceneView = sceneView,
        scope = labelScope,
        dragSourceProvider = { dragSource },
      )
      installComposePreviewDnDSource(
        component = surfacePanel,
        surface = surface,
        parentDisposable = parentDisposable,
        dragSourceProvider = { dragSource },
      )

      verify(labelRecognizer, never()).component = null
      verify(surfaceRecognizer, never()).component = null

      // Cancelling the transient label scope detaches the label recognizer immediately without waiting for parentDisposable.
      labelScope.cancel()
      verify(labelRecognizer).component = null
      verify(surfaceRecognizer, never()).component = null

      Disposer.dispose(parentDisposable)
      verify(surfaceRecognizer).component = null
    } finally {
      if (!Disposer.isDisposed(parentDisposable)) {
        Disposer.dispose(parentDisposable)
      }
    }
  }

  @Test
  fun glassPaneDragImage_paintsThumbnailFollowingPointer_andCleansUpOnDragDropEnd() {
    val rootDisposable = Disposer.newDisposable("glassPaneDragImageTest")
    try {
      val sourcePanel = JPanel()
      val otherPanel = JPanel()
      val dragSource = mock<DragSource>()
      val glassComponent = mock<JPanel>(extraInterfaces = arrayOf(IdeGlassPane::class))
      val glassPane = glassComponent as IdeGlassPane
      whenever(glassComponent.isShowing).thenReturn(true)
      whenever(glassComponent.x).thenReturn(100)
      whenever(glassComponent.y).thenReturn(200)

      val thumbnail =
        ImageUtil.createImage(20, 16, BufferedImage.TYPE_INT_ARGB).apply {
          val g = createGraphics()
          g.color = Color.RED
          g.fillRect(0, 0, 20, 16)
          g.dispose()
        }

      val session =
        ComposePreviewGlassPaneDragImage(
          sourceComponent = sourcePanel,
          parentDisposable = rootDisposable,
          dragImage = thumbnail,
          dragSource = dragSource,
          glassPane = glassPane,
          initialScreenLocation = Point(120, 230),
        )

      verify(dragSource).addDragSourceListener(session.dragSourceAdapter)
      verify(dragSource).addDragSourceMotionListener(session.dragSourceAdapter)
      val painterCaptor = argumentCaptor<Painter>()
      verify(glassPane).addPainter(anyOrNull(), painterCaptor.capture(), any())
      // Must not mutate IdeGlassPane mouse preprocessors inside dragGestureRecognized (causes ConcurrentModificationException).
      verify(glassPane, never()).addMousePreprocessor(any(), any())
      verify(glassPane, never()).addMouseMotionPreprocessor(any(), any())

      val painter = painterCaptor.firstValue
      // Seeded immediately from initialScreenLocation on frame 1 before any motion events arrive.
      assertThat(painter.needsRepaint()).isTrue()

      // Unrelated component drags must not move or end our session.
      val otherContext = mock<DragSourceContext>()
      whenever(otherContext.component).thenReturn(otherPanel)
      session.dragSourceAdapter.dragDropEnd(DragSourceDropEvent(otherContext))
      assertThat(painter.needsRepaint()).isTrue()

      val ownContext = mock<DragSourceContext>()
      whenever(ownContext.component).thenReturn(sourcePanel)
      session.dragSourceAdapter.dragMouseMoved(DragSourceDragEvent(ownContext, DnDConstants.ACTION_COPY, 0, 0, 150, 260))
      assertThat(painter.needsRepaint()).isTrue()

      val canvas = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
      val g2d = canvas.createGraphics()
      try {
        painter.paint(glassComponent, g2d)
      } finally {
        g2d.dispose()
      }
      // Pointer is at (50, 60) in glassPane coords; with offset >= 12px, pixel (70, 80) is inside the 20x16 red thumbnail.
      assertThat(Color(canvas.getRGB(70, 80), true).red).isEqualTo(255)

      session.dragSourceAdapter.dragDropEnd(DragSourceDropEvent(ownContext))
      verify(dragSource).removeDragSourceListener(session.dragSourceAdapter)
      verify(dragSource).removeDragSourceMotionListener(session.dragSourceAdapter)
      assertThat(painter.needsRepaint()).isFalse()
    } finally {
      Disposer.dispose(rootDisposable)
    }
  }
}
