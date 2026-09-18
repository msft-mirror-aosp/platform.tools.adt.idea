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
package com.android.tools.idea.run.deployment.liveedit.desugaring

import com.android.tools.idea.run.deployment.liveedit.LiveEditClassType
import com.android.tools.idea.run.deployment.liveedit.LiveEditCompiledClass
import com.android.tools.idea.run.deployment.liveedit.LiveEditCompilerOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private const val API_LEVEL = 23

class LiveEditDesugarResponseTest {

  private fun compilerOutput() =
    LiveEditCompilerOutput.Builder()
      .addClass(LiveEditCompiledClass("com/example/MainActivity", byteArrayOf(1), null, LiveEditClassType.NORMAL_CLASS))
      .addClass(LiveEditCompiledClass("com/example/ComposableSingletons\$AKt", byteArrayOf(2), null, LiveEditClassType.SUPPORT_CLASS))
      .build()

  @Test
  fun classesGeneratedByDesugaringAreSentAsSupportClasses() {
    val response = LiveEditDesugarResponse(compilerOutput())

    // Long::hashCode needs API 24, so R8 backports it.
    val backport = "com/example/MainActivity\$\$ExternalSyntheticBackport0"
    response.addOutputSet(
      API_LEVEL,
      mapOf(
        "com/example/MainActivity" to byteArrayOf(11),
        "com/example/ComposableSingletons\$AKt" to byteArrayOf(22),
        backport to byteArrayOf(33),
      ),
    )

    val classes = response.classes(API_LEVEL)
    assertEquals(setOf("com/example/MainActivity"), classes.keys)
    assertArrayEquals(byteArrayOf(11), classes["com/example/MainActivity"])

    val supportClasses = response.supportClasses(API_LEVEL)
    assertEquals(setOf("com/example/ComposableSingletons\$AKt", backport), supportClasses.keys)
    assertArrayEquals(byteArrayOf(33), supportClasses[backport])
  }

  @Test
  fun noExtraClassesWhenDesugaringGeneratesNothing() {
    val response = LiveEditDesugarResponse(compilerOutput())
    response.addOutputSet(
      API_LEVEL,
      mapOf("com/example/MainActivity" to byteArrayOf(11), "com/example/ComposableSingletons\$AKt" to byteArrayOf(22)),
    )

    assertEquals(setOf("com/example/MainActivity"), response.classes(API_LEVEL).keys)
    assertEquals(setOf("com/example/ComposableSingletons\$AKt"), response.supportClasses(API_LEVEL).keys)
  }
}
