/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.wear.dwf.dom.raw

import com.android.SdkConstants.FN_ANDROID_MANIFEST_XML
import com.android.annotations.concurrency.GuardedBy
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.wear.dwf.analytics.DeclarativeWatchFaceUsageTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.FileContentUtilCore
import com.intellij.xml.XmlSchemaProvider
import com.intellij.xml.util.XmlUtil
import org.jetbrains.android.dom.isDeclarativeWatchFaceFile
import org.jetbrains.annotations.NonNls

/**
 * Provides XSD Schemas based on the current WFF version defined in the merged manifest.
 *
 * If the WFF version in the merged manifest is not valid, a fallback version is used instead.
 *
 * Because the schema depends on the merged manifest, and that the merged manifest can be updated asynchronously, we rely on
 * [RawWatchFaceXmlSchemaUpdater] to reparse declarative watch face files to ensure they're using the WFF version defined in the merged
 * manifest.
 *
 * @see CurrentWFFVersionService
 * @see RawWatchFaceXmlSchemaUpdater
 */
class RawWatchfaceXmlSchemaProvider() : XmlSchemaProvider() {

  override fun getSchema(url: @NonNls String, module: Module?, baseFile: PsiFile): XmlFile? {
    if (module == null) return null
    (baseFile.virtualFile ?: baseFile.originalFile.virtualFile)?.let {
      RawWatchFaceXmlSchemaUpdater.getInstance(module.project).registerFileInUse(it)
    }
    val (schemaVersion, isFallback) =
      runBlockingMaybeCancellable { CurrentWFFVersionService.getInstance().getCurrentWFFVersion(module) } ?: return null

    DeclarativeWatchFaceUsageTracker.getInstance().trackXmlSchemaUsed(schemaVersion, isFallback)

    return XmlUtil.findXmlFile(
      baseFile,
      VfsUtilCore.urlToPath(VfsUtilCore.toIdeaUrl(FileUtil.unquote(schemaVersion.schemaUrl.toExternalForm()), false)),
    )
  }

  override fun isAvailable(file: XmlFile) =
    StudioFlags.WEAR_DECLARATIVE_WATCH_FACE_XML_EDITOR_SUPPORT.get() && isDeclarativeWatchFaceFile(file)
}

/**
 * This class attempts to fix a caching issue regarding the use of XSD files.
 *
 * The problem is that the schema returned by [RawWatchfaceXmlSchemaProvider] is cached and the cache dependencies do not include
 * [com.intellij.psi.util.PsiModificationTracker]. Any changes to the WFF version in the manifest will not invalid the cache. The XSD schema
 * depends on a property set in the manifest. If that property is modified, the schema used will become obsolete.
 *
 * This class fixes the problem by forcing the declarative watch face files to be reparsed whenever we detect that a change has occurred in
 * an AndroidManifest.xml file, which is where the WFF version property is defined.
 */
@Service(Service.Level.PROJECT)
private class RawWatchFaceXmlSchemaUpdater private constructor(val project: Project) : BulkFileListener, Disposable {

  /** Guards [filesInUse]. */
  private val lock = Any()

  @GuardedBy("lock") private val filesInUse = mutableSetOf<VirtualFile>()

  init {
    val connection = ApplicationManager.getApplication().messageBus.connect()
    Disposer.register(this, connection)
    connection.subscribe(VirtualFileManager.VFS_CHANGES, this)
  }

  /**
   * Registers a Declarative Watch Face file as in use (i.e. opened and has had a schema loaded).
   *
   * Only registered files will be reparsed when changes to [FN_ANDROID_MANIFEST_XML] are detected, ensuring that their cached schemas are
   * updated with the latest WFF version without reparsing unopened watch face files.
   */
  fun registerFileInUse(file: VirtualFile) {
    synchronized(lock) {
      filesInUse.add(file)
    }
  }

  override fun after(events: List<VFileEvent>) {
    val isManifestChange = events.any { it.file?.name == FN_ANDROID_MANIFEST_XML }
    if (!isManifestChange) return

    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      val filesToReparse =
        synchronized(lock) {
          val files = filesInUse.filter { it.isValid }
          filesInUse.clear()
          files
        }
      if (filesToReparse.isEmpty()) return@invokeLater
      // reparse the files for the caches to be dropped and the schemas recomputed with
      // the latest merged manifest
      FileContentUtilCore.reparseFiles(filesToReparse)
    }
  }

  override fun dispose() {
    synchronized(lock) {
      filesInUse.clear()
    }
  }

  companion object {
    fun getInstance(project: Project) = project.service<RawWatchFaceXmlSchemaUpdater>()
  }
}
