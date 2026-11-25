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
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.assertInstanceOf
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.io.delete
import com.jetbrains.rd.util.AtomicInteger
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImplementationDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

internal class EmbeddedCompilerClientImplTest {
  private val tempFolder = TemporaryFolder()
  private val projectRule =
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

  // Outer tempFolder deletes temp JARs only after projectRule tears down and releases VFS/ZipHandler locks on Windows.
  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(tempFolder).around(projectRule)

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
        waitUntilIndexesAreReady()

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
            FacetManager.getInstance(commonModule).getFacetByType(KotlinFacetType.TYPE_ID)!!.configuration.settings.apply {
              targetPlatform = commonPlatform
              updateMergedArguments()
            }
          }
          waitUntilIndexesAreReady()
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

  private suspend fun compileKtToJar(tempDir: Path, moduleName: String, sourceRelPath: String, sourceCode: String): Path {
    val srcRoot = tempDir.resolve("${moduleName}_src")
    val outDir = tempDir.resolve("${moduleName}_out")
    val jarPath = tempDir.resolve("$moduleName.jar")
    Files.createDirectories(srcRoot)
    Files.createDirectories(outDir)

    val srcFile = srcRoot.resolve(sourceRelPath)
    Files.createDirectories(srcFile.parent)
    Files.writeString(srcFile, sourceCode)

    val mod = runWriteActionAndWait {
      val rootVf = VfsUtil.findFile(srcRoot, true)!!
      PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, moduleName, rootVf).also {
        PsiTestUtil.addLibrary(it, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
      }
    }
    try {
      val srcVf = VfsUtil.findFile(srcFile, true)!!
      waitUntilIndexesAreReady()
      val psiFile = readAction { srcVf.toPsiFile(projectRule.project) as KtFile }
      val buildTargetReference = readAction { BuildTargetReference.from(mod, psiFile.virtualFile) }
      val result =
        compiler.compileRequest(
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(psiFile),
          buildTargetReference,
          outDir,
          EmptyProgressIndicator(),
        )
      assertInstanceOf<CompilationResult.Success>(result)

      // Copy both .class files and META-INF/*.kotlin_module so K2 resolves top-level @JvmName facades from this jar.
      JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
        Files.walk(outDir).use { stream ->
          stream
            .filter { Files.isRegularFile(it) }
            .forEach { file ->
              val entryName = outDir.relativize(file).toString().replace(File.separatorChar, '/')
              jos.putNextEntry(JarEntry(entryName))
              jos.write(Files.readAllBytes(file))
              jos.closeEntry()
            }
        }
      }
    } finally {
      runWriteActionAndWait {
        val model = ModuleManager.getInstance(projectRule.project).getModifiableModel()
        model.disposeModule(mod)
        model.commit()
      }
    }
    return jarPath
  }

  private fun configureKmpFacet(
    module: Module,
    targetPlatform: TargetPlatform,
    moduleName: String,
    isMetadata: Boolean = false,
    dependsOn: List<String> = emptyList(),
  ) {
    FacetUtil.addFacet(module, KotlinFacetType.INSTANCE, "Kotlin").configuration.settings.apply {
      useProjectSettings = false
      isHmppEnabled = true
      this.targetPlatform = targetPlatform
      implementedModuleNames = dependsOn
      dependsOnModuleNames = dependsOn
      compilerArguments =
        if (isMetadata) {
          K2MetadataCompilerArguments().apply {
            multiPlatform = true
            this.moduleName = moduleName
          }
        } else {
          K2JVMCompilerArguments().apply {
            multiPlatform = true
            this.moduleName = moduleName
          }
        }
      updateMergedArguments()
    }
  }

  // Wait for background scanning (UnindexedFilesScanner) and dumb-mode tasks (such as PushedFilePropertiesUpdater
  // queued by VfsUtil.findFile(..., refresh = true) and workspace mutations) to finish before compiling, so their
  // enter/exit write actions cannot cancel ReadAction.computeCancellable inside backendCodeGenForK2 with CompilationAborted.
  private fun waitUntilIndexesAreReady() {
    runInEdtAndWait { IndexingTestUtil.waitUntilIndexesAreReady(projectRule.project, Duration.ofMinutes(2)) }
  }

  @Test
  @OptIn(KaExperimentalApi::class)
  fun `physical KMP commonMain file with Android and Desktop targets resolves Android facade instead of Skiko`(): Unit = runBlocking {
    val appFile =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/AndroidMarker.kt",
        """
        package com.test

        class AndroidMarker
        """
          .trimIndent(),
      )
    val appModule = readAction { appFile.module!! }

    val tempDir = tempFolder.newFolder("kmp_physical_test").toPath()
    val skikoJar =
      compileKtToJar(
        tempDir = tempDir,
        moduleName = "skikoInsetsLib",
        sourceRelPath = "com/example/insets/WindowInsetsPadding.kt",
        sourceCode =
          """
          @file:JvmName("WindowInsetsPadding_skikoKt")
          package com.example.insets

          fun safeContentPadding(): String = "skiko"
          """
            .trimIndent(),
      )
    val androidJar =
      compileKtToJar(
        tempDir = tempDir,
        moduleName = "androidInsetsLib",
        sourceRelPath = "com/example/insets/WindowInsetsPadding.kt",
        sourceCode =
          """
          @file:JvmName("WindowInsetsPadding_androidKt")
          package com.example.insets

          fun safeContentPadding(): String = "android"
          """
            .trimIndent(),
      )

    val commonRoot = tempDir.resolve("commonMain")
    val intermediateRoot = tempDir.resolve("jvmAndAndroidMain")
    val jvmRoot = tempDir.resolve("jvmMain")
    Files.createDirectories(commonRoot)
    Files.createDirectories(intermediateRoot)
    Files.createDirectories(jvmRoot)

    val (commonModule, intermediateModule, jvmModule) =
      runWriteActionAndWait {
        val commonVf = VfsUtil.findFile(commonRoot, true)!!
        val intermediateVf = VfsUtil.findFile(intermediateRoot, true)!!
        val jvmVf = VfsUtil.findFile(jvmRoot, true)!!

        val commonMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpCommonMain", commonVf)
        val intermediateMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpJvmAndAndroidMain", intermediateVf)
        val jvmMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpJvmMain", jvmVf)

        for (mod in listOf(commonMod, intermediateMod, jvmMod)) {
          PsiTestUtil.addLibrary(mod, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
          PsiTestUtil.addLibrary(mod, "skikoInsets", skikoJar.parent.toString(), skikoJar.fileName.toString())
        }
        PsiTestUtil.addLibrary(appModule, "androidInsets", androidJar.parent.toString(), androidJar.fileName.toString())

        ModuleRootModificationUtil.addDependency(intermediateMod, commonMod)
        ModuleRootModificationUtil.addDependency(appModule, commonMod)
        ModuleRootModificationUtil.addDependency(appModule, intermediateMod)
        ModuleRootModificationUtil.addDependency(jvmMod, commonMod)
        ModuleRootModificationUtil.addDependency(jvmMod, intermediateMod)

        configureKmpFacet(commonMod, JvmPlatforms.jvm11, "common_jvm")
        configureKmpFacet(intermediateMod, JvmPlatforms.jvm11, "intermediate_jvm", dependsOn = listOf(commonMod.name))
        configureKmpFacet(
          appModule,
          JvmPlatforms.jvm11,
          "app_android_debug",
          dependsOn = listOf(commonMod.name, intermediateMod.name),
        )
        configureKmpFacet(
          jvmMod,
          JvmPlatforms.jvm11,
          "desktop_jvm",
          dependsOn = listOf(commonMod.name, intermediateMod.name),
        )
        Triple(commonMod, intermediateMod, jvmMod)
      }

    val commonAppPath = commonRoot.resolve("com/test/App.kt")
    Files.createDirectories(commonAppPath.parent)
    Files.writeString(
      commonAppPath,
      """
      package com.test

      import com.example.insets.safeContentPadding

      fun appScreen(): String = safeContentPadding()

      class AppModel {
        internal fun label(): String = safeContentPadding()
      }
      """
        .trimIndent(),
    )
    val commonAppVf = VfsUtil.findFile(commonAppPath, true)!!

    val intermediatePath = intermediateRoot.resolve("com/test/Intermediate.kt")
    Files.createDirectories(intermediatePath.parent)
    Files.writeString(
      intermediatePath,
      """
      package com.test

      import com.example.insets.safeContentPadding

      fun intermediateScreen(): String = safeContentPadding()
      """
        .trimIndent(),
    )
    val intermediateFileVf = VfsUtil.findFile(intermediatePath, true)!!

    val jvmMainPath = jvmRoot.resolve("com/test/DesktopEntry.kt")
    Files.createDirectories(jvmMainPath.parent)
    Files.writeString(
      jvmMainPath,
      """
      package com.test

      import com.example.insets.safeContentPadding

      fun desktopScreen(): String = safeContentPadding()
      """
        .trimIndent(),
    )
    val jvmMainFileVf = VfsUtil.findFile(jvmMainPath, true)!!
    waitUntilIndexesAreReady()
    val commonAppPsi = readAction { commonAppVf.toPsiFile(projectRule.project) as KtFile }
    val intermediatePsi = readAction { intermediateFileVf.toPsiFile(projectRule.project) as KtFile }
    val jvmMainPsi = readAction { jvmMainFileVf.toPsiFile(projectRule.project) as KtFile }

    readAction {
      assertEquals(commonModule, ModuleUtilCore.findModuleForFile(commonAppPsi))
      assertEquals(intermediateModule, ModuleUtilCore.findModuleForFile(intermediatePsi))
      assertEquals(jvmModule, ModuleUtilCore.findModuleForFile(jvmMainPsi))
      assertTrue(commonModule.implementingModules.count { it.platform.isJvm() } >= 2)
      assertTrue(intermediateModule.implementingModules.count { it.platform.isJvm() } >= 2)
    }

    // Test physical commonMain across all three TargetPlatform shapes:
    // 1) Single JvmPlatform (Android + Desktop with same JVM target -> isCommon() == false)
    // 2) Multi-JVM TargetPlatform (Android + Desktop with distinct JVM targets -> isCommon() == false)
    // 3) CommonPlatforms (Android + Desktop + iOS -> isCommon() == true)
    val multiJvmPlatform = TargetPlatform(setOf(JvmPlatforms.jvm8.single(), JvmPlatforms.jvm17.single()))
    val platformCases =
      listOf(
        JvmPlatforms.jvm11 to false,
        multiJvmPlatform to false,
        CommonPlatforms.defaultCommonPlatform to true,
      )
    for ((commonPlatform, expectedIsCommon) in platformCases) {
      runWriteActionAndWait {
        val facetManager = FacetManager.getInstance(commonModule)
        val modifiableModel = facetManager.createModifiableModel()
        modifiableModel.getFacetByType(KotlinFacetType.TYPE_ID)?.let { modifiableModel.removeFacet(it) }
        modifiableModel.commit()
        configureKmpFacet(
          commonModule,
          commonPlatform,
          if (expectedIsCommon) "common_metadata" else "common_jvm",
          isMetadata = expectedIsCommon,
        )
      }
      waitUntilIndexesAreReady()
      readAction {
        assertEquals(commonPlatform, commonModule.platform)
        assertEquals(expectedIsCommon, commonModule.platform.isCommon())
      }

      val outDir = tempFolder.newFolder().toPath()
      val buildTargetReference = readAction { BuildTargetReference.from(commonModule, commonAppPsi.virtualFile) }
      val result =
        compiler.compileRequest(
          ApplicationLiveEditServices.LegacyForTests(projectRule.project),
          listOf(commonAppPsi),
          buildTargetReference,
          outDir,
          EmptyProgressIndicator(),
        )
      assertInstanceOf<CompilationResult.Success>(result)
      assertEquals(listOf("AppKt.class", "AppModel.class"), outDir.toFileNameSet().sorted())

      val appKtBytes = Files.readAllBytes(outDir.resolve("com/test/AppKt.class")).decodeToString()
      assertTrue(
        "Expected reference to WindowInsetsPadding_androidKt for $commonPlatform",
        appKtBytes.contains("WindowInsetsPadding_androidKt"),
      )
      assertFalse(
        "Unexpected reference to WindowInsetsPadding_skikoKt for $commonPlatform",
        appKtBytes.contains("WindowInsetsPadding_skikoKt"),
      )

      // Execute against ONLY the Android library jar (no Skiko jar) to verify runtime compatibility and internal mangling.
      URLClassLoader(arrayOf(outDir.toUri().toURL(), androidJar.toUri().toURL()), null).use { loader ->
        val appKtClass = loader.loadClass("com.test.AppKt")
        assertEquals("android", appKtClass.getDeclaredMethod("appScreen").invoke(null))

        val modelClass = loader.loadClass("com.test.AppModel")
        val modelInstance = modelClass.getDeclaredConstructor().newInstance()
        val mangledMethod = modelClass.getDeclaredMethod("label\$app_android_debug")
        assertEquals("android", mangledMethod.invoke(modelInstance))
      }
    }

    // Intermediate jvmAndAndroidMain source set must also compile in the Android context.
    val intermediateOut = tempFolder.newFolder("kmp_intermediate_out").toPath()
    val intermediateBuildTargetReference = readAction { BuildTargetReference.from(intermediateModule, intermediatePsi.virtualFile) }
    val intermediateResult =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(intermediatePsi),
        intermediateBuildTargetReference,
        intermediateOut,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(intermediateResult)
    val intermediateBytes = Files.readAllBytes(intermediateOut.resolve("com/test/IntermediateKt.class")).decodeToString()
    assertTrue(intermediateBytes.contains("WindowInsetsPadding_androidKt"))
    assertFalse(intermediateBytes.contains("WindowInsetsPadding_skikoKt"))

    // Leaf jvmMain source set must stay in the Desktop/JVM context (not redirected to Android).
    val jvmOut = tempFolder.newFolder("kmp_jvm_out").toPath()
    val buildTargetReference = readAction { BuildTargetReference.from(jvmModule, jvmMainPsi.virtualFile) }
    val result =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(jvmMainPsi),
        buildTargetReference,
        jvmOut,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(result)
    val bytes = Files.readAllBytes(jvmOut.resolve("com/test/DesktopEntryKt.class")).decodeToString()
    assertTrue(bytes.contains("WindowInsetsPadding_skikoKt"))
    assertFalse(bytes.contains("WindowInsetsPadding_androidKt"))
  }

  @Test
  @OptIn(KaExperimentalApi::class)
  fun `physical KMP commonMain file with single Android JVM implementer compiles cleanly`(): Unit = runBlocking {
    val appFile =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/AndroidMarkerSingle.kt",
        """
        package com.test

        class AndroidMarkerSingle
        """
          .trimIndent(),
      )
    val appModule = readAction { appFile.module!! }

    val tempDir = tempFolder.newFolder("kmp_single_jvm_test").toPath()
    val commonRoot = tempDir.resolve("commonMain")
    Files.createDirectories(commonRoot)

    val commonModule = runWriteActionAndWait {
      val commonVf = VfsUtil.findFile(commonRoot, true)!!
      val commonMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "singleJvmCommonMain", commonVf)
      PsiTestUtil.addLibrary(commonMod, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
      ModuleRootModificationUtil.addDependency(appModule, commonMod)

      configureKmpFacet(commonMod, CommonPlatforms.defaultCommonPlatform, "single_common_metadata", isMetadata = true)
      configureKmpFacet(appModule, JvmPlatforms.defaultJvmPlatform, "app_android_debug", dependsOn = listOf(commonMod.name))
      commonMod
    }

    val commonFilePath = commonRoot.resolve("com/test/CommonOnly.kt")
    Files.createDirectories(commonFilePath.parent)
    Files.writeString(
      commonFilePath,
      """
      package com.test

      fun commonGreeting(): String = "hello"

      class CommonOnlyModel {
        internal fun value(): String = commonGreeting()
      }
      """
        .trimIndent(),
    )
    val commonFileVf = VfsUtil.findFile(commonFilePath, true)!!
    waitUntilIndexesAreReady()
    val commonPsi = readAction { commonFileVf.toPsiFile(projectRule.project) as KtFile }

    readAction {
      assertEquals(1, commonModule.implementingModules.count { it.platform.isJvm() })
    }

    val outDir = tempFolder.newFolder("kmp_single_jvm_out").toPath()
    val buildTargetReference = readAction { BuildTargetReference.from(commonModule, commonPsi.virtualFile) }
    val result =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(commonPsi),
        buildTargetReference,
        outDir,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(result)
    assertEquals(listOf("CommonOnlyKt.class", "CommonOnlyModel.class"), outDir.toFileNameSet().sorted())

    URLClassLoader(arrayOf(outDir.toUri().toURL()), null).use { loader ->
      val modelClass = loader.loadClass("com.test.CommonOnlyModel")
      val modelInstance = modelClass.getDeclaredConstructor().newInstance()
      assertEquals("hello", modelClass.getDeclaredMethod("value\$app_android_debug").invoke(modelInstance))
    }
  }

  @Test
  @OptIn(KaExperimentalApi::class)
  fun `physical KMP commonMain compile error reports original file name and line number`(): Unit = runBlocking {
    val appFile =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/AndroidErrorMarker.kt",
        """
        package com.test

        class AndroidErrorMarker
        """
          .trimIndent(),
      )
    val appModule = readAction { appFile.module!! }

    val tempDir = tempFolder.newFolder("kmp_error_line_test").toPath()
    val commonRoot = tempDir.resolve("commonMain")
    val jvmRoot = tempDir.resolve("jvmMain")
    Files.createDirectories(commonRoot)
    Files.createDirectories(jvmRoot)

    val commonModule = runWriteActionAndWait {
      val commonVf = VfsUtil.findFile(commonRoot, true)!!
      val jvmVf = VfsUtil.findFile(jvmRoot, true)!!
      val commonMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpErrCommonMain", commonVf)
      val jvmMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpErrJvmMain", jvmVf)

      for (mod in listOf(commonMod, jvmMod)) {
        ModuleRootModificationUtil.setModuleSdk(mod, ModuleRootManager.getInstance(appModule).sdk)
        PsiTestUtil.addLibrary(mod, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
      }
      ModuleRootModificationUtil.addDependency(appModule, commonMod)
      ModuleRootModificationUtil.addDependency(jvmMod, commonMod)

      configureKmpFacet(commonMod, JvmPlatforms.jvm11, "common_jvm")
      configureKmpFacet(appModule, JvmPlatforms.jvm11, "app_android_debug", dependsOn = listOf(commonMod.name))
      configureKmpFacet(jvmMod, JvmPlatforms.jvm11, "desktop_jvm", dependsOn = listOf(commonMod.name))
      commonMod
    }

    val brokenPath = commonRoot.resolve("com/test/BrokenApp.kt")
    Files.createDirectories(brokenPath.parent)
    Files.writeString(
      brokenPath,
      """
      package com.test

      fun brokenScreen(): String {
        val ok = "hi"
        return missingSymbol()
      }
      """
        .trimIndent(),
    )
    val brokenVf = VfsUtil.findFile(brokenPath, true)!!
    waitUntilIndexesAreReady()
    val brokenPsi = readAction { brokenVf.toPsiFile(projectRule.project) as KtFile }

    val outDir = tempFolder.newFolder("kmp_error_line_out").toPath()
    val buildTargetReference = readAction { BuildTargetReference.from(commonModule, brokenPsi.virtualFile) }
    val result =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(brokenPsi),
        buildTargetReference,
        outDir,
        EmptyProgressIndicator(),
      )
    val error = assertInstanceOf<CompilationResult.CompilationError>(result)
    val updateException = assertInstanceOf<LiveEditUpdateException>(error.e)
    assertEquals("BrokenApp.kt", updateException.sourceFilename)
    assertTrue(
      "Expected diagnostic to report BrokenApp.kt at line 5, got: ${updateException.details}",
      updateException.details.contains("BrokenApp.kt at line 5"),
    )
  }

  @Test
  @OptIn(KaExperimentalApi::class)
  fun `physical KMP commonMain file using expect actual compiles against Android actual`(): Unit = runBlocking {
    val appFile =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/Platform.android.kt",
        """
        package com.test

        actual fun platformName(): String = "Android"
        """
          .trimIndent(),
      )
    val appModule = readAction { appFile.module!! }

    val tempDir = tempFolder.newFolder("kmp_expect_actual_test").toPath()
    val commonRoot = tempDir.resolve("commonMain")
    val jvmRoot = tempDir.resolve("jvmMain")
    Files.createDirectories(commonRoot)
    Files.createDirectories(jvmRoot)

    val commonModule = runWriteActionAndWait {
      val commonVf = VfsUtil.findFile(commonRoot, true)!!
      val jvmVf = VfsUtil.findFile(jvmRoot, true)!!
      val commonMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpExpectCommonMain", commonVf)
      val jvmMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "kmpExpectJvmMain", jvmVf)

      for (mod in listOf(commonMod, jvmMod)) {
        ModuleRootModificationUtil.setModuleSdk(mod, ModuleRootManager.getInstance(appModule).sdk)
        PsiTestUtil.addLibrary(mod, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
      }
      ModuleRootModificationUtil.addDependency(appModule, commonMod)
      ModuleRootModificationUtil.addDependency(jvmMod, commonMod)

      configureKmpFacet(commonMod, JvmPlatforms.jvm11, "common_jvm")
      configureKmpFacet(appModule, JvmPlatforms.jvm11, "app_android_debug", dependsOn = listOf(commonMod.name))
      configureKmpFacet(jvmMod, JvmPlatforms.jvm11, "desktop_jvm", dependsOn = listOf(commonMod.name))
      commonMod
    }

    val jvmActualPath = jvmRoot.resolve("com/test/Platform.jvm.kt")
    Files.createDirectories(jvmActualPath.parent)
    Files.writeString(
      jvmActualPath,
      """
      package com.test

      actual fun platformName(): String = "Desktop"
      """
        .trimIndent(),
    )
    VfsUtil.findFile(jvmActualPath, true)!!

    val expectFilePath = commonRoot.resolve("com/test/Platform.kt")
    Files.createDirectories(expectFilePath.parent)
    Files.writeString(
      expectFilePath,
      """
      package com.test

      expect fun platformName(): String

      fun greetingFromExpectFile(): String = "Hello from " + platformName()
      """
        .trimIndent(),
    )
    val expectVf = VfsUtil.findFile(expectFilePath, true)!!

    val callerFilePath = commonRoot.resolve("com/test/PlatformCaller.kt")
    Files.writeString(
      callerFilePath,
      """
      package com.test

      fun greetingFromCaller(): String = "Hi from " + platformName()
      """
        .trimIndent(),
    )
    val callerVf = VfsUtil.findFile(callerFilePath, true)!!
    waitUntilIndexesAreReady()
    val expectPsi = readAction { expectVf.toPsiFile(projectRule.project) as KtFile }
    val callerPsi = readAction { callerVf.toPsiFile(projectRule.project) as KtFile }

    // 1) Compiling a commonMain file that calls an expect function declared in another commonMain file.
    val callerOut = tempFolder.newFolder("kmp_expect_caller_out").toPath()
    val callerBuildTargetReference = readAction { BuildTargetReference.from(commonModule, callerPsi.virtualFile) }
    val callerResult =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(callerPsi),
        callerBuildTargetReference,
        callerOut,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(callerResult)
    val callerBytes = Files.readAllBytes(callerOut.resolve("com/test/PlatformCallerKt.class")).decodeToString()
    assertTrue(
      "Expected call to Android actual facade Platform_androidKt",
      callerBytes.contains("Platform_androidKt"),
    )
    assertFalse(
      "Unexpected call to Desktop actual facade Platform_jvmKt",
      callerBytes.contains("Platform_jvmKt"),
    )

    // 2) Compiling a commonMain file that declares an expect function in the same file reports a clean
    //    CompilationError mapped to the original file and line (rather than an internal crash or line -1).
    val expectOut = tempFolder.newFolder("kmp_expect_decl_out").toPath()
    val expectBuildTargetReference = readAction { BuildTargetReference.from(commonModule, expectPsi.virtualFile) }
    val expectResult =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(expectPsi),
        expectBuildTargetReference,
        expectOut,
        EmptyProgressIndicator(),
      )
    val error = assertInstanceOf<CompilationResult.CompilationError>(expectResult)
    val updateException = assertInstanceOf<LiveEditUpdateException>(error.e)
    assertEquals("Platform.kt", updateException.sourceFilename)
    assertTrue(
      "Expected diagnostic mapped to Platform.kt at line 3, got: ${updateException.details}",
      updateException.details.contains("Platform.kt at line 3"),
    )
  }

  @Test
  @OptIn(KaExperimentalApi::class)
  fun `physical KMP commonMain file with single Android JVM implementer and JVM platform redirects to Android`(): Unit = runBlocking {
    val appFile =
      projectRule.fixture.addFileToProject(
        "app/src/main/java/com/test/SingleJvmPlatform.android.kt",
        """
        package com.test

        actual fun singleTargetPlatform(): String = "AndroidOnly"
        """
          .trimIndent(),
      )
    val appModule = readAction { appFile.module!! }

    val tempDir = tempFolder.newFolder("kmp_android_only_jvm_test").toPath()
    val commonRoot = tempDir.resolve("commonMain")
    Files.createDirectories(commonRoot)

    val commonModule = runWriteActionAndWait {
      val commonVf = VfsUtil.findFile(commonRoot, true)!!
      val commonMod = PsiTestUtil.addModule(projectRule.project, ModuleType.EMPTY, "androidOnlyKmpCommonMain", commonVf)
      ModuleRootModificationUtil.setModuleSdk(commonMod, ModuleRootManager.getInstance(appModule).sdk)
      PsiTestUtil.addLibrary(commonMod, "stdlib", AdtTestKotlinArtifacts.kotlinStdlib.parent, AdtTestKotlinArtifacts.kotlinStdlib.name)
      ModuleRootModificationUtil.addDependency(appModule, commonMod)

      // Android-only KMP library (com.android.kotlin.multiplatform.library) or Android+iOS synced on Linux/Windows:
      // commonMain gets a JVM TargetPlatform (isCommon() == false) while only 1 JVM implementer (androidMain) exists.
      configureKmpFacet(commonMod, JvmPlatforms.jvm11, "android_only_common_jvm")
      configureKmpFacet(appModule, JvmPlatforms.jvm11, "app_android_debug", dependsOn = listOf(commonMod.name))
      commonMod
    }

    val expectPath = commonRoot.resolve("com/test/SingleJvmPlatform.kt")
    Files.createDirectories(expectPath.parent)
    Files.writeString(
      expectPath,
      """
      package com.test

      expect fun singleTargetPlatform(): String
      """
        .trimIndent(),
    )
    VfsUtil.findFile(expectPath, true)!!

    val callerPath = commonRoot.resolve("com/test/SingleJvmCaller.kt")
    Files.writeString(
      callerPath,
      """
      package com.test

      fun singleTargetGreeting(): String = "Hello " + singleTargetPlatform()
      """
        .trimIndent(),
    )
    val callerVf = VfsUtil.findFile(callerPath, true)!!
    waitUntilIndexesAreReady()
    val callerPsi = readAction { callerVf.toPsiFile(projectRule.project) as KtFile }

    readAction {
      assertEquals(JvmPlatforms.jvm11, commonModule.platform)
      assertFalse(commonModule.platform.isCommon())
      assertEquals(1, commonModule.implementingModules.count { it.platform.isJvm() })
    }

    val outDir = tempFolder.newFolder("kmp_android_only_jvm_out").toPath()
    val buildTargetReference = readAction { BuildTargetReference.from(commonModule, callerPsi.virtualFile) }
    val result =
      compiler.compileRequest(
        ApplicationLiveEditServices.LegacyForTests(projectRule.project),
        listOf(callerPsi),
        buildTargetReference,
        outDir,
        EmptyProgressIndicator(),
      )
    assertInstanceOf<CompilationResult.Success>(result)
    val callerBytes = Files.readAllBytes(outDir.resolve("com/test/SingleJvmCallerKt.class")).decodeToString()
    assertTrue(
      "Expected call to Android actual facade SingleJvmPlatform_androidKt",
      callerBytes.contains("SingleJvmPlatform_androidKt"),
    )
  }
}
