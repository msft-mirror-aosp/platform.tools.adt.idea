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
package com.android.tools.idea.editors.fast

import com.android.tools.compile.fast.CompilationResult
import com.android.tools.idea.rendering.BuildTargetReference
import com.android.tools.idea.run.deployment.liveedit.LiveEditUpdateException
import com.android.tools.idea.run.deployment.liveedit.composeRuntimePath
import com.android.tools.idea.run.deployment.liveedit.configureCompilerOptions
import com.android.tools.idea.run.deployment.liveedit.k2.backendCodeGenForK2
import com.android.tools.idea.run.deployment.liveedit.registerComposeCompilerPlugin
import com.android.tools.idea.run.deployment.liveedit.tokens.ApplicationLiveEditServices
import com.android.tools.idea.testing.AndroidModuleDependency
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaLibraryDependency
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.tests.AdtTestKotlinArtifacts
import com.intellij.facet.FacetManager
import com.intellij.facet.impl.FacetUtil
import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.assertInstanceOf
import com.intellij.util.io.delete
import com.jetbrains.rd.util.AtomicInteger
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.idea.base.facet.implementingModules
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.projectStructure.toKaSourceModuleForProduction
import org.jetbrains.kotlin.idea.base.util.module
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImplementationDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

internal class EmbeddedCompilerClientImplTest {
  @get:Rule
  val projectRule =
    AndroidProjectRule.withAndroidModels(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        ":app",
        "debug",
        AndroidProjectBuilder()
          .withAndroidModuleDependencyList { listOf(AndroidModuleDependency(":lib", "debug")) }
          .withJavaLibraryDependencyList {
            listOf(
              JavaLibraryDependency.forJar(AdtTestKotlinArtifacts.kotlinStdlib),
              JavaLibraryDependency.forJar(File(composeRuntimePath)),
            )
          },
      ),
      AndroidModuleModelBuilder(
        ":lib",
        "debug",
        AndroidProjectBuilder().withJavaLibraryDependencyList {
          listOf(JavaLibraryDependency.forJar(AdtTestKotlinArtifacts.kotlinStdlib), JavaLibraryDependency.forJar(File(composeRuntimePath)))
        },
      ),
    )

  private val compiler: EmbeddedCompilerClientImpl by lazy {
    EmbeddedCompilerClientImpl(project = projectRule.project, log = Logger.getInstance(EmbeddedCompilerClientImplTest::class.java))
  }

  @Before
  fun setUp() {
    registerComposeCompilerPlugin(projectRule.project)
  }

  @Test
  fun `simple compilation request`() {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/Source.kt",
        """
        fun testMethod() {
        }

        fun testMethodB() {
          testMethod()
        }
        """
          .trimIndent(),
      )
    val outputDirectory = Files.createTempDirectory("out")
    runBlocking {
      val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
      val result =
        compiler.compileRequest(
          // TODO: solodkyy - get it the right way?
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(file),
          buildTargetReference,
          outputDirectory,
          EmptyProgressIndicator(),
        )
      assertTrue(result.toString(), result is CompilationResult.Success)
      assertEquals(
        """
        SourceKt.class
        """
          .trimIndent(),
        outputDirectory.toFileNameSet().sorted().joinToString("\n"),
      )
    }
  }

  @Test
  fun `multi module compilation request succeeds`() {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/Source.kt",
        """
        package com.test

        fun testMethod() {
        }

        fun testMethodB() {
          testMethod()
        }
        """
          .trimIndent(),
      )
    val fileInLib =
      projectRule.fixture.addFileToProject(
        "lib/src/main/java/com/test/lib/Source.kt",
        """
        package com.test.lib

        fun aLibMethod() {
        }
        """
          .trimIndent(),
      )
    val outputDirectory = Files.createTempDirectory("out")
    runBlocking {
      val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
      val result =
        compiler.compileRequest(
          // TODO: solodkyy - get it the right way?
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(file, fileInLib),
          buildTargetReference,
          outputDirectory,
          EmptyProgressIndicator(),
        )
      assertInstanceOf<CompilationResult.Success>(result)
      assertEquals(
        """
        SourceKt.class
        """
          .trimIndent(),
        outputDirectory.toFileNameSet().sorted().joinToString("\n"),
      )
    }
  }

  @Test
  fun `syntax error compilation request`() {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        fun testMethod(
        }
        """
          .trimIndent(),
      )
    val outputDirectory = Files.createTempDirectory("out")
    runBlocking {
      val result =
        compiler.compileRequest(
          // TODO: solodkyy - get it the right way?
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(file),
          BuildTargetReference.gradleOnly(projectRule.module),
          outputDirectory,
          EmptyProgressIndicator(),
        )
      assertTrue(result.toString(), result is CompilationResult.CompilationError)
      assertTrue(outputDirectory.toFileNameSet().isEmpty())
    }
  }

  @Test
  fun `parallel requests`() {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        fun testMethod() {
        }
        """
          .trimIndent(),
      )

    val outputDirectories = (1..200).map { Files.createTempDirectory("out") }.toList()
    try {
      runBlocking {
        outputDirectories.forEach { outputDirectory ->
          launch {
            val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
            val result =
              compiler.compileRequest(
                // TODO: solodkyy - get it the right way?
                ApplicationLiveEditServices.LegacyForTests(projectRule.project),
                listOf(file),
                buildTargetReference,
                outputDirectory,
                EmptyProgressIndicator(),
              )
            assertTrue(result.toString(), result is CompilationResult.Success)
            assertEquals(
              """
              SourceKt.class
              """
                .trimIndent(),
              outputDirectory.toFileNameSet().sorted().joinToString("\n"),
            )
          }
        }
      }
    } finally {
      outputDirectories.forEach { it.delete(true) }
    }
  }

  @Test
  fun `inline test`() {
    projectRule.fixture.addFileToProject(
      "app/src/main/java/src/com/test/Inline.kt",
      """
      inline fun inlineMethod() {
      }
      """
        .trimIndent(),
    )
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        fun testMethod() {
          inlineMethod()
        }
        """
          .trimIndent(),
      )

    // Test with inline analysis enabled. This should pass when using inline methods in other files.
    run {
      val compiler =
        EmbeddedCompilerClientImpl(
          project = projectRule.project,
          log = Logger.getInstance(EmbeddedCompilerClientImplTest::class.java),
          true,
        )
      val outputDirectory = Files.createTempDirectory("out")
      runBlocking {
        val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
        val result =
          compiler.compileRequest(
            // TODO: solodkyy - get it the right way?
            ApplicationLiveEditServices.LegacyForTests(projectRule.project),
            listOf(file),
            buildTargetReference,
            outputDirectory,
            EmptyProgressIndicator(),
          )
        assertTrue(result.toString(), result is CompilationResult.Success)
        assertEquals(
          """
          SourceKt.class
          """
            .trimIndent(),
          outputDirectory.toFileNameSet().sorted().joinToString("\n"),
        )
      }
    }
  }

  /**
   * Verifies that the compileRequest fails correctly when passing a qualifier call with no receiver like `Test.`. This is a regression test
   * to verify that compileRequest does not break and handles that case correctly.
   */
  @Test
  fun `check dot qualifier error`() {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        object Test {
          fun method() {}
        }

        fun testMethod() {
          Test.
        }
        """
          .trimIndent(),
      )
    val outputDirectory = Files.createTempDirectory("out")
    runBlocking {
      val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
      val result =
        compiler.compileRequest(
          // TODO: solodkyy - get it the right way?
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(file),
          buildTargetReference,
          outputDirectory,
          EmptyProgressIndicator(),
        )
      assertTrue((result as CompilationResult.CompilationError).e is LiveEditUpdateException)
    }
  }

  /** Verifies that the compileRequest fails correctly when a failure could have been caused by the embedded plugin not being used. */
  @Test
  fun `check compilation error with non-embedded plugin`() {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        object Test {
          fun method() {}
        }

        fun testMethod() {
          Test.
        }
        """
          .trimIndent(),
      )

    run {
      val compiler =
        EmbeddedCompilerClientImpl(
          project = projectRule.project,
          log = Logger.getInstance(EmbeddedCompilerClientImplTest::class.java),
          isKotlinPluginBundled = false,
          { throw IllegalStateException("Message") },
        )
      val outputDirectory = Files.createTempDirectory("out")
      runBlocking {
        val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
        val result =
          compiler.compileRequest(
            // TODO: solodkyy - get it the right way?
            ApplicationLiveEditServices.LegacyForTests(projectRule.project),
            listOf(file),
            buildTargetReference,
            outputDirectory,
            EmptyProgressIndicator(),
          )
        assertTrue(result.toString(), result is CompilationResult.RequestException)
        assertEquals(
          "Fast Preview does not support running with this Kotlin Plugin version and will only work with the bundled Kotlin Plugin.",
          (result as CompilationResult.RequestException).e?.message?.trim(),
        )
      }
    }

    // Retry simulating that we are using the embedded compiler. We should get the original exception.
    run {
      val compiler =
        EmbeddedCompilerClientImpl(
          project = projectRule.project,
          log = Logger.getInstance(EmbeddedCompilerClientImplTest::class.java),
          isKotlinPluginBundled = true,
          { throw IllegalStateException("Message") },
        )
      val outputDirectory = Files.createTempDirectory("out")
      runBlocking {
        val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
        val result =
          compiler.compileRequest(
            // TODO: solodkyy - get it the right way?
            ApplicationLiveEditServices.LegacyForTests(projectRule.project),
            listOf(file),
            buildTargetReference,
            outputDirectory,
            EmptyProgressIndicator(),
          )
        assertTrue(result.toString(), result is CompilationResult.RequestException)
        assertEquals("Message", (result as CompilationResult.RequestException).e?.message?.trim())
      }
    }
  }

  @Test
  fun `write action can run during compilation`() = runBlocking {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        object Test {
          fun method() {}
        }

        fun testMethod() {
        }
        """
          .trimIndent(),
      )

    val compilationHasStarted = CompletableDeferred<Unit>()
    val countDownLatch = CountDownLatch(1)
    val beforeCompileCallCount = AtomicInteger(0)
    var writeActionRan = false
    run {
      val compiler =
        EmbeddedCompilerClientImpl(
          project = projectRule.project,
          log = Logger.getInstance(EmbeddedCompilerClientImplTest::class.java),
          isKotlinPluginBundled = true,
        ) {
          beforeCompileCallCount.incrementAndGet()
          compilationHasStarted.complete(Unit)
          countDownLatch.await(5, TimeUnit.SECONDS)
        }
      launch(Dispatchers.Default) {
        compilationHasStarted.await()

        // Trigger a write action while compilation lock is held before backend code generation starts outside readAction
        runWriteActionAndWait { writeActionRan = true }
        countDownLatch.countDown()
      }

      val outputDirectory = Files.createTempDirectory("out")

      val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
      val result =
        compiler.compileRequest(
          // TODO: solodkyy - get it the right way?
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(file),
          buildTargetReference,
          outputDirectory,
          EmptyProgressIndicator(),
        )
      assertEquals(CompilationResult.Success, result)
      assertTrue("Write action should have completed successfully during compilation", writeActionRan)
      assertEquals("Compilation should complete in a single attempt", 1, beforeCompileCallCount.get())
    }
  }

  @Test
  fun `duplicate file inputs in compilation request succeeds`() = runBlocking {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        package com.test

        fun testMethod() {
        }
        """
          .trimIndent(),
      )

    val outputDirectory = Files.createTempDirectory("out")
    val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
    val result =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(file, file),
        buildTargetReference,
        outputDirectory,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(result)
    assertEquals(
      """
      SourceKt.class
      """
        .trimIndent(),
      outputDirectory.toFileNameSet().sorted().joinToString("\n"),
    )
  }

  @Test
  @OptIn(KtImplementationDetail::class) // Subclasses KtFile.
  fun `compilation request succeeds when psiFile module returns null but findModuleForFile resolves module`() = runBlocking {
    val file =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/src/com/test/Source.kt",
        """
        package com.test

        fun testMethod() {
        }
        """
          .trimIndent(),
      ) as KtFile

    val fileWithContext = readAction {
      val dummyContext = PsiFileFactory.getInstance(projectRule.project).createFileFromText("dummy.txt", PlainTextFileType.INSTANCE, "")
      object : KtFile(file.viewProvider, true) {
        override fun getContext(): PsiElement = dummyContext
      }
    }

    readAction {
      assertNull("fileWithContext.module should return null", fileWithContext.module)
      assertNotNull("ModuleUtilCore.findModuleForFile should resolve the module", ModuleUtilCore.findModuleForFile(fileWithContext))
    }

    val outputDirectory = Files.createTempDirectory("out")
    val buildTargetReference = readAction { BuildTargetReference.from(file)!! }
    val result =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(file, fileWithContext),
        buildTargetReference,
        outputDirectory,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(result)
    assertEquals(
      """
      SourceKt.class
      """
        .trimIndent(),
      outputDirectory.toFileNameSet().sorted().joinToString("\n"),
    )
  }

  @Test
  fun `backendCodeGenForK2 uses cancellable read action and throws CannotReadException when write action is pending`() {
    runBlocking {
      val file =
        projectRule.fixture.addFileToProject(
          "app/src/main/java/src/com/test/Source.kt",
          """
          package com.test

          fun testMethod() {
          }
          """
            .trimIndent(),
        ) as KtFile

      val module = readAction { file.module!! }
      val finishWrite = CountDownLatch(1)
      val pendingWrite = CountDownLatch(1)
      ApplicationManager.getApplication()
        .addApplicationListener(
          object : ApplicationListener {
            override fun beforeWriteActionStart(action: Any) {
              pendingWrite.countDown()
              assertTrue("Timeout waiting for finishWrite", finishWrite.await(5, TimeUnit.SECONDS))
            }
          },
          projectRule.testRootDisposable,
        )

      ApplicationManager.getApplication().invokeLater {
        runWriteAction {}
      }

      assertTrue("Timeout waiting for pendingWrite", pendingWrite.await(5, TimeUnit.SECONDS))
      assertThrows(ReadAction.CannotReadException::class.java) {
        @OptIn(KaExperimentalApi::class) backendCodeGenForK2(file, module) { configureCompilerOptions(module, file) }
      }
    }
  }

  @Test
  @OptIn(KaExperimentalApi::class)
  fun `in-memory compilation backed by KMP commonMain with single Android JVM target resolves Android symbols and moduleName`() =
    runBlocking {
      val appFile =
        projectRule.fixture.addFileToProject(
          "app/src/main/java/com/test/AndroidPreview.kt",
          """
          package com.test

          annotation class AndroidOnlyPreview(val name: String = "")
          """
            .trimIndent(),
        )
      val appModule = readAction { appFile.module!! }

      val commonRoot = Files.createTempDirectory("commonMain")
      try {
        val commonModule = runWriteActionAndWait {
          val commonRootVf = VfsUtil.findFile(commonRoot, true)!!
          val mod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "commonMain", commonRootVf)
          PsiTestUtil.addLibrary(mod, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
          val composeJar = File(composeRuntimePath)
          PsiTestUtil.addLibrary(mod, "compose", composeJar.parent, composeJar.name)
          ModuleRootModificationUtil.addDependency(appModule, mod)

          val commonFacet = FacetUtil.addFacet(mod, KotlinFacetType.INSTANCE, "Kotlin")
          commonFacet.configuration.settings.apply {
            useProjectSettings = false
            targetPlatform = CommonPlatforms.defaultCommonPlatform
            compilerArguments =
              K2MetadataCompilerArguments().apply {
                multiPlatform = true
                moduleName = "common_metadata"
              }
            updateMergedArguments()
          }

          val appFacet = FacetUtil.addFacet(appModule, KotlinFacetType.INSTANCE, "Kotlin")
          appFacet.configuration.settings.apply {
            useProjectSettings = false
            targetPlatform = JvmPlatforms.defaultJvmPlatform
            implementedModuleNames = listOf(mod.name)
            dependsOnModuleNames = listOf(mod.name)
            compilerArguments =
              K2JVMCompilerArguments().apply {
                multiPlatform = true
                moduleName = "app_android_debug"
              }
            updateMergedArguments()
          }
          mod
        }

        val commonSourceIoFile = commonRoot.resolve("com/test/CommonCard.kt")
        Files.createDirectories(commonSourceIoFile.parent)
        Files.writeString(
          commonSourceIoFile,
          """
          package com.test

          class CommonCard {
            internal fun title(): String = "Hello KMP"
          }
          """
            .trimIndent(),
        )
        val commonVirtualFile = VfsUtil.findFile(commonSourceIoFile, true)!!

        readAction {
          assertEquals(commonModule, ModuleUtilCore.findModuleForFile(commonVirtualFile, projectRule.project))
          // Verify this reproduces the exact 1-JVM-target KMP topology (Android + non-JVM, size == 1 < 2).
          assertEquals(1, commonModule.implementingModules.count { it.platform.isJvm() })
        }

        val inMemoryCode =
          """
          package com.test

          @AndroidOnlyPreview(name = "KMP")
          fun InMemoryPreview(): String = CommonCard().title()

          class InMemoryHelper {
            internal fun checkMangling(): Int = 42
          }
          """
            .trimIndent()

        // Test both KMP commonMain platform representations:
        // 1) CommonPlatforms (e.g. Android + iOS/Web, where module.platform.isCommon() == true and jvmImplementingModules.size == 1),
        // 2) JvmPlatforms (e.g. Android-only or Android + Desktop KMP, where commonizer sets commonMain's platform to JVM so
        //    module.platform.isCommon() == false even though commonMain lacks AndroidFacet and Android-specific dependencies),
        // and both caller styles (contextModule left on commonMain vs pre-bound to androidMain).
        for (commonPlatform in listOf(CommonPlatforms.defaultCommonPlatform, JvmPlatforms.defaultJvmPlatform)) {
          runWriteActionAndWait {
            FacetManager.getInstance(commonModule)
              .getFacetByType(KotlinFacetType.TYPE_ID)!!
              .configuration
              .settings
              .apply {
                targetPlatform = commonPlatform
                updateMergedArguments()
              }
          }
          for (bindAndroidContextUpFront in listOf(false, true)) {
            val inMemoryPsiFile = readAction {
              val lightVirtualFile =
                @Suppress("UnstableApiUsage")
                object : LightVirtualFile("InMemoryPreview.kt", commonVirtualFile.fileType, inMemoryCode), BackedVirtualFile {
                  override fun getOriginFile(): VirtualFile = commonVirtualFile

                  override fun getParent(): VirtualFile? = commonVirtualFile.parent
                }
              (lightVirtualFile.toPsiFile(projectRule.project) as KtFile).also { ktFile ->
                val targetForContext = if (bindAndroidContextUpFront) appModule else commonModule
                ktFile.contextModule = targetForContext.toKaSourceModuleForProduction()
              }
            }

            val outputDirectory = Files.createTempDirectory("kmp_out")
            try {
              val buildTargetReference = readAction { BuildTargetReference.from(commonModule, inMemoryPsiFile.virtualFile) }
              val result =
                compiler.compileRequest(
                  ApplicationLiveEditServices.LegacyForTests(projectRule.project),
                  listOf(inMemoryPsiFile),
                  buildTargetReference,
                  outputDirectory,
                  EmptyProgressIndicator(),
                )
              assertInstanceOf<CompilationResult.Success>(result)
              assertEquals(
                listOf("InMemoryHelper.class", "InMemoryPreviewKt.class"),
                outputDirectory.toFileNameSet().sorted(),
              )

              // Verify internal members are mangled with the Android target's moduleName ("app_android_debug"),
              // not commonMain's metadata moduleName ("common_metadata").
              URLClassLoader(arrayOf(outputDirectory.toUri().toURL()), null).use { loader ->
                val helperClass = loader.loadClass("com.test.InMemoryHelper")
                assertTrue(
                  "Expected internal method mangled with app_android_debug, got: ${helperClass.declaredMethods.map { it.name }}",
                  helperClass.declaredMethods.any { it.name == "checkMangling\$app_android_debug" },
                )
              }
            } finally {
              outputDirectory.delete(true)
            }
          }
        }
      } finally {
        commonRoot.delete(true)
      }
    }
}
