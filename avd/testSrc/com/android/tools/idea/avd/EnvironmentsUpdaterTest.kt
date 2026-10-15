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

import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.internal.avd.AvdManager.Companion.ENVIRONMENTS_DIR
import com.android.sdklib.internal.avd.AvdManager.Companion.ENVIRONMENT_INI
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.android.testutils.file.recordExistingFile
import com.android.tools.idea.avd.EnvironmentFileAnalyzerTest.Companion.createMockJpegWithXmp
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.IdeAvdManagers
import com.android.tools.idea.testing.registerServiceInstance
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [EnvironmentsUpdater]. */
class EnvironmentsUpdaterTest {

  @get:Rule val applicationRule = ApplicationRule()

  @get:Rule val disposableRule = DisposableRule()

  private val sdkHandler: AndroidSdkHandler by lazy {
    val tempSdkDir = Files.createTempDirectory("sdk")
    val mockSdks = mock<AndroidSdks>()
    val mockHandler = mock<AndroidSdkHandler>()
    whenever(mockHandler.location).thenReturn(tempSdkDir)
    whenever(mockSdks.tryToChooseSdkHandler()).thenReturn(mockHandler)

    val app = ApplicationManager.getApplication()
    app.registerServiceInstance(AndroidSdks::class.java, mockSdks, disposableRule.disposable)
    mockHandler
  }

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

    // 4) Non-existent source directory throws NoSuchFileException.
    assertFailsWith<NoSuchFileException> {
      updateDirectory(root.resolve("non_existent"), destDir)
    }
  }

  @Test
  fun testGetEnvironments_removesUnreferencedObsoleteFiles() = runBlocking {
    val envDir = sdkHandler.location!!.resolve(ENVIRONMENTS_DIR)
    Files.createDirectories(envDir)
    val obsoleteFile1 = envDir.resolve("default-background-1.png").recordExistingFile("png")
    val obsoleteFile2 = envDir.resolve("indoor-study-dark.jpg")
    Files.write(obsoleteFile2, createMockJpegWithXmp("Indoor Study Dark", isDefault = false))
    val obsoleteFile3 = envDir.resolve("outdoor-city-bright.jpg").recordExistingFile("city")
    val obsoleteFile4 = envDir.resolve("outdoor-nature-bright.jpg").recordExistingFile("nature")
    val customFile = envDir.resolve("custom-unreferenced.jpg").recordExistingFile("custom")

    val updater = EnvironmentsUpdater()
    Disposer.register(disposableRule.disposable, updater)

    val list = updater.getEnvironments()
    assertThat(list).isNotEmpty()
    val defaultEnv = list.find { it.isDefault }
    assertThat(defaultEnv).isNotNull()
    assertThat(defaultEnv!!.path.fileName.toString()).isEqualTo("study-360.jpg")
    assertThat(defaultEnv.title).isEqualTo("Study")
    assertThat(list.map { it.path.fileName.toString() }).doesNotContain("indoor-study-dark.jpg")

    // The obsolete unreferenced files should be removed by asynchronous garbage collection, while other files are kept.
    updater.awaitCleanup()
    assertThat(Files.exists(obsoleteFile1)).isFalse()
    assertThat(Files.exists(obsoleteFile2)).isFalse()
    assertThat(Files.exists(obsoleteFile3)).isFalse()
    assertThat(Files.exists(obsoleteFile4)).isFalse()
    assertThat(Files.exists(customFile)).isTrue()
  }

  @Test
  fun testGetEnvironments_preservesReferencedEnvironment() = runBlocking {
    val envDir = sdkHandler.location!!.resolve(ENVIRONMENTS_DIR)
    Files.createDirectories(envDir)
    val referencedObsoleteFile = envDir.resolve("indoor-study-dark.jpg")
    Files.write(referencedObsoleteFile, createMockJpegWithXmp("Indoor Study Dark", isDefault = false))

    val unreferencedObsoleteFile = envDir.resolve("outdoor-city-bright.jpg")
    Files.write(unreferencedObsoleteFile, createMockJpegWithXmp("Outdoor City Bright", isDefault = false))

    val avdFolder = Files.createTempDirectory("test_avd")
    val envIni = avdFolder.resolve(ENVIRONMENT_INI)
    Files.writeString(envIni, "background.image.filename=indoor-study-dark.jpg\n")
    val avd = AvdInfo(avdFolder.parent.resolve("test_avd.ini"), avdFolder, null)

    registerAvdManager(listOf(avd))

    val updater = EnvironmentsUpdater()
    Disposer.register(disposableRule.disposable, updater)

    val list = updater.getEnvironments()
    assertThat(list).isNotEmpty()
    // It is filtered out from getEnvironments() because it's obsolete (not in sourceDir).
    assertThat(list.map { it.path.fileName.toString() }).doesNotContain("indoor-study-dark.jpg")

    // But on disk, the referenced file should be kept, and the unreferenced obsolete one deleted.
    updater.awaitCleanup()
    assertThat(Files.exists(referencedObsoleteFile)).isTrue()
    assertThat(Files.exists(unreferencedObsoleteFile)).isFalse()
  }

  @Test
  fun testGetEnvironments_variousReferenceFormats() = runBlocking {
    val envDir = sdkHandler.location!!.resolve(ENVIRONMENTS_DIR)
    Files.createDirectories(envDir)

    val env1 = envDir.resolve("default-background-1.png").recordExistingFile("1")
    val env2 = envDir.resolve("indoor-study-dark.jpg").recordExistingFile("2")
    val env3 = envDir.resolve("outdoor-city-bright.jpg").recordExistingFile("3")
    val env4 = envDir.resolve("outdoor-nature-bright.jpg").recordExistingFile("4")

    val avdBase = Files.createTempDirectory("avds")
    // AVD 1: Absolute path with imagefile prefix and path relative to parent directory (environments/...).
    val avdFolder1 = avdBase.resolve("avd1.avd")
    avdFolder1
      .resolve(ENVIRONMENT_INI)
      .recordExistingFile("background.image.filename=imagefile:${env1.toAbsolutePath()}\ncustom.key=environments/indoor-study-dark.jpg\n")
    val avd1 = AvdInfo(avdBase.resolve("avd1.ini"), avdFolder1, null)

    // AVD 2: referenced in properties (config.ini) with image360 prefix and userSettings (user-settings.ini).
    val avd2 =
      AvdInfo(
        avdBase.resolve("avd2.ini"),
        avdBase.resolve("avd2.avd"),
        null,
        properties = mapOf("background.image.filename" to "image360:outdoor-city-bright.jpg"),
        userSettings = mapOf("background.image.filename" to "outdoor-nature-bright.jpg"),
      )

    registerAvdManager(listOf(avd1, avd2))

    val updater = EnvironmentsUpdater()
    Disposer.register(disposableRule.disposable, updater)

    updater.getEnvironments()
    updater.awaitCleanup()

    assertThat(Files.exists(env1)).isTrue()
    assertThat(Files.exists(env2)).isTrue()
    assertThat(Files.exists(env3)).isTrue()
    assertThat(Files.exists(env4)).isTrue()
  }

  private fun registerAvdManager(avds: List<AvdInfo>) {
    val mockAvdManager = mock<AvdManager>()
    whenever(mockAvdManager.allAvds).thenReturn(avds)

    val mockIdeAvdManagers = mock<IdeAvdManagers>()
    whenever(mockIdeAvdManagers.getAvdManager(eq(sdkHandler), any())).thenReturn(mockAvdManager)
    ApplicationManager.getApplication().registerServiceInstance(IdeAvdManagers::class.java, mockIdeAvdManagers, disposableRule.disposable)
  }
}
