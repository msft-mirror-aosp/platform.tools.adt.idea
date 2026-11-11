/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.tools.res.CacheableResourceRepository
import com.android.tools.res.ResourceRepositoryManager
import com.android.tools.res.ids.ResourceIdManagerBase
import com.android.tools.res.ids.ResourceIdManagerModelModule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

private const val PKG_1 = "come.on.fhqwhgads"
private const val PKG_2 = "everybody.to.the.limit"

class ResourceClassRegistryTest {

  private val registry = ResourceClassRegistry(1.days) // Not testing the TimeoutCachedValue
  private val disposable = Disposer.newDisposable()
  private val idManager by lazy { ResourceIdManagerBase(ResourceIdManagerModelModule.appModule()) }
  private val repository: CacheableResourceRepository = mock()
  private val disposableRepository: CacheableResourceRepository = mock(extraInterfaces = arrayOf(Disposable::class))
  private val manager: ResourceRepositoryManager = mock {
    on { appResources } doReturn repository
  }

  @Before
  fun setUp() {
    Disposer.register(disposable, disposableRepository as Disposable)
  }

  @After
  fun tearDown() {
    Disposer.dispose(disposable)
  }

  @Test
  fun addLibrary_alreadyDisposedRepo() {
    Disposer.dispose(disposableRepository as Disposable)
    registry.addLibrary(repository, idManager, PKG_1)
    registry.addLibrary(disposableRepository, idManager, PKG_2)

    assertThat(registry.findClassDefinition("$PKG_1.R", manager)).isNotNull()
    assertThat(registry.findClassDefinition("$PKG_2.R", manager)).isNull()
  }

  @Test
  fun addLibrary_disposableRepo() {
    whenever(manager.appResources).thenReturn(disposableRepository)
    registry.addLibrary(disposableRepository, idManager, PKG_1)

    assertThat(registry.findClassDefinition("$PKG_1.R", manager)).isNotNull()

    Disposer.dispose(disposableRepository as Disposable)

    assertThat(registry.findClassDefinition("$PKG_1.R", manager)).isNull()
  }

  @Test
  fun findClassDefinition_notRClass() {
    assertThat(registry.findClassDefinition("$PKG_1.StrongBad", manager)).isNull()
    verifyNoInteractions(manager)
  }

  @Test
  fun findClassDefinition_notInPackages() {
    assertThat(registry.findClassDefinition("$PKG_1.R", manager)).isNull()
    verifyNoInteractions(manager)
  }

  @Test
  fun findClassDefinition_noRepository() {
    registry.addLibrary(repository, idManager, PKG_1)
    whenever(manager.appResources).thenReturn(disposableRepository)

    assertThat(registry.findClassDefinition("$PKG_1.R", manager)).isNull()
  }

  @Test
  fun findClassDefinition() {
    registry.addLibrary(repository, idManager, PKG_1)

    val bytes = registry.findClassDefinition("$PKG_1.R", manager)
    assertThat(Integer.toHexString(ByteBuffer.wrap(bytes).int)).startsWith("cafebabe")
  }

  @Test
  fun clearCache() {
    registry.addLibrary(repository, idManager, PKG_1)

    registry.clearCache()

    assertThat(registry.findClassDefinition("$PKG_1.R\$values", manager)).isNull()
  }

  @Test
  fun concurrentAccess() {
    // Populate multiple resource types so that concurrent lookups also exercise the ResourceClassGenerator caches.
    val resourceTypes = listOf("string", "id", "layout", "drawable")
    whenever(repository.getResourceNames(eq(ResourceNamespace.RES_AUTO), any())).thenReturn((1..32).map { "res_$it" }.toSet())
    registry.addLibrary(repository, idManager, PKG_1)
    val expectedSizes = resourceTypes.associateWith { type -> registry.findClassDefinition("$PKG_1.R\$$type", manager)!!.size }
    registry.clearCache()

    // Libraries are registered and R classes looked up from several threads while the cache is cleared. Every registration adds a new
    // package, so that the registered packages keep changing while other threads read them to look up R classes.
    val threadCount = 8
    val executor = Executors.newFixedThreadPool(threadCount)
    try {
      val start = CountDownLatch(1)
      val futures =
        (0 until threadCount).map { thread ->
          executor.submit {
            start.await()
            repeat(5_000) { i ->
              when ((thread + i) % 3) {
                0 -> registry.addLibrary(repository, idManager, "pkg.$thread.$i")
                1 -> {
                  val type = resourceTypes[i % resourceTypes.size]
                  registry.findClassDefinition("$PKG_1.R\$$type", manager)?.let { bytes ->
                    assertThat(bytes).hasLength(expectedSizes.getValue(type))
                  }
                }
                else -> {
                  registry.clearCache()
                  registry.addLibrary(repository, idManager, PKG_1)
                }
              }
            }
          }
        }
      start.countDown()
      futures.forEach { it.get(1, TimeUnit.MINUTES) }
    } finally {
      executor.shutdownNow()
    }

    registry.addLibrary(repository, idManager, PKG_1)
    assertThat(registry.findClassDefinition("$PKG_1.R", manager)).isNotNull()
  }
}
