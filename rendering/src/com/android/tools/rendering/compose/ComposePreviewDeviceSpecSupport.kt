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
package com.android.tools.rendering.compose

import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.google.common.cache.CacheBuilder
import com.google.common.util.concurrent.UncheckedExecutionException
import com.intellij.openapi.diagnostic.ControlFlowException
import java.util.concurrent.CancellationException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

const val PREVIEW_DEVICE_SPEC_CLASS_FQCN = "androidx.compose.ui.tooling.PreviewDeviceSpec"

/**
 * Class file resource of `androidx.compose.ui.tooling.PreviewDeviceSpec` in `androidx.compose.ui:ui-tooling`, which declares the `spec:`
 * device string parameters supported by that version of `ui-tooling`.
 */
private const val PREVIEW_DEVICE_SPEC_CLASS_RESOURCE = "androidx/compose/ui/tooling/PreviewDeviceSpec.class"

/**
 * Name of the `const val` field on `androidx.compose.ui.tooling.PreviewDeviceSpec` holding the comma-separated list of supported `spec:`
 * parameter names.
 */
const val SUPPORTED_PARAMETERS_FIELD_NAME = "SUPPORTED_PARAMETERS"

private const val DEVICE_SPEC_PREFIX = "spec:"
private const val DEVICE_SPEC_SEPARATOR = ','
private const val DEVICE_SPEC_OPERATOR = '='
private const val PARAMETER_EXPAND = "expand"

/** Returns whether this set of supported `PreviewDeviceSpec` parameters includes `expand`. */
fun Set<String>.supportsExpand(): Boolean = any { it.equals(PARAMETER_EXPAND, ignoreCase = true) }

/**
 * `ui-tooling` `spec:` parameters that shipped in `androidx.compose.ui:ui-tooling` (1.11.0-alpha06) before `PreviewDeviceSpec` existed.
 * When `PreviewDeviceSpec` is absent (`supportedParameters` is empty), we cannot tell whether the project's `ui-tooling` supports them, so
 * they are only reported as unsupported when `PreviewDeviceSpec` is present (`supportedParameters` is non-empty) and omits them.
 */
private val PRE_PREVIEW_DEVICE_SPEC_PARAMETERS =
  setOf(
    "windowPosture",
    "pointerPrecision",
    "keyboardKind",
    "hasCamera",
    "hasMicrophone",
    "viewingDistance",
  )

/**
 * `spec:` device string parameters that are interpreted by `ComposeViewAdapter` in `androidx.compose.ui:ui-tooling` (as opposed to device
 * hardware parameters such as `parent`, `width`, `height`, `dpi`, `isRound`, `chinSize`, `orientation`, `cutout`, and `navigation` which
 * are applied by the host rendering tools).
 */
private val UI_TOOLING_DEVICE_SPEC_PARAMETERS =
  listOf(
    "windowPosture",
    "pointerPrecision",
    "keyboardKind",
    "hasCamera",
    "hasMicrophone",
    "viewingDistance",
    PARAMETER_EXPAND,
    "maxHeight",
    "maxWidth",
  )

private val UI_TOOLING_PARAMETER_BY_LOWERCASE_NAME = UI_TOOLING_DEVICE_SPEC_PARAMETERS.associateBy { it.lowercase() }

/**
 * Per-[ClassLoader] weak cache so repeated queries on the same [ClassLoader] (including negative lookups when `PreviewDeviceSpec.class` is
 * absent) only read `PreviewDeviceSpec.class` once, while allowing disposed class loaders to be garbage collected.
 */
private val supportedParametersByClassLoader = CacheBuilder.newBuilder().weakKeys().build<ClassLoader, Set<String>>()

/**
 * Returns the set of `spec:` device string parameter names declared as supported by the `androidx.compose.ui:ui-tooling` library in this
 * [ClassLoader], or an empty set if `PreviewDeviceSpec` is not on the classpath (e.g. with an older `ui-tooling` version) or cannot be
 * read.
 *
 * The `SUPPORTED_PARAMETERS` `String` constant is read directly from the `PreviewDeviceSpec.class` bytecode constant pool without loading
 * the class, and cached per [ClassLoader].
 */
fun ClassLoader.supportedComposePreviewDeviceSpecParameters(): Set<String> =
  try {
    supportedParametersByClassLoader.get(this) {
      // Reading `PreviewDeviceSpec.class` via `getResource` / `getResourceAsStream` is cheap (< 0.1 ms): in Android Studio and the
      // standalone renderer, external library JARs are backed by a shared `UrlClassLoader` with an in-memory package-hash index and cached
      // `MissingClasses` set, and `PreviewDeviceSpec.class` itself is a ~600-byte class containing only a single `const val` String field.
      // `useCaches = false` avoids pinning the JAR in the JVM's global `JarURLConnection` cache when the library file is updated on disk.
      // When `ui-tooling` is a module dependency rather than an external JAR (where `StudioModuleClassLoader.getResource` returns `null`),
      // fall back to `DelegatingClassLoader.loadClassBytes`, which reads `.class` bytes through `ClassFileFinder` without loading the
      // class.
      val bytes =
        runCatching {
          val stream =
            getResource(PREVIEW_DEVICE_SPEC_CLASS_RESOURCE)?.openConnection()?.apply { useCaches = false }?.getInputStream()
              ?: getResourceAsStream(PREVIEW_DEVICE_SPEC_CLASS_RESOURCE)
          stream?.use { it.readBytes() } ?: (this as? DelegatingClassLoader)?.loadClassBytes(PREVIEW_DEVICE_SPEC_CLASS_FQCN)
        }
          .onFailure {
            if (it is CancellationException || it is ControlFlowException) throw it
          }
          .getOrNull() ?: return@get emptySet()
      readSupportedParametersFromClassBytes(bytes)
    }
  } catch (e: UncheckedExecutionException) {
    throw e.cause ?: e
  }

/**
 * Returns a warning for the `ui-tooling` `spec:` parameters used in [deviceSpec], and `expand` if [hasExpandSettings], that are not in
 * [supportedParameters], or `null` if they are all supported.
 *
 * Parameters in [PRE_PREVIEW_DEVICE_SPEC_PARAMETERS] shipped before `PreviewDeviceSpec` existed and are only reported when
 * [supportedParameters] is non-empty. [hasExpandSettings] covers previews that are expanded without `expand` in [deviceSpec]. Each
 * parameter is only reported once. [supportedParameters] is only queried, at most once, if [hasExpandSettings] is true or [deviceSpec]
 * contains a `ui-tooling` parameter.
 */
fun unsupportedComposePreviewDeviceSpecWarning(
  deviceSpec: String?,
  hasExpandSettings: Boolean = false,
  supportedParameters: () -> Set<String>,
): String? {
  val usedParameters = parseUsedUiToolingParameters(deviceSpec)
  if (!hasExpandSettings && usedParameters.isEmpty()) return null
  val supportedLowercase = supportedParameters().mapTo(HashSet()) { it.lowercase() }
  val unsupportedParameters = buildSet {
    if (hasExpandSettings && PARAMETER_EXPAND !in supportedLowercase) add(PARAMETER_EXPAND)
    for (parameter in usedParameters) {
      if (supportedLowercase.isEmpty() && parameter in PRE_PREVIEW_DEVICE_SPEC_PARAMETERS) continue
      if (parameter.lowercase() !in supportedLowercase) add(parameter)
    }
  }
  if (unsupportedParameters.isEmpty()) return null
  val parametersList = unsupportedParameters.joinToString(", ")
  val verb = if (unsupportedParameters.size == 1) "requires" else "require"
  val suffix =
    if (PARAMETER_EXPAND in unsupportedParameters) {
      "; rendering at device size."
    } else {
      " and will be ignored."
    }
  return "$parametersList in the device spec $verb a newer version of androidx.compose.ui:ui-tooling$suffix"
}

private fun parseUsedUiToolingParameters(deviceSpec: String?): Set<String> {
  if (deviceSpec.isNullOrBlank()) return emptySet()
  val trimmed = deviceSpec.trim()
  if (!trimmed.startsWith(DEVICE_SPEC_PREFIX, ignoreCase = true)) return emptySet()
  return buildSet {
    for (entry in trimmed.substring(DEVICE_SPEC_PREFIX.length).split(DEVICE_SPEC_SEPARATOR)) {
      val key = entry.substringBefore(DEVICE_SPEC_OPERATOR, missingDelimiterValue = "").trim()
      val canonicalName = UI_TOOLING_PARAMETER_BY_LOWERCASE_NAME[key.lowercase()] ?: continue
      add(canonicalName)
    }
  }
}

/** Parses the comma-separated `PreviewDeviceSpec.SUPPORTED_PARAMETERS` value into a set of trimmed, non-empty parameter names. */
fun parseSupportedComposePreviewDeviceSpecParameters(rawParameters: String): Set<String> =
  rawParameters.split(DEVICE_SPEC_SEPARATOR).map(String::trim).filterTo(linkedSetOf(), String::isNotEmpty)

/**
 * Reads the comma-separated `PreviewDeviceSpec.SUPPORTED_PARAMETERS` constant directly from the `PreviewDeviceSpec.class` [bytes] without
 * loading the class.
 */
fun readSupportedParametersFromClassBytes(bytes: ByteArray): Set<String> {
  val rawParameters =
    runCatching {
      var fieldValue: String? = null
      ClassReader(bytes)
        .accept(
          object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(access: Int, name: String?, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
              if (name == SUPPORTED_PARAMETERS_FIELD_NAME && value is String) {
                fieldValue = value
              }
              return null
            }
          },
          ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
      fieldValue
    }
      .getOrNull() ?: return emptySet()
  return parseSupportedComposePreviewDeviceSpecParameters(rawParameters)
}
