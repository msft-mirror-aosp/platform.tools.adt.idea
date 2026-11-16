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
package com.android.tools.idea.run

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.projectsystem.PROJECT_SYSTEM_SYNC_TOPIC
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.TaskSupport
import com.intellij.testFramework.registerOrReplaceServiceInstance
import java.awt.EventQueue
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JList
import javax.swing.SwingUtilities
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.mock

@RunWith(JUnit4::class)
class UnsignedApkQuickFixTest {
  @get:Rule val projectRule = AndroidGradleProjectRule()
  private var quickFix: UnsignedApkQuickFix? = null

  @After
  fun dispose() {
    quickFix?.let { Disposer.dispose(it) }
  }

  @Test
  fun updatesBuildWithSelectedConfig() {
    projectRule.load(TestProjectPaths.SIMPLE_APPLICATION)

    // There should be a default debug signing config.
    val module = projectRule.fixture.module
    val buildModel = ProjectBuildModel.get(projectRule.project).getModuleBuildModel(module)!!
    val signingConfigs = buildModel.android().signingConfigs()
    assertThat(signingConfigs).hasSize(1)
    assertThat(signingConfigs[0].name()).isEqualTo("debug")

    // Fake a selector that picks the default debug signing config.
    val fakeSelector =
      object : SigningConfigSelector {
        override fun showAndGet() = true

        override fun selectedConfig() = signingConfigs[0].name()
      }

    // Release build doesn't have a signing config assigned.
    val releaseBuildSigningConfig = buildModel.android().buildTypes().find { it.name() == "release" }?.signingConfig()
    assertThat(releaseBuildSigningConfig?.valueAsString()).isNull()

    val unsignedApkQuickFix = UnsignedApkQuickFix(module, "release", null) { fakeSelector }
    unsignedApkQuickFix.applyFix(DataContext.EMPTY_CONTEXT)
    val updatedBuildModel = ProjectBuildModel.get(projectRule.project).getModuleBuildModel(module)!!

    // Release build is now assigned the debug signing config.
    val expectedSigningConfig = updatedBuildModel.android().buildTypes().find { it.name() == "release" }?.signingConfig()
    assertThat(expectedSigningConfig?.valueAsString()).contains("debug")
  }

  @Test
  fun applyFixFromLockFreeEdtParsesBuildModelOnBackgroundThread() {
    projectRule.load(TestProjectPaths.SIMPLE_APPLICATION)

    val project = projectRule.project
    val module = projectRule.fixture.module
    val app = ApplicationManager.getApplication()

    val initialSigningConfig =
      ReadAction.compute<String?, RuntimeException> {
        ProjectBuildModel.get(project)
          .getModuleBuildModel(module)!!
          .android()
          .buildTypes()
          .find { it.name() == "release" }
          ?.signingConfig()
          ?.valueAsString()
      }
    assertThat(initialSigningConfig).isNull()

    val syncResults = CopyOnWriteArrayList<ProjectSystemSyncManager.SyncResult>()
    val callbackInvocations = AtomicInteger(0)
    project.messageBus
      .connect(projectRule.fixture.testRootDisposable)
      .subscribe(PROJECT_SYSTEM_SYNC_TOPIC, ProjectSystemSyncManager.SyncResultListener { syncResults.add(it) })

    SwingUtilities.invokeAndWait {
      assertThat(EventQueue.isDispatchThread()).isTrue()
      assertThat(app.isReadAccessAllowed).isFalse()
      assertThat(app.isWriteIntentLockAcquired).isFalse()

      // Simulate clicking the Run Configuration "Fix" button (an InputEvent user activity on
      // lock-free Swing EDT: write-safe for TransactionGuard, with neither read nor write-intent lock).
      (TransactionGuard.getInstance() as TransactionGuardImpl).performUserActivity {
        assertThat(EventQueue.isDispatchThread()).isTrue()
        assertThat(app.isReadAccessAllowed).isFalse()
        assertThat(app.isWriteIntentLockAcquired).isFalse()

        val unsignedApkQuickFix =
          UnsignedApkQuickFix(module, "release", { callbackInvocations.incrementAndGet() }) { signingConfigs ->
            assertThat(EventQueue.isDispatchThread()).isTrue()
            assertThat(app.isReadAccessAllowed).isFalse()
            assertThat(app.isWriteIntentLockAcquired).isFalse()
            assertThat(signingConfigs).containsExactly("debug")

            val dialog = SigningConfigSelectorDialog(signingConfigs)
            try {
              dialog.signingConfigComboBox.renderer.getListCellRendererComponent(JList(), signingConfigs.first(), 0, false, false)
              val selected = dialog.selectedConfig()
              assertThat(selected).isEqualTo("debug")
              object : SigningConfigSelector {
                override fun showAndGet() = true

                override fun selectedConfig() = selected
              }
            } finally {
              dialog.close(DialogWrapper.OK_EXIT_CODE)
            }
          }
        quickFix = unsignedApkQuickFix
        unsignedApkQuickFix.applyFix(DataContext.EMPTY_CONTEXT)
      }
    }

    assertThat(syncResults).containsExactly(ProjectSystemSyncManager.SyncResult.SUCCESS)
    assertThat(callbackInvocations.get()).isEqualTo(1)

    val (updatedSigningConfig, buildFileText) =
      ReadAction.compute<Pair<String?, String>, RuntimeException> {
        val updatedBuildModel = ProjectBuildModel.get(project).getModuleBuildModel(module)!!
        val value = updatedBuildModel.android().buildTypes().find { it.name() == "release" }?.signingConfig()?.valueAsString()
        value to VfsUtilCore.loadText(updatedBuildModel.virtualFile)
      }
    assertThat(updatedSigningConfig).contains("debug")
    assertThat(buildFileText).contains("signingConfigs.debug")
  }

  @Suppress("UnstableApiUsage")
  @Test
  fun applyFixHandlesModalProgressCancellationAndNullBuildModel() {
    projectRule.load(TestProjectPaths.SIMPLE_APPLICATION)

    val project = projectRule.project
    val module = projectRule.fixture.module
    val app = ApplicationManager.getApplication()
    val buildFile =
      ReadAction.compute<VirtualFile, RuntimeException> {
        ProjectBuildModel.get(project).getModuleBuildModel(module)!!.virtualFile
      }
    val originalBuildFileText = ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(buildFile) }

    val syncResults = CopyOnWriteArrayList<ProjectSystemSyncManager.SyncResult>()
    val callbackInvocations = AtomicInteger(0)
    project.messageBus
      .connect(projectRule.fixture.testRootDisposable)
      .subscribe(PROJECT_SYSTEM_SYNC_TOPIC, ProjectSystemSyncManager.SyncResultListener { syncResults.add(it) })

    val realTaskSupport = app.service<TaskSupport>()
    var cancelTitle: String? = null
    app.registerOrReplaceServiceInstance(
      TaskSupport::class.java,
      object : TaskSupport by realTaskSupport {
        override fun <T> runWithModalProgressBlockingInternal(
          owner: ModalTaskOwner,
          title: String,
          cancellation: TaskCancellation,
          action: suspend CoroutineScope.() -> T,
        ): T {
          val effectiveAction: suspend CoroutineScope.() -> T =
            if (title == cancelTitle) {
              { throw ProcessCanceledException() }
            } else {
              action
            }
          return realTaskSupport.runWithModalProgressBlockingInternal(owner, title, cancellation, effectiveAction)
        }
      },
      projectRule.fixture.testRootDisposable,
    )

    val selectorInvocations = AtomicInteger(0)
    val runOnLockFreeEdt = { block: () -> Unit ->
      SwingUtilities.invokeAndWait {
        (TransactionGuard.getInstance() as TransactionGuardImpl).performUserActivity { block() }
      }
    }

    // 1. Cancelling during "Loading Signing Configurations..." returns cleanly without opening the selector.
    cancelTitle = "Loading Signing Configurations..."
    runOnLockFreeEdt {
      val fix =
        UnsignedApkQuickFix(module, "release", { callbackInvocations.incrementAndGet() }) {
          selectorInvocations.incrementAndGet()
          throw AssertionError("Selector should not be invoked when loading is cancelled")
        }
      quickFix = fix
      fix.applyFix(DataContext.EMPTY_CONTEXT)
    }
    assertThat(selectorInvocations.get()).isEqualTo(0)
    assertThat(syncResults).isEmpty()
    assertThat(callbackInvocations.get()).isEqualTo(0)
    assertThat(ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(buildFile) }).isEqualTo(originalBuildFileText)

    // 2. Cancelling during "Applying Signing Configuration..." returns cleanly without modifying build file or syncing.
    cancelTitle = "Applying Signing Configuration..."
    runOnLockFreeEdt {
      val fix =
        UnsignedApkQuickFix(module, "release", { callbackInvocations.incrementAndGet() }) { signingConfigs ->
          selectorInvocations.incrementAndGet()
          assertThat(signingConfigs).containsExactly("debug")
          object : SigningConfigSelector {
            override fun showAndGet() = true

            override fun selectedConfig() = "debug"
          }
        }
      quickFix = fix
      fix.applyFix(DataContext.EMPTY_CONTEXT)
    }
    assertThat(selectorInvocations.get()).isEqualTo(1)
    assertThat(syncResults).isEmpty()
    assertThat(callbackInvocations.get()).isEqualTo(0)
    assertThat(ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(buildFile) }).isEqualTo(originalBuildFileText)

    // 3. A genuinely missing Gradle build model still throws IllegalStateException rather than being treated as cancellation.
    cancelTitle = null
    WriteAction.runAndWait<IOException> { buildFile.delete(this) }
    runOnLockFreeEdt {
      val fix =
        UnsignedApkQuickFix(module, "release", { callbackInvocations.incrementAndGet() }) {
          throw AssertionError("Selector should not be invoked when build model is null")
        }
      quickFix = fix
      val error = assertThrows(IllegalStateException::class.java) { fix.applyFix(DataContext.EMPTY_CONTEXT) }
      assertThat(error).hasMessageThat().isEqualTo("Gradle build model should not be null for module: ${module.name}.")
    }
    assertThat(syncResults).isEmpty()
    assertThat(callbackInvocations.get()).isEqualTo(0)
  }

  @Test
  fun differentModuleReCaches() {
    val module1 = mock<Module>()
    UnsignedApkQuickFix.unsignedApkQuickFix = UnsignedApkQuickFix(module1, "release", null)

    val module2 = mock<Module>()
    quickFix = UnsignedApkQuickFix.create(module2, "release", null)

    assertEquals(module2, quickFix!!.module)

    Disposer.dispose(module1)
    Disposer.dispose(module2)
  }

  @Test
  fun differentBuildTypeReCaches() {
    val module1 = mock<Module>()
    UnsignedApkQuickFix.unsignedApkQuickFix = UnsignedApkQuickFix(module1, "release", null)

    quickFix = UnsignedApkQuickFix.create(module1, "debug", null)

    assertEquals("debug", quickFix!!.selectedBuildTypeName)

    Disposer.dispose(module1)
  }

  /**
   * Tests the case where validation has been run already, but then the {@code AndroidRunConfigurationEditor} is opened, creating a new
   * QuickFix where the callback would trigger the editor revalidation.
   */
  @Test
  fun nonNullCallbackReCachesIfCurrentlyNull() {
    val module1 = mock<Module>()
    UnsignedApkQuickFix.unsignedApkQuickFix = UnsignedApkQuickFix(module1, "release", null)

    val callback = mock<Runnable>()
    quickFix = UnsignedApkQuickFix.create(module1, "release", callback)

    assertEquals(callback, quickFix!!.callback)

    Disposer.dispose(module1)
  }

  /**
   * Tests the case where the {@code AndroidRunConfigurationEditor} has already set a revalidation callback, but a different validation
   * request would have created a new QuickFix. In this case, we do not want to overwrite the cache.
   */
  @Test
  fun nullCallbackDoesNotReCache() {
    val module1 = mock<Module>()
    val callback = mock<Runnable>()
    UnsignedApkQuickFix.unsignedApkQuickFix = UnsignedApkQuickFix(module1, "release", callback)

    quickFix = UnsignedApkQuickFix.create(module1, "release", null)

    assertEquals(callback, quickFix!!.callback)

    Disposer.dispose(module1)
  }

  /**
   * Tests the case where a different callback is requested, e.g. if the {@code AndroidRunConfigurationEditor} dialog is opened again,
   * meaning the new dialog would need to receive the revalidation request.
   */
  @Test
  fun differentCallbackReCaches() {
    val module1 = mock<Module>()
    val callback = mock<Runnable>()
    UnsignedApkQuickFix.unsignedApkQuickFix = UnsignedApkQuickFix(module1, "release", callback)

    val callback2 = mock<Runnable>()
    quickFix = UnsignedApkQuickFix.create(module1, "release", callback2)

    assertEquals(callback2, quickFix!!.callback)

    Disposer.dispose(module1)
  }
}
