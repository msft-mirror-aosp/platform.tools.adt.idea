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
package com.android.tools.idea.fonts

import com.google.common.truth.Truth.assertThat
import com.intellij.util.download.DownloadableFileDescription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FontDownloadServiceTest {
  private val files = (1..5).map { fileDescription("https://fonts.example.com/font$it.ttf") }
  private val downloaded = mutableListOf<String>()
  private val notifications = mutableListOf<String>()

  @Test
  fun allDownloadsSucceed() {
    val success = download { downloaded.add(it.downloadUrl) }

    assertThat(success).isTrue()
    assertThat(downloaded).hasSize(5)
    assertThat(notifications).isEmpty()
  }

  @Test
  fun singleFailureIsReportedWithItsUrl() {
    val success = download {
      if (it.downloadUrl.endsWith("font3.ttf")) throw IOException("network error")
      downloaded.add(it.downloadUrl)
    }

    assertThat(success).isFalse()
    assertThat(downloaded).hasSize(4)
    assertThat(notifications).containsExactly("Unable to download: https://fonts.example.com/font3.ttf")
  }

  @Test
  fun multipleFailuresAreCombinedIntoOneNotification() {
    val success = download {
      if (it.downloadUrl.endsWith("font2.ttf") || it.downloadUrl.endsWith("font4.ttf")) {
        throw InvalidFontException("Font data was rejected by the OpenType Sanitizer")
      }
      downloaded.add(it.downloadUrl)
    }

    assertThat(success).isFalse()
    assertThat(downloaded).hasSize(3)
    assertThat(notifications).containsExactly("Unable to download 2 font files")
  }

  @Test
  fun sanitizerUnavailableStopsDownloadsAndNotifiesOnce() {
    val notified = AtomicBoolean(false)
    val unavailable: (DownloadableFileDescription) -> Unit = {
      throw FontSanitizerUnavailableException("OTS native library (ots_jni) is not loaded")
    }

    assertThat(download(notified, unavailable)).isFalse()
    // A second download (e.g. the next batch of menu fonts) does not show another notification
    assertThat(download(notified, unavailable)).isFalse()

    assertThat(notifications).containsExactly("Fonts cannot be downloaded: the font sanitizer is not available")
  }

  @Test
  fun sanitizerUnavailableSkipsRemainingFiles() {
    var attempts = 0
    download {
      attempts++
      throw FontSanitizerUnavailableException("OTS native library (ots_jni) is not loaded")
    }

    assertThat(attempts).isEqualTo(1)
  }

  private fun download(
    notified: AtomicBoolean = AtomicBoolean(false),
    action: (DownloadableFileDescription) -> Unit,
  ): Boolean = FontDownloadService.performSingleFileDownloads(files, { action(it) }, { notifications.add(it) }, notified)

  private fun fileDescription(url: String): DownloadableFileDescription {
    val description = mock(DownloadableFileDescription::class.java)
    `when`(description.downloadUrl).thenReturn(url)
    return description
  }
}
