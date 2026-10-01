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
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JScrollPane
import javax.swing.JViewport

/**
 * Resizes a detached tool window ([ToolWindowType.WINDOWED] or [ToolWindowType.FLOATING]) to preserve the zoom level when the display
 * orientation or dimensions change and there is no empty space around [displayView].
 */
internal class DetachedToolWindowReshaper(displayView: ZoomablePanel) : ToolWindowResizer(displayView) {

  /**
   * If the tool window is detached ([ToolWindowType.WINDOWED] or [ToolWindowType.FLOATING]) and there is no empty space around
   * [displayView], resizes the tool window to fit the new content size at [oldScale] and returns true. Otherwise, returns false.
   */
  fun resizeToolWindowToPreserveZoom(oldActualSize: Dimension, oldScale: Double, oldContentRectangle: Rectangle?): Boolean {
    if (oldActualSize.width <= 0 || oldActualSize.height <= 0 || oldScale <= 0.0) {
      return false
    }
    val toolWindow =
      DataManager.getInstance().getDataContext(displayView).getData(PlatformDataKeys.TOOL_WINDOW) as? ToolWindowEx ?: return false
    val isUndocked = toolWindow.type == ToolWindowType.WINDOWED || toolWindow.type == ToolWindowType.FLOATING
    if (!isUndocked) {
      return false
    }
    val scrollPane = (displayView.parent as? JViewport)?.parent as? JScrollPane
    val viewport = scrollPane ?: (displayView.parent as? JViewport) ?: displayView
    val availableWidth = viewport.width
    val availableHeight = viewport.height
    if (availableWidth <= 0 || availableHeight <= 0) {
      return false
    }
    val newActualSize = displayView.computeActualSize(displayView.framing)
    if (newActualSize.width <= 0 || newActualSize.height <= 0) {
      return false
    }

    val targetScale = if (oldScale >= 1.0) oldScale else roundDownToNaturalNumberOrNearestSmallFraction(oldScale)
    if (!hasNoEmptySpace(viewport, oldActualSize, oldScale, oldContentRectangle, targetScale)) {
      return false
    }

    val wasPreferredSizeSet = displayView.isPreferredSizeSet
    if (oldScale <= 1.0) {
      displayView.resetZoom()
      scrollPane?.validate()
    } else if (wasPreferredSizeSet) {
      displayView.preferredSize = null
      scrollPane?.validate()
    }

    val desiredSize =
      Dimension(
        computeLogicalSizeForScale(newActualSize.width, targetScale),
        computeLogicalSizeForScale(newActualSize.height, targetScale),
      )

    val devicePanel = displayView.findAncestor<AbstractDevicePanel<*>>()
    val toolbarPanel = (devicePanel?.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
    val originalToolbarBounds = toolbarPanel?.bounds?.let { Rectangle(it) }
    val originalToolbarHeight = toolbarPanel?.height ?: 0
    val toolbarHeightDelta =
      updateToolbarHeightForWidth(
        viewport,
        desiredSize.width,
        originalToolbarHeight,
        originalToolbarBounds,
        canResizeToolWindowWidth = true,
        canResizeToolWindowHeight = true,
      )

    if (desiredSize.width == availableWidth && desiredSize.height == availableHeight) {
      if (toolbarPanel != null && originalToolbarBounds != null) {
        toolbarPanel.bounds = Rectangle(originalToolbarBounds)
        layoutToolbarPanel(toolbarPanel)
      }
    } else {
      val rootContainer = findRootContainer()
      applyDesiredViewportSize(
        toolWindow = toolWindow,
        viewport = viewport,
        rootContainer = rootContainer,
        desiredSize = desiredSize,
        toolbarHeightDelta = toolbarHeightDelta,
        isUndocked = true,
        canResizeToolWindowWidth = true,
        canResizeToolWindowHeight = true,
      )
    }

    if (oldScale > 1.0) {
      if (wasPreferredSizeSet) {
        displayView.preferredSize = desiredSize
        scrollPane?.validate()
      } else if (displayView.scale != oldScale) {
        displayView.zoom(if (displayView.framing == ZoomablePanel.Framing.INNER) ZoomType.FIT_INNER else ZoomType.FIT)
        scrollPane?.validate()
      }
    }
    toolWindow.component.revalidate()
    toolWindow.component.repaint()
    return true
  }

  private fun hasNoEmptySpace(
    viewport: Component,
    oldActualSize: Dimension,
    oldScale: Double,
    oldContentRectangle: Rectangle?,
    targetScale: Double,
  ): Boolean {
    if (displayView.width != viewport.width || displayView.height != viewport.height) {
      return false
    }

    // 1. Scaled down (< 1.0) and both width and height constrain the scale equally (tight aspect ratio fit).
    if (oldScale < 1.0 && isScaleConstrainedByBothAxes(viewport, oldActualSize)) {
      return true
    }

    // 2. Viewport dimensions exactly match the logical size at the target scale.
    if (
      viewport.width == computeLogicalSizeForScale(oldActualSize.width, targetScale) &&
        viewport.height == computeLogicalSizeForScale(oldActualSize.height, targetScale)
    ) {
      return true
    }

    // 3. In outer framing mode, content starts at (0, 0) without centering margins.
    return displayView.framing == ZoomablePanel.Framing.OUTER &&
      oldContentRectangle != null &&
      oldContentRectangle.x == 0 &&
      oldContentRectangle.y == 0
  }

  private fun isScaleConstrainedByBothAxes(viewport: Component, actualSize: Dimension): Boolean {
    val screenScalingFactor = displayView.screenScalingFactor
    val availablePhysicalWidth = viewport.width.scaled(screenScalingFactor)
    val availablePhysicalHeight = viewport.height.scaled(screenScalingFactor)
    val maxScaleX = roundDownToNaturalNumberOrNearestSmallFraction(availablePhysicalWidth.toDouble() / actualSize.width)
    val maxScaleY = roundDownToNaturalNumberOrNearestSmallFraction(availablePhysicalHeight.toDouble() / actualSize.height)
    return maxScaleX == maxScaleY
  }
}
