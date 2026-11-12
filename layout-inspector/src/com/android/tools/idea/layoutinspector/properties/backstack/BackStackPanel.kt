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

import com.android.tools.adtui.common.secondaryPanelBackground
import com.android.tools.adtui.util.disableHtml
import com.android.tools.adtui.workbench.ToolContent
import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.LayoutInspectorBundle
import com.android.tools.idea.layoutinspector.properties.InspectorPropertiesModel
import com.android.tools.idea.layoutinspector.tree.createCenterTextPanel
import com.google.common.html.HtmlEscapers
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ExpandedItemListCellRendererWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.SingleSelectionModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.CurrentTheme.Banner
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.border.LineBorder
import kotlin.math.abs
import org.jetbrains.annotations.TestOnly

/**
 * UI panel for displaying androidx nav3 navigation back stack in the Layout Inspector.
 *
 * Automatically observes the inspector model for navigation changes during emulator interaction, and renders the navigation stack entries
 * as a list of cards, with the top (current) entry first. The list is a single Tab stop: the arrow keys move a keyboard cursor across the
 * entries, which is shown as a focus ring while the list has focus.
 *
 * @param model The [BackStackPanelModel] backing this panel.
 */
class BackStackPanel(val model: BackStackPanelModel) : JPanel(BorderLayout()), ToolContent<LayoutInspector> {

  constructor(
    propertiesModel: InspectorPropertiesModel? = null,
    initialBackStackList: List<String> = emptyList(),
  ) : this(BackStackPanelModel(propertiesModel, initialBackStackList))

  /** Current list of back stack items in chronological order. */
  val backStackList: List<String>
    get() = model.backStackList

  /** Current filter string applied to back stack items. */
  val filter: String
    get() = model.filter

  private val listModel = CollectionListModel<BackStackEntry>()

  // List of back stack cards
  private val list = BackStackList(listModel)

  // Message displayed below the list when there are no entries to show, wrapped and centered like the other Layout Inspector panels
  private var emptyStatePanel: JComponent? = null
  private var emptyMessage = ""

  // Content of the scroll pane: the list, and the empty state message below it when the list has no entries. The list stays in the
  // component hierarchy when it is empty, so that it keeps the keyboard focus.
  private val listContainer =
    object : JPanel(BorderLayout()), Scrollable {
      override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

      override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        list.getScrollableUnitIncrement(visibleRect, orientation, direction)

      override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        list.getScrollableBlockIncrement(visibleRect, orientation, direction)

      // Lay out the cards with the viewport width, so that long routes are truncated rather than scrolled horizontally
      override fun getScrollableTracksViewportWidth(): Boolean = true

      // Fill the viewport when the content is smaller, so that the empty state message is centered
      override fun getScrollableTracksViewportHeight(): Boolean = (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false
    }

  // Scroll pane enabling vertical scrolling when the back stack exceeds panel height
  private val scrollPane =
    JBScrollPane(listContainer).apply {
      border = JBUI.Borders.empty(4, 0)
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

  // Header displaying the title for the back stack section
  private val header =
    JPanel(BorderLayout()).apply {
      background = secondaryPanelBackground
      border =
        JBUI.Borders.compound(
          JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
          JBUI.Borders.empty(4, 8),
        )
      val titleLabel =
        JBLabel(LayoutInspectorBundle.message("layout.inspector.backstack.panel.title")).apply {
          font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(Font.BOLD)
          foreground = UIUtil.getLabelForeground()
          // Also provides the accessible name of the list
          labelFor = list
        }
      add(titleLabel, BorderLayout.WEST)
    }

  init {
    background = secondaryPanelBackground
    minimumSize = Dimension(0, JBUI.scale(150))
    isVisible = model.isVisible
    add(header, BorderLayout.NORTH)
    add(scrollPane, BorderLayout.CENTER)
    listContainer.background = secondaryPanelBackground
    listContainer.add(list, BorderLayout.NORTH)
    model.addListener(::onModelChanged)
    updateEntries()
  }

  private fun onModelChanged() {
    val updateAction = {
      if (isVisible != model.isVisible) {
        isVisible = model.isVisible
        parent?.revalidate()
        parent?.repaint()
      }
      updateEntries()
    }
    val app = ApplicationManager.getApplication()
    if (app == null || app.isDispatchThread || app.isUnitTestMode) {
      updateAction()
    } else {
      app.invokeLater(updateAction)
    }
  }

  override fun addNotify() {
    super.addNotify()
    model.attachPropertiesListener()
  }

  override fun removeNotify() {
    super.removeNotify()
    model.detachPropertiesListener()
  }

  override fun supportsFiltering(): Boolean = true

  override fun setFilter(filter: String) {
    model.setFilter(filter)
  }

  override fun setToolContext(toolContext: LayoutInspector?) {
    model.setToolContext(toolContext)
  }

  override fun getComponent(): JComponent = this

  override fun dispose() {
    model.dispose()
  }

  /**
   * Updates the list with the back stack entries matching the filter, in reverse chronological order so that the top entry appears first.
   *
   * The list component is never replaced, so it keeps the keyboard focus. The keyboard cursor stays on the same back stack entry, or moves
   * to the closest remaining one if that entry is gone.
   */
  private fun updateEntries() {
    val entries =
      backStackList
        .withIndex()
        .filter { (_, route) -> filter.isEmpty() || route.contains(filter, ignoreCase = true) }
        .map { (index, route) -> BackStackEntry(index, route, isCurrent = index == backStackList.lastIndex) }
        .asReversed()

    if (entries != listModel.items) {
      val previousCursorEntry = list.selectedValue
      listModel.replaceAll(entries)
      val cursorRow =
        when {
          previousCursorEntry != null -> entries.indexOfClosest(previousCursorEntry)
          list.isFocusOwner -> 0
          else -> -1
        }
      if (cursorRow in entries.indices) {
        list.selectedIndex = cursorRow
        if (list.isFocusOwner) {
          list.ensureIndexIsVisible(cursorRow)
        }
      }
    }

    // Display the appropriate empty-state message depending on whether the entire back stack is empty or if all entries were filtered out
    val emptyMessageKey =
      if (backStackList.isEmpty()) "layout.inspector.backstack.empty.message" else "layout.inspector.backstack.no.matching.filter"
    val message = LayoutInspectorBundle.message(emptyMessageKey)
    if (message != emptyMessage) {
      emptyMessage = message
      emptyStatePanel?.let { listContainer.remove(it) }
      val panel = createCenterTextPanel(listOf(HtmlEscapers.htmlEscaper().escape(message))).apply { isOpaque = false }
      listContainer.add(panel, BorderLayout.CENTER)
      emptyStatePanel = panel
    }
    emptyStatePanel?.isVisible = entries.isEmpty()
    // An empty list is not a Tab stop, but it keeps the focus if it has it (see BackStackList), or if it should get it back when its
    // window is activated again
    list.isFocusable = entries.isNotEmpty() || list.isFocusOwner || SwingUtilities.getWindowAncestor(list)?.mostRecentFocusOwner === list
    listContainer.revalidate()
    listContainer.repaint()
    // Also announce the message when the empty list has the focus
    list.accessibleContext.accessibleDescription = if (entries.isEmpty()) message else null
  }

  /** Returns the row of the entry that is the closest to [entry] in the back stack, preferring older entries on ties, or -1 if empty. */
  private fun List<BackStackEntry>.indexOfClosest(entry: BackStackEntry): Int =
    withIndex().minWithOrNull(compareBy({ abs(it.value.index - entry.index) }, { it.value.index }))?.index ?: -1

  @TestOnly
  fun updateBackStackForTesting() {
    model.updateBackStackForTesting()
  }
}

/**
 * An entry displayed in the back stack list.
 *
 * @param index The position of the entry in the full back stack (0 being the oldest), identifying it even when routes are duplicated.
 * @param route The route of the entry, displayed as plain text.
 * @param isCurrent Whether this is the top entry of the back stack, i.e. the current destination.
 */
private data class BackStackEntry(val index: Int, val route: String, val isCurrent: Boolean) {
  // Used by JList for type-ahead navigation and by JBList when copying the selected entry
  override fun toString() = route
}

/** List of back stack entries with single selection, which acts as the keyboard cursor. */
private class BackStackList(model: CollectionListModel<BackStackEntry>) : JBList<BackStackEntry>(model) {
  init {
    cellRenderer = BackStackEntryRenderer()
    // The cursor can be moved but not removed (e.g. with Ctrl+Space or Ctrl+Click), which would leave the focused list without a selected
    // entry and make the list UI paint its own lead indicator over the cards. Removing entries from the model still updates the selection.
    selectionModel = SingleSelectionModel()
    background = secondaryPanelBackground
    // Truncated routes are shown in a tooltip instead: the expanded item popup only extends to the right of the list, up to the screen
    // edge, so it can't show the route when the panel is docked on the right side. It is also disabled when a screen reader is active.
    setExpandableItemsEnabled(false)
    addFocusListener(
      object : FocusAdapter() {
        override fun focusGained(event: FocusEvent) {
          // Show the keyboard cursor as soon as the list is focused (e.g. with Tab) rather than on the first arrow key press
          if (isSelectionEmpty && !isEmpty) {
            selectedIndex = firstVisibleIndex.coerceAtLeast(0)
            ensureIndexIsVisible(selectedIndex)
          }
        }

        override fun focusLost(event: FocusEvent) {
          // Stop being a Tab stop once the focus has left a list that was emptied while it had the focus
          if (!event.isTemporary && isEmpty) {
            isFocusable = false
          }
        }
      }
    )
  }

  // Without the size of the empty text, which isn't used: the empty state message is displayed below the list, which then has no height
  override fun getPreferredSize(): Dimension = super_getPreferredSize()

  /** Returns the full route of the entry under the mouse if it is truncated. */
  override fun getToolTipText(event: MouseEvent?): String? {
    if (event == null) return null
    val row = locationToIndex(event.point)
    val cellBounds = getCellBounds(row, row)?.takeIf { it.contains(event.point) } ?: return null
    val entry = model.getElementAt(row)
    val rendererComponent = cellRenderer.getListCellRendererComponent(this, entry, row, isSelectedIndex(row), false)
    if (rendererComponent.preferredSize.width <= cellBounds.width) return null
    // Tooltips are rendered as HTML in the IDE, so the route must be escaped to be displayed as plain text
    return HtmlChunk.text(entry.route).wrapWith(HtmlChunk.html()).toString()
  }

  override fun updateUI() {
    super.updateUI()
    // The renderer components are not in the component hierarchy, so they would otherwise keep using the previous look and feel.
    // Note: this is also called by the JList constructor, before the renderer is set.
    (ExpandedItemListCellRendererWrapper.unwrap(cellRenderer) as? BackStackEntryRenderer)?.updateUI()
  }
}

/** Renders a [BackStackEntry] as a rounded card. The current entry is highlighted and marked with a badge. */
private class BackStackEntryRenderer : ListCellRenderer<BackStackEntry> {
  private val routeLabel = JBLabel().disableHtml()

  private val currentBadge =
    JBLabel(LayoutInspectorBundle.message("layout.inspector.backstack.active")).apply {
      border = BorderFactory.createCompoundBorder(LineBorder(Banner.INFO_BORDER_COLOR, 1, true), JBUI.Borders.empty(1, 4))
    }

  private val card =
    CardPanel().apply {
      add(routeLabel, BorderLayout.CENTER)
      add(currentBadge, BorderLayout.EAST)
    }

  private val cell = ListItemPanel().apply { add(card, BorderLayout.CENTER) }

  private val cardBorder = createCardBorder(JBColor.border())
  private val currentCardBorder = createCardBorder(Banner.INFO_BORDER_COLOR)

  // Side margins matching the panel content inset, and a gap below each card except the last one
  private val cellBorder = JBUI.Borders.empty(0, 8, 4, 8)
  private val lastCellBorder = JBUI.Borders.empty(0, 8)

  fun updateUI() {
    SwingUtilities.updateComponentTreeUI(cell)
  }

  override fun getListCellRendererComponent(
    list: JList<out BackStackEntry>,
    value: BackStackEntry,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ): Component {
    val isCurrent = value.isCurrent
    val smallFont = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
    routeLabel.text = value.route
    routeLabel.font = smallFont.deriveFont(if (isCurrent) Font.BOLD else Font.PLAIN)
    routeLabel.foreground = if (isCurrent) Banner.FOREGROUND else UIUtil.getLabelForeground()
    currentBadge.isVisible = isCurrent
    currentBadge.font = smallFont.deriveFont(Font.BOLD, smallFont.size2D - 1.0f)
    currentBadge.foreground = Banner.FOREGROUND
    card.background = if (isCurrent) Banner.INFO_BACKGROUND else UIUtil.getTextFieldBackground()
    card.border = if (isCurrent) currentCardBorder else cardBorder
    // The selection is only a keyboard cursor: it is shown as a focus ring while the list has focus, and has no other styling.
    // Only the cursor is checked, as some callers pass cellHasFocus for any row while the list has focus (e.g. ListExpandableItemsHandler).
    card.paintFocusRing = isSelected && cellHasFocus
    cell.background = list.background
    cell.border = if (index < list.model.size - 1) cellBorder else lastCellBorder
    cell.accessibleItemName =
      if (isCurrent) LayoutInspectorBundle.message("layout.inspector.backstack.current.entry.accessible.name", value.route) else value.route
    return cell
  }

  private fun createCardBorder(lineColor: Color): Border =
    BorderFactory.createCompoundBorder(LineBorder(lineColor, 1, true), JBUI.Borders.empty(4, 8))
}

/** A card that can paint a focus ring over its border, so that showing the ring never changes the card insets or layout. */
private class CardPanel : JPanel(BorderLayout()) {
  var paintFocusRing = false

  // Square, so that it covers the whole card border. It follows the theme and scale changes.
  private val focusRingBorder = JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)

  override fun paintBorder(g: Graphics) {
    super.paintBorder(g)
    if (paintFocusRing) {
      focusRingBorder.paintBorder(this, g, 0, 0, width, height)
    }
  }
}

/** The root of a rendered entry, exposed to accessibility as a single list item whose name describes the whole card. */
private class ListItemPanel : JPanel(BorderLayout()) {
  var accessibleItemName: String? = null

  override fun getAccessibleContext(): AccessibleContext {
    if (accessibleContext == null) {
      accessibleContext =
        object : AccessibleJPanel() {
          override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LIST_ITEM

          override fun getAccessibleName(): String? = accessibleItemName

          // The labels of the card are already described by the item name
          override fun getAccessibleChildrenCount(): Int = 0

          override fun getAccessibleChild(i: Int): Accessible? = null
        }
    }
    return accessibleContext
  }
}
