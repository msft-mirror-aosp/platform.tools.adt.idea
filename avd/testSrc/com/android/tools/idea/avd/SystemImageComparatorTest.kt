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

import com.android.repository.Revision
import com.android.repository.api.RepoPackage
import com.android.repository.testframework.FakePackage.FakeRemotePackage
import com.android.sdklib.AndroidVersion
import com.android.sdklib.ISystemImage
import com.android.sdklib.SystemImageTags
import com.android.sdklib.repository.IdDisplay
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SystemImageComparatorTest {
  @Test
  fun compareIsForTablet() {
    // Arrange
    val image1 = FakeSystemImage(AndroidVersion(34).withBaseExtensionLevel())

    val image2 =
      FakeSystemImage(
        AndroidVersion(34).withBaseExtensionLevel(),
        tags = listOf(SystemImageTags.PLAY_STORE_TAG, SystemImageTags.TABLET_TAG),
      )

    val images = listOf(image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2), sortedImages)
  }

  @Test
  fun compareIsPreview() {
    // Arrange
    val image1 = FakeSystemImage(AndroidVersion(34, "VanillaIceCream", 12, true))
    val image2 = FakeSystemImage(AndroidVersion(34, null, 12, false))

    val images = listOf(image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2), sortedImages)
  }

  @Test
  fun compareAndroidVersions() {
    // Arrange
    val image1 = FakeSystemImage(AndroidVersion(37).withCanaryNumber(1000))
    val image2 = FakeSystemImage(AndroidVersion(37).withCanaryNumber(1001))
    val image3 = FakeSystemImage(AndroidVersion(37, 1).withBetaNumber(3))
    val image4 = FakeSystemImage(AndroidVersion(37, 1).withBetaNumber(4))
    val image5 = FakeSystemImage(AndroidVersion(33).withExtensionLevel(5))
    val image6 = FakeSystemImage(AndroidVersion(34).withExtensionLevel(12))

    val images = listOf(image6, image5, image4, image3, image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2, image3, image4, image5, image6), sortedImages)
  }

  @Test
  fun compareExtensionLevels() {
    // Arrange
    val image1 = FakeSystemImage(AndroidVersion(34, null, 10, false))
    val image2 = FakeSystemImage(AndroidVersion(34, null, 8, false))
    val image3 = FakeSystemImage(AndroidVersion(34, null, null, true))
    val image4 = FakeSystemImage(AndroidVersion(34, null, 7, true), tags = listOf(SystemImageTags.PLAY_STORE_TAG))

    val images = listOf(image4, image3, image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2, image3, image4), sortedImages)
  }

  @Test
  fun compareServices() {
    val image1 = FakeSystemImage(AndroidVersion(33).withBaseExtensionLevel(), tags = listOf(SystemImageTags.GOOGLE_APIS_TAG))

    val image2 = FakeSystemImage(AndroidVersion(33).withBaseExtensionLevel(), tags = listOf(SystemImageTags.PLAY_STORE_TAG))

    val images = listOf(image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2), sortedImages)
  }

  @Test
  fun compareOtherTagCounts() {
    val image1 =
      FakeSystemImage(
        AndroidVersion(33).withBaseExtensionLevel(),
        tags = listOf(SystemImageTags.PLAY_STORE_TAG, IdDisplay.create("other-tag", "Other Tag")),
      )

    val image2 = FakeSystemImage(AndroidVersion(33).withBaseExtensionLevel(), tags = listOf(SystemImageTags.PLAY_STORE_TAG))

    val images = listOf(image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2), sortedImages)
  }

  @Test
  fun compareDisplayNames() {
    val image1 =
      FakeSystemImage(
        AndroidVersion(33).withBaseExtensionLevel(),
        displayName = "Google Play Intel x86_64 Atom System Image",
        tags = listOf(SystemImageTags.PLAY_STORE_TAG),
      )

    val image2 =
      FakeSystemImage(
        AndroidVersion(33).withBaseExtensionLevel(),
        displayName = "Google Play ARM 64 v8a System Image",
        tags = listOf(SystemImageTags.PLAY_STORE_TAG),
      )

    val images = listOf(image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2), sortedImages)
  }

  @Test
  fun compareXrPriority() {
    val image1 = FakeSystemImage(AndroidVersion(34).withBaseExtensionLevel(), path = "system-images;android-34;google-xr;x86_64")
    val image2 =
      FakeSystemImage(AndroidVersion(34).withBaseExtensionLevel(), path = "system-images;android-34;android-xr-v3-playstore;x86_64")
    val image3 =
      FakeSystemImage(AndroidVersion(34).withBaseExtensionLevel(), path = "system-images;android-34;android-xr-preview-playstore;x86_64")
    val image4 = FakeSystemImage(AndroidVersion(34).withBaseExtensionLevel(), path = "system-images;android-34;google_apis;x86_64")

    val images = listOf(image4, image3, image2, image1)

    // Act
    val sortedImages = images.sortedWith(SystemImageComparator)

    // Assert
    assertEquals(listOf(image1, image2, image3, image4), sortedImages)
  }

  private data class FakeSystemImage(
    private val androidVersion: AndroidVersion,
    val displayName: String? = null,
    val path: String = "system-images;android-34;google_apis;x86_64",
    private val tags: List<IdDisplay> = emptyList(),
  ) : ISystemImage {
    override fun getPackage(): RepoPackage =
      FakeRemotePackage(path).apply {
        this@FakeSystemImage.displayName?.let { displayName = it }
      }

    override fun getLocation(): Path = Path.of("")

    override fun getTags(): List<IdDisplay> = tags

    override fun getAddonVendor(): IdDisplay? = null

    override fun getPrimaryAbiType(): String = ""

    override fun getAbiTypes(): List<String> = emptyList()

    override fun getTranslatedAbiTypes(): List<String> = emptyList()

    override fun getSkins(): List<Path> = emptyList()

    override fun getRevision(): Revision = Revision.NOT_SPECIFIED

    override fun getAndroidVersion(): AndroidVersion = androidVersion

    override fun obsolete(): Boolean = false
  }
}
