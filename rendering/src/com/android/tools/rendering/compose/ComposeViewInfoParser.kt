/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.ide.common.rendering.api.ViewInfo

/**
 * Parses the [ComposeViewInfo] hierarchy from [rootViewInfo]'s `ComposeViewAdapter` instance without requiring IntelliJ UI/Logger
 * dependencies.
 */
fun parseViewInfo(
  rootViewInfo: ViewInfo,
  onWarning: (String, Throwable?) -> Unit = { _, _ -> },
  onDebug: (Throwable) -> Unit = {},
): List<ComposeViewInfo> {
  try {
    val viewObj = findComposeViewAdapter(rootViewInfo.viewObject) ?: return emptyList()
    // With JDK 11, Kotlin reflection fails to find the declaredProperties (b/162686073).
    // For now, we use Java reflection to find the getter method while avoiding Kotlin internal name mangling.
    val viewInfoMethod = viewObj::class.java.declaredMethods.single { it.name.contains("getViewInfos") }.also { it.isAccessible = true }
    val composeViewInfos = viewInfoMethod.invoke(viewObj) as List<*>
    val viewInfos = parseBounds(composeViewInfos, onWarning)

    if (viewInfos.size > 1) {
      // TODO(b/419541055): Investigate multiple top-level ComposeViewInfo entries when merging trees.
      onWarning(
        "Expected one ComposeViewInfo. Received multiple entries. This indicates a failure in the " +
          "stitchTree function's merging logic. While a list is accepted to prevent immediate " +
          "failure, the presence of multiple entries here requires investigation of stitchTree. " +
          "Alternatively, if multiple entries are now expected, this assert should be removed or " +
          "modified.",
        null,
      )
    }
    return viewInfos
  } catch (e: Exception) {
    onDebug(e)
    return emptyList()
  }
}

private fun parseBounds(elements: List<Any?>, onWarning: (String, Throwable?) -> Unit): List<ComposeViewInfo> =
  elements.mapNotNull { item ->
    parseViewInfoNode(item, onWarning)
  }

private fun parseViewInfoNode(item: Any?, onWarning: (String, Throwable?) -> Unit): ComposeViewInfo? =
  try {
    val node = item!!
    val fileName = node.invokeAccessible("getFileName") as String
    val lineNumber = node.invokeAccessible("getLineNumber") as Int
    val bounds = getBound(node)
    val children = node.invokeAccessible("getChildren") as List<Any?>
    val packageHash =
      try {
        node.invokeAccessible("getLocation")?.let { it.invokeAccessible("getPackageHash") as Int }
      } catch (t: Throwable) {
        onWarning(t.message ?: t.javaClass.simpleName, t)
        null
      } ?: -1

    val sourceLocation = SourceLocationImpl(fileName, lineNumber, packageHash)
    val name =
      try {
        node.invokeAccessible("getName") as String?
      } catch (_: Throwable) {
        null
      }
    ComposeViewInfo(sourceLocation, bounds, parseBounds(children, onWarning), name ?: "")
  } catch (t: Throwable) {
    onWarning(t.message ?: t.javaClass.simpleName, t)
    null
  }

private fun getBound(viewInfo: Any): PxBounds {
  val bounds = viewInfo.invokeAccessible("getBounds")!!
  val topPx = bounds.invokeAccessible("getTop")!!
  val bottomPx = bounds.invokeAccessible("getBottom")!!
  val rightPx = bounds.invokeAccessible("getRight")!!
  val leftPx = bounds.invokeAccessible("getLeft")!!

  return PxBounds(left = getInt(leftPx), top = getInt(topPx), right = getInt(rightPx), bottom = getInt(bottomPx))
}

private fun getInt(px: Any): Int {
  // dev10 started using inline classes so we might have an Int already
  if (px is Int) return px

  val value = px.invokeAccessible("getValue")
  // In dev05, the type of Px changed from Float to Int. We need to handle both cases here for
  // backwards compatibility
  return value as? Int ?: (value as Float).toInt()
}

private fun Any.invokeAccessible(methodName: String): Any? = javaClass.getMethod(methodName).apply { isAccessible = true }.invoke(this)
