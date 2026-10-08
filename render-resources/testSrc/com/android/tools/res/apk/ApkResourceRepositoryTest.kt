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
package com.android.tools.res.apk

import com.android.ide.common.rendering.api.ArrayResourceValue
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.PluralsResourceValue
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.StyleResourceValue
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.resources.getConfiguredResources
import com.android.resources.ResourceType
import com.android.testutils.TestUtils
import com.android.tools.res.ids.apk.ApkResourceIdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkResourceRepositoryTest {

  @Test
  fun testResourceValues() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-for-local-test.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }

    val animRes =
      apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ANIM, "fragment_fast_out_extra_slow_in"))[0]

    assertTrue(animRes.resourceValue?.value?.endsWith("res/anim-v21/fragment_fast_out_extra_slow_in.xml") == true)

    val dimenRes =
      apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DIMEN, "compat_control_corner_material"))[0]

    assertEquals("2dp", dimenRes.resourceValue?.value)

    val strRes =
      apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "news_notification_channel_description"))[0]

    assertEquals("The latest updates on what's new in Android", strRes.resourceValue?.value)
  }

  @Test
  fun testAttrValues() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-for-local-test.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }

    val attrRes = apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ATTR, "buttonSize"))[0]

    val attrVals = (attrRes.resourceValue as AttrResourceValue).attributeValues
    assertEquals(3, attrVals.size)
    assertEquals(
      """
      icon_only
      standard
      wide
      """
        .trimIndent(),
      attrVals.keys.sorted().joinToString("\n"),
    )
    assertEquals(0, attrVals["standard"])
    assertEquals(1, attrVals["wide"])
    assertEquals(2, attrVals["icon_only"])
  }

  @Test
  fun testStyleValues() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-for-local-test.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }

    val styleRes = apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STYLE, "DialogWindowTheme"))[0]
    val styleValue = styleRes.resourceValue as StyleResourceValue
    assertEquals("", styleValue.parentStyleName)
    assertNull(styleValue.parentStyle)

    val styleItems = styleValue.definedItems.toList()
    assertEquals(1, styleItems.size)
    assertEquals("android:windowClipToOutline", styleItems[0].attrName)
    assertEquals("false", styleItems[0].value)
  }

  @Test
  fun testPluralsValues() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-with-plurals.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }

    val pluralsRes =
      apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.PLURALS, "mtrl_badge_content_description"))[0]

    val pluralsItem = pluralsRes.resourceValue as PluralsResourceValue
    assertEquals(2, pluralsItem.pluralsCount)
    assertEquals("%d new notification", pluralsItem.getValue(0))
    assertEquals("one", pluralsItem.getQuantity(0))
    assertEquals("%d new notifications", pluralsItem.getValue(1))
    assertEquals("other", pluralsItem.getQuantity(1))
  }

  @Test
  fun testAllResources() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-all-resources.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }

    val pluralsRes = apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.PLURALS, "numberOfSongsAvailable"))[0]

    val pluralsItem = pluralsRes.resourceValue as PluralsResourceValue
    assertEquals(2, pluralsItem.pluralsCount)
    assertEquals("%d song found.", pluralsItem.getValue(0))
    assertEquals("one", pluralsItem.getQuantity(0))
    assertEquals("%d songs found.", pluralsItem.getValue(1))
    assertEquals("other", pluralsItem.getQuantity(1))

    val planetsArrayRes = apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ARRAY, "planets_array"))[0]

    val planetsArrayItem = planetsArrayRes.resourceValue as ArrayResourceValue
    assertEquals(4, planetsArrayItem.elementCount)
    assertEquals("Mercury", planetsArrayItem.getElement(0))
    assertEquals("Venus", planetsArrayItem.getElement(1))
    assertEquals("Earth", planetsArrayItem.getElement(2))
    assertEquals("Mars", planetsArrayItem.getElement(3))

    val colorsArrayRes = apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ARRAY, "colors"))[0]

    val colorsArrayItem = colorsArrayRes.resourceValue as ArrayResourceValue
    assertEquals(3, colorsArrayItem.elementCount)
    assertEquals("#FFFF0000", colorsArrayItem.getElement(0))
    assertEquals("#FF00FF00", colorsArrayItem.getElement(1))
    assertEquals("#FF0000FF", colorsArrayItem.getElement(2))

    val themeRes = apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STYLE, "Theme.AllResources"))[0]

    val themeItem = themeRes.resourceValue as StyleResourceValue
    assertEquals("Theme.MaterialComponents.DayNight.DarkActionBar", themeItem.parentStyleName)
  }

  @Test
  fun testBagTypeResourcesDeclaredAsItems() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-resource-aliases.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }

    fun valueOf(type: ResourceType, name: String): ResourceValue? =
      apkRes.getResources(ResourceReference(ResourceNamespace.RES_AUTO, type, name)).single().resourceValue

    // Regular bag resources are still loaded as bags.
    assertTrue(valueOf(ResourceType.ATTR, "realAttr") is AttrResourceValue)
    val baseStyle = valueOf(ResourceType.STYLE, "Base") as StyleResourceValue
    assertEquals("@style/aliasStyle", baseStyle.definedItems.single().value)
    assertEquals(listOf("first", "second"), (valueOf(ResourceType.ARRAY, "baseArray") as ArrayResourceValue).toList())
    assertEquals(2, (valueOf(ResourceType.PLURALS, "basePlurals") as PluralsResourceValue).pluralsCount)

    // <item type="..." name="..."/> of a bag type is a simple entry referencing @null.
    for (type in listOf(ResourceType.ATTR, ResourceType.ARRAY, ResourceType.PLURALS)) {
      val value = valueOf(type, "empty${type.name.lowercase().replaceFirstChar { it.uppercase() }}")!!
      assertEquals("$type", type, value.resourceType)
      assertEquals("$type", "@null", value.value)
      assertFalse(
        "$type",
        value is AttrResourceValue || value is StyleResourceValue || value is ArrayResourceValue || value is PluralsResourceValue,
      )
    }
    // Except for an empty style (@null or @empty), which is loaded as a style without parent and items, like an empty <style> tag.
    for (name in listOf("emptyStyle", "emptyValueStyle")) {
      val emptyStyle = valueOf(ResourceType.STYLE, name) as StyleResourceValue
      assertEquals(name, "", emptyStyle.parentStyleName)
      assertTrue(name, emptyStyle.definedItems.isEmpty())
    }

    // Resource aliases are simple entries referencing the aliased resource.
    assertEquals("@attr/realAttr", valueOf(ResourceType.ATTR, "aliasAttr")?.value)
    assertEquals("@style/Base", valueOf(ResourceType.STYLE, "aliasStyle")?.value)
    assertEquals("@array/baseArray", valueOf(ResourceType.ARRAY, "aliasArray")?.value)
    assertEquals("@plurals/basePlurals", valueOf(ResourceType.PLURALS, "aliasPlurals")?.value)
  }

  @Test
  fun testUnresolvableAttrAlias() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-resource-aliases.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val realAttrRef = ResourceReference.attr(ResourceNamespace.RES_AUTO, "realAttr")
    // Simulates an alias to an attribute that the resolver does not know, e.g. a framework attribute newer than the bundled layoutlib.
    val apkRes = ApkResourceRepository(path.toString()) { resId -> idManager.findById(resId)?.takeUnless { it == realAttrRef } }

    val aliasAttr = apkRes.getResources(ResourceReference.attr(ResourceNamespace.RES_AUTO, "aliasAttr")).single().resourceValue
    assertTrue(aliasAttr is AttrResourceValue)
    assertTrue((aliasAttr as AttrResourceValue).attributeValues.isEmpty())
    // The rest of the repository is loaded.
    assertEquals(
      "@style/Base",
      apkRes.getResources(ResourceReference.style(ResourceNamespace.RES_AUTO, "aliasStyle")).single().resourceValue?.value,
    )
  }

  @Test
  fun testResourceAliasesAreResolved() {
    val path = TestUtils.resolveWorkspacePath(TEST_DATA_DIR + "apk-resource-aliases.ap_")
    val idManager = ApkResourceIdManager().apply { this.loadApkResources(path.toString()) }
    val apkRes = ApkResourceRepository(path.toString()) { idManager.findById(it) }
    val baseStyleRef = ResourceReference.style(ResourceNamespace.RES_AUTO, "Base")
    val resolver = ResourceResolver.create(apkRes.getConfiguredResources(FolderConfiguration.createDefault()).rowMap(), baseStyleRef)

    fun resolve(type: ResourceType, name: String): ResourceValue? =
      resolver.resolveResValue(resolver.getUnresolvedResource(ResourceReference(ResourceNamespace.RES_AUTO, type, name)))

    assertEquals(baseStyleRef, (resolve(ResourceType.STYLE, "aliasStyle") as StyleResourceValue).asReference())
    assertEquals(listOf("first", "second"), (resolve(ResourceType.ARRAY, "aliasArray") as ArrayResourceValue).toList())
    assertEquals("basePlurals", (resolve(ResourceType.PLURALS, "aliasPlurals") as PluralsResourceValue).name)
    assertEquals("realAttr", (resolve(ResourceType.ATTR, "aliasAttr") as AttrResourceValue).name)

    // A theme attribute pointing to a style alias resolves to the aliased style, like AssetManager2::ResolveReference does.
    val styleFromThemeAttr = resolver.getStyle(ResourceReference.attr(ResourceNamespace.RES_AUTO, "realAttr"))
    assertEquals(baseStyleRef, styleFromThemeAttr?.asReference())

    // An empty style resolves to a style (layoutlib's Resources.Theme.applyStyle casts resolved style resources to StyleResourceValue).
    val emptyStyleRef = ResourceReference.style(ResourceNamespace.RES_AUTO, "emptyStyle")
    val emptyStyle = resolver.getResolvedResource(emptyStyleRef) as StyleResourceValue
    assertEquals(emptyStyleRef, emptyStyle.asReference())
    assertNull(resolver.getParent(emptyStyle))
  }
}
