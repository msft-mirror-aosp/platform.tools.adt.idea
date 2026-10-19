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
package org.jetbrains.android.uipreview

import com.android.tools.rendering.security.AllowAllRenderSandbox
import com.android.tools.rendering.security.EP_NAME
import com.android.tools.rendering.security.RenderSandbox
import com.android.tools.rendering.security.RenderSandboxTransformTrampoline
import com.android.tools.rendering.security.RenderSecurityException
import com.android.tools.rendering.security.RenderSecurityManager
import com.android.tools.rendering.security.RenderSecurityManagerOverrides
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.registerExtension
import java.beans.XMLDecoder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

class StudioRenderSandboxTest {
  /** [RenderSecurityManagerOverrides] allows ASWB to override the "checkProperty" check. */
  @Test
  fun `check render security manager overrides`() {
    val disposable = Disposer.newDisposable()
    try {
      val app = MockApplication(disposable)
      ApplicationManager.setApplication(app, disposable)
      @Suppress("UnstableApiUsage")
      app.extensionArea.registerExtensionPoint(
        EP_NAME.name,
        RenderSecurityManagerOverrides::class.java.name,
        ExtensionPoint.Kind.INTERFACE,
        false,
      )

      val sandbox = StudioRenderSandbox(null, null, null)
      try {
        sandbox.checkPropertyAccess()
        fail("Expected SecurityException")
      } catch (_: SecurityException) {}

      // Install extension
      app.registerExtension(
        EP_NAME,
        object : RenderSecurityManagerOverrides {
          override fun allowsPropertiesAccess(): Boolean = true
        },
        disposable,
      )
      sandbox.checkPropertyAccess()
    } finally {
      Disposer.dispose(disposable)
    }
  }

  @Test
  fun `check file read allowed in project path`() {
    val projectDir = File("/path/to/project").absolutePath
    val sandbox = StudioRenderSandbox(null, projectDir, null)
    sandbox.checkFileRead(File(projectDir, "file.txt").absolutePath)
  }

  @Test
  fun `check file read denied outside project path`() {
    val projectDir = File("/path/to/project").absolutePath
    val sandbox = StudioRenderSandbox(null, projectDir, null)
    try {
      sandbox.checkFileRead(File("/path/to/other/file.txt").absolutePath)
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check file write allowed in temp dir`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    val tempDir = System.getProperty("java.io.tmpdir")
    sandbox.checkFileWrite(File(tempDir, "file.txt").absolutePath)
  }

  @Test
  fun `check file write denied outside temp dir`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkFileWrite(File("/path/to/project/file.txt").absolutePath)
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Ignore("b/513189628")
  @Test
  fun `check concurrency denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkConcurrency()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Concurrency is not allowed during rendering", e.message)
    }
  }

  @Test
  fun `check cleaner denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkCleaner()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Cleaner registration is denied during rendering", e.message)
    }
  }

  @Test
  fun `check image io denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkImageIo()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Access to ImageIO SPI registry is denied during rendering", e.message)
    }
  }

  @Test
  fun `check print job denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkPrintJob()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Print job access is denied during rendering", e.message)
    }
  }

  @Test
  fun `check signal denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkSignal()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Signal handling is denied during rendering", e.message)
    }
  }

  @Test
  fun `check event queue denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkEventQueue()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Event queue and AWT dispatch access is denied during rendering", e.message)
    }
  }

  @Test
  fun `check studio render security credential and nesting lifecycle`() {
    val security = StudioRenderSecurity(null, null, null)
    val credential = Any()
    val wrongCredential = Any()

    // Activating with credential installs the sandbox
    security.activate(credential)
    assertNotEquals(AllowAllRenderSandbox, RenderSandbox.getRenderSandbox())

    // Nested activation with same credential succeeds
    security.activate(credential)

    // Activating with wrong credential throws RenderSecurityException
    try {
      security.activate(wrongCredential)
      fail("Expected RenderSecurityException")
    } catch (_: RenderSecurityException) {}

    // Deactivating with wrong credential throws RenderSecurityException
    try {
      security.deactivate(wrongCredential)
      fail("Expected RenderSecurityException")
    } catch (_: RenderSecurityException) {}

    // First deactivate decrements count; sandbox remains active
    security.deactivate(credential)
    assertNotEquals(AllowAllRenderSandbox, RenderSandbox.getRenderSandbox())

    // Second deactivate resets sandbox
    security.deactivate(credential)
    assertEquals(AllowAllRenderSandbox, RenderSandbox.getRenderSandbox())

    // Further deactivate throws RenderSecurityException
    try {
      security.deactivate(credential)
      fail("Expected RenderSecurityException")
    } catch (_: RenderSecurityException) {}
  }

  @Test
  fun `check concurrent activation across threads applies sandbox on all threads`() {
    val security = StudioRenderSecurity(null, null, null)
    val executor = Executors.newFixedThreadPool(4)
    val barrier = CyclicBarrier(4)
    try {
      val futures =
        (1..4).map {
          executor.submit(
            Callable {
              val threadCred = Any()
              assertEquals(AllowAllRenderSandbox, RenderSandbox.getRenderSandbox())
              security.activate(threadCred)
              try {
                barrier.await()
                assertNotEquals(AllowAllRenderSandbox, RenderSandbox.getRenderSandbox())
                barrier.await()
              } finally {
                security.deactivate(threadCred)
              }
              assertEquals(AllowAllRenderSandbox, RenderSandbox.getRenderSandbox())
            }
          )
        }
      for (f in futures) {
        f.get()
      }
    } finally {
      executor.shutdown()
    }
  }

  @Test
  fun `check disable sandbox blocked while studio render security is active`() {
    val security = StudioRenderSecurity(null, null, null)
    val credential = Any()
    security.activate(credential)
    try {
      try {
        RenderSecurityManager.disableSandbox()
        fail("Expected RenderSecurityException")
      } catch (_: RenderSecurityException) {}
    } finally {
      security.deactivate(credential)
    }
  }

  @Test
  fun `check setFactory denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkSetFactory()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Setting URL stream handler factory is denied", e.message)
    }
  }

  @Test
  fun `check scriptEngine denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkScriptEngine()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Access to ScriptEngineManager is denied", e.message)
    }
  }

  @Test
  fun `check serviceLoader denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkServiceLoader()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Access to ServiceLoader is denied", e.message)
    }
  }

  @Test
  fun `check render executor denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkRenderExecutor()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("RenderExecutor modification is denied during rendering", e.message)
    }
  }

  @Test
  fun `check class loader access denied for plugin classes`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkGetClassLoader(RenderSandboxTransformTrampoline::class.java)
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals(
        "Access to class loader via com.android.tools.rendering.security.RenderSandboxTransformTrampoline is denied during rendering",
        e.message,
      )
    }
  }

  @Test
  fun `check getClassLoader allowed for bootstrap classes`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    sandbox.checkGetClassLoader(String::class.java)
  }

  @Test
  fun `check class loader navigation denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkClassLoaderAccess()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Class loader navigation is denied during rendering", e.message)
    }
  }

  @Test
  fun `check class load denied for restricted classes`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkClassLoad("com.android.tools.rendering.security.RenderSandbox")
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Loading restricted class is denied: com.android.tools.rendering.security.RenderSandbox", e.message)
    }
  }

  @Test
  fun `check class load allowed for RenderSandboxTransformTrampoline`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    sandbox.checkClassLoad("com.android.tools.rendering.security.RenderSandboxTransformTrampoline")
  }

  @Test
  fun `check reflection invoke denied for restricted class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke("java/lang/ProcessBuilder", "start")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke allowed for benign class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    sandbox.checkReflectionInvoke("java/lang/String", "length")
  }

  @Test
  fun `check reflection invoke with Class denied for restricted class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke(ProcessBuilder::class.java, "start")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke with Class allowed for benign class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    sandbox.checkReflectionInvoke(String::class.java, "length")
  }

  @Test
  fun `check xmlDecoder denied`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkXmlDecoder()
      fail("Expected SecurityException")
    } catch (e: SecurityException) {
      assertEquals("Access to java.beans.XMLDecoder is denied", e.message)
    }
  }

  @Test
  fun `check reflection invoke denied for ProcessImpl`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke("java/lang/ProcessImpl", "start")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke denied for XMLDecoder`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke("java/beans/XMLDecoder", "readObject")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
    try {
      sandbox.checkReflectionInvoke("java/beans/XMLDecoder", "<init>")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke with Class denied for XMLDecoder`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke(java.beans.XMLDecoder::class.java, "readObject")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
    try {
      sandbox.checkReflectionInvoke(java.beans.XMLDecoder::class.java, "<init>")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke denied for XMLEncoder`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke("java/beans/XMLEncoder", "<init>")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check field access with Class denied for restricted class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkFieldAccess(File::class.java, "path")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke denied for privateLookupIn on restricted class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke(ProcessBuilder::class.java, "<privateLookup>")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }

  @Test
  fun `check reflection invoke denied for MethodHandles bind on restricted class`() {
    val sandbox = StudioRenderSandbox(null, null, null)
    try {
      sandbox.checkReflectionInvoke(ProcessBuilder::class.java, "start")
      fail("Expected SecurityException")
    } catch (_: SecurityException) {}
  }
}
