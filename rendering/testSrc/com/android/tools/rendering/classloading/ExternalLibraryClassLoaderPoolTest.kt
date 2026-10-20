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
package com.android.tools.rendering.classloading

import com.android.tools.rendering.classloading.codeexecution.A
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalLibraryClassLoaderPoolTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @After
  fun tearDown() {
    ExternalLibraryClassLoaderPool.clearForTest()
  }

  @Test
  fun testGetOrCreateEntryReusesEntryUntilIdentityChanges() {
    val jarFile = temporaryFolder.newFile("lib.jar")
    writeClassToJar(jarFile, A::class.java)
    val paths = listOf(jarFile.toPath())

    val identitiesV1 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths) { 1000L }
    val entry1 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)
    val entry2 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)
    assertThat(entry2).isSameAs(entry1)

    val identitiesV2 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths) { 2000L }
    val entry3 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV2)
    assertThat(entry3).isNotSameAs(entry1)
  }

  @Test
  fun testGetOrCreateEntryDifferentiatesClasspathOrder() {
    val jarFile1 = temporaryFolder.newFile("lib1.jar")
    val jarFile2 = temporaryFolder.newFile("lib2.jar")
    writeClassToJar(jarFile1, A::class.java)
    writeClassToJar(jarFile2, A::class.java)

    val paths1 = listOf(jarFile1.toPath(), jarFile2.toPath())
    val paths2 = listOf(jarFile2.toPath(), jarFile1.toPath())
    val identities = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths1) { 1000L }

    val entry1 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths1, identities)
    val entry2 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths2, identities)
    assertThat(entry2).isNotSameAs(entry1)
  }

  @Test
  fun testCachingJarLoaderResolvesLibraryIdentityAndCachesNegativeLookups() {
    val jarFile = temporaryFolder.newFile("lib with spaces.jar")
    writeClassToJar(jarFile, A::class.java)
    val paths = listOf(jarFile.toPath())
    val identities = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths)
    val expectedIdentity = identities.values.single()

    val entry = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identities)
    val resolvedIdentities = mutableMapOf<String, String>()
    val loader =
      entry.createCachingJarLoader(identities) { fqcn, identity ->
        resolvedIdentities[fqcn] = identity
      }

    val fqcn = A::class.java.name
    val bytes = loader.loadClass(fqcn)
    assertThat(bytes).isNotNull()
    assertThat(resolvedIdentities[fqcn]).isEqualTo(expectedIdentity)

    // Negative lookup populates missingClasses
    val missingFqcn = "com.example.DoesNotExist"
    assertThat(loader.loadClass(missingFqcn)).isNull()
    assertThat(entry.getMissingClasses()).contains(missingFqcn)

    // Subsequent call short-circuits via missingClasses
    assertThat(loader.loadClass(missingFqcn)).isNull()
  }

  @Test
  fun testResolveLibraryPathHandlesJarAndDirectoryPaths() {
    val jarRoot = "/tmp/my lib/classes.jar"
    val jarUrlPath = "file:/tmp/my%20lib/classes.jar!/com/example/Foo.class"
    assertThat(ExternalLibraryClassLoaderPool.resolveLibraryPath(jarUrlPath, "com.example.Foo")).isEqualTo(jarRoot)

    val dirRoot = FileUtil.toSystemIndependentName(temporaryFolder.newFolder("dir-lib").absolutePath)
    val classFilePath = "$dirRoot/com/example/Bar.class"
    assertThat(ExternalLibraryClassLoaderPool.resolveLibraryPath(classFilePath, "com.example.Bar")).isEqualTo(dirRoot)
  }

  private fun writeClassToJar(jarFile: File, clazz: Class<*>) {
    val internalName = clazz.name.replace('.', '/') + ".class"
    val bytes = loadClassBytes(clazz)
    JarOutputStream(FileOutputStream(jarFile)).use { jos ->
      jos.putNextEntry(JarEntry(internalName))
      jos.write(bytes)
      jos.closeEntry()
    }
  }
}
