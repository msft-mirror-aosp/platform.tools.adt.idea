/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.android.tools.rendering.classloading.NopModuleClassLoadedDiagnostics
import com.android.tools.rendering.classloading.toClassTransform
import com.android.tools.rendering.classloading.useWithClassLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StudioModuleClassLoaderManagerTest {
  @get:Rule val project = AndroidProjectRule.inMemory()

  @After
  fun testDown() {
    val moduleClassLoader = StudioModuleClassLoaderManager.get() as StudioModuleClassLoaderManager
    StudioModuleClassLoaderManager.setCaptureClassLoadingDiagnostics(false)
    moduleClassLoader.assertNoClassLoadersHeld()
  }

  @Test
  fun `shared class loader gets invalidated for different transformations`() {
    var moduleClassLoaderReference: ModuleClassLoaderManager.Reference<*>? = null
    var moduleClassLoader: ModuleClassLoader? = null
    run {
      val projectTransformations = toClassTransform({ TestClassVisitorWithId("project-id1") }, { TestClassVisitorWithId("project-id2") })
      val nonProjectTransformations =
        toClassTransform({ TestClassVisitorWithId("non-project-id1") }, { TestClassVisitorWithId("non-project-id2") })
      moduleClassLoaderReference =
        StudioModuleClassLoaderManager.get()
          .getShared(null, StudioModuleRenderContext.forModule(project.module), projectTransformations, nonProjectTransformations)
      moduleClassLoader = moduleClassLoaderReference!!.classLoader
    }

    run {
      // Same transformations, no new class loader.
      val projectTransformations = toClassTransform({ TestClassVisitorWithId("project-id1") }, { TestClassVisitorWithId("project-id2") })
      val nonProjectTransformations =
        toClassTransform({ TestClassVisitorWithId("non-project-id1") }, { TestClassVisitorWithId("non-project-id2") })
      StudioModuleClassLoaderManager.get()
        .getShared(null, StudioModuleRenderContext.forModule(project.module), projectTransformations, nonProjectTransformations)
        .useWithClassLoader { assertEquals("No changes into the transformations. Same class loader was expected", it, moduleClassLoader) }
    }

    run {
      // Remove project transformation, it should generate a new one.
      val projectTransformations = toClassTransform({ TestClassVisitorWithId("project-id1") })
      val nonProjectTransformations =
        toClassTransform({ TestClassVisitorWithId("non-project-id1") }, { TestClassVisitorWithId("non-project-id2") })
      StudioModuleClassLoaderManager.get()
        .getShared(null, StudioModuleRenderContext.forModule(project.module), projectTransformations, nonProjectTransformations)
        .also { newReference ->
          assertNotEquals(newReference.classLoader, moduleClassLoader)
          StudioModuleClassLoaderManager.get().release(moduleClassLoaderReference!!)
          moduleClassLoaderReference = newReference
        }
    }

    run {
      // Remove non-project transformation, it should generate a new one.
      val projectTransformations = toClassTransform({ TestClassVisitorWithId("project-id1") })
      val nonProjectTransformations = toClassTransform({ TestClassVisitorWithId("non-project-id1") })
      val newClassLoader =
        StudioModuleClassLoaderManager.get()
          .getShared(null, StudioModuleRenderContext.forModule(project.module), projectTransformations, nonProjectTransformations)
          .useWithClassLoader { newClassLoader -> assertNotEquals(newClassLoader, moduleClassLoader) }
      StudioModuleClassLoaderManager.get().release(moduleClassLoaderReference!!)
    }
  }

  @Test
  fun `ensure stats are not activated accidentally`() {
    StudioModuleClassLoaderManager.get().getShared(null, StudioModuleRenderContext.forModule(project.module)).use {
      sharedClassLoaderReference ->
      run {
        assertTrue(sharedClassLoaderReference.classLoader.stats is NopModuleClassLoadedDiagnostics)
        StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader {
          privateClassLoader ->
          assertTrue(privateClassLoader.stats is NopModuleClassLoadedDiagnostics)
        }
      }

      StudioModuleClassLoaderManager.setCaptureClassLoadingDiagnostics(true)
      // Destroying hatchery so that getPrivate returns freshly-created ModuleClassLoader that respects diagnostics settings change
      project.module.getUserData(HATCHERY)?.destroy()

      run {
        // The shared class loader will be reused so, even though we are reactivating the diagnostics,
        // it should be not using them.
        StudioModuleClassLoaderManager.get().getShared(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader {
          sharedClassLoader ->
          assertTrue(sharedClassLoader.stats is NopModuleClassLoadedDiagnostics)
        }
        StudioModuleClassLoaderManager.get().getPrivate(null, StudioModuleRenderContext.forModule(project.module)).useWithClassLoader {
          privateClassLoader ->
          assertFalse(privateClassLoader.stats is NopModuleClassLoadedDiagnostics)
        }
      }
    }
  }

  @Test
  fun testConcurrentReleaseDoesNotDoubleDispose() {
    val manager = StudioModuleClassLoaderManager.get() as StudioModuleClassLoaderManager
    val context = StudioModuleRenderContext.forModule(project.module)
    val executor = Executors.newFixedThreadPool(4)
    try {
      for (i in 0 until 10) {
        val ref1 = manager.getShared(null, context)
        val ref2 = manager.getShared(null, context)
        assertEquals(ref1.classLoader, ref2.classLoader)

        val latch = CountDownLatch(1)
        val f1 = executor.submit {
          latch.await()
          manager.release(ref1)
        }
        val f2 = executor.submit {
          latch.await()
          manager.release(ref2)
        }
        latch.countDown()
        f1.get(5, TimeUnit.SECONDS)
        f2.get(5, TimeUnit.SECONDS)
        assertTrue(ref1.classLoader.isDisposed)
      }
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun testClearCacheDoesNotDisposeHeldClassLoader() {
    val manager = StudioModuleClassLoaderManager.get()
    val context = StudioModuleRenderContext.forModule(project.module)

    val ref1 = manager.getShared(null, context)
    val ref2 = manager.getShared(null, context)
    val initialClassLoader = ref1.classLoader
    assertEquals(initialClassLoader, ref2.classLoader)
    assertFalse(initialClassLoader.isDisposed)

    // Clearing the cache while references are actively held should not dispose the in-use class loader,
    // but should detach it from the cache so subsequent getShared calls get a new instance.
    manager.clearCache(project.module)
    assertFalse(initialClassLoader.isDisposed)

    val ref3 = manager.getShared(null, context)
    val newClassLoader = ref3.classLoader
    assertNotEquals(initialClassLoader, newClassLoader)
    assertFalse(newClassLoader.isDisposed)

    // Releasing only the first reference must not dispose initialClassLoader while ref2 is still held.
    manager.release(ref1)
    assertFalse(initialClassLoader.isDisposed)

    // Releasing the last reference to initialClassLoader must dispose it without affecting newClassLoader.
    manager.release(ref2)
    assertTrue(initialClassLoader.isDisposed)
    assertFalse(newClassLoader.isDisposed)

    // Releasing ref3 disposes newClassLoader and places an unheld preloaded copy in PRELOADER.
    manager.release(ref3)
    assertTrue(newClassLoader.isDisposed)

    val unheldPreloaded = project.module.getUserData(PRELOADER)?.getClassLoader()!!
    assertFalse(unheldPreloaded.isDisposed)

    // Clearing the cache when no references hold the preloaded class loader must dispose it immediately.
    manager.clearCache(project.module)
    assertTrue(unheldPreloaded.isDisposed)
  }
}
