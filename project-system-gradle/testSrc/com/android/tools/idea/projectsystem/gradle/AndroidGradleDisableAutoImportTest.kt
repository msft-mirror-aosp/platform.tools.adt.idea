package com.android.tools.idea.projectsystem.gradle

import com.android.tools.idea.IdeInfo
import com.android.tools.idea.gradle.util.AndroidStudioPreferences
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTrackerSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ProjectRule
import org.junit.Rule
import org.junit.Test

class AndroidGradleDisableAutoImportTest {
  @get:Rule val projectRule = ProjectRule()

  @Test
  fun testAutoImportDisabled() {
    if (IdeInfo.getInstance().isAndroidStudio) {
      val project = projectRule.project
      assertThat(Registry.`is`("external.system.auto.import.disabled")).isTrue()
      val projectTracker = ExternalSystemProjectTracker.getInstance(project)
      assertThat(projectTracker).isInstanceOf(RefreshOnlyAutoImportProjectTracker::class.java)
    }
  }

  @Test
  fun testExternalSystemAutoReloadAndConfigurableDisabled() {
    if (IdeInfo.getInstance().isAndroidStudio) {
      val project = projectRule.project
      val trackerSettings = ExternalSystemProjectTrackerSettings.getInstance(project)
      trackerSettings.autoReloadType = ExternalSystemProjectTrackerSettings.AutoReloadType.ALL

      val buildToolsConfigurablesBefore = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.id == "build.tools" }
      assertThat(buildToolsConfigurablesBefore.first().instanceClass).isEqualTo(AndroidStudioBuildToolsConfigurable::class.java.name)

      AndroidStudioPreferences.unregisterUnnecessaryExtensions(project)

      assertThat(trackerSettings.autoReloadType).isEqualTo(ExternalSystemProjectTrackerSettings.AutoReloadType.NONE)
      val buildToolsConfigurablesAfter =
        Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.id == "build.tools" }.map { it.instanceClass }
      assertThat(buildToolsConfigurablesAfter).containsExactly(AndroidStudioBuildToolsConfigurable::class.java.name)
      assertThat(buildToolsConfigurablesAfter)
        .doesNotContain("com.intellij.openapi.externalSystem.service.settings.ExternalSystemGroupConfigurable")
    }
  }
}
