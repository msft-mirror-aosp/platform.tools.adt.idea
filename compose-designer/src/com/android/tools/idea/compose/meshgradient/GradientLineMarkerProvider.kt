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

import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.flags.StudioFlags
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.NavigateAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.MarkupEditorFilter
import com.intellij.openapi.editor.markup.MarkupEditorFilterFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import icons.StudioIcons
import javax.swing.Icon
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

private val GRADIENT_GUTTER_ICON: Icon = StudioIcons.GutterIcons.PREVIEW_SETTINGS

/**
 * Adds a gutter icon to the callee of every gradient factory call (see [gradientCallKind]) that opens the gradient editor.
 *
 * The check runs for every identifier of the file on each daemon pass, so it relies on the syntactic, cached [gradientCallKind] and never
 * resolves references.
 */
internal class GradientLineMarkerProvider : LineMarkerProviderDescriptor() {

  override fun getName(): String = message("gradient.editor.annotator.name")

  override fun getIcon(): Icon = GRADIENT_GUTTER_ICON

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element !is LeafPsiElement) return null
    if (!StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR.get()) return null
    if (element.tokenType != KtTokens.IDENTIFIER) return null

    val nameRef = element.parent as? KtNameReferenceExpression ?: return null
    val callExpression = nameRef.parent as? KtCallExpression ?: return null
    if (callExpression.calleeExpression != nameRef) return null

    val kind = callExpression.gradientCallKind() ?: return null

    val info = GradientLineMarkerInfo(element, kind)
    NavigateAction.setNavigateAction(info, message("gradient.editor.action.title"), null, icon)
    return info
  }
}

/** The gutter icon of a gradient factory call of the given [kind], anchored on the callee identifier [element]. */
internal class GradientLineMarkerInfo(element: PsiElement, kind: GradientCallKind) :
  LineMarkerInfo<PsiElement>(
    element,
    element.textRange,
    GRADIENT_GUTTER_ICON,
    kind.tooltipProvider,
    { _, elt ->
      val validCall =
        (elt.parent?.parent as? KtCallExpression)?.takeIf { it.calleeExpression == elt.parent && it.gradientCallKind() != null }
      val file = validCall?.containingFile as? KtFile
      if (validCall != null && file != null) {
        val project = elt.project
        val dialog = GradientEditorDialog(project, file, validCall)
        dialog.show()
      }
    },
    GutterIconRenderer.Alignment.LEFT,
    { kind.tooltip },
  ) {
  override fun getEditorFilter(): MarkupEditorFilter = MarkupEditorFilterFactory.createIsNotDiffFilter()
}
