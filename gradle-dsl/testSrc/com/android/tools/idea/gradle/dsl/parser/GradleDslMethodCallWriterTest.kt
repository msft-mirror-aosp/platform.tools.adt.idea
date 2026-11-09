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

import com.android.tools.idea.gradle.dsl.TestFileName
import com.android.tools.idea.gradle.dsl.api.ext.PropertyType.REGULAR
import com.android.tools.idea.gradle.dsl.model.GradleDslBlockModel
import com.android.tools.idea.gradle.dsl.model.GradleFileModelImpl
import com.android.tools.idea.gradle.dsl.model.GradleFileModelTestCase
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.gradle.dsl.parser.ExternalNameInfo.ExternalNameSyntax.ASSIGNMENT
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslClosure
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslLiteral
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslMethodCall
import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement
import com.android.tools.idea.gradle.dsl.parser.elements.GradlePropertiesDslElement
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.jetbrains.annotations.SystemDependent
import org.junit.Test

/**
 * Tests verifying that creating a [GradleDslMethodCall] whose external element name matches its method name emits a single method call
 * (`foo(...)`) rather than duplicating the name (`foo foo(...)`), while preserving assignments (`a = b(...)`, `x = x(...)`) and
 * constructors.
 */
class GradleDslMethodCallWriterTest : GradleFileModelTestCase() {

  @Test
  fun testCopyParsedSameNameMethodCallsDoesNotDuplicateMethodName() {
    writeToBuildFile(TestFile.COPY_PARSED_SAME_NAME_METHOD_CALLS)

    val buildModel = gradleBuildModel
    val fileDsl = (buildModel as GradleFileModelImpl).dslFile
    val androidDsl = (buildModel.android() as GradleDslBlockModel).rawElement as GradlePropertiesDslElement

    val topCall = fileDsl.allElements.filterIsInstance<GradleDslMethodCall>().single()
    val copiedTop = topCall.copy()
    (copiedTop.arguments[0] as GradleDslLiteral).setValue("new1")
    fileDsl.removeProperty(topCall)
    fileDsl.addNewElementAt(0, copiedTop)

    val customCall = androidDsl.allElements.filterIsInstance<GradleDslMethodCall>().single()
    val copiedCustom = customCall.copy()
    (copiedCustom.arguments[0] as GradleDslLiteral).setValue("updated")
    androidDsl.removeProperty(customCall)
    androidDsl.setNewElement(copiedCustom)

    applyChangesAndReparse(buildModel)
    verifyFileContents(myBuildFile, TestFile.COPY_PARSED_SAME_NAME_METHOD_CALLS_EXPECTED)
  }

  @Test
  fun testCreateSameNameQualifiedAndAssignedMethodCalls() {
    writeToBuildFile(TestFile.CREATE_SAME_NAME_QUALIFIED_AND_ASSIGNED_METHOD_CALLS)

    val buildModel = gradleBuildModel
    val fileDsl = (buildModel as GradleFileModelImpl).dslFile
    val androidDsl = (buildModel.android() as GradleDslBlockModel).rawElement as GradlePropertiesDslElement

    val qualifiedNestedCall =
      GradleDslMethodCall(
          androidDsl,
          GradleNameElement.fake("android.customOption"),
          "android.customOption",
        )
        .apply {
          addNewArgument(GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply { setValue("opt") })
        }
    androidDsl.setNewElement(qualifiedNestedCall)

    val sameNameWithClosure =
      GradleDslMethodCall(fileDsl, GradleNameElement.create("customTask"), "customTask").apply {
        addNewArgument(GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply { setValue("verify") })
        val closure = GradleDslClosure(this, null, GradleNameElement.create("customTask"))
        closure.setNewElement(
          GradleDslLiteral(closure, GradleNameElement.create("group")).apply {
            elementType = REGULAR
            externalSyntax = ASSIGNMENT
            setValue("verification")
          }
        )
        setNewClosureElement(closure)
      }
    fileDsl.setNewElement(sameNameWithClosure)

    val distinctAssignedCall =
      GradleDslMethodCall(fileDsl, GradleNameElement.create("assignedTask"), "createTask").apply {
        externalSyntax = ASSIGNMENT
        addNewArgument(GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply { setValue("lint") })
      }
    fileDsl.setNewElement(distinctAssignedCall)

    applyChangesAndReparse(buildModel)
    verifyFileContents(
      myBuildFile,
      TestFile.CREATE_SAME_NAME_QUALIFIED_AND_ASSIGNED_METHOD_CALLS_EXPECTED,
    )
  }

  @Test
  fun testGroovyPreservesSameNameAssignmentConstructorAndSchemaMappedCall() {
    // Groovy supports untyped `x = x('arg')` and `new Foo('arg')` constructor expressions, and
    // parses single-argument parenthesized schema properties (`testNamespace('...')`, mapped to
    // model property `mTestNamespace`) as GradleDslMethodCall.
    isIrrelevantForKotlinScript("Groovy untyped assignment, constructor syntax, and parenthesized schema properties")
    writeToBuildFile(TestFile.PRESERVE_SAME_NAME_ASSIGNMENT_CONSTRUCTOR_AND_SCHEMA_MAPPED_CALL)

    val buildModel = gradleBuildModel
    val fileDsl = (buildModel as GradleFileModelImpl).dslFile
    val androidDsl = (buildModel.android() as GradleDslBlockModel).rawElement as GradlePropertiesDslElement

    val parsedAssign = fileDsl.allElements.filterIsInstance<GradleDslMethodCall>().single()
    val copiedAssign = parsedAssign.copy().apply { externalSyntax = ASSIGNMENT }
    (copiedAssign.arguments.single() as GradleDslLiteral).setValue("new")
    fileDsl.removeProperty(parsedAssign)
    fileDsl.addNewElementAt(0, copiedAssign)

    val testNamespaceCall = androidDsl.allElements.filterIsInstance<GradleDslMethodCall>().single()
    val copiedTestNamespace = testNamespaceCall.copy()
    (copiedTestNamespace.arguments.single() as GradleDslLiteral).setValue("com.example.newtest")
    androidDsl.removeProperty(testNamespaceCall)
    androidDsl.setNewElement(copiedTestNamespace)

    val sameCtorAssign =
      GradleDslMethodCall(fileDsl, null, GradleNameElement.create("File"), true, "File").apply {
        externalSyntax = ASSIGNMENT
        addNewArgument(
          GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply {
            setValue("out.txt")
          }
        )
      }
    fileDsl.setNewElement(sameCtorAssign)

    applyChangesAndReparse(buildModel)
    verifyFileContents(
      myBuildFile,
      TestFile.PRESERVE_SAME_NAME_ASSIGNMENT_CONSTRUCTOR_AND_SCHEMA_MAPPED_CALL_EXPECTED,
    )
    val reparsedCtor = (buildModel as GradleFileModelImpl).dslFile.allElements.last() as GradleDslMethodCall
    assertThat(reparsedCtor.isConstructor).isTrue()
    assertThat(reparsedCtor.name).isEqualTo("File")
    assertThat(reparsedCtor.methodName).isEqualTo("File")
  }

  enum class TestFile(private val path: @SystemDependent String) : TestFileName {
    COPY_PARSED_SAME_NAME_METHOD_CALLS("copyParsedSameNameMethodCalls"),
    COPY_PARSED_SAME_NAME_METHOD_CALLS_EXPECTED("copyParsedSameNameMethodCallsExpected"),
    CREATE_SAME_NAME_QUALIFIED_AND_ASSIGNED_METHOD_CALLS("createSameNameQualifiedAndAssignedMethodCalls"),
    CREATE_SAME_NAME_QUALIFIED_AND_ASSIGNED_METHOD_CALLS_EXPECTED("createSameNameQualifiedAndAssignedMethodCallsExpected"),
    PRESERVE_SAME_NAME_ASSIGNMENT_CONSTRUCTOR_AND_SCHEMA_MAPPED_CALL("preserveSameNameAssignmentConstructorAndSchemaMappedCall"),
    PRESERVE_SAME_NAME_ASSIGNMENT_CONSTRUCTOR_AND_SCHEMA_MAPPED_CALL_EXPECTED(
      "preserveSameNameAssignmentConstructorAndSchemaMappedCallExpected"
    );

    override fun toFile(basePath: @SystemDependent String, extension: String): File {
      return super.toFile("$basePath/gradleDslMethodCallWriter/$path", extension)
    }
  }
}
