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
package com.android.tools.idea.gradle.structure.configurables

import com.android.tools.idea.structure.dialog.ProjectStructureConfigurable
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JTree

/** Number of [com.android.tools.idea.gradle.structure.configurables.ui.IssuesViewerPanel]s (one per issue severity) in `IssuesViewer`. */
internal const val ISSUE_PANELS_PER_ISSUES_VIEWER = 4

/**
 * Drains the IDE event queue and waits for every [JTree] currently under [root] (including `AsyncTreeModel`-backed trees) to finish
 * loading.
 *
 * Only trees present when this is called are awaited; the initial event drain gives queued UI work a chance to create trees first.
 */
internal fun awaitTreesAndEdt(root: JComponent) {
  PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  UIUtil.findComponentsOfType(root, JTree::class.java).forEach { tree -> PlatformTestUtil.waitWhileBusy(tree) }
  PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
}

/** Asserts [value] is non-null with a descriptive Truth failure [message], and returns it. */
internal fun <T : Any> assertNotNullWithMessage(value: T?, message: String): T {
  assertWithMessage(message).that(value).isNotNull()
  return value!!
}

/** Finds the first component of [componentClass] under [root], failing with a descriptive message if absent. */
internal fun <T : JComponent> findRequiredComponent(root: JComponent, componentClass: Class<T>): T =
  assertNotNullWithMessage(
    UIUtil.findComponentOfType(root, componentClass),
    "Expected to find component of type ${componentClass.simpleName}",
  )

/** Finds the [Configurable] of [configurableClass] registered in [psd], failing with a descriptive message if absent. */
internal fun <T : Configurable> findRequiredConfigurable(psd: ProjectStructureConfigurable, configurableClass: Class<T>): T =
  assertNotNullWithMessage(psd.findConfigurable(configurableClass), "Expected to find configurable of type ${configurableClass.simpleName}")
