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
package com.android.tools.idea.testartifacts.instrumented

import com.android.tools.idea.console.SafeUrlFilter
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.browsers.OpenUrlHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
@RunsInEdt
class AndroidTestConsolePropertiesTest {
  private val projectRule = AndroidProjectRule.inMemory()
  private val disposableRule = DisposableRule()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule)

  private val project
    get() = projectRule.project

  @Test
  fun createConsole_excludesPredefinedConsoleFilterProvidersAndBlocksUncUrls() {
    val predefinedProviderInvoked = registerUnsafePredefinedProvider()
    val configuration =
      mock(RunConfiguration::class.java, withSettings().extraInterfaces(ModuleRunProfile::class.java)).also {
        whenever(it.project).thenReturn(project)
        whenever((it as ModuleRunProfile).modules).thenReturn(arrayOf(projectRule.module))
      }
    val properties = AndroidTestConsoleProperties(configuration, DefaultRunExecutor.getRunExecutorInstance())
    Disposer.register(disposableRule.disposable, properties)

    val console = properties.createConsole() as ConsoleViewImpl
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
    assertThat(EditorHyperlinkSupport.getHyperlinkInfo(hyperlinks.findAllHyperlinksOnLine(2).single())!!::class.java.name)
      .isEqualTo("${SafeUrlFilter::class.java.name}\$NopHyperlinkInfo")
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
