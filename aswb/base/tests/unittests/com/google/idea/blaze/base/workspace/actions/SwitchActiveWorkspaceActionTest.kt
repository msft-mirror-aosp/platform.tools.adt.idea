/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.workspace.actions

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.workspace.DefaultWorkspaceDiscoverer
import com.google.idea.blaze.base.workspace.WorkspaceDescriptor
import com.google.idea.blaze.base.workspace.WorkspaceDiscoverer
import com.google.idea.blaze.base.workspace.WorkspaceDiscoveryResult
import com.google.idea.blaze.base.workspace.WorkspaceSwitchManager.getWorkspacePath
import com.google.idea.switcher.VirtualSymlinkFileSystemBackend
import com.intellij.mock.MockProject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.awt.event.KeyEvent
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.KeyStroke
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock

@RunWith(JUnit4::class)
class SwitchActiveWorkspaceActionTest : BlazeTestCase() {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpSystemPath() {
      if (System.getProperty(PathManager.PROPERTY_SYSTEM_PATH) == null) {
        System.setProperty(PathManager.PROPERTY_SYSTEM_PATH, Files.createTempDirectory("idea-system").toString())
      }
    }
  }

  @get:Rule val tempFolder = TemporaryFolder()

  override fun initTest(applicationServices: Container, projectServices: Container) {
    super.initTest(applicationServices, projectServices)
    applicationServices.register(ActionManager::class.java, mock(ActionManager::class.java))
  }

  @Test
  fun defaultWorkspaceDiscoverer_unmappedTarget_enablesDirectoryBrowsing() {
    val result = DefaultWorkspaceDiscoverer().discoverWorkspaces(project)

    assertThat(result.workspaces).isEmpty()
    assertThat(result.canBrowseDirectories).isTrue()
  }

  @Test
  fun defaultWorkspaceDiscoverer_discoversSiblingDirectoriesAndEnablesBrowsing() {
    val parentDir = tempFolder.newFolder("workspaces").toPath()
    val activeDir = Files.createDirectory(parentDir.resolve("ws_active"))
    val siblingDir = Files.createDirectory(parentDir.resolve("ws_sibling"))
    Files.createFile(parentDir.resolve("not_a_directory.txt"))

    val mappedProject =
      object : MockProject(project.picoContainer, testDisposable) {
        override fun getLocationHash(): String = "mapped_test_project_${tempFolder.root.name}"
      }
    val virtualRoot = mappedProject.getWorkspacePath()
    Files.createDirectories(virtualRoot.parent)
    Files.deleteIfExists(virtualRoot)
    Files.createSymbolicLink(virtualRoot, activeDir)
    try {
      VirtualSymlinkFileSystemBackend().compute(FileSystems.getDefault(), virtualRoot.toString())

      val result = DefaultWorkspaceDiscoverer().discoverWorkspaces(mappedProject)

      assertThat(result.canBrowseDirectories).isTrue()
      assertThat(result.workspaces).containsExactly(WorkspaceDescriptor(siblingDir, "ws_sibling"))
    } finally {
      Files.deleteIfExists(virtualRoot)
    }
  }

  @Test
  fun discoverPopupOptions_propagatesCandidatesAndBrowsingFlagFromDiscoverer() {
    val wsAlpha = Path.of("/tmp/ws_alpha")
    val wsBeta = Path.of("/tmp/ws_beta")
    val extensionPoint = registerExtensionPoint(WorkspaceDiscoverer.EP_NAME, WorkspaceDiscoverer::class.java)
    extensionPoint.registerExtension(
      object : WorkspaceDiscoverer {
        override fun isApplicable(project: Project): Boolean = true

        override fun discoverWorkspaces(project: Project): WorkspaceDiscoveryResult {
          return WorkspaceDiscoveryResult(
            workspaces = listOf(WorkspaceDescriptor(wsBeta, "ws_beta"), WorkspaceDescriptor(wsAlpha, "ws_alpha")),
            canBrowseDirectories = false,
          )
        }
      },
      testDisposable,
    )

    val popupOptions = SwitchActiveWorkspaceAction.discoverPopupOptions(project)

    assertThat(popupOptions.canBrowseDirectories).isFalse()
    assertThat(popupOptions.candidates.map { it.name }).containsExactly("ws_alpha", "ws_beta").inOrder()
  }

  @Test
  fun buildPopupActionGroup_withBrowsingEnabled_appendsSeparatorAndBrowseActionWithSlashShortcut() {
    val candidatePath = Path.of("/tmp/ws-one")
    val popupOptions =
      WorkspacePopupOptions(
        candidates = listOf(WorkspaceCandidate(WorkspaceDescriptor(candidatePath, "ws-one"))),
        canBrowseDirectories = true,
      )
    var browseInvoked = false

    val group =
      SwitchActiveWorkspaceAction.buildPopupActionGroup(
        project = project,
        popupOptions = popupOptions,
        activeTarget = candidatePath,
        onBrowseSelected = { browseInvoked = true },
      )

    val children = group.childActionsOrStubs
    assertThat(children).hasLength(3)
    assertThat(children[0].templateText).isEqualTo("ws-one")
    assertThat(children[1]).isInstanceOf(Separator::class.java)

    val browseAction = children[2]
    assertThat(browseAction.templateText).isEqualTo("Browse...")
    val expectedSlashShortcut = KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, 0), null)
    val expectedDivideShortcut = KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DIVIDE, 0), null)
    assertThat(browseAction.shortcutSet.shortcuts).asList().containsExactly(expectedSlashShortcut, expectedDivideShortcut).inOrder()

    browseAction.actionPerformed(AnActionEvent.createFromAnAction(browseAction, null, "test", DataContext.EMPTY_CONTEXT))
    assertThat(browseInvoked).isTrue()
  }

  @Test
  fun buildPopupActionGroup_emptyCandidatesWithBrowsingEnabled_includesPlaceholderAndBrowseAction() {
    val popupOptions =
      WorkspacePopupOptions(
        candidates = emptyList(),
        canBrowseDirectories = true,
      )

    val group =
      SwitchActiveWorkspaceAction.buildPopupActionGroup(
        project = project,
        popupOptions = popupOptions,
        activeTarget = null,
        onBrowseSelected = {},
      )

    val children = group.childActionsOrStubs
    assertThat(children).hasLength(3)
    assertThat(children[0].templateText).isEqualTo("No workspaces found")
    assertThat(children[1]).isInstanceOf(Separator::class.java)
    assertThat(children[2].templateText).isEqualTo("Browse...")
  }

  @Test
  fun buildPopupActionGroup_withBrowsingDisabled_omitsSeparatorAndBrowseAction() {
    val candidatePath = Path.of("/tmp/ws-one")
    val popupOptions =
      WorkspacePopupOptions(
        candidates = listOf(WorkspaceCandidate(WorkspaceDescriptor(candidatePath, "ws-one"))),
        canBrowseDirectories = false,
      )

    val group =
      SwitchActiveWorkspaceAction.buildPopupActionGroup(
        project = project,
        popupOptions = popupOptions,
        activeTarget = candidatePath,
        onBrowseSelected = {},
      )

    val children = group.childActionsOrStubs
    assertThat(children).hasLength(1)
    assertThat(children[0].templateText).isEqualTo("ws-one")
  }
}
