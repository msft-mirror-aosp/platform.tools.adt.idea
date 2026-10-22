/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.bazel.java.run

import com.google.common.collect.ImmutableSet
import com.google.idea.bazel.java.run.producers.BlazeJUnitTestFilterFlags
import com.google.idea.bazel.java.sync.source.JavaLikeLanguage
import com.google.idea.blaze.base.command.BlazeFlags
import com.google.idea.blaze.base.model.primitives.Kind
import com.google.idea.blaze.base.run.producers.TestFilterSyntax
import com.google.idea.blaze.base.run.producers.TestSelector
import com.google.idea.blaze.base.run.smrunner.BlazeTestEventsHandler
import com.google.idea.blaze.base.run.smrunner.BlazeXmlSchema.TestSuite
import com.google.idea.blaze.common.Label
import com.intellij.execution.Location
import com.intellij.execution.junit.JUnitUtil
import com.intellij.execution.junit2.PsiMemberParameterizedLocation
import com.intellij.execution.junit2.info.MethodLocation
import com.intellij.execution.testframework.JavaTestLocator
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.util.io.URLUtil

/** Provides java-specific methods needed by the SM-runner test UI. */
class BlazeJavaTestEventsHandler : BlazeTestEventsHandler {

  override fun handlesKind(kind: Kind?): Boolean = HANDLED_KINDS.contains(kind)

  /** Overridden to support parameterized tests, which use nested test_suite XML elements. */
  override fun ignoreSuite(label: Label, kind: Kind?, suite: TestSuite): Boolean {
    if (suite.testSuites.isEmpty()) {
      return false
    }
    // target/class names are fully-qualified; unqualified names denote parameterized methods
    return suite.testSuites.all { it.name == null || it.name.contains(".") }
  }

  override fun getTestLocator(): SMTestLocator = BlazeJavaTestLocator

  override fun suiteLocationUrl(label: Label, kind: Kind?, name: String): String =
    JavaTestLocator.SUITE_PROTOCOL + URLUtil.SCHEME_SEPARATOR + name

  override fun testLocationUrl(
    label: Label,
    kind: Kind?,
    parentSuite: String?,
    name: String,
    classname: String?,
  ): String? {
    if (classname == null) {
      return null
    }
    val classComponent = JavaTestLocator.TEST_PROTOCOL + URLUtil.SCHEME_SEPARATOR + classname
    val parameterComponent = extractParameterComponent(name)
    if (parameterComponent != null) {
      return "$classComponent$TEST_CASE_SEPARATOR$parentSuite$parameterComponent"
    }
    return "$classComponent$TEST_CASE_SEPARATOR$name"
  }

  override fun suiteDisplayName(label: Label, kind: Kind?, rawName: String): String {
    val name = StringUtil.trimEnd(rawName, '.')
    return name.substringAfterLast('.')
  }

  override fun getTestFilter(project: Project, testLocations: List<Location<*>>): String? {
    val locationsPerClass = HashMap<PsiClass, MutableCollection<Location<*>>>()
    for (location in testLocations) {
      appendTest(locationsPerClass, location)
    }
    val filter = BlazeJUnitTestFilterFlags.testFilterForClassesAndMethods(locationsPerClass)
    return if (filter != null) "${BlazeFlags.TEST_FILTER}='$filter'" else null
  }

  override fun getTestSelectors(
    project: Project,
    testLocations: List<Location<*>>,
  ): List<TestSelector>? {
    val locationsPerClass = HashMap<PsiClass, MutableCollection<Location<*>>>()
    for (location in testLocations) {
      appendTest(locationsPerClass, location)
    }
    val filter = BlazeJUnitTestFilterFlags.testFilterForClassesAndMethods(locationsPerClass) ?: return null
    val version =
      if (locationsPerClass.keys.any(JUnitUtil::isJUnit4TestClass)) {
        TestFilterSyntax.JUNIT_4
      } else {
        TestFilterSyntax.JUNIT_3
      }
    return listOf(TestSelector.Filter(filter, version))
  }

  companion object {
    private val HANDLED_KINDS: ImmutableSet<Kind> by lazy {
      JavaLikeLanguage.getAllHandledTestKinds()
    }
    private const val TEST_CASE_SEPARATOR = '/'

    private fun extractParameterComponent(name: String): String? {
      if (name.startsWith("[") && name.contains("]")) {
        return name.substring(0, name.indexOf(']') + 1)
      }
      return null
    }

    private fun appendTest(
      map: MutableMap<PsiClass, MutableCollection<Location<*>>>,
      location: Location<*>,
    ) {
      val psi = location.psiElement
      if (psi is PsiClass) {
        val locations = map.getOrPut(psi) { HashSet() }
        if (location is BlazePsiMemberParameterizedLocation) {
          locations.add(location)
        }
        return
      }
      if (psi !is PsiMethod) {
        return
      }
      val psiClass = getContainingClass(psi, location) ?: return
      map.getOrPut(psiClass) { HashSet() }.add(location)
    }

    private fun getContainingClass(method: PsiMethod, location: Location<*>): PsiClass? {
      if (location is PsiMemberParameterizedLocation) {
        location.containingClass?.let {
          return it
        }
      }
      if (location is MethodLocation) {
        return location.containingClass
      }
      return method.containingClass
    }
  }
}
