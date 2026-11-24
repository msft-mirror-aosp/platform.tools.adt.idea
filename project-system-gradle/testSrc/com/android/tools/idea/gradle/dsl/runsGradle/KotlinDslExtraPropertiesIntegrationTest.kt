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
package com.android.tools.idea.gradle.dsl.runsGradle

import com.android.SdkConstants
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.BOOLEAN_TYPE
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.INTEGER_TYPE
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.STRING_TYPE
import com.android.tools.idea.gradle.dsl.api.ext.ReferenceTo
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.gradle.model.IdeArtifactLibrary
import com.android.tools.idea.gradle.project.model.GradleAndroidDependencyModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.sync.snapshots.TemplateBasedTestProject
import com.android.tools.idea.gradle.project.sync.snapshots.TestProjectDefinition.Companion.prepareTestProject
import com.android.tools.idea.gradle.project.sync.snapshots.testProjectTemplateFromPath
import com.android.tools.idea.gradle.project.sync.snapshots.withAdditionalPatch
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AndroidGradleTests
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import com.android.tools.idea.testing.OpenPreparedProjectOptions
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.gradleModule
import com.android.tools.idea.testing.requestSyncAndWait
import com.android.tools.idea.testing.withoutKtsRelatedIndexing
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests verifying that KotlinScript (`.gradle.kts`) files written or refactored by the Gradle DSL model for `extra` properties
 * actually compile and evaluate cleanly under real Gradle sync, and produce the expected resolved values in the synced Android model.
 *
 * Note: points below that still use the legacy `val ... by extra(...)` form (either as input workarounds where HEAD does not yet emit
 * `project.extra[...]` for same-file collection/non-literal `extra["..."] = ...` declarations, or in expected `build.gradle.kts` outputs
 * written by HEAD) are marked with `TODO(b/535936551)`. This keeps the tests green today while ensuring any migration to `extra["..."] =
 * ...` / `project.extra[...]` produces valid KotlinScript casts and compiles under Gradle.
 */
class KotlinDslExtraPropertiesIntegrationTest {

  @get:Rule val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun testCreateAndReferenceScalarExtraProperties() {
    val preparedProject = projectRule.prepareTestProject(basicKtsProject())
    preparedProject.open(updateOptions = OpenPreparedProjectOptions::withoutKtsRelatedIndexing) { project ->
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()

        val appId = ext.findProperty("appId").apply { setValue("com.example.fromextra") }
        val appIdSuffix = ext.findProperty("appIdSuffix").apply { setValue(".extra") }
        val appVersionCode = ext.findProperty("appVersionCode").apply { setValue(42) }
        val appVersionName = ext.findProperty("appVersionName").apply { setValue("2.5") }
        val useMultiDex = ext.findProperty("useMultiDex").apply { setValue(true) }
        val minifyRelease = ext.findProperty("minifyRelease").apply { setValue(true) }
        val coreKtxVersion = ext.findProperty("coreKtxVersion").apply { setValue("1.9.0") }

        val defaultConfig = buildModel.android().defaultConfig()
        defaultConfig.applicationId().setValue(ReferenceTo(appId, defaultConfig.applicationId()))
        defaultConfig.applicationIdSuffix().setValue(ReferenceTo(appIdSuffix, defaultConfig.applicationIdSuffix()))
        defaultConfig.versionCode().setValue(ReferenceTo(appVersionCode, defaultConfig.versionCode()))
        defaultConfig.versionName().setValue(ReferenceTo(appVersionName, defaultConfig.versionName()))
        defaultConfig.multiDexEnabled().setValue(ReferenceTo(useMultiDex, defaultConfig.multiDexEnabled()))

        val releaseBuildType = buildModel.android().addBuildType("release")
        releaseBuildType.minifyEnabled().setValue(ReferenceTo(minifyRelease, releaseBuildType.minifyEnabled()))

        val coreKtx = buildModel.dependencies().artifacts().first { it.name().forceString() == "core-ktx" }
        coreKtx.version().setValue(ReferenceTo(coreKtxVersion, coreKtx.version()))
      }

      // TODO(b/535936551): HEAD currently writes newly created extra properties as `val ... by extra(...)`
      // and references them as bare identifiers; update this expected file once creation/references migrate
      // to `extra["..."] = ...` / `project.extra["..."] as ...`.
      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }
        val appId by extra("com.example.fromextra")
        val appIdSuffix by extra(".extra")
        val appVersionCode by extra(42)
        val appVersionName by extra("2.5")
        val useMultiDex by extra(true)
        val minifyRelease by extra(true)
        val coreKtxVersion by extra("1.9.0")

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
          compileSdk {
                version = release(36)
            }

          defaultConfig {
            applicationId = appId
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = appVersionCode
            versionName = appVersionName
              applicationIdSuffix = appIdSuffix
              multiDexEnabled = useMultiDex
          }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
            buildTypes {
                getByName("release") {
                    isMinifyEnabled = minifyRelease
                }
            }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:${'$'}coreKtxVersion")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
      )

      val multiVariantData = gradleAndroidModel(project, ":").androidProject.multiVariantData!!
      val syncedDefaultConfig = multiVariantData.defaultConfig
      assertThat(syncedDefaultConfig.applicationId).isEqualTo("com.example.fromextra")
      assertThat(syncedDefaultConfig.applicationIdSuffix).isEqualTo(".extra")
      assertThat(syncedDefaultConfig.versionCode).isEqualTo(42)
      assertThat(syncedDefaultConfig.versionName).isEqualTo("2.5")
      assertThat(syncedDefaultConfig.multiDexEnabled).isTrue()

      val syncedRelease = multiVariantData.buildTypes.single { it.buildType.name == "release" }.buildType
      assertThat(syncedRelease.isMinifyEnabled).isTrue()
      assertCoreKtxVersion(project, ":", "1.9.0")

      runReadAction {
        val reparsed = ProjectBuildModel.get(project).projectBuildModel!!
        assertThat(reparsed.android().defaultConfig().applicationId().getValue(STRING_TYPE)).isEqualTo("com.example.fromextra")
        assertThat(reparsed.android().defaultConfig().versionCode().getValue(INTEGER_TYPE)).isEqualTo(42)
        assertThat(reparsed.android().defaultConfig().multiDexEnabled().getValue(BOOLEAN_TYPE)).isTrue()
      }
    }
  }

  @Test
  fun testReferenceListAndMapElementExtraProperties() {
    val preparedProject =
      projectRule.prepareTestProject(
        basicKtsProject()
          // TODO(b/535936551): Uses `val ... by extra(...)` instead of `extra["..."] = ...` because HEAD does
          // not yet emit `project.extra[...]` for same-file collection element references; switch to (or also
          // cover) `extra["..."] = ...` once b/535936551 lands.
          .withExtraDeclarations(
            """
            val appConfig by extra(mapOf("appId" to "com.example.frommap", "suffix" to ".map"))
            val versionNames by extra(listOf("3.0", "1.9.0"))
            val versionCodes by extra(listOf(7, 8))
            val proguardList by extra(listOf("proguard-rules.pro", "extra-rules.pro"))
            """
              .trimIndent()
          )
      )

    preparedProject.open(updateOptions = OpenPreparedProjectOptions::withoutKtsRelatedIndexing) { project ->
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()
        val appConfigMap = ext.findProperty("appConfig").toMap()!!
        val versionNamesList = ext.findProperty("versionNames").toList()!!
        val versionCodesList = ext.findProperty("versionCodes").toList()!!
        val proguardFilesList = ext.findProperty("proguardList").toList()!!

        val defaultConfig = buildModel.android().defaultConfig()
        defaultConfig.applicationId().setValue(ReferenceTo(appConfigMap["appId"]!!, defaultConfig.applicationId()))
        defaultConfig.applicationIdSuffix().setValue(ReferenceTo(appConfigMap["suffix"]!!, defaultConfig.applicationIdSuffix()))
        defaultConfig.versionName().setValue(ReferenceTo(versionNamesList[0], defaultConfig.versionName()))
        defaultConfig.versionCode().setValue(ReferenceTo(versionCodesList[0], defaultConfig.versionCode()))

        val proguardItem = defaultConfig.proguardFiles().addListValue()!!
        proguardItem.setValue(ReferenceTo(proguardFilesList[0], proguardItem))

        val coreKtx = buildModel.dependencies().artifacts().first { it.name().forceString() == "core-ktx" }
        coreKtx.version().setValue(ReferenceTo(versionNamesList[1], coreKtx.version()))
      }

      // TODO(b/535936551): Asserts HEAD's bare indexed references (`appConfig["..."]`, `versionCodes[0]`,
      // `versionNames[...]`, `proguardList[0]`); update to `project.extra[...]` with valid KotlinScript casts
      // once b/535936551 lands.
      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        val appConfig by extra(mapOf("appId" to "com.example.frommap", "suffix" to ".map"))
        val versionNames by extra(listOf("3.0", "1.9.0"))
        val versionCodes by extra(listOf(7, 8))
        val proguardList by extra(listOf("proguard-rules.pro", "extra-rules.pro"))

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
          compileSdk {
                version = release(36)
            }

          defaultConfig {
            applicationId = appConfig["appId"]
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = versionCodes[0]
            versionName = versionNames[0]
              applicationIdSuffix = appConfig["suffix"]
              proguardFiles(proguardList[0])
          }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:${'$'}{versionNames[1]}")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
      )

      val syncedDefaultConfig = gradleAndroidModel(project, ":").androidProject.multiVariantData!!.defaultConfig
      assertThat(syncedDefaultConfig.applicationId).isEqualTo("com.example.frommap")
      assertThat(syncedDefaultConfig.applicationIdSuffix).isEqualTo(".map")
      assertThat(syncedDefaultConfig.versionName).isEqualTo("3.0")
      assertThat(syncedDefaultConfig.versionCode).isEqualTo(7)
      assertThat(syncedDefaultConfig.proguardFiles.map { it.name }).contains("proguard-rules.pro")
      assertCoreKtxVersion(project, ":", "1.9.0")
    }
  }

  @Test
  fun testReferenceWholeCollectionExtraProperties() {
    val preparedProject =
      projectRule.prepareTestProject(
        basicKtsProject()
          // TODO(b/535936551): Uses `val ... by extra(...)` instead of `extra["..."] = ...` because HEAD does
          // not yet emit `project.extra[...]` for same-file whole-collection references; switch to (or also
          // cover) `extra["..."] = ...` once b/535936551 lands.
          .withExtraDeclarations(
            """
            val proguardList by extra(listOf("proguard-rules.pro", "extra-rules.pro"))
            val resConfigList by extra(listOf("en", "fr"))
            val placeholderMap by extra(mapOf("hostName" to "example.com", "scheme" to "https"))
            val proguardOne by extra("rules-one.pro")
            val proguardTwo by extra("rules-two.pro")
            """
              .trimIndent()
          )
      )

    preparedProject.open(updateOptions = OpenPreparedProjectOptions::withoutKtsRelatedIndexing) { project ->
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()
        val defaultConfig = buildModel.android().defaultConfig()

        defaultConfig.proguardFiles().setValue(ReferenceTo(ext.findProperty("proguardList"), defaultConfig.proguardFiles()))
        defaultConfig.resConfigs().setValue(ReferenceTo(ext.findProperty("resConfigList"), defaultConfig.resConfigs()))
        defaultConfig.manifestPlaceholders().setValue(ReferenceTo(ext.findProperty("placeholderMap"), defaultConfig.manifestPlaceholders()))
      }

      // TODO(b/535936551): Asserts HEAD's bare collection references (`placeholderMap`, `proguardList`,
      // `resConfigList`); update to `project.extra[...]` with valid KotlinScript casts once b/535936551 lands.
      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        val proguardList by extra(listOf("proguard-rules.pro", "extra-rules.pro"))
        val resConfigList by extra(listOf("en", "fr"))
        val placeholderMap by extra(mapOf("hostName" to "example.com", "scheme" to "https"))
        val proguardOne by extra("rules-one.pro")
        val proguardTwo by extra("rules-two.pro")

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
          compileSdk {
                version = release(36)
            }

          defaultConfig {
            manifestPlaceholders += placeholderMap
              applicationId = "com.example.kotlingradle"
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = 1
            versionName = "1.0"
              setProguardFiles(proguardList)
              resourceConfigurations += resConfigList
          }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:1.6.0")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
      )

      val firstSyncDefaultConfig = gradleAndroidModel(project, ":").androidProject.multiVariantData!!.defaultConfig
      assertThat(firstSyncDefaultConfig.proguardFiles.map { it.name }).containsExactly("proguard-rules.pro", "extra-rules.pro")
      assertThat(firstSyncDefaultConfig.resourceConfigurations).containsExactly("en", "fr")
      assertThat(firstSyncDefaultConfig.manifestPlaceholders).containsExactly("hostName", "example.com", "scheme", "https")

      // Convert proguardFiles from a whole-list reference back to individual scalar extra references.
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()
        val proguardOne = ext.findProperty("proguardOne")
        val proguardTwo = ext.findProperty("proguardTwo")

        buildModel.android().defaultConfig().proguardFiles().run {
          convertToEmptyList()
          addListValue()!!.let { it.setValue(ReferenceTo(proguardOne, it)) }
          addListValue()!!.let { it.setValue(ReferenceTo(proguardTwo, it)) }
        }
      }

      // TODO(b/535936551): Asserts HEAD's bare references (`placeholderMap`, `proguardOne`, `proguardTwo`,
      // `resConfigList`); update to `project.extra[...]` with valid KotlinScript casts once b/535936551 lands.
      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        val proguardList by extra(listOf("proguard-rules.pro", "extra-rules.pro"))
        val resConfigList by extra(listOf("en", "fr"))
        val placeholderMap by extra(mapOf("hostName" to "example.com", "scheme" to "https"))
        val proguardOne by extra("rules-one.pro")
        val proguardTwo by extra("rules-two.pro")

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
          compileSdk {
                version = release(36)
            }

          defaultConfig {
            manifestPlaceholders += placeholderMap
              applicationId = "com.example.kotlingradle"
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = 1
            versionName = "1.0"
              proguardFiles(proguardOne, proguardTwo)
              resourceConfigurations += resConfigList
          }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:1.6.0")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
      )

      val secondSyncDefaultConfig = gradleAndroidModel(project, ":").androidProject.multiVariantData!!.defaultConfig
      assertThat(secondSyncDefaultConfig.proguardFiles.map { it.name }).containsExactly("rules-one.pro", "rules-two.pro")
    }
  }

  @Test
  fun testReferenceNonLiteralAndChainedExtraProperties() {
    val preparedProject =
      projectRule.prepareTestProject(
        basicKtsProject()
          // TODO(b/535936551): Uses `val ... by extra(...)` instead of `extra["..."] = ...` because HEAD does
          // not yet emit `project.extra[...]` for same-file non-literal/chained extra references; switch to
          // (or also cover) `extra["..."] = ...` once b/535936551 lands.
          .withExtraDeclarations(
            """
            val baseSuffix by extra(".base")
            val derivedSuffix by extra(baseSuffix)
            val computedAppId by extra("com.example." + "computed")
            val computedVersionCode by extra(10 + 5)
            val computedProguard by extra(listOf(file("proguard-rules.pro"), "extra-rules.pro"))
            """
              .trimIndent()
          )
      )

    preparedProject.open(updateOptions = OpenPreparedProjectOptions::withoutKtsRelatedIndexing) { project ->
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()
        val defaultConfig = buildModel.android().defaultConfig()

        defaultConfig.applicationIdSuffix().setValue(ReferenceTo(ext.findProperty("derivedSuffix"), defaultConfig.applicationIdSuffix()))
        defaultConfig.applicationId().setValue(ReferenceTo(ext.findProperty("computedAppId"), defaultConfig.applicationId()))
        defaultConfig.versionCode().setValue(ReferenceTo(ext.findProperty("computedVersionCode"), defaultConfig.versionCode()))
        defaultConfig.proguardFiles().setValue(ReferenceTo(ext.findProperty("computedProguard"), defaultConfig.proguardFiles()))
      }

      // TODO(b/535936551): Asserts HEAD's bare references (`computedAppId`, `computedVersionCode`,
      // `derivedSuffix`, `computedProguard`); update to `project.extra[...]` with valid KotlinScript casts
      // once b/535936551 lands.
      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        val baseSuffix by extra(".base")
        val derivedSuffix by extra(baseSuffix)
        val computedAppId by extra("com.example." + "computed")
        val computedVersionCode by extra(10 + 5)
        val computedProguard by extra(listOf(file("proguard-rules.pro"), "extra-rules.pro"))

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
          compileSdk {
                version = release(36)
            }

          defaultConfig {
            applicationId = computedAppId
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = computedVersionCode
            versionName = "1.0"
              applicationIdSuffix = derivedSuffix
              setProguardFiles(computedProguard)
          }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:1.6.0")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
      )

      val syncedDefaultConfig = gradleAndroidModel(project, ":").androidProject.multiVariantData!!.defaultConfig
      assertThat(syncedDefaultConfig.applicationIdSuffix).isEqualTo(".base")
      assertThat(syncedDefaultConfig.applicationId).isEqualTo("com.example.computed")
      assertThat(syncedDefaultConfig.versionCode).isEqualTo(15)
      assertThat(syncedDefaultConfig.proguardFiles.map { it.name }).containsExactly("proguard-rules.pro", "extra-rules.pro")
    }
  }

  @Test
  fun testArrayStyleExtraPropertiesAndMutations() {
    val compileSdk = AgpVersionSoftwareEnvironmentDescriptor.selected.compileSdk.substringBefore('.').toInt()
    val preparedProject =
      projectRule.prepareTestProject(
        basicKtsProject()
          .withExtraDeclarations(
            """
          extra["sdkVersion"] = $compileSdk
          extra["appVersionName"] = "4.0"
          extra["altVersionName"] = "4.1"
          extra["enableMultiDex"] = true
          extra["proguardOne"] = "rules-one.pro"
          extra["proguardTwo"] = "rules-two.pro"
          """
              .trimIndent()
          )
      )

    preparedProject.open(updateOptions = OpenPreparedProjectOptions::withoutKtsRelatedIndexing) { project ->
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()
        val android = buildModel.android()
        val defaultConfig = android.defaultConfig()

        android.compileSdkVersion().setValue(ReferenceTo(ext.findProperty("sdkVersion"), android.compileSdkVersion()))
        defaultConfig.versionName().setValue(ReferenceTo(ext.findProperty("appVersionName"), defaultConfig.versionName()))
        defaultConfig.multiDexEnabled().setValue(ReferenceTo(ext.findProperty("enableMultiDex"), defaultConfig.multiDexEnabled()))
        defaultConfig.proguardFiles().run {
          addListValue()!!.let { it.setValue(ReferenceTo(ext.findProperty("proguardOne"), it)) }
          addListValue()!!.let { it.setValue(ReferenceTo(ext.findProperty("proguardTwo"), it)) }
        }
      }

      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        extra["sdkVersion"] = $compileSdk
        extra["appVersionName"] = "4.0"
        extra["altVersionName"] = "4.1"
        extra["enableMultiDex"] = true
        extra["proguardOne"] = "rules-one.pro"
        extra["proguardTwo"] = "rules-two.pro"

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
            compileSdk {
                version = release(project.extra["sdkVersion"] as Int)
            }

            defaultConfig {
            applicationId = "com.example.kotlingradle"
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = 1
            versionName = project.extra["appVersionName"] as String
                multiDexEnabled = project.extra["enableMultiDex"] as Boolean
                proguardFiles(
                    project.extra["proguardOne"] as String,
                    project.extra["proguardTwo"] as String
                )
            }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:1.6.0")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
        updateCompileSdk = false,
      )

      val firstSyncModel = gradleAndroidModel(project, ":")
      assertThat(firstSyncModel.androidProject.compileTarget).isEqualTo("android-$compileSdk")
      val firstDefaultConfig = firstSyncModel.androidProject.multiVariantData!!.defaultConfig
      assertThat(firstDefaultConfig.versionName).isEqualTo("4.0")
      assertThat(firstDefaultConfig.multiDexEnabled).isTrue()
      assertThat(firstDefaultConfig.proguardFiles.map { it.name }).containsExactly("rules-one.pro", "rules-two.pro")

      // Replace and delete cast references to verify no dangling `as T` casts remain.
      editRootBuildModelAndSync(project) { buildModel ->
        val ext = buildModel.ext()
        val defaultConfig = buildModel.android().defaultConfig()

        defaultConfig.versionName().setValue(ReferenceTo(ext.findProperty("altVersionName"), defaultConfig.versionName()))
        defaultConfig.proguardFiles().toList()!![0].delete()
        defaultConfig.multiDexEnabled().delete()
      }

      assertBuildFileContents(
        projectRoot.resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        extra["sdkVersion"] = $compileSdk
        extra["appVersionName"] = "4.0"
        extra["altVersionName"] = "4.1"
        extra["enableMultiDex"] = true
        extra["proguardOne"] = "rules-one.pro"
        extra["proguardTwo"] = "rules-two.pro"

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
            compileSdk {
                version = release(project.extra["sdkVersion"] as Int)
            }

            defaultConfig {
            applicationId = "com.example.kotlingradle"
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = 1
            versionName = project.extra["altVersionName"] as String
                proguardFiles(project.extra["proguardTwo"] as String)
            }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:1.6.0")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
        updateCompileSdk = false,
      )

      val secondDefaultConfig = gradleAndroidModel(project, ":").androidProject.multiVariantData!!.defaultConfig
      assertThat(secondDefaultConfig.versionName).isEqualTo("4.1")
      assertThat(secondDefaultConfig.multiDexEnabled).isNull()
      assertThat(secondDefaultConfig.proguardFiles.map { it.name }).containsExactly("rules-two.pro")
    }
  }

  @Test
  fun testCrossModuleRootProjectExtraProperties() {
    val preparedProject =
      projectRule.prepareTestProject(
        basicKtsProject().withAdditionalPatch { root ->
          val appDir = root.resolve("app").apply { mkdirs() }
          check(root.resolve(SdkConstants.FN_BUILD_GRADLE_KTS).renameTo(appDir.resolve(SdkConstants.FN_BUILD_GRADLE_KTS)))
          check(root.resolve("src").renameTo(appDir.resolve("src")))

          root
            .resolve(SdkConstants.FN_BUILD_GRADLE_KTS)
            .writeText(
              """
              plugins {
                alias(libs.plugins.android.application) apply false
              }

              extra["rootAppId"] = "com.example.rootapp"
              extra["rootVersionCode"] = 99
              extra["rootMultiDex"] = true
              extra["rootVersionList"] = listOf("5.0", "1.9.0")
              extra["rootConfigMap"] = mapOf("suffix" to ".root")
              """
                .trimIndent() + "\n"
            )
          root.resolve(SdkConstants.FN_SETTINGS_GRADLE_KTS).appendText("\ninclude(\":app\")\n")
        }
      )

    preparedProject.open(updateOptions = OpenPreparedProjectOptions::withoutKtsRelatedIndexing) { project ->
      editBuildModelsAndSync(
        project,
        readModels = { projectBuildModel ->
          readBuildModel(projectBuildModel, project, ":") to readBuildModel(projectBuildModel, project, ":app")
        },
      ) { (rootBuildModel, appBuildModel) ->
        val rootExt = rootBuildModel.ext()
        val defaultConfig = appBuildModel.android().defaultConfig()

        defaultConfig.applicationId().setValue(ReferenceTo(rootExt.findProperty("rootAppId"), defaultConfig.applicationId()))
        defaultConfig.versionCode().setValue(ReferenceTo(rootExt.findProperty("rootVersionCode"), defaultConfig.versionCode()))
        defaultConfig.multiDexEnabled().setValue(ReferenceTo(rootExt.findProperty("rootMultiDex"), defaultConfig.multiDexEnabled()))

        val rootVersionList = rootExt.findProperty("rootVersionList").toList()!!
        defaultConfig.versionName().setValue(ReferenceTo(rootVersionList[0], defaultConfig.versionName()))

        val rootConfigMap = rootExt.findProperty("rootConfigMap").toMap()!!
        defaultConfig.applicationIdSuffix().setValue(ReferenceTo(rootConfigMap["suffix"]!!, defaultConfig.applicationIdSuffix()))

        val coreKtx = appBuildModel.dependencies().artifacts().first { it.name().forceString() == "core-ktx" }
        coreKtx.version().setValue(ReferenceTo(rootVersionList[1], coreKtx.version()))
      }

      assertBuildFileContents(
        projectRoot.resolve("app").resolve(SdkConstants.FN_BUILD_GRADLE_KTS),
        """
        plugins {
          alias(libs.plugins.android.application) apply true
        }

        @Suppress("DEPRECATION")
        android {
          namespace = "com.example.kotlingradle"
          compileSdk {
                version = release(36)
            }

          defaultConfig {
            applicationId = rootProject.extra["rootAppId"] as String
            minSdk { version = release(24) }
            targetSdk { version = release(33) }
            versionCode = rootProject.extra["rootVersionCode"] as Int
            versionName = (rootProject.extra["rootVersionList"] as List<*>)[0] as String
              multiDexEnabled = rootProject.extra["rootMultiDex"] as Boolean
              applicationIdSuffix = (rootProject.extra["rootConfigMap"] as Map<*, *>)["suffix"] as String
          }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        kotlin {
          compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
          }
        }

        dependencies {
          implementation("androidx.core:core-ktx:${'$'}{(rootProject.extra["rootVersionList"] as List<*>)[1]}")
          implementation("androidx.appcompat:appcompat:1.3.0")
        }
        """
          .trimIndent(),
      )

      val syncedDefaultConfig = gradleAndroidModel(project, ":app").androidProject.multiVariantData!!.defaultConfig
      assertThat(syncedDefaultConfig.applicationId).isEqualTo("com.example.rootapp")
      assertThat(syncedDefaultConfig.versionCode).isEqualTo(99)
      assertThat(syncedDefaultConfig.multiDexEnabled).isTrue()
      assertThat(syncedDefaultConfig.versionName).isEqualTo("5.0")
      assertThat(syncedDefaultConfig.applicationIdSuffix).isEqualTo(".root")
      assertCoreKtxVersion(project, ":app", "1.9.0")
    }
  }

  private fun basicKtsProject(): TemplateBasedTestProject =
    testProjectTemplateFromPath(TestProjectPaths.BASIC_KOTLIN_GRADLE_DSL, "tools/adt/idea/android/testData")

  private fun TemplateBasedTestProject.withExtraDeclarations(declarations: String): TemplateBasedTestProject = withAdditionalPatch { root ->
    val buildFile = root.resolve(SdkConstants.FN_BUILD_GRADLE_KTS)
    val content = buildFile.readText()
    val anchor = "\n@Suppress(\"DEPRECATION\")\nandroid {"
    val updated =
      if (content.contains(anchor)) {
        content.replaceFirst(anchor, "\n$declarations\n$anchor")
      } else {
        val fallbackAnchor = "\nandroid {"
        check(content.contains(fallbackAnchor)) { "Expected android block in $buildFile:\n$content" }
        content.replaceFirst(fallbackAnchor, "\n$declarations\n$fallbackAnchor")
      }
    buildFile.writeText(updated)
  }

  private fun assertBuildFileContents(buildFile: File, expected: String, updateCompileSdk: Boolean = true) {
    val actual = buildFile.readText().replace("\r\n", "\n")
    val expectedNormalized =
      if (updateCompileSdk) {
        AndroidGradleTests.updateCompileSdkVersion(expected, AgpVersionSoftwareEnvironmentDescriptor.selected.compileSdk)
      } else {
        expected
      }
    assertThat(actual).isEqualTo(expectedNormalized)
  }

  private fun readBuildModel(projectBuildModel: ProjectBuildModel, project: Project, gradlePath: String): GradleBuildModel {
    return if (gradlePath == ":") {
      checkNotNull(projectBuildModel.projectBuildModel) { "Expected root projectBuildModel" }
    } else {
      val module = checkNotNull(project.gradleModule(gradlePath)) { "Expected module for Gradle path $gradlePath" }
      checkNotNull(projectBuildModel.getModuleBuildModel(module)) { "Expected GradleBuildModel for $gradlePath" }
    }
  }

  private fun editRootBuildModelAndSync(project: Project, edit: (GradleBuildModel) -> Unit) {
    editBuildModelsAndSync(project, readModels = { readBuildModel(it, project, ":") }, edit = edit)
  }

  private fun <T> editBuildModelsAndSync(project: Project, readModels: (ProjectBuildModel) -> T, edit: (T) -> Unit) {
    val (projectBuildModel, models) =
      runReadAction {
        val projectBuildModel = ProjectBuildModel.get(project)
        projectBuildModel to readModels(projectBuildModel)
      }
    WriteCommandAction.runWriteCommandAction(project) {
      edit(models)
      projectBuildModel.applyChanges()
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      FileDocumentManager.getInstance().saveAllDocuments()
    }
    project.requestSyncAndWait()
  }

  private fun gradleAndroidModel(project: Project, gradlePath: String): GradleAndroidModel {
    val module = checkNotNull(project.gradleModule(gradlePath)) { "Expected module for Gradle path $gradlePath" }
    return checkNotNull(GradleAndroidModel.get(module)) { "Expected GradleAndroidModel for $gradlePath" }
  }

  private fun assertCoreKtxVersion(project: Project, gradlePath: String, expectedVersion: String) {
    val module = checkNotNull(project.gradleModule(gradlePath)) { "Expected module for Gradle path $gradlePath" }
    val dependencyModel = checkNotNull(GradleAndroidDependencyModel.get(module)) { "Expected GradleAndroidDependencyModel for $gradlePath" }
    val resolvedVersion =
      dependencyModel.mainArtifactWithDependencies.compileClasspath.libraries
        .filterIsInstance<IdeArtifactLibrary>()
        .firstOrNull { it.component?.group == "androidx.core" && it.component?.name == "core-ktx" }
        ?.component
        ?.version
        ?.toString()
    assertThat(resolvedVersion).isEqualTo(expectedVersion)

    val reparsedVersion = runReadAction {
      val buildModel = readBuildModel(ProjectBuildModel.get(project), project, gradlePath)
      buildModel.dependencies().artifacts().first { it.name().forceString() == "core-ktx" }.version().getValue(STRING_TYPE)
    }
    assertThat(reparsedVersion).isEqualTo(expectedVersion)
  }
}
