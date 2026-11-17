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
package com.android.tools.idea.layoutinspector.properties.backstack

import com.android.tools.adtui.swing.FakeKeyboardFocusManager
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HiDpiRule
import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.model
import com.android.tools.idea.layoutinspector.model.ROOT
import com.android.tools.idea.layoutinspector.model.SelectionOrigin
import com.android.tools.idea.layoutinspector.model.VIEW1
import com.android.tools.idea.layoutinspector.model.VIEW2
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterGroupItem
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterItem
import com.android.tools.idea.layoutinspector.properties.InspectorPropertiesModel
import com.android.tools.idea.layoutinspector.properties.InspectorPropertyItem
import com.android.tools.idea.layoutinspector.properties.PropertySection
import com.android.tools.idea.layoutinspector.properties.PropertyType
import com.android.tools.idea.layoutinspector.tree.createCenterTextPanel
import com.android.tools.property.panel.api.PropertiesTable
import com.google.common.collect.HashBasedTable
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.IdeTooltipManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.HintHint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleState
import javax.swing.CellRendererPane
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.LayoutFocusTraversalPolicy
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.basic.BasicHTML
import javax.swing.plaf.basic.BasicLabelUI
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/** UI tests for [BackStackPanel], verifying rendering, visibility, filtering, keyboard navigation, focus and accessibility. */
class BackStackPanelTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()
  @get:Rule val hiDpiRule = HiDpiRule()

  @Test
  fun testBackStackPanelRendersEntries() {
    val model = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail"))
    val panel = BackStackPanel(model)

    assertThat(panel.backStackList).containsExactly("Home", "Detail").inOrder()
    // The top (current) entry is first, with a badge
    assertThat(panel.list.renderedLabels()).containsExactly(listOf("Detail", "Current"), listOf("Home")).inOrder()
  }

  @Test
  fun testBackStackPanelVisibilityOnSelection() {
    val inspectorModel =
      model(disposableRule.disposable) {
        view(ROOT) {
          view(VIEW1, qualifiedName = "androidx.compose.material3.Button")
          view(VIEW2, qualifiedName = "androidx.navigation3.NavDisplay")
        }
      }
    val layoutInspector: LayoutInspector = mock()
    whenever(layoutInspector.inspectorModel).thenReturn(inspectorModel)

    val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply {
        this.layoutInspector = layoutInspector
      }

    val panel = BackStackPanel(propertiesModel)
    panel.setToolContext(layoutInspector)
    assertThat(panel.isVisible).isFalse()

    inspectorModel.setSelection(inspectorModel[VIEW1], SelectionOrigin.COMPONENT_TREE)
    assertThat(panel.isVisible).isFalse()

    inspectorModel.setSelection(inspectorModel[VIEW2], SelectionOrigin.COMPONENT_TREE)
    assertThat(panel.isVisible).isTrue()

    inspectorModel.setSelection(null, SelectionOrigin.INTERNAL)
    assertThat(panel.isVisible).isFalse()
  }

  @Test
  fun testBackStackPanelFiltering() {
    val model = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail"))
    val panel = BackStackPanel(model)
    val list = panel.list

    panel.setFilter("Det")
    assertThat(panel.filter).isEqualTo("Det")
    assertThat(list.renderedLabels()).containsExactly(listOf("Detail", "Current"))

    // Filter with non-matching term to verify empty state message is shown, and exposed to screen readers
    panel.setFilter("NonExistentRoute")
    assertThat(panel.filter).isEqualTo("NonExistentRoute")
    assertThat(list.model.size).isEqualTo(0)
    assertThat(list.accessibleContext.accessibleDescription).isEqualTo("No matching back stack entries found.")

    // Reset filter
    panel.setFilter("")
    assertThat(panel.filter).isEmpty()
    assertThat(list.renderedLabels()).containsExactly(listOf("Detail", "Current"), listOf("Home")).inOrder()
    assertThat(list.accessibleContext.accessibleDescription).isNull()
  }

  @Test
  fun testBackStackPanelEmptyBackStack() {
    val panel = BackStackPanel(BackStackPanelModel())
    val list = panel.list

    assertThat(list.model.size).isEqualTo(0)
    assertThat(list.accessibleContext.accessibleDescription).isEqualTo("No navigation back stack found.")
  }

  @Test
  fun testEmptyMessageIsWrappedInNarrowPanels() = runInEdtAndWait {
    val message = "No navigation back stack found."
    val wideFixture = createUi(emptyList(), width = 400)
    val narrowFixture = createUi(emptyList(), width = 110)

    // Displayed like the other Layout Inspector empty states (e.g. the properties panel without selection)
    for (fixture in listOf(wideFixture, narrowFixture)) {
      val content = fixture.scrollPaneContent
      val expectedPanel =
        JPanel(BorderLayout()).apply {
          background = content.background
          add(createCenterTextPanel(listOf(message)).apply { isOpaque = false })
          size = content.size
        }
      val expectedUi = FakeUi(expectedPanel, parentDisposable = disposableRule.disposable)
      expectedUi.layout()
      assertThat(fixture.ui.render(content).differenceBounds(expectedUi.render())).isNull()
    }

    // The message fits on one line in the wide panel, and is wrapped on more lines in the narrow one
    val wideTextBounds = wideFixture.renderedTextBounds()
    val narrowTextBounds = narrowFixture.renderedTextBounds()
    assertThat(narrowTextBounds.height).isAtLeast(2 * wideTextBounds.height)
    assertThat(narrowTextBounds.width).isLessThan(narrowFixture.scrollPaneContent.width)

    // The message is wrapped again when the panel is resized
    wideFixture.ui.root.size = Dimension(110, wideFixture.ui.root.height)
    wideFixture.ui.layout()
    assertThat(wideFixture.renderedTextBounds()).isEqualTo(narrowTextBounds)

    // The message is hidden when there are entries
    wideFixture.setBackStack(listOf("Home"))
    assertThat(UIUtil.findComponentsOfType(wideFixture.scrollPaneContent, JEditorPane::class.java).none { it.isShowing }).isTrue()
  }

  @Test
  fun testBackStackPanelDuplicateRoutesActiveState() {
    val model = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail", "Home"))
    val panel = BackStackPanel(model)

    assertThat(panel.backStackList).containsExactly("Home", "Detail", "Home").inOrder()
    // Only the top entry is current, even though the oldest entry has the same route
    assertThat(panel.list.renderedLabels()).containsExactly(listOf("Home", "Current"), listOf("Detail"), listOf("Home")).inOrder()
    assertThat(panel.list.routeLabel(0).font.isBold).isTrue()
    assertThat(panel.list.routeLabel(2).font.isBold).isFalse()
  }

  @Test
  fun testRoutesAreRenderedAsPlainText() {
    val routes = listOf("<html><b>Bold</b></html>", "Search(query=\"a&b\", type=Route<String>)")
    val panel = BackStackPanel(BackStackPanelModel(initialBackStackList = routes))

    for ((row, route) in routes.asReversed().withIndex()) {
      val label = panel.list.routeLabel(row)
      assertThat(label.text).isEqualTo(route)
      // No HTML view: the text is painted as is, without interpreting tags or entities
      assertThat(label.getClientProperty(BasicHTML.propertyKey)).isNull()
    }
  }

  @Test
  fun testBackStackPanelRefreshesWhenReaddedToHierarchy() = runInEdtAndWait {
    // Render the panel initially attached to the Swing hierarchy with a single Home route
    val fixture = createUi(listOf("Home"))
    assertThat(fixture.list.renderedLabels()).containsExactly(listOf("Home", "Current"))

    // Remove the panel from its parent container to trigger removeNotify and detach its properties listener
    val parentContainer = fixture.panel.parent
    parentContainer.remove(fixture.panel)

    // Update the underlying properties table while the panel is detached from the component hierarchy
    fixture.updateBackStack(listOf("Home", "Detail", "Checkout(orderId=99)"), refresh = false)
    assertThat(fixture.panel.backStackList).containsExactly("Home")

    // Re-add the panel to its parent container to trigger addNotify and verify it displays the latest routes
    parentContainer.add(fixture.panel, BorderLayout.CENTER)
    fixture.ui.layout()

    assertThat(fixture.panel.backStackList).containsExactly("Home", "Detail", "Checkout(orderId=99)").inOrder()
    assertThat(fixture.list.renderedLabels())
      .containsExactly(listOf("Checkout(orderId=99)", "Current"), listOf("Detail"), listOf("Home"))
      .inOrder()
  }

  @Test
  fun testPropertiesEventsAreDeliveredOnlyWhileInHierarchy() = runInEdtAndWait {
    val fixture = createUi(listOf("Home"))
    val ancestorContainer = fixture.panel.parent
    // Flush the properties events queued while the fixture was created
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    assertThat(fixture.panel.backStackList).containsExactly("Home")

    // Remove the panel from its parent container to unregister its properties listener
    ancestorContainer.remove(fixture.panel)

    // Changing the selection empties the properties table and fires a properties event through invokeLater
    fixture.inspectorModel.setSelection(null, SelectionOrigin.COMPONENT_TREE)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    // The panel did not receive the event, so it still shows the old route
    assertThat(fixture.panel.backStackList).containsExactly("Home")

    // Re-add the panel and verify it picks up the empty properties table
    ancestorContainer.add(fixture.panel, BorderLayout.CENTER)
    fixture.ui.layout()

    assertThat(fixture.panel.backStackList).isEmpty()

    // Select the NavDisplay again and show new routes on the re-added panel
    fixture.inspectorModel.setSelection(fixture.inspectorModel[VIEW1], SelectionOrigin.COMPONENT_TREE)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    fixture.setBackStack(listOf("Home", "Detail"))

    // Fire a real properties event and verify the re-registered listener receives it
    fixture.inspectorModel.setSelection(null, SelectionOrigin.COMPONENT_TREE)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    assertThat(fixture.panel.backStackList).isEmpty()
  }

  @Test
  fun testListIsSingleTabStop() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Catalog", "Checkout(orderId=99)"))
    val list = fixture.list

    fixture.before.requestFocusInWindow()
    fixture.pressKey(KeyEvent.VK_TAB)
    assertThat(fixture.focusOwner).isSameAs(list)
    // The keyboard cursor is shown on the first entry as soon as the list is focused
    assertThat(list.selectedIndex).isEqualTo(0)

    fixture.pressKey(KeyEvent.VK_TAB)
    assertThat(fixture.focusOwner).isSameAs(fixture.after)

    fixture.pressKey(KeyEvent.VK_TAB, shift = true)
    assertThat(fixture.focusOwner).isSameAs(list)
    fixture.pressKey(KeyEvent.VK_TAB, shift = true)
    assertThat(fixture.focusOwner).isSameAs(fixture.before)
  }

  @Test
  fun testKeyboardNavigation() = runInEdtAndWait {
    // Small enough to require scrolling
    val fixture = createUi((1..8).map { "Route$it" }, height = 180)
    val list = fixture.list
    list.requestFocusInWindow()
    assertThat(list.selectedIndex).isEqualTo(0)

    val expectedRowAfterKey =
      listOf(
        KeyEvent.VK_DOWN to 1,
        KeyEvent.VK_KP_DOWN to 2,
        KeyEvent.VK_UP to 1,
        KeyEvent.VK_KP_UP to 0,
        KeyEvent.VK_END to 7,
        KeyEvent.VK_HOME to 0,
      )
    for ((key, expectedRow) in expectedRowAfterKey) {
      fixture.pressKey(key)
      assertThat(list.selectedIndex).isEqualTo(expectedRow)
      // The cursor is always scrolled into view
      assertThat(list.visibleRect.contains(list.getCellBounds(expectedRow, expectedRow))).isTrue()
    }

    fixture.pressKey(KeyEvent.VK_PAGE_DOWN)
    val rowAfterPageDown = list.selectedIndex
    assertThat(rowAfterPageDown).isGreaterThan(1)
    assertThat(list.visibleRect.contains(list.getCellBounds(rowAfterPageDown, rowAfterPageDown))).isTrue()
    fixture.pressKey(KeyEvent.VK_PAGE_UP)
    assertThat(list.selectedIndex).isEqualTo(0)

    // The cursor can't be removed
    fixture.pressKey(KeyEvent.VK_DOWN)
    fixture.pressKey(KeyEvent.VK_SPACE, ctrl = true)
    assertThat(list.selectedIndex).isEqualTo(1)
    fixture.pressKey(KeyEvent.VK_BACK_SLASH, ctrl = true)
    assertThat(list.selectedIndex).isEqualTo(1)
    assertThat(fixture.focusOwner).isSameAs(list)
  }

  @Test
  fun testFocusRingIsOnlyShownOnKeyboardCursorWhileFocused() = runInEdtAndWait {
    // Fractional scale, where rounding could make the insets differ if the ring was part of the border
    hiDpiRule.setUserScale(1.25f)
    val fixture = createUi(listOf("Home", "Catalog", "Checkout(orderId=99)"))
    val list = fixture.list
    val cellBounds = list.allCellBounds()
    val initialImage = fixture.renderPanel()

    // The cursor is not visible while the list is not focused
    list.selectedIndex = 1
    assertThat(fixture.renderPanel().differenceBounds(initialImage)).isNull()

    // A focus ring is painted on the card of the cursor entry only, without changing the layout
    list.requestFocusInWindow()
    assertThat(fixture.focusOwner).isSameAs(list)
    val focusedImage = fixture.renderPanel()
    val ringBounds = focusedImage.differenceBounds(initialImage)
    assertThat(ringBounds).isNotNull()
    val cardBounds = SwingUtilities.convertRectangle(list, list.getCellBounds(1, 1), fixture.panel)
    cardBounds.x += JBUI.scale(8)
    cardBounds.width -= 2 * JBUI.scale(8)
    cardBounds.height -= JBUI.scale(4)
    assertThat(cardBounds.contains(ringBounds)).isTrue()
    assertThat(list.allCellBounds()).isEqualTo(cellBounds)
    val unfocusedCard = list.renderCell(1, cellHasFocus = false).card
    val unfocusedInsets = unfocusedCard.insets
    val unfocusedSize = unfocusedCard.preferredSize
    val focusedCard = list.renderCell(1, cellHasFocus = true).card
    assertThat(focusedCard.insets).isEqualTo(unfocusedInsets)
    assertThat(focusedCard.preferredSize).isEqualTo(unfocusedSize)

    // Only the cursor has a ring, even when other cells are rendered with cellHasFocus (as ListExpandableItemsHandler does)
    assertThat(list.paintCell(0, cellHasFocus = true).differenceBounds(list.paintCell(0, cellHasFocus = false))).isNull()
    assertThat(list.paintCell(1, cellHasFocus = true).differenceBounds(list.paintCell(1, cellHasFocus = false))).isNotNull()

    // The ring also shows on the current entry, and follows the cursor
    fixture.pressKey(KeyEvent.VK_UP)
    val ringOnCurrentBounds = fixture.renderPanel().differenceBounds(initialImage)
    assertThat(ringOnCurrentBounds).isNotNull()
    assertThat(ringOnCurrentBounds!!.y).isLessThan(ringBounds!!.y)

    // The ring disappears when the focus leaves the list
    fixture.focusManager.setFocusOwner(fixture.after)
    assertThat(fixture.renderPanel().differenceBounds(initialImage)).isNull()
  }

  @Test
  fun testCursorStaysOnSameEntryWhenBackStackChanges() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Detail", "Home"))
    val list = fixture.list
    val focusEvents = mutableListOf<String>()
    fixture.recordFocusEvents(focusEvents)
    list.requestFocusInWindow()
    focusEvents.clear()

    // Rows are in reverse chronological order, so the row of the oldest entry is the last one.
    // Move the cursor to the oldest "Home" entry
    fixture.pressKey(KeyEvent.VK_END)
    assertThat(list.cursor()).isEqualTo(2 to "Home")

    // Push: the rows move down, the cursor stays on the same entry rather than on the same row or the first row with the same route
    fixture.setBackStack(listOf("Home", "Detail", "Home", "Settings"))
    assertThat(list.cursor()).isEqualTo(3 to "Home")

    // Pop
    fixture.setBackStack(listOf("Home", "Detail"))
    assertThat(list.cursor()).isEqualTo(1 to "Home")

    // Popping the entry under the cursor moves the cursor to the closest remaining entry, i.e. the new top entry
    fixture.pressKey(KeyEvent.VK_HOME)
    assertThat(list.cursor()).isEqualTo(0 to "Detail")
    fixture.setBackStack(listOf("Home"))
    assertThat(list.cursor()).isEqualTo(0 to "Home")

    // Filtering out the entry under the cursor moves the cursor to the closest entry, preferring the older one
    fixture.setBackStack(listOf("Home", "Detail", "Home"))
    assertThat(list.cursor()).isEqualTo(2 to "Home")
    fixture.pressKey(KeyEvent.VK_UP)
    assertThat(list.cursor()).isEqualTo(1 to "Detail")
    fixture.panel.setFilter("Home")
    assertThat(list.cursor()).isEqualTo(1 to "Home")
    fixture.panel.setFilter("")
    assertThat(list.cursor()).isEqualTo(2 to "Home")

    // The list kept the focus during all the updates
    assertThat(fixture.focusOwner).isSameAs(list)
    assertThat(focusEvents).isEmpty()
  }

  @Test
  fun testEmptyListKeepsFocus() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Detail"))
    val list = fixture.list
    val focusEvents = mutableListOf<String>()
    fixture.recordFocusEvents(focusEvents)
    list.requestFocusInWindow()
    focusEvents.clear()

    fixture.panel.setFilter("NonExistentRoute")
    assertThat(list.model.size).isEqualTo(0)
    assertThat(list.accessibleContext.accessibleDescription).isEqualTo("No matching back stack entries found.")
    assertThat(fixture.focusOwner).isSameAs(list)

    fixture.panel.setFilter("")
    fixture.setBackStack(emptyList())
    assertThat(list.model.size).isEqualTo(0)
    assertThat(list.accessibleContext.accessibleDescription).isEqualTo("No navigation back stack found.")
    assertThat(fixture.focusOwner).isSameAs(list)

    // The cursor appears on the first entry when entries are added to the focused list
    fixture.setBackStack(listOf("Home", "Detail"))
    assertThat(list.cursor()).isEqualTo(0 to "Detail")
    assertThat(fixture.focusOwner).isSameAs(list)
    assertThat(focusEvents).isEmpty()

    // Once the focus leaves the empty list, the list is no longer a Tab stop
    fixture.setBackStack(emptyList())
    assertThat(fixture.focusOwner).isSameAs(list)
    fixture.pressKey(KeyEvent.VK_TAB)
    assertThat(fixture.focusOwner).isSameAs(fixture.after)
    fixture.pressKey(KeyEvent.VK_TAB, shift = true)
    assertThat(fixture.focusOwner).isSameAs(fixture.before)
  }

  @Test
  fun testEmptyListGetsFocusBackWhenWindowIsActivated() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Detail"))
    val list = fixture.list
    list.requestFocusInWindow()

    // The window is deactivated, the list empties, then the window is activated again.
    // FakeUi windows are always focused mocks: return the list as the most recent focus owner, as Window does when it isn't focused.
    fixture.focusManager.setFocusOwner(null, temporary = true)
    whenever(SwingUtilities.getWindowAncestor(list).mostRecentFocusOwner).thenReturn(list)
    fixture.setBackStack(emptyList())
    assertThat(list.requestFocusInWindow()).isTrue()
    assertThat(fixture.focusOwner).isSameAs(list)
  }

  @Test
  fun testEmptyListIsNotTabStop() = runInEdtAndWait {
    val fixture = createUi(emptyList())

    fixture.before.requestFocusInWindow()
    fixture.pressKey(KeyEvent.VK_TAB)
    assertThat(fixture.focusOwner).isSameAs(fixture.after)

    // It is a Tab stop again when there are entries
    fixture.setBackStack(listOf("Home"))
    fixture.pressKey(KeyEvent.VK_TAB, shift = true)
    assertThat(fixture.focusOwner).isSameAs(fixture.list)
    assertThat(fixture.list.cursor()).isEqualTo(0 to "Home")
  }

  @Test
  fun testAccessibility() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Catalog", "Checkout(orderId=99)"))
    val list = fixture.list
    list.requestFocusInWindow()
    fixture.pressKey(KeyEvent.VK_DOWN)

    assertThat(list.accessibleContext.dump())
      .isEqualTo(
        """
        list "Back stack" [selected children: 1]
          list item "Checkout(orderId=99), current"
          list item "Catalog" [selected]
          list item "Home"
        """
          .trimIndent()
      )

    fixture.panel.setFilter("NonExistentRoute")
    assertThat(list.accessibleContext.dump()).isEqualTo("""list "Back stack" (No matching back stack entries found.)""")
    // The message is also a text in the panel
    val messageText = UIUtil.findComponentOfType(fixture.panel, JEditorPane::class.java)!!.accessibleContext.accessibleEditableText
    assertThat(messageText.getTextRange(0, messageText.charCount).trim()).isEqualTo("No matching back stack entries found.")
  }

  @Test
  fun testClickBelowEntriesDoesNothing() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Detail"))
    val list = fixture.list
    list.requestFocusInWindow()
    assertThat(list.cursor()).isEqualTo(0 to "Detail")

    val lastCell = list.getCellBounds(1, 1)
    val belowEntries = SwingUtilities.convertPoint(list, 20, lastCell.y + lastCell.height + 20, fixture.ui.root)
    fixture.ui.mouse.click(belowEntries.x, belowEntries.y)
    assertThat(list.cursor()).isEqualTo(0 to "Detail")
    assertThat(fixture.focusOwner).isSameAs(list)
  }

  @Test
  fun testTooltipShowsFullRouteOnlyWhenTruncated() = runInEdtAndWait {
    val longRoute = "ProductDetail(productId=1234567890, query=\"a&b\", type=Route<String>, highlight=true)"
    val fixture = createUi(listOf("Home", longRoute))
    val list = fixture.list

    val tooltip = list.tooltipAtRow(0)
    assertThat(tooltip)
      .isEqualTo("<html>ProductDetail(productId=1234567890, query=&quot;a&amp;b&quot;, type=Route&lt;String&gt;, highlight=true)</html>")
    // The IDE renders tooltips as HTML: the escaped route must be displayed as is
    val tooltipPane = IdeTooltipManager.initPane(tooltip, HintHint(), null)
    assertThat(tooltipPane.document.getText(0, tooltipPane.document.length).trim()).isEqualTo(longRoute)
    // The expanded item popup would duplicate the tooltip
    assertThat(list.expandableItemsHandler.isEnabled).isFalse()

    // No tooltip for routes that are fully visible, or outside of the cells
    assertThat(list.tooltipAtRow(1)).isNull()
    val belowCells = list.getCellBounds(1, 1).let { MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0, 20, it.maxY.toInt() + 5, 0, false) }
    assertThat(list.getToolTipText(belowCells)).isNull()
  }

  @Test
  fun testCopySelectedRoute() = runInEdtAndWait {
    val fixture = createUi(listOf("Home", "Search(query=\"a&b\")"))
    val list = fixture.list
    list.requestFocusInWindow()

    fixture.pressKey(KeyEvent.VK_C, ctrl = true)
    assertThat(CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)).isEqualTo("Search(query=\"a&b\")")
  }

  @Test
  fun testRendererFollowsLookAndFeelChanges() {
    val panel = BackStackPanel(BackStackPanelModel(initialBackStackList = listOf("Home", "Detail")))
    val list = panel.list
    val routeLabel = list.routeLabel(1)
    assertThat(routeLabel.ui.javaClass).isNotEqualTo(BasicLabelUI::class.java)

    // The renderer components are updated with the rest of the UI, as done by the IDE on theme changes
    withLookAndFeelDefault("LabelUI", BasicLabelUI::class.java.name) {
      SwingUtilities.updateComponentTreeUI(panel)
      assertThat(list.routeLabel(1)).isSameAs(routeLabel)
      assertThat(routeLabel.ui.javaClass).isEqualTo(BasicLabelUI::class.java)
    }

    // Theme colors are read every time a cell is rendered
    withLookAndFeelDefault("TextField.background", ColorUIResource(Color.MAGENTA)) {
      assertThat(list.renderCell(1).card.background).isEqualTo(Color.MAGENTA)
    }
  }

  /** Overrides a look and feel default while running [block], without leaving it in the developer defaults afterwards. */
  private fun withLookAndFeelDefault(key: String, value: Any, block: () -> Unit) {
    val defaults = UIManager.getLookAndFeelDefaults()
    val originalValue = defaults[key]
    defaults[key] = value
    try {
      block()
    } finally {
      defaults[key] = originalValue
    }
  }

  private val BackStackPanel.list: JBList<*>
    get() = UIUtil.findComponentOfType(this, JBList::class.java)!!

  /** Renders the cell of [row] as the list would paint it. */
  private fun JBList<*>.renderCell(row: Int, cellHasFocus: Boolean = false): JComponent {
    @Suppress("UNCHECKED_CAST") val list = this as JBList<Any?>
    @Suppress("UNCHECKED_CAST") val renderer = list.cellRenderer as ListCellRenderer<Any?>
    return renderer.getListCellRendererComponent(list, list.model.getElementAt(row), row, list.isSelectedIndex(row), cellHasFocus)
      as JComponent
  }

  /** Paints the cell of [row] at its current size in the list. */
  private fun JBList<*>.paintCell(row: Int, cellHasFocus: Boolean): BufferedImage {
    val bounds = getCellBounds(row, row)
    val cell = renderCell(row, cellHasFocus)
    val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    CellRendererPane().apply { add(cell) }.paintComponent(graphics, cell, this, 0, 0, bounds.width, bounds.height, true)
    graphics.dispose()
    return image
  }

  /** Returns an image of the size of this component, filled with its background. */
  private fun JComponent.backgroundImage(): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.color = background
    graphics.fillRect(0, 0, width, height)
    graphics.dispose()
    return image
  }

  private val JComponent.card: JComponent
    get() = getComponent(0) as JComponent

  /** Returns the texts of the visible labels of each rendered row. */
  private fun JBList<*>.renderedLabels(): List<List<String>> =
    (0 until model.size).map { row ->
      UIUtil.findComponentsOfType(renderCell(row), JBLabel::class.java).filter { it.isVisible }.map { it.text }
    }

  private fun JBList<*>.routeLabel(row: Int): JBLabel = UIUtil.findComponentsOfType(renderCell(row), JBLabel::class.java).first()

  /** Returns the row and the route of the entry under the keyboard cursor. */
  private fun JBList<*>.cursor(): Pair<Int, String?> = selectedIndex to selectedValue?.toString()

  private fun JBList<*>.allCellBounds(): List<Rectangle> = (0 until model.size).map { getCellBounds(it, it) }

  private fun JBList<*>.tooltipAtRow(row: Int): String? {
    val bounds = getCellBounds(row, row)
    return getToolTipText(MouseEvent(this, MouseEvent.MOUSE_MOVED, 0, 0, bounds.x + 20, bounds.y + 5, 0, false))
  }

  /** Returns the bounds of the pixels that differ between this image and [other], or null if they are identical. */
  private fun BufferedImage.differenceBounds(other: BufferedImage): Rectangle? {
    assertThat(width to height).isEqualTo(other.width to other.height)
    var bounds: Rectangle? = null
    for (x in 0 until width) {
      for (y in 0 until height) {
        if (getRGB(x, y) != other.getRGB(x, y)) {
          bounds = bounds?.apply { add(Rectangle(x, y, 1, 1)) } ?: Rectangle(x, y, 1, 1)
        }
      }
    }
    return bounds
  }

  /** Dumps the accessible role, name, description, selection state of this context and its children. */
  private fun AccessibleContext.dump(indent: String = ""): String {
    val description = accessibleDescription?.let { " ($it)" } ?: ""
    val selectedChildren = accessibleSelection?.accessibleSelectionCount?.takeIf { it > 0 }?.let { " [selected children: $it]" } ?: ""
    val selected = if (accessibleStateSet.contains(AccessibleState.SELECTED)) " [selected]" else ""
    val line = "$indent$accessibleRole \"$accessibleName\"$description$selectedChildren$selected"
    val children = (0 until accessibleChildrenCount).map { getAccessibleChild(it).accessibleContext.dump("$indent  ") }
    return (listOf(line) + children).joinToString("\n")
  }

  private fun createUi(backStack: List<String>, width: Int = 260, height: Int = 200): Fixture = Fixture(backStack, width, height)

  /** A visible [BackStackPanel] between two buttons, backed by the properties of a selected NavDisplay, with a fake focus manager. */
  private inner class Fixture(backStack: List<String>, width: Int, height: Int) {
    val inspectorModel = model(disposableRule.disposable) { view(ROOT) { view(VIEW1, qualifiedName = "androidx.navigation3.NavDisplay") } }
    private val layoutInspector: LayoutInspector = mock<LayoutInspector>().also { whenever(it.inspectorModel).thenReturn(inspectorModel) }
    private val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply { layoutInspector = this@Fixture.layoutInspector }
    val focusManager = FakeKeyboardFocusManager(disposableRule.disposable)
    val panel = BackStackPanel(propertiesModel)
    val before = JButton("Before")
    val after = JButton("After")
    val ui: FakeUi

    init {
      inspectorModel.setSelection(inspectorModel[VIEW1], SelectionOrigin.COMPONENT_TREE)
      panel.setToolContext(layoutInspector)
      updateBackStack(backStack)
      assertThat(panel.isVisible).isTrue()
      val root =
        JPanel(BorderLayout()).apply {
          add(before, BorderLayout.NORTH)
          add(panel, BorderLayout.CENTER)
          add(after, BorderLayout.SOUTH)
          size = Dimension(width, height)
          // Use the Swing focus traversal policy, as in the IDE. Otherwise, the policy of the fake window depends on the tests that ran
          // before.
          isFocusCycleRoot = true
          focusTraversalPolicy = LayoutFocusTraversalPolicy()
        }
      // FakeUi windows can't provide the graphics needed to blit the viewport content when scrolling
      UIUtil.findComponentOfType(panel, JViewport::class.java)!!.scrollMode = JViewport.SIMPLE_SCROLL_MODE
      ui = FakeUi(root, parentDisposable = disposableRule.disposable)
      focusManager.setActiveWindow(SwingUtilities.getWindowAncestor(root))
      ui.layout()
    }

    val list: JBList<*>
      get() = panel.list

    val focusOwner
      get() = focusManager.focusOwner

    fun renderPanel(): BufferedImage = ui.render(panel)

    /** The content of the scroll pane: the list, and the empty state message. */
    val scrollPaneContent: JComponent
      get() = UIUtil.findComponentOfType(panel, JViewport::class.java)!!.view as JComponent

    /** Returns the bounds of what is painted over the background of the scroll pane content. */
    fun renderedTextBounds(): Rectangle = ui.render(scrollPaneContent).differenceBounds(scrollPaneContent.backgroundImage())!!

    /** Updates the back stack of the selected NavDisplay, as the inspector does when the app navigates. */
    fun setBackStack(routes: List<String>) {
      updateBackStack(routes)
      ui.layout()
    }

    fun updateBackStack(routes: List<String>, refresh: Boolean = true) {
      val entries = routes.mapIndexed { index, route ->
        ParameterItem("$index", PropertyType.STRING, route, PropertySection.PARAMETERS, VIEW1, inspectorModel, VIEW1, index)
      }
      val backStack =
        ParameterGroupItem(
          name = "backStack",
          type = PropertyType.ITERABLE,
          value = "List[${routes.size}]",
          section = PropertySection.PARAMETERS,
          viewId = VIEW1,
          lookup = inspectorModel,
          rootId = VIEW1,
          index = 0,
          reference = null,
          children = entries.toMutableList(),
        )
      val table = HashBasedTable.create<String, String, InspectorPropertyItem>()
      table.put("parameter", "backStack", backStack)
      propertiesModel.properties = PropertiesTable.create(table)
      if (refresh) {
        panel.updateBackStackForTesting()
        assertThat(panel.backStackList).isEqualTo(routes)
      }
    }

    fun pressKey(keyCode: Int, shift: Boolean = false, ctrl: Boolean = false) {
      val keyboard = ui.keyboard
      keyboard.setFocus(focusOwner)
      if (shift) keyboard.press(KeyEvent.VK_SHIFT)
      if (ctrl) keyboard.press(KeyEvent.VK_CONTROL)
      keyboard.pressAndRelease(keyCode)
      if (ctrl) keyboard.release(KeyEvent.VK_CONTROL)
      if (shift) keyboard.release(KeyEvent.VK_SHIFT)
    }

    /** Records the focus events of the list and of the components around the panel. */
    fun recordFocusEvents(events: MutableList<String>) {
      for ((name, component) in listOf("list" to list, "before" to before, "after" to after)) {
        component.addFocusListener(
          object : FocusListener {
            override fun focusGained(event: FocusEvent) {
              events.add("$name gained")
            }

            override fun focusLost(event: FocusEvent) {
              events.add("$name lost")
            }
          }
        )
      }
    }
  }
}
