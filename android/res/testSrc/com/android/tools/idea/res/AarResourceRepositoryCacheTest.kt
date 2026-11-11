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
package com.android.tools.idea.res

import com.android.ide.common.util.PathString
import com.android.projectmodel.ExternalAndroidLibrary
import com.android.projectmodel.ExternalLibraryImpl
import com.android.projectmodel.RecursiveResourceFolder
import com.android.projectmodel.ResourceFolder
import com.android.resources.aar.AarSourceResourceRepository
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AarResourceRepositoryCacheTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var executor: ExecutorService
  private lateinit var library: ExternalAndroidLibrary

  @Before
  fun setUp() {
    executor = Executors.newSingleThreadExecutor()
    val resDir = tempFolder.newFolder("res").toPath()
    library = ExternalLibraryImpl(address = "com.example:lib:1.0", resFolder = RecursiveResourceFolder(PathString(resDir)))
  }

  @After
  fun tearDown() {
    executor.shutdownNow()
    executor.awaitTermination(10, TimeUnit.SECONDS)
  }

  @Test
  fun clearDiscardsInFlightLoadFinishingAfterClear() {
    val loadCount = AtomicInteger()
    val firstLoadStarted = CountDownLatch(1)
    val releaseFirstLoad = CountDownLatch(1)
    val cache =
      object : AarResourceRepositoryCache() {
        override fun createSourceRepository(library: ExternalAndroidLibrary, resFolder: ResourceFolder): AarSourceResourceRepository {
          if (loadCount.incrementAndGet() == 1) {
            firstLoadStarted.countDown()
            assertThat(releaseFirstLoad.await(10, TimeUnit.SECONDS)).isTrue()
          }
          return AarSourceResourceRepository.create(resFolder.root.toPath()!!, library.libraryName())
        }
      }

    val firstRepoFuture =
      try {
        executor.submit(Callable { cache.getSourceRepository(library) }).also {
          assertThat(firstLoadStarted.await(10, TimeUnit.SECONDS)).isTrue()
          cache.clear()
        }
      } finally {
        releaseFirstLoad.countDown()
      }

    val firstRepo = firstRepoFuture.get(10, TimeUnit.SECONDS)
    val postClearRepo = cache.getSourceRepository(library)

    assertThat(postClearRepo).isNotSameAs(firstRepo)
    assertThat(loadCount.get()).isEqualTo(2)
    assertThat(cache.getSourceRepository(library)).isSameAs(postClearRepo)
    assertThat(loadCount.get()).isEqualTo(2)
  }

  @Test
  fun clearDetachesPostClearCallerFromInFlightLoad() {
    val loadCount = AtomicInteger()
    val firstLoadStarted = CountDownLatch(1)
    val releaseFirstLoad = CountDownLatch(1)
    val cache =
      object : AarResourceRepositoryCache() {
        override fun createSourceRepository(library: ExternalAndroidLibrary, resFolder: ResourceFolder): AarSourceResourceRepository {
          if (loadCount.incrementAndGet() == 1) {
            firstLoadStarted.countDown()
            assertThat(releaseFirstLoad.await(10, TimeUnit.SECONDS)).isTrue()
          }
          return AarSourceResourceRepository.create(resFolder.root.toPath()!!, library.libraryName())
        }
      }

    val postClearRepo: AarSourceResourceRepository
    val firstRepoFuture =
      try {
        executor.submit(Callable { cache.getSourceRepository(library) }).also {
          assertThat(firstLoadStarted.await(10, TimeUnit.SECONDS)).isTrue()
          cache.clear()
          postClearRepo = cache.getSourceRepository(library)
        }
      } finally {
        releaseFirstLoad.countDown()
      }

    val firstRepo = firstRepoFuture.get(10, TimeUnit.SECONDS)

    assertThat(postClearRepo).isNotSameAs(firstRepo)
    assertThat(cache.getSourceRepository(library)).isSameAs(postClearRepo)
    assertThat(loadCount.get()).isEqualTo(2)
  }
}
