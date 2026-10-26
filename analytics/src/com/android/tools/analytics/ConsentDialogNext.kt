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
package com.android.tools.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.adtui.compose.StudioComposePanel
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import icons.StudioIllustrationsCompose
import javax.swing.JComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.checkboxStyle
import org.jetbrains.jewel.ui.theme.linkStyle
import org.jetbrains.jewel.ui.typography

class ConsentDialogNext : DialogWrapper(true) {
  val isOptedIn: Boolean
    get() = isCheckBoxChecked && isOK

  private var isCheckBoxChecked by mutableStateOf(true)

  init {
    isResizable = false
    isModal = true
    setUndecorated(true)
    init()
  }

  override fun createCenterPanel(): JComponent {
    val component = StudioComposePanel { ConsentDialogPage() }
    component.preferredSize = JBUI.size(590, 420)
    return component
  }

  override fun createContentPaneBorder() = null

  override fun createSouthPanel() = null

  @Composable
  fun ConsentDialogPage() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Spacer(Modifier.height(40.dp))
      Icon(StudioIllustrationsCompose.Common.Plant, contentDescription = null, modifier = Modifier.size(64.dp))
      Spacer(Modifier.height(40.dp))
      Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp)) {
        Text(
          text = "Help improve Android Studio for everyone",
          style = JewelTheme.typography.rememberDefaultTextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(20.dp))
        CheckboxRow(
          checked = isCheckBoxChecked,
          onCheckedChange = { isChecked -> isCheckBoxChecked = isChecked },
          modifier = Modifier.fillMaxWidth().testTag("OptInCheckbox"),
          verticalAlignment = Alignment.Top,
        ) {
          Text(createCheckboxCommentString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        val checkboxTextOffset = JewelTheme.checkboxStyle.metrics.checkboxSize.width + JewelTheme.checkboxStyle.metrics.iconContentGap
        Text(
          "You can change data sharing preferences in ${ShowSettingsUtil.getSettingsMenuName()} > Appearance & Behavior > System Settings > Data Sharing",
          fontSize = 13.sp,
          color = JewelTheme.globalColors.text.disabled,
          modifier = Modifier.padding(start = checkboxTextOffset),
        )
      }
      Spacer(Modifier.height(20.dp))
      DefaultButton(onClick = { close(OK_EXIT_CODE) }) { Text("OK") }
      Spacer(Modifier.height(40.dp))
    }
  }

  @Composable
  private fun createCheckboxCommentString() = buildAnnotatedString {
    append(
      "Yes, I agree to allow Google to collect usage data for Android Studio and its related tools to help improve the product, including feature/resource usage, packages, and plugin configuration in accordance with the "
    )
    pushLink(
      LinkAnnotation.Clickable("policy", TextLinkStyles(SpanStyle(color = JewelTheme.linkStyle.colors.content))) {
        BrowserUtil.browse("https://policies.google.com/privacy")
      }
    )
    append("Google Privacy Policy")
    pop()
    append(". The anonymous and aggregated usage data may be shared with Google's partners to improve Android Studio.")
  }
}
