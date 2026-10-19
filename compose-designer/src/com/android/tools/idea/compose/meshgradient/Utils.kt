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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelPropertyFqnNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.isPrivate

internal const val FUN_LINEAR_GRADIENT = "linearGradient"
internal const val FUN_RADIAL_GRADIENT = "radialGradient"
internal const val FUN_SWEEP_GRADIENT = "sweepGradient"
internal const val FUN_HORIZONTAL_GRADIENT = "horizontalGradient"
internal const val FUN_VERTICAL_GRADIENT = "verticalGradient"

internal const val CLASS_BRUSH = "Brush"
internal const val CLASS_MESH_GRADIENT_PAINTER = "MeshGradientPainter"
internal const val SUFFIX_COMPANION = ".Companion"
internal const val FQN_COMPOSE_UI_GRAPHICS = "androidx.compose.ui.graphics"
internal const val FQN_BRUSH = "$FQN_COMPOSE_UI_GRAPHICS.$CLASS_BRUSH"
internal const val FQN_BRUSH_COMPANION = "$FQN_BRUSH$SUFFIX_COMPANION"
internal const val FQN_MESH_GRADIENT_PAINTER = "$FQN_COMPOSE_UI_GRAPHICS.$CLASS_MESH_GRADIENT_PAINTER"

fun formatFloat(number: Float): String {
  if (!number.isFinite()) return number.toString()
  return number.toBigDecimal().setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** Generates a Kotlin `Float` literal for [value], rounded with [formatFloat]. */
internal fun generateFloatSource(value: Float): String =
  when {
    value == Float.POSITIVE_INFINITY -> "Float.POSITIVE_INFINITY"
    value == Float.NEGATIVE_INFINITY -> "Float.NEGATIVE_INFINITY"
    value.isNaN() -> "Float.NaN"
    else -> "${formatFloat(value)}f"
  }

/** Generates the source for [offset], referencing the Compose `Offset` class as [offsetName]. */
internal fun generateOffsetSource(offset: Offset, offsetName: String = "Offset"): String =
  when (offset) {
    Offset.Unspecified -> "$offsetName.Unspecified"
    Offset.Infinite -> "$offsetName.Infinite"
    else -> "$offsetName(${generateFloatSource(offset.x)}, ${generateFloatSource(offset.y)})"
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

/**
 * Returns whether this call constructs `androidx.compose.ui.graphics.MeshGradientPainter`. See [gradientCallKind] for the matching rules.
 */
internal fun KtCallExpression.isValidMeshGradientCall(): Boolean = gradientCallKind() == GradientCallKind.MESH

/**
 * Returns the scope in which top-level properties referenced from [file] are looked up: its module together with the modules it depends on,
 * or the whole project if [file] doesn't belong to a module.
 */
private fun getModuleSearchScope(project: Project, file: KtFile): GlobalSearchScope {
  val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return GlobalSearchScope.projectScope(project)
  return GlobalSearchScope.moduleWithDependenciesScope(module)
}

/**
 * Whether [this] top-level property, declared in another file, may be referenced and statically evaluated. Only stub-backed data is
 * accessed, so this is cheap to check before loading the AST of the candidate.
 *
 * Visibility is approximated: `internal` properties declared in a dependency module are accepted, even though Kotlin only makes them
 * visible within their own module.
 */
private fun KtProperty.isEvaluableFromOtherFile(): Boolean = !isPrivate() && hasInitializer()

/**
 * Finds the non-private top-level properties with an initializer that are visible from [file] through its package, explicit imports and
 * star imports, within its module and the modules it depends on.
 */
@RequiresReadLock
internal fun findImportedAndSamePackageProperties(project: Project, file: KtFile): List<KtProperty> {
  if (DumbService.isDumb(project)) return emptyList()
  val scope = getModuleSearchScope(project, file)
  val result = mutableListOf<KtProperty>()

  fun addPackageProperties(packageFqName: String) {
    for (pkgFile in KotlinExactPackagesIndex.get(packageFqName, project, scope)) {
      ProgressManager.checkCanceled()
      if (pkgFile == file) continue
      pkgFile.declarations.filterIsInstance<KtProperty>().filterTo(result) { it.isEvaluableFromOtherFile() }
    }
  }

  addPackageProperties(file.packageFqName.asString())

  for (directive in file.importDirectives) {
    ProgressManager.checkCanceled()
    val importedFqName = directive.importedFqName?.asString() ?: continue

    if (directive.isAllUnder) {
      addPackageProperties(importedFqName)
    } else {
      KotlinTopLevelPropertyFqnNameIndex.get(importedFqName, project, scope).filterTo(result) { it.isEvaluableFromOtherFile() }
    }
  }

  return result
}

/**
 * Resolves [targetName], as referenced from [file], to a non-private top-level property with an initializer, declared in another file and
 * visible through an explicit import (or import alias), the package of [file] or a star import, within its module and the modules it
 * depends on.
 */
@RequiresReadLock
internal fun resolveImportedOrSamePackageProperty(project: Project, file: KtFile, targetName: String): KtProperty? {
  if (DumbService.isDumb(project)) return null
  val scope = getModuleSearchScope(project, file)

  fun findProperty(fqName: String, predicate: (KtProperty) -> Boolean = { true }): KtProperty? =
    KotlinTopLevelPropertyFqnNameIndex.get(fqName, project, scope).firstOrNull {
      ProgressManager.checkCanceled()
      it.isEvaluableFromOtherFile() && predicate(it)
    }

  // 1. Explicit import (including import alias). Explicit imports take priority over the package and star imports, so the name is not
  // looked up any further even if the imported declaration is not an evaluable property.
  val explicitImport =
    file.importDirectives.firstOrNull {
      !it.isAllUnder && (it.aliasName == targetName || (it.aliasName == null && it.importedName?.asString() == targetName))
    }
  if (explicitImport != null) {
    val fqn = explicitImport.importedFqName?.asString() ?: return null
    return findProperty(fqn)
  }

  // 2. Same-package files in the module and its dependencies
  val currentPkg = file.packageFqName.asString()
  val candidateFqn = if (currentPkg.isEmpty()) targetName else "$currentPkg.$targetName"
  findProperty(candidateFqn) { it.containingKtFile != file }
    ?.let {
      return it
    }

  // 3. Star-imported packages in the module and its dependencies
  for (directive in file.importDirectives) {
    if (!directive.isAllUnder) continue
    val pkgFqn = directive.importedFqName?.asString() ?: continue
    val starCandidateFqn = if (pkgFqn.isEmpty()) targetName else "$pkgFqn.$targetName"
    findProperty(starCandidateFqn)?.let {
      return it
    }
  }

  return null
}

// TODO(b/527852354): replace with gradientCallKind()
fun KtCallExpression.isValidBrushGradientCall(name: String): Boolean {
  val callee = calleeExpression as? KtNameReferenceExpression ?: return false
  val file = containingFile as? KtFile ?: return false
  val methodFqn = "$FQN_BRUSH.$name"
  val companionMethodFqn = "$FQN_BRUSH_COMPANION.$name"

  val parentDot = parent as? KtDotQualifiedExpression
  if (parentDot != null && parentDot.selectorExpression == this) {
    if (callee.text != name) return false
    val receiverText = parentDot.receiverExpression.text.removeSuffix(SUFFIX_COMPANION)
    return receiverText == CLASS_BRUSH ||
      receiverText == FQN_BRUSH ||
      file.importDirectives.any {
        it.aliasName == receiverText && (it.importedFqName?.asString() == FQN_BRUSH || it.importedFqName?.asString() == FQN_BRUSH_COMPANION)
      }
  }

  val explicitImport =
    file.importDirectives.firstOrNull {
      it.importedFqName?.asString() == methodFqn || it.importedFqName?.asString() == companionMethodFqn
    }
  if (explicitImport != null) {
    val expectedName = explicitImport.aliasName ?: name
    return callee.text == expectedName
  }

  if (callee.text != name) return false
  val target = callee.references.firstNotNullOfOrNull { it.resolve() } ?: return false
  val resolvedFqn =
    when (target) {
      is KtNamedFunction -> target.fqName?.asString()
      is PsiMethod -> target.containingClass?.qualifiedName?.let { "$it.${target.name}" }
      else -> null
    }
  return resolvedFqn == companionMethodFqn || resolvedFqn == methodFqn
}
