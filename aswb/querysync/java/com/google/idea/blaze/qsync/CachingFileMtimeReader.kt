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
package com.google.idea.blaze.qsync

import com.google.common.annotations.VisibleForTesting
import com.google.common.hash.Hashing
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.qsync.query.QuerySummary
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** The value hashed for a subinclude that doesn't exist. */
@VisibleForTesting const val MISSING_MTIME = -1L

/**
 * How long before the start of a sync an mtime has to be for it to be hashed as it is. Some file systems, such as CitC, store mtimes in
 * whole seconds.
 */
@VisibleForTesting const val MTIME_MARGIN_MS = 2_000L

/**
 * Computes subinclude stamps for a single sync, from the mtimes of the files that packages load. It reads and hashes each file at most
 * once, so a package's stamp is the same each time it's computed in the sync. Create a new one at the start of each sync.
 *
 * Not thread-safe.
 *
 * @param subincludeFileResolver finds the file of each subinclude, and decides which files are tracked: [BlazeSubincludeFileResolver] or
 *   [BazelSubincludeFileResolver].
 * @param syncStartMs when the sync started. The public constructor uses the time at which the reader is created.
 */
class CachingFileMtimeReader
@VisibleForTesting
constructor(private val subincludeFileResolver: SubincludeFileResolver, private val syncStartMs: Long) {
  constructor(subincludeFileResolver: SubincludeFileResolver) : this(subincludeFileResolver, System.currentTimeMillis())

  /**
   * What a tracked file adds to a subinclude stamp.
   *
   * @property mtime the value that [getMtimeOrSyncStartTime] returns for the file.
   * @property hash the hash of the file's label and [mtime].
   */
  private class LoadedFileStamp(val mtime: Long, val hash: Long)

  /** The stamp of each file looked up so far in this sync, or null for a file that isn't tracked. */
  private val stampByLoadedFile = HashMap<Label, LoadedFileStamp?>()

  /**
   * Returns the mtime value hashed for [file], or null if it isn't tracked.
   *
   * The value is [MISSING_MTIME] if the file doesn't exist, the start time of the sync if its mtime is less than [MTIME_MARGIN_MS] before
   * it, or later, and otherwise its mtime.
   *
   * A recent mtime is replaced because the file may change after the query reads it. If its new mtime were hashed, a later check could
   * match it: when the change keeps the same mtime tick, or when this is the first read of the file, after the change. The start time of
   * the sync differs from the file's mtime at a later check, unless the two are exactly equal, so the change is caught then.
   */
  fun getMtimeOrSyncStartTime(file: Label): Long? = stampOf(file)?.mtime

  /** Returns the subinclude stamp of [pkg]: the sum of the hashes of the tracked files that it loads, or 0 if it loads none. */
  fun subincludeStampOf(pkg: QuerySummary.BuildPackage): Long = pkg.subincludes.sumOf { stampOf(it)?.hash ?: 0L }

  private fun stampOf(file: Label): LoadedFileStamp? {
    if (stampByLoadedFile.containsKey(file)) return stampByLoadedFile[file]
    val stamp =
      subincludeFileResolver.resolve(file)?.let { path ->
        val mtime = readMtime(path)
        val hashedMtime = if (mtime != MISSING_MTIME && mtime >= syncStartMs - MTIME_MARGIN_MS) syncStartMs else mtime
        LoadedFileStamp(hashedMtime, hashMtime(file, hashedMtime))
      }
    stampByLoadedFile[file] = stamp
    return stamp
  }

  private fun readMtime(path: Path): Long {
    return try {
      Files.getLastModifiedTime(path).toMillis()
    } catch (e: NoSuchFileException) {
      MISSING_MTIME
    } catch (e: IOException) {
      logger.log(Level.WARNING, "Could not read the mtime of $path", e)
      MISSING_MTIME
    }
  }

  private companion object {
    val logger: Logger = Logger.getLogger(CachingFileMtimeReader::class.java.name)
  }
}

/**
 * Returns the subinclude stamp of this package for the mtime values that [mtimeOf] returns: the sum of a hash of each subinclude's label
 * and mtime value, over the subincludes for which [mtimeOf] returns a value, or 0 if there are none.
 *
 * The sum doesn't depend on the order of the subincludes. Each mtime is hashed with its label, so that two files swapping mtimes changes
 * the stamp.
 */
@VisibleForTesting
fun QuerySummary.BuildPackage.calculateSubincludeStamp(mtimeOf: (Label) -> Long?): Long = subincludes.sumOf { file ->
  mtimeOf(file)?.let { hashMtime(file, it) } ?: 0L
}

/** Returns a farmhash64 of [file]'s label and [mtime]. */
private fun hashMtime(file: Label, mtime: Long): Long =
  Hashing.farmHashFingerprint64().newHasher().putString(file.toString(), UTF_8).putLong(mtime).hash().asLong()
