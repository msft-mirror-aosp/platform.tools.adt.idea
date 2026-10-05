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
package com.android.tools.idea.streaming.emulator

import com.android.adblib.DeviceSelector
import com.android.adblib.shellAsLines
import com.android.emulator.control.KeyboardEvent.KeyEventType
import com.android.tools.adtui.common.primaryPanelBackground
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.concurrency.createCoroutineScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Panel simulating the directional and navigation section of an Android TV remote control. */
internal class TvRemotePanel(
  private val emulator: EmulatorController,
  private val project: Project,
  disposableParent: Disposable,
) : JPanel(null), Disposable {

  private val coroutineScope = createCoroutineScope()
  private val buttons = mutableListOf<RemoteButton>()

  init {
    Disposer.register(disposableParent, this)
    background = primaryPanelBackground
    border = JBUI.Borders.compound(IdeBorderFactory.createBorder(JBColor.border(), SideBorder.TOP), JBUI.Borders.empty(10))

    addCommandButton(
      tooltip = "Watchlist",
      refButtonShape = createCircle(-80.0, -128.0, SMALL_BUTTON_RADIUS),
      icon = StudioIcons.Emulator.TV.WATCHLIST,
      command = "input keyevent KEYCODE_BOOKMARK",
    )
    addCommandButton(
      tooltip = "Toggle Dashboard",
      refButtonShape = createCircle(0.0, -152.0, SMALL_BUTTON_RADIUS),
      icon = StudioIcons.Emulator.TV.DASHBOARD,
      command = "input keyevent KEYCODE_NOTIFICATION",
    )
    addCommandButton(
      tooltip = "Open Settings",
      refButtonShape = createCircle(80.0, -128.0, SMALL_BUTTON_RADIUS),
      icon = StudioIcons.Emulator.TV.SETTINGS,
      command = "am start -n com.android.tv.settings/com.android.tv.settings.MainSettings",
    )

    addKeyButton(
      tooltip = "Up",
      refButtonShape = createDpadSector(-PI / 2),
      icon = StudioIcons.Emulator.TV.CHEVRON_UP,
      keyName = "ArrowUp",
      refIconCenter = createDpadIconCenter(-PI / 2),
    )
    addKeyButton(
      tooltip = "Left",
      refButtonShape = createDpadSector(PI),
      icon = StudioIcons.Emulator.TV.CHEVRON_LEFT,
      keyName = "ArrowLeft",
      refIconCenter = createDpadIconCenter(PI),
    )
    addKeyButton(
      tooltip = "Select",
      refButtonShape = createCircle(0.0, 0.0, SELECT_BUTTON_RADIUS),
      icon = null,
      keyName = "Enter",
    )
    addKeyButton(
      tooltip = "Right",
      refButtonShape = createDpadSector(0.0),
      icon = StudioIcons.Emulator.TV.CHEVRON_RIGHT,
      keyName = "ArrowRight",
      refIconCenter = createDpadIconCenter(0.0),
    )
    addKeyButton(
      tooltip = "Down",
      refButtonShape = createDpadSector(PI / 2),
      icon = StudioIcons.Emulator.TV.CHEVRON_DOWN,
      keyName = "ArrowDown",
      refIconCenter = createDpadIconCenter(PI / 2),
    )

    addKeyButton(
      tooltip = "Back",
      refButtonShape = createCircle(-80.0, 128.0, SMALL_BUTTON_RADIUS),
      icon = StudioIcons.Emulator.TV.BACK,
      keyName = "GoBack",
    )
    addKeyButton(
      tooltip = "Home",
      refButtonShape = createCircle(0.0, 152.0, SMALL_BUTTON_RADIUS),
      icon = StudioIcons.Emulator.TV.HOME,
      keyName = "GoHome",
    )
    val liveChannelsCommand =
      if (emulator.emulatorConfig.api < 34) {
        "am start -n com.google.android.tv/com.android.tv.MainActivity"
      } else {
        "am start -n com.android.tv/com.android.tv.MainActivity"
      }
    addCommandButton(
      tooltip = "Open Live Channels",
      refButtonShape = createCircle(80.0, 128.0, SMALL_BUTTON_RADIUS),
      icon = StudioIcons.Emulator.TV.LIVE_CHANNELS,
      command = liveChannelsCommand,
    )
  }

  private fun addKeyButton(
    tooltip: String,
    refButtonShape: Shape,
    icon: Icon?,
    keyName: String,
    refIconCenter: Point2D = Point2D.Double(refButtonShape.bounds2D.centerX, refButtonShape.bounds2D.centerY),
  ) {
    val button =
      RemoteButton(tooltip, refButtonShape, icon, refIconCenter).apply {
        addMouseListener(
          object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
              if (SwingUtilities.isLeftMouseButton(event)) {
                emulator.sendKeyEvent(keyName, eventType = KeyEventType.keydown)
              }
            }

            override fun mouseReleased(event: MouseEvent) {
              if (SwingUtilities.isLeftMouseButton(event)) {
                emulator.sendKeyEvent(keyName, eventType = KeyEventType.keyup)
              }
            }
          }
        )
        addKeyListener(
          object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
              if (event.modifiersEx == 0 && event.keyCode == KeyEvent.VK_SPACE) {
                emulator.sendKeyEvent(keyName, eventType = KeyEventType.keydown)
              }
            }

            override fun keyReleased(event: KeyEvent) {
              if (event.modifiersEx == 0 && event.keyCode == KeyEvent.VK_SPACE) {
                emulator.sendKeyEvent(keyName, eventType = KeyEventType.keyup)
              }
            }
          }
        )
      }
    buttons.add(button)
    add(button)
  }

  private fun addCommandButton(tooltip: String, refButtonShape: Shape, icon: Icon, command: String) {
    val button =
      RemoteButton(tooltip, refButtonShape, icon).apply {
        addActionListener { executeShellCommand(command) }
      }
    buttons.add(button)
    add(button)
  }

  private fun executeShellCommand(command: String) {
    coroutineScope.launch {
      try {
        val adb = AdbLibService.getSession(project).deviceServices
        adb.shellAsLines(DeviceSelector.fromSerialNumber(emulator.emulatorId.serialNumber), command).collect()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        thisLogger().warn("Error executing shell command: $command", e)
      }
    }
  }

  override fun getPreferredSize(): Dimension {
    val scale = JBUI.scale(1f) * DEFAULT_SCALE
    val insets = insets
    val width = (REF_WIDTH * scale).roundToInt() + insets.left + insets.right
    val height = (REF_HEIGHT * scale).roundToInt() + insets.top + insets.bottom
    return Dimension(width, height)
  }

  override fun doLayout() {
    val insets = insets
    val availWidth = (width - insets.left - insets.right).coerceAtLeast(0)
    val availHeight = (height - insets.top - insets.bottom).coerceAtLeast(0)
    val centerX = insets.left + availWidth / 2.0
    val centerY = insets.top + availHeight / 2.0
    val maxScale = JBUI.scale(1f) * DEFAULT_SCALE
    val scale = minOf(maxScale, availWidth / REF_WIDTH, availHeight / REF_HEIGHT).coerceAtLeast(0.1)
    for (button in buttons) {
      button.updateLayout(centerX, centerY, scale, maxScale)
    }
  }

  override fun dispose() {}

  private class RemoteButton(
    tooltip: String,
    private val refButtonShape: Shape,
    private val baseIcon: Icon?,
    private val refIconCenter: Point2D = Point2D.Double(refButtonShape.bounds2D.centerX, refButtonShape.bounds2D.centerY),
  ) : JButton() {

    private var localButtonShape: Shape = refButtonShape
    private var iconCenterX: Double = 0.0
    private var iconCenterY: Double = 0.0
    private var scaledIcon: Icon? = baseIcon

    init {
      name = tooltip
      toolTipText = tooltip
      isOpaque = false
      isContentAreaFilled = false
      isBorderPainted = false
      isFocusPainted = false
      isFocusable = false
    }

    fun updateLayout(centerX: Double, centerY: Double, scale: Double, maxScale: Double) {
      val toPanel = AffineTransform.getTranslateInstance(centerX, centerY).apply { scale(scale, scale) }
      val panelButtonShape = toPanel.createTransformedShape(refButtonShape)
      val bounds = panelButtonShape.bounds
      setBounds(bounds)
      val toLocal = AffineTransform.getTranslateInstance(-bounds.x.toDouble(), -bounds.y.toDouble())
      localButtonShape = toLocal.createTransformedShape(panelButtonShape)
      val panelIconCenter = toPanel.transform(refIconCenter, null)
      iconCenterX = panelIconCenter.x - bounds.x
      iconCenterY = panelIconCenter.y - bounds.y
      val iconScale = (scale / maxScale).toFloat()
      scaledIcon = if (iconScale < 0.99f) baseIcon?.let { IconUtil.scale(it, this, iconScale) } else baseIcon
    }

    override fun contains(x: Int, y: Int): Boolean = localButtonShape.contains(x.toDouble(), y.toDouble())

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.color = if (model.isPressed) BUTTON_PRESSED_COLOR else BUTTON_NORMAL_COLOR
        g2.fill(localButtonShape)
        scaledIcon?.let { icon ->
          val x = (iconCenterX - icon.iconWidth / 2.0).roundToInt()
          val y = (iconCenterY - icon.iconHeight / 2.0).roundToInt()
          icon.paintIcon(this, g2, x, y)
        }
      } finally {
        g2.dispose()
      }
    }
  }
}

private const val REF_WIDTH = 208.0
private const val REF_HEIGHT = 352.0
private const val DEFAULT_SCALE = 0.5
private const val SMALL_BUTTON_RADIUS = 24.0
private const val SELECT_BUTTON_RADIUS = 40.0
private const val DPAD_INNER_RADIUS = 48.0
private const val DPAD_OUTER_RADIUS = 101.0
private const val DPAD_GAP_HALF_WIDTH = 4.0
private const val DPAD_CHEVRON_RADIUS = 74.5

private val BUTTON_NORMAL_COLOR = JBColor(0xE0E0E0, 0x546E7A)
private val BUTTON_PRESSED_COLOR = JBColor(0xC7C7C7, 0x3D5059)

private fun createCircle(centerX: Double, centerY: Double, radius: Double): Shape =
  Ellipse2D.Double(centerX - radius, centerY - radius, radius * 2, radius * 2)

private fun createDpadSector(angleRadians: Double): Shape {
  val ring = Area(createCircle(0.0, 0.0, DPAD_OUTER_RADIUS))
  ring.subtract(Area(createCircle(0.0, 0.0, DPAD_INNER_RADIUS)))

  val apexX = DPAD_GAP_HALF_WIDTH * sqrt(2.0)
  val reach = DPAD_OUTER_RADIUS * 2.0
  val wedge =
    Path2D.Double().apply {
      moveTo(apexX, 0.0)
      lineTo(apexX + reach, -reach)
      lineTo(apexX + reach, reach)
      closePath()
    }
  val rotatedWedge = Area(AffineTransform.getRotateInstance(angleRadians).createTransformedShape(wedge))
  ring.intersect(rotatedWedge)
  return ring
}

private fun createDpadIconCenter(angleRadians: Double): Point2D =
  Point2D.Double(DPAD_CHEVRON_RADIUS * cos(angleRadians), DPAD_CHEVRON_RADIUS * sin(angleRadians))
