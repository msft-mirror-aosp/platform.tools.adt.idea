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
package com.android.tools.idea.gradle.project.entities.impl

import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import com.intellij.platform.workspace.storage.metadata.impl.MetadataStorageBase
import com.intellij.platform.workspace.storage.metadata.model.ExtendableClassMetadata
import com.intellij.platform.workspace.storage.metadata.model.FinalClassMetadata
import com.intellij.platform.workspace.storage.metadata.model.StorageTypeMetadata
import com.intellij.platform.workspace.storage.metadata.model.ValueTypeMetadata
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.runners.model.Statement

/**
 * Verifies via reflection that all classes and properties recorded in `MetadataStorageImpl` stay in sync with their runtime class
 * declarations.
 *
 * If this test fails after modifying an entity or model class reachable from `GradleAndroidModelEntity`, `GradleModuleModelEntity`, or
 * `GradleProjectPathEntity`, regenerate the Workspace Model implementation files under `tools/adt/idea/project-system-gradle/gen/` using
 * the `"Generate Workspace Model Implementation"` action in IntelliJ IDEA.
 */
@RunWith(JUnit4::class)
class MetadataStorageImplTest {

  private val expect: Expect = Expect.create()

  @get:Rule
  val rule: TestRule = TestRule { base, description ->
    val delegate = expect.apply(base, description)
    object : Statement() {
      override fun evaluate() {
        try {
          delegate.evaluate()
        } catch (e: AssertionError) {
          throw AssertionError("$REMEDIATION_MESSAGE\n\n${e.message}", e)
        }
      }
    }
  }

  @Test
  fun metadataMatchesReflection() {
    val storage =
      Class.forName("com.android.tools.idea.gradle.project.entities.impl.MetadataStorageImpl").getDeclaredField("INSTANCE").get(null)
        as MetadataStorageBase
    // Trigger lazy initialization of MetadataStorageBase.metadataByTypeFqn.
    storage.getMetadataByTypeFqnOrNull("")
    val field =
      MetadataStorageBase::class.java.getDeclaredField("metadataByTypeFqn").apply {
        isAccessible = true
      }
    @Suppress("UNCHECKED_CAST") val metadataByTypeFqn = field.get(storage) as Map<String, StorageTypeMetadata>
    assertThat(metadataByTypeFqn).isNotEmpty()

    val enumBaseProperties = Enum::class.memberProperties.map { it.name }.toSet() + "declaringJavaClass"

    for ((fqName, metadata) in metadataByTypeFqn.entries.sortedBy { it.key }) {
      val clazz = Class.forName(fqName)

      if (metadata is ExtendableClassMetadata) {
        expect.withMessage("$fqName properties").that(metadata.properties).isEmpty()
        val expectedSubclasses = metadata.subclasses.map { it.fqName.replace('$', '.') }.toSet()
        val actualSubclasses = clazz.kotlin.leafSealedSubclasses().map { it.java.name.replace('$', '.') }.toSet()
        expect
          .withMessage("$fqName sealed subclasses in MetadataStorageImpl")
          .that(expectedSubclasses)
          .containsExactlyElementsIn(actualSubclasses)
        continue
      }

      if (metadata is FinalClassMetadata.EnumClassMetadata) {
        val actualEnumValues = clazz.enumConstants.map { (it as Enum<*>).name }
        expect.withMessage("$fqName enum values in MetadataStorageImpl").that(metadata.values).containsExactlyElementsIn(actualEnumValues)
      }

      val isKotlinClass = clazz.isAnnotationPresent(Metadata::class.java)
      if (!isKotlinClass) {
        expect.withMessage("$fqName (Java class) properties").that(metadata.properties).isEmpty()
        continue
      }

      val actualPropertiesByName =
        clazz.kotlin.memberProperties
          .filter { prop ->
            metadata !is FinalClassMetadata.EnumClassMetadata || prop.name !in enumBaseProperties
          }
          .associateBy { it.name }

      val expectedPropertyNames = metadata.properties.map { it.name }.toSet()
      expect
        .withMessage("$fqName properties in MetadataStorageImpl")
        .that(expectedPropertyNames)
        .containsExactlyElementsIn(actualPropertiesByName.keys)

      for (propMetadata in metadata.properties) {
        val actualProp = actualPropertiesByName[propMetadata.name] ?: continue
        val isKnownClass =
          ((propMetadata.valueType as? ValueTypeMetadata.SimpleType.CustomType)?.typeMetadata is FinalClassMetadata.KnownClass)
        expect
          .withMessage("$fqName.${propMetadata.name} type in MetadataStorageImpl")
          .that(propMetadata.valueType.toTypeDescription())
          .isEqualTo(actualProp.returnType.toTypeDescription(rawKnownClass = isKnownClass))
      }
    }
  }
}

private fun KClass<*>.leafSealedSubclasses(): List<KClass<*>> = sealedSubclasses.flatMap {
  if (it.isSealed) it.leafSealedSubclasses() else listOf(it)
}

private fun ValueTypeMetadata.toTypeDescription(): String =
  when (this) {
    is ValueTypeMetadata.SimpleType.PrimitiveType -> "$type${if (isNullable) "?" else ""}"
    is ValueTypeMetadata.SimpleType.CustomType -> "${typeMetadata.fqName.replace('$', '.')}${if (isNullable) "?" else ""}"
    is ValueTypeMetadata.ParameterizedType ->
      "${primitive.toTypeDescription().removeSuffix("?")}<${generics.joinToString(", ") { it.toTypeDescription() }}>${if (primitive.isNullable) "?" else ""}"
    is ValueTypeMetadata.EntityReference -> "${entityFqName.replace('$', '.')}${if (isNullable) "?" else ""}"
  }

private fun KType.toTypeDescription(rawKnownClass: Boolean = false): String {
  val cls = classifier as? KClass<*> ?: return toString()
  val qName = cls.qualifiedName ?: cls.java.name.replace('$', '.')
  val base =
    when (qName) {
      "kotlin.Boolean",
      "kotlin.Int",
      "kotlin.Long",
      "kotlin.String",
      "kotlin.Byte",
      "kotlin.Short",
      "kotlin.Float",
      "kotlin.Double",
      "kotlin.Char",
      "kotlin.Any" -> qName.removePrefix("kotlin.")
      "kotlin.collections.List",
      "kotlin.collections.Set",
      "kotlin.collections.Map" -> qName.removePrefix("kotlin.collections.")
      "java.util.EnumSet" -> "Set"
      else -> qName
    }
  val args = if (rawKnownClass) emptyList() else arguments.map { it.type?.toTypeDescription() ?: "*" }
  val genericPart = if (args.isEmpty()) "" else "<${args.joinToString(", ")}>"
  return "$base$genericPart${if (isMarkedNullable) "?" else ""}"
}

private val REMEDIATION_MESSAGE =
  """
  Workspace Model metadata in MetadataStorageImpl.kt is out of sync with runtime class declarations!

  Why this failed:
    A class reachable from the Workspace Model entities in `intellij.android.projectSystem.gradle`
    (`GradleAndroidModelEntity`, `GradleModuleModelEntity`, or `GradleProjectPathEntity`) changed its
    Kotlin properties, sealed subclasses, or enum constants without updating
    `tools/adt/idea/project-system-gradle/gen/com/android/tools/idea/gradle/project/entities/impl/MetadataStorageImpl.kt`.
    Stale metadata in `MetadataStorageImpl.kt` causes runtime errors when the IDE serializes or
    deserializes cached Workspace Model entities (see b/532118189).

  How to fix:
    1. Use IntelliJ IDEA 2026.2 (build `262.*`, matching `prebuilts/studio/intellij-sdk/AI/build.txt`
       `AI-262.10968.63`) and install the "Plugin DevKit" (`org.jetbrains.idea.devkit`) plugin version
       `262.*` from the JetBrains Marketplace (Plugin DevKit is not bundled; version `262.*` targets
       Workspace Model `CodeGeneratorVersions` `3.7.21`: `API_VERSION_INTERNAL = 3`,
       `IMPL_MAJOR_VERSION_INTERNAL = 7`, `IMPL_MINOR_VERSION_INTERNAL = 21`).
    2. Temporarily delete `prebuilts/tools/common/lint-psi/intellij-core` before running the generator
       (and restore it afterwards) so Plugin DevKit's PSI lookup for
       `com.intellij.platform.workspace.storage.CodeGeneratorVersions` resolves the current version from
       `prebuilts/studio/intellij-sdk` rather than an older copy in `lint-psi/intellij-core`.
    3. Open `tools/adt/idea` in IntelliJ IDEA 2026.2, open one of the entity files in
       `intellij.android.projectSystem.gradle` (for example,
       `tools/adt/idea/project-system-gradle/src/com/android/tools/idea/gradle/project/entities/GradleAndroidModelEntity.kt`)
       or select the `intellij.android.projectSystem.gradle` module in the Project view, and run the
       "Generate Workspace Model Implementation" action (Find Action -> "Generate Workspace Model
       Implementation") to regenerate the files under `tools/adt/idea/project-system-gradle/gen/`.
  """
    .trimIndent()
