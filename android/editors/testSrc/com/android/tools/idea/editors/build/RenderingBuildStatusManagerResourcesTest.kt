/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.editors.build

import com.android.tools.idea.concurrency.awaitStatus
import com.android.tools.idea.editors.fast.simulateResourcesChange
import com.android.tools.idea.projectsystem.ProjectSystemBuildManager
import com.android.tools.idea.rendering.tokens.FakeBuildSystemFilePreviewServices
import com.android.tools.idea.res.AndroidPsiTreeChangeListener
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.ui.ApplicationUtils
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ex.temp.TempFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.VfsTestUtil
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RenderingBuildStatusManagerResourcesTest {
  private val projectRule = AndroidProjectRule.withAndroidModel()
  private val projectRule2 = ProjectRule()

  @get:Rule val ruleChain = RuleChain(projectRule, projectRule2)

  val project: Project
    get() = projectRule.project

  val buildServices = FakeBuildSystemFilePreviewServices()

  @Before
  fun setup() {
    buildServices.register(projectRule.testRootDisposable)
  }

  @Test
  fun testResourcesMakeTheProjectOutOfDate() = runBlocking {
    val psiFile = projectRule.fixture.addFileToProject("/src/a/Test.kt", "fun a() {}")
    val psiFilePointer = runReadAction { SmartPointerManager.createPointer(psiFile) }
    val statusManager = RenderingBuildStatusManager.create(projectRule.fixture.testRootDisposable, psiFilePointer)

    // Simulate a successful build
    buildServices.simulateArtifactBuild(buildStatus = ProjectSystemBuildManager.BuildStatus.SUCCESS)
    statusManager.statusFlow.awaitStatus("Ready state expected", 5.seconds) { it == RenderingBuildStatus.Ready }

    ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
      projectRule.fixture.openFileInEditor(psiFile.virtualFile)
    }

    // Simulate a resources change
    (statusManager as RenderingBuildStatusManagerForTests).simulateResourcesChange()
    statusManager.statusFlow.awaitStatus("OutOfDate expected after a resource change", 5.seconds) { it is RenderingBuildStatus.OutOfDate }

    // A build should restore the ready state
    buildServices.simulateArtifactBuild(buildStatus = ProjectSystemBuildManager.BuildStatus.SUCCESS)
    statusManager.statusFlow.awaitStatus("Ready state expected", 5.seconds) { it == RenderingBuildStatus.Ready }
  }

  @Test
  fun testResourceChangeInOtherProjectDoesNotMakeProjectOutOfDate() = runBlocking {
    val psiFile = projectRule.fixture.addFileToProject("/src/a/Test.kt", "fun a() {}")
    val psiFilePointer = runReadAction { SmartPointerManager.createPointer(psiFile) }
    val statusManager = RenderingBuildStatusManager.create(projectRule.fixture.testRootDisposable, psiFilePointer)

    // Simulate a successful build and initialize app resources (as rendering would do)
    buildServices.simulateArtifactBuild(buildStatus = ProjectSystemBuildManager.BuildStatus.SUCCESS)
    statusManager.statusFlow.awaitStatus("Ready state expected", 5.seconds) { it == RenderingBuildStatus.Ready }
    StudioResourceRepositoryManager.getAppResources(projectRule.module)

    // Simulate a PSI change event for an XML file belonging to a second open project
    val project2 = projectRule2.project
    val otherProjectDir = VfsTestUtil.createDir(TempFileSystem.getInstance().findFileByPath("/")!!, "otherProject/res/values")
    val otherProjectVFile =
      VfsTestUtil.createFile(otherProjectDir, "strings.xml", "<resources><string name=\"app_name\">App1</string></resources>")
    val otherProjectXml = runReadAction { PsiManager.getInstance(project2).findFile(otherProjectVFile)!! }
    val otherProjectPsiDir = runReadAction { otherProjectXml.parent!! }

    writeAction {
      val listener = AndroidPsiTreeChangeListener.getInstance(project)

      val childAddedEvent = PsiTreeChangeEventImpl(PsiManager.getInstance(project2) as PsiManagerEx)
      childAddedEvent.parent = otherProjectPsiDir
      childAddedEvent.child = otherProjectXml
      listener.childAdded(childAddedEvent)

      val childrenChangedEvent = PsiTreeChangeEventImpl(PsiManager.getInstance(project2) as PsiManagerEx)
      childrenChangedEvent.setFile(otherProjectXml)
      listener.childrenChanged(childrenChangedEvent)

      val childRemovedEvent = PsiTreeChangeEventImpl(PsiManager.getInstance(project2) as PsiManagerEx)
      childRemovedEvent.setFile(otherProjectXml)
      childRemovedEvent.child = PsiWhiteSpaceImpl(" ")
      listener.childRemoved(childRemovedEvent)
    }
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    delay(200)
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertThat(statusManager.statusFlow.value).isEqualTo(RenderingBuildStatus.Ready)
  }
}
