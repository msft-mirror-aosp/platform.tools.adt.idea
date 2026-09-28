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

import com.android.ddmlib.IDevice
import com.android.ddmlib.testrunner.IInstrumentationResultParser.StatusKeys.DDMLIB_LOGCAT
import com.android.ddmlib.testrunner.TestIdentifier
import com.android.sdklib.AndroidVersion
import com.android.tools.idea.testartifacts.instrumented.testsuite.adapter.DdmlibTestRunListenerAdapter.Companion.BENCHMARK_PATH_TEST_METRICS_KEY
import com.android.tools.idea.testartifacts.instrumented.testsuite.adapter.DdmlibTestRunListenerAdapter.Companion.BENCHMARK_TEST_METRICS_KEY
import com.android.tools.idea.testartifacts.instrumented.testsuite.adapter.DdmlibTestRunListenerAdapter.Companion.BENCHMARK_V2_TEST_METRICS_KEY
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultListener
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDeviceType
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuite
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuiteResult
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.ProjectRule
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.konan.file.File
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.argThat
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

private const val DEVICE_ROOT = "/device/root/path"

/** Unit tests for [DdmlibTestRunListenerAdapter]. */
class DdmlibTestRunListenerAdapterTest {

  @get:Rule val projectRule = ProjectRule()

  @get:Rule val mockitoJunitRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  @Mock lateinit var mockDevice: IDevice
  @Mock lateinit var mockListener: AndroidTestResultListener

  @Before
  fun setup() {
    whenever(mockDevice.serialNumber).thenReturn("mockDeviceSerialNumber")
    whenever(mockDevice.avdName).thenReturn("mockDeviceAvdName")
    whenever(mockDevice.version).thenReturn(AndroidVersion(29))
    whenever(mockDevice.isEmulator).thenReturn(true)
  }

  private fun eq(arg: AndroidTestCase): AndroidTestCase {
    argThat<AndroidTestCase> {
      arg.copy(startTimestampMillis = 0, endTimestampMillis = 0) == it.copy(startTimestampMillis = 0, endTimestampMillis = 0)
    }
    return arg
  }

  private fun device(id: String = "mockDeviceSerialNumber", name: String = "mockDeviceAvdName"): AndroidDevice {
    return AndroidDevice(
      id,
      name,
      name,
      AndroidDeviceType.LOCAL_EMULATOR,
      AndroidVersion(29),
      mutableMapOf("SerialNumber" to "mockDeviceSerialNumber"),
    )
  }

  @Test
  fun runSuccess() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    verify(mockListener).onTestSuiteScheduled(eq(device()))

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 2)

    verify(mockListener).onTestSuiteStarted(eq(device()), eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)))

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))

    verify(mockListener)
      .onTestCaseStarted(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 0", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.IN_PROGRESS)),
      )

    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest1", 1), mutableMapOf())

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 0", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.PASSED)),
      )

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest2", 2))

    verify(mockListener)
      .onTestCaseStarted(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest2 - 0", "exampleTest2", "exampleTestClass", "", AndroidTestCaseResult.IN_PROGRESS)),
      )

    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest2", 2), mutableMapOf())

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest2 - 0", "exampleTest2", "exampleTestClass", "", AndroidTestCaseResult.PASSED)),
      )

    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    verify(mockListener)
      .onTestSuiteFinished(eq(device()), eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2, AndroidTestSuiteResult.PASSED)))
  }

  @Test
  fun runPartiallyFail() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    verify(mockListener).onTestSuiteScheduled(eq(device()))

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 2)

    verify(mockListener).onTestSuiteStarted(eq(device()), eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)))

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))

    verify(mockListener)
      .onTestCaseStarted(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 0", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.IN_PROGRESS)),
      )

    adapter.testFailed(TestIdentifier("exampleTestClass", "exampleTest1", 1), "")
    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest1", 1), mutableMapOf())

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2, AndroidTestSuiteResult.FAILED)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 0", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.FAILED)),
      )

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest2", 2))

    verify(mockListener)
      .onTestCaseStarted(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2, AndroidTestSuiteResult.FAILED)),
        eq(AndroidTestCase("exampleTestClass#exampleTest2 - 0", "exampleTest2", "exampleTestClass", "", AndroidTestCaseResult.IN_PROGRESS)),
      )

    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest2", 2), mutableMapOf())

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2, AndroidTestSuiteResult.FAILED)),
        eq(AndroidTestCase("exampleTestClass#exampleTest2 - 0", "exampleTest2", "exampleTestClass", "", AndroidTestCaseResult.PASSED)),
      )

    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    verify(mockListener)
      .onTestSuiteFinished(eq(device()), eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2, AndroidTestSuiteResult.FAILED)))
  }

  @Test
  fun runAssumptionFailure() {
    val suiteId = "exampleTestSuite"
    val testId = TestIdentifier("exampleTestClass", "exampleTest1", 1)
    DdmlibTestRunListenerAdapter(mockDevice, mockListener).apply {
      testRunStarted(suiteId, /* testCount= */ 1)
      testStarted(testId)
      testAssumptionFailure(testId, "test assumption failed")
      testEnded(testId, mutableMapOf())
      testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())
    }

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite(suiteId, suiteId, 1, AndroidTestSuiteResult.PASSED)),
        eq(
          AndroidTestCase(
            "exampleTestClass#exampleTest1 - 0",
            "exampleTest1",
            "exampleTestClass",
            "",
            AndroidTestCaseResult.SKIPPED,
            errorStackTrace = "test assumption failed",
          )
        ),
      )
    verify(mockListener).onTestSuiteFinished(eq(device()), eq(AndroidTestSuite(suiteId, suiteId, 1, AndroidTestSuiteResult.PASSED)))
  }

  @Test
  fun testResultIsUpdatedInPlace() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)

    val testSuite = ArgumentCaptor.forClass(AndroidTestSuite::class.java)
    verify(mockListener)
      .onTestSuiteStarted(
        any(),
        testSuite.capture() ?: AndroidTestSuite("", "", 0),
      ) // Workaround for https://github.com/mockito/mockito/issues/1255

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))

    val testCase = ArgumentCaptor.forClass(AndroidTestCase::class.java)
    verify(mockListener)
      .onTestCaseStarted(
        any(),
        any(),
        testCase.capture() ?: AndroidTestCase("", "", "", ""),
      ) // Workaround for https://github.com/mockito/mockito/issues/1255

    adapter.testEnded(
      TestIdentifier("exampleTestClass", "exampleTest1", 1),
      mutableMapOf(DDMLIB_LOGCAT to "test logcat message", BENCHMARK_TEST_METRICS_KEY to "test benchmark output message"),
    )
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    assertThat(testCase.value.result).isEqualTo(AndroidTestCaseResult.PASSED)
    assertThat(testCase.value.logcat).isEqualTo("test logcat message")
    assertThat(testCase.value.benchmark).isEqualTo("test benchmark output message")
    assertThat(testSuite.value.result).isEqualTo(AndroidTestSuiteResult.PASSED)
  }

  @Test
  fun testDualBenchmarkKeysUsesNewKey() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)

    val testSuite = ArgumentCaptor.forClass(AndroidTestSuite::class.java)
    verify(mockListener)
      .onTestSuiteStarted(
        any(),
        testSuite.capture() ?: AndroidTestSuite("", "", 0),
      ) // Workaround for https://github.com/mockito/mockito/issues/1255

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))

    val testCase = ArgumentCaptor.forClass(AndroidTestCase::class.java)
    verify(mockListener)
      .onTestCaseStarted(
        any(),
        any(),
        testCase.capture() ?: AndroidTestCase("", "", "", ""),
      ) // Workaround for https://github.com/mockito/mockito/issues/1255

    adapter.testEnded(
      TestIdentifier("exampleTestClass", "exampleTest1", 1),
      mutableMapOf(
        DDMLIB_LOGCAT to "test logcat message",
        BENCHMARK_TEST_METRICS_KEY to "test benchmark output legacy message",
        BENCHMARK_V2_TEST_METRICS_KEY to "new [linked](style/message) is used",
      ),
    )
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    assertThat(testCase.value.result).isEqualTo(AndroidTestCaseResult.PASSED)
    assertThat(testCase.value.logcat).isEqualTo("test logcat message")
    assertThat(testCase.value.benchmark).isEqualTo("new [linked](style/message) is used")
    assertThat(testSuite.value.result).isEqualTo(AndroidTestSuiteResult.PASSED)
  }

  @Test
  fun benchmarkPrefixIsStripped() {
    val benchmarkOutputFromAndroidX =
      """
      WARNING: Not using IsolationActivity via AndroidBenchmarkRunner
      benchmark:     AndroidBenchmarkRunner should be used to isolate benchmarks from interference
      benchmark:     from other visible apps. To fix this, add the following to your module-level
      benchmark:     build.gradle:
      benchmark:         android.defaultConfig.testInstrumentationRunner
      benchmark:             = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
      benchmark:
      benchmark:    30,233,969 ns DEBUGGABLE_EMULATOR_UNLOCKED_ACTIVITY-MISSING_MyBenchmarkTest.benchmarkSomeWork
      """
        .trimIndent()
    val expectedBenchmarkText =
      """
      WARNING: Not using IsolationActivity via AndroidBenchmarkRunner
          AndroidBenchmarkRunner should be used to isolate benchmarks from interference
          from other visible apps. To fix this, add the following to your module-level
          build.gradle:
              android.defaultConfig.testInstrumentationRunner
                  = "androidx.benchmark.junit4.AndroidBenchmarkRunner"

         30,233,969 ns DEBUGGABLE_EMULATOR_UNLOCKED_ACTIVITY-MISSING_MyBenchmarkTest.benchmarkSomeWork
      """
        .trimIndent()

    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))
    val testCase = ArgumentCaptor.forClass(AndroidTestCase::class.java)
    verify(mockListener).onTestCaseStarted(any(), any(), testCase.capture() ?: AndroidTestCase("", "", "", ""))
    adapter.testEnded(
      TestIdentifier("exampleTestClass", "exampleTest1", 1),
      mutableMapOf(BENCHMARK_TEST_METRICS_KEY to benchmarkOutputFromAndroidX),
    )
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    assertThat(testCase.value.benchmark).isEqualTo(expectedBenchmarkText)
  }

  @Test
  fun benchmarkFileLinkIsCopied() {
    val validTracePath = "path/to/valid/my.trace"
    val benchmarkOutputFromAndroidX =
      """
      Benchmark test ran in [32 ns](file://$validTracePath)
      However there was a bug in [this trace path](path/to/invalid.trace)
    """
        .trimIndent()
    val deviceRoot = "/device/root/path"
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)
    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))
    val testCase = ArgumentCaptor.forClass(AndroidTestCase::class.java)
    verify(mockListener).onTestCaseStarted(any(), any(), testCase.capture() ?: AndroidTestCase("", "", "", ""))
    adapter.testEnded(
      TestIdentifier("exampleTestClass", "exampleTest1", 1),
      mutableMapOf(BENCHMARK_TEST_METRICS_KEY to benchmarkOutputFromAndroidX, BENCHMARK_PATH_TEST_METRICS_KEY to deviceRoot),
    )
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())
    // Expect we attempt to copy the valid trace file, and we do not attempt to copy the invalid trace file.
    verify(mockDevice, times(1))
      .pullFile(
        "${deviceRoot}/$validTracePath",
        "${FileUtil.getTempDirectory()}${File.separator}${validTracePath.replace("/", File.separator)}",
      )
  }

  @Test
  fun benchmarkFileLinkWithPathTraversalIsRejected() {
    val traversalTracePath = "../../../evil.trace"
    val benchmarkOutputFromAndroidX =
      """
      Benchmark test ran in [32 ns](file://$traversalTracePath)
    """
        .trimIndent()
    val deviceRoot = "/device/root/path"
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)
    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))
    val testCase = ArgumentCaptor.forClass(AndroidTestCase::class.java)
    verify(mockListener).onTestCaseStarted(any(), any(), testCase.capture() ?: AndroidTestCase("", "", "", ""))
    adapter.testEnded(
      TestIdentifier("exampleTestClass", "exampleTest1", 1),
      mutableMapOf(BENCHMARK_TEST_METRICS_KEY to benchmarkOutputFromAndroidX, BENCHMARK_PATH_TEST_METRICS_KEY to deviceRoot),
    )
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())
    // Expect we DO NOT attempt to copy the trace file because it is a path traversal.
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinkWithInvalidPathCharactersIsRejected() {
    reportBenchmarkOutput("Benchmark test ran in [32 ns](file://evil\u0000.trace)")
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinkWithQueryParametersIsCopiedWithoutParameters() {
    withTempRoot { tempRoot, _ ->
      val validTracePath = "path/to/valid/my.perfetto-trace"
      reportBenchmarkOutput("Benchmark test ran in [32 ns](file://$validTracePath?enablePlugins=trace_processor)")
      verify(mockDevice, times(1)).pullFile(DEVICE_ROOT + "/" + validTracePath, tempRoot.resolve(validTracePath).toString())
    }
  }

  @Test
  fun benchmarkFileLinkWithNonTraceExtensionIsRejected() {
    reportBenchmarkOutput("Benchmark test ran in [32 ns](file://path/to/evil.sh)")
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinkWithLeadingSlashIsRejected() {
    reportBenchmarkOutput("Benchmark test ran in [32 ns](file:///etc/evil.trace) and [trace](file://\\evil.trace)")
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinkWithColonIsRejected() {
    reportBenchmarkOutput("Benchmark test ran in [32 ns](file://C:evil.trace) and [trace](file://evil.bat:stream.trace)")
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinkWithDisguisedDotSegmentIsRejected() {
    reportBenchmarkOutput("Traces: [a](file://dir/.. /evil.trace) [b](file://.../evil.trace) [c](file://dir/ ./evil.trace)")
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinkWithDeviceRootTraversalIsRejected() {
    reportBenchmarkOutput("Benchmark test ran in [32 ns](file://path/to/valid/my.trace)", deviceRoot = "/device/root/../escape")
    verify(mockDevice, times(0)).pullFile(any(), any())
  }

  @Test
  fun benchmarkFileLinksSharingANewDirectoryAreAllPulled() {
    withTempRoot { tempRoot, _ ->
      reportBenchmarkOutput("Traces: [first](file://shared/dir/first.trace) [second](file://shared/dir/second.perfetto-trace)")
      verify(mockDevice).pullFile("$DEVICE_ROOT/shared/dir/first.trace", tempRoot.resolve("shared/dir/first.trace").toString())
      verify(mockDevice)
        .pullFile("$DEVICE_ROOT/shared/dir/second.perfetto-trace", tempRoot.resolve("shared/dir/second.perfetto-trace").toString())
    }
  }

  @Test
  fun benchmarkFileLinkIntoSymlinkedDirectoryIsRejected() {
    withTempRoot { tempRoot, outsideDir ->
      createSymbolicLinkOrSkip(tempRoot.resolve("linked"), outsideDir)
      reportBenchmarkOutput("Benchmark test ran in [32 ns](file://linked/evil.trace)")
      verify(mockDevice, times(0)).pullFile(any(), any())
      assertThat(Files.list(outsideDir).use { it.count() }).isEqualTo(0)
    }
  }

  @Test
  fun benchmarkFileLinkOverDanglingSymlinkIsRejected() {
    withTempRoot { tempRoot, outsideDir ->
      val outsideTarget = outsideDir.resolve("evil.trace")
      createSymbolicLinkOrSkip(tempRoot.resolve("evil.trace"), outsideTarget)
      reportBenchmarkOutput("Benchmark test ran in [32 ns](file://evil.trace)")
      verify(mockDevice, times(0)).pullFile(any(), any())
      assertThat(Files.exists(outsideTarget, LinkOption.NOFOLLOW_LINKS)).isFalse()
    }
  }

  @Test
  fun failedBenchmarkFilePullRemovesIncompleteFile() {
    withTempRoot { tempRoot, _ ->
      val localFile = tempRoot.resolve("retry/my.trace")
      doThrow(IOException("device disconnected")).doNothing().whenever(mockDevice).pullFile(any(), any())
      val benchmarkOutput = "Benchmark test ran in [32 ns](file://retry/my.trace)"

      reportBenchmarkOutput(benchmarkOutput)
      assertThat(Files.exists(localFile)).isFalse()

      // The failed pull must not block a later pull of the same trace.
      reportBenchmarkOutput(benchmarkOutput)
      verify(mockDevice, times(2)).pullFile("$DEVICE_ROOT/retry/my.trace", localFile.toString())
      assertThat(Files.exists(localFile)).isTrue()
    }
  }

  /** Reports [benchmarkOutput] as the benchmark result of a single test. Benchmark files are pulled synchronously in unit tests. */
  private fun reportBenchmarkOutput(benchmarkOutput: String, deviceRoot: String = DEVICE_ROOT) {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)
    val testId = TestIdentifier("exampleTestClass", "exampleTest1", 1)
    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(testId)
    adapter.testEnded(testId, mutableMapOf(BENCHMARK_TEST_METRICS_KEY to benchmarkOutput, BENCHMARK_PATH_TEST_METRICS_KEY to deviceRoot))
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())
  }

  /**
   * Runs [block] with a fresh, empty directory as the IDE temp directory that benchmark files are pulled into, and a sibling directory
   * outside of it.
   */
  private fun withTempRoot(block: (tempRoot: Path, outsideDir: Path) -> Unit) {
    val originalTempDirectory = FileUtil.getTempDirectory()
    val baseDir = Files.createTempDirectory(Paths.get(originalTempDirectory), "benchmarkPull")
    try {
      val tempRoot = Files.createDirectory(baseDir.resolve("temp")).toRealPath()
      val outsideDir = Files.createDirectory(baseDir.resolve("outside")).toRealPath()
      FileUtil.resetCanonicalTempPathCache(tempRoot.toString())
      block(tempRoot, outsideDir)
    } finally {
      FileUtil.resetCanonicalTempPathCache(originalTempDirectory)
      FileUtil.delete(baseDir.toFile())
    }
  }

  /** Creates a symbolic link, or skips the test where that is not permitted (e.g. Windows without the required privilege). */
  private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
    try {
      Files.createSymbolicLink(link, target)
    } catch (e: IOException) {
      assumeNoException(e)
    } catch (e: UnsupportedOperationException) {
      assumeNoException(e)
    }
  }

  @Test
  fun testResultsShouldChangeToCancelledWhenTestProcessIsKilled() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))
    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    val testCaseCaptor = ArgumentCaptor.forClass(AndroidTestCase::class.java)
    verify(mockListener).onTestCaseStarted(any(), any(), testCaseCaptor.capture() ?: AndroidTestCase("", "", "", ""))
    assertThat(testCaseCaptor.value.result).isEqualTo(AndroidTestCaseResult.CANCELLED)
    assertThat(testCaseCaptor.value.endTimestampMillis).isNotNull()

    val testSuiteCaptor = ArgumentCaptor.forClass(AndroidTestSuite::class.java)
    verify(mockListener).onTestSuiteFinished(any(), testSuiteCaptor.capture() ?: AndroidTestSuite("", "", 0))
    assertThat(testSuiteCaptor.value.result).isEqualTo(AndroidTestSuiteResult.CANCELLED)
  }

  @Test
  fun methodNameAndClassNameAndPackageNameIsExtractedCorrectly() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("com.example.test.exampleTestClass", "exampleTest1", 1))

    verify(mockListener)
      .onTestCaseStarted(
        any(),
        any(),
        eq(
          AndroidTestCase(
            "com.example.test.exampleTestClass#exampleTest1 - 0",
            "exampleTest1",
            "exampleTestClass",
            "com.example.test",
            AndroidTestCaseResult.IN_PROGRESS,
          )
        ),
      )
  }

  @Test
  fun methodNameAndClassNameAndPackageNameIsExtractedCorrectlyForNestedClass() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("com.example.test.exampleTestClass\$NestedClassName", "exampleTest1", 1))

    verify(mockListener)
      .onTestCaseStarted(
        any(),
        any(),
        eq(
          AndroidTestCase(
            "com.example.test.exampleTestClass\$NestedClassName#exampleTest1 - 0",
            "exampleTest1",
            "exampleTestClass\$NestedClassName",
            "com.example.test",
            AndroidTestCaseResult.IN_PROGRESS,
          )
        ),
      )
  }

  @Test
  fun timestamp() {
    lateinit var result: AndroidTestCase
    val adapter =
      DdmlibTestRunListenerAdapter(
        mockDevice,
        object : AndroidTestResultListener {
          override fun onTestSuiteScheduled(device: AndroidDevice) {}

          override fun onTestSuiteStarted(device: AndroidDevice, testSuite: AndroidTestSuite) {}

          override fun onTestCaseStarted(device: AndroidDevice, testSuite: AndroidTestSuite, testCase: AndroidTestCase) {
            result = testCase
          }

          override fun onTestCaseFinished(device: AndroidDevice, testSuite: AndroidTestSuite, testCase: AndroidTestCase) {}

          override fun onTestSuiteFinished(device: AndroidDevice, testSuite: AndroidTestSuite) {}

          override fun onRerunScheduled(device: AndroidDevice) {}
        },
      )

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 1)
    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))

    assertThat(result.startTimestampMillis).isNotNull()
    assertThat(result.endTimestampMillis).isNull()

    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest1", 1), mutableMapOf())

    assertThat(result.startTimestampMillis).isNotNull()
    assertThat(result.endTimestampMillis).isNotNull()
  }

  @Test
  fun rerunOfSameTestShouldGetDifferentId() {
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener)

    verify(mockListener).onTestSuiteScheduled(eq(device()))

    adapter.testRunStarted("exampleTestSuite", /* testCount= */ 2)

    verify(mockListener).onTestSuiteStarted(eq(device()), eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)))

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 1))

    verify(mockListener)
      .onTestCaseStarted(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 0", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.IN_PROGRESS)),
      )

    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest1", 1), mutableMapOf())

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 0", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.PASSED)),
      )

    adapter.testStarted(TestIdentifier("exampleTestClass", "exampleTest1", 2))

    verify(mockListener)
      .onTestCaseStarted(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 1", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.IN_PROGRESS)),
      )

    adapter.testEnded(TestIdentifier("exampleTestClass", "exampleTest1", 2), mutableMapOf())

    verify(mockListener)
      .onTestCaseFinished(
        eq(device()),
        eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2)),
        eq(AndroidTestCase("exampleTestClass#exampleTest1 - 1", "exampleTest1", "exampleTestClass", "", AndroidTestCaseResult.PASSED)),
      )

    adapter.testRunEnded(/* elapsedTime= */ 1000, mutableMapOf())

    verify(mockListener)
      .onTestSuiteFinished(eq(device()), eq(AndroidTestSuite("exampleTestSuite", "exampleTestSuite", 2, AndroidTestSuiteResult.PASSED)))
  }

  @Test
  fun runCancelledByProcessHandler() {
    val processHandler = mock<ProcessHandler>()
    val adapter = DdmlibTestRunListenerAdapter(mockDevice, mockListener).apply { processTerminated(ProcessEvent(processHandler)) }

    inOrder(mockListener, processHandler).apply {
      verify(mockListener).onTestSuiteScheduled(eq(device()))
      verify(processHandler).removeProcessListener(eq(adapter))
      verify(mockListener).onTestSuiteFinished(eq(device()), eq(AndroidTestSuite("", "", 0, AndroidTestSuiteResult.CANCELLED)))
      verifyNoMoreInteractions()
    }
  }
}
