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
package com.android.screenshottest.util

import com.android.annotations.concurrency.WorkerThread
import com.android.screenshottest.ui.PreviewDetails
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.util.ScreenshotTestUtils
import com.intellij.openapi.project.Project

/** Keys of the artifacts that the screenshot test plugin reports for each preview test case. */
object PreviewScreenshotArtifacts {
  const val METHOD_NAME = "PreviewScreenshot.methodName"
  const val PREVIEW_NAME = "PreviewScreenshot.previewName"
  const val REF_IMAGE_PATH = "PreviewScreenshot.refImagePath"
  const val NEW_IMAGE_PATH = "PreviewScreenshot.newImagePath"
  const val DIFF_IMAGE_PATH = "PreviewScreenshot.diffImagePath"
  const val DIFF_PERCENT = "PreviewScreenshot.diffPercent"
}

private const val SIZE_MISMATCH_PREFIX = "Size Mismatch"
private const val PARAM_START = "[{"
private const val PARAM_END = "}]"
private const val PROVIDER_KEY = "provider="

/**
 * Creates the [PreviewDetails] of a screenshot test case from the artifacts it reported.
 *
 * Resolves the image paths and checks whether the images exist, so it must not be called on the EDT.
 *
 * @param rawPreviewName The preview name as reported by the test; parameter lists are cleaned up with [cleanPreviewName].
 * @param errorStackTrace The failure stack trace of the test case, used to detect image size mismatches.
 */
@WorkerThread
fun createPreviewDetails(
  project: Project?,
  className: String,
  methodName: String,
  rawPreviewName: String,
  artifacts: Map<String, String>,
  testResult: AndroidTestCaseResult?,
  errorStackTrace: String,
): PreviewDetails {
  val previewName = cleanPreviewName(rawPreviewName)
  val sizeMismatchLine = errorStackTrace.lineSequence().firstOrNull { it.contains(SIZE_MISMATCH_PREFIX) }
  return PreviewDetails(
      testId = "$className.$methodName.$previewName",
      className = className,
      methodName = methodName,
      previewName = previewName,
      testResult = testResult,
      destImagePath = ScreenshotTestUtils.resolvePath(project, artifacts[PreviewScreenshotArtifacts.REF_IMAGE_PATH]),
      srcImagePath = ScreenshotTestUtils.resolvePath(project, artifacts[PreviewScreenshotArtifacts.NEW_IMAGE_PATH]),
      diffImagePath = ScreenshotTestUtils.resolvePath(project, artifacts[PreviewScreenshotArtifacts.DIFF_IMAGE_PATH]),
      diffPercent = artifacts[PreviewScreenshotArtifacts.DIFF_PERCENT],
      isSizeMismatch = sizeMismatchLine != null,
      sizeMismatchMessage = sizeMismatchLine?.let { line -> line.substring(line.indexOf(SIZE_MISMATCH_PREFIX)).trim() },
    )
    .withResolvedImageFiles()
}

/**
 * Cleans the preview name by formatting parameter lists.
 *
 * Parses raw strings like "[{provider=com.example.MyProvider}]" into "MyProvider", extracting simple class names for providers.
 */
fun cleanPreviewName(name: String): String {
  if (name.startsWith(PARAM_START) && name.contains(PARAM_END)) {
    return name.substringAfter(PARAM_START).substringBefore(PARAM_END).split(", ").joinToString("_") { part ->
      if (part.startsWith(PROVIDER_KEY)) part.substringAfter(PROVIDER_KEY).substringAfterLast('.') else part
    } + name.substringAfter(PARAM_END)
  }
  return name
}
