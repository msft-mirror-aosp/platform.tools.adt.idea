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

  private val resourceMap =
    EnumMap<ResourceType, MutableMap<ResourceNamespace, ListMultimap<String, ResourceItem>>>(ResourceType::class.java)

  init {
    forEveryResource(apkPath) { stringPool, resType, folderConfig, _, typeChunkEntry ->
      val namespacedName = typeChunkEntry.key()
      val (namespace, name) = extractNameAndNamespace(namespacedName)
      val resRef = ResourceReference(namespace, resType, name)
      val resValue = typeChunkEntry.createResValue(resRef, apkPath, stringPool) { resId -> resourceIdResolver.invoke(resId) }

      val resourceItem = ApkResourceItem(resRef, folderConfig, resValue)

      resourceMap
        .computeIfAbsent(resType) { mutableMapOf() }
        .computeIfAbsent(namespace) { ArrayListMultimap.create() }
        .put(name, resourceItem)
    }
  }

  override fun accept(visitor: ResourceVisitor): ResourceVisitor.VisitResult {
    for ((_, u) in resourceMap) {
      for (resTypeCollection in u.values) {
        for (item in resTypeCollection.values()) {
          if (visitor.visit(item) == ResourceVisitor.VisitResult.ABORT) {
            return ResourceVisitor.VisitResult.ABORT
          }
        }
      }
    }
    return ResourceVisitor.VisitResult.CONTINUE
  }

  override fun getPublicResources(namespace: ResourceNamespace, type: ResourceType): MutableCollection<ResourceItem> {
    throw UnsupportedOperationException()
  }

  override fun getNamespaces(): MutableSet<ResourceNamespace> {
    return resourceMap.flatMap { it.value.keys }.toMutableSet()
  }

  override fun getLeafResourceRepositories(): MutableCollection<SingleNamespaceResourceRepository> = mutableListOf()

  override val modificationCount: Long = 0

  override fun getResourcesInternal(namespace: ResourceNamespace, resourceType: ResourceType): ListMultimap<String, ResourceItem> {
    return resourceMap[resourceType]?.get(namespace) ?: ArrayListMultimap.create()
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
 * resource type. This matches both the runtime behavior of the framework (AssetManager2 treats such entries as values, not bags, and
 * follows alias references during reference resolution) and how [com.android.resources.aar.AarProtoResourceRepository] represents the same
 * entries when loading compiled resources. See [createSimpleResValue] for the exceptions to this rule.
 */
internal fun TypeChunk.Entry.createResValue(
  resRef: ResourceReference,
  apkPath: String,
  stringPool: StringPoolChunk,
  resLookUp: (Int) -> ResourceReference?,
): ResourceValue {
  if (!this.isComplex) {
    val binResVal = this.value() ?: throw IllegalArgumentException("Unexpected null value for ${resRef.resourceType}")
    return createSimpleResValue(resRef, binResVal, apkPath, stringPool, resLookUp)
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
      val parentStyle =
        if (this.parentEntry() != 0) {
          resLookUp(this.parentEntry())?.getQualifiedName()
        } else {
          ""
        }
      val styleValue = StyleResourceValueImpl(resRef, parentStyle, null)
      this.values().forEach { (i, v) ->
        val itemName = resLookUp(i)?.qualifiedName ?: "$i"
        val itemVal = formatVal(v, stringPool, resLookUp)
        styleValue.addItem(StyleItemResourceValueImpl(resRef.namespace, itemName, itemVal, null))
      }
      styleValue
    }
    ResourceType.PLURALS -> {
      val pluralsValue = PluralsResourceValueImpl(resRef, null, null)
      this.values().forEach { (i, v) ->
        val itemQuantity = PLURALS_NAMES[i] ?: throw IllegalArgumentException("Unknown quantity $i for plural")
        val itemVal = formatVal(v, stringPool, resLookUp)
        pluralsValue.addPlural(itemQuantity, itemVal)
      }
      pluralsValue
    }
    ResourceType.ARRAY -> {
      val arrayValue = ArrayResourceValueImpl(resRef, null)
      this.values().forEach { (i, v) ->
        val itemVal = formatVal(v, stringPool, resLookUp)
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
 * * An [ResourceType.ATTR] alias to a resource that can not be resolved (e.g. a framework attribute unknown to [resLookUp], or a resource
 *   of another package) is represented as an empty [AttrResourceValueImpl] instead of failing the loading of the whole repository.
 */
private fun createSimpleResValue(
  resRef: ResourceReference,
  binResVal: BinaryResourceValue,
  apkPath: String,
  stringPool: StringPoolChunk,
  resLookUp: (Int) -> ResourceReference?,
): ResourceValue {
  if (resRef.resourceType == ResourceType.STYLE && binResVal.isNullOrEmpty()) {
    return StyleResourceValueImpl(resRef, "", null)
  }
  val value =
    try {
      formatVal(binResVal, stringPool, resLookUp)
    } catch (e: UnresolvedResourceIdException) {
      if (resRef.resourceType == ResourceType.ATTR) return AttrResourceValueImpl(resRef, null)
      throw e
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

internal fun extractNameAndNamespace(namespacedName: String): Pair<ResourceNamespace, String> {
  // In the namespaced case the namespace and the name are separated by $ symbol
  val separatorIdx = namespacedName.indexOf('$')
  // If it is the first character, there is still no namespace
  return if (separatorIdx > 0) {
    ResourceNamespace.fromPackageName(namespacedName.substring(0, separatorIdx)) to namespacedName.substring(separatorIdx + 1)
  } else {
    ResourceNamespace.RES_AUTO to namespacedName
  }
}

private class UnresolvedResourceIdException(resId: Int) : IllegalArgumentException("Could not resolve resource id $resId")

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
