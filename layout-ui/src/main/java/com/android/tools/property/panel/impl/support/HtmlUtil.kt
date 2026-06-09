/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.google.common.html.HtmlEscapers
import javax.swing.plaf.basic.BasicHTML

// To show text without ellipses in a JLabel we format the text as html:
fun toHtmlString(value: String): String = "<html><nobr>${HtmlEscapers.htmlEscaper().escape(value)}</nobr></html>"

fun toHtmlUnderlinedString(value: String): String = "<html><nobr><u>${HtmlEscapers.htmlEscaper().escape(value)}</u></nobr></html>"

fun expandableText(value: String?, tableExpansionState: TableExpansionState, underlined: Boolean = false): String? {
  if (value == null) return null
  // SECURITY: In NORMAL state we want JLabel's native ellipsis (plain-text rendering), but a
  // device-/file-supplied value that *itself* begins with "<html>" would flip the JLabel into
  // Swing-HTML mode and eagerly fetch <img src=…> (SSRF beacon / Windows NTLM leak). Route any
  // HTML-looking value through the escaped wrapper instead. Values that are not HTML keep the
  // plain-text path so ellipsis truncation is unchanged for the 99.9% case.
  if (tableExpansionState == TableExpansionState.NORMAL) {
    return if (BasicHTML.isHTMLString(value)) toHtmlString(value) else value
  }
  return if (underlined) toHtmlUnderlinedString(value) else toHtmlString(value)
}
