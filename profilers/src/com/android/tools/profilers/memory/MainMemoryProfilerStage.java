/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.profilers.memory;

import static com.android.tools.profilers.StudioProfilers.DAEMON_DEVICE_DIR_PATH;

import com.android.sdklib.AndroidVersion;
import com.android.tools.adtui.model.DataSeries;
import com.android.tools.adtui.model.DurationDataModel;
import com.android.tools.adtui.model.Range;
import com.android.tools.adtui.model.SeriesData;
import com.android.tools.adtui.model.updater.Updatable;
import com.android.tools.adtui.model.updater.Updater;
import com.android.tools.idea.io.grpc.StatusRuntimeException;
import com.android.tools.idea.transport.TransportFileManager;
import com.android.tools.idea.transport.poller.TransportEventListener;
import com.android.tools.profiler.proto.Commands;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.Memory;
import com.android.tools.profiler.proto.Memory.AllocationsInfo;
import com.android.tools.profiler.proto.Trace;
import com.android.tools.profiler.proto.Transport;
import com.android.tools.profiler.proto.Transport.TimeRequest;
import com.android.tools.profiler.proto.Transport.TimeResponse;
import com.android.tools.profilers.FeatureConfig;
import com.android.tools.profilers.IdeProfilerServices;
import com.android.tools.profilers.InterimStage;
import com.android.tools.profilers.LogUtils;
import com.android.tools.profilers.ProfilerContext;
import com.android.tools.profilers.ProfilerCaptureFileUtils;
import com.android.tools.profilers.RecordingOption;
import com.android.tools.profilers.RecordingOptionsModel;
import com.android.tools.profilers.StudioProfilers;
import com.android.tools.profilers.SupportLevel;
import com.android.tools.profilers.TraceDeobfuscator;
import com.android.tools.profilers.cpu.CpuCapture;
import com.android.tools.profilers.memory.adapters.CaptureObject;
import com.android.tools.profilers.memory.adapters.HeapDumpCaptureObject;
import com.android.tools.profilers.memory.adapters.LegacyAllocationCaptureObject;
import com.android.tools.profilers.memory.adapters.NativeAllocationSampleCaptureObject;
import com.android.tools.profilers.perfetto.config.PerfettoTraceConfigBuilders;
import com.android.tools.profilers.sessions.SessionAspect;
import com.android.tools.profilers.taskbased.task.interim.RecordingScreenModel;
import com.android.tools.profilers.tasks.ProfilerTaskType;
import com.android.tools.profilers.tasks.analytics.TaskStartFailedMetadata;
import com.android.tools.profilers.tasks.analytics.TaskStopFailedMetadata;
import com.android.tools.profilers.transporteventutils.TransportListenerTracker;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.util.io.FileUtil;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import kotlin.Lazy;
import kotlin.LazyKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import perfetto.protos.PerfettoConfig;

public class MainMemoryProfilerStage extends BaseStreamingMemoryProfilerStage implements InterimStage {
  private static final String HEAP_DUMP_TOOLTIP = "View objects in your app that are using memory at a specific point in time";
  private static final String CAPTURE_HEAP_DUMP_TEXT = "Capture heap dump";
  private static final String RECORD_JAVA_TEXT = "Record Java / Kotlin allocations";
  private static final String RECORD_JAVA_TOOLTIP = "View how each Java / Kotlin object was allocated over a period of time";
  @VisibleForTesting static final String RECORD_NATIVE_TEXT = "Record native allocations";
  private static final String RECORD_NATIVE_DESC = "View how each C / C++ object was allocated over a period of time";


  // The safe factor estimating how many times of memory is needed compared to hprof file size
  public static final int MEMORY_HPROF_SAFE_FACTOR =
    Math.max(1, Math.min(Integer.getInteger("profiler.memory.hprof.safeFactor", 10), 1000));

  /**
   * Whether the stage only contains heap dump data imported from hprof file
   */
  private final boolean myIsMemoryCaptureOnly;

  private final DurationDataModel<CaptureDurationData<? extends CaptureObject>> myHeapDumpDurations;
  private final DurationDataModel<CaptureDurationData<? extends CaptureObject>> myAllocationDurations;
  private final DurationDataModel<CaptureDurationData<? extends CaptureObject>> myNativeAllocationDurations;
  /**
   * Heap dumps of profileable processes, captured as Perfetto heap graphs because {@code am dumpheap} is not permitted there. They are
   * only recorded in the Task-Based UX, which shows this stage as a recording screen, so {@code MemoryTimelineComponent} doesn't draw
   * them.
   */
  private final DurationDataModel<CaptureDurationData<? extends CaptureObject>> myJavaHeapGraphDurations;

  private long myPendingLegacyAllocationStartTimeNs = BaseMemoryProfilerStage.INVALID_START_TIME;

  @VisibleForTesting boolean myNativeAllocationTracking = false;

  private final RecordingOptionsModel myRecordingOptionsModel;

  @NotNull
  private final Runnable myStopAction;

  @Nullable
  private final RecordingScreenModel<MainMemoryProfilerStage> myRecordingScreenModel;

  @VisibleForTesting
  public Lazy<RecordingOption> lazyHeapDumpRecordingOption =
    LazyKt.lazyOf(new RecordingOption(CAPTURE_HEAP_DUMP_TEXT, HEAP_DUMP_TOOLTIP, () -> {
      requestHeapDump();
      getStudioProfilers().getIdeServices().getFeatureTracker().trackDumpHeap();
    }));

  @VisibleForTesting
  public Lazy<RecordingOption> lazyNativeRecordingOption =
    LazyKt.lazyOf(makeToggleOption(RECORD_NATIVE_TEXT, RECORD_NATIVE_DESC, this::toggleNativeAllocationTracking));

  @VisibleForTesting
  public Lazy<RecordingOption> lazyJavaKotlinAllocationsRecordingOption =
    LazyKt.lazyOf(makeToggleOption(RECORD_JAVA_TEXT, RECORD_JAVA_TOOLTIP,
                                   isLiveAllocationTrackingSupported() ?
                                   // post-O
                                   () -> getStudioProfilers().setStage(
                                     AllocationStage.makeLiveStage(
                                       getStudioProfilers(), getStopAction())) :
                                   // legacy
                                   () -> {
                                     if (isTrackingAllocations()) {
                                       getStudioProfilers().getIdeServices()
                                       .getFeatureTracker()
                                       .trackRecordAllocations();
                                     }
                                     trackAllocations(
                                       !isTrackingAllocations());
                                   }
    ));

  public MainMemoryProfilerStage(@NotNull StudioProfilers profilers) {
    this(profilers, new CaptureObjectLoader(), () -> {
    });
  }

  public MainMemoryProfilerStage(@NotNull StudioProfilers profilers, @NotNull CaptureObjectLoader loader) {
    this(profilers, loader, () -> {
    });
  }

  public static ProfilerContext createDefaultContext(@NotNull StudioProfilers profilers) {
    return new ProfilerContext() {
      @Override
      @NotNull
      public IdeProfilerServices getIdeProfilerServices() {
        return profilers.getIdeServices();
      }

      @Override
      @NotNull
      public Updater getUpdater() {
        return profilers.getUpdater();
      }

      @Override
      public boolean isJvmtiEnabled() {
        return profilers.getSessionsManager().getSelectedSessionMetaData().getJvmtiEnabled();
      }

      @Override
      @NotNull
      public DataSeries<Long> getDataSeries(@Nullable CpuCapture capture) {
        return new DataSeries<Long>() {
          @Override
          @NotNull
          public java.util.List<SeriesData<Long>> getDataForRange(@NotNull Range timeCurrentRangeUs) {
            return java.util.Collections.emptyList();
          }
        };
      }

      @Override
      public void onParseFailure(@NotNull String message) { }

      @Override
      public void reportParsedTrace(@NotNull CpuCapture capture) { }
    };
  }

  /**
   * This constructor is utilized to create instances of MainMemoryProfilerStage that allow the owning class to supply a custom runnable
   * called stopAction. This enables the bound view to invoke this custom behavior on stoppage of whatever flow this stage is facilitating
   * (e.g. if this stage is facilitating a recording, this can serve as the handler for the "Stop Recording" button).
   */
  public MainMemoryProfilerStage(@NotNull StudioProfilers profilers, @NotNull Runnable stopAction) {
    this(profilers, new CaptureObjectLoader(), stopAction);
  }

  public MainMemoryProfilerStage(@NotNull StudioProfilers profilers,
                                 @NotNull CaptureObjectLoader loader,
                                 @NotNull Runnable stopAction) {
    super(profilers, loader);
    myListenerTracker = new TransportListenerTracker(profilers);
    myIsMemoryCaptureOnly =
      profilers.getSessionsManager().getSelectedSessionMetaData().getType() == Common.SessionMetaData.SessionType.MEMORY_CAPTURE;

    // TODO(b/122964201) Pass data range as 3rd param to RangedSeries to only show data from current session
    myHeapDumpDurations = makeModel(CaptureDataSeries::ofHeapDumpSamples);
    myAllocationDurations = isLiveAllocationTrackingSupported()
                            ? makeModel(CaptureDataSeries::ofAllocationInfos)
                            : makeModel(CaptureDataSeries::ofLegacyAllocationInfos);
    myNativeAllocationDurations = makeModel(CaptureDataSeries::ofNativeAllocationSamples);
    myJavaHeapGraphDurations = makeModel(CaptureDataSeries::ofJavaHeapGraphSamples);

    myHeapDumpDurations.setRenderSeriesPredicate((data, series) ->
                                                   // Do not show the object series during a heap dump.
                                                   !series.getName().equals(getDetailedMemoryUsage().getObjectsSeries().getName())
    );

    getRangeSelectionModel().addConstraint(myAllocationDurations);
    getRangeSelectionModel().addConstraint(myNativeAllocationDurations);
    getRangeSelectionModel().addConstraint(myHeapDumpDurations);
    getRangeSelectionModel().addConstraint(myJavaHeapGraphDurations);

    getStudioProfilers().getSessionsManager().addDependency(this)
      .onChange(SessionAspect.SELECTED_SESSION, this::stopRecordingOnSessionStop);

    myRecordingOptionsModel = new RecordingOptionsModel();
    myStopAction = stopAction;
    if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
      myRecordingScreenModel = new RecordingScreenModel<>(this);
    }
    else {
      myRecordingScreenModel = null;
    }
  }

  private final TransportListenerTracker myListenerTracker;

  public RecordingOptionsModel getRecordingOptionsModel() {
    return myRecordingOptionsModel;
  }

  void stopRecordingOnSessionStop() {
    boolean isAlive = getStudioProfilers().getSessionsManager().isSessionAlive();
    if (!isAlive && myNativeAllocationTracking) {
      toggleNativeAllocationTracking();
    }
  }

  public boolean hasUserUsedMemoryCapture() {
    return getStudioProfilers().getIdeServices().getTemporaryProfilerPreferences().getBoolean(HAS_USED_MEMORY_CAPTURE, false);
  }

  @Override
  public void onEnter() {
    super.onEnter();
    BiConsumer<SupportLevel.Feature, RecordingOption> adder = (feature, option) -> {
      myRecordingOptionsModel.addBuiltInOptions(option);
      if (!getStudioProfilers().getSelectedSessionSupportLevel().isFeatureSupported(feature)) {
        myRecordingOptionsModel.setOptionNotReady(option, feature.getTitle() + " is not supported for profileable processes");
      }
    };
    RecordingOption heapDumpOption = lazyHeapDumpRecordingOption.getValue();
    if (MemoryProfiler.recordsHeapDumpAsJavaHeapGraph(getStudioProfilers().getSelectedSessionSupportLevel(),
                                                      getStudioProfilers().getIdeServices().getFeatureConfig())) {
      // Profileable processes can't use `am dumpheap`; they are captured as a Perfetto heap graph, which needs Android 11 or higher.
      myRecordingOptionsModel.addBuiltInOptions(heapDumpOption);
      if (!useJavaHeapGraphForHeapDump()) {
        myRecordingOptionsModel.setOptionNotReady(
          heapDumpOption,
          SupportLevel.Feature.MEMORY_HEAP_DUMP.getTitle() + " requires API " + MemoryProfiler.JAVA_HEAP_GRAPH_MIN_API +
          " or higher for profileable processes");
      }
    }
    else {
      adder.accept(SupportLevel.Feature.MEMORY_HEAP_DUMP, heapDumpOption);
    }
    if (isNativeAllocationSamplingEnabled()) {
      adder.accept(SupportLevel.Feature.MEMORY_NATIVE_RECORDING, lazyNativeRecordingOption.getValue());
    }
    adder.accept(SupportLevel.Feature.MEMORY_JVM_RECORDING, lazyJavaKotlinAllocationsRecordingOption.getValue());

    // Update statuses after recording options model has been initialized
    updateAllocationTrackingStatus();
    updateNativeAllocationTrackingStatus();

    // Register recording screen model updatable so timer can update on tick.
    if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled() && myRecordingScreenModel != null) {
      getStudioProfilers().getUpdater().register(myRecordingScreenModel);
    }
  }

  @Override
  public void onExit() {
    super.onExit();
    enableSelectLatestCapture(false, null);
    selectCaptureDuration(null, null);
    // Deregister recording screen model updatable so timer does not continue in background.
    if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled() && myRecordingScreenModel != null) {
      getStudioProfilers().getUpdater().unregister(myRecordingScreenModel);
    }
    myListenerTracker.onExit();
  }

  @NotNull
  @Override
  public List<DurationDataModel<CaptureDurationData<? extends CaptureObject>>> getCaptureSeries() {
    return Arrays.asList(myAllocationDurations, myHeapDumpDurations, myNativeAllocationDurations, myJavaHeapGraphDurations);
  }

  @Override
  protected void selectCaptureFromSelectionRange() {
    if (!getUpdateCaptureOnSelection()) {
      return;
    }

    setUpdateCaptureOnSelection(false);
    Range selectionRange = getTimeline().getSelectionRange();
    selectCaptureDuration(getIntersectingCaptureDuration(selectionRange), SwingUtilities::invokeLater);
    setUpdateCaptureOnSelection(true);
  }

  public boolean isMemoryCaptureOnly() {
    return myIsMemoryCaptureOnly;
  }

  @Override
  protected void onCaptureToSelect(SeriesData<CaptureDurationData<? extends CaptureObject>> captureToSelect, @NotNull Executor loadJoiner) {
    if (HeapDumpCaptureObject.class.isAssignableFrom(captureToSelect.value.getCaptureObjectType())) {
      getAspect().changed(MemoryProfilerAspect.HEAP_DUMP_FINISHED);
      LogUtils.log(getClass(), "Heap dump capture has finished");
    }
    selectCaptureDuration(captureToSelect.value, loadJoiner);
  }

  /**
   * Set the start time for pending capture object imported from hprof file.
   */
  public void setPendingCaptureStartTimeGuarded(long pendingCaptureStartTime) {
    assert myIsMemoryCaptureOnly;
    super.setPendingCaptureStartTime(pendingCaptureStartTime);
  }

  public void startHeapDumpCapture() {
    startMemoryRecording(lazyHeapDumpRecordingOption.getValue());
  }

  public void startNativeAllocationCapture() {
    startMemoryRecording(lazyNativeRecordingOption.getValue());
  }

  public void startJavaKotlinAllocationCapture() {
    startMemoryRecording(lazyJavaKotlinAllocationsRecordingOption.getValue());
  }

  private void startMemoryRecording(RecordingOption recordingOption) {
    getRecordingOptionsModel().selectBuiltInOption(recordingOption);
    getRecordingOptionsModel().start();
  }

  public void stopMemoryRecording() {
    getRecordingOptionsModel().stop();
  }

  private void startNativeAllocationTracking() {
    IdeProfilerServices ide = getStudioProfilers().getIdeServices();
    ide.getFeatureTracker().trackRecordAllocations();
    Common.Process process = getStudioProfilers().getProcess();
    String traceFilePath = String.format(Locale.getDefault(), "%s/%s.trace", DAEMON_DEVICE_DIR_PATH, process.getName());

    Trace.TraceConfiguration configuration = Trace.TraceConfiguration.newBuilder()
      .setAbiCpuArch(
        TransportFileManager.getShortAbiName(getStudioProfilers().getDevice().getCpuAbi()))
      .setTempPath(traceFilePath)
      .setAppName(process.getName())
      .setPerfettoOptions(
        PerfettoTraceConfigBuilders.INSTANCE.getMemoryTraceConfig(process.getName(), ide.getNativeAllocationsMemorySamplingRate()))
      .build();

    // Note: This will use the config for the one that is loaded (in the drop-down) vs the one used to launch
    // the app.
    startMemoryTrace(configuration, false, this::nativeAllocationTrackingStart, () -> {});
  }

  private void stopNativeAllocationTracking() {
    stopMemoryTrace(getStudioProfilers().getProcess().getName(),
                    TransportFileManager.getShortAbiName(getStudioProfilers().getDevice().getCpuAbi()),
                    this::nativeAllocationTrackingStop,
                    this::cleanupFailedCapture);
  }

  /**
   * Starts a Perfetto-backed MEMORY trace with the given {@code configuration}. Shared by Native Allocations and heap dumps of profileable
   * processes.
   *
   * @param surviveExit     whether the start status listener outlives this stage for a while, see
   *                        {@link TransportListenerTracker#trackListener}
   * @param onStartStatus   receives the daemon's start status on the main thread
   * @param onInvalidStatus runs on the main thread if the status event carries no start status, after the failure is tracked and before
   *                        it is logged
   */
  private void startMemoryTrace(@NotNull Trace.TraceConfiguration configuration,
                                boolean surviveExit,
                                @NotNull Consumer<Trace.TraceStartStatus> onStartStatus,
                                @NotNull Runnable onInvalidStatus) {
    Commands.Command startCommand = Commands.Command.newBuilder()
      .setStreamId(getSessionData().getStreamId())
      .setPid(getSessionData().getPid())
      .setType(Commands.Command.CommandType.START_TRACE)
      .setStartTrace(Trace.StartTrace.newBuilder()
                       .setProfilerType(Trace.ProfilerType.MEMORY)
                       .setConfiguration(configuration))
      .build();

    executeTraceCommand(startCommand, surviveExit, traceStatus -> {
      if (traceStatus.hasTraceStartStatus()) {
        // trace status event is a start tracing event
        onStartStatus.accept(traceStatus.getTraceStartStatus());
      }
      else {
        // unknown/undefined trace status event found
        myTaskTracker.trackStartTaskFailed(new TaskStartFailedMetadata(Trace.TraceStartStatus.getDefaultInstance(), null, null));
        onInvalidStatus.run();
        getLogger().error("Invalid trace status event received.");
      }
    });
  }

  /**
   * Stops the MEMORY trace running in {@code appName}. The daemon finds the capture by app name, so it must match the one used to start
   * it. Under the Task-Based UX this also ends the session.
   *
   * @param onStopStatus    receives the daemon's stop status on the main thread
   * @param onInvalidStatus runs on the main thread if the status event carries no stop status, before the failure is tracked and logged
   */
  private void stopMemoryTrace(@NotNull String appName,
                               @NotNull String abiCpuArch,
                               @NotNull Consumer<Trace.TraceStopStatus> onStopStatus,
                               @NotNull Runnable onInvalidStatus) {
    Trace.TraceConfiguration configuration = Trace.TraceConfiguration.newBuilder()
      .setAppName(appName)
      .setAbiCpuArch(abiCpuArch)
      .setInitiationType(Trace.TraceInitiationType.INITIATED_BY_UI)
      .setPerfettoOptions(PerfettoConfig.TraceConfig.getDefaultInstance())
      .build();

    Commands.Command.Builder stopCommandBuilder = Commands.Command.newBuilder()
      .setStreamId(getSessionData().getStreamId())
      .setPid(getSessionData().getPid())
      .setSessionId(getSessionData().getSessionId())
      .setType(Commands.Command.CommandType.STOP_TRACE)
      .setStopTrace(Trace.StopTrace.newBuilder()
                      .setProfilerType(Trace.ProfilerType.MEMORY)
                      .setConfiguration(configuration));

    if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
      stopCommandBuilder.setShouldEndSession(true);
    }

    executeTraceCommand(stopCommandBuilder.build(), true, traceStatus -> {
      if (traceStatus.hasTraceStopStatus()) {
        // trace status event is a stop tracing event
        onStopStatus.accept(traceStatus.getTraceStopStatus());
      }
      else {
        onInvalidStatus.run();
        // unknown/undefined trace status event found
        if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
          myTaskTracker.trackStopTaskFailed(new TaskStopFailedMetadata(Trace.TraceStopStatus.getDefaultInstance(), null, null));
        }
        getLogger().error("Invalid trace status event received.");
      }
    });
  }

  /**
   * Executes a START_TRACE or STOP_TRACE command and hands the matching TRACE_STATUS event to {@code onTraceStatus} on the main thread.
   *
   * @param surviveExit whether the status listener outlives this stage for a while, see {@link TransportListenerTracker#trackListener}
   */
  private void executeTraceCommand(@NotNull Commands.Command command,
                                   boolean surviveExit,
                                   @NotNull Consumer<Trace.TraceStatusData> onTraceStatus) {
    IdeProfilerServices ide = getStudioProfilers().getIdeServices();
    getStudioProfilers().getClient().executeAsync(command, ide.getPoolExecutor())
      .thenAcceptAsync(response -> {
        TransportEventListener statusListener =
          new TransportEventListener(Common.Event.Kind.TRACE_STATUS,
                                     ide.getMainExecutor(),
                                     event -> event.getCommandId() == response.getCommandId(),
                                     () -> getSessionData().getStreamId(),
                                     () -> getSessionData().getPid(),
                                     event -> {
                                       onTraceStatus.accept(event.getTraceStatus());
                                       // unregisters the listener.
                                       return true;
                                     });
        getStudioProfilers().getTransportPoller().registerListener(statusListener);
        myListenerTracker.trackListener(statusListener, surviveExit);
      }, ide.getPoolExecutor());
  }

  @VisibleForTesting
  public void toggleNativeAllocationTracking() {
    if (!myNativeAllocationTracking) {
      assert getStudioProfilers().getProcess() != null;
      startNativeAllocationTracking();
    }
    else {
      // Not asserting on `getStudioProfilers().getProcess()` because it would be null if the user stops the session
      // before stopping native allocation tracking first.
      stopNativeAllocationTracking();
    }
  }

  /**
   * Handles start tracing status events received by the transport event listener.
   */
  @VisibleForTesting
  void nativeAllocationTrackingStart(@NotNull Trace.TraceStartStatus status) {
    switch (status.getStatus()) {
      case SUCCESS:
        LogUtils.log(getClass(), "Native allocations capture start succeeded");
        myNativeAllocationTracking = true;
        setModelToRecordingNative();
        setPendingCaptureStartTime(status.getStartTimeNs());
        setTrackingAllocations(true);
        myPendingLegacyAllocationStartTimeNs = status.getStartTimeNs();
        getTimeline().setStreaming(true);
        break;
      case FAILURE:
        if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
          myTaskTracker.trackStartTaskFailed(new TaskStartFailedMetadata(status, null, null));
          cleanupFailedCapture();
        }
        getLogger().error("Failure with error code " + status.getErrorCode());
        break;
      case UNSPECIFIED:
        break;
    }
  }

  /**
   * Handles stop tracing status events received by the transport event listener.
   */
  @VisibleForTesting
  void nativeAllocationTrackingStop(@NotNull Trace.TraceStopStatus status) {
    // Whether the stop was successful or resulted in failure, call setFinished() to indicate
    // that the recording has stopped so that the user is able to start a new capture.
    myRecordingOptionsModel.setFinished();

    switch (status.getStatus()) {
      case SUCCESS:
        LogUtils.log(getClass(), "Native allocations capture stop succeeded");
        // stop allocation tracing
        myNativeAllocationTracking = false;
        setTrackingAllocations(false);
        break;
      case NO_ONGOING_PROFILING:
        // TODO: Integrate the ongoing profile detection cpu profiler has with memory profiler
        break;
      default:
        getLogger().error(status.getErrorMessage());
        if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
          cleanupFailedCapture();
          myTaskTracker.trackStopTaskFailed(new TaskStopFailedMetadata(status, null, null));
        }
        break;
    }
  }

  private void setModelToRecordingNative() {
    RecordingOption option = getRecordingOptionsModel().getBuiltInOptions().stream()
      .filter(opt -> opt.getTitle().equals(RECORD_NATIVE_TEXT)).findFirst().orElse(null);
    if (option != null) {
      getRecordingOptionsModel().selectBuiltInOption(option);
      getRecordingOptionsModel().setRecording();
    }
  }

  public void requestHeapDump() {
    assert getStudioProfilers().getProcess() != null;

    if (useJavaHeapGraphForHeapDump()) {
      requestJavaHeapGraphDump();
      return;
    }

    Commands.Command.Builder dumpCommandBuilder = Commands.Command.newBuilder()
      .setStreamId(getSessionData().getStreamId())
      .setPid(getSessionData().getPid())
      .setSessionId(getSessionData().getSessionId())
      .setType(Commands.Command.CommandType.HEAP_DUMP);

    if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
      dumpCommandBuilder.setShouldEndSession(true);
    }

    Commands.Command dumpCommand = dumpCommandBuilder.build();
    CompletableFuture.runAsync(() -> {
      Transport.ExecuteResponse response = getStudioProfilers().getClient().getTransportClient().execute(
        Transport.ExecuteRequest.newBuilder().setCommand(dumpCommand).build());
      TransportEventListener statusListener =
        new TransportEventListener(Common.Event.Kind.MEMORY_HEAP_DUMP_STATUS,
                                   getStudioProfilers().getIdeServices().getMainExecutor(),
                                   event -> event.getCommandId() == response.getCommandId(),
                                   () -> getSessionData().getStreamId(),
                                   () -> getSessionData().getPid(),
                                   event -> {
                                     handleHeapDumpStart(
                                       event.getMemoryHeapdumpStatus().getStatus());
                                     // unregisters the listener.
                                     return true;
                                   });
      getStudioProfilers().getTransportPoller().registerListener(statusListener);
      myListenerTracker.trackListener(statusListener, false);
    }, getStudioProfilers().getIdeServices().getPoolExecutor());
    getTimeline().setStreaming(true);
    getStudioProfilers().getIdeServices().getTemporaryProfilerPreferences().setBoolean(HAS_USED_MEMORY_CAPTURE, true);
  }

  /**
   * Whether a heap dump should be captured as a Perfetto heap graph instead of an ART HPROF, see
   * {@link MemoryProfiler#recordsHeapDumpAsJavaHeapGraph} and {@link MemoryProfiler#JAVA_HEAP_GRAPH_MIN_API}.
   */
  private boolean useJavaHeapGraphForHeapDump() {
    Common.Device device = getDeviceForSelectedSession();
    return MemoryProfiler.recordsHeapDumpAsJavaHeapGraph(getStudioProfilers().getSelectedSessionSupportLevel(),
                                                         getStudioProfilers().getIdeServices().getFeatureConfig()) &&
           device != null &&
           device.getFeatureLevel() >= MemoryProfiler.JAVA_HEAP_GRAPH_MIN_API;
  }

  /**
   * Captures a heap dump as a Perfetto heap graph.
   *
   * <p>This goes through the generic trace pipeline (START_TRACE / STOP_TRACE with {@link Trace.ProfilerType#MEMORY}) because the daemon's
   * HEAP_DUMP command is hardcoded to {@code am dumpheap}. The resulting file surfaces as a MEMORY_TRACE event and is picked up by
   * {@link CaptureDataSeries#ofJavaHeapGraphSamples}.
   */
  private void requestJavaHeapGraphDump() {
    IdeProfilerServices ide = getStudioProfilers().getIdeServices();
    Common.Process process = getStudioProfilers().getProcess();
    // The session's own device, which useJavaHeapGraphForHeapDump() has checked; the device selected in the UI can differ.
    Common.Device device = Objects.requireNonNull(getDeviceForSelectedSession());
    String traceFilePath = String.format(Locale.getDefault(), "%s/%s.trace", DAEMON_DEVICE_DIR_PATH, process.getName());

    Trace.TraceConfiguration configuration = Trace.TraceConfiguration.newBuilder()
      .setAbiCpuArch(TransportFileManager.getShortAbiName(device.getCpuAbi()))
      .setTempPath(traceFilePath)
      .setAppName(process.getName())
      .setInitiationType(Trace.TraceInitiationType.INITIATED_BY_UI)
      .setPerfettoOptions(PerfettoTraceConfigBuilders.INSTANCE.getJavaHeapGraphTraceConfig(
        getSessionData().getPid(), process.getName(), device.getFeatureLevel()))
      .build();

    // surviveExit: this listener is what schedules the stop, and losing it would leave the capture running and block later recordings.
    startMemoryTrace(configuration, true, status -> handleJavaHeapGraphStart(status, configuration),
                     this::cleanupFailedJavaHeapGraphCapture);

    getTimeline().setStreaming(true);
    ide.getTemporaryProfilerPreferences().setBoolean(HAS_USED_MEMORY_CAPTURE, true);
  }

  private void handleJavaHeapGraphStart(@NotNull Trace.TraceStartStatus status, @NotNull Trace.TraceConfiguration startConfiguration) {
    switch (status.getStatus()) {
      case SUCCESS:
        setPendingCaptureStartTime(status.getStartTimeNs());
        getAspect().changed(MemoryProfilerAspect.HEAP_DUMP_STARTED);
        Common.Device device = getDeviceForSelectedSession();
        LogUtils.log(getClass(), String.format(Locale.US, "Perfetto heap graph capture start succeeded (API %d, session %d ms)",
                                               device != null ? device.getFeatureLevel() : 0,
                                               startConfiguration.getPerfettoOptions().getDurationMs()));
        scheduleJavaHeapGraphStop(startConfiguration);
        break;
      default:
        // Includes UNSPECIFIED: no heap graph is coming.
        myTaskTracker.trackStartTaskFailed(new TaskStartFailedMetadata(status, null, null));
        cleanupFailedJavaHeapGraphCapture();
        getLogger().warn("Perfetto heap graph capture failed to start: " + status.getStatus() + ", error code " + status.getErrorCode() +
                         (status.getErrorMessage().isEmpty() ? "" : ", " + status.getErrorMessage()));
        break;
    }
  }

  /**
   * Sends the stop {@link PerfettoTraceConfigBuilders#JAVA_HEAP_GRAPH_STOP_DELAY_MS} after a successful start. Uses a one-shot
   * {@link Updatable} on the profilers {@link Updater}, which outlives this stage, so the stop is still sent if the stage exits first.
   */
  private void scheduleJavaHeapGraphStop(@NotNull Trace.TraceConfiguration startConfiguration) {
    Updater updater = getStudioProfilers().getUpdater();
    long delayNs = TimeUnit.MILLISECONDS.toNanos(PerfettoTraceConfigBuilders.JAVA_HEAP_GRAPH_STOP_DELAY_MS);
    updater.register(new Updatable() {
      private long myElapsedNs;

      @Override
      public void update(long elapsedNs) {
        myElapsedNs += elapsedNs;
        if (myElapsedNs >= delayNs) {
          // Unregister so the stop is sent exactly once.
          updater.unregister(this);
          stopJavaHeapGraphDump(startConfiguration);
        }
      }
    });
  }

  /**
   * Stops the heap graph capture started with {@code startConfiguration}. Takes the app name from it rather than the current process,
   * which may be gone by the time this delayed stop runs.
   */
  private void stopJavaHeapGraphDump(@NotNull Trace.TraceConfiguration startConfiguration) {
    stopMemoryTrace(startConfiguration.getAppName(), startConfiguration.getAbiCpuArch(), this::handleJavaHeapGraphStop,
                    this::cleanupFailedJavaHeapGraphCapture);
  }

  private void handleJavaHeapGraphStop(@NotNull Trace.TraceStopStatus status) {
    myRecordingOptionsModel.setFinished();
    switch (status.getStatus()) {
      case SUCCESS:
        // The daemon's stop waits for perfetto to exit, and on Android 11 first for the trace file to stop growing, so this is how long the
        // dump took to finish after the stop.
        LogUtils.log(getClass(), "Perfetto heap graph capture stop succeeded after " +
                                 TimeUnit.NANOSECONDS.toMillis(status.getStoppingDurationNs()) + " ms");
        break;
      default:
        // Includes NO_ONGOING_PROFILING and UNSPECIFIED: no heap graph is coming.
        cleanupFailedJavaHeapGraphCapture();
        myTaskTracker.trackStopTaskFailed(new TaskStopFailedMetadata(status, null, null));
        // The daemon sets only the error code for a Perfetto stop, not the message.
        getLogger().warn("Perfetto heap graph capture failed to stop: " + status.getStatus() + ", error code " + status.getErrorCode());
        break;
    }
  }

  private void handleHeapDumpStart(@NotNull Memory.HeapDumpStatus status) {
    switch (status.getStatus()) {
      case SUCCESS:
        setPendingCaptureStartTime(status.getStartTime());
        getAspect().changed(MemoryProfilerAspect.HEAP_DUMP_STARTED);
        LogUtils.log(getClass(), "Heap dump capture start succeeded");
        break;
      case IN_PROGRESS:
        getLogger().debug(String.format(Locale.getDefault(), "A heap dump for %d is already in progress.", getSessionData().getPid()));
        break;
      case UNSPECIFIED:
      case NOT_PROFILING:
      case FAILURE_UNKNOWN:
      case UNRECOGNIZED:
        if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
          cleanupFailedCapture();
          myTaskTracker.trackStartTaskFailed(new TaskStartFailedMetadata(null, null, status));
        }
        break;
    }
  }

  public DurationDataModel<CaptureDurationData<? extends CaptureObject>> getHeapDumpSampleDurations() {
    return myHeapDumpDurations;
  }

  /**
   * @param enable whether to enable or disable allocation tracking.
   * @return the actual status, which may be different from the input
   */
  public void trackAllocations(boolean enable) {
    MemoryProfiler.trackAllocations(getStudioProfilers(), getSessionData(), enable, true, status -> {
      switch (status.getStatus()) {
        case SUCCESS:
          setTrackingAllocations(enable);
          setPendingCaptureStartTime(status.getStartTime());
          myPendingLegacyAllocationStartTimeNs = enable ? status.getStartTime() : INVALID_START_TIME;
          break;
        case IN_PROGRESS:
          setTrackingAllocations(true);
          break;
        case NOT_ENABLED:
          setTrackingAllocations(false);
          break;
        default:
          if (getStudioProfilers().getIdeServices().getFeatureConfig().isTaskBasedUxEnabled()) {
            cleanupFailedCapture();
            if (enable) {
              // Start task failure
              myTaskTracker.trackStartTaskFailed(new TaskStartFailedMetadata(null, status, null));
            }
            else {
              // Stop task failure
              myTaskTracker.trackStopTaskFailed(new TaskStopFailedMetadata(null, status, null));
            }
          }
          break;
      }
      getAspect().changed(MemoryProfilerAspect.TRACKING_ENABLED);

      if (isTrackingAllocations()) {
        getTimeline().setStreaming(true);
        getStudioProfilers().getIdeServices().getTemporaryProfilerPreferences().setBoolean(HAS_USED_MEMORY_CAPTURE, true);
      }
    }, listener -> myListenerTracker.trackListener(listener, !enable));
  }

  public long getAllocationTrackingElapsedTimeNs() {
    if (isTrackingAllocations()) {
      try {
        TimeResponse timeResponse = getStudioProfilers().getClient().getTransportClient()
          .getCurrentTime(TimeRequest.newBuilder().setStreamId(getSessionData().getStreamId()).build());
        return timeResponse.getTimestampNs() - myPendingLegacyAllocationStartTimeNs;
      }
      catch (StatusRuntimeException exception) {
        getLogger().warn(exception);
      }
    }
    return INVALID_START_TIME;
  }

  public boolean isNativeAllocationSamplingEnabled() {
    Common.Device device = getDeviceForSelectedSession();
    return device != null && device.getFeatureLevel() >= AndroidVersion.VersionCodes.Q;
  }

  @NotNull
  public DurationDataModel<CaptureDurationData<? extends CaptureObject>> getAllocationInfosDurations() {
    return myAllocationDurations;
  }

  @NotNull
  public DurationDataModel<CaptureDurationData<? extends CaptureObject>> getNativeAllocationInfosDurations() {
    return myNativeAllocationDurations;
  }

  @Override
  @NotNull
  public Runnable getStopAction() {
    return myStopAction;
  }

  @Nullable
  @Override
  public RecordingScreenModel<MainMemoryProfilerStage> getRecordingScreenModel() {
    return myRecordingScreenModel;
  }

  @VisibleForTesting
  public void selectCaptureDuration(@Nullable CaptureDurationData<? extends CaptureObject> durationData,
                                    @Nullable Executor joiner) {
    StudioProfilers profilers = getStudioProfilers();
    if (durationData instanceof AllocationDurationData) {
      profilers.setStage(AllocationStage.makeStaticStage(profilers,
                                                         ((AllocationDurationData<?>)durationData).getStart(),
                                                         ((AllocationDurationData<?>)durationData).getEnd()));
    }
    else if (durationData != null &&
             (HeapDumpCaptureObject.class.isAssignableFrom(durationData.getCaptureObjectType()) ||
              NativeAllocationSampleCaptureObject.class.isAssignableFrom(durationData.getCaptureObjectType()) ||
              LegacyAllocationCaptureObject.class.isAssignableFrom(durationData.getCaptureObjectType()))) {
      boolean isTaskBasedUxEnabled = profilers.getIdeServices().getFeatureConfig().isTaskBasedUxEnabled();
      boolean isUnifiedEditorEnabled = false;
      if (HeapDumpCaptureObject.class.isAssignableFrom(durationData.getCaptureObjectType())) {
        isUnifiedEditorEnabled = profilers.getIdeServices().getFeatureConfig().isHeapDumpTraceInEditorEnabled();
      }
      else if (NativeAllocationSampleCaptureObject.class.isAssignableFrom(durationData.getCaptureObjectType())) {
        isUnifiedEditorEnabled = profilers.getIdeServices().getFeatureConfig().isNativeAllocationsTraceInEditorEnabled();
      }
      else if (LegacyAllocationCaptureObject.class.isAssignableFrom(durationData.getCaptureObjectType())) {
        isUnifiedEditorEnabled = profilers.getIdeServices().getFeatureConfig().isJavaKotlinAllocationsLegacyTraceInEditorEnabled();
      }

      if (isTaskBasedUxEnabled && isUnifiedEditorEnabled) {
        if (!getCaptureSelection().selectCaptureEntry(durationData.getCaptureEntry())) {
          return;
        }
        String deviceSerial = getSelectedDeviceSerial();
        // Read the session here, so the saved file's name, its transport request and its metadata all use the same one.
        Common.Session session = profilers.getSession();
        profilers.getIdeServices().getPoolExecutor().execute(() -> {
          CaptureObject capture = durationData.getCaptureEntry().getCaptureObject();
          String extension = capture.getExportableExtension() != null ? capture.getExportableExtension() : "alloc";
          // The capture's start time is also the ID of its file in the transport pipeline.
          File recordingFile = ProfilerCaptureFileUtils.getRecordingFile(
            session, profilers.getSessionsManager().getSessionMetaData(session.getSessionId()), getMemoryTaskType(capture),
            capture.getStartTimeNs(), extension, capture.getStartTimeNs());
          File file = recordingFile;
          if (!file.exists()) {
            Transport.BytesRequest request = Transport.BytesRequest.newBuilder()
              .setStreamId(session.getStreamId())
              .setId(String.valueOf(capture.getStartTimeNs()))
              .build();
            Transport.FileResponse response = profilers.getClient().getTransportClient().getFile(request);
            if (!response.getFilePath().isEmpty()) {
              File originalFile = new File(response.getFilePath());
              File processedFile = originalFile;
              TraceDeobfuscation deobfuscation = getTraceDeobfuscation(capture);
              if (deobfuscation != null) {
                processedFile = TraceDeobfuscator.deobfuscate(profilers.getIdeServices(), originalFile, deobfuscation.appName(),
                                                              deviceSerial, deobfuscation.withNativeSymbols());
              }
              file = ProfilerCaptureFileUtils.renameToTargetFile(processedFile, recordingFile);
              if (file == null) {
                file = processedFile;
              }
              // Delete the original raw trace when traceconv generated a distinct bundled file,
              // to avoid leaking stale files in the temp directory.
              if (!FileUtil.filesEqual(processedFile, originalFile)) {
                FileUtil.delete(originalFile);
              }
            }
          }
          ProfilerCaptureFileUtils.writeMetadataToFile(profilers, session, file);
          final File traceFile = file;
          profilers.getIdeServices().getMainExecutor().execute(() -> {
            if (traceFile != null && traceFile.exists() && traceFile.length() > 0) {
              profilers.getIdeServices().openTraceFile(traceFile);
            }
            else {
              // Shared by every recording opened in the editor, so name the file rather than the task.
              getLogger().warn(traceFile == null || !traceFile.exists()
                               ? "No file arrived from the device for " + recordingFile
                               : "The file from the device is empty: " + traceFile);
              profilers.getIdeServices().showNotification(MemoryProfilerNotifications.getCaptureFailure(capture));
              cleanupFailedCapture();
            }
            closeMemoryTaskTab(capture);
          });
        });
      }
      else {
        profilers.setStage(new MemoryCaptureStage(profilers, getContext(), getLoader(), durationData, joiner));
      }
    }
    else {
      doSelectCaptureDuration(durationData, joiner);
    }
  }

  private void closeMemoryTaskTab(@NotNull CaptureObject capture) {
    ProfilerTaskType taskType = getMemoryTaskType(capture);
    if (taskType != ProfilerTaskType.UNSPECIFIED) {
      getStudioProfilers().getIdeServices().closeTaskTab(taskType);
    }
  }

  /** Returns the task that records {@code capture}: Heap Dump, Native Allocations or the legacy Java/Kotlin Allocations. */
  @NotNull
  private static ProfilerTaskType getMemoryTaskType(@NotNull CaptureObject capture) {
    if (capture instanceof HeapDumpCaptureObject) {
      return ProfilerTaskType.HEAP_DUMP;
    }
    else if (capture instanceof NativeAllocationSampleCaptureObject) {
      return ProfilerTaskType.NATIVE_ALLOCATIONS;
    }
    else if (capture instanceof LegacyAllocationCaptureObject) {
      return ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS;
    }
    return ProfilerTaskType.UNSPECIFIED;
  }

  /** Whose R8 mapping a recorded trace needs, and whether it also needs native symbols. */
  private record TraceDeobfuscation(@NotNull String appName, boolean withNativeSymbols) {}

  /**
   * Returns what {@code capture}'s trace needs added before it opens, or null if it opens as recorded. To deobfuscate another kind of
   * capture, add a case here.
   */
  @Nullable
  private TraceDeobfuscation getTraceDeobfuscation(@NotNull CaptureObject capture) {
    FeatureConfig featureConfig = getStudioProfilers().getIdeServices().getFeatureConfig();
    if (capture instanceof NativeAllocationSampleCaptureObject nativeAllocations &&
        featureConfig.isDeobfuscationForNativeAllocationsEnabled()) {
      return new TraceDeobfuscation(nativeAllocations.getAppName(), true);
    }
    // A heap graph has no native frames, so it gets only the R8 mapping. An ART HPROF is not a Perfetto trace, so it is skipped.
    if (capture instanceof HeapDumpCaptureObject heapDump &&
        heapDump.isPerfettoHeapGraph() &&
        heapDump.getAppName() != null &&
        featureConfig.isDeobfuscationForHeapDumpsEnabled()) {
      return new TraceDeobfuscation(heapDump.getAppName(), false);
    }
    return null;
  }

  @NotNull
  private String getSelectedDeviceSerial() {
    Common.Stream stream = getStudioProfilers().getStream(getStudioProfilers().getSession().getStreamId());
    return stream.getType() == Common.Stream.Type.DEVICE ? stream.getDevice().getSerial() : "";
  }

  private void updateAllocationTrackingStatus() {
    List<AllocationsInfo> allocationsInfos = MemoryProfiler.getAllocationInfosForSession(getStudioProfilers().getClient(),
                                                                                         getSessionData(),
                                                                                         new Range(Long.MIN_VALUE, Long.MAX_VALUE));
    AllocationsInfo lastInfo = allocationsInfos.isEmpty() ? null : allocationsInfos.get(allocationsInfos.size() - 1);
    setTrackingAllocations(lastInfo != null && (lastInfo.getLegacy() && lastInfo.getEndTime() == Long.MAX_VALUE));
    if (isTrackingAllocations()) {
      setPendingCaptureStartTime(lastInfo.getStartTime());
      myPendingLegacyAllocationStartTimeNs = lastInfo.getStartTime();
    }
    else {
      setPendingCaptureStartTime(INVALID_START_TIME);
      myPendingLegacyAllocationStartTimeNs = INVALID_START_TIME;
    }
  }

  private void updateNativeAllocationTrackingStatus() {
    List<Common.Event> events =
      MemoryProfiler.getNativeHeapEventsForSessionSortedByTimestamp(getStudioProfilers().getClient(), getSessionData(),
                                                                    new Range(Long.MIN_VALUE, Long.MAX_VALUE));
    if (events.isEmpty()) {
      return;
    }
    Trace.TraceStartStatus lastStartStatus = events.get(events.size() - 1).getTraceStatus().getTraceStartStatus();
    // If there is an ongoing recording. Skip a heap graph: it also starts with START_TRACE, and stops on its own, so it can still be
    // running after its stage has exited.
    if (lastStartStatus.getStatus() == Trace.TraceStartStatus.Status.SUCCESS &&
        !isJavaHeapGraphTrace(lastStartStatus.getStartTimeNs())) {
      nativeAllocationTrackingStart(lastStartStatus);
    }
  }

  /** Whether the memory trace that started at {@code startTimeNs} is a heap graph. */
  private boolean isJavaHeapGraphTrace(long startTimeNs) {
    return MemoryProfiler.getJavaHeapGraphsForSession(getStudioProfilers().getClient(), getSessionData(),
                                                      new Range(Long.MIN_VALUE, Long.MAX_VALUE))
      .stream().anyMatch(info -> info.getFromTimestamp() == startTimeNs);
  }

  private static RecordingOption makeToggleOption(String title, String desc, Runnable toggle) {
    return new RecordingOption(title, desc, toggle, toggle);
  }

  private void cleanupFailedCapture() {
    if (getStudioProfilers().getSessionsManager().isSessionAlive()) {
      getStudioProfilers().getSessionsManager().endCurrentSession();
    }

    if (myRecordingScreenModel != null) {
      myRecordingScreenModel.setRecordingFailed();
    }

    myRecordingOptionsModel.setFinished();
    myNativeAllocationTracking = false;
    setTrackingAllocations(false);
    getTimeline().setStreaming(false);
  }

  /**
   * Like {@link #cleanupFailedCapture}, for a heap graph. A heap dump has no Stop button, so any failure must clean up, or the recording
   * screen stays stuck. Its listeners outlive this stage, so if another session has been selected since, leave that session alone.
   */
  private void cleanupFailedJavaHeapGraphCapture() {
    // The daemon still reports the end of a trace whose stop failed. It has no file, so don't select it when it arrives.
    setPendingCaptureStartTime(INVALID_START_TIME);
    if (getSessionData().getSessionId() == getStudioProfilers().getSession().getSessionId()) {
      cleanupFailedCapture();
    }
  }

  public static boolean canSafelyLoadHprof(long fileSize) {
    System.gc(); // To avoid overly conservative estimation of free memory
    long leeway = 300 * 1024 * 1024; // Studio needs ~300MB to run without major freezes
    long requestableMemory = Runtime.getRuntime().maxMemory() -
                             Runtime.getRuntime().totalMemory() +
                             Runtime.getRuntime().freeMemory();
    return requestableMemory >= MEMORY_HPROF_SAFE_FACTOR * fileSize + leeway;
  }
}