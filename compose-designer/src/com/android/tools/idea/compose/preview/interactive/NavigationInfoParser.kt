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
package com.android.tools.idea.compose.preview.interactive

/** Property name to reflectively unwrap the `Scene` from a Navigation 3 `SceneInfo`. */
private const val SCENE_PROPERTY = "scene"

/** Property name to reflectively access `entries: List<NavEntry<T>>` from a `Scene`. */
private const val ENTRIES_PROPERTY = "entries"

/** Property name to reflectively read the destination key from a `NavEntry`. */
private const val KEY_PROPERTY = "key"

/**
 * Structured representation of a parsed navigation stack entry for display in the interactive preview UI.
 *
 * @property navKey The extracted human-readable navigation key or screen name (e.g., "Home", "Product(id=1)").
 */
data class NavigationItemInfo(val navKey: String)

/**
 * Extracts a list of [NavigationItemInfo] objects from a navigation back stack history item using reflection.
 *
 * In Navigation 3, the back stack history exposed by `NavigationEventDispatcher` contains `NavigationEventInfo` instances—specifically
 * `androidx.navigation3.scene.SceneInfo`. Each `SceneInfo` wraps a `Scene` (such as `SinglePaneScene`, `TwoPaneScene`, `ListDetailScene`,
 * or `DialogScene`), and every `Scene` exposes its active panes via `entries: List<NavEntry<T>>`, where each `NavEntry` holds its
 * destination key in `private val key: T`.
 *
 * Traversing `SceneInfo.scene` -> `Scene.entries` -> `NavEntry.key` via reflection supports single-pane, two-pane, and arbitrary multi-pane
 * scenes without relying on `toString()` regex parsing.
 *
 * @param navigationInfoItem The `NavigationEventInfo` history item (or test representation) to inspect.
 * @return A list of [NavigationItemInfo] objects representing each active pane in the navigation state.
 */
fun parseNavigationItem(navigationInfoItem: Any): List<NavigationItemInfo> {
  if (navigationInfoItem is Iterable<*>) {
    return navigationInfoItem.filterNotNull().flatMap(::parseNavigationItem)
  }

  // 1. Unwrap `SceneInfo.scene` if this item is a `SceneInfo` container.
  val sceneOrItem = getReflectedProperty(navigationInfoItem, SCENE_PROPERTY) ?: navigationInfoItem

  // 2. If the scene exposes `entries: List<NavEntry<T>>` (the public `androidx.navigation3.scene.Scene`
  // interface contract across SinglePaneScene, TwoPaneScene, ListDetailScene, DialogScene, etc.),
  // extract each active `NavEntry`'s `key`.
  val sceneEntries = getReflectedProperty(sceneOrItem, ENTRIES_PROPERTY) as? Iterable<*>
  if (sceneEntries != null) {
    return sceneEntries.filterNotNull().mapNotNull { navEntry ->
      val rawEntryKey = getReflectedProperty(navEntry, KEY_PROPERTY) ?: navEntry
      formatNavKey(rawEntryKey).takeIf { it.isNotEmpty() }?.let(::NavigationItemInfo)
    }
  }

  // 3. Fallback for direct key objects or custom `NavigationEventInfo` implementations.
  return formatNavKey(navigationInfoItem).takeIf { it.isNotEmpty() }?.let { listOf(NavigationItemInfo(navKey = it)) } ?: emptyList()
}

/**
 * Extracts a property value from [targetObject] via reflection by looking up either a zero-argument getter method (e.g., `getScene()`,
 * `getEntries()`) or a declared backing field (e.g., `NavEntry`'s `private val key: T`) across the class hierarchy.
 *
 * Rationale: `SceneInfo`, `Scene`, and `NavEntry` belong to `androidx.navigation3` and `androidx.navigationevent` loaded in the preview
 * classloader. Accessing `Scene.entries` (defined on the `Scene` interface) requires invoking `getEntries()` on potentially internal scene
 * implementations (such as `SinglePaneScene` or `DialogScene`), while accessing `NavEntry.key` requires reading its private `key` field.
 */
private fun getReflectedProperty(targetObject: Any, propertyName: String): Any? {
  val getterMethodName = "get${propertyName.replaceFirstChar { it.uppercaseChar() }}"
  val classHierarchy = generateSequence<Class<*>>(targetObject.javaClass) { it.superclass }

  // 1. Look for a getter method (e.g., getScene(), getEntries()) across the class hierarchy to read the property.
  val getterMethod = classHierarchy.firstNotNullOfOrNull { currentClass ->
    currentClass.declaredMethods.firstOrNull { method ->
      method.name == getterMethodName && method.parameterCount == 0
    }
  }
  if (getterMethod != null) {
    return runCatching {
      getterMethod.isAccessible = true
      getterMethod.invoke(targetObject)
    }
      .getOrNull()
  }

  // 2. Fallback to a declared field up the superclass hierarchy (e.g., `private val key: T` on `NavEntry`).
  val declaredField = classHierarchy.firstNotNullOfOrNull { currentClass ->
    currentClass.declaredFields.firstOrNull { field -> field.name == propertyName }
  }
  if (declaredField != null) {
    return runCatching {
      declaredField.isAccessible = true
      declaredField.get(targetObject)
    }
      .getOrNull()
  }

  return null
}

/**
 * Formats a resolved navigation key instance into a clean, human-readable display string without identity hashcodes.
 *
 * - If [navKeyObject] does not override [Any.toString] (such as a plain `object Home : NavKey`), its default `Object.toString()` starts
 *   with `"${javaClass.name}@"`, so we return its simple class name (`"Home"`), stripping both the package name and the `@hexHashCode`.
 * - If [navKeyObject] overrides `toString()` (such as `data class Product(val id: Int)`, `data object Profile`, or `String`), its
 *   `toString()` representation is returned directly (e.g., `"Product(id=1)"`, `"Profile"`).
 */
private fun formatNavKey(navKeyObject: Any): String {
  val rawString = navKeyObject.toString().trim()
  if (rawString.startsWith("${navKeyObject.javaClass.name}@")) {
    return navKeyObject.javaClass.simpleName.ifEmpty {
      navKeyObject.javaClass.name.substringAfterLast('.').substringAfterLast('$')
    }
  }
  return rawString
}
