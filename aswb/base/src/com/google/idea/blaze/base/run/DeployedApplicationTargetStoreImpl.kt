/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.run

import com.google.idea.blaze.base.qsync.QuerySyncManager
import com.google.idea.blaze.common.AtomicFileWriter
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.qsync.deps.ArtifactDirectories
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import org.jetbrains.annotations.TestOnly

class DeployedApplicationTargetStoreImpl(
  private val applicationIdsDirectory: Path,
  private val knownTargetsSupplier: () -> Set<Label>?,
  private val modificationTracker: ModificationTracker,
) : DeployedApplicationTargetStore {

  @Suppress("unused")
  constructor(
    project: Project
  ) : this(
    Path.of(requireNotNull(project.basePath)).resolve(ArtifactDirectories.APPLICATION_IDS.relativePath),
    knownTargetsSupplier = { getKnownTargets(project) },
    modificationTracker = QuerySyncManager.getInstance(project).projectModificationTracker,
  )

  private data class TargetEntry(
    val applicationId: String,
    val deployedWith: MutableSet<Label>,
  )

  private val mappingsFile: Path
    get() = applicationIdsDirectory.resolve(MAPPINGS_FILE_NAME)

  private val lock = Any()
  private val targets: MutableMap<Label, TargetEntry> by lazy {
    synchronized(lock) {
      loadTargets()
    }
  }

  /**
   * Loads persisted target mappings from `application_ids.txt`.
   *
   * Blank lines and `#` comments are ignored. Each non-empty line is whitespace-delimited: `<target-label> <application-id>
   * [<co-deployed-target-label> ...]`.
   *
   * Example:
   * ```
   * //java/com/example:app com.example.app //javatests/com/example:app_test
   * //java/com/example:standalone com.example.standalone
   * //javatests/com/example:app_test com.example.app.test //java/com/example:app
   * ```
   */
  private fun loadTargets(): MutableMap<Label, TargetEntry> {
    val loadedTargets = mutableMapOf<Label, TargetEntry>()
    if (!mappingsFile.isRegularFile()) {
      return loadedTargets
    }
    return try {
      for (line in mappingsFile.readLines(StandardCharsets.UTF_8)) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue
        }
        val parts = trimmed.split(WHITESPACE_REGEX)
        if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
          val target = Label.parseLabelIfValid(parts[0]) ?: continue
          val appId = parts[1]
          val companions = parts.drop(2).mapNotNullTo(linkedSetOf()) { Label.parseLabelIfValid(it) }
          recordMapping(loadedTargets, target, appId, companions)
        }
      }
      loadedTargets
    } catch (e: IOException) {
      logger.error("Failed to read application ID mappings from $mappingsFile", e)
      loadedTargets
    }
  }

  private var lastModificationCount: Long = -1L

  private fun cleanUpStaleTargetsIfNeeded() {
    val currentModCount = modificationTracker.modificationCount
    if (currentModCount != lastModificationCount) {
      val knownTargets = knownTargetsSupplier()
      if (knownTargets != null) {
        var changed = targets.keys.retainAll(knownTargets)
        for (entry in targets.values) {
          if (entry.deployedWith.retainAll(knownTargets)) {
            changed = true
          }
        }
        if (changed) {
          saveMappings()
        }
        lastModificationCount = currentModCount
      }
    }
  }

  override fun trackTargetForApplication(applicationId: String, target: Label, deployedWith: Set<Label>) {
    synchronized(lock) {
      cleanUpStaleTargetsIfNeeded()
      recordMapping(targets, target, applicationId, deployedWith)
      saveMappings()
    }
  }

  private fun recordMapping(
    targetMap: MutableMap<Label, TargetEntry>,
    target: Label,
    applicationId: String,
    deployedWith: Set<Label>,
  ) {
    val evictedTarget = targetMap.entries.firstOrNull { it.key != target && it.value.applicationId == applicationId }?.key
    if (evictedTarget != null) {
      targetMap.remove(evictedTarget)
      for (entry in targetMap.values) {
        entry.deployedWith.remove(evictedTarget)
      }
    }

    val companions = deployedWith.filterTo(linkedSetOf()) { it != target }
    targetMap[target] = TargetEntry(applicationId, companions)
    for ((otherTarget, otherEntry) in targetMap) {
      if (otherTarget == target) continue
      if (otherTarget in companions) {
        otherEntry.deployedWith.add(target)
      } else {
        otherEntry.deployedWith.remove(target)
      }
    }
  }

  private fun saveMappings() {
    try {
      if (!applicationIdsDirectory.exists()) {
        Files.createDirectories(applicationIdsDirectory)
      }
      AtomicFileWriter.create(mappingsFile).use { writer ->
        val sortedEntries = targets.entries.sortedBy { it.key.toString() }
        val output = buildString {
          for ((target, entry) in sortedEntries) {
            append(target.toString())
            append(' ')
            append(entry.applicationId)
            for (companion in entry.deployedWith.sortedBy { it.toString() }) {
              append(' ')
              append(companion.toString())
            }
            append('\n')
          }
        }
        writer.outputStream.write(output.toByteArray(StandardCharsets.UTF_8))
        writer.onWriteComplete()
      }
    } catch (e: IOException) {
      logger.error("Failed to write application ID mappings to $mappingsFile", e)
    }
  }

  override fun getTargetForApplication(applicationId: String): Label? {
    synchronized(lock) {
      cleanUpStaleTargetsIfNeeded()
      return targets.entries.firstOrNull { it.value.applicationId == applicationId }?.key
    }
  }

  override fun getTargetsForApplication(applicationId: String): DeployedApplicationTargets? {
    synchronized(lock) {
      cleanUpStaleTargetsIfNeeded()
      val (primaryTarget, entry) = targets.entries.firstOrNull { it.value.applicationId == applicationId } ?: return null
      return DeployedApplicationTargets(primaryTarget, entry.deployedWith.toSet())
    }
  }

  override fun getAllApplicationIds(): Set<String> {
    synchronized(lock) {
      cleanUpStaleTargetsIfNeeded()
      return targets.values.mapTo(linkedSetOf()) { it.applicationId }
    }
  }

  companion object {
    private const val MAPPINGS_FILE_NAME = "application_ids.txt"
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val logger = Logger.getInstance(DeployedApplicationTargetStoreImpl::class.java)

    private fun getKnownTargets(project: Project): Set<Label>? {
      val graph = QuerySyncManager.getInstance(project).currentBuildGraphdata ?: return null
      return graph.allLoadedTargets().map { it.label() }.toSet()
    }

    @TestOnly
    fun createForTest(
      applicationIdsDirectory: Path,
      knownTargetsSupplier: () -> Set<Label>? = { null },
      modificationTracker: ModificationTracker = SimpleModificationTracker(),
    ): DeployedApplicationTargetStoreImpl =
      DeployedApplicationTargetStoreImpl(applicationIdsDirectory, knownTargetsSupplier, modificationTracker)
  }
}
