/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.testartifacts.instrumented.testsuite.adapter

import com.android.annotations.concurrency.AnyThread
import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.SyncException
import com.android.ddmlib.TimeoutException
import com.android.ddmlib.testrunner.IInstrumentationResultParser.StatusKeys.DDMLIB_LOGCAT
import com.android.ddmlib.testrunner.ITestRunListener
import com.android.ddmlib.testrunner.TestIdentifier
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultListener
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuite
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuiteResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.benchmark.BenchmarkOutput
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Key
import com.intellij.psi.util.ClassUtil
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** An adapter to translate [ITestRunListener] and [ProcessListener] callback methods into [AndroidTestResultListener]. */
class DdmlibTestRunListenerAdapter(private val myIDevice: IDevice, private val listener: AndroidTestResultListener) :
  ITestRunListener, ProcessListener {

  companion object {
    private val logger = Logger.getInstance(DdmlibTestRunListenerAdapter::class.java)
    const val BENCHMARK_TEST_METRICS_KEY = "android.studio.display.benchmark"
    // V2
    const val BENCHMARK_V2_TEST_METRICS_KEY = "android.studio.v2display.benchmark"
    const val BENCHMARK_PATH_TEST_METRICS_KEY = "android.studio.v2display.benchmark.outputDirPath"
    // V3
    const val BENCHMARK_V3_TEST_METRICS_KEY = "android.studio.v3display.benchmark"
    const val BENCHMARK_V3_PATH_TEST_METRICS_KEY = "android.studio.v3display.benchmark.outputDirPath"

    private val benchmarkPrefixRegex = "^benchmark:( )?".toRegex(RegexOption.MULTILINE)

    /**
     * Gets the benchmark output from the [testMetrics] map. The order of the keys is important here, given we look at the first key (in
     * that specific order) that exists in [testMetrics].
     *
     * We do this because, benchmark output is versioned, and we typically want outputs in the latest version while gracefully falling back
     * to a prior version if that version of the output is not a part of [testMetrics]. That might happen when an older version of the
     * `androidx.benchmark` library might be being used.
     */
    private fun getBenchmarkOutput(testMetrics: MutableMap<String, String>, orderedKeys: List<String>): String {
      for (key in orderedKeys) {
        if (testMetrics.containsKey(key)) {
          return benchmarkPrefixRegex.replace(testMetrics.getOrDefault(key, ""), "")
        }
      }
      return "" // Default to empty
    }
  }

  private val myDevice = convertIDeviceToAndroidDevice(myIDevice)

  private lateinit var myTestSuite: AndroidTestSuite
  private val myTestCases = mutableMapOf<TestIdentifier, AndroidTestCase>()

  // This map keeps track of number of rerun of the same test method.
  // This value is used to create a unique identifier for each test case
  // yet to be able to group them together across multiple devices.
  private val myTestCaseRunCount: MutableMap<String, Int> = mutableMapOf()

  init {
    listener.onTestSuiteScheduled(myDevice)
  }

  @Synchronized
  @AnyThread
  override fun testRunStarted(runName: String, testCount: Int) {
    myTestSuite = AndroidTestSuite(runName, runName, testCount)
    listener.onTestSuiteStarted(myDevice, myTestSuite)
  }

  @Synchronized
  @AnyThread
  override fun testStarted(testId: TestIdentifier) {
    val fullyQualifiedTestMethodName = "${testId.className}#${testId.testName}"
    val testCaseRunCount = myTestCaseRunCount.compute(fullyQualifiedTestMethodName) { _, currentValue -> currentValue?.plus(1) ?: 0 }
    val testCase =
      AndroidTestCase(
        "${testId} - ${testCaseRunCount}",
        testId.testName,
        ClassUtil.extractClassName(testId.className),
        ClassUtil.extractPackageName(testId.className),
        AndroidTestCaseResult.IN_PROGRESS,
        startTimestampMillis = System.currentTimeMillis(),
      )
    myTestCases[testId] = testCase
    listener.onTestCaseStarted(myDevice, myTestSuite, testCase)
  }

  @Synchronized
  @AnyThread
  override fun testFailed(testId: TestIdentifier, trace: String) {
    val testCase = myTestCases.getValue(testId)
    testCase.result = AndroidTestCaseResult.FAILED
    testCase.errorStackTrace = trace
    myTestSuite.result = AndroidTestSuiteResult.FAILED
  }

  @Synchronized
  @AnyThread
  override fun testAssumptionFailure(testId: TestIdentifier, trace: String) {
    val testCase = myTestCases.getValue(testId)
    testCase.result = AndroidTestCaseResult.SKIPPED
    testCase.errorStackTrace = trace
  }

  @Synchronized
  @AnyThread
  override fun testIgnored(testId: TestIdentifier) {
    val testCase = myTestCases.getValue(testId)
    testCase.result = AndroidTestCaseResult.SKIPPED
  }

  @Synchronized
  @AnyThread
  override fun testEnded(testId: TestIdentifier, testMetrics: MutableMap<String, String>) {
    val testCase = myTestCases.getValue(testId)
    if (!testCase.result.isTerminalState) {
      testCase.result = AndroidTestCaseResult.PASSED
    }
    val logcatMessage = testMetrics.getOrDefault(DDMLIB_LOGCAT, "")
    if (logcatMessage.isNotEmpty()) {
      testCase.logcat = logcatMessage
    }
    testCase.benchmark =
      getBenchmarkOutput(
        testMetrics = testMetrics,
        orderedKeys = listOf(BENCHMARK_V3_TEST_METRICS_KEY, BENCHMARK_V2_TEST_METRICS_KEY, BENCHMARK_TEST_METRICS_KEY),
      )
    testCase.endTimestampMillis = System.currentTimeMillis()
    // When copying outputs use the V2 format for ease. This way we don't need to prune path parameters.
    copyBenchmarkFilesIfNeeded(
      benchmark =
        getBenchmarkOutput(testMetrics = testMetrics, orderedKeys = listOf(BENCHMARK_V2_TEST_METRICS_KEY, BENCHMARK_TEST_METRICS_KEY)),
      deviceRoot = testMetrics.getOrDefault(BENCHMARK_PATH_TEST_METRICS_KEY, ""),
    )
    listener.onTestCaseFinished(myDevice, myTestSuite, testCase)
  }

  @Synchronized
  @AnyThread
  override fun testRunFailed(errorMessage: String) {
    myTestSuite.result = AndroidTestSuiteResult.ABORTED
  }

  @Synchronized
  @AnyThread
  override fun testRunStopped(elapsedTime: Long) {
    myTestSuite.result = AndroidTestSuiteResult.CANCELLED
  }

  @Synchronized
  @AnyThread
  override fun testRunEnded(elapsedTime: Long, runMetrics: MutableMap<String, String>) {
    // Ddmlib calls testRunEnded() callback if the target app process has crashed or
    // killed manually. (For example, if you click "stop" run button from Android Studio,
    // it kills the app process. Thus, we update test results to cancelled for all
    // pending tests.)
    if (!this::myTestSuite.isInitialized) {
      myTestSuite = AndroidTestSuite("", "", 0, AndroidTestSuiteResult.CANCELLED)
    }

    for (testCase in myTestCases.values) {
      if (!testCase.result.isTerminalState) {
        testCase.result = AndroidTestCaseResult.CANCELLED
        testCase.endTimestampMillis = System.currentTimeMillis()
        myTestSuite.result = myTestSuite.result ?: AndroidTestSuiteResult.CANCELLED
      }
    }

    myTestSuite.result = myTestSuite.result ?: AndroidTestSuiteResult.PASSED
    listener.onTestSuiteFinished(myDevice, myTestSuite)
  }

  private fun copyBenchmarkFilesIfNeeded(benchmark: String, deviceRoot: String) {
    if (benchmark.isBlank() || deviceRoot.isBlank() || deviceRoot.contains("..")) {
      return
    }
    val benchmarkOutput = BenchmarkOutput(benchmark)
    for (line in benchmarkOutput.lines) {
      var match = line.matches
      while (match != null) {
        val link = match.groups[BenchmarkOutput.LINK_GROUP]?.value ?: ""
        if (link.startsWith(BenchmarkOutput.BENCHMARK_TRACE_FILE_PREFIX)) {
          val task =
            object : Task.Backgroundable(null, "Pulling: $link", true) {
              override fun run(indicator: ProgressIndicator) {
                val relativeFilePath = link.removePrefix(BenchmarkOutput.BENCHMARK_TRACE_FILE_PREFIX).substringBefore('?')
                if (!BenchmarkOutput.isSafeRelativeFilePath(relativeFilePath)) {
                  logger.warn("Rejected invalid benchmark trace file path: $relativeFilePath")
                  return
                }
                val tempRoot = BenchmarkOutput.getBenchmarkTraceDirectory()
                val localPath =
                  try {
                    tempRoot.resolve(relativeFilePath).normalize()
                  } catch (e: InvalidPathException) {
                    logger.warn("Rejected benchmark trace path with invalid characters: $relativeFilePath")
                    return
                  }
                if (!localPath.startsWith(tempRoot)) {
                  logger.warn("Rejected benchmark trace path traversal: $relativeFilePath")
                  return
                }
                val localFileName = localPath.fileName?.toString().orEmpty()
                if (!BenchmarkOutput.isBenchmarkTraceFile(localFileName)) {
                  logger.warn("Rejected non-trace benchmark file: $relativeFilePath")
                  return
                }
                val localFile = createLocalTraceFile(tempRoot, relativeFilePath)
                if (localFile == null) {
                  logger.warn("Unable to copy latest trace file ($relativeFilePath) from device (${myIDevice.serialNumber})")
                  return
                }
                pullTraceFile("$deviceRoot/$relativeFilePath", localFile)
              }
            }
          ProgressManager.getInstance().run(task)
        }
        match = match.next()
      }
    }
  }

  /**
   * Pulls [remotePath] into the placeholder [localFile]. If the pull fails, the placeholder is deleted so that it does not block a later
   * pull of the same trace.
   */
  private fun pullTraceFile(remotePath: String, localFile: Path) {
    try {
      myIDevice.pullFile(remotePath, localFile.toString())
    } catch (e: Exception) {
      try {
        Files.deleteIfExists(localFile)
      } catch (deleteError: IOException) {
        e.addSuppressed(deleteError)
      }
      when (e) {
        is IOException,
        is AdbCommandRejectedException,
        is TimeoutException,
        is SyncException -> logger.warn("Failed to pull trace file ($remotePath) from device (${myIDevice.serialNumber})", e)
        else -> throw e
      }
    }
  }

  /**
   * Creates an empty file at [relativeFilePath] under [tempRoot] and returns it, or returns null if the file already exists or cannot be
   * created safely. Parent directories that are symbolic links, or that resolve outside [tempRoot], are rejected. The file is created
   * exclusively, so an existing file or symbolic link at the destination is never written through.
   */
  private fun createLocalTraceFile(tempRoot: Path, relativeFilePath: String): Path? {
    val segments = relativeFilePath.split('/', '\\').filter { it.isNotEmpty() }
    if (segments.isEmpty()) {
      return null
    }
    try {
      var parent = tempRoot
      for (segment in segments.dropLast(1)) {
        parent = parent.resolve(segment)
        try {
          Files.createDirectory(parent).toFile().deleteOnExit()
        } catch (e: FileAlreadyExistsException) {
          // Created earlier, possibly by a concurrent pull of another trace in the same directory. Verified below.
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().startsWith(tempRoot)) {
          return null
        }
      }
      return Files.createFile(parent.resolve(segments.last())).also { it.toFile().deleteOnExit() }
    } catch (e: IOException) {
      return null
    } catch (e: InvalidPathException) {
      return null
    }
  }

  @Synchronized @AnyThread override fun startNotified(event: ProcessEvent) {}

  @Synchronized @AnyThread override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}

  @Synchronized
  @AnyThread
  override fun processTerminated(event: ProcessEvent) {
    event.processHandler.removeProcessListener(this)
    testRunEnded(0, mutableMapOf())
  }
}
