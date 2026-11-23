/*
 * Copyright (C) 2025 The Android Open Source Project
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
package org.jetbrains.android.facet

import com.android.tools.idea.projectsystem.NamedIdeaSourceProvider
import com.android.tools.idea.projectsystem.NamedIdeaSourceProviderBuilder
import com.android.tools.idea.projectsystem.SourceProviderManager
import com.android.tools.idea.res.isInResourceSubdirectoryInAnyVariant
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.androidFacet
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.android.dom.AndroidXmlExtension
import org.jetbrains.android.resourceManagers.ModuleResourceManagers
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

// language=XML
const val emptyLayout =
  """
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
    </LinearLayout>
    """

class ResourceFolderManagerTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun testTopicNotificationOrder() {
    val resourceFolderManager = ResourceFolderManager.getInstance(projectRule.module.androidFacet!!)
    var earlyListenerCalls = AtomicInteger(0)
    var listenerCalls = AtomicInteger(0)
    val earlyListener = ResourceFolderManager.ResourceFolderListener { _, _ -> earlyListenerCalls.incrementAndGet() }
    val listener = ResourceFolderManager.ResourceFolderListener { _, _ ->
      val previousCounter = listenerCalls.getAndIncrement()
      assertWithMessage("EARLY_TOPIC is expected to always be called before TOPIC")
        .that(previousCounter)
        .isLessThan(earlyListenerCalls.get())
    }

    projectRule.fixture.addFileToProject("res1/layout/test_layout.xml", emptyLayout)
    val res1Directory = projectRule.fixture.findFileInTempDir("res1")
    var resourceDirectories = mutableListOf(res1Directory.url)
    SourceProviderManager.replaceForTest(
      projectRule.module.androidFacet!!,
      projectRule.testRootDisposable,
      NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml").withResDirectoryUrls(resourceDirectories).build(),
    )

    projectRule.project.messageBus
      .connect(projectRule.fixture.testRootDisposable)
      .subscribe(ResourceFolderManager.EARLY_TOPIC, earlyListener)
    projectRule.project.messageBus.connect(projectRule.fixture.testRootDisposable).subscribe(ResourceFolderManager.TOPIC, listener)

    resourceFolderManager.checkForChanges()
    assertThat(resourceFolderManager.folders).containsExactly(res1Directory)

    // Simulate a new resource folder being added
    projectRule.fixture.addFileToProject("res2/layout/test_layout.xml", emptyLayout)
    val res2Directory = projectRule.fixture.findFileInTempDir("res2")
    resourceDirectories.add(res2Directory.url)
    resourceFolderManager.checkForChanges()
    assertThat(resourceFolderManager.folders).containsExactly(res1Directory, res2Directory)
    assertThat(earlyListenerCalls.get()).isEqualTo(1)
    assertThat(listenerCalls.get()).isEqualTo(1)
  }

  @Test
  fun testIsResourceFolderDoesNotResolveUnrelatedResDirectoriesWhenCold() {
    val facet = projectRule.module.androidFacet!!
    val resourceFolderManager = ResourceFolderManager.getInstance(facet)

    projectRule.fixture.addFileToProject("res1/layout/test_layout.xml", emptyLayout)
    projectRule.fixture.addFileToProject("other/foo.txt", "")
    val res1Directory = projectRule.fixture.findFileInTempDir("res1")
    val otherDirectory = projectRule.fixture.findFileInTempDir("other")

    val baseProvider =
      NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml")
        .withResDirectoryUrls(listOf("${res1Directory.url}/", "file:///nonexistent/unrelated/res"))
        .build()
    val noVfsResolutionProvider =
      object : NamedIdeaSourceProvider by baseProvider {
        override val resDirectories: Iterable<VirtualFile>
          get() = throw AssertionError("isResourceFolder should not resolve resDirectories when cache is cold")
      }
    SourceProviderManager.replaceForTest(facet, projectRule.testRootDisposable, noVfsResolutionProvider)

    assertThat(resourceFolderManager.isResourceFolder(res1Directory)).isTrue()
    assertThat(resourceFolderManager.isResourceFolder(otherDirectory)).isFalse()

    val localResourceManager = ModuleResourceManagers.getInstance(facet).localResourceManager
    assertThat(localResourceManager.isResourceDir(res1Directory)).isTrue()
    assertThat(localResourceManager.isResourceDir(otherDirectory)).isFalse()
  }

  @Test
  fun testIsResourceFolderWhenFoldersAreCached() {
    val facet = projectRule.module.androidFacet!!
    val resourceFolderManager = ResourceFolderManager.getInstance(facet)

    projectRule.fixture.addFileToProject("res1/layout/test_layout.xml", emptyLayout)
    projectRule.fixture.addFileToProject("other/foo.txt", "")
    val res1Directory = projectRule.fixture.findFileInTempDir("res1")
    val otherDirectory = projectRule.fixture.findFileInTempDir("other")

    SourceProviderManager.replaceForTest(
      facet,
      projectRule.testRootDisposable,
      NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml").withResDirectoryUrls(listOf(res1Directory.url)).build(),
    )

    // Warm the FOLDERS_KEY cache.
    assertThat(resourceFolderManager.folders).containsExactly(res1Directory)
    assertThat(resourceFolderManager.isResourceFolder(res1Directory)).isTrue()
    assertThat(resourceFolderManager.isResourceFolder(otherDirectory)).isFalse()
  }

  @Test
  fun testIsResourceFolderReturnsFalseForInvalidVirtualFile() {
    val facet = projectRule.module.androidFacet!!
    val resourceFolderManager = ResourceFolderManager.getInstance(facet)

    projectRule.fixture.addFileToProject("res_deleted/layout/test_layout.xml", emptyLayout)
    val resDir = projectRule.fixture.findFileInTempDir("res_deleted")
    SourceProviderManager.replaceForTest(
      facet,
      projectRule.testRootDisposable,
      NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml").withResDirectoryUrls(listOf(resDir.url)).build(),
    )

    runWriteActionAndWait { resDir.delete(this) }
    assertThat(resDir.isValid).isFalse()
    assertThat(resourceFolderManager.isResourceFolder(resDir)).isFalse()
  }

  @Test
  fun testMatchesAnyUrlHandlesNormalizationAndCaseSensitivity() {
    fun mockFile(protocol: String, path: String, caseSensitive: Boolean, valid: Boolean = true): VirtualFile {
      val fs = mock(VirtualFileSystem::class.java)
      whenever(fs.protocol).thenReturn(protocol)
      whenever(fs.isCaseSensitive).thenReturn(caseSensitive)
      val segments =
        if (path.startsWith("/")) {
          listOf("/") + path.removePrefix("/").split("/").filter { it.isNotEmpty() }
        } else {
          path.split("/").filter { it.isNotEmpty() }
        }
      var current: VirtualFile? = null
      for ((index, segment) in segments.withIndex()) {
        val node = mock(VirtualFile::class.java)
        val isLeaf = index == segments.lastIndex
        whenever(node.nameSequence).thenReturn(segment)
        whenever(node.parent).thenReturn(current)
        whenever(node.isCaseSensitive).thenReturn(caseSensitive)
        whenever(node.fileSystem).thenReturn(fs)
        whenever(node.isValid).thenReturn(if (isLeaf) valid else true)
        if (isLeaf) {
          whenever(node.path).thenReturn(path)
          whenever(node.url).thenReturn("$protocol://$path")
        }
        current = node
      }
      return current!!
    }

    projectRule.fixture.addFileToProject("res_norm/layout/test_layout.xml", emptyLayout)
    val realResDir = projectRule.fixture.findFileInTempDir("res_norm")
    assertThat(realResDir.matchesAnyUrl(sequenceOf("${realResDir.url}/"))).isTrue()
    assertThat(realResDir.matchesAnyUrl(sequenceOf("${realResDir.parent.url}/sub/../${realResDir.name}"))).isTrue()
    assertThat(realResDir.matchesAnyUrl(sequenceOf("file://${realResDir.path}"))).isFalse()
    assertThat(realResDir.matchesAnyUrl(sequenceOf(realResDir.path))).isFalse()

    val linuxDir = mockFile("file", "/workspace/app/res", caseSensitive = true)
    assertThat(linuxDir.matchesAnyUrl(sequenceOf("file:///workspace/app/res"))).isTrue()
    assertThat(linuxDir.matchesAnyUrl(sequenceOf("file:///workspace/app/res/"))).isTrue()
    assertThat(linuxDir.matchesAnyUrl(sequenceOf("file:///workspace/app/sub/../res"))).isTrue()
    assertThat(linuxDir.matchesAnyUrl(sequenceOf("file:///workspace/app/RES"))).isFalse()
    assertThat(linuxDir.matchesAnyUrl(sequenceOf("jar:///workspace/app/res"))).isFalse()
    assertThat(linuxDir.matchesAnyUrl(sequenceOf("/workspace/app/res"))).isFalse()

    val windowsDir = mockFile("file", "C:/Users/Dev/res", caseSensitive = false)
    assertThat(windowsDir.matchesAnyUrl(sequenceOf("file://c:/users/dev/RES/"))).isTrue()
    assertThat(windowsDir.matchesAnyUrl(sequenceOf("file://c:/users/dev/other"))).isFalse()

    val invalidDir = mockFile("file", "/workspace/app/res", caseSensitive = true, valid = false)
    assertThat(invalidDir.matchesAnyUrl(sequenceOf("file:///workspace/app/res"))).isFalse()
  }

  @Test
  fun testAndroidXmlExtensionChecksManifestBeforeResourceDirectories() {
    val facet = projectRule.module.androidFacet!!
    // Place the manifest under a subdirectory so its grandparent (temp:///src) belongs to the
    // module; otherwise isLocalResourceDirectoryInAnyVariant would return early before consulting
    // SourceProviders.
    val manifestFile = projectRule.fixture.addFileToProject("sub/AndroidManifest.xml", "<manifest/>")
    runReadAction {
      assertThat(ModuleUtilCore.findModuleForFile(manifestFile.virtualFile.parent.parent, projectRule.project))
        .isEqualTo(projectRule.module)
    }

    val baseProvider = NamedIdeaSourceProviderBuilder.create("main", manifestFile.virtualFile.url).build()
    val failingResProvider =
      object : NamedIdeaSourceProvider by baseProvider {
        override val resDirectories: Iterable<VirtualFile>
          get() = throw AssertionError("Should not inspect resDirectories for AndroidManifest.xml")

        override val resDirectoryUrls: Iterable<String>
          get() = throw AssertionError("Should not inspect resDirectoryUrls for AndroidManifest.xml")
      }
    SourceProviderManager.replaceForTest(facet, projectRule.testRootDisposable, failingResProvider)

    assertThat(AndroidXmlExtension().isAvailable(manifestFile)).isTrue()
    // Verify that scanning resource directories for this manifest would have triggered failingResProvider.
    assertThrows(AssertionError::class.java) {
      runReadAction { isInResourceSubdirectoryInAnyVariant(manifestFile) }
    }
  }
}
