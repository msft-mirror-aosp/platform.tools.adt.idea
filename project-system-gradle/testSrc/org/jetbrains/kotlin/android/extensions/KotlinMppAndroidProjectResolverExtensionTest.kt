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
package org.jetbrains.kotlin.android.extensions

import com.android.builder.model.proto.ide.AndroidLibraryData
import com.android.builder.model.proto.ide.File as FileProto
import com.android.builder.model.proto.ide.Library
import com.android.kotlin.multiplatform.ide.models.serialization.androidDependencyKey
import com.android.kotlin.multiplatform.models.DependencyInfo
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.ApplicationRule
import java.io.File
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinMppGradleProjectResolver.Context
import org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.addDependency
import org.jetbrains.kotlin.idea.projectModel.KotlinSourceSet
import org.jetbrains.kotlin.tooling.core.mutableExtrasOf
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class KotlinMppAndroidProjectResolverExtensionTest {

  @get:Rule val applicationRule = ApplicationRule()

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testSourcesAndDocumentationPreservedAndAttachedForAndroidLibrary() {
    val extension = KotlinMppAndroidProjectResolverExtension()

    val sourceJarFile = temporaryFolder.newFile("ink-brush-android-1.1.0-alpha07-sources.jar")
    val docJarFile = temporaryFolder.newFile("ink-brush-android-1.1.0-alpha07-javadoc.jar")
    val classesJarFile = temporaryFolder.newFile("classes.jar")
    val manifestFile = temporaryFolder.newFile("AndroidManifest.xml")

    val moduleDir = temporaryFolder.root.path
    val moduleData =
      ModuleData(
        "testProject:kmpLib",
        GradleConstants.SYSTEM_ID,
        "JAVA_MODULE",
        "kmpLib",
        moduleDir,
        moduleDir,
      )
    val moduleDataNode = DataNode(ProjectKeys.MODULE, moduleData, null)
    val sourceSetData =
      GradleSourceSetData(
        "testProject:kmpLib:androidMain",
        "testProject:kmpLib:androidMain",
        "testProject.kmpLib.androidMain",
        moduleDir,
        moduleDir,
      )
    val sourceSetDataNode = moduleDataNode.createChild(GradleSourceSetData.KEY, sourceSetData)

    val coordinates =
      IdeaKotlinBinaryCoordinates(
        group = "androidx.ink",
        module = "ink-brush-android",
        version = "1.1.0-alpha07",
      )
    val dependency =
      IdeaKotlinResolvedBinaryDependency(
        binaryType = "KOTLIN_COMPILE",
        classpath = IdeaKotlinClasspath(classesJarFile),
        coordinates = coordinates,
      )

    val androidLibraryData =
      AndroidLibraryData.newBuilder()
        .setManifest(FileProto.newBuilder().setAbsolutePath(manifestFile.absolutePath).build())
        .addCompileJarFiles(FileProto.newBuilder().setAbsolutePath(classesJarFile.absolutePath).build())
        .build()

    val library =
      Library.newBuilder()
        .setAndroidLibraryData(androidLibraryData)
        .addSrcJars(FileProto.newBuilder().setAbsolutePath(sourceJarFile.absolutePath).build())
        .setDocJar(FileProto.newBuilder().setAbsolutePath(docJarFile.absolutePath).build())
        .build()

    dependency.extras[androidDependencyKey] = DependencyInfo.newBuilder().setLibrary(library).build()

    val libNode = sourceSetDataNode.addDependency(dependency)
    assertThat(libNode).isNotNull()

    val context = Mockito.mock(Context::class.java)
    val sourceSet = Mockito.mock(KotlinSourceSet::class.java)
    Mockito.`when`(sourceSet.extras).thenReturn(mutableExtrasOf())

    extension.afterPopulateSourceSetDependencies(
      context = context,
      sourceSetDataNode = sourceSetDataNode,
      sourceSet = sourceSet,
      dependencies = setOf(dependency),
      dependencyNodes = listOf(libNode!!),
    )

    val libraryData = libNode.data.target
    val sourcePaths = libraryData.getPaths(LibraryPathType.SOURCE)
    val docPaths = libraryData.getPaths(LibraryPathType.DOC)

    assertContainsPath(sourcePaths, sourceJarFile)
    assertContainsPath(docPaths, docJarFile)
  }

  @Test
  fun testPreexistingSourcesAndDocumentationPreservedFromKotlinIdePlugin() {
    val extension = KotlinMppAndroidProjectResolverExtension()

    val sourceJarFile = temporaryFolder.newFile("preexisting-sources.jar")
    val docJarFile = temporaryFolder.newFile("preexisting-javadoc.jar")
    val annotationsFile = temporaryFolder.newFile("preexisting-annotations.zip")
    val classesJarFile = temporaryFolder.newFile("classes2.jar")
    val manifestFile = temporaryFolder.newFile("AndroidManifest2.xml")

    val moduleDir = temporaryFolder.root.path
    val moduleData =
      ModuleData(
        "testProject:kmpLib2",
        GradleConstants.SYSTEM_ID,
        "JAVA_MODULE",
        "kmpLib2",
        moduleDir,
        moduleDir,
      )
    val moduleDataNode = DataNode(ProjectKeys.MODULE, moduleData, null)
    val sourceSetData =
      GradleSourceSetData(
        "testProject:kmpLib2:androidMain",
        "testProject:kmpLib2:androidMain",
        "testProject.kmpLib2.androidMain",
        moduleDir,
        moduleDir,
      )
    val sourceSetDataNode = moduleDataNode.createChild(GradleSourceSetData.KEY, sourceSetData)

    val coordinates =
      IdeaKotlinBinaryCoordinates(
        group = "com.example",
        module = "example-lib",
        version = "1.0.0",
      )
    val dependency =
      IdeaKotlinResolvedBinaryDependency(
        binaryType = "KOTLIN_COMPILE",
        classpath = IdeaKotlinClasspath(classesJarFile),
        coordinates = coordinates,
      )

    val androidLibraryData =
      AndroidLibraryData.newBuilder()
        .setManifest(FileProto.newBuilder().setAbsolutePath(manifestFile.absolutePath).build())
        .addCompileJarFiles(FileProto.newBuilder().setAbsolutePath(classesJarFile.absolutePath).build())
        .build()

    val library = Library.newBuilder().setAndroidLibraryData(androidLibraryData).build()

    dependency.extras[androidDependencyKey] = DependencyInfo.newBuilder().setLibrary(library).build()

    val libNode = sourceSetDataNode.addDependency(dependency)
    assertThat(libNode).isNotNull()

    // Pre-populate the non-binary path types the kotlin IDE plugin may have resolved.
    libNode!!.data.target.addPath(LibraryPathType.SOURCE, sourceJarFile.path)
    libNode.data.target.addPath(LibraryPathType.DOC, docJarFile.path)
    libNode.data.target.addPath(LibraryPathType.ANNOTATION, annotationsFile.path)

    val context = Mockito.mock(Context::class.java)
    val sourceSet = Mockito.mock(KotlinSourceSet::class.java)
    Mockito.`when`(sourceSet.extras).thenReturn(mutableExtrasOf())

    extension.afterPopulateSourceSetDependencies(
      context = context,
      sourceSetDataNode = sourceSetDataNode,
      sourceSet = sourceSet,
      dependencies = setOf(dependency),
      dependencyNodes = listOf(libNode),
    )

    val libraryData = libNode.data.target
    val sourcePaths = libraryData.getPaths(LibraryPathType.SOURCE)
    val docPaths = libraryData.getPaths(LibraryPathType.DOC)
    val annotationPaths = libraryData.getPaths(LibraryPathType.ANNOTATION)

    assertContainsPath(sourcePaths, sourceJarFile)
    assertContainsPath(docPaths, docJarFile)
    assertContainsPath(annotationPaths, annotationsFile)
  }

  /**
   * Asserts that [paths] contains [file], comparing with system-independent separators.
   *
   * [com.intellij.openapi.externalSystem.model.project.LibraryData.addPath] canonicalizes the paths it stores to forward slashes, whereas
   * [File.getPath] returns the platform separator (backslashes on Windows). Normalizing both sides keeps the comparison correct on every
   * platform.
   */
  private fun assertContainsPath(paths: Collection<String>, file: File) {
    assertThat(paths.map(FileUtil::toSystemIndependentName)).contains(FileUtil.toSystemIndependentName(file.path))
  }
}
