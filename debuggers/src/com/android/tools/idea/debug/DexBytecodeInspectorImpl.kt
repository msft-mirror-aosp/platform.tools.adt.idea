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

import com.intellij.debugger.BytecodeInspector
import com.intellij.debugger.actions.JvmSmartStepIntoErrorReporter
import com.intellij.debugger.actions.JvmSmartStepIntoHandler.SmartStepIntoDetectionStatus
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.PositionManagerAsync
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.statistics.DebuggerStatistics
import com.intellij.debugger.statistics.Engine
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.util.parentOfType
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.VirtualMachine
import java.util.LinkedList
import kexter.Dex
import kexter.DexBytecode
import kexter.DexMethod
import kexter.DexMethodDebugInfo
import kexter.Instruction
import kexter.InvokeInstruction
import kexter.LineTableEntry
import kexter.Opcode
import org.jetbrains.kotlin.idea.debugger.base.util.safeAllLineLocations
import org.jetbrains.kotlin.idea.debugger.base.util.safeGetSourcePositionAsync
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.core.DexBytecodeInspector
import org.jetbrains.kotlin.idea.debugger.core.getInlineFunctionAndArgumentVariablesToBordersMap
import org.jetbrains.kotlin.idea.debugger.core.isInlineFunctionMarkerVariableName
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinMethodSmartStepTarget
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinSmartStepTargetFilterer
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.SmartStepIntoContext
import org.jetbrains.kotlin.psi.KtNamedFunction

class DexBytecodeInspectorImpl : DexBytecodeInspector, BytecodeInspector {
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

  @Suppress("UnstableApiUsage")
  override suspend fun filterAlreadyExecutedTargets(
    targets: List<KotlinMethodSmartStepTarget>,
    context: SmartStepIntoContext,
  ): List<KotlinMethodSmartStepTarget> {
    val (expression, debugProcess, position, lines, location) = context
    val visitation =
      location.safeMethod()?.let { method ->
        DexFinder.findDex(debugProcess, expression, location)?.let { dex ->
          KotlinSmartStepTargetFilterer(targets, debugProcess).visitMethod(debugProcess, method, lines, location, dex)
        }
      }
    if (visitation == null) {
      DebuggerStatistics.logSmartStepIntoTargetsDetection(
        debugProcess.project,
        Engine.KOTLIN,
        SmartStepIntoDetectionStatus.BYTECODE_NOT_AVAILABLE,
      )
      return targets
    }
    val (eliminatedEqualsCalls, remainingEliminated) = visitation.eliminated.partition { it.methodInfo.isEqualsNullCall }
    if (remainingEliminated.isNotEmpty()) {
      if (Registry.`is`("debugger.kotlin.report.smart.step.into.targets.detection.failure")) {
        val targetStrings = remainingEliminated.map { "Target(name=${it.methodInfo.name}, info=${it.methodInfo})" }
        JvmSmartStepIntoErrorReporter.report(expression, debugProcess.session, position, "Failed to locate target calls: $targetStrings")
      }
      DebuggerStatistics.logSmartStepIntoTargetsDetection(
        debugProcess.project,
        Engine.KOTLIN,
        SmartStepIntoDetectionStatus.TARGETS_MISMATCH,
      )
      // We failed to filter out all smart step targets during the traversal of the whole method, so
      // we can't guarantee the correctness of filtering.
      return targets
    }
    DebuggerStatistics.logSmartStepIntoTargetsDetection(debugProcess.project, Engine.KOTLIN, SmartStepIntoDetectionStatus.SUCCESS)
    return when (eliminatedEqualsCalls.isNotEmpty()) {
      true -> visitation.unvisited - eliminatedEqualsCalls.toSet()
      false -> visitation.unvisited
    }
  }

  override fun isApplicable(virtualMachine: VirtualMachine) = DebuggerUtils.isAndroidVM(virtualMachine)

  override fun findInlineConditionalReturnLocations(method: Method, lineNumber: Int): List<Location> {
    val bytecode = DexBytecode.fromBytes(method.bytecodes(), method.getDebugInfo())
    val instructions = bytecode.instructionsForLineNumber(lineNumber)
    // // Implicit return instruction at the end of bytecode should not be treated as conditional return.
    val lastInstruction = bytecode.getLastInstructionOfLastLine()?.index ?: return emptyList()
    val returns = instructions.filter { it.opcode.isReturn() && it.index != lastInstruction }
    return returns.map { method.locationOfCodeIndex(it.index.toLong()) }
  }

  override fun isConditionalReturnLocation(location: Location): Boolean {
    val method = DebuggerUtilsEx.getMethod(location) ?: return false
    val bytecode = DexBytecode.fromBytes(method.bytecodes(), method.getDebugInfo())
    val instructions = bytecode.instructionsForLineNumber(location.lineNumber())
    val index = location.codeIndex().toUInt()
    val instruction = instructions.find { it.index == index } ?: return false

    // // Implicit return instruction at the end of bytecode should not be treated as conditional return.
    return instruction.opcode.isReturn() && instruction.index != bytecode.getLastInstructionOfLastLine()?.index
  }
}

private data class MethodVisitation(
  val unvisited: List<KotlinMethodSmartStepTarget>,
  val eliminated: List<KotlinMethodSmartStepTarget>,
)

private suspend fun KotlinSmartStepTargetFilterer.visitMethod(
  debugProcess: DebugProcessImpl,
  method: Method,
  lines: ClosedRange<Int>,
  location: Location,
  dex: Dex,
): MethodVisitation? {
  val debugInfo = method.getDebugInfo()
  if (debugInfo.lineTable.isEmpty()) {
    return null
  }

  val methodBytecode = DexBytecode.fromBytes(method.bytecodes(), debugInfo)
  if (methodBytecode.instructions.isEmpty()) {
    return null
  }

  val lineTableIterator = debugInfo.lineTable.iterator()
  var nextLine = lineTableIterator.next()
  var currentLineNumber: Int? = null
  var lineEverMatched = false
  var inInline = false
  val sortedInlineCalls = LinkedList(extractInlineCalls(location))
  val targetCodeIndex = location.codeIndex().toUInt()
  var unvisited: List<KotlinMethodSmartStepTarget>? = null
  for (insn in methodBytecode.instructions) {
    if (unvisited == null && insn.index >= targetCodeIndex) {
      unvisited = getUnvisitedTargets()
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
  val eliminated = getUnvisitedTargets()
  return MethodVisitation(unvisited ?: eliminated, eliminated)
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
  val lineTable = safeAllLineLocations().map { LineTableEntry(it.codeIndex().toUInt(), it.lineNumber()) }
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
Merge contiguous ranges of the same variable so that enclosing inline functions
are properly filtered out and inline calls are not split, without absorbing
nested calls to the same inline function (whose ranges strictly overlap).
*/
private fun mergeCalls(inlineVariables: List<ScopedVariable>): List<InlineCallInfo> {
  return inlineVariables
    .groupBy { it.variable.name() }
    .values
    .flatMap { varsWithSameName ->
      val sorted =
        varsWithSameName.sortedWith(
          compareBy<ScopedVariable> { it.scope.start.codeIndex() }.thenByDescending { it.scope.endInclusive.codeIndex() }
        )
      val merged = mutableListOf<InlineCallInfo>()
      sorted.forEach { (variable, scope) ->
        val start = scope.start.codeIndex()
        val end = scope.endInclusive.codeIndex()
        val contiguousIndex = merged.indexOfFirst { start == it.bciRange.last + 1 }
        if (contiguousIndex >= 0) {
          val prev = merged[contiguousIndex]
          merged[contiguousIndex] = prev.copy(bciRange = prev.bciRange.first..maxOf(prev.bciRange.last, end))
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

/**
 * Returns the last instruction of the last line in the source code.
 *
 * Note that this is not the same as getting the last instruction of the bytecode. For example:
 * ```
 * fun headAndTail(s: Set<Int>): Pair<Int, Int>? {
 *   val head = s.firstOrNull() ?: return null
 *   val tail = s.last()
 *   return Pair(head, tail)
 * }
 * ```
 *
 * Generates code (opcode arguments removed for readability):
 * ```
 *     3:  0000: move-object
 *         0001: check-cast
 *         0003: invoke-static
 *         0006: move-result-object
 *         0007: check-cast
 *         0009: if-eqz
 *         000b: invoke-virtual
 *         000e: move-result
 *     4:  000f: move-object
 *         0010: check-cast
 *         0012: invoke-static
 *         0015: move-result-object
 *         0016: check-cast
 *         0018: invoke-virtual
 *         001b: move-result
 *     5:  001c: new-instance
 *         001e: invoke-static
 *         0021: move-result-object
 *         0022: invoke-static
 *         0025: move-result-object
 *         0026: invoke-direct
 *         0029: return-object
 *     3:  002a: const/4
 *         002b: return-object
 * ```
 *
 * Notice that the last instruction on the bytecode is on source line 3 while the last source line is 5
 */
private fun DexBytecode.getLastInstructionOfLastLine(): Instruction? {
  val lastLine = debugInfo.lineTable.maxOfOrNull { it.lineNumber } ?: return null
  return instructionsForLineNumber(lastLine).lastOrNull()
}
