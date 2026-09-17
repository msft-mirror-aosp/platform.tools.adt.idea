/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.qsync.java

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PackageReaderUtilsTest {

  @Test
  fun standard_multi_segment_package() {
    assertThat(packageNameToRelativePath("com.example.foo")).isEqualTo(Path.of("com/example/foo"))
  }

  @Test
  fun single_segment_package() {
    assertThat(packageNameToRelativePath("foo")).isEqualTo(Path.of("foo"))
  }

  @Test
  fun empty_package() {
    assertThat(packageNameToRelativePath("")).isEqualTo(Path.of(""))
  }

  @Test
  fun consecutive_and_edge_dots_trimmed() {
    assertThat(packageNameToRelativePath(".com..foo.")).isEqualTo(Path.of("com/foo"))
  }

  @Test
  fun dots_only() {
    assertThat(packageNameToRelativePath("..")).isEqualTo(Path.of(""))
    assertThat(packageNameToRelativePath(".")).isEqualTo(Path.of(""))
    assertThat(packageNameToRelativePath("...")).isEqualTo(Path.of(""))
  }

  @Test
  fun absolute_unix_path_made_relative() {
    assertThat(packageNameToRelativePath("/tmp/aswb_042_pwned")).isEqualTo(Path.of("_tmp_aswb_042_pwned"))
  }

  @Test
  fun windows_absolute_path_made_relative() {
    assertThat(packageNameToRelativePath("C:/Windows/System32")).isEqualTo(Path.of("C__Windows_System32"))
  }

  @Test
  fun traversal_components_made_relative() {
    assertThat(packageNameToRelativePath("../../etc/passwd")).isEqualTo(Path.of("_/_etc_passwd"))
  }

  @Test
  fun spaces_replaced_with_underscore() {
    assertThat(packageNameToRelativePath("com.my org.foo")).isEqualTo(Path.of("com/my_org/foo"))
  }

  @Test
  fun latin_unicode_preserved() {
    assertThat(packageNameToRelativePath("com.café.pkg")).isEqualTo(Path.of("com/café/pkg"))
  }

  @Test
  fun ukrainian_unicode_preserved() {
    assertThat(packageNameToRelativePath("com.приклад.пакунок")).isEqualTo(Path.of("com/приклад/пакунок"))
  }

  @Test
  fun special_symbols_replaced_with_underscore() {
    assertThat(packageNameToRelativePath("com.foo:bar*baz")).isEqualTo(Path.of("com/foo_bar_baz"))
  }
}
