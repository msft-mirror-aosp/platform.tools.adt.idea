/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.sdk.wizard

import com.android.repository.api.DelegatingProgressIndicator
import com.android.repository.api.Installer
import com.android.repository.api.InstallerFactory
import com.android.repository.api.LocalPackage
import com.android.repository.api.PackageOperation
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoPackage
import com.android.repository.api.Uninstaller
import com.android.repository.api.UpdatablePackage
import com.android.repository.impl.meta.RepositoryPackages
import com.android.repository.testframework.FakePackage.FakeLocalPackage
import com.android.repository.testframework.FakePackage.FakeRemotePackage
import com.android.repository.testframework.FakeProgressIndicator
import com.android.repository.testframework.FakeRepoManager
import com.android.repository.testframework.FakeSettingsController
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.android.testutils.waitForCondition
import com.android.tools.idea.concurrency.pumpEventsAndWaitForFuture
import com.android.tools.idea.progress.StudioProgressIndicatorAdapter
import com.android.tools.idea.wizard.model.ModelWizard
import com.android.tools.idea.wizard.model.ModelWizard.WizardListener
import com.android.tools.idea.wizard.model.ModelWizard.WizardResult
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.jetbrains.android.AndroidTestCase
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

/**
 * Tests for [InstallSelectedPackagesStep].
 *
 * TODO: this does not include tests for in-process installs.
 */
class InstallTaskTest : AndroidTestCase() {
  private val sdkRoot = createInMemoryFileSystemAndFolder("sdk")
  private val progressIndicator: ProgressIndicator = FakeProgressIndicator()

  private lateinit var available1: RemotePackage
  private lateinit var available2: RemotePackage
  private lateinit var installer: Installer
  private lateinit var installer2: Installer
  private lateinit var uninstaller: Uninstaller
  private lateinit var operations: MutableMap<RepoPackage, PackageOperation>
  private lateinit var installTask: InstallTask
  private lateinit var sdkHandler: AndroidSdkHandler
  private lateinit var factory: InstallerFactory
  private lateinit var existing1: LocalPackage

  override fun setUp() {
    super.setUp()
    existing1 = spy(FakeLocalPackage("p1", sdkRoot.resolve("p1")))
    available1 = spy(FakeRemotePackage("p2"))
    available2 = spy(FakeRemotePackage("p3"))
    factory = mock()
    installer = mock()
    installer2 = mock()
    uninstaller = mock()

    val repoPackages = RepositoryPackages(listOf(existing1), listOf(available1, available2))
    val repoManager = FakeRepoManager(sdkRoot, repoPackages)
    sdkHandler = AndroidSdkHandler(sdkRoot, sdkRoot, repoManager)

    whenever(installer.prepare(any())).thenReturn(true)
    whenever(installer2.prepare(any())).thenReturn(true)
    whenever(uninstaller.prepare(any())).thenReturn(true)
    whenever(installer.complete(any())).thenReturn(true)
    whenever(installer2.complete(any())).thenReturn(true)
    whenever(uninstaller.complete(any())).thenReturn(true)

    operations =
      mutableMapOf(
        existing1 to uninstaller,
        available1 to installer,
        available2 to installer2,
      )

    whenever(factory.createInstaller(eq(available1), eq(repoManager), any())).thenReturn(installer)
    whenever(factory.createInstaller(eq(available2), eq(repoManager), any())).thenReturn(installer2)
    whenever(factory.createUninstaller(existing1, repoManager)).thenReturn(uninstaller)

    installTask = createInstallTask()
  }

  private fun createInstallTask(completeCallback: ((List<RepoPackage>) -> Unit)? = null): InstallTask =
    InstallTask(
      installerFactory = factory,
      sdkHandler = sdkHandler,
      settingsController = FakeSettingsController(false),
      logger = progressIndicator,
      installRequests = listOf(UpdatablePackage(available1), UpdatablePackage(available2)),
      uninstallRequests = listOf(existing1),
      completeCallback = completeCallback,
    )

  fun testPrepare() {
    val failures = mutableListOf<RepoPackage>()
    installTask.preparePackages(operations, failures, FakeProgressIndicator())

    verify(installer).prepare(any())
    verify(installer2).prepare(any())
    verify(uninstaller).prepare(any())

    assertTrue(failures.isEmpty())
  }

  fun testPrepareWithErrors() {
    whenever(installer2.prepare(any())).thenReturn(false)

    val failures = mutableListOf<RepoPackage>()
    installTask.preparePackages(operations, failures, FakeProgressIndicator())

    verify(installer).prepare(any())
    verify(installer2).prepare(any())
    verify(uninstaller).prepare(any())

    assertTrue(available2 in failures)
    assertEquals(1, failures.size)
  }

  fun testComplete() {
    val failures = mutableListOf<RepoPackage>()
    installTask.completePackages(
      operations,
      failures,
      FakeProgressIndicator(true),
      FakeProgressIndicator(),
    )

    verify(installer).complete(any())
    verify(installer2).complete(any())
    verify(uninstaller).complete(any())

    assertTrue(failures.isEmpty())
    assertTrue(operations.isEmpty())
  }

  fun testCompleteWithErrors() {
    whenever(installer.complete(any())).thenReturn(false)

    val failures = mutableListOf<RepoPackage>()
    installTask.completePackages(
      operations,
      failures,
      FakeProgressIndicator(true),
      FakeProgressIndicator(),
    )

    verify(installer).complete(any())
    verify(installer2).complete(any())
    verify(uninstaller).complete(any())

    assertTrue(available1 in failures)
    assertEquals(1, failures.size)
    assertTrue(operations.isEmpty())
  }

  fun testRunBasic() {
    installTask.execute(progressIndicator)

    val installer1Calls = inOrder(installer)
    installer1Calls.verify(installer).prepare(any())
    installer1Calls.verify(installer).complete(any())
    val installer2Calls = inOrder(installer2)
    installer2Calls.verify(installer2).prepare(any())
    installer2Calls.verify(installer2).complete(any())
    val uninstallerCalls = inOrder(uninstaller)
    uninstallerCalls.verify(uninstaller).prepare(any())
    uninstallerCalls.verify(uninstaller).complete(any())
  }

  fun testRunCompleteCallback() {
    val complete = mock<(List<RepoPackage>) -> Unit>()
    val installTask = createInstallTask(completeCallback = complete)

    installTask.execute(progressIndicator)

    val callbackCalls = inOrder(installer, complete)
    callbackCalls.verify(installer).prepare(any())
    callbackCalls.verify(installer).complete(any())
    callbackCalls.verify(complete).invoke(emptyList())
  }

  fun testBackground() {
    val factory = mock<InstallerFactory>()
    whenever(factory.createInstaller(eq(available1), any(), any())).thenReturn(installer)

    val installStep =
      InstallSelectedPackagesStep(
        installRequests = listOf(UpdatablePackage(available1)),
        sdkHandler = sdkHandler,
        backgroundable = true,
        factory = factory,
      )
    val listenerAdded = CompletableFuture<Boolean>()
    whenever(installer.prepare(any())).thenAnswer {
      // wait until the wizard completion listener is added, or maybe we'll be done too early and not see that the wizard is finished.
      listenerAdded.get()
      // This is the background action
      installStep.extraAction?.actionPerformed(null)
      true
    }
    val completedInstall = CompletableFuture<Boolean>()
    whenever(installer.complete(any())).thenAnswer {
      completedInstall.complete(true)
      true
    }
    assertNotNull(installStep.extraAction)
    val wizard = ModelWizard.Builder().addStep(installStep).build()
    val completedWizard = CompletableFuture<Boolean>()
    wizard.addResultListener(
      object : WizardListener {
        override fun onWizardFinished(result: WizardResult) {
          completedWizard.complete(true)
        }
      }
    )
    listenerAdded.complete(true)
    pumpEventsAndWaitForFuture(completedWizard, 5, TimeUnit.SECONDS)

    // Backgrounding closes the wizard, but the install runs to completion on its own.
    assertTrue(wizard.isFinished)
    pumpEventsAndWaitForFuture(completedInstall, 5, TimeUnit.SECONDS)
    verify(installer).prepare(any())
    verify(installer).complete(any())
    // This would normally be done by the wizard frame
    Disposer.dispose(wizard)
  }

  fun testCancelWizardCancelsInstall() {
    val factory = mock<InstallerFactory>()
    whenever(factory.createInstaller(eq(available1), any(), any())).thenReturn(installer)

    val installStep =
      InstallSelectedPackagesStep(
        installRequests = listOf(UpdatablePackage(available1)),
        sdkHandler = sdkHandler,
        backgroundable = true,
        factory = factory,
      )
    val prepareStarted = CompletableFuture<Boolean>()
    val sawCancellation = CompletableFuture<Boolean>()
    whenever(installer.prepare(any())).thenAnswer { invocation ->
      // StudioDownloader wraps the indicator in a StudioProgressIndicatorAdapter and polls checkCanceled() as bytes arrive.
      val progress = StudioProgressIndicatorAdapter(invocation.arguments[0] as ProgressIndicator)
      prepareStarted.complete(true)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      try {
        while (System.nanoTime() < deadline) {
          progress.checkCanceled()
          Thread.sleep(10)
        }
      } catch (e: ProcessCanceledException) {
        sawCancellation.complete(true)
        throw e
      }
      false
    }

    val wizard = ModelWizard.Builder().addStep(installStep).build()
    pumpEventsAndWaitForFuture(prepareStarted, 5, TimeUnit.SECONDS)

    wizard.cancel()
    Disposer.dispose(wizard)

    pumpEventsAndWaitForFuture(sawCancellation, 10, TimeUnit.SECONDS)
    verify(installer, never()).complete(any())
  }

  fun testProgressWithBackgrounding() {
    val factory = mock<InstallerFactory>()
    val p3 = spy(FakeRemotePackage("p4"))
    val p4 = spy(FakeRemotePackage("p5"))
    whenever(factory.createInstaller(eq(available1), any(), any())).thenReturn(installer)
    whenever(factory.createInstaller(eq(available2), any(), any())).thenReturn(installer2)
    val installer3 = mock<Installer>()
    whenever(factory.createInstaller(eq(p3), any(), any())).thenReturn(installer3)
    val installer4 = mock<Installer>()
    whenever(factory.createInstaller(eq(p4), any(), any())).thenReturn(installer4)

    val installStep =
      InstallSelectedPackagesStep(
        installRequests =
          listOf(
            UpdatablePackage(available1),
            UpdatablePackage(available2),
            UpdatablePackage(p3),
            UpdatablePackage(p4),
          ),
        sdkHandler = sdkHandler,
        backgroundable = true,
        factory = factory,
      )
    val listenerAdded = CompletableFuture<Boolean>()
    var capturedProgress: ProgressIndicator? = null
    whenever(installer.prepare(any())).thenAnswer { invocation ->
      val delegating = invocation.arguments[0] as DelegatingProgressIndicator
      capturedProgress = delegating.delegates.first()
      assertEquals(0.0, capturedProgress?.fraction)
      // wait until the wizard completion listener is added, or maybe we'll be done too early and not see that the wizard is finished.
      listenerAdded.get()
      true
    }
    whenever(installer2.prepare(any())).thenAnswer {
      // At this point we're 1/8 done = one preparation out of four preparations + four completions.
      assertEquals(0.125, capturedProgress?.fraction)
      true
    }
    whenever(installer3.prepare(any())).thenAnswer {
      assertEquals(0.25, capturedProgress?.fraction)
      // This is the background action
      installStep.extraAction?.actionPerformed(null)
      true
    }
    whenever(installer4.prepare(any())).thenAnswer {
      // Backgrounding doesn't change what's left to do, so the progress scale is unaffected: we're still 3/8 done.
      assertEquals(0.375, capturedProgress?.fraction)
      true
    }
    for (i in listOf(installer, installer2, installer3, installer4)) {
      whenever(i.complete(any())).thenReturn(true)
    }
    var wizard: ModelWizard? = null
    try {
      wizard = ModelWizard.Builder().addStep(installStep).build()
      val completed = CompletableFuture<Boolean>()
      wizard.addResultListener(
        object : WizardListener {
          override fun onWizardFinished(result: WizardResult) {
            completed.complete(true)
          }
        }
      )
      listenerAdded.complete(true)
      pumpEventsAndWaitForFuture(completed, 5, TimeUnit.SECONDS)
      // The above only waits for the wizard to be completed, but the backgrounded step may not be done yet.
      waitForCondition(5, TimeUnit.SECONDS) { capturedProgress?.fraction == 1.0 }
    } finally {
      wizard?.let { Disposer.dispose(it) }
    }
  }

  /** Operations that we're never going to finish have to be abandoned, or they look like they're still being installed. */
  fun testCancellationAbandonsUnfinishedOperations() {
    val canceledProgress = FakeProgressIndicator().apply { cancel() }

    try {
      installTask.execute(canceledProgress)
      fail("Expected the canceled install to throw")
    } catch (expected: ProcessCanceledException) {}

    verify(installer).abandon(any())
    verify(installer2).abandon(any())
    verify(uninstaller).abandon(any())
    verify(installer, never()).complete(any())
  }

  /**
   * The install runs blocking code, which notices that the task was cancelled the way the downloader and file I/O do: through
   * [ProgressManager.checkCanceled], which sees the cancellation of the coroutine running it.
   */
  fun testCancellingTheTaskCancelsTheInstall() {
    val prepareStarted = CompletableFuture<Boolean>()
    val sawCancellation = CompletableFuture<Boolean>()
    whenever(installer.prepare(any())).thenAnswer {
      prepareStarted.complete(true)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      try {
        while (System.nanoTime() < deadline) {
          ProgressManager.checkCanceled()
          Thread.sleep(10)
        }
      } catch (e: ProcessCanceledException) {
        sawCancellation.complete(true)
        throw e
      }
      false
    }

    val job = installTask.runAsync()
    val finished = CompletableFuture<Boolean>()
    job.invokeOnCompletion { finished.complete(true) }

    pumpEventsAndWaitForFuture(prepareStarted, 5, TimeUnit.SECONDS)
    job.cancel()

    pumpEventsAndWaitForFuture(sawCancellation, 10, TimeUnit.SECONDS)
    pumpEventsAndWaitForFuture(finished, 10, TimeUnit.SECONDS)
    // The install never got to the packages it hadn't started on, so nothing is working on them any more.
    verify(installer2, never()).prepare(any())
    verify(installer2).abandon(any())
  }

  private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)

  private inline fun <reified T : Any> spy(value: T): T = Mockito.spy(value)
}
