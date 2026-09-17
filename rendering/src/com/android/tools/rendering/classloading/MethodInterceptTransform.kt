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
package com.android.tools.rendering.classloading

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter

private const val VIRTUAL_METHOD_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
private const val STATIC_METHOD_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
private const val MEMBER_CHECK_METHOD_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;)V"

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // This is intentionally the Object type
private val OBJECT_TYPE = Type.getType(Object::class.java)

private class MethodInterceptorVisitor(
  delegate: MethodVisitor,
  access: Int,
  name: String?,
  descriptor: String?,
  private val staticTrampolineClassType: Type,
  private val staticTrampolineMethodType: org.objectweb.asm.commons.Method,
  private val virtualTrampolineClassType: Type,
  private val virtualTrampolineMethodType: org.objectweb.asm.commons.Method,
  private val memberCheckTrampolineClassType: Type?,
  private val memberCheckTrampolineMethodType: org.objectweb.asm.commons.Method?,
  private val fieldCheckTrampolineClassType: Type?,
  private val fieldCheckTrampolineMethodType: org.objectweb.asm.commons.Method?,
  private val shouldIntercept: (Int, String, String) -> Boolean,
  private val shouldInterceptField: ((String, String) -> Boolean)?,
  private val shouldInterceptMember: ((String, String) -> Boolean)?,
) : GeneratorAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
  /**
   * Creates a local variable with an `Object[]` that will store all the parameters to the call. The arguments are expected to be currently
   * sitting at the top of the stack as follows:
   * ```
   * ---- Stack Top -----
   *   ARG2
   *   ARG1
   *   ...other elements...
   * ---- Stack Bottom ----
   * ```
   *
   * This method will get the all the arguments and leave them as a reference to an `Object[]` array at the top of the stack. `other
   * elements` will not be affected.
   *
   * ```
   * ---- Stack Top -----
   *   array reference to [ARG1, ARG2]
   *   ...other elements...
   * ---- Stack Bottom ----
   * ```
   */
  private fun GeneratorAdapter.buildObjectsArrayFromOperandStackForCall(arguments: Array<Type>) {
    // the "stack:" lines represent the expected stack layout at that moment before the next line
    // executes.
    // "ar" references to the Arguments Array Reference

    // val args = Array<Any>(arguments.size)
    push(arguments.size)
    newArray(OBJECT_TYPE)
    // stack: [ar]

    arguments.reversed().forEachIndexed { index, type ->
      // stack: [ar]
      if (type.size < 2) dupX1() else dupX2() // If the value size is 2, use dupX2 to jump over the two parts of the value.
      // stack: [ar, value, ar]
      if (type.size < 2) dupX1() else dupX2()
      // stack: [ar, ar, value, ar]
      pop() // Pop array reference
      push(arguments.size - 1 - index)
      // stack: [ar, ar, value, index]
      if (type.size < 2) dupX1() else dupX2()
      // stack: [ar, ar, index, value, index]
      pop() // Pop array index
      // stack: [ar, ar, index, value]
      box(type)
      checkCast(OBJECT_TYPE)
      arrayStore(OBJECT_TYPE)
      // stack: [ar]
    }
  }

  /**
   * This method does the reverse operation to [buildObjectsArrayFromOperandStackForCall] by reading a reference to an `Object[]` array from
   * the top of the stack and leaving all the arguments in the correct order.
   */
  private fun GeneratorAdapter.pushObjectsFromArrayIntoOperandStack(arguments: Array<Type>) {
    // stack: [ar]
    arguments.forEachIndexed { index, type ->
      dup() // stack: [ar, ar]
      push(index) // stack: [ar, ar, index]
      arrayLoad(OBJECT_TYPE) // Load from the array
      unbox(type)
      // [ar, value]
      if (type.size < 2) dupX1() else dup2X1()
      // [value, ar, value]
      if (type.size < 2) pop() else pop2()
      // [value, ar]
    }
    pop()
  }

  private fun interceptMethodCall(
    opcode: Int,
    interceptedOwner: String,
    interceptedMethodName: String,
    interceptedMethodDescriptor: String,
    @Suppress("UNUSED_PARAMETER") isInterface: Boolean,
  ) =
    when (opcode) {
      Opcodes.INVOKESTATIC -> {
        val arguments = Type.getArgumentTypes(interceptedMethodDescriptor)
        if (arguments.isEmpty()) {
          push(interceptedOwner)
          push(interceptedMethodName)
          push(null as String?)
          invokeStatic(staticTrampolineClassType, staticTrampolineMethodType)
        } else {
          buildObjectsArrayFromOperandStackForCall(arguments)
          dup() // stack: [ar, ar]
          push(interceptedOwner)
          push(interceptedMethodName)
          // stack: [ar, ar, owner, method]
          dup2X1()
          // stack: [ar, owner, method, ar, owner, method]
          pop()
          pop()
          // stack: [ar, owner, method, ar]
          invokeStatic(staticTrampolineClassType, staticTrampolineMethodType)
          // stack: [ar]
          pushObjectsFromArrayIntoOperandStack(arguments)
          // stack: []
        }
      }
      Opcodes.INVOKEVIRTUAL,
      Opcodes.INVOKEINTERFACE -> {
        interceptVirtualOrSpecial(interceptedOwner, interceptedMethodName, interceptedMethodDescriptor)
      }
      Opcodes.INVOKESPECIAL -> {
        if (interceptedMethodName == "<init>") {
          val arguments = Type.getArgumentTypes(interceptedMethodDescriptor)
          if (arguments.isEmpty()) {
            push(interceptedOwner)
            push(interceptedMethodName)
            push(null as String?)
            invokeStatic(staticTrampolineClassType, staticTrampolineMethodType)
          } else {
            buildObjectsArrayFromOperandStackForCall(arguments)
            dup() // stack: [ar, ar]
            push(interceptedOwner)
            push(interceptedMethodName)
            // stack: [ar, ar, owner, method]
            dup2X1()
            // stack: [ar, owner, method, ar, owner, method]
            pop()
            pop()
            // stack: [ar, owner, method, ar]
            invokeStatic(staticTrampolineClassType, staticTrampolineMethodType)
            // stack: [ar]
            pushObjectsFromArrayIntoOperandStack(arguments)
            // stack: []
          }
        } else {
          interceptVirtualOrSpecial(interceptedOwner, interceptedMethodName, interceptedMethodDescriptor)
        }
      }
      else -> {
        // Do nothing
      }
    }

  private fun interceptVirtualOrSpecial(
    interceptedOwner: String,
    interceptedMethodName: String,
    interceptedMethodDescriptor: String,
  ) {
    val arguments = Type.getArgumentTypes(interceptedMethodDescriptor)
    if (arguments.isEmpty()) {
      dup() // Push "this" again
      push(interceptedOwner)
      push(interceptedMethodName)
      push(null as String?)
      invokeStatic(virtualTrampolineClassType, virtualTrampolineMethodType)
    } else {
      // stack: [this, <arguments>]
      buildObjectsArrayFromOperandStackForCall(arguments)
      // stack: [this, ar]
      dup2() // stack: [this, ar, this, ar]
      push(interceptedOwner) // stack: [this, ar, this, ar, owner]
      swap() // stack: [this, ar, this, owner, ar]
      push(interceptedMethodName) // stack: [this, ar, this, owner, ar, methodName]
      swap() // stack: [this, ar, this, owner, methodName, ar]
      invokeStatic(virtualTrampolineClassType, virtualTrampolineMethodType)
      // stack: [this, ar]
      pushObjectsFromArrayIntoOperandStack(arguments)
      // stack: [this, <arguments>]
    }
  }

  private fun extractHandles(arg: Any): List<Handle> =
    when (arg) {
      is Handle -> listOf(arg)
      is ConstantDynamic -> {
        val result = mutableListOf<Handle>()
        result.add(arg.bootstrapMethod)
        for (i in 0 until arg.bootstrapMethodArgumentCount) {
          result.addAll(extractHandles(arg.getBootstrapMethodArgument(i)))
        }
        result
      }
      else -> emptyList()
    }

  private fun isFieldHandle(handle: Handle): Boolean =
    when (handle.tag) {
      Opcodes.H_GETFIELD,
      Opcodes.H_GETSTATIC,
      Opcodes.H_PUTFIELD,
      Opcodes.H_PUTSTATIC -> true
      else -> false
    }

  private fun handleMatches(handle: Handle): Boolean {
    if (isFieldHandle(handle)) {
      return shouldInterceptField?.invoke(handle.owner, handle.name) ?: false
    }
    if (shouldInterceptMember?.invoke(handle.owner, handle.name) == true) {
      return true
    }
    val opcode =
      when (handle.tag) {
        Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC
        Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL
        else -> Opcodes.INVOKEVIRTUAL
      }
    return shouldIntercept(opcode, handle.owner, handle.name)
  }

  private fun emitHandleChecks(handles: List<Handle>) {
    for (handle in handles) {
      if (handleMatches(handle)) {
        push(handle.owner)
        push(handle.name)
        if (isFieldHandle(handle) && fieldCheckTrampolineClassType != null && fieldCheckTrampolineMethodType != null) {
          invokeStatic(fieldCheckTrampolineClassType, fieldCheckTrampolineMethodType)
        } else if (memberCheckTrampolineClassType != null && memberCheckTrampolineMethodType != null) {
          invokeStatic(memberCheckTrampolineClassType, memberCheckTrampolineMethodType)
        }
      }
    }
  }

  override fun visitInvokeDynamicInsn(
    name: String,
    descriptor: String,
    bootstrapMethodHandle: Handle,
    vararg bootstrapMethodArguments: Any,
  ) {
    if (
      (memberCheckTrampolineClassType != null && memberCheckTrampolineMethodType != null) ||
        (fieldCheckTrampolineClassType != null && fieldCheckTrampolineMethodType != null)
    ) {
      val handles = mutableListOf<Handle>()
      handles.add(bootstrapMethodHandle)
      for (arg in bootstrapMethodArguments) {
        handles.addAll(extractHandles(arg))
      }
      emitHandleChecks(handles)
    }
    super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
  }

  override fun visitLdcInsn(value: Any) {
    if (
      (memberCheckTrampolineClassType != null && memberCheckTrampolineMethodType != null) ||
        (fieldCheckTrampolineClassType != null && fieldCheckTrampolineMethodType != null)
    ) {
      val handles = extractHandles(value)
      emitHandleChecks(handles)
    }
    super.visitLdcInsn(value)
  }

  override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
    if (shouldIntercept(opcode, owner, name)) interceptMethodCall(opcode, owner, name, descriptor, isInterface)
    if (mv != null) mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
  }
}

/**
 * [ClassVisitor] that transform the given classes by replacing certain method call with new calls that will invoke a trampoline method that
 * allows to inspect the call parameters.
 *
 * [shouldInstrument] looks on whether a certain class should be instrumented. If a class is not instrumented, the method calls will not be
 * inspected for that class. [shouldIntercept] will determine if a specific call should be instrumented. For an instrumented class,
 * [shouldIntercept] will be called for every call with the first parameter being the class name of the class being invoked and the second
 * the method being called. If [shouldIntercept] returns false, the call will not be instrumented.
 *
 * An example on how this class works would be the following:
 *
 * Let's assume a class called `TestClass` that contains a simple method called `testMethod`.
 *
 * ```
 * class TestClass {
 *   fun testMethod() {
 *     java.io.File("test.txt").delete()
 *   }
 * }
 * ```
 *
 * If this [MethodInterceptTransform] is applied, [shouldInstrument] will be called with the parameters ("TestClass", "testMethod"). If
 * [shouldInstrument] returns true, then [shouldIntercept] will be called with the parameters ("java/io/File","delete").
 *
 * If [shouldInstrument] returns true, then the [virtualTrampolineMethodType] will be called for the `delete` call.
 */
class MethodInterceptTransform(
  delegate: ClassVisitor,
  virtualTrampolineMethod: java.lang.reflect.Method,
  staticTrampolineMethod: java.lang.reflect.Method,
  memberCheckTrampolineMethod: java.lang.reflect.Method? = null,
  fieldCheckTrampolineMethod: java.lang.reflect.Method? = null,
  private val shouldInstrument: (String, String) -> Boolean = { _, _ -> true },
  private val shouldInterceptWithOpcode: ((Int, String, String) -> Boolean)? = null,
  private val shouldInterceptField: ((String, String) -> Boolean)? = null,
  private val shouldInterceptMember: ((String, String) -> Boolean)? = null,
  private val shouldIntercept: (String, String) -> Boolean = { _, _ -> false },
) : ClassVisitor(Opcodes.ASM9, delegate), ClassVisitorUniqueIdProvider {
  constructor(
    delegate: ClassVisitor,
    virtualTrampolineMethod: java.lang.reflect.Method,
    staticTrampolineMethod: java.lang.reflect.Method,
    memberCheckTrampolineMethod: java.lang.reflect.Method? = null,
    shouldInstrument: (String, String) -> Boolean = { _, _ -> true },
    shouldInterceptWithOpcode: ((Int, String, String) -> Boolean)? = null,
    shouldIntercept: (String, String) -> Boolean = { _, _ -> false },
  ) : this(
    delegate,
    virtualTrampolineMethod,
    staticTrampolineMethod,
    memberCheckTrampolineMethod,
    null,
    shouldInstrument,
    shouldInterceptWithOpcode,
    null,
    null,
    shouldIntercept,
  )

  constructor(
    delegate: ClassVisitor,
    virtualTrampolineMethod: java.lang.reflect.Method,
    staticTrampolineMethod: java.lang.reflect.Method,
    shouldInstrument: (String, String) -> Boolean = { _, _ -> true },
    shouldIntercept: (String, String) -> Boolean = { _, _ -> false },
  ) : this(
    delegate,
    virtualTrampolineMethod,
    staticTrampolineMethod,
    null,
    null,
    shouldInstrument,
    null,
    null,
    null,
    shouldIntercept,
  )

  override val uniqueId: String = "${MethodInterceptTransform::className}"
  private var className = ""

  private val virtualTrampolineClassType = Type.getType(virtualTrampolineMethod.declaringClass)
  private val virtualTrampolineMethodType = virtualTrampolineMethod.toMethodType()
  private val staticTrampolineClassType = Type.getType(staticTrampolineMethod.declaringClass)
  private val staticTrampolineMethodType = staticTrampolineMethod.toMethodType()
  private val memberCheckTrampolineClassType = memberCheckTrampolineMethod?.let { Type.getType(it.declaringClass) }
  private val memberCheckTrampolineMethodType = memberCheckTrampolineMethod?.toMethodType()
  private val fieldCheckTrampolineClassType = fieldCheckTrampolineMethod?.let { Type.getType(it.declaringClass) }
  private val fieldCheckTrampolineMethodType = fieldCheckTrampolineMethod?.toMethodType()

  init {
    assert(virtualTrampolineMethodType.descriptor == VIRTUAL_METHOD_DESCRIPTOR) {
      "Virtual trampoline method descriptor must be $VIRTUAL_METHOD_DESCRIPTOR but was ${virtualTrampolineMethodType.descriptor}"
    }
    assert(staticTrampolineMethodType.descriptor == STATIC_METHOD_DESCRIPTOR) {
      "Static trampoline method descriptor must be $STATIC_METHOD_DESCRIPTOR but was ${staticTrampolineMethodType.descriptor}"
    }
    if (memberCheckTrampolineMethodType != null) {
      assert(memberCheckTrampolineMethodType.descriptor == MEMBER_CHECK_METHOD_DESCRIPTOR) {
        "Member check trampoline method descriptor must be $MEMBER_CHECK_METHOD_DESCRIPTOR but was ${memberCheckTrampolineMethodType.descriptor}"
      }
    }
    if (fieldCheckTrampolineMethodType != null) {
      assert(fieldCheckTrampolineMethodType.descriptor == MEMBER_CHECK_METHOD_DESCRIPTOR) {
        "Field check trampoline method descriptor must be $MEMBER_CHECK_METHOD_DESCRIPTOR but was ${fieldCheckTrampolineMethodType.descriptor}"
      }
    }
  }

  override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
    className = name ?: ""
    super.visit(version, access, name, signature, superName, interfaces)
  }

  override fun visitMethod(
    access: Int,
    name: String?,
    descriptor: String?,
    signature: String?,
    exceptions: Array<out String>?,
  ): MethodVisitor {
    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
    if (!shouldInstrument(className, name ?: "")) return delegate

    val interceptPredicate: (Int, String, String) -> Boolean =
      shouldInterceptWithOpcode ?: { _, owner, methodName -> shouldIntercept(owner, methodName) }

    return MethodInterceptorVisitor(
      delegate,
      access,
      name,
      descriptor,
      staticTrampolineClassType,
      staticTrampolineMethodType,
      virtualTrampolineClassType,
      virtualTrampolineMethodType,
      memberCheckTrampolineClassType,
      memberCheckTrampolineMethodType,
      fieldCheckTrampolineClassType,
      fieldCheckTrampolineMethodType,
      interceptPredicate,
      shouldInterceptField,
      shouldInterceptMember,
    )
  }
}
