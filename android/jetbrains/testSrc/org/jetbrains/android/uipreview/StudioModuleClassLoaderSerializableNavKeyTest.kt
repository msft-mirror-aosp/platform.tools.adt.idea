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
package org.jetbrains.android.uipreview

import com.android.tools.idea.projectsystem.ClassContent
import com.android.tools.idea.rendering.BuildTargetReference
import com.android.tools.idea.rendering.StudioModuleRenderContext
import com.android.tools.idea.rendering.classloading.loaders.ProjectSystemClassLoader
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.toVirtualFile
import com.android.tools.rendering.classloading.useWithClassLoader
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * End-to-end regression tests verifying that [StudioModuleClassLoader] applies
 * [com.android.tools.idea.rendering.classloading.PublicSerializableTransform] unconditionally to both project and non-project (library)
 * classes, even when `StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK` is disabled (b/581476550).
 *
 * When Layoutlib repackages `kotlinx.serialization` under `_layoutlib_._internal_.`,
 * `kotlinx.serialization.internal.PlatformKt.findObjectSerializer` reflects on the `INSTANCE` field of `@Serializable` Kotlin `object` /
 * `data object` routes without calling `setAccessible(true)`. Unless non-public `@Serializable` `NavKey` classes are widened to `public`
 * during class loading, the JVM rejects cross-package reflection on non-public declaring classes with [IllegalAccessException].
 */
@RunWith(JUnit4::class)
class StudioModuleClassLoaderSerializableNavKeyTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule val temporaryFolder = TemporaryFolder()

  @After
  fun tearDown() {
    val classLoaderManager = StudioModuleClassLoaderManager.get()
    classLoaderManager.clearCache(projectRule.module)
    classLoaderManager.assertNoClassLoadersHeld()
  }

  @Test
  fun privateSerializableProjectNavKeyObjectIsAccessibleFromRepackagedKotlinxSerialization() {
    addExternalLibraryJar(
      libraryName = "navigation3-and-serialization-runtime",
      jarEntries = createNavigationAndSerializationRuntimeEntries(),
    )

    val privateRouteFqcn = "com.example.preview.PrivateProjectRoute"
    val renderContext =
      InjectableProjectClassesContext(
        buildTargetReference = BuildTargetReference.gradleOnly(projectRule.module),
        injectedClasses =
          mapOf(
            privateRouteFqcn to
              ClassContent.forTests(
                createSingletonObjectBytecode(
                  internalClassName = "com/example/preview/PrivateProjectRoute",
                  implementedInterfaces = arrayOf(NAV_KEY_INTERNAL_NAME),
                  hasSerializableAnnotation = true,
                )
              )
          ),
      )

    StudioModuleClassLoaderManager.get()
      .getPrivate(StudioModuleClassLoaderSerializableNavKeyTest::class.java.classLoader, renderContext)
      .useWithClassLoader { moduleClassLoader ->
        val loadedRouteClass = moduleClassLoader.loadClass(privateRouteFqcn)
        val resolvedInstance = invokeRepackagedPlatformObjectLookup(moduleClassLoader, loadedRouteClass)
        val declaredInstance = loadedRouteClass.getDeclaredField("INSTANCE").get(null)
        assertSame(declaredInstance, resolvedInstance)
        assertThat(Modifier.isPublic(loadedRouteClass.modifiers)).isTrue()
      }
  }

  @Test
  fun privateSerializableInheritedProjectNavKeyObjectIsAccessibleFromRepackagedKotlinxSerialization() {
    addExternalLibraryJar(
      libraryName = "navigation3-and-serialization-runtime",
      jarEntries = createNavigationAndSerializationRuntimeEntries(),
    )

    val baseRouteFqcn = "com.example.preview.BaseAppRoute"
    val childRouteFqcn = "com.example.preview.ChildPrivateScreenRoute"
    val renderContext =
      InjectableProjectClassesContext(
        buildTargetReference = BuildTargetReference.gradleOnly(projectRule.module),
        injectedClasses =
          mapOf(
            baseRouteFqcn to ClassContent.forTests(createAbstractBaseNavKeyBytecode("com/example/preview/BaseAppRoute")),
            childRouteFqcn to
              ClassContent.forTests(
                createSingletonObjectBytecode(
                  internalClassName = "com/example/preview/ChildPrivateScreenRoute",
                  superInternalName = "com/example/preview/BaseAppRoute",
                  implementedInterfaces = emptyArray(),
                  hasSerializableAnnotation = true,
                )
              ),
          ),
      )

    StudioModuleClassLoaderManager.get()
      .getPrivate(StudioModuleClassLoaderSerializableNavKeyTest::class.java.classLoader, renderContext)
      .useWithClassLoader { moduleClassLoader ->
        val loadedChildRouteClass = moduleClassLoader.loadClass(childRouteFqcn)
        val resolvedInstance = invokeRepackagedPlatformObjectLookup(moduleClassLoader, loadedChildRouteClass)
        val declaredInstance = loadedChildRouteClass.getDeclaredField("INSTANCE").get(null)
        assertSame(declaredInstance, resolvedInstance)
        assertThat(Modifier.isPublic(loadedChildRouteClass.modifiers)).isTrue()
      }
  }

  @Test
  fun privateSerializableNestedProjectNavKeyObjectMakesNestedClassAccessible() {
    addExternalLibraryJar(
      libraryName = "navigation3-and-serialization-runtime",
      jarEntries = createNavigationAndSerializationRuntimeEntries(),
    )

    val outerContainerInternalName = "com/example/preview/RouteContainer"
    val nestedRouteInternalName = "com/example/preview/RouteContainer\$NestedPrivateRoute"
    val nestedRouteFqcn = "com.example.preview.RouteContainer\$NestedPrivateRoute"
    val renderContext =
      InjectableProjectClassesContext(
        buildTargetReference = BuildTargetReference.gradleOnly(projectRule.module),
        injectedClasses =
          mapOf(
            "com.example.preview.RouteContainer" to
              ClassContent.forTests(createOuterContainerBytecode(outerContainerInternalName, nestedRouteInternalName)),
            nestedRouteFqcn to
              ClassContent.forTests(
                createSingletonObjectBytecode(
                  internalClassName = nestedRouteInternalName,
                  implementedInterfaces = arrayOf(NAV_KEY_INTERNAL_NAME),
                  hasSerializableAnnotation = true,
                  enclosingInternalName = outerContainerInternalName,
                  simpleInnerName = "NestedPrivateRoute",
                )
              ),
          ),
      )

    StudioModuleClassLoaderManager.get()
      .getPrivate(StudioModuleClassLoaderSerializableNavKeyTest::class.java.classLoader, renderContext)
      .useWithClassLoader { moduleClassLoader ->
        val loadedNestedRouteClass = moduleClassLoader.loadClass(nestedRouteFqcn)
        val resolvedInstance = invokeRepackagedPlatformObjectLookup(moduleClassLoader, loadedNestedRouteClass)
        val declaredInstance = loadedNestedRouteClass.getDeclaredField("INSTANCE").get(null)
        assertSame(declaredInstance, resolvedInstance)
        assertThat(Modifier.isPublic(loadedNestedRouteClass.modifiers)).isTrue()
      }
  }

  @Test
  fun privateSerializableLibraryNavKeyObjectIsAccessibleFromRepackagedKotlinxSerialization() {
    val libraryRouteInternalName = "com/example/library/PrivateLibraryRoute"
    val libraryRouteFqcn = "com.example.library.PrivateLibraryRoute"
    val libraryEntries =
      createNavigationAndSerializationRuntimeEntries() +
        mapOf(
          "$libraryRouteInternalName.class" to
            createSingletonObjectBytecode(
              internalClassName = libraryRouteInternalName,
              implementedInterfaces = arrayOf(NAV_KEY_INTERNAL_NAME),
              hasSerializableAnnotation = true,
            )
        )
    addExternalLibraryJar(
      libraryName = "feature-navigation-library",
      jarEntries = libraryEntries,
    )

    val renderContext = StudioModuleRenderContext.forModule(projectRule.module)
    StudioModuleClassLoaderManager.get()
      .getPrivate(StudioModuleClassLoaderSerializableNavKeyTest::class.java.classLoader, renderContext)
      .useWithClassLoader { moduleClassLoader ->
        val loadedLibraryRouteClass = moduleClassLoader.loadClass(libraryRouteFqcn)
        val resolvedInstance = invokeRepackagedPlatformObjectLookup(moduleClassLoader, loadedLibraryRouteClass)
        val declaredInstance = loadedLibraryRouteClass.getDeclaredField("INSTANCE").get(null)
        assertSame(declaredInstance, resolvedInstance)
        assertThat(Modifier.isPublic(loadedLibraryRouteClass.modifiers)).isTrue()
      }
  }

  @Test
  fun privateSerializableNonNavKeyObjectRemainsPackagePrivate() {
    addExternalLibraryJar(
      libraryName = "navigation3-and-serialization-runtime",
      jarEntries = createNavigationAndSerializationRuntimeEntries(),
    )

    val nonNavKeyObjectFqcn = "com.example.preview.PrivateNonNavKeyObject"
    val renderContext =
      InjectableProjectClassesContext(
        buildTargetReference = BuildTargetReference.gradleOnly(projectRule.module),
        injectedClasses =
          mapOf(
            nonNavKeyObjectFqcn to
              ClassContent.forTests(
                createSingletonObjectBytecode(
                  internalClassName = "com/example/preview/PrivateNonNavKeyObject",
                  implementedInterfaces = emptyArray(),
                  hasSerializableAnnotation = true,
                )
              )
          ),
      )

    StudioModuleClassLoaderManager.get()
      .getPrivate(StudioModuleClassLoaderSerializableNavKeyTest::class.java.classLoader, renderContext)
      .useWithClassLoader { moduleClassLoader ->
        val loadedNonNavKeyClass = moduleClassLoader.loadClass(nonNavKeyObjectFqcn)
        assertThat(Modifier.isPublic(loadedNonNavKeyClass.modifiers)).isFalse()
      }
  }

  private fun invokeRepackagedPlatformObjectLookup(
    moduleClassLoader: ClassLoader,
    targetRouteClass: Class<*>,
  ): Any? {
    val repackagedPlatformClass = moduleClassLoader.loadClass("${INTERNAL_PACKAGE}kotlinx.serialization.internal.PlatformKt")
    val lookupMethod = repackagedPlatformClass.getDeclaredMethod("findObjectInstance", Class::class.java)
    return lookupMethod.invoke(null, targetRouteClass)
  }

  private fun addExternalLibraryJar(libraryName: String, jarEntries: Map<String, ByteArray>): File {
    val libraryJarFile = temporaryFolder.newFile("$libraryName.jar")
    JarOutputStream(libraryJarFile.outputStream()).use { jarOutputStream ->
      jarEntries.forEach { (entryPath, classBytecode) ->
        jarOutputStream.putNextEntry(JarEntry(entryPath))
        jarOutputStream.write(classBytecode)
        jarOutputStream.closeEntry()
      }
    }

    val virtualJarFile = checkNotNull(libraryJarFile.toVirtualFile(refresh = true))
    val projectLibrary =
      PsiTestUtil.addProjectLibrary(
        projectRule.module,
        "$libraryName.aar",
        listOf(virtualJarFile),
        emptyList(),
      )
    ModuleRootModificationUtil.addDependency(projectRule.module, projectLibrary)
    return libraryJarFile
  }

  private fun createNavigationAndSerializationRuntimeEntries(): Map<String, ByteArray> =
    mapOf(
      "$NAV_KEY_INTERNAL_NAME.class" to createNavKeyInterfaceBytecode(),
      "$SERIALIZABLE_ANNOTATION_INTERNAL_NAME.class" to createSerializableAnnotationBytecode(),
      "$PLATFORM_KT_INTERNAL_NAME.class" to createPlatformObjectLookupBytecode(),
    )

  /**
   * Injects compiled project `.class` bytes directly into the [ProjectSystemClassLoader] so [StudioModuleClassLoader] transforms them
   * through `PROJECT_DEFAULT_TRANSFORMS`.
   */
  private class InjectableProjectClassesContext(
    buildTargetReference: BuildTargetReference,
    private val injectedClasses: Map<String, ClassContent>,
  ) : StudioModuleRenderContext(buildTargetReference) {
    override fun createInjectableClassLoaderLoader(): ProjectSystemClassLoader =
      super.createInjectableClassLoaderLoader().also { projectSystemClassLoader ->
        injectedClasses.forEach { (fqcn, classContent) ->
          projectSystemClassLoader.injectClassFile(fqcn, classContent)
        }
      }
  }

  companion object {
    private const val JAVA_LANG_OBJECT_INTERNAL_NAME = "java/lang/Object"
    private const val NAV_KEY_INTERNAL_NAME = "androidx/navigation3/runtime/NavKey"
    private const val SERIALIZABLE_ANNOTATION_INTERNAL_NAME = "kotlinx/serialization/Serializable"
    private const val PLATFORM_KT_INTERNAL_NAME = "kotlinx/serialization/internal/PlatformKt"

    private fun createNavKeyInterfaceBytecode(): ByteArray {
      val classWriter = ClassWriter(0)
      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
        NAV_KEY_INTERNAL_NAME,
        null,
        JAVA_LANG_OBJECT_INTERNAL_NAME,
        null,
      )
      classWriter.visitEnd()
      return classWriter.toByteArray()
    }

    private fun createSerializableAnnotationBytecode(): ByteArray {
      val classWriter = ClassWriter(0)
      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION,
        SERIALIZABLE_ANNOTATION_INTERNAL_NAME,
        null,
        JAVA_LANG_OBJECT_INTERNAL_NAME,
        arrayOf("java/lang/annotation/Annotation"),
      )
      classWriter.visitEnd()
      return classWriter.toByteArray()
    }

    /**
     * Synthesizes `kotlinx.serialization.internal.PlatformKt.findObjectInstance(Class)` matching the exact reflection call in
     * `kotlinx-serialization-core` (`Platform.kt:154`): reading the `INSTANCE` field via `Field.get(null)` without calling
     * `setAccessible(true)`.
     */
    private fun createPlatformObjectLookupBytecode(): ByteArray {
      val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        PLATFORM_KT_INTERNAL_NAME,
        null,
        JAVA_LANG_OBJECT_INTERNAL_NAME,
        null,
      )

      val reflectionMethodVisitor =
        classWriter.visitMethod(
          Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
          "findObjectInstance",
          "(Ljava/lang/Class;)Ljava/lang/Object;",
          null,
          arrayOf("java/lang/ReflectiveOperationException"),
        )
      reflectionMethodVisitor.visitCode()
      reflectionMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
      reflectionMethodVisitor.visitLdcInsn("INSTANCE")
      reflectionMethodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/Class",
        "getDeclaredField",
        "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
        false,
      )
      reflectionMethodVisitor.visitInsn(Opcodes.ACONST_NULL)
      reflectionMethodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/reflect/Field",
        "get",
        "(Ljava/lang/Object;)Ljava/lang/Object;",
        false,
      )
      reflectionMethodVisitor.visitInsn(Opcodes.ARETURN)
      reflectionMethodVisitor.visitMaxs(0, 0)
      reflectionMethodVisitor.visitEnd()

      classWriter.visitEnd()
      return classWriter.toByteArray()
    }

    private fun createAbstractBaseNavKeyBytecode(internalClassName: String): ByteArray {
      val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
        internalClassName,
        null,
        JAVA_LANG_OBJECT_INTERNAL_NAME,
        arrayOf(NAV_KEY_INTERNAL_NAME),
      )

      val constructorVisitor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      constructorVisitor.visitCode()
      constructorVisitor.visitVarInsn(Opcodes.ALOAD, 0)
      constructorVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        JAVA_LANG_OBJECT_INTERNAL_NAME,
        "<init>",
        "()V",
        false,
      )
      constructorVisitor.visitInsn(Opcodes.RETURN)
      constructorVisitor.visitMaxs(0, 0)
      constructorVisitor.visitEnd()

      classWriter.visitEnd()
      return classWriter.toByteArray()
    }

    private fun createOuterContainerBytecode(
      outerInternalName: String,
      nestedInternalName: String,
    ): ByteArray {
      val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        outerInternalName,
        null,
        JAVA_LANG_OBJECT_INTERNAL_NAME,
        null,
      )
      classWriter.visitInnerClass(
        nestedInternalName,
        outerInternalName,
        "NestedPrivateRoute",
        Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
      )
      classWriter.visitEnd()
      return classWriter.toByteArray()
    }

    /**
     * Synthesizes a non-public Kotlin `object` / `data object` class (`final class` without `ACC_PUBLIC`, holding `public static final
     * INSTANCE`), matching `kotlinc` output for `private object` and `private data object` declarations.
     */
    private fun createSingletonObjectBytecode(
      internalClassName: String,
      superInternalName: String = JAVA_LANG_OBJECT_INTERNAL_NAME,
      implementedInterfaces: Array<String> = emptyArray(),
      hasSerializableAnnotation: Boolean = true,
      enclosingInternalName: String? = null,
      simpleInnerName: String? = null,
    ): ByteArray {
      val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      classWriter.visit(
        Opcodes.V17,
        Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        internalClassName,
        null,
        superInternalName,
        implementedInterfaces,
      )

      if (enclosingInternalName != null && simpleInnerName != null) {
        classWriter.visitInnerClass(
          internalClassName,
          enclosingInternalName,
          simpleInnerName,
          Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
        )
      }

      if (hasSerializableAnnotation) {
        val annotationVisitor = classWriter.visitAnnotation("L$SERIALIZABLE_ANNOTATION_INTERNAL_NAME;", true)
        annotationVisitor.visitEnd()
      }

      val instanceDescriptor = "L$internalClassName;"
      val fieldVisitor =
        classWriter.visitField(
          Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
          "INSTANCE",
          instanceDescriptor,
          null,
          null,
        )
      fieldVisitor.visitEnd()

      val constructorVisitor = classWriter.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
      constructorVisitor.visitCode()
      constructorVisitor.visitVarInsn(Opcodes.ALOAD, 0)
      constructorVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        superInternalName,
        "<init>",
        "()V",
        false,
      )
      constructorVisitor.visitInsn(Opcodes.RETURN)
      constructorVisitor.visitMaxs(0, 0)
      constructorVisitor.visitEnd()

      val staticInitializerVisitor = classWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
      staticInitializerVisitor.visitCode()
      staticInitializerVisitor.visitTypeInsn(Opcodes.NEW, internalClassName)
      staticInitializerVisitor.visitInsn(Opcodes.DUP)
      staticInitializerVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        internalClassName,
        "<init>",
        "()V",
        false,
      )
      staticInitializerVisitor.visitFieldInsn(
        Opcodes.PUTSTATIC,
        internalClassName,
        "INSTANCE",
        instanceDescriptor,
      )
      staticInitializerVisitor.visitInsn(Opcodes.RETURN)
      staticInitializerVisitor.visitMaxs(0, 0)
      staticInitializerVisitor.visitEnd()

      classWriter.visitEnd()
      return classWriter.toByteArray()
    }
  }
}
