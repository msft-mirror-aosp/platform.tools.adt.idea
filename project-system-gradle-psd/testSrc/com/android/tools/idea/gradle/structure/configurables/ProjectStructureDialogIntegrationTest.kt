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
import com.android.tools.idea.structure.dialog.ProjectStructureConfigurable
import com.android.tools.idea.structure.dialog.SidePanel
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.navigation.Place
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end integration tests for [ProjectStructureConfigurable] and [SidePanel] navigation across all Project Structure Dialog (PSD)
 * perspectives and deep [Place] paths (regression coverage for b/401022308).
 */
@RunsInEdt
class ProjectStructureDialogIntegrationTest {

  @get:Rule val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun testSidePanelAndDeepPlaceNavigation_multiModuleProject() {
    // Share the synced 10-module PSD_SAMPLE_GROOVY project across two isolated PSD dialog lifecycles
    // (SidePanel perspective traversal and deep Place navigation) to avoid redundant multi-module sync overhead.
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.PSD_SAMPLE_GROOVY)
    preparedProject.open { project ->
      val psd = ProjectStructureConfigurable.getInstance(project)
      verifySidePanelNavigationAcrossAllPerspectives(psd)
      verifyDeepPlaceNavigationToModuleDependency(psd)
    }
  }

  @Test
  fun testSidePanelNavigationToDependencies_versionCatalogProject() {
    // Covers the Gradle Version Catalog PSD workflow from b/401022308
    // (originally GradleVersionCatalogTest#testProjectStructureDialog).
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.PSD_VERSION_CATALOG_SAMPLE_GROOVY)
    preparedProject.open { project ->
      val psd = ProjectStructureConfigurable.getInstance(project)
      val rootComponent = createInitializedPsdComponent(psd)

      try {
        psd.reset()
        awaitTreesAndEdt(rootComponent)

        val sidePanel = findRequiredComponent(rootComponent, SidePanel::class.java)
        sidePanel.selectPerspective(psd, rootComponent, "Variables")
        sidePanel.selectPerspective(psd, rootComponent, DEPENDENCIES_PERSPECTIVE_DISPLAY_NAME)

        val dependenciesConfigurable = findRequiredConfigurable(psd, DependenciesPerspectiveConfigurable::class.java)
        assertThat(dependenciesConfigurable.selectedModule?.gradlePath).isEqualTo("<All Modules>")

        assertThat(dependenciesConfigurable.selectModule(":app")).isNotNull()
        awaitTreesAndEdt(rootComponent)

        val dependenciesTable = findRequiredComponent(rootComponent, DeclaredDependenciesTableView::class.java)
        val dependencyTexts = dependenciesTable.items.map { it.toText() }
        assertThat(dependencyTexts).contains("com.google.guava:guava:19.0")
        assertThat(dependencyTexts).contains("junit:junit:4.12")
        assertThat(UIUtil.findComponentsOfType(rootComponent, IssuesViewerPanel::class.java)).hasSize(ISSUE_PANELS_PER_ISSUES_VIEWER)
      } finally {
        psd.disposeUIResources()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      }
    }
  }

  private fun verifySidePanelNavigationAcrossAllPerspectives(psd: ProjectStructureConfigurable) {
    val rootComponent = createInitializedPsdComponent(psd)

    try {
      psd.reset()
      awaitTreesAndEdt(rootComponent)

      val sidePanel = findRequiredComponent(rootComponent, SidePanel::class.java)
      assertThat(sidePanel.perspectiveNames())
        .containsExactly(
          "Project",
          "SDK Location",
          "Variables",
          "Modules",
          "Dependencies",
          "Build Variants",
          "Suggestions",
        )
        .inOrder()

      // First, click directly from Project -> Dependencies (exact b/401022308 user flow),
      // verifying <All Modules> and DependencyGraphPanel / IssuesViewer initialize cleanly.
      sidePanel.selectPerspective(psd, rootComponent, DEPENDENCIES_PERSPECTIVE_DISPLAY_NAME)
      val initialDependenciesConfigurable = findRequiredConfigurable(psd, DependenciesPerspectiveConfigurable::class.java)
      assertThat(initialDependenciesConfigurable.selectedModule?.gradlePath).isEqualTo("<All Modules>")
      assertThat(UIUtil.findComponentOfType(rootComponent, AbstractDependenciesPanel::class.java)).isNotNull()

      // Explicitly traverse every perspective via SidePanel selection, exercising
      // SidePanel -> ProjectStructureConfigurable.navigateTo -> Configurable.createComponent() across all views.
      sidePanel.selectPerspective(psd, rootComponent, "Project")
      sidePanel.selectPerspective(psd, rootComponent, "SDK Location")
      sidePanel.selectPerspective(psd, rootComponent, "Variables")
      sidePanel.selectPerspective(psd, rootComponent, "Modules")

      // Visiting "Modules" immediately prior selects ":app", which CrossModuleUiStateComponent preserves into "Dependencies".
      sidePanel.selectPerspective(psd, rootComponent, DEPENDENCIES_PERSPECTIVE_DISPLAY_NAME)
      val dependenciesView =
        assertNotNullWithMessage(
          UIUtil.findComponentsOfType(rootComponent, JComponent::class.java).firstOrNull { it.name == DEPENDENCIES_VIEW },
          "Expected component named $DEPENDENCIES_VIEW",
        )

      val dependenciesConfigurable = findRequiredConfigurable(psd, DependenciesPerspectiveConfigurable::class.java)
      assertThat(dependenciesConfigurable.selectedModule?.gradlePath).isEqualTo(":app")
      assertThat(UIUtil.findComponentsOfType(dependenciesView, IssuesViewerPanel::class.java)).hasSize(ISSUE_PANELS_PER_ISSUES_VIEWER)

      // Switch across Android library, Java, <All Modules>, and Android app modules within Dependencies.
      selectAndVerifyModuleInDependencies(dependenciesConfigurable, dependenciesView, ":lib")
      selectAndVerifyModuleInDependencies(dependenciesConfigurable, dependenciesView, ":jav")
      selectAndVerifyModuleInDependencies(dependenciesConfigurable, dependenciesView, "<All Modules>")
      selectAndVerifyModuleInDependencies(dependenciesConfigurable, dependenciesView, ":app")

      sidePanel.selectPerspective(psd, rootComponent, "Build Variants")

      sidePanel.selectPerspective(psd, rootComponent, "Suggestions")
      assertNotNullWithMessage(
        UIUtil.findComponentsOfType(rootComponent, JComponent::class.java).firstOrNull { it.name == "SuggestionsView" },
        "Expected component named SuggestionsView",
      )

      // Reset the dialog's last-edited perspective to "Project" so the next PSD session in this project starts from the default view.
      sidePanel.selectPerspective(psd, rootComponent, "Project")
    } finally {
      psd.disposeUIResources()
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
  }

  private fun verifyDeepPlaceNavigationToModuleDependency(psd: ProjectStructureConfigurable) {
    val rootComponent = createInitializedPsdComponent(psd)

    try {
      psd.reset()
      awaitTreesAndEdt(rootComponent)

      val targetDependency = "com.android.support.constraint:constraint-layout:1.1.0"
      val place =
        Place()
          .putPath(
            ProjectStructureConfigurable.CATEGORY_NAME,
            DEPENDENCIES_PERSPECTIVE_DISPLAY_NAME,
          )
          .putPath(BASE_PERSPECTIVE_MODULE_PLACE_NAME, ":app")
          .putPath(MODULE_DEPENDENCIES_PLACE_NAME, targetDependency)

      val navigationCallback = psd.navigateTo(place, false)
      PlatformTestUtil.waitForCallback(navigationCallback)
      assertWithMessage("[DeepPlace] navigation callback done").that(navigationCallback.isDone).isTrue()
      assertWithMessage("[DeepPlace] navigation callback rejected").that(navigationCallback.isRejected).isFalse()
      awaitTreesAndEdt(rootComponent)

      val sidePanel = findRequiredComponent(rootComponent, SidePanel::class.java)
      assertWithMessage("[DeepPlace] selected SidePanel perspective")
        .that(sidePanel.descriptor.getTextFor(sidePanel.list.selectedValue))
        .isEqualTo(DEPENDENCIES_PERSPECTIVE_DISPLAY_NAME)

      val dependenciesConfigurable = findRequiredConfigurable(psd, DependenciesPerspectiveConfigurable::class.java)
      assertWithMessage("[DeepPlace] selected module").that(dependenciesConfigurable.selectedModule?.gradlePath).isEqualTo(":app")

      val dependenciesTable = findRequiredComponent(rootComponent, DeclaredDependenciesTableView::class.java)
      assertWithMessage("[DeepPlace] selected dependency row")
        .that(dependenciesTable.selection.map { it.toText() })
        .containsExactly(targetDependency)
    } finally {
      psd.disposeUIResources()
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
  }

  private fun selectAndVerifyModuleInDependencies(
    dependenciesConfigurable: DependenciesPerspectiveConfigurable,
    dependenciesView: JComponent,
    modulePath: String,
  ) {
    assertNotNullWithMessage(dependenciesConfigurable.selectModule(modulePath), "Expected module $modulePath to exist in Dependencies tree")
    awaitTreesAndEdt(dependenciesView)
    assertThat(dependenciesConfigurable.selectedModule?.gradlePath).isEqualTo(modulePath)
    assertThat(UIUtil.findComponentOfType(dependenciesView, AbstractDependenciesPanel::class.java)).isNotNull()
  }

  private fun SidePanel.perspectiveNames(): List<String> =
    (0 until list.model.size).mapNotNull { index -> descriptor.getTextFor(list.model.getElementAt(index)) }

  private fun SidePanel.selectPerspective(
    psd: ProjectStructureConfigurable,
    rootComponent: JComponent,
    perspectiveName: String,
  ) {
    val index =
      assertNotNullWithMessage(
        (0 until list.model.size).firstOrNull { candidateIndex ->
          descriptor.getTextFor(list.model.getElementAt(candidateIndex)) == perspectiveName
        },
        "Expected perspective '$perspectiveName' in SidePanel ${perspectiveNames()}",
      )
    list.selectedIndex = index
    awaitTreesAndEdt(rootComponent)

    val currentPlace = Place()
    psd.queryPlace(currentPlace)
    assertThat(currentPlace.getPath(ProjectStructureConfigurable.CATEGORY_NAME)).isEqualTo(perspectiveName)
  }

  private fun createInitializedPsdComponent(psd: ProjectStructureConfigurable): JComponent =
    assertNotNullWithMessage(
      psd.createComponent(),
      "Expected ProjectStructureConfigurable.createComponent() to return a non-null root component",
    )
}
