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
package org.jetbrains.android.dom.structure.manifest

import com.android.tools.idea.projectsystem.AndroidIconProviderProjectToken
import com.android.tools.idea.projectsystem.AndroidProjectSystem
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.Module
import com.intellij.psi.PsiElement
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.util.ui.EmptyIcon
import icons.StudioIcons
import javax.swing.Icon
import org.jetbrains.android.AndroidTestCase
import org.jetbrains.android.dom.manifest.Application
import org.jetbrains.android.dom.manifest.Manifest

class ApplicationPresentationProviderTest : AndroidTestCase() {
  private val provider = ApplicationPresentationProvider()

  private fun getApplication(): Application = runReadAction {
    requireNotNull(Manifest.getMainManifest(myFacet)?.application) { "No <application> element in the manifest" }
  }

  private fun setIconTokens(vararg tokens: AndroidIconProviderProjectToken<AndroidProjectSystem>) {
    ExtensionTestUtil.maskExtensions(AndroidIconProviderProjectToken.EP_NAME, tokens.toList(), testRootDisposable)
  }

  fun testGetIcon_applicableToken_usesTokenModuleIcon() {
    val moduleIcon = EmptyIcon.create(7)
    val requestedModules = mutableListOf<Module>()
    setIconTokens(
      FakeIconToken(applicable = false, moduleIcon = EmptyIcon.create(3)),
      FakeIconToken(applicable = true, moduleIcon = moduleIcon, requestedModules = requestedModules),
    )

    val icon = runReadAction { provider.getIcon(getApplication()) }

    assertThat(icon).isSameAs(moduleIcon)
    assertThat(requestedModules).containsExactly(myModule)
  }

  fun testGetIcon_noApplicableToken_usesDefaultIcon() {
    setIconTokens(FakeIconToken(applicable = false, moduleIcon = EmptyIcon.create(3)))

    val icon = runReadAction { provider.getIcon(getApplication()) }

    assertThat(icon).isSameAs(StudioIcons.Shell.Filetree.ANDROID_MODULE)
  }

  private class FakeIconToken(
    private val applicable: Boolean,
    private val moduleIcon: Icon?,
    private val requestedModules: MutableList<Module> = mutableListOf(),
  ) : AndroidIconProviderProjectToken<AndroidProjectSystem> {
    override fun isApplicable(projectSystem: AndroidProjectSystem): Boolean = applicable

    override fun getIcon(projectSystem: AndroidProjectSystem, element: PsiElement): Icon? = null

    override fun getModuleIcon(projectSystem: AndroidProjectSystem, module: Module): Icon? {
      requestedModules.add(module)
      return moduleIcon
    }
  }
}
