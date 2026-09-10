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
package com.android.tools.idea.uibuilder.editor

import com.android.SdkConstants
import com.android.tools.adtui.TreeWalker
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.loadNewFile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.runInEdtAndGet
import org.intellij.lang.annotations.Language
import org.jetbrains.android.facet.AndroidFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

class AnimatedSelectorToolbarTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Language("XML")
  private val animatedSelectorContent =
    """
    <animated-selector xmlns:android="http://schemas.android.com/apk/res/android">
        <item android:id="@+id/state_off" android:drawable="@drawable/ic_off" android:state_selected="false" />
        <item android:id="@+id/state_on" android:drawable="@drawable/ic_on" android:state_selected="true" />
        <transition
            android:fromId="@id/state_off"
            android:toId="@id/state_on">
            <animated-vector android:drawable="@drawable/ic_off">
                <target
                    android:name="path"
                    android:animation="@anim/rotation" />
            </animated-vector>
        </transition>
        <transition
            android:fromId="@id/state_on"
            android:toId="@id/state_off">
            <animation-list android:oneshot="true">
                <item android:drawable="@drawable/ic_on" android:duration="100" />
                <item android:drawable="@drawable/ic_off" android:duration="100" />
            </animation-list>
        </transition>
    </animated-selector>
    """
      .trimIndent()

  @Test
  fun testAnimatedSelectorModelAndToolbarOnEdt() {
    val file = projectRule.fixture.loadNewFile("res/drawable/my_selector.xml", animatedSelectorContent)
    val virtualFile = file.virtualFile
    val facet = AndroidFacet.getInstance(projectRule.module)!!
    val buildTarget = AndroidBuildTargetReference.gradleOnly(facet)
    val config = ConfigurationManager.getOrCreateInstance(projectRule.module).getConfiguration(virtualFile)

    val model =
      WriteCommandAction.runWriteCommandAction(
        projectRule.project,
        Computable {
          AnimatedSelectorModel(
            virtualFile,
            projectRule.testRootDisposable,
            projectRule.project,
            buildTarget,
            {},
            config,
          )
        },
      )

    val options = model.getPreviewOption()
    assertEquals(setOf("Select Transition...", "state_off to state_on", "state_on to state_off"), options)
    assertEquals(SdkConstants.TAG_ANIMATED_VECTOR, model.getPreviewOptionTagName("state_off to state_on"))
    assertEquals(SdkConstants.TAG_ANIMATION_LIST, model.getPreviewOptionTagName("state_on to state_off"))

    val toolbar = runInEdtAndGet {
      AnimatedSelectorToolbar.createToolbar(
        projectRule.testRootDisposable,
        model,
        mock(),
        16L,
        0L,
      )
    }

    // Changing preview option on EDT should succeed without requiring read action
    runInEdtAndGet {
      val comboBox = TreeWalker(toolbar).descendants().filterIsInstance<ComboBox<*>>().firstOrNull()
      assertNotNull(comboBox)
      comboBox!!.selectedItem = "state_off to state_on"
      assertEquals("state_off to state_on", comboBox.selectedItem)
      assertTrue(toolbar.isTransitionSelected())

      comboBox.selectedItem = "state_on to state_off"
      assertEquals("state_on to state_off", comboBox.selectedItem)
      assertTrue(toolbar.isTransitionSelected())

      toolbar.setNoTransition()
      assertEquals("Select Transition...", comboBox.selectedItem)
    }
  }
}
