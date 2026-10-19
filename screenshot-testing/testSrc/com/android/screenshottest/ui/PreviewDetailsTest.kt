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
package com.android.screenshottest.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewDetailsTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private val details = PreviewDetails(testId = "id", className = "C", methodName = "m", previewName = "p")

  @Test
  fun withResolvedImageFiles_existingFiles() {
    val ref = temporaryFolder.newFile("ref.png")
    val diff = temporaryFolder.newFile("diff.png")

    val resolved = details.copy(destImagePath = ref.path, diffImagePath = diff.path).withResolvedImageFiles()

    assertTrue(resolved.destImageExists)
    assertTrue(resolved.diffImageExists)
  }

  @Test
  fun withResolvedImageFiles_missingFiles() {
    val missing = File(temporaryFolder.root, "missing.png").path

    val resolved = details.copy(destImagePath = missing, diffImagePath = missing).withResolvedImageFiles()

    assertFalse(resolved.destImageExists)
    assertFalse(resolved.diffImageExists)
  }

  @Test
  fun withResolvedImageFiles_nullPaths() {
    val resolved = details.copy(destImageExists = true, diffImageExists = true).withResolvedImageFiles()

    assertFalse(resolved.destImageExists)
    assertFalse(resolved.diffImageExists)
  }
}
