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
package com.android.tools.idea.gradle.dsl.parser.groovy

import com.android.tools.idea.gradle.dsl.api.GradleFileModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencySpec
import com.android.tools.idea.gradle.dsl.api.ext.PropertyType.REGULAR
import com.android.tools.idea.gradle.dsl.api.ext.PropertyType.VARIABLE
import com.android.tools.idea.gradle.dsl.api.ext.RawText
import com.android.tools.idea.gradle.dsl.api.ext.ReferenceTo
import com.android.tools.idea.gradle.dsl.api.util.GradleBlockModel
import com.android.tools.idea.gradle.dsl.model.GradleDslBlockModel
import com.android.tools.idea.gradle.dsl.model.GradleFileModelImpl
import com.android.tools.idea.gradle.dsl.model.GradleFileModelTestCase
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.gradle.dsl.parser.ExternalNameInfo.ExternalNameSyntax.ASSIGNMENT
import com.android.tools.idea.gradle.dsl.parser.ExternalNameInfo.ExternalNameSyntax.AUGMENTED_ASSIGNMENT
import com.android.tools.idea.gradle.dsl.parser.ExternalNameInfo.ExternalNameSyntax.METHOD
import com.android.tools.idea.gradle.dsl.parser.ExternalNameInfo.ExternalNameSyntax.SET_METHOD
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslClosure
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionList
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionMap
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslLiteral
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslMethodCall
import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement
import com.android.tools.idea.gradle.dsl.parser.elements.GradlePropertiesDslElement
import com.google.common.truth.Truth.assertThat
import com.intellij.lang.ASTNode
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.openapi.vfs.VfsUtilCore.loadText
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiType
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.source.tree.TreeCopyHandler
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.testFramework.LoggedErrorProcessor
import java.util.Collections
import org.jetbrains.plugins.groovy.lang.psi.GrReferenceElement
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrAssignmentExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression
import org.jetbrains.plugins.groovy.lang.resolve.NonCodeMembersContributor
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for b/472823740 verifying that [GroovyDslWriter] and [GroovyDslUtil] mutate Groovy PSI without triggering
 * [TreeCopyHandler] reference encoding (`GroovyChangeUtilSupport`) or dynamic `.gdsl` member resolution
 * (`GroovyDslFileIndex.getDslScripts`), while preserving exact indentation and enforcing synthesized-element adoption invariants.
 */
class GroovyDslWriterTest : GradleFileModelTestCase() {

  private val encodedReferences: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val dynamicMemberPlaces: MutableList<String> = Collections.synchronizedList(mutableListOf())

  @Before
  fun installPsiCopyAndResolveRecorders() {
    TreeCopyHandler.EP_NAME.point.registerExtension(
      object : TreeCopyHandler {
        override fun encodeInformation(
          element: TreeElement,
          original: ASTNode,
          encodingState: MutableMap<Any, Any>,
        ) {
          val psi = original.psi
          if (psi is GrReferenceElement<*>) {
            encodedReferences.add(psi.text)
          }
        }

        override fun decodeInformation(
          element: TreeElement,
          decodingState: Map<Any, Any>,
        ): TreeElement? = null
      },
      testRootDisposable,
    )

    NonCodeMembersContributor.EP_NAME.point.registerExtension(
      object : NonCodeMembersContributor() {
        override fun processDynamicElements(
          qualifierType: PsiType,
          processor: PsiScopeProcessor,
          place: PsiElement,
          state: ResolveState,
        ) {
          dynamicMemberPlaces.add(place.text)
        }
      },
      testRootDisposable,
    )
  }

  private fun assertNoReferenceCopyOrDynamicResolve() {
    assertThat(encodedReferences).isEmpty()
    assertThat(dynamicMemberPlaces).isEmpty()
  }

  private fun loadNormalizedText(file: VirtualFile): String = convertLineSeparators(loadText(file))

  private fun applyAndVerifyNoResolve(projectModel: ProjectBuildModel) {
    encodedReferences.clear()
    dynamicMemberPlaces.clear()
    applyChanges(projectModel)
    assertNoReferenceCopyOrDynamicResolve()
  }

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

  @Test
  fun testNewProjectTemplateApplyChangesSkipsGroovyReferenceResolution() {
    assumeTrue(!isKotlinScript)

    writeToSettingsFile("include ':${SUB_MODULE_NAME}'\n")
    writeToBuildFile("")
    writeToSubModuleBuildFile("")
    writeToVersionCatalogFile(
      """
      [versions]
      agp = "8.9.0"
      coreKtx = "1.15.0"

      [libraries]
      androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }

      [plugins]
      android-application = { id = "com.android.application", version.ref = "agp" }
      kotlin-android = { id = "org.jetbrains.kotlin.android", version = "2.0.21" }
      """
        .trimIndent()
    )

    val projectModel = projectBuildModel
    val catalogs = projectModel.versionCatalogsModel
    val rootBuild = projectModel.projectBuildModel!!
    val appPlugin = catalogs.plugins("libs")!!.findProperty("android-application")
    val kotlinPlugin = catalogs.plugins("libs")!!.findProperty("kotlin-android")
    val coreKtxLib = catalogs.libraries("libs")!!.findProperty("androidx-core-ktx")

    // Root build.gradle created by New Project Wizard (`alias(...) apply false` infix statements).
    rootBuild.applyPlugin(ReferenceTo(appPlugin, rootBuild), false)
    rootBuild.applyPlugin(ReferenceTo(kotlinPlugin, rootBuild), false)
    rootBuild.applyPlugin("com.android.library", "8.9.0", false)

    // App module build.gradle created by New Project Wizard (`plugins`, `android`, `dependencies`).
    val appBuild = projectModel.getModuleBuildModel(mySubModuleBuildFile)
    appBuild.applyPlugin(ReferenceTo(appPlugin, appBuild), null)
    appBuild.applyPlugin(ReferenceTo(kotlinPlugin, appBuild), null)

    val android = appBuild.android()
    android.namespace().setValue("com.example.myapplication")
    android.compileSdkVersion().setValue(35)
    android.defaultConfig().let { config ->
      config.applicationId().setValue("com.example.myapplication")
      config.minSdkVersion().setValue(24)
      config.targetSdkVersion().setValue(35)
      config.versionCode().setValue(1)
      config.versionName().setValue("1.0")
      config.testInstrumentationRunner().setValue("androidx.test.runner.AndroidJUnitRunner")
      config.manifestPlaceholders().getMapValue("hostName")!!.setValue(RawText("ext.host", "ext.host"))
    }
    android
      .buildTypes()
      .first { it.name() == "release" }
      .let { release ->
        release.minifyEnabled().setValue(false)
        release.proguardFiles().addListValue()!!.setValue("proguard-rules.pro")
      }
    android.compileOptions().let { compileOptions ->
      compileOptions.sourceCompatibility().setLanguageLevel(LanguageLevel.JDK_11)
      compileOptions.targetCompatibility().setLanguageLevel(LanguageLevel.JDK_11)
    }
    android.buildFeatures().compose().setValue(true)

    val dependencies = appBuild.dependencies()
    dependencies.addArtifact("implementation", ReferenceTo(coreKtxLib, dependencies))
    dependencies.addArtifact(
      "implementation",
      "androidx.appcompat:appcompat:1.7.0",
      listOf(ArtifactDependencySpec.create("support-annotations", "com.android.support", null)),
    )
    dependencies.addFileTree("implementation", "libs", listOf("*.jar"), null)

    applyAndVerifyNoResolve(projectModel)

    assertThat(loadNormalizedText(myBuildFile).trimEnd())
      .isEqualTo(
        """
        plugins {
            alias(libs.plugins.android.application) apply false
            alias(libs.plugins.kotlin.android) apply false
            id 'com.android.library' version '8.9.0' apply false
        }
        """
          .trimIndent()
      )

    assertThat(loadNormalizedText(mySubModuleBuildFile).trimEnd())
      .isEqualTo(
        """
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.android)
        }
        android {
            namespace = 'com.example.myapplication'
            compileSdk = 35
            defaultConfig {
                manifestPlaceholders = [hostName: ext.host]
                applicationId = 'com.example.myapplication'
                minSdk = 24
                targetSdk = 35
                versionCode = 1
                versionName = '1.0'
                testInstrumentationRunner = 'androidx.test.runner.AndroidJUnitRunner'
            }
            buildTypes {
                release {
                    minifyEnabled = false
                    proguardFiles 'proguard-rules.pro'
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            buildFeatures {
                compose = true
            }
        }
        dependencies {
            implementation 'androidx.appcompat:appcompat:1.7.0', {
                exclude group: 'com.android.support', module: 'support-annotations'
            }
            implementation fileTree(dir: 'libs', include: ['*.jar'])
            implementation libs.androidx.core.ktx
        }
        """
          .trimIndent()
      )

    // Re-parse and mutate/delete existing live PSI elements in a second applyChanges() pass.
    val reparsedProject = projectBuildModel
    val reparsedApp = reparsedProject.getModuleBuildModel(mySubModuleBuildFile)
    assertThat(reparsedApp.android().compileOptions().sourceCompatibility().toLanguageLevel()).isEqualTo(LanguageLevel.JDK_11)
    reparsedApp.android().compileOptions().sourceCompatibility().setLanguageLevel(LanguageLevel.JDK_17)
    reparsedApp.android().defaultConfig().minSdkVersion().setValue(26)
    reparsedApp.dependencies().remove(reparsedApp.dependencies().fileTrees().single())

    applyAndVerifyNoResolve(reparsedProject)

    assertThat(loadNormalizedText(mySubModuleBuildFile).trimEnd())
      .isEqualTo(
        """
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.android)
        }
        android {
            namespace = 'com.example.myapplication'
            compileSdk = 35
            defaultConfig {
                manifestPlaceholders = [hostName: ext.host]
                applicationId = 'com.example.myapplication'
                minSdk = 26
                targetSdk = 35
                versionCode = 1
                versionName = '1.0'
                testInstrumentationRunner = 'androidx.test.runner.AndroidJUnitRunner'
            }
            buildTypes {
                release {
                    minifyEnabled = false
                    proguardFiles 'proguard-rules.pro'
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_11
            }
            buildFeatures {
                compose = true
            }
        }
        dependencies {
            implementation 'androidx.appcompat:appcompat:1.7.0', {
                exclude group: 'com.android.support', module: 'support-annotations'
            }
            implementation libs.androidx.core.ktx
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun testStatementSyntaxesMapsListsClosuresAndRenamesSkipReferenceResolution() {
    assumeTrue(!isKotlinScript)

    // Start with a file-level block comment to exercise the ML_COMMENT insertion branch.
    writeToBuildFile("/* Header comment */")

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val fileDsl = buildModel.dslElement()

    // Uncreated `ext` parent triggers `needToCreateParent` (`addBefore` branch in GroovyDslWriter).
    buildModel.ext().findProperty("catalogRef").setValue(RawText("libs.some.bundle", "libs.some.bundle"))
    val localVar = buildModel.ext().findProperty("localSdk")
    localVar.setValue(RawText("SdkConstants.LATEST", "SdkConstants.LATEST"))
    localVar.convertToEmptyMap() // exercise map conversion then overwrite in separate property
    localVar.getMapValue("api")!!.setValue(RawText("ApiLevel.CURRENT", "ApiLevel.CURRENT"))
    // Nested derived map inside literal map (`createDerivedMap`).
    val derivedSubMap = localVar.getMapValue("nestedMap")!!
    derivedSubMap.getMapValue("inner")!!.setValue(RawText("InnerRef.ONE", "InnerRef.ONE"))

    // Variable declaration: `def computedVar = UnresolvedType.VALUE`
    val defVar =
      GradleDslLiteral(fileDsl, GradleNameElement.create("computedVar")).apply {
        elementType = VARIABLE
        externalSyntax = ASSIGNMENT
        setValue(RawText("UnresolvedType.VALUE", "UnresolvedType.VALUE"))
      }
    fileDsl.setNewElement(defVar)

    // Augmented assignment (`+=`), `.set(...)` (`SET_METHOD`), and command statement (`METHOD`).
    val augProp =
      GradleDslLiteral(fileDsl, GradleNameElement.create("augItems")).apply {
        elementType = REGULAR
        externalSyntax = AUGMENTED_ASSIGNMENT
        setValue(RawText("ExtraItems.DEFAULT", "ExtraItems.DEFAULT"))
      }
    fileDsl.setNewElement(augProp)

    val setterProp =
      GradleDslLiteral(fileDsl, GradleNameElement.create("maxParallel")).apply {
        elementType = REGULAR
        externalSyntax = SET_METHOD
        setValue(RawText("BuildConfig.MAX_WORKERS", "BuildConfig.MAX_WORKERS"))
      }
    fileDsl.setNewElement(setterProp)

    val cmdProp =
      GradleDslLiteral(fileDsl, GradleNameElement.create("customDirective")).apply {
        elementType = REGULAR
        externalSyntax = METHOD
        setValue(RawText("CustomTarget.DEBUG", "CustomTarget.DEBUG"))
      }
    fileDsl.setNewElement(cmdProp)

    // Empty literal map populated solely with a nested list (`[:]` colon removal in createNamedArgumentList).
    val listOnlyMap = buildModel.ext().findProperty("onlyListMap").convertToEmptyMap()
    val onlyList = listOnlyMap.getMapValue("items")!!.convertToEmptyList()
    onlyList.addListValue()!!.setValue(RawText("ListRef.FIRST", "ListRef.FIRST"))
    onlyList.addListValue()!!.setValue(RawText("ListRef.SECOND", "ListRef.SECOND"))

    // Non-empty literal map receiving a nested list (`setListInMap` front insertion).
    val mixedMap = buildModel.ext().findProperty("mixedMap").convertToEmptyMap()
    mixedMap.getMapValue("first")!!.setValue(RawText("MapRef.A", "MapRef.A"))
    mixedMap.getMapValue("second")!!.setValue(RawText("MapRef.B", "MapRef.B"))
    mixedMap.getMapValue("flags")!!.convertToEmptyList().addListValue()!!.setValue(RawText("FlagRef.ENABLED", "FlagRef.ENABLED"))

    // Literal list (`GrListOrMap`) populated with `GradleDslMethodCall` expressions (`adoptAfter` on `GrListOrMap`).
    val extDsl = buildModel.ext().dslElement()
    val callList =
      GradleDslExpressionList(extDsl, GradleNameElement.create("helperCalls"), true).apply {
        elementType = REGULAR
        externalSyntax = ASSIGNMENT
      }
    extDsl.setNewElement(callList)
    callList.addNewExpression(
      GradleDslMethodCall(callList, GradleNameElement.empty(), "firstHelper").apply {
        addNewArgument(
          GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply {
            setValue(RawText("HelperArg.ONE", "HelperArg.ONE"))
          }
        )
      }
    )
    callList.addNewExpression(
      GradleDslMethodCall(callList, GradleNameElement.empty(), "secondHelper").apply {
        addNewArgument(
          GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply {
            setValue(RawText("HelperArg.TWO", "HelperArg.TWO"))
          }
        )
      }
    )

    // Command-argument map (`GrCommandArgumentList`) with multiple named args + nested list.
    val commandMap =
      GradleDslExpressionMap(fileDsl, GradleNameElement.create("customApply")).apply {
        elementType = REGULAR
        externalSyntax = METHOD
      }
    fileDsl.setNewElement(commandMap)
    commandMap.addNewLiteral("plugin", RawText("PluginIds.APP", "PluginIds.APP"))
    commandMap.addNewLiteral("from", RawText("ScriptPaths.COMMON", "ScriptPaths.COMMON"))
    val cmdNestedList =
      GradleDslExpressionList(commandMap, GradleNameElement.create("targets"), true).apply {
        elementType = REGULAR
        addNewExpression(
          GradleDslLiteral(this, GradleNameElement.empty()).apply {
            setValue(RawText("Targets.LINUX", "Targets.LINUX"))
          }
        )
      }
    commandMap.setNewElement(cmdNestedList)

    // Method call with RawText argument and attached closure (`createDslMethodCall` + `createAndAddClosure`).
    val methodCall =
      GradleDslMethodCall(fileDsl, GradleNameElement.create("assignedTask"), "registerTask").apply {
        externalSyntax = ASSIGNMENT
        addNewArgument(
          GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply {
            setValue(RawText("TaskTypes.VERIFY", "TaskTypes.VERIFY"))
          }
        )
        val closure = GradleDslClosure(this, null, GradleNameElement.create("registerTask"))
        closure.setNewElement(
          GradleDslLiteral(closure, GradleNameElement.create("group")).apply {
            elementType = REGULAR
            externalSyntax = ASSIGNMENT
            setValue(RawText("TaskGroups.CHECK", "TaskGroups.CHECK"))
          }
        )
        setNewClosureElement(closure)
      }
    fileDsl.setNewElement(methodCall)

    // Named domain element creation + later rename (`maybeUpdateName` literal & identifier branches).
    buildModel.android().addProductFlavor("demo").dimension().setValue("tier")

    applyAndVerifyNoResolve(projectModel)

    assertThat(loadNormalizedText(myBuildFile).trimEnd())
      .isEqualTo(
        """
        /* Header comment */
        ext {
            catalogRef = libs.some.bundle
            localSdk = [api: ApiLevel.CURRENT, nestedMap: [inner: InnerRef.ONE]]
            onlyListMap = [items: [ListRef.FIRST, ListRef.SECOND]]
            mixedMap = [flags: [FlagRef.ENABLED], first: MapRef.A, second: MapRef.B]
            helperCalls = [firstHelper(HelperArg.ONE), secondHelper(HelperArg.TWO)]
        }
        def computedVar = UnresolvedType.VALUE
        augItems += ExtraItems.DEFAULT
        maxParallel.set(BuildConfig.MAX_WORKERS)
        customDirective CustomTarget.DEBUG
        customApply plugin: PluginIds.APP, from: ScriptPaths.COMMON, targets: [Targets.LINUX]
        assignedTask = registerTask(TaskTypes.VERIFY) {
            group = TaskGroups.CHECK
        }
        android {
            productFlavors {
                demo {
                    dimension = 'tier'
                }
            }
        }
        """
          .trimIndent()
      )

    // Re-parse and test value replacement (`replaceWithSynthesized`) + property/flavor renaming (`maybeUpdateName`).
    val reparsedProject = projectBuildModel
    val reparsedBuild = reparsedProject.projectBuildModel!!
    reparsedBuild.ext().findProperty("catalogRef").setValue(RawText("libs.updated.bundle", "libs.updated.bundle"))
    reparsedBuild.ext().findProperty("catalogRef").rename("renamedCatalogRef")
    reparsedBuild.android().productFlavors().single { it.name() == "demo" }.rename("full")
    val reparsedExt = reparsedBuild.ext().dslElement()
    val reparsedCallList = reparsedExt.getPropertyElement("helperCalls", GradleDslExpressionList::class.java)!!
    reparsedCallList.addNewExpression(
      GradleDslMethodCall(reparsedCallList, GradleNameElement.empty(), "zeroHelper").apply {
        addNewArgument(
          GradleDslLiteral(argumentsElement, GradleNameElement.empty()).apply {
            setValue(RawText("HelperArg.ZERO", "HelperArg.ZERO"))
          }
        )
      },
      0,
    )

    applyAndVerifyNoResolve(reparsedProject)

    assertThat(loadNormalizedText(myBuildFile).trimEnd())
      .isEqualTo(
        """
        /* Header comment */
        ext {
            renamedCatalogRef = libs.updated.bundle
            localSdk = [api: ApiLevel.CURRENT, nestedMap: [inner: InnerRef.ONE]]
            onlyListMap = [items: [ListRef.FIRST, ListRef.SECOND]]
            mixedMap = [flags: [FlagRef.ENABLED], first: MapRef.A, second: MapRef.B]
            helperCalls = [zeroHelper(HelperArg.ZERO), firstHelper(HelperArg.ONE), secondHelper(HelperArg.TWO)]
        }
        def computedVar = UnresolvedType.VALUE
        augItems += ExtraItems.DEFAULT
        maxParallel.set(BuildConfig.MAX_WORKERS)
        customDirective CustomTarget.DEBUG
        customApply plugin: PluginIds.APP, from: ScriptPaths.COMMON, targets: [Targets.LINUX]
        assignedTask = registerTask(TaskTypes.VERIFY) {
            group = TaskGroups.CHECK
        }
        android {
            productFlavors {
                full {
                    dimension = 'tier'
                }
            }
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun testMoveDslElementClonesAstWithoutReferenceResolution() {
    assumeTrue(!isKotlinScript)

    writeToBuildFile(
      """
      android {
          compileOptions {
              sourceCompatibility JavaVersion.VERSION_11
          }
      }

      ext {
          first = UnresolvedRefs.ALPHA
          second = UnresolvedRefs.BETA
          def third = UnresolvedRefs.GAMMA
      }
      """
        .trimIndent()
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!
    val fileDsl = buildModel.dslElement()
    val extDsl = buildModel.ext().dslElement()
    val extElements = extDsl.elements

    // Reorder statements with unresolved references inside `ext { ... }` and move `ext` above `android`.
    extDsl.moveElementTo(0, extElements["third"]!!)
    extDsl.moveElementTo(2, extElements["first"]!!)
    fileDsl.moveElementTo(0, extDsl)

    applyAndVerifyNoResolve(projectModel)

    assertThat(loadNormalizedText(myBuildFile).trimEnd())
      .isEqualTo(
        """
        ext {
            def third = UnresolvedRefs.GAMMA
            second = UnresolvedRefs.BETA
            first = UnresolvedRefs.ALPHA
        }
        android {
            compileOptions {
                sourceCompatibility JavaVersion.VERSION_11
            }
        }
        """
          .trimIndent()
      )

    // Reparse, add a nested element inside moved blocks, update/delete properties, and verify indentation.
    val reparsedProject = projectBuildModel
    val reparsedBuild = reparsedProject.projectBuildModel!!
    reparsedBuild.ext().findProperty("first").setValue(RawText("UnresolvedRefs.DELTA", "UnresolvedRefs.DELTA"))
    reparsedBuild.ext().findProperty("second").delete()
    reparsedBuild.android().compileOptions().targetCompatibility().setLanguageLevel(LanguageLevel.JDK_11)

    applyAndVerifyNoResolve(reparsedProject)

    assertThat(loadNormalizedText(myBuildFile).trimEnd())
      .isEqualTo(
        """
        ext {
            def third = UnresolvedRefs.GAMMA
            first = UnresolvedRefs.DELTA
        }
        android {
            compileOptions {
                sourceCompatibility JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun testNonLiteralExpressionListMutationsPreserveCommasAndSkipResolve() {
    assumeTrue(!isKotlinScript)

    writeToBuildFile(
      """
      android {
          defaultConfig {
              proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
          }
      }
      dependencies {
          implementation fileTree(dir: 'libs', include: '*.jar'), fileTree('extra')
          implementation project(path: ':one', configuration: 'debug'), project(path: ':two', configuration: 'release'), project(":three")
      }
      """
        .trimIndent()
    )

    val projectModel = projectBuildModel
    val buildModel = projectModel.projectBuildModel!!

    val proguardFiles = buildModel.android().defaultConfig().proguardFiles()
    proguardFiles.addListValueAt(0)!!.setValue(RawText("ProguardConfigs.BASE", "ProguardConfigs.BASE"))

    val firstTree = buildModel.dependencies().fileTrees()[0]
    firstTree.dir().setValue("updatedLibs")
    val includes = firstTree.includes().convertToEmptyList()
    includes.addListValue()!!.setValue("*.aar")
    includes.addListValue()!!.setValue("*.jar")

    val modules = buildModel.dependencies().modules()
    modules[0].setName("renamedOne")
    modules[1].setName("renamedTwo")
    modules[2].setName("renamedThree")

    applyAndVerifyNoResolve(projectModel)

    assertThat(loadNormalizedText(myBuildFile).trimEnd())
      .isEqualTo(
        """
        android {
            defaultConfig {
                proguardFiles ProguardConfigs.BASE, getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            }
        }
        dependencies {
            implementation fileTree(dir: 'updatedLibs', include: ['*.aar', '*.jar']), fileTree('extra')
            implementation project(configuration: 'debug', path: ':renamedOne'), project(configuration: 'release', path: ':renamedTwo'), project(':renamedThree')
        }
        """
          .trimIndent()
      )

    val reparsed = projectBuildModel.projectBuildModel!!
    assertThat(reparsed.dependencies().fileTrees()).hasSize(2)
    assertThat(reparsed.dependencies().modules().map { it.path().forceString() })
      .containsExactly(":renamedOne", ":renamedTwo", ":renamedThree")
      .inOrder()
  }

  @Test
  fun testAdoptAndReplaceHelpersRejectLiveAndReusedElements() {
    assumeTrue(!isKotlinScript)

    writeToBuildFile(
      """
      ext {
          existing = LiveRef.ORIGINAL
          target = LiveRef.TARGET
          items = [ListRef.ONE]
      }
      """
        .trimIndent()
    )
    writeToSubModuleBuildFile(
      """
      ext {
          foreign = ForeignRef.VALUE
      }
      """
        .trimIndent()
    )

    val psiManager = PsiManager.getInstance(myProject)
    val buildPsi = psiManager.findFile(myBuildFile) as GroovyFile
    val foreignPsi = psiManager.findFile(mySubModuleBuildFile) as GroovyFile
    val extClosure = (buildPsi.topStatements.single() as GrMethodCallExpression).closureArguments.single()
    val foreignClosure = (foreignPsi.topStatements.single() as GrMethodCallExpression).closureArguments.single()
    val existingStmt = extClosure.statements[0] as GrAssignmentExpression
    val targetStmt = extClosure.statements[1] as GrAssignmentExpression
    val itemsStmt = extClosure.statements[2] as GrAssignmentExpression
    val itemsList = itemsStmt.rValue as GrListOrMap
    val foreignStmt = foreignClosure.statements.single()
    val factory = GroovyPsiElementFactory.getInstance(myProject)

    encodedReferences.clear()
    dynamicMemberPlaces.clear()

    runWriteCommandAction(myProject) {
      // 1. Live element from the same physical file must be rejected without detaching the original.
      val sameFileErr = LoggedErrorProcessor.executeAndReturnLoggedError {
        GroovyDslUtil.adoptAfter(extClosure, existingStmt, itemsStmt)
        GroovyDslUtil.adoptBefore(extClosure, factory.createLineTerminator(1), existingStmt.nextSibling)
      }
      assertThat(sameFileErr).hasMessageThat().contains("Expected a freshly synthesized, unconsumed PSI element")
      assertThat(existingStmt.isValid).isTrue()
      assertThat(extClosure.statements[0]).isSameAs(existingStmt)

      // 2. Live element from another physical file must be rejected without mutating that file.
      val foreignFileErr = LoggedErrorProcessor.executeAndReturnLoggedError {
        GroovyDslUtil.adoptBefore(extClosure, foreignStmt, extClosure.rBrace)
      }
      assertThat(foreignFileErr).hasMessageThat().contains("Expected a freshly synthesized, unconsumed PSI element")
      assertThat(foreignStmt.containingFile).isSameAs(foreignPsi)

      // 3. Live expression passed to replaceWithSynthesized must be rejected without detaching source.
      val replaceLiveErr = LoggedErrorProcessor.executeAndReturnLoggedError {
        GroovyDslUtil.replaceWithSynthesized(targetStmt.rValue!!, existingStmt.rValue!!)
      }
      assertThat(replaceLiveErr).hasMessageThat().contains("Expected a freshly synthesized, unconsumed PSI element")
      assertThat(existingStmt.text).isEqualTo("existing = LiveRef.ORIGINAL")
      assertThat(targetStmt.text).isEqualTo("target = LiveRef.ORIGINAL")

      // 4. Reusing a synthesized statement across two adopt* calls in the same write action must fail on the second call.
      val synthStmt = factory.createStatementFromText("reused = SynthRef.ONCE")
      GroovyDslUtil.adoptBefore(extClosure, factory.createLineTerminator(1), extClosure.rBrace)
      val firstAdopted = GroovyDslUtil.adoptBefore(extClosure, synthStmt, extClosure.rBrace) as GrAssignmentExpression
      val reuseStmtErr = LoggedErrorProcessor.executeAndReturnLoggedError {
        GroovyDslUtil.adoptAfter(extClosure, synthStmt, firstAdopted)
      }
      assertThat(reuseStmtErr).hasMessageThat().contains("Expected a freshly synthesized, unconsumed PSI element")
      assertThat(firstAdopted.isValid).isTrue()
      assertThat(firstAdopted.parent).isSameAs(extClosure)

      // 5. Passing a child of an already-adopted synthesized subtree must also fail.
      val childOfAdoptedErr = LoggedErrorProcessor.executeAndReturnLoggedError {
        GroovyDslUtil.replaceWithSynthesized(targetStmt.rValue!!, firstAdopted.rValue!!)
      }
      assertThat(childOfAdoptedErr).hasMessageThat().contains("Expected a freshly synthesized, unconsumed PSI element")
      assertThat(firstAdopted.text).isEqualTo("reused = SynthRef.ONCE")

      // 6. Reusing a synthesized expression in emplaceElement* list helpers must fail on the second call.
      val synthListItem = factory.createExpressionFromText("ListRef.TWO")
      val firstListInserted = GroovyDslUtil.emplaceElementToFrontOfList(itemsList, synthListItem)
      val reuseListErr = LoggedErrorProcessor.executeAndReturnLoggedError {
        GroovyDslUtil.emplaceElementIntoList(firstListInserted, itemsList, synthListItem)
      }
      assertThat(reuseListErr).hasMessageThat().contains("Expected a freshly synthesized, unconsumed PSI element")
    }

    PsiDocumentManager.getInstance(myProject).commitAllDocuments()
    runWriteCommandAction(myProject) { FileDocumentManager.getInstance().saveAllDocuments() }

    assertNoReferenceCopyOrDynamicResolve()
    assertThat(loadNormalizedText(mySubModuleBuildFile))
      .isEqualTo(
        """
        ext {
            foreign = ForeignRef.VALUE
        }
        """
          .trimIndent()
      )
    assertThat(itemsList.initializers.map { it.text }).containsExactly("ListRef.TWO", "ListRef.TWO", "ListRef.ONE").inOrder()
  }
}
