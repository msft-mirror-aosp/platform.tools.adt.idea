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
package com.android.tools.idea.avd

import com.android.sdklib.internal.avd.AvdManager.Companion.ENVIRONMENTS_DIR
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.android.testutils.file.recordExistingFile
import com.android.tools.idea.avd.EnvironmentFileAnalyzerTest.Companion.createMockJpegWithXmp
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.testing.registerServiceInstance
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [EnvironmentsUpdater]. */
class EnvironmentsUpdaterTest {

  @get:Rule val applicationRule = ApplicationRule()

  @get:Rule val disposableRule = DisposableRule()

  @Test
  fun testUpdateDirectory() {
    val root = createInMemoryFileSystemAndFolder("testRoot")
    val sourceDir = root.resolve("source")
    val destDir = root.resolve("dest")

    // Setup source directory.
    sourceDir.resolve("file1.txt").recordExistingFile(10000L, "v1".toByteArray())
    sourceDir.resolve("sub/file2.txt").recordExistingFile(10000L, "v2".toByteArray())

    // 1) Initial copy.
    val initialFiles = updateDirectory(sourceDir, destDir)
    assertThat(initialFiles).containsExactly(destDir.resolve("file1.txt"), destDir.resolve("sub/file2.txt"))

    assertThat(Files.readAllBytes(destDir.resolve("file1.txt"))).isEqualTo("v1".toByteArray())
    assertThat(Files.readAllBytes(destDir.resolve("sub/file2.txt"))).isEqualTo("v2".toByteArray())

    // 2) Modify file in destination to simulate existing older file; recordExistingFile will also create parent directories if needed.
    sourceDir.resolve("file1.txt").recordExistingFile(5000L, "v0".toByteArray())
    val upToDateFiles = updateDirectory(sourceDir, destDir)
    assertThat(upToDateFiles).containsExactly(destDir.resolve("file1.txt"), destDir.resolve("sub/file2.txt"))

    // Verify it wasn't overwritten by the older source.
    assertThat(Files.readAllBytes(destDir.resolve("file1.txt"))).isEqualTo("v1".toByteArray())

    // 3) Update source with a newer timestamp.
    sourceDir.resolve("file1.txt").recordExistingFile(20000L, "v1_newer".toByteArray())
    val updatedFiles = updateDirectory(sourceDir, destDir)
    assertThat(updatedFiles).containsExactly(destDir.resolve("file1.txt"), destDir.resolve("sub/file2.txt"))

    // Verify it was overwritten by the newer source.
    assertThat(Files.readAllBytes(destDir.resolve("file1.txt"))).isEqualTo("v1_newer".toByteArray())
  }

  @Test
  fun testGetEnvironments() = runBlocking {
    val tempSdkDir = Files.createTempDirectory("sdk")
    val mockSdks = mock<AndroidSdks>()
    val mockHandler = mock<AndroidSdkHandler>()
    whenever(mockHandler.location).thenReturn(tempSdkDir)
    whenever(mockSdks.tryToChooseSdkHandler()).thenReturn(mockHandler)

    val app = ApplicationManager.getApplication()
    app.registerServiceInstance(AndroidSdks::class.java, mockSdks, disposableRule.disposable)

    // Pre-create an obsolete environment image in the destination directory that is not in sourceDir.
    val envDir = tempSdkDir.resolve(ENVIRONMENTS_DIR)
    Files.createDirectories(envDir)
    val obsoleteFile = envDir.resolve("indoor-study-dark.jpg")
    Files.write(obsoleteFile, createMockJpegWithXmp("Indoor Study Dark", isDefault = false))

    val updater = EnvironmentsUpdater()
    Disposer.register(disposableRule.disposable, updater)

    val list = updater.getEnvironments()
    assertThat(list).isNotEmpty()
    val defaultEnv = list.find { it.isDefault }
    assertThat(defaultEnv).isNotNull()
    assertThat(defaultEnv!!.path.fileName.toString()).isEqualTo("study-360.jpg")
    assertThat(defaultEnv.title).isEqualTo("Study")
    assertThat(list.map { it.path.fileName.toString() }).doesNotContain("indoor-study-dark.jpg")
  }
}
