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
package com.android.tools.idea.gradle.structure.configurables

import com.android.tools.idea.gradle.project.sync.snapshots.AndroidCoreTestProject
import com.android.tools.idea.gradle.project.sync.snapshots.TestProjectDefinition.Companion.prepareTestProject
import com.android.tools.idea.gradle.structure.configurables.dependencies.module.MODULE_DEPENDENCIES_PLACE_NAME
import com.android.tools.idea.gradle.structure.configurables.ui.IssuesViewerPanel
import com.android.tools.idea.gradle.structure.configurables.ui.dependencies.AbstractDependenciesPanel
import com.android.tools.idea.gradle.structure.configurables.ui.dependencies.DeclaredDependenciesTableView
import com.android.tools.idea.gradle.structure.configurables.ui.testStructure
import com.android.tools.idea.gradle.structure.model.PsDeclaredLibraryDependency
import com.android.tools.idea.gradle.structure.model.PsGeneralIssue
import com.android.tools.idea.gradle.structure.model.PsIssue
import com.android.tools.idea.gradle.structure.model.PsIssueType
import com.android.tools.idea.gradle.structure.model.android.PsAndroidModule
import com.android.tools.idea.gradle.structure.model.android.psTestWithContext
import com.android.tools.idea.gradle.structure.model.meta.DslText
import com.android.tools.idea.gradle.structure.model.meta.ParsedValue
import com.android.tools.idea.gradle.structure.quickfix.PsLibraryDependencyVersionQuickFixPath
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.navigation.Place
import com.intellij.util.ui.UIUtil
import javax.swing.event.HyperlinkEvent
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests for [DependenciesPerspectiveConfigurable] covering component creation across `<All Modules>`, Android modules, and Java
 * modules, as well as issue rendering and quick-fix hyperlink execution in `IssuesViewer` (regression coverage for b/401022308).
 */
@RunsInEdt
class DependenciesPerspectiveConfigurableTest {

  @get:Rule val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun testDependenciesTreeAndModulePanelsCreateComponents() {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.PSD_SAMPLE_GROOVY)
    projectRule.psTestWithContext(preparedProject, disableAnalysis = true, resolveModels = false) {
      val configurable =
        DependenciesPerspectiveConfigurable(context).also {
          Disposer.register(context, it)
          // Matches ProjectStructureConfigurable.reset(), which resets child perspectives upfront
          // before lazily invoking createComponent() when the perspective is first selected.
          it.reset()
        }

      try {
        PlatformTestUtil.waitWhileBusy(configurable.tree)
        val treeStructure = (configurable.tree.model as ConfigurablesTreeModel).rootNode.testStructure()
        val expectedStructure =
          """
          root
              <All Modules>
              app
              dyn_feature
              jav
              lib
              nested1
                  deep
              nested2
                  deep
                  trans
                      deep2
          """
            .trimIndent()
        assertThat(treeStructure.toString()).isEqualTo(expectedStructure)

        // Creating the component selects <All Modules> by default and instantiates
        // ProjectDependenciesConfigurable -> DependencyGraphPanel -> IssuesViewer -> QuickFixLinkHandler.
        val component = configurable.createComponent()
        awaitTreesAndEdt(component)

        assertThat(component.name).isEqualTo(DEPENDENCIES_VIEW)
        assertThat(configurable.selectedModule?.gradlePath).isEqualTo("<All Modules>")
        assertThat(UIUtil.findComponentOfType(component, AbstractDependenciesPanel::class.java)).isNotNull()

        // Select an Android module (:app) -> AndroidModuleDependenciesConfigurable -> DeclaredDependenciesPanel -> IssuesViewer.
        // Because :app has declared dependencies, selecting its first row mounts DependencyInfoPanel and all IssuesViewerPanels.
        assertThat(configurable.selectModule(":app")).isNotNull()
        awaitTreesAndEdt(component)
        assertThat(configurable.selectedModule?.gradlePath).isEqualTo(":app")

        val appDependenciesTable = findRequiredComponent(component, DeclaredDependenciesTableView::class.java)
        assertThat(appDependenciesTable.items.map { it.toText() }).contains("com.android.support.constraint:constraint-layout:1.1.0")
        val appIssuePanels = UIUtil.findComponentsOfType(component, IssuesViewerPanel::class.java)
        assertThat(appIssuePanels).hasSize(ISSUE_PANELS_PER_ISSUES_VIEWER)
        appIssuePanels.forEach { panel ->
          assertThat(panel.issuesView.hyperlinkListeners).isNotEmpty()
        }

        // Select a Java module (:jav) -> JavaModuleDependenciesConfigurable -> DeclaredDependenciesPanel -> IssuesViewer.
        assertThat(configurable.selectModule(":jav")).isNotNull()
        awaitTreesAndEdt(component)
        assertThat(configurable.selectedModule?.gradlePath).isEqualTo(":jav")
        assertThat(UIUtil.findComponentOfType(component, AbstractDependenciesPanel::class.java)).isNotNull()
        assertThat(UIUtil.findComponentOfType(component, DeclaredDependenciesTableView::class.java)).isNotNull()
      } finally {
        configurable.disposeUIResources()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      }
    }
  }

  @Test
  fun testIssuesViewerRendersAndExecutesQuickFixLink() {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.PSD_DEPENDENCY)
    projectRule.psTestWithContext(preparedProject, disableAnalysis = true, resolveModels = true) {
      val module = project.findModuleByName("mainModule")
      assertThat(module).isInstanceOf(PsAndroidModule::class.java)
      val mainModule = module as PsAndroidModule

      val plusDependencyText = "com.android.support:appcompat-v7:+"
      val matchingDependencies = mainModule.dependencies.findLibraryDependencies("com.android.support", "appcompat-v7")
      assertThat(matchingDependencies).hasSize(1)
      val plusDependency = matchingDependencies.single()
      assertThat(plusDependency.version).isEqualTo(ParsedValue.Set.Parsed("+", DslText.Literal))

      // Populate both the real analyzer issue and a version-update quick-fix issue on the '+' dependency.
      val analyzerIssues = context.analyzerDaemon.validate(mainModule).toList()
      val quickFixIssue =
        PsGeneralIssue(
          "Newer version available: <b>28.0.0</b>",
          "",
          plusDependency.path,
          PsIssueType.LIBRARY_UPDATES_AVAILABLE,
          PsIssue.Severity.UPDATE,
          listOf(PsLibraryDependencyVersionQuickFixPath(plusDependency, "28.0.0")),
        )
      context.analyzerDaemon.addAll(analyzerIssues + quickFixIssue, now = true)

      val configurable =
        DependenciesPerspectiveConfigurable(context).also {
          Disposer.register(context, it)
          // Matches ProjectStructureConfigurable.reset(), which resets child perspectives upfront
          // before lazily invoking createComponent() when the perspective is first selected.
          it.reset()
        }

      try {
        val component = configurable.createComponent()
        awaitTreesAndEdt(component)

        val place =
          Place().putPath(BASE_PERSPECTIVE_MODULE_PLACE_NAME, ":mainModule").putPath(MODULE_DEPENDENCIES_PLACE_NAME, plusDependencyText)
        val navigationCallback = configurable.navigateTo(place, false)
        PlatformTestUtil.waitForCallback(navigationCallback)
        assertThat(navigationCallback.isDone).isTrue()
        assertThat(navigationCallback.isRejected).isFalse()
        awaitTreesAndEdt(component)

        // The navigation callbacks resolve to DONE even when no module/row was selected, so verify the resulting UI state directly.
        assertThat(configurable.selectedModule?.gradlePath).isEqualTo(":mainModule")
        val dependenciesTable = findRequiredComponent(component, DeclaredDependenciesTableView::class.java)
        assertThat(dependenciesTable.selection.map { it.toText() }).containsExactly(plusDependencyText)

        // IssuesViewer toggles each panel's own visibility flag; isShowing would always be false in this headless (windowless) test.
        val visibleIssuePanels =
          UIUtil.findComponentsOfType(component, IssuesViewerPanel::class.java).filter {
            it.isVisible
          }
        assertThat(visibleIssuePanels).isNotEmpty()

        val combinedHtml = visibleIssuePanels.joinToString("\n") { it.issuesView.text }.replace(Regex("\\s+"), " ")
        assertThat(combinedHtml).contains("Avoid using '+' in version numbers; can lead to unpredictable and unrepeatable builds.")
        assertThat(combinedHtml).contains("psdFix://LibraryDependencyVersion")

        // Activate the psdFix:// hyperlink in IssuesViewerPanel to verify NavigationHyperlinkListener -> QuickFixLinkHandler
        // executes against the non-null PsContext and updates the declared dependency version.
        val quickFixPanel =
          assertNotNullWithMessage(
            visibleIssuePanels.firstOrNull { it.issuesView.text.contains("psdFix://LibraryDependencyVersion") },
            "Expected an IssuesViewerPanel containing psdFix://LibraryDependencyVersion in:\n$combinedHtml",
          )
        val panelHtml = quickFixPanel.issuesView.text
        val hrefMatch =
          assertNotNullWithMessage(
            Regex("""href="(psdFix://LibraryDependencyVersion[^"]+)"""").find(panelHtml),
            "Expected href attribute for psdFix://LibraryDependencyVersion in:\n$panelHtml",
          )
        val quickFixHref = hrefMatch.groupValues[1]

        quickFixPanel.issuesView.fireHyperlinkUpdate(
          HyperlinkEvent(quickFixPanel.issuesView, HyperlinkEvent.EventType.ACTIVATED, null, quickFixHref)
        )
        awaitTreesAndEdt(component)

        val updatedDependencies = mainModule.dependencies.findLibraryDependencies("com.android.support", "appcompat-v7")
        assertThat(updatedDependencies).hasSize(1)
        val updatedDependency: PsDeclaredLibraryDependency = updatedDependencies.single()
        assertThat(updatedDependency.spec.version).isEqualTo("28.0.0")
      } finally {
        configurable.disposeUIResources()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      }
    }
  }
}
