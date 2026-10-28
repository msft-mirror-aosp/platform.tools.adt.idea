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

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Geographic distance, bearing, and Web Mercator projection utilities matching `location-page.cpp`. */
internal object GeoMath {
  private const val EARTH_RADIUS_NM = 3440.0
  private const val METERS_PER_NM = 1852.0
  private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
  const val MAX_SPEED_KNOTS = 666.0
  const val TILE_SIZE = 256.0
  const val MIN_ZOOM = 1
  const val MAX_ZOOM = 16
  const val DEFAULT_ZOOM = 5
  const val MAX_VISIBLE_DEGREES = 120.0

  /** Returns the full Web Mercator world width/height in pixels at the given [zoom] level. */
  fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

  /** Calculates the great-circle distance between two coordinates in nautical miles using the Haversine formula. */
  fun distanceNm(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Double {
    val startLatRad = startLat * PI / 180.0
    val startLonRad = startLon * PI / 180.0
    val endLatRad = endLat * PI / 180.0
    val endLonRad = endLon * PI / 180.0

    val dLat = endLatRad - startLatRad
    val dLon = endLonRad - startLonRad
    val sinDLat = sin(dLat / 2.0)
    val sinDLon = sin(dLon / 2.0)

    val a = sinDLat * sinDLat + cos(startLatRad) * cos(endLatRad) * sinDLon * sinDLon
    val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    return EARTH_RADIUS_NM * c
  }

  /** Calculates the great-circle distance between two points in meters. */
  fun distanceMeters(start: GeoPoint, end: GeoPoint): Double = distanceMeters(start.latitude, start.longitude, end.latitude, end.longitude)

  /** Calculates the great-circle distance between two coordinates in meters. */
  fun distanceMeters(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Double =
    distanceNm(startLat, startLon, endLat, endLon) * METERS_PER_NM

  /**
   * Calculates the initial bearing (heading) in degrees in `[0.0, 360.0)` from `(startLat, startLon)` to `(endLat, endLon)`, matching
   * `getHeading` in `location-page.cpp` (0 = North, 90 = East, 180 = South, 270 = West).
   */
  fun headingDegrees(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Double {
    val startLatRad = startLat * PI / 180.0
    val startLonRad = startLon * PI / 180.0
    val endLatRad = endLat * PI / 180.0
    val endLonRad = endLon * PI / 180.0

    val dLon = endLonRad - startLonRad
    val aa = cos(endLatRad) * sin(dLon)
    val bb = cos(startLatRad) * sin(endLatRad) - sin(startLatRad) * cos(endLatRad) * cos(dLon)
    return (atan2(aa, bb) * 180.0 / PI + 360.0) % 360.0
  }

  /** Converts latitude to normalized Web Mercator Y in `[0.0, 1.0]`. */
  fun latToNormalizedY(lat: Double): Double {
    val clamped = lat.coerceIn(-GeoPoint.WEB_MERCATOR_MAX_LATITUDE, GeoPoint.WEB_MERCATOR_MAX_LATITUDE)
    val latRad = clamped * PI / 180.0
    return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
  }

  /** Wraps a longitude in degrees to `[-180.0, 180.0]`. */
  fun wrapLongitude(lon: Double): Double {
    val wrapped = ((lon + 180.0).mod(360.0)) - 180.0
    return if (wrapped == -180.0 && lon > 0.0) 180.0 else wrapped
  }

  /** Returns the signed shortest-arc longitude difference in `[-180.0, 180.0]` from [startLon] to [endLon]. */
  fun shortestDeltaLongitude(startLon: Double, endLon: Double): Double {
    var delta = (endLon - startLon).mod(360.0)
    if (delta > 180.0) delta -= 360.0
    return delta
  }

  /** Linearly interpolates longitude along the shortest arc from [startLon] to [endLon] at fraction [alpha] in `[0.0, 1.0]`. */
  fun interpolateLongitude(startLon: Double, endLon: Double, alpha: Double): Double =
    wrapLongitude(startLon + alpha * shortestDeltaLongitude(startLon, endLon))

  /** Converts longitude to normalized Web Mercator X in `[0.0, 1.0]`. */
  fun lonToNormalizedX(lon: Double): Double = (wrapLongitude(lon) + 180.0) / 360.0

  /** Converts normalized Web Mercator Y in `[0.0, 1.0]` back to latitude in degrees. */
  fun normalizedYToLat(y: Double): Double {
    val clamped = y.coerceIn(0.0, 1.0)
    val n = PI - 2.0 * PI * clamped
    return 180.0 / PI * atan2(0.5 * (exp(n) - exp(-n)), 1.0)
  }

  /** Converts normalized Web Mercator X in `[0.0, 1.0]` back to longitude in degrees. */
  fun normalizedXToLon(x: Double): Double = wrapLongitude(x * 360.0 - 180.0)

  /** Returns the ground resolution in meters per pixel at the given [latitude] and [zoom]. */
  fun metersPerPixel(latitude: Double, zoom: Double): Double {
    val clampedLat = latitude.coerceIn(-GeoPoint.WEB_MERCATOR_MAX_LATITUDE, GeoPoint.WEB_MERCATOR_MAX_LATITUDE)
    val worldPixels = worldSize(zoom)
    return EARTH_CIRCUMFERENCE_METERS * cos(clampedLat * PI / 180.0) / worldPixels
  }

  /** Formats a duration in seconds as `Xh Ym Zs`, `Ym Zs`, or `Zs` matching `location-page-route.cpp`. */
  fun formatDuration(durationSeconds: Int): String {
    val total = durationSeconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
      hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
      minutes > 0 -> "${minutes}m ${seconds}s"
      else -> "${seconds}s"
    }
  }
}
