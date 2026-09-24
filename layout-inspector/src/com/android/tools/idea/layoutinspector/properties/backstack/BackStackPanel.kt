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
import com.android.tools.adtui.workbench.ToolContent
import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.LayoutInspectorBundle
import com.android.tools.idea.layoutinspector.properties.InspectorPropertiesModel
import com.android.tools.idea.layoutinspector.tree.createCenterTextPanel
import com.google.common.html.HtmlEscapers
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.CurrentTheme.Banner
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.border.LineBorder
import org.jetbrains.annotations.TestOnly

/**
 * UI panel for displaying androidx nav3 navigation back stack in the Layout Inspector.
 *
 * Automatically observes the inspector model for navigation changes during emulator interaction, and renders the navigation stack entries
 * directly as a vertical list of cards.
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

  // Main container holding either the scrollable items or the empty state message
  private val mainContainer =
    JPanel(BorderLayout()).apply {
      background = secondaryPanelBackground
      border = JBUI.Borders.empty(4, 0)
    }

  // Scrollable container holding the stack cards vertically that tracks viewport width
  private val itemsContainer =
    object : JPanel(), Scrollable {
      init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = secondaryPanelBackground
        isOpaque = false
      }

      override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

      override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

      override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(48)

      override fun getScrollableTracksViewportWidth(): Boolean = true

      override fun getScrollableTracksViewportHeight(): Boolean = false
    }

  // Scroll pane enabling vertical scrolling when the back stack exceeds panel height
  private val scrollPane =
    JBScrollPane(itemsContainer).apply {
      border = JBUI.Borders.empty()
      isOpaque = false
      viewport.isOpaque = false
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
      alignmentX = LEFT_ALIGNMENT
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
        }
      add(titleLabel, BorderLayout.WEST)
    }

  init {
    background = secondaryPanelBackground
    minimumSize = Dimension(0, JBUI.scale(150))
    isVisible = model.isVisible
    add(header, BorderLayout.NORTH)
    add(mainContainer, BorderLayout.CENTER)
    model.addListener(::onModelChanged)
    rebuildUi()
  }

  private fun onModelChanged() {
    val updateAction = {
      if (isVisible != model.isVisible) {
        isVisible = model.isVisible
        parent?.revalidate()
        parent?.repaint()
      }
      rebuildUi()
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

  /** Reconstructs the visual rows representing each navigation back stack item. */
  private fun rebuildUi() {
    mainContainer.removeAll()
    itemsContainer.removeAll()

    // Filter back stack entries while preserving original indices to correctly determine the active item
    val indexedList = backStackList.withIndex().toList()
    val filteredList =
      if (filter.isEmpty()) {
        indexedList
      } else {
        indexedList.filter { it.value.contains(filter, ignoreCase = true) }
      }

    if (filteredList.isEmpty()) {
      // Display appropriate empty-state message depending on whether the entire back stack is empty
      // or if all entries were filtered out by the active search query.
      val emptyMessageKey =
        if (backStackList.isEmpty()) "layout.inspector.backstack.empty.message" else "layout.inspector.backstack.no.matching.filter"
      val emptyText = HtmlEscapers.htmlEscaper().escape(LayoutInspectorBundle.message(emptyMessageKey))
      val infoText = createCenterTextPanel(listOf(emptyText))
      mainContainer.add(infoText, BorderLayout.CENTER)
    } else {
      // Render back stack entries in reverse chronological order so that the top active item appears first
      val reversedList = filteredList.asReversed()
      for (i in reversedList.indices) {
        val indexedItem = reversedList[i]
        // Mark as active only if this entry corresponds to the most recent entry in the full back stack (top of stack).
        // Comparing by original index avoids erroneously marking earlier duplicate routes as active (e.g. ["Home", "Detail", "Home"]).
        val isActive = (indexedItem.index == backStackList.lastIndex)

        itemsContainer.add(buildItemRow(indexedItem.value, isActive))

        if (i < reversedList.lastIndex) {
          itemsContainer.add(Box.createVerticalStrut(4))
        }
      }

      mainContainer.add(scrollPane, BorderLayout.CENTER)
    }

    revalidate()
    repaint()
  }

  /** Builds the container card for an individual back stack entry. */
  private fun buildItemRow(item: String, isActive: Boolean): JComponent {
    val rowPanel =
      JPanel(BorderLayout()).apply {
        background = if (isActive) Banner.SUCCESS_BACKGROUND else UIUtil.getTextFieldBackground()
        border =
          BorderFactory.createCompoundBorder(
            LineBorder(if (isActive) Banner.SUCCESS_BORDER_COLOR else JBColor.border(), 1, true),
            JBUI.Borders.empty(4, 8),
          )
        alignmentX = LEFT_ALIGNMENT
      }

    rowPanel.add(buildTextPanel(item, isActive), BorderLayout.CENTER)

    if (isActive) {
      rowPanel.add(createActiveBadge(), BorderLayout.EAST)
    }

    // Wrap with left and right margins matching the panel content inset
    return JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(0, 8)
      add(rowPanel, BorderLayout.CENTER)
    }
  }

  /** Builds the label panel displaying the raw string representation of a back stack entry. */
  private fun buildTextPanel(item: String, isActive: Boolean): JComponent {
    val textPanel =
      JPanel(GridBagLayout()).apply {
        isOpaque = false
      }
    val gbc =
      GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.WEST
      }

    val nameLabel =
      JBLabel(HtmlEscapers.htmlEscaper().escape(item)).apply {
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(if (isActive) Font.BOLD else Font.PLAIN)
        foreground = if (isActive) Banner.FOREGROUND else UIUtil.getLabelForeground()
      }
    textPanel.add(nameLabel, gbc)

    return textPanel
  }

  /** Creates the visual badge marking the active top destination entry. */
  private fun createActiveBadge(): JComponent {
    val smallFont = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
    return JBLabel(LayoutInspectorBundle.message("layout.inspector.backstack.active")).apply {
      font = smallFont.deriveFont(Font.BOLD, smallFont.size2D - 1.0f)
      foreground = Banner.FOREGROUND
      border =
        BorderFactory.createCompoundBorder(
          LineBorder(Banner.SUCCESS_BORDER_COLOR, 1, true),
          JBUI.Borders.empty(1, 4),
        )
    }
  }

  @TestOnly
  fun updateBackStackForTesting() {
    model.updateBackStackForTesting()
  }
}
