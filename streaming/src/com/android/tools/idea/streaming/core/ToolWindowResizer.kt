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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.InternalDecorator
import com.intellij.ui.ClientProperty
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Window
import java.util.IdentityHashMap
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Base class for resizing a tool window and/or split panes around a [ZoomablePanel]. */
internal abstract class ToolWindowResizer(protected val displayView: ZoomablePanel) {

  /**
   * Resizes [rootContainer] (via [toolWindow] or its detached window) and adjusts internal [Splitter] proportions along the path from
   * [viewport] to [rootContainer] so [viewport] approaches [desiredSize] without shrinking split siblings below their zoom-preserving
   * bounds or violating [capabilities].
   *
   * Because platform or window bounds can clamp the requested root stretch, sizing proceeds in five stages:
   * 1. Plan desired and capability-clamped widths along the path ([planHorizontalPath]) and snapshot pre-stretch vertical geometry.
   * 2. Plan desired heights and sibling zoom-preserving minimum heights for the planned widths ([planVerticalPath]).
   * 3. Stretch [rootContainer] ([applyTargetRootSize]) and reconcile horizontal splitters top-down from the actual post-stretch width.
   * 4. If width clamping changed any path width or [AbstractDevicePanel] toolbar wrap height, re-plan the vertical path against the actual
   *    widths and updated toolbar height delta (re-applying root height when adjustable).
   * 5. Reconcile vertical splitters top-down from the actual post-stretch root height.
   */
  protected fun applyDesiredViewportSize(
    toolWindow: ToolWindowEx,
    viewport: Component,
    rootContainer: Component,
    desiredSize: Dimension,
    toolbarHeightDelta: Int,
    isDetached: Boolean,
    capabilities: ResizeCapabilities,
    recomputeDesiredHeight: ((actualViewportWidth: Int, effectiveToolbarDelta: Int, actualViewportMaxHeight: Int) -> Int)? = null,
  ) {
    val widthPlan = planHorizontalPath(viewport, rootContainer, desiredSize.width, capabilities)
    val path = widthPlan.path
    val preStretchState = PreStretchVerticalState.capture(rootContainer)
    val oldHeights = IntArray(path.size) { preStretchState.height(path[it]) }
    val devicePanelIndex = path.indexOfFirst { it is AbstractDevicePanel<*> }
    val preStretchToolbarHeight =
      if (devicePanelIndex >= 0) {
        val devicePanel = path[devicePanelIndex] as AbstractDevicePanel<*>
        val toolbarPanel = (devicePanel.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
        if (toolbarPanel != null && toolbarPanel.isVisible) preStretchState.height(toolbarPanel) - toolbarHeightDelta else 0
      } else {
        0
      }
    val plannedOrthogonalWidths = widthPlan.plannedWidths
    val targetRootWidth = widthPlan.desiredWidths[path.lastIndex]

    var verticalPlan =
      planVerticalPath(
        path,
        rootContainer,
        { desiredSize.height },
        toolbarHeightDelta,
        capabilities,
        widthPlan.toWidthMap(),
        preStretchState,
      )
    val initialRootTargetHeight = verticalPlan.desiredHeights[path.lastIndex]

    applyTargetRootSize(
      toolWindow,
      rootContainer,
      Dimension(targetRootWidth, initialRootTargetHeight),
      isDetached,
      capabilities.canShrinkWidth,
      capabilities.canShrinkHeight,
    )

    val (actualWidths, updatedHorizontalSplitter) = reconcileHorizontalPath(widthPlan)
    var updatedAnySplitter = updatedHorizontalSplitter

    var effectiveToolbarHeightDelta = toolbarHeightDelta
    if (devicePanelIndex >= 1 && actualWidths[devicePanelIndex] != plannedOrthogonalWidths[devicePanelIndex]) {
      val devicePanel = path[devicePanelIndex] as AbstractDevicePanel<*>
      val toolbarPanel = (devicePanel.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
      if (toolbarPanel != null && toolbarPanel.isVisible) {
        effectiveToolbarHeightDelta =
          updateDevicePanelToolbarHeightForWidth(devicePanel, actualWidths[devicePanelIndex], preStretchToolbarHeight, null)
      }
    }

    if (effectiveToolbarHeightDelta != toolbarHeightDelta || !actualWidths.contentEquals(plannedOrthogonalWidths)) {
      val actualWidthMap = IdentityHashMap<Component, Int>(path.size)
      for (i in path.indices) {
        actualWidthMap[path[i]] = actualWidths[i]
      }
      verticalPlan =
        planVerticalPath(
          path,
          rootContainer,
          { viewportMaxHeight ->
            recomputeDesiredHeight?.invoke(actualWidths[0], effectiveToolbarHeightDelta, viewportMaxHeight) ?: desiredSize.height
          },
          effectiveToolbarHeightDelta,
          capabilities,
          actualWidthMap,
          preStretchState,
        )
      val replannedRootTargetHeight = verticalPlan.desiredHeights[path.lastIndex]
      if ((isDetached || toolWindow.anchor.isHorizontal) && replannedRootTargetHeight != initialRootTargetHeight) {
        val adjustedRootHeight =
          if (capabilities.canShrinkHeight) replannedRootTargetHeight else max(replannedRootTargetHeight, oldHeights.last())
        if (adjustedRootHeight != rootContainer.height) {
          applyTargetRootSize(
            toolWindow,
            rootContainer,
            Dimension(rootContainer.width, adjustedRootHeight),
            isDetached,
            canShrinkWidth = false,
            canShrinkHeight = true,
          )
        }
      }
    }

    updatedAnySplitter =
      reconcileVerticalPath(path, oldHeights, widthPlan.oldProportions, verticalPlan, effectiveToolbarHeightDelta) || updatedAnySplitter
    if (updatedAnySplitter || effectiveToolbarHeightDelta != toolbarHeightDelta) {
      rootContainer.validate()
    }
  }

  protected fun computePlannedPathWidths(
    startComponent: Component,
    stopAncestor: Component,
    startWidth: Int,
    capabilities: ResizeCapabilities,
  ): Map<Component, Int> = planHorizontalPath(startComponent, stopAncestor, startWidth, capabilities).toWidthMap()

  /**
   * Plans desired widths bottom-up from [startComponent] to [stopAncestor] and then reconciles capability-clamped widths top-down without
   * mutating any [Splitter] proportions.
   */
  private fun planHorizontalPath(
    startComponent: Component,
    stopAncestor: Component,
    startWidth: Int,
    capabilities: ResizeCapabilities,
  ): HorizontalPathPlan {
    val path = ArrayList<Component>()
    var curr: Component = startComponent
    path.add(curr)
    while (curr !== stopAncestor) {
      val p = curr.parent ?: break
      path.add(p)
      curr = p
    }
    val oldWidths = IntArray(path.size) { path[it].width }
    val oldProportions = FloatArray(path.size) { (path[it] as? Splitter)?.proportion ?: 0f }
    val minOtherWidths = IntArray(path.size)
    val desiredWidths = IntArray(path.size)
    val maxWidthSizes = computeMaxComponentSizes(startComponent, stopAncestor, capabilities.canExpandWidth, isVertical = false)
    desiredWidths[0] = startWidth
    for (i in 1..path.lastIndex) {
      val c = path[i - 1]
      val p = path[i]
      if (p is Splitter && !p.isVertical) {
        val other = if (c === p.firstComponent) p.secondComponent else p.firstComponent
        if (other != null && other.isVisible) {
          minOtherWidths[i] = max(computeMinZoomPreservingSize(other, isVertical = false), getMinimumChildSize(p, other))
        }
      }
      desiredWidths[i] =
        if (desiredWidths[i - 1] == oldWidths[i - 1]) {
          oldWidths[i]
        } else if (p is Splitter && !p.isVertical) {
          val hasOuterSplitter = hasSplitterAncestor(p, stopAncestor, isVertical = false)
          val maxSplitterWidth = maxWidthSizes[p] ?: oldWidths[i]
          computeSplitterSizeAndUpdateProportion(
            p,
            c === p.firstComponent,
            desiredWidths[i - 1],
            updateSplitter = false,
            canExpandRoot = maxSplitterWidth == Int.MAX_VALUE,
            canShrinkRoot = capabilities.canShrinkWidth || hasOuterSplitter,
            maxSplitterSize = maxSplitterWidth,
          )
        } else {
          desiredWidths[i - 1] + (oldWidths[i] - oldWidths[i - 1])
        }
    }

    val plannedWidths = IntArray(path.size)
    var plannedWidth = capabilities.clampWidth(desiredWidths[path.lastIndex], oldWidths[path.lastIndex])
    plannedWidths[path.lastIndex] = plannedWidth
    for (i in path.lastIndex downTo 1) {
      val p = path[i]
      val c = path[i - 1]
      plannedWidth =
        if (p is Splitter && !p.isVertical) {
          reconcileSplitterChildSize(
              p,
              c,
              plannedWidth,
              oldWidths[i - 1],
              oldProportions[i],
              desiredWidths[i - 1],
              minOtherWidths[i],
              updateSplitter = false,
            )
            .first
        } else {
          (plannedWidth - (oldWidths[i] - oldWidths[i - 1])).coerceAtLeast(0)
        }
      plannedWidths[i - 1] = plannedWidth
    }
    return HorizontalPathPlan(path, oldWidths, oldProportions, minOtherWidths, desiredWidths, plannedWidths)
  }

  /**
   * Plans desired heights and sibling zoom-preserving minimum heights along [path] for the given [orthogonalWidths] and
   * [toolbarHeightDelta], reading pre-stretch component heights from [preStretchState] so it remains accurate even if called after
   * [applyTargetRootSize].
   */
  private fun planVerticalPath(
    path: List<Component>,
    rootContainer: Component,
    computeStartHeight: (viewportMaxHeight: Int) -> Int,
    toolbarHeightDelta: Int,
    capabilities: ResizeCapabilities,
    orthogonalWidths: Map<Component, Int>,
    preStretchState: PreStretchVerticalState,
  ): VerticalPathPlan {
    val viewport = path.first()
    val minOtherHeights = IntArray(path.size)
    val desiredHeights = IntArray(path.size)
    val maxHeightSizes =
      computeMaxComponentSizes(
        viewport,
        rootContainer,
        capabilities.canExpandHeight,
        isVertical = true,
        toolbarHeightDelta,
        orthogonalWidths,
        preStretchState,
      )
    desiredHeights[0] = computeStartHeight(maxHeightSizes.getValue(viewport))
    for (i in 1..path.lastIndex) {
      val c = path[i - 1]
      val p = path[i]
      val oldChildHeight = preStretchState.height(c)
      val oldParentHeight = preStretchState.height(p)
      val parentWidth = orthogonalWidths[p] ?: preStretchState.width(p)
      var oldOtherHeight = 0
      if (p is Splitter && p.isVertical) {
        val other = if (c === p.firstComponent) p.secondComponent else p.firstComponent
        if (other != null && other.isVisible) {
          oldOtherHeight = preStretchState.height(other)
          val otherOrthogonalWidth = computeChildOrthogonalSize(p, other, parentWidth, isVertical = true, preStretchState)
          minOtherHeights[i] =
            max(
              computeMinZoomPreservingSize(other, isVertical = true, otherOrthogonalWidth, preStretchState),
              getMinimumChildSize(p, other),
            )
        }
      }
      var h =
        if (desiredHeights[i - 1] == oldChildHeight && minOtherHeights[i] <= oldOtherHeight) {
          oldParentHeight
        } else if (p is Splitter && p.isVertical) {
          val hasOuterSplitter = hasSplitterAncestor(p, rootContainer, isVertical = true)
          val maxSplitterHeight = maxHeightSizes[p] ?: oldParentHeight
          computeSplitterSizeAndUpdateProportion(
            p,
            c === p.firstComponent,
            desiredHeights[i - 1],
            updateSplitter = false,
            canExpandRoot = maxSplitterHeight == Int.MAX_VALUE,
            canShrinkRoot = capabilities.canShrinkHeight || hasOuterSplitter,
            toolbarHeightDelta = toolbarHeightDelta,
            maxSplitterSize = maxSplitterHeight,
            minOtherZoomSize = minOtherHeights[i],
            preStretchState = preStretchState,
          )
        } else {
          desiredHeights[i - 1] + (oldParentHeight - oldChildHeight)
        }
      if (p is AbstractDevicePanel<*>) {
        h += toolbarHeightDelta
      }
      desiredHeights[i] = h
    }
    return VerticalPathPlan(desiredHeights, minOtherHeights)
  }

  private fun reconcileHorizontalPath(widthPlan: HorizontalPathPlan): Pair<IntArray, Boolean> {
    val path = widthPlan.path
    var updatedAnySplitter = false
    var actualWidth = path.last().width
    val actualWidths = IntArray(path.size)
    actualWidths[path.lastIndex] = actualWidth
    for (i in path.lastIndex downTo 1) {
      val p = path[i]
      val c = path[i - 1]
      if (p is Splitter && !p.isVertical) {
        val (childWidth, updated) =
          reconcileSplitterChildSize(
            p,
            c,
            actualWidth,
            widthPlan.oldWidths[i - 1],
            widthPlan.oldProportions[i],
            widthPlan.desiredWidths[i - 1],
            widthPlan.minOtherWidths[i],
          )
        actualWidth = childWidth
        updatedAnySplitter = updatedAnySplitter || updated
      } else {
        actualWidth = (actualWidth - (widthPlan.oldWidths[i] - widthPlan.oldWidths[i - 1])).coerceAtLeast(0)
      }
      actualWidths[i - 1] = actualWidth
    }
    return Pair(actualWidths, updatedAnySplitter)
  }

  private fun reconcileVerticalPath(
    path: List<Component>,
    oldHeights: IntArray,
    oldProportions: FloatArray,
    verticalPlan: VerticalPathPlan,
    effectiveToolbarHeightDelta: Int,
  ): Boolean {
    var updatedAnySplitter = false
    var actualHeight = path.last().height
    for (i in path.lastIndex downTo 1) {
      val p = path[i]
      val c = path[i - 1]
      if (p is Splitter && p.isVertical) {
        val (childHeight, updated) =
          reconcileSplitterChildSize(
            p,
            c,
            actualHeight,
            oldHeights[i - 1],
            oldProportions[i],
            verticalPlan.desiredHeights[i - 1],
            verticalPlan.minOtherHeights[i],
          )
        actualHeight = childHeight
        updatedAnySplitter = updatedAnySplitter || updated
      } else {
        var delta = oldHeights[i] - oldHeights[i - 1]
        if (p is AbstractDevicePanel<*>) {
          delta += effectiveToolbarHeightDelta
        }
        actualHeight = (actualHeight - delta).coerceAtLeast(0)
      }
    }
    return updatedAnySplitter
  }

  private class HorizontalPathPlan(
    val path: List<Component>,
    val oldWidths: IntArray,
    val oldProportions: FloatArray,
    val minOtherWidths: IntArray,
    val desiredWidths: IntArray,
    val plannedWidths: IntArray,
  ) {
    fun plannedWidthOf(component: Component): Int? {
      val index = path.indexOfFirst { it === component }
      return if (index >= 0) plannedWidths[index] else null
    }

    fun toWidthMap(): Map<Component, Int> {
      val map = IdentityHashMap<Component, Int>(path.size)
      for (i in path.indices) {
        map[path[i]] = plannedWidths[i]
      }
      return map
    }
  }

  private class VerticalPathPlan(val desiredHeights: IntArray, val minOtherHeights: IntArray)

  private fun reconcileSplitterChildSize(
    splitter: Splitter,
    child: Component,
    actualSplitterSize: Int,
    oldChildSize: Int,
    oldProportion: Float,
    desiredChildSize: Int,
    minOtherSize: Int,
    updateSplitter: Boolean = true,
  ): Pair<Int, Boolean> {
    val other = if (child === splitter.firstComponent) splitter.secondComponent else splitter.firstComponent
    if (other == null || !other.isVisible) {
      return Pair(actualSplitterSize.coerceAtLeast(0), false)
    }
    val actualTotal = (actualSplitterSize - splitter.dividerWidth).coerceAtLeast(0)
    val isFirst = child === splitter.firstComponent
    var effectiveProportion = splitter.proportion
    var updated = false
    if (actualTotal > 0) {
      val oldShare = if (isFirst) oldProportion.toDouble() else 1.0 - oldProportion.toDouble()
      val unreconciledChild = (actualTotal * oldShare).roundToInt()
      if (desiredChildSize != oldChildSize || actualTotal - unreconciledChild < minOtherSize) {
        val effectiveDesiredChild = max(desiredChildSize, getMinimumChildSize(splitter, child))
        val baselineChild = min(oldChildSize, unreconciledChild)
        val allocatedChild =
          if (effectiveDesiredChild >= baselineChild) {
            min(effectiveDesiredChild, (actualTotal - minOtherSize).coerceAtLeast(baselineChild))
          } else {
            effectiveDesiredChild
          }
        val desiredFirst = if (isFirst) allocatedChild else actualTotal - allocatedChild
        val newProportion = (desiredFirst.toFloat() / actualTotal).coerceIn(splitter.minimumProportion, splitter.maximumProportion)
        effectiveProportion = newProportion
        if (updateSplitter && splitter.proportion != newProportion) {
          splitter.proportion = newProportion
          updated = true
        }
      }
    }
    val firstSize = (actualTotal * effectiveProportion.toDouble()).roundToInt()
    val actualChildSize = if (isFirst) firstSize else (actualTotal - firstSize).coerceAtLeast(0)
    return Pair(actualChildSize, updated)
  }

  protected fun applyTargetRootSize(
    toolWindow: ToolWindowEx,
    rootContainer: Component,
    targetSize: Dimension,
    isDetached: Boolean,
    canShrinkWidth: Boolean = true,
    canShrinkHeight: Boolean = true,
  ) {
    val rawDeltaWidth = targetSize.width - rootContainer.width
    val rawDeltaHeight = targetSize.height - rootContainer.height
    val deltaWidth = if (canShrinkWidth) rawDeltaWidth else rawDeltaWidth.coerceAtLeast(0)
    val deltaHeight = if (canShrinkHeight) rawDeltaHeight else rawDeltaHeight.coerceAtLeast(0)

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
        stretchDockedToolWindowWidth(toolWindow, rootContainer, deltaWidth)
      }
    } else {
      if (deltaHeight != 0) {
        toolWindow.stretchHeight(deltaHeight)
        (rootContainer.parent as? Splitter)?.doLayout()
      }
    }
    rootContainer.validate()
  }

  private fun stretchDockedToolWindowWidth(toolWindow: ToolWindowEx, rootContainer: Component, deltaWidth: Int) {
    val parentSplitter = rootContainer.parent as? Splitter
    val other =
      if (parentSplitter != null && !parentSplitter.isVertical) {
        if (rootContainer === parentSplitter.firstComponent) parentSplitter.secondComponent else parentSplitter.firstComponent
      } else {
        null
      }
    if (parentSplitter != null && other != null && other.isVisible) {
      val isFirstComponent = rootContainer === parentSplitter.firstComponent
      val oldChildWidth = rootContainer.width
      val targetChildWidth = oldChildWidth + deltaWidth
      val effectiveChildWidth = max(targetChildWidth, getMinimumChildSize(parentSplitter, rootContainer))
      val otherWidth = max(other.width, getMinimumChildSize(parentSplitter, other))
      val oldParentWidth = parentSplitter.width
      val targetParentWidth =
        computeSplitterSizeAndUpdateProportion(
          parentSplitter,
          isFirstComponent,
          targetChildWidth,
          updateSplitter = false,
          canExpandRoot = true,
        )
      val parentDelta = targetParentWidth - oldParentWidth
      if (parentDelta != 0) {
        toolWindow.stretchWidth(parentDelta)
      }
      val actualTotal = parentSplitter.width - parentSplitter.dividerWidth
      if (actualTotal > 0) {
        val allocatedChildWidth =
          if (effectiveChildWidth >= oldChildWidth) {
            min(effectiveChildWidth, (actualTotal - otherWidth).coerceAtLeast(oldChildWidth))
          } else {
            max(effectiveChildWidth, (actualTotal - otherWidth).coerceAtMost(oldChildWidth))
          }
        val desiredFirstWidth = if (isFirstComponent) allocatedChildWidth else actualTotal - allocatedChildWidth
        parentSplitter.proportion =
          (desiredFirstWidth.toFloat() / actualTotal).coerceIn(parentSplitter.minimumProportion, parentSplitter.maximumProportion)
        parentSplitter.doLayout()
        parentSplitter.validate()
      }
    } else {
      toolWindow.stretchWidth(deltaWidth)
      (rootContainer.parent as? Splitter)?.doLayout()
    }
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
    capabilities: ResizeCapabilities,
  ): Int {
    val devicePanel = displayView.findAncestor<AbstractDevicePanel<*>>() ?: return 0
    val newDevicePanelWidth = computeAncestorWidth(viewport, devicePanel, newViewportWidth, capabilities).coerceAtLeast(0)
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

  protected fun computeVerticalShareRatio(startComponent: Component, stopAncestor: Component): Double =
    computeVerticalShareRatio(startComponent, stopAncestor, preStretchState = null)

  private fun computeVerticalShareRatio(
    startComponent: Component,
    stopAncestor: Component,
    preStretchState: PreStretchVerticalState?,
  ): Double {
    var ratio = 1.0
    var c: Component = startComponent
    while (c !== stopAncestor) {
      val p = c.parent ?: break
      if (p is Splitter && p.isVertical) {
        val other = if (c === p.firstComponent) p.secondComponent else p.firstComponent
        if (other != null && other.isVisible) {
          val prop = (preStretchState?.proportion(p) ?: p.proportion).toDouble()
          ratio *= if (c === p.firstComponent) prop else (1.0 - prop)
        }
      }
      c = p
    }
    return ratio
  }

  protected fun computeAncestorWidth(
    startComponent: Component,
    stopAncestor: Component,
    startWidth: Int,
    capabilities: ResizeCapabilities,
  ): Int {
    val rootContainer = findRootContainer()
    val widthPlan = planHorizontalPath(startComponent, rootContainer, startWidth, capabilities)
    return widthPlan.plannedWidthOf(stopAncestor) ?: stopAncestor.width
  }

  protected fun computeSplitterSizeAndUpdateProportion(
    splitter: Splitter,
    isFirstComponent: Boolean,
    targetChildSize: Int,
    updateSplitter: Boolean,
    canExpandRoot: Boolean,
    canShrinkRoot: Boolean = canExpandRoot,
    toolbarHeightDelta: Int = 0,
    maxSplitterSize: Int = if (canExpandRoot) Int.MAX_VALUE else -1,
  ): Int =
    computeSplitterSizeAndUpdateProportion(
      splitter = splitter,
      isFirstComponent = isFirstComponent,
      targetChildSize = targetChildSize,
      updateSplitter = updateSplitter,
      canExpandRoot = canExpandRoot,
      canShrinkRoot = canShrinkRoot,
      toolbarHeightDelta = toolbarHeightDelta,
      maxSplitterSize = maxSplitterSize,
      preStretchState = null,
    )

  private fun computeSplitterSizeAndUpdateProportion(
    splitter: Splitter,
    isFirstComponent: Boolean,
    targetChildSize: Int,
    updateSplitter: Boolean,
    canExpandRoot: Boolean,
    canShrinkRoot: Boolean = canExpandRoot,
    toolbarHeightDelta: Int = 0,
    maxSplitterSize: Int = if (canExpandRoot) Int.MAX_VALUE else -1,
    minOtherZoomSize: Int = 0,
    preStretchState: PreStretchVerticalState? = null,
  ): Int {
    val canAbsorbToolbarDelta = if (toolbarHeightDelta > 0) canExpandRoot else canShrinkRoot
    val heightAdjustment =
      if (splitter.isVertical && !canAbsorbToolbarDelta && toolbarHeightDelta != 0) {
        splitter.findAncestor<AbstractDevicePanel<*>>()?.let {
          -(toolbarHeightDelta * computeVerticalShareRatio(splitter, it, preStretchState)).roundToInt()
        } ?: 0
      } else {
        0
      }
    val splitterCurrentDim = if (splitter.isVertical) (preStretchState?.height(splitter) ?: splitter.height) else splitter.width
    val currentSplitterSize = splitterCurrentDim + heightAdjustment
    val effectiveMaxSplitterSize = if (maxSplitterSize < 0) currentSplitterSize else maxSplitterSize
    val child = if (isFirstComponent) splitter.firstComponent else splitter.secondComponent
    val other = if (isFirstComponent) splitter.secondComponent else splitter.firstComponent
    val otherVisible = other != null && other.isVisible
    if (!otherVisible) {
      val newSize =
        when {
          targetChildSize > currentSplitterSize -> min(targetChildSize, effectiveMaxSplitterSize.coerceAtLeast(currentSplitterSize))
          targetChildSize < currentSplitterSize -> if (canShrinkRoot) targetChildSize else currentSplitterSize
          else -> currentSplitterSize
        }
      return newSize.coerceAtLeast(0)
    }
    val minChildSize = getMinimumChildSize(splitter, child)
    val minOtherSize = max(getMinimumChildSize(splitter, other), minOtherZoomSize)
    val effectiveChildSize = max(targetChildSize, minChildSize)
    val otherCurrentDim = if (splitter.isVertical) (preStretchState?.height(other) ?: other.height) else other.width
    val otherSize = max(otherCurrentDim, minOtherSize)
    val dividerWidth = splitter.dividerWidth
    val currentTotal = currentSplitterSize - dividerWidth
    val maxTotal =
      if (effectiveMaxSplitterSize == Int.MAX_VALUE) Int.MAX_VALUE
      else (effectiveMaxSplitterSize - dividerWidth).coerceAtLeast(currentTotal)
    val desiredTotal = otherSize + effectiveChildSize
    var newTotal =
      when {
        desiredTotal > currentTotal -> min(desiredTotal, maxTotal)
        desiredTotal < currentTotal -> if (canShrinkRoot) desiredTotal else currentTotal
        else -> currentTotal
      }
    if (newTotal > 0) {
      val desiredFirstSize = if (isFirstComponent) effectiveChildSize else newTotal - effectiveChildSize
      val rawProportion = desiredFirstSize.toFloat() / newTotal
      val newProportion = rawProportion.coerceIn(splitter.minimumProportion, splitter.maximumProportion)
      if (newProportion != rawProportion) {
        val capTotal = if (desiredTotal < currentTotal) currentTotal else maxTotal
        if (newTotal < capTotal) {
          val prop = newProportion.toDouble()
          val childShare = if (isFirstComponent) prop else 1.0 - prop
          val otherShare = 1.0 - childShare
          var requiredTotal = newTotal
          if (childShare > 0.0 && effectiveChildSize > 0) {
            requiredTotal = max(requiredTotal, ceil((effectiveChildSize - 0.5) / childShare).toInt())
          }
          if (otherShare > 0.0 && otherSize > 0) {
            requiredTotal = max(requiredTotal, ceil((otherSize - 0.5) / otherShare).toInt())
          }
          requiredTotal = requiredTotal.coerceAtMost(capTotal)
          while (requiredTotal < capTotal) {
            val firstSize = (requiredTotal * prop).roundToInt()
            val actualChildSize = if (isFirstComponent) firstSize else requiredTotal - firstSize
            val actualOtherSize = requiredTotal - actualChildSize
            if ((childShare <= 0.0 || actualChildSize >= effectiveChildSize) && (otherShare <= 0.0 || actualOtherSize >= otherSize)) {
              break
            }
            requiredTotal++
          }
          newTotal = max(newTotal, requiredTotal)
        }
      }
      if (updateSplitter) {
        splitter.proportion = newProportion
      }
    }
    return (newTotal + dividerWidth).coerceAtLeast(0)
  }

  protected fun computeMaxComponentSizes(
    startComponent: Component,
    stopAncestor: Component,
    canExpandRoot: Boolean,
    isVertical: Boolean,
    toolbarHeightDelta: Int = 0,
    plannedOrthogonalSizes: Map<Component, Int>? = null,
  ): Map<Component, Int> =
    computeMaxComponentSizes(
      startComponent = startComponent,
      stopAncestor = stopAncestor,
      canExpandRoot = canExpandRoot,
      isVertical = isVertical,
      toolbarHeightDelta = toolbarHeightDelta,
      plannedOrthogonalSizes = plannedOrthogonalSizes,
      preStretchState = null,
    )

  private fun computeMaxComponentSizes(
    startComponent: Component,
    stopAncestor: Component,
    canExpandRoot: Boolean,
    isVertical: Boolean,
    toolbarHeightDelta: Int = 0,
    plannedOrthogonalSizes: Map<Component, Int>? = null,
    preStretchState: PreStretchVerticalState? = null,
  ): Map<Component, Int> {
    val path = ArrayList<Component>()
    var curr: Component = startComponent
    path.add(curr)
    while (curr !== stopAncestor) {
      val p = curr.parent ?: break
      path.add(p)
      curr = p
    }
    val state = if (isVertical) preStretchState else null
    fun primaryDim(comp: Component): Int = if (isVertical) (state?.height(comp) ?: comp.height) else comp.width
    fun orthDim(comp: Component): Int = if (isVertical) (state?.width(comp) ?: comp.width) else comp.height

    val maxSizes = IdentityHashMap<Component, Int>(path.size)
    val orthogonalSizes = IdentityHashMap<Component, Int>(path.size)
    val top = path.last()
    maxSizes[top] = if (canExpandRoot) Int.MAX_VALUE else primaryDim(top)
    orthogonalSizes[top] = plannedOrthogonalSizes?.get(top)?.takeIf { it >= 0 } ?: orthDim(top)
    for (i in path.lastIndex downTo 1) {
      val p = path[i]
      val c = path[i - 1]
      val maxP = maxSizes.getValue(p)
      val parentOrthogonalSize = orthogonalSizes.getValue(p)
      orthogonalSizes[c] =
        plannedOrthogonalSizes?.get(c)?.takeIf { it >= 0 } ?: computeChildOrthogonalSize(p, c, parentOrthogonalSize, isVertical, state)
      if (maxP == Int.MAX_VALUE) {
        maxSizes[c] = Int.MAX_VALUE
      } else if (p is Splitter && p.isVertical == isVertical) {
        val other = if (c === p.firstComponent) p.secondComponent else p.firstComponent
        if (other == null || !other.isVisible) {
          maxSizes[c] = maxP.coerceAtLeast(0)
        } else {
          val total = (maxP - p.dividerWidth).coerceAtLeast(0)
          val otherOrthogonalSize = computeChildOrthogonalSize(p, other, parentOrthogonalSize, isVertical, state)
          val minOther = max(computeMinZoomPreservingSize(other, isVertical, otherOrthogonalSize, state), getMinimumChildSize(p, other))
          val maxByOther = total - minOther
          val maxByProportion =
            if (c === p.firstComponent) (total * p.maximumProportion.toDouble()).roundToInt()
            else total - (total * p.minimumProportion.toDouble()).roundToInt()
          maxSizes[c] = min(maxByOther, maxByProportion).coerceAtLeast(0)
        }
      } else {
        var delta = primaryDim(p) - primaryDim(c)
        if (isVertical && p is AbstractDevicePanel<*>) {
          delta += toolbarHeightDelta
        }
        maxSizes[c] = (maxP - delta).coerceAtLeast(0)
      }
    }
    return maxSizes
  }

  private fun computeChildOrthogonalSize(
    parent: Component,
    child: Component,
    parentOrthogonalSize: Int,
    isVertical: Boolean,
    preStretchState: PreStretchVerticalState? = null,
  ): Int {
    if (parentOrthogonalSize <= 0) {
      return -1
    }
    if (parent is Splitter && parent.isVertical != isVertical) {
      val other = if (child === parent.firstComponent) parent.secondComponent else parent.firstComponent
      if (other == null || !other.isVisible) {
        return parentOrthogonalSize
      }
      val total = (parentOrthogonalSize - parent.dividerWidth).coerceAtLeast(0)
      val prop = (if (isVertical) preStretchState?.proportion(parent) else null) ?: parent.proportion
      val firstSize = (total * prop.toDouble()).roundToInt()
      return if (child === parent.firstComponent) firstSize else (total - firstSize).coerceAtLeast(0)
    }
    val parentOrthogonalCurrent = if (isVertical) (preStretchState?.width(parent) ?: parent.width) else parent.height
    val childOrthogonalCurrent = if (isVertical) (preStretchState?.width(child) ?: child.width) else child.height
    return (parentOrthogonalSize - (parentOrthogonalCurrent - childOrthogonalCurrent)).coerceAtLeast(0)
  }

  private fun computeMinZoomPreservingSize(
    component: Component,
    isVertical: Boolean,
    orthogonalSize: Int = -1,
    preStretchState: PreStretchVerticalState? = null,
  ): Int {
    val state = if (isVertical) preStretchState else null
    return computeMinZoomPreservingSizeOrNull(component, isVertical, orthogonalSize, state)
      ?: if (isVertical) (state?.height(component) ?: component.height) else component.width
  }

  private fun computeMinZoomPreservingSizeOrNull(
    comp: Component,
    isVertical: Boolean,
    orthogonalSize: Int = -1,
    preStretchState: PreStretchVerticalState? = null,
  ): Int? {
    if (!comp.isVisible) {
      return null
    }
    val state = if (isVertical) preStretchState else null
    val currentSize = if (isVertical) (state?.height(comp) ?: comp.height) else comp.width
    if (isZoomableLeaf(comp)) {
      if (state != null && state.hasLeafMinHeight(comp)) {
        return state.leafMinHeight(comp)
      }
      return computeLeafMinZoomPreservingSizeOrNull(comp, isVertical)
    }
    if (comp is Splitter) {
      if (currentSize <= 0) {
        return 0
      }
      val first = comp.firstComponent?.takeIf { it.isVisible }
      val second = comp.secondComponent?.takeIf { it.isVisible }
      val firstMinOrNull = first?.let {
        computeMinZoomPreservingSizeOrNull(it, isVertical, computeChildOrthogonalSize(comp, it, orthogonalSize, isVertical, state), state)
      }
      val secondMinOrNull = second?.let {
        computeMinZoomPreservingSizeOrNull(it, isVertical, computeChildOrthogonalSize(comp, it, orthogonalSize, isVertical, state), state)
      }
      if (firstMinOrNull == null && secondMinOrNull == null) {
        return null
      }
      if (first == null) {
        val onlyChild = checkNotNull(second)
        val secondDim = if (isVertical) (state?.height(onlyChild) ?: onlyChild.height) else onlyChild.width
        return if (secondMinOrNull!! > secondDim) secondMinOrNull.coerceAtLeast(0) else secondMinOrNull.coerceAtMost(currentSize)
      }
      if (second == null) {
        val firstDim = if (isVertical) (state?.height(first) ?: first.height) else first.width
        return if (firstMinOrNull!! > firstDim) firstMinOrNull.coerceAtLeast(0) else firstMinOrNull.coerceAtMost(currentSize)
      }
      val firstDim = if (isVertical) (state?.height(first) ?: first.height) else first.width
      val secondDim = if (isVertical) (state?.height(second) ?: second.height) else second.width
      val grewBeyondCurrent = (firstMinOrNull ?: 0) > firstDim || (secondMinOrNull ?: 0) > secondDim
      val minFirst =
        max(
          firstMinOrNull ?: firstDim,
          if (comp.isVertical == isVertical) getMinimumChildSize(comp, first) else 0,
        )
      val minSecond =
        max(
          secondMinOrNull ?: secondDim,
          if (comp.isVertical == isVertical) getMinimumChildSize(comp, second) else 0,
        )
      if (comp.isVertical != isVertical) {
        val raw = max(minFirst, minSecond).coerceAtLeast(0)
        return if (grewBeyondCurrent) raw else raw.coerceAtMost(currentSize)
      }
      val currentTotal = (currentSize - comp.dividerWidth).coerceAtLeast(0)
      val prop = ((if (isVertical) state?.proportion(comp) else null) ?: comp.proportion).toDouble()
      if (grewBeyondCurrent) {
        if (prop <= 0.0 || prop >= 1.0) {
          val fallbackTotal = max(currentTotal.toLong(), minFirst.toLong() + minSecond.toLong())
          return (fallbackTotal + comp.dividerWidth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        var subTotal =
          max(ceil((minFirst - 0.5) / prop).toInt(), ceil((minSecond - 0.5) / (1.0 - prop)).toInt()).coerceAtLeast(minFirst + minSecond)
        while (true) {
          val firstAllocated = (prop * subTotal).roundToInt()
          if (firstAllocated >= minFirst && subTotal - firstAllocated >= minSecond) {
            break
          }
          subTotal++
        }
        return subTotal + comp.dividerWidth
      }
      if (minFirst.toLong() + minSecond.toLong() >= currentTotal) {
        return currentSize
      }
      var subTotal =
        max(
            if (prop > 0.0) ceil(minFirst / prop).toInt() else currentTotal,
            if (prop < 1.0) ceil(minSecond / (1.0 - prop)).toInt() else currentTotal,
          )
          .coerceIn(minFirst + minSecond, currentTotal)
      while (subTotal < currentTotal) {
        val firstAllocated = (prop * subTotal).roundToInt()
        if (firstAllocated >= minFirst && subTotal - firstAllocated >= minSecond) {
          break
        }
        subTotal++
      }
      return (subTotal + comp.dividerWidth).coerceAtMost(currentSize)
    }
    if (comp is Container) {
      var required: Int? = null
      for (child in comp.components) {
        val childWidth = state?.width(child) ?: child.width
        val childHeight = state?.height(child) ?: child.height
        if (!child.isVisible || childWidth <= 0 || childHeight <= 0) {
          continue
        }
        val childOrthogonalSize = computeChildOrthogonalSize(comp, child, orthogonalSize, isVertical, state)
        val childMin = computeMinZoomPreservingSizeOrNull(child, isVertical, childOrthogonalSize, state) ?: continue
        if (currentSize <= 0) {
          return 0
        }
        val childCurrent = if (isVertical) childHeight else childWidth
        val needed = (childMin + (currentSize - childCurrent)).coerceAtLeast(0)
        required = if (required == null) needed else max(required, needed)
      }
      if (required == null) {
        return null
      }
      val toolbarPanel = (comp.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH)
      if (comp is AbstractDevicePanel<*> && toolbarPanel != null && toolbarPanel.isVisible) {
        val compWidth = state?.width(comp) ?: comp.width
        val toolbarHeight = state?.height(toolbarPanel) ?: toolbarPanel.height
        if (isVertical && orthogonalSize > 0 && orthogonalSize != compWidth) {
          val originalBounds = Rectangle(toolbarPanel.bounds)
          val delta = probeToolbarHeightDelta(toolbarPanel, orthogonalSize, toolbarHeight)
          toolbarPanel.bounds = originalBounds
          layoutToolbarPanel(toolbarPanel)
          required = (required + delta).coerceAtLeast(0)
        } else if (!isVertical && required < currentSize) {
          val minVertical = computeMinZoomPreservingSizeOrNull(comp, isVertical = true) ?: comp.height
          val verticalSlack = (comp.height - minVertical).coerceAtLeast(0)
          val originalBounds = Rectangle(toolbarPanel.bounds)
          val originalHeight = toolbarPanel.height
          if (probeToolbarHeightDelta(toolbarPanel, required, originalHeight) > verticalSlack) {
            var low: Int = required
            var high = currentSize
            while (low < high) {
              val mid = (low + high) ushr 1
              if (probeToolbarHeightDelta(toolbarPanel, mid, originalHeight) <= verticalSlack) {
                high = mid
              } else {
                low = mid + 1
              }
            }
            required = low
          }
          toolbarPanel.bounds = originalBounds
          layoutToolbarPanel(toolbarPanel)
        }
      }
      return required
    }
    return null
  }

  /**
   * Snapshot of component dimensions, splitter proportions, and leaf [ZoomablePanel] zoom-preserving vertical minimums captured prior to
   * [applyTargetRootSize] so vertical constraints can be re-evaluated accurately if root stretching is clamped.
   */
  private class PreStretchVerticalState(
    private val widths: IdentityHashMap<Component, Int>,
    private val heights: IdentityHashMap<Component, Int>,
    private val proportions: IdentityHashMap<Splitter, Float>,
    private val leafMinHeights: IdentityHashMap<Component, Int>,
  ) {
    fun width(component: Component): Int = widths[component] ?: component.width

    fun height(component: Component): Int = heights[component] ?: component.height

    fun proportion(splitter: Splitter): Float = proportions[splitter] ?: splitter.proportion

    fun hasLeafMinHeight(component: Component): Boolean = leafMinHeights.containsKey(component)

    fun leafMinHeight(component: Component): Int? = leafMinHeights[component]?.takeIf { it >= 0 }

    companion object {
      fun capture(root: Component): PreStretchVerticalState {
        val widths = IdentityHashMap<Component, Int>()
        val heights = IdentityHashMap<Component, Int>()
        val proportions = IdentityHashMap<Splitter, Float>()
        val leafMinHeights = IdentityHashMap<Component, Int>()
        fun visit(comp: Component) {
          widths[comp] = comp.width
          heights[comp] = comp.height
          if (comp is Splitter) {
            proportions[comp] = comp.proportion
          }
          if (isZoomableLeaf(comp)) {
            leafMinHeights[comp] = computeLeafMinZoomPreservingSizeOrNull(comp, isVertical = true) ?: -1
          }
          if (comp is Container) {
            for (child in comp.components) {
              visit(child)
            }
          }
        }
        visit(root)
        return PreStretchVerticalState(widths, heights, proportions, leafMinHeights)
      }
    }
  }

  private fun probeToolbarHeightDelta(toolbarPanel: Component, width: Int, originalHeight: Int): Int {
    toolbarPanel.setSize(width.coerceAtLeast(0), originalHeight)
    layoutToolbarPanel(toolbarPanel)
    return toolbarPanel.preferredSize.height - originalHeight
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
    return (if (splitter.isVertical) child.minimumSize.height else child.minimumSize.width).coerceAtLeast(0)
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

  protected class ResizeCapabilities(
    val canExpandWidth: Boolean,
    val canExpandHeight: Boolean,
    val canShrinkWidth: Boolean = canExpandWidth,
    val canShrinkHeight: Boolean = canExpandHeight,
  ) {
    fun clampWidth(targetWidth: Int, currentWidth: Int): Int =
      when {
        targetWidth > currentWidth -> if (canExpandWidth) targetWidth else currentWidth
        targetWidth < currentWidth -> if (canShrinkWidth) targetWidth else currentWidth
        else -> currentWidth
      }

    companion object {
      val UNRESTRICTED = ResizeCapabilities(canExpandWidth = true, canExpandHeight = true)
    }
  }

  companion object {
    internal fun isLayoutInspectorActive(toolWindow: ToolWindow): Boolean {
      return toolWindow.contentManagerIfCreated?.contentsRecursively?.any {
        it.isSelected && (it.component as? AbstractDevicePanel<*>)?.isLayoutInspectorActive == true
      } == true
    }

    private fun isZoomableLeaf(comp: Component): Boolean =
      comp is JScrollPane && comp.viewport?.view is ZoomablePanel ||
        comp is JViewport && comp.view is ZoomablePanel ||
        comp is ZoomablePanel

    private fun computeLeafMinZoomPreservingSizeOrNull(comp: Component, isVertical: Boolean): Int? {
      if (!comp.isVisible) {
        return null
      }
      val currentSize = if (isVertical) comp.height else comp.width
      val scrollPane = comp as? JScrollPane
      val view =
        when (comp) {
          is JScrollPane -> comp.viewport.view as ZoomablePanel
          is JViewport -> comp.view as ZoomablePanel
          else -> comp as ZoomablePanel
        }
      if (!view.isVisible) {
        return null
      }
      if (currentSize <= 0) {
        return 0
      }
      val actualSize = view.computeActualSize(view.framing)
      if (actualSize.width <= 0 || actualSize.height <= 0) {
        return currentSize
      }
      if (view.isPreferredSizeSet) {
        val prefSize = view.preferredSize
        return if (isVertical) {
          val hBarHeight = if (scrollPane?.horizontalScrollBar?.isVisible == true) scrollPane.horizontalScrollBar.height else 0
          (prefSize.height + hBarHeight).coerceIn(0, currentSize)
        } else {
          val vBarWidth = if (scrollPane?.verticalScrollBar?.isVisible == true) scrollPane.verticalScrollBar.width else 0
          (prefSize.width + vBarWidth).coerceIn(0, currentSize)
        }
      }
      val scale = view.scale
      val actualDim = if (isVertical) actualSize.height else actualSize.width
      val ssf = view.screenScalingFactor
      var minDim = ceil(actualDim * scale / ssf).toInt().coerceAtLeast(1)
      while (minDim < currentSize && minDim.scaled(ssf).toDouble() / actualDim < scale) {
        minDim++
      }
      return minDim.coerceAtMost(currentSize)
    }
  }
}
