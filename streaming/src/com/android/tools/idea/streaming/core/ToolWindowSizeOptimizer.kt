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
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JScrollPane
import javax.swing.JViewport
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

    val screenScalingFactor = displayView.screenScalingFactor
    val availablePhysicalWidth = availableWidth.scaled(screenScalingFactor)
    val maxScaleX = roundDownToNaturalNumberOrNearestSmallFraction(availablePhysicalWidth.toDouble() / actualSize.width)

    val rootContainer = findRootContainer()
    val isUndocked = toolWindow.type == ToolWindowType.WINDOWED || toolWindow.type == ToolWindowType.FLOATING
    val canResizeToolWindowWidth = isUndocked || !toolWindow.anchor.isHorizontal
    val canResizeToolWindowHeight = isUndocked || toolWindow.anchor.isHorizontal
    val hasHorizontalSplitter = hasSplitterAncestor(viewport, rootContainer, isVertical = false)
    val hasVerticalSplitter = hasSplitterAncestor(viewport, rootContainer, isVertical = true)
    val canAdjustWidth = canResizeToolWindowWidth || hasHorizontalSplitter
    val canAdjustHeight = canResizeToolWindowHeight || hasVerticalSplitter

    fun computeDesiredSizeForHeight(targetAvailableHeight: Int): Dimension {
      if (currentScale > 1.0) {
        val imageWidth = computeLogicalSizeForScale(actualSize.width, currentScale)
        val imageHeight = computeLogicalSizeForScale(actualSize.height, currentScale)
        val vsbWidth =
          if (!canAdjustHeight && targetAvailableHeight < imageHeight) scrollPane?.verticalScrollBar?.preferredSize?.width ?: 0 else 0
        val hsbHeight =
          if (!canAdjustWidth && availableWidth < imageWidth) scrollPane?.horizontalScrollBar?.preferredSize?.height ?: 0 else 0
        val width = if (canAdjustWidth) imageWidth + vsbWidth else availableWidth
        val height = if (canAdjustHeight) imageHeight + hsbHeight else availableHeight
        return Dimension(width, height)
      }
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

    var desiredSize = computeDesiredSizeForHeight(availableHeight)
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
          desiredSize = computeDesiredSizeForHeight(effectiveHeight)
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

    if (desiredSize.width == availableWidth && desiredSize.height == availableHeight) {
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
      isUndocked = isUndocked,
      canResizeToolWindowWidth = canResizeToolWindowWidth,
      canResizeToolWindowHeight = canResizeToolWindowHeight,
    )
    if (currentScale > 1.0 && displayView.scale != currentScale) {
      displayView.preferredSize =
        Dimension(
          computeLogicalSizeForScale(actualSize.width, currentScale),
          computeLogicalSizeForScale(actualSize.height, currentScale),
        )
      scrollPane?.validate()
    }
    toolWindow.component.revalidate()
    toolWindow.component.repaint()
  }
}
