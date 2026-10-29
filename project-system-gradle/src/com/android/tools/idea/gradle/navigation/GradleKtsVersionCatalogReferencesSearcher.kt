// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.android.tools.idea.gradle.navigation

import com.android.tools.idea.projectsystem.gradle.isHolderModule
import com.intellij.lang.java.beans.PropertyKind
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.RequestResultProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.parentOfType
import com.intellij.util.Processor
import org.jetbrains.kotlin.idea.core.script.v1.KotlinScriptSearchScope
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.plugins.gradle.service.resolve.getVersionCatalogFiles
import org.jetbrains.plugins.gradle.service.resolve.isInVersionCatalog
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.toml.lang.psi.TomlElement
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue

class GradleKtsVersionCatalogReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(false) {

  override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
    val element = queryParameters.elementToSearch
    if (element !is TomlKeySegment) {
      return
    }

    val (keyValue, name, searchScope) =
      runReadAction {
        if (!isInVersionCatalogFile(element)) return@runReadAction null
        val keyValue = element.parentOfType<TomlKeyValue>() ?: return@runReadAction null
        val name = element.name ?: return@runReadAction null
        val project = element.project
        val ktsScope = queryParameters.effectiveSearchScope.intersectWith(GradleKtsBuildscriptSearchScope(project))
        val scope =
          when (ktsScope) {
            is GlobalSearchScope -> KotlinScriptSearchScope(project, ktsScope)
            else -> ktsScope
          }
        Triple(keyValue, name, scope)
      } ?: return
    val nameParts = name.getVersionCatalogParts()
    val identifier = nameParts.lastOrNull() ?: return
    val getter = PropertyUtilBase.getAccessorName(identifier, PropertyKind.GETTER)
    val searchContext = UsageSearchContext.IN_CODE
    val processor = MyProcessor(keyValue, nameParts)
    val search: (String) -> Unit = { queryParameters.optimizer.searchWord(it, searchScope, searchContext, true, element, processor) }
    search(identifier)
    search(getter)
  }

  class MyProcessor(private val searchedElement: TomlKeyValue, private val oldNameParts: List<String>) : RequestResultProcessor() {

    override fun processTextOccurrence(element: PsiElement, offsetInElement: Int, consumer: Processor<in PsiReference>): Boolean {
      val parent = element.parentOfType<KtNameReferenceExpression>() ?: return true
      val handler = VersionCatalogGoToDeclarationHandler()
      val gotoDeclarationTargets = handler.getGotoDeclarationTargets(element, 0, null)
      if (gotoDeclarationTargets?.singleOrNull() == searchedElement) {
        return consumer.process(KtsVersionCatalogReference(parent, oldNameParts, searchedElement))
      }
      return true
    }
  }

  private class KtsVersionCatalogReference(
    refExpr: KtNameReferenceExpression,
    val oldNameParts: List<String>,
    val searchedElement: TomlKeyValue,
  ) : PsiReferenceBase<KtNameReferenceExpression>(refExpr) {

    override fun resolve(): PsiElement {
      return searchedElement
    }

    override fun handleElementRename(newElementName: String): PsiElement {
      val parts = newElementName.getVersionCatalogParts()
      val elementToReplace = element.parent

      if (elementToReplace is KtDotQualifiedExpression) {
        val (catalogName, tableName) = getCatalogDotExpression(elementToReplace)
        catalogName?.let {
          val newElementText =
            StringBuilder()
              .append(catalogName.text)
              .append(".")
              .apply {
                when (val table = tableName?.text) {
                  "plugins",
                  "bundles",
                  "versions" -> append(table).append(".")
                }
              }
              .append(parts.joinToString("."))
              .toString()
          val newElement = KtPsiFactory(element.project).createExpression(newElementText)
          return elementToReplace.replace(newElement)
        }
      }

      return elementToReplace
    }

    private fun getCatalogDotExpression(element: KtDotQualifiedExpression): Pair<KtNameReferenceExpression?, KtNameReferenceExpression?> {
      var newElement = element
      while (newElement.firstChild is KtDotQualifiedExpression) {
        newElement = (newElement.firstChild as KtDotQualifiedExpression)
      }
      return newElement.firstChild.let { it as? KtNameReferenceExpression to it?.nextSibling?.nextSibling as? KtNameReferenceExpression }
    }
  }

  private class GradleKtsBuildscriptSearchScope(project: Project) : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean = file.name.endsWith(GradleConstants.KOTLIN_DSL_SCRIPT_EXTENSION)

    override fun isSearchInModuleContent(aModule: Module): Boolean = aModule.isHolderModule()

    override fun isSearchInLibraries(): Boolean = false
  }
}

private fun isInVersionCatalogFile(element: TomlElement): Boolean {
  val containingFile = element.containingFile?.virtualFile ?: return false
  return isInVersionCatalog(element) || containingFile in getVersionCatalogFiles(element.project).values
}

private fun String.getVersionCatalogParts(): List<String> = split("_", "-")
