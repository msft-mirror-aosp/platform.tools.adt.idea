/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.scene

import android.view.ViewGroup
import com.android.SdkConstants.FD_RES_LAYOUT
import com.android.SdkConstants.FD_RES_MENU
import com.android.SdkConstants.FD_RES_XML
import com.android.SdkConstants.FRAME_LAYOUT
import com.android.SdkConstants.LINEAR_LAYOUT
import com.android.SdkConstants.PreferenceTags.PREFERENCE_SCREEN
import com.android.SdkConstants.TAG_MENU
import com.android.SdkConstants.VIEW
import com.android.tools.idea.common.fixtures.ModelBuilder
import com.android.tools.idea.common.type.DesignerTypeRegistrar
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.idea.uibuilder.surface.NlScreenViewProvider
import com.android.tools.idea.uibuilder.type.MenuFileType
import com.android.tools.idea.uibuilder.type.PreferenceScreenFileType
import com.android.tools.rendering.RenderService
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.utils.editor.saveToDisk
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.mockito.kotlin.whenever

class LayoutlibSceneManagerTest : SceneTest() {

  private lateinit var myLayoutlibSceneManager: LayoutlibSceneManager

  override fun setUp() {
    // we register it manually here in the tests context, but in production it should be handled by
    // NlEditorProvider
    DesignerTypeRegistrar.register(PreferenceScreenFileType)
    DesignerTypeRegistrar.register(MenuFileType)
    super.setUp()
    myLayoutlibSceneManager = (myScene.designSurface as NlDesignSurface).sceneManagers.first()
  }

  override fun tearDown() {
    super.tearDown()
    DesignerTypeRegistrar.clearRegisteredTypes()
  }

  fun testSceneModeWithPreferenceFile() {
    // Regression test for b/122673792
    val nlSurface = myScene.designSurface as NlDesignSurface

    whenever(nlSurface.screenViewProvider).thenReturn(NlScreenViewProvider.RENDER)
    myLayoutlibSceneManager.updateSceneViews()
    assertEquals(1, myLayoutlibSceneManager.sceneViews.size)

    whenever(nlSurface.screenViewProvider).thenReturn(NlScreenViewProvider.BLUEPRINT)
    myLayoutlibSceneManager.updateSceneViews()
    assertEquals(1, myLayoutlibSceneManager.sceneViews.size)

    whenever(nlSurface.screenViewProvider).thenReturn(NlScreenViewProvider.RENDER_AND_BLUEPRINT)
    myLayoutlibSceneManager.updateSceneViews()
    // Secondary scene view should be present now
    assertEquals(2, myLayoutlibSceneManager.sceneViews.size)
  }

  fun testDoNotCacheSuccessfulRenderImage() = runBlocking {
    myLayoutlibSceneManager.sceneRenderConfiguration.cacheSuccessfulRenderImage = false
    myLayoutlibSceneManager.requestRenderAndWait()
    myLayoutlibSceneManager.sceneRenderConfiguration.needsInflation.set(true)
    myLayoutlibSceneManager.renderResult!!.let {
      assertTrue(it.renderResult.isSuccess)
      assertTrue(it.renderedImage.isValid)
      assertEquals(768, it.rootViewDimensions.width)
      assertEquals(1280, it.rootViewDimensions.height)
    }

    // Break the XML, the next render will fail but will retain the image and dimensions
    runWriteActionAndWait {
      val manager = PsiDocumentManager.getInstance(project)
      val document = manager.getDocument(myLayoutlibSceneManager.model.file)!!
      document.setText("<broken />")
      manager.commitAllDocuments()
      // We need to save to disk here because below we call requestRenderAndWait blocking the UI
      // thread while waiting for it to finish, and if this file is not saved, then the inflation
      // pre-process logic will try to save it, causing a deadlock with the blocking wait mentioned
      // above. Note that this should never happen in production as requestRenderAndWait should not
      // be called in a blocking manner from the UI thread.
      document.saveToDisk()
    }
    myLayoutlibSceneManager.requestRenderAndWait()
    myLayoutlibSceneManager.renderResult!!.let {
      assertFalse("broken render should have failed", it.renderResult.isSuccess)
      assertFalse("image should not be valid after the failed rener", it.renderedImage.isValid)
    }
  }

  fun testCacheSuccessfulRenderImage() = runBlocking {
    myLayoutlibSceneManager.sceneRenderConfiguration.cacheSuccessfulRenderImage = true
    myLayoutlibSceneManager.requestRenderAndWait()
    myLayoutlibSceneManager.sceneRenderConfiguration.needsInflation.set(true)
    myLayoutlibSceneManager.renderResult!!.let {
      assertTrue(it.renderResult.isSuccess)
      assertTrue(it.renderedImage.isValid)
      assertEquals(768, it.rootViewDimensions.width)
      assertEquals(1280, it.rootViewDimensions.height)
    }

    // Break the XML, the next render will fail but will retain the image and dimensions
    runWriteActionAndWait {
      val manager = PsiDocumentManager.getInstance(project)
      val document = manager.getDocument(myLayoutlibSceneManager.model.file)!!
      document.setText("<broken />")
      manager.commitAllDocuments()
      // We need to save to disk here because below we call requestRenderAndWait blocking the UI
      // thread while waiting for it to finish, and if this file is not saved, then the inflation
      // pre-process logic will try to save it, causing a deadlock with the blocking wait mentioned
      // above. Note that this should never happen in production as requestRenderAndWait should not
      // be called in a blocking manner from the UI thread.
      document.saveToDisk()
    }
    myLayoutlibSceneManager.requestRenderAndWait()
    myLayoutlibSceneManager.renderResult!!.let {
      assertFalse("broken render should have failed", it.renderResult.isSuccess)
      assertTrue(
        "image should be still valid because of a previous successful render",
        it.renderedImage.isValid,
      )
      assertEquals(768, it.rootViewDimensions.width)
      assertEquals(1280, it.rootViewDimensions.height)
    }
  }

  fun testRequestRenderWithNewSize() = runBlocking {
    val surface = myScene.designSurface as NlDesignSurface
    whenever(surface.screenViewProvider).thenReturn(NlScreenViewProvider.RENDER)
    myLayoutlibSceneManager.updateSceneViews()

    myLayoutlibSceneManager.requestRenderAndWait()

    val initialResult = myLayoutlibSceneManager.renderResult!!
    val initialWidth = initialResult.rootViewDimensions.width
    val initialHeight = initialResult.rootViewDimensions.height

    val newWidthDp = 200
    val newHeightDp = 300

    myLayoutlibSceneManager.requestRenderWithNewSize(newWidthDp, newHeightDp)

    // to ensure rendering is done
    myLayoutlibSceneManager.requestRenderAndWait()
    val result = myLayoutlibSceneManager.renderResult!!

    assertNotEquals(initialWidth, result.rootViewDimensions.width)
    assertNotEquals(initialHeight, result.rootViewDimensions.height)

    assertEquals(newWidthDp, result.rootViewDimensions.width)
    assertEquals(newHeightDp, result.rootViewDimensions.height)
  }

  override fun createModel(): ModelBuilder {
    return model(
      FD_RES_XML,
      "preference.xml",
      component(PREFERENCE_SCREEN).withBounds(0, 0, 1000, 1000).matchParentWidth().matchParentHeight(),
    )
  }

  fun testUpdateSceneViewsForMenu() {
    val menuModel =
      model(
          FD_RES_MENU,
          "menu.xml",
          component(TAG_MENU).withBounds(0, 0, 1000, 1000).matchParentWidth().matchParentHeight(),
        )
        .build()
    try {
      val sceneManager = menuModel.surface.getSceneManager(menuModel) as LayoutlibSceneManager

      // This should not throw ThreadingAssertions.createThreadAccessException
      sceneManager.updateSceneViews()

      assertEquals(1, sceneManager.sceneViews.size)
    } finally {
      Disposer.dispose(menuModel)
    }
  }

  fun testDynamicResizeInShrinkMode() = runBlocking {
    val shrinkModel =
      model(
          FD_RES_LAYOUT,
          "shrink_layout.xml",
          component(FRAME_LAYOUT)
            .withBounds(0, 0, 100, 100)
            .wrapContentWidth()
            .wrapContentHeight()
            .children(component(VIEW).withBounds(0, 0, 100, 100).width("100px").height("100px")),
        )
        .build()

    try {
      val sceneManager = shrinkModel.surface.getSceneManager(shrinkModel) as LayoutlibSceneManager
      sceneManager.sceneRenderConfiguration.useShrinkRendering = true
      sceneManager.requestRenderAndWait()

      val initialResult = sceneManager.renderResult!!
      assertEquals(100, initialResult.rootViewDimensions.width)
      assertEquals(100, initialResult.rootViewDimensions.height)

      // Dynamically grow the underlying android.view.View inside the render session (simulating an
      // animation frame)
      RenderService.getRenderAsyncActionExecutor()
        .runAsyncAction {
          val rootView = sceneManager.renderResult!!.rootViews.first().viewObject as ViewGroup
          val childView = rootView.getChildAt(0)
          childView.layoutParams.width = 240
          childView.layoutParams.height = 320
          childView.requestLayout()
        }
        .join()

      sceneManager.sceneRenderConfiguration.executeCallbacksAfterRender.set(true)
      sceneManager.sceneRenderConfiguration.doubleRender.set(true)
      sceneManager.requestRenderAndWait()
      UIUtil.dispatchAllInvocationEvents()

      val grownResult = sceneManager.renderResult!!
      assertEquals(240, grownResult.rootViewDimensions.width)
      assertEquals(320, grownResult.rootViewDimensions.height)

      // Dynamically shrink the underlying android.view.View inside the render session
      RenderService.getRenderAsyncActionExecutor()
        .runAsyncAction {
          val rootView = sceneManager.renderResult!!.rootViews.first().viewObject as ViewGroup
          val childView = rootView.getChildAt(0)
          childView.layoutParams.width = 60
          childView.layoutParams.height = 80
          childView.requestLayout()
        }
        .join()

      sceneManager.sceneRenderConfiguration.executeCallbacksAfterRender.set(true)
      sceneManager.sceneRenderConfiguration.doubleRender.set(true)
      sceneManager.requestRenderAndWait()
      UIUtil.dispatchAllInvocationEvents()

      val shrunkResult = sceneManager.renderResult!!
      assertEquals(60, shrunkResult.rootViewDimensions.width)
      assertEquals(80, shrunkResult.rootViewDimensions.height)
    } finally {
      Disposer.dispose(shrinkModel)
    }
  }

  fun testTriggerHoverEventUpdatesHoveredState() {
    val hoverModel =
      model(
          FD_RES_LAYOUT,
          "hover_layout.xml",
          component(FRAME_LAYOUT)
            .withBounds(0, 0, 200, 200)
            .matchParentWidth()
            .matchParentHeight()
            .children(component("Button").withBounds(0, 0, 200, 200).width("200dp").height("200dp")),
        )
        .build()

    try {
      val sceneManager = hoverModel.surface.getSceneManager(hoverModel) as LayoutlibSceneManager
      runBlocking { sceneManager.requestRenderAndWait() }

      val buttonView =
        RenderService.getRenderAsyncActionExecutor()
          .runAsyncAction<android.view.View> {
            val rootView = sceneManager.renderResult!!.rootViews.first().viewObject as ViewGroup
            rootView.getChildAt(0)
          }
          .join()

      assertFalse(buttonView.isHovered)

      sceneManager.triggerHoverEventAsync(android.view.MotionEvent.ACTION_HOVER_ENTER, 50, 50).join()
      assertTrue(buttonView.isHovered)

      sceneManager.triggerHoverEventAsync(android.view.MotionEvent.ACTION_HOVER_EXIT, -1, -1).join()
      assertFalse(buttonView.isHovered)
    } finally {
      Disposer.dispose(hoverModel)
    }
  }

  fun testTriggerKeyEventRestoresDefaultFocus() {
    val focusModel =
      model(
          FD_RES_LAYOUT,
          "focus_layout.xml",
          component(LINEAR_LAYOUT)
            .withBounds(0, 0, 200, 200)
            .matchParentWidth()
            .matchParentHeight()
            .withAttribute("android:orientation", "vertical")
            .children(
              component("Button")
                .withBounds(0, 0, 200, 100)
                .width("200dp")
                .height("100dp")
                .withAttribute("android:focusable", "true")
                .withAttribute("android:focusableInTouchMode", "true"),
              component("Button")
                .withBounds(0, 100, 200, 100)
                .width("200dp")
                .height("100dp")
                .withAttribute("android:focusable", "true")
                .withAttribute("android:focusableInTouchMode", "true"),
            ),
        )
        .build()

    try {
      val sceneManager = focusModel.surface.getSceneManager(focusModel) as LayoutlibSceneManager
      runBlocking { sceneManager.requestRenderAndWait() }

      val (firstButton, secondButton) =
        RenderService.getRenderAsyncActionExecutor()
          .runAsyncAction<Pair<android.view.View, android.view.View>> {
            val rootView = sceneManager.renderResult!!.rootViews.first().viewObject as ViewGroup
            Pair(rootView.getChildAt(0), rootView.getChildAt(1))
          }
          .join()
      assertFalse(firstButton.hasWindowFocus())
      assertFalse(secondButton.isFocused)

      val sourceComponent = javax.swing.JPanel()
      val tabPressEvent =
        java.awt.event.KeyEvent(
          sourceComponent,
          java.awt.event.KeyEvent.KEY_PRESSED,
          System.currentTimeMillis(),
          0,
          java.awt.event.KeyEvent.VK_TAB,
          '\t',
        )
      sceneManager.triggerKeyEventAsync(tabPressEvent).join()
      assertTrue(firstButton.hasWindowFocus())

      val tabReleaseEvent =
        java.awt.event.KeyEvent(
          sourceComponent,
          java.awt.event.KeyEvent.KEY_RELEASED,
          System.currentTimeMillis(),
          0,
          java.awt.event.KeyEvent.VK_TAB,
          '\t',
        )
      sceneManager.triggerKeyEventAsync(tabReleaseEvent).join()
      assertTrue(firstButton.isFocused)
    } finally {
      Disposer.dispose(focusModel)
    }
  }
}
