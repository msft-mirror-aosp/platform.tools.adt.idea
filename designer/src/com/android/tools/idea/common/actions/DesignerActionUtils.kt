/*
 * Copyright (C) 2020 The Android Open Source Project
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
@file:JvmName("DesignerActionUtils")

package com.android.tools.idea.common.actions

import com.android.annotations.concurrency.AnyThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.ui.EDT
import java.awt.event.KeyEvent
import javax.swing.JTextField

/**
 * Helper function to check if an action event is the key event is from JTextField. In such case we may not process the event. For example,
 * if the shortcut of an Action is a single key stroke, the action will be performed when user is typing the text.
 */
@AnyThread
fun isActionEventFromJTextField(event: AnActionEvent): Boolean {
  val keyEvent = event.inputEvent as? KeyEvent ?: return false
  if (keyEvent.source is JTextField) return true
  val contextComponent = PlatformCoreDataKeys.CONTEXT_COMPONENT.getData(event.dataContext)
  if (contextComponent != null) return contextComponent is JTextField
  if (!EDT.isCurrentThreadEdt()) return false
  val focusOwner = IdeFocusManager.findInstanceByContext(event.dataContext).focusOwner
  return focusOwner is JTextField
}
