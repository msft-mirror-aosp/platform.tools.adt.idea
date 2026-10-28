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

import com.android.tools.idea.streaming.StreamingBundle.message
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline location resolver and direct route calculator that operates without any external Maps API. Supports decimal and DMS coordinates,
 * saved points lookup, and a bundled catalog of major world cities.
 */
internal object OfflineLocationResolver {
  private val DMS_COORDINATE_REGEX =
    Regex(
      """^\s*([+-]?\d+(?:\.\d+)?)\s*°?\s*(?:(\d+(?:\.\d+)?)\s*['′]\s*)?(?:(\d+(?:\.\d+)?)\s*["″]\s*)?([NnSs])?\s*[,;\s]\s*([+-]?\d+(?:\.\d+)?)\s*°?\s*(?:(\d+(?:\.\d+)?)\s*['′]\s*)?(?:(\d+(?:\.\d+)?)\s*["″]\s*)?([EeWw])?\s*$"""
    )

  /**
   * Resolves [query] offline by checking:
   * 1. Decimal or DMS geographic coordinates (e.g., `"37.4220, -122.0840"` or `"37°25'19\"N 122°05'02\"W"`),
   * 2. User's [savedPoints] (matching name or description),
   * 3. Bundled major world cities in [OfflineWorldMap.loadedMapData].
   */
  fun resolveLocation(query: String, savedPoints: List<SavedPointItem> = emptyList()): ResolvedLocation? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null

    parseCoordinatesQuery(trimmed)?.let { point ->
      return ResolvedLocation(point, point.formatCoordinates())
    }

    // Match against saved points (exact name match first, then substring match on name or description).
    val exactSaved =
      savedPoints.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        ?: savedPoints.firstOrNull {
          it.name.contains(trimmed, ignoreCase = true) || it.description.contains(trimmed, ignoreCase = true)
        }
    if (exactSaved != null) {
      val pt = GeoPoint(exactSaved.latitude, exactSaved.longitude, exactSaved.elevation)
      return ResolvedLocation(pt, exactSaved.name, matchedSavedPoint = exactSaved)
    }

    // Match against bundled major world cities.
    val city =
      OfflineWorldMap.loadedMapData.cities.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        ?: OfflineWorldMap.loadedMapData.cities.firstOrNull {
          "${it.name}, ${it.region}".equals(trimmed, ignoreCase = true)
        }
        ?: OfflineWorldMap.loadedMapData.cities.firstOrNull {
          it.name.contains(trimmed, ignoreCase = true) ||
            it.region.contains(trimmed, ignoreCase = true) ||
            "${it.name}, ${it.region}".contains(trimmed, ignoreCase = true)
        }
    if (city != null) {
      val pt = GeoPoint(city.latitude, city.longitude)
      return ResolvedLocation(pt, "${city.name}, ${city.region}")
    }

    return null
  }

  /** Parses decimal `"lat, lon[, ele]"` or DMS coordinate strings into a [GeoPoint]. */
  private fun parseCoordinatesQuery(query: String): GeoPoint? {
    val trimmed = query.trim()
    val commaParts = trimmed.split(',').map { it.trim() }
    if (commaParts.size in 2..3) {
      val lat = parseDegreeToken(commaParts[0], isLatitude = true)
      val lon = parseDegreeToken(commaParts[1], isLatitude = false)
      val ele = if (commaParts.size == 3) commaParts[2].toDoubleOrNull() else 0.0
      if (lat != null && lon != null && ele != null) {
        return GeoPoint(lat, lon, ele).takeIf { it.isValid }
      }
    }

    val dmsMatch = DMS_COORDINATE_REGEX.matchEntire(trimmed)
    if (dmsMatch != null) {
      val latDeg = dmsMatch.groupValues[1].toDoubleOrNull() ?: return null
      val latMin = dmsMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
      val latSec = dmsMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
      val latHemi = dmsMatch.groupValues[4].uppercase(Locale.ROOT)

      val lonDeg = dmsMatch.groupValues[5].toDoubleOrNull() ?: return null
      val lonMin = dmsMatch.groupValues[6].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
      val lonSec = dmsMatch.groupValues[7].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
      val lonHemi = dmsMatch.groupValues[8].uppercase(Locale.ROOT)

      if (latMin !in 0.0..<60.0 || latSec !in 0.0..<60.0 || lonMin !in 0.0..<60.0 || lonSec !in 0.0..<60.0) {
        return null
      }

      var lat = abs(latDeg) + latMin / 60.0 + latSec / 3600.0
      if (latDeg < 0 || latHemi == "S") lat = -lat

      var lon = abs(lonDeg) + lonMin / 60.0 + lonSec / 3600.0
      if (lonDeg < 0 || lonHemi == "W") lon = -lon

      return GeoPoint(lat, lon).takeIf { it.isValid }
    }

    return null
  }

  private fun parseDegreeToken(token: String, isLatitude: Boolean): Double? {
    val t = token.trim().removeSuffix("°").trim()
    if (t.isEmpty()) return null
    val lastChar = t.last().uppercaseChar()
    val sign =
      when {
        isLatitude && lastChar == 'S' -> -1.0
        isLatitude && lastChar == 'N' -> 1.0
        !isLatitude && lastChar == 'W' -> -1.0
        !isLatitude && lastChar == 'E' -> 1.0
        else -> {
          return t.toDoubleOrNull()
        }
      }
    val numeric = t.dropLast(1).trim().removeSuffix("°").trim().toDoubleOrNull() ?: return null
    return abs(numeric) * sign
  }

  /** Computes a straight-line interpolated route between [origin] and [destination] using [mode]'s speed. */
  fun computeDirectRoute(
    origin: GeoPoint,
    destination: GeoPoint,
    mode: TravelMode,
    originLabel: String = origin.formatCoordinates(),
    destinationLabel: String = destination.formatCoordinates(),
  ): ComputedRoute = computeDirectRoute(listOf(origin, destination), mode, originLabel, destinationLabel)

  /** Computes a multi-waypoint straight-line interpolated route across [waypoints] using [mode]'s speed. */
  private fun computeDirectRoute(
    waypoints: List<GeoPoint>,
    mode: TravelMode,
    originLabel: String = waypoints.firstOrNull()?.formatCoordinates().orEmpty(),
    destinationLabel: String = waypoints.lastOrNull()?.formatCoordinates().orEmpty(),
  ): ComputedRoute {
    require(waypoints.size >= 2) { "At least 2 waypoints are required to build a route" }
    val speed = mode.defaultSpeedMetersPerSecond
    val routePoints = ArrayList<RoutePoint>()
    var totalDuration = 0.0

    val first = waypoints.first()
    routePoints.add(RoutePoint(first.latitude, first.longitude, first.elevation, 0.0))

    for (segIdx in 1 until waypoints.size) {
      val start = waypoints[segIdx - 1]
      val end = waypoints[segIdx]
      val distance = GeoMath.distanceMeters(start, end)
      val segDuration = (distance / speed).coerceAtLeast(1.0)
      totalDuration += segDuration
      val steps = max(2, min(20, (distance / 50.0).roundToInt() + 1))
      val stepDelay = segDuration / (steps - 1)
      for (step in 1 until steps) {
        val alpha = step.toDouble() / (steps - 1)
        val lat = start.latitude + alpha * (end.latitude - start.latitude)
        val lon = GeoMath.interpolateLongitude(start.longitude, end.longitude, alpha)
        val ele = start.elevation + alpha * (end.elevation - start.elevation)
        routePoints.add(RoutePoint(lat, lon, ele, stepDelay))
      }
    }

    return ComputedRoute(
      points = routePoints,
      durationSeconds = totalDuration.roundToInt().coerceAtLeast(1),
      originLabel = originLabel,
      destinationLabel = destinationLabel,
      mode = mode,
    )
  }
}

/** Modes of travel for route speed calculation, matching the emulator's route travel modes. */
internal enum class TravelMode(internal val defaultSpeedMetersPerSecond: Double, private val messageKey: String) {
  DRIVING(13.89, "location.dialog.mode.driving"), // ~50 km/h
  WALKING(1.4, "location.dialog.mode.walking"), // ~5 km/h
  BICYCLING(4.5, "location.dialog.mode.bicycling"), // ~16 km/h
  TRANSIT(10.0, "location.dialog.mode.transit"); // ~36 km/h

  val displayName: String
    get() = message(messageKey)

  override fun toString(): String = displayName
}

/** Result of an offline location lookup (from coordinates, saved points, or bundled cities). */
internal data class ResolvedLocation(
  val point: GeoPoint,
  val label: String,
  val matchedSavedPoint: SavedPointItem? = null,
)

/** Result of a direct point-to-point route calculation. */
internal data class ComputedRoute(
  val points: List<RoutePoint>,
  val durationSeconds: Int,
  val originLabel: String,
  val destinationLabel: String,
  val mode: TravelMode,
)

/** A point along a route with a delay (in seconds at 1X speed) from the previous point. */
internal data class RoutePoint(
  val latitude: Double,
  val longitude: Double,
  val elevation: Double = 0.0,
  val delaySeconds: Double = 0.0,
)
