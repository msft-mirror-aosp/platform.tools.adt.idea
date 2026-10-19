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

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.rendering.security.AllowAllRenderSandbox
import com.android.tools.rendering.security.PreCheckRenderSandboxDelegate
import com.android.tools.rendering.security.RenderSandbox
import com.android.tools.rendering.security.RenderSecurity
import com.android.tools.rendering.security.RenderSecurityException
import com.android.tools.rendering.security.RenderSecurityManager

/** Android Studio specific implementation of [RenderSecurity] that supports [RenderSandbox]. */
@Suppress("VisibleForTests")
class StudioRenderSecurity(val sdkPath: String?, val projectPath: String?, val appTempDir: String?) : RenderSecurity {

  private val sandbox: RenderSandbox?
  private val useSandbox = StudioFlags.RENDER_SANDBOX.get()
  private val activeCredential = ThreadLocal<Any?>()
  private val activationCount = ThreadLocal.withInitial { 0 }
  private val previousSandbox = ThreadLocal<RenderSandbox?>()

  init {
    val baseSandbox = StudioRenderSandbox(sdkPath, projectPath, appTempDir)
    sandbox = PreCheckRenderSandboxDelegate(baseSandbox, { RenderSecurityManager.isEnabled() })
  }

  override fun activate(credential: Any) {
    if (!useSandbox || sandbox == null) return
    val count = activationCount.get()
    if (count == 0) {
      activeCredential.set(credential)
      previousSandbox.set(RenderSandbox.setRenderSandbox(sandbox))
      activationCount.set(1)
      RenderSecurityManager.enterActiveSession(credential)
    } else {
      if (credential !== activeCredential.get()) {
        throw RenderSecurityException.create("Invalid credential")
      }
      activationCount.set(count + 1)
    }
  }

  override fun deactivate(credential: Any) {
    if (!useSandbox || sandbox == null) return
    val count = activationCount.get()
    if (count == 0 || credential !== activeCredential.get()) {
      throw RenderSecurityException.create("Invalid credential")
    }
    if (count > 1) {
      activationCount.set(count - 1)
    } else {
      activationCount.remove()
      activeCredential.remove()
      val prev = previousSandbox.get() ?: AllowAllRenderSandbox
      previousSandbox.remove()
      RenderSandbox.setRenderSandbox(prev)
      RenderSecurityManager.exitActiveSession(credential)
    }
  }
}
