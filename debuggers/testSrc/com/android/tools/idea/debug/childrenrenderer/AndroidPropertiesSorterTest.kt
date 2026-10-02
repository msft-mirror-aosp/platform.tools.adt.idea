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
package com.android.tools.idea.debug.childrenrenderer

import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.debugger.mockJDI.MockVirtualMachine
import com.intellij.debugger.mockJDI.members.MockField
import com.intellij.debugger.mockJDI.types.MockClassType
import com.sun.jdi.Field
import com.sun.jdi.ReferenceType
import org.junit.Rule
import org.junit.Test

class AndroidPropertiesSorterTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory().withKotlin()

  private val project
    get() = projectRule.project

  private val sorter by lazy { AndroidPropertySorter(project) }
  private val vm = MockVirtualMachine()

  @Test
  fun testJavaClass() {
    projectRule.fixture.addFileToProject(
      "src/Foo.java",
      """
      class Foo {
        int f3;
        int f2;
        int f1;
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name)
    val fields =
      fields(
        "Foo.f1",
        "Foo.f2",
        "Foo.f3",
      )

    val sorted = sorter.sortFields(fields).names()

    assertThat(sorted)
      .containsExactly(
        "Foo#f3",
        "Foo#f2",
        "Foo#f1",
      )
      .inOrder()
  }

  @Test
  fun testJavaClassWithHierarchy() {
    projectRule.fixture.addFileToProject(
      "src/Foo.java",
      """
      class A extends B {
        int x;
        int a2;
        int a1;
      }
      class B extends C{
        int x;
        int b2;
        int b1;
      }
      class C {
        int x;
        int c2;
        int c1;
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name per type)
    val fields =
      fields(
        "A.a1",
        "A.a2",
        "A.x",
        "B.b1",
        "B.b2",
        "B.x",
        "C.c1",
        "C.c2",
        "C.x",
      )

    val sorted = sorter.sortFields(fields)

    val sortedNames = sorted.names()
    assertThat(sortedNames)
      .containsExactly(
        "A#x",
        "A#a2",
        "A#a1",
        "B#x",
        "B#b2",
        "B#b1",
        "C#x",
        "C#c2",
        "C#c1",
      )
      .inOrder()
  }

  private fun fields(vararg fields: String) = fields.map {
    val split = it.split('.')
    field(split[0], split[1])
  }

  private fun field(typeName: String, fieldName: String): Field {
    val declaringRefType =
      object : MockClassType(vm, null) {
        override fun name(): String = typeName
      }
    return object : MockField(null, vm) {
      override fun name(): String = fieldName

      override fun declaringType(): ReferenceType = declaringRefType
    }
  }
}

private fun List<Field>.names() = map { "${it.declaringType().name()}#${it.name()}" }
