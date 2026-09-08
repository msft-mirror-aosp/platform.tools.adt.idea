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
package org.jetbrains.android.uipreview

import com.android.tools.idea.rendering.StudioModuleRenderContext
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.FirewalledResourcesClassLoader
import com.android.tools.rendering.classloading.toClassTransform
import com.android.tools.rendering.classloading.useWithClassLoader
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModuleClassLoaderHatcheryTest {
  @get:Rule val project = AndroidProjectRule.inMemory()

  @Test
  fun `incubates only when needed`() {
    val hatchery = ModuleClassLoaderHatchery(1, 2, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).use { reference ->
      val donor = reference.classLoader
      val studioModuleClassLoaderCreationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)

      var requests = 0

      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d ->
        requests++
        d.createClassLoader()
      }

      // Was not requested before => not needed
      assertFalse(hatchery.incubateIfNeeded(studioModuleClassLoaderCreationContext, cloner))
      assertEquals(0, requests)
      // Cannot be created, no information
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      // Was requested => will be used
      assertTrue(hatchery.incubateIfNeeded(studioModuleClassLoaderCreationContext, cloner))
      assertEquals(2, requests)

      assertNotNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertEquals(3, requests)
    }
  }

  @Test
  fun `hatchery maintains capacity`() {
    val hatchery = ModuleClassLoaderHatchery(1, 2, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val studioModuleClassLoaderCreationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)

      val projectTransformations = toClassTransform({ TestClassVisitorWithId("project-id1") })
      StudioModuleClassLoaderManager.get()
        .getPrivate(null, StudioModuleRenderContext.forModule(project.module), projectTransformations)
        .useWithClassLoader { donor2 ->
          val donor2Information = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor2)

          val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }
          // Nothing at the beginning, provide donor, check that request is successful
          assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
          assertTrue(hatchery.incubateIfNeeded(studioModuleClassLoaderCreationContext, cloner))
          assertNotNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
          // Request a different one, and provide a different donor, check that request is successful
          assertNull(hatchery.requestClassLoader(null, donor2.projectClassesTransform, donor2.nonProjectClassesTransform))
          assertTrue(hatchery.incubateIfNeeded(donor2Information, cloner))
          assertNotNull(hatchery.requestClassLoader(null, donor2.projectClassesTransform, donor2.nonProjectClassesTransform))
          // Check that due to capacity of 1, the first one is not longer provided
          assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
        }
    }
  }

  @Test
  fun `hatchery correctly identifies different parent class loaders`() {
    val hatchery = ModuleClassLoaderHatchery(1, 2, parentDisposable = project.testRootDisposable)
    val parent1 = FirewalledResourcesClassLoader(null)
    StudioModuleClassLoaderManager.get().getPrivate(parent1, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor
      ->
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }
      // Create a request for a new class loader and incubate it
      assertNull(hatchery.requestClassLoader(parent1, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor), cloner))
      // This request has the same Request so it should return a new classloader
      assertNotNull(hatchery.requestClassLoader(parent1, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      // This request is using a different parent, we should not have anything available and should return null
      val parent2 = FirewalledResourcesClassLoader(null)
      assertNull(hatchery.requestClassLoader(parent2, donor.projectClassesTransform, donor.nonProjectClassesTransform))
    }
  }

  @Test
  fun `hatchery does not mix null and non-null parent requests`() {
    val hatchery = ModuleClassLoaderHatchery(1, 1, parentDisposable = project.testRootDisposable)
    val parent = FirewalledResourcesClassLoader(null)

    StudioModuleClassLoaderManager.get().getPrivate(parent, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor
      ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }

      // 1. Request with null parent
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // 2. Try to incubate with a donor that has a non-null parent.
      // Under buggy Request.equals contract, it would match and return true.
      // Under correct contract, it should return false because they have different parent class loaders.
      assertFalse(hatchery.incubateIfNeeded(creationContext, cloner))
    }
  }

  @Test
  fun `clutch retrieval handles GCed classloader gracefully`() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 1, copies = 2, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }

      // 1. Record the request
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // 2. Incubate a clutch with 2 copies
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 3. Clear/dispose the first copy's classloader to simulate GC
      hatchery.disposeFirstEggForTesting()

      // 4. Request classloader. Under buggy code, sequence terminates and returns null.
      // Under correct code, the second copy is successfully retrieved and returned.
      val retrieved = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(retrieved)
    }
  }

  @Test
  fun `hatchery limits the number of pending requests`() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 1, copies = 1, maxRequestsSize = 5, parentDisposable = project.testRootDisposable)

    // Request many unique configurations
    repeat(50) { i ->
      val projectTransform = toClassTransform({ TestClassVisitorWithId("project-$i") })
      hatchery.requestClassLoader(null, projectTransform, ClassTransform.identity)
    }

    // Verify that the size of pending requests is bounded to the configured limit
    assertEquals(5, hatchery.getRequestsSizeForTesting())
  }

  @Test
  fun `clutch fallback compatibility check during background preloading`() {
    val commands = mutableListOf<Runnable>()
    val delayedExecutor = Executor { command -> commands.add(command) }

    val hatchery =
      ModuleClassLoaderHatchery(capacity = 1, copies = 1, executor = delayedExecutor, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }

      // 1. Record the request
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // 2. Incubate. This will submit the preloading command to our delayedExecutor, so eggs queue remains empty!
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 3. Request classloader again. Since eggs queue is empty, isCompatible should fall back to donor metadata check
      // and find it compatible, but return null since no copies are fully prepared yet.
      // We verify that it does not add a new request by incubating again with the same donor.
      // If it recorded a new request, incubateIfNeeded would return true. If it found it compatible (and didn't record), it returns false.
      assertFalse(hatchery.incubateIfNeeded(creationContext, cloner))
    }
  }

  @Test
  fun testClutchRetrievalReplenishesAsynchronouslyWithoutBlockingCaller() {
    val commands = mutableListOf<Runnable>()
    val delayedExecutor = Executor { command -> commands.add(command) }

    val hatchery =
      ModuleClassLoaderHatchery(capacity = 1, copies = 1, executor = delayedExecutor, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      // Precondition: classesToPreload is empty, ensuring delayedExecutor only receives replenishment tasks.
      assertTrue(creationContext.classesToPreload.isEmpty())
      var clonerInvocations = 0
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d ->
        clonerInvocations++
        d.createClassLoader()
      }

      // Record request and incubate initial copy
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))
      assertEquals(1, commands.size)
      assertEquals(0, clonerInvocations)

      // Run incubation on delayed executor
      commands.removeAt(0).run()
      assertEquals(1, clonerInvocations)

      // Retrieve classloader. It should return the ready egg and dispatch replenishment to executor
      val retrieved = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(retrieved)
      // Cloner was NOT invoked synchronously during retrieve
      assertEquals(1, clonerInvocations)
      assertEquals(1, commands.size)

      // Execute replenishment command
      commands.removeAt(0).run()
      assertEquals(2, clonerInvocations)

      // Verify that the replenished classloader is now ready and retrievable
      val replenished = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(replenished)
    }
  }

  @Test
  fun testClutchWithSingleCopyHandlesGcAndReplenishes() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 1, copies = 1, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }

      // 1. Record request and incubate
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 2. Clear/dispose the egg to simulate GC
      hatchery.disposeFirstEggForTesting()

      // 3. Request classloader. The GC'd egg is the only one, so this drains it and returns null while synchronously triggering
      // replenishment from the same donor.
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // The replacement egg is available to the next caller.
      val secondRetrieval = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(secondRetrieval)

      // 4. Verify no duplicate clutches were created
      assertFalse(hatchery.incubateIfNeeded(creationContext, cloner))
    }
  }

  @Test
  fun testIsCompatibleDoesNotCancelEggPreloading() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 2, copies = 1, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor1
      ->
      val projectTransform2 = toClassTransform({ TestClassVisitorWithId("project-id2") })
      StudioModuleClassLoaderManager.get()
        .getPrivate(null, StudioModuleRenderContext.forModule(project.module), projectTransform2)
        .useWithClassLoader { donor2 ->
          val context1 = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor1)
          val context2 = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor2)

          val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }

          // Incubate donor1's clutch
          assertNull(hatchery.requestClassLoader(null, donor1.projectClassesTransform, donor1.nonProjectClassesTransform))
          assertTrue(hatchery.incubateIfNeeded(context1, cloner))

          // The egg in donor1's clutch should have active preloading
          assertTrue(hatchery.isFirstEggActiveForTesting())

          // Probe isCompatible for a non-matching request (donor2).
          // Prior to the fix, this probed donor1's egg with getClassLoader() and permanently cancelled its preloading!
          assertNull(hatchery.requestClassLoader(null, donor2.projectClassesTransform, donor2.nonProjectClassesTransform))
          assertTrue(hatchery.incubateIfNeeded(context2, cloner))

          // Verify that donor1's egg STILL has active preloading!
          assertTrue(hatchery.isFirstEggActiveForTesting())

          // When donor1's egg is finally retrieved for use, its preloading is cancelled as expected
          val retrieved = hatchery.requestClassLoader(null, donor1.projectClassesTransform, donor1.nonProjectClassesTransform)
          assertNotNull(retrieved)
        }
    }
  }

  @Test
  fun testFailedCloningDoesNotLeaveClutchInPermanentZombieState() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 1, copies = 1, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      var shouldFailCloning = true
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d ->
        if (shouldFailCloning) null else d.createClassLoader()
      }

      // 1. Record request and incubate with failing cloner
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 2. Cloning returned null: clutch has 0 eggs and nothing in flight.
      // Requesting again should record the request and return null.
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // 3. Now cloner will succeed. incubateIfNeeded must be able to recreate the clutch!
      shouldFailCloning = false
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 4. Retrieve succeeds
      val retrieved = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(retrieved)
    }
  }

  @Test
  fun testThrowingClonerDoesNotLeaveClutchInPermanentZombieState() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 1, copies = 1, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      var shouldThrow = true
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d ->
        if (shouldThrow) {
          throw RuntimeException("Cloning failed")
        } else {
          d.createClassLoader()
        }
      }

      // 1. Record request and incubate with throwing cloner
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 2. Cloner threw exception: clutch has 0 eggs and nothing in flight.
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // 3. Cloner now succeeds; incubateIfNeeded should successfully recreate the clutch
      shouldThrow = false
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 4. Retrieve succeeds
      val retrieved = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(retrieved)
    }
  }

  @Test
  fun testReplenishmentFailureAfterRetrieveAllowsReincubation() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 1, copies = 1, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      var shouldFailCloning = false
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d ->
        if (shouldFailCloning) null else d.createClassLoader()
      }

      // 1. Initial incubation succeeds
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // 2. Make future replenishments fail, then retrieve the ready egg
      shouldFailCloning = true
      val retrieved = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(retrieved)

      // 3. Clutch is now empty and replenishment failed (nothing in flight).
      // Requesting again records the request since no compatible clutch is alive.
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))

      // 4. Cloner now succeeds again; incubateIfNeeded must rebuild the clutch.
      shouldFailCloning = false
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))
      assertNotNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
    }
  }

  @Test
  fun testReplenishSchedulingFailureDoesNotThrowDuringRetrieve() {
    var shouldReject = false
    val rejectingExecutor = Executor { command ->
      if (shouldReject) {
        throw RejectedExecutionException("Executor shutdown")
      } else {
        command.run()
      }
    }

    val hatchery =
      ModuleClassLoaderHatchery(capacity = 1, copies = 1, executor = rejectingExecutor, parentDisposable = project.testRootDisposable)

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor ->
      val creationContext = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor)
      val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }

      // Incubate normally
      assertNull(hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform))
      assertTrue(hatchery.incubateIfNeeded(creationContext, cloner))

      // Make subsequent execution scheduling throw RejectedExecutionException
      shouldReject = true

      // Retrieve should succeed and NOT throw even though replenishAsync fails to schedule
      val retrieved = hatchery.requestClassLoader(null, donor.projectClassesTransform, donor.nonProjectClassesTransform)
      assertNotNull(retrieved)
    }
  }

  /**
   * A clutch whose donor cannot be cloned holds no eggs and has no replenishment in flight, so it can never serve a class loader. It has to
   * be purged when a new type is incubated, otherwise the capacity eviction removes the head of the queue instead, which may well be a
   * perfectly healthy clutch for a different preview type.
   */
  @Test
  fun testDeadClutchIsPurgedInsteadOfEvictingHealthyClutch() {
    val hatchery = ModuleClassLoaderHatchery(capacity = 2, copies = 1, parentDisposable = project.testRootDisposable)
    val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { d -> d.createClassLoader() }
    val failingCloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader? = { null }

    StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader { donor1
      ->
      val projectTransform2 = toClassTransform({ TestClassVisitorWithId("project-id2") })
      val projectTransform3 = toClassTransform({ TestClassVisitorWithId("project-id3") })
      StudioModuleClassLoaderManager.get()
        .getPrivate(null, StudioModuleRenderContext.forModule(project.module), projectTransform2)
        .useWithClassLoader { donor2 ->
          StudioModuleClassLoaderManager.get()
            .getPrivate(null, StudioModuleRenderContext.forModule(project.module), projectTransform3)
            .useWithClassLoader { donor3 ->
              val context1 = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor1)
              val context2 = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor2)
              val context3 = StudioModuleClassLoaderCreationContext.fromClassLoaderOrThrow(donor3)

              // Head of the queue: a healthy clutch.
              assertNull(hatchery.requestClassLoader(null, donor1.projectClassesTransform, donor1.nonProjectClassesTransform))
              assertTrue(hatchery.incubateIfNeeded(context1, cloner))

              // Behind it: a clutch whose donor cannot be cloned, so it is born dead.
              assertNull(hatchery.requestClassLoader(null, donor2.projectClassesTransform, donor2.nonProjectClassesTransform))
              assertTrue(hatchery.incubateIfNeeded(context2, failingCloner))

              // Incubating a third type would exceed the capacity. The dead clutch must be the one that goes.
              assertNull(hatchery.requestClassLoader(null, donor3.projectClassesTransform, donor3.nonProjectClassesTransform))
              assertTrue(hatchery.incubateIfNeeded(context3, cloner))

              // The dead clutch was purged rather than left behind, so the hatchery holds the two healthy ones.
              assertEquals(2, hatchery.getStats().size)

              // Both healthy clutches survived.
              assertNotNull(hatchery.requestClassLoader(null, donor1.projectClassesTransform, donor1.nonProjectClassesTransform))
              assertNotNull(hatchery.requestClassLoader(null, donor3.projectClassesTransform, donor3.nonProjectClassesTransform))
            }
        }
    }
  }
}
