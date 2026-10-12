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

import com.android.ide.common.rendering.api.ViewInfo
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Path
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ComposeViewInfoParserTest {
  data class FakeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

  data class FakeLocation(val packageHash: Int)

  data class FakeComposeViewInfoNode(
    val name: String,
    val fileName: String,
    val lineNumber: Int,
    val bounds: FakeBounds,
    val children: List<FakeComposeViewInfoNode> = emptyList(),
    val location: FakeLocation? = null,
  )

  class LeafView

  class FakeViewGroup(private val children: List<Any?>) {
    fun getChildCount(): Int = children.size

    fun getChildAt(index: Int): Any? = children[index]
  }

  /**
   * Dynamically generates and instantiates a `ComposeViewAdapter` class inside an isolated [ClassLoader] so tests do not pollute the shared
   * module classpath.
   *
   * Equivalent Java source code generated via ASM:
   * ```java
   * package androidx.compose.ui.tooling;
   *
   * import java.util.List;
   *
   * public class ComposeViewAdapter {
   *   private final List<Object> viewInfos;
   *
   *   public ComposeViewAdapter(List<Object> viewInfos) {
   *     super();
   *     this.viewInfos = viewInfos;
   *   }
   *
   *   public List<Object> getViewInfos() {
   *     return this.viewInfos;
   *   }
   * }
   * ```
   */
  private fun createComposeViewAdapterInstance(viewInfos: List<Any> = emptyList()): Any {
    val internalName = COMPOSE_VIEW_ADAPTER_FQN.replace('.', '/')
    val cw = ClassWriter(0)
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
    cw
      .visitField(
        Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
        "viewInfos",
        "Ljava/util/List;",
        null,
        null,
      )
      .visitEnd()

    // Constructor: public ComposeViewAdapter(List viewInfos)
    cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null).apply {
      visitCode()
      visitVarInsn(Opcodes.ALOAD, 0)
      visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitVarInsn(Opcodes.ALOAD, 0)
      visitVarInsn(Opcodes.ALOAD, 1)
      visitFieldInsn(Opcodes.PUTFIELD, internalName, "viewInfos", "Ljava/util/List;")
      visitInsn(Opcodes.RETURN)
      visitMaxs(2, 2)
      visitEnd()
    }

    // Getter: public List getViewInfos()
    cw.visitMethod(Opcodes.ACC_PUBLIC, "getViewInfos", "()Ljava/util/List;", null, null).apply {
      visitCode()
      visitVarInsn(Opcodes.ALOAD, 0)
      visitFieldInsn(Opcodes.GETFIELD, internalName, "viewInfos", "Ljava/util/List;")
      visitInsn(Opcodes.ARETURN)
      visitMaxs(1, 1)
      visitEnd()
    }

    cw.visitEnd()
    val bytes = cw.toByteArray()
    val loader =
      object : ClassLoader(List::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
          if (name == COMPOSE_VIEW_ADAPTER_FQN) {
            return defineClass(name, bytes, 0, bytes.size)
          }
          throw ClassNotFoundException(name)
        }
      }
    return loader.loadClass(COMPOSE_VIEW_ADAPTER_FQN).getDeclaredConstructor(List::class.java).newInstance(viewInfos)
  }

  @Test
  fun parsesViewInfoPastNullAndLeafSiblingsAndRoundTripsJson() {
    val buttonNode =
      FakeComposeViewInfoNode(
        name = "Button",
        fileName = "HomeScreen.kt",
        lineNumber = 48,
        bounds = FakeBounds(10, 20, 90, 60),
        location = FakeLocation(777),
      )
    val rootNode =
      FakeComposeViewInfoNode(
        name = "Column",
        fileName = "HomeScreen.kt",
        lineNumber = 40,
        bounds = FakeBounds(0, 0, 100, 200),
        children = listOf(buttonNode),
        location = FakeLocation(777),
      )
    val adapter = createComposeViewAdapterInstance(listOf(rootNode))
    val wrapperRoot = FakeViewGroup(listOf(null, LeafView(), FakeViewGroup(emptyList()), adapter))
    val layoutlibViewInfo = ViewInfo("FrameLayout", null, 0, 0, 100, 200, wrapperRoot, null, null)

    val parsed = parseViewInfo(layoutlibViewInfo)
    assertThat(parsed).hasSize(1)
    assertThat(parsed.single().name).isEqualTo("Column")
    assertThat(parsed.single().bounds).isEqualTo(PxBounds(0, 0, 100, 200))
    assertThat(parsed.single().children.single().name).isEqualTo("Button")
    assertThat(parsed.findSmallestHit(50, 40).single().name).isEqualTo("Button")

    val json = parsed.toJsonArray().toString()
    val roundTripped = parseComposeViewInfosJson(json)
    assertThat(roundTripped).isEqualTo(parsed)
    assertThat(boundsJsonPath(Path.of("/tmp/out/HomeScreenPreview_0.png")).toString())
      .isEqualTo(Path.of("/tmp/out/HomeScreenPreview_0.bounds.json").toString())

    val serialized =
      ByteArrayOutputStream().use { byteOut ->
        ObjectOutputStream(byteOut).use { it.writeObject(parsed) }
        byteOut.toByteArray()
      }
    val deserialized = ObjectInputStream(ByteArrayInputStream(serialized)).use { it.readObject() }
    assertThat(deserialized).isEqualTo(parsed)
  }
}
