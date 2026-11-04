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
@file:JvmName("EditorPaneUtils")

package com.android.tools.idea.adb.wireless

import com.android.utils.HtmlBuilder
import com.intellij.ui.JBColor
import com.intellij.util.ui.SwingHelper
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JEditorPane

fun createHtmlEditorPane(): JEditorPane {
  val viewer: JEditorPane = SwingHelper.createHtmlViewer(true, null, JBColor.WHITE, JBColor.BLACK)
  viewer.isOpaque = false
  viewer.isFocusable = false
  UIUtil.doNotScrollToCaret(viewer)
  return viewer
}

fun JEditorPane.setHtml(htmlBuilder: HtmlBuilder, textColor: Color?) {
  SwingHelper.setHtml(this, htmlBuilder.html, textColor)
}
