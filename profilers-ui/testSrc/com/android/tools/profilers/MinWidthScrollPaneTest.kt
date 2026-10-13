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

import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Point
import javax.swing.BorderFactory
import javax.swing.JPanel
import org.junit.Rule
import org.junit.Test

class MinWidthScrollPaneTest {

  @get:Rule val applicationRule = ApplicationRule()

  private val content = JPanel(BorderLayout())
  // Created lazily, after the application rule has set up the application that JBScrollPane depends on.
  private val scrollPane by lazy { MinWidthScrollPane(content) { MIN_WIDTH } }

  @Test
  fun `minimum width does not depend on the content`() {
    content.minimumSize = Dimension(5000, 10)

    assertThat(scrollPane.minimumSize.width).isLessThan(MIN_WIDTH)
  }

  @Test
  fun `content keeps the minimum width and scrolls when narrower, and fills the viewport when wider`() {
    layOut(scrollPane, MIN_WIDTH / 2, 400)

    val scrollBar = scrollPane.horizontalScrollBar
    assertThat(content.width).isEqualTo(MIN_WIDTH)
    assertThat(scrollBar.isVisible).isTrue()
    // The scrollbar is laid out below the viewport, not over the content.
    assertThat(scrollPane.viewport.height).isEqualTo(400 - scrollBar.height)
    assertThat(scrollBar.y).isEqualTo(scrollPane.viewport.height)

    scrollPane.viewport.viewPosition = Point(100, 0)
    layOut(scrollPane, MIN_WIDTH + 200, 400)

    assertThat(content.width).isEqualTo(MIN_WIDTH + 200)
    assertThat(content.height).isEqualTo(400)
    assertThat(scrollBar.isVisible).isFalse()
    assertThat(scrollPane.viewport.viewPosition.x).isEqualTo(0)
  }

  @Test
  fun `content keeps the preferred width of visible marked components, including their offset and ancestors' insets`() {
    val keepWidth = MIN_WIDTH + 300
    val marked =
      JPanel().apply {
        preferredSize = Dimension(keepWidth, 20)
        putClientProperty(MinWidthScrollPane.KEEP_PREFERRED_WIDTH, true)
      }
    val wrapper = JPanel(BorderLayout()).apply { border = BorderFactory.createEmptyBorder(0, 30, 0, 20) }
    wrapper.add(marked, BorderLayout.NORTH)
    content.add(wrapper, BorderLayout.NORTH)

    layOut(scrollPane, MIN_WIDTH, 400)
    assertThat(content.width).isEqualTo(30 + keepWidth + 20)
    assertThat(marked.width).isEqualTo(keepWidth)

    marked.isVisible = false
    layOut(scrollPane, MIN_WIDTH, 400)
    assertThat(content.width).isEqualTo(MIN_WIDTH)
  }

  /**
   * Sizes [component] and lays out its whole hierarchy, parents before children, after invalidating it as a change in a displayed hierarchy
   * would.
   */
  private fun layOut(component: Component, width: Int, height: Int) {
    fun invalidateTree(c: Component) {
      c.invalidate()
      (c as? Container)?.components?.forEach(::invalidateTree)
    }
    fun layOutTree(c: Component) {
      c.doLayout()
      (c as? Container)?.components?.forEach(::layOutTree)
    }
    component.setSize(width, height)
    // Twice, as the required width uses the positions of components from the previous layout.
    repeat(2) {
      invalidateTree(component)
      layOutTree(component)
    }
  }

  private companion object {
    const val MIN_WIDTH = 480
  }
}
