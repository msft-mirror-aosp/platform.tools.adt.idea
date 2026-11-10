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
package com.google.idea.blaze.base.run

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.common.Label
import com.intellij.openapi.util.SimpleModificationTracker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Integration/unit tests for [DeployedApplicationTargetStoreImpl]. */
@RunWith(JUnit4::class)
class DeployedApplicationTargetStoreImplTest {

  @get:Rule val tmpDir = TemporaryFolder()
  private lateinit var applicationIdsDirectory: Path
  private lateinit var targetStore: DeployedApplicationTargetStoreImpl

  @Before
  fun setUp() {
    applicationIdsDirectory = tmpDir.root.toPath().resolve(".application-ids")
    targetStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
  }

  @Test
  fun trackTargetForApplication_recordsMappingAndReadsBack() {
    val applicationId = "com.example.app"
    val target = Label.of("//java/com/example:app")

    targetStore.trackTargetForApplication(applicationId, target, emptySet())

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    assertThat(mappingsFile.isRegularFile()).isTrue()
    assertThat(mappingsFile.readText().trim()).isEqualTo("$target $applicationId")

    assertThat(targetStore.getTargetForApplication(applicationId)).isEqualTo(target)
    assertThat(targetStore.getTargetsForApplication(applicationId)).isEqualTo(DeployedApplicationTargets(target, emptySet()))
  }

  @Test
  fun trackTargetForApplication_updatesExistingMapping() {
    val applicationId = "com.example.app"
    val target1 = Label.of("//java/com/example:app1")
    val target2 = Label.of("//java/com/example:app2")

    targetStore.trackTargetForApplication(applicationId, target1, emptySet())
    assertThat(targetStore.getTargetForApplication(applicationId)).isEqualTo(target1)

    targetStore.trackTargetForApplication(applicationId, target2, emptySet())
    assertThat(targetStore.getTargetForApplication(applicationId)).isEqualTo(target2)

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    assertThat(mappingsFile.readText().trim()).isEqualTo("$target2 $applicationId")
  }

  @Test
  fun trackTargetForApplication_dropsOldApplicationIdWhenTargetReused() {
    val target = Label.of("//java/com/example:app")
    val oldAppId = "com.example.oldapp"
    val newAppId = "com.example.newapp"

    targetStore.trackTargetForApplication(oldAppId, target, emptySet())
    assertThat(targetStore.getTargetForApplication(oldAppId)).isEqualTo(target)
    assertThat(targetStore.getAllApplicationIds()).containsExactly(oldAppId)

    targetStore.trackTargetForApplication(newAppId, target, emptySet())
    assertThat(targetStore.getTargetForApplication(newAppId)).isEqualTo(target)
    assertThat(targetStore.getTargetForApplication(oldAppId)).isNull()
    assertThat(targetStore.getAllApplicationIds()).containsExactly(newAppId)

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    assertThat(mappingsFile.readText().trim()).isEqualTo("$target $newAppId")
  }

  @Test
  fun trackTargetForApplication_writesSortedEntriesByTarget() {
    targetStore.trackTargetForApplication("com.b.app", Label.of("//java/b:app"), emptySet())
    targetStore.trackTargetForApplication("com.a.app", Label.of("//java/a:app"), emptySet())
    targetStore.trackTargetForApplication("com.c.app", Label.of("//java/c:app"), emptySet())

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    val lines = mappingsFile.readText().lines().filter { it.isNotBlank() }
    assertThat(lines)
      .containsExactly(
        "//java/a:app com.a.app",
        "//java/b:app com.b.app",
        "//java/c:app com.c.app",
      )
      .inOrder()
  }

  @Test
  fun getTargetForApplication_returnsNullWhenUncached() {
    assertThat(targetStore.getTargetForApplication("non.existent.app")).isNull()
  }

  @Test
  fun getAllApplicationIds_returnsEmptyWhenDirectoryDoesNotExist() {
    assertThat(targetStore.getAllApplicationIds()).isEmpty()
  }

  @Test
  fun getAllApplicationIds_returnsAllTrackedApplicationIds() {
    targetStore.trackTargetForApplication("com.example.app1", Label.of("//java/com/example:app1"), emptySet())
    targetStore.trackTargetForApplication("com.example.app2", Label.of("//java/com/example:app2"), emptySet())
    targetStore.trackTargetForApplication("com.example.app3", Label.of("//java/com/example:app3"), emptySet())

    assertThat(targetStore.getAllApplicationIds()).containsExactly("com.example.app1", "com.example.app2", "com.example.app3")
  }

  @Test
  fun cleanupOnAccess_removesStaleTargetsWhenModificationTrackerChanges() {
    val modTracker = SimpleModificationTracker()
    var knownTargets: Set<Label>? = null
    val store =
      DeployedApplicationTargetStoreImpl(
        applicationIdsDirectory,
        knownTargetsSupplier = { knownTargets },
        modificationTracker = modTracker,
      )

    val target1 = Label.of("//java/com/example:app1")
    val target2 = Label.of("//java/com/example:app2")
    val target3 = Label.of("//java/com/example:app3")

    store.trackTargetForApplication("com.example.app1", target1, emptySet())
    store.trackTargetForApplication("com.example.app2", target2, emptySet())
    store.trackTargetForApplication("com.example.app3", target3, emptySet())

    // Modification count increments after sync
    knownTargets = setOf(target1, target3)
    modTracker.incModificationCount()

    // Access triggers cleanup
    assertThat(store.getAllApplicationIds()).containsExactly("com.example.app1", "com.example.app3")
    assertThat(store.getTargetForApplication("com.example.app2")).isNull()

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    val lines = mappingsFile.readText().lines().filter { it.isNotBlank() }
    assertThat(lines)
      .containsExactly(
        "//java/com/example:app1 com.example.app1",
        "//java/com/example:app3 com.example.app3",
      )
      .inOrder()
  }

  @Test
  fun existingMappingsFile_isLoadedIntoCacheOnFirstAccess() {
    Files.createDirectories(applicationIdsDirectory)
    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    mappingsFile.writeText(
      """
      # Comment line

      //java/com/example:app1 com.example.app1
      malformed_line_without_app_id
      //java/com/example:app2 com.example.app2
      """
        .trimIndent()
    )

    val newStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
    assertThat(newStore.getAllApplicationIds()).containsExactly("com.example.app1", "com.example.app2")
    assertThat(newStore.getTargetForApplication("com.example.app1")).isEqualTo(Label.of("//java/com/example:app1"))
    assertThat(newStore.getTargetForApplication("com.example.app2")).isEqualTo(Label.of("//java/com/example:app2"))
  }

  @Test
  fun getTargetForApplication_returnsNullWhenFileEmpty() {
    Files.createDirectories(applicationIdsDirectory)
    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    mappingsFile.writeText("   \n\n  # only comments\n")

    val newStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
    assertThat(newStore.getAllApplicationIds()).isEmpty()
    assertThat(newStore.getTargetForApplication("any.app")).isNull()
  }

  @Test
  fun `trackTargetForApplication records symmetric co-deployed targets and persists across store reload`() {
    val appTarget = Label.of("//java/com/example:app")
    val testTarget = Label.of("//javatests/com/example:app_test")

    targetStore.trackTargetForApplication("com.example.app.test", testTarget, setOf(appTarget))
    targetStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget))

    assertThat(targetStore.getTargetsForApplication("com.example.app")).isEqualTo(DeployedApplicationTargets(appTarget, setOf(testTarget)))
    assertThat(targetStore.getTargetsForApplication("com.example.app.test"))
      .isEqualTo(DeployedApplicationTargets(testTarget, setOf(appTarget)))

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    val lines = mappingsFile.readText().lines().filter { it.isNotBlank() }
    assertThat(lines)
      .containsExactly(
        "//java/com/example:app com.example.app //javatests/com/example:app_test",
        "//javatests/com/example:app_test com.example.app.test //java/com/example:app",
      )
      .inOrder()

    val reloadedStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
    assertThat(reloadedStore.getTargetForApplication("com.example.app")).isEqualTo(appTarget)
    assertThat(reloadedStore.getTargetForApplication("com.example.app.test")).isEqualTo(testTarget)
    assertThat(reloadedStore.getTargetsForApplication("com.example.app"))
      .isEqualTo(DeployedApplicationTargets(appTarget, setOf(testTarget)))
    assertThat(reloadedStore.getTargetsForApplication("com.example.app.test"))
      .isEqualTo(DeployedApplicationTargets(testTarget, setOf(appTarget)))
  }

  @Test
  fun `redeploying target alone clears stale co-deployed pairing on both sides and on disk`() {
    val appTarget = Label.of("//java/com/example:app")
    val testTarget = Label.of("//javatests/com/example:app_test")

    targetStore.trackTargetForApplication("com.example.app.test", testTarget, setOf(appTarget))
    targetStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget))
    assertThat(targetStore.getTargetsForApplication("com.example.app")?.deployedWith).containsExactly(testTarget)

    targetStore.trackTargetForApplication("com.example.app", appTarget, emptySet())
    assertThat(targetStore.getTargetsForApplication("com.example.app")?.deployedWith).isEmpty()
    assertThat(targetStore.getTargetsForApplication("com.example.app.test")?.deployedWith).isEmpty()

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    val lines = mappingsFile.readText().lines().filter { it.isNotBlank() }
    assertThat(lines)
      .containsExactly(
        "//java/com/example:app com.example.app",
        "//javatests/com/example:app_test com.example.app.test",
      )
      .inOrder()

    val reloadedStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
    assertThat(reloadedStore.getTargetsForApplication("com.example.app")?.deployedWith).isEmpty()
    assertThat(reloadedStore.getTargetsForApplication("com.example.app.test")?.deployedWith).isEmpty()
  }

  @Test
  fun `redeploying target with a new companion unlinks previous companion`() {
    val appTarget = Label.of("//java/com/example:app")
    val testTarget1 = Label.of("//javatests/com/example:app_test1")
    val testTarget2 = Label.of("//javatests/com/example:app_test2")

    targetStore.trackTargetForApplication("com.example.app.test1", testTarget1, setOf(appTarget))
    targetStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget1))

    targetStore.trackTargetForApplication("com.example.app.test2", testTarget2, setOf(appTarget))
    targetStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget2))

    assertThat(targetStore.getTargetsForApplication("com.example.app")?.deployedWith).containsExactly(testTarget2)
    assertThat(targetStore.getTargetsForApplication("com.example.app.test2")?.deployedWith).containsExactly(appTarget)
    assertThat(targetStore.getTargetsForApplication("com.example.app.test1")?.deployedWith).isEmpty()

    val reloadedStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
    assertThat(reloadedStore.getTargetsForApplication("com.example.app")?.deployedWith).containsExactly(testTarget2)
    assertThat(reloadedStore.getTargetsForApplication("com.example.app.test2")?.deployedWith).containsExactly(appTarget)
    assertThat(reloadedStore.getTargetsForApplication("com.example.app.test1")?.deployedWith).isEmpty()
  }

  @Test
  fun `self-instrumenting target ignores self-pairing`() {
    val selfTarget = Label.of("//javatests/com/example:self_test")

    targetStore.trackTargetForApplication("com.example.self", selfTarget, setOf(selfTarget))

    assertThat(targetStore.getTargetsForApplication("com.example.self")).isEqualTo(DeployedApplicationTargets(selfTarget, emptySet()))
    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    assertThat(mappingsFile.readText().trim()).isEqualTo("$selfTarget com.example.self")
  }

  @Test
  fun `evicting target via applicationId reassignment clears its co-deployed links`() {
    val oldAppTarget = Label.of("//java/com/example:app_v1")
    val newAppTarget = Label.of("//java/com/example:app_v2")
    val testTarget = Label.of("//javatests/com/example:app_test")

    targetStore.trackTargetForApplication("com.example.app.test", testTarget, setOf(oldAppTarget))
    targetStore.trackTargetForApplication("com.example.app", oldAppTarget, setOf(testTarget))
    targetStore.trackTargetForApplication("com.example.app", newAppTarget, emptySet())

    assertThat(targetStore.getTargetForApplication("com.example.app")).isEqualTo(newAppTarget)
    assertThat(targetStore.getTargetsForApplication("com.example.app")?.deployedWith).isEmpty()
    assertThat(targetStore.getTargetsForApplication("com.example.app.test")?.deployedWith).isEmpty()
  }

  @Test
  fun `cleanupOnAccess prunes stale companion targets from co-deployed sets and disk`() {
    val modTracker = SimpleModificationTracker()
    var knownTargets: Set<Label>? = null
    val store =
      DeployedApplicationTargetStoreImpl(
        applicationIdsDirectory,
        knownTargetsSupplier = { knownTargets },
        modificationTracker = modTracker,
      )

    val appTarget = Label.of("//java/com/example:app")
    val staleTestTarget = Label.of("//javatests/com/example:deleted_test")

    store.trackTargetForApplication("com.example.app.test", staleTestTarget, setOf(appTarget))
    store.trackTargetForApplication("com.example.app", appTarget, setOf(staleTestTarget))
    assertThat(store.getTargetsForApplication("com.example.app")?.deployedWith).containsExactly(staleTestTarget)

    knownTargets = setOf(appTarget)
    modTracker.incModificationCount()

    assertThat(store.getTargetsForApplication("com.example.app")?.deployedWith).isEmpty()
    assertThat(store.getTargetsForApplication("com.example.app.test")).isNull()

    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    assertThat(mappingsFile.readText().trim()).isEqualTo("$appTarget com.example.app")
  }

  @Test
  fun `legacy two-column mappings file loads backward-compatibly alongside paired entries`() {
    Files.createDirectories(applicationIdsDirectory)
    val mappingsFile = applicationIdsDirectory.resolve("application_ids.txt")
    mappingsFile.writeText(
      """
      # Legacy two-column entry + new paired entry
      //java/com/example:standalone com.example.standalone
      //java/com/example:app com.example.app //javatests/com/example:app_test
      //javatests/com/example:app_test com.example.app.test //java/com/example:app
      """
        .trimIndent()
    )

    val newStore = DeployedApplicationTargetStoreImpl.createForTest(applicationIdsDirectory)
    val standaloneTarget = Label.of("//java/com/example:standalone")
    val appTarget = Label.of("//java/com/example:app")
    val testTarget = Label.of("//javatests/com/example:app_test")

    assertThat(newStore.getTargetsForApplication("com.example.standalone"))
      .isEqualTo(DeployedApplicationTargets(standaloneTarget, emptySet()))
    assertThat(newStore.getTargetsForApplication("com.example.app")).isEqualTo(DeployedApplicationTargets(appTarget, setOf(testTarget)))
    assertThat(newStore.getTargetsForApplication("com.example.app.test"))
      .isEqualTo(DeployedApplicationTargets(testTarget, setOf(appTarget)))
  }

  @Test
  fun `getTargetsForApplication returns primary and companions in single lookup`() {
    assertThat(targetStore.getTargetsForApplication("non.existent.app")).isNull()

    val appTarget = Label.of("//java/com/example:app")
    val testTarget = Label.of("//javatests/com/example:app_test")
    targetStore.trackTargetForApplication("com.example.app.test", testTarget, setOf(appTarget))
    targetStore.trackTargetForApplication("com.example.app", appTarget, setOf(testTarget))

    val appTargets = targetStore.getTargetsForApplication("com.example.app")
    assertThat(appTargets).isEqualTo(DeployedApplicationTargets(appTarget, setOf(testTarget)))
    assertThat(appTargets?.allTargets).containsExactly(appTarget, testTarget).inOrder()
    val testTargets = targetStore.getTargetsForApplication("com.example.app.test")
    assertThat(testTargets).isEqualTo(DeployedApplicationTargets(testTarget, setOf(appTarget)))
    assertThat(testTargets?.allTargets).containsExactly(testTarget, appTarget).inOrder()
  }
}
