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
package com.android.tools.idea.preview

import com.android.tools.preview.PreviewDisplaySettings
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

private val NEW_BACKGROUND = PreviewDisplaySettings.Background.Color("#FFFFFF")

/**
 * Creates a [TestBasePreviewElement] with a [String] as definition. All the elements share the same method FQN by default so, unless
 * [instanceId] is given, they also share the same instance id, like the non-parameterized Compose preview instances of the same function.
 */
private fun element(
  name: String,
  definition: String? = null,
  baseName: String = "",
  methodFqn: String = "TestMethod",
  instanceId: String = methodFqn,
  background: PreviewDisplaySettings.Background = PreviewDisplaySettings.Background.None,
) =
  TestBasePreviewElement(
    displayName = name,
    baseName = baseName,
    methodFqn = methodFqn,
    background = background,
    instanceId = instanceId,
    previewElementDefinition = definition,
  )

class PreviewElementMatchingTest {

  @Test
  fun nullTargetDoesNotMatch() {
    assertNull(listOf(element("Preview1")).findMatchingElement(null))
  }

  @Test
  fun matchesEqualElement() {
    val element1 = TestPreviewElement("Preview1")
    val element2 = TestPreviewElement("Preview2")
    val elements = listOf(element1, element2)
    assertEquals(element1, elements.findMatchingElement(element1))
    assertEquals(element2, elements.findMatchingElement(element2))
  }

  @Test
  fun matchesStaleElementWithChangedBackgroundByDefinition() {
    val oldElement = element("Preview1", definition = "def1")
    val newElement = element("Preview1", definition = "def1", background = NEW_BACKGROUND)
    val otherElement = element("Preview2", definition = "def2")

    assertEquals(newElement, listOf(otherElement, newElement).findMatchingElement(oldElement))
  }

  @Test
  fun matchesRenamedElementWhenDefinitionIsUnique() {
    val oldElement = element("Old name", definition = "def1")
    val newElement = element("New name", definition = "def1")
    val otherElement = element("Other", definition = "def2")

    assertEquals(newElement, listOf(otherElement, newElement).findMatchingElement(oldElement))
  }

  @Test
  fun matchesMultiPreviewLeafByName() {
    // The leaves of a MultiPreview share the definition (the MultiPreview annotation) and the instance id (the method FQN).
    val darkOld = element("Preview - Dark", definition = "multiPreview")
    val lightNew = element("Preview - Light", definition = "multiPreview", background = NEW_BACKGROUND)
    val darkNew = element("Preview - Dark", definition = "multiPreview", background = NEW_BACKGROUND)

    assertEquals(darkNew, listOf(lightNew, darkNew).findMatchingElement(darkOld))
  }

  @Test
  fun matchesParameterizedMultiPreviewInstanceByNameAndInstanceId() {
    // Instances with the same parameter index in different MultiPreview leaves share the instance id.
    fun instance(leaf: String, index: Int, background: PreviewDisplaySettings.Background = PreviewDisplaySettings.Background.None) =
      element("Preview - $leaf ($index)", definition = "multiPreview", instanceId = "TestMethod#param$index", background = background)

    val light0 = instance("Light", 0, NEW_BACKGROUND)
    val light1 = instance("Light", 1, NEW_BACKGROUND)
    val dark0 = instance("Dark", 0, NEW_BACKGROUND)
    val dark1 = instance("Dark", 1, NEW_BACKGROUND)
    val newElements = listOf(light0, light1, dark0, dark1)

    assertEquals(dark0, newElements.findMatchingElement(instance("Dark", 0)))
    assertEquals(light1, newElements.findMatchingElement(instance("Light", 1)))
  }

  @Test
  fun matchesParameterizedInstanceByInstanceIdWhenNameChanged() {
    val inst0Old = element("Preview (0)", definition = "def1", instanceId = "TestMethod#param0")
    val inst0New = element("Preview (zero)", definition = "def1", instanceId = "TestMethod#param0")
    val inst1New = element("Preview (one)", definition = "def1", instanceId = "TestMethod#param1")

    assertEquals(inst0New, listOf(inst1New, inst0New).findMatchingElement(inst0Old))
  }

  @Test
  fun matchesByNameAndInstanceIdWithoutDefinition() {
    val oldElement = element("Preview", methodFqn = "A")
    val sameNameOtherMethod = element("Preview", methodFqn = "B", background = NEW_BACKGROUND)
    val otherNameSameMethod = element("Other", methodFqn = "A", background = NEW_BACKGROUND)
    val newElement = element("Preview", methodFqn = "A", background = NEW_BACKGROUND)

    assertEquals(newElement, listOf(sameNameOtherMethod, otherNameSameMethod, newElement).findMatchingElement(oldElement))
  }

  @Test
  fun prefersNameAndBaseNameOverInstanceId() {
    val oldElement = element("Preview", baseName = "Base", instanceId = "old")
    val sameInstanceId = element("Other", baseName = "Base", instanceId = "old", methodFqn = "Other")
    val sameNameAndBaseName = element("Preview", baseName = "Base", instanceId = "new")

    assertEquals(sameNameAndBaseName, listOf(sameInstanceId, sameNameAndBaseName).findMatchingElement(oldElement))
  }

  @Test
  fun fallsBackToNameAsLastResort() {
    val oldElement = element("Preview", baseName = "A", methodFqn = "A")
    val sameName = element("Preview", baseName = "B", methodFqn = "B")

    assertEquals(sameName, listOf(element("Other", methodFqn = "C"), sameName).findMatchingElement(oldElement))
  }

  @Test
  fun returnsNullWhenThereIsNoMatch() {
    val oldElement = element("Preview", definition = "def1", methodFqn = "A")

    assertNull(listOf(element("Other", definition = "def2", methodFqn = "B")).findMatchingElement(oldElement))
  }
}
