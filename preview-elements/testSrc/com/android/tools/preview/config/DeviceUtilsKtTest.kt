/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.preview.config

import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRatio
import com.android.resources.ScreenRound
import com.android.resources.ScreenSize
import com.android.sdklib.devices.Device
import com.android.sdklib.devices.Hardware
import com.android.sdklib.devices.Screen
import com.android.sdklib.devices.Software
import com.android.sdklib.devices.State
import kotlin.test.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class DeviceUtilsKtTest {

  @Test
  fun deviceToDeviceConfig() {
    val device = MutableDeviceConfig().createDeviceInstance()
    val screen = device.defaultHardware.screen!!

    screen.xDimension = 1080
    screen.yDimension = 2280
    screen.pixelDensity = Density.HIGH

    // Make it Round
    screen.screenRound = ScreenRound.ROUND

    // Give it a chin
    screen.chin = 10

    val roundChinConfig = device.toDeviceConfig()
    assertTrue(roundChinConfig.isRound)
    assertEquals(Shape.Round, roundChinConfig.shape) // Not using Shape.Chin anymore
    assertEquals(10f, roundChinConfig.chinSize) // ChinSize is reflected properly

    // Make width higher than height, should not affect orientation
    screen.xDimension = 2300
    // ScreenOrientation affects orientation
    device.defaultState.orientation = ScreenOrientation.LANDSCAPE
    val landscapeConfig = device.toDeviceConfig()
    assertEquals(Orientation.landscape, landscapeConfig.orientation)
    assertEquals(2300f, landscapeConfig.width)
    assertEquals(2280f, landscapeConfig.height)

    // On legacy DeviceSpec, width and height are swapped to reflect orientation
    assertEquals("spec:width=2300px,height=2280px,dpi=240,isRound=true,chinSize=10px", landscapeConfig.deviceSpec())
  }

  @Test
  fun deviceInstanceOrientation() {
    var device: Device? = null
    val screenProvider = { device!!.defaultHardware.screen!! }
    val orientationProvider = { device!!.defaultState.orientation }

    device = deviceFromDeviceSpec("spec:width=100px,height=200px")
    assertEquals(100, screenProvider().xDimension)
    assertEquals(200, screenProvider().yDimension)
    assertEquals(ScreenOrientation.PORTRAIT, orientationProvider())

    device = deviceFromDeviceSpec("spec:width=300px,height=200px")
    assertEquals(300, screenProvider().xDimension)
    assertEquals(200, screenProvider().yDimension)
    assertEquals(ScreenOrientation.LANDSCAPE, orientationProvider()) // Orientation implied from dimensions

    device = deviceFromDeviceSpec("spec:width=100px,height=200px,orientation=portrait")
    assertEquals(100, screenProvider().xDimension)
    assertEquals(200, screenProvider().yDimension)
    assertEquals(ScreenOrientation.PORTRAIT, orientationProvider())
  }

  @Test
  fun deviceInstanceRoundAndChin() {
    // From DeviceConfig
    var screen =
      DeviceConfig(width = 100f, height = 100f, dimUnit = DimUnit.px, shape = Shape.Round, chinSize = 20f)
        .createDeviceInstance()
        .defaultHardware
        .screen!!
    assertEquals(ScreenRound.ROUND, screen.screenRound)
    assertEquals(20, screen.chin)

    // From DeviceSpec Language
    screen = deviceFromDeviceSpec("spec:width=100px,height=200px,isRound=true,chinSize=50px")!!.defaultHardware.screen!!
    assertEquals(ScreenRound.ROUND, screen.screenRound)
    assertEquals(50, screen.chin)
  }

  @Test
  fun deviceInstanceWithDifferentDimensionUnit() {
    val device1 = deviceFromDeviceSpec("spec:width=100px,height=200px,dpi=310")
    assertNotNull(device1)
    val screen1 = device1.defaultHardware.screen!!
    assertEquals(100, screen1.xDimension)
    assertEquals(200, screen1.yDimension)
    assertEquals(320, screen1.pixelDensity.dpiValue) // Adjusted Density bucket
    assertEquals(0.69, (screen1.diagonalLength * 100).toInt() / 100.0, 0.0)

    val device2 = deviceFromDeviceSpec("spec:width=100dp,height=200dp,dpi=310")
    assertNotNull(device2)
    val screen2 = device2.defaultHardware.screen!!

    // Note: these dimensions are calculated with the closest Density bucket for dpi=310: XHDPI
    // (320)
    assertEquals(200, screen2.xDimension)
    assertEquals(400, screen2.yDimension)
    assertEquals(320, screen2.pixelDensity.dpiValue) // Adjusted Density bucket
    assertEquals(1.39, (screen2.diagonalLength * 100).toInt() / 100.0, 0.0)
  }

  @Test
  fun findByIdAndName() {
    val existingDevices = buildMockDevices()

    val deviceByName = existingDevices.findOrParseFromDefinition("name:name0")
    val screen0 = deviceByName!!.defaultHardware.screen!!
    assertEquals("name0", deviceByName.displayName)
    assertEquals(1080, screen0.xDimension)
    assertEquals(1920, screen0.yDimension)
    assertEquals(320, screen0.pixelDensity.dpiValue)

    val deviceById = existingDevices.findOrParseFromDefinition("id:id1")
    val screen1 = deviceById!!.defaultHardware.screen!!
    assertEquals("id1", deviceById.id)
    assertEquals(540, screen1.xDimension)
    assertEquals(960, screen1.yDimension)
    assertEquals(640, screen1.pixelDensity.dpiValue)
    assertEquals(ScreenOrientation.PORTRAIT, deviceById.defaultState.orientation)

    // Device parameters should be the same as 'id1' with a different orientation
    val deviceByParentId = existingDevices.findOrParseFromDefinition("spec:parent=id1,orientation=landscape")
    val screen2 = deviceByParentId!!.defaultHardware.screen!!
    // Devices defined by 'spec' are always Custom devices
    assertEquals("Custom", deviceByParentId.id)
    assertEquals(540, screen2.xDimension)
    assertEquals(960, screen2.yDimension)
    assertEquals(640, screen2.pixelDensity.dpiValue)
    assertEquals(ScreenOrientation.LANDSCAPE, deviceByParentId.defaultState.orientation)
  }

  @Test
  fun deviceSpecWithMediaQueryParameters() {
    val spec =
      "spec:width=411dp,height=891dp,windowPosture=tabletop,pointerPrecision=fine,keyboardKind=physical,hasCamera=false,hasMicrophone=false,viewingDistance=far"
    val config = DeviceConfig.toDeviceConfigOrNull(spec, emptyList())
    assertNotNull(config)
    assertEquals(WindowPosture.tabletop, config!!.windowPosture)
    assertEquals(PointerPrecision.fine, config.pointerPrecision)
    assertEquals(KeyboardKind.physical, config.keyboardKind)
    assertFalse(config.hasCamera)
    assertFalse(config.hasMicrophone)
    assertEquals(ViewingDistance.far, config.viewingDistance)

    // Round-trip back to deviceSpec string
    assertEquals(spec, config.deviceSpec())
  }

  @Test
  fun parentDeviceSpecWithMediaQueryParameters() {
    val spec =
      "spec:parent=id1,windowPosture=tabletop,pointerPrecision=fine,keyboardKind=physical,hasCamera=false,hasMicrophone=false,viewingDistance=far"
    val existingDevices = buildMockDevices()
    val config = DeviceConfig.toDeviceConfigOrNull(spec, existingDevices)
    assertNotNull(config)
    assertEquals(WindowPosture.tabletop, config!!.windowPosture)
    assertEquals(PointerPrecision.fine, config.pointerPrecision)
    assertEquals(KeyboardKind.physical, config.keyboardKind)
    assertFalse(config.hasCamera)
    assertFalse(config.hasMicrophone)
    assertEquals(ViewingDistance.far, config.viewingDistance)

    assertEquals(spec, config.deviceSpec())
  }

  @Test
  fun parentDeviceSpecPreservesParentDeviceScreenAndTagProperties() {
    // Build a parent device with a 6.7-inch screen explicitly configured as NORMAL screen size
    val parentScreen =
      Screen().apply {
        xDimension = 1344
        yDimension = 2992
        pixelDensity = Density.XXHIGH
        diagonalLength = 6.70
        size = ScreenSize.NORMAL
        ratio = ScreenRatio.LONG
        screenRound = ScreenRound.NOTROUND
      }
    val parentState =
      State().apply {
        name = "Portrait"
        isDefaultState = true
        orientation = ScreenOrientation.PORTRAIT
        hardware = Hardware().apply { screen = parentScreen }
      }
    val parentDevice =
      Device.Builder()
        .apply {
          setId("pixel_8_pro")
          setName("Pixel 8 Pro")
          setTagId("android-wear")
          setManufacturer("Google")
          addSoftware(Software())
          addState(parentState)
        }
        .build()
    val availableDevices = listOf(parentDevice)

    // Parse a parent device spec and verify the created device retains the parent's screen size, diagonal length, ratio, and tagId
    val resolvedParentDevice = availableDevices.findOrParseFromDefinition("spec:parent=pixel_8_pro,orientation=landscape")
    assertNotNull(resolvedParentDevice)
    val resolvedScreen = resolvedParentDevice.defaultHardware.screen!!
    assertEquals("Custom", resolvedParentDevice.id)
    assertEquals("android-wear", resolvedParentDevice.tagId)
    assertEquals(ScreenOrientation.LANDSCAPE, resolvedParentDevice.defaultState.orientation)
    assertEquals(ScreenSize.NORMAL, resolvedScreen.size)
    assertEquals(6.70, resolvedScreen.diagonalLength, 0.001)
    assertEquals(ScreenRatio.LONG, resolvedScreen.ratio)

    // Modify a hardware dimension on the mutable config and verify the parent device is invalidated and screen size is recomputed
    val modifiedConfig = DeviceConfig.toMutableDeviceConfigOrNull("spec:parent=pixel_8_pro", availableDevices)
    assertNotNull(modifiedConfig)
    modifiedConfig.width = 1400f
    assertNull(modifiedConfig.parentDeviceId)
    assertNull(modifiedConfig.parentDevice)
    val recomputedDevice = modifiedConfig.createDeviceInstance()
    assertEquals(ScreenSize.LARGE, recomputedDevice.defaultHardware.screen!!.size)

    // Assign and clear parentDevice on the mutable config and verify parentDeviceId stays in sync
    modifiedConfig.parentDevice = parentDevice
    assertEquals("pixel_8_pro", modifiedConfig.parentDeviceId)
    assertEquals(parentDevice, modifiedConfig.parentDevice)
    modifiedConfig.parentDevice = null
    assertNull(modifiedConfig.parentDeviceId)
    assertNull(modifiedConfig.parentDevice)
  }

  @Test
  fun deviceSpecWithExpandParameters() {
    val verticalSpec = "spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000dp"
    val verticalConfig = DeviceConfig.toDeviceConfigOrNull(verticalSpec, emptyList())
    assertNotNull(verticalConfig)
    assertEquals(ExpandAxis.vertical, verticalConfig!!.expand)
    assertEquals(2000f, verticalConfig.maxHeight)
    assertNull(verticalConfig.maxWidth)
    assertEquals(verticalSpec, verticalConfig.deviceSpec())

    val horizontalSpec = "spec:width=1080px,height=1920px,dpi=440,expand=horizontal,maxWidth=5000px"
    val horizontalConfig = DeviceConfig.toDeviceConfigOrNull(horizontalSpec, emptyList())
    assertNotNull(horizontalConfig)
    assertEquals(ExpandAxis.horizontal, horizontalConfig!!.expand)
    assertNull(horizontalConfig.maxHeight)
    assertEquals(5000f, horizontalConfig.maxWidth)
    assertEquals(horizontalSpec, horizontalConfig.deviceSpec())

    // Max size is optional
    val noMaxSpec = "spec:width=411dp,height=891dp,expand=vertical"
    val noMaxConfig = DeviceConfig.toDeviceConfigOrNull(noMaxSpec, emptyList())
    assertNotNull(noMaxConfig)
    assertEquals(ExpandAxis.vertical, noMaxConfig!!.expand)
    assertNull(noMaxConfig.maxHeight)
    assertEquals(noMaxSpec, noMaxConfig.deviceSpec())

    // Case-insensitive axis value
    val mixedCaseConfig = DeviceConfig.toDeviceConfigOrNull("spec:width=411dp,height=891dp,expand=Vertical", emptyList())
    assertNotNull(mixedCaseConfig)
    assertEquals(ExpandAxis.vertical, mixedCaseConfig!!.expand)
    assertEquals("spec:width=411dp,height=891dp,expand=vertical", mixedCaseConfig.deviceSpec())

    // Not expanded by default
    val defaultConfig = DeviceConfig.toDeviceConfigOrNull("spec:width=411dp,height=891dp", emptyList())
    assertNotNull(defaultConfig)
    assertNull(defaultConfig!!.expand)
    assertNull(defaultConfig.maxHeight)
    assertNull(defaultConfig.maxWidth)
    assertEquals("spec:width=411dp,height=891dp", defaultConfig.deviceSpec())

    assertNotEquals(defaultConfig, noMaxConfig)
    assertNotEquals(noMaxConfig, verticalConfig)
    assertEquals(verticalConfig, DeviceConfig.toDeviceConfigOrNull(verticalSpec, emptyList()))
    assertEquals(verticalConfig.hashCode(), DeviceConfig.toDeviceConfigOrNull(verticalSpec, emptyList()).hashCode())
  }

  @Test
  fun deviceSpecWithInvalidExpandParameters() {
    val invalidSpecs =
      listOf(
        // Unknown axis
        "spec:width=411dp,height=891dp,expand=both",
        // Missing unit
        "spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000",
        // Mixed units
        "spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000px",
        "spec:width=1080px,height=1920px,expand=horizontal,maxWidth=2000dp",
        // Not a number
        "spec:width=411dp,height=891dp,expand=vertical,maxHeight=abcdp",
        // Not positive (>= 1 when rounded)
        "spec:width=411dp,height=891dp,expand=vertical,maxHeight=0dp",
        "spec:width=1080px,height=1920px,expand=vertical,maxHeight=0px",
        "spec:width=1080px,height=1920px,expand=vertical,maxHeight=0.2px",
        "spec:width=411dp,height=891dp,expand=horizontal,maxWidth=-10dp",
        // Invalid values with a parent device
        "spec:parent=id1,expand=both",
        "spec:parent=id1,expand=vertical,maxHeight=2000",
        "spec:parent=id1,expand=vertical,maxHeight=1000dp,maxWidth=3000px",
      )
    val existingDevices = buildMockDevices()
    invalidSpecs.forEach {
      assertNull(it, DeviceConfig.toDeviceConfigOrNull(it, existingDevices))
      assertNull(it, parseExpandSettings(it))
    }
  }

  @Test
  fun parentDeviceSpecWithExpandParameters() {
    val existingDevices = buildMockDevices()

    val spec = "spec:parent=id1,expand=vertical"
    val config = DeviceConfig.toDeviceConfigOrNull(spec, existingDevices)
    assertNotNull(config)
    assertEquals(ExpandAxis.vertical, config!!.expand)
    assertNull(config.maxHeight)
    assertEquals(spec, config.deviceSpec())

    // Parent devices use px, so the same unit is kept
    val pxSpec = "spec:parent=id1,orientation=landscape,expand=horizontal,maxWidth=3000px"
    val pxConfig = DeviceConfig.toDeviceConfigOrNull(pxSpec, existingDevices)
    assertNotNull(pxConfig)
    assertEquals(ExpandAxis.horizontal, pxConfig!!.expand)
    assertEquals(3000f, pxConfig.maxWidth)
    assertEquals(pxSpec, pxConfig.deviceSpec())

    // dp values are accepted with a parent device, and converted to px using the density of the parent (640dpi)
    val dpConfig = DeviceConfig.toDeviceConfigOrNull("spec:parent=id1,expand=vertical,maxHeight=1000dp", existingDevices)
    assertNotNull(dpConfig)
    assertEquals(ExpandAxis.vertical, dpConfig!!.expand)
    assertEquals(4000f, dpConfig.maxHeight)
    assertEquals("spec:parent=id1,expand=vertical,maxHeight=4000px", dpConfig.deviceSpec())
  }

  @Test
  fun mutableDeviceConfigWithExpandParameters() {
    val config = DeviceConfig.toMutableDeviceConfigOrNull("spec:parent=id1,expand=vertical,maxHeight=4000px", buildMockDevices())
    assertNotNull(config)

    config!!.expand = ExpandAxis.horizontal
    config.maxHeight = null
    config.maxWidth = 3000f
    // Expand parameters don't affect the parent device
    assertEquals("id1", config.parentDeviceId)
    assertEquals("spec:parent=id1,expand=horizontal,maxWidth=3000px", config.deviceSpec())

    // Changing the unit also converts the max size, using the density of the device (640dpi)
    config.dimUnit = DimUnit.dp
    assertEquals(750f, config.maxWidth!!, 0.01f)
    assertNull(config.maxHeight)
  }

  @Test
  fun parseExpandSettingsFromDeviceSpec() {
    assertNull(parseExpandSettings(""))
    assertNull(parseExpandSettings("id:pixel_7"))
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp"))
    assertNull(parseExpandSettings("spec:parent=pixel_7,maxHeight=2000dp"))
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,expand=both"))

    assertEquals(ExpandSettings(ExpandAxis.vertical), parseExpandSettings("spec:parent=pixel_7,expand=vertical"))
    assertEquals(ExpandSettings(ExpandAxis.horizontal), parseExpandSettings("spec:parent=pixel_7, expand = horizontal "))
    assertEquals(
      ExpandSettings(ExpandAxis.vertical, 1200f, DimUnit.dp),
      parseExpandSettings("spec:parent=wearos_large_round,expand=vertical,maxHeight=1200dp"),
    )
    assertEquals(
      ExpandSettings(ExpandAxis.horizontal, 5000f, DimUnit.px),
      parseExpandSettings("spec:width=1080px,height=1920px,expand=horizontal,maxWidth=5000px,maxHeight=100px"),
    )
    // Case-insensitive axis values and units, case-sensitive parameter keys
    assertEquals(
      ExpandSettings(ExpandAxis.vertical, 1200f, DimUnit.dp),
      parseExpandSettings("spec:parent=wearos_large_round,expand=Vertical,maxHeight=1200DP"),
    )
    assertEquals(
      ExpandSettings(ExpandAxis.horizontal, 5000f, DimUnit.px),
      parseExpandSettings("spec:parent=pixel_7,expand=HORIZONTAL,maxWidth=5000PX"),
    )
    assertNull(parseExpandSettings("spec:parent=wearos_large_round,EXPAND=vertical"))
    assertEquals(
      ExpandSettings(ExpandAxis.vertical),
      parseExpandSettings("spec:parent=wearos_large_round,expand=vertical,MAXHEIGHT=1200dp"),
    )
    // Only the max size of the expanded axis is used
    assertEquals(ExpandSettings(ExpandAxis.vertical), parseExpandSettings("spec:width=411dp,height=891dp,expand=vertical,maxWidth=100dp"))
    // Invalid max sizes and mixed units are rejected
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,expand=vertical,maxHeight=100"))
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,expand=vertical,maxHeight=0dp"))
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,expand=vertical,maxHeight=0px"))
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,expand=vertical,maxHeight=0.2px"))
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000px"))
    assertNull(parseExpandSettings("spec:parent=pixel_7,expand=vertical,maxHeight=1000dp,maxWidth=3000px"))
    // scrollTo is not an expand setting
    assertNull(parseExpandSettings("spec:width=411dp,height=891dp,scrollTo=end"))
    assertEquals(ExpandSettings(ExpandAxis.vertical), parseExpandSettings("spec:parent=pixel_7,expand=vertical,scrollTo=end"))
  }

  @Test
  fun deviceSpecWithScrollTo() {
    val spec = "spec:width=411dp,height=891dp,scrollTo=end"
    val config = DeviceConfig.toDeviceConfigOrNull(spec, emptyList())
    assertNotNull(config)
    assertEquals(ScrollTarget.End, config!!.scrollTo)
    assertNull(config.expand)
    assertEquals(spec, config.deviceSpec())

    // Case-insensitive scrollTo value
    val mixedCaseConfig = DeviceConfig.toDeviceConfigOrNull("spec:width=411dp,height=891dp,scrollTo=End", emptyList())
    assertNotNull(mixedCaseConfig)
    assertEquals(ScrollTarget.End, mixedCaseConfig!!.scrollTo)
    assertEquals(spec, mixedCaseConfig.deviceSpec())

    // scrollTo can be combined with the expand parameters
    val expandSpec = "spec:width=1080px,height=1920px,dpi=440,expand=vertical,maxHeight=5000px,scrollTo=end"
    val expandConfig = DeviceConfig.toDeviceConfigOrNull(expandSpec, emptyList())
    assertNotNull(expandConfig)
    assertEquals(ScrollTarget.End, expandConfig!!.scrollTo)
    assertEquals(ExpandAxis.vertical, expandConfig.expand)
    assertEquals(expandSpec, expandConfig.deviceSpec())

    // Not scrolled by default
    val defaultConfig = DeviceConfig.toDeviceConfigOrNull("spec:width=411dp,height=891dp", emptyList())
    assertNotNull(defaultConfig)
    assertNull(defaultConfig!!.scrollTo)

    assertNotEquals(defaultConfig, config)
    assertEquals(config, DeviceConfig.toDeviceConfigOrNull(spec, emptyList()))
    assertEquals(config.hashCode(), DeviceConfig.toDeviceConfigOrNull(spec, emptyList()).hashCode())
  }

  @Test
  fun parentDeviceSpecWithScrollTo() {
    val existingDevices = buildMockDevices()

    // A parent based spec with scrollTo is not shortened to id:<device-id>
    val spec = "spec:parent=id1,scrollTo=end"
    val config = DeviceConfig.toDeviceConfigOrNull(spec, existingDevices)
    assertNotNull(config)
    assertEquals(ScrollTarget.End, config!!.scrollTo)
    assertEquals("id1", config.parentDeviceId)
    assertEquals(spec, config.deviceSpec())

    val landscapeSpec = "spec:parent=id1,orientation=landscape,scrollTo=end"
    assertEquals(landscapeSpec, DeviceConfig.toDeviceConfigOrNull(landscapeSpec, existingDevices)!!.deviceSpec())

    val mutableConfig = DeviceConfig.toMutableDeviceConfigOrNull(spec, existingDevices)
    assertNotNull(mutableConfig)
    mutableConfig!!.scrollTo = null
    // scrollTo doesn't affect the parent device
    assertEquals("id1", mutableConfig.parentDeviceId)
    assertEquals("id:id1", mutableConfig.deviceSpec())
    mutableConfig.scrollTo = ScrollTarget.End
    assertEquals(spec, mutableConfig.deviceSpec())
  }

  @Test
  fun deviceSpecWithInvalidScrollTo() {
    val invalidSpecs =
      listOf(
        "spec:width=411dp,height=891dp,scrollTo=start",
        "spec:width=411dp,height=891dp,scrollTo=",
        "spec:parent=id1,scrollTo=bottom",
      )
    val existingDevices = buildMockDevices()
    invalidSpecs.forEach { assertNull(it, DeviceConfig.toDeviceConfigOrNull(it, existingDevices)) }
  }

  @Test
  fun scrollTargetSpecValues() {
    assertEquals(ScrollTarget.End, ScrollTarget.fromSpecValue("end"))
    assertEquals(ScrollTarget.End, ScrollTarget.fromSpecValue("End"))
    assertEquals("end", ScrollTarget.End.specValue)
    assertNull(ScrollTarget.fromSpecValue(""))
    ScrollTarget.keywordTargets.forEach { assertEquals(it, ScrollTarget.fromSpecValue(it.specValue)) }
  }
}

private fun deviceFromDeviceSpec(deviceDefinition: String): Device? = emptyList<Device>().findOrParseFromDefinition(deviceDefinition)

private fun buildMockDevices(): List<Device> {
  // Assign it to name if even, otherwise as an id
  var nameOrIdCount = 0
  return listOf(
      DeviceConfig(width = 1080f, height = 1920f, dimUnit = DimUnit.px, dpi = 320, shape = Shape.Normal),
      DeviceConfig(width = 540f, height = 960f, dimUnit = DimUnit.px, dpi = 640, shape = Shape.Normal),
      DeviceConfig(width = 1080f, height = 2280f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Normal),
      DeviceConfig(width = 600f, height = 600f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Round),
    )
    .map {
      Device.Builder(it.createDeviceInstance())
        .also { builder ->
          if (nameOrIdCount % 2 == 0) {
            builder.setName("name${nameOrIdCount++}")
          } else {
            builder.setId("id${nameOrIdCount++}")
          }
        }
        .build()
    }
}
