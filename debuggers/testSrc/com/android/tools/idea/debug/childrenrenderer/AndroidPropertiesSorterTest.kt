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
import com.intellij.debugger.mockJDI.members.MockMethod
import com.intellij.debugger.mockJDI.types.MockClassType
import com.sun.jdi.Field
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.TypeComponent
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

  @Test
  fun testKotlinClass() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class Foo(val f3: Int) {
        val f2: Int = 0
        val f1: Int = 0
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
  fun testKotlinClassWithHierarchy() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class A(val a3: Int) : B(0) {
        override var x: Int = 0
        var a2: Int = 0
        var a1: Int = 0
      }
      open class B(val b3: Int) : C(0) {
        override val x: Int = 0
        val b2: Int = 0
        val b1: Int = 0
      }
      open class C(val c3: Int) {
        open val x: Int = 0
        val c2: Int = 0
        val c1: Int = 0
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name per type)
    val fields =
      fields(
        "A.a1",
        "A.a2",
        "A.a3",
        "A.x",
        "B.b1",
        "B.b2",
        "B.b3",
        "B.x",
        "C.c1",
        "C.c2",
        "C.c3",
        "C.x",
      )

    val sorted = sorter.sortFields(fields)

    val sortedNames = sorted.names()
    assertThat(sortedNames)
      .containsExactly(
        "A#a3",
        "A#x",
        "A#a2",
        "A#a1",
        "B#b3",
        "B#x",
        "B#b2",
        "B#b1",
        "C#c3",
        "C#x",
        "C#c2",
        "C#c1",
      )
      .inOrder()
  }

  @Test
  fun testUnknownFieldsAppendedAtEnd() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class Foo {
        val f2: Int = 0
        val f1: Int = 0
      }
      """
        .trimIndent(),
    )

    val inputFields =
      fields(
        "Foo.a",
        "Foo.f1",
        "Foo.f2",
      )

    val sorted = sorter.sortFields(inputFields).names()

    assertThat(sorted)
      .containsExactly(
        "Foo#f2",
        "Foo#f1",
        "Foo#a",
      )
      .inOrder()
  }

  @Test
  fun testSyntheticThisPrecedesUnknown() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class Foo {
        val f2: Int = 0
        val f1: Int = 0
      }
      """
        .trimIndent(),
    )

    val inputFields =
      fields(
        "Foo.a",
        $$"Foo.this$0",
        "Foo.f1",
        "Foo.f2",
      )

    val sorted = sorter.sortFields(inputFields).names()

    assertThat(sorted)
      .containsExactly(
        "Foo#f2",
        "Foo#f1",
        $$"Foo#this$0",
        "Foo#a",
      )
      .inOrder()
  }

  @Test
  fun testUnknownClassPreservesOriginalOrder() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class Foo {
        val f2: Int = 0
        val f1: Int = 0
      }
      """
        .trimIndent(),
    )

    val inputFields =
      fields(
        "Foo.a",
        "Foo.f1",
        "Foo.c",
        "Foo.f2",
        "Foo.b",
      )

    val sorted = sorter.sortFields(inputFields).names()

    assertThat(sorted)
      .containsExactly(
        "Foo#f2",
        "Foo#f1",
        "Foo#a",
        "Foo#c",
        "Foo#b",
      )
      .inOrder()
  }

  @Test
  fun sortGetters() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class Foo:  {
        val f2: Int get() = 0
        val f1: Int get() = 0
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name)
    val getters =
      getters(
        "Foo.getF1",
        "Foo.getF2",
      )

    val sorted = sorter.sortGetters(getters).names()

    assertThat(sorted)
      .containsExactly(
        "Foo#getF2",
        "Foo#getF1",
      )
      .inOrder()
  }

  @Test
  fun sortGetters_withHierarchy() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      class A : B() {
        override val x: Int get() = 0
        val a2: Int get() = 0
        val a1: Int get() = 0
      }
      open class B : C() {
        override val x: Int get() = 0
        val b2: Int get() = 0
        val b1: Int get() = 0
      }
      open class C {
        open val x: Int get() = 0
        val c2: Int get() = 0
        val c1: Int get() = 0
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name)
    val getters =
      getters(
        "C.getC1",
        "C.getC2",
        "C.getX",
        "B.getB1",
        "B.getB2",
        "B.getX",
        "A.getA1",
        "A.getA2",
        "A.getX",
      )

    val sorted = sorter.sortGetters(getters).names()

    assertThat(sorted)
      .containsExactly(
        "C#getX",
        "C#getC2",
        "C#getC1",
        "B#getX",
        "B#getB2",
        "B#getB1",
        "A#getX",
        "A#getA2",
        "A#getA1",
      )
      .inOrder()
  }

  @Test
  fun sortGetters_withInterfaceAndAbstractBase() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      interface Interface {
        val interfaceValue get() = 0
      }
      abstract class AbstractClass {
        val abstractValue get() = 0
      }
      class Bar : AbstractClass(), Interface {
        val x get() = 0
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by declaring-class, name)
    val getters =
      getters(
        "Bar.getInterfaceValue",
        "Bar.getX",
        "AbstractClass.getAbstractValue",
      )

    val sorted = sorter.sortGetters(getters).names()

    assertThat(sorted)
      .containsExactly(
        "Bar#getX",
        "Bar#getInterfaceValue",
        "AbstractClass#getAbstractValue",
      )
      .inOrder()
  }

  @Test
  fun sortGetters_withDiamondInterfaces() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      interface I1 {
        val i1 get() = 1
      }
      interface I2 : I1 {
        val i2 get() = 2
      }
      interface I3 : I1 {
        val i3 get() = 1
      }
      class Diamond : I2, I3
      """
        .trimIndent(),
    )

    // In Dex order (sorted by declaring-class, name)
    val getters =
      getters(
        "Diamond.getI1",
        "Diamond.getI2",
        "Diamond.getI3",
      )

    val sorted = sorter.sortGetters(getters).names()

    assertThat(sorted)
      .containsExactly(
        "Diamond#getI2",
        "Diamond#getI1",
        "Diamond#getI3",
      )
      .inOrder()
  }

  @Test
  fun testKotlinNamedLocalClass() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      fun main() {
        class NamedLocalClass {
          val b = 1
          val a = 1
        }
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name)
    val fields =
      fields(
        $$"FooKt$main$NamedLocalClass.a",
        $$"FooKt$main$NamedLocalClass.b",
      )

    val sorted = sorter.sortFields(fields).names()

    assertThat(sorted)
      .containsExactly(
        $$"FooKt$main$NamedLocalClass#b",
        $$"FooKt$main$NamedLocalClass#a",
      )
      .inOrder()
  }

  @Test
  fun testKotlinLocalObjectWithAssignment() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      fun main() {
        val variable = object {
          val b = 1
          val a = 2
        }
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name)
    val fields =
      fields(
        $$"FooKt$main$variable$1.a",
        $$"FooKt$main$variable$1.b",
      )

    val sorted = sorter.sortFields(fields).names()

    assertThat(sorted)
      .containsExactly(
        $$"FooKt$main$variable$1#b",
        $$"FooKt$main$variable$1#a",
      )
      .inOrder()
  }

  @Test
  fun testKotlinLocalObjectWithoutAssignment() {
    projectRule.fixture.addFileToProject(
      "src/Foo.kt",
      """
      fun main() {
        object {
          val b = 1
          val a = 2
        }
      }
      """
        .trimIndent(),
    )

    // In Dex order (sorted by name)
    val fields =
      fields(
        $$"FooKt$main$1.a",
        $$"FooKt$main$1.b",
      )

    val sorted = sorter.sortFields(fields).names()

    assertThat(sorted)
      .containsExactly(
        $$"FooKt$main$1#b",
        $$"FooKt$main$1#a",
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

  private fun getters(vararg getters: String) = getters.map {
    val split = it.split('.')
    getter(split[0], split[1])
  }

  private fun getter(typeName: String, name: String): Method {
    val declaringRefType =
      object : MockClassType(vm, null) {
        override fun name(): String = typeName
      }
    return object : MockMethod(null, vm) {
      override fun name(): String = name

      override fun declaringType(): ReferenceType = declaringRefType
    }
  }
}

private fun List<TypeComponent>.names() = map { "${it.declaringType().name()}#${it.name()}" }
