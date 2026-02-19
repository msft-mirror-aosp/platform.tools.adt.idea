/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.rendering.gradle

import com.android.tools.idea.compose.preview.ComposePreviewRefreshType
import com.android.tools.idea.concurrency.asCollection
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class PerfgateComposeRenderQualityGradleTest : PerfgateComposeGradleTestBase() {
  @Test
  fun qualityRefresh_5Previews() = runBlocking {
    Assert.assertEquals(1, composePreviewRepresentation.renderedPreviewElementsInstancesFlowForTest().value.asCollection().size)
    addPreviewsAndMeasure(4, 5, buildMeasurements("qualityRefresh_5_previews"), measuredRunnable = ::qualityRefresh)
  }

  @Test
  fun qualityRefresh_30Previews() = runBlocking {
    Assert.assertEquals(1, composePreviewRepresentation.renderedPreviewElementsInstancesFlowForTest().value.asCollection().size)
    addPreviewsAndMeasure(29, 30, buildMeasurements("qualityRefresh_30_previews"), measuredRunnable = ::qualityRefresh)
  }

  @Test
  fun qualityRefresh_200Previews() = runBlocking {
    Assert.assertEquals(1, composePreviewRepresentation.renderedPreviewElementsInstancesFlowForTest().value.asCollection().size)
    addPreviewsAndMeasure(199, 200, buildMeasurements("qualityRefresh_200_previews"), measuredRunnable = ::qualityRefresh)
  }

  private suspend fun qualityRefresh() {
    val rng = Random(seed = System.currentTimeMillis())
    projectRule.runAndWaitForRefresh(expectedRefreshType = ComposePreviewRefreshType.QUALITY) {
      previewView.mainSurface.zoomController.setScale(rng.nextDouble(from = 0.01, until = 1.5))
      // The zoom change above should usually trigger a quality refresh
      // automatically, but here a quality refresh is manually requested
      // to avoid the error where the refresh doesn't happen in the
      // unfortunate case of the new scale being equal to the old scale
      composePreviewRepresentation.requestRefreshForTest(type = ComposePreviewRefreshType.QUALITY)
    }
  }
}
