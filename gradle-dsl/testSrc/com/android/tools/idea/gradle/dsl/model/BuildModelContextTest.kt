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
package com.android.tools.idea.gradle.dsl.model

import com.android.SdkConstants.FN_BUILD_GRADLE
import com.android.SdkConstants.FN_GRADLE_PROPERTIES
import com.android.SdkConstants.FN_SETTINGS_GRADLE
import com.android.tools.idea.Projects.getBaseDirPath
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.STRING_TYPE
import com.android.tools.idea.gradle.dsl.parser.GradleDslParser
import com.android.tools.idea.gradle.dsl.parser.GradleDslTransformerFactory
import com.android.tools.idea.gradle.dsl.parser.files.GradleBuildFile
import com.android.tools.idea.gradle.dsl.parser.files.GradleDslFile
import com.android.tools.idea.sdk.IdeSdks
import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.project.stateStore
import com.intellij.psi.PsiFile
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.OpenProjectTaskBuilder
import com.intellij.util.ui.UIUtil
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.jetbrains.annotations.SystemIndependent

private const val SUB_MODULE_NAME = "gradleModelTest"

class BuildModelContextTest : HeavyPlatformTestCase() {

  private lateinit var subModule: Module
  private lateinit var projectBaseDir: VirtualFile
  private lateinit var settingsFile: VirtualFile
  private lateinit var buildFile: VirtualFile
  private lateinit var propertiesFile: VirtualFile
  private lateinit var subModuleBuildFile: VirtualFile
  private lateinit var versionCatalogFile: VirtualFile

  override fun runInDispatchThread(): Boolean = false

  override fun getOpenProjectOptions(): OpenProjectTaskBuilder = super.getOpenProjectOptions().runPostStartUpActivities(false)

  override fun createMainModule(): Module {
    val mainModule = createModule(myProject.name)
    val subModuleFile =
      myProject.stateStore.projectBasePath.resolve(SUB_MODULE_NAME).resolve(SUB_MODULE_NAME + ModuleFileType.DOT_DEFAULT_EXTENSION)
    subModule =
      WriteAction.compute<Module, RuntimeException> {
        ModuleManager.getInstance(myProject).newModule(subModuleFile, moduleType.id)
      }
    return mainModule
  }

  override fun setUp() {
    super.setUp()
    EdtTestUtil.runInEdtAndWait<Exception> {
      IdeSdks.removeJdksOn(testRootDisposable)
      val basePath = myProject.stateStore.projectBasePath
      val subModuleNioDir = basePath.resolve(SUB_MODULE_NAME)
      Files.createDirectories(basePath)
      Files.createDirectories(subModuleNioDir)

      val fs = LocalFileSystem.getInstance()
      projectBaseDir = fs.refreshAndFindFileByNioFile(basePath)!!
      val subModuleDir = fs.refreshAndFindFileByNioFile(subModuleNioDir)!!

      ApplicationManager.getApplication().runWriteAction<Unit, IOException> {
        settingsFile = projectBaseDir.createChildData(this, FN_SETTINGS_GRADLE)
        buildFile = projectBaseDir.createChildData(this, FN_BUILD_GRADLE)
        propertiesFile = projectBaseDir.createChildData(this, FN_GRADLE_PROPERTIES)
        subModuleBuildFile = subModuleDir.createChildData(this, FN_BUILD_GRADLE)
        val gradleDir = projectBaseDir.createChildDirectory(this, "gradle")
        versionCatalogFile = gradleDir.createChildData(this, "libs.versions.toml")
      }
      UIUtil.dispatchAllInvocationEvents()
    }
  }

  fun testInitializeWithRootBuildFileAndParseModuleOnLockFreeEdt() {
    prepareProjectWithRootBuildFile()
    verifyInitializeWithRootBuildFileAndParseModule(
      runner = { SwingUtilities.invokeAndWait(it) },
      assertThreadState = ::assertLockFreeEdt,
    )
  }

  fun testInitializeWithoutRootBuildFileAndParseModuleOnLockFreeEdt() {
    prepareProjectWithoutRootBuildFile()
    verifyInitializeWithoutRootBuildFileAndParseModule(
      runner = { SwingUtilities.invokeAndWait(it) },
      assertThreadState = ::assertLockFreeEdt,
    )
  }

  fun testInitializeWithRootBuildFileAndParseModuleOnWriteIntentEdt() {
    prepareProjectWithRootBuildFile()
    verifyInitializeWithRootBuildFileAndParseModule(
      runner = { ApplicationManager.getApplication().invokeAndWait(it) },
      assertThreadState = ::assertWriteIntentEdt,
    )
  }

  fun testInitializeWithoutRootBuildFileAndParseModuleOnWriteIntentEdt() {
    prepareProjectWithoutRootBuildFile()
    verifyInitializeWithoutRootBuildFileAndParseModule(
      runner = { ApplicationManager.getApplication().invokeAndWait(it) },
      assertThreadState = ::assertWriteIntentEdt,
    )
  }

  fun testInitializeWithRootBuildFileAndParseModuleOnEdtInsideReadAction() {
    prepareProjectWithRootBuildFile()
    verifyInitializeWithRootBuildFileAndParseModule(
      runner = {
        SwingUtilities.invokeAndWait {
          ApplicationManager.getApplication().runReadAction(Runnable { it() })
        }
      },
      assertThreadState = ::assertReadActionEdt,
    )
  }

  fun testInitializeWithoutRootBuildFileAndParseModuleOnEdtInsideReadAction() {
    prepareProjectWithoutRootBuildFile()
    verifyInitializeWithoutRootBuildFileAndParseModule(
      runner = {
        SwingUtilities.invokeAndWait {
          ApplicationManager.getApplication().runReadAction(Runnable { it() })
        }
      },
      assertThreadState = ::assertReadActionEdt,
    )
  }

  fun testInitializeWithRootBuildFileAndParseModuleOnEdtInsideWriteAction() {
    prepareProjectWithRootBuildFile()
    verifyInitializeWithRootBuildFileAndParseModule(
      runner = {
        ApplicationManager.getApplication().invokeAndWait {
          ApplicationManager.getApplication().runWriteAction(Runnable { it() })
        }
      },
      assertThreadState = ::assertWriteActionEdt,
    )
  }

  fun testInitializeWithoutRootBuildFileAndParseModuleOnEdtInsideWriteAction() {
    prepareProjectWithoutRootBuildFile()
    verifyInitializeWithoutRootBuildFileAndParseModule(
      runner = {
        ApplicationManager.getApplication().invokeAndWait {
          ApplicationManager.getApplication().runWriteAction(Runnable { it() })
        }
      },
      assertThreadState = ::assertWriteActionEdt,
    )
  }

  fun testInitializeWithRootBuildFileAndParseModuleOnBackgroundThread() {
    prepareProjectWithRootBuildFile()
    verifyInitializeWithRootBuildFileAndParseModule(
      runner = ::runOnBackgroundThread,
      assertThreadState = ::assertBackgroundThreadWithoutReadLock,
    )
  }

  fun testInitializeWithoutRootBuildFileAndParseModuleOnBackgroundThread() {
    prepareProjectWithoutRootBuildFile()
    verifyInitializeWithoutRootBuildFileAndParseModule(
      runner = ::runOnBackgroundThread,
      assertThreadState = ::assertBackgroundThreadWithoutReadLock,
    )
  }

  fun testInitializeAndParseModuleOnBackgroundThreadRetriesCleanlyOnWriteAction() {
    EdtTestUtil.runInEdtAndWait<Exception> {
      saveFileUnderWrite(settingsFile, "include \':$SUB_MODULE_NAME\'\n")
      saveFileUnderWrite(
        buildFile,
        """
        subprojects {
          repositories {
            mavenCentral()
          }
        }

        dependencies {
          implementation 'com.example:root:1.0'
        }
        """
          .trimIndent(),
      )
      saveFileUnderWrite(
        subModuleBuildFile,
        """
        dependencies {
          implementation 'com.example:sub:1.0'
        }
        """
          .trimIndent(),
      )
      UIUtil.dispatchAllInvocationEvents()
    }

    val parseAttemptsByFile = ConcurrentHashMap<VirtualFile, Int>()
    val completedWriteActions = ConcurrentHashMap<VirtualFile, Semaphore>()
    val existingFactories = GradleDslTransformerFactory.EXTENSION_POINT_NAME.extensionList
    ExtensionTestUtil.maskExtensions(
      GradleDslTransformerFactory.EXTENSION_POINT_NAME,
      existingFactories.map { delegateFactory ->
        object : GradleDslTransformerFactory by delegateFactory {
          override fun createParser(
            psiFile: PsiFile,
            context: BuildModelContext,
            dslFile: GradleDslFile,
          ): GradleDslParser {
            val delegateParser = delegateFactory.createParser(psiFile, context, dslFile)
            return object : GradleDslParser by delegateParser {
              override fun parse() {
                delegateParser.parse()
                if (dslFile is GradleBuildFile && parseAttemptsByFile.merge(dslFile.file, 1, Int::plus) == 1) {
                  val writeActionCompleted = Semaphore(0)
                  assertNull(completedWriteActions.putIfAbsent(dslFile.file, writeActionCompleted))
                  ApplicationManager.getApplication()
                    .invokeLater(
                      {
                        ApplicationManager.getApplication().runWriteAction(Runnable { writeActionCompleted.release() })
                      },
                      ModalityState.any(),
                    )
                  // NonBlockingReadAction cancels its ProgressIndicator in beforeWriteActionStart
                  // (without interrupting the thread), and runWriteAction cannot execute release()
                  // until checkCanceled() unwinds and releases this thread's read lock.
                  while (!writeActionCompleted.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                    ProgressManager.checkCanceled()
                  }
                  throw AssertionError("Expected ReadAction.nonBlocking to be cancelled by pending write action")
                }
              }
            }
          }
        }
      },
      testRootDisposable,
    )

    runOnBackgroundThread {
      assertBackgroundThreadWithoutReadLock()
      val pbm = createProjectBuildModel()
      completedWriteActions.getValue(buildFile).acquire()

      assertBackgroundThreadWithoutReadLock()
      val subModuleModel = pbm.getModuleBuildModel(subModule)!!
      completedWriteActions.getValue(subModuleBuildFile).acquire()
      val rootModel = pbm.projectBuildModel!!

      assertEquals(2, parseAttemptsByFile[buildFile])
      assertEquals(2, parseAttemptsByFile[subModuleBuildFile])

      val rootDslFile = (rootModel as GradleBuildModelImpl).myGradleBuildFile
      val subModuleDslFile = (subModuleModel as GradleBuildModelImpl).myGradleBuildFile
      assertSame(rootDslFile, pbm.context.rootProjectFile)
      assertEquals(listOf(subModuleDslFile), rootDslFile.childModuleBuildFiles.toList())
      assertEquals(
        listOf("com.example:root:1.0"),
        rootModel.dependencies().artifacts().map { it.compactNotation() },
      )
      assertEquals(
        listOf("MavenRepo"),
        subModuleModel.repositories().repositories().map { it.name().forceString() },
      )
      assertEquals(
        listOf("com.example:sub:1.0"),
        subModuleModel.dependencies().artifacts().map { it.compactNotation() },
      )
    }
  }

  fun testLockFreeEdtFallbackCleansUpFailedBuildFileOnException() {
    verifyEdtFallbackCleansUpFailedBuildFileOnException(
      runner = { SwingUtilities.invokeAndWait(it) },
      assertThreadState = ::assertLockFreeEdt,
    )
  }

  fun testWriteIntentEdtFallbackCleansUpFailedBuildFileOnException() {
    verifyEdtFallbackCleansUpFailedBuildFileOnException(
      runner = { ApplicationManager.getApplication().invokeAndWait(it) },
      assertThreadState = ::assertWriteIntentEdt,
    )
  }

  private fun verifyInitializeWithRootBuildFileAndParseModule(
    runner: (() -> Unit) -> Unit,
    assertThreadState: () -> Unit,
  ) {
    runner {
      assertThreadState()
      val pbm = createProjectBuildModel()
      val rootModel = pbm.projectBuildModel
      assertNotNull(rootModel)

      assertThreadState()
      val subModuleModel = pbm.getModuleBuildModel(subModule)
      assertNotNull(subModuleModel)
      assertThreadState()

      verifyParsedProjectWithRootBuildFile(pbm, rootModel!!, subModuleModel!!)
    }
  }

  private fun verifyInitializeWithoutRootBuildFileAndParseModule(
    runner: (() -> Unit) -> Unit,
    assertThreadState: () -> Unit,
  ) {
    runner {
      assertThreadState()
      val pbm = createProjectBuildModel()
      assertNull(pbm.projectBuildModel)

      assertThreadState()
      val subModuleModel = pbm.getModuleBuildModel(subModule)
      assertNotNull(subModuleModel)
      assertThreadState()

      verifyParsedProjectWithoutRootBuildFile(pbm, subModuleModel!!)
    }
  }

  private fun verifyEdtFallbackCleansUpFailedBuildFileOnException(
    runner: (() -> Unit) -> Unit,
    assertThreadState: () -> Unit,
  ) {
    EdtTestUtil.runInEdtAndWait<Exception> {
      saveFileUnderWrite(settingsFile, "include \':$SUB_MODULE_NAME\'\n")
      saveFileUnderWrite(buildFile, "dependencies { implementation 'com.example:root:1.0' }")
      saveFileUnderWrite(subModuleBuildFile, "dependencies { implementation 'com.example:sub:1.0' }")
      UIUtil.dispatchAllInvocationEvents()
    }

    var failingTarget: VirtualFile? = buildFile
    val existingFactories = GradleDslTransformerFactory.EXTENSION_POINT_NAME.extensionList
    ExtensionTestUtil.maskExtensions(
      GradleDslTransformerFactory.EXTENSION_POINT_NAME,
      existingFactories.map { delegateFactory ->
        object : GradleDslTransformerFactory by delegateFactory {
          override fun createParser(
            psiFile: PsiFile,
            context: BuildModelContext,
            dslFile: GradleDslFile,
          ): GradleDslParser {
            val delegateParser = delegateFactory.createParser(psiFile, context, dslFile)
            return object : GradleDslParser by delegateParser {
              override fun parse() {
                delegateParser.parse()
                if (dslFile is GradleBuildFile && dslFile.file == failingTarget) {
                  throw IllegalStateException("Simulated parse failure for ${dslFile.file.name}")
                }
              }
            }
          }
        }
      },
      testRootDisposable,
    )

    runner {
      assertThreadState()
      val failingRootContext = createContext()
      val rootFile = failingRootContext.getGradleBuildFile(getBaseDirPath(project))!!
      try {
        failingRootContext.initializeContext(project, rootFile)
        fail("Expected IllegalStateException")
      } catch (expected: IllegalStateException) {
        assertNull(failingRootContext.rootProjectFile)
      }

      failingTarget = subModuleBuildFile
      assertThreadState()
      val pbm = createProjectBuildModel()
      val rootDslFile = pbm.context.rootProjectFile!!
      assertThreadState()
      try {
        pbm.context.parseBuildFile(project, subModuleBuildFile, SUB_MODULE_NAME, false)
        fail("Expected IllegalStateException")
      } catch (expected: IllegalStateException) {
        assertTrue(rootDslFile.childModuleBuildFiles.isEmpty())
      }
      assertThreadState()
    }
  }

  private fun prepareProjectWithRootBuildFile() {
    EdtTestUtil.runInEdtAndWait<Exception> {
      val appliedFile =
        ApplicationManager.getApplication().runWriteAction<VirtualFile, IOException> {
          projectBaseDir.createChildData(this, "applied.gradle")
        }
      saveFileUnderWrite(
        appliedFile,
        """
        dependencies {
          implementation 'com.example:applied:1.0'
        }
        """
          .trimIndent(),
      )
      saveFileUnderWrite(settingsFile, "include \':$SUB_MODULE_NAME\'\n")
      saveFileUnderWrite(propertiesFile, "rootProp=fromProperties\n")
      saveFileUnderWrite(versionCatalogFile, "[versions]\nrootCatalogVer = \"2.3.4\"\n")
      saveFileUnderWrite(
        buildFile,
        """
        apply from: 'applied.gradle'

        subprojects {
          repositories {
            mavenCentral()
          }
        }

        dependencies {
          implementation 'com.example:root:1.0'
        }
        """
          .trimIndent(),
      )
      saveFileUnderWrite(
        subModuleBuildFile,
        """
        dependencies {
          implementation 'com.example:sub:1.0'
        }
        """
          .trimIndent(),
      )
      UIUtil.dispatchAllInvocationEvents()
    }
  }

  private fun verifyParsedProjectWithRootBuildFile(
    pbm: ProjectBuildModel,
    rootModel: GradleBuildModel,
    subModuleModel: GradleBuildModel,
  ) {
    assertEquals(
      listOf("com.example:applied:1.0", "com.example:root:1.0"),
      rootModel.dependencies().artifacts().map { it.compactNotation() },
    )
    assertEquals(
      "fromProperties",
      rootModel.propertiesModel?.declaredProperties?.find { it.name == "rootProp" }?.getValue(STRING_TYPE),
    )
    assertEquals(
      "2.3.4",
      pbm.versionCatalogsModel.getVersionCatalogModel("libs")?.versions()?.findProperty("rootCatalogVer")?.getValue(STRING_TYPE),
    )
    assertEquals(setOf(":", ":$SUB_MODULE_NAME"), pbm.projectSettingsModel?.modulePaths())
    assertEquals(
      listOf("MavenRepo"),
      subModuleModel.repositories().repositories().map { it.name().forceString() },
    )
    assertEquals(
      listOf("com.example:sub:1.0"),
      subModuleModel.dependencies().artifacts().map { it.compactNotation() },
    )
  }

  private fun prepareProjectWithoutRootBuildFile() {
    EdtTestUtil.runInEdtAndWait<Exception> {
      val customCatalog =
        ApplicationManager.getApplication().runWriteAction<VirtualFile, IOException> {
          buildFile.delete(this)
          projectBaseDir.findChild("gradle")!!.createChildData(this, "custom.versions.toml")
        }
      saveFileUnderWrite(customCatalog, "[versions]\ncustomVer = \"4.5.6\"\n")
      saveFileUnderWrite(versionCatalogFile, "[versions]\ndefaultVer = \"1.2.3\"\n")
      saveFileUnderWrite(
        settingsFile,
        """
        include ':$SUB_MODULE_NAME'
        dependencyResolutionManagement {
          versionCatalogs {
            customLibs {
              from files('gradle/custom.versions.toml')
            }
          }
        }
        """
          .trimIndent(),
      )
      saveFileUnderWrite(
        subModuleBuildFile,
        """
        dependencies {
          implementation 'com.example:sub:1.0'
        }
        """
          .trimIndent(),
      )
      UIUtil.dispatchAllInvocationEvents()
    }
  }

  private fun verifyParsedProjectWithoutRootBuildFile(
    pbm: ProjectBuildModel,
    subModuleModel: GradleBuildModel,
  ) {
    assertEquals(setOf("libs", "customLibs"), pbm.versionCatalogsModel.catalogNames())
    assertEquals(
      "1.2.3",
      pbm.versionCatalogsModel.getVersionCatalogModel("libs")?.versions()?.findProperty("defaultVer")?.getValue(STRING_TYPE),
    )
    assertEquals(
      "4.5.6",
      pbm.versionCatalogsModel.getVersionCatalogModel("customLibs")?.versions()?.findProperty("customVer")?.getValue(STRING_TYPE),
    )
    assertEquals(setOf(":", ":$SUB_MODULE_NAME"), pbm.projectSettingsModel?.modulePaths())
    assertEquals(
      listOf("com.example:sub:1.0"),
      subModuleModel.dependencies().artifacts().map { it.compactNotation() },
    )
  }

  private fun createContext(): BuildModelContext =
    BuildModelContext.create(
      project,
      object : BuildModelContext.ResolvedConfigurationFileLocationProvider {
        override fun getGradleBuildFile(module: Module): VirtualFile? = null

        override fun getGradleProjectRootPath(module: Module): @SystemIndependent String? = VfsUtil.getParentDir(module.moduleFilePath)

        override fun getGradleProjectRootPath(project: Project): @SystemIndependent String = projectBaseDir.path
      },
    )

  private fun createProjectBuildModel(): ProjectBuildModel {
    val context = createContext()
    val file = context.getGradleBuildFile(getBaseDirPath(project))
    return ProjectBuildModelImpl(project, file, context)
  }

  private fun saveFileUnderWrite(file: VirtualFile, text: String) {
    ApplicationManager.getApplication().runWriteAction<Unit, IOException> {
      VfsUtil.saveText(file, text)
    }
  }

  private fun runOnBackgroundThread(action: () -> Unit) {
    try {
      ApplicationManager.getApplication().executeOnPooledThread(Callable { action() }).get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
  }

  private fun assertLockFreeEdt() {
    val app = ApplicationManager.getApplication()
    assertTrue(EventQueue.isDispatchThread())
    assertTrue(app.isDispatchThread)
    assertFalse(app.isWriteIntentLockAcquired)
    assertFalse(app.isReadAccessAllowed)
    assertFalse(app.isWriteAccessAllowed)
  }

  private fun assertWriteIntentEdt() {
    val app = ApplicationManager.getApplication()
    assertTrue(EventQueue.isDispatchThread())
    assertTrue(app.isDispatchThread)
    assertTrue(app.isWriteIntentLockAcquired)
    assertTrue(app.isReadAccessAllowed)
    assertFalse(app.isWriteAccessAllowed)
  }

  private fun assertReadActionEdt() {
    val app = ApplicationManager.getApplication()
    assertTrue(EventQueue.isDispatchThread())
    assertTrue(app.isDispatchThread)
    assertFalse(app.isWriteIntentLockAcquired)
    assertTrue(app.isReadAccessAllowed)
    assertFalse(app.isWriteAccessAllowed)
  }

  private fun assertWriteActionEdt() {
    val app = ApplicationManager.getApplication()
    assertTrue(EventQueue.isDispatchThread())
    assertTrue(app.isDispatchThread)
    assertTrue(app.isWriteIntentLockAcquired)
    assertTrue(app.isReadAccessAllowed)
    assertTrue(app.isWriteAccessAllowed)
  }

  private fun assertBackgroundThreadWithoutReadLock() {
    val app = ApplicationManager.getApplication()
    assertFalse(EventQueue.isDispatchThread())
    assertFalse(app.isDispatchThread)
    assertFalse(app.isWriteIntentLockAcquired)
    assertFalse(app.isReadAccessAllowed)
    assertFalse(app.isWriteAccessAllowed)
  }
}
