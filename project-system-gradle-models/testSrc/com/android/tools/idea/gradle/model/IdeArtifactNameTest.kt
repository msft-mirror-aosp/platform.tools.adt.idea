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
package com.android.tools.idea.gradle.model

import com.android.tools.idea.gradle.model.IdeArtifactName.Companion.toPrintableName
import com.android.tools.idea.gradle.model.IdeArtifactName.Companion.toSourceSet
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IdeArtifactNameTest {

  @Test
  fun testWellKnownArtifactNames() {
    val main: WellKnownIdeArtifactName = WellKnownIdeArtifactName.MAIN
    assertEquals(IdeModuleWellKnownSourceSet.MAIN, main.toWellKnownSourceSet())
    assertEquals(IdeModuleWellKnownSourceSet.MAIN, main.toSourceSet())
    assertEquals("Main", main.toPrintableName())

    val unitTest: WellKnownIdeArtifactName = WellKnownIdeArtifactName.UNIT_TEST
    assertEquals(IdeModuleWellKnownSourceSet.UNIT_TEST, unitTest.toWellKnownSourceSet())
    assertEquals(IdeModuleWellKnownSourceSet.UNIT_TEST, unitTest.toSourceSet())
    assertEquals("UnitTest", unitTest.toPrintableName())

    val androidTest: WellKnownIdeArtifactName = WellKnownIdeArtifactName.ANDROID_TEST
    assertEquals(IdeModuleWellKnownSourceSet.ANDROID_TEST, androidTest.toWellKnownSourceSet())
    assertEquals(IdeModuleWellKnownSourceSet.ANDROID_TEST, androidTest.toSourceSet())
    assertEquals("AndroidTest", androidTest.toPrintableName())

    val testFixtures: WellKnownIdeArtifactName = WellKnownIdeArtifactName.TEST_FIXTURES
    assertEquals(IdeModuleWellKnownSourceSet.TEST_FIXTURES, testFixtures.toWellKnownSourceSet())
    assertEquals(IdeModuleWellKnownSourceSet.TEST_FIXTURES, testFixtures.toSourceSet())
    assertEquals("TestFixtures", testFixtures.toPrintableName())

    val screenshotTest: WellKnownIdeArtifactName = WellKnownIdeArtifactName.SCREENSHOT_TEST
    assertEquals(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST, screenshotTest.toWellKnownSourceSet())
    assertEquals(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST, screenshotTest.toSourceSet())
    assertEquals("ScreenshotTest", screenshotTest.toPrintableName())
  }

  @Test
  fun testIdeArtifactNameCompanionAccessors() {
    assertEquals(WellKnownIdeArtifactName.MAIN, IdeArtifactName.MAIN)
    assertEquals(WellKnownIdeArtifactName.UNIT_TEST, IdeArtifactName.UNIT_TEST)
    assertEquals(WellKnownIdeArtifactName.ANDROID_TEST, IdeArtifactName.ANDROID_TEST)
    assertEquals(WellKnownIdeArtifactName.TEST_FIXTURES, IdeArtifactName.TEST_FIXTURES)
    assertEquals(WellKnownIdeArtifactName.SCREENSHOT_TEST, IdeArtifactName.SCREENSHOT_TEST)
  }

  @Test
  fun testTestSuiteArtifactName() {
    val suiteArtifact = IdeTestSuiteArtifactName("myCustomSuite")
    assertEquals("myCustomSuite", suiteArtifact.name)
    assertEquals("myCustomSuite", suiteArtifact.toPrintableName())

    val sourceSet = suiteArtifact.toSourceSet()
    assertEquals("myCustomSuite", sourceSet.sourceSetName)
    assertFalse(sourceSet.canBeConsumed)
  }

  @Test
  fun testTestSuiteArtifactNameMatchingWellKnownSourceSetName() {
    // A screenshot test suite named "screenshotTest" has a source set with the same name as the well-known screenshot test source set.
    val suiteArtifact = IdeTestSuiteArtifactName("screenshotTest")
    assertEquals("screenshotTest", suiteArtifact.toPrintableName())
    assertEquals(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST, suiteArtifact.toSourceSet())
    assertEquals("screenshotTest", suiteArtifact.toSourceSet().sourceSetName)

    for (wellKnown in IdeModuleWellKnownSourceSet.values()) {
      assertEquals(wellKnown, IdeTestSuiteArtifactName(wellKnown.sourceSetName).toSourceSet())
    }
  }

  @Test
  fun testTestSuiteArtifactNameValidation() {
    try {
      IdeTestSuiteArtifactName("")
      fail("Expected IllegalArgumentException for empty name")
    } catch (e: IllegalArgumentException) {
      assertEquals("Test suite artifact name cannot be empty", e.message)
    }
  }

  @Test
  fun testEqualityAndHashCode() {
    val suite1 = IdeTestSuiteArtifactName("mixedTest")
    val suite2 = IdeTestSuiteArtifactName("mixedTest")
    val suite3 = IdeTestSuiteArtifactName("otherTest")

    assertEquals(suite1, suite2)
    assertEquals(suite1.hashCode(), suite2.hashCode())
    assertNotEquals(suite1, suite3)

    val main: IdeArtifactName = IdeArtifactName.MAIN
    val mainWellKnown: IdeArtifactName = WellKnownIdeArtifactName.MAIN
    assertEquals(main, mainWellKnown)
    assertNotEquals(main, suite1)
  }

  @Test
  fun testSerialization() {
    fun <T> serializeAndDeserialize(obj: T): T {
      val byteOut = ByteArrayOutputStream()
      ObjectOutputStream(byteOut).use { it.writeObject(obj) }
      val byteIn = ByteArrayInputStream(byteOut.toByteArray())
      @Suppress("UNCHECKED_CAST")
      return ObjectInputStream(byteIn).use { it.readObject() as T }
    }

    val restoredMain = serializeAndDeserialize(IdeArtifactName.MAIN)
    assertEquals(WellKnownIdeArtifactName.MAIN, restoredMain)

    val restoredSuite = serializeAndDeserialize(IdeTestSuiteArtifactName("mixedTest"))
    assertEquals(IdeTestSuiteArtifactName("mixedTest"), restoredSuite)
  }

  @Test
  fun testPolymorphicIdeArtifactNameDispatch() {
    val artifacts: List<IdeArtifactName> =
      listOf(
        IdeArtifactName.MAIN,
        IdeArtifactName.UNIT_TEST,
        IdeArtifactName.ANDROID_TEST,
        IdeArtifactName.TEST_FIXTURES,
        IdeArtifactName.SCREENSHOT_TEST,
        IdeTestSuiteArtifactName("mixedTest"),
        IdeTestSuiteArtifactName("mixedAndroidTest"),
      )

    val printableNames = artifacts.map { it.toPrintableName() }
    assertEquals(
      listOf("Main", "UnitTest", "AndroidTest", "TestFixtures", "ScreenshotTest", "mixedTest", "mixedAndroidTest"),
      printableNames,
    )

    val sourceSetNames = artifacts.map { it.toSourceSet().sourceSetName }
    assertEquals(
      listOf("main", "unitTest", "androidTest", "testFixtures", "screenshotTest", "mixedTest", "mixedAndroidTest"),
      sourceSetNames,
    )
  }

  @Test
  fun testNamePropertyPolymorphism() {
    val artifacts: List<IdeArtifactName> =
      listOf(
        IdeArtifactName.MAIN,
        IdeArtifactName.ANDROID_TEST,
        IdeArtifactName.UNIT_TEST,
        IdeArtifactName.TEST_FIXTURES,
        IdeArtifactName.SCREENSHOT_TEST,
        IdeTestSuiteArtifactName("mixedTest"),
        IdeTestSuiteArtifactName("customHostJar"),
      )
    val names = artifacts.map { it.name }
    assertEquals(
      listOf("MAIN", "ANDROID_TEST", "UNIT_TEST", "TEST_FIXTURES", "SCREENSHOT_TEST", "mixedTest", "customHostJar"),
      names,
    )
  }
}
