/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.gradle.dsl.parser

import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.UsefulTestCase
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GradleNameElementTest : UsefulTestCase() {
  // These tests check that we have correctly implemented the isomorphism between a list of unescaped strings and a single string with
  // a delimiter to separate suitably-escaped parts.
  fun testSplitNoDots() {
    assertThat(GradleNameElement.split("abcde")).isEqualTo(listOf("abcde"))
  }

  fun testSplitDot() {
    assertThat(GradleNameElement.split("ab.de")).isEqualTo(listOf("ab", "de"))
  }

  fun testSplitMultipleDots() {
    assertThat(GradleNameElement.split("ab.de.gh")).isEqualTo(listOf("ab", "de", "gh"))
  }

  fun testSplitMultipleConsecutiveDots() {
    assertThat(GradleNameElement.split("ab..ef")).isEqualTo(listOf("ab", "", "ef"))
  }

  fun testSplitDotAtBeginning() {
    assertThat(GradleNameElement.split(".bcdef")).isEqualTo(listOf("", "bcdef"))
  }

  fun testSplitDotAtEnd() {
    assertThat(GradleNameElement.split("abcde.")).isEqualTo(listOf("abcde", ""))
  }

  fun testSplitEscapedDot() {
    assertThat(GradleNameElement.split("""ab\.de""")).isEqualTo(listOf("ab.de"))
  }

  fun testSplitEscapedBackslash() {
    assertThat(GradleNameElement.split("""ab\\de""")).isEqualTo(listOf("""ab\de"""))
  }

  fun testSplitExoticCharacters() {
    assertThat(GradleNameElement.split("ab \t \n é Å £ € \u1234")).isEqualTo(listOf("ab \t \n é Å £ € \u1234"))
  }

  fun testJoinNoDots() {
    assertThat(GradleNameElement.join(listOf("abcde"))).isEqualTo("abcde")
  }

  fun testJoinDot() {
    assertThat(GradleNameElement.join(listOf("ab", "de"))).isEqualTo("ab.de")
  }

  fun testJoinMultipleDots() {
    assertThat(GradleNameElement.join(listOf("ab", "de", "gh"))).isEqualTo("ab.de.gh")
  }

  fun testJoinMultipleConsecutiveDots() {
    assertThat(GradleNameElement.join(listOf("ab", "", "ef"))).isEqualTo("ab..ef")
  }

  fun testJoinDotAtBeginning() {
    assertThat(GradleNameElement.join(listOf("", "bcdef"))).isEqualTo(".bcdef")
  }

  fun testJoinDotAtEnd() {
    assertThat(GradleNameElement.join(listOf("abcde", ""))).isEqualTo("abcde.")
  }

  fun testJoinEscapedDot() {
    assertThat(GradleNameElement.join(listOf("ab.de"))).isEqualTo("""ab\.de""")
  }

  fun testJoinEscapedBackslash() {
    assertThat(GradleNameElement.join(listOf("""ab\de"""))).isEqualTo("""ab\\de""")
  }

  fun testJoinExoticCharacters() {
    assertThat(GradleNameElement.join(listOf("ab \t \n é Å £ € \u1234"))).isEqualTo("ab \t \n é Å £ € \u1234")
  }

  // These tests check that we have implemented the lower-level escaping and unescaping operations correctly, which are occasionally
  // needed by backends.
  fun testEscapeNoDot() {
    assertThat(GradleNameElement.escape("abcde")).isEqualTo("abcde")
  }

  fun testEscapeDot() {
    assertThat(GradleNameElement.escape("ab.de")).isEqualTo("""ab\.de""")
  }

  fun testEscapeMultipleDots() {
    assertThat(GradleNameElement.escape("ab.de.gh")).isEqualTo("""ab\.de\.gh""")
  }

  fun testEscapeMultipleConsecutiveDots() {
    assertThat(GradleNameElement.escape("ab..ef")).isEqualTo("""ab\.\.ef""")
  }

  fun testEscapeDotAtBeginning() {
    assertThat(GradleNameElement.escape(".bcdef")).isEqualTo("""\.bcdef""")
  }

  fun testEscapeDotAtEnd() {
    assertThat(GradleNameElement.escape("abcde.")).isEqualTo("""abcde\.""")
  }

  fun testEscapeEscapedDot() {
    assertThat(GradleNameElement.escape("""ab\.de""")).isEqualTo("""ab\\\.de""")
  }

  fun testEscapeEscapedBackslash() {
    assertThat(GradleNameElement.escape("""ab\\de""")).isEqualTo("""ab\\\\de""")
  }

  fun testUnescapeNoDot() {
    assertThat(GradleNameElement.unescape("abcde")).isEqualTo("abcde")
  }

  fun testUnescapeDot() {
    assertThat(GradleNameElement.unescape("""ab\.de""")).isEqualTo("ab.de")
  }

  fun testUnescapeMultipleDots() {
    assertThat(GradleNameElement.unescape("""ab\.de\.gh""")).isEqualTo("ab.de.gh")
  }

  fun testUnescapeMultipleConsecutiveDots() {
    assertThat(GradleNameElement.unescape("""ab\.\.ef""")).isEqualTo("ab..ef")
  }

  fun testUnescapeDotAtBeginning() {
    assertThat(GradleNameElement.unescape("""\.bcdef""")).isEqualTo(".bcdef")
  }

  fun testUnescapeDotAtEnd() {
    assertThat(GradleNameElement.unescape("""abcde\.""")).isEqualTo("abcde.")
  }

  fun testUnescapeEscapedEscapedDot() {
    assertThat(GradleNameElement.unescape("""ab\\\.de""")).isEqualTo("""ab\.de""")
  }

  fun testUnescapeEscapedEscapedBackslash() {
    assertThat(GradleNameElement.unescape("""ab\\\\de""")).isEqualTo("""ab\\de""")
  }

  fun testCreateAndRename() {
    val element = GradleNameElement.create("foo.bar")
    assertThat(element.isFake).isFalse()
    assertThat(element.isEmpty).isFalse()
    assertThat(element.isQualified).isFalse()
    assertThat(element.name()).isEqualTo("foo.bar")
    assertThat(element.fullName()).isEqualTo("""foo\.bar""")
    assertThat(element.fullNameParts()).containsExactly("foo.bar").inOrder()
    assertThat(element.qualifyingParts()).isEmpty()
    assertThat(element.localName).isEqualTo("""foo\.bar""")
    assertThat(element.originalName).isEqualTo("""foo\.bar""")

    element.rename(listOf("ext", "my.prop"))
    assertThat(element.isQualified).isTrue()
    assertThat(element.name()).isEqualTo("my.prop")
    assertThat(element.fullName()).isEqualTo("""ext.my\.prop""")
    assertThat(element.fullNameParts()).containsExactly("ext", "my.prop").inOrder()
    assertThat(element.qualifyingParts()).containsExactly("ext").inOrder()
    assertThat(element.originalName).isEqualTo("""foo\.bar""")

    element.rename("renamed")
    assertThat(element.isQualified).isFalse()
    assertThat(element.name()).isEqualTo("renamed")
    assertThat(element.fullName()).isEqualTo("renamed")
  }

  fun testFakeCopyAndEmpty() {
    val empty = GradleNameElement.empty()
    assertThat(empty.isEmpty).isTrue()
    assertThat(empty.isFake).isFalse()
    assertThat(empty.name()).isEmpty()
    assertThat(empty.fullName()).isEmpty()
    assertThat(empty.fullNameParts()).isEmpty()
    assertThat(empty.localName).isNull()

    val fake = GradleNameElement.fake("android.defaultConfig.minSdk")
    assertThat(fake.isFake).isTrue()
    assertThat(fake.isEmpty).isFalse()
    assertThat(fake.isQualified).isTrue()
    assertThat(fake.localName).isNull()
    assertThat(fake.name()).isEqualTo("minSdk")
    assertThat(fake.fullName()).isEqualTo("android.defaultConfig.minSdk")
    assertThat(fake.fullNameParts()).containsExactly("android", "defaultConfig", "minSdk").inOrder()
    assertThat(fake.qualifyingParts()).containsExactly("android", "defaultConfig").inOrder()

    val copy = GradleNameElement.copy(fake)
    assertThat(copy.isFake).isTrue()
    assertThat(copy.fullNameParts()).containsExactly("android", "defaultConfig", "minSdk").inOrder()
    copy.rename("targetSdk")
    assertThat(copy.isFake).isTrue()
    assertThat(copy.name()).isEqualTo("targetSdk")
    assertThat(fake.name()).isEqualTo("minSdk")
  }

  fun testConcurrentReads() {
    val localElement = GradleNameElement.create("androidx-core-ktx")
    val fakeElement = GradleNameElement.fake("versions.agp")
    val renamedElement = GradleNameElement.create("initial").apply { rename(listOf("libraries", "junit.jupiter")) }

    val threadCount = 8
    val barrier = CyclicBarrier(threadCount)
    val executor = Executors.newFixedThreadPool(threadCount)
    try {
      val tasks =
        (0 until threadCount).map {
          Callable {
            barrier.await(10, TimeUnit.SECONDS)
            repeat(2000) {
              assertThat(localElement.isEmpty).isFalse()
              assertThat(localElement.name()).isEqualTo("androidx-core-ktx")
              assertThat(localElement.fullName()).isEqualTo("androidx-core-ktx")

              assertThat(fakeElement.isEmpty).isFalse()
              assertThat(fakeElement.name()).isEqualTo("agp")
              assertThat(fakeElement.fullName()).isEqualTo("versions.agp")

              assertThat(renamedElement.name()).isEqualTo("junit.jupiter")
              assertThat(renamedElement.fullNameParts()).containsExactly("libraries", "junit.jupiter").inOrder()
              assertThat(renamedElement.qualifyingParts()).containsExactly("libraries").inOrder()
            }
          }
        }
      val futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS)
      for (future in futures) {
        assertThat(future.isCancelled).isFalse()
        future.get()
      }
    } finally {
      executor.shutdownNow()
    }
  }
}
