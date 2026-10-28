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
package com.android.tools.idea.streaming.emulator.dialogs

import java.util.Locale

/** Geographic coordinates in degrees, with optional elevation in meters. */
internal data class GeoPoint(
  val latitude: Double,
  val longitude: Double,
  val elevation: Double = 0.0,
) {
  val isValid: Boolean
    get() =
      latitude.isFinite() &&
        longitude.isFinite() &&
        elevation.isFinite() &&
        latitude in MIN_LATITUDE..MAX_LATITUDE &&
        longitude in MIN_LONGITUDE..MAX_LONGITUDE

  fun formatCoordinates(): String = String.format(Locale.US, "%.4f, %.4f", latitude, longitude)

  companion object {
    const val MIN_LATITUDE = -90.0
    const val MAX_LATITUDE = 90.0
    const val MIN_LONGITUDE = -180.0
    const val MAX_LONGITUDE = 180.0
    const val WEB_MERCATOR_MAX_LATITUDE = 85.05112878

    /** Default location (Googleplex, Mountain View, CA). */
    val DEFAULT = GeoPoint(37.4220, -122.0840, 5.0)
  }
}
