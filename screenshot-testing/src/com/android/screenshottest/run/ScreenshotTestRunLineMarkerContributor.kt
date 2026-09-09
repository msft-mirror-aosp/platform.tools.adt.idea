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
package com.android.screenshottest.run

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.projectsystem.isScreenshotTestFile
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.base.util.isUnderKotlinSourceRootTypes
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElement

private const val UPDATE_REFERENCE_IMAGES_ACTION_ID = "com.android.screenshottest.action.UpdateReferenceImagesAction"
private const val PREVIEW_TEST_QUALIFIED_NAME = "com.android.tools.screenshot.PreviewTest"
private const val PREVIEW_TEST_SHORT_NAME = "PreviewTest"

class ScreenshotTestRunLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    return null
  }

  override fun getSlowInfo(element: PsiElement): Info? {
    val virtualFile = element.containingFile?.virtualFile ?: return null
    if (!StudioFlags.ENABLE_SCREENSHOT_TESTING.get() || !isScreenshotTestFile(element.project, virtualFile)) return null

    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    // TODO: Enable screenshot tests for release variants as well.
    if (!module.getModuleSystem().isDebuggable) return null

    val declaration = element.getStrictParentOfType<KtNamedDeclaration>()?.takeIf { it.nameIdentifier == element } ?: return null
    val isClass = isValidKtTestClassIdentifier(declaration)
    if (isClass || isValidKtMethodIdentifier(declaration)) {
      val icon = if (isClass) AllIcons.RunConfigurations.TestState.Run_run else AllIcons.RunConfigurations.TestState.Run
      val actions =
        listOfNotNull(
            *ExecutorAction.getActions(),
            ActionManager.getInstance().getAction(UPDATE_REFERENCE_IMAGES_ACTION_ID),
          )
          .toTypedArray()
      return Info(icon, actions) { "Run screenshot tests" }
    }
    return null
  }

  private fun isValidKtTestClassIdentifier(declaration: KtNamedDeclaration): Boolean {
    return declaration is KtClass &&
      !declaration.isInterface() &&
      !declaration.isEnum() &&
      !declaration.isAnnotation() &&
      declaration.isUnderKotlinSourceRootTypes() &&
      declaration.declarations.any { it is KtNamedFunction && isPreviewTestMethod(it) }
  }

  private fun isValidKtMethodIdentifier(declaration: KtNamedDeclaration): Boolean {
    return declaration is KtNamedFunction && declaration.isUnderKotlinSourceRootTypes() && isPreviewTestMethod(declaration)
  }

  private fun isPreviewTestMethod(declaration: KtNamedFunction): Boolean {
    return declaration.annotationEntries.any { annotation ->
      if (annotation.shortName?.asString() != PREVIEW_TEST_SHORT_NAME) return@any false
      (annotation.toUElement() as? UAnnotation)?.javaPsi?.let {
        it.qualifiedName == PREVIEW_TEST_QUALIFIED_NAME
      } ?: false
    }
  }
}
