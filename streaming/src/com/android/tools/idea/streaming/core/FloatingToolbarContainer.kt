/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.streaming.core

import com.android.tools.adtui.util.makeNavigable
import com.android.tools.adtui.util.scaled
import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable.SELECTED_KEY
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.openapi.wm.impl.IdeGlassPaneEx
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.AbstractLayoutManager
import com.intellij.util.ui.Animator
import com.intellij.util.ui.GraphicsUtil.disableAAPainting
import com.intellij.util.ui.GraphicsUtil.setupAAPainting
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.CurrentTheme.Toolbar.SEPARATOR_COLOR
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.AWTEvent
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Shape
import java.awt.Toolkit
import java.awt.Transparency
import java.awt.event.AWTEventListener
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants.HORIZONTAL
import javax.swing.SwingConstants.VERTICAL
import kotlin.math.max
import kotlin.math.min
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.VisibleForTesting

/**
 * A container of floating toolbars that may change their appearance by becoming semi-transparent and/or shrinking when inactive. If
 * [activateOnHover] is true, the activity state is controlled by the mouse hover events. Otherwise, the toolbar should include actions
 * explicitly controlling its activity state.
 *
 * If [collapsedStateSelector] is not null, the toolbar container is collapsible. In the collapsed state the contained toolbars shrink to a
 * single button or completely disappear from the view. The button that remains visible when the toolbar is shrunk is the first one, for
 * which [collapsedStateSelector] returns true.
 */
internal class FloatingToolbarContainer(
  horizontal: Boolean,
  private val inactiveAlpha: Double = 1.0,
  private val collapsedStateSelector: ((ActionButton) -> Boolean)? = null,
  private val activateOnHover: Boolean = false,
  initiallyActive: Boolean = false,
) : JPanel() {

  @Orientation private val orientation = if (horizontal) HORIZONTAL else VERTICAL
  private val actionToolbars = mutableListOf<ActionToolbar>()

  private var visibilityDisposable: Disposable? = null
  private var listeningToMouseEvents = false
  private var activationAnimator: Animator? = null
  private var deactivationAnimator: Animator? = null
  private var pendingDeactivation: PendingDeactivation? = null

  private val awtListener = AWTEventListener { event ->
    val component = (event as HierarchyEvent).component
    if (
      event.changeFlags.toInt() and HierarchyEvent.SHOWING_CHANGED != 0 &&
        (component == this || component.findAncestor<FloatingToolbarContainer>() == this@FloatingToolbarContainer) &&
        component.background == JBUI.CurrentTheme.ToolWindow.background()
    ) {
      // Restore the original background modified by the IslandsUICustomization class.
      component.background = JBColor.PanelBackground
    }
  }

  val isActive: Boolean
    get() = (activationFactor > 0 || activationAnimator != null) && pendingDeactivation == null && deactivationAnimator == null

  var onActivationChanged: ((Boolean) -> Unit)? = null

  /**
   * Returns the bounding rectangle of the visible toolbars in this container's coordinate space, using the fully expanded bounds while
   * [isActive] is true, or an empty [Rectangle] if none are visible.
   */
  val visibleBounds: Rectangle
    get() {
      val result = Rectangle()
      if (!isVisible || width <= 0 || height <= 0 || computeSize(collapsed = false)[orientation] == 0) {
        return result
      }
      forEachChildBounds(if (isActive) 1.0 else expansionFactor) { child, bounds ->
        if (child.isVisible && !bounds.isEmpty) {
          if (result.isEmpty) {
            result.bounds = bounds
          } else {
            result.add(bounds)
          }
        }
      }
      return result
    }

  /** Zero means inactive, one means active. */
  @VisibleForTesting
  internal var activationFactor: Double = if (initiallyActive) 1.0 else 0.0
    private set(value) {
      if (field != value) {
        field = value
        if (collapsible) {
          expansionFactor = activationFactor
        }
        alpha = ((inactiveAlpha + (ACTIVE_ALPHA - inactiveAlpha) * value)).coerceIn(inactiveAlpha, ACTIVE_ALPHA)
      }
    }

  private var expansionFactor: Double = if (collapsible) activationFactor else 1.0
    set(value) {
      if (field != value) {
        field = value
        revalidate()
      }
    }

  private var alpha: Double = inactiveAlpha
    set(value) {
      if (field != value) {
        field = value
        for (child in components) {
          if (child is ToolbarPanel) {
            child.alpha = alpha.toFloat()
          }
        }
        repaint()
      }
    }

  internal val collapsible: Boolean
    get() = collapsedStateSelector != null

  init {
    require(inactiveAlpha in 0.0..1.0)
    background = JBColor.PanelBackground
    isOpaque = false
    layout = Layout()

    addHierarchyListener { event ->
      if (event.changeFlags.toInt() and (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
        onVisibilityChanged()
      }
    }
  }

  override fun addNotify() {
    Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.HIERARCHY_EVENT_MASK)
    super.addNotify()
  }

  override fun removeNotify() {
    Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
    super.removeNotify()
  }

  /**
   * Since FloatingToolbarContainer stretches across the full orthogonal dimension of its parent container while its child [ToolbarPanel]s
   * only occupy part of that strip, return true only over visible child panels so that AWT's cursor lookup [Container.findComponentAt]) and
   * mouse hit-testing pass through the empty area to the underlying [DisplayView].
   */
  override fun contains(x: Int, y: Int): Boolean = anyVisibleChildContains(x, y)

  /** Adds a floating toolbar. */
  fun addToolbar(@NonNls place: String, actionGroup: ActionGroup) {
    val actionToolbar =
      ActionManager.getInstance().createActionToolbar(place, actionGroup, orientation == HORIZONTAL).apply { configureToolbar() }
    actionToolbars.add(actionToolbar)
    val toolbarPanel = ToolbarPanel(actionToolbar, collapsedStateSelector)
    toolbarPanel.alpha = alpha.toFloat()
    add(toolbarPanel)
  }

  fun setTargetComponent(component: JComponent) {
    for (toolbar in actionToolbars) {
      toolbar.targetComponent = component
    }
  }

  fun triggerActivation() {
    val wasActive = isActive
    pendingDeactivation = null
    deactivationAnimator?.dispose()
    if (activationAnimator == null && activationFactor < 1.0) {
      activationAnimator = ActivationAnimator(ACTIVATION_ANIMATION_DURATION_MILLIS.scaled(1 - activationFactor)).apply { resume() }
    }
    if (!wasActive && isActive) {
      onActivationChanged?.invoke(true)
    }
  }

  fun triggerDeactivation(delayMillis: Int = 0, slow: Boolean = false) {
    val wasActive = isActive
    if (delayMillis == 0 && !slow) {
      pendingDeactivation = null
      activationAnimator?.dispose()
    }
    if (activationAnimator == null) {
      pendingDeactivation = null
      if (activationFactor > 0.0 && deactivationAnimator == null) {
        val duration = if (slow) COLLAPSE_ANIMATION_DURATION_SLOW_MILLIS else COLLAPSE_ANIMATION_DURATION_MILLIS.scaled(activationFactor)
        deactivationAnimator = DeactivationAnimator(delayMillis, duration).apply { resume() }
      }
    } else {
      pendingDeactivation = PendingDeactivation(delayMillis, slow)
    }
    if (wasActive && !isActive) {
      onActivationChanged?.invoke(false)
    }
  }

  fun toggleActiveState() {
    if (isActive) triggerDeactivation() else triggerActivation()
  }

  private fun setUpMouseListener(disposable: Disposable) {
    if (listeningToMouseEvents) {
      return // Already set up.
    }

    val mouseListener =
      object : MouseAdapter() {

        override fun mouseEntered(event: MouseEvent) {
          controlActivation(event)
        }

        override fun mouseExited(event: MouseEvent) {
          controlActivation(event)
        }

        override fun mouseMoved(event: MouseEvent) {
          controlActivation(event)
        }
      }

    val glass = IdeGlassPaneUtil.find(this) as IdeGlassPaneEx
    glass.addMousePreprocessor(mouseListener, disposable)
    glass.addMouseMotionPreprocessor(mouseListener, disposable)
    listeningToMouseEvents = true
  }

  private fun controlActivation(event: MouseEvent) {
    val mouseLocation = event.locationOnScreen - locationOnScreen
    if (
      mouseLocation.x >= 0 &&
        mouseLocation.y >= 0 &&
        mouseLocation.x < width &&
        mouseLocation.y < height &&
        componentCount != 0 &&
        mouseLocation[orientation] >= getComponent(0).location[orientation]
    ) {
      triggerActivation()
    } else {
      triggerDeactivation(COLLAPSE_DELAY_MILLIS, slow = true)
    }
  }

  private fun onVisibilityChanged() {
    if (isShowing) {
      val disposable = visibilityDisposable ?: Disposer.newDisposable("FloatingToolbarContainer").also { visibilityDisposable = it }
      if (activateOnHover && (collapsible || inactiveAlpha < 1.0)) {
        setUpMouseListener(disposable)
      }
    } else {
      pendingDeactivation = null
      activationAnimator?.dispose()
      deactivationAnimator?.dispose()
      visibilityDisposable?.let { Disposer.dispose(it) }
      visibilityDisposable = null
      listeningToMouseEvents = false
    }
  }

  private data class PendingDeactivation(val delayMillis: Int, val slow: Boolean)

  @MagicConstant(intValues = [HORIZONTAL.toLong(), VERTICAL.toLong()]) private annotation class Orientation

  private inner class ActivationAnimator(durationMillis: Int) :
    Animator("ActivationAnimator", numFrames(durationMillis), durationMillis, false) {

    private val initialActivationFactor = activationFactor

    override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
      activationFactor = (initialActivationFactor + frame / totalFrames.toDouble()).coerceIn(0.0, 1.0)
    }

    override fun paintCycleEnd() {
      if (isDisposed) return
      activationFactor = 1.0
      dispose()
    }

    override fun dispose() {
      super.dispose()
      activationAnimator = null
      pendingDeactivation?.let {
        triggerDeactivation(it.delayMillis, it.slow)
      }
    }
  }

  private inner class DeactivationAnimator(delayMillis: Int, durationMillis: Int) :
    Animator("CollapseAnimator", numFrames(delayMillis + durationMillis), delayMillis + durationMillis, false) {

    private val initialActivationFactor = activationFactor
    private val delayFrames = numFrames(delayMillis)

    override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
      if (frame <= delayFrames) {
        return
      }
      activationFactor = (initialActivationFactor - (frame - delayFrames) / (totalFrames - delayFrames).toDouble()).coerceIn(0.0, 1.0)
    }

    override fun paintCycleEnd() {
      if (isDisposed) return
      activationFactor = 0.0
      dispose()
    }

    override fun dispose() {
      super.dispose()
      deactivationAnimator = null
    }
  }

  private class ToolbarPanel(private val toolbar: ActionToolbar, private val collapsedStateSelector: ((ActionButton) -> Boolean)?) :
    BorderLayoutPanel() {

    private var bufferingPainter = VolatileImageBufferingPainter(Transparency.TRANSLUCENT)

    private val crossDimension
      get() = if (toolbar.orientation == HORIZONTAL) height else width

    private val cornerRadius
      get() = crossDimension / 2

    var alpha: Float by bufferingPainter::alpha
    private val actionButtons = mutableListOf<ActionButton>()
    private val hierarchyListener = HierarchyListener { updateActionButtons() }
    private val containerListener =
      object : ContainerAdapter() {
        override fun componentAdded(event: ContainerEvent) = updateActionButtons()

        override fun componentRemoved(event: ContainerEvent) = updateActionButtons()
      }
    private val buttonSelectionListener = PropertyChangeListener { event ->
      if (event.propertyName == SELECTED_KEY.toString()) {
        revalidate()
        parent?.repaint()
      }
    }

    val collapsedSize: Dimension
      get() {
        if (isVisibleWhenCollapsed()) {
          val maxSize = getMaximumSize()
          val s = min(maxSize.width, maxSize.height)
          return Dimension(s, s)
        }
        return ZERO_DIMENSION
      }

    init {
      isOpaque = false
      layout = Layout()
      toolbar.component.addHierarchyListener(hierarchyListener)
      toolbar.component.addContainerListener(containerListener)
      add(toolbar.component)
      updateActionButtons()
    }

    private fun updateActionButtons() {
      for (button in actionButtons) {
        button.presentation.removePropertyChangeListener(buttonSelectionListener)
      }
      actionButtons.clear()
      for (child in toolbar.component.components) {
        if (child is ActionButton) {
          actionButtons.add(child)
          child.presentation.addPropertyChangeListener(buttonSelectionListener)
        }
      }
      revalidate()
      parent?.repaint()
    }

    /** This property causes repaint of a child to trigger repaint of this panel. */
    override fun isPaintingOrigin(): Boolean = true

    override fun paintComponent(g: Graphics) {
      // Everything is painted by the paintChildren method.
    }

    override fun paintBorder(g: Graphics) {
      // Everything is painted by the paintChildren method.
    }

    override fun paintChildren(g: Graphics) {
      if (width > 0 && height > 0) {
        val outsideShape = createOutsideShape()
        bufferingPainter.paintBuffered(g, size) { paintWithTransparentCorners(it, outsideShape) }
      }
    }

    private fun paintWithTransparentCorners(g2: Graphics2D, outsideShape: Shape) {
      setupAAPainting(g2)
      // Paint background.
      if (background != null) {
        g2.color = background
        g2.fillRect(0, 0, width, height)
      }
      // Paint children.
      super.paintChildren(g2)
      // Make corners transparent
      clearArea(g2, outsideShape)
      // Paint border.
      g2.color = SEPARATOR_COLOR
      g2.draw(createRoundRectangle(0, 0, width - 1, height - 1, cornerRadius))
    }

    private fun clearArea(g2: Graphics2D, area: Shape) {
      val config = disableAAPainting(g2) // Disable antialiasing for speed.
      val composite = g2.composite
      g2.composite = AlphaComposite.Clear
      g2.fill(area)
      g2.composite = composite
      config.restore()
    }

    private fun createOutsideShape(): Shape {
      return Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
        append(createRoundRectangle(0, 0, width - 1, height - 1, cornerRadius), false)
        append(Rectangle(0, 0, width, height), false)
      }
    }

    override fun getMaximumSize(): Dimension =
      if (containsActionButtonsOtherThanCollapser()) toolbar.component.preferredSize + insets else ZERO_DIMENSION

    private fun isVisibleWhenCollapsed(): Boolean {
      val selector = collapsedStateSelector ?: return false
      return containsActionButtonsOtherThanCollapser() && actionButtons.any { it.isVisible && selector(it) }
    }

    private fun containsActionButtonsOtherThanCollapser(): Boolean =
      toolbar.component.isVisible && actionButtons.any { it.isVisible && it.action !is CollapserAction }

    private inner class Layout : AbstractLayoutManager() {

      private val orientation
        get() = toolbar.orientation

      override fun preferredLayoutSize(parent: Container): Dimension =
        if (containsActionButtonsOtherThanCollapser()) toolbar.component.preferredSize + insets else ZERO_DIMENSION

      override fun layoutContainer(parent: Container) {
        val insets = insets
        val toolbarSize = toolbar.component.preferredSize
        val offset = calculateToolbarOffset(size - insets, toolbarSize)
        toolbar.component.setBounds(insets.left + offset.width, insets.top + offset.height, toolbarSize.width, toolbarSize.height)
      }

      private fun calculateToolbarOffset(availableSize: Dimension, preferredToolbarSize: Dimension): Dimension {
        val insets = toolbar.component.insets
        val available = (availableSize - insets)[orientation]
        val preferred = (preferredToolbarSize - insets)[orientation]
        if (preferred <= available) {
          return ZERO_DIMENSION
        }
        val anchorExtent = locateAnchorButton() ?: return ZERO_DIMENSION
        if (preferred <= anchorExtent.size) {
          return ZERO_DIMENSION
        }
        val d = anchorExtent.offset.scaled(preferred - available, preferred - anchorExtent.size)
        return if (orientation == HORIZONTAL) Dimension(-d, 0) else Dimension(0, -d)
      }

      private fun locateAnchorButton(): Extent? {
        var offset = 0
        var firstEnabled: Extent? = null
        var firstVisible: Extent? = null
        for (child in toolbar.component.components) {
          val childSize = child.preferredSize[orientation]
          if (child is ActionButton && child.isVisible) {
            if (collapsedStateSelector?.invoke(child) == true) {
              return Extent(offset, childSize)
            }
            if (firstEnabled == null && child.isEnabled) {
              firstEnabled = Extent(offset, childSize)
            }
            if (firstVisible == null) {
              firstVisible = Extent(offset, childSize)
            }
          }
          offset += childSize
        }
        return firstEnabled ?: firstVisible
      }
    }

    class Extent(val offset: Int, val size: Int)
  }

  private inline fun forEachChildBounds(factor: Double, action: (Component, Rectangle) -> Unit) {
    val expandedSize = computeSize(collapsed = false)
    val collapsedSize = computeSize(collapsed = true)
    val insets = insets
    val rect = Rectangle(insets.left, insets.top, width - insets.left - insets.right, height - insets.top - insets.bottom)
    val totalExpansion = (expandedSize[orientation] - collapsedSize[orientation]).scaled(factor)
    var expansion = totalExpansion

    val children = components
    val childSizes = Array(children.size) { children[it].preferredSize }
    val nonEmptyIndices = children.indices.filter { childSizes[it][orientation] > 0 }
    val lengths = IntArray(children.size)
    val spacers = IntArray(children.size)

    if (nonEmptyIndices.isNotEmpty()) {
      val minLengths = IntArray(children.size) { children[it].preferredSize(collapsed = true)[orientation] }
      val anchorPos = nonEmptyIndices.indexOfFirst { minLengths[it] > 0 }.let { if (it >= 0) it else 0 }

      // Allocate expansion starting from the first collapsed-visible child forward, then outward to earlier children
      // so that spacers open before collapsed-hidden toolbars emerge.
      var hasPreviousCollapsedChild = false
      for (pos in anchorPos until nonEmptyIndices.size) {
        val idx = nonEmptyIndices[pos]
        val minLen = minLengths[idx]
        if (pos > anchorPos) {
          spacers[idx] =
            if (hasPreviousCollapsedChild && minLen > 0) {
              SPACER_SIZE
            } else {
              min(expansion, SPACER_SIZE).also { expansion -= it }
            }
        }
        val childExpansion = min(expansion, childSizes[idx][orientation] - minLen)
        lengths[idx] = minLen + childExpansion
        expansion -= childExpansion
        if (minLen > 0) {
          hasPreviousCollapsedChild = true
        }
      }

      for (pos in anchorPos - 1 downTo 0) {
        val idx = nonEmptyIndices[pos]
        val nextIdx = nonEmptyIndices[pos + 1]
        spacers[nextIdx] = min(expansion, SPACER_SIZE).also { expansion -= it }
        val childExpansion = min(expansion, childSizes[idx][orientation])
        lengths[idx] = childExpansion
        expansion -= childExpansion
      }
    }

    val offset = Dimension()
    offset[orientation] = rect.size[orientation] - collapsedSize[orientation] - totalExpansion
    for (idx in children.indices) {
      val child = children[idx]
      if (childSizes[idx][orientation] == 0) {
        action(child, Rectangle(offset.width, offset.height, 0, 0))
        continue
      }
      offset.increment(orientation, spacers[idx])
      val size = childSizes[idx]
      size[orientation] = lengths[idx]
      action(child, Rectangle(offset.width, offset.height, size.width, size.height))
      offset.increment(orientation, size[orientation])
    }
  }

  private fun computeSize(collapsed: Boolean): Dimension {
    val size = Dimension()
    var pendingSpacer = false
    for (child in components) {
      val childSize = child.preferredSize(collapsed)
      if (childSize[orientation] != 0) {
        if (pendingSpacer) {
          size.increment(orientation, SPACER_SIZE)
        }
        size.combine(orientation, childSize)
        pendingSpacer = true
      }
    }
    return size
  }

  private inner class Layout : AbstractLayoutManager() {

    override fun preferredLayoutSize(container: Container): Dimension {
      require(container == this@FloatingToolbarContainer)
      return computeSize(collapsed = false)
    }

    override fun layoutContainer(container: Container) {
      require(container == this@FloatingToolbarContainer)
      forEachChildBounds(expansionFactor) { child, bounds -> child.bounds = bounds }
    }
  }

  /**
   * Action that triggers collapse of the floating toolbar. The value of the [horizontal] parameter must match orientation of the containing
   * toolbar.
   */
  class CollapserAction(
    private val horizontal: Boolean,
    private val onOrientationChange: ((Boolean) -> Unit)? = null,
  ) :
    DumbAwareAction(
      "Collapse Toolbar",
      null,
      if (horizontal) AllIcons.Actions.ArrowExpand else AllIcons.Actions.FindAndShowNextMatches,
    ),
    CustomComponentAction {

    override fun actionPerformed(event: AnActionEvent) {
      triggerDeactivation(event)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
      presentation.putClientProperty(ActionButton.CUSTOM_HELP_TOOLTIP, createTooltip())
      val minimumSize =
        if (horizontal) {
          JBDimension(0, DEFAULT_MINIMUM_BUTTON_SIZE.height, true)
        } else {
          JBDimension(DEFAULT_MINIMUM_BUTTON_SIZE.width, 0, true)
        }
      return ActionButton(this, presentation, place, minimumSize).apply {
        if (onOrientationChange != null) {
          addMouseListener(createPopupHandler(place))
        }
      }
    }

    private fun createTooltip(): HelpTooltip {
      val tooltipText =
        if (onOrientationChange == null) {
          HtmlChunk.text("Click to collapse the toolbar.")
        } else {
          HtmlChunk.fragment(
            HtmlChunk.text("Click to collapse the toolbar."),
            HtmlChunk.br(),
            HtmlChunk.text("Right-click to change the toolbar orientation."),
          )
        }
      return HelpTooltip().setTitle(tooltipText)
    }

    private fun createPopupHandler(place: String): PopupHandler =
      object : PopupHandler() {
        override fun invokePopup(comp: Component?, x: Int, y: Int) {
          if (comp != null) {
            HelpTooltip.hide(comp)
          }
          val group =
            DefaultActionGroup(
              OrientationAction("Vertical", orientationHorizontal = false),
              OrientationAction("Horizontal", orientationHorizontal = true),
            )
          val popupMenu = ActionManager.getInstance().createActionPopupMenu(place, group)
          if (comp is JComponent) {
            popupMenu.setTargetComponent(comp)
          }
          popupMenu.component.show(comp, x, y)
        }
      }

    private inner class OrientationAction(text: String, private val orientationHorizontal: Boolean) : ToggleAction(text), DumbAware {

      init {
        templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never // Don't keep the popup open after toggling.
      }

      override fun isSelected(event: AnActionEvent): Boolean = horizontal == orientationHorizontal

      override fun setSelected(event: AnActionEvent, state: Boolean) {
        if (state) {
          onOrientationChange?.invoke(orientationHorizontal)
        }
      }

      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
  }

  companion object {

    /**
     * Returns the [FloatingToolbarContainer] associated with the given [event], if any. The [event] has to be triggered by a mouse event on
     * a button of that toolbar.
     */
    fun fromActionEvent(event: AnActionEvent): FloatingToolbarContainer? =
      event.dataContext.getData(FLOATING_TOOLBAR_KEY) as? FloatingToolbarContainer

    /** Activates the floating toolbar. The action [event] has to be triggered by a mouse event on a button of that toolbar. */
    fun triggerActivation(event: AnActionEvent) {
      fromActionEvent(event)?.triggerActivation()
    }

    /** Deactivates the floating toolbar. The [event] has to be triggered by a mouse event on a button of that toolbar. */
    fun triggerDeactivation(event: AnActionEvent) {
      fromActionEvent(event)?.triggerDeactivation()
    }

    /** Toggles activation state of the floating toolbar. The [event] has to be triggered by a mouse event on a button of that toolbar. */
    fun toggleActiveState(event: AnActionEvent) {
      fromActionEvent(event)?.toggleActiveState()
    }

    @Suppress("SameParameterValue")
    private fun createRoundRectangle(x: Int, y: Int, w: Int, h: Int, cornerRadius: Int): RoundRectangle2D =
      RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), cornerRadius.toDouble(), cornerRadius.toDouble())

    private fun Component.preferredSize(collapsed: Boolean): Dimension = if (collapsed) collapsedSize() else preferredSize

    private fun Component.collapsedSize(): Dimension = (this as? ToolbarPanel)?.collapsedSize ?: ZERO_DIMENSION

    private fun ActionToolbar.configureToolbar() {
      layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
      minimumButtonSize = DEFAULT_MINIMUM_BUTTON_SIZE
      ((this as? ActionToolbarImpl)?.setActionButtonBorder(1, 1))
      component.apply {
        border = JBUI.Borders.empty(2)
        isOpaque = false
        putClientProperty(ActionToolbarImpl.IMPORTANT_TOOLBAR_KEY, true)
      }
      makeNavigable()
    }

    private fun Dimension.combine(@Orientation orientation: Int, other: Dimension) {
      if (orientation == HORIZONTAL) {
        width += other.width
        height = max(height, other.height)
      } else {
        width = max(width, other.width)
        height += other.height
      }
    }

    private fun Dimension.increment(@Orientation orientation: Int, value: Int) {
      if (orientation == HORIZONTAL) {
        width += value
      } else {
        height += value
      }
    }

    private operator fun Dimension.set(@Orientation orientation: Int, value: Int) {
      if (orientation == HORIZONTAL) {
        width = value
      } else {
        height = value
      }
    }

    private operator fun Dimension.get(@Orientation orientation: Int): Int = if (orientation == HORIZONTAL) width else height

    private operator fun Dimension.minus(insets: Insets): Dimension =
      Dimension(width - insets.left - insets.right, height - insets.top - insets.bottom)

    private operator fun Dimension.plus(insets: Insets): Dimension =
      Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)

    private operator fun Point.get(@Orientation orientation: Int): Int = if (orientation == HORIZONTAL) x else y

    private operator fun Point.minus(point: Point): Point = Point(x - point.x, y - point.y)

    private fun Int.scaled(numerator: Int, denominator: Int): Int = ((this.toLong() * numerator + denominator / 2) / denominator).toInt()

    private fun numFrames(durationMillis: Int): Int = max(durationMillis.scaled(ANIMATION_FRAMES_PER_SECOND, 1000), 1)

    private const val ANIMATION_FRAMES_PER_SECOND = 60
    private const val ACTIVATION_ANIMATION_DURATION_MILLIS = 100
    private const val COLLAPSE_ANIMATION_DURATION_MILLIS = 100
    private const val COLLAPSE_ANIMATION_DURATION_SLOW_MILLIS = 400
    private const val COLLAPSE_DELAY_MILLIS = 2000
    private const val ACTIVE_ALPHA = 1.0

    private val ZERO_DIMENSION = Dimension()

    private val SPACER_SIZE = DEFAULT_MINIMUM_BUTTON_SIZE.width / 3
  }
}
