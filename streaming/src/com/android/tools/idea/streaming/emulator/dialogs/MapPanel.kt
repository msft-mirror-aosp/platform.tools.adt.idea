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
import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.annotations.VisibleForTesting

/**
 * Interactive offline Swing map component supporting Web Mercator projection, vector world landmass rendering, adaptive lat/lon graticule
 * grid, pan/zoom, bundled major cities, device location marker (blue dot), and route polyline/waypoint rendering.
 */
@Suppress("UseJBColor")
internal class MapPanel : JComponent() {

  var center: GeoPoint = GeoPoint.DEFAULT
    private set

  var zoom: Double = GeoMath.DEFAULT_ZOOM.toDouble()
    private set

  /** Current GPS location of the emulator (rendered as a blue dot with white halo). */
  var deviceLocation: GeoPoint? = null
    set(value) {
      field = value
      repaint()
    }

  /** Route origin waypoint (rendered as a green marker). */
  var routeOrigin: GeoPoint? = null
    set(value) {
      field = value
      repaint()
    }

  /** Route destination waypoint (rendered as a red marker). */
  var routeDestination: GeoPoint? = null
    set(value) {
      field = value
      repaint()
    }

  /** Active route polyline points (rendered as a blue path). */
  var routePoints: List<GeoPoint> = emptyList()
    set(value) {
      field = value
      repaint()
    }

  private var dragLastPoint: Point? = null

  private val zoomInButton = createZoomButton("+", message("location.dialog.zoom.in"), "zoomInButton") { setZoomLevel(zoom + 1.0) }
  val zoomOutButton = createZoomButton("\u2212", message("location.dialog.zoom.out"), "zoomOutButton") { setZoomLevel(zoom - 1.0) }

  init {
    layout = null
    isOpaque = true
    isFocusable = true
    updatePanCursor()
    addPropertyChangeListener("graphicsConfiguration") { updatePanCursor() }

    add(zoomInButton)
    add(zoomOutButton)

    val mouseHandler =
      object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          if (!isEnabled) return
          if (e.button == MouseEvent.BUTTON1) {
            requestFocusInWindow()
            dragLastPoint = e.point
          }
        }

        override fun mouseDragged(e: MouseEvent) {
          if (!isEnabled) return
          val last = dragLastPoint ?: return
          val dx = e.x - last.x
          val dy = e.y - last.y
          dragLastPoint = e.point
          panByPixels(-dx.toDouble(), -dy.toDouble())
        }

        override fun mouseReleased(e: MouseEvent) {
          dragLastPoint = null
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
          if (!isEnabled) return
          val rotation = e.wheelRotation
          if (rotation < 0) {
            setZoomLevel(zoom + 1.0)
          } else if (rotation > 0) {
            setZoomLevel(zoom - 1.0)
          }
        }
      }

    addMouseListener(mouseHandler)
    addMouseMotionListener(mouseHandler)
    addMouseWheelListener(mouseHandler)
  }

  override fun updateUI() {
    super.updateUI()
    updatePanCursor()
  }

  private fun updatePanCursor() {
    val newCursor =
      if (isEnabled) {
        val targetSize = getPanCursorTargetSize(this)
        panCursors.getOrPut(targetSize) { createPanCursor(targetSize) }
      } else {
        Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
      }
    if (cursor !== newCursor) {
      cursor = newCursor
    }
  }

  private fun createZoomButton(text: String, tooltip: String, buttonName: String, action: () -> Unit): JButton {
    return JButton(text).apply {
      name = buttonName
      setToolTipText(HtmlChunk.text(tooltip))
      isFocusable = false
      font = JBFont.regular().asBold()
      margin = JBUI.emptyInsets()
      cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
      addActionListener { action() }
    }
  }

  override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
    super.setBounds(x, y, width, height)
    val minZoom = minZoomLevel()
    if (zoom < minZoom) {
      zoom = minZoom
    }
    updateZoomButtons()
  }

  override fun doLayout() {
    val minZoom = minZoomLevel()
    if (zoom < minZoom) {
      zoom = minZoom
    }
    updateZoomButtons()
    val btnSize = JBUIScale.scale(ZOOM_BUTTON_SIZE)
    val margin = JBUIScale.scale(ZOOM_BUTTON_MARGIN)
    val x = (width - btnSize - margin).coerceAtLeast(margin)
    val yOut = (height - btnSize - margin).coerceAtLeast(margin)
    val yIn = (yOut - btnSize - JBUIScale.scale(4)).coerceAtLeast(margin)
    zoomInButton.setBounds(x, yIn, btnSize, btnSize)
    zoomOutButton.setBounds(x, yOut, btnSize, btnSize)
  }

  override fun getPreferredSize(): Dimension =
    if (isPreferredSizeSet) super.getPreferredSize() else JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)

  override fun getMinimumSize(): Dimension = JBUI.size(MIN_WIDTH, MIN_HEIGHT)

  /**
   * Returns the minimum zoom level for the current viewport size so that when centered at the equator, the visible vertical latitude span
   * is [GeoMath.MAX_VISIBLE_DEGREES] (`-60°` to `+60°`).
   */
  private fun minZoomLevel(): Double {
    val w = (width.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_WIDTH)).toDouble()
    val h = (height.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_HEIGHT)).toDouble()
    val maxLatHalfSpan = GeoMath.MAX_VISIBLE_DEGREES / 2.0
    val maxNormYSpan = GeoMath.latToNormalizedY(-maxLatHalfSpan) - GeoMath.latToNormalizedY(maxLatHalfSpan)
    val minWorldSize = max(h / maxNormYSpan, w)
    val z = ln(minWorldSize / GeoMath.TILE_SIZE) / ln(2.0)
    return z.coerceIn(GeoMath.MIN_ZOOM.toDouble(), GeoMath.MAX_ZOOM.toDouble())
  }

  fun setZoomLevel(newZoom: Int) {
    setZoomLevel(newZoom.toDouble())
  }

  private fun setZoomLevel(newZoom: Double) {
    val clamped = newZoom.coerceIn(minZoomLevel(), GeoMath.MAX_ZOOM.toDouble())
    if (abs(zoom - clamped) > 1e-6) {
      zoom = clamped
      updateZoomButtons()
      repaint()
    } else {
      updateZoomButtons()
    }
  }

  override fun setEnabled(enabled: Boolean) {
    super.setEnabled(enabled)
    updatePanCursor()
    updateZoomButtons()
  }

  private fun updateZoomButtons() {
    zoomInButton.isEnabled = isEnabled && zoom < GeoMath.MAX_ZOOM - 1e-6
    zoomOutButton.isEnabled = isEnabled && zoom > minZoomLevel() + 1e-6
  }

  fun panTo(point: GeoPoint) {
    if (!point.isValid) return
    center =
      GeoPoint(
        point.latitude.coerceIn(-GeoPoint.WEB_MERCATOR_MAX_LATITUDE, GeoPoint.WEB_MERCATOR_MAX_LATITUDE),
        point.longitude.coerceIn(GeoPoint.MIN_LONGITUDE, GeoPoint.MAX_LONGITUDE),
        point.elevation,
      )
    repaint()
  }

  /** Adjusts [center] and [zoom] so all [points] fit comfortably within the map view. */
  fun fitBounds(points: List<GeoPoint>) {
    val valid = points.filter { it.isValid }
    if (valid.isEmpty()) return
    if (valid.size == 1) {
      panTo(valid[0])
      return
    }
    val minLat = valid.minOf { it.latitude }
    val maxLat = valid.maxOf { it.latitude }
    val minNormY = GeoMath.latToNormalizedY(maxLat)
    val maxNormY = GeoMath.latToNormalizedY(minLat)
    val centerLat = GeoMath.normalizedYToLat((minNormY + maxNormY) / 2.0)

    var minUnwrappedLon = valid[0].longitude
    var maxUnwrappedLon = valid[0].longitude
    var currentUnwrappedLon = valid[0].longitude
    for (i in 1 until valid.size) {
      currentUnwrappedLon += GeoMath.shortestDeltaLongitude(valid[i - 1].longitude, valid[i].longitude)
      if (currentUnwrappedLon < minUnwrappedLon) minUnwrappedLon = currentUnwrappedLon
      if (currentUnwrappedLon > maxUnwrappedLon) maxUnwrappedLon = currentUnwrappedLon
    }
    val lonSpan = (maxUnwrappedLon - minUnwrappedLon).coerceAtMost(360.0)
    val centerLon = GeoMath.wrapLongitude((minUnwrappedLon + maxUnwrappedLon) / 2.0)

    val viewW = (width.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_WIDTH)) * 0.75
    val viewH = (height.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_HEIGHT)) * 0.75
    val dx = (lonSpan / 360.0).coerceAtLeast(1e-6)
    val dy = (maxNormY - minNormY).coerceAtLeast(1e-6)

    val minZoom = minZoomLevel()
    var bestZoom = minZoom
    for (z in GeoMath.MAX_ZOOM downTo GeoMath.MIN_ZOOM) {
      val zDouble = z.toDouble()
      if (zDouble < minZoom) break
      val worldPixels = GeoMath.worldSize(zDouble)
      if (dx * worldPixels <= viewW && dy * worldPixels <= viewH) {
        bestZoom = zDouble
        break
      }
    }
    center = GeoPoint(centerLat, centerLon)
    setZoomLevel(bestZoom)
    repaint()
  }

  private fun panByPixels(deltaX: Double, deltaY: Double) {
    val worldSize = GeoMath.worldSize(zoom)
    val normX = GeoMath.lonToNormalizedX(center.longitude) + deltaX / worldSize
    val normY = (GeoMath.latToNormalizedY(center.latitude) + deltaY / worldSize).coerceIn(0.0, 1.0)
    center = GeoPoint(GeoMath.normalizedYToLat(normY), GeoMath.normalizedXToLon(normX), center.elevation)
    repaint()
  }

  /** Converts component pixel coordinates `(px, py)` to a [GeoPoint]. */
  @VisibleForTesting
  internal fun pixelToGeoPoint(px: Double, py: Double): GeoPoint {
    val w = width.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_WIDTH)
    val h = height.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_HEIGHT)
    val worldSize = GeoMath.worldSize(zoom)
    val centerWorldX = GeoMath.lonToNormalizedX(center.longitude) * worldSize
    val centerWorldY = GeoMath.latToNormalizedY(center.latitude) * worldSize
    val targetWorldX = centerWorldX + (px - w / 2.0)
    val targetWorldY = (centerWorldY + (py - h / 2.0)).coerceIn(0.0, worldSize)
    val lat = GeoMath.normalizedYToLat(targetWorldY / worldSize)
    val lon = GeoMath.normalizedXToLon(targetWorldX / worldSize)
    return GeoPoint(
      latitude = (lat * 1e6).roundToInt() / 1e6,
      longitude = (lon * 1e6).roundToInt() / 1e6,
    )
  }

  /** Converts a [GeoPoint] to component pixel coordinates `(x, y)`. */
  private fun geoPointToPixel(point: GeoPoint): Point = coordinatesToPixel(point.latitude, point.longitude)

  /** Converts `(latitude, longitude)` in degrees to component pixel coordinates `(x, y)`. */
  private fun coordinatesToPixel(latitude: Double, longitude: Double): Point {
    val w = width.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_WIDTH)
    val h = height.takeIf { it > 0 } ?: JBUIScale.scale(PREFERRED_HEIGHT)
    val worldSize = GeoMath.worldSize(zoom)
    val centerWorldX = GeoMath.lonToNormalizedX(center.longitude) * worldSize
    val centerWorldY = GeoMath.latToNormalizedY(center.latitude) * worldSize
    val pointWorldX = GeoMath.lonToNormalizedX(longitude) * worldSize
    val pointWorldY = GeoMath.latToNormalizedY(latitude) * worldSize
    var deltaX = pointWorldX - centerWorldX
    if (deltaX > worldSize / 2.0) deltaX -= worldSize
    if (deltaX < -worldSize / 2.0) deltaX += worldSize
    val px = (w / 2.0 + deltaX).roundToInt()
    val py = (h / 2.0 + (pointWorldY - centerWorldY)).roundToInt()
    return Point(px, py)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

      val isDark = !JBColor.isBright()
      // Ocean fill
      g2.color = if (isDark) Color(0x1E, 0x26, 0x31) else Color(0xD8, 0xE8, 0xF5)
      g2.fillRect(0, 0, width, height)

      paintLandPolygons(g2, isDark)
      paintGraticule(g2, isDark)
      paintWorldCities(g2, isDark)
      paintRoute(g2)
      routeOrigin?.let { paintWaypointMarker(g2, it, ORIGIN_COLOR) }
      routeDestination?.let { paintWaypointMarker(g2, it, DESTINATION_COLOR) }
      deviceLocation?.let { paintDeviceLocationDot(g2, it) }
      paintScaleBar(g2, isDark)
      paintCoordinatesFooter(g2, isDark)

      // Subtle border around map
      g2.color = JBColor.border()
      g2.drawRect(0, 0, width - 1, height - 1)
    } finally {
      g2.dispose()
    }
  }

  private fun paintLandPolygons(g2: Graphics2D, isDark: Boolean) {
    val worldSize = GeoMath.worldSize(zoom)
    val centerWorldX = GeoMath.lonToNormalizedX(center.longitude) * worldSize
    val centerWorldY = GeoMath.latToNormalizedY(center.latitude) * worldSize
    val topLeftWorldX = centerWorldX - width / 2.0
    val topLeftWorldY = centerWorldY - height / 2.0

    val oceanFill = if (isDark) Color(0x1E, 0x26, 0x31) else Color(0xD8, 0xE8, 0xF5)
    val landFill = if (isDark) Color(0x2D, 0x35, 0x3B) else Color(0xF1, 0xEF, 0xE9)
    val coastStroke = if (isDark) Color(0x45, 0x52, 0x5C) else Color(0xB3, 0xC1, 0xCE)
    g2.stroke = BasicStroke(JBUIScale.scale(1f))

    val mapData = OfflineWorldMap.loadedMapDataOrNull ?: return

    val minWrap = floor(topLeftWorldX / worldSize).toInt() - 1
    val maxWrap = floor((topLeftWorldX + width) / worldSize).toInt() + 1

    for (wrap in minWrap..maxWrap) {
      val wrapOffsetX = wrap * worldSize
      paintPolygonGroup(
        g2,
        mapData.landPolygons,
        worldSize,
        wrapOffsetX,
        topLeftWorldX,
        topLeftWorldY,
        landFill,
        coastStroke,
      )
      paintPolygonGroup(
        g2,
        mapData.lakePolygons,
        worldSize,
        wrapOffsetX,
        topLeftWorldX,
        topLeftWorldY,
        oceanFill,
        coastStroke,
      )
      paintPolygonGroup(
        g2,
        mapData.lakeIslandPolygons,
        worldSize,
        wrapOffsetX,
        topLeftWorldX,
        topLeftWorldY,
        landFill,
        coastStroke,
      )
    }
  }

  private fun paintPolygonGroup(
    g2: Graphics2D,
    polygons: List<OfflineWorldMap.MapPolygon>,
    worldSize: Double,
    wrapOffsetX: Double,
    topLeftWorldX: Double,
    topLeftWorldY: Double,
    fillColor: Color,
    strokeColor: Color,
  ) {
    val viewRight = width.toDouble()
    val viewBottom = height.toDouble()
    for (poly in polygons) {
      val sxMin = poly.minNormX * worldSize + wrapOffsetX - topLeftWorldX
      val sxMax = poly.maxNormX * worldSize + wrapOffsetX - topLeftWorldX
      if (sxMax < 0.0 || sxMin > viewRight) continue
      val syMin = poly.minNormY * worldSize - topLeftWorldY
      val syMax = poly.maxNormY * worldSize - topLeftWorldY
      if (syMax < 0.0 || syMin > viewBottom) continue

      val coords = poly.normCoords
      if (coords.size < 6) continue
      val path = Path2D.Double()
      for (i in coords.indices step 2) {
        val sx = coords[i] * worldSize + wrapOffsetX - topLeftWorldX
        val sy = coords[i + 1] * worldSize - topLeftWorldY
        if (i == 0) {
          path.moveTo(sx, sy)
        } else {
          path.lineTo(sx, sy)
        }
      }
      path.closePath()
      g2.color = fillColor
      g2.fill(path)
      g2.color = strokeColor
      g2.draw(path)
    }
  }

  private fun paintGraticule(g2: Graphics2D, isDark: Boolean) {
    val worldSize = GeoMath.worldSize(zoom)
    val centerWorldX = GeoMath.lonToNormalizedX(center.longitude) * worldSize
    val centerWorldY = GeoMath.latToNormalizedY(center.latitude) * worldSize
    val topLeftWorldX = centerWorldX - width / 2.0
    val topLeftWorldY = centerWorldY - height / 2.0

    val stepDeg = graticuleStepDegrees(zoom)
    val gridColor = if (isDark) Color(0x38, 0x44, 0x52, 130) else Color(0x9A, 0xB4, 0xCC, 130)
    val majorGridColor = if (isDark) Color(0x52, 0x64, 0x78, 180) else Color(0x6E, 0x8F, 0xAE, 180)
    val labelColor = if (isDark) Color(0x8A, 0x99, 0xAD) else Color(0x52, 0x69, 0x80)

    g2.font = JBFont.small()
    val fm = g2.fontMetrics
    g2.stroke = BasicStroke(1f)

    // Latitude lines
    val maxLat = GeoMath.normalizedYToLat((topLeftWorldY / worldSize).coerceIn(0.0, 1.0))
    val minLat = GeoMath.normalizedYToLat(((topLeftWorldY + height) / worldSize).coerceIn(0.0, 1.0))
    val startLatIdx = floor(minLat / stepDeg).toInt()
    val endLatIdx = floor(maxLat / stepDeg).toInt()

    for (idx in startLatIdx..endLatIdx) {
      val lat = idx * stepDeg
      if (lat !in -85.0..85.0) continue
      val sy = (GeoMath.latToNormalizedY(lat) * worldSize - topLeftWorldY).roundToInt()
      if (sy !in 0..height) continue
      val isEquator = abs(lat) < 1e-6
      g2.color = if (isEquator) majorGridColor else gridColor
      g2.drawLine(0, sy, width, sy)
      if (sy in JBUIScale.scale(16)..(height - JBUIScale.scale(28))) {
        g2.color = labelColor
        g2.drawString(formatDegreeLabel(lat, isLat = true, stepDeg = stepDeg), JBUIScale.scale(4), sy - JBUIScale.scale(2))
      }
    }

    // Longitude lines
    val minLonUnwrapped = (topLeftWorldX / worldSize) * 360.0 - 180.0
    val maxLonUnwrapped = ((topLeftWorldX + width) / worldSize) * 360.0 - 180.0
    val startLonIdx = floor(minLonUnwrapped / stepDeg).toInt()
    val endLonIdx = floor(maxLonUnwrapped / stepDeg).toInt()

    for (idx in startLonIdx..endLonIdx) {
      val lonUnwrapped = idx * stepDeg
      val sx = (((lonUnwrapped + 180.0) / 360.0) * worldSize - topLeftWorldX).roundToInt()
      if (sx !in 0..width) continue
      val wrappedLon = ((lonUnwrapped + 180.0).mod(360.0)) - 180.0
      val isPrimeMeridian = abs(wrappedLon) < 1e-6
      g2.color = if (isPrimeMeridian) majorGridColor else gridColor
      g2.drawLine(sx, 0, sx, height)
      if (sx in JBUIScale.scale(24)..(width - JBUIScale.scale(48))) {
        g2.color = labelColor
        g2.drawString(
          formatDegreeLabel(wrappedLon, isLat = false, stepDeg = stepDeg),
          sx + JBUIScale.scale(3),
          fm.ascent + JBUIScale.scale(3),
        )
      }
    }
  }

  /** Returns the spacing in degrees between adjacent latitude and longitude grid lines (graticules) for the given [zoom] level. */
  private fun graticuleStepDegrees(zoom: Double): Double {
    val z = (zoom + 0.25).toInt()
    return when {
      z <= 2 -> 30.0
      z == 3 -> 15.0
      z == 4 -> 10.0
      z == 5 -> 5.0
      z in 6..7 -> 2.0
      z == 8 -> 1.0
      z == 9 -> 0.5
      z == 10 -> 0.2
      z == 11 -> 0.1
      z == 12 -> 0.05
      z == 13 -> 0.02
      z == 14 -> 0.01
      else -> 0.005
    }
  }

  private fun formatDegreeLabel(value: Double, isLat: Boolean, stepDeg: Double): String {
    val hemi =
      if (abs(value) < 1e-6) {
        ""
      } else if (isLat) {
        if (value > 0) "N" else "S"
      } else {
        if (value > 0) "E" else "W"
      }
    val decimals =
      when {
        stepDeg >= 1.0 -> 0
        stepDeg >= 0.1 -> 1
        stepDeg >= 0.01 -> 2
        else -> 3
      }
    return String.format(Locale.US, "%.${decimals}f°%s", abs(value), hemi)
  }

  private fun paintWorldCities(g2: Graphics2D, isDark: Boolean) {
    val dotColor = if (isDark) Color(0x9A, 0xA8, 0xB8) else Color(0x5F, 0x6B, 0x7A)
    val capitalDotColor = if (isDark) Color(0xBD, 0xCC, 0xDE) else Color(0x3D, 0x4A, 0x59)
    val textColor = if (isDark) Color(0xBD, 0xC6, 0xD1) else Color(0x3C, 0x45, 0x52)
    val haloColor = if (isDark) Color(0x1E, 0x26, 0x31, 200) else Color(0xF1, 0xEF, 0xE9, 200)
    g2.font = JBFont.small()
    val fm = g2.fontMetrics
    val textH = fm.ascent
    val regularRadius = JBUIScale.scale(2.5f).toDouble()
    val capitalRadius = JBUIScale.scale(3.0f).toDouble()
    val labelGap = JBUIScale.scale(5)
    val padX = JBUIScale.scale(if (zoom < 3.5) 5 else 2).toDouble()
    val padY = JBUIScale.scale(if (zoom < 3.5) 3 else 1).toDouble()
    val marginX = JBUIScale.scale(4)
    val marginTop = JBUIScale.scale(14)
    val marginBottom = JBUIScale.scale(28)
    val occupiedRects = ArrayList<Rectangle2D.Double>(128)

    val worldSize = GeoMath.worldSize(zoom)
    val centerWorldY = GeoMath.latToNormalizedY(center.latitude) * worldSize
    val topLeftWorldY = centerWorldY - height / 2.0
    val maxLat = GeoMath.normalizedYToLat((topLeftWorldY / worldSize).coerceIn(0.0, 1.0))
    val minLat = GeoMath.normalizedYToLat(((topLeftWorldY + height) / worldSize).coerceIn(0.0, 1.0))

    class VisibleCity(
      val city: OfflineWorldMap.WorldCity,
      val pt: Point,
      val r: Double,
      val dotRect: Rectangle2D.Double,
    )
    val mapData = OfflineWorldMap.loadedMapDataOrNull ?: return
    val visibleCities = ArrayList<VisibleCity>()
    for (city in mapData.cities) {
      if (zoom < city.minZoom) continue
      if (city.latitude !in minLat..maxLat) continue
      val pt = coordinatesToPixel(city.latitude, city.longitude)
      if (pt.x !in 0..width || pt.y !in 0..height) continue
      val r = if (city.isCapital) capitalRadius else regularRadius
      val dotRect = Rectangle2D.Double(pt.x - r - 1.0, pt.y - r - 1.0, (r + 1.0) * 2.0, (r + 1.0) * 2.0)
      if (visibleCities.any { it.dotRect.intersects(dotRect) }) continue
      visibleCities.add(VisibleCity(city, pt, r, dotRect))
    }

    for (i in visibleCities.indices) {
      val vc = visibleCities[i]
      val city = vc.city
      val pt = vc.pt
      val r = vc.r
      val dotRect = vc.dotRect
      if (occupiedRects.any { it.intersects(dotRect) }) continue

      val textW = fm.stringWidth(city.name)
      val candidates =
        arrayOf(
          intArrayOf(pt.x + labelGap, pt.y + textH / 2 - 1),
          intArrayOf(pt.x - labelGap - textW, pt.y + textH / 2 - 1),
          intArrayOf(pt.x - textW / 2, pt.y + labelGap + textH),
          intArrayOf(pt.x - textW / 2, pt.y - labelGap),
        )

      var placedX = Int.MIN_VALUE
      var placedY = Int.MIN_VALUE
      var placedRect: Rectangle2D.Double? = null
      var bestFirstHitIndex = -1
      for (cand in candidates) {
        val lx = cand[0]
        val ly = cand[1]
        if (lx < marginX || lx + textW > width - marginX) continue
        if (ly - textH < marginTop || ly > height - marginBottom) continue
        val rect = Rectangle2D.Double(lx - padX, ly - textH - padY, textW + padX * 2.0, textH + padY * 2.0)
        if (occupiedRects.any { it.intersects(rect) }) continue

        var firstHitIndex = Int.MAX_VALUE
        for (j in i + 1 until visibleCities.size) {
          val otherDot = visibleCities[j].dotRect
          if (occupiedRects.any { it.intersects(otherDot) }) continue
          if (rect.intersects(otherDot)) {
            firstHitIndex = j
            break
          }
        }
        if (firstHitIndex > bestFirstHitIndex) {
          bestFirstHitIndex = firstHitIndex
          placedX = lx
          placedY = ly
          placedRect = rect
          if (firstHitIndex == Int.MAX_VALUE) {
            break
          }
        }
      }

      if (placedRect != null) {
        occupiedRects.add(dotRect)
        occupiedRects.add(placedRect)
        g2.color = if (city.isCapital) capitalDotColor else dotColor
        g2.fill(Ellipse2D.Double(pt.x - r, pt.y - r, r * 2, r * 2))
        g2.color = haloColor
        g2.drawString(city.name, placedX + 1, placedY + 1)
        g2.color = textColor
        g2.drawString(city.name, placedX, placedY)
      }
    }
  }

  private fun paintRoute(g2: Graphics2D) {
    if (routePoints.size < 2) return
    val path = Path2D.Double()
    for (i in routePoints.indices) {
      val pt = geoPointToPixel(routePoints[i])
      if (i == 0) {
        path.moveTo(pt.x.toDouble(), pt.y.toDouble())
      } else {
        path.lineTo(pt.x.toDouble(), pt.y.toDouble())
      }
    }
    // Outer casing
    g2.color = Color(0x19, 0x52, 0xB8, 200)
    g2.stroke = BasicStroke(JBUIScale.scale(6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g2.draw(path)
    // Inner line
    g2.color = ROUTE_LINE_COLOR
    g2.stroke = BasicStroke(JBUIScale.scale(4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g2.draw(path)
  }

  private fun paintWaypointMarker(g2: Graphics2D, point: GeoPoint, fillColor: Color) {
    val pt = geoPointToPixel(point)
    val r = JBUIScale.scale(6).toDouble()
    g2.color = Color.WHITE
    g2.fill(Ellipse2D.Double(pt.x - r - 2, pt.y - r - 2, (r + 2) * 2, (r + 2) * 2))
    g2.color = fillColor
    g2.fill(Ellipse2D.Double(pt.x - r, pt.y - r, r * 2, r * 2))
  }

  /** Renders the device location marker matching `blueDot` in `location-point.js`. */
  private fun paintDeviceLocationDot(g2: Graphics2D, point: GeoPoint) {
    val pt = geoPointToPixel(point)
    val haloRadius = JBUIScale.scale(10).toDouble()
    val dotRadius = JBUIScale.scale(6).toDouble()

    g2.color = Color(0, 0, 0, 35)
    g2.fill(Ellipse2D.Double(pt.x - haloRadius, pt.y - haloRadius, haloRadius * 2, haloRadius * 2))

    g2.color = Color.WHITE
    g2.fill(Ellipse2D.Double(pt.x - dotRadius - 2, pt.y - dotRadius - 2, (dotRadius + 2) * 2, (dotRadius + 2) * 2))

    g2.color = DEVICE_DOT_COLOR
    g2.fill(Ellipse2D.Double(pt.x - dotRadius, pt.y - dotRadius, dotRadius * 2, dotRadius * 2))
  }

  private fun paintScaleBar(g2: Graphics2D, isDark: Boolean) {
    val mpp = GeoMath.metersPerPixel(center.latitude, zoom)
    if (mpp <= 0.0 || !mpp.isFinite()) return
    val targetMeters = mpp * JBUIScale.scale(70)
    val niceMeters = SCALE_STEPS_METERS.firstOrNull { it >= targetMeters } ?: SCALE_STEPS_METERS.last()
    val barPx = (niceMeters / mpp).roundToInt().coerceIn(JBUIScale.scale(30), JBUIScale.scale(120))
    val label =
      if (niceMeters >= 1000.0) {
        "${(niceMeters / 1000.0).roundToInt()} km"
      } else {
        "${niceMeters.roundToInt()} m"
      }

    g2.font = JBFont.small()
    val fm = g2.fontMetrics
    val btnReserve = JBUIScale.scale(ZOOM_BUTTON_SIZE + ZOOM_BUTTON_MARGIN + 10)
    val xRight = width - btnReserve
    val xLeft = xRight - barPx
    if (xLeft < JBUIScale.scale(120)) return
    val yBar = height - JBUIScale.scale(12)

    g2.color = if (isDark) Color(0xCE, 0xD0, 0xD6) else Color(0x3C, 0x40, 0x43)
    g2.stroke = BasicStroke(JBUIScale.scale(1.5f))
    g2.drawLine(xLeft, yBar, xRight, yBar)
    g2.drawLine(xLeft, yBar - JBUIScale.scale(4), xLeft, yBar)
    g2.drawLine(xRight, yBar - JBUIScale.scale(4), xRight, yBar)
    g2.drawString(label, xLeft + (barPx - fm.stringWidth(label)) / 2, yBar - JBUIScale.scale(4))
  }

  private fun paintCoordinatesFooter(g2: Graphics2D, isDark: Boolean) {
    val activePoint = deviceLocation ?: center
    val text = activePoint.formatCoordinates()
    g2.font = JBFont.small()
    val fm = g2.fontMetrics
    val padX = JBUIScale.scale(6)
    val padY = JBUIScale.scale(3)
    val boxW = fm.stringWidth(text) + padX * 2
    val boxH = fm.height + padY * 2
    val x = JBUIScale.scale(8)
    val y = height - boxH - JBUIScale.scale(8)

    g2.color = if (isDark) Color(0x1E, 0x20, 0x24, 210) else Color(0xFF, 0xFF, 0xFF, 210)
    g2.fill(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), boxW.toDouble(), boxH.toDouble(), 8.0, 8.0))
    g2.color = if (isDark) Color(0xCE, 0xD0, 0xD6) else Color(0x3C, 0x40, 0x43)
    g2.drawString(text, x + padX, y + padY + fm.ascent)
  }

  companion object {
    private const val PREFERRED_WIDTH = 360
    private const val PREFERRED_HEIGHT = 320
    private const val MIN_WIDTH = 240
    private const val MIN_HEIGHT = 220
    private const val ZOOM_BUTTON_SIZE = 28
    private const val ZOOM_BUTTON_MARGIN = 10
    private const val CURSOR_BASE_SIZE = 24
    private const val CURSOR_LOGICAL_SIZE = 32

    private val panCursors = ConcurrentHashMap<Int, Cursor>()

    private fun getPanCursorTargetSize(component: JComponent): Int {
      // On macOS, AWT scales cursor images for Retina automatically. On Linux/Windows, cursor bitmaps
      // are passed to the native windowing system in physical pixels, so scale by sysScale/userScale.
      val cursorScale = if (SystemInfo.isMac) 1f else maxOf(JBUIScale.sysScale(component), JBUIScale.scale(1f))
      return (CURSOR_LOGICAL_SIZE * cursorScale).toInt().coerceAtLeast(CURSOR_LOGICAL_SIZE)
    }

    private fun createPanCursor(targetSize: Int): Cursor {
      if (GraphicsEnvironment.isHeadless()) {
        return Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
      }
      val toolkit = Toolkit.getDefaultToolkit()
      val bestSize = toolkit.getBestCursorSize(targetSize, targetSize)
      val width = bestSize.width.takeIf { it >= 16 } ?: targetSize
      val height = bestSize.height.takeIf { it >= 16 } ?: targetSize
      @Suppress("UndesirableClassUsage") val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      val g2 = image.createGraphics()
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.scale(width.toDouble() / CURSOR_BASE_SIZE, height.toDouble() / CURSOR_BASE_SIZE)
        val c = CURSOR_BASE_SIZE / 2.0
        val triangles =
          arrayOf(
            createTriangle(c, c - 9.2, c - 3.8, c - 5.2, c + 3.8, c - 5.2),
            createTriangle(c, c + 9.2, c - 3.8, c + 5.2, c + 3.8, c + 5.2),
            createTriangle(c - 9.2, c, c - 5.2, c - 3.8, c - 5.2, c + 3.8),
            createTriangle(c + 9.2, c, c + 5.2, c - 3.8, c + 5.2, c + 3.8),
          )
        val outlineStroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER)
        val centerDotWhite = Ellipse2D.Double(c - 3.0, c - 3.0, 6.0, 6.0)
        val centerDotBlack = Ellipse2D.Double(c - 1.7, c - 1.7, 3.4, 3.4)

        val shadow = g2.create() as Graphics2D
        try {
          shadow.translate(0.5, 1.0)
          shadow.color = Color(0, 0, 0, 75)
          shadow.stroke = outlineStroke
          for (triangle in triangles) {
            shadow.draw(triangle)
            shadow.fill(triangle)
          }
          shadow.fill(centerDotWhite)
        } finally {
          shadow.dispose()
        }

        g2.color = Color.WHITE
        g2.stroke = outlineStroke
        for (triangle in triangles) {
          g2.draw(triangle)
        }
        g2.fill(centerDotWhite)

        g2.color = Color.BLACK
        for (triangle in triangles) {
          g2.fill(triangle)
        }
        g2.fill(centerDotBlack)
      } finally {
        g2.dispose()
      }
      return toolkit.createCustomCursor(image, Point(width / 2, height / 2), "MapPanCursor")
    }

    private fun createTriangle(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double): Path2D.Double =
      Path2D.Double().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
        lineTo(x3, y3)
        closePath()
      }

    private val DEVICE_DOT_COLOR = Color(0x42, 0x86, 0xF5)
    private val ROUTE_LINE_COLOR = Color(0x42, 0x85, 0xF4)
    private val ORIGIN_COLOR = Color(0x34, 0xA8, 0x53)
    private val DESTINATION_COLOR = Color(0xEA, 0x43, 0x35)

    private val SCALE_STEPS_METERS =
      doubleArrayOf(
        10.0,
        20.0,
        50.0,
        100.0,
        200.0,
        500.0,
        1_000.0,
        2_000.0,
        5_000.0,
        10_000.0,
        20_000.0,
        50_000.0,
        100_000.0,
        200_000.0,
        500_000.0,
        1_000_000.0,
        2_000_000.0,
        5_000_000.0,
      )
  }
}
