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
package com.google.idea.blaze.android.run

import com.android.tools.idea.projectsystem.ApplicationProjectContextProvider.RunningApplicationIdentity
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.android.projectsystem.BazelProjectSystem
import com.google.idea.blaze.android.resources.BlazeLightResourceClassService
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.run.DeployedApplicationTargetStore
import com.google.idea.blaze.base.run.DeployedApplicationTargetStoreImpl
import com.google.idea.blaze.base.run.DeployedApplicationTargets
import com.google.idea.blaze.base.run.RuntimeArtifactCache
import com.google.idea.blaze.base.run.RuntimeArtifactKind
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.common.artifact.OutputArtifact
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [BazelApplicationProjectContextProvider]. */
@RunWith(JUnit4::class)
class BazelApplicationProjectContextProviderTest : BlazeTestCase() {

  @get:Rule val tempFolder = TemporaryFolder()

  private val mockArtifactCache = MockRuntimeArtifactCache()
  private val mockTargetStore = MockDeployedApplicationTargetStore()
  private var activeTargetStore: DeployedApplicationTargetStore = mockTargetStore

  override fun initTest(applicationServices: Container, projectServices: Container) {
    super.initTest(applicationServices, projectServices)
    activeTargetStore = mockTargetStore
    projectServices.register(RuntimeArtifactCache::class.java, mockArtifactCache)
    projectServices.register(
      DeployedApplicationTargetStore::class.java,
      object : DeployedApplicationTargetStore {
        override fun trackTargetForApplication(applicationId: String, target: Label, deployedWith: Set<Label>) =
          activeTargetStore.trackTargetForApplication(applicationId, target, deployedWith)

        override fun getTargetForApplication(applicationId: String): Label? = activeTargetStore.getTargetForApplication(applicationId)

        override fun getTargetsForApplication(applicationId: String): DeployedApplicationTargets? =
          activeTargetStore.getTargetsForApplication(applicationId)

        override fun getAllApplicationIds(): Set<String> = activeTargetStore.getAllApplicationIds()
      },
    )
    val constructor =
      BlazeLightResourceClassService::class.java.getDeclaredConstructor(Project::class.java).apply {
        isAccessible = true
      }
    projectServices.register(BlazeLightResourceClassService::class.java, constructor.newInstance(project))
  }

  @Test
  fun testComputeApplicationProjectContext_withCachedSymbols() {
    val target = Label.of("//java/com/example:app")
    mockTargetStore.trackTargetForApplication("com.example.app", target, deployedWith = emptySet())
    val symbolFile1 = Path.of("/path/to/symbols1/libnative1.so")
    val symbolFile2 = Path.of("/path/to/symbols2/libnative2.so")
    mockArtifactCache.cachedArtifactsMap[target] = ImmutableList.of(symbolFile1, symbolFile2)

    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = "com.example.app", applicationId = "com.example.app")

    val context = provider.computeApplicationProjectContext(projectSystem, identity)
    assertThat(context).isNotNull()
    assertThat(context).isInstanceOf(BazelApplicationProjectContext::class.java)

    val bazelContext = context as BazelApplicationProjectContext
    assertThat(bazelContext.applicationId).isEqualTo("com.example.app")
    assertThat(bazelContext.project).isSameInstanceAs(project)
    assertThat(bazelContext.liveEditDataExtractor).isNull()
    assertThat(bazelContext.symbolDirs).containsExactly(File("/path/to/symbols1"), File("/path/to/symbols2"))
  }

  @Test
  fun testComputeApplicationProjectContext_withoutCachedSymbols_returnsEmptySymbolDirs() {
    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = "com.example.uncached", applicationId = "com.example.uncached")

    val context = provider.computeApplicationProjectContext(projectSystem, identity)
    assertThat(context).isNotNull()
    assertThat(context).isInstanceOf(BazelApplicationProjectContext::class.java)

    val bazelContext = context as BazelApplicationProjectContext
    assertThat(bazelContext.applicationId).isEqualTo("com.example.uncached")
    assertThat(bazelContext.symbolDirs).isEmpty()
  }

  @Test
  fun testComputeApplicationProjectContext_evaluatesSymbolDirsLazily() {
    val target = Label.of("//java/com/example:app")
    mockTargetStore.trackTargetForApplication("com.example.app", target, deployedWith = emptySet())
    mockArtifactCache.cachedArtifactsMap[target] = ImmutableList.of(Path.of("/path/to/symbols/libnative.so"))
    mockTargetStore.resetQueryCounts()
    mockArtifactCache.resetQueryCounts()

    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = "com.example.app", applicationId = "com.example.app")

    val context = provider.computeApplicationProjectContext(projectSystem, identity)
    assertThat(context).isNotNull()
    // Verify target store and cache have NOT been queried during computeApplicationProjectContext
    assertThat(mockTargetStore.queryCount).isEqualTo(0)
    assertThat(mockArtifactCache.queryCount).isEqualTo(0)

    val bazelContext = context as BazelApplicationProjectContext
    // Now access symbolDirs
    val symbolDirs = bazelContext.symbolDirs
    assertThat(symbolDirs).containsExactly(File("/path/to/symbols"))
    // Queried on first access
    assertThat(mockTargetStore.queryCount).isEqualTo(1)
    assertThat(mockArtifactCache.queryCount).isEqualTo(1)

    // Accessing again should use cached lazy value without re-querying
    val symbolDirsAgain = bazelContext.symbolDirs
    assertThat(symbolDirsAgain).containsExactly(File("/path/to/symbols"))
    assertThat(mockTargetStore.queryCount).isEqualTo(1)
    assertThat(mockArtifactCache.queryCount).isEqualTo(1)
  }

  @Test
  fun testComputeApplicationProjectContext_withNullApplicationId_returnsNull() {
    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = null, applicationId = null)

    val context = provider.computeApplicationProjectContext(projectSystem, identity)
    assertThat(context).isNull()
  }

  @Test
  fun `computeApplicationProjectContext includes symbol directories from co-deployed companion targets`() {
    val appTarget = Label.of("//java/com/example:app")
    val testTarget = Label.of("//javatests/com/example:app_test")
    mockTargetStore.trackTargetForApplication("com.example.app.test", testTarget, setOf(appTarget))
    mockTargetStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget))
    mockArtifactCache.cachedArtifactsMap[appTarget] =
      ImmutableList.of(
        Path.of("/path/to/app_symbols/libapp.so"),
        Path.of("/path/to/shared_symbols/libcommon.so"),
      )
    mockArtifactCache.cachedArtifactsMap[testTarget] =
      ImmutableList.of(
        Path.of("/path/to/shared_symbols/libtest_common.so"),
        Path.of("/path/to/test_symbols/libtest_jni.so"),
      )

    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = "com.example.app", applicationId = "com.example.app")

    val bazelContext = provider.computeApplicationProjectContext(projectSystem, identity) as BazelApplicationProjectContext
    assertThat(bazelContext.symbolDirs)
      .containsExactly(
        File("/path/to/app_symbols"),
        File("/path/to/shared_symbols"),
        File("/path/to/test_symbols"),
      )
      .inOrder()
  }

  @Test
  fun `computeApplicationProjectContext returns companion symbol directories when primary target has no native symbols`() {
    val appTarget = Label.of("//java/com/example:pure_java_app")
    val testTarget = Label.of("//javatests/com/example:native_test")
    mockTargetStore.trackTargetForApplication("com.example.pure_java_app", appTarget, setOf(testTarget))
    mockTargetStore.trackTargetForApplication("com.example.native_test", testTarget, setOf(appTarget))
    mockArtifactCache.cachedArtifactsMap[testTarget] = ImmutableList.of(Path.of("/path/to/test_symbols/libtest_jni.so"))

    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = "com.example.pure_java_app", applicationId = "com.example.pure_java_app")

    val bazelContext = provider.computeApplicationProjectContext(projectSystem, identity) as BazelApplicationProjectContext
    assertThat(bazelContext.symbolDirs).containsExactly(File("/path/to/test_symbols"))
  }

  @Test
  fun `computeApplicationProjectContext resolves co-deployed symbol directories across DeployedApplicationTargetStoreImpl reload`() {
    val storeDir = tempFolder.root.toPath().resolve(".application-ids")
    val appTarget = Label.of("//java/com/example:app")
    val testTarget = Label.of("//javatests/com/example:app_test")

    val initialStore = DeployedApplicationTargetStoreImpl.createForTest(storeDir)
    initialStore.trackTargetForApplication("com.example.app.test", testTarget, setOf(appTarget))
    initialStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget))

    val reloadedStore = DeployedApplicationTargetStoreImpl.createForTest(storeDir)
    activeTargetStore = reloadedStore
    mockArtifactCache.cachedArtifactsMap[appTarget] = ImmutableList.of(Path.of("/path/to/app_symbols/libapp.so"))
    mockArtifactCache.cachedArtifactsMap[testTarget] = ImmutableList.of(Path.of("/path/to/test_symbols/libtest.so"))

    val provider = BazelApplicationProjectContextProvider()
    val projectSystem = BazelProjectSystem(project)
    val identity = RunningApplicationIdentity(processName = "com.example.app", applicationId = "com.example.app")

    val bazelContext = provider.computeApplicationProjectContext(projectSystem, identity) as BazelApplicationProjectContext
    assertThat(bazelContext.symbolDirs).containsExactly(File("/path/to/app_symbols"), File("/path/to/test_symbols")).inOrder()
  }

  private class MockDeployedApplicationTargetStore : DeployedApplicationTargetStore {
    val trackedTargets = mutableMapOf<String, Label>()
    private val coDeployedTargets = mutableMapOf<Label, MutableSet<Label>>()
    var queryCount = 0

    fun resetQueryCounts() {
      queryCount = 0
    }

    override fun trackTargetForApplication(applicationId: String, target: Label, deployedWith: Set<Label>) {
      trackedTargets[applicationId] = target
      val companions = deployedWith.filterTo(linkedSetOf()) { it != target }
      val previous = coDeployedTargets.remove(target).orEmpty()
      for (removed in previous - companions) {
        coDeployedTargets[removed]?.remove(target)
      }
      if (companions.isNotEmpty()) {
        coDeployedTargets[target] = companions
        for (companion in companions) {
          coDeployedTargets.getOrPut(companion) { linkedSetOf() }.add(target)
        }
      }
    }

    override fun getTargetForApplication(applicationId: String): Label? {
      queryCount++
      return trackedTargets[applicationId]
    }

    override fun getTargetsForApplication(applicationId: String): DeployedApplicationTargets? {
      queryCount++
      val primary = trackedTargets[applicationId] ?: return null
      return DeployedApplicationTargets(primary, coDeployedTargets[primary]?.toSet().orEmpty())
    }

    override fun getAllApplicationIds(): Set<String> = trackedTargets.keys
  }

  private class MockRuntimeArtifactCache : RuntimeArtifactCache {
    val cachedArtifactsMap = mutableMapOf<Label, ImmutableList<Path>>()
    var queryCount = 0

    fun resetQueryCounts() {
      queryCount = 0
    }

    override fun fetchArtifacts(
      target: Label,
      artifacts: List<OutputArtifact>,
      context: BlazeContext,
      artifactKind: RuntimeArtifactKind,
    ): ImmutableList<Path> {
      return ImmutableList.of()
    }

    override fun getCachedArtifacts(target: Label, artifactKind: RuntimeArtifactKind): ImmutableList<Path> {
      queryCount++
      return cachedArtifactsMap[target] ?: ImmutableList.of()
    }
  }
}
