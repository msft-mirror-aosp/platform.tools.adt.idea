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
package com.android.tools.idea.uibuilder.surface.layer

import com.android.tools.idea.testing.AndroidProjectRule
import java.awt.Rectangle
import java.awt.image.BufferedImage
import org.jetbrains.android.uipreview.HATCHERY
import org.jetbrains.android.uipreview.ModuleClassLoaderHatchery
import org.jetbrains.android.uipreview.ReadyState
import org.jetbrains.android.uipreview.Stats
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ClassLoadingDebugLayerTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun testPaintWithoutHatcheryDoesNotCrash() {
    val module = projectRule.module
    val layer = ClassLoadingDebugLayer(module)

    val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.clip = Rectangle(0, 0, 400, 400)

    try {
      layer.paint(graphics)
    } finally {
      graphics.dispose()
    }
  }

  @Test
  fun testPaintWithEdgeCasesDoesNotCrash() {
    val module = projectRule.module
    val hatchery = mock(ModuleClassLoaderHatchery::class.java)
    `when`(hatchery.getStats())
      .thenReturn(
        listOf(
          Stats("Clutch with zero toDo", listOf(ReadyState(progress = 0, toDo = 0))),
          Stats("Clutch with overflow progress", listOf(ReadyState(progress = 50, toDo = 10))),
        )
      )
    module.putUserData(HATCHERY, hatchery)

    val layer = ClassLoadingDebugLayer(module)
    val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.clip = Rectangle(0, 0, 400, 400)

    try {
      layer.paint(graphics)
    } finally {
      graphics.dispose()
    }
  }
}
