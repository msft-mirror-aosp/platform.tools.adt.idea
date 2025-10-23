/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.glassespairing

import com.android.annotations.concurrency.UiThread
import com.android.tools.idea.AndroidStartupActivity
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Class to manage Glasses AVDs pairing with Phone AVDs. */
@Service(Service.Level.APP)
class GlassesPairingManager {
  class GlassesPairingManagerStartupActivity : AndroidStartupActivity {
    @UiThread
    override fun runActivity(project: Project, disposable: Disposable) {}
  }

  companion object {
    @JvmStatic
    fun getInstance(): GlassesPairingManager = service()
  }
}