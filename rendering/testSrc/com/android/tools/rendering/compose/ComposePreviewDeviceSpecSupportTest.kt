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
import com.android.tools.rendering.classloading.loaders.StaticLoader
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.diagnostic.ControlFlowException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertThrows
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ComposePreviewDeviceSpecSupportTest {
  private class TestControlFlowException : RuntimeException("ControlFlow"), ControlFlowException

  /**
   * Returns the bytecode of a class equivalent to the `PreviewDeviceSpec` object that AndroidX ui-tooling declares, so that tests don't
   * need a ui-tooling dependency. With the default arguments, it is the bytecode of:
   * ```
   * package androidx.compose.ui.tooling;
   *
   * public final class PreviewDeviceSpec {
   *   public static final String SUPPORTED_PARAMETERS = <supportedParameters>;
   * }
   * ```
   *
   * [supportedParameters] is the constant value of the field, stored in the constant pool as for a Kotlin `const val`: a [String] such as
   * `"expand,maxHeight"`, an [Int] to declare the field with the `int` type instead, or `null` for a field without a constant value. With
   * [includeField] set to `false`, the class has no `SUPPORTED_PARAMETERS` field, as in ui-tooling versions before it was added. The class
   * has no methods, not even a constructor, because only its fields are read.
   */
  private fun createPreviewDeviceSpecClassBytes(supportedParameters: Any? = null, includeField: Boolean = true): ByteArray {
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      "androidx/compose/ui/tooling/PreviewDeviceSpec",
      null,
      "java/lang/Object",
      null,
    )
    if (includeField) {
      val descriptor = if (supportedParameters is Int) "I" else "Ljava/lang/String;"
      cw
        .visitField(
          Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC,
          "SUPPORTED_PARAMETERS",
          descriptor,
          null,
          supportedParameters,
        )
        .visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
  }

  private fun classLoaderWithResource(resourcePath: String, bytes: ByteArray): ClassLoader =
    object : ClassLoader(null) {
      override fun getResourceAsStream(name: String?): InputStream? = if (name == resourcePath) ByteArrayInputStream(bytes) else null
    }

  @Test
  fun readsSupportedParametersFromPreviewDeviceSpecClass() {
    val bytes = createPreviewDeviceSpecClassBytes("windowPosture,pointerPrecision,keyboardKind,hasCamera,hasMicrophone,viewingDistance")
    val loader = classLoaderWithResource("androidx/compose/ui/tooling/PreviewDeviceSpec.class", bytes)

    assertThat(loader.supportedComposePreviewDeviceSpecParameters())
      .containsExactly(
        "windowPosture",
        "pointerPrecision",
        "keyboardKind",
        "hasCamera",
        "hasMicrophone",
        "viewingDistance",
      )
      .inOrder()
  }

  @Test
  fun trimsWhitespaceAndIgnoresEmptyEntries() {
    val bytes = createPreviewDeviceSpecClassBytes(" windowPosture , , pointerPrecision , viewingDistance , ")
    val loader = classLoaderWithResource("androidx/compose/ui/tooling/PreviewDeviceSpec.class", bytes)

    assertThat(loader.supportedComposePreviewDeviceSpecParameters())
      .containsExactly("windowPosture", "pointerPrecision", "viewingDistance")
      .inOrder()
  }

  @Test
  fun returnsEmptySetWhenPreviewDeviceSpecClassIsAbsent() {
    val loader = classLoaderWithResource("androidx/compose/ui/tooling/Other.class", ByteArray(0))

    assertThat(loader.supportedComposePreviewDeviceSpecParameters()).isEmpty()
  }

  @Test
  fun returnsEmptySetWhenSupportedParametersFieldIsMissingOrNotString() {
    val withoutField =
      classLoaderWithResource(
        "androidx/compose/ui/tooling/PreviewDeviceSpec.class",
        createPreviewDeviceSpecClassBytes(includeField = false),
      )
    assertThat(withoutField.supportedComposePreviewDeviceSpecParameters()).isEmpty()

    val nonStringField =
      classLoaderWithResource(
        "androidx/compose/ui/tooling/PreviewDeviceSpec.class",
        createPreviewDeviceSpecClassBytes(supportedParameters = 42),
      )
    assertThat(nonStringField.supportedComposePreviewDeviceSpecParameters()).isEmpty()
  }

  @Test
  fun returnsEmptySetWhenClassBytesAreCorrupted() {
    val corrupted = classLoaderWithResource("androidx/compose/ui/tooling/PreviewDeviceSpec.class", byteArrayOf(1, 2, 3))

    assertThat(corrupted.supportedComposePreviewDeviceSpecParameters()).isEmpty()
  }

  @Test
  fun cachesResultPerClassLoaderInstance() {
    val streamOpenCount = AtomicInteger(0)
    val bytes = createPreviewDeviceSpecClassBytes("windowPosture,pointerPrecision")
    val loader =
      object : ClassLoader(null) {
        override fun getResourceAsStream(name: String?): InputStream? {
          if (name == "androidx/compose/ui/tooling/PreviewDeviceSpec.class") {
            streamOpenCount.incrementAndGet()
            return ByteArrayInputStream(bytes)
          }
          return null
        }
      }

    assertThat(loader.supportedComposePreviewDeviceSpecParameters()).containsExactly("windowPosture", "pointerPrecision").inOrder()
    assertThat(loader.supportedComposePreviewDeviceSpecParameters()).containsExactly("windowPosture", "pointerPrecision").inOrder()
    assertThat(streamOpenCount.get()).isEqualTo(1)
  }

  @Test
  fun fallsBackToDelegatingClassLoaderWhenResourceIsAbsent() {
    val bytes = createPreviewDeviceSpecClassBytes("windowPosture,pointerPrecision")
    val loader = DelegatingClassLoader(null, StaticLoader("androidx.compose.ui.tooling.PreviewDeviceSpec" to bytes))

    assertThat(loader.supportedComposePreviewDeviceSpecParameters()).containsExactly("windowPosture", "pointerPrecision").inOrder()
  }

  @Test
  fun rethrowsCancellationAndControlFlowExceptionsWithoutCaching() {
    val bytes = createPreviewDeviceSpecClassBytes("windowPosture")
    for (exceptionFactory in listOf({ CancellationException("Cancelled") }, { TestControlFlowException() })) {
      val shouldThrow = AtomicBoolean(true)
      val loader =
        object : ClassLoader(null) {
          override fun getResourceAsStream(name: String?): InputStream? {
            if (shouldThrow.get()) throw exceptionFactory()
            return if (name == "androidx/compose/ui/tooling/PreviewDeviceSpec.class") ByteArrayInputStream(bytes) else null
          }
        }

      val expectedException = exceptionFactory()
      assertThrows(expectedException.javaClass) { loader.supportedComposePreviewDeviceSpecParameters() }

      shouldThrow.set(false)
      assertThat(loader.supportedComposePreviewDeviceSpecParameters()).containsExactly("windowPosture")
    }
  }

  @Test
  fun unsupportedDeviceSpecWarning_reportsUnsupportedParameters() {
    // Non-spec or tool-handled device specs do not query supportedParameters
    assertThat(
        unsupportedComposePreviewDeviceSpecWarning(null) {
          throw AssertionError("Should not query supportedParameters for null spec")
        }
      )
      .isNull()
    assertThat(
        unsupportedComposePreviewDeviceSpecWarning("id:pixel_7") {
          throw AssertionError("Should not query supportedParameters for id: spec")
        }
      )
      .isNull()
    assertThat(
        unsupportedComposePreviewDeviceSpecWarning(
          "spec:parent=pixel_7,width=411dp,height=891dp,dpi=420,isRound=false,chinSize=0dp,orientation=portrait,cutout=none,navigation=gesture"
        ) {
          throw AssertionError("Should not query supportedParameters when only tool-handled keys are used")
        }
      )
      .isNull()
    assertThat(
        unsupportedComposePreviewDeviceSpecWarning("spec:parent=pixel_7,windowPosture=tabletop") {
          setOf("windowPosture")
        }
      )
      .isNull()
    // Pre-PreviewDeviceSpec parameters (MediaQuery) do not warn when PreviewDeviceSpec is absent (emptySet()), because ui-tooling
    // 1.11.0-alpha06+ already supports them without PreviewDeviceSpec.class.
    assertThat(
        unsupportedComposePreviewDeviceSpecWarning("spec:parent=pixel_7,windowPosture=tabletop,WINDOWPOSTURE=book") {
          emptySet()
        }
      )
      .isNull()
    assertThat(
        unsupportedComposePreviewDeviceSpecWarning("spec:parent=pixel_7,windowPosture=tabletop,WINDOWPOSTURE=book") {
          setOf("pointerPrecision")
        }
      )
      .isEqualTo("windowPosture in the device spec requires a newer version of androidx.compose.ui:ui-tooling and will be ignored.")
    // Case-insensitive matching, canonical parameter names, preserved order
    val spec = " SPEC:parent=pixel_7,WINDOWPOSTURE=tabletop,POINTERPRECISION=fine,viewingDistance=near "
    assertThat(unsupportedComposePreviewDeviceSpecWarning(spec) { setOf("pointerPrecision") })
      .isEqualTo(
        "windowPosture, viewingDistance in the device spec require a newer version of androidx.compose.ui:ui-tooling and will be ignored."
      )
  }
}
