/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.visual

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.testFramework.RunsInEdt
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class VisualizationToolProjectSettingsTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  private val project
    get() = projectRule.project

  private var defaultScale: Double = 0.0

  @Before
  fun setUp() {
    val settings = VisualizationToolProjectSettings.getInstance(project)
    defaultScale = settings.projectState.scale
  }

  @After
  fun tearDown() {
    val settings = VisualizationToolProjectSettings.getInstance(project)
    settings.projectState.scale = defaultScale
  }

  @Test
  fun testShareGlobalState() {
    val settings1 = VisualizationToolProjectSettings.getInstance(project)
    val settings2 = VisualizationToolProjectSettings.getInstance(project)

    assertEquals(settings1.projectState, settings2.projectState)
  }

  @Test
  fun testSaveLoadSettings() {
    val settings = VisualizationToolProjectSettings.getInstance(project)
    val scale = 0.1
    settings.projectState.scale = scale

    // Check the values are same after getting another instance.
    val anotherSettings = VisualizationToolProjectSettings.getInstance(project)
    assertEquals(scale, anotherSettings.projectState.scale)
  }
}
