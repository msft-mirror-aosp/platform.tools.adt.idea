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
package com.android.tools.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.resources.ResourceType
import com.google.common.collect.ListMultimap
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MultiResourceRepositoryTest {

  @Test
  fun modificationCountUpdatesWhenSingleChildChanges() {
    val child = TestLocalRepository("child")
    val repo = TestMultiResourceRepository("multi", listOf(child))
    val count1 = repo.modificationCount

    child.incrementModificationCount()
    val count2 = repo.modificationCount
    assertThat(count2).isGreaterThan(count1)

    // Modification count stays stable until another change
    assertThat(repo.modificationCount).isEqualTo(count2)

    child.incrementModificationCount()
    val count3 = repo.modificationCount
    assertThat(count3).isGreaterThan(count2)
  }

  @Test
  fun modificationCountUpdatesWhenMultipleChildrenChange() {
    val child1 = TestLocalRepository("child1")
    val child2 = TestLocalRepository("child2")
    val child3 = TestLocalRepository("child3")
    val repo = TestMultiResourceRepository("multi", listOf(child1, child2, child3))

    val initialCount = repo.modificationCount

    child1.incrementModificationCount()
    val countAfterChild1 = repo.modificationCount
    assertThat(countAfterChild1).isGreaterThan(initialCount)

    child2.incrementModificationCount()
    val countAfterChild2 = repo.modificationCount
    assertThat(countAfterChild2).isGreaterThan(countAfterChild1)

    child3.incrementModificationCount()
    val countAfterChild3 = repo.modificationCount
    assertThat(countAfterChild3).isGreaterThan(countAfterChild2)
  }

  @Test
  fun modificationCountUpdatesOnSetChildren() {
    val child1 = TestLocalRepository("child1")
    val child2 = TestLocalRepository("child2")
    val repo = TestMultiResourceRepository("multi", listOf(child1))

    val count1 = repo.modificationCount

    // Add child2
    repo.updateChildren(listOf(child1, child2))
    val count2 = repo.modificationCount
    assertThat(count2).isGreaterThan(count1)

    // Updating child2 updates the repo
    child2.incrementModificationCount()
    val count3 = repo.modificationCount
    assertThat(count3).isGreaterThan(count2)

    // Remove child1
    repo.updateChildren(listOf(child2))
    val count4 = repo.modificationCount
    assertThat(count4).isGreaterThan(count3)

    // Modifying child1 no longer affects repo modification count
    child1.incrementModificationCount()
    val count5 = repo.modificationCount
    assertThat(count5).isEqualTo(count4)

    // Modifying child2 still affects repo
    child2.incrementModificationCount()
    val count6 = repo.modificationCount
    assertThat(count6).isGreaterThan(count5)
  }

  @Test
  fun modificationCountUpdatesOnInvalidateCache() {
    val child = TestLocalRepository("child")
    val repo = TestMultiResourceRepository("multi", listOf(child))

    val count1 = repo.modificationCount
    synchronized(AbstractResourceRepositoryWithLocking.ITEM_MAP_LOCK) {
      repo.invalidateCache()
    }
    val count2 = repo.modificationCount
    assertThat(count2).isGreaterThan(count1)

    // Subsequent read returns stable count
    assertThat(repo.modificationCount).isEqualTo(count2)
  }

  @Test
  fun modificationCountUpdatesOnInvalidateCacheForRepository() {
    val child1 = TestLocalRepository("child1", ResourceNamespace.RES_AUTO)
    val child2 = TestLocalRepository("child2", ResourceNamespace.RES_AUTO)
    val repo = TestMultiResourceRepository("multi", listOf(child1, child2))

    val count1 = repo.modificationCount
    synchronized(AbstractResourceRepositoryWithLocking.ITEM_MAP_LOCK) {
      repo.invalidateCache(child1, ResourceType.STRING)
    }
    val count2 = repo.modificationCount
    assertThat(count2).isGreaterThan(count1)
  }

  private class TestLocalRepository(
    displayName: String,
    private val namespace: ResourceNamespace = ResourceNamespace.RES_AUTO,
  ) : LocalResourceRepository<String>(displayName), SingleNamespaceResourceRepository {

    fun incrementModificationCount() {
      setModificationCount(ourModificationCounter.incrementAndGet())
    }

    override fun computeResourceDirs(): Set<String> = emptySet()

    override fun getMap(namespace: ResourceNamespace, resourceType: ResourceType): ListMultimap<String, ResourceItem>? = null

    override fun getNamespace(): ResourceNamespace = namespace

    override fun getPackageName(): String? = namespace.packageName

    override fun accept(visitor: ResourceVisitor): ResourceVisitor.VisitResult = ResourceVisitor.VisitResult.CONTINUE
  }

  private class TestMultiResourceRepository(
    displayName: String,
    localResources: List<LocalResourceRepository<String>> = emptyList(),
  ) : MultiResourceRepository<String>(displayName) {

    init {
      setChildren(localResources, emptyList(), emptyList())
    }

    fun updateChildren(localResources: List<LocalResourceRepository<String>>) {
      setChildren(localResources, emptyList(), emptyList())
    }

    override fun refreshChildren() {}
  }
}
