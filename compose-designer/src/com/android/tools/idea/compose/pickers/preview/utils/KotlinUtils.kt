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
package com.android.tools.idea.compose.pickers.preview.utils

import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.base.psi.appendValueArgument
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Adds [newValueArgument] to this [KtCallElement]'s argument list, or creates a new [KtValueArgumentList] containing [newValueArgument] if
 * [valueArgumentList] is currently `null` (e.g., `@Preview` without parentheses).
 */
internal fun KtCallElement.addNewValueArgument(newValueArgument: KtValueArgument, psiFactory: KtPsiFactory): KtValueArgument {
  // Reuse the existing argument list when parentheses are already present (e.g. `@Preview()` or `@Preview(name = "...")`). Note that
  // appendValueArgument is used here because it correctly handles comma insertion and formatting when adding to an existing list, whether
  // it's empty or already contains arguments.
  valueArgumentList?.let { existingArgumentList ->
    return existingArgumentList.appendValueArgument(newValueArgument)
  }
  // When no parentheses exist (e.g. `@Preview`), create and attach `(argument)`. Using string interpolation here is safe because
  // newValueArgument.text provides the valid Kotlin source for the argument. This also ensures that we only perform one PSI write to add
  // the entire argument list at once.
  val newArgumentList = add(psiFactory.createCallArguments("(${newValueArgument.text})")) as KtValueArgumentList
  return newArgumentList.arguments.first()
}

internal fun getArgumentForParameter(functionCall: KaFunctionCall<*>, parameterSymbol: KaValueParameterSymbol) =
  functionCall.valueArgumentMapping.entries.singleOrNull { (_, parameter) -> parameter.symbol == parameterSymbol }?.key
