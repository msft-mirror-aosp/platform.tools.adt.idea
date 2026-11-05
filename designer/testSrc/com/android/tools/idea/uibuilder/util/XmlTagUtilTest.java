/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.util;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertEquals;

import com.android.tools.idea.common.util.XmlTagUtil;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.RunsInEdt;
import org.junit.Rule;
import org.junit.Test;

@RunsInEdt
public class XmlTagUtilTest {
  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  @Test
  public void testCreateTag() throws Exception {
    XmlTag result = XmlTagUtil.createTag(projectRule.getProject(), "<LinearLayout" +
                                                       "  attr1=\"value1\"" +
                                                       "  attr2=\"2\" />");
    assertEquals("LinearLayout", result.getName());

    result = XmlTagUtil.createTag(projectRule.getProject(), "not-xml");
    assertEquals("<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
                 "  android:text=\"not-xml\" android:layout_width=\"wrap_content\"" +
                 " android:layout_height=\"wrap_content\"/>", result.getText());
  }

  @Test
  public void testSetNamespaceUri() throws Exception {
    XmlTag tag = XmlTagUtil.createTag(projectRule.getProject(), "<LinearLayout android:attr1=\"value1\" />");
    XmlTagUtil.setNamespaceUri(tag, "android", "url");
    assertEquals("<LinearLayout android:attr1=\"value1\" xmlns:android=\"url\"/>", tag.getText());
  }
}