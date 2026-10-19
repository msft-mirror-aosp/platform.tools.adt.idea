/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.adtui.stdui.menu

import com.android.testutils.ImageDiffUtil
import com.android.tools.adtui.model.stdui.CommonAction
import com.android.tools.adtui.swing.FakeUi
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import icons.StudioIcons
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import java.util.Arrays
import javax.swing.KeyStroke
import org.junit.Assert.assertThrows
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CommonDropDownButtonTest {
  companion object {
    @JvmField @ClassRule val appRule = ApplicationRule()
  }

  @Test
  fun testProperyChangedListeners() {
    val parent = CommonAction("Parent", null)
    val child1 = CommonAction("Child1", null)
    val child2 = CommonAction("Child2", null)
    val grandChild1 = CommonAction("Grandchild1", null)
    parent.addChildrenActions(child1, child2)
    child1.addChildrenActions(grandChild1)

    var actions = Arrays.asList(parent, child1, child2, grandChild1)
    for (action in actions) {
      assertThat<PropertyChangeListener>(action.propertyChangeListeners).asList().isEmpty()
    }

    // Listeners should be hooked up after creating the dropdown.
    val dropdown = CommonDropDownButton(parent)
    for (action in actions) {
      assertThat<PropertyChangeListener>(action.propertyChangeListeners).asList().containsExactly(dropdown)
    }

    // Modifying the first child should clear listeners on grandChild1
    child1.clear()
    assertThat<PropertyChangeListener>(grandChild1.propertyChangeListeners).asList().isEmpty()

    // Modifying the second child should add listeners for grandChild2
    val grandChild2 = CommonAction("Grandchild2", null)
    child2.addChildrenActions(grandChild2)
    actions = Arrays.asList(parent, child1, child2, grandChild2)
    for (action in actions) {
      assertThat<PropertyChangeListener>(action.propertyChangeListeners).asList().containsExactly(dropdown)
    }
  }

  @Test
  fun testFindMenuRecursive() {
    val parent = CommonAction("Parent", null)
    val child = CommonAction("Child", null)
    parent.addChildrenActions(child)
    val grandChild = CommonAction("GrandChild", null)
    child.addChildrenActions(grandChild)
    val dropdown = CommonDropDownButton(parent)

    // Note - we cannot trigger the popup to show in a headless test.
    // here we trigger a propertyChanged event on the parent which forces the popup to populate
    val child2 = CommonAction("Child2", null)
    parent.addChildrenActions(child2)

    val popup = dropdown.popup
    val childMenu = dropdown.findMenuRecursive(popup, child)!!
    assertThat(childMenu.action).isEqualTo(child)
    val grandChildMenu = dropdown.findMenuRecursive(popup, grandChild)!!
    assertThat(grandChildMenu.action).isEqualTo(grandChild)

    val child3 = CommonAction("Child2", null)
    var childMenu3 = dropdown.findMenuRecursive(popup, child3)
    assertThat(childMenu3).isNull()
    parent.addChildrenActions(child3)
    childMenu3 = dropdown.findMenuRecursive(popup, child3)!!
    assertThat(childMenu3.action).isEqualTo(child3)
  }

  @Test
  fun testMenuAndMenuItemLayoutAndPaint() {
    val menuAction = CommonAction("Submenu", StudioIcons.Common.FILTER)
    menuAction.isSelected = true
    val leafAction = CommonAction("Item", StudioIcons.Common.ADD)
    leafAction.isSelected = true
    menuAction.addChildrenActions(leafAction)

    val popup = CommonPopupMenu()
    val menu = CommonMenu(menuAction)
    val menuItem = CommonMenuItem(leafAction)
    menuItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK)
    popup.add(menu)
    popup.add(menuItem)

    val menuPrefSize = menu.preferredSize
    assertThat(menuPrefSize.width).isGreaterThan(0)
    assertThat(menuPrefSize.height).isGreaterThan(0)

    val itemPrefSize = menuItem.preferredSize
    assertThat(itemPrefSize.width).isGreaterThan(0)
    assertThat(itemPrefSize.height).isGreaterThan(0)

    menu.setBounds(0, 0, menuPrefSize.width, menuPrefSize.height)
    menuItem.setBounds(0, 0, itemPrefSize.width, itemPrefSize.height)

    val menuUi = FakeUi(menu)
    val unarmedMenuImage = menuUi.render()
    assertThat(unarmedMenuImage.width).isEqualTo(menuPrefSize.width)
    assertThat(unarmedMenuImage.height).isEqualTo(menuPrefSize.height)

    menu.isSelected = true
    val armedMenuImage = menuUi.render()
    assertThrows(AssertionError::class.java) { assertSameImage(unarmedMenuImage, armedMenuImage) }

    menu.isSelected = false
    assertSameImage(unarmedMenuImage, menuUi.render())

    val menuItemUi = FakeUi(menuItem)
    val unarmedItemImage = menuItemUi.render()
    assertThat(unarmedItemImage.width).isEqualTo(itemPrefSize.width)
    assertThat(unarmedItemImage.height).isEqualTo(itemPrefSize.height)

    menuItem.isArmed = true
    val armedItemImage = menuItemUi.render()
    assertThrows(AssertionError::class.java) { assertSameImage(unarmedItemImage, armedItemImage) }

    menuItem.isArmed = false
    assertSameImage(unarmedItemImage, menuItemUi.render())
  }
}

private fun assertSameImage(image1: BufferedImage, image2: BufferedImage) {
  ImageDiffUtil.assertImageSimilar("menu", image1, image2, 0.0, 0)
}
