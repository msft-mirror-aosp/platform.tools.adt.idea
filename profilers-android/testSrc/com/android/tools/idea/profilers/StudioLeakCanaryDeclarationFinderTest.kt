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
package com.android.tools.idea.profilers

import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.LightVirtualFile
import java.io.IOException
import java.io.InputStream
import org.junit.Rule
import org.junit.Test

class StudioLeakCanaryDeclarationFinderTest {
  @get:Rule val applicationRule = ApplicationRule()

  @Test
  fun `finds the declaration in a Groovy build file`() {
    val buildFile =
      LightVirtualFile(
        "build.gradle",
        """
        dependencies {
          implementation 'androidx.core:core-ktx:1.12.0'
          debugImplementation 'com.android.tools.studio.leakcanary:leakcanary:1.0.0'
        }
        """
          .trimIndent(),
      )

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog = null))
      .isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 2))
  }

  @Test
  fun `finds the declaration in a Kotlin build file`() {
    val buildFile =
      LightVirtualFile(
        "build.gradle.kts",
        """
        dependencies {
          debugImplementation("com.android.tools.studio.leakcanary:leakcanary:1.0.0")
        }
        """
          .trimIndent(),
      )

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog = null))
      .isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 1))
  }

  @Test
  fun `finds the declaration in the version catalog when the build file uses an alias`() {
    val buildFile =
      LightVirtualFile(
        "build.gradle.kts",
        """
        dependencies {
          debugImplementation(libs.studio.leakcanary)
        }
        """
          .trimIndent(),
      )
    val versionCatalog =
      LightVirtualFile(
        "libs.versions.toml",
        """
        [versions]
        leakcanary = "1.0.0"

        [libraries]
        studio-leakcanary = { group = "com.android.tools.studio.leakcanary", name = "leakcanary", version.ref = "leakcanary" }
        """
          .trimIndent(),
      )

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog)).isEqualTo(StudioLeakCanaryDeclaration(versionCatalog, line = 4))
  }

  @Test
  fun `build file comes first when both have the declaration`() {
    val buildFile = LightVirtualFile("build.gradle", "debugImplementation 'com.android.tools.studio.leakcanary:leakcanary:1.0.0'")
    val versionCatalog = LightVirtualFile("libs.versions.toml", "leakcanary = \"com.android.tools.studio.leakcanary:leakcanary:1.0.0\"")

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog)).isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 0))
  }

  @Test
  fun `opens the top of the build file when nothing declares it`() {
    val buildFile = LightVirtualFile("build.gradle", "dependencies {\n  implementation 'androidx.core:core-ktx:1.12.0'\n}")
    val versionCatalog = LightVirtualFile("libs.versions.toml", "[libraries]\ncore = \"androidx.core:core-ktx:1.12.0\"")

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog)).isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 0))
    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog = null))
      .isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 0))
  }

  @Test
  fun `uses unsaved editor text`() {
    val buildFile = LightVirtualFile("build.gradle", "debugImplementation 'com.android.tools.studio.leakcanary:leakcanary:1.0.0'")
    val document = runReadAction { FileDocumentManager.getInstance().getDocument(buildFile)!! }
    WriteAction.runAndWait<Throwable> {
      document.setText("dependencies {\n  debugImplementation 'com.android.tools.studio.leakcanary:leakcanary:1.0.0'\n}")
    }

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog = null))
      .isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 1))
  }

  @Test
  fun `skips a build file that can't be read`() {
    val buildFile = UnreadableFile("build.gradle")
    val versionCatalog =
      LightVirtualFile("libs.versions.toml", "[libraries]\nleakcanary = \"com.android.tools.studio.leakcanary:leakcanary:1.0.0\"")

    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog)).isEqualTo(StudioLeakCanaryDeclaration(versionCatalog, line = 1))
    assertThat(findStudioLeakCanaryDeclaration(buildFile, versionCatalog = null))
      .isEqualTo(StudioLeakCanaryDeclaration(buildFile, line = 0))
  }

  /** A file whose content can't be read, like one that was deleted or locked. */
  private class UnreadableFile(name: String) :
    LightVirtualFile(name, "debugImplementation 'com.android.tools.studio.leakcanary:leakcanary:1.0.0'") {
    override fun contentsToByteArray(): ByteArray = throw IOException("Can't read $name")

    override fun getInputStream(): InputStream = throw IOException("Can't read $name")

    // The default length reads the content, so return a size without reading it.
    override fun getLength(): Long = 100
  }
}
