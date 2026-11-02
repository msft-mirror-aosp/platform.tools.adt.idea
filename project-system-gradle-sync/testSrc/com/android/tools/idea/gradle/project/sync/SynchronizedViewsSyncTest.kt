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

import com.android.builder.model.v2.models.VariantDependenciesAdjacencyList
import com.android.ide.common.repository.AgpVersion
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import org.gradle.tooling.BuildController
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class SynchronizedViewsSyncTest {
  @Test
  fun `sync wraps the views of the fetched dependency models`() {
    val graph = ProtocolToModelAdapter().newGraph()
    val dependencies = graph.adapt(VariantDependenciesAdjacencyList::class.java, VariantDependenciesSource())!!
    val controller =
      Proxy.newProxyInstance(BuildController::class.java.classLoader, arrayOf(BuildController::class.java)) { _, method, _ ->
        if (method.name == "findModel") dependencies else throw UnsupportedOperationException(method.name)
      } as BuildController
    val modelVersions = ModelVersions(AgpVersion.parse("9.0.0"), ModelVersion(24, 0), minimumModelConsumer = null)
    val project = Mockito.mock(BasicGradleProject::class.java)

    val fetched = controller.findVariantDependenciesV2Model(project, modelVersions, "debug") {}!!

    val compileEdge = (fetched.mainArtifact.compileDependencies as DependencyGraphCompat.AdjacencyList).edges.single()
    assertTrue(compileEdge is SynchronizedView)
    assertEquals("lib", compileEdge.from)
    assertEquals("lib", compileEdge.to)

    val runtimeEdge = (fetched.mainArtifact.runtimeDependencies as DependencyGraphCompat.AdjacencyList).edges.single()
    assertTrue(runtimeEdge is SynchronizedView)
    assertEquals("lib", runtimeEdge.from)
    assertEquals("lib", runtimeEdge.to)

    assertThat(fetched.mainArtifact.unresolvedDependencies).isEmpty()
    assertThat(fetched.deviceTestArtifacts).isEmpty()
    assertThat(fetched.hostTestArtifacts).isEmpty()
    assertThat(fetched.testSuiteArtifacts).isEmpty()
    assertNull(fetched.testFixturesArtifact)

    val library = fetched.libraries.values.single()
    assertTrue(library is SynchronizedView)
    assertEquals("lib", library.key)
  }

  /** Object behind a `VariantDependenciesAdjacencyList` view with one library. */
  class VariantDependenciesSource {
    val mainArtifact = ArtifactDependenciesSource()
    val deviceTestArtifacts = emptyMap<String, Any>()
    val hostTestArtifacts = emptyMap<String, Any>()
    val testSuiteArtifacts = emptyMap<String, Any>()
    val testFixturesArtifact: Any? = null
    val libraries = mapOf("lib" to LibrarySource())
  }

  class ArtifactDependenciesSource {
    val compileDependencies = listOf(EdgeSource())
    val runtimeDependencies = listOf(EdgeSource())
    val unresolvedDependencies = emptyList<Any>()
  }

  class EdgeSource {
    val from = "lib"
    val to = "lib"
  }

  class LibrarySource {
    val key = "lib"
  }
}
