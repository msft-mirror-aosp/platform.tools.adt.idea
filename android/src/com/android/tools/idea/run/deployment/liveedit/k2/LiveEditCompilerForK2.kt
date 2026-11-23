/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.run.deployment.liveedit.k2

import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.deployment.liveedit.CompilerErrorSource
import com.android.tools.idea.run.deployment.liveedit.LiveEditCompiler
import com.android.tools.idea.run.deployment.liveedit.LiveEditCompilerInput
import com.android.tools.idea.run.deployment.liveedit.LiveEditUpdateException
import com.android.tools.idea.run.deployment.liveedit.LiveEditUpdateException.Companion.compilationError
import com.android.tools.idea.run.deployment.liveedit.checkPsiErrorElement
import com.android.tools.idea.run.deployment.liveedit.readActionPrebuildChecks
import com.android.tools.idea.run.deployment.liveedit.runWithCompileLock
import com.android.tools.idea.run.deployment.liveedit.tokens.ApplicationLiveEditServices
import com.android.tools.idea.util.findAndroidModule
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaCompilationOptionsBuilder
import org.jetbrains.kotlin.analysis.api.components.KaCompilationResult
import org.jetbrains.kotlin.analysis.api.components.KaCompilationTarget
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnostic
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.getDefaultMessageWithFactoryName
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.projectStructure.isDangling
import org.jetbrains.kotlin.idea.base.facet.implementingModules
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.projectStructure.toKaSourceModuleForProduction
import org.jetbrains.kotlin.idea.base.projectStructure.toKaSourceModuleForTest
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

@OptIn(KaExperimentalApi::class)
internal class LiveEditCompilerForK2(private val project: Project, private val module: Module) :
  LiveEditCompiler.LiveEditCompilerForKotlinVersion {

  private val LOGGER = LogWrapper(Logger.getInstance(LiveEditCompilerForK2::class.java))

  override fun compileKtFile(
    applicationLiveEditServices: ApplicationLiveEditServices,
    file: KtFile,
    inputs: Collection<LiveEditCompilerInput>,
  ) = runWithCompileLock {
    LOGGER.info("Using Live Edit K2 CodeGen")
    ReadAction.computeCancellable<Unit, Throwable> { readActionPrebuildChecks(project, file) }
    val result = backendCodeGenForK2(file, module) { with(applicationLiveEditServices) { configureKotlinCompilerOptions(file) } }
    return@runWithCompileLock result.output.map { OutputFileForKtCompiledFile(it) }
  }
}

@OptIn(KaExperimentalApi::class)
private fun getCompileTargetFile(original: KtFile, module: Module): KtFile {
  val androidModule = module.findAndroidModule() ?: return original
  if (androidModule == module) {
    return original
  }

  // `module` is a non-Android shared KMP source set with an Android implementer.
  // `KaFirCompilerFacility`'s built-in single-JVM actualizer only runs when `module.platform.isCommon()` is
  // true. When all active targets are JVM (e.g. androidTarget() + jvm(), com.android.kotlin.multiplatform.library,
  // or Android + iOS synced on Linux/Windows where Apple targets are inactive), KGP assigns the shared source
  // set a JVM platform (`isCommon() == false`), so it must always redirect to `androidModule` regardless of
  // how many JVM implementers exist. Keep the KMT-1285 `< 2` guard only for true common platforms.
  val isInMemory = original.isDangling || original.virtualFile is LightVirtualFile
  if (!isInMemory && module.platform.isCommon() && module.implementingModules.count { it.platform.isJvm() } < 2) {
    return original
  }

  val sourceModule = androidModule.toKaSourceModuleForProduction() ?: androidModule.toKaSourceModuleForTest() ?: return original
  if (original.isDangling && original.contextModule == sourceModule) {
    return original
  }

  // create a dangling copy of this file with the proper (Android) context.
  val danglingFile = KtPsiFactory(module.project).createFile(original.name, original.text)
  danglingFile.contextModule = sourceModule
  return danglingFile
}

@OptIn(KaExperimentalApi::class)
fun backendCodeGenForK2(file: KtFile, module: Module, configurator: KaCompilationOptionsBuilder.() -> Unit): KaCompilationResult.Success {
  // TODO(316965795): Check the performance and the responsiveness once we complete K2 LE implementation.
  //                  Add/remove ProgressManager.checkCanceled() based on the performance and the responsiveness.
  ProgressManager.checkCanceled()

  return ReadAction.computeCancellable<KaCompilationResult.Success, Throwable> {
    if (!file.isValid) {
      throw LiveEditUpdateException.fileNotValid(file)
    }
    if (module.isDisposed) {
      throw LiveEditUpdateException.moduleIsDisposed(module)
    }
    if (ModuleUtilCore.findModuleForFile(file) != module) {
      throw LiveEditUpdateException.internalErrorFileOutsideModule(file)
    }

    // Since K2 compile AA reports syntax error, this may be unnecessary, but it throws an exception early when it has a syntax error.
    // In other words, there is no performance penalty from this early check. Let's keep it because there is no guarantee that
    // K2 compile AA covers all cases.
    listOf(file).checkPsiErrorElement()

    ProgressManager.checkCanceled()
    val substituteFile = getCompileTargetFile(file, module)
    ProgressManager.checkCanceled()
    analyze(substituteFile) {
      val options = createCompilationOptions {
        target(KaCompilationTarget.JVM)
        allowedErrorFilter { false } // Always report diagnostic errors, do not filter.
        configurator()
      }
      when (val result = this@analyze.compile(substituteFile, options)) {
        is KaCompilationResult.Success -> result
        is KaCompilationResult.Failure -> throw compilationError(result.errors.map { it.getErrorMessage(file, substituteFile) })
      }
    }
  }
}

private fun KaDiagnostic.getErrorMessage(originalFile: KtFile, substituteFile: KtFile): CompilerErrorSource {
  val message = getDefaultMessageWithFactoryName()
  if (this is KaDiagnosticWithPsi<*>) {
    val psiFile = psi.containingFile
    val reportedFile = if (psiFile == substituteFile) originalFile else psiFile
    val lineNumber = getLineNumberFromKaDiagnostic()
    return CompilerErrorSource(severity.toString(), message, reportedFile, lineNumber)
  } else {
    return CompilerErrorSource(severity.toString(), message, null, -1)
  }
}

private fun KaDiagnosticWithPsi<*>.getLineNumberFromKaDiagnostic(): Int {
  val containingFile = psi.containingFile ?: return -1
  val textOffset = psi.textOffset
  val document = PsiDocumentManager.getInstance(containingFile.project).getDocument(containingFile)
  if (document != null && textOffset in 0..document.textLength) {
    return document.getLineNumber(textOffset) + 1
  }

  val text = containingFile.text
  return if (textOffset in 0..text.length) StringUtil.offsetToLineNumber(text, textOffset) + 1 else -1
}
