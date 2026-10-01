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
package com.android.tools.idea.streaming.core

import com.android.tools.adtui.util.scaled
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ex.ToolWindowEx
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JScrollPane
import javax.swing.JViewport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Optimizes the size of the tool window and/or split panes to remove or minimize empty space around a [ZoomablePanel]. */
internal class ToolWindowSizeOptimizer(displayView: ZoomablePanel) : ToolWindowResizer(displayView) {

  fun resizeToolWindowToRemoveEmptySpace() {
    val toolWindow = DataManager.getInstance().getDataContext(displayView).getData(PlatformDataKeys.TOOL_WINDOW) as? ToolWindowEx ?: return
    val currentScale = displayView.scale
    val scrollPane = (displayView.parent as? JViewport)?.parent as? JScrollPane
    if (currentScale <= 1.0) {
      displayView.resetZoom()
      scrollPane?.validate()
    }
    val viewport = scrollPane ?: (displayView.parent as? JViewport) ?: displayView
    val availableWidth = viewport.width
    val availableHeight = viewport.height
    if (availableWidth <= 0 || availableHeight <= 0) {
      return
    }
    val actualSize = displayView.computeActualSize(displayView.framing)
    if (actualSize.width <= 0 || actualSize.height <= 0) {
      return
    }

    val rootContainer = findRootContainer()
    val isDetached = toolWindow.isDetached
    val canResizeToolWindowWidth = isDetached || !toolWindow.anchor.isHorizontal
    val canResizeToolWindowHeight = isDetached || toolWindow.anchor.isHorizontal
    val hasHorizontalSplitter = hasSplitterAncestor(viewport, rootContainer, isVertical = false)
    val hasVerticalSplitter = hasSplitterAncestor(viewport, rootContainer, isVertical = true)
    val canAdjustWidth = canResizeToolWindowWidth || hasHorizontalSplitter
    val canAdjustHeight = canResizeToolWindowHeight || hasVerticalSplitter

    var desiredSize =
      computeDesiredSizeForHeight(
        scrollPane,
        actualSize,
        currentScale,
        availableWidth,
        availableHeight,
        availableHeight,
        canAdjustWidth,
        canAdjustHeight,
      )
    val initialImageHeight = desiredSize.height
    val devicePanel = displayView.findAncestor<AbstractDevicePanel<*>>()
    val toolbarPanel = (devicePanel?.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
    val originalToolbarBounds = toolbarPanel?.bounds?.let { Rectangle(it) }
    val originalToolbarHeight = toolbarPanel?.height ?: 0
    var toolbarHeightDelta = 0
    if (canAdjustWidth) {
      toolbarHeightDelta =
        updateToolbarHeightForWidth(
          viewport,
          desiredSize.width,
          originalToolbarHeight,
          originalToolbarBounds,
          canResizeToolWindowWidth,
          canResizeToolWindowHeight,
        )
      if (!canResizeToolWindowHeight && toolbarHeightDelta != 0 && devicePanel != null) {
        val shareRatio = if (canAdjustHeight) computeVerticalShareRatio(viewport, devicePanel) else 1.0
        for (i in 0 until 5) {
          val effectiveHeight = (availableHeight - (toolbarHeightDelta * shareRatio).roundToInt()).coerceAtLeast(1)
          if (canAdjustHeight && initialImageHeight <= effectiveHeight) {
            break
          }
          desiredSize =
            computeDesiredSizeForHeight(
              scrollPane,
              actualSize,
              currentScale,
              availableWidth,
              availableHeight,
              effectiveHeight,
              canAdjustWidth,
              canAdjustHeight,
            )
          val newToolbarHeightDelta =
            updateToolbarHeightForWidth(
              viewport,
              desiredSize.width,
              originalToolbarHeight,
              originalToolbarBounds,
              canResizeToolWindowWidth,
              canResizeToolWindowHeight,
            )
          if (newToolbarHeightDelta == toolbarHeightDelta) {
            break
          }
          toolbarHeightDelta = newToolbarHeightDelta
        }
      }
    }

    if (desiredSize.width == availableWidth && desiredSize.height == availableHeight && toolbarHeightDelta == 0) {
      if (toolbarPanel != null && originalToolbarBounds != null) {
        toolbarPanel.bounds = Rectangle(originalToolbarBounds)
        layoutToolbarPanel(toolbarPanel)
      }
      return
    }

    applyDesiredViewportSize(
      toolWindow = toolWindow,
      viewport = viewport,
      rootContainer = rootContainer,
      desiredSize = desiredSize,
      toolbarHeightDelta = toolbarHeightDelta,
      isDetached = isDetached,
      canResizeToolWindowWidth = canResizeToolWindowWidth,
      canResizeToolWindowHeight = canResizeToolWindowHeight,
    )
    restoreScaleIfNeeded(actualSize, currentScale, scrollPane)
    toolWindow.component.revalidate()
    toolWindow.component.repaint()
  }

  private fun restoreScaleIfNeeded(actualSize: Dimension, currentScale: Double, scrollPane: JScrollPane?) {
    if (currentScale > 1.0 && displayView.scale != currentScale) {
      displayView.preferredSize =
        Dimension(
          computeLogicalSizeForScale(actualSize.width, currentScale),
          computeLogicalSizeForScale(actualSize.height, currentScale),
        )
      scrollPane?.validate()
    }
  }

  private fun computeDesiredSizeForHeight(
    scrollPane: JScrollPane?,
    actualSize: Dimension,
    currentScale: Double,
    availableWidth: Int,
    availableHeight: Int,
    targetAvailableHeight: Int,
    canAdjustWidth: Boolean,
    canAdjustHeight: Boolean,
  ): Dimension {
    if (currentScale > 1.0) {
      val imageWidth = computeLogicalSizeForScale(actualSize.width, currentScale)
      val imageHeight = computeLogicalSizeForScale(actualSize.height, currentScale)
      val verticalScrollBarWidth =
        if (!canAdjustHeight && targetAvailableHeight < imageHeight) scrollPane?.verticalScrollBar?.preferredSize?.width ?: 0 else 0
      val horizontalScrollBarHeight =
        if (!canAdjustWidth && availableWidth < imageWidth) scrollPane?.horizontalScrollBar?.preferredSize?.height ?: 0 else 0
      val width = if (canAdjustWidth) imageWidth + verticalScrollBarWidth else availableWidth
      val height = if (canAdjustHeight) imageHeight + horizontalScrollBarHeight else availableHeight
      return Dimension(width, height)
    }
    val screenScalingFactor = displayView.screenScalingFactor
    val availablePhysicalWidth = availableWidth.scaled(screenScalingFactor)
    val maxScaleX = roundDownToNaturalNumberOrNearestSmallFraction(availablePhysicalWidth.toDouble() / actualSize.width)
    val physicalHeight = targetAvailableHeight.scaled(screenScalingFactor)
    val maxScaleY = roundDownToNaturalNumberOrNearestSmallFraction(physicalHeight.toDouble() / actualSize.height)
    val fitScale = min(maxScaleX, maxScaleY)
    val imageWidth =
      if (fitScale < 1.0 && maxScaleX <= maxScaleY) availableWidth
      else computeLogicalSizeForScale(actualSize.width, fitScale).coerceAtMost(availableWidth)
    val imageHeight =
      if (fitScale < 1.0 && maxScaleY <= maxScaleX) targetAvailableHeight
      else computeLogicalSizeForScale(actualSize.height, fitScale).coerceAtMost(targetAvailableHeight)
    val width =
      when {
        !canAdjustWidth -> availableWidth
        canAdjustHeight || imageWidth < availableWidth -> imageWidth
        else -> computeLogicalSizeForScale(actualSize.width, maxScaleY)
      }
    val height =
      when {
        !canAdjustHeight -> availableHeight
        canAdjustWidth || imageHeight < targetAvailableHeight -> imageHeight
        else -> computeLogicalSizeForScale(actualSize.height, maxScaleX)
      }
    return Dimension(width, height)
  }

  private fun computeTargetWidth(
    rootContainer: Component,
    targetsByViewport: Map<Component, ViewTarget>,
    activeChildren: Map<Component, List<Component>>,
    canResizeToolWindowWidth: Boolean,
    component: Component = rootContainer,
  ): Int {
    targetsByViewport[component]?.let { target ->
      return target.desiredSize.width
    }
    val children = activeChildren[component] ?: return component.width
    return if (children.size == 1) {
      val child = children[0]
      val childWidth = computeTargetWidth(rootContainer, targetsByViewport, activeChildren, canResizeToolWindowWidth, child)
      if (childWidth == child.width) {
        component.width
      } else if (component is Splitter && !component.isVertical) {
        val canResizeSplitter = canResizeToolWindowWidth || hasSplitterAncestor(component, rootContainer, isVertical = false)
        computeSplitterSizeAndUpdateProportion(
          component,
          child === component.firstComponent,
          childWidth,
          canResizeSplitter,
          updateSplitter = true,
        )
      } else {
        childWidth + (component.width - child.width)
      }
    } else {
      val first = children[0]
      val second = children[1]
      val w1 = computeTargetWidth(rootContainer, targetsByViewport, activeChildren, canResizeToolWindowWidth, first)
      val w2 = computeTargetWidth(rootContainer, targetsByViewport, activeChildren, canResizeToolWindowWidth, second)
      if (component is Splitter && !component.isVertical) {
        if (w1 == first.width && w2 == second.width) {
          component.width
        } else {
          val canResizeSplitter = canResizeToolWindowWidth || hasSplitterAncestor(component, rootContainer, isVertical = false)
          computeTwoChildSplitterSizeAndUpdateProportion(component, w1, w2, canResizeSplitter)
        }
      } else {
        max(w1, w2)
      }
    }
  }

  private fun computeToolbarHeightDeltas(
    rootContainer: Component,
    allocatedWidth: Int,
    activeChildren: Map<Component, List<Component>>,
    canResizeToolWindowWidth: Boolean,
  ): Map<AbstractDevicePanel<*>, Int> {
    val toolbarHeightDeltas = HashMap<AbstractDevicePanel<*>, Int>()
    allocateWidth(rootContainer, allocatedWidth, rootContainer, activeChildren, canResizeToolWindowWidth, toolbarHeightDeltas)
    return toolbarHeightDeltas
  }

  private fun allocateWidth(
    c: Component,
    allocatedWidth: Int,
    rootContainer: Component,
    activeChildren: Map<Component, List<Component>>,
    canResizeToolWindowWidth: Boolean,
    toolbarHeightDeltas: MutableMap<AbstractDevicePanel<*>, Int>,
  ) {
    if (c is AbstractDevicePanel<*>) {
      val toolbarPanel = (c.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
      if (toolbarPanel != null) {
        val originalBounds = Rectangle(toolbarPanel.bounds)
        val canAdjustPanelWidth = canResizeToolWindowWidth || hasSplitterAncestor(c, rootContainer, isVertical = false)
        if (canAdjustPanelWidth) {
          toolbarHeightDeltas[c] = updateDevicePanelToolbarHeightForWidth(c, allocatedWidth, toolbarPanel.height, originalBounds)
        }
      }
    }
    val children = activeChildren[c] ?: return
    if (c is Splitter) {
      if (c.isVertical) {
        for (child in children) {
          allocateWidth(child, allocatedWidth, rootContainer, activeChildren, canResizeToolWindowWidth, toolbarHeightDeltas)
        }
      } else {
        val total = (allocatedWidth - c.dividerWidth).coerceAtLeast(0)
        val first = c.firstComponent
        val second = c.secondComponent
        val minFirst = if (c.isHonorMinimumSize && first != null) first.minimumSize.width else 0
        val minSecond = if (c.isHonorMinimumSize && second != null) second.minimumSize.width else 0
        var firstWidth = (total * c.proportion.toDouble()).roundToInt()
        var secondWidth = total - firstWidth
        if (firstWidth < minFirst) {
          firstWidth = minFirst.coerceAtMost(total)
          secondWidth = (total - firstWidth).coerceAtLeast(0)
        } else if (secondWidth < minSecond) {
          secondWidth = minSecond.coerceAtMost(total)
          firstWidth = (total - secondWidth).coerceAtLeast(0)
        }
        for (child in children) {
          allocateWidth(
            child,
            if (child === first) firstWidth else secondWidth,
            rootContainer,
            activeChildren,
            canResizeToolWindowWidth,
            toolbarHeightDeltas,
          )
        }
      }
    } else {
      for (child in children) {
        allocateWidth(
          child,
          allocatedWidth - (c.width - child.width),
          rootContainer,
          activeChildren,
          canResizeToolWindowWidth,
          toolbarHeightDeltas,
        )
      }
    }
  }

  private fun computeTargetHeight(
    rootContainer: Component,
    targetsByViewport: Map<Component, ViewTarget>,
    activeChildren: Map<Component, List<Component>>,
    toolbarHeightDeltas: Map<AbstractDevicePanel<*>, Int>,
    canResizeToolWindowHeight: Boolean,
    c: Component = rootContainer,
  ): Int {
    targetsByViewport[c]?.let { target ->
      return target.desiredSize.height
    }
    val children = activeChildren[c] ?: return c.height
    var height =
      if (children.size == 1) {
        val child = children[0]
        val childHeight =
          computeTargetHeight(rootContainer, targetsByViewport, activeChildren, toolbarHeightDeltas, canResizeToolWindowHeight, child)
        if (childHeight == child.height) {
          c.height
        } else if (c is Splitter && c.isVertical) {
          val canResizeSplitter = canResizeToolWindowHeight || hasSplitterAncestor(c, rootContainer, isVertical = true)
          computeSplitterSizeAndUpdateProportion(
            c,
            child === c.firstComponent,
            childHeight,
            canResizeSplitter,
            updateSplitter = true,
          )
        } else {
          childHeight + (c.height - child.height)
        }
      } else {
        val first = children[0]
        val second = children[1]
        val h1 =
          computeTargetHeight(rootContainer, targetsByViewport, activeChildren, toolbarHeightDeltas, canResizeToolWindowHeight, first)
        val h2 =
          computeTargetHeight(rootContainer, targetsByViewport, activeChildren, toolbarHeightDeltas, canResizeToolWindowHeight, second)
        if (c is Splitter && c.isVertical) {
          if (h1 == first.height && h2 == second.height) {
            c.height
          } else {
            val canResizeSplitter = canResizeToolWindowHeight || hasSplitterAncestor(c, rootContainer, isVertical = true)
            computeTwoChildSplitterSizeAndUpdateProportion(c, h1, h2, canResizeSplitter)
          }
        } else {
          max(h1, h2)
        }
      }
    if (c is AbstractDevicePanel<*>) {
      height += toolbarHeightDeltas[c] ?: 0
    }
    return height
  }

  private class ViewTarget(
    val optimizer: ToolWindowSizeOptimizer,
    val scrollPane: JScrollPane?,
    val viewport: Component,
    val actualSize: Dimension,
    val currentScale: Double,
    val desiredSize: Dimension,
  )

  companion object {
    /** Optimizes the size of [toolWindow] and its split panes to remove or minimize empty space around all visible device displays. */
    fun optimizeSize(toolWindow: ToolWindowEx, preDetachedSize: Dimension? = null) {
      val contentManager = toolWindow.contentManagerIfCreated ?: return
      val displayViews =
        contentManager.contentsRecursively
          .asSequence()
          .filter { it.isSelected }
          .mapNotNull { it.component as? AbstractDevicePanel<*> }
          .flatMap { it.displayPanels }
          .map { it.displayView }
          .filter { it.isVisible && it.width > 0 && it.height > 0 }
          .toList()
      if (displayViews.isEmpty()) {
        return
      }
      val firstOptimizer = ToolWindowSizeOptimizer(displayViews.first())
      val rootContainer = firstOptimizer.findRootContainer()
      if (toolWindow.isDetached && preDetachedSize != null && preDetachedSize.width > 0 && preDetachedSize.height > 0) {
        if (rootContainer.size != preDetachedSize) {
          firstOptimizer.applyTargetRootSize(toolWindow, rootContainer, preDetachedSize, isDetached = true)
        } else {
          rootContainer.validate()
        }
      }

      if (displayViews.size == 1) {
        firstOptimizer.resizeToolWindowToRemoveEmptySpace()
        return
      }
      optimizeMultipleViews(toolWindow, displayViews, firstOptimizer, rootContainer)
    }

    private fun optimizeMultipleViews(
      toolWindow: ToolWindowEx,
      displayViews: List<ZoomablePanel>,
      firstOptimizer: ToolWindowSizeOptimizer,
      rootContainer: Component,
    ) {
      val isDetached = toolWindow.isDetached
      val canResizeToolWindowWidth = isDetached || !toolWindow.anchor.isHorizontal
      val canResizeToolWindowHeight = isDetached || toolWindow.anchor.isHorizontal

      val targets = createViewTargets(displayViews, rootContainer, canResizeToolWindowWidth, canResizeToolWindowHeight)
      if (targets.isEmpty()) {
        return
      }

      val targetsByViewport = targets.associateBy { it.viewport }
      val activeChildren = collectActiveChildren(targets, rootContainer)

      val targetRootWidth = firstOptimizer.computeTargetWidth(rootContainer, targetsByViewport, activeChildren, canResizeToolWindowWidth)
      val allocatedRootWidth = if (canResizeToolWindowWidth) targetRootWidth else rootContainer.width
      val toolbarHeightDeltas =
        firstOptimizer.computeToolbarHeightDeltas(rootContainer, allocatedRootWidth, activeChildren, canResizeToolWindowWidth)
      val targetRootHeight =
        firstOptimizer.computeTargetHeight(
          rootContainer,
          targetsByViewport,
          activeChildren,
          toolbarHeightDeltas,
          canResizeToolWindowHeight,
        )

      firstOptimizer.applyTargetRootSize(toolWindow, rootContainer, Dimension(targetRootWidth, targetRootHeight), isDetached)
      for (target in targets) {
        target.optimizer.restoreScaleIfNeeded(target.actualSize, target.currentScale, target.scrollPane)
      }
      toolWindow.component.revalidate()
      toolWindow.component.repaint()
    }

    private fun createViewTargets(
      displayViews: List<ZoomablePanel>,
      rootContainer: Component,
      canResizeToolWindowWidth: Boolean,
      canResizeToolWindowHeight: Boolean,
    ): List<ViewTarget> {
      val targets = mutableListOf<ViewTarget>()
      for (displayView in displayViews) {
        val optimizer = ToolWindowSizeOptimizer(displayView)
        val currentScale = displayView.scale
        val scrollPane = (displayView.parent as? JViewport)?.parent as? JScrollPane
        if (currentScale <= 1.0) {
          displayView.resetZoom()
          scrollPane?.validate()
        }
        val viewport = scrollPane ?: (displayView.parent as? JViewport) ?: displayView
        val availableWidth = viewport.width
        val availableHeight = viewport.height
        if (availableWidth <= 0 || availableHeight <= 0) {
          continue
        }
        val actualSize = displayView.computeActualSize(displayView.framing)
        if (actualSize.width <= 0 || actualSize.height <= 0) {
          continue
        }
        val canAdjustWidth = canResizeToolWindowWidth || optimizer.hasSplitterAncestor(viewport, rootContainer, isVertical = false)
        val canAdjustHeight = canResizeToolWindowHeight || optimizer.hasSplitterAncestor(viewport, rootContainer, isVertical = true)
        val desiredSize =
          optimizer.computeDesiredSizeForHeight(
            scrollPane,
            actualSize,
            currentScale,
            availableWidth,
            availableHeight,
            availableHeight,
            canAdjustWidth,
            canAdjustHeight,
          )
        targets.add(ViewTarget(optimizer, scrollPane, viewport, actualSize, currentScale, desiredSize))
      }
      return targets
    }

    private fun collectActiveChildren(
      targets: List<ViewTarget>,
      rootContainer: Component,
    ): Map<Component, List<Component>> {
      val activeChildren = LinkedHashMap<Component, MutableList<Component>>()
      for (target in targets) {
        var c: Component = target.viewport
        while (c !== rootContainer) {
          val p = c.parent ?: break
          val children = activeChildren.getOrPut(p) { mutableListOf() }
          if (c !in children) {
            if (p is Splitter && c === p.firstComponent && children.isNotEmpty()) {
              children.add(0, c)
            } else {
              children.add(c)
            }
          }
          c = p
        }
      }
      return activeChildren
    }
  }
}
