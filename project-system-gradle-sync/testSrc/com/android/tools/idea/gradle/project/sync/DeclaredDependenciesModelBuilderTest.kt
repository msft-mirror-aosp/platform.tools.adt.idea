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

import com.android.ide.gradle.model.dependencies.CoordinatesImpl
import com.android.ide.gradle.model.dependencies.DeclaredDependencies
import com.android.ide.gradle.model.dependencies.DeclaredDependenciesImpl
import com.android.ide.gradle.model.dependencies.DeclaredDependenciesModelBuilder
import com.android.tools.idea.gradle.model.impl.IdeDeclaredDependenciesImpl
import com.android.tools.idea.gradle.model.impl.IdeDeclaredDependenciesImpl.IdeCoordinatesImpl
import com.android.tools.idea.gradle.project.sync.idea.AndroidGradleProjectResolver.Companion.toIdeDeclaredDependencies
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Proxy
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.invocation.Gradle
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

class DeclaredDependenciesModelBuilderTest {

  private val builder = DeclaredDependenciesModelBuilder()

  @Test
  fun `canBuild matches only DeclaredDependencies`() {
    assertThat(builder.canBuild(DeclaredDependencies::class.java.name)).isTrue()
    assertThat(builder.canBuild("com.example.OtherModel")).isFalse()
  }

  @Test
  fun `buildAll collects external module dependencies across all configurations into allOutgoingExternalModuleDependencies`() {
    val project =
      mockProject(
        path = ":app",
        configurations =
          listOf(
            mockConfiguration("implementation", listOf(mockExternalDependency("com.example", "core", "1.0.0"))),
            mockConfiguration("api", listOf(mockExternalDependency("com.example", "api-lib", null))),
            mockConfiguration("debugImplementation", listOf(mockExternalDependency("com.example", "debug-lib", "1.0"))),
            mockConfiguration("releaseImplementation", listOf(mockExternalDependency("com.included", "included-lib", "2.0.0"))),
            mockConfiguration("freeDebugImplementation", listOf(mockExternalDependency("com.included", "flavor-lib", "1.0"))),
            mockConfiguration("kapt", listOf(mockExternalDependency("com.example", "processor", "3.0"))),
            mockConfiguration("testImplementation", listOf(mockExternalDependency("junit", "junit", "4.13.2"))),
            mockConfiguration("androidTestImplementation", listOf(mockExternalDependency(null, "no-group-lib", "1.0"))),
          ),
      )

    val model = builder.buildAll(DeclaredDependencies::class.java.name, project) as DeclaredDependencies

    assertThat(model.allOutgoingExternalModuleDependencies)
      .containsExactly(
        "implementation",
        listOf(CoordinatesImpl("com.example", "core", "1.0.0")),
        "api",
        listOf(CoordinatesImpl("com.example", "api-lib", null)),
        "debugImplementation",
        listOf(CoordinatesImpl("com.example", "debug-lib", "1.0")),
        "releaseImplementation",
        listOf(CoordinatesImpl("com.included", "included-lib", "2.0.0")),
        "freeDebugImplementation",
        listOf(CoordinatesImpl("com.included", "flavor-lib", "1.0")),
        "kapt",
        listOf(CoordinatesImpl("com.example", "processor", "3.0")),
        "testImplementation",
        listOf(CoordinatesImpl("junit", "junit", "4.13.2")),
        "androidTestImplementation",
        listOf(CoordinatesImpl(null, "no-group-lib", "1.0")),
      )
    assertThat(model.configurationsToCoordinates).isEmpty()
    assertThat(model.allOutgoingProjectsDependenciesToConfigurations).isEmpty()
  }

  @Test
  fun `buildAll puts fallback non-external dependencies in configurationsToCoordinates for CONFIGURATIONS_OF_INTEREST only`() {
    val fileDep = Mockito.mock(FileCollectionDependency::class.java)
    whenever(fileDep.group).thenReturn(null)
    whenever(fileDep.name).thenReturn("unspecified")
    whenever(fileDep.version).thenReturn(null)

    val project =
      mockProject(
        path = ":app",
        configurations =
          listOf(
            mockConfiguration("implementation", listOf(fileDep, mockExternalDependency("com.example", "lib", "1.0"))),
            mockConfiguration("releaseImplementation", listOf(fileDep)),
          ),
      )

    val model = builder.buildAll(DeclaredDependencies::class.java.name, project) as DeclaredDependencies

    assertThat(model.allOutgoingExternalModuleDependencies)
      .containsExactly("implementation", listOf(CoordinatesImpl("com.example", "lib", "1.0")))
    assertThat(model.configurationsToCoordinates).containsExactly("implementation", listOf(CoordinatesImpl(null, "unspecified", null)))
  }

  @Test
  fun `buildAll collects project dependencies and filters self dependencies`() {
    val project =
      mockProject(
        path = ":app",
        gradleVersion = "8.11",
        configurations =
          listOf(
            mockConfiguration("implementation", listOf(mockProjectDependency(":lib"), mockProjectDependency(":app"))),
            mockConfiguration("releaseImplementation", listOf(mockProjectDependency(":lib"), mockProjectDependency(":releaseLib"))),
          ),
      )

    val model = builder.buildAll(DeclaredDependencies::class.java.name, project) as DeclaredDependencies

    assertThat(model.allOutgoingProjectsDependenciesToConfigurations)
      .containsExactly(":lib", setOf("implementation", "releaseImplementation"), ":releaseLib", setOf("releaseImplementation"))
    assertThat(model.configurationsToCoordinates).isEmpty()
    assertThat(model.allOutgoingExternalModuleDependencies).isEmpty()
  }

  @Test
  fun `buildAll supports pre-8_11 project dependencies via dependencyProject`() {
    val project =
      mockProject(
        path = ":app",
        gradleVersion = "8.10",
        configurations =
          listOf(
            mockConfiguration(
              "implementation",
              listOf(mockLegacyProjectDependency(":lib"), mockLegacyProjectDependency(":app")),
            )
          ),
      )

    val model = builder.buildAll(DeclaredDependencies::class.java.name, project) as DeclaredDependencies

    assertThat(model.allOutgoingProjectsDependenciesToConfigurations).containsExactly(":lib", setOf("implementation"))
  }

  @Test
  fun `built model is serializable`() {
    val project =
      mockProject(
        path = ":app",
        configurations =
          listOf(
            mockConfiguration("implementation", listOf(mockExternalDependency("com.example", "core", "1.0"))),
            mockConfiguration(
              "releaseImplementation",
              listOf(mockExternalDependency("com.included", "lib", "1.0"), mockProjectDependency(":localLib")),
            ),
          ),
      )

    val model = builder.buildAll(DeclaredDependencies::class.java.name, project) as DeclaredDependenciesImpl
    val bytes =
      ByteArrayOutputStream().use { out ->
        ObjectOutputStream(out).use { it.writeObject(model) }
        out.toByteArray()
      }
    val deserialized = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as DeclaredDependenciesImpl

    assertThat(deserialized).isEqualTo(model)
  }

  @Test
  fun `toIdeDeclaredDependencies merges filtered external module dependencies and fallback configurationsToCoordinates`() {
    val declaredDependencies =
      DeclaredDependenciesImpl(
        configurationsToCoordinates =
          mapOf(
            "implementation" to listOf(CoordinatesImpl(null, "unspecified", null)),
            "debugApi" to listOf(CoordinatesImpl(null, "local-jar", null)),
          ),
        allOutgoingProjectsDependenciesToConfigurations = emptyMap(),
        allOutgoingExternalModuleDependencies =
          mapOf(
            "api" to listOf(CoordinatesImpl("com.example", "api-lib", "1.0")),
            "implementation" to listOf(CoordinatesImpl("com.example", "impl-lib", "2.0")),
            "debugImplementation" to listOf(CoordinatesImpl("com.example", "debug-impl-lib", "3.0")),
            "annotationProcessor" to listOf(CoordinatesImpl("com.example", "ap-lib", "4.0")),
            "releaseImplementation" to listOf(CoordinatesImpl("com.included", "release-only", "1.0")),
            "freeDebugImplementation" to listOf(CoordinatesImpl("com.included", "flavor-only", "1.0")),
            "kapt" to listOf(CoordinatesImpl("com.example", "kapt-lib", "1.0")),
            "testImplementation" to listOf(CoordinatesImpl("junit", "junit", "4.13.2")),
          ),
      )

    val ideModel = declaredDependencies.toIdeDeclaredDependencies()

    assertThat(ideModel.configurationsToCoordinates)
      .containsExactly(
        "api",
        listOf(IdeCoordinatesImpl("com.example", "api-lib", "1.0")),
        "implementation",
        listOf(IdeCoordinatesImpl("com.example", "impl-lib", "2.0"), IdeCoordinatesImpl(null, "unspecified", null)),
        "debugApi",
        listOf(IdeCoordinatesImpl(null, "local-jar", null)),
        "debugImplementation",
        listOf(IdeCoordinatesImpl("com.example", "debug-impl-lib", "3.0")),
        "annotationProcessor",
        listOf(IdeCoordinatesImpl("com.example", "ap-lib", "4.0")),
      )
  }

  @Test
  fun `toIdeDeclaredDependencies returns empty model for null input`() {
    val nullModel: DeclaredDependencies? = null
    assertThat(nullModel.toIdeDeclaredDependencies()).isEqualTo(IdeDeclaredDependenciesImpl(emptyMap()))
  }

  private fun mockProject(
    path: String,
    configurations: List<Configuration>,
    gradleVersion: String = "8.13",
  ): Project {
    val gradle = Mockito.mock(Gradle::class.java)
    whenever(gradle.gradleVersion).thenReturn(gradleVersion)
    val project = Mockito.mock(Project::class.java)
    whenever(project.path).thenReturn(path)
    whenever(project.gradle).thenReturn(gradle)
    whenever(project.configurations).thenReturn(setProxy(ConfigurationContainer::class.java, configurations))
    return project
  }

  private fun mockConfiguration(name: String, dependencies: List<Dependency>): Configuration {
    val configuration = Mockito.mock(Configuration::class.java)
    whenever(configuration.name).thenReturn(name)
    whenever(configuration.dependencies).thenReturn(setProxy(DependencySet::class.java, dependencies))
    return configuration
  }

  private fun mockExternalDependency(group: String?, name: String, version: String?): ExternalModuleDependency {
    val dependency = Mockito.mock(ExternalModuleDependency::class.java)
    whenever(dependency.group).thenReturn(group)
    whenever(dependency.name).thenReturn(name)
    whenever(dependency.version).thenReturn(version)
    return dependency
  }

  private fun mockProjectDependency(path: String): ProjectDependency {
    val dependency = Mockito.mock(ProjectDependency::class.java)
    whenever(dependency.path).thenReturn(path)
    return dependency
  }

  private interface LegacyProjectDependency : ProjectDependency {
    fun getDependencyProject(): Project
  }

  private fun mockLegacyProjectDependency(path: String): ProjectDependency {
    val targetProject = Mockito.mock(Project::class.java)
    whenever(targetProject.path).thenReturn(path)
    val dependency = Mockito.mock(LegacyProjectDependency::class.java)
    whenever(dependency.getDependencyProject()).thenReturn(targetProject)
    return dependency
  }

  private fun <T : Set<E>, E> setProxy(iface: Class<T>, items: List<E>): T {
    val backingSet = LinkedHashSet(items)
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
      method.invoke(backingSet, *(args ?: emptyArray()))
    } as T
  }
}
