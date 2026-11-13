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

import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.google.common.truth.Truth.assertThat
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import org.junit.Test

class ExpandedScreenShapeTest {
  @Test
  fun nonRoundDevicesAreNotClipped() {
    assertThat(expandedScreenShape(null, null, 100, 300)).isNull()
    assertThat(expandedScreenShape(null, RenderingMode.V_SCROLL, 100, 300)).isNull()
  }

  @Test
  fun screenShapeIsDeviceShapeWhenNotExpanded() {
    val deviceShape = Ellipse2D.Double(0.0, 0.0, 100.0, 300.0)

    assertThat(expandedScreenShape(deviceShape, null, 100, 300)).isSameAs(deviceShape)
    assertThat(expandedScreenShape(deviceShape, RenderingMode.SHRINK, 100, 300)).isSameAs(deviceShape)
    assertThat(expandedCapsuleShape(null, 100, 300)).isNull()
    assertThat(expandedCapsuleShape(RenderingMode.SHRINK, 100, 300)).isNull()
  }

  @Test
  fun screenShapeIsDeviceShapeWhenSquare() {
    val deviceShape = Ellipse2D.Double(0.0, 0.0, 100.0, 100.0)

    assertThat(expandedScreenShape(deviceShape, RenderingMode.V_SCROLL, 100, 100)).isSameAs(deviceShape)
    assertThat(expandedScreenShape(deviceShape, RenderingMode.H_SCROLL, 100, 100)).isSameAs(deviceShape)
    assertThat(expandedCapsuleShape(RenderingMode.V_SCROLL, 100, 100)).isNull()
    assertThat(expandedCapsuleShape(RenderingMode.H_SCROLL, 100, 100)).isNull()
  }

  @Test
  fun screenShapeIsDeviceShapeWhenNotElongatedAlongExpandAxis() {
    val wideShape = Ellipse2D.Double(0.0, 0.0, 300.0, 100.0)
    val tallShape = Ellipse2D.Double(0.0, 0.0, 100.0, 300.0)

    assertThat(expandedScreenShape(wideShape, RenderingMode.V_SCROLL, 300, 100)).isSameAs(wideShape)
    assertThat(expandedScreenShape(tallShape, RenderingMode.H_SCROLL, 100, 300)).isSameAs(tallShape)
    assertThat(expandedCapsuleShape(RenderingMode.V_SCROLL, 300, 100)).isNull()
    assertThat(expandedCapsuleShape(RenderingMode.H_SCROLL, 100, 300)).isNull()
  }

  @Test
  fun screenShapeIsDeviceShapeWhenDimensionsAreNotPositive() {
    val deviceShape = Ellipse2D.Double(0.0, 0.0, 0.0, 300.0)

    assertThat(expandedCapsuleShape(RenderingMode.V_SCROLL, 0, 300)).isNull()
    assertThat(expandedCapsuleShape(RenderingMode.H_SCROLL, 300, 0)).isNull()
    assertThat(expandedCapsuleShape(RenderingMode.V_SCROLL, -10, 300)).isNull()
    assertThat(expandedScreenShape(deviceShape, RenderingMode.V_SCROLL, 0, 300)).isSameAs(deviceShape)
  }

  @Test
  fun screenShapeIsVerticalCapsuleWhenTallerThanWide() {
    val deviceShape = Ellipse2D.Double(0.0, 0.0, 100.0, 300.0)

    val capsule = expandedScreenShape(deviceShape, RenderingMode.V_SCROLL, 100, 300) as RoundRectangle2D
    assertThat(capsule).isEqualTo(RoundRectangle2D.Double(0.0, 0.0, 100.0, 300.0, 100.0, 100.0))
    assertThat(expandedCapsuleShape(RenderingMode.V_SCROLL, 100, 300)).isEqualTo(capsule)
    // Half-circles at the top and bottom, straight sides in between
    assertThat(capsule.contains(50.0, 1.0)).isTrue()
    assertThat(capsule.contains(1.0, 1.0)).isFalse()
    assertThat(capsule.contains(1.0, 150.0)).isTrue()
  }

  @Test
  fun screenShapeIsHorizontalCapsuleWhenWiderThanTall() {
    val deviceShape = Ellipse2D.Double(0.0, 0.0, 300.0, 100.0)

    val capsule = expandedScreenShape(deviceShape, RenderingMode.H_SCROLL, 300, 100) as RoundRectangle2D
    assertThat(capsule).isEqualTo(RoundRectangle2D.Double(0.0, 0.0, 300.0, 100.0, 100.0, 100.0))
    assertThat(expandedCapsuleShape(RenderingMode.H_SCROLL, 300, 100)).isEqualTo(capsule)
    // Half-circles at the start and end, straight sides in between
    assertThat(capsule.contains(1.0, 50.0)).isTrue()
    assertThat(capsule.contains(1.0, 1.0)).isFalse()
    assertThat(capsule.contains(150.0, 1.0)).isTrue()
  }

  @Test
  fun capsuleIsPlacedAtTheGivenPosition() {
    val deviceShape = Ellipse2D.Double(10.0, 20.0, 100.0, 300.0)
    val expected = RoundRectangle2D.Double(10.0, 20.0, 100.0, 300.0, 100.0, 100.0)

    assertThat(expandedScreenShape(deviceShape, RenderingMode.V_SCROLL, 100, 300, x = 10.0, y = 20.0)).isEqualTo(expected)
    assertThat(expandedCapsuleShape(RenderingMode.V_SCROLL, 100, 300, x = 10.0, y = 20.0)).isEqualTo(expected)
  }
}
