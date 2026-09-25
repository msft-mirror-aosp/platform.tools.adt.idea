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

import androidx.compose.ui.graphics.Color
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.parentOfType
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

fun formatFloat(number: Float): String {
  return number.toBigDecimal().setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

fun Color.toHexStringNoHash(includeAlpha: Boolean = false): String {
  val r = (red * 255f).roundToInt()
  val g = (green * 255f).roundToInt()
  val b = (blue * 255f).roundToInt()
  return if (includeAlpha) {
    val a = (alpha * 255f).roundToInt()
    String.format(Locale.US, "%02X%02X%02X%02X", a, r, g, b)
  } else {
    String.format(Locale.US, "%02X%02X%02X", r, g, b)
  }
}

fun Color.toComposeHexLiteral(): String {
  val r = (red * 255f).roundToInt()
  val g = (green * 255f).roundToInt()
  val b = (blue * 255f).roundToInt()
  val a = (alpha * 255f).roundToInt()
  return String.format(Locale.US, "0x%02X%02X%02X%02X", a, r, g, b)
}

fun KtCallExpression.isValidMeshGradientCall(expectedFqn: String = "androidx.compose.ui.graphics.MeshGradientPainter"): Boolean {
  val callee = calleeExpression as? KtNameReferenceExpression ?: return false
  val file = containingFile as? KtFile ?: return false
  val shortName = expectedFqn.substringAfterLast('.')
  val packagePrefix = expectedFqn.substringBeforeLast('.', "")

  val qualifiedParent = parent as? KtQualifiedExpression
  if (qualifiedParent != null && qualifiedParent.selectorExpression == this) {
    return callee.text == shortName && qualifiedParent.receiverExpression.text == packagePrefix
  }

  // 1. First Check: Try to find an explicit import in the file
  val explicitImport = file.importDirectives.firstOrNull { it.importedFqName?.asString() == expectedFqn }
  if (explicitImport != null) {
    val expectedName = explicitImport.aliasName ?: shortName
    return callee.text == expectedName
  }

  // 2. Resolve reference once (handles star-imports and test sandboxes)
  val target = callee.references.firstNotNullOfOrNull { it.resolve() }
  if (target == null) {
    return callee.text == shortName
  }

  val resolvedFqn =
    when (target) {
      is KtConstructor<*> -> target.parentOfType<KtClass>()?.fqName?.asString()
      is KtClass -> target.fqName?.asString()
      is PsiClass -> target.qualifiedName
      is PsiMethod -> if (target.isConstructor) target.containingClass?.qualifiedName else null
      else -> null
    }
  return resolvedFqn == expectedFqn
}
