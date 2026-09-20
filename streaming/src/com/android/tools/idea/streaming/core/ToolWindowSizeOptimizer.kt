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
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.InternalDecorator
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Optimizes the size of the tool window and/or split panes to remove or minimize empty space around a [ZoomablePanel]. */
internal class ToolWindowSizeOptimizer(private val displayView: ZoomablePanel) {

  fun resizeToolWindowToRemoveEmptySpace() {
    val toolWindow = DataManager.getInstance().getDataContext(displayView).getData(PlatformDataKeys.TOOL_WINDOW) as? ToolWindowEx ?: return
    displayView.resetZoom()
    (displayView.parent as? JViewport)?.let { viewport ->
      (viewport.parent as? JScrollPane)?.validate()
    }
    val viewport = (displayView.parent as? JViewport) ?: displayView
    val availableWidth = viewport.width
    val availableHeight = viewport.height
    if (availableWidth <= 0 || availableHeight <= 0) {
      return
    }
    val actualSize = displayView.computeActualSize(displayView.framing)
    if (actualSize.width <= 0 || actualSize.height <= 0) {
      return
    }

    val screenScalingFactor = displayView.screenScalingFactor
    val availablePhysicalWidth = availableWidth.scaled(screenScalingFactor)
    val availablePhysicalHeight = availableHeight.scaled(screenScalingFactor)
    val maxScaleX = roundDownToNaturalNumberOrNearestSmallFraction(availablePhysicalWidth.toDouble() / actualSize.width)
    val maxScaleY = roundDownToNaturalNumberOrNearestSmallFraction(availablePhysicalHeight.toDouble() / actualSize.height)
    val fitScale = min(maxScaleX, maxScaleY)
    val imageWidth = computeLogicalWidthForScale(fitScale).coerceAtMost(availableWidth)
    val imageHeight = computeLogicalHeightForScale(fitScale).coerceAtMost(availableHeight)

    val rootContainer = findRootContainer()
    val isUndocked = toolWindow.type == ToolWindowType.WINDOWED || toolWindow.type == ToolWindowType.FLOATING
    val canResizeToolWindowWidth = isUndocked || !toolWindow.anchor.isHorizontal
    val canResizeToolWindowHeight = isUndocked || toolWindow.anchor.isHorizontal
    val hasHorizontalSplitter = hasSplitterAncestor(viewport, rootContainer, isVertical = false)
    val hasVerticalSplitter = hasSplitterAncestor(viewport, rootContainer, isVertical = true)
    val canAdjustWidth = canResizeToolWindowWidth || hasHorizontalSplitter
    val canAdjustHeight = canResizeToolWindowHeight || hasVerticalSplitter

    var desiredWidth: Int
    var desiredHeight: Int
    val devicePanel = displayView.findAncestor<AbstractDevicePanel<*>>()
    val toolbarPanel = (devicePanel?.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
    val originalToolbarBounds = toolbarPanel?.bounds?.let { Rectangle(it) }
    val originalToolbarHeight = toolbarPanel?.height ?: 0
    var toolbarHeightDelta = 0
    when {
      canAdjustWidth && canAdjustHeight -> {
        desiredWidth = imageWidth
        desiredHeight = imageHeight
        toolbarHeightDelta =
          updateToolbarHeightForWidth(
            viewport,
            desiredWidth,
            originalToolbarHeight,
            originalToolbarBounds,
            canResizeToolWindowWidth,
            canResizeToolWindowHeight,
          )
        if (!canResizeToolWindowHeight && imageHeight == availableHeight && toolbarHeightDelta > 0 && devicePanel != null) {
          val shareRatio = computeVerticalShareRatio(viewport, rootContainer)
          val effectiveHeight = (availableHeight - (toolbarHeightDelta * shareRatio).roundToInt()).coerceAtLeast(1)
          val effectivePhysicalHeight = effectiveHeight.scaled(screenScalingFactor)
          val adjustedMaxScaleY = roundDownToNaturalNumberOrNearestSmallFraction(effectivePhysicalHeight.toDouble() / actualSize.height)
          val adjustedFitScale = min(maxScaleX, adjustedMaxScaleY)
          desiredWidth = computeLogicalWidthForScale(adjustedFitScale).coerceAtMost(availableWidth)
          desiredHeight = computeLogicalHeightForScale(adjustedFitScale).coerceAtMost(effectiveHeight)
          toolbarHeightDelta =
            updateToolbarHeightForWidth(
              viewport,
              desiredWidth,
              originalToolbarHeight,
              originalToolbarBounds,
              canResizeToolWindowWidth,
              canResizeToolWindowHeight,
            )
        }
      }
      canAdjustWidth -> {
        // Only width can be adjusted (e.g., docked LEFT or RIGHT without a vertical splitter).
        desiredWidth = if (imageWidth < availableWidth) imageWidth else computeLogicalWidthForScale(maxScaleY)
        toolbarHeightDelta =
          updateToolbarHeightForWidth(
            viewport,
            desiredWidth,
            originalToolbarHeight,
            originalToolbarBounds,
            canResizeToolWindowWidth,
            canResizeToolWindowHeight,
          )
        if (toolbarHeightDelta != 0 && devicePanel != null) {
          val shareRatio = computeVerticalShareRatio(viewport, rootContainer)
          val effectiveHeight = (availableHeight - (toolbarHeightDelta * shareRatio).roundToInt()).coerceAtLeast(1)
          val effectivePhysicalHeight = effectiveHeight.scaled(screenScalingFactor)
          val adjustedMaxScaleY = roundDownToNaturalNumberOrNearestSmallFraction(effectivePhysicalHeight.toDouble() / actualSize.height)
          val adjustedFitScale = min(maxScaleX, adjustedMaxScaleY)
          val adjustedImageWidth = computeLogicalWidthForScale(adjustedFitScale).coerceAtMost(availableWidth)
          desiredWidth = if (adjustedImageWidth < availableWidth) adjustedImageWidth else computeLogicalWidthForScale(adjustedMaxScaleY)
          toolbarHeightDelta =
            updateToolbarHeightForWidth(
              viewport,
              desiredWidth,
              originalToolbarHeight,
              originalToolbarBounds,
              canResizeToolWindowWidth,
              canResizeToolWindowHeight,
            )
        }
        desiredHeight = availableHeight
      }
      else -> {
        // Only height can be adjusted (e.g., docked TOP or BOTTOM without a horizontal splitter).
        desiredWidth = availableWidth
        desiredHeight = if (imageHeight < availableHeight) imageHeight else computeLogicalHeightForScale(maxScaleX)
      }
    }

    if (desiredWidth == availableWidth && desiredHeight == availableHeight) {
      if (toolbarPanel != null && originalToolbarBounds != null) {
        toolbarPanel.bounds = Rectangle(originalToolbarBounds)
        toolbarPanel.doLayout()
      }
      return
    }

    val targetSize =
      computeAncestorSize(
        viewport,
        rootContainer,
        desiredWidth,
        desiredHeight,
        toolbarHeightDelta,
        canResizeToolWindowWidth,
        canResizeToolWindowHeight,
        updateSplitters = true,
      )
    val deltaWidth = targetSize.width - rootContainer.width
    val deltaHeight = targetSize.height - rootContainer.height

    if (isUndocked) {
      val oldWidth = rootContainer.width
      val oldHeight = rootContainer.height
      if (deltaWidth != 0) {
        toolWindow.stretchWidth(deltaWidth)
      }
      if (deltaHeight != 0) {
        toolWindow.stretchHeight(deltaHeight)
      }
      val remainingDeltaWidth = if (rootContainer.width == oldWidth) deltaWidth else 0
      val remainingDeltaHeight = if (rootContainer.height == oldHeight) deltaHeight else 0
      if (remainingDeltaWidth != 0 || remainingDeltaHeight != 0) {
        SwingUtilities.getWindowAncestor(displayView)?.let { window ->
          val minSize = window.minimumSize
          val minWidth = (minSize?.width ?: 0).coerceAtLeast(1)
          val minHeight = (minSize?.height ?: 0).coerceAtLeast(1)
          val targetWidth = (window.width + remainingDeltaWidth).coerceAtLeast(minWidth)
          val targetHeight = (window.height + remainingDeltaHeight).coerceAtLeast(minHeight)
          window.setSize(targetWidth, targetHeight)
          window.validate()
        }
      }
    } else if (!toolWindow.anchor.isHorizontal) {
      if (deltaWidth != 0) {
        toolWindow.stretchWidth(deltaWidth)
      }
    } else {
      if (deltaHeight != 0) {
        toolWindow.stretchHeight(deltaHeight)
      }
    }
    rootContainer.validate()
    toolWindow.component.revalidate()
    toolWindow.component.repaint()
  }

  private fun hasSplitterAncestor(startComponent: Component, stopAncestor: Component, isVertical: Boolean): Boolean {
    var c: Component = startComponent
    while (c !== stopAncestor) {
      val p = c.parent ?: break
      if (p is Splitter && p.isVertical == isVertical) {
        return true
      }
      c = p
    }
    return false
  }

  private fun updateToolbarHeightForWidth(
    viewport: Component,
    newViewportWidth: Int,
    originalToolbarHeight: Int,
    originalToolbarBounds: Rectangle?,
    canResizeToolWindowWidth: Boolean,
    canResizeToolWindowHeight: Boolean,
  ): Int {
    val devicePanel = displayView.findAncestor<AbstractDevicePanel<*>>() ?: return 0
    val layout = devicePanel.layout as? BorderLayout ?: return 0
    val toolbarPanel = layout.getLayoutComponent(BorderLayout.NORTH) ?: return 0
    val newDevicePanelWidth =
      computeAncestorSize(
          viewport,
          devicePanel,
          newViewportWidth,
          viewport.height,
          0,
          canResizeToolWindowWidth,
          canResizeToolWindowHeight,
          updateSplitters = false,
        )
        .width
        .coerceAtLeast(0)
    toolbarPanel.setSize(newDevicePanelWidth, originalToolbarHeight)
    toolbarPanel.doLayout()
    for (child in (toolbarPanel as? Container)?.components.orEmpty()) {
      child.doLayout()
    }
    val newToolbarHeight = toolbarPanel.preferredSize.height
    val heightDelta = newToolbarHeight - originalToolbarHeight
    if (heightDelta != 0) {
      toolbarPanel.setSize(newDevicePanelWidth, newToolbarHeight)
    } else if (originalToolbarBounds != null) {
      toolbarPanel.bounds = Rectangle(originalToolbarBounds)
      toolbarPanel.doLayout()
    }
    return heightDelta
  }

  private fun computeVerticalShareRatio(startComponent: Component, stopAncestor: Component): Double {
    var ratio = 1.0
    var c: Component = startComponent
    while (c !== stopAncestor) {
      val p = c.parent ?: break
      if (p is Splitter && p.isVertical) {
        val other = if (c === p.firstComponent) p.secondComponent else p.firstComponent
        if (other != null && other.isVisible) {
          ratio *= if (c === p.firstComponent) p.proportion.toDouble() else (1.0 - p.proportion.toDouble())
        }
      }
      c = p
    }
    return ratio
  }

  private fun computeAncestorSize(
    startComponent: Component,
    stopAncestor: Component,
    startWidth: Int,
    startHeight: Int,
    toolbarHeightDelta: Int,
    canResizeToolWindowWidth: Boolean,
    canResizeToolWindowHeight: Boolean,
    updateSplitters: Boolean,
  ): Dimension {
    var targetWidth = startWidth
    var targetHeight = startHeight
    var c: Component = startComponent
    while (c !== stopAncestor) {
      val p = c.parent ?: break
      if (targetWidth == c.width) {
        targetWidth = p.width
      } else if (p is Splitter && !p.isVertical) {
        targetWidth =
          computeSplitterSizeAndUpdateProportion(
            p,
            c === p.firstComponent,
            targetWidth,
            canResizeToolWindowWidth,
            updateSplitters,
          )
      } else {
        targetWidth += p.width - c.width
      }

      if (targetHeight == c.height) {
        targetHeight = p.height
      } else if (p is Splitter && p.isVertical) {
        targetHeight =
          computeSplitterSizeAndUpdateProportion(
            p,
            c === p.firstComponent,
            targetHeight,
            canResizeToolWindowHeight,
            updateSplitters,
          )
      } else {
        targetHeight += p.height - c.height
      }
      if (p is AbstractDevicePanel<*>) {
        targetHeight += toolbarHeightDelta
      }
      c = p
    }
    return Dimension(targetWidth, targetHeight)
  }

  private fun computeSplitterSizeAndUpdateProportion(
    splitter: Splitter,
    isFirstComponent: Boolean,
    targetChildSize: Int,
    canResizeRoot: Boolean,
    updateSplitter: Boolean,
  ): Int {
    val currentSplitterSize = if (splitter.isVertical) splitter.height else splitter.width
    val other = if (isFirstComponent) splitter.secondComponent else splitter.firstComponent
    val otherVisible = other != null && other.isVisible
    val otherSize = if (otherVisible) (if (splitter.isVertical) other.height else other.width) else 0
    val dividerWidth = if (otherVisible) splitter.dividerWidth else 0
    val newTotal = if (canResizeRoot) otherSize + targetChildSize else currentSplitterSize - dividerWidth
    if (newTotal > 0 && updateSplitter) {
      val desiredFirstSize = if (isFirstComponent) targetChildSize else newTotal - targetChildSize
      val newProportion = ((desiredFirstSize + 0.25f) / newTotal).coerceIn(splitter.minimumProportion, splitter.maximumProportion)
      splitter.proportion = newProportion
    }
    return (newTotal + dividerWidth).coerceAtLeast(0)
  }

  private fun computeLogicalWidthForScale(targetScale: Double): Int {
    val screenScalingFactor = displayView.screenScalingFactor
    val actualWidth = displayView.computeActualSize(displayView.framing).width
    var w = ceil(actualWidth * targetScale / screenScalingFactor).toInt()
    if (roundDownToNaturalNumberOrNearestSmallFraction(w.scaled(screenScalingFactor).toDouble() / actualWidth) < targetScale) {
      w++
    }
    return w
  }

  private fun computeLogicalHeightForScale(targetScale: Double): Int {
    val screenScalingFactor = displayView.screenScalingFactor
    val actualHeight = displayView.computeActualSize(displayView.framing).height
    var h = ceil(actualHeight * targetScale / screenScalingFactor).toInt()
    if (roundDownToNaturalNumberOrNearestSmallFraction(h.scaled(screenScalingFactor).toDouble() / actualHeight) < targetScale) {
      h++
    }
    return h
  }

  private fun findRootContainer(): Component {
    var topDecorator: InternalDecorator? = null
    var root: Component = (displayView.parent as? JViewport) ?: displayView
    while (true) {
      val p = root.parent
      if (p == null || p is Window || p is JRootPane) {
        break
      }
      if (p is InternalDecorator) {
        topDecorator = p
      }
      root = p
    }
    return topDecorator ?: root
  }
}
