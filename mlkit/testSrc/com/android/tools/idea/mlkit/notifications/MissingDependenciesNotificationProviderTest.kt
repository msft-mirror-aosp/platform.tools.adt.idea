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
package com.android.tools.idea.mlkit.notifications

import com.android.ide.common.repository.StubGoogleMavenRepository
import com.android.tools.idea.gradle.model.ARTIFACT_NAME_MAIN
import com.android.tools.idea.gradle.model.IdeSourceProvider
import com.android.tools.idea.gradle.model.impl.IdeDeclaredDependenciesImpl
import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker
import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.android.tools.idea.gradle.repositories.RepositoryUrlManager
import com.android.tools.idea.mlkit.viewer.TfliteModelFileEditor
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.setupTestProjectFromAndroidModel
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.EDT
import com.intellij.util.ui.UIUtil
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/** Integration-style tests for [MissingDependenciesNotificationProvider]. */
class MissingDependenciesNotificationProviderTest {

  @get:Rule val projectRule = AndroidProjectRule.withAndroidModels()

  private val notificationProvider = MissingDependenciesNotificationProvider()
  private val dialogMessages = CopyOnWriteArrayList<String>()
  private val syncTriggers = CopyOnWriteArrayList<Trigger>()
  private var dialogAnswer: Int = Messages.OK
  private var assertLockFreeDialogEdt: Boolean = false

  @Before
  fun setUp() {
    val stubMavenRepo =
      StubGoogleMavenRepository(
        mapOf(
          "master-index.xml" to
            """
            <?xml version='1.0' encoding='UTF-8'?>
            <metadata>
              <org.tensorflow/>
            </metadata>
            """
              .trimIndent(),
          "org/tensorflow/group-index.xml" to
            """
            <?xml version='1.0' encoding='UTF-8'?>
            <org.tensorflow>
              <tensorflow-lite-support versions="0.1.0"/>
              <tensorflow-lite-metadata versions="0.1.0"/>
            </org.tensorflow>
            """
              .trimIndent(),
        )
      )
    val app = ApplicationManager.getApplication()
    app.replaceService(
      RepositoryUrlManager::class.java,
      RepositoryUrlManager(stubMavenRepo, stubMavenRepo, false),
      projectRule.testRootDisposable,
    )
    app.replaceService(
      GradleSyncInvoker::class.java,
      object : GradleSyncInvoker.FakeInvoker() {
        override fun requestProjectSync(
          project: Project,
          request: GradleSyncInvoker.Request,
          listener: GradleSyncListener?,
        ) {
          syncTriggers.add(request.trigger)
          super.requestProjectSync(project, request, listener)
        }
      },
      projectRule.testRootDisposable,
    )

    TestDialogManager.setTestDialog { message ->
      assertThat(EDT.isCurrentThreadEdt()).isTrue()
      assertThat(app.isWriteAccessAllowed).isFalse()
      if (assertLockFreeDialogEdt) {
        assertThat(app.isReadAccessAllowed).isFalse()
        assertThat(app.isWriteIntentLockAcquired).isFalse()
      }
      dialogMessages.add(message)
      dialogAnswer
    }
  }

  @After
  fun tearDown() {
    TestDialogManager.setTestDialog(TestDialog.DEFAULT)
  }

  @Test
  fun addNowHyperlink_withVersionCatalog_updatesCatalogAndBuildFileAndRequestsSync() {
    val baseDir = File(projectRule.project.basePath!!)
    val catalogFile = File(baseDir, "gradle/libs.versions.toml")
    catalogFile.parentFile.mkdirs()
    catalogFile.writeText(
      """
      [versions]

      [libraries]
      """
        .trimIndent() + "\n"
    )
    val buildFile = File(baseDir, "build.gradle")
    val modelFile =
      setupMlProject(
        buildGradleText =
          """
          dependencies {
          }
          """
            .trimIndent() + "\n"
      )

    val fileEditor = createMockTfliteEditor()
    val panel = collectAndCreatePanelOnLockFreeEdt(modelFile, fileEditor)
    assertThat(panel).isNotNull()
    assertThat(panel!!.text).isEqualTo("ML Model Binding dependencies not found.")

    runOnLockFreeSwingEdt {
      val addNowLink = findHyperlink(panel, "Add Now")
      addNowLink.doClick()
    }

    assertThat(dialogMessages)
      .containsExactly(
        "This operation requires the libraries " +
          "org.tensorflow:tensorflow-lite-support, org.tensorflow:tensorflow-lite-metadata.\n\n" +
          "Would you like to add these now?"
      )

    saveAndFlushAllDocuments()
    val updatedCatalog = catalogFile.readText()
    assertThat(updatedCatalog).contains("tensorflowLiteSupport = \"0.1.0\"")
    assertThat(updatedCatalog).contains("tensorflowLiteMetadata = \"0.1.0\"")
    assertThat(updatedCatalog).contains("name = \"tensorflow-lite-support\"")
    assertThat(updatedCatalog).contains("name = \"tensorflow-lite-metadata\"")

    val updatedBuild = buildFile.readText()
    assertThat(updatedBuild).contains("implementation libs.tensorflow.lite.support")
    assertThat(updatedBuild).contains("implementation libs.tensorflow.lite.metadata")

    assertThat(syncTriggers).containsExactly(Trigger.TRIGGER_PROJECT_MODIFIED)
  }

  @Test
  fun addNowHyperlink_inSubmoduleWithVersionCatalogAndNoRootBuildFile_updatesCatalogAndAppBuildFile() {
    val baseDir = File(projectRule.project.basePath!!)
    val catalogFile = File(baseDir, "gradle/libs.versions.toml")
    catalogFile.parentFile.mkdirs()
    catalogFile.writeText(
      """
      [versions]

      [libraries]
      """
        .trimIndent() + "\n"
    )
    val appBuildFile = File(baseDir, "app/build.gradle")
    val modelFile =
      setupSubmoduleMlProject(
        buildGradleText =
          """
          dependencies {
          }
          """
            .trimIndent() + "\n"
      )

    val fileEditor = createMockTfliteEditor()
    val panel = checkNotNull(collectAndCreatePanelOnLockFreeEdt(modelFile, fileEditor))

    runOnLockFreeSwingEdt { findHyperlink(panel, "Add Now").doClick() }

    saveAndFlushAllDocuments()
    val updatedCatalog = catalogFile.readText()
    assertThat(updatedCatalog).contains("tensorflowLiteSupport = \"0.1.0\"")
    assertThat(updatedCatalog).contains("tensorflowLiteMetadata = \"0.1.0\"")

    val updatedAppBuild = appBuildFile.readText()
    assertThat(updatedAppBuild).contains("implementation libs.tensorflow.lite.support")
    assertThat(updatedAppBuild).contains("implementation libs.tensorflow.lite.metadata")

    assertThat(syncTriggers).containsExactly(Trigger.TRIGGER_PROJECT_MODIFIED)
  }

  @Test
  fun addNowIntentionQuickFix_onLockFreeEdt_addsRemainingBuildFileDependency() {
    val baseDir = File(projectRule.project.basePath!!)
    val buildFile = File(baseDir, "build.gradle")
    val modelFile =
      setupMlProject(
        declaredDependencies = listOf("org.tensorflow:tensorflow-lite-support:0.1.0"),
        buildGradleText =
          """
          dependencies {
              implementation 'org.tensorflow:tensorflow-lite-support:0.1.0'
          }
          """
            .trimIndent() + "\n",
      )

    val fileEditor = createMockTfliteEditor()
    val panel = checkNotNull(collectAndCreatePanelOnLockFreeEdt(modelFile, fileEditor))
    val mockEditor = mock(Editor::class.java)
    val mockPsiFile = mock(PsiFile::class.java)
    assertLockFreeDialogEdt = true

    runOnLockFreeSwingEdt {
      val intention = checkNotNull(panel.intentionAction)
      assertThat(intention.text).isEqualTo("Add Now")
      intention.invoke(projectRule.project, mockEditor, mockPsiFile)
    }

    assertThat(dialogMessages)
      .containsExactly(
        "This operation requires the library org.tensorflow:tensorflow-lite-metadata.\n\n" + "Would you like to add this now?"
      )

    saveAndFlushAllDocuments()
    val updatedBuild = buildFile.readText()
    assertThat(updatedBuild).contains("org.tensorflow:tensorflow-lite-support:0.1.0")
    assertThat(updatedBuild).contains("implementation 'org.tensorflow:tensorflow-lite-metadata:0.1.0'")
    assertThat(syncTriggers).containsExactly(Trigger.TRIGGER_PROJECT_MODIFIED)
  }

  @Test
  fun addNow_whenCancelled_doesNotModifyBuildFileOrRequestSync() {
    val baseDir = File(projectRule.project.basePath!!)
    val initialBuild = "dependencies {\n}\n"
    val modelFile = setupMlProject(buildGradleText = initialBuild)
    val buildFile = File(baseDir, "build.gradle")
    dialogAnswer = Messages.CANCEL

    val fileEditor = createMockTfliteEditor()
    val panel = checkNotNull(collectAndCreatePanelOnLockFreeEdt(modelFile, fileEditor))

    runOnLockFreeSwingEdt { findHyperlink(panel, "Add Now").doClick() }

    assertThat(dialogMessages).hasSize(1)
    saveAndFlushAllDocuments()
    assertThat(buildFile.readText()).isEqualTo(initialBuild)
    assertThat(syncTriggers).isEmpty()
  }

  @Test
  fun hideNotification_marksEditorHiddenAndSuppressesSubsequentPanel() {
    val modelFile = setupMlProject(buildGradleText = "dependencies {\n}\n")
    val fileEditor = createMockTfliteEditor()
    val paneProvider =
      checkNotNull(
        ReadAction.compute<
          java.util.function.Function<FileEditor, EditorNotificationPanel>?,
          RuntimeException,
        > {
          notificationProvider.collectNotificationData(projectRule.project, modelFile)
        }
      )

    val panelRef = AtomicReference<EditorNotificationPanel?>()
    runOnLockFreeSwingEdt { panelRef.set(paneProvider.apply(fileEditor)) }
    val panel = checkNotNull(panelRef.get())

    runOnLockFreeSwingEdt {
      findHyperlink(panel, "Hide notification").doClick()
      assertThat(paneProvider.apply(fileEditor)).isNull()
    }
  }

  @Test
  fun allDependenciesPresent_returnsNoNotification() {
    val modelFile =
      setupMlProject(
        declaredDependencies =
          listOf(
            "org.tensorflow:tensorflow-lite-support:0.1.0",
            "org.tensorflow:tensorflow-lite-metadata:0.1.0",
          ),
        buildGradleText = "dependencies {\n}\n",
      )

    val paneProvider =
      ReadAction.compute<
        java.util.function.Function<FileEditor, EditorNotificationPanel>?,
        RuntimeException,
      > {
        notificationProvider.collectNotificationData(projectRule.project, modelFile)
      }
    assertThat(paneProvider).isNull()
  }

  private fun setupMlProject(
    declaredDependencies: List<String> = emptyList(),
    buildGradleText: String,
  ): VirtualFile {
    val project = projectRule.project
    val baseDir = File(project.basePath!!)
    File(baseDir, "settings.gradle").writeText("rootProject.name = 'testProject'\n")
    File(baseDir, "build.gradle").writeText(buildGradleText)
    val mlDir = File(baseDir, "ml").apply { mkdirs() }
    val modelIoFile = File(mlDir, "my_model.tflite").apply { writeBytes(byteArrayOf(0)) }

    return EdtTestUtil.runInEdtAndGet<VirtualFile, RuntimeException> {
      setupTestProjectFromAndroidModel(
        project,
        baseDir,
        createMlAndroidModuleModelBuilder(
          gradlePath = ":",
          declaredDependencies = declaredDependencies,
        ),
      )
      VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)
      checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(modelIoFile))
    }
  }

  private fun setupSubmoduleMlProject(
    declaredDependencies: List<String> = emptyList(),
    buildGradleText: String,
  ): VirtualFile {
    val project = projectRule.project
    val baseDir = File(project.basePath!!)
    File(baseDir, "settings.gradle.kts").writeText("rootProject.name = \"testProject\"\ninclude(\":app\")\n")
    val appDir = File(baseDir, "app").apply { mkdirs() }
    File(appDir, "build.gradle").writeText(buildGradleText)
    val mlDir = File(appDir, "ml").apply { mkdirs() }
    val modelIoFile = File(mlDir, "my_model.tflite").apply { writeBytes(byteArrayOf(0)) }

    return EdtTestUtil.runInEdtAndGet<VirtualFile, RuntimeException> {
      setupTestProjectFromAndroidModel(
        project,
        baseDir,
        JavaModuleModelBuilder.rootModuleBuilder,
        createMlAndroidModuleModelBuilder(
          gradlePath = ":app",
          declaredDependencies = declaredDependencies,
        ),
      )
      VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)
      checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(modelIoFile))
    }
  }

  private fun createMlAndroidModuleModelBuilder(
    gradlePath: String,
    declaredDependencies: List<String>,
  ): AndroidModuleModelBuilder =
    AndroidModuleModelBuilder(
      gradlePath = gradlePath,
      agpVersion = "8.0.0",
      selectedBuildVariant = "debug",
      projectBuilder =
        AndroidProjectBuilder(
          minSdk = { 28 },
          mlModelBindingEnabled = { true },
          namespace = { "p1.p2" },
          declaredDependencies = { IdeDeclaredDependenciesImpl(declaredDependencies) },
          mainSourceProvider = {
            IdeSourceProvider(
              ARTIFACT_NAME_MAIN,
              moduleBasePath,
              "AndroidManifest.xml",
              listOf("src"),
              listOf("srcKotlin"),
              emptyList(),
              emptyList(),
              emptyList(),
              listOf("res"),
              emptyList(),
              emptyList(),
              emptyList(),
              listOf("ml"),
              emptyList(),
              emptyList(),
              emptyList(),
              emptyList(),
            )
          },
        ),
    )

  private fun collectAndCreatePanelOnLockFreeEdt(
    modelFile: VirtualFile,
    fileEditor: FileEditor,
  ): EditorNotificationPanel? {
    val paneProvider =
      ReadAction.compute<
        java.util.function.Function<FileEditor, EditorNotificationPanel>?,
        RuntimeException,
      > {
        assertThat(EDT.isCurrentThreadEdt()).isFalse()
        notificationProvider.collectNotificationData(projectRule.project, modelFile)
      } ?: return null
    val panelRef = AtomicReference<EditorNotificationPanel?>()
    runOnLockFreeSwingEdt { panelRef.set(paneProvider.apply(fileEditor)) }
    return panelRef.get()
  }

  private fun runOnLockFreeSwingEdt(action: () -> Unit) {
    assertThat(EDT.isCurrentThreadEdt()).isFalse()
    SwingUtilities.invokeAndWait {
      (TransactionGuard.getInstance() as TransactionGuardImpl).performUserActivity {
        val app = ApplicationManager.getApplication()
        assertThat(EventQueue.isDispatchThread()).isTrue()
        assertThat(EDT.isCurrentThreadEdt()).isTrue()
        assertThat(app.isDispatchThread).isTrue()
        assertThat(app.isReadAccessAllowed).isFalse()
        assertThat(app.isWriteIntentLockAcquired).isFalse()
        assertThat(app.isWriteAccessAllowed).isFalse()
        action()
      }
    }
  }

  private fun saveAndFlushAllDocuments() {
    EdtTestUtil.runInEdtAndWait<RuntimeException> {
      ApplicationManager.getApplication().runWriteAction {
        FileDocumentManager.getInstance().saveAllDocuments()
      }
    }
    // Flush asynchronous VFS content writes off EDT before reading files via java.io.File.
    PlatformTestUtil.flushAllPendingVFSUpdates()
  }

  private fun findHyperlink(panel: EditorNotificationPanel, text: String): HyperlinkLabel =
    UIUtil.findComponentsOfType(panel, HyperlinkLabel::class.java).single { it.text == text }

  private fun createMockTfliteEditor(): TfliteModelFileEditor {
    val holder = UserDataHolderBase()
    val editor = mock(TfliteModelFileEditor::class.java)
    doAnswer { invocation -> holder.getUserData(invocation.getArgument<Key<Any?>>(0)) }.`when`(editor).getUserData(any<Key<Any?>>())
    doAnswer { invocation ->
        holder.putUserData(invocation.getArgument<Key<Any?>>(0), invocation.getArgument(1))
        null
      }
      .`when`(editor)
      .putUserData(any<Key<Any?>>(), any())
    return editor
  }
}
