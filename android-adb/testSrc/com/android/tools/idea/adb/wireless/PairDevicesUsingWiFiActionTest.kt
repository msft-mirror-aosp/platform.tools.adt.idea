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
package com.android.tools.idea.adb.wireless

import com.google.common.truth.Truth
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.LightPlatform4TestCase
import com.intellij.testFramework.TestActionEvent
import org.junit.Test

class PairDevicesUsingWiFiActionTest : LightPlatform4TestCase() {
  @Test
  fun actionShouldBeEnabledWhenProjectIsSet() {
    // Prepare
    val action = PairDevicesUsingWiFiAction()
    val event = TestActionEvent.createTestEvent(action)

    // Act
    action.update(event)

    // Assert
    Truth.assertThat(event.presentation.isEnabled).isTrue()
    Truth.assertThat(event.presentation.isVisible).isTrue()
  }

  @Test
  fun actionShouldBeDisabledWhenProjectIsNull() {
    // Prepare
    val action = PairDevicesUsingWiFiAction()
    val event = TestActionEvent.createTestEvent(action, DataContext.EMPTY_CONTEXT)

    // Act
    action.update(event)

    // Assert
    Truth.assertThat(event.presentation.isEnabled).isFalse()
    Truth.assertThat(event.presentation.isVisible).isFalse()
  }
}
