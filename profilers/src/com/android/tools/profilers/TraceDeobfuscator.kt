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
package com.android.tools.profilers

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Makes the names in a recorded Perfetto trace readable before it is opened. It adds the R8 mapping of the app that Android Studio deployed
 * and, if asked, native symbols, through [IdeProfilerServices.symbolizeAndDeobfuscateTrace].
 *
 * It is not tied to the memory tasks, so any task that records a Perfetto trace of an app can use it; it only has to say whether its trace
 * has native frames.
 */
object TraceDeobfuscator {
  private val logger = Logger.getInstance(TraceDeobfuscator::class.java)

  /**
   * Returns a copy of [traceFile] with the R8 mapping and, if asked, native symbols added, or [traceFile] itself when there is nothing to
   * add or traceconv fails. Blocks while traceconv runs, so call it off the EDT.
   *
   * @param appName the recorded process. The part before ':' is the package whose R8 mapping is used.
   * @param deviceSerial the device the app ran on. Empty for an imported trace, which gets no R8 mapping.
   * @param withNativeSymbols whether to also add native symbols, for traces with native frames.
   */
  @JvmStatic
  fun deobfuscate(services: IdeProfilerServices, traceFile: File, appName: String, deviceSerial: String, withNativeSymbols: Boolean): File {
    if (traceFile.length() == 0L) {
      return traceFile
    }
    val symbolDirs = if (withNativeSymbols) services.nativeSymbolsDirectories else emptyList()
    val proguardMaps = resolveProguardMap(services, appName, deviceSerial)
    if (symbolDirs.isEmpty() && proguardMaps.isEmpty()) {
      return traceFile
    }
    return services.symbolizeAndDeobfuscateTrace(traceFile, symbolDirs, proguardMaps) ?: traceFile
  }

  /**
   * Returns the R8 mapping of [appName]'s package, keyed by the package name, or an empty map. Custom (global) process names, and apps not
   * deployed by Android Studio with minification enabled, have no mapping.
   */
  private fun resolveProguardMap(services: IdeProfilerServices, appName: String, deviceSerial: String): Map<String, String> {
    val packageName = appName.substringBefore(':')
    if (packageName.isEmpty() || deviceSerial.isEmpty()) {
      return emptyMap()
    }
    val mappingPath = services.getProguardMappingForApp(deviceSerial, packageName)
    if (mappingPath == null) {
      logger.info("Skipping R8 deobfuscation for $packageName on device '$deviceSerial': no Studio-deployed obfuscated mapping found.")
      return emptyMap()
    }
    return mapOf(packageName to mappingPath)
  }
}
