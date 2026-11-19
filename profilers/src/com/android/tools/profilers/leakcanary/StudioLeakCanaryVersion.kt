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
package com.android.tools.profilers.leakcanary

import com.android.ide.common.gradle.Version
import com.android.tools.profilers.ProfilerPreferences
import com.intellij.openapi.diagnostic.Logger

/**
 * The lowest studio-leakcanary version that has everything Android Studio needs. It must always be a stable version. Raise it only after
 * that version is on GMaven.
 */
const val MIN_STUDIO_LEAKCANARY_VERSION = "1.1.0"

/** studio-leakcanary versions before 1.1.0 don't send their version, so an empty version means this one. */
private const val VERSION_WHEN_NOT_SENT = "1.0.0"

/** Stored when nothing was saved for an app process. The library never sends this text. */
private const val NOT_SAVED = "<not saved>"

private val logger: Logger
  get() = Logger.getInstance("StudioLeakCanaryVersion")

/** Saves the studio-leakcanary version that an app process sent with the LeakCanary threshold. */
fun saveStudioLeakCanaryVersion(preferences: ProfilerPreferences, streamId: Long, pid: Int, version: String) {
  preferences.setValue(versionKey(streamId, pid), version)
}

/** Returns the studio-leakcanary version saved for an app process, or null if nothing was saved. */
fun loadStudioLeakCanaryVersion(preferences: ProfilerPreferences, streamId: Long, pid: Int): String? =
  preferences.getValue(versionKey(streamId, pid), NOT_SAVED).takeIf { it != NOT_SAVED }

/**
 * Returns the version to show when the app's studio-leakcanary is older than [MIN_STUDIO_LEAKCANARY_VERSION]. Returns null when it is new
 * enough, or when the version can't be read.
 */
fun findOutdatedStudioLeakCanaryVersion(sentVersion: String): String? {
  val versionText = sentVersion.ifEmpty { VERSION_WHEN_NOT_SENT }
  val version = Version.parse(versionText)
  if (version.major == null) {
    logger.warn("Can't read the studio-leakcanary version \"$versionText\", so it is not checked.")
    return null
  }
  return if (version < Version.parse(MIN_STUDIO_LEAKCANARY_VERSION)) versionText else null
}

private fun versionKey(streamId: Long, pid: Int) = "LEAKCANARY_LIBRARY_VERSION:$streamId:$pid"
