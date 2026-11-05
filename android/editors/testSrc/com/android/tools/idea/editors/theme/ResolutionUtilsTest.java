/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.editors.theme;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertEquals;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValueImpl;
import com.android.ide.common.resources.ResourceResolver;
import com.android.resources.ResourceType;
import com.android.tools.configurations.Configuration;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.RunsInEdt;
import org.jetbrains.android.AndroidTestBase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@RunsInEdt
public class ResolutionUtilsTest {
  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  @Before
  public void setUp() {
    projectRule.getFixture().setTestDataPath(AndroidTestBase.getTestDataPath());
  }

  /*
   * The test SDK only includes some resources. It only includes a few incomplete styles.
   */

  /**
   * Tests {@link ResolutionUtils#getQualifiedNameFromResourceUrl(String)}
   */
  @Test
  public void testGetQualifiedNameFromResourceUrl() {
    assertEquals("android:Theme", ResolutionUtils.getQualifiedNameFromResourceUrl("@android:style/Theme"));
    assertEquals("namespace:Theme", ResolutionUtils.getQualifiedNameFromResourceUrl("@namespace:style/Theme"));
    assertEquals("AppTheme", ResolutionUtils.getQualifiedNameFromResourceUrl("@style/AppTheme"));
    // TODO get it to also work for theme attr urls
    // for theme attributes, the "attr/" is optional: http://developer.android.com/guide/topics/resources/accessing-resources.html
  }

  @Test
  public void testGetOriginalApiLevel() {
    assertEquals(-1, ResolutionUtils.getOriginalApiLevel(null, projectRule.getProject()));

    // Testing Api of an attribute name
    assertEquals(21, ResolutionUtils.getOriginalApiLevel("android:colorAccent", projectRule.getProject()));
    assertEquals(-1, ResolutionUtils.getOriginalApiLevel("colorAccent", projectRule.getProject()));

    // Testing Api of an attribute value
    assertEquals(14, ResolutionUtils.getOriginalApiLevel("@android:color/holo_green_dark", projectRule.getProject()));
    assertEquals(21, ResolutionUtils.getOriginalApiLevel("?android:attr/colorAccent", projectRule.getProject()));
    assertEquals(3, ResolutionUtils.getOriginalApiLevel("@android:integer/config_longAnimTime", projectRule.getProject()));
    assertEquals(4, ResolutionUtils.getOriginalApiLevel("@android:drawable/stat_sys_vp_phone_call", projectRule.getProject()));
    assertEquals(1, ResolutionUtils.getOriginalApiLevel("@android:color/black", projectRule.getProject()));
    assertEquals(-1, ResolutionUtils.getOriginalApiLevel("?attr/colorAccent", projectRule.getProject()));
    assertEquals(-1, ResolutionUtils.getOriginalApiLevel("@color/holo_green_dark", projectRule.getProject()));
  }
}
