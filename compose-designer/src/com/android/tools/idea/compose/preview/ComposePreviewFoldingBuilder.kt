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
package com.android.tools.idea.compose.preview

import com.android.tools.compose.isComposableFunction
import com.android.tools.idea.projectsystem.getModuleSystem
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElement

private fun KtNamedFunction.isComposablePreviewFunction(): Boolean {
  return isComposableFunction() &&
    annotationEntries.any { entry ->
      val uAnnotation = (entry.toUElement() as? UAnnotation) ?: return@any false
      uAnnotation.isPreviewAnnotation() || uAnnotation.isMultiPreviewAnnotation()
    }
}

/** Adds folding regions for Compose @Preview function bodies. */
class ComposePreviewFoldingBuilder : FoldingBuilderEx() {
  override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
    if (quick || (root !is KtFile) || (root.getModuleSystem()?.usesCompose != true)) {
      return FoldingDescriptor.EMPTY_ARRAY
    }

    val descriptors = mutableListOf<FoldingDescriptor>()
    root.accept(
      object : KtTreeVisitorVoid() {
        override fun visitNamedFunction(function: KtNamedFunction) {
          if (function.isComposablePreviewFunction()) {
            function.bodyBlockExpression?.let { bodyBlock ->
              val range = bodyBlock.textRange
              if (document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)) {
                descriptors.add(FoldingDescriptor(bodyBlock.node, range))
              }
            }
          }
          // Do not call super to avoid recursing into function bodies, since @Preview functions
          // cannot be nested inside other functions.
        }
      }
    )

    return descriptors.toTypedArray()
  }

  override fun getPlaceholderText(node: ASTNode): String = "{...}"

  override fun isCollapsedByDefault(node: ASTNode): Boolean {
    return (node.elementType == KtNodeTypes.BLOCK) && ComposePreviewSettings.getInstance().state.isFoldComposePreviewFunctions
  }
}
