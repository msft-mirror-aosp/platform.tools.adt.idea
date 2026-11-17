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
package com.android.screenshottest.producers

import com.android.tools.idea.gradle.model.IdeTestSuite
import com.android.tools.idea.gradle.model.isScreenshotTestSuite
import com.android.tools.idea.gradle.model.sourceSetNames
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.model.gradleModuleModel
import com.android.tools.idea.projectsystem.CommonTestType
import com.android.tools.idea.projectsystem.IdeaSourceProvider
import com.android.tools.idea.projectsystem.SourceProviderManager
import com.android.tools.idea.projectsystem.containsFile
import com.android.tools.idea.projectsystem.gradle.getTestSuiteSourceSetName
import com.android.tools.idea.testartifacts.testsuite.runconfiguration.TestSuiteUtils
import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiUtilCore
import java.util.Locale
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.util.AndroidUtils
import org.jetbrains.plugins.gradle.util.GradleUtil
import org.jetbrains.plugins.gradle.util.gradleIdentityPathOrNull

const val DEFAULT_VERIFICATION_TARGET_NAME = "Default"

private const val PREVIEW_TEST_ANNOTATION = "com.android.tools.screenshot.PreviewTest"

val IS_SCREENSHOT_TEST_CONFIGURATION = Key.create<Boolean>("com.android.tools.idea.testartifacts.screenshot.isScreenshotTest")
val IS_SCREENSHOT_UPDATE_CONFIGURATION = Key.create<Boolean>("com.android.tools.idea.testartifacts.screenshot.isScreenshotUpdate")

private fun String.capitalize(): String = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
}

/** Returns the test suites of this model that run Compose Preview screenshot tests and target the selected variant. */
private fun GradleAndroidModel.getScreenshotTestSuites(): List<IdeTestSuite> =
  testSuites.filter { it.isScreenshotTestSuite() && selectedVariantName in it.targetedVariants }

/**
 * Returns the test suites in [screenshotSuites] that [virtualFile] in [module] belongs to: the test suite of [module] if it is a test suite
 * source set module, or else the test suites matched by [TestSuiteUtils.findMatchingTestSuites].
 */
private fun findMatchingScreenshotTestSuites(
  module: Module,
  screenshotSuites: List<IdeTestSuite>,
  virtualFile: VirtualFile?,
): List<IdeTestSuite> {
  if (screenshotSuites.isEmpty()) {
    return emptyList()
  }

  // A test suite source set module only contains the sources, including the generated ones, of its own test suite, which is not
  // necessarily a screenshot test suite.
  val sourceSetName = module.getTestSuiteSourceSetName()
  if (sourceSetName != null) {
    return listOfNotNull(screenshotSuites.find { sourceSetName in it.sourceSetNames })
  }

  if (virtualFile == null) {
    return emptyList()
  }
  return TestSuiteUtils.findMatchingTestSuites(screenshotSuites, virtualFile)
}

/**
 * Checks if the given location belongs to screenshot test source set. It verifies if the virtual file associated with the location either
 * - is contained within the screenshot test or generated screenshot test source roots, or
 * - contains the screenshot test or generated screenshot test source sets, or
 * - belongs to a screenshot test suite of the selected variant.
 *
 * Without the legacy screenshot plugin, screenshot tests can only run through screenshot test suites, so this returns `false` if the module
 * has neither (see [getScreenshotTestTaskNames]).
 *
 * @param location The location of the PSI element to check.
 * @param facet The Android facet associated with the project.
 * @return {@code true} if the location is within a screenshot test source set, {@code false} otherwise.
 */
fun isScreenshotTestSourceSet(location: Location<PsiElement>, facet: AndroidFacet): Boolean {
  val sourceProviders = SourceProviderManager.getInstance(facet)
  val virtualFile = PsiUtilCore.getVirtualFile(location.psiElement) ?: return false
  val androidModel = GradleAndroidModel.get(facet) ?: return false
  val screenshotSuites = androidModel.getScreenshotTestSuites()
  if (!isLegacyScreenshotPluginApplied(facet.module) && screenshotSuites.isEmpty()) {
    return false
  }
  val screenshotSources =
    listOfNotNull(
      sourceProviders.hostTestSources[CommonTestType.SCREENSHOT_TEST],
      sourceProviders.generatedHostTestSources[CommonTestType.SCREENSHOT_TEST],
    )
  if (screenshotSources.any { it.containsFile(virtualFile) || it.containedIn(virtualFile) }) {
    return true
  }
  return findMatchingScreenshotTestSuites(facet.module, screenshotSuites, virtualFile).isNotEmpty()
}

/** Checks if the module has the legacy standalone screenshot plugin applied. */
fun isLegacyScreenshotPluginApplied(module: Module): Boolean {
  val gradleModuleModel = module.gradleModuleModel ?: return false
  return gradleModuleModel.hasComposeScreenshotPlugin
}

/**
 * Retrieves the Gradle task name(s) for screenshot validation.
 *
 * For legacy screenshot plugin consumers, this resolves to the standard `:validate<Variant>ScreenshotTest` task. For AGP native screenshot
 * test suites, this maps the clicked file to its screenshot test suite(s) and constructs the Gradle test suite task of the default
 * verification target for each of them, `:test<Variant><Suite>DefaultTestSuite` (e.g. `:app:testDebugScreenshotTestDefaultTestSuite`).
 *
 * Note: Target resolution is currently hardcoded to the default verification target to prevent accidentally executing companion
 * recording/update tasks from IDE run configurations.
 *
 * @param context The configuration context.
 * @return A list of Strings containing the screenshot test task name(s), or `null` if required information is missing or, without the
 *   legacy screenshot plugin, if the model has no screenshot test suite for the selected variant.
 */
fun getScreenshotTestTaskNames(context: ConfigurationContext): List<String>? {
  val myModule = AndroidUtils.getAndroidModule(context) ?: return null
  val facet = AndroidFacet.getInstance(myModule) ?: return null
  val androidModel = GradleAndroidModel.get(facet) ?: return null
  val modulePath = GradleUtil.findGradleModuleData(myModule)?.data?.gradleIdentityPathOrNull?.trimEnd(':') ?: return null

  if (isLegacyScreenshotPluginApplied(myModule)) {
    val taskName = androidModel.getGradleScreenshotTestTaskNameForSelectedVariant("validate")
    return listOf("$modulePath:$taskName")
  } else {
    val variantName = androidModel.selectedVariantName.capitalize()
    val screenshotSuites = androidModel.getScreenshotTestSuites()
    // Without the legacy screenshot plugin, screenshot tests can only run through screenshot test suites.
    if (screenshotSuites.isEmpty()) {
      return null
    }
    val virtualFile = context.location?.psiElement?.let { PsiUtilCore.getVirtualFile(it) }
    val matchingSuites = findMatchingScreenshotTestSuites(myModule, screenshotSuites, virtualFile)
    // If the location does not match a screenshot test suite, run all of them.
    val suiteNames = matchingSuites.ifEmpty { screenshotSuites }.map { it.name }

    // TODO(b/530362351): The target name is currently hardcoded to "Default" to ensure we only
    // execute
    // verification tasks and never accidentally trigger companion recording/update targets (e.g.
    // "defaultUpdate",
    // which overwrites reference screenshots). Once the AGP test suite model exposes whether a
    // target is a
    // recording vs. verification target, dynamically resolve the target name from
    // IdeTestSuiteComponent
    // while filtering out recording targets.
    return suiteNames.map { suiteName ->
      val capitalizedSuiteName = suiteName.capitalize()
      "$modulePath:test$variantName$capitalizedSuiteName${DEFAULT_VERIFICATION_TARGET_NAME}TestSuite"
    }
  }
}

/**
 * Checks if a given class declaration contains any methods annotated with the Compose Preview annotation or compose multi preview
 * annotation
 *
 * @param psiClass The PSI class to check.
 * @param visitedAnnotations A mutable map to track visited annotations to avoid infinite recursion.
 * @return {@code true} if the class has at least one preview-annotated method, {@code false} otherwise.
 */
fun isClassDeclarationWithPreviewTestAnnotatedMethods(
  psiClass: PsiClass,
  visitedAnnotations: MutableMap<String, Boolean> = mutableMapOf(),
): Boolean {
  return psiClass.methods.any { isMethodDeclarationPreviewTestAnnotated(it, visitedAnnotations) }
}

/**
 * Checks if a given method declaration is annotated with the Compose Preview annotation or any multi preview annotation.
 *
 * @param psiMethod The PSI method to check.
 * @param visitedAnnotations A mutable map to track visited annotations to avoid infinite recursion.
 * @return {@code true} if the method is preview annotated, {@code false} otherwise.
 */
fun isMethodDeclarationPreviewTestAnnotated(
  psiMethod: PsiMethod,
  visitedAnnotations: MutableMap<String, Boolean> = mutableMapOf(),
): Boolean {
  return psiMethod.annotations.any { it.qualifiedName == PREVIEW_TEST_ANNOTATION }
}

private fun IdeaSourceProvider.containedIn(targetFolder: VirtualFile): Boolean {
  return manifestFileUrls.any { manifestFileUrl ->
    VfsUtilCore.isEqualOrAncestor(targetFolder.url, manifestFileUrl)
  } ||
    allSourceFolderUrls().any { sourceFolderUrl ->
      VfsUtilCore.isEqualOrAncestor(targetFolder.url, sourceFolderUrl)
    }
}

private fun IdeaSourceProvider.allSourceFolderUrls(): Sequence<String> {
  return arrayOf(
      javaDirectoryUrls,
      resDirectoryUrls,
      aidlDirectoryUrls,
      renderscriptDirectoryUrls,
      assetsDirectoryUrls,
      jniLibsDirectoryUrls,
    )
    .asSequence()
    .flatten()
}
