/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.model

import com.android.tools.idea.gradle.model.IdeDependencies
import com.android.tools.idea.gradle.model.IdeLibrary
import com.android.tools.idea.gradle.model.IdeModuleLibraryImpl
import com.android.tools.idea.gradle.model.LibraryReference
import com.android.tools.idea.gradle.model.ResolverType
import com.android.tools.idea.gradle.model.impl.IdeAndroidArtifactImpl
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.model.impl.IdeDependenciesCoreDirect
import com.android.tools.idea.gradle.model.impl.IdeDependenciesCoreRef
import com.android.tools.idea.gradle.model.impl.IdeDependencyCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeJavaArtifactImpl
import com.android.tools.idea.gradle.model.impl.IdeLibraryModelResolverImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.impl.IdeResolvedLibraryTableImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantImpl
import com.android.tools.idea.gradle.project.sync.InternedModels
import com.android.tools.idea.testing.AndroidModuleDependency
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.google.common.truth.Truth.assertThat
import java.io.File
import nl.jqno.equalsverifier.EqualsVerifier
import nl.jqno.equalsverifier.Warning
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ModelEqualityTest {
  val example =
    AndroidProjectBuilder()
      .build()
      .invoke("projectName", ":", File("root"), File("root/module"), "9.0.0", InternedModels(buildRootDirectory = null))

  /** A variant whose main, device test and host test artifacts all depend on the `:lib` module. */
  private val variantWithLibDependency: IdeVariantCoreImpl =
    AndroidProjectBuilder(androidModuleDependencyList = { listOf(AndroidModuleDependency(":lib", "debug")) })
      .build()
      .invoke("projectName", ":app", File("root"), File("root/app"), "9.0.0", InternedModels(buildRootDirectory = null))
      .variants
      .first()

  /** Index of the `:lib` dependency in the library table. */
  private val libIndex: Int = variantWithLibDependency.mainArtifact.compileClasspathCore.dependencies.single().target.libraryIndex

  /** Size of the library table referenced by [variantWithLibDependency]. */
  private val libraryTableSize: Int =
    (listOf(variantWithLibDependency.mainArtifact) + variantWithLibDependency.deviceTestArtifacts)
      .flatMap { it.compileClasspathCore.dependencies + it.runtimeClasspathCore.dependencies }
      .plus(
        variantWithLibDependency.hostTestArtifacts.flatMap { it.compileClasspathCore.dependencies + it.runtimeClasspathCore.dependencies }
      )
      .maxOf { it.target.libraryIndex } + 1

  private val classpathReferencingLibrary0 =
    IdeDependenciesCoreDirect(listOf(IdeDependencyCoreImpl(LibraryReference(0), dependencies = listOf())))

  @Test
  fun testIdeAndroidProjectImpl() {
    EqualsVerifier.forClass(IdeAndroidProjectImpl::class.java)
      .withCachedHashCode("hashCode", "computeHashCode", example.androidProject)
      .verify()
  }

  @Test
  fun testIdeAndroidArtifactImpl() {
    EqualsVerifier.forClass(IdeAndroidArtifactImpl::class.java)
      .withPrefabValues(
        IdeDependencies::class.java,
        IdeDependencies(classpathReferencingLibrary0, resolverOf(listOf(listOf(moduleLibrary(":red"))))),
        IdeDependencies(classpathReferencingLibrary0, resolverOf(listOf(listOf(moduleLibrary(":blue"))))),
      )
      // Delegates equality check to core and the libraries its classpaths resolve to, the rest of the fields are derived from core.
      .withOnlyTheseFields("core", "compileClasspath", "runtimeClasspath")
      // hashCode intentionally only uses core to avoid resolving libraries.
      .suppress(Warning.STRICT_HASHCODE)
      .verify()
  }

  @Test
  fun testIdeJavaArtifactImpl() {
    EqualsVerifier.forClass(IdeJavaArtifactImpl::class.java)
      .withPrefabValues(
        IdeDependencies::class.java,
        IdeDependencies(classpathReferencingLibrary0, resolverOf(listOf(listOf(moduleLibrary(":red"))))),
        IdeDependencies(classpathReferencingLibrary0, resolverOf(listOf(listOf(moduleLibrary(":blue"))))),
      )
      // Delegates equality check to core and the libraries its classpaths resolve to, the rest of the fields are derived from core.
      .withOnlyTheseFields("core", "compileClasspath", "runtimeClasspath")
      // hashCode intentionally only uses core to avoid resolving libraries.
      .suppress(Warning.STRICT_HASHCODE)
      .verify()
  }

  @Test
  fun testIdeDependencies() {
    val dependencies = listOf(IdeDependencyCoreImpl(LibraryReference(0), dependencies = listOf(1, 2, 3, 4, 5)))
    val direct = IdeDependenciesCoreDirect(dependencies)
    val ref = IdeDependenciesCoreRef(direct, 1, dependencies)

    EqualsVerifier.forClass(IdeDependenciesCoreDirect::class.java).withCachedHashCode("hashCode", "computeHashCode", direct).verify()
    EqualsVerifier.forClass(IdeDependenciesCoreRef::class.java)
      .withOnlyTheseFields("dependencies") // delegates equality check to dependencies, rest of the fields are used to derive it
      .withCachedHashCode("hashCode", "computeHashCode", ref)
      .verify()
  }

  @Test
  fun testIdeVariantImpl() {
    val mainArtifactCore =
      example.variants
        .first()
        .mainArtifact
        .copy(
          compileClasspathCore = classpathReferencingLibrary0,
          runtimeClasspathCore = classpathReferencingLibrary0,
        )
    val hostTestArtifactCore =
      example.variants
        .first()
        .hostTestArtifacts
        .first()
        .copy(
          compileClasspathCore = classpathReferencingLibrary0,
          runtimeClasspathCore = classpathReferencingLibrary0,
        )
    val redResolver = resolverOf(listOf(listOf(moduleLibrary(":red"))))
    val blueResolver = resolverOf(listOf(listOf(moduleLibrary(":blue"))))
    EqualsVerifier.forClass(IdeVariantImpl::class.java)
      .withPrefabValues(
        IdeAndroidArtifactImpl::class.java,
        IdeAndroidArtifactImpl(mainArtifactCore, redResolver),
        IdeAndroidArtifactImpl(mainArtifactCore, blueResolver),
      )
      .withPrefabValues(
        IdeJavaArtifactImpl::class.java,
        IdeJavaArtifactImpl(hostTestArtifactCore, redResolver),
        IdeJavaArtifactImpl(hostTestArtifactCore, blueResolver),
      )
      // Delegates equality check to core and the resolved dependencies of its artifacts, the rest of the fields are derived from core.
      .withOnlyTheseFields("core", "mainArtifact", "deviceTestArtifacts", "hostTestArtifacts", "testFixturesArtifact")
      // hashCode intentionally only uses core to avoid resolving libraries.
      .suppress(Warning.STRICT_HASHCODE)
      .verify()
  }

  @Test
  fun testResolvedModelsDifferWhenReferencedLibraryResolvesDifferently() {
    // Mimics Phased Sync, which could not resolve `:lib` and resolves it to nothing, followed by a full sync which resolves it.
    val unresolvedLibResolver = resolverOf(libraryTable { index -> if (index == libIndex) emptyList() else otherLibrary(index) })
    val resolvedLibResolver =
      resolverOf(libraryTable { index -> if (index == libIndex) listOf(moduleLibrary(":lib")) else otherLibrary(index) })

    val unresolved = IdeVariantImpl(variantWithLibDependency, unresolvedLibResolver)
    val resolved = IdeVariantImpl(variantWithLibDependency, resolvedLibResolver)

    assertThat(unresolved).isNotEqualTo(resolved)
    assertThat(unresolved.mainArtifact).isNotEqualTo(resolved.mainArtifact)
    assertThat(unresolved.deviceTestArtifacts.single()).isNotEqualTo(resolved.deviceTestArtifacts.single())
    assertThat(unresolved.hostTestArtifacts.single()).isNotEqualTo(resolved.hostTestArtifacts.single())
  }

  @Test
  fun testResolvedModelsDifferWhenReferencedLibraryChanges() {
    // The library index stays the same while the library it refers to changes, e.g. because its version changed.
    val before = IdeVariantImpl(variantWithLibDependency, resolverOf(libraryTable { index -> otherLibrary(index) }))
    val after =
      IdeVariantImpl(
        variantWithLibDependency,
        resolverOf(libraryTable { index -> if (index == libIndex) listOf(moduleLibrary(":lib")) else otherLibrary(index) }),
      )

    assertThat(before).isNotEqualTo(after)
    assertThat(before.mainArtifact).isNotEqualTo(after.mainArtifact)
  }

  @Test
  fun testResolvedModelsAreEqualWhenUnreferencedLibraryChanges() {
    // Library table entries which are not referenced by the variant, e.g. dependencies of other modules in the build, change between syncs.
    val before =
      IdeVariantImpl(
        variantWithLibDependency,
        resolverOf(libraryTable { index -> otherLibrary(index) } + listOf(listOf(moduleLibrary(":x")))),
      )
    val after =
      IdeVariantImpl(
        variantWithLibDependency,
        resolverOf(libraryTable { index -> otherLibrary(index) } + listOf(listOf(moduleLibrary(":y")))),
      )

    assertThat(before).isEqualTo(after)
    assertThat(before.hashCode()).isEqualTo(after.hashCode())
    assertThat(before.mainArtifact).isEqualTo(after.mainArtifact)
    assertThat(before.deviceTestArtifacts.single()).isEqualTo(after.deviceTestArtifacts.single())
    assertThat(before.hostTestArtifacts.single()).isEqualTo(after.hostTestArtifacts.single())
  }

  @Test
  fun testResolvedModelsCompareKmpLibrariesWithoutThrowing() {
    val kmpClasspath =
      IdeDependenciesCoreDirect(listOf(IdeDependencyCoreImpl(LibraryReference(0, ResolverType.KMP_ANDROID), dependencies = listOf())))
    val artifactCore = example.variants.first().mainArtifact.copy(compileClasspathCore = kmpClasspath, runtimeClasspathCore = kmpClasspath)
    val withoutKmpTable = IdeLibraryModelResolverImpl(IdeResolvedLibraryTableImpl(emptyList()), null)
    val withKmpTable =
      IdeLibraryModelResolverImpl(
        IdeResolvedLibraryTableImpl(emptyList()),
        IdeResolvedLibraryTableImpl(listOf(listOf(moduleLibrary(":kmp")))),
      )

    assertThat(IdeAndroidArtifactImpl(artifactCore, withoutKmpTable)).isNotEqualTo(IdeAndroidArtifactImpl(artifactCore, withKmpTable))
    assertThat(IdeAndroidArtifactImpl(artifactCore, withoutKmpTable))
      .isEqualTo(IdeAndroidArtifactImpl(artifactCore, IdeLibraryModelResolverImpl(null, null)))
  }

  @Test
  fun testHashCodeAndSharedResolverEqualityDoNotAccessLibraryTable() {
    val throwingLibraryTable =
      object : AbstractList<List<IdeLibrary>>() {
        override val size: Int
          get() = error("The library table should not be accessed")

        override fun get(index: Int): List<IdeLibrary> = error("The library table should not be accessed")
      }
    val resolver = resolverOf(throwingLibraryTable)
    val variant = IdeVariantImpl(variantWithLibDependency, resolver)

    assertThat(variant.hashCode()).isEqualTo(variantWithLibDependency.hashCode())
    assertThat(variant.mainArtifact.hashCode()).isEqualTo(variantWithLibDependency.mainArtifact.hashCode())
    assertThat(variant.hostTestArtifacts.single().hashCode()).isEqualTo(variantWithLibDependency.hostTestArtifacts.single().hashCode())
    assertThat(variant).isEqualTo(IdeVariantImpl(variantWithLibDependency, resolver))
  }

  @Test
  fun testIdeVariantCoreImpl() {
    EqualsVerifier.forClass(IdeVariantCoreImpl::class.java)
      .withCachedHashCode("hashCode", "computeHashCode", example.variants.first())
      .verify()
  }

  @Test
  fun testGradleAndroidModelData() {
    EqualsVerifier.forClass(GradleAndroidModelData::class.java).verify()
  }

  /** Returns a library table of [libraryTableSize] entries, created anew on each call to mimic separate syncs. */
  private fun libraryTable(librariesAt: (Int) -> List<IdeLibrary>): List<List<IdeLibrary>> = List(libraryTableSize, librariesAt)

  private fun resolverOf(libraries: List<List<IdeLibrary>>) =
    IdeLibraryModelResolverImpl.fromLibraryTables(IdeResolvedLibraryTableImpl(libraries), null)

  private fun otherLibrary(index: Int): List<IdeLibrary> = listOf(moduleLibrary(":other$index"))

  private fun moduleLibrary(projectPath: String) =
    IdeModuleLibraryImpl(
      buildId = "/root",
      projectPath = projectPath,
      variant = "debug",
      lintJar = null as File?,
      sourceSet = IdeModuleWellKnownSourceSet.MAIN,
    )
}
