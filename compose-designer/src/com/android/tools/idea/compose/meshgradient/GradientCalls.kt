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
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.Function
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Gradient factory calls that can be opened in the gradient editor.
 *
 * @property calleeName the simple name under which Compose declares the factory.
 */
internal enum class GradientCallKind(val calleeName: String) {
  MESH(CLASS_MESH_GRADIENT_PAINTER),
  LINEAR(FUN_LINEAR_GRADIENT),
  HORIZONTAL(FUN_HORIZONTAL_GRADIENT),
  VERTICAL(FUN_VERTICAL_GRADIENT),
  RADIAL(FUN_RADIAL_GRADIENT),
  SWEEP(FUN_SWEEP_GRADIENT);

  /** The fully qualified name used to import the factory explicitly. */
  val importFqName: String
    get() = if (this == MESH) FQN_MESH_GRADIENT_PAINTER else "$FQN_BRUSH_COMPANION.$calleeName"

  /** The localized name of the gradient kind, e.g. "Linear Gradient". */
  val displayName: String
    get() =
      when (this) {
        MESH -> message("gradient.editor.kind.mesh")
        LINEAR -> message("gradient.editor.kind.linear")
        HORIZONTAL -> message("gradient.editor.kind.horizontal")
        VERTICAL -> message("gradient.editor.kind.vertical")
        RADIAL -> message("gradient.editor.kind.radial")
        SWEEP -> message("gradient.editor.kind.sweep")
      }

  /** The tooltip of the gutter icon of this kind of call, e.g. "Edit Linear Gradient". */
  val tooltip: String
    get() = message("gradient.editor.action.tooltip", displayName)

  /**
   * Provides [tooltip]. It is a single instance per kind because the platform compares tooltip providers by equality to decide whether a
   * gutter icon changed between daemon passes.
   */
  val tooltipProvider: Function<PsiElement, String> = Function { tooltip }

  companion object {
    private val byCalleeName = entries.associateBy { it.calleeName }
    private val byImportFqName = entries.associateBy { it.importFqName }

    fun fromCalleeName(name: String): GradientCallKind? = byCalleeName[name]

    fun fromImportFqName(fqName: String): GradientCallKind? = byImportFqName[fqName]
  }
}

/**
 * Returns the gradient factory invoked by this call, or null if the call is not a gradient factory call.
 *
 * Matching is purely syntactic and never resolves references, so it is cheap enough for line marker providers. A call matches when its
 * callee is either:
 * - qualified by `androidx.compose.ui.graphics` (for `MeshGradientPainter`), or by a receiver denoting `Brush` or `Brush.Companion` (for
 *   the `Brush` factories). Such a receiver is the `Brush` FQN, or a name under which `Brush` or `Brush.Companion` is reachable, optionally
 *   followed by `.Companion`.
 * - unqualified and imported explicitly, possibly under an alias.
 *
 * `MeshGradientPainter` and `Brush` are also reachable by their simple names when the file is in the `androidx.compose.ui.graphics`
 * package, or star-imports it. Following Kotlin's precedence, explicit imports of other declarations with the same name shadow them, and so
 * do top-level declarations of the file over a star import; importing them under an alias hides their simple names from the star import.
 *
 * Any other call does not match, including unqualified calls that the imports do not make reachable (for example, calls that cannot be
 * resolved). Since this is an approximation of name resolution, it misses:
 * - shadowing by local declarations, or by declarations in other files of the same package;
 * - calls through implicit receivers, such as `with(Brush) { linearGradient() }`;
 * - receivers whose text differs from the expected one, such as FQNs split across lines or written with backticks.
 */
internal fun KtCallExpression.gradientCallKind(): GradientCallKind? {
  val callee = calleeExpression as? KtNameReferenceExpression ?: return null
  val name = callee.getReferencedName()
  val file = containingFile as? KtFile ?: return null

  val qualified = parent as? KtQualifiedExpression
  if (qualified != null && qualified.selectorExpression == this) {
    if (qualified !is KtDotQualifiedExpression) return null
    val kind = GradientCallKind.fromCalleeName(name) ?: return null
    val receiver = qualified.receiverExpression.text
    val matches =
      if (kind == GradientCallKind.MESH) receiver == FQN_COMPOSE_UI_GRAPHICS
      else receiver.removeSuffix(SUFFIX_COMPANION) in file.gradientImportScope().brushReceivers
    return kind.takeIf { matches }
  }

  return file.gradientImportScope().unqualifiedCallees[name]
}

/**
 * Names under which the gradient factories and `Brush` are reachable in a file, derived from its package, imports and top-level
 * declarations only.
 *
 * @property unqualifiedCallees the names usable as unqualified callees, mapped to the gradient factory they refer to.
 * @property brushReceivers the receiver texts that denote `Brush` or `Brush.Companion`.
 */
private class GradientImportScope(val unqualifiedCallees: Map<String, GradientCallKind>, val brushReceivers: Set<String>)

private fun KtFile.gradientImportScope(): GradientImportScope =
  CachedValuesManager.getCachedValue(this) { CachedValueProvider.Result.create(computeGradientImportScope(this), this) }

private fun computeGradientImportScope(file: KtFile): GradientImportScope {
  val inComposeGraphicsPackage = file.packageFqName.asString() == FQN_COMPOSE_UI_GRAPHICS
  var starImportsComposeGraphics = false
  val unqualifiedCallees = mutableMapOf<String, GradientCallKind>()
  val brushReceivers = mutableSetOf(FQN_BRUSH)
  val otherExplicitNames = mutableSetOf<String>()
  val aliasedFqNames = mutableSetOf<String>()

  for (directive in file.importDirectives) {
    val importedFqName = directive.importedFqName ?: continue
    val fqName = importedFqName.asString()
    if (directive.isAllUnder) {
      if (fqName == FQN_COMPOSE_UI_GRAPHICS) starImportsComposeGraphics = true
      continue
    }
    val aliasName = directive.aliasName
    if (aliasName != null) aliasedFqNames += fqName
    val name = aliasName ?: importedFqName.shortName().asString()
    val kind = GradientCallKind.fromImportFqName(fqName)
    when {
      kind != null -> unqualifiedCallees[name] = kind
      fqName == FQN_BRUSH || fqName == FQN_BRUSH_COMPANION -> brushReceivers += name
      else -> otherExplicitNames += name
    }
  }

  val topLevelNames by lazy(LazyThreadSafetyMode.NONE) { file.declarations.mapNotNullTo(mutableSetOf()) { it.name } }
  fun isReachableBySimpleName(simpleName: String, fqName: String): Boolean =
    when {
      simpleName in otherExplicitNames -> false
      inComposeGraphicsPackage -> true
      else -> starImportsComposeGraphics && fqName !in aliasedFqNames && simpleName !in topLevelNames
    }

  if (isReachableBySimpleName(CLASS_MESH_GRADIENT_PAINTER, FQN_MESH_GRADIENT_PAINTER)) {
    unqualifiedCallees.putIfAbsent(CLASS_MESH_GRADIENT_PAINTER, GradientCallKind.MESH)
  }
  if (isReachableBySimpleName(CLASS_BRUSH, FQN_BRUSH)) brushReceivers += CLASS_BRUSH
  return GradientImportScope(unqualifiedCallees, brushReceivers)
}
