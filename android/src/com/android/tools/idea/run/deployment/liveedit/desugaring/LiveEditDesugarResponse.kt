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
package com.android.tools.idea.run.deployment.liveedit.desugaring

import com.android.tools.idea.run.deployment.liveedit.LiveEditCompilerOutput
import com.android.tools.idea.run.deployment.liveedit.LiveEditUpdateException.Companion.desugarFailure

class LiveEditDesugarResponse(val compilerOutput: LiveEditCompilerOutput) {
  private val apiToClasses: MutableMap<MinApiLevel, Map<ClassName, ByteCode>> = mutableMapOf()
  val classes: Map<MinApiLevel, Map<ClassName, ByteCode>>
    get() = apiToClasses

  internal fun addOutputSet(minApiLevel: MinApiLevel, classes: Map<ClassName, ByteCode>) {
    apiToClasses[minApiLevel] = classes
  }

  val invalidateMode = compilerOutput.invalidateMode
  val groupIds = compilerOutput.groupIds
  val hasNonComposeChanges = compilerOutput.hasNonComposeChanges

  private fun getClasses(classNames: Set<String>, apiLevel: MinApiLevel): MutableMap<String, ByteArray> {
    val desugaredClasses = apiToClasses[apiLevel] ?: throw desugarFailure("No desugared classes for api=$apiLevel")

    val classes = mutableMapOf<String, ByteArray>()
    for (className in classNames) {
      val classData = desugaredClasses[className] ?: throw desugarFailure("Desugared classes api $apiLevel does not contain $className")
      classes[className] = classData
    }
    return classes
  }

  /** Synthetics D8 generated but the compiler never emitted, such as backports for APIs newer than the app min API. */
  private fun desugaringGeneratedClasses(apiLevel: MinApiLevel): Map<String, ByteArray> {
    val desugaredClasses = apiToClasses[apiLevel] ?: throw desugarFailure("No desugared classes for api=$apiLevel")
    val compiledClassNames = compilerOutput.classesMap.keys + compilerOutput.supportClassesMap.keys
    return desugaredClasses.filterKeys { it !in compiledClassNames }
  }

  fun classes(apiLevel: MinApiLevel): MutableMap<String, ByteArray> {
    return getClasses(compilerOutput.classesMap.keys, apiLevel)
  }

  /** Classes missing from the APK, sent as interpreted proxies. Desugaring's synthetics are missing too, so they ride along. */
  fun supportClasses(apiLevel: MinApiLevel): MutableMap<String, ByteArray> {
    val classes = getClasses(compilerOutput.supportClassesMap.keys, apiLevel)
    classes.putAll(desugaringGeneratedClasses(apiLevel))
    return classes
  }
}
