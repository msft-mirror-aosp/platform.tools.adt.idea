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
package com.android.tools.rendering

import java.awt.Dimension
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstrainedRenderSizeProviderTest {

  @Test
  fun testTargetSizeWithQuality() {
    val maxImageSize = 1000L * 1000L // 1,000,000 pixels
    var quality = 1.0f

    val provider = ConstrainedRenderSizeProvider(maxImageSize) { quality }

    // 1. With 1.0 quality and size within bounds, target size should be exactly requested size
    var targetSize = provider.getTargetSize(500, 500)
    assertNotNull(targetSize)
    assertEquals(Dimension(500, 500), targetSize)

    // 2. With 0.25 quality (multiplier 0.5), target size should be downscaled by 0.5
    quality = 0.25f
    targetSize = provider.getTargetSize(500, 500)
    assertNotNull(targetSize)
    assertEquals(Dimension(250, 250), targetSize)
  }

  @Test
  fun testTargetSizeWithMaxSizeConstraint() {
    val maxImageSize = 100L * 100L // 10,000 pixels
    val quality = 1.0f

    val provider = ConstrainedRenderSizeProvider(maxImageSize) { quality }

    // Size within bounds (50 * 50 = 2500 < 10000)
    var targetSize = provider.getTargetSize(50, 50)
    assertNotNull(targetSize)
    assertEquals(Dimension(50, 50), targetSize)

    // Size exceeding bounds (200 * 200 = 40000 > 10000)
    // Scale should be sqrt(10000 / 40000) = 0.5
    targetSize = provider.getTargetSize(200, 200)
    assertNotNull(targetSize)
    assertEquals(Dimension(100, 100), targetSize)
  }

  @Test
  fun testTargetSizeWithTargetScale() {
    var quality = 0.95f
    var targetScale = 9.6f
    val provider = ConstrainedRenderSizeProvider(8L * 1024 * 1024, { quality }, { targetScale })

    // Regression test for b/442315045: mdpi Large Desktop wrap_content preview (100x22 px) zoomed in
    val expectedFactor = sqrt(quality.toDouble()) * targetScale.toDouble()
    assertEquals(Dimension((100 * expectedFactor).toInt(), (22 * expectedFactor).toInt()), provider.getTargetSize(100, 22))

    // targetScale < 1f is clamped to 1f (downscaling remains controlled by quality)
    targetScale = 0.5f
    val qualityFactor = sqrt(quality.toDouble())
    assertEquals(Dimension((100 * qualityFactor).toInt(), (22 * qualityFactor).toInt()), provider.getTargetSize(100, 22))

    // Zero quality still yields 1x1 regardless of targetScale
    quality = 0f
    targetScale = 9.6f
    assertEquals(Dimension(1, 1), provider.getTargetSize(100, 22))

    // Large upscaled render is capped by MAX_UPSCALED_IMAGE_SIZE, consistently with maxTargetScale
    quality = 1f
    targetScale = 10f
    val capped = provider.getTargetSize(1920, 1080)
    assertTrue(capped.width.toLong() * capped.height.toLong() <= ConstrainedRenderSizeProvider.MAX_UPSCALED_IMAGE_SIZE)
    assertTrue(capped.width > 1920 && capped.height > 1080)
    val maxTargetScale = ConstrainedRenderSizeProvider.maxTargetScale(1920, 1080, 1.0)
    assertEquals(Dimension((1920 * maxTargetScale).toInt(), (1080 * maxTargetScale).toInt()), capped)

    // Upscaling never shrinks an image below its quality-only size, even when that is above MAX_UPSCALED_IMAGE_SIZE
    assertEquals(1.0, ConstrainedRenderSizeProvider.maxTargetScale(2560, 2560, 1.0), 0.0)
    assertEquals(Dimension(2560, 2560), provider.getTargetSize(2560, 2560))

    // Single-axis rounding difference falls back to measured dimensions so Layoutlib doesn't skip auto-scaling
    targetScale = 1.015f
    assertEquals(Dimension(100, 5), provider.getTargetSize(100, 5))
    // ... including 1px-thick views such as dividers, whose thin axis cannot be scaled
    targetScale = 1.5f
    assertEquals(Dimension(100, 1), provider.getTargetSize(100, 1))
    assertEquals(Dimension(1, 100), provider.getTargetSize(1, 100))
    // Downscaling where both axes shrink is kept
    quality = 0.001f
    targetScale = 1f
    assertEquals(Dimension(3, 1), provider.getTargetSize(100, 22))
  }
}
