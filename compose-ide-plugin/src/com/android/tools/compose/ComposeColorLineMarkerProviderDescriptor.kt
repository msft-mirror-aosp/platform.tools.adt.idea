/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.compose

import com.android.ide.common.rendering.api.ResourceReference
import com.android.tools.adtui.LightCalloutPopup
import com.android.tools.adtui.MaterialColorPaletteProvider
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.elementType
import com.intellij.ui.colorpicker.ColorPickerBuilder
import com.intellij.ui.colorpicker.MaterialGraphicalColorPipetteProvider
import com.intellij.ui.picker.ColorListener
import com.intellij.util.ui.ColorIcon
import java.awt.Color
import java.awt.MouseInfo
import java.awt.event.MouseEvent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.codeinsight.utils.getInitializerOrGetterInitializer
import org.jetbrains.kotlin.j2k.resolve
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElement

private const val ICON_SIZE = 8

/**
 * Resolves the given [KtReferenceExpression] to a [KtCallExpression], potentially traversing through intermediate properties.
 *
 * @param allowedDepth The maximum depth of resolution allowed. Defaults to 3.
 * @return The resolved [KtCallExpression], or `null` if resolution fails within the allowed depth.
 */
private fun KtReferenceExpression.resolveAll(allowedDepth: Int = 3): KtCallExpression? {
  if (this is KtCallExpression) return this
  if (allowedDepth == 0) return null

  var current: KtReferenceExpression? = this
  while (current != null) {
    when (@Suppress("UnstableApiUsage") val resolved = current.resolve()) {
      is KtCallExpression -> return resolved
      is KtProperty -> {
        val initializer =
          when (val expr = resolved.getInitializerOrGetterInitializer()) {
            is KtDotQualifiedExpression -> expr.selectorExpression as? KtReferenceExpression
            is KtReferenceExpression -> expr
            else -> null
          }
        return initializer?.resolveAll(allowedDepth - 1)
      }
      else -> current = resolved as? KtReferenceExpression
    }
  }
  return null
}

class ComposeColorLineMarkerProviderDescriptor : LineMarkerProviderDescriptor() {
  override fun getName() = ComposeBundle.message("compose.color.picker.name")

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.elementType != KtTokens.IDENTIFIER || !isComposeEnabled(element)) return null

    // This handles two different cases:
    //  - Annotates variable declarations like `val color = Color(0xFF00000)
    //  - Annotates color references, for example where `color` is used.
    val ktCallExpression =
      (element.parent.parent as? KtCallExpression) ?: ((element.parent as? KtReferenceExpression)?.resolveAll()) ?: return null

    val uElement = ktCallExpression.toUElement(UCallExpression::class.java) ?: return null
    if (!uElement.isColorCall()) return null

    val color = getColor(uElement) ?: return null
    val iconRenderer = ColorIconRenderer(uElement, color)
    return LineMarkerInfo(
      element,
      element.textRange,
      iconRenderer.icon,
      { ComposeBundle.message("compose.color.picker.tooltip") },
      iconRenderer,
      GutterIconRenderer.Alignment.RIGHT,
      { ComposeBundle.message("compose.color.picker.tooltip") },
    )
  }

  private fun UCallExpression.isColorCall(): Boolean {
    if (kind != UastCallKind.METHOD_CALL || returnType != PsiTypes.longType()) return false
    return when (methodName) {
      COLOR_METHOD -> resolve()?.containingClass?.qualifiedName == COMPOSE_COLOR_CLASS
      HSL_METHOD,
      HSV_METHOD ->
        receiverType?.canonicalText == COMPOSE_COLOR_COMPANION_CLASS ||
          resolve()?.containingClass?.qualifiedName == COMPOSE_COLOR_COMPANION_CLASS
      else -> false
    }
  }

  private fun getColor(uElement: UCallExpression): Color? {
    val arguments = (uElement.sourcePsi as? KtCallExpression)?.valueArguments ?: return null
    return when (getConstructorType(uElement)) {
      ComposeColorConstructor.INT -> getColorInt(arguments)
      ComposeColorConstructor.LONG -> getColorLong(arguments)
      ComposeColorConstructor.INT_X3 -> getColorIntX3(arguments)
      ComposeColorConstructor.INT_X4 -> getColorIntX4(arguments)
      ComposeColorConstructor.FLOAT_X3 -> getColorFloatX3(arguments)
      ComposeColorConstructor.FLOAT_X4 -> getColorFloatX4(arguments)
      ComposeColorConstructor.HSL_X3 -> getColorHslX3(arguments)
      ComposeColorConstructor.HSL_X4 -> getColorHslX4(arguments)
      ComposeColorConstructor.HSV_X3 -> getColorHsvX3(arguments)
      ComposeColorConstructor.HSV_X4 -> getColorHsvX4(arguments)
      // TODO: Provide the color preview for ComposeColorConstructor.FLOAT_X4_COLORSPACE
      // constructor.
      ComposeColorConstructor.FLOAT_X4_COLORSPACE,
      ComposeColorConstructor.HSL_X4_COLORSPACE,
      ComposeColorConstructor.HSV_X4_COLORSPACE -> null
      else -> null
    }
  }
}

/**
 * Simplified version of [AndroidAnnotatorUtil.ColorRenderer] that does not work on [ResourceReference] but still displays the same color
 * picker.
 *
 * TODO(lukeegan): Implement for ComposeColorConstructor.FLOAT_X4_COLORSPACE Color parameter
 */
data class ColorIconRenderer(val element: UCallExpression, val color: Color) : GutterIconNavigationHandler<PsiElement> {

  val icon = ColorIcon(ICON_SIZE, color)

  override fun navigate(e: MouseEvent?, elt: PsiElement?) {
    val project = element.sourcePsi?.project ?: return
    val setColorTask: (Color) -> Unit = getSetColorTask() ?: return

    val pickerListener = ColorListener { color, _ ->
      ApplicationManager.getApplication()
        .invokeLater(
          { WriteCommandAction.runWriteCommandAction(project, "Change Color", null, { setColorTask.invoke(color) }) },
          project.disposed,
        )
    }

    val dialog = LightCalloutPopup()
    val colorPicker =
      ColorPickerBuilder(showAlpha = true, showAlphaAsPercent = false)
        .setOriginalColor(color)
        .addSaturationBrightnessComponent()
        .addColorAdjustPanel(MaterialGraphicalColorPipetteProvider())
        .addColorValuePanel()
        .withFocus()
        .addSeparator()
        .addCustomComponent(MaterialColorPaletteProvider)
        .addColorListener(pickerListener)
        .focusWhenDisplay(true)
        .setFocusCycleRoot(true)
        .build()
    dialog.show(colorPicker.content, null, MouseInfo.getPointerInfo().location)
  }

  @VisibleForTesting
  fun getSetColorTask(): ((Color) -> Unit)? {
    val ktCallExpression = element.sourcePsi as? KtCallExpression ?: return null
    val constructorType = getConstructorType(element) ?: return null
    // No matter what the original format is, we make the format become one of:
    // - (0xAARRGGBB)
    // - (color = 0xAARRGGBB)
    // or
    // - ([0..255], [0..255], [0..255], [0.255])
    // - (red = [0..255], green = [0..255], blue = [0..255], alpha = [0.255])
    // or
    // - ([0x00..0xFF], [0x00..0xFF], [0x00..0xFF], [0x00..0xFF])
    // - (red = [0x00..0xFF], green =[0x00..0xFF], blue = [0x00..0xFF], alpha = [0x00..0xFF])
    // or
    // - ([0.0f..1.0f], [0.0f..1.0f], [0.0f..1.0f], [0.0f..1.0f])
    // - (red = [0.0f..1.0f], green = [0.0f..1.0f], blue = [0.0f..1.0f], alpha = [0.0f..1.0f])
    // , depends on the original value type and numeral system.
    return when (constructorType) {
      ComposeColorConstructor.INT,
      ComposeColorConstructor.LONG -> { color: Color ->
        val valueArgumentList = ktCallExpression.valueArgumentList
        if (valueArgumentList != null) {
          val needsArgumentName = valueArgumentList.arguments.any { it.getArgumentName() != null }
          val hexString = "0x${String.format("%08X", color.rgb)}"
          val argumentText = if (needsArgumentName) "(color = $hexString)" else "($hexString)"
          valueArgumentList.replace(KtPsiFactory(ktCallExpression.project).createCallArguments(argumentText))
        }
      }
      ComposeColorConstructor.INT_X3,
      ComposeColorConstructor.INT_X4 -> { color: Color ->
        val valueArgumentList = ktCallExpression.valueArgumentList
        if (valueArgumentList != null) {
          val needsArgumentName = valueArgumentList.arguments.any { it.getArgumentName() != null }
          val hasHexFormat = valueArgumentList.arguments.any { it.getArgumentExpression()?.text?.startsWith("0x") ?: false }
          val red = if (hasHexFormat) color.red.toHexString() else color.red.toString()
          val green = if (hasHexFormat) color.green.toHexString() else color.green.toString()
          val blue = if (hasHexFormat) color.blue.toHexString() else color.blue.toString()
          val alpha = if (hasHexFormat) color.alpha.toHexString() else color.alpha.toString()

          val argumentText =
            if (needsArgumentName) "(red = $red, green = $green, blue = $blue, alpha = $alpha)" else "($red, $green, $blue, $alpha)"
          valueArgumentList.replace(KtPsiFactory(ktCallExpression.project).createCallArguments(argumentText))
        }
      }
      ComposeColorConstructor.FLOAT_X3,
      ComposeColorConstructor.FLOAT_X4 -> { color: Color ->
        val valueArgumentList = ktCallExpression.valueArgumentList
        if (valueArgumentList != null) {
          val needsArgumentName = valueArgumentList.arguments.any { it.getArgumentName() != null }
          val red = (color.red / 255f).toRoundString()
          val green = (color.green / 255f).toRoundString()
          val blue = (color.blue / 255f).toRoundString()
          val alpha = (color.alpha / 255f).toRoundString()

          val argumentText =
            if (needsArgumentName) "(red = ${red}f, green = ${green}f, blue = ${blue}f, alpha = ${alpha}f)"
            else "(${red}f, ${green}f, ${blue}f, ${alpha}f)"
          valueArgumentList.replace(KtPsiFactory(ktCallExpression.project).createCallArguments(argumentText))
        }
      }
      ComposeColorConstructor.HSL_X3,
      ComposeColorConstructor.HSL_X4 -> { color: Color ->
        val valueArgumentList = ktCallExpression.valueArgumentList
        if (valueArgumentList != null) {
          val needsArgumentName = valueArgumentList.arguments.any { it.getArgumentName() != null }
          val argumentText = getHSLColorArgumentText(color, needsArgumentName)
          valueArgumentList.replace(KtPsiFactory(ktCallExpression.project).createCallArguments(argumentText))
        }
      }
      ComposeColorConstructor.HSV_X3,
      ComposeColorConstructor.HSV_X4 -> { color: Color ->
        val valueArgumentList = ktCallExpression.valueArgumentList
        if (valueArgumentList != null) {
          val needsArgumentName = valueArgumentList.arguments.any { it.getArgumentName() != null }
          val argumentText = getHSVColorArgumentText(color, needsArgumentName)
          valueArgumentList.replace(KtPsiFactory(ktCallExpression.project).createCallArguments(argumentText))
        }
      }
      ComposeColorConstructor.FLOAT_X4_COLORSPACE,
      ComposeColorConstructor.HSL_X4_COLORSPACE,
      ComposeColorConstructor.HSV_X4_COLORSPACE -> null // TODO: support custom ColorSpace in the future.
    }
  }
}

private const val COLOR_METHOD = "Color"
private const val HSL_METHOD = "hsl"
private const val HSV_METHOD = "hsv"
private const val COMPOSE_COLOR_CLASS = "androidx.compose.ui.graphics.ColorKt"
private const val COMPOSE_COLOR_COMPANION_CLASS = "androidx.compose.ui.graphics.Color.Companion"

private const val ARG_NAME_RED = "red"
private const val ARG_NAME_GREEN = "green"
private const val ARG_NAME_BLUE = "blue"
private const val ARG_NAME_ALPHA = "alpha"
private const val ARG_NAME_HUE = "hue"
private const val ARG_NAME_SATURATION = "saturation"
private const val ARG_NAME_LIGHTNESS = "lightness"
private const val ARG_NAME_VALUE = "value"

private val ARGS_RGB = listOf(ARG_NAME_RED, ARG_NAME_GREEN, ARG_NAME_BLUE)
private val ARGS_RGBA = listOf(ARG_NAME_RED, ARG_NAME_GREEN, ARG_NAME_BLUE, ARG_NAME_ALPHA)
private val ARGS_HSL = listOf(ARG_NAME_HUE, ARG_NAME_SATURATION, ARG_NAME_LIGHTNESS)
private val ARGS_HSLA = listOf(ARG_NAME_HUE, ARG_NAME_SATURATION, ARG_NAME_LIGHTNESS, ARG_NAME_ALPHA)
private val ARGS_HSV = listOf(ARG_NAME_HUE, ARG_NAME_SATURATION, ARG_NAME_VALUE)
private val ARGS_HSVA = listOf(ARG_NAME_HUE, ARG_NAME_SATURATION, ARG_NAME_VALUE, ARG_NAME_ALPHA)

enum class ComposeColorConstructor {
  INT,
  LONG,
  INT_X3,
  INT_X4,
  FLOAT_X3,
  FLOAT_X4,
  FLOAT_X4_COLORSPACE,
  HSL_X3,
  HSL_X4,
  HSL_X4_COLORSPACE,
  HSV_X3,
  HSV_X4,
  HSV_X4_COLORSPACE,
}

private fun getColorInt(arguments: List<KtValueArgument>): Color? {
  val colorValue = arguments.first().getArgumentExpression()?.evaluateToConstantOrNull<Int>() ?: return null
  return Color(colorValue, true)
}

private fun getColorLong(arguments: List<KtValueArgument>): Color? {
  val colorValue = arguments.first().getArgumentExpression()?.evaluateToConstantOrNull<Long>() ?: return null
  return Color(colorValue.toInt(), true)
}

private fun getColorIntX3(arguments: List<KtValueArgument>): Color? {
  val rgbValues = getNamedValues<Int>(ARGS_RGB, arguments) ?: return null
  return intColorMapToColor(rgbValues)
}

private fun getColorIntX4(arguments: List<KtValueArgument>): Color? {
  val rgbaValues = getNamedValues<Int>(ARGS_RGBA, arguments) ?: return null
  return intColorMapToColor(rgbaValues)
}

private fun getColorFloatX3(arguments: List<KtValueArgument>): Color? {
  val rgbValues = getNamedValues<Float>(ARGS_RGB, arguments) ?: return null
  return floatColorMapToColor(rgbValues)
}

private fun getColorFloatX4(arguments: List<KtValueArgument>): Color? {
  val rgbaValues = getNamedValues<Float>(ARGS_RGBA, arguments) ?: return null
  return floatColorMapToColor(rgbaValues)
}

private fun getColorHslX3(arguments: List<KtValueArgument>): Color? {
  val hslValues = getNamedValues<Float>(ARGS_HSL, arguments) ?: return null
  return hslColorMapToColor(hslValues)
}

private fun getColorHslX4(arguments: List<KtValueArgument>): Color? {
  val hslaValues = getNamedValues<Float>(ARGS_HSLA, arguments) ?: return null
  return hslColorMapToColor(hslaValues)
}

private fun getColorHsvX3(arguments: List<KtValueArgument>): Color? {
  val hsvValues = getNamedValues<Float>(ARGS_HSV, arguments) ?: return null
  return hsvColorMapToColor(hsvValues)
}

private fun getColorHsvX4(arguments: List<KtValueArgument>): Color? {
  val hsvaValues = getNamedValues<Float>(ARGS_HSVA, arguments) ?: return null
  return hsvColorMapToColor(hsvaValues)
}

/** This function return the name-value pair for the request arguments names by extracting the given ktValueArguments. */
private inline fun <reified T> getNamedValues(requestArgumentNames: List<String>, ktValueArgument: List<KtValueArgument>): Map<String, T>? {
  val namedValues = mutableMapOf<String, T>()

  val unnamedValue = mutableListOf<T>()
  for (argument in ktValueArgument) {
    val (name, value) = getArgumentNameValuePair<T>(argument) ?: return null
    if (name != null) {
      namedValues[name] = value
    } else {
      unnamedValue.add(value)
    }
  }

  val unnamedArgument = requestArgumentNames.filterNot { it in namedValues.keys }.toList()
  if (unnamedArgument.size != unnamedValue.size) {
    // The number of argument values doesn't match the given KtValueArgument.
    return null
  }

  for (index in unnamedArgument.indices) {
    // Fill the unnamed argument value from KtValueArgument.
    namedValues[unnamedArgument[index]] = unnamedValue[index]
  }
  if (namedValues.keys != requestArgumentNames.toSet()) {
    // Has the redundant or missed argument(s).
    return null
  }
  return namedValues
}

private inline fun <reified T> getArgumentNameValuePair(valueArgument: KtValueArgument): Pair<String?, T>? {
  val name = valueArgument.getArgumentName()?.asName?.asString()
  val value = valueArgument.getArgumentExpression()?.evaluateToConstantOrNull<T>() ?: return null
  return name to value
}

private inline fun <reified T> KtExpression.evaluateToConstantOrNull(): T? {
  return analyze(this) { evaluate()?.value as? T ?: return null }
}

private fun Int.toHexString(): String = "0x${(Integer.toHexString(this)).uppercase(Locale.getDefault())}"

// Note: toFloat() then toString() is for removing the tail zero(s).
private fun Float.toRoundString(decimals: Int = 3): String =
  String.format(Locale.US, "%.${decimals}f", this).toFloat().toString()

private typealias IntColorMap = Map<String, Int>

private fun intColorMapToColor(intColorMap: IntColorMap): Color? {
  val red = intColorMap[ARG_NAME_RED] ?: return null
  val green = intColorMap[ARG_NAME_GREEN] ?: return null
  val blue = intColorMap[ARG_NAME_BLUE] ?: return null
  val alpha = intColorMap[ARG_NAME_ALPHA]
  return if (alpha == null) Color(red, green, blue) else Color(red, green, blue, alpha)
}

private typealias FloatColorMap = Map<String, Float>

private fun floatColorMapToColor(floatColorMap: FloatColorMap): Color? {
  val red = floatColorMap[ARG_NAME_RED] ?: return null
  val green = floatColorMap[ARG_NAME_GREEN] ?: return null
  val blue = floatColorMap[ARG_NAME_BLUE] ?: return null
  val alpha = floatColorMap[ARG_NAME_ALPHA]
  return if (alpha == null) Color(red, green, blue) else Color(red, green, blue, alpha)
}

private fun hslColorMapToColor(hslColorMap: FloatColorMap): Color? {
  val hue = hslColorMap[ARG_NAME_HUE] ?: return null
  val saturation = hslColorMap[ARG_NAME_SATURATION] ?: return null
  val lightness = hslColorMap[ARG_NAME_LIGHTNESS] ?: return null
  val alpha = hslColorMap[ARG_NAME_ALPHA] ?: 1f
  if (hue !in 0f..360f || saturation !in 0f..1f || lightness !in 0f..1f || alpha !in 0f..1f) return null

  val red = hslToRgbComponent(0, hue, saturation, lightness)
  val green = hslToRgbComponent(8, hue, saturation, lightness)
  val blue = hslToRgbComponent(4, hue, saturation, lightness)
  return Color(red, green, blue, alpha)
}

private fun hslToRgbComponent(n: Int, h: Float, s: Float, l: Float): Float {
  val k = (n.toFloat() + h / 30f) % 12f
  val a = s * min(l, 1f - l)
  return (l - a * max(-1f, minOf(k - 3f, 9f - k, 1f))).coerceIn(0f, 1f)
}

private fun hsvColorMapToColor(hsvColorMap: FloatColorMap): Color? {
  val hue = hsvColorMap[ARG_NAME_HUE] ?: return null
  val saturation = hsvColorMap[ARG_NAME_SATURATION] ?: return null
  val value = hsvColorMap[ARG_NAME_VALUE] ?: return null
  val alpha = hsvColorMap[ARG_NAME_ALPHA] ?: 1f
  if (hue !in 0f..360f || saturation !in 0f..1f || value !in 0f..1f || alpha !in 0f..1f) return null

  val red = hsvToRgbComponent(5, hue, saturation, value)
  val green = hsvToRgbComponent(3, hue, saturation, value)
  val blue = hsvToRgbComponent(1, hue, saturation, value)
  return Color(red, green, blue, alpha)
}

private fun hsvToRgbComponent(n: Int, h: Float, s: Float, v: Float): Float {
  val k = (n.toFloat() + h / 60f) % 6f
  return (v - v * s * max(0f, minOf(k, 4f - k, 1f))).coerceIn(0f, 1f)
}

private fun getHSLColorArgumentText(color: Color, needsArgumentName: Boolean): String {
  val r = color.red / 255f
  val g = color.green / 255f
  val b = color.blue / 255f
  // In HSL, hue matches the HSV hue, so we can reuse Color.RGBtoHSB to compute the hue angle in degrees [0..360).
  val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
  val hue = (hsb[0] * 360f).toRoundString()

  // Lightness and saturation require the minimum and maximum channel components:
  val maxComp = maxOf(r, g, b)
  val minComp = minOf(r, g, b)
  // Lightness is defined as the midpoint between the max and min RGB values: L = (max + min) / 2.
  val l = (maxComp + minComp) / 2f
  // Saturation is the ratio of chroma to (1 - |2L - 1|): S = (max - min) / (1 - |2L - 1|).
  // If max == min (grayscale/achromatic), saturation is 0.
  val s = if (maxComp == minComp) 0f else (maxComp - minComp) / (1f - abs(2f * l - 1f))
  val saturation = s.toRoundString()
  val lightness = l.toRoundString()
  val alpha = (color.alpha / 255f).toRoundString()

  return if (needsArgumentName) {
    "(hue = ${hue}f, saturation = ${saturation}f, lightness = ${lightness}f, alpha = ${alpha}f)"
  } else {
    "(${hue}f, ${saturation}f, ${lightness}f, ${alpha}f)"
  }
}

private fun getHSVColorArgumentText(color: Color, needsArgumentName: Boolean): String {
  // Color.RGBtoHSB returns hue [0..1], saturation [0..1], and brightness/value [0..1].
  val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
  val hue = (hsb[0] * 360f).toRoundString()
  val saturation = hsb[1].toRoundString()
  val value = hsb[2].toRoundString()
  val alpha = (color.alpha / 255f).toRoundString()

  return if (needsArgumentName) {
    "(hue = ${hue}f, saturation = ${saturation}f, value = ${value}f, alpha = ${alpha}f)"
  } else {
    "(${hue}f, ${saturation}f, ${value}f, ${alpha}f)"
  }
}

private fun getConstructorType(uElement: UCallExpression): ComposeColorConstructor? {
  val arguments = uElement.valueArguments
  val paramType = arguments.firstOrNull()?.getExpressionType() ?: return null
  return when (uElement.methodName) {
    COLOR_METHOD ->
      when (arguments.size) {
        1 -> if (PsiTypes.intType() == paramType) ComposeColorConstructor.INT else ComposeColorConstructor.LONG
        3 -> if (PsiTypes.intType() == paramType) ComposeColorConstructor.INT_X3 else ComposeColorConstructor.FLOAT_X3
        4 -> if (PsiTypes.intType() == paramType) ComposeColorConstructor.INT_X4 else ComposeColorConstructor.FLOAT_X4
        5 -> ComposeColorConstructor.FLOAT_X4_COLORSPACE
        else -> null
      }
    HSL_METHOD ->
      when (arguments.size) {
        3 -> ComposeColorConstructor.HSL_X3
        4 -> ComposeColorConstructor.HSL_X4
        5 -> ComposeColorConstructor.HSL_X4_COLORSPACE
        else -> null
      }
    HSV_METHOD ->
      when (arguments.size) {
        3 -> ComposeColorConstructor.HSV_X3
        4 -> ComposeColorConstructor.HSV_X4
        5 -> ComposeColorConstructor.HSV_X4_COLORSPACE
        else -> null
      }
    else -> null
  }
}
