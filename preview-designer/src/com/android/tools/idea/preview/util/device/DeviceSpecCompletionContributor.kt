/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.preview.util.device

import com.android.annotations.concurrency.Slow
import com.android.ide.common.util.enumValueOfOrNull
import com.android.tools.completion.addLookupElement
import com.android.tools.completion.inserthandler.InsertionFormat
import com.android.tools.completion.inserthandler.LiveTemplateFormat
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.preview.util.device.parser.DeviceSpecParam
import com.android.tools.idea.preview.util.device.parser.DeviceSpecPsiFile
import com.android.tools.idea.preview.util.device.parser.DeviceSpecTypes
import com.android.tools.idea.preview.util.device.parser.impl.DeviceSpecSpecImpl
import com.android.tools.idea.preview.util.getSdkDevices
import com.android.tools.idea.projectsystem.ProjectSyncModificationTracker
import com.android.tools.idea.rendering.BuildTargetReference
import com.android.tools.idea.rendering.tokens.BuildSystemFilePreviewServices
import com.android.tools.idea.rendering.tokens.BuildSystemFilePreviewServices.Companion.getBuildSystemFilePreviewServices
import com.android.tools.preview.config.Cutout
import com.android.tools.preview.config.DEFAULT_DEVICE_ID
import com.android.tools.preview.config.DEVICE_BY_ID_PREFIX
import com.android.tools.preview.config.DEVICE_BY_SPEC_PREFIX
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.ExpandAxis
import com.android.tools.preview.config.KeyboardKind
import com.android.tools.preview.config.Navigation
import com.android.tools.preview.config.Orientation
import com.android.tools.preview.config.PointerPrecision
import com.android.tools.preview.config.Preview.DeviceSpec
import com.android.tools.preview.config.ReferenceDesktopConfig
import com.android.tools.preview.config.ReferenceFoldableConfig
import com.android.tools.preview.config.ReferencePhoneConfig
import com.android.tools.preview.config.ReferenceTabletConfig
import com.android.tools.preview.config.ScrollTarget
import com.android.tools.preview.config.ViewingDistance
import com.android.tools.preview.config.WindowPosture
import com.android.tools.preview.config.getDefaultPreviewDevice
import com.android.tools.rendering.classloading.ExternalLibraryClassLoaderPool
import com.android.tools.rendering.compose.PREVIEW_DEVICE_SPEC_CLASS_FQCN
import com.android.tools.rendering.compose.SUPPORTED_PARAMETERS_FIELD_NAME
import com.android.tools.rendering.compose.parseSupportedComposePreviewDeviceSpecParameters
import com.android.tools.rendering.compose.readSupportedParametersFromClassBytes
import com.android.tools.rendering.compose.supportedComposePreviewDeviceSpecParameters
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ProcessingContext
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.idea.completion.or
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/** [CompletionContributor] for the [DeviceSpecLanguage] parameters. */
class DeviceSpecCompletionContributor : CompletionContributor() {
  init {
    extend(
      CompletionType.BASIC,
      // Completion for `id:<device_id>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(PlatformPatterns.psiElement(DeviceSpecTypes.COLON), PlatformPatterns.psiElement(DeviceSpecTypes.ID_KEYWORD)),
      SdkDeviceIdProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `parent=<device_id>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.PARENT_KEYWORD),
        ),
      SdkDeviceIdProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `orientation=<portrait/landscape>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.ORIENTATION_KEYWORD),
        ),
      OrientationValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `cutout=<none|corner|double|punch_hole|tall>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.CUTOUT_KEYWORD),
        ),
      CutoutValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `navigation=<buttons|gesture>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.NAVIGATION_KEYWORD),
        ),
      NavigationValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `windowPosture=<flat|tabletop|book>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.WINDOW_POSTURE_KEYWORD),
        ),
      WindowPostureValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `pointerPrecision=<coarse|fine|blunt|none>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.POINTER_PRECISION_KEYWORD),
        ),
      PointerPrecisionValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `keyboardKind=<virtual|physical|none>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.KEYBOARD_KIND_KEYWORD),
        ),
      KeyboardKindValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `hasCamera=<true|false>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.HAS_CAMERA_KEYWORD),
        ),
      BooleanValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `hasMicrophone=<true|false>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.HAS_MICROPHONE_KEYWORD),
        ),
      BooleanValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `viewingDistance=<near|medium|far>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.VIEWING_DISTANCE_KEYWORD),
        ),
      ViewingDistanceValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `expand=<vertical|horizontal>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.EXPAND_KEYWORD),
        ),
      ExpandValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for `scrollTo=<end>`
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T)
        .afterLeafSkipping(
          PlatformPatterns.psiElement(DeviceSpecTypes.EQUALS),
          PlatformPatterns.psiElement(DeviceSpecTypes.SCROLL_TO_KEYWORD),
        ),
      ScrollToValueProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for spec/id prefix.
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T) // String token
        // The initial prefix for the device definition is always the first element of the file, and
        // it's always within PsiErrorElement
        // since incomplete statements cannot be parsed into DeviceSpec element, such as: "", "s",
        // "i", "spec", "id". The first valid prefix
        // statement has to end with a ':'. Ie: "spec:", "id:". At that point the completion results
        // would no longer show.
        .withParent(PlatformPatterns.psiElement(PsiErrorElement::class.java).isFirstChild())
        .withSuperParent(2, DeviceSpecPsiFile::class.java),
      DeviceReferenceProvider,
    )
    extend(
      CompletionType.BASIC,
      // Completion for DeviceSpec parameters, a parameter definition may start after a colon
      // character ':' or after a comma ',' following
      // the value of another parameter.
      PlatformPatterns.psiElement(DeviceSpecTypes.STRING_T) // String token
        // Unresolved/incomplete statements are wrapped in a PsiErrorElement.
        .withParent(
          PlatformPatterns.psiElement(PsiErrorElement::class.java)
            .afterLeaf(PlatformPatterns.psiElement(DeviceSpecTypes.COLON).or(PlatformPatterns.psiElement(DeviceSpecTypes.COMMA)))
        )
        .withSuperParent(2, DeviceSpecPsiFile::class.java),
      DeviceSpecParameterProvider,
    )
  }
}

/** Pattern that is successful when the currently captured [PsiElement] is the first element within its parent. */
private fun <T : PsiElement> PsiElementPattern<T, PsiElementPattern.Capture<T>>.isFirstChild() =
  with(
    object : PatternCondition<T>("isFirstChild") {
      override fun accepts(element: T, context: ProcessingContext?): Boolean {
        val parent = element.context ?: return false
        return parent.children.indexOf(element) == 0
      }
    }
  )

/**
 * Parameters to expand the render area or scroll its content, supported both with and without a parent device. They are not included in the
 * [baseDeviceSpecTemplate] since they change how the preview is rendered.
 */
private val renderSpecParamsToDefaultValues: Map<String, String> =
  mapOf(
    DeviceSpec.PARAMETER_EXPAND to ExpandAxis.vertical.name,
    DeviceSpec.PARAMETER_MAX_HEIGHT to DeviceSpec.DEFAULT_EXPAND_MAX_SIZE_PX.toString(),
    DeviceSpec.PARAMETER_MAX_WIDTH to DeviceSpec.DEFAULT_EXPAND_MAX_SIZE_PX.toString(),
    DeviceSpec.PARAMETER_SCROLL_TO to ScrollTarget.End.specValue,
  )

/** Supported parameters to autocomplete a custom Device definition with the `spec` prefix when using the DeviceSpec Language. */
private val customSpecParamsToDefaultValues: Map<String, String> =
  mapOf(
    DeviceSpec.PARAMETER_WIDTH to DeviceSpec.DEFAULT_WIDTH_DP.toString(),
    DeviceSpec.PARAMETER_HEIGHT to DeviceSpec.DEFAULT_HEIGHT_DP.toString(),
    DeviceSpec.PARAMETER_DPI to DeviceSpec.DEFAULT_DPI.toString(),
    DeviceSpec.PARAMETER_IS_ROUND to DeviceSpec.DEFAULT_IS_ROUND.toString(),
    DeviceSpec.PARAMETER_CHIN_SIZE to DeviceSpec.DEFAULT_CHIN_SIZE_ZERO.toString(),
    DeviceSpec.PARAMETER_ORIENTATION to DeviceSpec.DEFAULT_ORIENTATION.name,
    DeviceSpec.PARAMETER_CUTOUT to DeviceSpec.DEFAULT_CUTOUT.name,
    DeviceSpec.PARAMETER_NAVIGATION to DeviceSpec.DEFAULT_NAVIGATION.name,
    DeviceSpec.PARAMETER_WINDOW_POSTURE to WindowPosture.flat.name,
    DeviceSpec.PARAMETER_POINTER_PRECISION to PointerPrecision.coarse.name,
    DeviceSpec.PARAMETER_KEYBOARD_KIND to KeyboardKind.virtual.name,
    DeviceSpec.PARAMETER_HAS_CAMERA to true.toString(),
    DeviceSpec.PARAMETER_HAS_MICROPHONE to true.toString(),
    DeviceSpec.PARAMETER_VIEWING_DISTANCE to ViewingDistance.near.name,
  ) + renderSpecParamsToDefaultValues

/** Supported parameters to autocomplete an existing device definition with the `spec` prefix when using the DeviceSpec Language */
private val parentBasedSpecParamsToDefaultValues: Map<String, String> =
  mapOf(
    DeviceSpec.PARAMETER_PARENT to DEFAULT_DEVICE_ID,
    DeviceSpec.PARAMETER_ORIENTATION to DeviceSpec.DEFAULT_ORIENTATION.name,
    DeviceSpec.PARAMETER_NAVIGATION to DeviceSpec.DEFAULT_NAVIGATION.name,
    DeviceSpec.PARAMETER_WINDOW_POSTURE to WindowPosture.flat.name,
    DeviceSpec.PARAMETER_POINTER_PRECISION to PointerPrecision.coarse.name,
    DeviceSpec.PARAMETER_KEYBOARD_KIND to KeyboardKind.virtual.name,
    DeviceSpec.PARAMETER_HAS_CAMERA to true.toString(),
    DeviceSpec.PARAMETER_HAS_MICROPHONE to true.toString(),
    DeviceSpec.PARAMETER_VIEWING_DISTANCE to ViewingDistance.near.name,
  ) + renderSpecParamsToDefaultValues

/** All supported parameters compatible with `spec...` when using the DeviceSpec Language. */
private val allSpecParamsToDefaultValues: Map<String, String> =
  mapOf(
    DeviceSpec.PARAMETER_WIDTH to DeviceSpec.DEFAULT_WIDTH_DP.toString(),
    DeviceSpec.PARAMETER_HEIGHT to DeviceSpec.DEFAULT_HEIGHT_DP.toString(),
    DeviceSpec.PARAMETER_DPI to DeviceSpec.DEFAULT_DPI.toString(),
    DeviceSpec.PARAMETER_IS_ROUND to DeviceSpec.DEFAULT_IS_ROUND.toString(),
    DeviceSpec.PARAMETER_CHIN_SIZE to DeviceSpec.DEFAULT_CHIN_SIZE_ZERO.toString(),
    DeviceSpec.PARAMETER_ORIENTATION to DeviceSpec.DEFAULT_ORIENTATION.name,
    DeviceSpec.PARAMETER_PARENT to DEFAULT_DEVICE_ID,
    DeviceSpec.PARAMETER_CUTOUT to DeviceSpec.DEFAULT_CUTOUT.name,
    DeviceSpec.PARAMETER_NAVIGATION to DeviceSpec.DEFAULT_NAVIGATION.name,
    DeviceSpec.PARAMETER_WINDOW_POSTURE to WindowPosture.flat.name,
    DeviceSpec.PARAMETER_POINTER_PRECISION to PointerPrecision.coarse.name,
    DeviceSpec.PARAMETER_KEYBOARD_KIND to KeyboardKind.virtual.name,
    DeviceSpec.PARAMETER_HAS_CAMERA to true.toString(),
    DeviceSpec.PARAMETER_HAS_MICROPHONE to true.toString(),
    DeviceSpec.PARAMETER_VIEWING_DISTANCE to ViewingDistance.near.name,
  ) + renderSpecParamsToDefaultValues

/**
 * The common parameters of [parentBasedSpecParamsToDefaultValues] and [customSpecParamsToDefaultValues].
 *
 * This help us decide if we can use [allSpecParamsToDefaultValues] when providing parameter completion.
 */
private val commonSpecParams: Set<String> =
  allSpecParamsToDefaultValues.keys.intersect(parentBasedSpecParamsToDefaultValues.keys).intersect(customSpecParamsToDefaultValues.keys)

/** A [LiveTemplateFormat] that includes all supported DeviceSpec parameters with their default values. */
private val baseDeviceSpecTemplate: LiveTemplateFormat by lazy {
  val template =
    customSpecParamsToDefaultValues
      // Exclude expand and scrollTo parameters so the default "spec:" live template creates a
      // standard device-sized preview rather than an expanded or scrolled one.
      .filterKeys { it !in renderSpecParamsToDefaultValues }
      .map { entry ->
        val suffix = if (DeviceSpec.isDimensionParameter(entry.key)) DeviceSpec.DEFAULT_UNIT.name else ""

        // param=<default_value>suffix
        entry.key + DeviceSpec.OPERATOR + "<${entry.value}>" + suffix
      }
      .joinToString(DeviceSpec.SEPARATOR.toString())
  LiveTemplateFormat(template)
}

/** Provides completions for the Id of the Devices present in the Sdk. */
private object SdkDeviceIdProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    val module = parameters.position.module ?: return
    val devices = getSdkDevices(module)

    devices.forEach { result.addElement(LookupElementBuilder.create(it.id)) }
  }
}

/**
 * [CompletionProvider] for the device parameter as a whole.
 *
 * This is meant to provide usage examples of the `device` parameter, so it should only provide completions on the first segment of the
 * device definition. Ie: when the user is typing 'id:' or 'spec:'
 */
private object DeviceReferenceProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    val module = parameters.position.module
    val defaultDeviceId = module?.let(ConfigurationManager::getOrCreateInstance)?.getDefaultPreviewDevice()?.id
    defaultDeviceId?.let { result.addLookupElement(lookupString = DEVICE_BY_ID_PREFIX + defaultDeviceId, tailText = " Default Device") }

    val unitName = DeviceSpec.DEFAULT_UNIT.name
    result.addLookupElement(
      lookupString = DEVICE_BY_SPEC_PREFIX,
      tailText = "width=$unitName,height=$unitName,dpi=int,isRound=boolean,chinSize=$unitName,orientation=portrait/landscape",
      format = baseDeviceSpecTemplate,
    )
    result.addLookupElement(lookupString = ReferencePhoneConfig.deviceSpec(), tailText = " Reference Phone")
    result.addLookupElement(lookupString = ReferenceTabletConfig.deviceSpec(), tailText = " Reference Tablet")
    result.addLookupElement(lookupString = ReferenceDesktopConfig.deviceSpec(), tailText = " Reference Desktop")
    result.addLookupElement(lookupString = ReferenceFoldableConfig.deviceSpec(), tailText = " Reference Foldable")
  }
}

/**
 * Provides the remaining parameters for a Device Specification, depending on the intended usage of `spec:...`.
 *
 * In other words, completes the parameters so that either of these declarations can be achieved:
 * - spec:width=...,height=...,dpi=...,isRound=...,chinSize=...,orientation=...
 * - spec:parent=...,orientation=...
 */
private object DeviceSpecParameterProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    val deviceSpecImplElement = parameters.position.getParentOfType<DeviceSpecPsiFile>(false)?.getChildOfType<DeviceSpecSpecImpl>()
    val expectedUnit = deviceSpecImplElement?.getFirstValidUnit() ?: DeviceSpec.DEFAULT_UNIT
    val existingParameters = deviceSpecImplElement?.getChildrenOfType<DeviceSpecParam>()?.map { it.firstChild.text }?.toSet() ?: emptySet()

    val createDimensionParameterFormat: (String) -> InsertionFormat = { defaultValue ->
      LiveTemplateFormat("=<$defaultValue>${expectedUnit.name}")
    }

    val parametersToComplete =
      when {
        existingParameters.isEmpty() || commonSpecParams.containsAll(existingParameters) -> {
          // May use any parameter if it's empty, or all existing correspond to common parameters
          allSpecParamsToDefaultValues
        }
        existingParameters.contains(DeviceSpec.PARAMETER_PARENT) -> {
          // When `parent` is in use, only parent related parameters may be used
          parentBasedSpecParamsToDefaultValues
        }
        else -> {
          // All other cases apply for custom spec only
          customSpecParamsToDefaultValues
        }
      }.toMutableMap()

    // Remove existing parameters and expand/scrollTo parameters not supported by the module's ui-tooling version
    val supportedParameters = parameters.position.module?.supportedComposePreviewDeviceSpecParameters().orEmpty()
    existingParameters.forEach { parametersToComplete.remove(it) }
    renderSpecParamsToDefaultValues.keys.forEach { param ->
      if (supportedParameters.none { it.equals(param, ignoreCase = true) }) {
        parametersToComplete.remove(param)
      }
    }

    parametersToComplete.forEach {
      if (DeviceSpec.isDimensionParameter(it.key)) {
        // For parameters that take a dimension, use an appropriate InsertionFormat that includes
        // the expected dimension unit.
        result.addLookupElement(lookupString = it.key, format = createDimensionParameterFormat(it.value))
      } else {
        result.addLookupElement(lookupString = it.key, format = LiveTemplateFormat("=<${it.value}>"))
      }
    }
  }
}

/**
 * Provides the possible orientation values for the Orientation parameter.
 *
 * I.e: provides "portrait,landscape" for `orientation=$caret`
 */
private object OrientationValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    Orientation.values().forEach { result.addLookupElement(lookupString = it.name) }
  }
}

/**
 * Provides the possible cutout values for the Cutout parameter.
 *
 * I.e: provides "none,corner,double,punch_hole,tall" for `cutout=$caret`
 */
private object CutoutValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    Cutout.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

/**
 * Provides the possible navigation values for the Navigation parameter.
 *
 * I.e: provides "buttons,gesture" for `navigation=$caret`
 */
private object NavigationValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    Navigation.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

/**
 * Return the first valid dimension unit [DimUnit] used within a [DeviceSpecSpecImpl] element. That same unit is expected to be used in
 * other dimension values.
 */
private fun DeviceSpecSpecImpl.getFirstValidUnit(): DimUnit? {
  getChildrenOfType<DeviceSpecParam>().forEach loop@{ paramElement ->
    when (paramElement.firstChild.text) {
      DeviceSpec.PARAMETER_WIDTH,
      DeviceSpec.PARAMETER_HEIGHT,
      DeviceSpec.PARAMETER_CHIN_SIZE,
      DeviceSpec.PARAMETER_MAX_HEIGHT,
      DeviceSpec.PARAMETER_MAX_WIDTH -> {
        enumValueOfOrNull<DimUnit>(paramElement.text.takeLast(2))?.let {
          return it
        }
      }
      else -> {
        return@loop
      }
    }
  }
  return null
}

private object WindowPostureValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    WindowPosture.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

private object PointerPrecisionValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    PointerPrecision.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

private object KeyboardKindValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    KeyboardKind.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

private object ViewingDistanceValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    ViewingDistance.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

/**
 * Provides the possible axis values for the Expand parameter.
 *
 * I.e: provides "vertical,horizontal" for `expand=$caret`
 */
private object ExpandValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    val supportedParameters = parameters.position.module?.supportedComposePreviewDeviceSpecParameters().orEmpty()
    if (supportedParameters.none { it.equals(DeviceSpec.PARAMETER_EXPAND, ignoreCase = true) }) return
    ExpandAxis.entries.forEach { result.addLookupElement(lookupString = it.name) }
  }
}

/**
 * Provides the keyword scroll targets for the ScrollTo parameter.
 *
 * I.e: provides "end" for `scrollTo=$caret`
 */
private object ScrollToValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    val supportedParameters = parameters.position.module?.supportedComposePreviewDeviceSpecParameters().orEmpty()
    if (supportedParameters.none { it.equals(DeviceSpec.PARAMETER_SCROLL_TO, ignoreCase = true) }) return
    ScrollTarget.keywordTargets.forEach { result.addLookupElement(lookupString = it.specValue) }
  }
}

private object BooleanValueProvider : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
    result.addLookupElement(lookupString = true.toString())
    result.addLookupElement(lookupString = false.toString())
  }
}

private val SUPPORTED_DEVICE_SPEC_PARAMETERS_KEY: Key<CachedValue<Set<String>>> =
  Key.create("DeviceSpecCompletionContributor.supportedComposePreviewDeviceSpecParameters")

private val EXTERNAL_LIBRARIES_DEVICE_SPEC_PARAMETERS_KEY: Key<CachedValue<Set<String>>> =
  Key.create("DeviceSpecCompletionContributor.externalLibrariesSupportedComposePreviewDeviceSpecParameters")

/**
 * Returns the set of `spec:` device string parameter names declared as supported by the `androidx.compose.ui:ui-tooling` library in this
 * [Module], or an empty set if `PreviewDeviceSpec` is not on the classpath (e.g. with an older `ui-tooling` version).
 */
@Slow
fun Module.supportedComposePreviewDeviceSpecParameters(): Set<String> {
  if (isDisposed || project.isDisposed) return emptySet()
  return CachedValuesManager.getManager(project)
    .getCachedValue(
      this,
      SUPPORTED_DEVICE_SPEC_PARAMETERS_KEY,
      {
        val parameters = computeSupportedComposePreviewDeviceSpecParameters(this)
        CachedValueProvider.Result.create(
          parameters,
          PsiModificationTracker.MODIFICATION_COUNT,
          ProjectRootModificationTracker.getInstance(project),
          ProjectSyncModificationTracker.getInstance(project),
          DumbService.getInstance(project).modificationTracker,
        )
      },
      false,
    )
}

@Slow
private fun computeSupportedComposePreviewDeviceSpecParameters(module: Module): Set<String> {
  val project = module.project
  val psiSupportedParameters = runReadAction {
    if (module.isDisposed || project.isDisposed || DumbService.isDumb(project)) return@runReadAction null
    val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
    val psiClass = JavaPsiFacade.getInstance(project).findClass(PREVIEW_DEVICE_SPEC_CLASS_FQCN, scope) ?: return@runReadAction null
    psiClass.findFieldByName(SUPPORTED_PARAMETERS_FIELD_NAME, false)?.computeConstantValue() as? String
  }
  if (psiSupportedParameters != null) {
    return parseSupportedComposePreviewDeviceSpecParameters(psiSupportedParameters)
  }
  if (module.isDisposed || project.isDisposed) return emptySet()

  return module.externalLibrariesSupportedComposePreviewDeviceSpecParameters()
}

@Slow
private fun Module.externalLibrariesSupportedComposePreviewDeviceSpecParameters(): Set<String> {
  if (isDisposed || project.isDisposed) return emptySet()
  return CachedValuesManager.getManager(project)
    .getCachedValue(
      this,
      EXTERNAL_LIBRARIES_DEVICE_SPEC_PARAMETERS_KEY,
      {
        val renderingServices = renderingServicesOrNull()
        val classFileParameters =
          renderingServices
            ?.classFileFinder
            ?.findClassFile(PREVIEW_DEVICE_SPEC_CLASS_FQCN)
            ?.content
            ?.let(::readSupportedParametersFromClassBytes)
            .orEmpty()
        val parameters =
          if (classFileParameters.isNotEmpty()) {
            classFileParameters
          } else {
            val externalLibraries = renderingServices?.externalLibraries?.toList().orEmpty()
            if (externalLibraries.isEmpty()) {
              emptySet()
            } else {
              val identities = ExternalLibraryClassLoaderPool.computeLibraryIdentities(externalLibraries)
              ExternalLibraryClassLoaderPool.getOrCreateEntry(externalLibraries, identities)
                .classLoader
                .supportedComposePreviewDeviceSpecParameters()
            }
          }
        CachedValueProvider.Result.create(
          parameters,
          ProjectRootModificationTracker.getInstance(project),
          ProjectSyncModificationTracker.getInstance(project),
        )
      },
      false,
    )
}

private fun Module.renderingServicesOrNull(): BuildSystemFilePreviewServices.RenderingServices? {
  val buildTargetReference =
    runCatching { BuildTargetReference.gradleOnly(this) }
      .onFailure { if (it is CancellationException || it is ControlFlowException) throw it }
      .getOrNull() ?: return null
  return runCatching { buildTargetReference.getBuildSystemFilePreviewServices().getRenderingServices(buildTargetReference) }
    .onFailure { if (it is CancellationException || it is ControlFlowException) throw it }
    .getOrNull()
}
