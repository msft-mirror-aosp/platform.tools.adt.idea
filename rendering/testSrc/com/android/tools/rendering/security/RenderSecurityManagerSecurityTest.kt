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
package com.android.tools.rendering.security

import com.intellij.mock.MockApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.util.Disposer
import java.io.File
import java.io.FilePermission
import java.net.NetPermission
import java.security.SecurityPermission
import java.sql.SQLPermission
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RenderSecurityManagerSecurityTest {
  private val myCredential = Any()
  private val disposable: Disposable = Disposer.newDisposable()

  @Before
  fun setUp() {
    val app = MockApplication(disposable)
    app.extensionArea.registerExtensionPoint(
      EP_NAME.name,
      RenderSecurityManagerOverrides::class.java.name,
      ExtensionPoint.Kind.INTERFACE,
      false,
    )
  }

  @After
  fun tearDown() {
    Disposer.dispose(disposable)
  }

  private fun createManager(isRenderThread: Boolean = true): RenderSecurityManager {
    val manager = RenderSecurityManager.createForTests(null, null, false) { isRenderThread }
    manager.setUseSandbox(true)
    return manager
  }

  @Test
  fun testSEnabledDefaultAndExitSafeRegionCannotDisable() {
    assertTrue(RenderSecurityManager.isEnabled())

    // Attempting to exit safe region with false should NEVER disable security
    RenderSecurityManager.exitSafeRegion(false)
    assertTrue(RenderSecurityManager.isEnabled())

    // Attempting to enter safe region with null credential must NOT disable security
    val token = RenderSecurityManager.enterSafeRegion(null)
    assertTrue(RenderSecurityManager.isEnabled())
    RenderSecurityManager.exitSafeRegion(token)
    assertTrue(RenderSecurityManager.isEnabled())
  }

  @Test
  fun testSafeRegionWithValidCredential() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertTrue(RenderSecurityManager.isEnabled())

      val token = RenderSecurityManager.enterSafeRegion(myCredential)
      assertFalse(RenderSecurityManager.isEnabled())

      RenderSecurityManager.exitSafeRegion(token)
      assertTrue(RenderSecurityManager.isEnabled())
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testDisableSandboxBlockedWhenSecurityManagerPresent() {
    // When inactive, disableSandbox succeeds
    RenderSecurityManager.disableSandbox()
    assertFalse(RenderSecurityManager.isEnabled())
    RenderSecurityManager.setEnabledForTest(true)

    // When active, disableSandbox throws RenderSecurityException
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        RenderSecurityManager.disableSandbox()
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testMultipleManagersIndependentCredentials() {
    val manager1 = createManager()
    val manager2 = createManager()
    val cred1 = Any()
    val cred2 = Any()

    manager1.setActive(true, cred1)
    manager2.setActive(true, cred2)
    try {
      // Deactivating manager2 with cred2 should succeed and not affect manager1
      manager2.setActive(false, cred2)

      // Manager1 should still be able to deactivate with its own credential
      manager1.setActive(false, cred1)
    } finally {
      try {
        manager1.dispose(cred1)
      } catch (_: Exception) {}
      try {
        manager2.dispose(cred2)
      } catch (_: Exception) {}
    }
  }

  @Test
  fun testSetAppTempDirValidation() {
    val manager = createManager()

    // Root directory must be rejected
    assertThrows(IllegalArgumentException::class.java) {
      manager.setAppTempDir("/")
    }

    // Empty path must be rejected
    assertThrows(IllegalArgumentException::class.java) {
      manager.setAppTempDir("")
    }
    assertThrows(IllegalArgumentException::class.java) {
      manager.setAppTempDir("   ")
    }

    // Valid subfolder should be accepted when not active
    manager.setAppTempDir("/tmp/my_app_temp")

    // Cannot change appTempDir when active
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        manager.setAppTempDir("/tmp/another_temp")
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testNetPermissionBlocked() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(NetPermission("getProxySelector"))
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(NetPermission("requestPasswordAuthentication"))
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(NetPermission("setProxySelector"))
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testSQLPermissionBlocked() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(SQLPermission("setLog"))
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(SQLPermission("deregisterDriver"))
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testSecurityPermissionBlocked() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(SecurityPermission("setPolicy"))
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(SecurityPermission("getProperty.auth.login.defaultCallbackHandler"))
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testEnvAndIOBlocked() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(RuntimePermission("getenv.*"))
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(RuntimePermission("getenv.SECRET_KEY"))
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(RuntimePermission("setIO"))
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testLogDirNotWritable() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      val logPath =
        try {
          PathManager.getLogPath()
        } catch (_: Throwable) {
          "/idea/log"
        }
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(FilePermission(logPath + File.separator + "freeze.duration", "write"))
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testCheckPropertiesAccessRejectsUntrustedCaller() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPropertiesAccess()
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testNestedActivation() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    // Nested activate with same credential succeeds
    manager.setActive(true, myCredential)

    // First deactivate decrements count, manager remains active
    manager.setActive(false, myCredential)
    assertThrows(RenderSecurityException::class.java) {
      manager.checkPermission(NetPermission("getProxySelector"))
    }

    // Second deactivate leaves manager inactive
    manager.setActive(false, myCredential)
    // When inactive on render thread, checkPermission does not throw
    manager.checkPermission(NetPermission("getProxySelector"))
  }

  @Test
  fun testActivationRequiresMatchingCredential() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      val wrongCredential = Any()
      assertThrows(RenderSecurityException::class.java) {
        manager.setActive(true, wrongCredential)
      }
      assertThrows(RenderSecurityException::class.java) {
        manager.setActive(false, wrongCredential)
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testSetAppTempDirPrefixConfusionBlocked() {
    val manager = createManager()
    val appTemp = File("/custom_app_temp").canonicalPath
    manager.setAppTempDir(appTemp)

    manager.setActive(true, myCredential)
    try {
      // Files inside the app temp directory are allowed to write
      manager.checkPermission(FilePermission(File(appTemp, "valid.txt").canonicalPath, "write"))

      // Sibling directory with the same prefix must NOT be allowed
      val siblingPath = File("/custom_app_temp_exploit/secret.txt").canonicalPath
      assertThrows(RenderSecurityException::class.java) {
        manager.checkPermission(FilePermission(siblingPath, "write"))
      }
    } finally {
      manager.dispose(myCredential)
    }
  }

  @Test
  fun testActionsNullSafe() {
    val manager = createManager()
    manager.setActive(true, myCredential)
    try {
      // Custom permission with null actions should not throw NullPointerException
      val nullActionsPermission =
        object : java.security.Permission("testPermission") {
          override fun implies(p: java.security.Permission?): Boolean = false

          override fun equals(other: Any?): Boolean = other === this

          override fun hashCode(): Int = 0

          override fun getActions(): String? = null
        }
      manager.checkPermission(nullActionsPermission)
    } finally {
      manager.dispose(myCredential)
    }
  }
}
