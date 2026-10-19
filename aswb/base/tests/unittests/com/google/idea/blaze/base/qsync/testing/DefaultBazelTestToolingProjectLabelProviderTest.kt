/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.qsync.testing

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.common.TargetPatternCollection
import com.google.idea.blaze.qsync.project.BuildGraphData
import com.google.idea.blaze.qsync.project.BuildGraphDataImpl
import com.google.idea.blaze.qsync.project.ProjectTarget
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DefaultBazelTestToolingProjectLabelProviderTest {
  private val provider = DefaultBazelTestToolingProjectLabelProvider()

  @Test
  fun emptyGraph_hasNoTests() {
    val graph = buildGraph()

    assertThat(provider.hasTests(graph)).isFalse()
    assertThat(provider.getTestToolingLabel(graph)).isNull()
  }

  @Test
  fun targetWithoutJUnitDeps_hasNoTests() {
    val target =
      ProjectTarget.builder()
        .label(Label.of("//app:lib"))
        .kind("java_library")
        .tags(emptyList())
        .apply { depsBuilder().add(Label.of("@maven//:com_google_guava_guava")) }
        .build()
    val graph = buildGraph(target)

    assertThat(provider.hasTests(graph)).isFalse()
    assertThat(provider.getTestToolingLabel(graph)).isNull()
  }

  @Test
  fun targetWithUnderscoreJUnitDep_returnsJUnitLabel() {
    val junitLabel = Label.of("@rules_android_maven//:junit_junit")
    val target =
      ProjectTarget.builder()
        .label(Label.of("//app:SampleTest"))
        .kind("java_test")
        .tags(emptyList())
        .apply { depsBuilder().add(junitLabel) }
        .build()
    val graph = buildGraph(target)

    assertThat(provider.hasTests(graph)).isTrue()
    assertThat(provider.getTestToolingLabel(graph)).isEqualTo(junitLabel)
  }

  @Test
  fun targetWithDotJUnitDep_returnsJUnitLabel() {
    val junitLabel = Label.of("@maven//:junit.junit")
    val target =
      ProjectTarget.builder()
        .label(Label.of("//app:SampleTest"))
        .kind("kt_jvm_test")
        .tags(emptyList())
        .apply { depsBuilder().add(junitLabel) }
        .build()
    val graph = buildGraph(target)

    assertThat(provider.hasTests(graph)).isTrue()
    assertThat(provider.getTestToolingLabel(graph)).isEqualTo(junitLabel)
  }

  @Test
  fun targetWithJUnitRuntimeDep_returnsJUnitLabel() {
    val junitLabel = Label.of("@rules_android_maven//:junit_junit")
    val target =
      ProjectTarget.builder()
        .label(Label.of("//app:SampleTest"))
        .kind("java_test")
        .tags(emptyList())
        .apply { runtimeDepsBuilder().add(junitLabel) }
        .build()
    val graph = buildGraph(target)

    assertThat(provider.hasTests(graph)).isTrue()
    assertThat(provider.getTestToolingLabel(graph)).isEqualTo(junitLabel)
  }

  private fun buildGraph(vararg targets: ProjectTarget): BuildGraphData {
    val builder = BuildGraphDataImpl.builder()
    for (target in targets) {
      builder.addTarget(target.label(), target)
    }
    return builder.build(
      TargetPatternCollection.create(emptyList()),
      emptySet(),
      emptySet(),
      BuildGraphData.ProtoRules.forTests(),
    )
  }
}
