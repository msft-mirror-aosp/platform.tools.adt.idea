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
package com.android.tools.idea.avd

import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdManager.Companion.ENVIRONMENTS_DIR
import com.android.sdklib.internal.avd.AvdManager.Companion.ENVIRONMENT_INI
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.IdeAvdManagers
import com.android.tools.idea.util.StudioPathManager.isRunningFromSources
import com.android.tools.idea.util.StudioPathManager.resolvePathFromSourcesRoot
import com.android.utils.FileUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.streams.asSequence
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.jetbrains.annotations.TestOnly

private val logger = logger<EnvironmentsUpdater>()

@Suppress("LightServiceMigrationCode")
class EnvironmentsUpdater : Disposable {

  private val environments: Deferred<List<EnvironmentImage>>
  private val sourceDir: Path
  private val destinationDir: Path
  private val cleanupJob: Deferred<Unit>

  @Volatile private var environmentsList: List<EnvironmentImage>? = null

  init {
    sourceDir =
      when {
        isRunningFromSources() -> resolvePathFromSourcesRoot("tools/adt/idea/artwork/resources/device-art-resources/ai_glasses_device")
        else -> Path.of(PathManager.getHomePath()).resolve("plugins/android/resources/device-art-resources/ai_glasses_device")
      }

    val sdkHandler = AndroidSdks.getInstance().tryToChooseSdkHandler()
    destinationDir = sdkHandler.location?.resolve(ENVIRONMENTS_DIR) ?: throw RuntimeException("Unable to get SDK location")

    environments =
      createCoroutineScope().async(Dispatchers.IO) {
        val updatedFiles =
          try {
            updateDirectory(sourceDir, destinationDir)
          } catch (e: Exception) {
            logger.warn("Error updating environment images in $destinationDir", e)
            emptySet()
          }
        try {
          // Only include environments that are present in the source directory.
          val list = EnvironmentFileAnalyzer.scanEnvironments(destinationDir) { it in updatedFiles }
          environmentsList = list
          list
        } catch (e: Exception) {
          logger.warn("Error loading environment images from $destinationDir", e)
          emptyList()
        }
      }

    cleanupJob =
      createCoroutineScope().async(Dispatchers.IO) {
        delay(1.minutes)
        try {
          deleteUnreferencedObsoleteFiles(sdkHandler)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.warn("Unable to remove obsolete files from $destinationDir", e)
        }
      }
  }

  suspend fun getEnvironments(): List<EnvironmentImage> {
    return environments.await()
  }

  override fun dispose() {}

  @TestOnly
  suspend fun awaitCleanup() {
    cleanupJob.await()
  }

  private fun deleteUnreferencedObsoleteFiles(sdkHandler: AndroidSdkHandler) {
    val candidateFiles = OBSOLETE_ENVIRONMENT_FILES.map { destinationDir.resolve(it) }.filter { Files.exists(it) }
    if (candidateFiles.isNotEmpty()) {
      val avdManager = IdeAvdManagers.getAvdManager(sdkHandler)
      deleteUnreferencedObsoleteFiles(destinationDir, candidateFiles, avdManager.allAvds)
    }
  }

  companion object {
    fun getInstance(): EnvironmentsUpdater = service<EnvironmentsUpdater>()
  }
}

/**
 * Copies files from [sourceDir] and its subdirectories to [destinationDir]. Only files that don't exist in [destinationDir] or are older
 * than the corresponding files in [sourceDir] are copied. Returns the set of destination files corresponding to the files in [sourceDir]
 * that were either copied or already up to date.
 *
 * @throws NoSuchFileException if [sourceDir] does not exist.
 */
fun updateDirectory(sourceDir: Path, destinationDir: Path): Set<Path> {
  require(!destinationDir.startsWith(sourceDir)) { "Copying into itself is not allowed" }

  return Files.walk(sourceDir).use { stream ->
    stream
      .filter { Files.isRegularFile(it) }
      .map { source -> updateFile(sourceDir, destinationDir, sourceDir.relativize(source)) }
      .asSequence()
      .toSet()
  }
}

/**
 * Copies a file from [sourceDir] to [destinationDir] if the destination doesn't exist or is older than the source. The file is defined by
 * its path relative to [sourceDir]. Returns absolute path of the destination file.
 */
private fun updateFile(sourceDir: Path, destinationDir: Path, relativePath: Path): Path {
  val source = sourceDir.resolve(relativePath)
  val destination = destinationDir.resolve(relativePath.toString())
  val sourceTimestamp = Files.getLastModifiedTime(source)
  if (destination.needsUpdate(source, sourceTimestamp)) {
    Files.createDirectories(destination.parent!!)
    FileUtils.copyFile(source, destination)
    if (System.getProperty("os.name").lowercase().contains("windows")) { // FileUtils.copyFile doesn't preserve timestamp on Windows.
      Files.setLastModifiedTime(destination, sourceTimestamp)
    }
  }
  return destination
}

private fun Path.needsUpdate(source: Path, sourceTimestamp: FileTime): Boolean =
  !Files.exists(this) || Files.getLastModifiedTime(this) < sourceTimestamp || Files.size(this) != Files.size(source)

/** Returns the set of existing files in [destinationDir] that are referenced by any of the [avds]. */
private fun getReferencedEnvironmentFiles(avds: List<AvdInfo>, destinationDir: Path): Set<Path> {
  val referencedFiles = mutableSetOf<Path>()
  val fs = destinationDir.fileSystem
  for (avd in avds) {
    val candidateValues = mutableListOf<String>()
    val environmentIni = avd.dataFolderPath.resolve(ENVIRONMENT_INI)
    candidateValues.addAll(readEnvironmentIniValues(environmentIni))
    candidateValues.addAll(avd.properties.values)
    candidateValues.addAll(avd.userSettings.values)

    for (value in candidateValues) {
      val pathStr = extractPathString(value).trim()
      if (pathStr.isEmpty()) continue
      val path =
        try {
          fs.getPath(pathStr)
        } catch (_: InvalidPathException) {
          null
        } ?: continue

      if (path.isAbsolute) {
        checkAndAddReferencedFile(path, destinationDir, referencedFiles)
        if (path.parent?.fileName?.toString() == destinationDir.fileName.toString()) {
          checkAndAddReferencedFile(destinationDir.resolve(path.fileName.toString()), destinationDir, referencedFiles)
        }
      } else {
        checkAndAddReferencedFile(destinationDir.resolve(path), destinationDir, referencedFiles)
        if (avd.dataFolderPath.fileSystem == destinationDir.fileSystem) {
          checkAndAddReferencedFile(avd.dataFolderPath.resolve(path), destinationDir, referencedFiles)
        }
        destinationDir.parent?.let { checkAndAddReferencedFile(it.resolve(path), destinationDir, referencedFiles) }
      }
    }
  }
  return referencedFiles
}

private fun readEnvironmentIniValues(environmentIni: Path): List<String> {
  if (!Files.exists(environmentIni)) return emptyList()
  return try {
    Files.readAllLines(environmentIni).mapNotNull { line ->
      val trimmed = line.trim()
      if (trimmed.isNotEmpty() && !trimmed.startsWith('#') && !trimmed.startsWith(';') && trimmed.contains('=')) {
        trimmed.substringAfter('=').trim()
      } else {
        null
      }
    }
  } catch (_: Exception) {
    emptyList()
  }
}

private fun extractPathString(value: String): String {
  val prefixes = listOf("imagefile:", "image360:", "mesh3d:", "videofile:")
  for (prefix in prefixes) {
    if (value.startsWith(prefix, ignoreCase = true)) {
      return value.substring(prefix.length)
    }
  }
  return value
}

private fun checkAndAddReferencedFile(candidate: Path, destinationDir: Path, referencedFiles: MutableSet<Path>) {
  try {
    if (candidate.fileSystem != destinationDir.fileSystem) return
    val normalized = candidate.toAbsolutePath().normalize()
    val normalizedDest = destinationDir.toAbsolutePath().normalize()
    if (normalized.startsWith(normalizedDest) && Files.isRegularFile(normalized)) {
      referencedFiles.add(normalized)
    }
  } catch (_: Exception) {}
}

/** Deletes files in [candidateFiles] from [destinationDir] that are not referenced by any AVD in [avds]. */
private fun deleteUnreferencedObsoleteFiles(destinationDir: Path, candidateFiles: Collection<Path>, avds: List<AvdInfo>) {
  if (!Files.exists(destinationDir)) {
    return
  }

  val referencedFiles = getReferencedEnvironmentFiles(avds, destinationDir)

  for (file in candidateFiles) {
    val normalized = file.toAbsolutePath().normalize()
    val isReferenced =
      normalized in referencedFiles ||
        referencedFiles.any { ref ->
          try {
            Files.isSameFile(ref, normalized)
          } catch (_: Exception) {
            false
          }
        }
    if (!isReferenced) {
      try {
        Files.deleteIfExists(file)
      } catch (e: Exception) {
        logger.warn("Failed to delete obsolete environment file $file", e)
      }
    }
  }
}

private val OBSOLETE_ENVIRONMENT_FILES =
  listOf(
    "default-background-1.png",
    "indoor-study-dark.jpg",
    "outdoor-city-bright.jpg",
    "outdoor-nature-bright.jpg",
  )
