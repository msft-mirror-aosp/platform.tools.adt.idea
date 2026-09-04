/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.preview.fast

import com.android.tools.compile.fast.CompilationResult
import com.android.tools.idea.concurrency.UniqueTaskCoroutineLauncher
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.editors.build.PsiCodeFileOutOfDateStatusReporter
import com.android.tools.idea.preview.lifecycle.PreviewLifecycleManager
import com.android.tools.idea.preview.mvvm.PreviewViewModelStatus
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.rendering.BuildTargetReference
import com.android.tools.idea.uibuilder.editor.multirepresentation.MultiRepresentationPreview
import com.android.tools.idea.uibuilder.editor.multirepresentation.TextEditorWithMultiRepresentationPreview
import com.android.tools.idea.util.findAndroidModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.graph.GraphAlgorithms
import kotlin.io.path.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jetbrains.android.uipreview.ModuleClassLoaderOverlays
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.kotlin.psi.KtFile

/** Interface to be implemented by surfaces (like the Preview) that support FastPreview. */
interface FastPreviewSurface {
  /**
   * Request a fast preview refresh. The result [Deferred] will contain the result of the compilation or the method will return
   * [CompilationResult.CompilationAborted] if the compilation request could not be scheduled (e.g. the code has syntax errors).
   */
  fun requestFastPreviewRefreshAsync(): Deferred<CompilationResult>

  companion object {
    val KEY = DataKey.create<FastPreviewSurface>("FastPreviewSurface")
  }
}

/**
 * Default implementation of [FastPreviewSurface], that triggers a fast preview compilation via [requestFastPreviewRefreshAndTrack], and
 * delegates the refresh after a successful compilation to [delegateRefresh].
 */
class CommonFastPreviewSurface(
  parentDisposable: Disposable,
  private val lifecycleManager: PreviewLifecycleManager,
  private val psiFilePointer: SmartPsiElementPointer<PsiFile>,
  private val previewStatusProvider: () -> PreviewViewModelStatus,
  private val delegateRefresh: suspend () -> Unit,
) : Disposable, FastPreviewSurface {

  private val myPsiCodeFileOutOfDateStatusReporter = PsiCodeFileOutOfDateStatusReporter.getInstance(psiFilePointer.project)

  private val coroutineScope: CoroutineScope

  init {
    Disposer.register(parentDisposable, this)
    coroutineScope = this.createCoroutineScope()
  }

  /** [UniqueTaskCoroutineLauncher] for ensuring that only one fast preview request is launched at a time. */
  private val fastPreviewCompilationLauncher: UniqueTaskCoroutineLauncher by
    lazy(LazyThreadSafetyMode.SYNCHRONIZED) { UniqueTaskCoroutineLauncher(coroutineScope, "Compilation Launcher") }

  override fun requestFastPreviewRefreshAsync(): Deferred<CompilationResult> =
    lifecycleManager.executeIfActive { async { requestFastPreviewRefreshSync() } }
      ?: CompletableDeferred(CompilationResult.CompilationAborted())

  /**
   * Request a fast preview compilation, followed by preview refresh when the compilation is successful. Note that this method waits for the
   * compilation to complete, returning its result, but it doesn't wait for the refresh to finish.
   */
  suspend fun requestFastPreviewRefreshSync(): CompilationResult {
    val previewFile =
      readAction { psiFilePointer.element } ?: return CompilationResult.RequestException(IllegalStateException("Preview File is not valid"))
    val previewFileBuildTargetReference =
      readAction { BuildTargetReference.from(previewFile) }
        ?: return CompilationResult.RequestException(IllegalStateException("Preview File does not have a valid module"))
    val previewFileAndroidModule =
      previewFileBuildTargetReference.module.findAndroidModule()
        ?: return CompilationResult.RequestException(IllegalStateException("Preview File does not have a valid Android module"))
    val outOfDateFiles =
      myPsiCodeFileOutOfDateStatusReporter.outOfDateFiles
        .filterIsInstance<KtFile>()
        .filter { modifiedFile ->
          if (modifiedFile.isEquivalentTo(previewFile)) return@filter true
          val modifiedFileBuildTargetReference = readAction { BuildTargetReference.from(modifiedFile) } ?: return@filter false

          // Keep the file if the file is from this module or from a module we depend on
          modifiedFileBuildTargetReference == previewFileBuildTargetReference ||
            // TODO: solodkyy - This is wrong. Expose this operation via the BTR.
            ModuleManager.getInstance(psiFilePointer.project)
              .isModuleDependent(previewFileAndroidModule, modifiedFileBuildTargetReference.module)
        }
        .toSet()

    // Nothing to compile
    if (outOfDateFiles.isEmpty()) return CompilationResult.Success

    // Resolve module instances for out-of-date files before compilation starts to avoid capturing
    // PsiFiles across the asynchronous compilation gap (which could lead to PSI invalidation).
    val outOfDateFilesModules = readAction {
      outOfDateFiles.mapNotNull { ModuleUtilCore.findModuleForFile(it) }.toSet()
    }

    return requestFastPreviewRefreshAndTrack(
      parentDisposable = this,
      previewFileBuildTargetReference,
      outOfDateFiles,
      previewStatusProvider(),
      fastPreviewCompilationLauncher,
    ) { outputAbsolutePath ->
      val outputPath = Path(outputAbsolutePath)
      val project = psiFilePointer.project

      fun pushOverlayPath(target: BuildTargetReference) = ModuleClassLoaderOverlays.getInstance(target).pushOverlayPath(outputPath)

      // Push the compiled overlay to the active preview's module first.
      pushOverlayPath(previewFileBuildTargetReference)

      // When code in a dependency module changes, any other open previews whose modules depend
      // on the modified module also need their ClassLoader overlays updated so that subsequent
      // refreshes in those previews pick up the newly compiled bytecode.
      // We retrieve the holder modules of any other open preview editors on the EDT.
      val otherOpenPreviewModules =
        withContext(Dispatchers.EDT) {
          runReadAction {
            project.findOpenPreviewAndroidHolderModules() - previewFileAndroidModule.androidHolderModule
          }
        }

      // If other preview editors are open, traverse the module dependency graph to find which of them
      // transitively depend on the modified modules and update their overlay paths as well.
      // If no other previews are open (the common case), this graph traversal is skipped entirely.
      if (otherOpenPreviewModules.isNotEmpty()) {
        val outOfDateModules = readAction {
          project.findDependentAndroidHolderModules(outOfDateFilesModules) { it in otherOpenPreviewModules }
        }
        outOfDateModules.forEach { module ->
          pushOverlayPath(BuildTargetReference.gradleOnly(module))
        }
      }

      delegateRefresh()
    }
  }

  override fun dispose() {}
}

internal inline val Module.androidHolderModule: Module
  get() = (findAndroidModule() ?: this).getModuleSystem().getHolderModule()

/** Finds holder modules for all currently open editors that host an active preview representation. */
@RequiresReadLock
@RequiresEdt
@VisibleForTesting
internal fun Project.findOpenPreviewAndroidHolderModules(): Set<Module> =
  findOpenPreviewAndroidHolderModules(FileEditorManager.getInstance(this).allEditors)

/** Finds holder modules for the given list of [allEditors] that host an active preview representation. */
@RequiresReadLock
@VisibleForTesting
internal fun Project.findOpenPreviewAndroidHolderModules(allEditors: Array<FileEditor>): Set<Module> = buildSet {
  val project = this@findOpenPreviewAndroidHolderModules
  val fileIndex = ProjectFileIndex.getInstance(project)

  for (editor in allEditors) when (editor) {
    is MultiRepresentationPreview -> editor
    is TextEditorWithMultiRepresentationPreview<*> -> editor.preview
    else -> null
  }?.let { preview ->
    if (preview.representationNames.isEmpty()) continue
    val module = fileIndex.getModuleForFile(preview.file) ?: continue
    val androidHolderModule = module.androidHolderModule
    add(androidHolderModule)
  }
}

/**
 * Traverses the project module dependency graph starting from [modules] to collect all downstream modules that transitively depend on them,
 * filtering the results with [predicate].
 */
@RequiresReadLock
@VisibleForTesting
internal fun Project.findDependentAndroidHolderModules(
  modules: Iterable<Module>,
  predicate: (Module) -> Boolean,
): Set<Module> {
  val moduleGraph = ModuleManager.getInstance(this).moduleGraph()
  val algorithms = GraphAlgorithms.getInstance()
  val dependentModules = HashSet<Module>()
  for (module in modules) {
    algorithms.collectOutsRecursively(moduleGraph, module, dependentModules)
  }
  return dependentModules.mapNotNullTo(HashSet()) { it.androidHolderModule.takeIf(predicate) }
}
