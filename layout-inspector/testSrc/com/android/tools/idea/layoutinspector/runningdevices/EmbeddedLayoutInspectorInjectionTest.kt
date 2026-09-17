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
package com.android.tools.idea.layoutinspector.runningdevices

import com.android.SdkConstants.ANDROID_URI
import com.android.testutils.ImageDiffUtil
import com.android.testutils.TestUtils.resolveWorkspacePathUnchecked
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.createDataContext
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.appinspection.api.process.ProcessesModel
import com.android.tools.idea.appinspection.test.TestProcessDiscovery
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.layoutinspector.FakeForegroundProcessDetection
import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.TestScopeRule
import com.android.tools.idea.layoutinspector.model
import com.android.tools.idea.layoutinspector.model.COMPOSE1
import com.android.tools.idea.layoutinspector.model.NotificationModel
import com.android.tools.idea.layoutinspector.model.ROOT
import com.android.tools.idea.layoutinspector.model.SelectionOrigin
import com.android.tools.idea.layoutinspector.model.ViewNode
import com.android.tools.idea.layoutinspector.pipeline.InspectorClient
import com.android.tools.idea.layoutinspector.pipeline.InspectorClientLauncher
import com.android.tools.idea.layoutinspector.pipeline.InspectorClientSettings
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterGroupItem
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterItem
import com.android.tools.idea.layoutinspector.pipeline.foregroundprocessdetection.DeviceModel
import com.android.tools.idea.layoutinspector.properties.InspectorPropertyItem
import com.android.tools.idea.layoutinspector.properties.PropertiesProvider
import com.android.tools.idea.layoutinspector.properties.PropertySection
import com.android.tools.idea.layoutinspector.properties.PropertyType
import com.android.tools.idea.layoutinspector.properties.ResultListener
import com.android.tools.idea.layoutinspector.runningdevices.ui.ActiveTabState
import com.android.tools.idea.layoutinspector.runningdevices.ui.TabComponents
import com.android.tools.idea.layoutinspector.util.FakeTreeSettings
import com.android.tools.idea.layoutinspector.window
import com.android.tools.idea.streaming.core.DevicePanel
import com.android.tools.idea.streaming.core.DisplayView
import com.android.tools.idea.streaming.core.STREAMING_CONTENT_PANEL_KEY
import com.android.tools.idea.streaming.core.StreamingDeviceId
import com.android.tools.idea.streaming.emulator.EmulatorViewRule
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.property.panel.api.PropertiesTable
import com.google.common.collect.HashBasedTable
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.Future
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private val TEST_DATA_PATH = Path.of("tools", "adt", "idea", "layout-inspector", "testData")
private const val DIFF_THRESHOLD = 1.0

@RunsInEdt
class EmbeddedLayoutInspectorInjectionTest {
  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()
  }

  private val emulatorViewRule = EmulatorViewRule()
  private val testName = TestName()

  @get:Rule val ruleChain = RuleChain(TestScopeRule(), emulatorViewRule, testName, PortableUiFontRule(), EdtRule())

  private val disposable
    get() = emulatorViewRule.disposable

  private val project
    get() = emulatorViewRule.project

  private lateinit var layoutInspector: LayoutInspector

  /** The dimension of the canvas/panel */
  private val screenDimension = Dimension(500, 500)

  @Before
  fun setUp() {

    val processModel = ProcessesModel(TestProcessDiscovery())
    val deviceModel = DeviceModel(disposable, processModel)
    val notificationModel = NotificationModel(project)

    val coroutineScope = disposable.createCoroutineScope()
    val launcher =
      InspectorClientLauncher(processModel, emptyList(), project, notificationModel, coroutineScope, disposable, metrics = mock())

    val fakeForegroundProcessDetection = FakeForegroundProcessDetection()

    val model = model(disposable) { view(ROOT, 10, 20, 30, 40) }

    layoutInspector =
      LayoutInspector(
        coroutineScope = coroutineScope,
        processModel = processModel,
        deviceModel = deviceModel,
        foregroundProcessDetection = fakeForegroundProcessDetection,
        inspectorClientSettings = InspectorClientSettings(project),
        launcher = launcher,
        layoutInspectorModel = model,
        notificationModel = notificationModel,
        treeSettings = FakeTreeSettings(),
      )
  }

  @Test
  fun testInjectedIntoRunningDevices() = runTest {
    val (panel, selectedTabState) = createUi()

    selectedTabState.enableLayoutInspector()
    testScheduler.advanceUntilIdle()

    renderAndAssertImageSimilarity(panel, selectedTabState.tabComponents)
  }

  @Test
  fun testInjectedIntoRunningDevicesWithBackStack() = runTest {
    StudioFlags.DYNAMIC_LAYOUT_INSPECTOR_BACK_STACK_VISUAL.overrideForTest(true, disposable)
    val navigationWindow =
      window(ROOT, ROOT, 10, 20, 30, 40, rootViewQualifiedName = "rootType") {
        compose(COMPOSE1, "NavDisplay", "nav.kt")
      }
    layoutInspector.inspectorModel.update(navigationWindow, listOf(ROOT), 0)
    val navigationDisplayNode = layoutInspector.inspectorModel.get(COMPOSE1)!!

    connectMockClientWithBackStackProperties(navigationDisplayNode.drawId)

    val (panel, selectedTabState) = createUi()

    selectedTabState.enableLayoutInspector()
    testScheduler.advanceUntilIdle()

    layoutInspector.inspectorModel.setSelection(navigationDisplayNode, SelectionOrigin.COMPONENT_TREE)
    testScheduler.advanceUntilIdle()

    val mainSplitter = UIUtil.findComponentsOfType(panel.component, OnePixelSplitter::class.java).firstOrNull()
    mainSplitter?.proportion = 0.5f

    renderAndAssertImageSimilarity(panel, selectedTabState.tabComponents, dimension = Dimension(500, 800))
  }

  @Test
  fun testRemovedFromRunningDevices() = runTest {
    val (panel, selectedTabState) = createUi()

    selectedTabState.enableLayoutInspector()
    testScheduler.advanceUntilIdle()

    Disposer.dispose(selectedTabState)
    testScheduler.advanceUntilIdle()

    renderAndAssertImageSimilarity(panel, selectedTabState.tabComponents)
  }

  private fun createUi(): Pair<DevicePanel<*>, ActiveTabState> {
    val panel = emulatorViewRule.newEmulatorToolWindowPanel()

    val context = createDataContext(panel.component, EMPTY_CONTEXT)
    val streamingContent = STREAMING_CONTENT_PANEL_KEY.getData(context)
    assertThat(streamingContent).isNotNull()

    val tabComponents = TabComponents(disposable = panel, tabContentPanel = streamingContent!!, displayOwner = panel)

    val activeTabState =
      ActiveTabState(
        disposable = panel,
        project = project,
        deviceId = StreamingDeviceId.ofPhysicalDevice("0"),
        tabComponents = tabComponents,
        layoutInspector = layoutInspector,
      )

    return panel to activeTabState
  }

  private fun renderAndAssertImageSimilarity(
    panel: DevicePanel<*>,
    tabComponents: TabComponents,
    dimension: Dimension = screenDimension,
  ) {
    val rootPanel = BorderLayoutPanel()
    rootPanel.size = Dimension(dimension.width, dimension.height)
    rootPanel.addToCenter(panel.component)
    val fakeUi = FakeUi(rootPanel)
    waitForFrame(fakeUi, tabComponents.displayList.value)
    val image = fakeUi.render()

    assertSimilar(image, testName.methodName)
  }

  private fun assertSimilar(renderImage: BufferedImage, imageName: String, maxDiff: Double = DIFF_THRESHOLD) {
    val testDataPath = TEST_DATA_PATH.resolve(this.javaClass.simpleName)
    ImageDiffUtil.assertImageSimilar(resolveWorkspacePathUnchecked(testDataPath.resolve("$imageName.png").pathString), renderImage, maxDiff)
  }

  /** Wait until the displays have rendered their UI */
  private fun waitForFrame(fakeUi: FakeUi, displays: List<DisplayView>) {
    displays.forEach { display ->
      waitForCondition(20.seconds) {
        fakeUi.render()
        display.frameNumber >= 1u
      }
    }
  }

  /**
   * Connects a mock [InspectorClient] with a [PropertiesProvider] supplying navigation back stack entries and composable attributes to the
   * inspector model.
   */
  private fun connectMockClientWithBackStackProperties(navigationDisplayNodeId: Long) {
    val propertiesTable =
      PropertiesTable.create(
        HashBasedTable.create<String, String, InspectorPropertyItem>().apply {
          val modifierProperty =
            InspectorPropertyItem(
              ANDROID_URI,
              "modifier",
              PropertyType.STRING,
              "Modifier.fillMaxSize()",
              PropertySection.DECLARED,
              null,
              navigationDisplayNodeId,
              layoutInspector.inspectorModel,
            )
          put(ANDROID_URI, "modifier", modifierProperty)

          val contentDescriptionProperty =
            InspectorPropertyItem(
              ANDROID_URI,
              "contentDescription",
              PropertyType.STRING,
              "NavHost Container",
              PropertySection.DECLARED,
              null,
              navigationDisplayNodeId,
              layoutInspector.inspectorModel,
            )
          put(ANDROID_URI, "contentDescription", contentDescriptionProperty)

          val backStackEntries =
            listOf(
              ParameterItem(
                name = "0",
                type = PropertyType.STRING,
                value = "Home",
                section = PropertySection.PARAMETERS,
                viewId = navigationDisplayNodeId,
                lookup = layoutInspector.inspectorModel,
                rootId = navigationDisplayNodeId,
                index = 0,
              ),
              ParameterItem(
                name = "1",
                type = PropertyType.STRING,
                value = "Detail",
                section = PropertySection.PARAMETERS,
                viewId = navigationDisplayNodeId,
                lookup = layoutInspector.inspectorModel,
                rootId = navigationDisplayNodeId,
                index = 1,
              ),
            )

          val backStackGroup =
            ParameterGroupItem(
              name = "backStack",
              type = PropertyType.ITERABLE,
              value = "List[2]",
              section = PropertySection.PARAMETERS,
              viewId = navigationDisplayNodeId,
              lookup = layoutInspector.inspectorModel,
              rootId = navigationDisplayNodeId,
              index = 0,
              reference = null,
              children = backStackEntries.toMutableList(),
            )
          put("parameter", "backStack", backStackGroup)
        }
      )

    val propertiesProvider =
      object : PropertiesProvider {
        private val resultListeners = mutableListOf<ResultListener>()

        override fun addResultListener(listener: ResultListener) {
          resultListeners.add(listener)
        }

        override fun removeResultListener(listener: ResultListener) {
          resultListeners.remove(listener)
        }

        override fun requestProperties(view: ViewNode): Future<*> {
          resultListeners.forEach { listener -> listener.onResult(this, view, propertiesTable) }
          return Futures.immediateFuture(null)
        }
      }

    val mockInspectorClient: InspectorClient = mock()
    whenever(mockInspectorClient.state).thenReturn(InspectorClient.State.CONNECTED)
    whenever(mockInspectorClient.isConnected).thenReturn(true)
    whenever(mockInspectorClient.provider).thenReturn(propertiesProvider)

    layoutInspector.inspectorModel.updateConnection(mockInspectorClient)
  }
}
