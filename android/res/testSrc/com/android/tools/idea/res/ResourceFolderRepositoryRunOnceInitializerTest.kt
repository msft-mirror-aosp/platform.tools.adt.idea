/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.res

import com.android.testutils.waitForCondition
import com.android.tools.idea.res.ResourceFolderRepository.RunOnceInitializer
import com.android.tools.idea.res.ResourceFolderRepository.RunOnceWithReadLockInitializer
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.concurrency.installThreadContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.application
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ResourceFolderRepositoryRunOnceInitializerTest {
  @get:Rule val androidProjectRule = AndroidProjectRule.inMemory()

  @Test
  fun runOnceInitializer_runsOnlyOnce() {
    var invocations = 0
    val init = RunOnceInitializer { invocations++ }

    repeat(20) { init.run() }

    assertThat(invocations).isEqualTo(1)
  }

  @Test
  fun runOnceInitializer_doesNotHaveReadLock() {
    val init = RunOnceInitializer { assertThat(application.isReadAccessAllowed).isFalse() }

    init.run()
  }

  @Test
  fun runOnceWithReadLockInitializer_runsOnlyOnce() {
    var invocations = 0
    val init = RunOnceWithReadLockInitializer { invocations++ }

    repeat(20) { init.run() }

    assertThat(invocations).isEqualTo(1)
  }

  @Test
  fun runOnceWithReadLockInitializer_hasReadLock() {
    val init = RunOnceWithReadLockInitializer { assertThat(application.isReadAccessAllowed).isTrue() }

    init.run()
  }

  @Test
  fun runOnceInitializer_impatientReaderYieldsToWriteActionWithoutInterruptingInFlightInit() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val initEntered = CountDownLatch(1)
      val allowInitToFinish = CountDownLatch(1)
      val wasInterrupted = AtomicBoolean(false)

      val init = RunOnceInitializer {
        invocations.incrementAndGet()
        initEntered.countDown()
        try {
          assertThat(allowInitToFinish.await(10, TimeUnit.SECONDS)).isTrue()
        } catch (_: InterruptedException) {
          wasInterrupted.set(true)
          Thread.currentThread().interrupt()
        }
      }

      val starterFuture = executor.submit { init.run() }
      assertThat(initEntered.await(10, TimeUnit.SECONDS)).isTrue()

      val readerThreadRef = AtomicReference<Thread>()
      val readerAcquiredReadLock = CountDownLatch(1)
      val appEx = ApplicationManagerEx.getApplicationEx()
      val readerFuture =
        executor.submit<Unit> {
          readerThreadRef.set(Thread.currentThread())
          appEx.executeByImpatientReader {
            ReadAction.computeCancellableUnsafe<Unit, RuntimeException> {
              readerAcquiredReadLock.countDown()
              init.run()
            }
          }
        }

      assertThat(readerAcquiredReadLock.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(readerThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")

      val writeActionCompleted = AtomicBoolean(false)
      appEx.invokeLater({ appEx.runWriteAction { writeActionCompleted.set(true) } }, ModalityState.any())
      waitForCondition(10, TimeUnit.SECONDS) { writeActionCompleted.get() }
      val thrown = assertThrows(ExecutionException::class.java) { readerFuture.get(10, TimeUnit.SECONDS) }
      assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)
      assertThat(wasInterrupted.get()).isFalse()
      assertThat(starterFuture.isDone).isFalse()

      allowInitToFinish.countDown()
      starterFuture.get(10, TimeUnit.SECONDS)

      init.run()
      assertThat(invocations.get()).isEqualTo(1)
      assertThat(wasInterrupted.get()).isFalse()
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceInitializer_waitingCallerWithCanceledIndicatorAbortsPromptly() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val initEntered = CountDownLatch(1)
      val allowInitToFinish = CountDownLatch(1)
      val wasInterrupted = AtomicBoolean(false)

      val init = RunOnceInitializer {
        invocations.incrementAndGet()
        initEntered.countDown()
        try {
          assertThat(allowInitToFinish.await(10, TimeUnit.SECONDS)).isTrue()
        } catch (_: InterruptedException) {
          wasInterrupted.set(true)
          Thread.currentThread().interrupt()
        }
      }

      val starterFuture = executor.submit { init.run() }
      assertThat(initEntered.await(10, TimeUnit.SECONDS)).isTrue()

      val waiterThreadRef = AtomicReference<Thread>()
      val waiterEnteredProcess = CountDownLatch(1)
      val indicator = EmptyProgressIndicator()
      val waiterFuture = executor.submit {
        waiterThreadRef.set(Thread.currentThread())
        ProgressManager.getInstance()
          .runProcess(
            {
              waiterEnteredProcess.countDown()
              init.run()
            },
            indicator,
          )
      }

      assertThat(waiterEnteredProcess.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(waiterThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")

      indicator.cancel()

      val thrown = assertThrows(ExecutionException::class.java) { waiterFuture.get(10, TimeUnit.SECONDS) }
      assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)
      assertThat(wasInterrupted.get()).isFalse()

      allowInitToFinish.countDown()
      starterFuture.get(10, TimeUnit.SECONDS)

      init.run()
      assertThat(invocations.get()).isEqualTo(1)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceInitializer_offloadsUnderBackgroundReadLockAndRunsOnce() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val initEntered = CountDownLatch(1)
      val allowInitToFinish = CountDownLatch(1)
      val initFinished = CountDownLatch(1)
      val observedReadAccess = AtomicBoolean(true)
      val wasInterrupted = AtomicBoolean(false)

      val init = RunOnceInitializer {
        observedReadAccess.set(application.isReadAccessAllowed)
        invocations.incrementAndGet()
        initEntered.countDown()
        try {
          assertThat(allowInitToFinish.await(10, TimeUnit.SECONDS)).isTrue()
        } catch (_: InterruptedException) {
          wasInterrupted.set(true)
          Thread.currentThread().interrupt()
        } finally {
          initFinished.countDown()
        }
      }

      val readerThreadRef = AtomicReference<Thread>()
      val readerIndicator = EmptyProgressIndicator()
      val readerFuture = executor.submit {
        readerThreadRef.set(Thread.currentThread())
        ProgressManager.getInstance()
          .runProcess(
            { runReadActionBlocking { init.run() } },
            readerIndicator,
          )
      }

      assertThat(initEntered.await(10, TimeUnit.SECONDS)).isTrue()
      assertThat(observedReadAccess.get()).isFalse()
      waitForWaitingCaller(readerThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")

      val secondCallerThreadRef = AtomicReference<Thread>()
      val secondCallerAcquiredReadLock = CountDownLatch(1)
      val secondCallerFuture = executor.submit {
        secondCallerThreadRef.set(Thread.currentThread())
        runReadActionBlocking {
          secondCallerAcquiredReadLock.countDown()
          init.run()
        }
      }

      assertThat(secondCallerAcquiredReadLock.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(secondCallerThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")
      assertThat(secondCallerFuture.isDone).isFalse()

      // Canceling the initiating reader must abort only that reader's wait, while the offloaded
      // initialization continues uninterrupted and satisfies concurrent/subsequent callers.
      readerIndicator.cancel()
      val thrown = assertThrows(ExecutionException::class.java) { readerFuture.get(10, TimeUnit.SECONDS) }
      assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)
      assertThat(wasInterrupted.get()).isFalse()
      assertThat(secondCallerFuture.isDone).isFalse()

      allowInitToFinish.countDown()
      secondCallerFuture.get(10, TimeUnit.SECONDS)
      assertThat(initFinished.await(10, TimeUnit.SECONDS)).isTrue()

      runReadActionBlocking { init.run() }
      assertThat(invocations.get()).isEqualTo(1)
      assertThat(wasInterrupted.get()).isFalse()
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceInitializer_uncancelledWaiterRetriesWhenInlineStarterIsCanceled() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val firstAttemptEntered = CountDownLatch(1)
      val proceedFirstAttempt = CountDownLatch(1)

      val init = RunOnceInitializer {
        if (invocations.incrementAndGet() == 1) {
          firstAttemptEntered.countDown()
          assertThat(proceedFirstAttempt.await(10, TimeUnit.SECONDS)).isTrue()
          ProgressManager.checkCanceled()
        }
      }

      val starterIndicator = EmptyProgressIndicator()
      val starterFuture = executor.submit {
        ProgressManager.getInstance().runProcess({ init.run() }, starterIndicator)
      }
      assertThat(firstAttemptEntered.await(10, TimeUnit.SECONDS)).isTrue()

      val waiterThreadRef = AtomicReference<Thread>()
      val waiterStarted = CountDownLatch(1)
      val waiterFuture = executor.submit {
        waiterThreadRef.set(Thread.currentThread())
        waiterStarted.countDown()
        init.run()
      }
      assertThat(waiterStarted.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(waiterThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")

      starterIndicator.cancel()
      proceedFirstAttempt.countDown()

      val thrown = assertThrows(ExecutionException::class.java) { starterFuture.get(10, TimeUnit.SECONDS) }
      assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)

      waiterFuture.get(10, TimeUnit.SECONDS)
      init.run()
      assertThat(invocations.get()).isEqualTo(2)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceInitializer_uncancelledWaiterRetriesWhenInlineStarterIsCanceledViaJob() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val firstAttemptEntered = CountDownLatch(1)
      val proceedFirstAttempt = CountDownLatch(1)

      val init = RunOnceInitializer {
        if (invocations.incrementAndGet() == 1) {
          firstAttemptEntered.countDown()
          assertThat(proceedFirstAttempt.await(10, TimeUnit.SECONDS)).isTrue()
          ProgressManager.checkCanceled()
        }
      }

      val starterJob = Job()
      val starterFuture = executor.submit {
        @Suppress("UnstableApiUsage") installThreadContext(starterJob, true) { init.run() }
      }
      assertThat(firstAttemptEntered.await(10, TimeUnit.SECONDS)).isTrue()

      val waiterThreadRef = AtomicReference<Thread>()
      val waiterStarted = CountDownLatch(1)
      val waiterFuture = executor.submit {
        waiterThreadRef.set(Thread.currentThread())
        waiterStarted.countDown()
        init.run()
      }
      assertThat(waiterStarted.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(waiterThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")

      starterJob.cancel()
      proceedFirstAttempt.countDown()

      val thrown = assertThrows(ExecutionException::class.java) { starterFuture.get(10, TimeUnit.SECONDS) }
      val starterCause = thrown.cause
      assertThat(starterCause).isInstanceOf(ProcessCanceledException::class.java)
      assertThat(starterCause!!.cause).isInstanceOf(CancellationException::class.java)
      assertThat(starterCause.cause).isNotInstanceOf(ProcessCanceledException::class.java)

      waiterFuture.get(10, TimeUnit.SECONDS)
      init.run()
      assertThat(invocations.get()).isEqualTo(2)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceInitializer_reentrantCallOnOwnerThreadThrowsIllegalStateException() {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val allowSuccess = AtomicBoolean(false)
      lateinit var inlineInit: RunOnceInitializer
      inlineInit = RunOnceInitializer {
        if (!allowSuccess.get()) {
          inlineInit.run()
        }
      }

      assertThrows(IllegalStateException::class.java) { inlineInit.run() }
      allowSuccess.set(true)
      inlineInit.run()

      val allowOffloadedSuccess = AtomicBoolean(false)
      lateinit var offloadedInit: RunOnceInitializer
      offloadedInit = RunOnceInitializer {
        if (!allowOffloadedSuccess.get()) {
          offloadedInit.run()
        }
      }

      LoggedErrorProcessor.executeWith<RuntimeException>(
        object : LoggedErrorProcessor() {
          override fun processWarn(category: String, message: String, t: Throwable?): Boolean = false
        }
      ) {
        val thrown =
          assertThrows(ExecutionException::class.java) {
            executor.submit { runReadActionBlocking { offloadedInit.run() } }.get(10, TimeUnit.SECONDS)
          }
        assertThat(thrown.cause).isInstanceOf(IllegalStateException::class.java)
      }

      allowOffloadedSuccess.set(true)
      executor.submit { runReadActionBlocking { offloadedInit.run() } }.get(10, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceInitializer_logsWarningWhenOffloadedRunFailsAfterWaiterCanceled() {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val shouldFail = AtomicBoolean(true)
      val initEntered = CountDownLatch(1)
      val allowInitToFail = CountDownLatch(1)
      val expectedFailure = IllegalStateException("Simulated offloaded failure")
      val loggedWarning = AtomicReference<Throwable?>()
      val warningLogged = CountDownLatch(1)

      val init = RunOnceInitializer {
        if (shouldFail.getAndSet(false)) {
          initEntered.countDown()
          assertThat(allowInitToFail.await(10, TimeUnit.SECONDS)).isTrue()
          throw expectedFailure
        }
      }

      LoggedErrorProcessor.executeWith<RuntimeException>(
        object : LoggedErrorProcessor() {
          override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
            if (t === expectedFailure) {
              loggedWarning.set(t)
              warningLogged.countDown()
              return false
            }
            return super.processWarn(category, message, t)
          }
        }
      ) {
        val readerThreadRef = AtomicReference<Thread>()
        val readerIndicator = EmptyProgressIndicator()
        val readerFuture = executor.submit {
          readerThreadRef.set(Thread.currentThread())
          ProgressManager.getInstance()
            .runProcess(
              { runReadActionBlocking { init.run() } },
              readerIndicator,
            )
        }

        assertThat(initEntered.await(10, TimeUnit.SECONDS)).isTrue()
        waitForWaitingCaller(readerThreadRef.get()!!, RunOnceInitializer::class.java, "awaitOrRetry")

        readerIndicator.cancel()
        val thrown = assertThrows(ExecutionException::class.java) { readerFuture.get(10, TimeUnit.SECONDS) }
        assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)

        allowInitToFail.countDown()
        assertThat(warningLogged.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(loggedWarning.get()).isSameAs(expectedFailure)
      }

      init.run()
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceWithReadLockInitializer_waitingCallerAbortsOnCanceledIndicator() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val initEntered = CountDownLatch(1)
      val allowInitToFinish = CountDownLatch(1)

      val init = RunOnceWithReadLockInitializer {
        invocations.incrementAndGet()
        initEntered.countDown()
        assertThat(allowInitToFinish.await(10, TimeUnit.SECONDS)).isTrue()
      }

      val holderFuture = executor.submit { init.run() }
      assertThat(initEntered.await(10, TimeUnit.SECONDS)).isTrue()

      val waiterThreadRef = AtomicReference<Thread>()
      val waiterEnteredProcess = CountDownLatch(1)
      val indicator = EmptyProgressIndicator()
      val waiterFuture = executor.submit {
        waiterThreadRef.set(Thread.currentThread())
        ProgressManager.getInstance()
          .runProcess(
            {
              waiterEnteredProcess.countDown()
              runReadActionBlocking { init.run() }
            },
            indicator,
          )
      }

      assertThat(waiterEnteredProcess.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(waiterThreadRef.get()!!, RunOnceWithReadLockInitializer::class.java, "runUnderReadLock")

      indicator.cancel()

      val thrown = assertThrows(ExecutionException::class.java) { waiterFuture.get(10, TimeUnit.SECONDS) }
      assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)

      allowInitToFinish.countDown()
      holderFuture.get(10, TimeUnit.SECONDS)

      init.run()
      assertThat(invocations.get()).isEqualTo(1)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun runOnceWithReadLockInitializer_impatientReaderAbortsOnPendingWriteAction() {
    val executor = Executors.newFixedThreadPool(2)
    try {
      val invocations = AtomicInteger(0)
      val initEntered = CountDownLatch(1)
      val allowInitToFinish = CountDownLatch(1)

      val init = RunOnceWithReadLockInitializer {
        invocations.incrementAndGet()
        initEntered.countDown()
        assertThat(allowInitToFinish.await(10, TimeUnit.SECONDS)).isTrue()
      }

      val appEx = ApplicationManagerEx.getApplicationEx()
      val holderFuture = executor.submit { appEx.runReadAction { init.run() } }
      assertThat(initEntered.await(10, TimeUnit.SECONDS)).isTrue()

      val readerThreadRef = AtomicReference<Thread>()
      val readerAcquiredReadLock = CountDownLatch(1)
      val readerFuture =
        executor.submit<Unit> {
          readerThreadRef.set(Thread.currentThread())
          appEx.executeByImpatientReader {
            ReadAction.computeCancellableUnsafe<Unit, RuntimeException> {
              readerAcquiredReadLock.countDown()
              init.run()
            }
          }
        }

      assertThat(readerAcquiredReadLock.await(10, TimeUnit.SECONDS)).isTrue()
      waitForWaitingCaller(readerThreadRef.get()!!, RunOnceWithReadLockInitializer::class.java, "runUnderReadLock")

      val writeActionCompleted = AtomicBoolean(false)
      appEx.invokeLater({ appEx.runWriteAction { writeActionCompleted.set(true) } }, ModalityState.any())

      val thrown = assertThrows(ExecutionException::class.java) { readerFuture.get(10, TimeUnit.SECONDS) }
      assertThat(thrown.cause).isInstanceOf(ProcessCanceledException::class.java)

      allowInitToFinish.countDown()
      holderFuture.get(10, TimeUnit.SECONDS)
      waitForCondition(10, TimeUnit.SECONDS) { writeActionCompleted.get() }

      init.run()
      assertThat(invocations.get()).isEqualTo(1)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  private fun waitForWaitingCaller(thread: Thread, clazz: Class<*>, methodName: String) {
    waitForCondition(10, TimeUnit.SECONDS) {
      val stack = thread.stackTrace
      stack.any { it.className == clazz.name && it.methodName == methodName } &&
        stack.any {
          it.className == ProgressIndicatorUtils::class.java.name && it.methodName == "awaitWithCheckCanceled"
        }
    }
  }
}
