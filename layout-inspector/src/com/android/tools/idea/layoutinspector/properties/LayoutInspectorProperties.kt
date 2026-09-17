/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.layoutinspector.properties

import com.android.tools.adtui.workbench.ToolContent
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.LayoutInspectorBundle.message
import com.android.tools.idea.layoutinspector.model.InspectorModel.SelectionListener
import com.android.tools.idea.layoutinspector.properties.backstack.BackStackPanel
import com.android.tools.idea.layoutinspector.tree.createCenterTextPanel
import com.android.tools.property.panel.api.PropertiesPanel
import com.google.common.html.HtmlEscapers
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

const val BACK_STACK_SPLITTER_KEY = "com.android.tools.idea.layoutinspector.properties.BackStackSplitter"
const val PROPERTIES_COMPONENT_NAME = "Properties Component"
const val NO_SELECTION_CARD = "No Selection"
const val SELECTED_VIEW_CARD = "View Selected"
const val INFO_TEXT = "Info Text"

class LayoutInspectorProperties(parentDisposable: Disposable) : ToolContent<LayoutInspector> {
  private val componentModel = InspectorPropertiesModel(parentDisposable)
  private val componentView = InspectorPropertiesView(componentModel)

  /** A panel dedicated to rendering the Navigation 3 (Nav3) backstack. */
  private val backStackPanel =
    if (StudioFlags.DYNAMIC_LAYOUT_INSPECTOR_BACK_STACK_VISUAL.get()) {
      BackStackPanel(componentModel)
    } else {
      null
    }
  private val cardLayout = CardLayout()
  private val cardView = JPanel(cardLayout)
  private val properties = PropertiesPanel<InspectorPropertyItem>(this)
  private val filterKeyListener = createFilterKeyListener()
  private val selectionListener: SelectionListener

  init {
    properties.component.name = PROPERTIES_COMPONENT_NAME
    properties.addView(componentView)

    val contentPanel =
      if (backStackPanel != null) {
        OnePixelSplitter(true, BACK_STACK_SPLITTER_KEY, 0.65f).apply {
          firstComponent = createAttributesPanel()
          secondComponent = backStackPanel
          setHonorComponentsMinimumSize(true)
          setBlindZone { JBUI.insets(0, 1) }
        }
      } else {
        properties.component
      }

    val infoPanel = JPanel(BorderLayout())
    val text = HtmlEscapers.htmlEscaper().escape(message("no.selection.no.properties"))
    val infoText = createCenterTextPanel(listOf(text))
    infoText.name = INFO_TEXT
    infoPanel.add(infoText, BorderLayout.CENTER)
    cardView.add(infoPanel, NO_SELECTION_CARD)
    cardView.add(contentPanel, SELECTED_VIEW_CARD)
    Disposer.register(parentDisposable, this)

    selectionListener = SelectionListener { _, newView, _ ->
      cardLayout.show(cardView, if (newView == null) NO_SELECTION_CARD else SELECTED_VIEW_CARD)
    }
    cardLayout.show(cardView, NO_SELECTION_CARD)
  }

  /** Creates the Attributes section containing its header, search bar, and the attributes component. */
  private fun createAttributesPanel(): JComponent {
    val header =
      JPanel(BorderLayout()).apply {
        border =
          JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(4, 8),
          )
        val titleLabel =
          JBLabel(ATTRIBUTES_TITLE).apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(Font.BOLD)
            foreground = UIUtil.getLabelForeground()
          }
        add(titleLabel, BorderLayout.WEST)
      }

    return JPanel(BorderLayout()).apply {
      add(header, BorderLayout.NORTH)
      add(properties.component, BorderLayout.CENTER)
    }
  }

  override fun setToolContext(toolContext: LayoutInspector?) {
    componentModel.layoutInspector?.inspectorModel?.removeSelectionListener(selectionListener)
    componentModel.layoutInspector = toolContext
    componentModel.layoutInspector?.inspectorModel?.addSelectionListener(selectionListener)
    backStackPanel?.setToolContext(toolContext)
  }

  override fun getComponent() = cardView

  override fun dispose() {
    setToolContext(null)
    backStackPanel?.dispose()
  }

  override fun getGearActions() = listOf(DimensionUnitAction)

  override fun supportsFiltering() = true

  override fun setFilter(filter: String) {
    properties.filter = filter
    backStackPanel?.setFilter(filter)
  }

  override fun getFilterKeyListener() = filterKeyListener

  override fun isFilteringActive(): Boolean {
    return componentModel.layoutInspector?.currentClient?.isConnected ?: false
  }

  private fun createFilterKeyListener() =
    object : KeyAdapter() {
      override fun keyPressed(event: KeyEvent) {
        if (properties.filter.isNotEmpty() && event.keyCode == KeyEvent.VK_ENTER && event.modifiers == 0 && properties.enterInFilter()) {
          event.consume()
        }
      }
    }
}
