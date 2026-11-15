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
package com.google.idea.blaze.base.run.filter

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.io.VirtualFileSystemProvider
import com.google.idea.blaze.base.issueparser.NonProblemHyperlinkInfo
import com.google.idea.blaze.base.projectview.ProjectViewSet
import com.google.idea.blaze.base.settings.BazelImportSettingsManager
import com.google.idea.blaze.base.settings.BuildSystemName
import com.google.idea.blaze.base.workspace.WorkspaceSwitchManager.getWorkspacePath
import com.google.idea.switcher.VirtualSymlinkFileSystemBackend
import com.google.idea.switcher.VirtualSymlinkPath
import com.intellij.execution.filters.CompositeFilter
import com.intellij.execution.filters.Filter
import com.intellij.mock.MockDumbService
import com.intellij.mock.MockLocalFileSystem
import com.intellij.mock.MockProject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(JUnit4::class)
class BlazeSafeConsoleFiltersTest : BlazeTestCase() {

  companion object {
    // WorkspaceMappingManager's singleton initializes PathManager.getSystemDir() on first access (exercised by the
    // workspace-switcher test below), which requires idea.system.path to be set in headless unit tests.
    @BeforeClass
    @JvmStatic
    fun setUpSystemPath() {
      if (System.getProperty(PathManager.PROPERTY_SYSTEM_PATH) == null) {
        System.setProperty(PathManager.PROPERTY_SYSTEM_PATH, Files.createTempDirectory("idea-system").toString())
      }
    }
  }

  @get:Rule val tempFolder = TemporaryFolder()

  private val fileResolverInvoked = AtomicBoolean(false)
  private var configuredWorkspaceRoot: Path? = null

  override fun initTest(applicationServices: Container, projectServices: Container) {
    fileResolverInvoked.set(false)
    configuredWorkspaceRoot = tempFolder.newFolder("workspace").toPath()
    registerExtensionPoint(FileResolver.EP_NAME, FileResolver::class.java).registerExtension { _, _ ->
      fileResolverInvoked.set(true)
      null
    }
    applicationServices.register(
      VirtualFileSystemProvider::class.java,
      VirtualFileSystemProvider { MockLocalFileSystem() },
    )
    projectServices.register(DumbService::class.java, MockDumbService(project))
    projectServices.register(
      BazelImportSettingsManager::class.java,
      object : BazelImportSettingsManager {
        override fun hasImportSettings(): Boolean = configuredWorkspaceRoot != null

        override val workspaceRoot: Path?
          get() = configuredWorkspaceRoot

        override val projectName: String = "test"
        override val projectViewFilePath: Path? = null
        override val buildSystem: BuildSystemName = BuildSystemName.Bazel
        override val projectViewSet: ProjectViewSet? = null

        override fun reloadProjectView(): ProjectViewSet? = null
      },
    )
  }

  @Test
  fun safeTestLogFilter_linksWorkspaceBazelTestlogsWithoutEagerLookup_andResolvesOnClick() {
    val wsRoot = checkNotNull(configuredWorkspaceRoot)
    val expectedLogPath = "$wsRoot/bazel-testlogs/foo/bar/my_test/test.log"
    val navigatedPath = AtomicReference<String>()
    val filter = newFilter(onNavigate = { _, path -> navigatedPath.set(path) })
    val line = "FAIL: //foo/bar:my_test (see $expectedLogPath)"

    val result = filter.applyFilter(line, line.length)

    assertThat(result).isNotNull()
    assertThat(result!!.nextAction).isEqualTo(Filter.NextAction.CONTINUE_FILTERING)
    assertThat(fileResolverInvoked.get()).isFalse()
    checkNotNull(result.firstHyperlinkInfo).navigate(project)
    assertThat(navigatedPath.get()).isEqualTo(expectedLogPath)

    val standaloneLine = "  $expectedLogPath  \n"
    val standaloneResult = filter.applyFilter(standaloneLine, standaloneLine.length)
    assertThat(standaloneResult).isNotNull()
    val standaloneItem = standaloneResult!!.resultItems.single()
    assertThat(standaloneLine.substring(standaloneItem.highlightStartOffset, standaloneItem.highlightEndOffset)).isEqualTo(expectedLogPath)
  }

  @Test
  fun safeTestLogFilter_defaultNavigate_resolvesFileOffEdtAndSkipsDisposedProject() {
    val wsRoot = checkNotNull(configuredWorkspaceRoot)
    val expectedLogPath = "$wsRoot/bazel-testlogs/foo/bar/my_test/test.log"
    val resolvedPath = AtomicReference<String>()
    val resolvedOnEdt = AtomicBoolean(true)
    val resolveCount = AtomicInteger(0)
    val resolveLatch = CountDownLatch(1)
    FileResolver.EP_NAME.point.registerExtension(
      FileResolver { _, path ->
        resolveCount.incrementAndGet()
        resolvedOnEdt.set(SwingUtilities.isEventDispatchThread())
        resolvedPath.set(path)
        resolveLatch.countDown()
        null
      },
      testDisposable,
    )

    val filter = newFilter()
    val line = "FAIL: //foo/bar:my_test (see $expectedLogPath)"
    val hyperlink = checkNotNull(filter.applyFilter(line, line.length)?.firstHyperlinkInfo)

    val disposedProject = mock(Project::class.java)
    `when`(disposedProject.isDisposed).thenReturn(true)
    SwingUtilities.invokeAndWait { hyperlink.navigate(disposedProject) }
    assertThat(fileResolverInvoked.get()).isFalse()
    assertThat(resolveCount.get()).isEqualTo(0)

    SwingUtilities.invokeAndWait { hyperlink.navigate(project) }
    assertThat(resolveLatch.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(resolveCount.get()).isEqualTo(1)
    assertThat(resolvedOnEdt.get()).isFalse()
    assertThat(resolvedPath.get()).isEqualTo(expectedLogPath)
  }

  @Test
  fun safeTestLogFilter_propagatesCancellationExceptionsFromSymlinkResolution() {
    val mockWorkspaceRoot = mock(Path::class.java)
    `when`(mockWorkspaceRoot.toString()).thenReturn("/workspace")
    `when`(mockWorkspaceRoot.resolve(anyString())).thenThrow(ProcessCanceledException())
    configuredWorkspaceRoot = mockWorkspaceRoot

    val filter = newFilter()
    val line = "FAIL: //foo:bar (see /private/var/tmp/execroot/_main/bazel-out/k8/testlogs/foo/bar/test.log)"

    assertThrows(ProcessCanceledException::class.java) { filter.applyFilter(line, line.length) }
  }

  @Test
  fun getSafeBlazeTestConsoleFilters_linksBothTargetLabelAndTestLogOnSameLine() {
    val wsRoot = checkNotNull(configuredWorkspaceRoot)
    val expectedLogPath = "$wsRoot/bazel-testlogs/foo/bar/my_test/test.log"
    val composite = CompositeFilter(project, getSafeBlazeTestConsoleFilters(project))
    val line = "FAIL: //foo/bar:my_test (see $expectedLogPath)"

    val result = composite.applyFilter(line, line.length)

    assertThat(result).isNotNull()
    val items = result!!.resultItems
    assertThat(items).hasSize(2)
    val highlightedSubstrings = items.map { line.substring(it.highlightStartOffset, it.highlightEndOffset) }
    assertThat(highlightedSubstrings).containsExactly("//foo/bar:my_test", expectedLogPath)
    assertThat(items.count { it.hyperlinkInfo is NonProblemHyperlinkInfo }).isEqualTo(1)
  }

  @Test
  fun safeTestLogFilter_handlesLongMalformedRepeatedTestlogsLinesWithoutBacktracking() {
    val filter = newFilter()
    val repeated = "/testlogs/segment".repeat(2_000)

    assertThat(filter.applyFilter("FAIL: $repeated", 6 + repeated.length)).isNull()
    val suffixLine = "FAIL: (see $repeated/not_a_log)"
    assertThat(filter.applyFilter(suffixLine, suffixLine.length)).isNull()
    val unallowedLongLogLine = "FAIL: (see $repeated/test.log)"
    assertThat(filter.applyFilter(unallowedLongLogLine, unallowedLongLogLine.length)).isNull()
    assertThat(fileResolverInvoked.get()).isFalse()
  }

  @Test
  fun safeTestLogFilter_linksResolvedBazelOutSymlinkTarget() {
    val wsRoot = checkNotNull(configuredWorkspaceRoot)
    val realBazelOut = tempFolder.newFolder("output_base", "execroot", "_main", "bazel-out").toPath().toRealPath()
    Files.createSymbolicLink(wsRoot.resolve("bazel-out"), realBazelOut)

    val filter = newFilter()
    val line = "FAIL: //foo/bar:my_test (see $realBazelOut/k8-fastbuild/testlogs/foo/bar/my_test/test.log)"

    val result = filter.applyFilter(line, line.length)

    assertThat(result).isNotNull()
    assertThat(fileResolverInvoked.get()).isFalse()
  }

  @Test
  fun safeTestLogFilter_cachesResolvedSymlinkTargetsAndInvalidatesOnRootChangeOrTtlExpiry() {
    val wsRoot1 = tempFolder.newFolder("ws1").toPath().toRealPath()
    val realBazelOut1 = tempFolder.newFolder("out1", "execroot", "_main", "bazel-out").toPath().toRealPath()
    Files.createSymbolicLink(wsRoot1.resolve("bazel-out"), realBazelOut1)

    val wsRoot2 = tempFolder.newFolder("ws2").toPath().toRealPath()
    val realBazelOut2 = tempFolder.newFolder("out2", "execroot", "_main", "bazel-out").toPath().toRealPath()
    Files.createSymbolicLink(wsRoot2.resolve("bazel-out"), realBazelOut2)

    val resolveCalls = AtomicInteger(0)
    fun countingRoot(delegate: Path): Path {
      val mockRoot = mock(Path::class.java)
      `when`(mockRoot.toString()).thenReturn(delegate.toString())
      `when`(mockRoot.resolve(anyString())).thenAnswer { invocation ->
        resolveCalls.incrementAndGet()
        delegate.resolve(invocation.getArgument<String>(0))
      }
      return mockRoot
    }

    val clockNanos = AtomicLong(1_000_000L)
    configuredWorkspaceRoot = countingRoot(wsRoot1)
    val filter = newFilter(nanoTime = clockNanos::get)

    val line1 = "FAIL: //foo:bar (see $realBazelOut1/k8-fastbuild/testlogs/foo/bar/test.log)"
    val externalLine = "FAIL: //foo:bar (see /etc/other/testlogs/foo/bar/test.log)"

    repeat(5) {
      assertThat(filter.applyFilter(line1, line1.length)).isNotNull()
      assertThat(filter.applyFilter(externalLine, externalLine.length)).isNull()
    }
    // All 4 workspace testlog subdirs are resolved once and then served from cache across repeated calls.
    assertThat(resolveCalls.get()).isEqualTo(4)

    // Switching workspace root invalidates the cache immediately without waiting for TTL expiry.
    configuredWorkspaceRoot = countingRoot(wsRoot2)
    val line2 = "FAIL: //foo:bar (see $realBazelOut2/k8-fastbuild/testlogs/foo/bar/test.log)"
    assertThat(filter.applyFilter(line1, line1.length)).isNull()
    assertThat(filter.applyFilter(line2, line2.length)).isNotNull()
    assertThat(resolveCalls.get()).isEqualTo(8)

    // Advancing past the TTL triggers a fresh symlink resolution.
    clockNanos.addAndGet(SafeTestLogFilter.REAL_PATH_CACHE_TTL_NANOS)
    assertThat(filter.applyFilter(line2, line2.length)).isNotNull()
    assertThat(resolveCalls.get()).isEqualTo(12)
  }

  @Test
  fun safeTestLogFilter_workspaceSwitcherVirtualSymlinkRoot_linksPhysicalAndExecrootTestLogs() {
    val physicalWorkspace = tempFolder.newFolder("physical_workspace").toPath().toRealPath()
    val realBazelOut = tempFolder.newFolder("execroot_base", "execroot", "_main", "bazel-out").toPath().toRealPath()
    Files.createSymbolicLink(physicalWorkspace.resolve("bazel-out"), realBazelOut)

    val mappedProject =
      object : MockProject(project.picoContainer, testDisposable) {
        override fun getLocationHash(): String = "safe_test_log_${tempFolder.root.name}"
      }
    val rawVirtualRoot = mappedProject.getWorkspacePath()
    Files.createDirectories(rawVirtualRoot.parent)
    Files.deleteIfExists(rawVirtualRoot)
    Files.createSymbolicLink(rawVirtualRoot, physicalWorkspace)
    try {
      val virtualFs = checkNotNull(VirtualSymlinkFileSystemBackend().compute(FileSystems.getDefault(), rawVirtualRoot.toString()))
      val virtualRoot = virtualFs.getPath(rawVirtualRoot.toString())
      assertThat(virtualRoot).isInstanceOf(VirtualSymlinkPath::class.java)
      // VirtualSymlinkPath.toRealPath() keeps external symlinks virtualized rather than resolving into execroot.
      assertThat(virtualRoot.resolve("bazel-out").toRealPath().toString()).isEqualTo("$virtualRoot/bazel-out")

      configuredWorkspaceRoot = virtualRoot
      mappedProject.registerService(BazelImportSettingsManager::class.java, project.getService(BazelImportSettingsManager::class.java))
      val filter = SafeTestLogFilter(mappedProject)

      val virtualLogLine = "FAIL: //foo:bar (see $virtualRoot/bazel-testlogs/foo/bar/test.log)"
      val physicalLogLine = "FAIL: //foo:bar (see $physicalWorkspace/bazel-testlogs/foo/bar/test.log)"
      val execrootLogLine = "FAIL: //foo:bar (see $realBazelOut/k8-fastbuild/testlogs/foo/bar/test.log)"
      val outsideLogLine = "FAIL: //foo:bar (see /etc/other/testlogs/foo/bar/test.log)"

      assertThat(filter.applyFilter(virtualLogLine, virtualLogLine.length)).isNotNull()
      assertThat(filter.applyFilter(physicalLogLine, physicalLogLine.length)).isNotNull()
      assertThat(filter.applyFilter(execrootLogLine, execrootLogLine.length)).isNotNull()
      assertThat(filter.applyFilter(outsideLogLine, outsideLogLine.length)).isNull()
    } finally {
      Files.deleteIfExists(rawVirtualRoot)
    }
  }

  @Test
  fun safeTestLogFilter_rejectsPathsOutsideWorkspaceSymlinksAndUncAndTraversal() {
    val wsRoot = checkNotNull(configuredWorkspaceRoot)
    val realBazelOut = tempFolder.newFolder("output_base", "execroot", "_main", "bazel-out").toPath().toRealPath()
    Files.createSymbolicLink(wsRoot.resolve("bazel-out"), realBazelOut)
    val otherBazelOut = tempFolder.newFolder("other_output_base", "execroot", "_main", "bazel-out").toPath().toRealPath()

    val filter = newFilter()

    val rejectedLines =
      listOf(
        "FAIL: //foo:bar (see /etc/other/testlogs/foo/bar/test.log)",
        "FAIL: //foo:bar (see $wsRoot/src/testlogs/foo/bar/test.log)",
        "FAIL: //foo:bar (see $otherBazelOut/k8-fastbuild/testlogs/foo/bar/test.log)",
        "FAIL: //foo:bar (see /run/user/1000/gvfs/smb-share/bazel-out/k8/testlogs/foo/bar/test.log)",
        "FAIL: //foo:bar (see //10.0.0.2/bazel-out/k8/testlogs/foo/bar/test.log)",
        "FAIL: //foo:bar (see //10.0.0.2/testlogs/foo/bar/test.log)",
        "FAIL: //foo:bar (see $wsRoot/bazel-testlogs/../../etc/testlogs/foo/bar/test.log)",
      )

    for (line in rejectedLines) {
      assertThat(filter.applyFilter(line, line.length)).named(line).isNull()
    }
    assertThat(fileResolverInvoked.get()).isFalse()
  }

  private fun newFilter(
    onNavigate: ((Project, String) -> Unit)? = null,
    nanoTime: () -> Long = System::nanoTime,
  ): SafeTestLogFilter =
    if (onNavigate != null) {
      SafeTestLogFilter(
        project,
        onNavigate = onNavigate,
        physicalWorkspaceRootProvider = { null },
        nanoTime = nanoTime,
      )
    } else {
      SafeTestLogFilter(
        project,
        physicalWorkspaceRootProvider = { null },
        nanoTime = nanoTime,
      )
    }
}
