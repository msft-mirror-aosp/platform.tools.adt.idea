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
package com.android.tools.idea.rendering

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceValue
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.tools.dom.ActivityAttributesSnapshot
import com.android.tools.idea.model.AndroidManifestIndex
import com.android.tools.idea.model.AndroidManifestRawText
import com.android.tools.idea.model.MergedManifestSnapshotFactory.ManifestResourceValue
import com.android.tools.rendering.api.RenderModelManifest
import com.android.xml.AndroidManifest.ATTRIBUTE_ICON
import com.android.xml.AndroidManifest.ATTRIBUTE_LABEL
import org.jetbrains.android.facet.AndroidFacet

class RenderIndexedManifest(val facet: AndroidFacet) : RenderModelManifest {
  private val data: List<AndroidManifestRawText> = AndroidManifestIndex.getDataForMergedManifestContributors(facet).toList()
  override val isRtlSupported: Boolean = data.firstNotNullOfOrNull { it.supportsRtl }?.equals("true") ?: false
  override val applicationLabel: ResourceValue? =
    data.firstNotNullOfOrNull { it.label }?.toResourceValue(ResourceType.STRING, ATTRIBUTE_LABEL)
  override val applicationIcon: ResourceValue? =
    data.firstNotNullOfOrNull { it.icon }?.toResourceValue(ResourceType.DRAWABLE, ATTRIBUTE_ICON)

  private fun String.toResourceValue(resourceType: ResourceType, name: String) =
    ResourceUrl.parse(this)?.resolve(ResourceNamespace.RES_AUTO, ResourceNamespace.Resolver.EMPTY_RESOLVER)?.let {
      ManifestResourceValue(ResourceNamespace.RES_AUTO, resourceType, name, this, it)
    }

  override fun getActivityAttributes(activity: String): ActivityAttributesSnapshot? =
    data
      .firstNotNullOfOrNull { it.activities.firstOrNull { it.name == activity } }
      ?.let { activityRawText ->
        ActivityAttributesSnapshot(
          activityRawText.icon?.toResourceValue(ResourceType.DRAWABLE, ATTRIBUTE_ICON),
          activityRawText.label?.toResourceValue(ResourceType.STRING, ATTRIBUTE_LABEL),
          activity,
          activityRawText.parentActivityName,
          activityRawText.theme,
          activityRawText.uiOptions,
        )
      }
}
