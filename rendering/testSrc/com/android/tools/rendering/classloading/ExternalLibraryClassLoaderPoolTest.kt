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
import java.nio.file.Files
import java.nio.file.attribute.FileTime
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

  @Test
  fun testSiblingModuleBuildOutputJarUpdatedInPlaceNeverResetsSharedCachePool() {
    val externalJar = temporaryFolder.newFile("external-compose-runtime.jar")
    val siblingBuildDir =
      temporaryFolder.newFolder("ui-lib", "build", "intermediates", "runtime_library_classes_jar", "debug", "bundleLibRuntimeToJarDebug")
    val siblingJar = File(siblingBuildDir, "classes.jar")

    val externalClass = "androidx.compose.runtime.StableMarker"
    val pkgV1Class = "com.example.uilib.ExistingBadge"
    val pkgV2Class = "com.example.uilib.newpkg.NewBranchBanner"
    val pkgV3Class = "com.example.uilib.v3pkg.ThirdVersionWidget"

    writeNamedEntryToJar(externalJar, externalClass, loadClassBytes(A::class.java), lastModifiedMs = 500_000L)
    writeNamedEntryToJar(siblingJar, pkgV1Class, loadClassBytes(A::class.java), lastModifiedMs = 1_000_000L)

    val paths = listOf(externalJar.toPath(), siblingJar.toPath())
    val identitiesV1 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths)

    val entryV1 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)
    val initialPool = ExternalLibraryClassLoaderPool.getCachePoolForTest()
    val loaderV1 = entryV1.createCachingJarLoader(identitiesV1) { _, _ -> }
    assertThat(loaderV1.loadClass(externalClass)).isNotNull()
    assertThat(loaderV1.loadClass(pkgV1Class)).isNotNull()
    assertThat(loaderV1.loadClass(pkgV2Class)).isNull()

    // Rebuild the sibling module JAR in place at the same `.../build/intermediates/.../classes.jar`
    // path with a brand-new package. Because build output paths are excluded from `ourLoaderCachePool`
    // upfront, the new package is loaded and `ourLoaderCachePool` is NOT reset even on the first mutation.
    writeNamedEntryToJar(siblingJar, pkgV2Class, loadClassBytes(A::class.java), lastModifiedMs = 2_000_000L)
    val identitiesV2 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths)

    val entryV2 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV2)
    assertThat(entryV2).isNotSameAs(entryV1)
    assertThat(ExternalLibraryClassLoaderPool.getCachePoolForTest()).isSameAs(initialPool)
    val loaderV2 = entryV2.createCachingJarLoader(identitiesV2) { _, _ -> }
    assertThat(loaderV2.loadClass(externalClass)).isNotNull()
    assertThat(loaderV2.loadClass(pkgV2Class)).isNotNull()
    assertThat(loaderV2.loadClass(pkgV1Class)).isNull()

    // Subsequent rebuild adding a third package also preserves `initialPool`.
    writeNamedEntryToJar(siblingJar, pkgV3Class, loadClassBytes(A::class.java), lastModifiedMs = 3_000_000L)
    val identitiesV3 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths)

    val entryV3 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV3)
    assertThat(entryV3).isNotSameAs(entryV2)
    assertThat(ExternalLibraryClassLoaderPool.getCachePoolForTest()).isSameAs(initialPool)
    val loaderV3 = entryV3.createCachingJarLoader(identitiesV3) { _, _ -> }
    assertThat(loaderV3.loadClass(pkgV3Class)).isNotNull()
    assertThat(loaderV3.loadClass(pkgV2Class)).isNull()

    // Restoring Version 1 timestamp (e.g. `git checkout main`) constructs a fresh entry (since `entryV1`
    // was evicted when superseded) while still keeping `initialPool` intact.
    writeNamedEntryToJar(siblingJar, pkgV1Class, loadClassBytes(A::class.java), lastModifiedMs = 1_000_000L)
    val entryV1Restored = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)
    assertThat(entryV1Restored).isNotSameAs(entryV1)
    assertThat(ExternalLibraryClassLoaderPool.getCachePoolForTest()).isSameAs(initialPool)
    val loaderV1Restored = entryV1Restored.createCachingJarLoader(identitiesV1) { _, _ -> }
    assertThat(loaderV1Restored.loadClass(pkgV1Class)).isNotNull()
    assertThat(loaderV1Restored.loadClass(pkgV2Class)).isNull()
    assertThat(loaderV1Restored.loadClass(pkgV3Class)).isNull()
  }

  @Test
  fun testIsMutableBuildOutputPath() {
    assertThat(
        ExternalLibraryClassLoaderPool.isMutableBuildOutputPath(
          "/workspace/project/ui-lib/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
        )
      )
      .isTrue()
    assertThat(ExternalLibraryClassLoaderPool.isMutableBuildOutputPath("/workspace/project/core/build/libs/core.jar")).isTrue()
    assertThat(ExternalLibraryClassLoaderPool.isMutableBuildOutputPath("/workspace/project/app/out/production/classes")).isTrue()
    assertThat(ExternalLibraryClassLoaderPool.isMutableBuildOutputPath("/workspace/project/app/build/tmp/kotlin-classes/debug")).isTrue()
    assertThat(
        ExternalLibraryClassLoaderPool.isMutableBuildOutputPath(
          "/home/user/.gradle/caches/modules-2/files-2.1/androidx.compose.ui/ui/1.7.0/abc/ui-1.7.0.jar"
        )
      )
      .isFalse()
    assertThat(
        ExternalLibraryClassLoaderPool.isMutableBuildOutputPath(
          "/home/user/.gradle/caches/8.11/transforms/0123456789abcdef/transformed/ui-release-runtime.jar"
        )
      )
      .isFalse()
  }

  @Test
  fun testInPlaceJarUpdateWithNewPackageInvalidatesSharedCachePoolAndEvictsSupersededEntry() {
    val jarFile = temporaryFolder.newFile("sibling-module-classes.jar")
    val pkgV1Class = "com.example.uilib.ExistingBadge"
    val pkgV2Class = "com.example.uilib.newpkg.NewBranchBanner"
    val pkgV3Class = "com.example.uilib.v3pkg.ThirdVersionWidget"

    // Version 1 (e.g. on `main` branch): JAR contains only `com.example.uilib.ExistingBadge`.
    writeNamedEntryToJar(jarFile, pkgV1Class, loadClassBytes(A::class.java), lastModifiedMs = 1_000_000L)
    val paths = listOf(jarFile.toPath())
    val identitiesV1 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths) { 1_000_000L }

    val entryV1 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)
    val initialPool = ExternalLibraryClassLoaderPool.getCachePoolForTest()
    // Warm cache hits reuse `entryV1` and keep `ourLoaderCachePool` unchanged.
    assertThat(ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)).isSameAs(entryV1)
    assertThat(ExternalLibraryClassLoaderPool.getCachePoolForTest()).isSameAs(initialPool)

    val loaderV1 = entryV1.createCachingJarLoader(identitiesV1) { _, _ -> }
    assertThat(loaderV1.loadClass(pkgV1Class)).isNotNull()
    assertThat(loaderV1.loadClass(pkgV2Class)).isNull()

    // Version 2 (e.g. after switching Git branches): JAR is overwritten in place at the exact same Path
    // with a class in a brand-new package (`com.example.uilib.newpkg.NewBranchBanner`).
    // The first in-place mutation of this path resets `ourLoaderCachePool` once and excludes the path
    // from future shared-pool caching.
    writeNamedEntryToJar(jarFile, pkgV2Class, loadClassBytes(A::class.java), lastModifiedMs = 2_000_000L)
    val identitiesV2 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths) { 2_000_000L }

    val entryV2 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV2)
    val poolAfterFirstMutation = ExternalLibraryClassLoaderPool.getCachePoolForTest()
    assertThat(entryV2).isNotSameAs(entryV1)
    assertThat(poolAfterFirstMutation).isNotSameAs(initialPool)
    val loaderV2 = entryV2.createCachingJarLoader(identitiesV2) { _, _ -> }
    assertThat(loaderV2.loadClass(pkgV2Class)).isNotNull()
    assertThat(loaderV2.loadClass(pkgV1Class)).isNull()

    // Version 3 (subsequent edit to the same mutable sibling-module JAR adding another package):
    // because the path is now excluded from `ourLoaderCachePool`, the new package is loaded and
    // `entryV2` is superseded without resetting `ourLoaderCachePool` again across unchanged libraries.
    writeNamedEntryToJar(jarFile, pkgV3Class, loadClassBytes(A::class.java), lastModifiedMs = 3_000_000L)
    val identitiesV3 = ExternalLibraryClassLoaderPool.computeLibraryIdentities(paths) { 3_000_000L }

    val entryV3 = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV3)
    assertThat(entryV3).isNotSameAs(entryV2)
    assertThat(ExternalLibraryClassLoaderPool.getCachePoolForTest()).isSameAs(poolAfterFirstMutation)
    val loaderV3 = entryV3.createCachingJarLoader(identitiesV3) { _, _ -> }
    assertThat(loaderV3.loadClass(pkgV3Class)).isNotNull()
    assertThat(loaderV3.loadClass(pkgV2Class)).isNull()

    // Version 1 restored (e.g. `git checkout main` or Gradle build-cache restore with original timestamp):
    // entryV1 must have been evicted when V2 superseded it so a fresh UrlClassLoader is constructed,
    // while `ourLoaderCachePool` remains intact.
    writeNamedEntryToJar(jarFile, pkgV1Class, loadClassBytes(A::class.java), lastModifiedMs = 1_000_000L)
    val entryV1Restored = ExternalLibraryClassLoaderPool.getOrCreateEntry(paths, identitiesV1)
    assertThat(entryV1Restored).isNotSameAs(entryV1)
    assertThat(ExternalLibraryClassLoaderPool.getCachePoolForTest()).isSameAs(poolAfterFirstMutation)
    val loaderV1Restored = entryV1Restored.createCachingJarLoader(identitiesV1) { _, _ -> }
    assertThat(loaderV1Restored.loadClass(pkgV1Class)).isNotNull()
    assertThat(loaderV1Restored.loadClass(pkgV2Class)).isNull()
    assertThat(loaderV1Restored.loadClass(pkgV3Class)).isNull()
  }

  @Test
  fun testCreateUrlClassLoaderInvalidatesCachePoolWhenJarModifiedInPlace() {
    val jarFile = temporaryFolder.newFile("direct-url-loader.jar")
    val pkgV1Resource = "com/example/v1/resource.txt"
    val pkgV2Resource = "com/example/v2/newpkg/resource.txt"

    writeRawEntryToJar(jarFile, pkgV1Resource, "v1".toByteArray(), lastModifiedMs = 1_000_000L)
    val cl1 = ExternalLibraryClassLoaderPool.createUrlClassLoader(listOf(jarFile.toPath()), allowLock = false)
    assertThat(cl1.getResource(pkgV1Resource)).isNotNull()
    assertThat(cl1.getResource(pkgV2Resource)).isNull()

    writeRawEntryToJar(jarFile, pkgV2Resource, "v2".toByteArray(), lastModifiedMs = 2_000_000L)
    val cl2 = ExternalLibraryClassLoaderPool.createUrlClassLoader(listOf(jarFile.toPath()), allowLock = false)
    assertThat(cl2.getResource(pkgV2Resource)).isNotNull()
    assertThat(cl2.getResource(pkgV1Resource)).isNull()
  }

  private fun writeClassToJar(jarFile: File, clazz: Class<*>) {
    writeNamedEntryToJar(jarFile, clazz.name, loadClassBytes(clazz), jarFile.lastModified())
  }

  private fun writeNamedEntryToJar(jarFile: File, fqcn: String, bytes: ByteArray, lastModifiedMs: Long) {
    val internalName = fqcn.replace('.', '/') + ".class"
    writeRawEntryToJar(jarFile, internalName, bytes, lastModifiedMs)
  }

  private fun writeRawEntryToJar(jarFile: File, entryPath: String, bytes: ByteArray, lastModifiedMs: Long) {
    JarOutputStream(FileOutputStream(jarFile)).use { jos ->
      val entry = JarEntry(entryPath)
      entry.time = lastModifiedMs
      jos.putNextEntry(entry)
      jos.write(bytes)
      jos.closeEntry()
    }
    Files.setLastModifiedTime(jarFile.toPath(), FileTime.fromMillis(lastModifiedMs))
  }
}
