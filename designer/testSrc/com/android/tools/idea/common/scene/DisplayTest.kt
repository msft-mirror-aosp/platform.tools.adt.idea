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
package com.android.tools.idea.common.scene

import com.android.SdkConstants
import com.android.tools.idea.common.fixtures.ModelBuilder
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.uibuilder.scene.SceneTest
import com.intellij.testFramework.PlatformTestUtil
import java.awt.image.BufferedImage

class DisplayTest : SceneTest() {

  override fun createModel(): ModelBuilder {
    return model(
      "model.xml",
      component(SdkConstants.LINEAR_LAYOUT)
        .id("@+id/root")
        .withBounds(0, 0, 1000, 1000)
        .children(component(SdkConstants.TEXT_VIEW).id("@+id/textView").withBounds(100, 100, 100, 50)),
    )
  }

  fun testSyncDisplayRebuildsOnLocationChange() {
    try {
      StudioFlags.NELE_BACKGROUND_DISPLAY_LIST.override(false)
      val display = Display.create(testRootDisposable)
      val screenView = myScreen.screen
      screenView.setLocation(0, 0)

      val initialImage = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB)
      display.draw(screenView, initialImage.createGraphics())
      assertTrue(initialImage.getRGB(0, 0) != 0)

      // Moving the screenView to a new location should cause a rebuild on next draw
      screenView.setLocation(100, 200)
      val movedImage = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB)
      display.draw(screenView, movedImage.createGraphics())
      assertEquals(0, movedImage.getRGB(0, 0))
      assertTrue(movedImage.getRGB(100, 200) != 0)
    } finally {
      StudioFlags.NELE_BACKGROUND_DISPLAY_LIST.clearOverride()
    }
  }

  fun testAsyncDisplayRebuildsOnLocationChange() {
    try {
      StudioFlags.NELE_BACKGROUND_DISPLAY_LIST.override(true)
      Display.setCaptureDisplayRepaints(true)
      try {
        val display = Display.create(testRootDisposable)
        val screenView = myScreen.screen
        screenView.setLocation(0, 0)

        val initialImage = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB)
        val g = initialImage.createGraphics()

        display.draw(screenView, g)
        assertTrue(display.hasPendingPaints())

        // Pump events until pending paints are processed
        PlatformTestUtil.waitWithEventsDispatching(
          "Timed out waiting for initial display list build",
          {
            display.draw(screenView, g)
            !display.hasPendingPaints()
          },
          5,
        )
        display.draw(screenView, initialImage.createGraphics())
        assertTrue(initialImage.getRGB(0, 0) != 0)

        // Now change location: moving screenView should trigger rebuild
        screenView.setLocation(100, 200)
        display.draw(screenView, g)
        assertTrue(display.hasPendingPaints())

        // Pump events until pending paints are processed
        PlatformTestUtil.waitWithEventsDispatching(
          "Timed out waiting for rebuild after location change",
          {
            display.draw(screenView, g)
            !display.hasPendingPaints()
          },
          5,
        )
        val movedImage = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB)
        display.draw(screenView, movedImage.createGraphics())
        assertEquals(0, movedImage.getRGB(0, 0))
        assertTrue(movedImage.getRGB(100, 200) != 0)
      } finally {
        Display.setCaptureDisplayRepaints(false)
      }
    } finally {
      StudioFlags.NELE_BACKGROUND_DISPLAY_LIST.clearOverride()
    }
  }
}
