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
package com.android.tools.idea.common.surface

import com.android.tools.adtui.ZoomController
import com.android.tools.adtui.actions.ZoomType
import com.android.tools.idea.common.BackedTestFile
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.RunsInEdt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Suppress("UnstableApiUsage")
@RunsInEdt
class DesignSurfaceSettingsTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val project
    get() = projectRule.project

  private val fixture by lazy { projectRule.fixture }

  private lateinit var defaultFilePathToZoomLevelMap: MutableMap<String, Double>

  @Before
  fun setUp() {
    val settings = SurfaceState()
    defaultFilePathToZoomLevelMap = settings.filePathToZoomLevelMap
  }

  @After
  fun tearDown() {
    val settings = SurfaceState()
    settings.filePathToZoomLevelMap = defaultFilePathToZoomLevelMap
  }

  @Test
  fun testShareGlobalState() {
    val state1 = DesignSurfaceSettings.getInstance(project).surfaceState
    val state2 = DesignSurfaceSettings.getInstance(project).surfaceState

    // The references should be same.
    assertEquals(state1, state2)
  }

  @Test
  fun testSaveLoadSettings() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    val filePathToZoomLevelMap = mutableMapOf<String, Double>()
    filePathToZoomLevelMap["path1"] = 0.1
    filePathToZoomLevelMap["path2"] = 0.2

    surfaceState.filePathToZoomLevelMap = filePathToZoomLevelMap

    // Check the values are same after getting another instance.
    val anotherSurfaceState = DesignSurfaceSettings.getInstance(project).surfaceState
    assertEquals(filePathToZoomLevelMap, anotherSurfaceState.filePathToZoomLevelMap)
  }

  @Test
  fun testSaveLoadSettingsSameFileSameSurfaces() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    val filePathToZoomLevelMap = mutableMapOf<String, Double>()
    filePathToZoomLevelMap["path1:23"] = 0.1
    filePathToZoomLevelMap["path1:32"] = 0.2
    filePathToZoomLevelMap["path1:23"] = 0.4
    filePathToZoomLevelMap["path1:32"] = 0.6

    surfaceState.filePathToZoomLevelMap = filePathToZoomLevelMap
    val anotherSurfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    // Check the stored value is still on the same surface
    assertEquals(2, anotherSurfaceState.filePathToZoomLevelMap.size)
    assertEquals(0.4, anotherSurfaceState.filePathToZoomLevelMap["path1:23"])
    assertEquals(0.6, anotherSurfaceState.filePathToZoomLevelMap["path1:32"])

    // Check the values are same after getting another instance.
    assertEquals(filePathToZoomLevelMap, anotherSurfaceState.filePathToZoomLevelMap)
  }

  @Test
  fun testSaveLoadSettingsWhenSameFileAndDifferentSurfaces() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    val filePathToZoomLevelMap = mutableMapOf<String, Double>()
    filePathToZoomLevelMap["path1:12"] = 0.1
    filePathToZoomLevelMap["path1:12"] = 0.2
    filePathToZoomLevelMap["path1:12"] = 0.4
    filePathToZoomLevelMap["path1:12"] = 0.2

    surfaceState.filePathToZoomLevelMap = filePathToZoomLevelMap

    val anotherSurfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    // Check the stored value is still on the same surface
    assertEquals(1, anotherSurfaceState.filePathToZoomLevelMap.size)
    assertEquals(0.2, anotherSurfaceState.filePathToZoomLevelMap.values.last(), 0.0)

    // Check the values are same after getting another instance.
    assertEquals(filePathToZoomLevelMap, anotherSurfaceState.filePathToZoomLevelMap)
  }

  @Test
  fun testSavingPathForBackedFile() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    val originalFile = fixture.addFileToProject("path/to/origin/file", "").virtualFile
    val backedFile1 = BackedTestFile("path/to/backed/file1", originalFile)
    val backedFile2 = BackedTestFile("path/to/backed/file2", originalFile)

    val zoomController = createZoomController()
    zoomController.setScale(0.1)
    surfaceState.saveFileScale(fixture.project, originalFile, zoomController)
    assertEquals(0.1, surfaceState.loadFileScale(fixture.project, backedFile1, zoomController))
    assertEquals(0.1, surfaceState.loadFileScale(fixture.project, backedFile2, zoomController))

    zoomController.setScale(3.0)
    surfaceState.saveFileScale(fixture.project, backedFile1, zoomController)
    assertEquals(3.0, surfaceState.loadFileScale(fixture.project, originalFile, zoomController))
    assertEquals(3.0, surfaceState.loadFileScale(fixture.project, backedFile2, zoomController))

    zoomController.setScale(0.5)
    surfaceState.saveFileScale(fixture.project, backedFile2, zoomController)
    assertEquals(0.5, surfaceState.loadFileScale(fixture.project, originalFile, zoomController))
    assertEquals(0.5, surfaceState.loadFileScale(fixture.project, backedFile1, zoomController))
  }

  @Test
  fun testSavingPathForBackedFileWithZoomKeys() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState

    val originalFile = fixture.addFileToProject("path/to/origin/file", "").virtualFile
    val backedFile1 = BackedTestFile("path/to/backed/file1", originalFile)
    val backedFile2 = BackedTestFile("path/to/backed/file2", originalFile)

    val zoomController = createZoomController()

    zoomController.setScale(0.1)
    surfaceState.saveFileScale(fixture.project, originalFile, zoomController)
    assertEquals(0.1, surfaceState.loadFileScale(fixture.project, backedFile1, zoomController))
    assertEquals(0.1, surfaceState.loadFileScale(fixture.project, backedFile2, zoomController))

    zoomController.setScale(3.0)
    surfaceState.saveFileScale(fixture.project, backedFile1, zoomController)
    assertEquals(3.0, surfaceState.loadFileScale(fixture.project, originalFile, zoomController))
    assertEquals(3.0, surfaceState.loadFileScale(fixture.project, backedFile2, zoomController))

    zoomController.storeId = "STORE_ID"
    zoomController.setScale(0.5)
    surfaceState.saveFileScale(fixture.project, backedFile2, zoomController)
    assertEquals(0.5, surfaceState.loadFileScale(fixture.project, originalFile, zoomController))
    assertEquals(0.5, surfaceState.loadFileScale(fixture.project, backedFile1, zoomController))
  }

  @Test
  fun testSaveOrganizationState() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState
    val file = fixture.addFileToProject("path/to/file", "").virtualFile
    surfaceState.saveOrganizationGroupState(file.url, "method", true)
    val savedState = surfaceState.getOrganizationGroupState(file.url)
    assertEquals(mapOf("method" to true), savedState)
  }

  @Test
  fun testUpdateOrganizationState() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState
    val file = fixture.addFileToProject("path/to/file", "").virtualFile
    surfaceState.saveOrganizationGroupState(file.url, "method", true)
    val savedState = surfaceState.getOrganizationGroupState(file.url)
    assertEquals(mapOf("method" to true), savedState)
    surfaceState.saveOrganizationGroupState(file.url, "method", false)
    val updatedState = surfaceState.getOrganizationGroupState(file.url)
    assertEquals(mapOf("method" to false), updatedState)
  }

  @Test
  fun testOrganizationStateForNewFile() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState
    val file = fixture.addFileToProject("path/to/file", "").virtualFile
    val savedState = surfaceState.getOrganizationGroupState(file.url)
    assertEquals(emptyMap<String, Boolean>(), savedState)
  }

  @Test
  fun testRevalidateOrganizationGroups() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState
    val file = fixture.addFileToProject("path/to/file", "").virtualFile
    surfaceState.saveOrganizationGroupState(file.url, "method1", true)
    surfaceState.saveOrganizationGroupState(file.url, "method2", true)
    surfaceState.saveOrganizationGroupState(file.url, "method3", false)
    surfaceState.saveOrganizationGroupState(file.url, "method4", false)
    surfaceState.saveOrganizationGroupState(file.url, "method5", false)
    surfaceState.revalidateOrganizationGroups(file, setOf("method1", "method2", "method3", "method4", "method5"))
    assertEquals(
      mapOf("method1" to true, "method2" to true, "method3" to false, "method4" to false, "method5" to false),
      surfaceState.getOrganizationGroupState(file.url),
    )
    surfaceState.revalidateOrganizationGroups(file, setOf("method4", "method5"))
    assertEquals(mapOf("method4" to false, "method5" to false), surfaceState.getOrganizationGroupState(file.url))
  }

  @Test
  fun testRemoveFileFromProject() {
    val surfaceState = DesignSurfaceSettings.getInstance(project).surfaceState
    val file = fixture.addFileToProject("path/to/file", "").virtualFile
    surfaceState.saveOrganizationGroupState(file.url, "method", true)
    assertTrue(surfaceState.organizationGroups.isNotEmpty())
    runWriteAction { file.delete(this) }
    surfaceState.revalidateOrganizationGroups()
    assertTrue(surfaceState.organizationGroups.isEmpty())
  }

  companion object {
    private fun createZoomController() =
      object : ZoomController {
        private var currentScale = 1.0
        override val scale: Double
          get() = currentScale

        override val screenScalingFactor: Double
          get() = 1.0

        override var storeId: String? = null

        override val minScale: Double
          get() = 0.1

        override val maxScale: Double
          get() = 10.0

        override fun setScale(scale: Double, x: Int, y: Int): Boolean {
          currentScale = scale
          return true
        }

        override fun zoomToFit(): Boolean = true

        override fun getFitScale(): Double = 1.0

        override fun resetZoomToFitSettings(shouldWaitForResize: Boolean, shouldWaitForLayoutCreated: Boolean) {}

        override fun zoom(type: ZoomType) = true

        override fun canZoomIn(): Boolean = true

        override fun canZoomOut(): Boolean = true

        override fun canZoomToFit(): Boolean = true

        override fun canZoomToActual(): Boolean = true
      }
  }
}
