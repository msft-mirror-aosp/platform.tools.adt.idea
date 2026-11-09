/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.build.attribution.ui.controllers

import com.android.build.attribution.BuildAttributionWarningsFilter
import com.android.build.attribution.analyzers.IncompatiblePluginWarning
import com.android.build.attribution.data.GradlePluginsData
import com.android.build.attribution.data.PluginData
import com.android.build.attribution.ui.MockUiData
import com.android.build.attribution.ui.analytics.BuildAttributionUiAnalytics
import com.android.build.attribution.ui.data.TimeWithPercentage
import com.android.build.attribution.ui.model.BuildAnalyzerViewModel
import com.android.build.attribution.ui.view.details.ConfigurationCachePluginWarningDetailsView
import com.android.ide.common.gradle.Version
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.adtui.TreeWalker
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind
import com.google.wireless.android.sdk.stats.BuildAttributionUiEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.replaceService
import java.awt.Dimension
import java.awt.EventQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.SwingUtilities
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.AdditionalAnswers
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

class PluginVersionDeclarationFinderTest {

  @get:Rule val projectRule = AndroidProjectRule.onDisk()

  private lateinit var gradleBuildFile: VirtualFile

  @Before
  fun setUp() {
    WriteAction.runAndWait<Throwable> {
      gradleBuildFile = projectRule.fixture.tempDirFixture.createFile("build.gradle")
      Assert.assertTrue(gradleBuildFile.isWritable)
    }
  }

  @After
  fun tearDown() {
    UsageTracker.cleanAfterTesting()
  }

  @Test
  fun testPluginDeclaredInDependencyBlock() {
    WriteAction.runAndWait<Throwable> {
      VfsUtil.saveText(
        gradleBuildFile,
        """
        buildscript {
            repositories {}
            dependencies {
                classpath 'my.test:gradle:1.5.0'
            }
        }

        allprojects {
            repositories {}
        }
        """
          .trimIndent(),
      )
    }

    val buildGradleText = runReadAction { VfsUtilCore.loadText(gradleBuildFile) }

    PluginVersionDeclarationFinder(projectRule.project)
      .findFileToOpen(GradlePluginsData.DependencyCoordinates("my.test", "gradle"), setOf("my.test.plugin"))
      .let {
        Truth.assertThat(it?.file).isEqualTo(gradleBuildFile)
        Truth.assertThat(it?.offset).isEqualTo(buildGradleText.indexOf("'my.test:gradle:1.5.0'"))
      }
  }

  @Test
  fun testPluginDeclaredInPluginsBlock() {
    WriteAction.runAndWait<Throwable> {
      VfsUtil.saveText(
        gradleBuildFile,
        """
        buildscript {
            repositories {}
            dependencies {
            // Some other plugin defined here
                classpath 'my.test2:gradle:1.5.0'
            }
        }

        plugins {
        // Plugin of interest defined here
          id 'my.test.plugin' version '1.5.0' apply false
        }

        allprojects {
            repositories {}
        }
        """
          .trimIndent(),
      )
    }

    val buildGradleText = runReadAction { VfsUtilCore.loadText(gradleBuildFile) }

    PluginVersionDeclarationFinder(projectRule.project)
      .findFileToOpen(GradlePluginsData.DependencyCoordinates("my.test", "gradle"), setOf("my.test.plugin"))
      .let {
        Truth.assertThat(it?.file).isEqualTo(gradleBuildFile)
        Truth.assertThat(it?.offset).isEqualTo(buildGradleText.indexOf("id 'my.test.plugin'"))
      }
  }

  @Test
  fun testPluginDeclarationNotFound() {
    WriteAction.runAndWait<Throwable> {
      VfsUtil.saveText(
        gradleBuildFile,
        """
        buildscript {
            repositories {}
            dependencies {
            // Some other plugin defined here
                classpath 'my.test2:gradle:1.5.0'
            }
        }

        plugins {
        // Some other plugin defined here
          id 'my.test.plugin3' version '1.5.0' apply false
        }

        allprojects {
            repositories {}
        }
        """
          .trimIndent(),
      )
    }

    val buildGradleText = runReadAction { VfsUtilCore.loadText(gradleBuildFile) }

    PluginVersionDeclarationFinder(projectRule.project)
      .findFileToOpen(GradlePluginsData.DependencyCoordinates("my.test", "gradle"), setOf("my.test.plugin"))
      .let {
        Truth.assertThat(it?.file).isEqualTo(gradleBuildFile)
        // Cursor sets at psi element of dependencies block.
        Truth.assertThat(it?.offset).isEqualTo(buildGradleText.indexOf("dependencies {") + "dependencies ".length)
      }
  }

  @Test
  fun testEvenDependenciesBlockNotFound() {
    WriteAction.runAndWait<Throwable> {
      VfsUtil.saveText(
        gradleBuildFile,
        """
        buildscript {
            repositories {}
        }

        plugins {
        // Some other plugin defined here
          id 'my.test.plugin3' version '1.5.0' apply false
        }

        allprojects {
            repositories {}
        }
        """
          .trimIndent(),
      )
    }

    PluginVersionDeclarationFinder(projectRule.project)
      .findFileToOpen(GradlePluginsData.DependencyCoordinates("my.test", "gradle"), setOf("my.test.plugin"))
      .let {
        Truth.assertThat(it?.file).isEqualTo(gradleBuildFile)
        // Just open root build.gradle in this case.
        Truth.assertThat(it?.offset).isEqualTo(-1)
      }
  }

  @Test
  fun testGoToPluginVersionDeclarationButtonFromLockFreeEdt() {
    WriteAction.runAndWait<Throwable> {
      projectRule.fixture.tempDirFixture.createFile("settings.gradle", "rootProject.name = 'testProject'")
      projectRule.fixture.tempDirFixture.createFile("gradle/libs.versions.toml", "[versions]\nmyPlugin = \"1.5.0\"\n")
      VfsUtil.saveText(
        gradleBuildFile,
        """
        buildscript {
            repositories {}
            dependencies {
                classpath 'my.test:gradle:1.5.0'
            }
        }
        """
          .trimIndent(),
      )
    }
    val buildGradleText = runReadAction { VfsUtilCore.loadText(gradleBuildFile) }

    val tracker = TestUsageTracker(VirtualTimeScheduler())
    UsageTracker.setWriterForTest(tracker)
    val analytics = BuildAttributionUiAnalytics(projectRule.project, uiSizeProvider = { Dimension(300, 200) })
    analytics.newReportSessionId(UUID.randomUUID().toString())

    val realProvider = GradleModelProvider.getInstance()
    val getProjectModelCalls = AtomicInteger(0)
    val parsedOnEdt = AtomicBoolean(true)
    val parsedWithReadAccess = AtomicBoolean(false)
    val spyProvider = Mockito.mock(GradleModelProvider::class.java, AdditionalAnswers.delegatesTo<Any>(realProvider))
    Mockito.doAnswer { invocation ->
        getProjectModelCalls.incrementAndGet()
        val app = ApplicationManager.getApplication()
        parsedOnEdt.set(app.isDispatchThread)
        parsedWithReadAccess.set(app.isReadAccessAllowed)
        realProvider.getProjectModel(invocation.getArgument<Project>(0))
      }
      .`when`(spyProvider)
      .getProjectModel(eq(projectRule.project))
    ApplicationManager.getApplication().replaceService(GradleModelProvider::class.java, spyProvider, projectRule.testRootDisposable)

    val pluginData =
      PluginData(PluginData.PluginType.BINARY_PLUGIN, "my.test.PluginClass").apply {
        recordDisplayName(PluginData.DisplayName("my.test.plugin", ":"))
      }
    val pluginInfo =
      GradlePluginsData.PluginInfo(
        name = "My Test Plugin",
        pluginClasses = listOf(pluginData.idName),
        pluginArtifact = GradlePluginsData.DependencyCoordinates("my.test", "gradle"),
        configurationCachingCompatibleFrom = Version.parse("2.0.0"),
      )
    val warning = IncompatiblePluginWarning(pluginData, Version.parse("1.5.0"), pluginInfo)

    lateinit var button: JButton
    EdtTestUtil.runInEdtAndWait<Throwable> {
      val model = BuildAnalyzerViewModel(MockUiData(), BuildAttributionWarningsFilter())
      val controller = BuildAnalyzerViewController(model, projectRule.project, analytics, mock())
      val detailsView = ConfigurationCachePluginWarningDetailsView(warning, TimeWithPercentage(100, 1000), controller)
      button = TreeWalker(detailsView.pagePanel).descendants().filterIsInstance<JButton>().single()
      Truth.assertThat(button.text).isEqualTo("Go to plugin version declaration")
    }

    SwingUtilities.invokeAndWait {
      val app = ApplicationManager.getApplication()
      Truth.assertThat(EventQueue.isDispatchThread()).isTrue()
      Truth.assertThat(app.isDispatchThread).isTrue()
      Truth.assertThat(app.isWriteIntentLockAcquired).isFalse()
      Truth.assertThat(app.isReadAccessAllowed).isFalse()
      Truth.assertThat(app.isWriteAccessAllowed).isFalse()
      button.doClick()
    }

    Truth.assertThat(getProjectModelCalls.get()).isEqualTo(1)
    Truth.assertThat(parsedOnEdt.get()).isFalse()
    Truth.assertThat(parsedWithReadAccess.get()).isTrue()

    EdtTestUtil.runInEdtAndWait<Throwable> {
      val editorManager = FileEditorManager.getInstance(projectRule.project)
      Truth.assertThat(editorManager.openFiles.asList()).containsExactly(gradleBuildFile)
      Truth.assertThat(editorManager.selectedTextEditor?.caretModel?.offset).isEqualTo(buildGradleText.indexOf("'my.test:gradle:1.5.0'"))
    }

    val buildAttributionEvents = tracker.usages.filter { it.studioEvent.kind == EventKind.BUILD_ATTRIBUTION_UI_EVENT }
    Truth.assertThat(buildAttributionEvents.map { it.studioEvent.buildAttributionUiEvent.eventType })
      .containsExactly(BuildAttributionUiEvent.EventType.UPDATE_PLUGIN_BUTTON_CLICKED)
  }
}
