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

import com.android.ide.common.rendering.api.ArrayResourceValueImpl
import com.android.ide.common.rendering.api.AttrResourceValueImpl
import com.android.ide.common.rendering.api.PluralsResourceValueImpl
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.rendering.api.StyleItemResourceValueImpl
import com.android.ide.common.rendering.api.StyleResourceValueImpl
import com.android.ide.common.resources.AbstractResourceRepository
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.resources.ResourceType
import com.android.tools.apk.analyzer.BinaryXmlParser
import com.android.tools.environment.Logger
import com.android.tools.res.CacheableResourceRepository
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue as BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.StringPoolChunk
import com.google.devrel.gmscore.tools.apk.arsc.TypeChunk
import java.util.EnumMap

/** [ResourceRepository] for reading resources stored in binary encoded format e.g. in application apk. */
class ApkResourceRepository(apkPath: String, private val resourceIdResolver: (Int) -> ResourceReference?) :
  AbstractResourceRepository(), CacheableResourceRepository {

  private val resourceMap = EnumMap<ResourceType, ListMultimap<String, ResourceItem>>(ResourceType::class.java)

  init {
    val unresolvedSamples = mutableListOf<String>()
    var unresolvedCount = 0
    val onUnresolved: (ResourceReference, Int) -> Unit = { resRef, resId ->
      unresolvedCount++
      if (unresolvedSamples.size < MAX_UNRESOLVED_SAMPLES) {
        val hexId = Integer.toHexString(resId).padStart(8, '0')
        unresolvedSamples.add("0x$hexId in ${resRef.resourceUrl}")
      }
    }
    var declaredPackageIds: Set<Int> = emptySet()
    forEveryResource(apkPath, onPackages = { declaredPackageIds = it }) { stringPool, resType, folderConfig, _, typeChunkEntry ->
      val name = typeChunkEntry.key()
      val resRef = ResourceReference(ResourceNamespace.RES_AUTO, resType, name)
      val resValue =
        typeChunkEntry.createResValue(resRef, apkPath, stringPool, declaredPackageIds, onUnresolved) { resId ->
          resourceIdResolver.invoke(resId)
        }

      val resourceItem = ApkResourceItem(resRef, folderConfig, resValue)

      resourceMap.computeIfAbsent(resType) { ArrayListMultimap.create() }.put(name, resourceItem)
    }
    if (unresolvedCount > 0) {
      val suffix = if (unresolvedCount > unresolvedSamples.size) ", ..." else ""
      Logger.getInstance(ApkResourceRepository::class.java)
        .warn(
          "Could not resolve $unresolvedCount resource reference(s) while loading $apkPath " +
            "(${unresolvedSamples.joinToString(", ")}$suffix); skipped or substituted fallback values."
        )
    }
  }

  override fun accept(visitor: ResourceVisitor): ResourceVisitor.VisitResult {
    if (visitor.shouldVisitNamespace(ResourceNamespace.RES_AUTO)) {
      return acceptByResources(resourceMap, visitor)
    }
    return ResourceVisitor.VisitResult.CONTINUE
  }

  override fun getPublicResources(namespace: ResourceNamespace, type: ResourceType): MutableCollection<ResourceItem> {
    throw UnsupportedOperationException()
  }

  override fun getNamespaces(): MutableSet<ResourceNamespace> {
    return if (resourceMap.isEmpty()) mutableSetOf() else mutableSetOf(ResourceNamespace.RES_AUTO)
  }

  override fun getLeafResourceRepositories(): MutableCollection<SingleNamespaceResourceRepository> = mutableListOf()

  override val modificationCount: Long = 0

  override fun getResourcesInternal(namespace: ResourceNamespace, resourceType: ResourceType): ListMultimap<String, ResourceItem> {
    return if (namespace == ResourceNamespace.RES_AUTO) {
      resourceMap[resourceType] ?: ArrayListMultimap.create()
    } else {
      ArrayListMultimap.create()
    }
  }
}

// Special resource types defined in frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
private const val ATTR_TYPE = 0x01000000
private const val ATTR_MIN = 0x01000001
private const val ATTR_MAX = 0x01000002
private val SERVICE_VALS = setOf(ATTR_TYPE, ATTR_MIN, ATTR_MAX)

// Plural support constants defined in frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
private const val ATTR_OTHER = 0x01000004
private const val ATTR_ZERO = 0x01000005
private const val ATTR_ONE = 0x01000006
private const val ATTR_TWO = 0x01000007
private const val ATTR_FEW = 0x01000008
private const val ATTR_MANY = 0x01000009
private val PLURALS_NAMES =
  mapOf(ATTR_OTHER to "other", ATTR_ZERO to "zero", ATTR_ONE to "one", ATTR_TWO to "two", ATTR_FEW to "few", ATTR_MANY to "many")

private const val MAX_UNRESOLVED_SAMPLES = 10

/**
 * Converts a binary resource table entry into a [ResourceValue].
 *
 * The shape of the binary entry, not the resource type, determines the kind of [ResourceValue] that is created. A resource table entry is
 * either a simple entry holding a single value, or a complex entry (a bag, flagged with FLAG_COMPLEX) holding a map of values. Resources of
 * the bag types ([ResourceType.ATTR], [ResourceType.STYLE], [ResourceType.ARRAY] and [ResourceType.PLURALS]) are normally complex entries,
 * but AAPT2 emits simple entries for them when they are declared with the generic `<item>` tag:
 * * `<item type="style" name="Empty"/>` is encoded as a reference to `@null` (TYPE_REFERENCE with data 0).
 * * `<item type="style" name="Alias">@style/Base</item>` is a resource alias, encoded as a reference to the aliased resource.
 *
 * Simple entries are represented as a plain [ResourceValueImpl] whose value is the (reference) value of the entry, regardless of the
 * resource type. This matches the runtime behavior of the framework (AssetManager2 treats such entries as values, not bags, and follows
 * alias references during reference resolution). See [createSimpleResValue] for the exceptions to this rule.
 *
 * References to package IDs not in [declaredPackageIds] can remain unresolvable by [resLookUp] even with an up-to-date Layoutlib:
 * * Private framework IDs (`@*android:...`, `0x01xxxxxx`) that shift across platform builds (not pinned in `public.xml`) or are stripped
 *   from `com.android.internal.R` by AAPT.
 * * Shared or OEM library packages (`0x02..0x7e`) referenced by the APK but not packaged inside its `resources.arsc`.
 * * Newer `compileSdk` public framework IDs when running against an older bundled Layoutlib.
 *
 * Note that shifted private framework IDs colliding with an existing ID in `layoutlib.jar` will still resolve to the wrong resource rather
 * than hitting this fallback.
 *
 * When such an external ID cannot be resolved, [onUnresolved] is invoked and the entry degrades without aborting loading of the repository:
 * unresolved style parents fall back to `""` (avoiding dot-in-name parent inference), style items with an unresolved attribute key or value
 * are skipped (preserving parent style/theme inheritance), array and plural items degrade to `@null` (preserving array index alignment),
 * and simple entries degrade per [createSimpleResValue]. Unresolved IDs whose package ID is in [declaredPackageIds] still throw so a
 * misconfigured [resLookUp] (for example, omitting `loadApkResources`) fails fast.
 */
internal fun TypeChunk.Entry.createResValue(
  resRef: ResourceReference,
  apkPath: String,
  stringPool: StringPoolChunk,
  declaredPackageIds: Set<Int> = emptySet(),
  onUnresolved: (ResourceReference, Int) -> Unit = { _, _ -> },
  resLookUp: (Int) -> ResourceReference?,
): ResourceValue {
  if (!this.isComplex) {
    val binResVal = this.value() ?: throw IllegalArgumentException("Unexpected null value for ${resRef.resourceType}")
    return createSimpleResValue(resRef, binResVal, apkPath, stringPool, declaredPackageIds, onUnresolved, resLookUp)
  }
  return when (resRef.resourceType) {
    // Following logic in frameworks/base/tools/aapt2/format/binary/ResEntryWriter.cpp
    // MapFlattenVisitor.Visit(Attribute)
    ResourceType.ATTR -> {
      val attrValue = AttrResourceValueImpl(resRef, null)
      this.values()
        .filter { it.key !in SERVICE_VALS }
        .forEach { (resId, binVal) -> attrValue.addValue(resLookUp(resId)?.name ?: resId.toString(), binVal.data(), null) }
      attrValue
    }
    ResourceType.STYLE -> {
      val parentId = this.parentEntry()
      val parentStyle =
        if (parentId != 0) {
          val resolvedParent = resLookUp(parentId)?.qualifiedName
          if (resolvedParent != null) {
            resolvedParent
          } else if (isExternalResourceId(parentId, declaredPackageIds)) {
            onUnresolved(resRef, parentId)
            ""
          } else {
            throw UnresolvedResourceIdException(parentId)
          }
        } else {
          ""
        }
      val styleValue = StyleResourceValueImpl(resRef, parentStyle, null)
      this.values().forEach { (i, v) ->
        val attrRef = resLookUp(i)
        if (attrRef == null) {
          if (isExternalResourceId(i, declaredPackageIds)) {
            onUnresolved(resRef, i)
            return@forEach
          }
          throw UnresolvedResourceIdException(i)
        }
        val itemVal =
          try {
            formatVal(v, stringPool, resLookUp)
          } catch (e: UnresolvedResourceIdException) {
            if (isExternalResourceId(e.resId, declaredPackageIds)) {
              onUnresolved(resRef, e.resId)
              return@forEach
            }
            throw e
          }
        styleValue.addItem(StyleItemResourceValueImpl(resRef.namespace, attrRef.qualifiedName, itemVal, null))
      }
      styleValue
    }
    ResourceType.PLURALS -> {
      val pluralsValue = PluralsResourceValueImpl(resRef, null, null)
      this.values().forEach { (i, v) ->
        val itemQuantity = PLURALS_NAMES[i] ?: throw IllegalArgumentException("Unknown quantity $i for plural")
        val itemVal = formatValOrFallback(resRef, v, stringPool, declaredPackageIds, onUnresolved, resLookUp)
        pluralsValue.addPlural(itemQuantity, itemVal)
      }
      pluralsValue
    }
    ResourceType.ARRAY -> {
      val arrayValue = ArrayResourceValueImpl(resRef, null)
      this.values().forEach { (_, v) ->
        val itemVal = formatValOrFallback(resRef, v, stringPool, declaredPackageIds, onUnresolved, resLookUp)
        arrayValue.addElement(itemVal)
      }
      arrayValue
    }
    else -> throw IllegalArgumentException("Unexpected complex value for ${resRef.resourceType}")
  }
}

/**
 * Converts a simple (non-complex) binary resource table entry into a [ResourceValue], with the following exceptions to representing it as a
 * plain [ResourceValueImpl]:
 * * An empty [ResourceType.STYLE] (`<item type="style" name="Empty"/>`, i.e. `@null` or `@empty`) is represented as a
 *   [StyleResourceValueImpl] without parent and items. On a device, both such an entry and an empty `<style>` contribute nothing when used
 *   as a style or a theme, while callers resolving style resource ids (e.g. layoutlib's `Resources.Theme.applyStyle`) expect a
 *   [com.android.ide.common.rendering.api.StyleResourceValue] and would fail on a plain `@null` value.
 * * An unresolved reference to a package ID not in [declaredPackageIds] falls back without failing the loading of the whole repository:
 *   [ResourceType.ATTR] degrades to an empty [AttrResourceValueImpl], [ResourceType.STYLE] degrades to an empty [StyleResourceValueImpl],
 *   and all other types degrade to `@null`. Unresolved references within [declaredPackageIds] still throw.
 */
private fun createSimpleResValue(
  resRef: ResourceReference,
  binResVal: BinaryResourceValue,
  apkPath: String,
  stringPool: StringPoolChunk,
  declaredPackageIds: Set<Int>,
  onUnresolved: (ResourceReference, Int) -> Unit,
  resLookUp: (Int) -> ResourceReference?,
): ResourceValue {
  if (resRef.resourceType == ResourceType.STYLE && binResVal.isNullOrEmpty()) {
    return StyleResourceValueImpl(resRef, "", null)
  }
  val value =
    try {
      formatVal(binResVal, stringPool, resLookUp)
    } catch (e: UnresolvedResourceIdException) {
      if (!isExternalResourceId(e.resId, declaredPackageIds)) throw e
      onUnresolved(resRef, e.resId)
      return when (resRef.resourceType) {
        ResourceType.ATTR -> AttrResourceValueImpl(resRef, null)
        ResourceType.STYLE -> StyleResourceValueImpl(resRef, "", null)
        else -> ResourceValueImpl(resRef, "@null")
      }
    }
  return ResourceValueImpl(resRef, convertToApkRefIfNeeded(value, resRef.resourceType, apkPath))
}

/** Whether this value is `@null` or `@empty`, see `Res_value::TYPE_NULL` and `Res_value::TYPE_REFERENCE` in ResourceTypes.h. */
private fun BinaryResourceValue.isNullOrEmpty(): Boolean =
  when (type()) {
    BinaryResourceValue.Type.NULL -> true
    BinaryResourceValue.Type.REFERENCE,
    BinaryResourceValue.Type.DYNAMIC_REFERENCE -> data() == 0
    else -> false
  }

private fun isExternalResourceId(resId: Int, declaredPackageIds: Set<Int>): Boolean = (resId ushr 24) !in declaredPackageIds

private class UnresolvedResourceIdException(val resId: Int) : IllegalArgumentException("Could not resolve resource id $resId")

private fun formatValOrFallback(
  resRef: ResourceReference,
  binResVal: BinaryResourceValue,
  stringPool: StringPoolChunk,
  declaredPackageIds: Set<Int>,
  onUnresolved: (ResourceReference, Int) -> Unit,
  resLookUp: (Int) -> ResourceReference?,
): String =
  try {
    formatVal(binResVal, stringPool, resLookUp)
  } catch (e: UnresolvedResourceIdException) {
    if (!isExternalResourceId(e.resId, declaredPackageIds)) throw e
    onUnresolved(resRef, e.resId)
    "@null"
  }

private fun formatVal(binResVal: BinaryResourceValue, stringPool: StringPoolChunk, resLookUp: (Int) -> ResourceReference?): String {
  return BinaryXmlParser.formatValue(binResVal, stringPool) { resId ->
    resLookUp(resId)?.resourceUrl?.toString() ?: throw UnresolvedResourceIdException(resId)
  }
}

/**
 * Some resources have references to resource files as their values. This makes these references fully resolvable by prepending the
 * containing apk path and specifying apk protocol.
 */
internal fun convertToApkRefIfNeeded(resValue: String, resType: ResourceType, apkPath: String): String =
  if (isResourceFileReference(resValue, resType)) "apk://$apkPath!/$resValue" else resValue
