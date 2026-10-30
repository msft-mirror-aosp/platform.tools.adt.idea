/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.model

import com.android.SdkConstants
import com.android.ide.common.util.PathString
import com.android.tools.idea.projectsystem.SourceProviderManager
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.projectsystem.isManifestFile
import com.android.tools.idea.util.LazyFileListenerSubscriber
import com.android.tools.idea.util.PoliteAndroidVirtualFileListener
import com.android.tools.idea.util.listenUntilNextSync
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.BoundedTaskExecutor
import com.intellij.util.containers.TreeTraversal
import java.util.concurrent.TimeUnit
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.idea.util.toKaModulesForModificationEvents

/**
 * A project-wide listener that determines which modules' merged manifests are affected by VFS changes or Document changes and tells the
 * corresponding [MergedManifestModificationTracker]s to increment counter. [MergedManifestModificationListener] registers itself to start
 * actively listening for VFS changes and Document changes after the first project sync of the session.
 */
class MergedManifestModificationListener(project: Project) :
  PoliteAndroidVirtualFileListener(project), DocumentListener, FileDocumentManagerListener {

  private val psiDocumentManager = PsiDocumentManager.getInstance(project)
  private val fileDocumentManager = FileDocumentManager.getInstance()

  /** Facets whose manifests changed and have not yet been processed by [flushCaches]. Guarded by itself. */
  private val pendingFacets = mutableSetOf<AndroidFacet>()

  /** Whether a [flushCaches] task is queued or running. Guarded by [pendingFacets]. */
  private var flushScheduled = false

  private val trackerUpdaterExecutor =
    AppExecutorUtil.createBoundedApplicationPoolExecutor("Merged Manifest Modification Tracker Updater Pool", 1)

  @TestOnly
  fun waitAllUpdatesCompletedWithTimeout(timeout: Long, unit: TimeUnit) {
    (trackerUpdaterExecutor as BoundedTaskExecutor).waitAllTasksExecuted(timeout, unit)
  }

  // If a directory was deleted, we won't get a separate event for each descendant, so we
  // must let directories pass through this fail-fast filter in case they contain relevant files.
  // For non-directories, only AndroidManifest.xml files can be relevant (see isRelevant), so avoid
  // looking up the containing module and AndroidFacet for all other XML files (such as layouts,
  // drawables, values, or IDE configuration files).
  override fun isPossiblyRelevant(file: VirtualFile) = file.isDirectory || file.name == SdkConstants.FN_ANDROID_MANIFEST_XML

  /**
   * Determines if the changed file contributes to merged manifest attributes computed using the [AndroidManifestIndex]. This means the file
   * is either:
   * 1. An AndroidManifest.xml belonging to one of the containing module's source providers.
   * 2. A directory that is an ancestor of a file matching (1).
   *
   * Note that we don't care about navigation files here, since we don't use navigation files for any [AndroidManifestIndex]-based attribute
   * computations.
   *
   * @see [MergedManifestModificationTracker]
   */
  override fun isRelevant(file: VirtualFile, facet: AndroidFacet): Boolean {
    if (file.name == SdkConstants.FN_ANDROID_MANIFEST_XML) return facet.isManifestFile(file)

    if (!file.isDirectory) {
      return false
    }

    return SourceProviderManager.getInstance(facet).sources.manifestFiles.any { VfsUtilCore.isAncestor(file, it, false) }
  }

  override fun contentsChanged(event: VirtualFileEvent) {
    // Content changes are not handled at the VFS level but either in fileWithNoDocumentChanged or documentChanged
  }

  override fun fileWithNoDocumentChanged(file: VirtualFile) = possiblyIrrelevantFileChanged(file)

  override fun documentChanged(event: DocumentEvent) {
    if (project.isDisposed) {
      // note that event may arrive from any project, not only from myProject
      // myProject can be temporarily disposed in light tests
      return
    }

    val document = event.document
    val psiFile = psiDocumentManager.getCachedPsiFile(document)

    if (psiFile == null) {
      fileDocumentManager.getFile(document)?.let { possiblyIrrelevantFileChanged(it) }
    } else {
      psiFile.virtualFile?.let { possiblyIrrelevantFileChanged(it) }
    }
  }

  override fun fileChanged(path: PathString, facet: AndroidFacet) {
    synchronized(pendingFacets) {
      pendingFacets.add(facet)
      // Coalesce rapid manifest updates (e.g. while typing in a manifest or switching Git branches across multiple modules): if a
      // flush is already queued or running, it will pick up this facet, either in its current batch or by rescheduling itself.
      if (!flushScheduled) {
        flushScheduled = true
        trackerUpdaterExecutor.execute { flushCaches() }
      }
    }
  }

  @OptIn(KaPlatformInterface::class)
  private fun flushCaches() {
    try {
      val facets =
        synchronized(pendingFacets) {
          pendingFacets.toSet().also { pendingFacets.clear() }
        }
      if (facets.isEmpty()) return

      // Modules are only disposed in write actions (e.g. when a project sync replaces modules), so the disposal checks and
      // all uses of the modules below must happen under the same read action to avoid a time-of-check to time-of-use race.
      // Note: this read action must not be taken while holding the pendingFacets lock, since fileChanged may acquire that
      // lock from within a write action.
      val affectedModules = runReadAction {
        if (project.isDisposed) return@runReadAction emptyList()
        facets
          .asSequence()
          .filter { !it.isDisposed && !it.module.isDisposed }
          .map { it.module }
          .toList()
          .getTransitiveResourceDependents()
          .filter { !it.isDisposed }
          .toList()
          .onEach { MergedManifestModificationTracker.getInstance(it).manifestChanged() }
      }
      if (affectedModules.isEmpty()) return

      runInEdt {
        runWriteAction {
          if (project.isDisposed) return@runWriteAction
          // The manifest does not belong to any Gradle source set, so we need to manually flush Kotlin analysis caches
          // so that changes to the XML file can cause re-resolution of references to the Manifest class.
          // Modules may have been disposed since the read action above, so re-check disposal under this write action.
          affectedModules
            .filter { !it.isDisposed }
            .flatMap { it.toKaModulesForModificationEvents() }
            .distinct()
            .forEach { it.publishModuleOutOfBlockModificationEvent() }
        }
      }
    } finally {
      // Facets that changed while this flush was running are processed in one more batch.
      synchronized(pendingFacets) {
        if (pendingFacets.isEmpty()) {
          flushScheduled = false
        } else {
          trackerUpdaterExecutor.execute { flushCaches() }
        }
      }
    }
  }

  /**
   * [ProjectComponent] responsible for ensuring that a [Project] has a [MergedManifestModificationListener] subscribed to listen for both
   * VFS and Document changes once the initial project sync has completed.
   */
  private class SubscriptionStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
      project.getService(SubscriptionService::class.java).onProjectOpened()
    }
  }

  @Service(Service.Level.PROJECT)
  private class SubscriptionService(private val project: Project) : Disposable.Default {
    val subscriber =
      object : LazyFileListenerSubscriber<MergedManifestModificationListener>(MergedManifestModificationListener(project), this) {
        override fun subscribe() {
          // To receive all changes happening in the VFS. File modifications may
          // not be picked up immediately if such changes are not saved on the disk yet
          VirtualFileManager.getInstance().addVirtualFileListener(listener, parent)

          // To receive all changes to documents that are open in an editor
          EditorFactory.getInstance().eventMulticaster.addDocumentListener(listener, parent)

          // To receive notifications when any Documents are saved or reloaded from disk
          project.messageBus.connect().subscribe(FileDocumentManagerListener.TOPIC, listener)
        }
      }

    fun onProjectOpened() {
      project.listenUntilNextSync { subscriber.ensureSubscribed() }
    }

    fun ensureSubscribed() = subscriber.ensureSubscribed()
  }

  companion object {
    @JvmStatic fun ensureSubscribed(project: Project) = project.getService(SubscriptionService::class.java).ensureSubscribed()
  }
}

private fun Iterable<Module>.getTransitiveResourceDependents() =
  TreeTraversal.PLAIN_BFS.unique().traversal<Module>(this) { it.getModuleSystem().getDirectResourceModuleDependents() }
