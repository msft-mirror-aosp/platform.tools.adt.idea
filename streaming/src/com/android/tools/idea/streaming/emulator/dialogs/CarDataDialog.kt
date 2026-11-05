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
import com.android.emulation.control.incubating.CarEvent
import com.android.emulation.control.incubating.EmulatorMessage
import com.android.emulation.control.incubating.MsgType
import com.android.emulation.control.incubating.Status
import com.android.emulation.control.incubating.VehicleAreaConfig
import com.android.emulation.control.incubating.VehiclePropConfig
import com.android.emulation.control.incubating.VehiclePropGet
import com.android.emulation.control.incubating.VehiclePropStatus
import com.android.emulation.control.incubating.VehiclePropValue
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.protobuf.InvalidProtocolBufferException
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertiesTab.VhalPropertyKey
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyType
import com.android.utils.throwIfCancellation
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.VisibleForTesting

/**
 * Extended controls dialog for Android Automotive OS emulators, providing controls for common car sensor data and a searchable
 * master-detail inspector/editor for Vehicle HAL (VHAL) properties.
 */
internal class CarDataDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("car.data.dialog.title", emulator.emulatorId.avdName)) {

  private val dialogJob = SupervisorJob(coroutineScope.coroutineContext[Job])
  private val dialogScope = CoroutineScope(coroutineScope.coroutineContext + dialogJob)

  var dictionary: VhalPropertyDictionary = VhalPropertyDictionary.DEFAULT
    private set

  private val sensorDataTab = CarSensorDataTab(this)
  private val vhalPropertiesTab = VhalPropertiesTab(this)

  private data class PendingUpdate(val propValue: VehiclePropValue, val isOptionalCompanion: Boolean)

  private val userModifiedKeys = HashSet<VhalPropertyKey>()
  private val streamUpdatedKeys = HashSet<VhalPropertyKey>()
  private val vhalConfirmedKeys = HashSet<VhalPropertyKey>()
  private val pendingUpdates = LinkedHashMap<VhalPropertyKey, PendingUpdate>()
  private val sentUpdatesQueue = ArrayDeque<PendingUpdate>()
  private var inFlightUpdateKey: VhalPropertyKey? = null
  private val sendSignal = Channel<Unit>(Channel.CONFLATED)

  private var refreshJob: Job? = null
  private var receiveEventsJob: Job? = null
  @Volatile private var grpcEventsReceived = false

  private val adbDeviceServices: AdbDeviceServices
    get() = (project?.let(AdbLibService::getSession) ?: AdbLibApplicationService.instance.session).deviceServices

  private val deviceSelector: DeviceSelector = DeviceSelector.fromSerialNumber(emulator.emulatorId.serialNumber)

  private val dictionaryDeferred: Deferred<VhalPropertyDictionary> =
    dialogScope.async(Dispatchers.IO) {
      // TODO: Replace reading from system image by accessing all needed information through gRPC once available (b/579958152).
      // TODO: Remove EmulatorConfig.systemImageDir once no longer used.
      VhalPropertyDictionary.loadFromDirectories(listOf(emulator.emulatorConfig.systemImageDir, emulator.emulatorId.avdFolder))
    }

  init {
    onDispose {
      sendSignal.close()
      dialogJob.cancel()
    }

    dialogScope.launch {
      while (sendSignal.receiveCatching().isSuccess) {
        while (true) {
          val next =
            synchronized(pendingUpdates) {
              val iterator = pendingUpdates.entries.iterator()
              if (!iterator.hasNext()) {
                inFlightUpdateKey = null
                null
              } else {
                val entry = iterator.next()
                iterator.remove()
                inFlightUpdateKey = entry.key
                if (grpcEventsReceived) {
                  if (sentUpdatesQueue.size >= MAX_SENT_UPDATES_QUEUE_SIZE) {
                    sentUpdatesQueue.removeFirst()
                  }
                  sentUpdatesQueue.addLast(entry.value)
                }
                entry.value
              }
            } ?: break
          try {
            sendEmulatorMessage(
              EmulatorMessage.newBuilder().setMsgType(MsgType.SET_PROPERTY_CMD).setStatus(Status.RESULT_OK).addValue(next.propValue).build()
            )
          } catch (t: Throwable) {
            t.throwIfCancellation()
            synchronized(pendingUpdates) {
              inFlightUpdateKey = null
              sentUpdatesQueue.remove(next)
            }
            withContext(uiContext) {
              handleSetPropertyFailure(next, t.message)
            }
          } finally {
            synchronized(pendingUpdates) {
              inFlightUpdateKey = null
            }
          }
        }
      }
    }

    dialogScope.launch {
      val loaded = dictionaryDeferred.await()
      withContext(uiContext) {
        dictionary = loaded
        vhalPropertiesTab.onDictionaryUpdated()
      }
    }
  }

  override fun createPanel(): DialogPanel {
    val tabbedPane =
      JBTabbedPane().apply {
        name = "carDataTabbedPane"
        tabComponentInsets = JBUI.emptyInsets()
        addTab(message("car.data.dialog.tab.sensor.data"), sensorDataTab.createPanel())
        addTab(message("car.data.dialog.tab.vhal.properties"), vhalPropertiesTab.createPanel())
      }

    startReceivingCarEvents()

    return panel {
      row {
        cell(tabbedPane).align(Align.FILL)
      }
        .resizableRow()
    }
  }

  private fun startReceivingCarEvents() {
    receiveEventsJob?.cancel()
    receiveEventsJob = dialogScope.launch {
      try {
        emulator
          .receiveCarEvents(
            onSubscribed = {
              withContext(uiContext) {
                refreshVhalProperties(isInitialLoad = true)
              }
            }
          )
          .collect { carEvent ->
            val emulatorMsg =
              try {
                EmulatorMessage.parseFrom(carEvent.data)
              } catch (_: InvalidProtocolBufferException) {
                return@collect
              }
            grpcEventsReceived = true
            withContext(uiContext) {
              processEmulatorMessage(emulatorMsg)
            }
          }
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
      }
    }
  }

  fun showErrorMessage(@Nls message: String) {
    showError(message)
  }

  fun clearErrorMessage() {
    clearError()
  }

  fun sendBooleanProperty(propId: Int, value: Boolean, areaId: Int = 0) {
    val propValue =
      VehiclePropValue.newBuilder()
        .setProp(propId)
        .setAreaId(areaId)
        .setStatus(VehiclePropStatus.AVAILABLE)
        .addInt32Values(if (value) 1 else 0)
        .build()
    sendPropertyValue(propValue, fromSensorTab = true)
  }

  fun sendInt32Property(propId: Int, value: Int, areaId: Int = 0) {
    val propValue =
      VehiclePropValue.newBuilder().setProp(propId).setAreaId(areaId).setStatus(VehiclePropStatus.AVAILABLE).addInt32Values(value).build()
    sendPropertyValue(propValue, fromSensorTab = true)
  }

  fun sendFloatProperty(propId: Int, value: Float, areaId: Int = 0, isOptionalCompanion: Boolean = false) {
    val propValue =
      VehiclePropValue.newBuilder().setProp(propId).setAreaId(areaId).setStatus(VehiclePropStatus.AVAILABLE).addFloatValues(value).build()
    sendPropertyValue(propValue, fromSensorTab = true, isOptionalCompanion = isOptionalCompanion)
  }

  fun sendPropertyValue(
    propValue: VehiclePropValue,
    fromSensorTab: Boolean = false,
    isOptionalCompanion: Boolean = false,
  ) {
    val key = VhalPropertyKey(propValue.prop, propValue.areaId)
    stateModified = true
    userModifiedKeys.add(key)
    clearError()
    if (!fromSensorTab) {
      sensorDataTab.applyPropertyValue(propValue)
    }
    vhalPropertiesTab.applyPropertyValue(propValue)
    synchronized(pendingUpdates) {
      pendingUpdates[key] = PendingUpdate(propValue, isOptionalCompanion)
    }
    sendSignal.trySend(Unit)
  }

  fun refreshVhalProperties(isInitialLoad: Boolean = false) {
    refreshJob?.cancel()
    if (!isInitialLoad) {
      userModifiedKeys.clear()
      streamUpdatedKeys.clear()
      clearError()
    }
    refreshJob = dialogScope.launch {
      var grpcException: Exception? = null
      try {
        sendEmulatorMessage(EmulatorMessage.newBuilder().setMsgType(MsgType.GET_CONFIG_ALL_CMD).setStatus(Status.RESULT_OK).build())
        sendEmulatorMessage(EmulatorMessage.newBuilder().setMsgType(MsgType.GET_PROPERTY_ALL_CMD).setStatus(Status.RESULT_OK).build())
      } catch (e: Exception) {
        e.throwIfCancellation()
        grpcException = e
      }
      // TODO: Remove ADB workaround once CarService.receiveCarEvents works in the emulator (b/580106156).
      if (!grpcEventsReceived) {
        fetchVhalPropertiesViaAdb(grpcException)
      }
    }
  }

  private fun processEmulatorMessage(emulatorMsg: EmulatorMessage) {
    when (emulatorMsg.msgType) {
      MsgType.GET_CONFIG_ALL_RESP,
      MsgType.GET_CONFIG_RESP -> {
        if (emulatorMsg.configCount > 0) {
          vhalPropertiesTab.setPropertyConfigs(emulatorMsg.configList)
        }
      }
      MsgType.GET_PROPERTY_ALL_RESP -> {
        applyFetchedProperties(emulatorMsg.valueList)
      }
      MsgType.GET_PROPERTY_RESP,
      MsgType.SET_PROPERTY_ASYNC -> {
        val valuesToApply = mutableListOf<VehiclePropValue>()
        val keysToRemove = mutableListOf<VhalPropertyKey>()
        for (propValue in emulatorMsg.valueList) {
          val key = VhalPropertyKey(propValue.prop, propValue.areaId)
          if (!VhalPropertiesTab.isSupportedPropertyValue(propValue)) {
            if (!hasPendingOrInFlightUpdate(key) && key !in userModifiedKeys) {
              vhalConfirmedKeys.remove(key)
              keysToRemove.add(key)
            }
            continue
          }
          vhalConfirmedKeys.add(key)
          if (
            propValue.prop == VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY &&
              propValue.status == VehiclePropStatus.AVAILABLE &&
              VhalPropertyKey(VehicleProperty.PERF_VEHICLE_SPEED, 0) in userModifiedKeys
          ) {
            sensorDataTab.onSpeedDisplaySupported()
          }
          if (!isStaleStreamUpdate(key, propValue)) {
            streamUpdatedKeys.add(key)
            sensorDataTab.applyPropertyValue(propValue)
            valuesToApply.add(propValue)
          }
        }
        vhalPropertiesTab.applyPropertyValues(valuesToApply, keysToRemove)
      }
      MsgType.SET_PROPERTY_RESP -> {
        val sentUpdate = synchronized(pendingUpdates) { sentUpdatesQueue.removeFirstOrNull() }
        if (emulatorMsg.hasStatus() && emulatorMsg.status != Status.RESULT_OK && sentUpdate != null) {
          handleSetPropertyFailure(sentUpdate, emulatorMsg.status.name)
        }
      }
      else -> {}
    }
  }

  private fun applyFetchedProperties(values: List<VehiclePropValue>) {
    vhalConfirmedKeys.clear()
    for (propValue in values) {
      if (VhalPropertiesTab.isSupportedPropertyValue(propValue)) {
        vhalConfirmedKeys.add(VhalPropertyKey(propValue.prop, propValue.areaId))
      }
      if (
        propValue.prop == VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY &&
          propValue.status == VehiclePropStatus.AVAILABLE &&
          VhalPropertyKey(VehicleProperty.PERF_VEHICLE_SPEED, 0) in userModifiedKeys
      ) {
        sensorDataTab.onSpeedDisplaySupported()
      }
    }
    val speedDisplayKey = VhalPropertyKey(VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY, 0)
    if (values.isNotEmpty() && speedDisplayKey !in vhalConfirmedKeys) {
      sensorDataTab.onSpeedDisplayWriteFailed()
      userModifiedKeys.remove(speedDisplayKey)
    }
    val nonStaleFetched = values.filter { propValue ->
      val key = VhalPropertyKey(propValue.prop, propValue.areaId)
      key !in userModifiedKeys && key !in streamUpdatedKeys && !hasPendingOrInFlightUpdate(key)
    }
    vhalPropertiesTab.setProperties(
      nonStaleFetched,
      retainExistingKey = { key -> key in userModifiedKeys || key in streamUpdatedKeys || hasPendingOrInFlightUpdate(key) },
    )
    for (propValue in nonStaleFetched) {
      sensorDataTab.applyPropertyValue(propValue)
    }
  }

  private suspend fun fetchVhalPropertiesViaAdb(grpcException: Exception? = null) {
    val lines = ArrayList<String>()
    try {
      adbDeviceServices.shellAsLines(deviceSelector, DUMP_VHAL_COMMAND).collect { element ->
        if (element is ShellCommandOutputElement.StdoutLine) {
          lines.add(element.contents)
        }
      }
    } catch (e: Exception) {
      e.throwIfCancellation()
      thisLogger().warn("Failed to query VHAL properties via ADB", e)
      withContext(uiContext) {
        if (!grpcEventsReceived) {
          showError(grpcException ?: e)
        }
      }
      return
    }
    if (grpcEventsReceived) {
      return
    }
    if (lines.isEmpty()) {
      if (grpcException != null) {
        withContext(uiContext) {
          if (!grpcEventsReceived) {
            showError(grpcException)
          }
        }
      }
      return
    }
    val (configs, values) = parseAdbVhalDump(lines)
    if (configs.isEmpty() && values.isEmpty()) {
      if (grpcException != null) {
        withContext(uiContext) {
          if (!grpcEventsReceived) {
            showError(grpcException)
          }
        }
      }
      return
    }
    withContext(uiContext) {
      if (!grpcEventsReceived) {
        if (configs.isNotEmpty()) {
          vhalPropertiesTab.setPropertyConfigs(configs)
        }
        if (values.isNotEmpty()) {
          applyFetchedProperties(values)
        }
      }
    }
  }

  private fun handleSetPropertyFailure(failedUpdate: PendingUpdate, errorMessage: String?) {
    val key = VhalPropertyKey(failedUpdate.propValue.prop, failedUpdate.propValue.areaId)
    if (failedUpdate.isOptionalCompanion) {
      sensorDataTab.onSpeedDisplayWriteFailed()
    } else {
      errorMessage?.let(::showError)
    }
    if (!hasPendingOrInFlightUpdate(key)) {
      userModifiedKeys.remove(key)
      if (key in vhalConfirmedKeys) {
        requestPropertyValue(key.propId, key.areaId)
      } else {
        vhalPropertiesTab.removeProperty(key)
      }
    }
  }

  private fun requestPropertyValue(propId: Int, areaId: Int) {
    dialogScope.launch {
      try {
        val getProp = VehiclePropGet.newBuilder().setProp(propId).setAreaId(areaId).build()
        sendEmulatorMessage(
          EmulatorMessage.newBuilder().setMsgType(MsgType.GET_PROPERTY_CMD).setStatus(Status.RESULT_OK).addProp(getProp).build()
        )
      } catch (t: Throwable) {
        t.throwIfCancellation()
      }
    }
  }

  private suspend fun sendEmulatorMessage(emulatorMessage: EmulatorMessage) {
    emulator.callWithRetry { sendCarEvent(CarEvent.newBuilder().setData(emulatorMessage.toByteString()).build()) }
  }

  private fun hasPendingOrInFlightUpdate(key: VhalPropertyKey): Boolean =
    synchronized(pendingUpdates) {
      pendingUpdates.containsKey(key) ||
        inFlightUpdateKey == key ||
        sentUpdatesQueue.any { it.propValue.prop == key.propId && it.propValue.areaId == key.areaId }
    }

  private fun isStaleStreamUpdate(key: VhalPropertyKey, incoming: VehiclePropValue): Boolean =
    synchronized(pendingUpdates) {
      if (pendingUpdates.containsKey(key) || inFlightUpdateKey == key) {
        return true
      }
      val latestSent = sentUpdatesQueue.lastOrNull { it.propValue.prop == key.propId && it.propValue.areaId == key.areaId } ?: return false
      !hasSamePropertyValue(latestSent.propValue, incoming)
    }

  private fun hasSamePropertyValue(a: VehiclePropValue, b: VehiclePropValue): Boolean =
    a.prop == b.prop &&
      a.areaId == b.areaId &&
      a.status == b.status &&
      a.int32ValuesList == b.int32ValuesList &&
      a.int64ValuesList == b.int64ValuesList &&
      a.floatValuesList == b.floatValuesList &&
      a.stringValue == b.stringValue &&
      a.bytesValue == b.bytesValue

  companion object {
    private const val MAX_SENT_UPDATES_QUEUE_SIZE = 64

    @VisibleForTesting const val DUMP_VHAL_COMMAND = "cmd car_service get-carpropertyconfig; cmd car_service get-property-value"

    private val PROP_ID_REGEX = Regex("""Property(?:\s+ID)?:\s*(?:[A-Za-z0-9_]+\()?(0x[0-9a-fA-F]+|-?\d+)\)?""")
    private val ACCESS_REGEX = Regex("""\baccess:\s*(?:[A-Za-z0-9_]+\()?(0x[0-9a-fA-F]+|-?\d+)\)?""")
    private val CHANGE_MODE_REGEX = Regex("""\bchangeMode:\s*(?:[A-Za-z0-9_]+\()?(0x[0-9a-fA-F]+|-?\d+)\)?""")
    private val CONFIG_ARRAY_REGEX = Regex("""\bconfig(?:Array)?:\s*\[([^\]]*)]""")
    private val MIN_SAMPLE_RATE_REGEX = Regex("""\b(?:fs min|minSampleRateHz):\s*([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""")
    private val MAX_SAMPLE_RATE_REGEX = Regex("""\b(?:fs max|maxSampleRateHz):\s*([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""")
    private val AREA_CONFIG_ID_REGEX = Regex("""^\s*areaId:\s*(?:[^,]*?\()?(0x[0-9a-fA-F]+|-?\d+)\)?""")
    private val F_MIN_REGEX = Regex("""\bf min:\s*([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""")
    private val F_MAX_REGEX = Regex("""\bf max:\s*([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""")
    private val I_MIN_REGEX = Regex("""\bi min:\s*(-?\d+)""")
    private val I_MAX_REGEX = Regex("""\bi max:\s*(-?\d+)""")
    private val I64_MIN_REGEX = Regex("""\bi64 min:\s*(-?\d+)""")
    private val I64_MAX_REGEX = Regex("""\bi64 max:\s*(-?\d+)""")

    private val VALUE_AREA_ID_REGEX =
      Regex("""(?:Area ID|zone):\s*(?:.*?)\((0x[0-9a-fA-F]+|-?\d+)\)|(?:Area ID|zone):\s*(0x[0-9a-fA-F]+|-?\d+)""")
    private val VALUE_STATUS_REGEX = Regex("""(?:SystemStatus|Status|status):\s*(?:[A-Za-z0-9_]+\()?(0x[0-9a-fA-F]+|-?\d+)\)?""")
    private val VALUE_TIMESTAMP_REGEX = Regex("""(?:ElapsedRealtimeNanos|timestamp):\s*(-?\d+)""")
    private val MODERN_VALUE_SUFFIX_REGEX = Regex("""Value:\s*(.*)}$""")
    private val FLOAT_VALUES_REGEX = Regex("""floatValues:\s*\[([^\]]*)]""")
    private val INT32_VALUES_REGEX = Regex("""int32Values:\s*\[([^\]]*)]""")
    private val INT64_VALUES_REGEX = Regex("""int64Values:\s*\[([^\]]*)]""")
    private val BYTES_VALUES_REGEX = Regex("""bytes:\s*\[([^\]]*)]""")
    private val STRING_VALUE_REGEX = Regex("""string:\s*(.*)$""")
    private val ENUM_OR_HEX_VALUE_REGEX = Regex("""^(?:[A-Za-z0-9_]+\()?(0x[0-9a-fA-F]+|-?\d+)\)?(?:\s+[A-Za-z0-9_]+)?$""")

    @VisibleForTesting
    fun parseAdbVhalDump(lines: Iterable<String>): Pair<List<VehiclePropConfig>, List<VehiclePropValue>> {
      val configs = ArrayList<VehiclePropConfig>()
      val values = ArrayList<VehiclePropValue>()
      var inValuesSection = false
      var currentConfig: VehiclePropConfig.Builder? = null

      fun flushConfig() {
        currentConfig?.let { configs.add(it.build()) }
        currentConfig = null
      }

      for (rawLine in lines) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("**All properties")) {
          flushConfig()
          inValuesSection = false
          continue
        }
        if (trimmed.startsWith("**All property values")) {
          flushConfig()
          inValuesSection = true
          continue
        }

        if (trimmed.startsWith("HalPropValue{") || (inValuesSection && trimmed.startsWith("Property:"))) {
          flushConfig()
          inValuesSection = true
          parseAdbPropValue(trimmed)?.let(values::add)
          continue
        }

        if (!inValuesSection) {
          if (trimmed.startsWith("Property:")) {
            flushConfig()
            val propId = PROP_ID_REGEX.find(trimmed)?.groupValues?.get(1)?.let(::parseIntLiteral) ?: continue
            val builder = VehiclePropConfig.newBuilder().setProp(propId).setValueType(propId and VehiclePropertyType.MASK)
            applyConfigAttributes(builder, trimmed)
            currentConfig = builder
          } else if (trimmed.startsWith("areaId:")) {
            val builder = currentConfig ?: continue
            val areaId = AREA_CONFIG_ID_REGEX.find(trimmed)?.groupValues?.get(1)?.let(::parseIntLiteral) ?: continue
            val areaBuilder = VehicleAreaConfig.newBuilder().setAreaId(areaId)
            F_MIN_REGEX.find(trimmed)?.groupValues?.get(1)?.toFloatOrNull()?.let(areaBuilder::setMinFloatValue)
            F_MAX_REGEX.find(trimmed)?.groupValues?.get(1)?.toFloatOrNull()?.let(areaBuilder::setMaxFloatValue)
            I_MIN_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()?.let(areaBuilder::setMinInt32Value)
            I_MAX_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()?.let(areaBuilder::setMaxInt32Value)
            I64_MIN_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()?.let(areaBuilder::setMinInt64Value)
            I64_MAX_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()?.let(areaBuilder::setMaxInt64Value)
            builder.addAreaConfigs(areaBuilder.build())
          } else {
            currentConfig?.let { applyConfigAttributes(it, trimmed) }
          }
        }
      }
      flushConfig()
      return configs to values
    }

    private fun applyConfigAttributes(builder: VehiclePropConfig.Builder, line: String) {
      ACCESS_REGEX.find(line)?.groupValues?.get(1)?.let(::parseIntLiteral)?.let(builder::setAccess)
      CHANGE_MODE_REGEX.find(line)?.groupValues?.get(1)?.let(::parseIntLiteral)?.let(builder::setChangeMode)
      MIN_SAMPLE_RATE_REGEX.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let(builder::setMinSampleRate)
      MAX_SAMPLE_RATE_REGEX.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let(builder::setMaxSampleRate)
      CONFIG_ARRAY_REGEX.find(line)?.groupValues?.get(1)?.let { arrayStr ->
        if (builder.configArrayCount == 0 && arrayStr.isNotBlank()) {
          for (elem in arrayStr.split(',')) {
            parseIntLiteral(elem.trim())?.let(builder::addConfigArray)
          }
        }
      }
    }

    private fun parseAdbPropValue(line: String): VehiclePropValue? {
      val propId = PROP_ID_REGEX.find(line)?.groupValues?.get(1)?.let(::parseIntLiteral) ?: return null
      val areaMatch = VALUE_AREA_ID_REGEX.find(line)
      val areaId = (areaMatch?.groupValues?.get(1)?.ifEmpty { areaMatch.groupValues[2] })?.let(::parseIntLiteral) ?: 0
      val statusCode = VALUE_STATUS_REGEX.find(line)?.groupValues?.get(1)?.let(::parseIntLiteral) ?: 0
      val status = VehiclePropStatus.forNumber(statusCode) ?: VehiclePropStatus.UNAVAILABLE
      val timestamp = VALUE_TIMESTAMP_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
      val valueType = propId and VehiclePropertyType.MASK

      val builder =
        VehiclePropValue.newBuilder().setProp(propId).setValueType(valueType).setAreaId(areaId).setStatus(status).setTimestamp(timestamp)

      if (line.startsWith("HalPropValue{")) {
        val rawValue = MODERN_VALUE_SUFFIX_REGEX.find(line)?.groupValues?.get(1) ?: return builder.build()
        parseModernHalPropValuePayload(builder, valueType, rawValue)
      } else {
        parseStructuredValuePayload(builder, line)
      }
      return builder.build()
    }

    private fun parseModernHalPropValuePayload(builder: VehiclePropValue.Builder, valueType: Int, rawValue: String) {
      if (rawValue.trim() == "NO_VALUE") {
        return
      }
      when (valueType) {
        VehiclePropertyType.STRING -> builder.setStringValue(rawValue)
        VehiclePropertyType.BOOLEAN -> {
          val trimmed = rawValue.trim()
          val boolInt =
            when {
              trimmed.equals("TRUE", ignoreCase = true) -> 1
              trimmed.equals("FALSE", ignoreCase = true) -> 0
              else -> parseScalarInt(trimmed) ?: return
            }
          builder.addInt32Values(boolInt)
        }
        VehiclePropertyType.INT32 -> {
          parseScalarInt(rawValue.trim())?.let(builder::addInt32Values)
        }
        VehiclePropertyType.INT32_VEC -> {
          for (elem in extractBracketElements(rawValue)) {
            parseScalarInt(elem)?.let(builder::addInt32Values)
          }
        }
        VehiclePropertyType.INT64 -> {
          parseScalarLong(rawValue.trim())?.let(builder::addInt64Values)
        }
        VehiclePropertyType.INT64_VEC -> {
          for (elem in extractBracketElements(rawValue)) {
            parseScalarLong(elem)?.let(builder::addInt64Values)
          }
        }
        VehiclePropertyType.FLOAT -> {
          parseScalarFloat(rawValue.trim())?.let(builder::addFloatValues)
        }
        VehiclePropertyType.FLOAT_VEC -> {
          for (elem in extractBracketElements(rawValue)) {
            parseScalarFloat(elem)?.let(builder::addFloatValues)
          }
        }
        VehiclePropertyType.BYTES -> {
          val byteList = ArrayList<Byte>()
          for (elem in extractBracketElements(rawValue)) {
            parseIntLiteral(elem)?.toByte()?.let(byteList::add)
          }
          if (byteList.isNotEmpty()) {
            builder.setBytesValue(ByteString.copyFrom(byteList.toByteArray()))
          }
        }
        VehiclePropertyType.MIXED -> {
          parseStructuredValuePayload(builder, rawValue)
        }
      }
    }

    private fun parseStructuredValuePayload(builder: VehiclePropValue.Builder, text: String) {
      FLOAT_VALUES_REGEX.find(text)?.groupValues?.get(1)?.let { listStr ->
        for (elem in splitNonBlank(listStr)) {
          parseScalarFloat(elem)?.let(builder::addFloatValues)
        }
      }
      INT32_VALUES_REGEX.find(text)?.groupValues?.get(1)?.let { listStr ->
        for (elem in splitNonBlank(listStr)) {
          parseScalarInt(elem)?.let(builder::addInt32Values)
        }
      }
      INT64_VALUES_REGEX.find(text)?.groupValues?.get(1)?.let { listStr ->
        for (elem in splitNonBlank(listStr)) {
          parseScalarLong(elem)?.let(builder::addInt64Values)
        }
      }
      BYTES_VALUES_REGEX.find(text)?.groupValues?.get(1)?.let { listStr ->
        val bytes = splitNonBlank(listStr).mapNotNull { parseIntLiteral(it)?.toByte() }.toByteArray()
        if (bytes.isNotEmpty()) {
          builder.setBytesValue(ByteString.copyFrom(bytes))
        }
      }
      STRING_VALUE_REGEX.find(text)?.groupValues?.get(1)?.let { str ->
        if (str.isNotEmpty() || builder.valueType == VehiclePropertyType.STRING) {
          builder.setStringValue(str)
        }
      }
    }

    private fun extractBracketElements(rawValue: String): List<String> {
      val trimmed = rawValue.trim()
      val content = trimmed.removePrefix("[").removeSuffix("]")
      return splitNonBlank(content)
    }

    private fun splitNonBlank(content: String): List<String> {
      if (content.isBlank()) return emptyList()
      return content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseScalarInt(token: String): Int? {
      val match = ENUM_OR_HEX_VALUE_REGEX.matchEntire(token.trim())
      if (match != null) {
        return parseIntLiteral(match.groupValues[1])
      }
      val firstPart = token.trim().substringBefore(' ')
      return parseIntLiteral(firstPart)
    }

    private fun parseScalarLong(token: String): Long? {
      val firstPart = token.trim().substringBefore(' ')
      return parseLongLiteral(firstPart)
    }

    private fun parseScalarFloat(token: String): Float? {
      val firstPart = token.trim().substringBefore(' ')
      return firstPart.toFloatOrNull()
    }

    private fun parseIntLiteral(literal: String): Int? {
      val trimmed = literal.trim()
      return try {
        if (trimmed.startsWith("0x", ignoreCase = true)) {
          java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16).toInt()
        } else {
          trimmed.toInt()
        }
      } catch (_: NumberFormatException) {
        null
      }
    }

    private fun parseLongLiteral(literal: String): Long? {
      val trimmed = literal.trim()
      return try {
        if (trimmed.startsWith("0x", ignoreCase = true)) {
          java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16)
        } else {
          trimmed.toLong()
        }
      } catch (_: NumberFormatException) {
        null
      }
    }
  }
}
