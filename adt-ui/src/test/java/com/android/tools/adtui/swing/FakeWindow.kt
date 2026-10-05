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
package com.android.tools.adtui.swing

import com.android.mockito.kotlin.mockStatic
import com.android.mockito.kotlin.whenever
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ReflectionUtil
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowFocusListener
import javax.swing.JComponent
import javax.swing.RootPaneContainer
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

internal inline fun <reified T : Window> createFakeWindow(root: JComponent, parentDisposable: Disposable): T {
  // A mock is used here because in a headless environment it is not possible to instantiate
  // Window or any of its subclasses due to checks in the Window constructor.
  val mockWindow = mock(T::class.java)
  wrapInFakeWindow(mockWindow, root, parentDisposable)
  parentDisposable.addWindow(mockWindow)
  return mockWindow
}

private fun wrapInFakeWindow(mockWindow: Window, root: JComponent, parentDisposable: Disposable?) {
  val components = arrayOf(root)
  val windowFocusListeners = mutableListOf<WindowFocusListener>()
  whenever(mockWindow.treeLock).thenCallRealMethod()
  whenever(mockWindow.toolkit).thenReturn(fakeToolkit)
  whenever(mockWindow.isShowing).thenReturn(true)
  whenever(mockWindow.isActive).thenReturn(true)
  whenever(mockWindow.isVisible).thenReturn(true)
  // We also set the 'visible' field itself because it is used by isRecursivelyVisible(), a non-mockable package-private method.
  check(ReflectionUtil.setField(Window::class.java, mockWindow, Boolean::class.java, "visible", true))
  ReflectionUtil.findField(Window::class.java, null, "appContext").let { field ->
    val appContext = Class.forName("sun.awt.AppContext").getMethod("getAppContext").invoke(null)
    field.isAccessible = true
    field.set(mockWindow, appContext)
  }
  whenever(mockWindow.isEnabled).thenReturn(true)
  whenever(mockWindow.isLightweight).thenReturn(true)
  whenever(mockWindow.isFocusableWindow).thenReturn(true)
  whenever(mockWindow.locationOnScreen).thenReturn(Point(0, 0))
  whenever(mockWindow.insets).thenReturn(JBUI.emptyInsets())
  whenever(mockWindow.size).thenAnswer { root.size }
  whenever(mockWindow.width).thenAnswer { root.width }
  whenever(mockWindow.height).thenAnswer { root.height }
  whenever(mockWindow.bounds).thenAnswer { Rectangle(0, 0, root.width, root.height) }
  whenever(mockWindow.maximumSize).thenAnswer { Dimension(root.width, root.height) }
  var minimumSize = Dimension(0, 0)
  whenever(mockWindow.minimumSize).thenAnswer { minimumSize }
  doAnswer { invocation ->
      minimumSize = invocation.getArgument(0)
      null
    }
    .whenever(mockWindow)
    .minimumSize = any()
  doAnswer { invocation ->
      val w = invocation.getArgument<Int>(0)
      val h = invocation.getArgument<Int>(1)
      root.setSize(w, h)
      for (child in (root as? java.awt.Container)?.components.orEmpty()) {
        child.setSize(w, h)
      }
      root.validate()
      null
    }
    .whenever(mockWindow)
    .setSize(anyInt(), anyInt())
  doAnswer { invocation ->
      val d = invocation.getArgument<Dimension>(0)
      mockWindow.setSize(d.width, d.height)
      null
    }
    .whenever(mockWindow)
    .size = any()
  doAnswer {
      root.validate()
      null
    }
    .whenever(mockWindow)
    .validate()
  whenever(mockWindow.ownedWindows).thenReturn(emptyArray())
  whenever(mockWindow.isFocused).thenReturn(true)
  whenever(mockWindow.getFocusTraversalKeys(anyInt())).thenCallRealMethod()
  whenever(mockWindow.components).thenReturn(components)
  whenever(mockWindow.graphics).thenCallRealMethod()
  if (mockWindow is RootPaneContainer) {
    whenever(mockWindow.contentPane).thenReturn(root)
  }
  doAnswer { invocation -> windowFocusListeners.add(invocation.arguments[0] as WindowFocusListener) }
    .whenever(mockWindow)
    .addWindowFocusListener(any())
  doAnswer { invocation -> windowFocusListeners.remove(invocation.arguments[0] as WindowFocusListener) }
    .whenever(mockWindow)
    .removeWindowFocusListener(any())
  doAnswer { windowFocusListeners.toTypedArray() }.whenever(mockWindow).windowFocusListeners
  ComponentAccessor.setPeer(mockWindow, FakeWindowPeer())
  ComponentAccessor.setParent(root, mockWindow)
  root.addNotify()
  if (parentDisposable != null) {
    Disposer.register(parentDisposable) {
      runInEdtAndWait {
        ComponentAccessor.setParent(root, null)
        root.removeNotify()
      }
    }
  }
}

private fun Disposable.addWindow(window: Window) {
  windows.add(window)
  if (windowStatic == null) {
    windowStatic = mockStatic<Window>().apply { whenever<Array<Window>>(Window::getWindows).thenAnswer { windows.toTypedArray() } }
  }
  Disposer.register(this) {
    windows.remove(window)
    if (windows.isEmpty()) {
      windowStatic?.close()
      windowStatic = null
    }
  }
}

private val fakeToolkit = FakeUiToolkit()
private val windows = mutableListOf<Window>()
private var windowStatic: MockedStatic<Window>? = null
