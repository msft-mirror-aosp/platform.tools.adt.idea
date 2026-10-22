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
package com.google.idea.blaze.base.actions.internal

import com.google.common.truth.Truth.assertThat
import com.intellij.mock.MockVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AswbDumpVfsTest {

  private val virtualFileSystem = MockVirtualFileSystem()
  private val loggedLines = mutableListOf<String>()
  private val listener = AswbDumpVfs.BulkListener(loggedLines::add)

  @Test
  fun before_propertyChangeOutsideGoogleRoots_logsNothing() {
    val events =
      listOf(
        createPropertyChangeEvent("/Users/dev/project/Foo.java"),
        createPropertyChangeEvent("/google_other/Foo.java"),
        createPropertyChangeEvent("/Volumes/google_other/Foo.java"),
      )

    listener.before(events)

    assertThat(loggedLines).isEmpty()
  }

  @Test
  fun before_propertyChangeUnderGoogleRoots_logsEvents() {
    val googleEvent = createPropertyChangeEvent("/google/src/cloud/user/ws/google3/Foo.java")
    val volumesGoogleEvent = createPropertyChangeEvent("/Volumes/google/src/cloud/user/ws/google3/Bar.java")

    listener.before(listOf(googleEvent, volumesGoogleEvent))

    assertThat(loggedLines)
      .containsExactly(
        "VFS Events: (2)",
        "    $googleEvent",
        "    $volumesGoogleEvent",
        "    end.",
      )
      .inOrder()
  }

  @Test
  fun before_mixedEvents_logsNonPropertyChangeEventsAndGooglePropertyChangesOnly() {
    val nonGooglePropertyEvent = createPropertyChangeEvent("/Users/dev/project/Ignored.java")
    val nonGoogleDeleteEvent = createDeleteEvent("/Users/dev/project/Deleted.java")
    val googlePropertyEvent = createPropertyChangeEvent("/google/src/cloud/user/ws/google3/Kept.java")

    listener.before(listOf(nonGooglePropertyEvent, nonGoogleDeleteEvent, googlePropertyEvent))

    assertThat(loggedLines)
      .containsExactly(
        "VFS Events: (2)",
        "    $nonGoogleDeleteEvent",
        "    $googlePropertyEvent",
        "    end.",
      )
      .inOrder()
  }

  private fun createPropertyChangeEvent(path: String): VFilePropertyChangeEvent {
    val virtualFile = checkNotNull(virtualFileSystem.findFileByPath(path))
    return VFilePropertyChangeEvent(
      this,
      virtualFile,
      VirtualFile.PROP_WRITABLE,
      false,
      true,
      false,
    )
  }

  private fun createDeleteEvent(path: String): VFileDeleteEvent {
    val virtualFile = checkNotNull(virtualFileSystem.findFileByPath(path))
    return VFileDeleteEvent(this, virtualFile, false)
  }
}
