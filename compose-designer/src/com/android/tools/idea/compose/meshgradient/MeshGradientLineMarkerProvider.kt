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
package com.android.tools.idea.compose.meshgradient

import com.android.tools.idea.flags.StudioFlags
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.NavigateAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.MarkupEditorFilter
import com.intellij.openapi.editor.markup.MarkupEditorFilterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.LeafPsiElement
import icons.StudioIcons
import javax.swing.Icon
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class MeshGradientLineMarkerProvider : LineMarkerProviderDescriptor() {

  override fun getName(): String = "Mesh Gradient Editor"

  override fun getIcon(): Icon = StudioIcons.GutterIcons.PREVIEW_SETTINGS

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (!StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR.get()) return null
    if (element !is LeafPsiElement) return null
    if (element.tokenType != KtTokens.IDENTIFIER) return null
    if (!element.isValid) return null
    if (!element.isPhysical) return null

    val nameRef = element.parent as? KtNameReferenceExpression ?: return null
    val callExpression = nameRef.parent as? KtCallExpression ?: return null
    if (callExpression.calleeExpression != nameRef) return null

    val file = element.containingFile as? KtFile ?: return null
    if (
      element.text != "MeshGradientPainter" &&
        file.importDirectives.none {
          it.aliasName == element.text && it.importedFqName?.asString() == "androidx.compose.ui.graphics.MeshGradientPainter"
        }
    ) {
      return null
    }

    if (!callExpression.isValidMeshGradientCall()) return null

    val callPointer = SmartPointerManager.getInstance(element.project).createSmartPsiElementPointer(callExpression)
    val info = createInfo(element, element.textRange, element.project, callPointer)
    NavigateAction.setNavigateAction(info, "Edit Mesh Gradient", null, icon)
    return info
  }

  private fun createInfo(
    element: PsiElement,
    textRange: TextRange,
    project: Project,
    callPointer: SmartPsiElementPointer<KtCallExpression>,
  ): LineMarkerInfo<PsiElement> {
    return object :
      LineMarkerInfo<PsiElement>(
        element,
        textRange,
        icon,
        { "Edit Mesh Gradient" },
        { _, _ ->
          val validCall = callPointer.element?.takeIf { it.isValid }
          val file = validCall?.containingFile as? KtFile
          if (validCall != null && file != null) {
            val dialog = MeshGradientEditorDialog(project, file, validCall)
            dialog.show()
          }
        },
        GutterIconRenderer.Alignment.LEFT,
        { "Edit Mesh Gradient" },
      ) {
      override fun getEditorFilter(): MarkupEditorFilter {
        return MarkupEditorFilterFactory.createIsNotDiffFilter()
      }
    }
  }
}
