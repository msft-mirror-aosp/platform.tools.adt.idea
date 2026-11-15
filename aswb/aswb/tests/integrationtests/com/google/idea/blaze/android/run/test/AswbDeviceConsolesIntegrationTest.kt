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
package com.google.idea.blaze.android.run.test

import com.android.tools.idea.console.SafeUrlFilter
import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.android.AndroidIntegrationTestSetupRule
import com.google.idea.blaze.android.run.binary.BlazeAndroidBinaryConsoleProvider
import com.google.idea.blaze.android.run.test.BlazeAndroidTestLaunchMethodsProvider.AndroidTestLaunchMethod
import com.google.idea.blaze.base.BlazeIntegrationTestCase
import com.google.idea.blaze.base.issueparser.NonProblemHyperlinkInfo
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration
import com.google.idea.blaze.base.run.BlazeCommandRunConfigurationType
import com.google.idea.blaze.base.run.filter.FileResolver
import com.google.idea.blaze.base.run.smrunner.SmRunnerUtils
import com.google.idea.blaze.base.run.testlogs.BlazeTestResults
import com.google.idea.blaze.base.run.testlogs.BlazeTestResultsProvider
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.browsers.OpenUrlHyperlinkInfo
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.UIUtil
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.event.AncestorEvent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Integration tests verifying ASwB device-output consoles exclude unsafe predefined platform [ConsoleFilterProvider] extensions and block
 * UNC `file:` URLs.
 */
@RunWith(JUnit4::class)
class AswbDeviceConsolesIntegrationTest : BlazeIntegrationTestCase() {

  @get:Rule val androidSetupRule = AndroidIntegrationTestSetupRule(true)

  private val testDisposable = Disposer.newDisposable()

  @After
  fun disposeTestDisposable() {
    // Dispose consoles before BlazeIntegrationTestCase.tearDown() checks released editors.
    Disposer.dispose(testDisposable)
  }

  @Test
  fun blazeAndroidTestConsoleProperties_excludesPredefinedProvidersAndBlocksUncUrls() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val fileResolverInvoked = registerFileResolverSpy()
    val properties =
      BlazeAndroidTestConsoleProperties(
        createRunConfiguration(),
        DefaultRunExecutor.getRunExecutorInstance(),
        BlazeTestResultsProvider { BlazeTestResults.NO_RESULTS },
      )
    Disposer.register(testDisposable, properties)

    val console = properties.createConsole() as ConsoleViewImpl
    Disposer.register(testDisposable, console)

    mountAndPrint(console, blazeTestLines())

    assertBlazeTestConsoleBehavior(console, predefinedProviderInvoked, fileResolverInvoked)
  }

  @Test
  fun blazeAndroidBinaryConsoleProvider_usesDeviceSafeFilters() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val provider = BlazeAndroidBinaryConsoleProvider(project)

    val console =
      provider.createAndAttach(
        testDisposable,
        NopProcessHandler(),
        DefaultRunExecutor.getRunExecutorInstance(),
      ) as ConsoleViewImpl
    Disposer.register(testDisposable, console)

    mountAndPrint(console, TEST_LINES)

    assertDeviceSafeConsoleBehavior(console, predefinedProviderInvoked)
  }

  @Test
  fun aitBlazeTestConsoleProvider_withoutResultsProvider_usesDeviceSafeFilters() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val fileResolverInvoked = registerFileResolverSpy()
    val provider =
      AitBlazeTestConsoleProvider(
        project,
        createRunConfiguration(),
        /* testResultFinderStrategy= */ null,
      )

    val console =
      provider.createAndAttach(
        testDisposable,
        NopProcessHandler(),
        DefaultRunExecutor.getRunExecutorInstance(),
      ) as ConsoleViewImpl
    Disposer.register(testDisposable, console)

    mountAndPrint(console, blazeTestLines())

    assertBlazeTestConsoleBehavior(console, predefinedProviderInvoked, fileResolverInvoked)
  }

  @Test
  fun aitBlazeTestConsoleProvider_whenDebugging_usesDeviceSafeFilters() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val fileResolverInvoked = registerFileResolverSpy()
    val provider =
      AitBlazeTestConsoleProvider(
        project,
        createRunConfiguration(),
        BlazeTestResultsProvider { BlazeTestResults.NO_RESULTS },
      )

    val console =
      provider.createAndAttach(
        testDisposable,
        NopProcessHandler(),
        DefaultDebugExecutor.getDebugExecutorInstance(),
      ) as ConsoleViewImpl
    Disposer.register(testDisposable, console)

    mountAndPrint(console, blazeTestLines())

    assertBlazeTestConsoleBehavior(console, predefinedProviderInvoked, fileResolverInvoked)
  }

  @Test
  fun aitBlazeTestConsoleProvider_withResultsProvider_usesDeviceSafeFilters() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val fileResolverInvoked = registerFileResolverSpy()
    val provider =
      AitBlazeTestConsoleProvider(
        project,
        createRunConfiguration(),
        BlazeTestResultsProvider { BlazeTestResults.NO_RESULTS },
      )

    val smConsole =
      provider.createAndAttach(
        testDisposable,
        NopProcessHandler(),
        DefaultRunExecutor.getRunExecutorInstance(),
      ) as SMTRunnerConsoleView
    Disposer.register(testDisposable, smConsole)

    val innerConsole = smConsole.console as ConsoleViewImpl
    mountAndPrint(innerConsole, blazeTestLines())

    assertBlazeTestConsoleBehavior(innerConsole, predefinedProviderInvoked, fileResolverInvoked)
  }

  @Test
  fun aitIdeTestConsoleProvider_nonBlazeDefault_usesDeviceSafeFilters() {
    assertAitIdeTestConsoleProviderUsesDeviceSafeFilters(AndroidTestLaunchMethod.NON_BLAZE)
  }

  @Test
  fun aitIdeTestConsoleProvider_mobileInstall_usesDeviceSafeFilters() {
    assertAitIdeTestConsoleProviderUsesDeviceSafeFilters(AndroidTestLaunchMethod.MOBILE_INSTALL)
  }

  private fun assertAitIdeTestConsoleProviderUsesDeviceSafeFilters(launchMethod: AndroidTestLaunchMethod) {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val configState = BlazeAndroidTestRunConfigurationState(buildSystem().getName())
    configState.launchMethod = launchMethod
    val provider = AitIdeTestConsoleProvider(createRunConfiguration(), configState)

    val smConsole =
      provider.createAndAttach(
        testDisposable,
        NopProcessHandler(),
        DefaultRunExecutor.getRunExecutorInstance(),
      ) as SMTRunnerConsoleView
    Disposer.register(testDisposable, smConsole)

    val innerConsole = smConsole.console as ConsoleViewImpl
    mountAndPrint(innerConsole, TEST_LINES)

    assertDeviceSafeConsoleBehavior(innerConsole, predefinedProviderInvoked)
  }

  @Test
  fun smRunnerUtilsDefaultOverload_preservesPredefinedFiltersForHostTests() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val executor = DefaultRunExecutor.getRunExecutorInstance()
    val smConsole =
      SmRunnerUtils.getConsoleView(
        project,
        createRunConfiguration(),
        executor,
        BlazeTestResultsProvider { BlazeTestResults.NO_RESULTS },
      )
    Disposer.register(testDisposable, smConsole)

    val innerConsole = smConsole.console as ConsoleViewImpl
    mountAndPrint(innerConsole, "TRIGGER_PREDEFINED\n")

    assertThat(predefinedProviderInvoked.get()).isTrue()
  }

  private fun createRunConfiguration(): BlazeCommandRunConfiguration =
    BlazeCommandRunConfigurationType.getInstance().factory.createTemplateConfiguration(project)

  private fun registerUnsafePredefinedProvider(): AtomicBoolean {
    val invoked = AtomicBoolean(false)
    val provider = ConsoleFilterProvider {
      arrayOf(
        Filter { line, entireLength ->
          if (line.contains("TRIGGER_PREDEFINED")) {
            invoked.set(true)
            Filter.Result(0, entireLength, HyperlinkInfo {})
          } else {
            null
          }
        }
      )
    }
    registerExtension(ConsoleFilterProvider.FILTER_PROVIDERS, provider)
    return invoked
  }

  private fun registerFileResolverSpy(): AtomicBoolean {
    val invoked = AtomicBoolean(false)
    registerExtensionFirst(
      FileResolver.EP_NAME,
      FileResolver { _, _ ->
        invoked.set(true)
        null
      },
    )
    return invoked
  }

  private fun blazeTestLines(): String {
    BlazeImportSettingsManager.getInstanceForTestingOnly(project)
      .setImportSettingsForTests(
        java.nio.file.Path.of("/workspace"),
        "test-project",
        java.nio.file.Path.of("/workspace/project-view-file"),
        buildSystem(),
      )
    return TEST_LINES +
      "Running //foo/bar:my_test\n" +
      "FAIL: //foo/bar:my_test (see /workspace/bazel-testlogs/foo/bar/my_test/test.log)\n" +
      "FAIL: outside testlogs (see /etc/other/testlogs/foo/bar/test.log)\n" +
      "FAIL: unc backslash (see \\\\10.0.0.2\\bazel-out\\k8\\testlogs\\foo\\test.log)\n" +
      "//10.0.0.2/bazel-out/k8/testlogs/foo/test.log\n"
  }

  private fun mountAndPrint(console: ConsoleViewImpl, text: String) {
    console.component
    for (listener in console.ancestorListeners) {
      listener.ancestorAdded(AncestorEvent(console, AncestorEvent.ANCESTOR_ADDED, console, console.parent))
    }
    console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    console.waitAllRequests()
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    checkNotNull(console.getHyperlinks()).waitForPendingFilters(5_000)
  }

  private fun assertDeviceSafeConsoleBehavior(
    console: ConsoleViewImpl,
    predefinedProviderInvoked: AtomicBoolean,
  ) {
    val hyperlinks = checkNotNull(console.getHyperlinks())
    assertThat(predefinedProviderInvoked.get()).isFalse()
    assertThat(hyperlinks.findAllHyperlinksOnLine(0)).isEmpty()

    val httpLinks = hyperlinks.findAllHyperlinksOnLine(1)
    assertThat(httpLinks).hasSize(1)
    assertThat(EditorHyperlinkSupport.getHyperlinkInfo(httpLinks.single())).isInstanceOf(OpenUrlHyperlinkInfo::class.java)

    val uncLinks = hyperlinks.findAllHyperlinksOnLine(2)
    assertThat(uncLinks).hasSize(1)
    val uncInfo = checkNotNull(EditorHyperlinkSupport.getHyperlinkInfo(uncLinks.single()))
    assertThat(uncInfo.javaClass.name).isEqualTo(NOP_HYPERLINK_INFO_CLASS)
  }

  private fun assertBlazeTestConsoleBehavior(
    console: ConsoleViewImpl,
    predefinedProviderInvoked: AtomicBoolean,
    fileResolverInvoked: AtomicBoolean,
  ) {
    assertDeviceSafeConsoleBehavior(console, predefinedProviderInvoked)
    assertThat(fileResolverInvoked.get()).isFalse()

    val hyperlinks = checkNotNull(console.getHyperlinks())

    // Line 3: Blaze target label gets a link.
    val targetLinks = hyperlinks.findAllHyperlinksOnLine(3)
    assertThat(targetLinks).hasSize(1)
    assertThat(EditorHyperlinkSupport.getHyperlinkInfo(targetLinks.single())).isInstanceOf(NonProblemHyperlinkInfo::class.java)

    // Line 4: both target label and test.log inside bazel-testlogs get links on the same line.
    val validTestLogLinks = hyperlinks.findAllHyperlinksOnLine(4)
    assertThat(validTestLogLinks).hasSize(2)
    val line4Infos = validTestLogLinks.map { checkNotNull(EditorHyperlinkSupport.getHyperlinkInfo(it)) }
    assertThat(line4Infos.count { it is NonProblemHyperlinkInfo }).isEqualTo(1)

    // Line 5 & 6: test.log outside bazel-testlogs and backslash UNC paths get no links.
    assertThat(hyperlinks.findAllHyperlinksOnLine(5)).isEmpty()
    assertThat(hyperlinks.findAllHyperlinksOnLine(6)).isEmpty()

    // Line 7: "//10.0.0.2/..." only matches BlazeTargetFilter (lazy PSI label), never TestLogFilter.
    val slashSlashLinks = hyperlinks.findAllHyperlinksOnLine(7)
    assertThat(slashSlashLinks).hasSize(1)
    assertThat(EditorHyperlinkSupport.getHyperlinkInfo(slashSlashLinks.single())).isInstanceOf(NonProblemHyperlinkInfo::class.java)
  }

  companion object {
    private val NOP_HYPERLINK_INFO_CLASS = "${SafeUrlFilter::class.java.name}\$NopHyperlinkInfo"
    private const val TEST_LINES = "TRIGGER_PREDEFINED\nhttps://developer.android.com\nfile:////10.0.0.2/share/evil.txt\n"
  }
}
