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

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parser for GPX and KML files, supporting the same elements and timestamp/delay rules as `GpxParser.cpp`, `KmlParser.cpp`, and
 * `LocationPage::toJsonString` in `location-page-route.cpp`.
 */
internal object GpxKmlParser {
  private const val DEFAULT_SPEED_MPS = 10.0 // 36 km/h
  private const val MIN_CALCULATED_DELAY_SEC = 2.0

  fun parseFile(file: Path): List<GpsFix> {
    val fileName = file.fileName.toString().lowercase(Locale.ROOT)
    return when {
      fileName.endsWith(".gpx") -> parseGpx(file)
      fileName.endsWith(".kml") -> parseKml(file)
      else -> throw IllegalArgumentException("Unsupported file type: ${file.fileName}")
    }
  }

  private fun parseGpx(file: Path): List<GpsFix> {
    val doc = parseXmlDocument(file)
    val root = doc.documentElement ?: throw IllegalArgumentException("Empty GPX document")
    val fixes = mutableListOf<GpsFix>()

    var child: Node? = root.firstChild
    while (child != null) {
      if (child is Element) {
        when (child.localOrTagName) {
          "wpt" -> fixes.add(parseGpxPoint(child))
          "rte" -> {
            var rteName = ""
            var rteDesc = ""
            val startIndex = fixes.size
            var rteChild: Node? = child.firstChild
            while (rteChild != null) {
              if (rteChild is Element) {
                when (rteChild.localOrTagName) {
                  "name" -> rteName = rteChild.textContent?.trim().orEmpty()
                  "desc" -> rteDesc = rteChild.textContent?.trim().orEmpty()
                  "rtept" -> fixes.add(parseGpxPoint(rteChild))
                }
              }
              rteChild = rteChild.nextSibling
            }
            if (fixes.size > startIndex) {
              val first = fixes[startIndex]
              fixes[startIndex] =
                first.copy(
                  name = first.name.ifBlank { rteName },
                  description = first.description.ifBlank { rteDesc },
                )
            }
          }
          "trk" -> {
            var trkName = ""
            var trkDesc = ""
            val startIndex = fixes.size
            var trkChild: Node? = child.firstChild
            while (trkChild != null) {
              if (trkChild is Element) {
                when (trkChild.localOrTagName) {
                  "name" -> trkName = trkChild.textContent?.trim().orEmpty()
                  "desc" -> trkDesc = trkChild.textContent?.trim().orEmpty()
                  "trkseg" -> {
                    var trkPt: Node? = trkChild.firstChild
                    while (trkPt != null) {
                      if (trkPt is Element && trkPt.localOrTagName == "trkpt") {
                        fixes.add(parseGpxPoint(trkPt))
                      }
                      trkPt = trkPt.nextSibling
                    }
                  }
                }
              }
              trkChild = trkChild.nextSibling
            }
            if (fixes.size > startIndex) {
              val first = fixes[startIndex]
              fixes[startIndex] =
                first.copy(
                  name = first.name.ifBlank { trkName },
                  description = first.description.ifBlank { trkDesc },
                )
            }
          }
        }
      }
      child = child.nextSibling
    }

    if (fixes.size > 1 && fixes.all { it.timeEpochSeconds > 0L }) {
      val firstName = fixes.first().name
      val firstDesc = fixes.first().description
      fixes.sort()
      if (fixes[0].name.isBlank() && firstName.isNotBlank()) {
        fixes[0] = fixes[0].copy(name = firstName)
      }
      if (fixes[0].description.isBlank() && firstDesc.isNotBlank()) {
        fixes[0] = fixes[0].copy(description = firstDesc)
      }
    }
    return fixes
  }

  private fun parseGpxPoint(element: Element): GpsFix {
    val latStr =
      element.getAttribute("lat").takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Point missing latitude attribute")
    val lonStr =
      element.getAttribute("lon").takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Point missing longitude attribute")
    val latitude = parseLatitude(latStr)
    val longitude = parseLongitude(lonStr)

    var elevation = 0.0
    var timeEpochSeconds = 0L
    var name = ""
    var description = ""

    var field: Node? = element.firstChild
    while (field != null) {
      if (field is Element) {
        val text = field.textContent?.trim().orEmpty()
        when (field.localOrTagName) {
          "ele" -> {
            if (text.isNotEmpty()) {
              elevation = parseElevation(text)
            }
          }
          "time" -> {
            if (text.isNotEmpty()) {
              timeEpochSeconds = parseIsoTime(text)
            }
          }
          "name" -> name = text
          "desc" -> description = text
        }
      }
      field = field.nextSibling
    }

    return GpsFix(
      latitude = latitude,
      longitude = longitude,
      elevation = elevation,
      timeEpochSeconds = timeEpochSeconds,
      name = name,
      description = description,
    )
  }

  private fun parseIsoTime(timeStr: String): Long {
    try {
      return Instant.parse(timeStr).epochSecond
    } catch (_: Exception) {
      // Match GpxParser.cpp sscanf("%u-%u-%uT%u:%u:%u")
      val regex = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})T(\d{1,2}):(\d{1,2}):(\d{1,2}).*""")
      val match = regex.matchEntire(timeStr) ?: throw IllegalArgumentException("Improperly formatted time: $timeStr")
      val (year, month, day, hour, minute, second) = match.destructured
      val dateTime =
        LocalDateTime.of(
          year.toInt(),
          month.toInt(),
          day.toInt(),
          hour.toInt(),
          minute.toInt(),
          second.toInt(),
        )
      return dateTime.toEpochSecond(ZoneOffset.UTC)
    }
  }

  private fun parseKml(file: Path): List<GpsFix> {
    val doc = parseXmlDocument(file)
    val root = doc.documentElement ?: throw IllegalArgumentException("Empty KML document")
    val fixes = mutableListOf<GpsFix>()
    traverseKmlNode(root, fixes)
    return fixes
  }

  private fun traverseKmlNode(node: Node, fixes: MutableList<GpsFix>) {
    var current: Node? = node
    while (current != null) {
      if (current is Element) {
        when (current.localOrTagName) {
          "Placemark" -> parseKmlPlacemark(current, fixes)
          "NetworkLink" -> {} // Ignored, matching KmlParser.cpp
          else -> current.firstChild?.let { traverseKmlNode(it, fixes) }
        }
      }
      current = current.nextSibling
    }
  }

  private fun parseKmlPlacemark(placemark: Element, fixes: MutableList<GpsFix>) {
    var name = ""
    var description = ""
    val startIndex = fixes.size

    var child: Node? = placemark.firstChild
    while (child != null) {
      if (child is Element) {
        when (child.localOrTagName) {
          "name" -> name = child.textContent?.trim().orEmpty()
          "description" -> description = child.textContent?.trim().orEmpty()
          else -> parseKmlGeometry(child, fixes)
        }
      }
      child = child.nextSibling
    }

    if (fixes.size > startIndex) {
      fixes[startIndex] = fixes[startIndex].copy(name = name, description = description)
    }
  }

  private fun parseKmlGeometry(element: Element, fixes: MutableList<GpsFix>) {
    when (element.localOrTagName) {
      "Point",
      "LineString",
      "Polygon" -> {
        val coordsElement = findCoordinatesElement(element) ?: throw IllegalArgumentException("Location found with missing coordinates")
        parseKmlCoordinates(coordsElement.textContent.orEmpty(), fixes)
      }
      "Track" -> {
        parseKmlGxTrack(element, fixes)
      }
      "MultiGeometry",
      "MultiTrack" -> {
        var child: Node? = element.firstChild
        while (child != null) {
          if (child is Element) {
            parseKmlGeometry(child, fixes)
          }
          child = child.nextSibling
        }
      }
    }
  }

  private fun findCoordinatesElement(root: Element): Element? {
    if (root.localOrTagName == "coordinates") return root
    var child: Node? = root.firstChild
    while (child != null) {
      if (child is Element) {
        findCoordinatesElement(child)?.let {
          return it
        }
      }
      child = child.nextSibling
    }
    return null
  }

  private fun parseKmlCoordinates(content: String, fixes: MutableList<GpsFix>) {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) {
      throw IllegalArgumentException("Empty coordinates element")
    }
    // KML coordinates are tuples of "lon,lat[,alt]" separated by whitespace (with optional spaces around commas).
    val tupleRegex =
      Regex(
        """([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*,\s*([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)(?:\s*,\s*([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?))?"""
      )
    val matches = tupleRegex.findAll(trimmed).toList()
    if (matches.isEmpty()) {
      throw IllegalArgumentException("Malformed KML coordinates")
    }
    for (match in matches) {
      val lon = parseLongitude(match.groupValues[1])
      val lat = parseLatitude(match.groupValues[2])
      val ele = match.groupValues[3].takeIf { it.isNotEmpty() }?.let(::parseElevation) ?: 0.0
      fixes.add(GpsFix(latitude = lat, longitude = lon, elevation = ele))
    }
  }

  private fun parseKmlGxTrack(track: Element, fixes: MutableList<GpsFix>) {
    val timestamps = mutableListOf<Long>()
    val trackFixes = mutableListOf<GpsFix>()
    var child: Node? = track.firstChild
    while (child != null) {
      if (child is Element) {
        when (child.localOrTagName) {
          "when" -> {
            val text = child.textContent?.trim().orEmpty()
            timestamps.add(if (text.isNotEmpty()) parseIsoTime(text) else 0L)
          }
          "coord" -> {
            val parts = child.textContent.orEmpty().trim().split(Regex("""\s+"""))
            if (parts.size < 2) {
              throw IllegalArgumentException("Malformed gx:coord in KML Track")
            }
            val lon = parseLongitude(parts[0])
            val lat = parseLatitude(parts[1])
            val ele = if (parts.size >= 3) parseElevation(parts[2]) else 0.0
            trackFixes.add(GpsFix(latitude = lat, longitude = lon, elevation = ele))
          }
        }
      }
      child = child.nextSibling
    }
    for (i in trackFixes.indices) {
      val timeEpochSeconds = timestamps.getOrElse(i) { 0L }
      fixes.add(if (timeEpochSeconds != 0L) trackFixes[i].copy(timeEpochSeconds = timeEpochSeconds) else trackFixes[i])
    }
  }

  private fun parseLatitude(value: String): Double {
    val lat = value.trim().toDoubleOrNull()
    if (lat == null || !lat.isFinite() || lat !in GeoPoint.MIN_LATITUDE..GeoPoint.MAX_LATITUDE) {
      throw IllegalArgumentException("Invalid latitude: $value")
    }
    return lat
  }

  private fun parseLongitude(value: String): Double {
    val lon = value.trim().toDoubleOrNull()
    if (lon == null || !lon.isFinite() || lon !in GeoPoint.MIN_LONGITUDE..GeoPoint.MAX_LONGITUDE) {
      throw IllegalArgumentException("Invalid longitude: $value")
    }
    return lon
  }

  private fun parseElevation(value: String): Double {
    val ele = value.trim().toDoubleOrNull()
    if (ele == null || !ele.isFinite()) {
      throw IllegalArgumentException("Invalid elevation: $value")
    }
    return ele
  }

  /**
   * Converts a list of [GpsFix] into [RoutePoint]s with per-segment delays matching `LocationPage::toJsonString` and
   * `LocationPage::parseGpxKmlJson` in the emulator.
   */
  fun fixesToRoutePoints(fixes: List<GpsFix>): List<RoutePoint> {
    if (fixes.isEmpty()) return emptyList()
    val result = ArrayList<RoutePoint>(fixes.size)

    for (i in fixes.indices) {
      val fix = fixes[i]
      if (i == 0) {
        result.add(RoutePoint(fix.latitude, fix.longitude, fix.elevation, 0.0))
      } else {
        val prev = fixes[i - 1]
        val timeDelta =
          if (prev.timeEpochSeconds > 0L && fix.timeEpochSeconds > prev.timeEpochSeconds) {
            (fix.timeEpochSeconds - prev.timeEpochSeconds).toDouble()
          } else {
            val distMeters = GeoMath.distanceMeters(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
            max(MIN_CALCULATED_DELAY_SEC, distMeters / DEFAULT_SPEED_MPS).roundToInt().toDouble()
          }
        result.add(RoutePoint(fix.latitude, fix.longitude, fix.elevation, timeDelta))
      }
    }
    return result
  }

  private fun parseXmlDocument(file: Path) =
    Files.newInputStream(file).buffered().use { stream ->
      DocumentBuilderFactory.newInstance()
        .apply {
          isNamespaceAware = true
          setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        .newDocumentBuilder()
        .parse(stream)
    }

  private val Element.localOrTagName: String
    get() = localName ?: tagName.substringAfter(':')
}

/** A GPS fix parsed from a GPX or KML file, matching `GpsFix` in `GpxParser.cpp` and `KmlParser.cpp`. */
internal data class GpsFix(
  val latitude: Double,
  val longitude: Double,
  val elevation: Double = 0.0,
  val timeEpochSeconds: Long = 0L,
  val name: String = "",
  val description: String = "",
) : Comparable<GpsFix> {
  override fun compareTo(other: GpsFix): Int = timeEpochSeconds.compareTo(other.timeEpochSeconds)
}
