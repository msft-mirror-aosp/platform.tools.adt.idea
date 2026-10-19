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
package com.android.tools.idea.preview

import com.android.tools.preview.PreviewElement
import com.android.tools.preview.PreviewElementInstance

/**
 * Finds the element in this collection that best matches [target]. [target] might be a stale instance of an element that has since been
 * recreated, for example because its [com.android.tools.preview.PreviewDisplaySettings] changed. Returns null if [target] is null or if no
 * unambiguous match exists, so callers can apply their own fallback.
 *
 * This is a best-effort heuristic. Candidates are checked from the most to the least specific criteria:
 * 1. An element equal to [target].
 * 2. Elements sharing the [PreviewElement.previewElementDefinition] of [target]. If there is only one, it is returned. Otherwise (e.g. the
 *    leaves of a MultiPreview or the instances of a parameterized preview), the element with the same display name and instance id, or with
 *    the same display name, is returned. As a weaker criteria, the only element with the same instance id is returned.
 * 3. The only element with the same display name and instance id, then the only element with the same display name and base name, then the
 *    only element with the same instance id and, as a last resort, the only element with the same display name, which might be a preview
 *    from a different function.
 *
 * The weaker criteria only return a match if it's unique. For example, if the selected leaf of a MultiPreview is renamed, there is no
 * single element that matches its instance id and null is returned instead of an arbitrary sibling.
 *
 * Note that [PreviewElementInstance.instanceId] is not unique on its own. Non-parameterized instances use the method FQN as their id, so
 * all the previews of a function, including all the leaves of a MultiPreview, share it.
 */
internal fun <T : PreviewElement<*>> Collection<T>.findMatchingElement(target: PreviewElement<*>?): T? {
  if (target == null) return null
  val targetInstanceId = target.instanceIdOrNull()
  fun T.hasSameName() = displaySettings.name == target.displaySettings.name
  fun T.hasSameBaseName() = displaySettings.baseName == target.displaySettings.baseName
  fun T.hasSameInstanceId() = targetInstanceId != null && instanceIdOrNull() == targetInstanceId

  val sameDefinition = target.previewElementDefinition?.let { definition -> filter { it.previewElementDefinition == definition } }.orEmpty()
  return find { it == target }
    ?: sameDefinition.singleOrNull()
    ?: sameDefinition.find { it.hasSameName() && it.hasSameInstanceId() }
    ?: sameDefinition.find { it.hasSameName() }
    ?: sameDefinition.singleOrNull { it.hasSameInstanceId() }
    ?: singleOrNull { it.hasSameName() && it.hasSameInstanceId() }
    ?: singleOrNull { it.hasSameName() && it.hasSameBaseName() }
    ?: singleOrNull { it.hasSameInstanceId() }
    ?: singleOrNull { it.hasSameName() }
}

private fun PreviewElement<*>.instanceIdOrNull(): String? = (this as? PreviewElementInstance<*>)?.instanceId
