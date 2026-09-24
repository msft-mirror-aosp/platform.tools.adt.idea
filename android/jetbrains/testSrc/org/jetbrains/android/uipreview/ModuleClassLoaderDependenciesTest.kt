/*
 * Copyright (C) 2022 The Android Open Source Project
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
package org.jetbrains.android.uipreview

import com.android.SdkConstants
import com.android.tools.idea.projectsystem.ClassContent
import com.android.tools.idea.rendering.BuildTargetReference
import com.android.tools.idea.rendering.StudioModuleRenderContext
import com.android.tools.idea.rendering.classloading.loaders.ProjectSystemClassLoader
import com.android.tools.idea.testing.JavacUtil.getJavac
import com.android.tools.idea.util.toVirtualFile
import com.android.tools.rendering.classloading.ClassBinaryCacheManager
import com.android.tools.rendering.classloading.useWithClassLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.SourceFolder
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.jetbrains.android.AndroidTestCase
import org.jetbrains.android.uipreview.StudioModuleClassLoaderManager.Companion.get
import org.junit.Test

// Regression test for b/229997303
class ModuleClassLoaderDependenciesTest : AndroidTestCase() {

  @Test
  fun testCommonSuperClassResolvedCorrectly() {
    val srcDir = Files.createDirectories(Files.createTempDirectory("testProject").resolve("src"))
    val packageDir = Files.createDirectories(srcDir.resolve("com/foo/qwe"))
    val dSrc = Files.createFile(packageDir.resolve("D.java"))
    FileUtil.writeToFile(
      dSrc.toFile(),
      // language=java
      """
      package com.foo.qwe;

      import com.foo.bar.A;
      import com.foo.bar.B;
      import com.foo.bar.C;

      public class D {
        private static A foo() {
          A a = null;
          if (a == null) {
            a = new B();
          } else {
            a = new C();
          }
          return a;
        }

        public D() {
          A a = foo();
        }
      }
      """
        .trimIndent(),
    )

    val classes = addAarDependency(myModule, "pseudoclasslocator/classes.jar", "foobar")

    ApplicationManager.getApplication()
      .runWriteAction(
        Computable { PsiTestUtil.addSourceRoot(myModule, VfsUtil.findFileByIoFile(srcDir.toFile(), true)!!) } as Computable<SourceFolder>
      )

    val javac = getJavac()
    javac.run(null, null, null, "-cp", "${classes.absolutePath}", "${dSrc.toAbsolutePath()}")

    val dClass = dSrc.getParent().toFile().resolve("D.class")
    assertTrue(dClass.exists())

    val context = InjectableContext(BuildTargetReference.gradleOnly(myModule), mapOf("com.foo.qwe.D" to ClassContent.loadFromFile(dClass)))
    val unused =
      get().getShared(null, context).useWithClassLoader { loader ->
        val loadedDClass = loader.loadClass("com.foo.qwe.D")
        assertNotNull(loadedDClass.getConstructor())
      }
  }

  @Test
  fun testClassBinaryCachePopulatedAndStalenessTracked() {
    val classes = addAarDependency(myModule, "pseudoclasslocator/classes.jar", "foobar")
    val cache = ClassBinaryCacheManager.getInstance().getCache(myModule)
    val context = StudioModuleRenderContext.forModule(myModule)

    var transformationId: String? = null
    var firstLoader: StudioModuleClassLoader? = null
    get().getShared(null, context).useWithClassLoader { loader ->
      val aClass = loader.loadClass("com.foo.bar.A")
      assertNotNull(aClass)
      firstLoader = loader as StudioModuleClassLoader
      transformationId = loader.nonProjectClassesTransform.id
      assertTrue(loader.hasLoadedClass("com.foo.bar.A"))
      assertTrue(loader.nonProjectLoadedClasses.contains("com.foo.bar.A"))
      assertTrue(loader.areDependenciesUpToDate())
    }

    assertNotNull(transformationId)
    val cachedBytes = cache.get("com.foo.bar.A", transformationId!!)
    assertNotNull("The loaded class must be cached and retrievable from the ClassBinaryCache", cachedBytes)

    // Verify cache hit also populates nonProjectLoadedClasses
    get().getPrivate(null, context).useWithClassLoader { secondLoader ->
      val aClass = secondLoader.loadClass("com.foo.bar.A")
      assertNotNull(aClass)
      val studioLoader = secondLoader as StudioModuleClassLoader
      assertTrue(
        "Classes loaded from cache must be recorded in hasLoadedClass",
        studioLoader.hasLoadedClass("com.foo.bar.A"),
      )
      assertTrue(
        "Classes loaded from cache must be recorded in nonProjectLoadedClasses",
        studioLoader.nonProjectLoadedClasses.contains("com.foo.bar.A"),
      )
    }

    // Stale dependency invalidation: if the jar is rebuilt in place (its timestamp changes),
    // areDependenciesUpToDate() must return false, and a new classloader created for the module
    // will have a new library identity.
    classes.setLastModified(classes.lastModified() + 10_000L)
    assertFalse(
      "Class loader must report dependencies out of date when library timestamp changes",
      firstLoader!!.areDependenciesUpToDate(),
    )

    get().getPrivate(null, context).useWithClassLoader {
      // The new loader initializes with the new jar timestamp
    }

    // The old cached entry for com.foo.bar.A had the old timestamp, so cache.get must reject and invalidate it
    assertNull(
      "Cache must invalidate entries when library timestamp changes",
      cache.get("com.foo.bar.A", transformationId),
    )
  }

  @Test
  fun testClassBinaryCacheWithSpacesInDependencyPath() {
    addAarDependency(myModule, "pseudoclasslocator/classes.jar", "library with spaces")
    val cache = ClassBinaryCacheManager.getInstance().getCache(myModule)
    val context = StudioModuleRenderContext.forModule(myModule)

    var transformationId: String? = null
    get().getShared(null, context).useWithClassLoader { loader ->
      val aClass = loader.loadClass("com.foo.bar.A")
      assertNotNull(aClass)
      transformationId = (loader as StudioModuleClassLoader).nonProjectClassesTransform.id
    }

    assertNotNull(transformationId)
    val cachedBytes = cache.get("com.foo.bar.A", transformationId!!)
    assertNotNull("The loaded class from a library with spaces in its path must be cached in ClassBinaryCache", cachedBytes)
  }

  companion object {
    private fun createManifest(aarDir: File, packageName: String) {
      aarDir.mkdirs()
      aarDir
        .resolve(SdkConstants.FN_ANDROID_MANIFEST_XML)
        .writeText(
          // language=xml
          """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
      </manifest>
    """
            .trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun addAarDependency(module: Module, classesjar: String, libraryName: String): File {
      val baseTempDir = FileUtil.createTempDirectory("test", null)
      val aarDir = File(baseTempDir, libraryName).apply { mkdirs() }
      createManifest(aarDir, "com.foo.bar")
      val classesJar = aarDir.resolve(SdkConstants.FN_CLASSES_JAR)
      ModuleClassLoaderDependenciesTest::class.java.classLoader.getResourceAsStream(classesjar).use { stream ->
        val bytes = stream.readAllBytes()
        classesJar.outputStream().use { it.write(bytes) }
      }

      val library = PsiTestUtil.addProjectLibrary(module, "$libraryName.aar", listOf(classesJar.toVirtualFile(refresh = true)), emptyList())
      ModuleRootModificationUtil.addDependency(module, library)
      return classesJar
    }
  }
}

/** The classes from [toInject] are not shown as loaded in [ModuleClassLoader]. */
private class InjectableContext(buildTargetReference: BuildTargetReference, private val toInject: Map<String, ClassContent>) :
  StudioModuleRenderContext(buildTargetReference) {
  override fun createInjectableClassLoaderLoader(): ProjectSystemClassLoader =
    super.createInjectableClassLoaderLoader().also { cl -> toInject.forEach { cl.injectClassFile(it.key, it.value) } }
}
