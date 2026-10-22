/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.annotations.concurrency.AnyThread
import com.android.annotations.concurrency.GuardedBy
import com.android.annotations.concurrency.Slow
import com.android.emulation.control.incubating.ActiveCalls
import com.android.emulation.control.incubating.AnimationState
import com.android.emulation.control.incubating.Call
import com.android.emulation.control.incubating.CellInfo
import com.android.emulation.control.incubating.ModemGrpc
import com.android.emulation.control.incubating.PhoneEvent
import com.android.emulation.control.incubating.Poster
import com.android.emulation.control.incubating.PosterList
import com.android.emulation.control.incubating.SmsMessage
import com.android.emulation.control.incubating.VirtualSceneServiceGrpc
import com.android.emulator.control.AudioPacket
import com.android.emulator.control.BatteryState
import com.android.emulator.control.CameraList
import com.android.emulator.control.ClipData
import com.android.emulator.control.DisplayConfigurations
import com.android.emulator.control.DisplayMode
import com.android.emulator.control.DisplayModeValue
import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.EmulatorStatus
import com.android.emulator.control.Environment
import com.android.emulator.control.ExtendedControlsStatus
import com.android.emulator.control.Fingerprint
import com.android.emulator.control.Image
import com.android.emulator.control.ImageFormat
import com.android.emulator.control.InputEvent
import com.android.emulator.control.MicrophoneState
import com.android.emulator.control.Notification
import com.android.emulator.control.PaneEntry
import com.android.emulator.control.PhysicalModelValue
import com.android.emulator.control.Posture.PostureValue
import com.android.emulator.control.RotationRadian
import com.android.emulator.control.SnapshotFilter
import com.android.emulator.control.SnapshotList
import com.android.emulator.control.SnapshotPackage
import com.android.emulator.control.SnapshotServiceGrpc
import com.android.emulator.control.ThemingStyle
import com.android.emulator.control.UiControllerGrpc
import com.android.emulator.control.Velocity
import com.android.emulator.control.VmRunState
import com.android.emulator.control.XrOptions
import com.android.ide.common.util.Cancelable
import com.android.tools.adtui.device.SkinDefinition
import com.android.tools.adtui.device.SkinDefinitionCache
import com.android.tools.idea.avdmanager.RunningAvdTracker
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.flags.StudioFlags.EMBEDDED_EMULATOR_TRACE_GRPC_CALLS
import com.android.tools.idea.flags.StudioFlags.EMBEDDED_EMULATOR_TRACE_HIGH_VOLUME_GRPC_CALLS
import com.android.tools.idea.io.grpc.CallCredentials
import com.android.tools.idea.io.grpc.ClientCall
import com.android.tools.idea.io.grpc.CompressorRegistry
import com.android.tools.idea.io.grpc.ConnectivityState
import com.android.tools.idea.io.grpc.DecompressorRegistry
import com.android.tools.idea.io.grpc.KnownLength
import com.android.tools.idea.io.grpc.ManagedChannel
import com.android.tools.idea.io.grpc.Metadata
import com.android.tools.idea.io.grpc.MethodDescriptor
import com.android.tools.idea.io.grpc.MethodDescriptor.Marshaller
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.android.tools.idea.io.grpc.stub.ClientCallStreamObserver
import com.android.tools.idea.io.grpc.stub.ClientCalls
import com.android.tools.idea.io.grpc.stub.ClientResponseObserver
import com.android.tools.idea.io.grpc.stub.StreamObserver
import com.android.tools.idea.protobuf.CodedInputStream
import com.android.tools.idea.protobuf.Empty
import com.android.tools.idea.protobuf.ExtensionRegistryLite
import com.android.tools.idea.protobuf.InvalidProtocolBufferException
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.protobuf.UnsafeByteOperations
import com.android.tools.idea.protobuf.WireFormat
import com.android.tools.idea.streaming.core.StreamingDeviceId
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.util.containers.DisposableWrapperList
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap
import java.io.IOException
import java.io.InputStream
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.annotations.TestOnly

/** Controls a running Emulator. */
class EmulatorController(val emulatorId: EmulatorId, parentDisposable: Disposable) : UserDataHolderBase(), Disposable {

  val deviceId: StreamingDeviceId = StreamingDeviceId.ofEmulator(emulatorId)
  private val log = Logger.getInstance("EmulatorController: ${emulatorId.avdName}")
  private val imageResponseMarshaller = ImageResponseMarshaller()
  private val streamScreenshotMethod =
    EmulatorControllerGrpc.getStreamScreenshotMethod()
      .toBuilder(EmulatorControllerGrpc.getStreamScreenshotMethod().requestMarshaller, imageResponseMarshaller)
      .build()
  private var connectionReference = AtomicReference<Connection>()
  @Volatile private var emulatorConfigInternal: EmulatorConfiguration? = null
  @Volatile private var defaultSkin: SkinDefinition? = null
  @Volatile private var postureSkins = emptyMap<PostureValue, SkinDefinition>()
  private val coroutineScope = createCoroutineScope()
  private var keepAliveJob: Job? = null
  private val connectionStateReference = AtomicReference(ConnectionState.NOT_INITIALIZED)
  private val emulatorState = AtomicReference(EmulatorState.RUNNING)
  private val connectionStateListeners = DisposableWrapperList<ConnectionStateListener>()
  private val runningAvdTracker = RunningAvdTracker.getInstance()
  @GuardedBy("this") private var inputEventSender: StreamObserver<InputEvent>? = null

  var emulatorConfig: EmulatorConfiguration
    get() {
      return emulatorConfigInternal ?: throw IllegalStateException("Emulator configuration has not been loaded")
    }
    private inline set(value) {
      emulatorConfigInternal = value
    }

  val connectionState: ConnectionState
    get() = connectionStateReference.get()

  /** Returns true if [shutdown] has been called. */
  val isShuttingDown: Boolean
    get() = emulatorState.get() != EmulatorState.RUNNING

  val emulatorControllerStub: EmulatorControllerGrpc.EmulatorControllerStub
    get() = connectionReference.get()?.emulatorControllerStub ?: throwNotYetConnected()

  val snapshotServiceStub: SnapshotServiceGrpc.SnapshotServiceStub
    get() = connectionReference.get()?.snapshotServiceStub ?: throwNotYetConnected()

  val uiControllerStub: UiControllerGrpc.UiControllerStub
    get() = connectionReference.get()?.uiControllerStub ?: throwNotYetConnected()

  val modemStub: ModemGrpc.ModemStub
    get() = connectionReference.get()?.modemStub ?: throwNotYetConnected()

  val virtualSceneStub: VirtualSceneServiceGrpc.VirtualSceneServiceStub
    get() = connectionReference.get()?.virtualSceneStub ?: throwNotYetConnected()

  init {
    Disposer.register(parentDisposable, this)
  }

  /** The listener is removed when [disposable] is disposed. */
  @AnyThread
  fun addConnectionStateListener(listener: ConnectionStateListener, disposable: Disposable) {
    connectionStateListeners.add(listener, disposable)
    listener.connectionStateChanged(this, connectionState)
  }

  /** If the listener is [Disposable], it is automatically removed upon disposal. */
  @AnyThread
  fun addConnectionStateListener(listener: ConnectionStateListener) {
    if (listener is Disposable) {
      addConnectionStateListener(listener, listener)
    } else {
      connectionStateListeners.add(listener)
      listener.connectionStateChanged(this, connectionState)
    }
  }

  @AnyThread
  fun removeConnectionStateListener(listener: ConnectionStateListener) {
    connectionStateListeners.remove(listener)
  }

  /** Returns true if the connection state has been changed. */
  private fun updateConnectionState(oldState: ConnectionState, newState: ConnectionState): Boolean {
    if (connectionStateReference.compareAndSet(oldState, newState)) {
      if (newState == ConnectionState.DISCONNECTED) {
        if (oldState == ConnectionState.CONNECTED) {
          log.info("Disconnected from ${emulatorConfig.avdName} on port ${emulatorId.grpcPort}")
        } else {
          log.warn("Unable to connect to ${emulatorConfig.avdName} on port ${emulatorId.grpcPort}")
        }
      }
      for (listener in connectionStateListeners) {
        ActivityTracker.getInstance().inc() // Trigger toolbar updates.
        listener.connectionStateChanged(this, connectionState)
      }
      return true
    }
    return false
  }

  /**
   * Establishes a connection to the Emulator. The process of establishing a connection is partially asynchronous, but the synchronous part
   * of this method also takes considerable time.
   */
  @Slow
  fun connect() {
    if (emulatorConfigInternal == null) {
      loadEmulatorConfiguration()
    }
    val config = emulatorConfig
    if (!config.isValid) {
      // The error has already been logged.
      updateConnectionState(ConnectionState.NOT_INITIALIZED, ConnectionState.DISCONNECTED)
      return
    }
    try {
      loadSkins(config)
    } catch (e: Throwable) {
      log.warn("Unable to load skins", e)
    }

    val maxDisplayPixels =
      (config.displayWidth * config.displayHeight)
        .coerceAtLeast(config.displayModes.maxOfOrZero { it.displaySize.width * it.displaySize.height })
        .coerceAtLeast(config.additionalDisplays.values.maxOfOrZero { it.width * it.height })
        .coerceAtLeast(config.environmentSize?.let { it.width * it.height } ?: 0)
    val maxInboundMessageSize = maxDisplayPixels * 3 + 100 // Three bytes per pixel plus some overhead.
    connectGrpcOrIncreaseMaxInboundMessageSize(maxInboundMessageSize)
  }

  /** Loads emulator configuration from disk. Returns true if successful. */
  @Slow
  fun loadEmulatorConfiguration(): Boolean {
    emulatorConfig =
      try {
        EmulatorConfiguration.readAvdDefinition(emulatorId.avdFolder)
      } catch (e: Exception) {
        val message = e.message ?: e.javaClass.name
        log.warn("Error while loading configuration for ${emulatorId.avdName} - $message")
        EmulatorConfiguration.createStub(emulatorId.avdName, emulatorId.avdFolder)
      }
    return emulatorConfig.isValid
  }

  private fun loadSkins(config: EmulatorConfiguration) {
    val skinFolder = config.skinFolder
    if (skinFolder != null) {
      if (config.postures.isEmpty()) {
        defaultSkin = SkinDefinitionCache.getInstance().getSkinDefinition(skinFolder)
      } else {
        defaultSkin = SkinDefinitionCache.getInstance().getSkinDefinition(skinFolder.resolve("default"))
        val skins = Object2ObjectArrayMap<PostureValue, SkinDefinition>()
        for (posture in config.postures) {
          val dir = posture.posture.name.substringAfter("_").lowercase(Locale.getDefault())
          val folder = skinFolder.resolve(dir)
          if (Files.exists(folder.resolve("layout"))) {
            val skin = SkinDefinitionCache.getInstance().getSkinDefinition(folder)
            if (skin != null) {
              skins[posture.posture] = skin
            }
          }
        }
        postureSkins = skins
      }
    }
  }

  /**
   * Establishes a gRPC connection to the Emulator, or recreates the connection if the [maxInboundMessageSize] parameter exceeds the value
   * supported by the current connection.
   */
  @Slow
  fun connectGrpcOrIncreaseMaxInboundMessageSize(maxInboundMessageSize: Int = 4 * 1024 * 1024) {
    val oldConnection = connectionReference.get()
    if (oldConnection != null && oldConnection.maxInboundMessageSize >= maxInboundMessageSize) {
      return
    }

    updateConnectionState(ConnectionState.NOT_INITIALIZED, ConnectionState.CONNECTING)
    val channel =
      service<GrpcChannelBuilderFactory>()
        .newGrpcChannelBuilder("localhost", emulatorId.grpcPort)
        .usePlaintext() // TODO: Add support for TLS encryption.
        .maxInboundMessageSize(maxInboundMessageSize)
        .compressorRegistry(CompressorRegistry.newEmptyInstance()) // Disable data compression.
        .decompressorRegistry(DecompressorRegistry.emptyInstance())
        .build()

    val emulatorControllerStub: EmulatorControllerGrpc.EmulatorControllerStub
    val snapshotServiceStub: SnapshotServiceGrpc.SnapshotServiceStub
    val uiControllerStub: UiControllerGrpc.UiControllerStub
    val modemStub: ModemGrpc.ModemStub
    val virtualSceneStub: VirtualSceneServiceGrpc.VirtualSceneServiceStub
    val token = emulatorId.grpcToken
    if (token == null) {
      emulatorControllerStub = EmulatorControllerGrpc.newStub(channel)
      snapshotServiceStub = SnapshotServiceGrpc.newStub(channel)
      uiControllerStub = UiControllerGrpc.newStub(channel)
      modemStub = ModemGrpc.newStub(channel)
      virtualSceneStub = VirtualSceneServiceGrpc.newStub(channel)
    } else {
      val credentials = TokenCallCredentials(token)
      emulatorControllerStub = EmulatorControllerGrpc.newStub(channel).withCallCredentials(credentials)
      snapshotServiceStub = SnapshotServiceGrpc.newStub(channel).withCallCredentials(credentials)
      uiControllerStub = UiControllerGrpc.newStub(channel).withCallCredentials(credentials)
      modemStub = ModemGrpc.newStub(channel).withCallCredentials(credentials)
      virtualSceneStub = VirtualSceneServiceGrpc.newStub(channel).withCallCredentials(credentials)
    }

    val connection =
      Connection(
        channel,
        maxInboundMessageSize,
        emulatorControllerStub,
        snapshotServiceStub,
        uiControllerStub,
        modemStub,
        virtualSceneStub,
      )

    val connectivityStateWatcher =
      object : Runnable {
        override fun run() {
          if (connection != connectionReference.get()) {
            return
          }
          val state = channel.getState(false)
          if (state == ConnectivityState.SHUTDOWN) {
            if (!updateConnectionState(ConnectionState.CONNECTING, ConnectionState.DISCONNECTED)) {
              updateConnectionState(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
            }
          } else {
            if (state == ConnectivityState.READY) {
              connectionReference.compareAndSet(oldConnection, connection)
              updateConnectionState(ConnectionState.CONNECTING, ConnectionState.CONNECTED)
            }
            channel.notifyWhenStateChanged(state, this)
          }
        }
      }

    if (connectionReference.compareAndSet(oldConnection, connection)) {
      inputEventSender = null
      connectivityStateWatcher.run()
      keepAliveJob?.cancel()
      sendKeepAlive()
      oldConnection?.channel?.shutdown()
    } else {
      connection.channel.shutdown()
    }
  }

  internal fun getSkin(posture: PostureValue? = null): SkinDefinition? = posture?.let { postureSkins[posture] } ?: defaultSkin

  private val channel
    get() = connectionReference.get()?.channel

  /** Sends a shutdown command to the emulator. Subsequent [shutdown] calls are ignored. */
  fun shutdown() {
    if (
      emulatorState.compareAndSet(EmulatorState.RUNNING, EmulatorState.SHUTDOWN_REQUESTED) && connectionState == ConnectionState.CONNECTED
    ) {
      sendShutdown()
      channel?.shutdown()
    }
  }

  private fun sendShutdown() {
    if (emulatorState.compareAndSet(EmulatorState.SHUTDOWN_REQUESTED, EmulatorState.SHUTDOWN_SENT)) {
      keepAliveJob?.cancel()
      val vmRunState = VmRunState.newBuilder().setState(VmRunState.RunState.SHUTDOWN).build()
      setVmState(vmRunState, getEmptyObserver())
      runningAvdTracker.shuttingDown(emulatorId.avdFolder)
    }
  }

  /** Waits for the termination of the gRPC channel. Shutdown should have been requested before calling this method. */
  @TestOnly
  internal fun awaitTermination(timeout: Duration) {
    channel?.awaitTermination(timeout.toLong(DurationUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  }

  /**
   * Pushes audio packets into the emulator microphone.
   *
   * Usually multiple packages need to be sent to push one audio file, including several AudioPackets that the injected audio file will be
   * divided into. The implementation of the stream observer must handle asynchronous events correctly, especially when pushing big files.
   * This is usually done by overwriting [ClientResponseObserver.beforeStart] and calling
   * [io.grpc.stub.CallStreamObserver.setOnReadyHandler] from there.
   *
   * @param streamObserver a client stream observer to handle events
   * @return a StreamObserver that can be used to trigger the push
   */
  fun injectAudio(streamObserver: ClientResponseObserver<AudioPacket, Empty>): StreamObserver<AudioPacket> {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("injectAudio()")
    }
    return emulatorControllerStub.injectAudio(
      DelegatingClientResponseObserver(streamObserver, EmulatorControllerGrpc.getInjectAudioMethod())
    )
  }

  /** Pushes audio packets into the emulator microphone. */
  suspend fun injectAudio(audioPackets: Flow<AudioPacket>) {
    callClientStreaming(audioPackets) { responseObserver -> injectAudio(responseObserver) }
  }

  /** Sets contents of the clipboard. */
  fun setClipboard(clipData: ClipData, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      // Don't log the actual clipboard contents to protect user privacy.
      val clipDataForLogging = shortDebugString(clipData.toBuilder().setText("<clipboard contents>").build())
      log.info("setClipboard($clipDataForLogging)")
    }
    emulatorControllerStub.setClipboard(clipData, DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetClipboardMethod()))
  }

  /** Sets contents of the clipboard. */
  suspend fun setClipboard(clipData: ClipData) {
    val observer = SuspendingStreamObserver<Empty>()
    setClipboard(clipData, observer)
    observer.getResult()
  }

  /** Streams contents of the clipboard. */
  fun streamClipboard(streamObserver: StreamObserver<ClipData>): Cancelable {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("streamClipboard()")
    }
    val method = EmulatorControllerGrpc.getStreamClipboardMethod()
    val call = emulatorControllerStub.channel.newCall(method, emulatorControllerStub.callOptions)
    ClientCalls.asyncServerStreamingCall(call, EMPTY_PROTO, DelegatingStreamObserver(streamObserver, method))
    return CancelableClientCall(call)
  }

  /** Streams contents of the clipboard. */
  fun streamClipboard(): Flow<ClipData> = callbackFlow {
    val cancelable = streamClipboard(FlowStreamObserver(this))
    awaitClose { cancelable.cancel() }
  }

  /** Sets the size of the primary display of a resizable AVD. */
  fun setDisplayMode(displayModeValue: DisplayModeValue, streamObserver: StreamObserver<Empty>) {
    val displayMode = DisplayMode.newBuilder().setValue(displayModeValue).build()
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setDisplayMode(${shortDebugString(displayMode)})")
    }
    emulatorControllerStub.setDisplayMode(
      displayMode,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetDisplayModeMethod()),
    )
  }

  /** Sets the size of the primary display of a resizable AVD. */
  suspend fun setDisplayMode(displayModeValue: DisplayModeValue) {
    val observer = SuspendingStreamObserver<Empty>()
    setDisplayMode(displayModeValue, observer)
    observer.getResult()
  }

  /** Sets the XR-related options. */
  fun setXrOptions(xrOptions: XrOptions, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setXrOptions(${shortDebugString(xrOptions)})")
    }
    emulatorControllerStub.setXrOptions(xrOptions, DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetXrOptionsMethod()))
  }

  /** Sets the XR-related options. */
  suspend fun setXrOptions(xrOptions: XrOptions) {
    val observer = SuspendingStreamObserver<Empty>()
    setXrOptions(xrOptions, observer)
    observer.getResult()
  }

  /** Retrieves the XR-related options. */
  fun getXrOptions(streamObserver: StreamObserver<XrOptions>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getXrOptions()")
    }
    emulatorControllerStub.getXrOptions(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetXrOptionsMethod()),
    )
  }

  /** Retrieves the XR-related options. */
  suspend fun getXrOptions(): XrOptions {
    val observer = SuspendingStreamObserver<XrOptions>()
    getXrOptions(observer)
    return observer.getResult()
  }

  /** Sets the state of microphone input. */
  fun setMicrophoneState(microphoneState: MicrophoneState, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setMicrophoneState(${shortDebugString(microphoneState)})")
    }
    emulatorControllerStub.setMicrophoneState(
      microphoneState,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetMicrophoneStateMethod()),
    )
  }

  /** Sets the state of microphone input. */
  suspend fun setMicrophoneState(microphoneState: MicrophoneState) {
    val observer = SuspendingStreamObserver<Empty>()
    setMicrophoneState(microphoneState, observer)
    observer.getResult()
  }

  /** Retrieves the state of microphone input. */
  fun getMicrophoneState(streamObserver: StreamObserver<MicrophoneState>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getMicrophoneState()")
    }
    emulatorControllerStub.getMicrophoneState(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetMicrophoneStateMethod()),
    )
  }

  /** Retrieves the state of microphone input. */
  suspend fun getMicrophoneState(): MicrophoneState {
    val observer = SuspendingStreamObserver<MicrophoneState>()
    getMicrophoneState(observer)
    return observer.getResult()
  }

  /** Retrieves the list of video cameras connected to the host machine. */
  fun getHostCameras(streamObserver: StreamObserver<CameraList>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getHostCameras()")
    }
    emulatorControllerStub.getHostCameras(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetHostCamerasMethod()),
    )
  }

  /** Retrieves the list of video cameras connected to the host machine. */
  suspend fun getHostCameras(): CameraList {
    val observer = SuspendingStreamObserver<CameraList>()
    getHostCameras(observer)
    return observer.getResult()
  }

  /**
   * Streams input events to the emulator.
   *
   * @param streamObserver a client stream observer that is used only for error handling
   * @return a StreamObserver that can be used to supply input events
   */
  private fun streamInputEvent(streamObserver: StreamObserver<Empty> = getEmptyObserver()): StreamObserver<InputEvent> {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("streamInputEvent()")
    }
    val sender =
      emulatorControllerStub.streamInputEvent(DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getStreamInputEventMethod()))
    return object : EmptyStreamObserver<InputEvent>() {

      override fun onNext(message: InputEvent) {
        val loggingEnabled =
          when {
            message.hasKeyEvent() ||
              message.hasAndroidEvent() ||
              message.hasXrCommand() ||
              message.hasXrHeadAngularVelocityEvent() ||
              message.hasXrHeadVelocityEvent() -> EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()
            else -> EMBEDDED_EMULATOR_TRACE_HIGH_VOLUME_GRPC_CALLS.get()
          }
        if (loggingEnabled) {
          log.info("streamInputEvent: sending ${shortDebugString(message)})")
        }
        sender?.onNext(message)
      }
    }
  }

  /** Returns an existing or creates a new input event sender. */
  fun getOrCreateInputEventSender(): StreamObserver<InputEvent> {
    synchronized(this) {
      return inputEventSender ?: streamInputEvent().also { inputEventSender = it }
    }
  }

  /** Streams input events to the emulator. */
  suspend fun streamInputEvents(inputEvents: Flow<InputEvent>) {
    callClientStreaming(inputEvents) { responseObserver -> streamInputEvent(responseObserver) }
  }

  /** Streams emulator notifications. */
  fun streamNotification(streamObserver: StreamObserver<Notification>): Cancelable {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("streamNotification()")
    }
    val method = EmulatorControllerGrpc.getStreamNotificationMethod()
    val call = emulatorControllerStub.channel.newCall(method, emulatorControllerStub.callOptions)
    ClientCalls.asyncServerStreamingCall(call, EMPTY_PROTO, DelegatingStreamObserver(streamObserver, method))
    return CancelableClientCall(call)
  }

  /** Streams emulator notifications. */
  fun streamNotification(): Flow<Notification> = callbackFlow {
    val cancelable = streamNotification(FlowStreamObserver(this))
    awaitClose { cancelable.cancel() }
  }

  /** Retrieves a physical model value. */
  fun getPhysicalModel(physicalType: PhysicalModelValue.PhysicalType, streamObserver: StreamObserver<PhysicalModelValue>) {
    val modelValue = PhysicalModelValue.newBuilder().setTarget(physicalType).build()
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getPhysicalModel(${shortDebugString(modelValue)})")
    }
    emulatorControllerStub.getPhysicalModel(
      modelValue,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetPhysicalModelMethod()),
    )
  }

  /** Retrieves a physical model value. */
  suspend fun getPhysicalModel(physicalType: PhysicalModelValue.PhysicalType): PhysicalModelValue {
    val observer = SuspendingStreamObserver<PhysicalModelValue>()
    getPhysicalModel(physicalType, observer)
    return observer.getResult()
  }

  /** Sets a physical model value. */
  fun setPhysicalModel(modelValue: PhysicalModelValue, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setPhysicalModel(${shortDebugString(modelValue)})")
    }
    emulatorControllerStub.setPhysicalModel(
      modelValue,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetPhysicalModelMethod()),
    )
  }

  /** Sets a physical model value. */
  suspend fun setPhysicalModel(modelValue: PhysicalModelValue) {
    val observer = SuspendingStreamObserver<Empty>()
    setPhysicalModel(modelValue, observer)
    observer.getResult()
  }

  /** Retrieves a screenshot of an Emulator display. */
  fun getScreenshot(imageFormat: ImageFormat, streamObserver: StreamObserver<Image>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getScreenshot(${shortDebugString(imageFormat)})")
    }
    emulatorControllerStub.getScreenshot(
      imageFormat,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetScreenshotMethod()),
    )
  }

  /** Retrieves a screenshot of an Emulator display. */
  suspend fun getScreenshot(imageFormat: ImageFormat): Image {
    val observer = SuspendingStreamObserver<Image>()
    getScreenshot(imageFormat, observer)
    return observer.getResult()
  }

  /**
   * Streams a series of screenshots.
   *
   * **Note**: The value returned by the [Image.getImage] method of the response object cannot be used outside the [StreamObserver.onNext]
   * method because it is backed by a mutable reusable byte array. For this reason a [Flow] based variant of this method is not provided.
   */
  fun streamScreenshot(imageFormat: ImageFormat, streamObserver: StreamObserver<Image>): Cancelable {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("streamScreenshot(${shortDebugString(imageFormat)})")
    }
    val call = emulatorControllerStub.channel.newCall(streamScreenshotMethod, emulatorControllerStub.callOptions)
    ClientCalls.asyncServerStreamingCall(call, imageFormat, DelegatingStreamObserver(streamObserver, streamScreenshotMethod))
    return CancelableClientCall(call)
  }

  /** Retrieves the status of the emulator. */
  fun getStatus(streamObserver: StreamObserver<EmulatorStatus>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getStatus()")
    }
    emulatorControllerStub.getStatus(EMPTY_PROTO, DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetStatusMethod()))
  }

  /** Retrieves the status of the emulator. */
  suspend fun getStatus(): EmulatorStatus {
    val observer = SuspendingStreamObserver<EmulatorStatus>()
    getStatus(observer)
    return observer.getResult()
  }

  /** Sets a virtual machine state. */
  fun setVmState(vmState: VmRunState, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setVmState(${shortDebugString(vmState)})")
    }
    emulatorControllerStub.setVmState(vmState, DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetVmStateMethod()))
  }

  /** Sets a virtual machine state. */
  suspend fun setVmState(vmState: VmRunState) {
    val observer = SuspendingStreamObserver<Empty>()
    setVmState(vmState, observer)
    observer.getResult()
  }

  /** Retrieves configurations of all displays. */
  fun getDisplayConfigurations(streamObserver: StreamObserver<DisplayConfigurations>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getDisplayConfigurations()")
    }
    emulatorControllerStub.getDisplayConfigurations(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetDisplayConfigurationsMethod()),
    )
  }

  /** Retrieves configurations of all displays. */
  suspend fun getDisplayConfigurations(): DisplayConfigurations {
    val observer = SuspendingStreamObserver<DisplayConfigurations>()
    getDisplayConfigurations(observer)
    return observer.getResult()
  }

  /** Creates, modifies, or deletes configurable secondary displays. */
  fun setDisplayConfigurations(displayConfigurations: DisplayConfigurations, streamObserver: StreamObserver<DisplayConfigurations>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setDisplayConfigurations(${shortDebugString(displayConfigurations)})")
    }
    emulatorControllerStub.setDisplayConfigurations(
      displayConfigurations,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetDisplayConfigurationsMethod()),
    )
  }

  /** Creates, modifies, or deletes configurable secondary displays. */
  suspend fun setDisplayConfigurations(displayConfigurations: DisplayConfigurations): DisplayConfigurations {
    val observer = SuspendingStreamObserver<DisplayConfigurations>()
    setDisplayConfigurations(displayConfigurations, observer)
    return observer.getResult()
  }

  /** Changes orientation of the virtual scene camera. */
  fun rotateVirtualSceneCamera(cameraRotation: RotationRadian, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("rotateVirtualSceneCamera(${shortDebugString(cameraRotation)})")
    }
    emulatorControllerStub.rotateVirtualSceneCamera(
      cameraRotation,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getRotateVirtualSceneCameraMethod()),
    )
  }

  /** Changes orientation of the virtual scene camera. */
  suspend fun rotateVirtualSceneCamera(cameraRotation: RotationRadian) {
    val observer = SuspendingStreamObserver<Empty>()
    rotateVirtualSceneCamera(cameraRotation, observer)
    observer.getResult()
  }

  /** Changes velocity of the virtual scene camera. */
  fun setVirtualSceneCameraVelocity(cameraVelocity: Velocity, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setVirtualSceneCameraVelocity(${shortDebugString(cameraVelocity)})")
    }
    emulatorControllerStub.setVirtualSceneCameraVelocity(
      cameraVelocity,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetVirtualSceneCameraVelocityMethod()),
    )
  }

  /** Changes velocity of the virtual scene camera. */
  suspend fun setVirtualSceneCameraVelocity(cameraVelocity: Velocity) {
    val observer = SuspendingStreamObserver<Empty>()
    setVirtualSceneCameraVelocity(cameraVelocity, observer)
    observer.getResult()
  }

  /** Sets the environment background for AI glasses. */
  fun setEnvironment(environment: Environment, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setEnvironment(${shortDebugString(environment)})")
    }
    emulatorControllerStub.setEnvironment(
      environment,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetEnvironmentMethod()),
    )
  }

  /** Sets the environment background for AI glasses. */
  suspend fun setEnvironment(environment: Environment) {
    val observer = SuspendingStreamObserver<Empty>()
    setEnvironment(environment, observer)
    observer.getResult()
  }

  /** Retrieves the current environment configuration for AI glasses. */
  fun getEnvironment(streamObserver: StreamObserver<Environment>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getEnvironment()")
    }
    emulatorControllerStub.getEnvironment(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetEnvironmentMethod()),
    )
  }

  /** Retrieves the current environment configuration for AI glasses. */
  suspend fun getEnvironment(): Environment {
    val observer = SuspendingStreamObserver<Environment>()
    getEnvironment(observer)
    return observer.getResult()
  }

  /** Sets the battery state of the emulator. */
  private fun setBattery(batteryState: BatteryState, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setBattery(${shortDebugString(batteryState)})")
    }
    emulatorControllerStub.setBattery(
      batteryState,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSetBatteryMethod()),
    )
  }

  /** Sets the battery state of the emulator. */
  suspend fun setBattery(batteryState: BatteryState) {
    val observer = SuspendingStreamObserver<Empty>()
    setBattery(batteryState, observer)
    observer.getResult()
  }

  /** Retrieves the battery state of the emulator. */
  private fun getBattery(streamObserver: StreamObserver<BatteryState>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getBattery()")
    }
    emulatorControllerStub.getBattery(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getGetBatteryMethod()),
    )
  }

  /** Retrieves the battery state of the emulator. */
  suspend fun getBattery(): BatteryState {
    val observer = SuspendingStreamObserver<BatteryState>()
    getBattery(observer)
    return observer.getResult()
  }

  /** Sends a fingerprint touch event to the emulator. */
  private fun sendFingerprint(fingerprint: Fingerprint, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("sendFingerprint(${shortDebugString(fingerprint)})")
    }
    emulatorControllerStub.sendFingerprint(
      fingerprint,
      DelegatingStreamObserver(streamObserver, EmulatorControllerGrpc.getSendFingerprintMethod()),
    )
  }

  /** Sends a fingerprint touch event to the emulator. */
  suspend fun sendFingerprint(fingerprint: Fingerprint) {
    val observer = SuspendingStreamObserver<Empty>()
    sendFingerprint(fingerprint, observer)
    observer.getResult()
  }

  /**
   * Lists existing snapshots. Only the snapshots compatible with the running emulator are returned.
   *
   * @param snapshotFilter determines whether all or only compatible snapshots are returned
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun listSnapshots(snapshotFilter: SnapshotFilter, streamObserver: StreamObserver<SnapshotList>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("listSnapshots()")
    }
    snapshotServiceStub.listSnapshots(
      snapshotFilter,
      DelegatingStreamObserver(streamObserver, SnapshotServiceGrpc.getListSnapshotsMethod()),
    )
  }

  /**
   * Lists existing snapshots. Only the snapshots compatible with the running emulator are returned.
   *
   * @param snapshotFilter determines whether all or only compatible snapshots are returned
   */
  suspend fun listSnapshots(snapshotFilter: SnapshotFilter): SnapshotList {
    val observer = SuspendingStreamObserver<SnapshotList>()
    listSnapshots(snapshotFilter, observer)
    return observer.getResult()
  }

  /**
   * Loads a snapshot in the emulator.
   *
   * @param snapshotId the ID of the snapshot to load
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun loadSnapshot(snapshotId: String, streamObserver: StreamObserver<SnapshotPackage>) {
    val snapshot = SnapshotPackage.newBuilder().setSnapshotId(snapshotId).build()
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("loadSnapshot(${shortDebugString(snapshot)})")
    }
    snapshotServiceStub.loadSnapshot(snapshot, DelegatingStreamObserver(streamObserver, SnapshotServiceGrpc.getLoadSnapshotMethod()))
  }

  /**
   * Loads a snapshot in the emulator.
   *
   * @param snapshotId the ID of the snapshot to load
   */
  suspend fun loadSnapshot(snapshotId: String): SnapshotPackage {
    val observer = SuspendingStreamObserver<SnapshotPackage>()
    loadSnapshot(snapshotId, observer)
    return observer.getResult()
  }

  /**
   * Pushes snapshot packages into the emulator.
   *
   * Usually multiple packages need to be sent to push one snapshot file, including one header and one or more payload packages. The
   * implementation of the stream observer must handle asynchronous events correctly, especially when pushing big files. This is usually
   * done by overwriting [ClientResponseObserver.beforeStart] and calling [io.grpc.stub.CallStreamObserver.setOnReadyHandler] from there.
   *
   * @param streamObserver a client stream observer to handle events
   * @return a StreamObserver that can be used to trigger the push
   */
  fun pushSnapshot(streamObserver: ClientResponseObserver<SnapshotPackage, SnapshotPackage>): StreamObserver<SnapshotPackage> {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("pushSnapshot()")
    }
    return snapshotServiceStub.pushSnapshot(DelegatingClientResponseObserver(streamObserver, SnapshotServiceGrpc.getPushSnapshotMethod()))
  }

  /** Pushes snapshot packages into the emulator. */
  suspend fun pushSnapshot(snapshotPackages: Flow<SnapshotPackage>): SnapshotPackage {
    return callClientStreaming(snapshotPackages) { responseObserver -> pushSnapshot(responseObserver) }
  }

  /**
   * Creates a snapshot of the current emulator state.
   *
   * @param snapshotId the ID of the snapshot to create
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun saveSnapshot(snapshotId: String, streamObserver: StreamObserver<SnapshotPackage>) {
    val snapshot = SnapshotPackage.newBuilder().setSnapshotId(snapshotId).build()
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("saveSnapshot(${shortDebugString(snapshot)})")
    }
    snapshotServiceStub.saveSnapshot(snapshot, DelegatingStreamObserver(streamObserver, SnapshotServiceGrpc.getSaveSnapshotMethod()))
  }

  /**
   * Creates a snapshot of the current emulator state.
   *
   * @param snapshotId the ID of the snapshot to create
   */
  suspend fun saveSnapshot(snapshotId: String): SnapshotPackage {
    val observer = SuspendingStreamObserver<SnapshotPackage>()
    saveSnapshot(snapshotId, observer)
    return observer.getResult()
  }

  /**
   * Delete a snapshot in the emulator.
   *
   * @param snapshotId the ID of the snapshot to delete
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun deleteSnapshot(snapshotId: String, streamObserver: StreamObserver<SnapshotPackage>) {
    val snapshot = SnapshotPackage.newBuilder().setSnapshotId(snapshotId).build()
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("deleteSnapshot(${shortDebugString(snapshot)})")
    }
    snapshotServiceStub.deleteSnapshot(snapshot, DelegatingStreamObserver(streamObserver, SnapshotServiceGrpc.getSaveSnapshotMethod()))
  }

  /**
   * Delete a snapshot in the emulator.
   *
   * @param snapshotId the ID of the snapshot to delete
   */
  suspend fun deleteSnapshot(snapshotId: String): SnapshotPackage {
    val observer = SuspendingStreamObserver<SnapshotPackage>()
    deleteSnapshot(snapshotId, observer)
    return observer.getResult()
  }

  /**
   * Shows the extended controls of the emulator.
   *
   * @param pane identifies the window position and the pane to open
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun showExtendedControls(pane: PaneEntry, streamObserver: StreamObserver<ExtendedControlsStatus>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("showExtendedControls(${shortDebugString(pane)})")
    }
    uiControllerStub.showExtendedControls(pane, DelegatingStreamObserver(streamObserver, UiControllerGrpc.getShowExtendedControlsMethod()))
  }

  /**
   * Shows the extended controls of the emulator.
   *
   * @param pane identifies the window position and the pane to open
   */
  suspend fun showExtendedControls(pane: PaneEntry): ExtendedControlsStatus {
    val observer = SuspendingStreamObserver<ExtendedControlsStatus>()
    showExtendedControls(pane, observer)
    return observer.getResult()
  }

  /**
   * Closes the extended controls of the emulator.
   *
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun closeExtendedControls(streamObserver: StreamObserver<ExtendedControlsStatus>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("closeExtendedControls()")
    }
    uiControllerStub.closeExtendedControls(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, UiControllerGrpc.getCloseExtendedControlsMethod()),
    )
  }

  /** Closes the extended controls of the emulator. */
  suspend fun closeExtendedControls(): ExtendedControlsStatus {
    val observer = SuspendingStreamObserver<ExtendedControlsStatus>()
    closeExtendedControls(observer)
    return observer.getResult()
  }

  /**
   * Sets the UI style for the extended controls of the emulator.
   *
   * @param style the style to set
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  fun setUiTheme(style: ThemingStyle.Style, streamObserver: StreamObserver<Empty>) {
    val themingStyle = ThemingStyle.newBuilder().setStyle(style).build()
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setUiTheme(${shortDebugString(themingStyle)})")
    }
    uiControllerStub.setUiTheme(themingStyle, DelegatingStreamObserver(streamObserver, UiControllerGrpc.getSetUiThemeMethod()))
  }

  /**
   * Sets the UI style for the extended controls of the emulator.
   *
   * @param style the style to set
   */
  suspend fun setUiTheme(style: ThemingStyle.Style) {
    val observer = SuspendingStreamObserver<Empty>()
    setUiTheme(style, observer)
    observer.getResult()
  }

  /**
   * Sets the current cell info, returning the active cell info state.
   *
   * @param cellInfo the cell info to set
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun setCellInfo(cellInfo: CellInfo, streamObserver: StreamObserver<CellInfo>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setCellInfo(${shortDebugString(cellInfo)})")
    }
    modemStub.setCellInfo(cellInfo, DelegatingStreamObserver(streamObserver, ModemGrpc.getSetCellInfoMethod()))
  }

  /**
   * Sets the current cell info, returning the active cell info state.
   *
   * @param cellInfo the cell info to set
   */
  suspend fun setCellInfo(cellInfo: CellInfo): CellInfo {
    val observer = SuspendingStreamObserver<CellInfo>()
    setCellInfo(cellInfo, observer)
    return observer.getResult()
  }

  /**
   * Gets the current cell info.
   *
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun getCellInfo(streamObserver: StreamObserver<CellInfo>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getCellInfo()")
    }
    modemStub.getCellInfo(EMPTY_PROTO, DelegatingStreamObserver(streamObserver, ModemGrpc.getGetCellInfoMethod()))
  }

  /** Gets the current cell info. */
  suspend fun getCellInfo(): CellInfo {
    val observer = SuspendingStreamObserver<CellInfo>()
    getCellInfo(observer)
    return observer.getResult()
  }

  /**
   * Creates a new call object, resulting in the emulator receiving a new incoming call.
   *
   * @param call the call to create
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun createCall(call: Call, streamObserver: StreamObserver<Call>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("createCall(${shortDebugString(call)})")
    }
    modemStub.createCall(call, DelegatingStreamObserver(streamObserver, ModemGrpc.getCreateCallMethod()))
  }

  /**
   * Creates a new call object, resulting in the emulator receiving a new incoming call.
   *
   * @param call the call to create
   */
  suspend fun createCall(call: Call): Call {
    val observer = SuspendingStreamObserver<Call>()
    createCall(call, observer)
    return observer.getResult()
  }

  /**
   * Updates the state of the given call. The unique number identifying the call must exist.
   *
   * @param call the call to update
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun updateCall(call: Call, streamObserver: StreamObserver<Call>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("updateCall(${shortDebugString(call)})")
    }
    modemStub.updateCall(call, DelegatingStreamObserver(streamObserver, ModemGrpc.getUpdateCallMethod()))
  }

  /**
   * Updates the state of the given call. The unique number identifying the call must exist.
   *
   * @param call the call to update
   */
  suspend fun updateCall(call: Call): Call {
    val observer = SuspendingStreamObserver<Call>()
    updateCall(call, observer)
    return observer.getResult()
  }

  /**
   * Removes the active call, disconnecting the call.
   *
   * @param call the call to delete
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun deleteCall(call: Call, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("deleteCall(${shortDebugString(call)})")
    }
    modemStub.deleteCall(call, DelegatingStreamObserver(streamObserver, ModemGrpc.getDeleteCallMethod()))
  }

  /**
   * Removes the active call, disconnecting the call.
   *
   * @param call the call to delete
   */
  suspend fun deleteCall(call: Call) {
    val observer = SuspendingStreamObserver<Empty>()
    deleteCall(call, observer)
    observer.getResult()
  }

  /**
   * Lists all the calls that are currently active in the modem.
   *
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun listCalls(streamObserver: StreamObserver<ActiveCalls>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("listCalls()")
    }
    modemStub.listCalls(EMPTY_PROTO, DelegatingStreamObserver(streamObserver, ModemGrpc.getListCallsMethod()))
  }

  /** Lists all the calls that are currently active in the modem. */
  suspend fun listCalls(): ActiveCalls {
    val observer = SuspendingStreamObserver<ActiveCalls>()
    listCalls(observer)
    return observer.getResult()
  }

  /**
   * Sends an SMS message to the emulator.
   *
   * @param smsMessage the SMS message to send
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun receiveSms(smsMessage: SmsMessage, streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("receiveSms(${shortDebugString(smsMessage)})")
    }
    modemStub.receiveSms(smsMessage, DelegatingStreamObserver(streamObserver, ModemGrpc.getReceiveSmsMethod()))
  }

  /**
   * Sends an SMS message to the emulator.
   *
   * @param smsMessage the SMS message to send
   */
  suspend fun receiveSms(smsMessage: SmsMessage) {
    val observer = SuspendingStreamObserver<Empty>()
    receiveSms(smsMessage, observer)
    observer.getResult()
  }

  /**
   * Updates the modem clock.
   *
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun updateClock(streamObserver: StreamObserver<Empty>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("updateClock()")
    }
    modemStub.updateClock(EMPTY_PROTO, DelegatingStreamObserver(streamObserver, ModemGrpc.getUpdateClockMethod()))
  }

  /** Updates the modem clock. */
  suspend fun updateClock() {
    val observer = SuspendingStreamObserver<Empty>()
    updateClock(observer)
    observer.getResult()
  }

  /**
   * Listens for events in the modem service as they arrive.
   *
   * @param streamObserver a stream observer to receive phone events
   */
  private fun receivePhoneEvents(streamObserver: StreamObserver<PhoneEvent>): Cancelable {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("receivePhoneEvents()")
    }
    val method = ModemGrpc.getReceivePhoneEventsMethod()
    val call = modemStub.channel.newCall(method, modemStub.callOptions)
    ClientCalls.asyncServerStreamingCall(call, EMPTY_PROTO, DelegatingStreamObserver(streamObserver, method))
    return CancelableClientCall(call)
  }

  /** Listens for events in the modem service as they arrive. */
  fun receivePhoneEvents(): Flow<PhoneEvent> = callbackFlow {
    val cancelable = receivePhoneEvents(FlowStreamObserver(this))
    awaitClose { cancelable.cancel() }
  }

  /**
   * Sets the poster for a specific position in the virtual scene.
   *
   * @param poster the poster details including name, image, filename, width, and scale
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun setPoster(poster: Poster, streamObserver: StreamObserver<Poster>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setPoster(${shortDebugString(poster)})")
    }
    virtualSceneStub.setPoster(poster, DelegatingStreamObserver(streamObserver, VirtualSceneServiceGrpc.getSetPosterMethod()))
  }

  /**
   * Sets the poster for a specific position in the virtual scene.
   *
   * @param poster the poster details including name, image, filename, width, and scale
   */
  suspend fun setPoster(poster: Poster): Poster {
    val observer = SuspendingStreamObserver<Poster>()
    setPoster(poster, observer)
    return observer.getResult()
  }

  /**
   * Lists all the posters currently set in the virtual scene.
   *
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun listPosters(streamObserver: StreamObserver<PosterList>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("listPosters()")
    }
    virtualSceneStub.listPosters(EMPTY_PROTO, DelegatingStreamObserver(streamObserver, VirtualSceneServiceGrpc.getListPostersMethod()))
  }

  /** Lists all the posters currently set in the virtual scene. */
  suspend fun listPosters(): PosterList {
    val observer = SuspendingStreamObserver<PosterList>()
    listPosters(observer)
    return observer.getResult()
  }

  /**
   * Sets the animation state of the virtual scene.
   *
   * @param animationState the new animation state
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun setAnimationState(animationState: AnimationState, streamObserver: StreamObserver<AnimationState>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("setAnimationState(${shortDebugString(animationState)})")
    }
    virtualSceneStub.setAnimationState(
      animationState,
      DelegatingStreamObserver(streamObserver, VirtualSceneServiceGrpc.getSetAnimationStateMethod()),
    )
  }

  /**
   * Sets the animation state of the virtual scene.
   *
   * @param animationState the new animation state
   */
  suspend fun setAnimationState(animationState: AnimationState): AnimationState {
    val observer = SuspendingStreamObserver<AnimationState>()
    setAnimationState(animationState, observer)
    return observer.getResult()
  }

  /**
   * Gets the current animation state of the virtual scene.
   *
   * @param streamObserver a stream observer to observe the response stream (which contains only 1 message in this case)
   */
  private fun getAnimationState(streamObserver: StreamObserver<AnimationState>) {
    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getAnimationState()")
    }
    virtualSceneStub.getAnimationState(
      EMPTY_PROTO,
      DelegatingStreamObserver(streamObserver, VirtualSceneServiceGrpc.getGetAnimationStateMethod()),
    )
  }

  /** Gets the current animation state of the virtual scene. */
  suspend fun getAnimationState(): AnimationState {
    val observer = SuspendingStreamObserver<AnimationState>()
    getAnimationState(observer)
    return observer.getResult()
  }

  private fun sendKeepAlive() {
    if (emulatorState.get() == EmulatorState.SHUTDOWN_REQUESTED) {
      sendShutdown()
      return
    }

    val responseObserver =
      object : EmptyStreamObserver<VmRunState>() {

        override fun onNext(message: VmRunState) {
          if (emulatorState.get() == EmulatorState.SHUTDOWN_REQUESTED) {
            sendShutdown()
          } else {
            keepAliveJob?.cancel()
            keepAliveJob = coroutineScope.launch {
              delay(KEEP_ALIVE_INTERVAL)
              sendKeepAlive()
            }
          }
        }
      }

    if (EMBEDDED_EMULATOR_TRACE_GRPC_CALLS.get()) {
      log.info("getVmState()")
    }
    val timeout = if (connectionState == ConnectionState.CONNECTED) 3L else 15L
    emulatorControllerStub
      .withDeadlineAfter(timeout, TimeUnit.SECONDS)
      .getVmState(EMPTY_PROTO, DelegatingStreamObserver(responseObserver, EmulatorControllerGrpc.getGetVmStateMethod()))
  }

  private fun throwNotYetConnected(): Nothing {
    throw IllegalStateException("Not yet connected to the Emulator")
  }

  override fun dispose() {
    inputEventSender?.onCompleted()
    inputEventSender = null
    channel?.shutdown()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as EmulatorController

    return emulatorId == other.emulatorId
  }

  override fun hashCode(): Int {
    return emulatorId.hashCode()
  }

  private class Connection(
    val channel: ManagedChannel,
    val maxInboundMessageSize: Int,
    val emulatorControllerStub: EmulatorControllerGrpc.EmulatorControllerStub,
    val snapshotServiceStub: SnapshotServiceGrpc.SnapshotServiceStub,
    val uiControllerStub: UiControllerGrpc.UiControllerStub,
    val modemStub: ModemGrpc.ModemStub,
    val virtualSceneStub: VirtualSceneServiceGrpc.VirtualSceneServiceStub,
  )

  /** The state of the [EmulatorController]. */
  enum class ConnectionState {
    NOT_INITIALIZED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
  }

  enum class EmulatorState {
    RUNNING,
    SHUTDOWN_REQUESTED,
    SHUTDOWN_SENT,
  }

  class CancelableClientCall(private val call: ClientCall<*, *>) : Cancelable {

    override fun cancel() {
      call.cancel("Canceled by consumer", null)
    }
  }

  open inner class DelegatingStreamObserver<RequestT, ResponseT>(
    open val delegate: StreamObserver<in ResponseT>?,
    val method: MethodDescriptor<in RequestT, in ResponseT>,
  ) : StreamObserver<ResponseT> {

    private val connection = connectionReference.get()

    override fun onNext(response: ResponseT) {
      delegate?.onNext(response)
    }

    override fun onError(t: Throwable) {
      if (
        channel?.isShutdown == false &&
          (t !is StatusRuntimeException ||
            (t.status.code != Status.Code.CANCELLED &&
              (t.status.code != Status.Code.UNAVAILABLE || emulatorState.get() == EmulatorState.RUNNING)))
      ) {
        log.warn("${method.fullMethodName} call failed - ${t.message}")
      }

      val error = if (connection == connectionReference.get()) t else RetryException()
      delegate?.onError(error)

      if (t is StatusRuntimeException && t.status.code == Status.Code.UNAVAILABLE && connection == connectionReference.get()) {
        updateConnectionState(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
      }
    }

    override fun onCompleted() {
      delegate?.onCompleted()
    }
  }

  inner class DelegatingClientResponseObserver<RequestT, ResponseT>(
    override val delegate: ClientResponseObserver<RequestT, ResponseT>?,
    method: MethodDescriptor<in RequestT, in ResponseT>,
  ) : DelegatingStreamObserver<RequestT, ResponseT>(delegate, method), ClientResponseObserver<RequestT, ResponseT> {

    override fun beforeStart(requestStream: ClientCallStreamObserver<RequestT>?) {
      delegate?.beforeStart(requestStream)
    }
  }

  /** The exception that indicates that the gRPC call should be retried. */
  class RetryException : RuntimeException()

  /** Defines interface for an object that receives notifications when the state of the Emulator connection changes. */
  interface ConnectionStateListener {
    /** Called when the state of the Emulator connection changes. */
    @AnyThread fun connectionStateChanged(emulator: EmulatorController, connectionState: ConnectionState)
  }

  private inner class TokenCallCredentials(private val token: String) : CallCredentials() {

    override fun applyRequestMetadata(requestInfo: RequestInfo, executor: Executor, applier: MetadataApplier) {
      executor.execute {
        try {
          val headers = Metadata()
          headers.put(AUTHORIZATION_METADATA_KEY, "Bearer $token")
          applier.apply(headers)
        } catch (e: Throwable) {
          log.error(e)
          applier.fail(Status.UNAUTHENTICATED.withCause(e))
        }
      }
    }
  }
}

private open class FlowStreamObserver<T>(private val channel: SendChannel<T>) : EmptyStreamObserver<T>() {

  override fun onNext(message: T) {
    channel.trySend(message)
  }

  override fun onError(t: Throwable) {
    channel.close(t)
  }

  override fun onCompleted() {
    channel.close()
  }
}

private suspend fun <ReqT, RespT> callClientStreaming(
  flow: Flow<ReqT>,
  stubCall: (ClientResponseObserver<ReqT, RespT>) -> StreamObserver<ReqT>,
): RespT {
  val deferred = CompletableDeferred<RespT>()
  val requestStreamRef = AtomicReference<ClientCallStreamObserver<ReqT>>()

  val isReady = AtomicBoolean(true)
  val readyCallback = AtomicReference<(() -> Unit)?>()

  val responseObserver =
    object : ClientResponseObserver<ReqT, RespT> {
      override fun beforeStart(requestStream: ClientCallStreamObserver<ReqT>) {
        requestStreamRef.set(requestStream)
        requestStream.setOnReadyHandler {
          isReady.set(true)
          readyCallback.getAndSet(null)?.invoke()
        }
      }

      override fun onNext(value: RespT) {
        deferred.complete(value)
      }

      override fun onError(t: Throwable) {
        deferred.completeExceptionally(t)
      }

      override fun onCompleted() {
        if (!deferred.isCompleted) {
          deferred.completeExceptionally(IllegalStateException("Call completed without response"))
        }
      }
    }

  val requestObserver = stubCall(responseObserver)

  try {
    flow.collect { value ->
      if (deferred.isCompleted) {
        deferred.await()
        return@collect
      }
      val requestStream = requestStreamRef.get()
      if (requestStream != null) {
        if (!isReady.get()) {
          suspendCancellableCoroutine { continuation ->
            readyCallback.set { continuation.resume(Unit) }
            if (isReady.get()) {
              readyCallback.set(null)
              continuation.resume(Unit)
            }
          }
        }
        requestStream.onNext(value)
      } else {
        requestObserver.onNext(value)
      }
    }
    requestObserver.onCompleted()
  } catch (t: Throwable) {
    requestObserver.onError(t)
    throw t
  }

  return deferred.await()
}

/**
 * Marshaller for the [Image] objects that implements custom deserialization, which, unlike the standard one, doesn't allocate short-lived
 * humongous objects (b/180151949).
 */
private class ImageResponseMarshaller : Marshaller<Image> {
  private val reusableBuffer = ThreadLocal<Reference<ByteArray>>()

  override fun stream(response: Image): InputStream {
    throw UnsupportedOperationException() // This marshaller is never used for serialization.
  }

  override fun parse(stream: InputStream): Image {
    try {
      val codedStream = createCodedInputStream(stream) ?: return Image.getDefaultInstance()
      return parseImage(codedStream)
    } catch (e: InvalidProtocolBufferException) {
      throw Status.INTERNAL.withDescription("Invalid protobuf byte sequence").withCause(e).asRuntimeException()
    }
  }

  @Throws(InvalidProtocolBufferException::class)
  private fun createCodedInputStream(stream: InputStream): CodedInputStream? {
    val codedStream: CodedInputStream
    try {
      if (stream is KnownLength) {
        val size = stream.available()
        if (size == 0) {
          return null
        }

        var buf = reusableBuffer.get()?.get()
        if (buf == null || buf.size < size) {
          buf = ByteArray(size)
          reusableBuffer.set(WeakReference(buf))
        }

        var remaining = size
        while (remaining > 0) {
          val position = size - remaining
          val count = stream.read(buf, position, remaining)
          if (count < 0) {
            break
          }
          remaining -= count
        }

        if (remaining != 0) {
          val position = size - remaining
          throw RuntimeException("Inaccurate size: $size != $position")
        }
        codedStream = UnsafeByteOperations.unsafeWrap(buf, 0, size).newCodedInput()
        codedStream.enableAliasing(true)
      } else {
        codedStream = CodedInputStream.newInstance(stream)
      }
    } catch (e: IOException) {
      throw InvalidProtocolBufferException(e)
    }
    // Remove the size limit restriction for parsing since the CodedInputStream is pre-created.
    codedStream.setSizeLimit(Int.MAX_VALUE)
    return codedStream
  }

  @Throws(InvalidProtocolBufferException::class)
  private fun parseImage(input: CodedInputStream): Image {
    val builder = Image.newBuilder()

    try {
      while (true) {
        when (val tag = input.readTag()) {
          0 -> break
          FORMAT_FIELD_TAG -> builder.format = input.readMessage(ImageFormat.parser(), EMPTY_REGISTRY) as ImageFormat
          IMAGE_FIELD_TAG -> builder.image = input.readBytes()
          SEQ_FIELD_TAG -> builder.seq = input.readUInt32()
          TIMESTAMPUS_FIELD_TAG -> builder.timestampUs = input.readUInt64()
          else -> if (!input.skipField(tag)) break
        }
      }
      input.checkLastTagWas(0)
      return builder.build()
    } catch (e: InvalidProtocolBufferException) {
      throw e.setUnfinishedMessage(builder.build())
    } catch (e: IOException) {
      throw InvalidProtocolBufferException(e).setUnfinishedMessage(builder.build())
    }
  }
}

private inline fun <T> Iterable<T>.maxOfOrZero(selector: (T) -> Int): Int = maxOfOrNull(selector) ?: 0

private const val FORMAT_FIELD_TAG = Image.FORMAT_FIELD_NUMBER shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
private const val IMAGE_FIELD_TAG = Image.IMAGE_FIELD_NUMBER shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
private const val SEQ_FIELD_TAG = Image.SEQ_FIELD_NUMBER shl 3 or WireFormat.WIRETYPE_VARINT
private const val TIMESTAMPUS_FIELD_TAG = Image.TIMESTAMPUS_FIELD_NUMBER shl 3 or WireFormat.WIRETYPE_VARINT

private val EMPTY_REGISTRY = ExtensionRegistryLite.getEmptyRegistry()

private val AUTHORIZATION_METADATA_KEY = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
private val KEEP_ALIVE_INTERVAL = 2.minutes
private val EMPTY_PROTO = Empty.getDefaultInstance()
