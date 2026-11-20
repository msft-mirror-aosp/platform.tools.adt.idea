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

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.shellAsLines
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.EdtAnyModality
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionState
import com.android.utils.throwIfCancellation
import com.intellij.icons.AllIcons
import com.intellij.ide.setToolTipText
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.concurrent.atomic.AtomicInteger
import javax.accessibility.AccessibleContext
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog simulating an Android Automotive rotary controller, providing functionality similar to the Car rotary page in extended controls.
 */
internal class CarRotaryDialog(
  emulator: EmulatorController,
  private val serviceCheckInterval: Duration,
) : AbstractExtendedControlsDialog(emulator, message("car.rotary.dialog.title", emulator.emulatorId.avdName)) {

  constructor(emulator: EmulatorController) : this(emulator, SERVICE_CHECK_INTERVAL)

  private var longPressIntervalMs: Int =
    (emulator.getUserData(LONG_PRESS_INTERVAL_KEY) ?: DEFAULT_LONG_PRESS_INTERVAL_MS).coerceIn(
      MIN_LONG_PRESS_INTERVAL_MS,
      MAX_LONG_PRESS_INTERVAL_MS,
    )
    set(value) {
      field = value
      emulator.putUserData(LONG_PRESS_INTERVAL_KEY, value)
    }

  private var warningVisible: Boolean = false

  private lateinit var dialogPanel: DialogPanel
  private lateinit var warningRow: Row
  private lateinit var controllerPanel: CarRotaryControllerPanel

  private val pendingRepeatCommands = AtomicInteger(0)
  private val commandChannel = Channel<AdbCommand>(Channel.UNLIMITED)
  private var serviceCheckJob: Job? = null

  private val adbDeviceServices: AdbDeviceServices
    get() = (project?.let(AdbLibService::getSession) ?: AdbLibApplicationService.instance.session).deviceServices

  private val deviceSelector: DeviceSelector = DeviceSelector.fromSerialNumber(emulator.emulatorId.serialNumber)

  init {
    coroutineScope.launch {
      for (item in commandChannel) {
        try {
          executeShellCommand(item.command)
        } finally {
          if (item.isRepeat) {
            pendingRepeatCommands.decrementAndGet()
          }
        }
      }
    }

    onDispose {
      serviceCheckJob?.cancel()
      serviceCheckJob = null
      if (::controllerPanel.isInitialized) {
        controllerPanel.releasePressedButtons(sendRelease = emulator.connectionState == ConnectionState.CONNECTED)
      }
      commandChannel.close()
    }
  }

  override fun createPanel(): DialogPanel {
    controllerPanel = CarRotaryControllerPanel()
    dialogPanel = panel {
      warningRow =
        row {
            icon(AllIcons.General.Warning).align(AlignY.TOP)
            label(message("car.rotary.dialog.warning")).applyToComponent {
              name = "warningLabel"
            }
          }
          .visible(false)

      row {
        cell(controllerPanel).align(Align.FILL)
      }
        .resizableRow()

      row(message("car.rotary.dialog.label.long.press.interval")) {
          intTextField(MIN_LONG_PRESS_INTERVAL_MS..MAX_LONG_PRESS_INTERVAL_MS, keyboardStep = LONG_PRESS_INTERVAL_STEP_MS)
            .bindIntText(::longPressIntervalMs)
            .applyToComponent {
              name = "longPressIntervalField"
              document.addDocumentListener(
                object : DocumentAdapter() {
                  override fun textChanged(event: DocumentEvent) {
                    val parsed = text.toIntOrNull()
                    if (parsed != null && parsed in MIN_LONG_PRESS_INTERVAL_MS..MAX_LONG_PRESS_INTERVAL_MS) {
                      longPressIntervalMs = parsed
                    }
                  }
                }
              )
            }
        }
        .topGap(TopGap.SMALL)
    }

    startCarRotaryServiceCheck()
    return dialogPanel
  }

  private fun startCarRotaryServiceCheck() {
    serviceCheckJob?.cancel()
    serviceCheckJob = coroutineScope.launch {
      while (true) {
        if (checkCarRotaryService()) {
          break
        }
        delay(serviceCheckInterval)
      }
    }
  }

  /**
   * Checks whether `com.android.car.rotary` is running on the booted device. Returns `true` once the service is confirmed to be running so
   * polling can stop.
   */
  private suspend fun checkCarRotaryService(): Boolean {
    try {
      if (!emulator.getStatus().booted) {
        return false
      }
      var isRunning = false
      var exitCode = -1
      adbDeviceServices.shellAsLines(deviceSelector, CHECK_ROTARY_SERVICE_COMMAND).collect { element ->
        when (element) {
          is ShellCommandOutputElement.StdoutLine -> {
            if (element.contents.trim() == CAR_ROTARY_SERVICE_PROCESS) {
              isRunning = true
            }
          }
          is ShellCommandOutputElement.ExitCode -> exitCode = element.exitCode
          is ShellCommandOutputElement.StderrLine -> {}
        }
      }
      if (isRunning) {
        withContext(Dispatchers.EdtAnyModality) { setWarningVisible(false) }
        return true
      }
      if (exitCode == 0) {
        withContext(Dispatchers.EdtAnyModality) { setWarningVisible(true) }
      }
    } catch (t: Throwable) {
      t.throwIfCancellation()
      thisLogger().debug("Error checking CarRotaryService status", t)
    }
    return false
  }

  private fun setWarningVisible(visible: Boolean) {
    if (warningVisible != visible && ::warningRow.isInitialized) {
      warningVisible = visible
      warningRow.visible(visible)
      dialogPanel.revalidate()
      dialogPanel.repaint()
      if (visible) {
        dialogWrapper?.window?.let { window ->
          val prefSize = window.preferredSize
          if (window.width < prefSize.width || window.height < prefSize.height) {
            window.setSize(maxOf(window.width, prefSize.width), maxOf(window.height, prefSize.height))
          }
        }
      }
    }
  }

  private fun enqueueCommand(command: String) {
    commandChannel.trySend(AdbCommand(command))
  }

  private fun enqueueRepeatCommand(command: String) {
    if (pendingRepeatCommands.compareAndSet(0, 1)) {
      if (!commandChannel.trySend(AdbCommand(command, isRepeat = true)).isSuccess) {
        pendingRepeatCommands.decrementAndGet()
      }
    }
  }

  private suspend fun executeShellCommand(command: String) {
    try {
      adbDeviceServices.shellAsLines(deviceSelector, command).collect()
    } catch (t: Throwable) {
      t.throwIfCancellation()
      thisLogger().warn("Error executing shell command: $command", t)
    }
  }

  private class AdbCommand(val command: String, val isRepeat: Boolean = false)

  /** Custom panel rendering the 9-button automotive rotary controller. */
  internal inner class CarRotaryControllerPanel : JPanel(null) {

    private val buttons = mutableListOf<RotaryButton>()

    init {
      isOpaque = false

      // TODO: Replace with gRPC when available (b/579552815).
      addRotaryButton(
        buttonName = "upButton",
        label = message("car.rotary.dialog.button.up"),
        refButtonShape = createDpadSector(-PI / 2),
        icon = StudioIcons.Emulator.TV.CHEVRON_UP,
        refIconCenter = createDpadIconCenter(-PI / 2),
        command = "cmd car_service inject-key 280",
        useActionArg = true,
      )
      addRotaryButton(
        buttonName = "leftButton",
        label = message("car.rotary.dialog.button.left"),
        refButtonShape = createDpadSector(PI),
        icon = StudioIcons.Emulator.TV.CHEVRON_LEFT,
        refIconCenter = createDpadIconCenter(PI),
        command = "cmd car_service inject-key 282",
        useActionArg = true,
      )
      addRotaryButton(
        buttonName = "centerButton",
        label = message("car.rotary.dialog.button.center"),
        refButtonShape = createCircle(CENTER_BUTTON_RADIUS),
        icon = null,
        refIconCenter = Point2D.Double(0.0, 0.0),
        command = "cmd car_service inject-key 23",
        useActionArg = true,
      )
      addRotaryButton(
        buttonName = "rightButton",
        label = message("car.rotary.dialog.button.right"),
        refButtonShape = createDpadSector(0.0),
        icon = StudioIcons.Emulator.TV.CHEVRON_RIGHT,
        refIconCenter = createDpadIconCenter(0.0),
        command = "cmd car_service inject-key 283",
        useActionArg = true,
      )
      addRotaryButton(
        buttonName = "downButton",
        label = message("car.rotary.dialog.button.down"),
        refButtonShape = createDpadSector(PI / 2),
        icon = StudioIcons.Emulator.TV.CHEVRON_DOWN,
        refIconCenter = createDpadIconCenter(PI / 2),
        command = "cmd car_service inject-key 281",
        useActionArg = true,
      )
      addRotaryButton(
        buttonName = "counterclockwiseButton",
        label = message("car.rotary.dialog.button.rotate.counterclockwise"),
        refButtonShape = createRotaryHalfRing(isLeft = true),
        refArrowShape = createRotaryArrow(isClockwise = false),
        refIconCenter = Point2D.Double(-ROTARY_ICON_RADIUS, 0.0),
        command = "cmd car_service inject-rotary",
        useActionArg = false,
        supportsLongPress = true,
      )
      addRotaryButton(
        buttonName = "clockwiseButton",
        label = message("car.rotary.dialog.button.rotate.clockwise"),
        refButtonShape = createRotaryHalfRing(isLeft = false),
        refArrowShape = createRotaryArrow(isClockwise = true),
        refIconCenter = Point2D.Double(ROTARY_ICON_RADIUS, 0.0),
        command = "cmd car_service inject-rotary -c true",
        useActionArg = false,
        supportsLongPress = true,
      )
      addRotaryButton(
        buttonName = "backButton",
        label = message("car.rotary.dialog.button.back"),
        refButtonShape = createBottomSkirtButton(isLeft = true),
        icon = StudioIcons.Emulator.TV.BACK,
        refIconCenter = Point2D.Double(-SKIRT_ICON_CENTER_X, SKIRT_ICON_CENTER_Y),
        command = "cmd car_service inject-key 4",
        useActionArg = true,
      )
      addRotaryButton(
        buttonName = "homeButton",
        label = message("car.rotary.dialog.button.home"),
        refButtonShape = createBottomSkirtButton(isLeft = false),
        icon = StudioIcons.Emulator.TV.HOME,
        refIconCenter = Point2D.Double(SKIRT_ICON_CENTER_X, SKIRT_ICON_CENTER_Y),
        command = "input keyevent 3",
        useActionArg = false,
      )
    }

    private fun addRotaryButton(
      buttonName: String,
      label: @NlsContexts.Tooltip String,
      refButtonShape: Shape,
      icon: Icon? = null,
      refArrowShape: Shape? = null,
      refIconCenter: Point2D,
      command: String,
      useActionArg: Boolean,
      supportsLongPress: Boolean = false,
    ) {
      val button =
        RotaryButton(buttonName, label, refButtonShape, icon, refArrowShape, refIconCenter, command, useActionArg, supportsLongPress)
      buttons.add(button)
      add(button)
    }

    fun releasePressedButtons(sendRelease: Boolean) {
      for (button in buttons) {
        button.releaseOnDispose(sendRelease)
      }
    }

    override fun getPreferredSize(): Dimension {
      val scale = JBUIScale.scale(DEFAULT_SCALE).toDouble()
      val insets = insets
      val width = (REF_WIDTH * scale).roundToInt() + insets.left + insets.right
      val height = (REF_HEIGHT * scale).roundToInt() + insets.top + insets.bottom
      return Dimension(width, height)
    }

    override fun getMinimumSize(): Dimension {
      val scale = JBUIScale.scale(MIN_SCALE).toDouble()
      val insets = insets
      val width = (REF_WIDTH * scale).roundToInt() + insets.left + insets.right
      val height = (REF_HEIGHT * scale).roundToInt() + insets.top + insets.bottom
      return Dimension(width, height)
    }

    override fun doLayout() {
      val insets = insets
      val availWidth = (width - insets.left - insets.right).coerceAtLeast(0)
      val availHeight = (height - insets.top - insets.bottom).coerceAtLeast(0)
      val maxScale = JBUIScale.scale(DEFAULT_SCALE).toDouble()
      val scale = minOf(maxScale, availWidth / REF_WIDTH, availHeight / REF_HEIGHT).coerceAtLeast(0.1)
      val knobCenterX = insets.left + availWidth / 2.0
      val knobCenterY = insets.top + availHeight / 2.0 - REF_CENTER_Y_OFFSET * scale
      for (button in buttons) {
        button.updateLayout(knobCenterX, knobCenterY, scale, maxScale)
      }
    }
  }

  internal inner class RotaryButton(
    buttonName: String,
    label: @NlsContexts.Tooltip String,
    private val refButtonShape: Shape,
    private val baseIcon: Icon?,
    private val refArrowShape: Shape?,
    private val refIconCenter: Point2D,
    private val command: String,
    private val useActionArg: Boolean,
    private val supportsLongPress: Boolean,
  ) : JButton() {

    private var localButtonShape: Shape = refButtonShape
    private var localArrowShape: Shape? = refArrowShape
    private var arrowStrokeWidth: Float = 1f
    private var iconCenterX: Double = 0.0
    private var iconCenterY: Double = 0.0
    private var scaledIcon: Icon? = baseIcon
    private var isPressedState: Boolean = false
    private var repeatJob: Job? = null

    /** A point in button-local coordinates guaranteed to lie inside [localButtonShape]. */
    val hitPoint: Point
      get() = Point(iconCenterX.roundToInt(), iconCenterY.roundToInt())

    init {
      name = buttonName
      setToolTipText(HtmlChunk.text(label))
      putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, label)
      isOpaque = false
      isContentAreaFilled = false
      isBorderPainted = false
      isFocusPainted = false
      isFocusable = true

      model.addChangeListener {
        onPressedChanged(model.isPressed)
      }
      addFocusListener(
        object : FocusAdapter() {
          override fun focusGained(event: FocusEvent) {
            repaint()
          }

          override fun focusLost(event: FocusEvent) {
            repaint()
          }
        }
      )
    }

    private fun onPressedChanged(pressed: Boolean) {
      if (isPressedState == pressed) {
        return
      }
      isPressedState = pressed
      repaint()

      if (pressed) {
        if (useActionArg) {
          enqueueCommand("$command -a down")
        } else if (supportsLongPress) {
          enqueueCommand(command)
          val intervalMs = longPressIntervalMs
          repeatJob?.cancel()
          repeatJob = coroutineScope.launch {
            while (true) {
              delay(intervalMs.milliseconds)
              enqueueRepeatCommand(command)
            }
          }
        }
      } else {
        repeatJob?.cancel()
        repeatJob = null
        if (useActionArg) {
          enqueueCommand("$command -a up")
        } else if (!supportsLongPress) {
          enqueueCommand(command)
        }
      }
    }

    fun releaseOnDispose(sendRelease: Boolean) {
      repeatJob?.cancel()
      repeatJob = null
      if (isPressedState) {
        isPressedState = false
        if (sendRelease && useActionArg) {
          enqueueCommand("$command -a up")
        }
      }
    }

    fun updateLayout(knobCenterX: Double, knobCenterY: Double, scale: Double, maxScale: Double) {
      val toPanel = AffineTransform.getTranslateInstance(knobCenterX, knobCenterY).apply { scale(scale, scale) }
      val panelButtonShape = toPanel.createTransformedShape(refButtonShape)
      val rawBounds = panelButtonShape.bounds
      val bounds = Rectangle(rawBounds.x - 1, rawBounds.y - 1, rawBounds.width + 2, rawBounds.height + 2)
      setBounds(bounds)
      val toLocal = AffineTransform.getTranslateInstance(-bounds.x.toDouble(), -bounds.y.toDouble())
      localButtonShape = toLocal.createTransformedShape(panelButtonShape)
      localArrowShape = refArrowShape?.let { toLocal.createTransformedShape(toPanel.createTransformedShape(it)) }
      val panelIconCenter = toPanel.transform(refIconCenter, null)
      iconCenterX = panelIconCenter.x - bounds.x
      iconCenterY = panelIconCenter.y - bounds.y
      val iconScale = (scale / maxScale).toFloat()
      arrowStrokeWidth = (scale / DEFAULT_SCALE).toFloat()
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
        if (isFocusOwner) {
          val previousClip = g2.clip
          g2.clip(localButtonShape)
          g2.color = JBUI.CurrentTheme.Focus.focusColor()
          g2.stroke = BasicStroke(JBUIScale.scale(4f))
          g2.draw(localButtonShape)
          g2.clip = previousClip
        }
        localArrowShape?.let { arrow ->
          g2.color = ROTARY_ARROW_COLOR
          g2.stroke = BasicStroke(arrowStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
          g2.draw(arrow)
        }
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

  companion object {
    internal const val MIN_LONG_PRESS_INTERVAL_MS = 1
    internal const val DEFAULT_LONG_PRESS_INTERVAL_MS = 300
    internal const val LONG_PRESS_INTERVAL_STEP_MS = 50
    internal const val MAX_LONG_PRESS_INTERVAL_MS = 99_999
    internal const val CHECK_ROTARY_SERVICE_COMMAND = "ps -A -o NAME"
    internal const val CAR_ROTARY_SERVICE_PROCESS = "com.android.car.rotary"

    private const val REF_WIDTH = 308.0
    private const val REF_HEIGHT = 358.0
    private const val REF_CENTER_Y_OFFSET = 25.0
    private const val DEFAULT_SCALE = 0.65f
    private const val MIN_SCALE = 0.4f

    private const val GAP = 6.0
    private const val HALF_GAP = GAP / 2.0
    private const val CENTER_BUTTON_RADIUS = 47.0
    private const val ROTARY_INNER_RADIUS = 53.0
    private const val ROTARY_OUTER_RADIUS = 97.0
    private const val ROTARY_ICON_RADIUS = (ROTARY_INNER_RADIUS + ROTARY_OUTER_RADIUS) / 2.0
    private const val ROTARY_ARROW_HALF_ANGLE_DEGREES = 31.0
    private const val ROTARY_ARROW_WING_LENGTH = 8.7
    private const val DPAD_INNER_RADIUS = 103.0
    private const val DPAD_OUTER_RADIUS = 150.0
    private const val DPAD_ICON_RADIUS = (DPAD_INNER_RADIUS + DPAD_OUTER_RADIUS) / 2.0
    private const val SKIRT_CUTOUT_RADIUS = DPAD_OUTER_RADIUS + GAP
    private const val SKIRT_OUTER_X = 144.0
    private const val SKIRT_TOP_Y = 60.0
    private const val SKIRT_BOTTOM_Y = 200.0
    private const val SKIRT_ICON_CENTER_X = 94.0
    private const val SKIRT_ICON_CENTER_Y = 163.0

    private val SERVICE_CHECK_INTERVAL = 5.seconds
    private val LONG_PRESS_INTERVAL_KEY = Key.create<Int>("com.android.tools.idea.streaming.emulator.dialogs.CarRotaryLongPressInterval")
    private val BUTTON_NORMAL_COLOR = JBColor(0xE0E0E0, 0x546E7A)
    private val BUTTON_PRESSED_COLOR = JBColor(0xC7C7C7, 0x3D5059)
    private val ROTARY_ARROW_COLOR = JBColor(0x6C707E, 0xCED0D6)

    private fun createCircle(radius: Double): Shape = Ellipse2D.Double(-radius, -radius, radius * 2, radius * 2)

    private fun createRotaryHalfRing(isLeft: Boolean): Shape {
      val ring = Area(createCircle(ROTARY_OUTER_RADIUS))
      ring.subtract(Area(createCircle(ROTARY_INNER_RADIUS)))
      val rectX = if (isLeft) -ROTARY_OUTER_RADIUS else HALF_GAP
      val rectWidth = ROTARY_OUTER_RADIUS - HALF_GAP
      ring.intersect(Area(Rectangle2D.Double(rectX, -ROTARY_OUTER_RADIUS, rectWidth, ROTARY_OUTER_RADIUS * 2)))
      return ring
    }

    private fun createRotaryArrow(isClockwise: Boolean): Shape {
      val arc =
        Arc2D.Double(
          -ROTARY_ICON_RADIUS,
          -ROTARY_ICON_RADIUS,
          ROTARY_ICON_RADIUS * 2,
          ROTARY_ICON_RADIUS * 2,
          ROTARY_ARROW_HALF_ANGLE_DEGREES,
          -ROTARY_ARROW_HALF_ANGLE_DEGREES * 2,
          Arc2D.OPEN,
        )
      val tipAngle = Math.toRadians(ROTARY_ARROW_HALF_ANGLE_DEGREES)
      val tipX = ROTARY_ICON_RADIUS * cos(tipAngle)
      val tipY = ROTARY_ICON_RADIUS * sin(tipAngle)
      val wing1Angle = tipAngle - 3 * PI / 4
      val wing2Angle = tipAngle - PI / 4

      val path =
        Path2D.Double().apply {
          append(arc, false)
          moveTo(tipX + ROTARY_ARROW_WING_LENGTH * cos(wing1Angle), tipY + ROTARY_ARROW_WING_LENGTH * sin(wing1Angle))
          lineTo(tipX, tipY)
          lineTo(tipX + ROTARY_ARROW_WING_LENGTH * cos(wing2Angle), tipY + ROTARY_ARROW_WING_LENGTH * sin(wing2Angle))
        }
      return if (isClockwise) path else AffineTransform.getScaleInstance(-1.0, 1.0).createTransformedShape(path)
    }

    private fun createDpadSector(angleRadians: Double): Shape {
      val ring = Area(createCircle(DPAD_OUTER_RADIUS))
      ring.subtract(Area(createCircle(DPAD_INNER_RADIUS)))

      val apexX = HALF_GAP * sqrt(2.0)
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
      Point2D.Double(DPAD_ICON_RADIUS * cos(angleRadians), DPAD_ICON_RADIUS * sin(angleRadians))

    private fun createBottomSkirtButton(isLeft: Boolean): Shape {
      val x = if (isLeft) -SKIRT_OUTER_X else HALF_GAP
      val width = SKIRT_OUTER_X - HALF_GAP
      val area = Area(Rectangle2D.Double(x, SKIRT_TOP_Y, width, SKIRT_BOTTOM_Y - SKIRT_TOP_Y))
      area.subtract(Area(createCircle(SKIRT_CUTOUT_RADIUS)))
      return area
    }
  }
}
