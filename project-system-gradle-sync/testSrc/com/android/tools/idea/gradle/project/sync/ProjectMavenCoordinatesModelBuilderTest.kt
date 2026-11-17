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
package com.android.tools.idea.gradle.project.sync

import com.android.SdkConstants
import com.android.ide.gradle.model.GradlePluginModel
import com.android.ide.gradle.model.builder.AndroidStudioToolingPlugin
import com.android.ide.gradle.model.builder.ProjectMavenCoordinates
import com.android.ide.gradle.model.builder.ProjectMavenCoordinatesImpl
import com.android.ide.gradle.model.builder.ProjectMavenCoordinatesModelBuilder
import com.android.tools.idea.gradle.util.GradleProjectSystemUtil
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.FileUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.reflect.KType
import kotlin.test.assertFailsWith
import org.gradle.api.Project
import org.gradle.api.plugins.PluginManager
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

class ProjectMavenCoordinatesModelBuilderTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val builder = ProjectMavenCoordinatesModelBuilder()

  @Test
  fun `canBuild matches only ProjectMavenCoordinates`() {
    assertThat(builder.canBuild(ProjectMavenCoordinates::class.java.name)).isTrue()
    assertThat(builder.canBuild("com.example.OtherModel")).isFalse()
  }

  @Test
  fun `buildAll rejects unexpected model name`() {
    val project = mockProject(group = "com.example", name = "lib", version = "1.0")
    assertFailsWith<IllegalStateException> { builder.buildAll("com.example.OtherModel", project) }
  }

  @Test
  fun `buildAll collects explicit group, name, and version`() {
    val project = mockProject(group = "com.example.libs", name = "network", version = "2.1.0")

    val coordinates = builder.buildAll(ProjectMavenCoordinates::class.java.name, project) as ProjectMavenCoordinates

    assertThat(coordinates).isEqualTo(ProjectMavenCoordinatesImpl("com.example.libs", "network", "2.1.0"))
  }

  @Test
  fun `buildAll preserves blank group and unspecified version without filtering`() {
    val project = mockProject(group = "", name = "rootProject", version = "unspecified")

    val coordinates = builder.buildAll(ProjectMavenCoordinates::class.java.name, project) as ProjectMavenCoordinates

    assertThat(coordinates).isEqualTo(ProjectMavenCoordinatesImpl("", "rootProject", "unspecified"))
  }

  @Test
  fun `buildAll handles null group and version safely`() {
    val project = mockProject(group = null, name = "nullableLib", version = null)

    val coordinates = builder.buildAll(ProjectMavenCoordinates::class.java.name, project) as ProjectMavenCoordinates

    assertThat(coordinates).isEqualTo(ProjectMavenCoordinatesImpl("", "nullableLib", Project.DEFAULT_VERSION))
  }

  @Test
  fun `buildAll eagerly converts non-serializable group and version objects to String`() {
    // Non-serializable objects (like Groovy GString or custom lazy version objects) must be converted to String in the daemon.
    val nonSerializableGroup =
      object {
        override fun toString(): String = "com.example.dynamic"
      }
    val nonSerializableVersion =
      object {
        override fun toString(): String = "1.0.0-SNAPSHOT"
      }
    val project = mockProject(group = nonSerializableGroup, name = "feature", version = nonSerializableVersion)

    val model = builder.buildAll(ProjectMavenCoordinates::class.java.name, project) as ProjectMavenCoordinatesImpl
    val bytes =
      ByteArrayOutputStream().use { out ->
        ObjectOutputStream(out).use { it.writeObject(model) }
        out.toByteArray()
      }
    val deserialized = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as ProjectMavenCoordinatesImpl

    assertThat(deserialized).isEqualTo(ProjectMavenCoordinatesImpl("com.example.dynamic", "feature", "1.0.0-SNAPSHOT"))
  }

  @Test
  fun `AndroidStudioToolingPlugin registers ProjectMavenCoordinatesModelBuilder`() {
    val registry = Mockito.mock(ToolingModelBuilderRegistry::class.java)
    val project = Mockito.mock(Project::class.java)
    whenever(project.pluginManager).thenReturn(Mockito.mock(PluginManager::class.java))

    val constructor = AndroidStudioToolingPlugin::class.java.getDeclaredConstructor(ToolingModelBuilderRegistry::class.java)
    constructor.isAccessible = true
    val plugin = constructor.newInstance(registry)
    plugin.apply(project)

    val captor = ArgumentCaptor.forClass(ToolingModelBuilder::class.java)
    Mockito.verify(registry, Mockito.atLeastOnce()).register(captor.capture())
    assertThat(captor.allValues.any { it is ProjectMavenCoordinatesModelBuilder }).isTrue()
  }

  @Test
  fun `collects ProjectMavenCoordinates across composite build via Gradle Tooling API with project isolation`() {
    val rootBuildDir = tempFolder.newFolder("rootBuild")
    val includedBuildDir = tempFolder.newFolder("includedBuild")

    File(rootBuildDir, "settings.gradle")
      .writeText(
        """
        rootProject.name = 'my-composite-root'
        include(':app')
        includeBuild('../includedBuild')
        """
          .trimIndent()
      )
    File(rootBuildDir, "app").mkdirs()
    File(rootBuildDir, "app/build.gradle")
      .writeText(
        """
        group = 'com.example.app'
        version = '1.0.0'
        """
          .trimIndent()
      )

    File(includedBuildDir, "settings.gradle")
      .writeText(
        """
        rootProject.name = 'included-libs'
        include(':lib-explicit', ':lib-props', ':lib-gstring', ':lib-default-group')
        """
          .trimIndent()
      )
    File(includedBuildDir, "lib-default-group").mkdirs()
    File(includedBuildDir, "lib-default-group/build.gradle").writeText("")
    File(includedBuildDir, "lib-explicit").mkdirs()
    File(includedBuildDir, "lib-explicit/build.gradle")
      .writeText(
        """
        group = 'com.included'
        version = '2.3.4'
        """
          .trimIndent()
      )

    File(includedBuildDir, "lib-props").mkdirs()
    File(includedBuildDir, "lib-props/gradle.properties")
      .writeText(
        """
        group=com.included.props
        version=0.5.0
        """
          .trimIndent()
      )
    File(includedBuildDir, "lib-props/build.gradle").writeText("")

    File(includedBuildDir, "lib-gstring").mkdirs()
    // Leave version unset ("unspecified") and use a Groovy GString for group.
    File(includedBuildDir, "lib-gstring/build.gradle")
      .writeText(
        """
        def orgPrefix = 'com.included'
        group = "${'$'}{orgPrefix}.dynamic"
        """
          .trimIndent()
      )

    val gradleDist =
      checkNotNull(GradleProjectSystemUtil.findEmbeddedGradleDistributionFile(SdkConstants.GRADLE_LATEST_VERSION)) {
        "Embedded Gradle distribution not found for ${SdkConstants.GRADLE_LATEST_VERSION}"
      }
    val toolingJars =
      listOf(GradlePluginModel::class.java, AndroidStudioToolingPlugin::class.java, KType::class.java).mapNotNull {
        PathManager.getJarPathForClass(it)?.let(FileUtil::toCanonicalPath)
      }
    val initScript =
      tempFolder.newFile("studio-tooling-init.gradle").apply {
        writeText(
          """
          initscript {
            dependencies {
              classpath files([${toolingJars.joinToString(", ") { "'$it'" }}])
            }
          }
          lifecycle.beforeProject {
            apply plugin: ${AndroidStudioToolingPlugin::class.java.name}
          }
          """
            .trimIndent()
        )
      }
    val args = listOf("--offline", "-Dorg.gradle.unsafe.isolated-projects=true", "--init-script", initScript.absolutePath)

    val gradleUserHome =
      System.getProperty("gradle.user.home")?.takeIf { it.isNotBlank() }?.let(::File) ?: tempFolder.newFolder("gradleUserHome")

    val connector =
      GradleConnector.newConnector()
        .forProjectDirectory(rootBuildDir)
        .useDistribution(gradleDist.toURI())
        .useGradleUserHomeDir(gradleUserHome)

    val collected =
      try {
        connector.connect().use { connection ->
          connection.action(CollectMavenCoordinatesAction()).withArguments(args).setJavaHome(File(System.getProperty("java.home"))).run()
        }
      } finally {
        DefaultGradleConnector.close()
      }

    assertThat(collected)
      .containsExactlyEntriesIn(
        mapOf(
          // Root build: root project has blank group and "unspecified" version by default.
          "my-composite-root::" to listOf("", "my-composite-root", "unspecified"),
          "my-composite-root::app" to listOf("com.example.app", "app", "1.0.0"),
          // Included build: root project + subprojects with explicit, gradle.properties, GString, and default coordinates.
          "included-libs::" to listOf("", "included-libs", "unspecified"),
          "included-libs::lib-explicit" to listOf("com.included", "lib-explicit", "2.3.4"),
          "included-libs::lib-props" to listOf("com.included.props", "lib-props", "0.5.0"),
          "included-libs::lib-gstring" to listOf("com.included.dynamic", "lib-gstring", "unspecified"),
          "included-libs::lib-default-group" to listOf("included-libs", "lib-default-group", "unspecified"),
        )
      )
  }

  private fun mockProject(group: Any?, name: String, version: Any?): Project {
    val project = Mockito.mock(Project::class.java)
    whenever(project.group).thenReturn(group)
    whenever(project.name).thenReturn(name)
    whenever(project.version).thenReturn(version)
    return project
  }
}

// Top-level BuildAction (not nested in ProjectMavenCoordinatesModelBuilderTest) so Gradle's ClasspathInferer does not walk IntelliJ test
// dependencies referenced by the outer test class.
private class CollectMavenCoordinatesAction : BuildAction<Map<String, List<String>>>, Serializable {
  override fun execute(controller: BuildController): Map<String, List<String>> {
    val result = LinkedHashMap<String, List<String>>()
    val allBuilds = ArrayList<org.gradle.tooling.model.gradle.GradleBuild>()
    allBuilds.add(controller.buildModel)
    allBuilds.addAll(controller.buildModel.includedBuilds.all)
    for (build in allBuilds) {
      val buildName = build.rootProject.name
      for (project in build.projects.all) {
        val coords =
          controller.findModel(project, ProjectMavenCoordinates::class.java)
            ?: throw IllegalStateException("Missing ProjectMavenCoordinates for $buildName:${project.path}")
        result["$buildName:${project.path}"] = arrayListOf(coords.group, coords.name, coords.version)
      }
    }
    return result
  }
}
