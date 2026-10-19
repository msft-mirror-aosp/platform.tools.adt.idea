/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.bazel.java.run.producers

import com.google.idea.blaze.base.command.BlazeCommandName
import com.google.idea.blaze.base.command.BlazeFlags
import com.google.idea.blaze.base.dependencies.TestSize
import com.google.idea.blaze.base.model.primitives.RuleType
import com.google.idea.blaze.base.run.producers.CommandComponent
import com.google.idea.blaze.base.run.producers.RunConfigurationContext
import com.google.idea.blaze.base.run.producers.TargetSpecification
import com.google.idea.blaze.base.run.producers.TestContextProvider
import com.google.idea.blaze.base.run.producers.TestFilterComponent
import com.google.idea.blaze.base.run.producers.TestFilterSyntax
import com.google.idea.blaze.base.run.producers.TestSelector
import com.google.idea.blaze.base.run.producers.UnifiedRunContext
import com.intellij.execution.JavaExecutionUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import java.io.File

/** Currently handles only non-abstract java test methods / single test class. */
internal class JavaTestContextProvider : TestContextProvider {

  override fun getTestContext(context: ConfigurationContext): RunConfigurationContext? {
    val location = context.location
    val locationPsi = location?.psiElement
    if (locationPsi != null) {
      val file = locationPsi.containingFile
      if (file != null && file.name.endsWith(".kt")) {
        return null
      }
    }
    val selectedMethods = TestMethodSelectionUtil.getSelectedMethods(context)
    if (!selectedMethods.isNullOrEmpty()) {
      if (isKotlin(selectedMethods[0].containingClass)) {
        return null
      }
      return fromSelectedMethods(selectedMethods)
    }
    val locationElement = context.psiLocation
    if (locationElement is PsiClass) {
      if (!isKotlin(locationElement)) {
        return fromClass(locationElement)
      }
    }
    if (locationElement is PsiMethod) {
      val testClass = locationElement.containingClass
      if (testClass != null && !isKotlin(testClass)) {
        return fromClassAndMethod(testClass, locationElement)
      }
    }
    val method = getTestMethod(context)
    if (method != null) {
      val testClass = method.containingClass
      if (testClass != null && !isKotlin(testClass)) {
        return fromClassAndMethod(testClass, method)
      }
    }
    val testClass = getSelectedTestClass(context)
    if (testClass == null || isKotlin(testClass)) {
      return null
    }
    return fromClass(testClass)
  }

  companion object {
    @JvmStatic
    fun fromClassAndMethod(testClass: PsiClass, method: PsiMethod?): UnifiedRunContext? {
      if (method != null) {
        return fromClassAndMethods(testClass, listOf(method))
      }
      return fromClass(testClass)
    }

    private fun getTestMethod(context: ConfigurationContext): PsiMethod? {
      val location = context.location ?: return null
      val methodLocation = ProducerUtils.getTestMethod(location)
      return methodLocation?.psiElement
    }

    private fun isKotlin(psiClass: PsiClass?): Boolean {
      if (psiClass == null) {
        return false
      }
      val file = psiClass.containingFile
      return file != null && file.name.endsWith(".kt")
    }

    private fun fromClass(testClass: PsiClass): UnifiedRunContext? {
      val qualifiedName = testClass.qualifiedName ?: return null
      val innerClasses = ProducerUtils.getInnerTestClasses(testClass)
      val allClasses = HashSet(innerClasses).apply { add(testClass) }

      val version =
        if (allClasses.any(ProducerUtils::isJUnit4Class)) {
          TestFilterSyntax.JUNIT_4
        } else {
          TestFilterSyntax.JUNIT_3
        }

      val testSelectors = ArrayList<TestSelector>()
      testSelectors.add(TestSelector.Member(qualifiedName, null, null, version))
      for (inner in innerClasses) {
        val innerQualifiedName = inner.qualifiedName
        if (innerQualifiedName != null) {
          testSelectors.add(TestSelector.Member(innerQualifiedName, null, null, version))
        }
      }

      val testSize = TestSizeFinder.getTestSize(testClass)
      val targetSpec = getTargetSpecification(testClass, testSize) ?: return null

      return UnifiedRunContext(
        testClass,
        targetSpec,
        TestFilterComponent(testSelectors, false),
        CommandComponent(BlazeCommandName.TEST, emptyList()),
      )
    }

    private fun fromSelectedMethods(selectedMethods: MutableList<PsiMethod>): UnifiedRunContext? {
      selectedMethods.sortBy { it.name }
      val firstMethod = selectedMethods[0]
      val containingClass = firstMethod.containingClass ?: return null
      for (method in selectedMethods) {
        if (containingClass != method.containingClass) {
          return null
        }
      }
      return fromClassAndMethods(containingClass, selectedMethods)
    }

    private fun fromClassAndMethods(
      containingClass: PsiClass,
      selectedMethods: List<PsiMethod>,
    ): UnifiedRunContext? {
      val qualifiedName = containingClass.qualifiedName ?: return null
      val firstMethod = selectedMethods[0]
      val version =
        if (ProducerUtils.isJUnit4Class(containingClass)) {
          TestFilterSyntax.JUNIT_4
        } else {
          TestFilterSyntax.JUNIT_3
        }

      val testSelectors = selectedMethods.map { m -> TestSelector.Member(qualifiedName, m.name, null, version) }

      val testSize = TestSizeFinder.getTestSize(firstMethod)
      val targetSpec = getTargetSpecification(containingClass, testSize) ?: return null

      val extraFlags =
        if (selectedMethods.size == 1) {
          listOf(BlazeFlags.DISABLE_TEST_SHARDING)
        } else {
          emptyList()
        }

      return UnifiedRunContext(
        firstMethod,
        targetSpec,
        TestFilterComponent(testSelectors, false),
        CommandComponent(BlazeCommandName.TEST, extraFlags),
      )
    }

    /**
     * Constructs a deferred [TargetSpecification.PendingResolution] for the given test class.
     *
     * Target resolution across reverse dependencies and heuristics is deferred to Stage 2.5 in background coroutines to ensure Stage 1
     * context extraction does not block the UI thread.
     */
    private fun getTargetSpecification(
      testClass: PsiClass,
      testSize: TestSize?,
    ): TargetSpecification? {
      val vf = testClass.containingFile?.virtualFile ?: return null
      return TargetSpecification.PendingResolution(File(vf.path), testSize, RuleType.TEST)
    }

    private fun getSelectedTestClass(context: ConfigurationContext): PsiClass? {
      var location = context.location ?: return null
      location = JavaExecutionUtil.stepIntoSingleClass(location) ?: return null
      if (JUnitConfigurationUtil.isMultipleElementsSelected(context)) {
        return null
      }
      return ProducerUtils.getTestClass(location)
    }
  }
}
