/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.rendering.compose

const val COMPOSE_VIEW_ADAPTER_FQN = "androidx.compose.ui.tooling.ComposeViewAdapter"

/**
 * Recursively searches [viewObj] and its child views to locate the `ComposeViewAdapter` instance hosting rendered Compose UI content.
 *
 * Safely skips leaf sibling views that do not define `getChildCount()` and continues searching across all siblings at each level.
 */
fun findComposeViewAdapter(viewObj: Any): Any? {
  if (COMPOSE_VIEW_ADAPTER_FQN == viewObj.javaClass.name) {
    return viewObj
  }

  val getChildCount =
    try {
      viewObj.javaClass.getMethod("getChildCount").apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
      return null
    }
  val childrenCount = getChildCount.invoke(viewObj) as Int
  val getChildAt = viewObj.javaClass.getMethod("getChildAt", Int::class.javaPrimitiveType).apply { isAccessible = true }
  for (i in 0 until childrenCount) {
    val child = getChildAt.invoke(viewObj, i) ?: continue
    findComposeViewAdapter(child)?.let {
      return it
    }
  }
  return null
}
