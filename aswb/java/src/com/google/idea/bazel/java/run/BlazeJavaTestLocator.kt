/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.junit2.info.MethodLocation
import com.intellij.execution.testframework.JavaTestLocator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.ClassUtil
import com.intellij.util.SmartList

/**
 * Locates Java and Kotlin test classes and methods from Test Results tree URLs, supporting inherited test methods as well as bracket,
 * parenthesis, custom method-suffix, and class-level parameter encodings.
 */
object BlazeJavaTestLocator : JavaTestLocator() {

  private val logger = Logger.getInstance(BlazeJavaTestLocator::class.java)

  override fun getLocation(
    protocol: String,
    sourcePath: String,
    project: Project,
    scope: GlobalSearchScope,
  ): List<Location<*>> {
    val dumbService = DumbService.getInstance(project)
    return try {
      dumbService.computeWithAlternativeResolveEnabled(
        ThrowableComputable<List<Location<*>>, Throwable> {
          when (protocol) {
            SUITE_PROTOCOL -> resolveSuiteLocations(sourcePath, project, scope)
            TEST_PROTOCOL -> resolveMethodLocationsFromPath(sourcePath, project, scope)
            else -> emptyList()
          }
        }
      )
    } catch (e: IndexNotReadyException) {
      logger.error(e)
      emptyList()
    }
  }

  private fun resolveSuiteLocations(
    sourcePath: String,
    project: Project,
    scope: GlobalSearchScope,
  ): List<Location<*>> {
    val trimmedPath = StringUtil.trimEnd(sourcePath, ".")
    val (className, classParamSuffix) = splitParameterSuffix(trimmedPath)
    val testClass = ClassUtil.findPsiClass(PsiManager.getInstance(project), className, null, true, scope)
    if (testClass != null) {
      return SmartList(createClassLocation(project, testClass, classParamSuffix))
    }
    return resolveMethodLocationsFromPath(trimmedPath, project, scope)
  }

  private fun resolveMethodLocationsFromPath(
    sourcePath: String,
    project: Project,
    scope: GlobalSearchScope,
  ): List<Location<*>> {
    val separatorIdx = findClassMethodSeparator(sourcePath)
    if (separatorIdx > 0) {
      val rawClassSegment = sourcePath.substring(0, separatorIdx)
      val rawMethodSegment = sourcePath.substring(separatorIdx + 1)
      return resolveMethodLocations(rawClassSegment, rawMethodSegment, project, scope)
    }
    val (pathWithoutParams, paramSuffix) = splitParameterSuffix(sourcePath)
    val className = StringUtil.getPackageName(pathWithoutParams)
    val methodName = StringUtil.getShortName(pathWithoutParams)
    if (className.isNotEmpty()) {
      return resolveMethodLocations(
        className,
        methodName + paramSuffix.orEmpty(),
        project,
        scope,
      )
    }
    return emptyList()
  }

  private fun resolveMethodLocations(
    rawClassSegment: String,
    rawMethodSegment: String,
    project: Project,
    scope: GlobalSearchScope,
  ): List<Location<*>> {
    val (className, classParamSuffix) = splitParameterSuffix(rawClassSegment)
    val testClass = ClassUtil.findPsiClass(PsiManager.getInstance(project), className, null, true, scope) ?: return emptyList()
    if (rawMethodSegment.trim() == testClass.name) {
      return SmartList(createClassLocation(project, testClass, classParamSuffix))
    }

    val (methods, methodParamSuffix) = findTargetMethods(testClass, rawMethodSegment)
    if (methods.isEmpty()) {
      return SmartList(
        BlazePsiMemberParameterizedLocation(
          project,
          testClass,
          testClass,
          rawMethodSegment,
          classParamSuffix,
        )
      )
    }

    val results = SmartList<Location<*>>()
    for (method in methods) {
      val location =
        if (methodParamSuffix != null || classParamSuffix != null) {
          BlazePsiMemberParameterizedLocation(
            project,
            method,
            testClass,
            methodParamSuffix.orEmpty(),
            classParamSuffix,
          )
        } else {
          MethodLocation.elementInClass(method, testClass)
        }
      results.add(location)
    }
    return results
  }

  private fun findTargetMethods(
    testClass: PsiClass,
    rawMethodSegment: String,
  ): Pair<Array<PsiMethod>, String?> {
    val (candidateName, bracketOrParenSuffix) = splitParameterSuffix(rawMethodSegment)
    val trimmedCandidate = candidateName.trim()
    var methods = testClass.findMethodsByName(trimmedCandidate, true)
    if (methods.isEmpty() && candidateName != trimmedCandidate) {
      methods = testClass.findMethodsByName(candidateName, true)
    }
    if (methods.isNotEmpty()) {
      val suffix =
        if (bracketOrParenSuffix != null) {
          rawMethodSegment.substring(trimmedCandidate.length)
        } else {
          null
        }
      return methods to suffix
    }

    val bestMethodName =
      testClass.allMethods
        .map { it.name }
        .filter { rawMethodSegment.length > it.length && rawMethodSegment.startsWith(it) }
        .maxByOrNull { it.length } ?: return emptyArray<PsiMethod>() to null

    val prefixMatchedMethods = testClass.findMethodsByName(bestMethodName, true)
    val suffix = rawMethodSegment.substring(bestMethodName.length)
    return prefixMatchedMethods to suffix
  }

  private fun createClassLocation(
    project: Project,
    testClass: PsiClass,
    classParamSuffix: String?,
  ): Location<*> =
    if (classParamSuffix != null) {
      BlazePsiMemberParameterizedLocation(project, testClass, null, "", classParamSuffix)
    } else {
      PsiLocation(project, testClass)
    }

  private fun findClassMethodSeparator(path: String): Int {
    var bracketDepth = 0
    var parenDepth = 0
    for (i in path.indices) {
      when (path[i]) {
        '[' -> bracketDepth++
        ']' -> if (bracketDepth > 0) bracketDepth--
        '(' -> parenDepth++
        ')' -> if (parenDepth > 0) parenDepth--
        '/' -> if (bracketDepth == 0 && parenDepth == 0) return i
      }
    }
    return -1
  }

  private fun splitParameterSuffix(segment: String): Pair<String, String?> {
    val bracketIdx = segment.indexOf('[')
    val parenIdx = segment.indexOf('(')
    val splitIdx =
      when {
        bracketIdx > 0 && parenIdx > 0 -> minOf(bracketIdx, parenIdx)
        bracketIdx > 0 -> bracketIdx
        parenIdx > 0 -> parenIdx
        else -> -1
      }
    return if (splitIdx > 0) {
      segment.substring(0, splitIdx) to segment.substring(splitIdx)
    } else {
      segment to null
    }
  }
}
