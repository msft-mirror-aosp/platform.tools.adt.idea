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
package com.android.tools.idea.console

import com.android.tools.idea.console.SafeUrlFilter.NopHyperlinkInfo
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.filters.UrlFilter.FileUrlHyperlinkInfo
import com.intellij.ide.browsers.OpenUrlHyperlinkInfo
import com.intellij.openapi.project.Project
import org.junit.Test
import org.mockito.kotlin.mock

class SafeUrlFilterTest {
  @Test
  fun applyFilter_http() {
    val result = SafeUrlFilter().applyFilter("http url: http://www.google.com")

    assertThat(result?.firstHyperlinkInfo).isInstanceOf(OpenUrlHyperlinkInfo::class.java)
  }

  @Test
  fun applyFilter_regularFile() {
    val result = SafeUrlFilter().applyFilter("regular file url: file:///tmp/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isInstanceOf(OpenUrlHyperlinkInfo::class.java)
  }

  @Test
  fun applyFilter_regularFile_withProject() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("regular file url: file:///tmp/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isInstanceOf(FileUrlHyperlinkInfo::class.java)
  }

  @Test
  fun applyFilter_localhostFile_withProject() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("localhost file url: file://localhost/tmp/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_localhostUncBypass_doubleSlash() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("localhost unc url: file://localhost//10.0.0.2/share/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_localhostUncBypass_percentEncodedBackslashes() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("localhost unc url: file://localhost/%5C%5C10.0.0.2/share/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_uncFile_fourSlashes() {
    val result = SafeUrlFilter().applyFilter("unc file url: file:////10.0.0.2/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_uncFile_twoSlashesAuthority() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("unc file url: file://10.0.0.2/share/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_uncFile_backslashes() {
    val result = SafeUrlFilter().applyFilter("""unc file url: file://\\10.0.0.2/foo.txt""")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_uncFile_singleSlashWithBackslashes() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("""unc file url: file:/\\10.0.0.2\share\foo.txt""")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_uncFile_percentEncodedBackslashes() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("unc file url: file:///%5C%5C10.0.0.2/share/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }

  @Test
  fun applyFilter_uncFile_singleSlashWithPercentEncodedSlashes() {
    val project = mock<Project>()
    val result = SafeUrlFilter(project).applyFilter("unc file url: file:/%2F%2F10.0.0.2/share/foo.txt")

    assertThat(result?.firstHyperlinkInfo).isSameAs(NopHyperlinkInfo)
  }
}

private fun SafeUrlFilter.applyFilter(line: String) = applyFilter(line, line.length)
