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
package com.google.idea.bazel.java.run.producers

import com.google.common.annotations.VisibleForTesting
import com.google.idea.bazel.java.run.producers.JUnitParameterizedClassHeuristic.ParameterizedTestInfo
import com.google.idea.blaze.base.run.producers.TestFilterSyntax
import com.google.idea.blaze.base.run.producers.TestSelector
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.execution.Location
import com.intellij.execution.junit.JUnitUtil
import com.intellij.execution.junit2.PsiMemberParameterizedLocation
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

/** Utilities for building test filter flags for JUnit tests. */
object BlazeJUnitTestFilterFlags {

  private const val PARAMETER_SUFFIX_REGEX = "(\\[.*\\])?(\\(.*\\))?"

  /** A version of JUnit to generate test filter flags for. */
  enum class JUnitVersion {
    JUNIT_3,
    JUNIT_4,
  }

  /** Builds the JUnit test filter corresponding to the given class.<br> Returns null if no class name can be found. */
  @JvmStatic fun testFilterForClass(psiClass: PsiClass): String? = testFilterForClassAndMethods(psiClass, emptyList())

  /** Builds the JUnit test filter corresponding to the given class and methods.<br> Returns null if no class name can be found. */
  @JvmStatic
  fun testFilterForClassAndMethods(psiClass: PsiClass, methods: Collection<PsiMethod>): String? {
    val version = if (JUnitUtil.isJUnit4TestClass(psiClass)) JUnitVersion.JUNIT_4 else JUnitVersion.JUNIT_3
    val parameterizedTestInfo = JUnitParameterizedClassHeuristic.getParameterizedTestInfo(psiClass)
    return testFilterForClassAndMethods(
      psiClass,
      version,
      extractMethodFilters(psiClass, methods, parameterizedTestInfo),
      parameterizedTestInfo,
    )
  }

  /** Runs all parameterized versions of methods. */
  private fun extractMethodFilters(
    psiClass: PsiClass,
    methods: Collection<PsiMethod>,
    parameterizedTestInfo: ParameterizedTestInfo?,
  ): List<String> {
    // standard org.junit.runners.Parameterized class requires no per-test annotations
    val testSuffixRegex = getTestSuffixRegex(psiClass, parameterizedTestInfo)
    return methods.map { method -> methodFilter(method, testSuffixRegex) }.sorted()
  }

  private fun getTestSuffixRegex(
    testClass: PsiClass,
    parameterizedTestInfo: ParameterizedTestInfo?,
  ): String? {
    if (parameterizedTestInfo != null) {
      return parameterizedTestInfo.testMethodSuffixRegex()
    }
    if (PsiMemberParameterizedLocation.getParameterizedLocation(testClass, null) != null) {
      return JUnitParameterizedClassHeuristic.STANDARD_JUNIT_TEST_SUFFIX
    }
    return null
  }

  private fun methodFilter(method: PsiMethod, testSuffixRegex: String?): String =
    when {
      testSuffixRegex != null -> method.name + testSuffixRegex
      AnnotationUtil.findAnnotation(method, "Parameters") != null ->
        // Supports @junitparams.Parameters, an annotation that applies to an individual test method
        method.name + JUnitParameterizedClassHeuristic.STANDARD_JUNIT_TEST_SUFFIX
      else -> method.name
    }

  @JvmStatic
  fun testFilterForClassesAndMethods(methodsPerClass: Map<PsiClass, Collection<Location<*>>>): String? {
    if (methodsPerClass.isEmpty()) {
      return null
    }
    // Note: this could be incorrect if there are no JUnit4 classes in this sample, but some in the
    // java_test target they're run from.
    val version = if (hasJUnit4Test(methodsPerClass.keys)) JUnitVersion.JUNIT_4 else JUnitVersion.JUNIT_3
    return testFilterForClassesAndMethods(methodsPerClass, version)
  }

  @JvmStatic
  fun testFilterForClassesAndMethods(
    methodsPerClass: Map<PsiClass, Collection<Location<*>>>,
    version: JUnitVersion,
  ): String? {
    val classFilters = ArrayList<String>()
    for ((psiClass, methods) in methodsPerClass) {
      val parameterizedTestInfo = JUnitParameterizedClassHeuristic.getParameterizedTestInfo(psiClass)
      val filter =
        testFilterForClassAndMethods(
          psiClass,
          version,
          extractMethodFilters(methods),
          parameterizedTestInfo,
        ) ?: return null
      classFilters.add(filter)
    }
    classFilters.sort()
    val separator = if (version == JUnitVersion.JUNIT_4) "|" else ","
    return classFilters.joinToString(separator)
  }

  /** Only runs specified parameterized versions, where relevant. */
  private fun extractMethodFilters(methods: Collection<Location<*>>): List<String> = methods.map(::testFilterForLocation).sorted()

  private fun testFilterForLocation(location: Location<*>): String {
    val psi = location.psiElement
    check(psi is PsiMethod)
    val methodName = psi.name
    if (location is PsiMemberParameterizedLocation) {
      return methodName + StringUtil.escapeToRegexp(location.paramSetName)
    }
    return methodName
  }

  private fun hasJUnit4Test(classes: Collection<PsiClass>): Boolean = classes.any(JUnitUtil::isJUnit4TestClass)

  /** Builds the JUnit test filter corresponding to the given class and methods.<br> Returns null if no class name can be found. */
  private fun testFilterForClassAndMethods(
    psiClass: PsiClass,
    version: JUnitVersion,
    methodFilters: List<String>,
    parameterizedTestInfo: ParameterizedTestInfo?,
  ): String? {
    val className = psiClass.qualifiedName ?: return null
    return testFilterForClassAndMethods(className, version, methodFilters, parameterizedTestInfo)
  }

  /**
   * Builds the blaze test_filter flag for JUnit tests. Excludes the "--test_filter" component of the flag, so that multiple test classes
   * can be combined.
   */
  @JvmStatic
  @VisibleForTesting
  fun testFilterForClassAndMethods(
    className: String,
    jUnitVersion: JUnitVersion,
    methodFilters: List<String>,
    parameterizedTestInfo: ParameterizedTestInfo?,
  ): String {
    val output = StringBuilder(className)
    val classSuffixRegex = parameterizedTestInfo?.testClassSuffixRegex()
    if (classSuffixRegex != null) {
      output.append(classSuffixRegex)
    }
    val methodNamePattern = concatenateMethodNames(methodFilters, jUnitVersion)
    if (methodNamePattern.isNullOrEmpty()) {
      if (jUnitVersion == JUnitVersion.JUNIT_4) {
        output.append('#')
      }
      return output.toString()
    }
    output.append('#').append(methodNamePattern)
    // JUnit 4 test filters are regexes, and must be terminated to avoid matching
    // unintended classes/methods. JUnit 3 test filters do not need or support this syntax.
    if (jUnitVersion == JUnitVersion.JUNIT_3) {
      return output.toString()
    }
    output.append('$')
    return output.toString()
  }

  private fun concatenateMethodNames(
    methodNames: List<String>,
    jUnitVersion: JUnitVersion,
  ): String? {
    if (methodNames.isEmpty()) {
      return null
    }
    if (methodNames.size == 1) {
      return methodNames[0]
    }
    return if (jUnitVersion == JUnitVersion.JUNIT_4) {
      "(${methodNames.joinToString("|")})"
    } else {
      methodNames.joinToString(",")
    }
  }

  @JvmStatic
  fun testFilterForSelectors(testSelectors: List<TestSelector>): String? {
    if (testSelectors.isEmpty()) {
      return null
    }
    if (testSelectors.any { it.testFilterSyntax == TestFilterSyntax.RULES_KOTLIN }) {
      val testFilters = testSelectors.map { selector ->
        if (selector.methodName != null) {
          "${selector.className}.${selector.methodName}"
        } else {
          selector.className
        }
      }
      return testFilters.joinToString(",")
    }
    val isJUnit4 = testSelectors.any { it.testFilterSyntax == TestFilterSyntax.JUNIT_4 }
    val selectorsByClass = testSelectors.groupBy { it.className }
    val classTestFilters = ArrayList<String>()
    for ((className, classSelectors) in selectorsByClass) {
      val version = classSelectors[0].testFilterSyntax
      val jv = if (version == TestFilterSyntax.JUNIT_4) JUnitVersion.JUNIT_4 else JUnitVersion.JUNIT_3
      val classFilter = if (jv == JUnitVersion.JUNIT_4) className + PARAMETER_SUFFIX_REGEX else className
      val methodFilters =
        classSelectors
          .filter { it.methodName != null }
          .map { selector ->
            val method = selector.methodName!!
            if (jv != JUnitVersion.JUNIT_4) {
              method
            } else {
              val parameterSuffix = selector.parameterFilterRegex ?: PARAMETER_SUFFIX_REGEX
              method + parameterSuffix
            }
          }
          .sorted()

      val filter = testFilterForClassAndMethods(classFilter, jv, methodFilters, null)
      classTestFilters.add(filter)
    }
    classTestFilters.sort()
    val separator = if (isJUnit4) "|" else ","
    return classTestFilters.joinToString(separator)
  }
}
