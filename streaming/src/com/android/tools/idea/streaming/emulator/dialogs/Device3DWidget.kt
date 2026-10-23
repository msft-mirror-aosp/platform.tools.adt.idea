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

import com.android.emulator.control.Posture.PostureValue
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.adtui.device.SkinDefinition
import com.android.tools.idea.streaming.emulator.EmulatorConfiguration.PostureDescriptor.ValueType
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.scale.JBUIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JComponent
import javax.swing.border.LineBorder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Interactive 3D preview widget that renders the virtual device's 3D pose (rotation, position, and fold/roll state) and supports dragging
 * to rotate/move and scrolling the mouse wheel to adjust the Z position.
 */
@Suppress("UseJBColor", "UndesirableClassUsage")
internal class Device3DWidget(
  emulator: EmulatorController,
  private val onRotationChanged: (Vec3) -> Unit = {},
  private val onPositionChanged: (Vec3) -> Unit = {},
  private val onFoldValueChanged: (Float) -> Unit = {},
) : JComponent() {

  enum class OperationMode {
    ROTATE,
    MOVE,
    FOLD,
  }

  private val modelHalfDepth = if (emulator.emulatorConfig.deviceType == DeviceType.WEAR) WEAR_HALF_DEPTH else PHONE_HALF_DEPTH
  private val postures = emulator.emulatorConfig.postures
  private val hasPostures = postures.isNotEmpty()
  private val isRollable = postures.firstOrNull()?.valueType == ValueType.ROLL_PERCENTAGE
  private val minFoldValue = postures.minOfOrNull { it.minValue }?.toFloat() ?: 0f
  private val maxFoldValue = postures.maxOfOrNull { it.maxValue }?.toFloat() ?: 180f

  /**
   * The primary [SkinDefinition] used for the 3D mesh outline and front/inner display (the unfolded skin for foldable devices, or the
   * default skin for other devices), or null if the AVD has no skin.
   */
  private val skin: SkinDefinition? =
    if (hasPostures && !isRollable) {
      emulator.getSkin(PostureValue.POSTURE_OPENED) ?: emulator.getSkin(null)
    } else {
      emulator.getSkin(null)
    }

  /** The [SkinDefinition] for the outer display on the back of a foldable device, or null if none. */
  private val outerSkin: SkinDefinition? =
    if (hasPostures && !isRollable) {
      emulator.getSkin(PostureValue.POSTURE_CLOSED)?.takeIf { it !== skin }
    } else {
      null
    }

  private val skinOutline: SkinOutline? = skin?.let(::analyzeSkinOutline)
  private val baseHalfExtents: Pair<Float, Float> = computeBaseHalfExtents()
  private val hingeX: Float =
    if (hasPostures && !isRollable && skinOutline != null) {
      skinOutline.normalizedDisplayCenterX * 2f * baseHalfExtents.first
    } else {
      0f
    }
  private val baseLeftFaces: List<Face3D>
  private val baseRightFaces: List<Face3D>
  private val baseFaces: List<Face3D>
  private var cachedDeviceFaces: List<Face3D>? = null

  var operationMode: OperationMode = OperationMode.ROTATE

  var rotationDegrees: Vec3 = Vec3.ZERO
    set(value) {
      if (field != value) {
        field = value
        repaint()
      }
    }

  var positionMeters: Vec3 = Vec3.ZERO
    set(value) {
      if (field != value) {
        field = value
        repaint()
      }
    }

  var foldValue: Float = maxFoldValue
    set(value) {
      if (field != value) {
        field = value
        cachedDeviceFaces = null
        if (isRollable) {
          invalidateFaceTexture()
        }
        repaint()
      }
    }

  var currentPosture: PostureValue? = postures.lastOrNull()?.posture
    set(value) {
      if (field != value) {
        field = value
        invalidateFaceTexture()
        repaint()
      }
    }

  var screenImage: BufferedImage? = null
    set(value) {
      if (field !== value) {
        field = value
        invalidateFaceTexture()
        repaint()
      }
    }

  private var prevMouseX: Int = 0
  private var prevMouseY: Int = 0
  private var prevDragOrigin: Vec3 = Vec3.ZERO
  private var cachedRotationDegrees: Vec3 = Vec3.ZERO
  private var cachedRotation: Quaternion = Quaternion.IDENTITY

  private val targetRotation: Quaternion
    get() {
      val deg = rotationDegrees
      if (deg != cachedRotationDegrees) {
        cachedRotationDegrees = deg
        cachedRotation = Quaternion.fromEulerAnglesDegrees(deg)
      }
      return cachedRotation
    }

  private var cachedFaceTexture: FaceTextureCache? = null
  private var cachedOuterFaceTexture: FaceTextureCache? = null
  private var patchBuffer: PatchBuffer? = null

  internal var preferredWidth: Int = 0

  init {
    val (baseHalfW, baseHalfH) = baseHalfExtents
    val polygon = extractPolygonFromPath(buildOutlinePath(baseHalfExtents))
    if (hasPostures && !isRollable) {
      val leftPoly = clipPolygonHalfPlane(polygon, splitX = hingeX, keepLeft = true)
      val rightPoly = clipPolygonHalfPlane(polygon, splitX = hingeX, keepLeft = false)
      baseLeftFaces =
        extrudePolygon(
          polygon = leftPoly,
          halfW = baseHalfW,
          halfH = baseHalfH,
          halfD = modelHalfDepth,
          leafAngleRad = 0f,
          hasOuterBackDisplay = true,
          leafCenterX = (hingeX - baseHalfW) * 0.5f,
          hingeX = hingeX,
        )
      baseRightFaces =
        extrudePolygon(
          polygon = rightPoly,
          halfW = baseHalfW,
          halfH = baseHalfH,
          halfD = modelHalfDepth,
          leafAngleRad = 0f,
          hasOuterBackDisplay = false,
          leafCenterX = (hingeX + baseHalfW) * 0.5f,
          hingeX = hingeX,
        )
      baseFaces = emptyList()
    } else {
      baseLeftFaces = emptyList()
      baseRightFaces = emptyList()
      baseFaces =
        extrudePolygon(
          polygon = polygon,
          halfW = baseHalfW,
          halfH = baseHalfH,
          halfD = modelHalfDepth,
          leafAngleRad = 0f,
          hasOuterBackDisplay = false,
          leafCenterX = 0f,
        )
    }

    name = "device3DWidget"
    isOpaque = true
    background = WIDGET_BACKGROUND
    border = LineBorder(JBColor.border(), 1)
    putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps.EMPTY)

    addMouseListener(
      object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          prevMouseX = e.x
          prevMouseY = e.y
          prevDragOrigin = screenToWorldCoordinate(e.x, e.y)
        }
      }
    )

    addMouseMotionListener(
      object : MouseMotionAdapter() {
        override fun mouseDragged(e: MouseEvent) {
          when (operationMode) {
            OperationMode.ROTATE -> {
              val deltaX = e.x - prevMouseX
              val deltaY = e.y - prevMouseY
              if (deltaX != 0 || deltaY != 0) {
                val rotX = Quaternion.fromAxisAngleDegrees(1f, 0f, 0f, deltaY.toFloat())
                val rotY = Quaternion.fromAxisAngleDegrees(0f, 1f, 0f, deltaX.toFloat())
                val newRotation = (rotX * rotY * targetRotation).normalized()
                val euler = newRotation.toEulerAnglesDegrees()
                cachedRotationDegrees = euler
                cachedRotation = newRotation
                prevMouseX = e.x
                prevMouseY = e.y
                onRotationChanged(euler)
              }
            }
            OperationMode.MOVE -> {
              val newDragOrigin = screenToWorldCoordinate(e.x, e.y)
              val delta = newDragOrigin - prevDragOrigin
              prevMouseX = e.x
              prevMouseY = e.y
              prevDragOrigin = newDragOrigin
              onPositionChanged(
                Vec3(
                  positionMeters.x + delta.x,
                  positionMeters.y + delta.y,
                  positionMeters.z,
                )
              )
            }
            OperationMode.FOLD -> {
              val deltaX = e.x - prevMouseX
              if (deltaX != 0) {
                prevMouseX = e.x
                prevMouseY = e.y
                val range = (maxFoldValue - minFoldValue).coerceAtLeast(1f)
                val sign = if (isRollable) 1f else -1f
                val deltaFold = sign * deltaX.toFloat() * range / width.coerceAtLeast(100)
                onFoldValueChanged(foldValue + deltaFold)
              }
            }
          }
        }
      }
    )

    addMouseWheelListener { e ->
      val deltaZ = (-e.preciseWheelRotation * 15.0 * METERS_PER_WHEEL_DEGREE).toFloat()
      if (deltaZ != 0f) {
        onPositionChanged(positionMeters.copy(z = positionMeters.z + deltaZ))
      }
    }
  }

  override fun getPreferredSize(): Dimension = Dimension(preferredWidth, preferredWidth)

  override fun getMinimumSize(): Dimension = preferredSize

  override fun getMaximumSize(): Dimension = preferredSize

  private fun invalidateFaceTexture() {
    cachedFaceTexture = null
    cachedOuterFaceTexture = null
  }

  /** Returns true when a foldable device is in the closed posture so the outer display is active instead of the inner display. */
  internal fun isOuterDisplayActive(): Boolean = hasPostures && !isRollable && currentPosture == PostureValue.POSTURE_CLOSED

  private fun computeBaseHalfExtents(): Pair<Float, Float> {
    if (skinOutline != null) {
      val aspect = skinOutline.frameWidth.toFloat() / skinOutline.frameHeight.coerceAtLeast(1).toFloat()
      val maxAspect = 1.25f
      return if (aspect <= maxAspect) {
        Pair(PHONE_HALF_HEIGHT * aspect, PHONE_HALF_HEIGHT)
      } else {
        val maxHalfW = PHONE_HALF_HEIGHT * maxAspect
        Pair(maxHalfW, maxHalfW / aspect)
      }
    }
    val halfW =
      when {
        hasPostures && isRollable -> PHONE_HALF_WIDTH * 1.3f
        hasPostures -> PHONE_HALF_WIDTH * 2.2f
        else -> PHONE_HALF_WIDTH
      }
    return Pair(halfW, PHONE_HALF_HEIGHT)
  }

  /** Computes the `(halfW, halfH)` 3D model extents in meters for the current posture/roll state. */
  internal fun computeModelHalfExtents(): Pair<Float, Float> {
    if (skinOutline == null && hasPostures && isRollable) {
      val ratio = (foldValue / 100f).coerceIn(0.4f, 1f)
      return Pair(PHONE_HALF_WIDTH * (0.7f + 0.6f * ratio), PHONE_HALF_HEIGHT)
    }
    return baseHalfExtents
  }

  /**
   * Builds the 2D outline [Path2D.Float] of the device in 3D model coordinates (`[-halfW, halfW] x [-halfH, halfH]`). When the AVD has a
   * skin, the outline is traced from the skin's runtime alpha silhouette; otherwise a generic frame with slightly rounded corners is used.
   */
  internal fun buildOutlinePath(halfExtents: Pair<Float, Float> = computeModelHalfExtents()): Path2D.Float {
    val (halfW, halfH) = halfExtents
    if (skinOutline != null) {
      val path = Path2D.Float()
      val verts = skinOutline.normalizedVertices
      if (verts.isNotEmpty()) {
        path.moveTo(verts[0].x * 2f * halfW, verts[0].y * 2f * halfH)
        for (i in 1 until verts.size) {
          path.lineTo(verts[i].x * 2f * halfW, verts[i].y * 2f * halfH)
        }
        path.closePath()
        return path
      }
    }
    val width = 2f * halfW
    val height = 2f * halfH
    val cornerRadius = min(width, height) * GENERIC_CORNER_RATIO
    val roundRect =
      RoundRectangle2D.Float(
        -halfW,
        -halfH,
        width,
        height,
        cornerRadius * 2f,
        cornerRadius * 2f,
      )
    return Path2D.Float(roundRect)
  }

  /**
   * Returns the composite front/inner-face texture combining [screenImage] (when active) with [skin] (or with a generic rounded bezel frame
   * when the AVD has no skin).
   */
  internal fun getFaceTexture(): BufferedImage = getFaceTextureCache().texture

  private fun getFaceTextureCache(): FaceTextureCache {
    val (halfW, halfH) = computeModelHalfExtents()
    val isScreenOn = !isOuterDisplayActive()
    val currentScreen = if (isScreenOn) screenImage else null
    val cached = cachedFaceTexture
    if (
      cached != null &&
        cached.screenImage === currentScreen &&
        cached.isScreenOn == isScreenOn &&
        abs(cached.halfW - halfW) < 1e-5f &&
        abs(cached.halfH - halfH) < 1e-5f
    ) {
      return cached
    }
    val texture =
      if (skin != null) {
        renderSkinnedFaceTexture(skin, currentScreen, isScreenOn)
      } else {
        renderGenericFaceTexture(halfW, halfH, currentScreen, isScreenOn)
      }
    return FaceTextureCache(currentScreen, isScreenOn, halfW, halfH, texture).also { cachedFaceTexture = it }
  }

  /** Returns the composite outer-display texture rendered on the back (`-Z`) face of the left leaf of a foldable device. */
  internal fun getOuterFaceTexture(): BufferedImage = getOuterFaceTextureCache().texture

  private fun getOuterFaceTextureCache(): FaceTextureCache {
    val (halfW, halfH) = baseHalfExtents
    val leafHalfW = halfW * 0.5f
    val isScreenOn = isOuterDisplayActive()
    val currentScreen = if (isScreenOn) screenImage else null
    val cached = cachedOuterFaceTexture
    if (
      cached != null &&
        cached.screenImage === currentScreen &&
        cached.isScreenOn == isScreenOn &&
        abs(cached.halfW - leafHalfW) < 1e-5f &&
        abs(cached.halfH - halfH) < 1e-5f
    ) {
      return cached
    }
    val texture =
      if (outerSkin != null) {
        renderSkinnedFaceTexture(outerSkin, currentScreen, isScreenOn)
      } else {
        renderGenericFaceTexture(leafHalfW, halfH, currentScreen, isScreenOn)
      }
    return FaceTextureCache(currentScreen, isScreenOn, leafHalfW, halfH, texture).also { cachedOuterFaceTexture = it }
  }

  private fun renderSkinnedFaceTexture(skin: SkinDefinition, screen: BufferedImage?, isScreenOn: Boolean): BufferedImage {
    val baseLayout = skin.layout
    val baseDisplay = baseLayout.displaySize
    val baseFrame = baseLayout.frameRectangle
    val maxDim = max(baseFrame.width, baseFrame.height).coerceAtLeast(1)
    val scale = (TEXTURE_MAX_DIMENSION.toFloat() / maxDim.toFloat()).coerceAtMost(1f)
    val texW = (baseFrame.width * scale).roundToInt().coerceAtLeast(1)
    val texH = (baseFrame.height * scale).roundToInt().coerceAtLeast(1)
    val displayRect = Rectangle(-baseFrame.x, -baseFrame.y, baseDisplay.width, baseDisplay.height)

    val image = BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.scale(
        texW.toDouble() / baseFrame.width.coerceAtLeast(1).toDouble(),
        texH.toDouble() / baseFrame.height.coerceAtLeast(1).toDouble(),
      )

      val oldClip = g.clip
      val cornerSize = baseLayout.displayCornerSize
      if (cornerSize.width > 0 || cornerSize.height > 0) {
        g.clip(
          RoundRectangle2D.Float(
            displayRect.x.toFloat(),
            displayRect.y.toFloat(),
            displayRect.width.toFloat(),
            displayRect.height.toFloat(),
            cornerSize.width.toFloat() * 2f,
            cornerSize.height.toFloat() * 2f,
          )
        )
      } else {
        g.clip(displayRect)
      }
      g.color = if (isScreenOn) DEVICE_SCREEN_COLOR else DEVICE_SCREEN_OFF_COLOR
      g.fillRect(displayRect.x, displayRect.y, displayRect.width, displayRect.height)
      if (isScreenOn && screen != null) {
        g.drawImage(screen, displayRect.x, displayRect.y, displayRect.width, displayRect.height, null)
      }
      g.clip = oldClip

      baseLayout.drawFrameAndMask(g, displayRect)
    } finally {
      g.dispose()
    }
    return image
  }

  private fun renderGenericFaceTexture(halfW: Float, halfH: Float, screen: BufferedImage?, isScreenOn: Boolean): BufferedImage {
    val maxHalf = max(halfW, halfH).coerceAtLeast(1e-4f)
    val texW = (TEXTURE_MAX_DIMENSION * (halfW / maxHalf)).roundToInt().coerceAtLeast(1)
    val texH = (TEXTURE_MAX_DIMENSION * (halfH / maxHalf)).roundToInt().coerceAtLeast(1)
    val image = BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

      val minDim = min(texW, texH).toFloat()
      val outerRadius = minDim * GENERIC_CORNER_RATIO
      val outerArc = outerRadius * 2f
      val outerRect = RoundRectangle2D.Float(0f, 0f, texW.toFloat(), texH.toFloat(), outerArc, outerArc)
      g.color = DEVICE_FRONT_COLOR
      g.fill(outerRect)

      val inset = minDim * GENERIC_BEZEL_RATIO
      val screenW = (texW - 2f * inset).coerceAtLeast(1f)
      val screenH = (texH - 2f * inset).coerceAtLeast(1f)
      val innerRadius = (outerRadius - inset).coerceAtLeast(0f)
      val innerArc = innerRadius * 2f
      val innerRect = RoundRectangle2D.Float(inset, inset, screenW, screenH, innerArc, innerArc)

      val oldClip = g.clip
      g.clip(innerRect)
      g.color = if (isScreenOn) DEVICE_SCREEN_COLOR else DEVICE_SCREEN_OFF_COLOR
      g.fill(innerRect)
      if (isScreenOn && screen != null) {
        g.drawImage(
          screen,
          inset.roundToInt(),
          inset.roundToInt(),
          screenW.roundToInt(),
          screenH.roundToInt(),
          null,
        )
      }
      g.clip = oldClip

      if (isScreenOn) {
        g.color = SCREEN_BORDER_COLOR
        g.stroke = BasicStroke(1.5f)
        g.draw(innerRect)
      }
    } finally {
      g.dispose()
    }
    return image
  }

  /**
   * Unprojects screen pixel `(x, y)` onto the plane `z = positionMeters.z` in world space, matching
   * `Device3DWidget::screenToWorldCoordinate` in `device-3d-widget.cpp`.
   */
  internal fun screenToWorldCoordinate(screenX: Int, screenY: Int): Vec3 {
    val w = width.coerceAtLeast(1).toFloat()
    val h = height.coerceAtLeast(1).toFloat()
    val aspect = w / h
    val fovRad = Math.toRadians(PERSPECTIVE_FOV_DEGREES.toDouble()).toFloat()
    val f = PRESENTATION_SCALE / tan(fovRad / 2f)

    val ndcX = (2f * screenX / w) - 1f
    val ndcY = 1f - (2f * screenY / h)

    // Camera is at (0, 0, CAMERA_DISTANCE) looking toward (0, 0, 0).
    // For a point at world z = positionMeters.z, distance from camera along -Z is (CAMERA_DISTANCE - positionMeters.z).
    val depth = CAMERA_DISTANCE - positionMeters.z
    val worldX = ndcX * depth * (aspect / f)
    val worldY = ndcY * depth * (1f / f)
    return Vec3(worldX, worldY, positionMeters.z)
  }

  private fun project(worldPoint: Vec3, width: Float, height: Float): ScreenPoint {
    val aspect = width / height
    val fovRad = Math.toRadians(PERSPECTIVE_FOV_DEGREES.toDouble()).toFloat()
    val f = PRESENTATION_SCALE / tan(fovRad / 2f)
    val depth = (CAMERA_DISTANCE - worldPoint.z).coerceAtLeast(NEAR_CLIP)
    val ndcX = (worldPoint.x / depth) * (f / aspect)
    val ndcY = (worldPoint.y / depth) * f
    val sx = (ndcX + 1f) * 0.5f * width
    val sy = (1f - ndcY) * 0.5f * height
    return ScreenPoint(sx, sy, depth)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
      val w = width.toFloat()
      val h = height.toFloat()
      if (w <= 0f || h <= 0f) return

      g2.color = background
      g2.fillRect(0, 0, width, height)

      val faces = getDeviceFaces()
      val centerOfGravity = computeCenterOfGravity()
      val faceTexture = getFaceTextureCache()
      val outerFaceTexture = if (hasPostures && !isRollable) getOuterFaceTextureCache() else null
      val cameraPos = Vec3(0f, 0f, CAMERA_DISTANCE)
      val projectedFaces = faces.map { face ->
        val worldVertices = face.vertices.map { v -> modelToWorld(v, centerOfGravity) }
        val screenVertices = worldVertices.map { project(it, w, h) }
        val minZ = worldVertices.minOf { it.z }
        val maxZ = worldVertices.maxOf { it.z }
        val avgDepth = CAMERA_DISTANCE - (minZ + maxZ) * 0.5f
        val leafCenterWorld = modelToWorld(face.leafCenter, centerOfGravity)
        val leafDepth = CAMERA_DISTANCE - leafCenterWorld.z
        val normalWorld = targetRotation.rotate(face.normal)
        val toCamera = cameraPos - worldVertices[0]
        val isFacingCamera = normalWorld.x * toCamera.x + normalWorld.y * toCamera.y + normalWorld.z * toCamera.z > 0f
        // Diffuse shading from camera direction (0, 0.2, 1)
        val lightIntensity = (0.65f + 0.35f * (normalWorld.z * 0.95f + normalWorld.y * 0.3f).coerceIn(-1f, 1f)).coerceIn(0.35f, 1f)
        ProjectedFace(
          face = face,
          vertices = screenVertices,
          leafDepth = leafDepth,
          avgDepth = avgDepth,
          color = shadeColor(face.color, lightIntensity),
          lightIntensity = lightIntensity,
          isFacingCamera = isFacingCamera,
        )
      }

      // Painter's algorithm: draw farthest leaf first, back-facing before front-facing within a leaf, then farthest face first.
      val sortedFaces =
        projectedFaces.sortedWith(
          compareByDescending<ProjectedFace> { it.leafDepth }.thenBy { it.isFacingCamera }.thenByDescending { it.avgDepth }
        )
      for (pf in sortedFaces) {
        if (pf.vertices.size < 3) continue
        val path = Path2D.Float()
        val first = pf.vertices.first()
        path.moveTo(first.x, first.y)
        for (i in 1 until pf.vertices.size) {
          path.lineTo(pf.vertices[i].x, pf.vertices[i].y)
        }
        path.closePath()
        g2.color = pf.color
        g2.fill(path)
        if (pf.isFacingCamera) {
          when (pf.face.texturedFaceType) {
            TexturedFaceType.FRONT_DISPLAY -> drawTexturedFace(g2, path, pf.face, pf.lightIntensity, faceTexture, centerOfGravity, w, h)
            TexturedFaceType.OUTER_BACK_DISPLAY ->
              if (outerFaceTexture != null) {
                drawTexturedFace(g2, path, pf.face, pf.lightIntensity, outerFaceTexture, centerOfGravity, w, h)
              }
            TexturedFaceType.NONE -> Unit
          }
        }
        if (pf.face.drawBorder) {
          val oldClip = g2.clip
          g2.clip(path)
          g2.color = BODY_EDGE_COLOR
          g2.stroke = BasicStroke(JBUIScale.scale(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
          g2.draw(path)
          g2.clip = oldClip
        }
      }
    } finally {
      g2.dispose()
    }
  }

  private fun drawTexturedFace(
    g2: Graphics2D,
    facePath: Path2D.Float,
    face: Face3D,
    lightIntensity: Float,
    textureCache: FaceTextureCache,
    centerOfGravity: Vec3,
    viewportW: Float,
    viewportH: Float,
  ) {
    val bounds = facePath.bounds
    val minX = bounds.x.coerceAtLeast(0)
    val minY = bounds.y.coerceAtLeast(0)
    val maxX = (bounds.x + bounds.width).coerceAtMost(width - 1)
    val maxY = (bounds.y + bounds.height).coerceAtMost(height - 1)
    val boxW = maxX - minX + 1
    val boxH = maxY - minY + 1
    if (boxW <= 0 || boxH <= 0) return

    val aspect = viewportW / viewportH
    val fovRad = Math.toRadians(PERSPECTIVE_FOV_DEGREES.toDouble()).toFloat()
    val f = PRESENTATION_SCALE / tan(fovRad / 2f)

    val qInv = targetRotation.conjugate()
    val cosLeaf = cos(-face.leafAngleRad)
    val sinLeaf = sin(-face.leafAngleRad)
    fun dirToLeaf(v: Vec3): Vec3 {
      val d = qInv.rotate(v)
      return Vec3(d.x * cosLeaf + d.z * sinLeaf, d.y, -d.x * sinLeaf + d.z * cosLeaf)
    }
    fun pointToLeaf(v: Vec3): Vec3 {
      val d = centerOfGravity + qInv.rotate(v - centerOfGravity)
      val dx = d.x - face.hingeX
      val dz = d.z - face.modelHalfD
      return Vec3(face.hingeX + dx * cosLeaf + dz * sinLeaf, d.y, face.modelHalfD - dx * sinLeaf + dz * cosLeaf)
    }

    val cameraInWorld = Vec3(0f, 0f, CAMERA_DISTANCE) - positionMeters
    val oLeaf = pointToLeaf(cameraInWorld)

    val ndcX0 = (2f * (minX + 0.5f) / viewportW) - 1f
    val ndcY0 = 1f - (2f * (minY + 0.5f) / viewportH)
    val d0 = dirToLeaf(Vec3(ndcX0 * (aspect / f), ndcY0 * (1f / f), -1f))
    val dDx = dirToLeaf(Vec3((2f / viewportW) * (aspect / f), 0f, 0f))
    val dDy = dirToLeaf(Vec3(0f, -(2f / viewportH) * (1f / f), 0f))

    val texW = textureCache.texture.width
    val texH = textureCache.texture.height
    val texPixels = textureCache.pixels

    val buffer = getOrCreatePatchBuffer(boxW, boxH)
    val patch = buffer.image
    val patchPixels = buffer.pixels
    val stride = patch.width
    val isOuterBack = face.texturedFaceType == TexturedFaceType.OUTER_BACK_DISPLAY
    val invWidth = 1f / (2f * face.modelHalfW).coerceAtLeast(1e-5f)
    val invLeafWidth = 1f / (face.hingeX + face.modelHalfW).coerceAtLeast(1e-5f)
    val invHeight = 1f / (2f * face.modelHalfH).coerceAtLeast(1e-5f)
    val planeZ = if (isOuterBack) -face.modelHalfD else face.modelHalfD
    val planeDeltaZ = planeZ - oLeaf.z

    val bgR = (DEVICE_FRONT_COLOR.red * lightIntensity).roundToInt().coerceIn(0, 255)
    val bgG = (DEVICE_FRONT_COLOR.green * lightIntensity).roundToInt().coerceIn(0, 255)
    val bgB = (DEVICE_FRONT_COLOR.blue * lightIntensity).roundToInt().coerceIn(0, 255)

    for (row in 0 until boxH) {
      var dx = d0.x + row * dDy.x
      var dy = d0.y + row * dDy.y
      var dz = d0.z + row * dDy.z
      val rowOffset = row * stride
      for (col in 0 until boxW) {
        var pixel = 0
        if (abs(dz) > 1e-6f) {
          val t = planeDeltaZ / dz
          if (t > 0f) {
            val xLeaf = oLeaf.x + t * dx
            val yLeaf = oLeaf.y + t * dy
            val u =
              if (isOuterBack) {
                ((face.hingeX - xLeaf) * invLeafWidth).coerceIn(0f, 0.9999f)
              } else {
                (xLeaf * invWidth + 0.5f).coerceIn(0f, 0.9999f)
              }
            val v = (0.5f - yLeaf * invHeight).coerceIn(0f, 0.9999f)
            val tx = (u * texW).toInt().coerceIn(0, texW - 1)
            val ty = (v * texH).toInt().coerceIn(0, texH - 1)
            val argb = texPixels[ty * texW + tx]
            val a = (argb ushr 24) and 0xFF
            if (a > 0) {
              val sr = (((argb ushr 16) and 0xFF) * lightIntensity).roundToInt().coerceIn(0, 255)
              val sg = (((argb ushr 8) and 0xFF) * lightIntensity).roundToInt().coerceIn(0, 255)
              val sb = ((argb and 0xFF) * lightIntensity).roundToInt().coerceIn(0, 255)
              val r = (sr * a + bgR * (255 - a)) / 255
              val g = (sg * a + bgG * (255 - a)) / 255
              val b = (sb * a + bgB * (255 - a)) / 255
              pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
          }
        }
        patchPixels[rowOffset + col] = pixel
        dx += dDx.x
        dy += dDx.y
        dz += dDx.z
      }
    }

    val oldClip = g2.clip
    g2.clip(facePath)
    g2.drawImage(patch, minX, minY, minX + boxW, minY + boxH, 0, 0, boxW, boxH, null)
    g2.clip = oldClip
  }

  private fun getOrCreatePatchBuffer(minW: Int, minH: Int): PatchBuffer {
    val current = patchBuffer
    if (current != null && current.image.width >= minW && current.image.height >= minH) {
      return current
    }
    val w = max(max(minW, width), current?.image?.width ?: 0)
    val h = max(max(minH, height), current?.image?.height ?: 0)
    return PatchBuffer(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)).also { patchBuffer = it }
  }

  /**
   * Returns the 3D mesh faces for the current fold/roll state, reusing the pre-extruded base mesh. For foldable devices, the pre-extruded
   * left leaf is rotated around `(X = hingeX, Z = +halfD)` by the hinge angle while the right leaf remains stationary. For rollable
   * devices, the pre-extruded mesh width is adjusted to match [computeModelHalfExtents].
   */
  internal fun getDeviceFaces(): List<Face3D> {
    cachedDeviceFaces?.let {
      return it
    }
    val faces =
      when {
        hasPostures && !isRollable -> {
          val foldAngleRad = Math.toRadians((180f - foldValue).coerceIn(0f, 180f).toDouble()).toFloat()
          if (abs(foldAngleRad) < 1e-6f) {
            baseLeftFaces + baseRightFaces
          } else {
            baseLeftFaces.map { it.rotateAroundHinge(foldAngleRad) } + baseRightFaces
          }
        }
        hasPostures && isRollable -> {
          val (halfW, _) = computeModelHalfExtents()
          baseFaces.map { it.resizeWidth(halfW) }
        }
        else -> baseFaces
      }
    cachedDeviceFaces = faces
    return faces
  }

  /**
   * Estimates the device's center of gravity in model coordinates by approximating both leaves of a foldable device as uniform rectangular
   * prisms of thickness `2 * halfD` and height `2 * halfH` split along `X = hingeX`.
   */
  internal fun computeCenterOfGravity(): Vec3 {
    if (!hasPostures || isRollable) return Vec3.ZERO
    val (halfW, _) = baseHalfExtents
    val halfD = modelHalfDepth
    val foldAngleRad = Math.toRadians((180f - foldValue).coerceIn(0f, 180f).toDouble()).toFloat()
    val c = cos(foldAngleRad)
    val s = sin(foldAngleRad)
    val leftWidth = (hingeX + halfW).coerceAtLeast(0f)
    val rightWidth = (halfW - hingeX).coerceAtLeast(0f)
    val totalWidth = (leftWidth + rightWidth).coerceAtLeast(1e-6f)
    val dx = -0.5f * leftWidth
    val dz = -halfD
    val leftCenterX = hingeX + dx * c + dz * s
    val leftCenterZ = halfD - dx * s + dz * c
    val rightCenterX = (hingeX + halfW) * 0.5f
    return Vec3(
      x = (leftWidth * leftCenterX + rightWidth * rightCenterX) / totalWidth,
      y = 0f,
      z = (leftWidth * leftCenterZ) / totalWidth,
    )
  }

  /** Transforms a point [v] in model space to world space by rotating around [centerOfGravity] and translating by [positionMeters]. */
  internal fun modelToWorld(v: Vec3, centerOfGravity: Vec3 = computeCenterOfGravity()): Vec3 =
    centerOfGravity + targetRotation.rotate(v - centerOfGravity) + positionMeters

  private fun extrudePolygon(
    polygon: List<Point2D.Float>,
    halfW: Float,
    halfH: Float,
    halfD: Float,
    leafAngleRad: Float,
    hasOuterBackDisplay: Boolean,
    leafCenterX: Float,
    hingeX: Float = 0f,
  ): List<Face3D> {
    if (polygon.size < 3) return emptyList()
    val ccwPoly = ensureCounterClockwise(polygon)
    val n = ccwPoly.size
    val faces = ArrayList<Face3D>(n + 2)
    val leafCenter = Vec3(leafCenterX, 0f, 0f)

    // Front face (+Z)
    val frontVertices = ccwPoly.map { Vec3(it.x, it.y, halfD) }
    faces.add(
      Face3D(
        vertices = frontVertices,
        normal = Vec3(0f, 0f, 1f),
        color = DEVICE_FRONT_COLOR,
        texturedFaceType = TexturedFaceType.FRONT_DISPLAY,
        drawBorder = true,
        leafAngleRad = leafAngleRad,
        leafCenter = leafCenter,
        modelHalfW = halfW,
        modelHalfH = halfH,
        modelHalfD = halfD,
        hingeX = hingeX,
      )
    )

    // Back face (-Z)
    val backVertices = ccwPoly.asReversed().map { Vec3(it.x, it.y, -halfD) }
    faces.add(
      Face3D(
        vertices = backVertices,
        normal = Vec3(0f, 0f, -1f),
        color = if (hasOuterBackDisplay) DEVICE_FRONT_COLOR else DEVICE_BACK_COLOR,
        texturedFaceType = if (hasOuterBackDisplay) TexturedFaceType.OUTER_BACK_DISPLAY else TexturedFaceType.NONE,
        drawBorder = true,
        leafAngleRad = leafAngleRad,
        leafCenter = leafCenter,
        modelHalfW = halfW,
        modelHalfH = halfH,
        modelHalfD = halfD,
        hingeX = hingeX,
      )
    )

    // Extruded side quads around the perimeter
    for (i in 0 until n) {
      val p0 = ccwPoly[i]
      val p1 = ccwPoly[(i + 1) % n]
      val dx = p1.x - p0.x
      val dy = p1.y - p0.y
      val segLen = hypot(dx.toDouble(), dy.toDouble()).toFloat()
      if (segLen < 1e-6f) continue
      // For a CCW polygon in (x, y), the outward normal of edge (p0 -> p1) is (dy / segLen, -dx / segLen, 0).
      val outwardNormal = Vec3(dy / segLen, -dx / segLen, 0f)
      val quad =
        listOf(
          Vec3(p0.x, p0.y, -halfD),
          Vec3(p1.x, p1.y, -halfD),
          Vec3(p1.x, p1.y, halfD),
          Vec3(p0.x, p0.y, halfD),
        )
      faces.add(
        Face3D(
          vertices = quad,
          normal = outwardNormal,
          color = DEVICE_SIDE_COLOR,
          texturedFaceType = TexturedFaceType.NONE,
          drawBorder = false,
          leafAngleRad = leafAngleRad,
          leafCenter = leafCenter,
          modelHalfW = halfW,
          modelHalfH = halfH,
          modelHalfD = halfD,
          hingeX = hingeX,
        )
      )
    }

    return faces
  }

  private class FaceTextureCache(
    val screenImage: BufferedImage?,
    val isScreenOn: Boolean,
    val halfW: Float,
    val halfH: Float,
    val texture: BufferedImage,
  ) {
    val pixels: IntArray = (texture.raster.dataBuffer as DataBufferInt).data
  }

  private class PatchBuffer(val image: BufferedImage) {
    val pixels: IntArray = (image.raster.dataBuffer as DataBufferInt).data
  }

  internal data class SkinOutline(
    val frameWidth: Int,
    val frameHeight: Int,
    /** Normalized outline vertices in `[-0.5, 0.5] x [-0.5, 0.5]`, where `+Y` is up. */
    val normalizedVertices: List<Point2D.Float>,
    /** Normalized X coordinate in `[-0.5, 0.5]` of the center line of the display opening in the skin. */
    val normalizedDisplayCenterX: Float = 0f,
  )

  private data class ScreenPoint(val x: Float, val y: Float, val depth: Float)

  internal enum class TexturedFaceType {
    NONE,
    FRONT_DISPLAY,
    OUTER_BACK_DISPLAY,
  }

  internal data class Face3D(
    val vertices: List<Vec3>,
    val normal: Vec3,
    val color: Color,
    val texturedFaceType: TexturedFaceType,
    val drawBorder: Boolean = true,
    val leafAngleRad: Float = 0f,
    val leafCenter: Vec3 = Vec3.ZERO,
    val modelHalfW: Float = PHONE_HALF_WIDTH,
    val modelHalfH: Float = PHONE_HALF_HEIGHT,
    val modelHalfD: Float = PHONE_HALF_DEPTH,
    val hingeX: Float = 0f,
  ) {
    val isFrontFace: Boolean
      get() = texturedFaceType == TexturedFaceType.FRONT_DISPLAY

    val isOuterBackFace: Boolean
      get() = texturedFaceType == TexturedFaceType.OUTER_BACK_DISPLAY

    fun rotateAroundHinge(angleRad: Float): Face3D {
      if (abs(angleRad) < 1e-6f) return this
      val c = cos(angleRad)
      val s = sin(angleRad)
      fun rotPoint(v: Vec3): Vec3 {
        val dx = v.x - hingeX
        val dz = v.z - modelHalfD
        return Vec3(hingeX + dx * c + dz * s, v.y, modelHalfD - dx * s + dz * c)
      }
      fun rotDir(v: Vec3): Vec3 = Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
      return copy(
        vertices = vertices.map(::rotPoint),
        normal = rotDir(normal),
        leafAngleRad = angleRad,
        leafCenter = rotPoint(leafCenter),
      )
    }

    fun resizeWidth(newHalfW: Float): Face3D {
      val delta = newHalfW - modelHalfW
      if (abs(delta) < 1e-6f) return this
      return copy(
        vertices = vertices.map { v -> Vec3(if (v.x >= 0f) v.x + delta else v.x - delta, v.y, v.z) },
        modelHalfW = newHalfW,
      )
    }
  }

  private data class ProjectedFace(
    val face: Face3D,
    val vertices: List<ScreenPoint>,
    val leafDepth: Float,
    val avgDepth: Float,
    val color: Color,
    val lightIntensity: Float,
    val isFacingCamera: Boolean,
  )

  /** Quaternion `(x, y, z, w)` with Euler conversion matching `GlmHelpers.h` (`fromEulerAnglesXYZ` and `toEulerAnglesXYZ`). */
  private data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Quaternion): Quaternion =
      Quaternion(
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w,
        w = w * o.w - x * o.x - y * o.y - z * o.z,
      )

    fun conjugate(): Quaternion = Quaternion(-x, -y, -z, w)

    fun normalized(): Quaternion {
      val norm = sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
      return if (norm > 1e-6f) Quaternion(x / norm, y / norm, z / norm, w / norm) else IDENTITY
    }

    fun rotate(v: Vec3): Vec3 {
      val tx = 2f * (y * v.z - z * v.y)
      val ty = 2f * (z * v.x - x * v.z)
      val tz = 2f * (x * v.y - y * v.x)
      return Vec3(
        v.x + w * tx + (y * tz - z * ty),
        v.y + w * ty + (z * tx - x * tz),
        v.z + w * tz + (x * ty - y * tx),
      )
    }

    fun toEulerAnglesDegrees(): Vec3 {
      val q = normalized()
      val xRad = atan2(2f * (q.x * q.w - q.y * q.z), q.w * q.w - q.x * q.x - q.y * q.y + q.z * q.z)
      val yRad = asin((2f * (q.x * q.z + q.y * q.w)).coerceIn(-1f, 1f))
      val zRad = atan2(2f * (q.z * q.w - q.x * q.y), q.w * q.w + q.x * q.x - q.y * q.y - q.z * q.z)
      return Vec3(
        Math.toDegrees(xRad.toDouble()).toFloat(),
        Math.toDegrees(yRad.toDouble()).toFloat(),
        Math.toDegrees(zRad.toDouble()).toFloat(),
      )
    }

    companion object {
      val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

      fun fromAxisAngleDegrees(ax: Float, ay: Float, az: Float, angleDeg: Float): Quaternion {
        val len = hypot(hypot(ax.toDouble(), ay.toDouble()), az.toDouble()).toFloat()
        if (len < 1e-6f) return IDENTITY
        val halfRad = Math.toRadians((angleDeg * 0.5f).toDouble()).toFloat()
        val s = sin(halfRad) / len
        val c = cos(halfRad)
        return Quaternion(ax * s, ay * s, az * s, c).normalized()
      }

      fun fromEulerAnglesDegrees(eulerDeg: Vec3): Quaternion {
        val qx = fromAxisAngleDegrees(1f, 0f, 0f, eulerDeg.x)
        val qy = fromAxisAngleDegrees(0f, 1f, 0f, eulerDeg.y)
        val qz = fromAxisAngleDegrees(0f, 0f, 1f, eulerDeg.z)
        return (qx * qy * qz).normalized()
      }
    }
  }

  companion object {
    private const val CAMERA_DISTANCE = 1.0f
    private const val NEAR_CLIP = 0.025f
    private const val PERSPECTIVE_FOV_DEGREES = 45f
    private const val PRESENTATION_SCALE = 1.2f
    private const val METERS_PER_WHEEL_DEGREE = 0.05f / 150f

    internal const val PHONE_HALF_WIDTH = 0.075f
    internal const val PHONE_HALF_HEIGHT = 0.15f
    internal const val PHONE_HALF_DEPTH = 0.0085f
    internal const val WEAR_HALF_DEPTH = 0.02f

    private const val GENERIC_CORNER_RATIO = 0.10f
    private const val GENERIC_BEZEL_RATIO = 0.06f
    private const val OUTLINE_MAX_DIMENSION = 320
    private const val TEXTURE_MAX_DIMENSION = 360

    private val WIDGET_BACKGROUND = JBColor(0xF2F4F7, 0x2B2D30)
    private val DEVICE_FRONT_COLOR = Color(0x3A, 0x3F, 0x45)
    private val DEVICE_SCREEN_COLOR = Color(0x52, 0x8B, 0xFF)
    private val DEVICE_SCREEN_OFF_COLOR = Color(0x1E, 0x21, 0x26)
    private val DEVICE_BACK_COLOR = Color(0x60, 0x67, 0x70)
    private val DEVICE_SIDE_COLOR = Color(0x4C, 0x52, 0x5A)
    private val SCREEN_BORDER_COLOR = Color(0x90, 0xB4, 0xFF)
    private val BODY_EDGE_COLOR = Color(0x25, 0x28, 0x2C)

    /**
     * Analyzes the runtime silhouette of [skin] and extracts a simplified outer contour polygon with coordinates normalized to `[-0.5, 0.5]
     * x [-0.5, 0.5]` (`+Y` pointing up).
     */
    internal fun analyzeSkinOutline(skin: SkinDefinition): SkinOutline {
      val baseLayout = skin.layout
      val baseDisplay = baseLayout.displaySize
      val baseFrame = baseLayout.frameRectangle
      val normalizedDisplayCenterX =
        if (baseFrame.width > 0) {
          ((-baseFrame.x + baseDisplay.width * 0.5f) / baseFrame.width.toFloat()) - 0.5f
        } else {
          0f
        }
      val maxDim = max(baseFrame.width, baseFrame.height).coerceAtLeast(1)
      val scale = (OUTLINE_MAX_DIMENSION.toFloat() / maxDim.toFloat()).coerceAtMost(1f)
      val w = (baseFrame.width * scale).roundToInt().coerceAtLeast(1)
      val h = (baseFrame.height * scale).roundToInt().coerceAtLeast(1)
      val displayRect = Rectangle(-baseFrame.x, -baseFrame.y, baseDisplay.width, baseDisplay.height)

      val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
      val g = image.createGraphics()
      try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.scale(
          w.toDouble() / baseFrame.width.coerceAtLeast(1).toDouble(),
          h.toDouble() / baseFrame.height.coerceAtLeast(1).toDouble(),
        )
        g.color = Color.BLACK
        val corner = baseLayout.displayCornerSize
        val insetX = (displayRect.width * 0.08f).coerceAtLeast(corner.width.toFloat())
        val insetY = (displayRect.height * 0.08f).coerceAtLeast(corner.height.toFloat())
        g.fill(
          RoundRectangle2D.Float(
            displayRect.x + insetX,
            displayRect.y + insetY,
            (displayRect.width - 2f * insetX).coerceAtLeast(1f),
            (displayRect.height - 2f * insetY).coerceAtLeast(1f),
            corner.width * 2f,
            corner.height * 2f,
          )
        )
        baseLayout.drawFrameAndMask(g, displayRect)
      } finally {
        g.dispose()
      }

      val pixels = IntArray(w * h)
      image.getRGB(0, 0, w, h, pixels, 0, w)

      // Flood-fill transparent exterior on a (w + 2) x (h + 2) padded grid.
      val pw = w + 2
      val ph = h + 2
      val exterior = BooleanArray(pw * ph)
      val queue = IntArray(pw * ph)
      var head = 0
      var tail = 0
      exterior[0] = true
      queue[tail++] = 0

      val dxs = intArrayOf(1, -1, 0, 0)
      val dys = intArrayOf(0, 0, 1, -1)

      while (head < tail) {
        val curr = queue[head++]
        val cx = curr % pw
        val cy = curr / pw
        for (dir in 0 until 4) {
          val nx = cx + dxs[dir]
          val ny = cy + dys[dir]
          if (nx in 0 until pw && ny in 0 until ph) {
            val nIdx = ny * pw + nx
            if (!exterior[nIdx]) {
              val isOpaque =
                if (nx in 1..w && ny in 1..h) {
                  val alpha = (pixels[(ny - 1) * w + (nx - 1)] ushr 24) and 0xFF
                  alpha >= 64
                } else {
                  false
                }
              if (!isOpaque) {
                exterior[nIdx] = true
                queue[tail++] = nIdx
              }
            }
          }
        }
      }

      // Keep the 4-connected interior component containing the center of the frame.
      val inside = BooleanArray(w * h)
      val startX = (w / 2).coerceIn(0, w - 1)
      val startY = (h / 2).coerceIn(0, h - 1)
      head = 0
      tail = 0
      val startIdx = startY * w + startX
      inside[startIdx] = true
      queue[tail++] = startIdx
      while (head < tail) {
        val curr = queue[head++]
        val cx = curr % w
        val cy = curr / w
        for (dir in 0 until 4) {
          val nx = cx + dxs[dir]
          val ny = cy + dys[dir]
          if (nx in 0 until w && ny in 0 until h) {
            val nIdx = ny * w + nx
            if (!inside[nIdx] && !exterior[(ny + 1) * pw + (nx + 1)]) {
              inside[nIdx] = true
              queue[tail++] = nIdx
            }
          }
        }
      }

      val contour = traceBoundaryContour(inside, w, h)
      if (contour.size < 3) {
        return SkinOutline(
          frameWidth = baseFrame.width,
          frameHeight = baseFrame.height,
          normalizedVertices =
            listOf(
              Point2D.Float(-0.5f, -0.5f),
              Point2D.Float(0.5f, -0.5f),
              Point2D.Float(0.5f, 0.5f),
              Point2D.Float(-0.5f, 0.5f),
            ),
          normalizedDisplayCenterX = normalizedDisplayCenterX,
        )
      }

      val smoothed = smoothClosedContour(contour, passes = 2)
      val simplified = simplifyClosedContourRdp(smoothed, epsilon = 0.75f)
      val normalized = simplified.map { pt ->
        Point2D.Float(
          (pt.x / w.toFloat()) - 0.5f,
          0.5f - (pt.y / h.toFloat()),
        )
      }
      return SkinOutline(
        frameWidth = baseFrame.width,
        frameHeight = baseFrame.height,
        normalizedVertices = ensureCounterClockwise(normalized),
        normalizedDisplayCenterX = normalizedDisplayCenterX,
      )
    }

    /** Traces the outer boundary of [inside] (`w x h` grid) on the vertex lattice `(0..w) x (0..h)`. */
    private fun traceBoundaryContour(inside: BooleanArray, w: Int, h: Int): List<Point2D.Float> {
      fun isInside(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && inside[y * w + x]

      var startVx = -1
      var startVy = -1
      outer@ for (y in 0 until h) {
        for (x in 0 until w) {
          if (isInside(x, y) && !isInside(x, y - 1)) {
            startVx = x
            startVy = y
            break@outer
          }
        }
      }
      if (startVx < 0) return emptyList()

      // Directions on vertex grid: 0 = East (+x), 1 = South (+y), 2 = West (-x), 3 = North (-y).
      val vxStep = intArrayOf(1, 0, -1, 0)
      val vyStep = intArrayOf(0, 1, 0, -1)
      // Right-hand (interior) pixel offset relative to directed edge starting at (vx, vy):
      val rightPxOffset = intArrayOf(0, -1, -1, 0)
      val rightPyOffset = intArrayOf(0, 0, -1, -1)
      // Left-hand (exterior) pixel offset relative to directed edge starting at (vx, vy):
      val leftPxOffset = intArrayOf(0, 0, -1, -1)
      val leftPyOffset = intArrayOf(-1, 0, 0, -1)

      fun isBoundaryEdge(vx: Int, vy: Int, dir: Int): Boolean {
        val rInside = isInside(vx + rightPxOffset[dir], vy + rightPyOffset[dir])
        val lInside = isInside(vx + leftPxOffset[dir], vy + leftPyOffset[dir])
        return rInside && !lInside
      }

      val points = ArrayList<Point2D.Float>()
      var vx = startVx
      var vy = startVy
      var dir = 0
      val maxSteps = (w + h) * 8

      for (step in 0 until maxSteps) {
        points.add(Point2D.Float(vx.toFloat(), vy.toFloat()))
        vx += vxStep[dir]
        vy += vyStep[dir]
        if (vx == startVx && vy == startVy) break

        // Turn priority: left (dir + 3), straight (dir), right (dir + 1).
        val nextDir =
          intArrayOf((dir + 3) and 3, dir, (dir + 1) and 3).firstOrNull { cand ->
            isBoundaryEdge(vx, vy, cand)
          } ?: break
        dir = nextDir
      }

      return points
    }

    private fun smoothClosedContour(points: List<Point2D.Float>, passes: Int): List<Point2D.Float> {
      var curr = points
      val n = curr.size
      if (n < 4) return curr
      repeat(passes) {
        val next = ArrayList<Point2D.Float>(n)
        for (i in 0 until n) {
          val prev = curr[(i - 1 + n) % n]
          val p = curr[i]
          val nxt = curr[(i + 1) % n]
          next.add(Point2D.Float(0.25f * prev.x + 0.5f * p.x + 0.25f * nxt.x, 0.25f * prev.y + 0.5f * p.y + 0.25f * nxt.y))
        }
        curr = next
      }
      return curr
    }

    private fun simplifyClosedContourRdp(points: List<Point2D.Float>, epsilon: Float): List<Point2D.Float> {
      if (points.size <= 4) return points
      var minIdx = 0
      var maxIdx = 0
      for (i in 1 until points.size) {
        if (points[i].y < points[minIdx].y || (points[i].y == points[minIdx].y && points[i].x < points[minIdx].x)) {
          minIdx = i
        }
        if (points[i].y > points[maxIdx].y || (points[i].y == points[maxIdx].y && points[i].x > points[maxIdx].x)) {
          maxIdx = i
        }
      }
      if (minIdx == maxIdx) return points

      fun sliceRing(start: Int, end: Int): List<Point2D.Float> {
        val result = ArrayList<Point2D.Float>()
        var idx = start
        while (true) {
          result.add(points[idx])
          if (idx == end) break
          idx = (idx + 1) % points.size
        }
        return result
      }

      val half1 = simplifyPolylineRdp(sliceRing(minIdx, maxIdx), epsilon)
      val half2 = simplifyPolylineRdp(sliceRing(maxIdx, minIdx), epsilon)
      val combined = ArrayList<Point2D.Float>(half1.size + half2.size - 2)
      combined.addAll(half1.dropLast(1))
      combined.addAll(half2.dropLast(1))
      return combined
    }

    private fun simplifyPolylineRdp(pts: List<Point2D.Float>, epsilon: Float): List<Point2D.Float> {
      if (pts.size <= 2) return pts
      val first = pts.first()
      val last = pts.last()
      var maxDist = -1f
      var index = 0
      for (i in 1 until pts.size - 1) {
        val d = pointToSegmentDistance(pts[i], first, last)
        if (d > maxDist) {
          maxDist = d
          index = i
        }
      }
      return if (maxDist > epsilon) {
        val left = simplifyPolylineRdp(pts.subList(0, index + 1), epsilon)
        val right = simplifyPolylineRdp(pts.subList(index, pts.size), epsilon)
        left.dropLast(1) + right
      } else {
        listOf(first, last)
      }
    }

    private fun pointToSegmentDistance(p: Point2D.Float, a: Point2D.Float, b: Point2D.Float): Float {
      val dx = b.x - a.x
      val dy = b.y - a.y
      val lenSq = dx * dx + dy * dy
      if (lenSq < 1e-8f) return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
      val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
      val projX = a.x + t * dx
      val projY = a.y + t * dy
      return hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
    }

    private fun extractPolygonFromPath(path: Path2D.Float): List<Point2D.Float> {
      val pts = ArrayList<Point2D.Float>()
      val coords = FloatArray(6)
      val iter = path.getPathIterator(null, 0.0001)
      while (!iter.isDone) {
        when (iter.currentSegment(coords)) {
          PathIterator.SEG_MOVETO,
          PathIterator.SEG_LINETO -> {
            val pt = Point2D.Float(coords[0], coords[1])
            if (pts.isEmpty() || hypot((pt.x - pts.last().x).toDouble(), (pt.y - pts.last().y).toDouble()) > 1e-5) {
              pts.add(pt)
            }
          }
        }
        iter.next()
      }
      if (pts.size >= 2 && hypot((pts.first().x - pts.last().x).toDouble(), (pts.first().y - pts.last().y).toDouble()) <= 1e-5) {
        pts.removeAt(pts.lastIndex)
      }
      return pts
    }

    private fun ensureCounterClockwise(pts: List<Point2D.Float>): List<Point2D.Float> {
      if (pts.size < 3) return pts
      var signedArea2 = 0f
      for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        signedArea2 += a.x * b.y - b.x * a.y
      }
      return if (signedArea2 >= 0f) pts else pts.asReversed()
    }

    /** Clips [polygon] against the vertical hinge line `x = splitX` using Sutherland-Hodgman clipping. */
    private fun clipPolygonHalfPlane(
      polygon: List<Point2D.Float>,
      splitX: Float,
      keepLeft: Boolean,
    ): List<Point2D.Float> {
      if (polygon.isEmpty()) return emptyList()
      fun inside(p: Point2D.Float): Boolean = if (keepLeft) p.x <= splitX + 1e-6f else p.x >= splitX - 1e-6f

      val output = ArrayList<Point2D.Float>(polygon.size + 2)
      var s = polygon.last()
      for (e in polygon) {
        val sIn = inside(s)
        val eIn = inside(e)
        if (eIn) {
          if (!sIn) {
            val dx = e.x - s.x
            val t = if (abs(dx) > 1e-7f) ((splitX - s.x) / dx).coerceIn(0f, 1f) else 0.5f
            output.add(Point2D.Float(splitX, s.y + t * (e.y - s.y)))
          }
          output.add(if (abs(e.x - splitX) <= 1e-6f) Point2D.Float(splitX, e.y) else e)
        } else if (sIn) {
          val dx = e.x - s.x
          val t = if (abs(dx) > 1e-7f) ((splitX - s.x) / dx).coerceIn(0f, 1f) else 0.5f
          output.add(Point2D.Float(splitX, s.y + t * (e.y - s.y)))
        }
        s = e
      }
      return output
    }

    private fun shadeColor(color: Color, factor: Float): Color {
      val r = (color.red * factor).roundToInt().coerceIn(0, 255)
      val g = (color.green * factor).roundToInt().coerceIn(0, 255)
      val b = (color.blue * factor).roundToInt().coerceIn(0, 255)
      return Color(r, g, b)
    }
  }
}

/** 3-component vector used for Euler angles, 3D coordinates, and sensor values. */
internal data class Vec3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

  operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

  companion object {
    val ZERO = Vec3(0f, 0f, 0f)
  }
}
