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
package org.jetbrains.android.sdk

import com.android.sdklib.AndroidVersion
import com.android.sdklib.IAndroidTarget
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StudioEmbeddedRenderTargetTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun testEmbeddedLayoutlibPathResolved() {
    assertNotNull(StudioEmbeddedRenderTarget.ourEmbeddedLayoutlibPath)
  }

  @Test
  fun testEmbeddedLayoutlibPathUnderCancelledProgressIndicator() {
    val indicator = EmptyProgressIndicator()
    indicator.cancel()
    ProgressManager.getInstance()
      .runProcess(
        {
          assertNotNull(StudioEmbeddedRenderTarget.getEmbeddedLayoutLibPath())
        },
        indicator,
      )
  }

  @Test
  fun testGetCompatibilityTarget() {
    val mockTarget = mock<IAndroidTarget>()
    whenever(mockTarget.version).thenReturn(AndroidVersion(34))
    val target = StudioEmbeddedRenderTarget.getCompatibilityTarget(mockTarget)
    assertNotNull(target)
  }
}
