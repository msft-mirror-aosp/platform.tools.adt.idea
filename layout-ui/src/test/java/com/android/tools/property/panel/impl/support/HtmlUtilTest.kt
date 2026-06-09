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
package com.android.tools.property.panel.impl.support

import com.android.tools.property.panel.api.TableExpansionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlUtilTest {

  @Test
  fun testExpandableTextWithNull() {
    assertThat(expandableText(null, TableExpansionState.NORMAL)).isNull()
    assertThat(expandableText(null, TableExpansionState.EXPANDED_POPUP)).isNull()
  }

  @Test
  fun testExpandableTextPlainTextNormal() {
    assertThat(expandableText("plain text", TableExpansionState.NORMAL)).isEqualTo("plain text")
  }

  @Test
  fun testExpandableTextPlainTextExpanded() {
    assertThat(expandableText("plain text", TableExpansionState.EXPANDED_POPUP))
      .isEqualTo("<html><nobr>plain text</nobr></html>")
  }

  @Test
  fun testExpandableTextHtmlTextNormalEscaped() {
    // A value starting with <html> should be escaped even in NORMAL state to prevent Swing HTML rendering.
    assertThat(expandableText("<html>bold</html>", TableExpansionState.NORMAL))
      .isEqualTo("<html><nobr>&lt;html&gt;bold&lt;/html&gt;</nobr></html>")

    assertThat(expandableText("<HTML>bold</HTML>", TableExpansionState.NORMAL))
      .isEqualTo("<html><nobr>&lt;HTML&gt;bold&lt;/HTML&gt;</nobr></html>")
  }

  @Test
  fun testExpandableTextHtmlTextExpanded() {
    assertThat(expandableText("<html>bold</html>", TableExpansionState.EXPANDED_POPUP))
      .isEqualTo("<html><nobr>&lt;html&gt;bold&lt;/html&gt;</nobr></html>")
  }

  @Test
  fun testExpandableTextUnderlinedPlainTextExpanded() {
    assertThat(expandableText("plain text", TableExpansionState.EXPANDED_POPUP, underlined = true))
      .isEqualTo("<html><nobr><u>plain text</u></nobr></html>")
  }

  @Test
  fun testExpandableTextUnderlinedHtmlTextNormalEscaped() {
    assertThat(expandableText("<html>bold</html>", TableExpansionState.NORMAL, underlined = true))
      .isEqualTo("<html><nobr>&lt;html&gt;bold&lt;/html&gt;</nobr></html>")
  }
}
