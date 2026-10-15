/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.avd

import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.android.sdklib.ISystemImage
import com.android.sdklib.NameDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
class SystemImageFilterStateTest {
  @Test
  fun systemImageFilterState() {
    // Arrange
    val images = listOf(mockSystemImage(AndroidVersion(34).withExtensionLevel(9)))

    // Act
    val state = SystemImageFilterState(API, null, showUnsupportedSystemImages = true)

    // Assert
    assertEquals(images, state.filter(images))
  }

  @Test
  fun systemImageFilterStateMinorVersion() {
    val image = mockSystemImage(AndroidVersion(36, 2))
    val images = listOf(mockSystemImage(AndroidVersion(36)), mockSystemImage(AndroidVersion(36, 1)), image)

    val state = SystemImageFilterState(AndroidVersionSelection.forVersion(AndroidVersion(36, 2)), null, showUnsupportedSystemImages = true)

    assertEquals(listOf(image), state.filter(images))
  }

  @Test
  fun systemImageFilterStateIsBaseExtensionAndExtensionLevelIsNull() {
    // Arrange
    val images = listOf(mockSystemImage(AndroidVersion(34, null, null, true)), mockSystemImage(AndroidVersion(34, null, 7, true)))

    // Act
    val state = SystemImageFilterState(API, null, showUnsupportedSystemImages = true)

    // Assert
    assertEquals(images, state.filter(images))
  }

  @Test
  fun systemImageFilterStatePreview() {
    // Arrange
    val images = listOf(mockSystemImage(AndroidVersion(35, "Baklava", 15, true)), mockSystemImage(AndroidVersion(35, "Baklava", 16, true)))

    // Act
    val state =
      SystemImageFilterState(AndroidVersionSelection.forVersion(AndroidVersion(35, "Baklava")), null, showUnsupportedSystemImages = true)

    // Assert
    assertEquals(images, state.filter(images))
  }

  @Test
  fun systemImageFilterStateCanary() {
    val canary1 = mockSystemImage(AndroidVersion(35, 0).withCanaryNumber(20250101))
    val canary2 = mockSystemImage(AndroidVersion(36, 0).withCanaryNumber(20250201))
    val stable = mockSystemImage(AndroidVersion(36, 0))
    val images = listOf(canary1, canary2, stable)

    val selection = AndroidVersionSelection.forVersion(AndroidVersion(35, 0).withCanaryNumber(20250101))
    assertEquals(AndroidVersionSelection.Canary, selection)

    val state = SystemImageFilterState(selection, null, showUnsupportedSystemImages = true)

    assertEquals(listOf(canary1, canary2), state.filter(images))
  }

  @Test
  fun systemImageFilterStateBeta() {
    val beta37_1 = mockSystemImage(AndroidVersion(37, 0).withBetaNumber(1))
    val beta37_2 = mockSystemImage(AndroidVersion(37, 0).withBetaNumber(2))
    val beta36 = mockSystemImage(AndroidVersion(36, 1).withBetaNumber(1))
    val stable37 = mockSystemImage(AndroidVersion(37, 0))
    val images = listOf(beta37_1, beta37_2, beta36, stable37)

    val selection = AndroidVersionSelection.forVersion(AndroidVersion(37, 0).withBetaNumber(1))
    assertEquals(AndroidVersionSelection.Beta(AndroidApiLevel(37, 0)), selection)
    assertEquals(NameDetails("API 37.0 Beta", null), selection.nameDetails)

    val state = SystemImageFilterState(selection, null, showUnsupportedSystemImages = true)

    assertEquals(listOf(beta37_1, beta37_2), state.filter(images))
  }

  @Test
  fun systemImageFilterStateApiLevelsAreEqual() {
    // Arrange
    val image = mockSystemImage(AndroidVersion(34).withBaseExtensionLevel())
    val images = listOf(mockSystemImage(AndroidVersion(34).withExtensionLevel(8)), image)

    // Act
    val state = SystemImageFilterState(API, null, showUnsupportedSystemImages = true)

    // Assert
    assertEquals(listOf(image), state.filter(images))
  }

  @Test
  fun systemImageFilterStateApiLevelsArentEqual() {
    // Arrange
    val image = mockSystemImage(AndroidVersion(34).withExtensionLevel(9))
    val images = listOf(mockSystemImage(AndroidVersion(33).withBaseExtensionLevel()), image)

    // Act
    val state = SystemImageFilterState(API, null, showUnsupportedSystemImages = true)

    // Assert
    assertEquals(listOf(image), state.filter(images))
  }

  @Test
  fun systemImageFilterStateMinorVersionBaseExtensionLevels() {
    val api36 = mockSystemImage(AndroidVersion(36, 0, null, 17, true))
    val api36ext = mockSystemImage(AndroidVersion(36, 0, null, 18, false))
    val api36_1 = mockSystemImage(AndroidVersion(36, 1, null, 19, true))
    val api36_1ext = mockSystemImage(AndroidVersion(36, 1, null, 20, false))

    val images = listOf(api36, api36ext, api36_1, api36_1ext)

    val stateWithoutExtensions = SystemImageFilterState(AndroidVersionSelection.Any, null, showUnsupportedSystemImages = true)

    assertEquals(listOf(api36, api36_1), stateWithoutExtensions.filter(images))

    val state =
      SystemImageFilterState(
        AndroidVersionSelection.Any,
        null,
        showSdkExtensionSystemImages = true,
        showUnsupportedSystemImages = true,
      )

    assertEquals(images, state.filter(images))
  }

  private companion object {
    private val API = AndroidVersionSelection.forVersion(AndroidVersion(34))

    private fun mockSystemImage(version: AndroidVersion): ISystemImage {
      val image = mock<ISystemImage>()
      whenever(image.androidVersion).thenReturn(version)

      return image
    }
  }
}
