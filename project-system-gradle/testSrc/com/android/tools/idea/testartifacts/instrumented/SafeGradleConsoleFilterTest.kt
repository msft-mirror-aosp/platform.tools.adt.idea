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
package com.android.tools.idea.testartifacts.instrumented

import com.android.tools.idea.testartifacts.instrumented.testsuite.view.AndroidTestSuiteView
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.ui.UIUtil
import java.io.File
import javax.swing.event.AncestorEvent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
class SafeGradleConsoleFilterTest {

  private val projectRule = AndroidProjectRule.onDisk()
  private val disposableRule = DisposableRule()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule)

  @Test
  fun applyFilter_linksCachedProjectBuildFile() {
    val virtualFile = projectRule.fixture.addFileToProject("build.gradle.kts", "plugins {}\nval x = 1\n").virtualFile
    val filter = SafeGradleConsoleFilter(projectRule.project)
    val line = "Build file '${virtualFile.path}' line: 2"

    val result = filter.applyFilter(line, line.length)

    assertThat(result).isNotNull()
    val info = result!!.firstHyperlinkInfo as OpenFileHyperlinkInfo
    assertThat(info.virtualFile).isEqualTo(virtualFile)
    assertThat(runReadActionBlocking { info.descriptor?.offset }).isEqualTo("plugins {}\n".length)
  }

  @Test
  fun applyFilter_uncPaths_returnsNullWithoutVfsOrDelegateLookup() {
    val mockVfs = mock(LocalFileSystem::class.java)
    val mockDelegate = mock(Filter::class.java)
    val filter = SafeGradleConsoleFilter(projectRule.project, mockVfs, mockDelegate)

    val uncLines =
      listOf(
        "Build file '\\\\10.0.0.2\\share\\build.gradle' line: 1",
        "Build file '//10.0.0.2/share/build.gradle' line: 1",
        "Settings file '\\\\attacker.example.com\\share\\settings.gradle.kts' line: 3",
      )

    for (line in uncLines) {
      assertThat(filter.applyFilter(line, line.length)).named(line).isNull()
    }
    verifyNoInteractions(mockVfs)
    verifyNoInteractions(mockDelegate)
  }

  @Test
  fun applyFilter_uncachedLocalPath_returnsNullWithoutDelegateLookup() {
    val posixVfs = mock(LocalFileSystem::class.java)
    whenever(posixVfs.findFileByPathIfCached("/uncached/project/build.gradle")).thenReturn(null)
    val posixDelegate = mock(Filter::class.java)
    val posixFilter = SafeGradleConsoleFilter(projectRule.project, posixVfs, posixDelegate, isWindows = false)
    val posixLine = "Build file '/uncached/project/build.gradle' line: 5"

    assertThat(posixFilter.applyFilter(posixLine, posixLine.length)).isNull()

    verify(posixVfs).findFileByPathIfCached("/uncached/project/build.gradle")
    verify(posixDelegate, never()).applyFilter(any(), any())

    val winVfs = mock(LocalFileSystem::class.java)
    val winDelegate = mock(Filter::class.java)
    val winFilter =
      SafeGradleConsoleFilter(
        projectRule.project,
        winVfs,
        winDelegate,
        isWindows = true,
        cachedWindowsDriveRootsProvider = { setOf('C') },
      )

    assertThat(winFilter.applyFilter(posixLine, posixLine.length)).isNull()
    verifyNoInteractions(winVfs)
    verifyNoInteractions(winDelegate)

    whenever(winVfs.findFileByPathIfCached("C:/uncached/project/build.gradle")).thenReturn(null)
    val winLine = "Build file 'C:/uncached/project/build.gradle' line: 5"

    assertThat(winFilter.applyFilter(winLine, winLine.length)).isNull()

    verify(winVfs).findFileByPathIfCached("C:/uncached/project/build.gradle")
    verify(winDelegate, never()).applyFilter(any(), any())
  }

  @Test
  fun applyFilter_missingSpaceAfterLineColon_doesNotDelegate() {
    val mockDelegate = mock(Filter::class.java)
    val filter = SafeGradleConsoleFilter(projectRule.project, LocalFileSystem.getInstance(), mockDelegate)
    val line = "Build file '/' line:x '//evil/share/build.gradle' line: 1"

    assertThat(filter.applyFilter(line, line.length)).isNull()

    verifyNoInteractions(mockDelegate)
  }

  @Test
  fun applyFilter_posixBackslashPaths_doNotBypassCacheGuard() {
    val virtualFile = projectRule.fixture.addFileToProject("app/build.gradle.kts", "plugins {}\n").virtualFile
    val mockDelegate = mock(Filter::class.java)
    val posixFilter =
      SafeGradleConsoleFilter(
        projectRule.project,
        LocalFileSystem.getInstance(),
        mockDelegate,
        isWindows = false,
      )
    val leadingBackslashLine = "Build file '${virtualFile.path.replace('/', '\\')}' line: 1"
    val mixedBackslashLine = "Build file '${virtualFile.parent.path}\\${virtualFile.name}' line: 1"

    assertThat(posixFilter.applyFilter(leadingBackslashLine, leadingBackslashLine.length)).isNull()
    assertThat(posixFilter.applyFilter(mixedBackslashLine, mixedBackslashLine.length)).isNull()

    verifyNoInteractions(mockDelegate)
  }

  @Test
  fun applyFilter_windowsShortNamesAndUncachedDriveRoots_returnNullWithoutVfsOrDelegateLookup() {
    val mockVfs = mock(LocalFileSystem::class.java)
    val mockDelegate = mock(Filter::class.java)
    val windowsFilter =
      SafeGradleConsoleFilter(
        projectRule.project,
        mockVfs,
        mockDelegate,
        isWindows = true,
        cachedWindowsDriveRootsProvider = { setOf('C') },
      )

    val blockedLines =
      listOf(
        "Build file 'C:\\EVIL~1\\build.gradle' line: 1",
        "Build file 'C:/Users/JANE~1/build.gradle' line: 1",
        "Build file 'Z:\\share\\build.gradle' line: 1",
        "Build file 'Z:/share/build.gradle' line: 1",
        "Build file '/Z:/share/build.gradle' line: 1",
      )

    for (line in blockedLines) {
      assertThat(windowsFilter.applyFilter(line, line.length)).named(line).isNull()
    }
    verifyNoInteractions(mockVfs)
    verifyNoInteractions(mockDelegate)

    val cachedFile = projectRule.fixture.addFileToProject("build.gradle", "plugins {}\n").virtualFile
    whenever(mockVfs.findFileByPathIfCached("C:/project/build.gradle")).thenReturn(cachedFile)
    val expectedResult = Filter.Result(0, 10, null)
    val validWindowsLine = "Build file 'C:\\project\\build.gradle' line: 1"
    whenever(mockDelegate.applyFilter(validWindowsLine, validWindowsLine.length)).thenReturn(expectedResult)

    assertThat(windowsFilter.applyFilter(validWindowsLine, validWindowsLine.length)).isSameAs(expectedResult)
    verify(mockVfs).findFileByPathIfCached("C:/project/build.gradle")
  }

  @Test
  @RunsInEdt
  fun androidTestSuiteView_withGradleRawOutputFilters_linksCachedFilesAndBlocksUnc() {
    val buildFile = File(projectRule.project.basePath!!, "build.gradle.kts").apply { writeText("plugins {}\n\n") }
    val sourceFile = File(projectRule.project.basePath!!, "MainActivity.kt").apply { writeText("class MainActivity\n\n") }
    val uncachedBuildFile = File(projectRule.project.basePath!!, "uncached/nonexistent/build.gradle")
    val buildVf = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(buildFile))
    val sourceVf = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sourceFile))

    val view = AndroidTestSuiteView(disposableRule.disposable, projectRule.project, projectRule.module)
    createGradleRawOutputFilters(projectRule.project).forEach(view::addMessageFilter)

    val rawConsole = view.myDetailsView.rawTestLogConsoleView
    rawConsole.component
    rawConsole.ancestorListeners.forEach {
      it.ancestorAdded(AncestorEvent(rawConsole, AncestorEvent.ANCESTOR_ADDED, rawConsole, rawConsole.parent))
    }

    view.print(
      "Build file '${buildFile.absolutePath}' line: 2\n" +
        "e: ${sourceFile.absolutePath}:1:1: Compilation error\n" +
        "Build file '\\\\10.0.0.2\\share\\build.gradle' line: 1\n" +
        "Build file '${uncachedBuildFile.absolutePath}' line: 1\n",
      ConsoleViewContentType.ERROR_OUTPUT,
    )

    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    rawConsole.waitAllRequests()
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    val hyperlinks = checkNotNull(rawConsole.getHyperlinks())
    hyperlinks.waitForPendingFilters(5_000)

    val buildLinks = hyperlinks.findAllHyperlinksOnLine(0)
    assertThat(buildLinks).hasSize(1)
    assertThat((EditorHyperlinkSupport.getHyperlinkInfo(buildLinks.single()) as OpenFileHyperlinkInfo).virtualFile).isEqualTo(buildVf)

    val sourceLinks = hyperlinks.findAllHyperlinksOnLine(1)
    assertThat(sourceLinks).hasSize(1)
    assertThat((EditorHyperlinkSupport.getHyperlinkInfo(sourceLinks.single()) as OpenFileHyperlinkInfo).virtualFile).isEqualTo(sourceVf)

    assertThat(hyperlinks.findAllHyperlinksOnLine(2)).isEmpty()
    assertThat(hyperlinks.findAllHyperlinksOnLine(3)).isEmpty()
  }
}
