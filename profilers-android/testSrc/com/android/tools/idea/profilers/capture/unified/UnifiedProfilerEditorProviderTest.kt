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
package com.android.tools.idea.profilers.capture.unified

import com.android.tools.idea.profilers.capture.AndroidProfilerCaptureEditorProvider
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.TrustedFiles
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.TrustedPaths
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UnifiedProfilerEditorProviderTest {

  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val projectRule = ProjectRule()

  private lateinit var provider: UnifiedProfilerEditorProvider

  @Before
  fun setUp() {
    provider = UnifiedProfilerEditorProvider()
  }

  @Test
  fun accept_profilerVirtualFile_returnsTrue() {
    val liveVirtualFile =
      ProfilerVirtualFile(sessionId = 1L, taskType = ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS, taskName = "Java/Kotlin Allocations")

    assertThat(provider.accept(projectRule.project, liveVirtualFile)).isTrue()
  }

  @Test
  fun createEditor_profilerVirtualFile_createsUnifiedProfilerFileEditor() {
    val liveVirtualFile =
      ProfilerVirtualFile(sessionId = 1L, taskType = ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS, taskName = "Java/Kotlin Allocations")

    val editor = provider.createEditor(projectRule.project, liveVirtualFile)
    assertThat(editor).isInstanceOf(UnifiedProfilerFileEditor::class.java)
    assertThat(editor.name).isEqualTo("Java/Kotlin Allocations")
  }

  @Test
  fun externalCaptureFiles_allowedInUntrustedSafeMode() {
    val previousHeadlessProperty = System.getProperty("idea.trust.headless.disabled")
    val safeModeKey = Registry.get(TrustedFiles.SAFE_MODE_REGISTRY_KEY)
    val previousSafeMode = safeModeKey.asBoolean()
    val trustedPaths = TrustedPaths.getInstance()
    val previousTrustedPaths = trustedPaths.state
    val tempRoot = createTempDirectory("untrusted-profiler-test")
    val projectDir = tempRoot.resolve("project").createDirectories()
    val outsideDir = tempRoot.resolve("external").createDirectories()
    val isolatedProject = checkNotNull(ProjectManagerEx.getInstanceEx().openProject(projectDir, OpenProjectTask { isNewProject = true }))

    try {
      System.setProperty("idea.trust.headless.disabled", "false")
      safeModeKey.setValue(true)
      TrustedProjects.setProjectTrusted(isolatedProject, true)

      val perfettoPath = outsideDir.resolve("external_trace.perfetto-trace")
      Files.write(perfettoPath, byteArrayOf(0))
      val perfettoFile = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(perfettoPath))
      TrustedFiles.markExternallyOpened(perfettoFile)

      assertThat(TrustedFiles.isTrusted(perfettoFile, isolatedProject)).isFalse()

      val perfettoProviders = FileEditorProviderManager.getInstance().getProviderList(isolatedProject, perfettoFile)
      assertThat(perfettoProviders.any { it is UnifiedProfilerEditorProvider }).isTrue()

      val asdbPath = outsideDir.resolve("external_capture.asdb")
      Files.write(asdbPath, byteArrayOf(0))
      val asdbFile = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(asdbPath))
      TrustedFiles.markExternallyOpened(asdbFile)

      assertThat(TrustedFiles.isTrusted(asdbFile, isolatedProject)).isFalse()

      val asdbProviders = FileEditorProviderManager.getInstance().getProviderList(isolatedProject, asdbFile)
      assertThat(asdbProviders.any { it is AndroidProfilerCaptureEditorProvider }).isTrue()
    } finally {
      runInEdtAndWait { PlatformTestUtil.forceCloseProjectWithoutSaving(isolatedProject) }
      trustedPaths.loadState(previousTrustedPaths)
      safeModeKey.setValue(previousSafeMode)
      if (previousHeadlessProperty == null) {
        System.clearProperty("idea.trust.headless.disabled")
      } else {
        System.setProperty("idea.trust.headless.disabled", previousHeadlessProperty)
      }
      tempRoot.toFile().deleteRecursively()
    }
  }
}
