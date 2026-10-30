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
package com.android.tools.idea.gradle.project.build.invoker.runsGradleBuild

import com.android.tools.idea.gradle.project.sync.snapshots.TestProject
import com.android.tools.idea.gradle.project.sync.snapshots.TestProjectDefinition.Companion.prepareTestProject
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import com.android.tools.idea.testing.buildAndWait
import com.google.common.truth.Truth
import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.replaceService
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.gradle.tooling.ProjectConnection
import org.jetbrains.plugins.gradle.connection.GradleConnectorService
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy

class GradleSaveAndSyncTest {
  @get:Rule val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun testSaveAndSyncIsCalledOnBuild() {
    val simpleApplication = projectRule.prepareTestProject(TestProject.SIMPLE_APPLICATION, "project")
    val saveAndSyncHandler = spy(SaveAndSyncHandler.getInstance())
    projectRule.replaceService(SaveAndSyncHandler::class.java, saveAndSyncHandler)
    simpleApplication.open { project ->
      val result = project.buildAndWait { buildInvoker -> buildInvoker.assemble() }
      val inOrder = inOrder(saveAndSyncHandler)
      inOrder.verify(saveAndSyncHandler).blockSyncOnFrameActivation()
      inOrder.verify(saveAndSyncHandler).unblockSyncOnFrameActivation()
      inOrder.verify(saveAndSyncHandler).scheduleRefresh()
      Truth.assertThat(result.isBuildSuccessful).isTrue()
    }
  }

  @Test
  fun testSavedDocumentPathsAreReportedToGradleConnectionOnBuild() {
    val simpleApplication = projectRule.prepareTestProject(TestProject.SIMPLE_APPLICATION, "project")
    simpleApplication.open { project ->
      val delegateConnectorService = GradleConnectorService.getInstance(project)
      val reportedChangedPaths = CopyOnWriteArrayList<Path>()
      project.replaceService(
        GradleConnectorService::class.java,
        object : GradleConnectorService by delegateConnectorService {
          override fun <R> withGradleConnection(context: GradleExecutionContext, function: (ProjectConnection) -> R): R {
            return delegateConnectorService.withGradleConnection(context) { connection ->
              val wrappedConnection =
                object : ProjectConnection by connection {
                  override fun notifyDaemonsAboutChangedPaths(changedPaths: List<Path>) {
                    reportedChangedPaths.addAll(changedPaths)
                    connection.notifyDaemonsAboutChangedPaths(changedPaths)
                  }
                }
              function(wrappedConnection)
            }
          }
        },
        projectRule.testRootDisposable,
      )

      val buildFile = VfsUtil.findFileByIoFile(simpleApplication.root.resolve("app/build.gradle"), true)!!
      ApplicationManager.getApplication().invokeAndWait {
        runWriteAction {
          val document = FileDocumentManager.getInstance().getDocument(buildFile)!!
          document.setText(document.text + "\n// modified\n")
        }
      }

      val result = project.buildAndWait { buildInvoker -> buildInvoker.assemble() }
      Truth.assertThat(result.isBuildSuccessful).isTrue()
      Truth.assertThat(reportedChangedPaths).contains(buildFile.toNioPath())
    }
  }
}
