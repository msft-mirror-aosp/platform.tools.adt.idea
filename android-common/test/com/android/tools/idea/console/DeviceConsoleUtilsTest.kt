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
package com.android.tools.idea.console

import com.android.tools.idea.console.SafeUrlFilter.NopHyperlinkInfo
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.ExceptionFilter
import com.intellij.execution.filters.ExceptionFilterFactory
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.FilterMixin
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.UrlFilter
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.browsers.OpenUrlHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.registerExtension
import com.intellij.util.ui.UIUtil
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.event.AncestorEvent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.withSettings
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
@RunsInEdt
class DeviceConsoleUtilsTest {
  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule)

  private val project
    get() = projectRule.project

  @Test
  fun getDeviceConsoleFilters_containsWrappedExceptionAndSafeUrlFiltersWithoutPlainUrlFilter() {
    val filters = getDeviceConsoleFilters(project)
    val exceptionFilters = filters.dropLast(1)

    assertThat(exceptionFilters).isNotEmpty()
    assertThat(exceptionFilters.none { it is ExceptionFilter }).isTrue()
    assertThat(exceptionFilters.any { DumbService.isDumbAware(it) }).isTrue()
    assertThat(exceptionFilters.any { it is FilterMixin }).isTrue()
    assertThat(filters.last()).isInstanceOf(SafeUrlFilter::class.java)
    assertThat(filters.none { it::class == UrlFilter::class }).isTrue()
  }

  @Test
  fun wrappedExceptionFilters_blockPathAndUncNativeStackFramesAndDelegateNormalFrames() {
    val mockDelegate = mock(Filter::class.java, withSettings().extraInterfaces(DumbAware::class.java))
    whenever(mockDelegate.isDumbAware).thenReturn(true)
    val wrapped = registerAndFindWrappedExceptionFilter(mockDelegate)

    assertThat(wrapped.isDumbAware).isTrue()
    assertThat(DumbService.isDumbAware(wrapped)).isTrue()
    clearInvocations(mockDelegate)

    val blockedLines =
      listOf(
        "at x (\\\\10.0.0.1\\share\\evil.kt:1:1)",
        "at x (//10.0.0.1/share/evil.kt:1:1)",
        "at x (Z:\\evil.kt:1:1)",
        "at x (Z:evil.kt:1:1)",
        "at x (/abs/path/a.kt:10:2)",
        "  at kfun:main() (\\\\10.0.0.1\\share\\evil.kt:1:1)  ",
        "at f(Foo.kt:1) (\\\\10.0.0.1\\share\\evil.kt:1:1)",
      )

    for (line in blockedLines) {
      assertThat(wrapped.applyFilter(line, line.length)).named(line).isNull()
    }
    verifyNoInteractions(mockDelegate)

    val allowedLines =
      listOf(
        "\tat com.foo.MainActivity.onCreate(MainActivity.kt:42)",
        "\tat com.foo.Bar.baz(Bar.java:17)",
        "Plain device output line",
      )

    for (line in allowedLines) {
      val expectedResult = Filter.Result(0, 5, HyperlinkInfo {})
      whenever(mockDelegate.applyFilter(line, line.length)).thenReturn(expectedResult)

      assertThat(wrapped.applyFilter(line, line.length)).named(line).isSameAs(expectedResult)
      verify(mockDelegate).applyFilter(line, line.length)
    }
  }

  @Test
  fun wrappedExceptionFilters_preserveNonDumbAwareAndFilterMixinContracts() {
    val heavyDelegate = mock(Filter::class.java, withSettings().extraInterfaces(FilterMixin::class.java))
    val heavyMixin = heavyDelegate as FilterMixin
    whenever(heavyMixin.shouldRunHeavy()).thenReturn(true)
    whenever(heavyMixin.updateMessage).thenReturn("Updating stack trace highlights")

    val wrapped = registerAndFindWrappedExceptionFilter(heavyDelegate)

    assertThat(DumbService.isDumbAware(wrapped)).isFalse()
    assertThat(wrapped).isInstanceOf(FilterMixin::class.java)
    val wrappedMixin = wrapped as FilterMixin
    assertThat(wrappedMixin.shouldRunHeavy()).isTrue()
    assertThat(wrappedMixin.updateMessage).isEqualTo("Updating stack trace highlights")
  }

  @Test
  fun createDeviceConsoleView_excludesPredefinedConsoleFilterProvidersAndBlocksUncUrls() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()

    val console = createDeviceConsoleView(project, viewer = true)
    Disposer.register(disposableRule.disposable, console)

    mountAndPrint(
      console,
      "TRIGGER_PREDEFINED\n" + "https://developer.android.com\n" + "file:////10.0.0.2/share/evil.txt\n",
    )

    assertThat(console.editor!!.isViewer).isTrue()
    val hyperlinks = console.getHyperlinks()!!
    assertThat(predefinedProviderInvoked.get()).isFalse()
    assertThat(hyperlinks.findAllHyperlinksOnLine(0)).isEmpty()
    assertThat(EditorHyperlinkSupport.getHyperlinkInfo(hyperlinks.findAllHyperlinksOnLine(1).single()))
      .isInstanceOf(OpenUrlHyperlinkInfo::class.java)
    assertThat(EditorHyperlinkSupport.getHyperlinkInfo(hyperlinks.findAllHyperlinksOnLine(2).single())).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun createDeviceTextConsoleBuilder_excludesPredefinedConsoleFilterProviders() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()

    val console = createDeviceTextConsoleBuilder(project).console as ConsoleViewImpl
    Disposer.register(disposableRule.disposable, console)

    mountAndPrint(console, "TRIGGER_PREDEFINED\nhttps://developer.android.com\n")

    val hyperlinks = console.getHyperlinks()!!
    assertThat(predefinedProviderInvoked.get()).isFalse()
    assertThat(hyperlinks.findAllHyperlinksOnLine(0)).isEmpty()
    assertThat(hyperlinks.findAllHyperlinksOnLine(1)).hasSize(1)
  }

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
    ApplicationManager.getApplication()
      .registerExtension(
        ConsoleFilterProvider.FILTER_PROVIDERS,
        provider,
        disposableRule.disposable,
      )
    return invoked
  }

  private fun registerAndFindWrappedExceptionFilter(delegate: Filter): Filter {
    val probe = "__IDENTIFY_DELEGATE__"
    val probeResult = Filter.Result(0, 1, HyperlinkInfo {})
    whenever(delegate.applyFilter(probe, probe.length)).thenReturn(probeResult)
    ApplicationManager.getApplication()
      .registerExtension(
        ExceptionFilterFactory.EP_NAME,
        ExceptionFilterFactory { delegate },
        disposableRule.disposable,
      )
    val wrapped = getDeviceConsoleFilters(project).single { it.applyFilter(probe, probe.length) === probeResult }
    clearInvocations(delegate)
    return wrapped
  }

  private fun mountAndPrint(console: ConsoleViewImpl, text: String) {
    console.component
    console.ancestorListeners.forEach {
      it.ancestorAdded(AncestorEvent(console, AncestorEvent.ANCESTOR_ADDED, console, console.parent))
    }
    console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    console.waitAllRequests()
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    console.getHyperlinks()!!.waitForPendingFilters(5_000)
  }
}
