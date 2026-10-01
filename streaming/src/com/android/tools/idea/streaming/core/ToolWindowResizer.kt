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
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.InternalDecorator
import com.intellij.ui.ClientProperty
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JRootPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Base class for resizing a tool window and/or split panes around a [ZoomablePanel]. */
internal abstract class ToolWindowResizer(protected val displayView: ZoomablePanel) {

  protected fun applyDesiredViewportSize(
    toolWindow: ToolWindowEx,
    viewport: Component,
    rootContainer: Component,
    desiredSize: Dimension,
    toolbarHeightDelta: Int,
    isDetached: Boolean,
    canResizeToolWindowWidth: Boolean,
    canResizeToolWindowHeight: Boolean,
  ) {
    val targetSize =
      computeAncestorSize(
        viewport,
        rootContainer,
        desiredSize.width,
        desiredSize.height,
        toolbarHeightDelta,
        canResizeToolWindowWidth,
        canResizeToolWindowHeight,
        updateSplitters = true,
      )
    applyTargetRootSize(toolWindow, rootContainer, targetSize, isDetached)
  }

  protected fun applyTargetRootSize(
    toolWindow: ToolWindowEx,
    rootContainer: Component,
    targetSize: Dimension,
    isDetached: Boolean,
  ) {
    val deltaWidth = targetSize.width - rootContainer.width
    val deltaHeight = targetSize.height - rootContainer.height

    if (isDetached) {
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
        SwingUtilities.getWindowAncestor(rootContainer)?.let { window ->
          val minSize = window.minimumSize
          val minWidth = (minSize?.width ?: 0).coerceAtLeast(1)
          val minHeight = (minSize?.height ?: 0).coerceAtLeast(1)
          val targetWidth = (window.width + remainingDeltaWidth).coerceAtLeast(minWidth)
          val targetHeight = (window.height + remainingDeltaHeight).coerceAtLeast(minHeight)
          window.setSize(targetWidth, targetHeight)
          syncExternalDecoratorBounds(window)
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
  }

  protected fun hasSplitterAncestor(startComponent: Component, stopAncestor: Component, isVertical: Boolean): Boolean {
    var c: Component = startComponent
    while (c !== stopAncestor) {
      val p = c.parent ?: break
      if (p is Splitter && p.isVertical == isVertical) {
        val other = if (c === p.firstComponent) p.secondComponent else p.firstComponent
        if (other != null && other.isVisible) {
          return true
        }
      }
      c = p
    }
    return false
  }

  protected fun updateToolbarHeightForWidth(
    viewport: Component,
    newViewportWidth: Int,
    originalToolbarHeight: Int,
    originalToolbarBounds: Rectangle?,
    canResizeToolWindowWidth: Boolean,
    canResizeToolWindowHeight: Boolean,
  ): Int {
    val devicePanel = displayView.findAncestor<AbstractDevicePanel<*>>() ?: return 0
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
    return updateDevicePanelToolbarHeightForWidth(devicePanel, newDevicePanelWidth, originalToolbarHeight, originalToolbarBounds)
  }

  protected fun updateDevicePanelToolbarHeightForWidth(
    devicePanel: AbstractDevicePanel<*>,
    newDevicePanelWidth: Int,
    originalToolbarHeight: Int,
    originalToolbarBounds: Rectangle?,
  ): Int {
    val layout = devicePanel.layout as? BorderLayout ?: return 0
    val toolbarPanel = layout.getLayoutComponent(BorderLayout.NORTH) ?: return 0
    toolbarPanel.setSize(newDevicePanelWidth.coerceAtLeast(0), originalToolbarHeight)
    layoutToolbarPanel(toolbarPanel)
    val newToolbarHeight = toolbarPanel.preferredSize.height
    val heightDelta = newToolbarHeight - originalToolbarHeight
    if (heightDelta != 0) {
      toolbarPanel.setSize(newDevicePanelWidth.coerceAtLeast(0), newToolbarHeight)
      layoutToolbarPanel(toolbarPanel)
    } else if (originalToolbarBounds != null) {
      toolbarPanel.bounds = Rectangle(originalToolbarBounds)
      layoutToolbarPanel(toolbarPanel)
    }
    return heightDelta
  }

  protected fun layoutToolbarPanel(toolbarPanel: Component) {
    toolbarPanel.doLayout()
    for (child in (toolbarPanel as? Container)?.components.orEmpty()) {
      child.doLayout()
    }
  }

  protected fun computeVerticalShareRatio(startComponent: Component, stopAncestor: Component): Double {
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
    val rootContainer = findRootContainer()
    var targetWidth = startWidth
    var targetHeight = startHeight
    var c: Component = startComponent
    while (c !== stopAncestor) {
      val p = c.parent ?: break
      if (targetWidth == c.width) {
        targetWidth = p.width
      } else if (p is Splitter && !p.isVertical) {
        val canResizeSplitter = canResizeToolWindowWidth || hasSplitterAncestor(p, rootContainer, isVertical = false)
        targetWidth =
          computeSplitterSizeAndUpdateProportion(
            p,
            c === p.firstComponent,
            targetWidth,
            canResizeSplitter,
            updateSplitters,
          )
      } else {
        targetWidth += p.width - c.width
      }

      if (targetHeight == c.height) {
        targetHeight = p.height
      } else if (p is Splitter && p.isVertical) {
        val canResizeSplitter = canResizeToolWindowHeight || hasSplitterAncestor(p, rootContainer, isVertical = true)
        targetHeight =
          computeSplitterSizeAndUpdateProportion(
            p,
            c === p.firstComponent,
            targetHeight,
            canResizeSplitter,
            updateSplitters,
            toolbarHeightDelta,
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

  protected fun computeSplitterSizeAndUpdateProportion(
    splitter: Splitter,
    isFirstComponent: Boolean,
    targetChildSize: Int,
    canResizeRoot: Boolean,
    updateSplitter: Boolean,
    toolbarHeightDelta: Int = 0,
  ): Int {
    val heightAdjustment =
      if (splitter.isVertical && !canResizeRoot && toolbarHeightDelta != 0) {
        splitter.findAncestor<AbstractDevicePanel<*>>()?.let {
          -(toolbarHeightDelta * computeVerticalShareRatio(splitter, it)).roundToInt()
        } ?: 0
      } else {
        0
      }
    val currentSplitterSize = (if (splitter.isVertical) splitter.height else splitter.width) + heightAdjustment
    val child = if (isFirstComponent) splitter.firstComponent else splitter.secondComponent
    val other = if (isFirstComponent) splitter.secondComponent else splitter.firstComponent
    val otherVisible = other != null && other.isVisible
    if (!otherVisible) {
      return (if (canResizeRoot) targetChildSize else currentSplitterSize).coerceAtLeast(0)
    }
    val minChildSize = getMinimumChildSize(splitter, child)
    val minOtherSize = getMinimumChildSize(splitter, other)
    val effectiveChildSize = max(targetChildSize, minChildSize)
    val otherSize = max(if (splitter.isVertical) other.height else other.width, minOtherSize)
    val dividerWidth = splitter.dividerWidth
    var newTotal = if (canResizeRoot) otherSize + effectiveChildSize else currentSplitterSize - dividerWidth
    if (newTotal > 0) {
      val desiredFirstSize = if (isFirstComponent) effectiveChildSize else newTotal - effectiveChildSize
      val rawProportion = desiredFirstSize.toFloat() / newTotal
      val newProportion = rawProportion.coerceIn(splitter.minimumProportion, splitter.maximumProportion)
      if (canResizeRoot && newProportion != rawProportion) {
        val childShare = if (isFirstComponent) newProportion.toDouble() else 1.0 - newProportion.toDouble()
        if (childShare > 0.0) {
          newTotal = max(newTotal, (effectiveChildSize / childShare).roundToInt())
          val firstSize = (newTotal * newProportion).roundToInt()
          val actualChildSize = if (isFirstComponent) firstSize else newTotal - firstSize
          if (actualChildSize < effectiveChildSize) {
            newTotal++
          }
        }
      }
      if (updateSplitter) {
        splitter.proportion = newProportion
      }
    }
    return (newTotal + dividerWidth).coerceAtLeast(0)
  }

  protected fun computeTwoChildSplitterSizeAndUpdateProportion(
    splitter: Splitter,
    targetFirstSize: Int,
    targetSecondSize: Int,
    canResizeRoot: Boolean,
  ): Int {
    val currentSplitterSize = if (splitter.isVertical) splitter.height else splitter.width
    val first = splitter.firstComponent
    val second = splitter.secondComponent
    val firstVisible = first != null && first.isVisible
    val secondVisible = second != null && second.isVisible
    if (!firstVisible && !secondVisible) {
      return currentSplitterSize.coerceAtLeast(0)
    }
    if (!firstVisible) {
      return (if (canResizeRoot) targetSecondSize else currentSplitterSize).coerceAtLeast(0)
    }
    if (!secondVisible) {
      return (if (canResizeRoot) targetFirstSize else currentSplitterSize).coerceAtLeast(0)
    }
    val minFirst = getMinimumChildSize(splitter, first)
    val minSecond = getMinimumChildSize(splitter, second)
    val effectiveFirst = max(targetFirstSize, minFirst)
    val effectiveSecond = max(targetSecondSize, minSecond)
    val dividerWidth = splitter.dividerWidth
    var newTotal = if (canResizeRoot) effectiveFirst + effectiveSecond else currentSplitterSize - dividerWidth
    if (newTotal > 0) {
      val totalDesired = effectiveFirst + effectiveSecond
      val rawProportion =
        if (canResizeRoot) {
          effectiveFirst.toFloat() / newTotal
        } else if (totalDesired > 0) {
          effectiveFirst.toFloat() / totalDesired
        } else {
          splitter.proportion
        }
      val newProportion = rawProportion.coerceIn(splitter.minimumProportion, splitter.maximumProportion)
      if (canResizeRoot) {
        if (newProportion > 0f) {
          newTotal = max(newTotal, (effectiveFirst / newProportion.toDouble()).roundToInt())
        }
        if (newProportion < 1f) {
          newTotal = max(newTotal, (effectiveSecond / (1.0 - newProportion.toDouble())).roundToInt())
        }
        val firstSize = (newTotal * newProportion.toDouble()).roundToInt()
        if (firstSize < effectiveFirst || newTotal - firstSize < effectiveSecond) {
          newTotal++
        }
      }
      splitter.proportion = newProportion
    }
    return (newTotal + dividerWidth).coerceAtLeast(0)
  }

  protected fun computeLogicalSizeForScale(actualSize: Int, targetScale: Double): Int {
    val screenScalingFactor = displayView.screenScalingFactor
    var size = ceil(actualSize * targetScale / screenScalingFactor).toInt()
    while (true) {
      val rawScale = size.scaled(screenScalingFactor).toDouble() / actualSize
      val effectiveScale = if (targetScale >= 1.0) rawScale else roundDownToNaturalNumberOrNearestSmallFraction(rawScale)
      if (effectiveScale >= targetScale) {
        break
      }
      size++
    }
    return size
  }

  protected fun findRootContainer(): Component {
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

  private fun getMinimumChildSize(splitter: Splitter, child: Component?): Int {
    if (!splitter.isHonorMinimumSize || child == null) {
      return 0
    }
    return if (splitter.isVertical) child.minimumSize.height else child.minimumSize.width
  }

  /**
   * Synchronizes the expected bounds tracked by IntelliJ's `ToolWindowExternalDecoratorBoundsHelper` with the current bounds of [window].
   *
   * Both `WindowedDecorator` and `FloatingDecorator` attach a `ToolWindowExternalDecoratorBoundsHelper` listener to their [Window] to guard
   * against unexpected OS/window-manager move or resize events during the first 100ms after the window is shown
   * (`ide.tool.window.prevent.move.resize.timeout`). During that window, any `componentShown` or `componentResized` event whose bounds
   * differ from the helper's stored bounds causes the helper to reset `visibleWindowBounds` back to the initial unoptimized bounds set
   * before the window was shown.
   *
   * Calling [Window.setSize] directly does not update the helper's stored bounds; only `ToolWindowExternalDecorator.setVisibleWindowBounds`
   * updates them. Since `ToolWindowExternalDecorator` has `internal` visibility in `intellij.platform.ide.impl`, reflection is used to read
   * and re-apply `visibleWindowBounds` after resizing [window].
   */
  private fun syncExternalDecoratorBounds(window: Window) {
    try {
      val decoratorClass = Class.forName("com.intellij.openapi.wm.impl.ToolWindowExternalDecorator")
      val key = decoratorClass.getField("DECORATOR_PROPERTY").get(null) as? Key<*> ?: return
      val externalDecorator = ClientProperty.get(window, key) ?: return
      val bounds = decoratorClass.getMethod("getVisibleWindowBounds").invoke(externalDecorator) as Rectangle
      decoratorClass.getMethod("setVisibleWindowBounds", Rectangle::class.java).invoke(externalDecorator, bounds)
    } catch (_: ReflectiveOperationException) {}
  }
}
