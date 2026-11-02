/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.debug

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.PositionManagerAsync
import com.intellij.openapi.application.readAction
import com.intellij.psi.util.parentOfType
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import com.sun.jdi.Method
import java.util.LinkedList
import kexter.Dex
import kexter.DexBytecode
import kexter.DexMethod
import kexter.DexMethodDebugInfo
import kexter.InvokeInstruction
import kexter.LineTableEntry
import kexter.Opcode
import org.jetbrains.kotlin.idea.debugger.base.util.safeGetSourcePositionAsync
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.core.DexBytecodeInspector
import org.jetbrains.kotlin.idea.debugger.core.getInlineFunctionAndArgumentVariablesToBordersMap
import org.jetbrains.kotlin.idea.debugger.core.isInlineFunctionMarkerVariableName
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinMethodSmartStepTarget
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinSmartStepTargetFilterer
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.SmartStepIntoContext
import org.jetbrains.kotlin.psi.KtNamedFunction

class DexBytecodeInspectorImpl : DexBytecodeInspector {
  /**
   * Checks if the method is a simple delegate to a static method by performing a bytecode instruction test.
   *
   * A simple delegate to a static method can be defined by these sets of opcodes:
   * ```
   * invoke-static
   * return
   * ```
   *
   * or
   *
   * ```
   * invoke-static
   * move-result
   * return
   * ```
   *
   * # Context
   * In Kotlin when providing a default implementation of a method in an interface:
   * ```
   * interface I {
   *     fun foo() {
   *     }
   * }
   * ```
   *
   * Two classes will be generated:
   * - `I.class`
   * - `I$DefaultImpls.class`
   *
   * The `I$DefaultImpls.class` will contain a static `foo` method with the default implementation.
   *
   * When implementing the `I` interface:
   * ```
   * class X : I
   * ```
   *
   * The `foo` method in `X` will simply call the `foo` method of `I$DefaultImpls`:
   * ```java
   *  public void foo();
   *    0: aload_0
   *    1: invokestatic  #18 // Method I$DefaultImpls.foo:(LI;)V
   *    4: return
   * ```
   */
  override fun hasOnlyInvokeStatic(method: Method): Boolean {
    val instructions = DexBytecode.fromBytes(method.bytecodes()).instructions
    if (instructions.isEmpty() || instructions.size > 3) {
      return false
    }

    return instructions.first().opcode.isInvokeStatic() &&
      instructions.last().opcode.isReturn() &&
      (instructions.size == 2 || instructions[1].opcode.isMoveResult())
  }

  override suspend fun filterAlreadyExecutedTargets(
    targets: List<KotlinMethodSmartStepTarget>,
    @Suppress("UnstableApiUsage") context: SmartStepIntoContext,
  ): List<KotlinMethodSmartStepTarget> {
    val (expression, debugProcess, _, lines, location) = context
    val method = location.safeMethod() ?: return targets

    val dex = DexFinder.findDex(debugProcess, expression, location) ?: return targets

    val filterer = KotlinSmartStepTargetFilterer(targets, debugProcess)
    filterer.visitMethodUntilLocation(debugProcess, method, lines, location, dex)
    return filterer.getUnvisitedTargets()
  }
}

private suspend fun KotlinSmartStepTargetFilterer.visitMethodUntilLocation(
  debugProcess: DebugProcessImpl,
  method: Method,
  lines: ClosedRange<Int>,
  location: Location,
  dex: Dex,
) {
  val debugInfo = method.getDebugInfo()
  if (debugInfo.lineTable.isEmpty()) {
    return
  }

  val methodBytecode = DexBytecode.fromBytes(method.bytecodes(), debugInfo)
  if (methodBytecode.instructions.isEmpty()) {
    return
  }

  val lineTableIterator = debugInfo.lineTable.iterator()
  var nextLine = lineTableIterator.next()
  var currentLineNumber: Int? = null
  var lineEverMatched = false
  var inInline = false
  val sortedInlineCalls = LinkedList(extractInlineCalls(location))
  for (insn in methodBytecode.instructions) {
    if (insn.index >= location.codeIndex().toUInt()) {
      break
    }

    if (insn.index >= nextLine.index) {
      currentLineNumber = nextLine.lineNumber
      if (lineTableIterator.hasNext()) {
        nextLine = lineTableIterator.next()
      }
    }

    if (currentLineNumber != null && (currentLineNumber - 1) in lines) {
      lineEverMatched = true
      if (insn is InvokeInstruction) {
        val methodInfo = dex.retrieveMethod(insn.methodIndex())
        if (methodInfo != null) {
          visitOrdinaryFunction(methodInfo.owner, methodInfo.name, methodInfo.signature, insn.opcode.name.startsWith("INVOKE_STATIC"))
        }
      }
    }

    if (sortedInlineCalls.isNotEmpty() && lineEverMatched) {
      while (sortedInlineCalls.isNotEmpty() && sortedInlineCalls.first().bciRange.last.toUInt() < insn.index) {
        sortedInlineCalls.pop()
      }
      val inlineCall = sortedInlineCalls.firstOrNull { insn.index.toLong() in it.bciRange }
      if (inlineCall == null) {
        inInline = false
        continue
      } else if (inInline) {
        continue
      }
      inInline = true
      if (inlineCall.isInlineFun) {
        val call = getCalledInlineFunction(debugProcess.positionManager, inlineCall.startLocation)
        if (call != null) {
          visitInlineFunction(call)
        }
      } else {
        visitInlineInvokeCall()
      }
    }
  }
}

private val DexMethod.owner: String
  // Drop first 'L' and last ';'
  get() = type.drop(1).dropLast(1)

private val DexMethod.signature: String
  get() = params.joinToString(separator = "", prefix = "(", postfix = ")") + returnType

private fun Opcode.isInvokeStatic(): Boolean {
  return this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE
}

private fun Opcode.isMoveResult(): Boolean {
  return this == Opcode.MOVE_RESULT || this == Opcode.MOVE_RESULT_WIDE || this == Opcode.MOVE_RESULT_OBJECT
}

private fun Opcode.isReturn(): Boolean {
  return this == Opcode.RETURN_VOID || this == Opcode.RETURN_OBJECT || this == Opcode.RETURN_WIDE || this == Opcode.RETURN
}

private fun Method.getDebugInfo(): DexMethodDebugInfo {
  val lineTable = allLineLocations().map { LineTableEntry(it.codeIndex().toUInt(), it.lineNumber()) }
  return DexMethodDebugInfo(lineTable)
}

// Copied from
// src/org/jetbrains/kotlin/idea/debugger/stepping/smartStepInto/KotlinSmartStepTargetFiltererAdapter.kt
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
internal data class InlineCallInfo(val isInlineFun: Boolean, val bciRange: LongRange, val startLocation: Location)

private fun extractInlineCalls(location: Location): List<InlineCallInfo> {
  val inlineVariables =
    location.safeMethod()?.getInlineFunctionAndArgumentVariablesToBordersMap()?.map { (k, v) -> ScopedVariable(k, v) }.orEmpty()

  val mergedCalls = mergeCalls(inlineVariables)

  return mergedCalls
    // Filter already visible variables to support smart-step-into while inside an inline function
    .filterNot { location.codeIndex() in it.bciRange }
    .sortedBy { it.bciRange.first }
}

/*
On Dex/ART, D8 register allocation can split a single inline function's marker
variable (e.g. $i$f$foo) into multiple contiguous LocalVariable records.
Merge contiguous or overlapping ranges of the same variable so that enclosing
inline functions are properly filtered out and inline calls are not split.
*/
private fun mergeCalls(inlineVariables: List<ScopedVariable>): List<InlineCallInfo> {
  return inlineVariables
    .groupBy { it.variable.name() }
    .values
    .flatMap { varsWithSameName ->
      val sorted = varsWithSameName.sortedBy { it.scope.start.codeIndex() }
      val merged = mutableListOf<InlineCallInfo>()
      sorted.forEach { (variable, scope) ->
        val last = merged.lastOrNull()
        val start = scope.start.codeIndex()
        val end = scope.endInclusive.codeIndex()
        if (last != null && start <= last.bciRange.last + 1) {
          merged[merged.lastIndex] = last.copy(bciRange = last.bciRange.first..maxOf(last.bciRange.last, end))
        } else {
          merged.add(
            InlineCallInfo(
              isInlineFun = variable.name().isInlineFunctionMarkerVariableName,
              bciRange = start..end,
              startLocation = scope.start,
            )
          )
        }
      }
      merged
    }
}

@Suppress("UnstableApiUsage")
private suspend fun getCalledInlineFunction(positionManager: PositionManagerAsync, location: Location): KtNamedFunction? {
  val sourcePosition = positionManager.safeGetSourcePositionAsync(location) ?: return null
  return readAction { sourcePosition.elementAt?.parentOfType<KtNamedFunction>() }
}

private data class ScopedVariable(val variable: LocalVariable, val scope: ClosedRange<Location>)
