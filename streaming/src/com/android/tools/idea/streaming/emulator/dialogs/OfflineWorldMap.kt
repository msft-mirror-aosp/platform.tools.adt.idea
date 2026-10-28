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

import com.intellij.openapi.diagnostic.thisLogger
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Bundled Natural Earth landmass outlines and world cities for offline map rendering. */
internal object OfflineWorldMap {
  data class WorldCity(
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val minZoom: Int = 1,
    val isCapital: Boolean = false,
  )

  /** Pre-projected polygon in normalized Web Mercator coordinates (`x` in `[0, 1+]`, `y` in `[0, 1]`). */
  class MapPolygon(
    val normCoords: FloatArray,
    val minNormX: Double,
    val maxNormX: Double,
    val minNormY: Double,
    val maxNormY: Double,
  )

  private fun MapPolygon.containsBoundingBox(other: MapPolygon): Boolean =
    other.minNormX >= minNormX && other.maxNormX <= maxNormX && other.minNormY >= minNormY && other.maxNormY <= maxNormY

  class WorldMapData(
    val landPolygons: List<MapPolygon>,
    val lakePolygons: List<MapPolygon>,
    val lakeIslandPolygons: List<MapPolygon>,
    val cities: List<WorldCity>,
  )

  private val lazyMapData: Lazy<WorldMapData> = lazy { loadWorldMapResource() }

  val loadedMapData: WorldMapData
    get() = lazyMapData.value

  val loadedMapDataOrNull: WorldMapData?
    get() = if (lazyMapData.isInitialized()) lazyMapData.value else null

  private fun loadWorldMapResource(): WorldMapData {
    val stream =
      OfflineWorldMap::class.java.getResourceAsStream("/worldmap/world_map_50m.zip")
        ?: return WorldMapData(emptyList(), emptyList(), emptyList(), emptyList())
    return try {
      ZipInputStream(BufferedInputStream(stream)).use { zip ->
        zip.nextEntry ?: return WorldMapData(emptyList(), emptyList(), emptyList(), emptyList())
        val input = DataInputStream(BufferedInputStream(zip))
        val allLand = readPolygonList(input)
        val lakes = readPolygonList(input)
        val cities = readCityList(input)
        val (lakeIslands, land) = allLand.partition { landPoly -> lakes.any { lakePoly -> lakePoly.containsBoundingBox(landPoly) } }
        WorldMapData(land, lakes, lakeIslands, cities)
      }
    } catch (e: IOException) {
      thisLogger().warn("Failed to load world_map_50m.zip", e)
      WorldMapData(emptyList(), emptyList(), emptyList(), emptyList())
    }
  }

  private fun readPolygonList(input: DataInputStream): List<MapPolygon> {
    val count = input.readUnsignedShort()
    val result = ArrayList<MapPolygon>(count)
    for (p in 0 until count) {
      val vertexCount = input.readUnsignedShort()
      val normCoords = FloatArray(vertexCount * 2)
      var ilat = 0
      var ilon = 0
      var minX = Double.POSITIVE_INFINITY
      var maxX = Double.NEGATIVE_INFINITY
      var minY = Double.POSITIVE_INFINITY
      var maxY = Double.NEGATIVE_INFINITY
      for (v in 0 until vertexCount) {
        if (v == 0) {
          ilat = input.readInt()
          ilon = input.readInt()
        } else {
          ilat += input.readShort().toInt()
          ilon += input.readShort().toInt()
        }
        val lat = ilat / 1_000.0
        val lon = ilon / 1_000.0
        val nx = (lon + 180.0) / 360.0
        val ny = GeoMath.latToNormalizedY(lat)
        normCoords[v * 2] = nx.toFloat()
        normCoords[v * 2 + 1] = ny.toFloat()
        if (nx < minX) minX = nx
        if (nx > maxX) maxX = nx
        if (ny < minY) minY = ny
        if (ny > maxY) maxY = ny
      }
      result.add(MapPolygon(normCoords, minX, maxX, minY, maxY))
    }
    return result
  }

  private fun readCityList(input: DataInputStream): List<WorldCity> {
    val count = input.readUnsignedShort()
    val result = ArrayList<WorldCity>(count)
    for (i in 0 until count) {
      val name = input.readUTF()
      val region = input.readUTF()
      val lat = input.readInt() / 10_000.0
      val lon = input.readInt() / 10_000.0
      val flags = input.readUnsignedByte()
      val minZoom = flags and 0x0F
      val isCapital = (flags and 0x10) != 0
      result.add(WorldCity(name, region, lat, lon, minZoom, isCapital))
    }
    return result
  }
}
