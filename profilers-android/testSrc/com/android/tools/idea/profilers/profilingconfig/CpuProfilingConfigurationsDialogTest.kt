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
package com.android.tools.idea.profilers.profilingconfig

import com.android.tools.adtui.TreeWalker
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.run.profiler.CpuProfilerConfig
import com.android.tools.idea.run.profiler.CpuProfilerConfigsState
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.cpu.CpuProfilerStage
import com.android.tools.profilers.cpu.config.CpuProfilerConfigModel
import com.google.common.truth.Truth.assertThat
import com.intellij.mock.MockProjectEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.JBSplitter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(Parameterized::class)
class CpuProfilingConfigurationsDialogTest(private val deviceLevel: Int) {

  private lateinit var configurations: CpuProfilingConfigurationsDialog.ProfilingConfigurable
  private lateinit var project: Project
  private lateinit var model: CpuProfilerConfigModel
  private lateinit var myStage: CpuProfilerStage

  private val myTimer = FakeTimer()
  private val myIdeServices = FakeIdeProfilerServices()
  private val myTransportService = FakeTransportService(myTimer)

  @get:Rule val myGrpcChannel = FakeGrpcChannel("CpuProfilingConfigDialogTestChannel", myTransportService)

  @get:Rule val myEdtRule = EdtRule()

  @get:Rule val applicationRule = ApplicationRule()

  @get:Rule val disposableRule = DisposableRule()

  @Before
  fun setUp() {
    val profilers = StudioProfilers(ProfilerClient(myGrpcChannel.channel), myIdeServices, myTimer)
    profilers.setPreferredProcess(FakeTransportService.FAKE_DEVICE_NAME, FakeTransportService.FAKE_PROCESS_NAME, null)
    project = MockProjectEx(disposableRule.disposable)
    myStage = CpuProfilerStage(profilers)
    model = CpuProfilerConfigModel(profilers, myStage)
    model.profilingConfiguration = FakeIdeProfilerServices.ATRACE_CONFIG
    configurations = CpuProfilingConfigurationsDialog.ProfilingConfigurable(project, model, deviceLevel, myIdeServices)
  }

  @Test
  fun testApplyButtonNotAvailable() {
    SwingUtilities.invokeAndWait {
      val cpuProfilingConfigurationsDialog = CpuProfilingConfigurationsDialog(project, deviceLevel, model, {}, myIdeServices)
      try {
        assertEquals(3, cpuProfilingConfigurationsDialog.createActions().size) // ok, cancel, help
      } finally {
        Disposer.dispose(cpuProfilingConfigurationsDialog.disposable)
      }
    }
  }

  @Test
  fun hasTaskHeader() {
    var profilingComponent: JComponent = configurations.createComponent()!!
    val walker = TreeWalker(profilingComponent)
    val headerLabel = walker.descendants().filterIsInstance<JLabel>().filter { ((it.text)) == "Tasks" }
    // Left panel in config dialog has "Tasks" header
    assertThat(headerLabel.size).isEqualTo(1)
  }

  @Test
  fun testGetDisplayName() {
    assertThat(configurations.displayName).isEqualTo("Task Settings")
  }

  @Test
  fun configDialogHasNoAddRemoveOrMoveActions() {
    var profilingComponent: JComponent = configurations.createComponent()!!
    assertThat(profilingComponent).isNotNull()
    val splitter: JBSplitter = TreeWalker(profilingComponent).descendants().filterIsInstance<JBSplitter>().first()
    val firstComponent = splitter.firstComponent
    val actionPanel: CommonActionsPanel = TreeWalker(firstComponent).descendants().filterIsInstance<CommonActionsPanel>().first()
    // Add, remove, up and down buttons should be null
    val addButtonAction = actionPanel.getAnAction(CommonActionsPanel.Buttons.ADD)
    assertThat(addButtonAction).isNull()
    val downButtonAction = actionPanel.getAnAction(CommonActionsPanel.Buttons.DOWN)
    assertThat(downButtonAction).isNull()
    val upButtonAction = actionPanel.getAnAction(CommonActionsPanel.Buttons.UP)
    assertThat(upButtonAction).isNull()
    val removeButtonAction = actionPanel.getAnAction(CommonActionsPanel.Buttons.REMOVE)
    assertThat(removeButtonAction).isNull()
  }

  @Test
  fun setupConfig() {
    project = spy(MockProjectEx(disposableRule.disposable))
    configurations = getCpuProfilingDialogConfiguration(project)
    // Contains task configurations SAMPLED_NATIVE, INSTRUMENTED_JAVA, NATIVE_ALLOCATIONS
    // ATRACE/PERFETTO will not be available since it doesn't have an editable fields
    assertThat(configurations.configurationModel.size()).isEqualTo(3)
  }

  @Test
  fun taskConfigGettingSavedInApply() {
    project = spy(MockProjectEx(disposableRule.disposable))
    val cpuProfilerStateSpy = spy(CpuProfilerConfigsState())
    whenever(project.getService(CpuProfilerConfigsState::class.java)).thenReturn(cpuProfilerStateSpy)
    configurations = getCpuProfilingDialogConfiguration(project)
    configurations.apply()
    val callbackCaptor = argumentCaptor<List<CpuProfilerConfig>>()
    verify(cpuProfilerStateSpy, times(1)).taskConfigs = callbackCaptor.capture()
    assertThat(callbackCaptor.firstValue.size).isEqualTo(model.taskProfilingConfigurations.size)
  }

  @Test
  fun savingTaskConfigInProject() {
    project = spy(MockProjectEx(disposableRule.disposable))
    val cpuProfilerStateSpy = spy(CpuProfilerConfigsState())
    whenever(project.getService(CpuProfilerConfigsState::class.java)).thenReturn(cpuProfilerStateSpy)
    configurations = getCpuProfilingDialogConfiguration(project)
    configurations.apply()

    // Value set in task config
    verify(cpuProfilerStateSpy, times(1)).taskConfigs = any()
    // User config shouldn't be invoked
    verify(cpuProfilerStateSpy, times(0)).userConfigs = any()
  }

  private fun getCpuProfilingDialogConfiguration(project: Project): CpuProfilingConfigurationsDialog.ProfilingConfigurable {
    val profilers = StudioProfilers(ProfilerClient(myGrpcChannel.channel), myIdeServices, myTimer)
    profilers.setPreferredProcess(FakeTransportService.FAKE_DEVICE_NAME, FakeTransportService.FAKE_PROCESS_NAME, null)
    myStage = CpuProfilerStage(profilers)
    model = CpuProfilerConfigModel(profilers, myStage)
    model.profilingConfiguration = FakeIdeProfilerServices.ATRACE_CONFIG
    return CpuProfilingConfigurationsDialog.ProfilingConfigurable(project, model, deviceLevel, myIdeServices)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters
    fun data(): Collection<Int> {
      return listOf(23, 27, 30)
    }
  }
}
