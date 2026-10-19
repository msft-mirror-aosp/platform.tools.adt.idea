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
package com.android.tools.profilers

import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Adjustable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * A scroll pane that lays out its content at no less than a required width, and scrolls horizontally when it is narrower.
 *
 * Horizontally, the content fills the viewport when the viewport is at least as wide as the required width, and keeps the required width
 * otherwise. The required width is the value of `minContentWidth`, or more if a visible component marked with [KEEP_PREFERRED_WIDTH] needs
 * more to get its preferred width. Vertically, the content always fills the viewport.
 *
 * The minimum size of this scroll pane doesn't depend on the content. Its horizontal scrollbar is laid out below the viewport, it has no
 * key bindings of its own, and a component that receives keyboard focus while completely outside of the visible area is scrolled into view.
 */
class MinWidthScrollPane private constructor(private val host: Host) :
  JBScrollPane(host, VERTICAL_SCROLLBAR_NEVER, HORIZONTAL_SCROLLBAR_AS_NEEDED) {

  /**
   * @param content the component to scroll.
   * @param minContentWidth returns the minimum width of [content], in scaled pixels. It is called on every layout, so it can follow changes
   *   of the IDE scale.
   */
  constructor(content: JComponent, minContentWidth: () -> Int) : this(Host(content, minContentWidth))

  private val focusOwnerListener = PropertyChangeListener { event -> scrollIntoViewIfHidden(event.newValue as? Component) }

  /** The focus manager [focusOwnerListener] is registered with while this scroll pane is displayable. */
  private var focusManager: KeyboardFocusManager? = null

  init {
    border = JBUI.Borders.empty()
    viewportBorder = JBUI.Borders.empty()
  }

  override fun updateUI() {
    super.updateUI()
    // Removes the scroll pane's key bindings (e.g. Left, Right, Page Up and Page Down), so that keys only go to the focused component.
    SwingUtilities.replaceUIInputMap(this, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, null)
  }

  override fun doLayout() {
    host.clearRequiredWidthCache()
    super.doLayout()
  }

  // Always opaque, so JBScrollPane lays it out below the viewport rather than over the bottom of the content, where it would cover the
  // content's own horizontal scrollbars.
  override fun createHorizontalScrollBar(): JScrollBar =
    object : JBScrollBar(Adjustable.HORIZONTAL) {
      override fun isOpaque() = true
    }

  override fun addNotify() {
    super.addNotify()
    focusManager?.removePropertyChangeListener(FOCUS_OWNER_PROPERTY, focusOwnerListener)
    focusManager =
      KeyboardFocusManager.getCurrentKeyboardFocusManager().also {
        it.addPropertyChangeListener(FOCUS_OWNER_PROPERTY, focusOwnerListener)
      }
  }

  override fun removeNotify() {
    focusManager?.removePropertyChangeListener(FOCUS_OWNER_PROPERTY, focusOwnerListener)
    focusManager = null
    super.removeNotify()
  }

  /**
   * Scrolls [focused] into view if it is within the content and its visible part is completely outside of the visible area, e.g. when it is
   * reached with Tab. A component that is at least partly visible, e.g. one that was clicked, is left as is.
   */
  private fun scrollIntoViewIfHidden(focused: Component?) {
    if (focused == null || !SwingUtilities.isDescendingFrom(focused, host)) return
    val bounds = visibleBoundsInHost(focused) ?: return
    if (!host.visibleRect.intersects(bounds)) {
      host.scrollRectToVisible(bounds)
    }
  }

  /**
   * Returns the bounds of [component] in [host], clipped by the bounds of its ancestors up to and including [host], or null if nothing of
   * [component] is left, e.g. because it is scrolled out of view within a nested scroll pane.
   */
  private fun visibleBoundsInHost(component: Component): Rectangle? {
    var bounds = Rectangle(0, 0, component.width, component.height)
    var current = component
    while (current !== host) {
      val parent = current.parent ?: return null
      bounds.translate(current.x, current.y)
      bounds = bounds.intersection(Rectangle(0, 0, parent.width, parent.height))
      if (bounds.isEmpty) return null
      current = parent
    }
    return bounds
  }

  /** The view of the scroll pane, laying out the content as described in [MinWidthScrollPane]. */
  private class Host(content: JComponent, private val minContentWidth: () -> Int) : JPanel(BorderLayout()), Scrollable {
    /** The required width as of the last time it was computed, or -1 if it needs to be computed again. */
    private var cachedRequiredWidth = -1

    init {
      isOpaque = false
      add(content, BorderLayout.CENTER)
    }

    /** Makes the next layout compute the required width again. */
    fun clearRequiredWidthCache() {
      cachedRequiredWidth = -1
    }

    override fun invalidate() {
      clearRequiredWidthCache()
      super.invalidate()
    }

    /** Width is the required width; height is the content's preferred height. */
    override fun getPreferredSize(): Dimension = Dimension(requiredWidth(), super.getPreferredSize().height)

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
      if (orientation == SwingConstants.HORIZONTAL) visibleRect.width else visibleRect.height

    override fun getScrollableTracksViewportWidth(): Boolean {
      val viewport = SwingUtilities.getUnwrappedParent(this) as? JViewport ?: return true
      return viewport.width >= requiredWidth()
    }

    override fun getScrollableTracksViewportHeight() = true

    private fun requiredWidth(): Int {
      if (cachedRequiredWidth < 0) {
        val keepPreferredWidth = components.maxOfOrNull { keepPreferredWidthRequirement(it, it.x, insets.right) } ?: 0
        cachedRequiredWidth = max(minContentWidth(), keepPreferredWidth)
      }
      return cachedRequiredWidth
    }

    /**
     * Returns the width this host needs for the visible components marked with [KEEP_PREFERRED_WIDTH] within [component] to get their
     * preferred width, or 0 if there are none. [left] is the x of [component] in this host, and [rightInsets] the sum of the right insets
     * of its ancestors up to and including this host.
     *
     * Positions are those of the last layout. Nothing is assumed to be laid out to the right of a marked component within its ancestors,
     * other than their insets.
     */
    private fun keepPreferredWidthRequirement(component: Component, left: Int, rightInsets: Int): Int {
      if (!component.isVisible) return 0
      if (ClientProperty.isTrue(component, KEEP_PREFERRED_WIDTH)) {
        return left + component.preferredSize.width + rightInsets
      }
      if (component !is Container) return 0
      val childRightInsets = rightInsets + component.insets.right
      return component.components.maxOfOrNull { keepPreferredWidthRequirement(it, left + it.x, childRightInsets) } ?: 0
    }
  }

  companion object {
    /**
     * Client property marking a component that loses content rather than compressing it when laid out narrower than its preferred width,
     * e.g. a [java.awt.FlowLayout] row. [MinWidthScrollPane] scrolls rather than lay out such a component narrower than its preferred
     * width.
     */
    @JvmField val KEEP_PREFERRED_WIDTH: Key<Boolean> = Key.create("profilers.keepPreferredWidth")

    private const val FOCUS_OWNER_PROPERTY = "focusOwner"
  }
}
