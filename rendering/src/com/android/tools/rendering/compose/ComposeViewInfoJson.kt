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
@file:JvmName("ComposeViewInfoJson")

package com.android.tools.rendering.compose

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/**
 * JSON serialization and deserialization for [ComposeViewInfo] component hierarchies (`b/571093046`).
 *
 * When a Compose preview is rendered outside Android Studio's JVM process (for example, by `standalone-render` / `android preview`), the
 * Layoutlib [com.android.ide.common.rendering.api.ViewInfo] hierarchy is disposed once rendering completes. To make component bounds and
 * source locations available to out-of-process tools such as the Compose Preview sidecar (`GET /api/bounds`), the rendered hierarchy is
 * serialized alongside each preview screenshot (`<stem>.png` -> `<stem>.bounds.json`) using [boundsJsonPath].
 *
 * ### Wire Format Schema
 *
 * A serialized bounds file contains a JSON array of root [ComposeViewInfo] nodes (typically a single root per rendered preview). Each node
 * object has the following shape:
 * ```json
 * [
 *   {
 *     "name": "Column",
 *     "fileName": "HomeScreen.kt",
 *     "lineNumber": 42,
 *     "packageHash": 12345678,
 *     "bounds": {
 *       "left": 0,
 *       "top": 0,
 *       "right": 1080,
 *       "bottom": 2400
 *     },
 *     "children": [
 *       {
 *         "name": "Button",
 *         "fileName": "HomeScreen.kt",
 *         "lineNumber": 45,
 *         "packageHash": 12345678,
 *         "bounds": { "left": 32, "top": 64, "right": 320, "bottom": 192 },
 *         "children": []
 *       }
 *     ]
 *   }
 * ]
 * ```
 *
 * ### Field Semantics & Units
 * - **`name`** (`string`): Composable function or node name emitted by Compose UI tooling, or `""` when unavailable.
 * - **`fileName`** (`string`): Source file name (e.g. `"HomeScreen.kt"`), or `""` when no source location is available.
 * - **`lineNumber`** (`int`): 1-indexed source line number in `fileName`, or `-1` when unknown.
 * - **`packageHash`** (`int`): Compose compiler package hash disambiguating files across packages, or `-1` when unknown.
 * - **`bounds`** (`object`): Bounding rectangle `{ "left", "top", "right", "bottom" }` in **physical pixels (`px`)** relative to the
 *   top-left `(0, 0)` origin of the rendered screenshot image.
 * - **`children`** (`array`): Ordered child [ComposeViewInfo] nodes within the same coordinate space.
 */
private const val FIELD_NAME = "name"
private const val FIELD_FILE_NAME = "fileName"
private const val FIELD_LINE_NUMBER = "lineNumber"
private const val FIELD_PACKAGE_HASH = "packageHash"
private const val FIELD_BOUNDS = "bounds"
private const val FIELD_CHILDREN = "children"
private const val FIELD_LEFT = "left"
private const val FIELD_TOP = "top"
private const val FIELD_RIGHT = "right"
private const val FIELD_BOTTOM = "bottom"

/**
 * Returns the companion `.bounds.json` path adjacent to a rendered preview image file ([imagePath]).
 *
 * For example, `/out/com/example/HomePreview_0.png` resolves to `/out/com/example/HomePreview_0.bounds.json`.
 */
fun boundsJsonPath(imagePath: Path): Path {
  val fileName = imagePath.fileName.toString()
  val stem = fileName.substringBeforeLast('.', fileName)
  return imagePath.resolveSibling("$stem.bounds.json")
}

/** Recursively converts this [ComposeViewInfo] node and all of its [ComposeViewInfo.children] into a Gson [JsonObject]. */
fun ComposeViewInfo.toJsonObject(): JsonObject =
  JsonObject().apply {
    addProperty(FIELD_NAME, name)
    addProperty(FIELD_FILE_NAME, sourceLocation.fileName)
    addProperty(FIELD_LINE_NUMBER, sourceLocation.lineNumber)
    addProperty(FIELD_PACKAGE_HASH, sourceLocation.packageHash)
    add(
      FIELD_BOUNDS,
      JsonObject().apply {
        addProperty(FIELD_LEFT, bounds.left)
        addProperty(FIELD_TOP, bounds.top)
        addProperty(FIELD_RIGHT, bounds.right)
        addProperty(FIELD_BOTTOM, bounds.bottom)
      },
    )
    add(FIELD_CHILDREN, children.toJsonArray())
  }

/** Converts a list of root [ComposeViewInfo] trees into a Gson [JsonArray]. */
fun List<ComposeViewInfo>.toJsonArray(): JsonArray = JsonArray().also { array -> forEach { array.add(it.toJsonObject()) } }

/**
 * Deserializes a JSON array string produced by [toJsonArray] back into an in-memory list of [ComposeViewInfo] trees.
 *
 * Missing optional scalar fields default to `""` (`name`, `fileName`) or `-1` (`lineNumber`, `packageHash`), matching [SourceLocation]
 * empty sentinel conventions.
 */
fun parseComposeViewInfosJson(json: String): List<ComposeViewInfo> =
  JsonParser.parseString(json).asJsonArray.map { parseComposeViewInfoObject(it.asJsonObject) }

private fun parseComposeViewInfoObject(obj: JsonObject): ComposeViewInfo {
  val name = obj.get(FIELD_NAME)?.asString ?: ""
  val fileName = obj.get(FIELD_FILE_NAME)?.asString ?: ""
  val lineNumber = obj.get(FIELD_LINE_NUMBER)?.asInt ?: -1
  val packageHash = obj.get(FIELD_PACKAGE_HASH)?.asInt ?: -1
  val boundsObj = obj.getAsJsonObject(FIELD_BOUNDS)
  val bounds =
    PxBounds(
      left = boundsObj.get(FIELD_LEFT).asInt,
      top = boundsObj.get(FIELD_TOP).asInt,
      right = boundsObj.get(FIELD_RIGHT).asInt,
      bottom = boundsObj.get(FIELD_BOTTOM).asInt,
    )
  val children = obj.getAsJsonArray(FIELD_CHILDREN)?.map { parseComposeViewInfoObject(it.asJsonObject) }.orEmpty()
  return ComposeViewInfo(SourceLocationImpl(fileName, lineNumber, packageHash), bounds, children, name)
}
