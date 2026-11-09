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
package com.android.tools.idea.gradle.dsl.parser

import com.android.tools.idea.gradle.dsl.api.GradleFileModel
import com.android.tools.idea.gradle.dsl.api.ext.PropertyType.DERIVED
import com.android.tools.idea.gradle.dsl.api.ext.PropertyType.REGULAR
import com.android.tools.idea.gradle.dsl.api.util.GradleBlockModel
import com.android.tools.idea.gradle.dsl.model.GradleDslBlockModel
import com.android.tools.idea.gradle.dsl.model.GradleFileModelImpl
import com.android.tools.idea.gradle.dsl.model.GradleFileModelTestCase
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.gradle.dsl.parser.ExternalNameInfo.ExternalNameSyntax.ASSIGNMENT
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslClosure
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpression
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionList
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionMap
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslLiteral
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslMethodCall
import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement
import com.android.tools.idea.gradle.dsl.parser.elements.GradlePropertiesDslElement
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.openapi.vfs.VfsUtilCore.loadText
import com.intellij.openapi.vfs.VirtualFile
import org.junit.Test

/**
 * Regression tests for b/472823740 verifying that [GradleDslMethodCall] PSI creation defers argument and closure population to
 * `applyDslMethodCall` (avoiding re-entrant duplicate PSI writes when a recreated child argument triggers `parent.create()`), that
 * `GradlePropertiesDslElement.deleteAndRecreate` applies copied composite elements after creating their PSI shells, and that newly created
 * list literals record `myExpression` and reset unsaved state.
 */
class GradleDslWriterReentrancyTest : GradleFileModelTestCase() {

  private fun GradleFileModel.dslElement(): GradlePropertiesDslElement {
    val impl = this as GradleFileModelImpl
    val field = GradleFileModelImpl::class.java.getDeclaredField("myGradleDslFile")
    field.isAccessible = true
    return field.get(impl) as GradlePropertiesDslElement
  }

  private fun GradleBlockModel.dslElement(): GradlePropertiesDslElement {
    val impl = this as GradleDslBlockModel
    val field = GradleDslBlockModel::class.java.getDeclaredField("myDslElement")
    field.isAccessible = true
    return field.get(impl) as GradlePropertiesDslElement
  }

  private fun saveAndLoadText(file: VirtualFile): String {
    runWriteCommandAction(myProject) { FileDocumentManager.getInstance().saveAllDocuments() }
    return convertLineSeparators(loadText(file)).trimEnd()
  }

  @Test
  fun testReplaceSingleArgumentOnNestedMethodCallAndEditAgainWithoutReparse() {
    writeToBuildFile(
      if (isKotlinScript) {
        "val jars by extra(files(\"keep.jar\", file(\"old.jar\")))"
      } else {
        """
        ext {
          jars = files('keep.jar', file('old.jar'))
        }
        """
          .trimIndent()
      }
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val extDsl = buildModel.ext().dslElement()
    val filesCall = extDsl.getPropertyElement("jars", GradleDslMethodCall::class.java)!!
    val fileCall = filesCall.arguments[1] as GradleDslMethodCall

    val newArg =
      GradleDslLiteral(fileCall.argumentsElement, GradleNameElement.empty()).apply {
        setValue("new.jar")
      }
    fileCall.replaceArgument(fileCall.arguments.single(), newArg)

    applyChanges(projectModel)

    assertThat(saveAndLoadText(myBuildFile))
      .isEqualTo(
        if (isKotlinScript) {
          "val jars by extra(files(\"keep.jar\", file(\"new.jar\")))"
        } else {
          """
          ext {
            jars = files('keep.jar', file('new.jar'))
          }
          """
            .trimIndent()
        }
      )
    assertThat(buildModel.isModified).isFalse()
    assertThat(newArg.expression?.isValid).isTrue()

    // Editing the live replaced argument again without reparsing verifies that processListElement /
    // createListElement set myExpression and reset unsaved state on the first apply.
    newArg.setValue("updated.jar")
    applyChanges(projectModel)

    assertThat(saveAndLoadText(myBuildFile))
      .isEqualTo(
        if (isKotlinScript) {
          "val jars by extra(files(\"keep.jar\", file(\"updated.jar\")))"
        } else {
          """
          ext {
            jars = files('keep.jar', file('updated.jar'))
          }
          """
            .trimIndent()
        }
      )
  }

  @Test
  fun testReplaceMultipleArgumentsOnSameNameOrUnnamedMethodCallAndEditAgainWithoutReparse() {
    writeToBuildFile(
      if (isKotlinScript) {
        """
        android {
            namespace = "com.example.app"
            customCall("old1.pro", "old2.pro")
        }
        """
          .trimIndent()
      } else {
        """
        android {
            namespace = 'com.example.app'
            customCall('old1.pro', 'old2.pro')
        }
        """
          .trimIndent()
      }
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val call = buildModel.android().dslElement().allElements.filterIsInstance<GradleDslMethodCall>().single()

    val newArg1 =
      GradleDslLiteral(call.argumentsElement, GradleNameElement.empty()).apply {
        setValue("new1.pro")
      }
    val newArg2 =
      GradleDslLiteral(call.argumentsElement, GradleNameElement.empty()).apply {
        setValue("new2.pro")
      }
    val oldArgs = call.arguments.toList()
    call.replaceArgument(oldArgs[0], newArg1)
    call.replaceArgument(oldArgs[1], newArg2)

    applyChanges(projectModel)

    assertThat(saveAndLoadText(myBuildFile))
      .isEqualTo(
        if (isKotlinScript) {
          """
          android {
              namespace = "com.example.app"
              customCall("new1.pro", "new2.pro")
          }
          """
            .trimIndent()
        } else {
          """
          android {
              namespace = 'com.example.app'
              customCall('new1.pro', 'new2.pro')
          }
          """
            .trimIndent()
        }
      )
    assertThat(buildModel.isModified).isFalse()
    assertThat(newArg1.expression?.isValid).isTrue()
    assertThat(newArg2.expression?.isValid).isTrue()

    newArg1.setValue("final1.pro")
    newArg2.setValue("final2.pro")
    applyChanges(projectModel)

    assertThat(saveAndLoadText(myBuildFile))
      .isEqualTo(
        if (isKotlinScript) {
          """
          android {
              namespace = "com.example.app"
              customCall("final1.pro", "final2.pro")
          }
          """
            .trimIndent()
        } else {
          """
          android {
              namespace = 'com.example.app'
              customCall('final1.pro', 'final2.pro')
          }
          """
            .trimIndent()
        }
      )
  }

  @Test
  fun testRecreateMethodCallsViaCompositeChildArguments() {
    writeToBuildFile(
      if (isKotlinScript) {
        """
        mapCall("a", "b")
        treeCall("a", "b")
        nestedCall("a", "b")
        """
          .trimIndent()
      } else {
        """
        mapCall('a', 'b')
        treeCall('a', 'b')
        nestedCall('a', 'b')
        """
          .trimIndent()
      }
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val calls = buildModel.dslElement().allElements.filterIsInstance<GradleDslMethodCall>()
    assertThat(calls).hasSize(3)

    fun clearAndSetSingleArg(call: GradleDslMethodCall, newArg: GradleDslExpression) {
      val existing = call.arguments.toList()
      call.replaceArgument(existing.first(), newArg)
      existing.drop(1).forEach { call.remove(it) }
    }

    // 1. Replace arguments with a DERIVED map (exercises createDslElement + processMapElement / createMapElement).
    val mapCall = calls[0]
    val mapArg =
      GradleDslExpressionMap(mapCall.argumentsElement, GradleNameElement.empty()).apply {
        elementType = DERIVED
        addNewLiteral("path", ":lib")
        addNewLiteral("configuration", "debug")
      }
    clearAndSetSingleArg(mapCall, mapArg)

    // 2. Replace arguments with a map containing a named list `include` (exercises createNamedArgumentList / createBinaryExpression).
    val treeCall = calls[1]
    val treeMap =
      GradleDslExpressionMap(treeCall.argumentsElement, GradleNameElement.empty()).apply {
        elementType = DERIVED
        addNewLiteral("dir", "libs")
      }
    val includeList =
      GradleDslExpressionList(treeMap, GradleNameElement.create("include"), false).apply {
        elementType = DERIVED
        addNewExpression(GradleDslLiteral(this, GradleNameElement.empty()).apply { setValue("*.jar") })
        addNewExpression(GradleDslLiteral(this, GradleNameElement.empty()).apply { setValue("*.aar") })
      }
    treeMap.setNewElement(includeList)
    clearAndSetSingleArg(treeCall, treeMap)

    // 3. Replace arguments with a nested method call (exercises createDslMethodCall without re-entry).
    val nestedCall = calls[2]
    val innerCall =
      GradleDslMethodCall(nestedCall.argumentsElement, GradleNameElement.empty(), "inner").apply {
        addNewArgument(GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply { setValue("arg") })
      }
    clearAndSetSingleArg(nestedCall, innerCall)

    applyChanges(projectModel)

    assertThat(saveAndLoadText(myBuildFile))
      .isEqualTo(
        if (isKotlinScript) {
          """
          mapCall(mapOf("path" to ":lib", "configuration" to "debug"))
          treeCall(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar")))
          nestedCall(inner("arg"))
          """
            .trimIndent()
        } else {
          """
          mapCall(path: ':lib', configuration: 'debug')
          treeCall(dir: 'libs', include: ['*.jar', '*.aar'])
          nestedCall(inner('arg'))
          """
            .trimIndent()
        }
      )
    assertThat(buildModel.isModified).isFalse()
  }

  @Test
  fun testMethodCallWithClosureCreationAndArgumentReplacement() {
    writeToBuildFile(
      if (isKotlinScript) {
        "existingTask(\"old1\", \"old2\")"
      } else {
        "existingTask('old1', 'old2')"
      }
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val fileDsl = buildModel.dslElement()

    val existingCall = fileDsl.allElements.filterIsInstance<GradleDslMethodCall>().single()
    val replacementArg =
      GradleDslLiteral(existingCall.argumentsElement, GradleNameElement.empty()).apply {
        setValue("renamedTask")
      }
    val oldExistingArgs = existingCall.arguments.toList()
    existingCall.replaceArgument(oldExistingArgs[0], replacementArg)
    oldExistingArgs.drop(1).forEach { existingCall.remove(it) }
    val existingClosure = GradleDslClosure(existingCall, null, GradleNameElement.create("existingTask"))
    existingClosure.setNewElement(
      GradleDslLiteral(existingClosure, GradleNameElement.create("group")).apply {
        elementType = REGULAR
        externalSyntax = ASSIGNMENT
        setValue("build")
      }
    )
    existingCall.setNewClosureElement(existingClosure)

    val bareCall =
      GradleDslMethodCall(fileDsl, GradleNameElement.empty(), "registerCheck").apply {
        addNewArgument(GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply { setValue("verify") })
        val closure = GradleDslClosure(this, null, GradleNameElement.create("registerCheck"))
        closure.setNewElement(
          GradleDslLiteral(closure, GradleNameElement.create("group")).apply {
            elementType = REGULAR
            externalSyntax = ASSIGNMENT
            setValue("verification")
          }
        )
        setNewClosureElement(closure)
      }
    fileDsl.setNewElement(bareCall)

    val assignedCall =
      GradleDslMethodCall(fileDsl, GradleNameElement.create("assignedTask"), "createTask").apply {
        externalSyntax = ASSIGNMENT
        addNewArgument(GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply { setValue("lint") })
        val closure = GradleDslClosure(this, null, GradleNameElement.create("createTask"))
        closure.setNewElement(
          GradleDslLiteral(closure, GradleNameElement.create("description")).apply {
            elementType = REGULAR
            externalSyntax = ASSIGNMENT
            setValue("Run lint")
          }
        )
        setNewClosureElement(closure)
      }
    fileDsl.setNewElement(assignedCall)

    val trailingProp =
      GradleDslLiteral(fileDsl, GradleNameElement.create("trailingFlag")).apply {
        elementType = REGULAR
        externalSyntax = ASSIGNMENT
        setValue("done")
      }
    fileDsl.setNewElement(trailingProp)

    applyChanges(projectModel)

    assertThat(saveAndLoadText(myBuildFile))
      .isEqualTo(
        if (isKotlinScript) {
          """
          existingTask("renamedTask") {
              group = "build"
          }
          registerCheck("verify") {
              group = "verification"
          }
          assignedTask = createTask("lint") {
              description = "Run lint"
          }
          trailingFlag = "done"
          """
            .trimIndent()
        } else {
          """
          existingTask('renamedTask') {
              group = 'build'
          }
          registerCheck('verify') {
              group = 'verification'
          }
          assignedTask = createTask('lint') {
              description = 'Run lint'
          }
          trailingFlag = 'done'
          """
            .trimIndent()
        }
      )
    assertThat(buildModel.isModified).isFalse()
  }

  @Test
  fun testDeleteAndRecreateNonLiteralListPreservesMethodCallArgumentsAndAllowsSecondEdit() {
    writeToBuildFile(
      if (isKotlinScript) {
        """
        android {
            defaultConfig {
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "drop.pro")
            }
        }
        """
          .trimIndent()
      } else {
        """
        android {
            defaultConfig {
                proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro', 'drop.pro'
            }
        }
        """
          .trimIndent()
      }
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val proguardFiles = buildModel.android().defaultConfig().proguardFiles()
    val quoteChar = if (isKotlinScript) "\"" else "'"

    proguardFiles.getListValue("drop.pro")!!.delete()
    proguardFiles.addListValueAt(0)!!.setValue("extra.pro")

    // Perform the first DSL apply and subsequent in-place literal edit inside one write command
    // before command-end postprocess formatting re-parses Groovy closure chameleons or wraps
    // multi-line Kotlin argument lists.
    runWriteCommandAction(myProject) {
      buildModel.dslElement().applyChanges()

      val listDsl = proguardFiles.rawElement as GradleDslExpressionList
      assertThat(listDsl.expressions).hasSize(3)
      for (expr in listDsl.expressions) {
        assertThat(expr.psiElement?.isValid).isTrue()
        assertThat(expr.isModified).isFalse()
        if (expr is GradleDslLiteral) {
          assertThat(expr.expression?.isValid).isTrue()
          assertThat(expr.unsavedValue).isNull()
        }
      }
      assertThat(proguardFiles.toList()?.map { it.forceString() })
        .containsExactly(
          "extra.pro",
          "getDefaultProguardFile(${quoteChar}proguard-android-optimize.txt${quoteChar})",
          "proguard-rules.pro",
        )
        .inOrder()
      assertThat(buildModel.isModified).isFalse()

      proguardFiles.getListValue("proguard-rules.pro")!!.setValue("updated-rules.pro")
      projectModel.applyChanges()
    }

    val saved = saveAndLoadText(myBuildFile)
    if (isKotlinScript) {
      assertThat(saved)
        .isEqualTo(
          """
          android {
              defaultConfig {
                  proguardFiles(
                      "extra.pro",
                      getDefaultProguardFile("proguard-android-optimize.txt"),
                      "updated-rules.pro"
                  )
              }
          }
          """
            .trimIndent()
        )
    } else {
      assertThat(saved)
        .isEqualTo(
          """
          android {
              defaultConfig {
                  proguardFiles 'extra.pro', getDefaultProguardFile('proguard-android-optimize.txt'), 'updated-rules.pro'
              }
          }
          """
            .trimIndent()
        )
    }
    assertThat(buildModel.isModified).isFalse()

    projectModel.reparse()
    val reparsedProguardFiles = projectModel.projectBuildModel!!.android().defaultConfig().proguardFiles()
    assertThat(reparsedProguardFiles.toList()?.map { it.forceString() })
      .containsExactly(
        "extra.pro",
        "getDefaultProguardFile(${quoteChar}proguard-android-optimize.txt${quoteChar})",
        "updated-rules.pro",
      )
      .inOrder()
  }
}
