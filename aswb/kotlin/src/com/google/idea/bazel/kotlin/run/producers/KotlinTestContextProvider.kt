/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.idea.bazel.kotlin.run.producers

import com.google.idea.blaze.base.command.BlazeCommandName
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
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.io.File
import java.util.Optional
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

internal class KotlinTestContextProvider : TestContextProvider {

  override fun getTestContext(context: ConfigurationContext): RunConfigurationContext? {
    val psiElement = context.location?.psiElement ?: return null
    return getTestContext(psiElement)
  }

  companion object {
    private fun getTestContext(element: PsiElement): UnifiedRunContext? {
      val testMethod = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, /* strict= */ false)
      val testClass = PsiTreeUtil.getParentOfType(element, KtClass::class.java, /* strict= */ false) ?: return null

      val contextElement = testMethod ?: testClass
      val fqName = contextElement.fqName ?: return null

      val testSize = getTestSize(testClass, testMethod).orElse(null)
      val vf = testClass.containingFile?.virtualFile ?: return null

      val targetSpec = TargetSpecification.PendingResolution(File(vf.path), testSize, RuleType.TEST)

      val className = testClass.fqName?.asString() ?: fqName.asString()
      val methodName = testMethod?.name
      val testSelector = TestSelector(className, methodName, null, TestFilterSyntax.RULES_KOTLIN)

      return UnifiedRunContext(
        contextElement,
        targetSpec,
        TestFilterComponent(listOf(testSelector), false),
        CommandComponent(BlazeCommandName.TEST, emptyList()),
      )
    }

    private fun getTestSize(
      testClass: KtClass,
      testMethod: KtNamedFunction?,
    ): Optional<TestSize> =
      if (testMethod != null) {
        KotlinTestSizeFinder.getTestSize(testMethod)
      } else {
        KotlinTestSizeFinder.getTestSize(testClass)
      }
  }
}
