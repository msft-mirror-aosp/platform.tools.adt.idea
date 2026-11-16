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

import static com.android.tools.profilers.memory.ClassGrouping.ARRANGE_BY_CLASS;
import static com.android.tools.profilers.memory.ClassGrouping.ARRANGE_BY_PACKAGE;
import static com.android.tools.profilers.memory.MemoryProfilerTestUtils.findChildClassSetNodeWithClassName;
import static com.android.tools.profilers.memory.MemoryProfilerTestUtils.findDescendantClassSetNodeWithInstance;
import static com.android.tools.profilers.memory.MemoryProfilerTestUtils.getRootClassifierSet;
import static com.google.common.truth.Truth.assertThat;

import com.android.tools.idea.transport.faketransport.FakeGrpcChannel;
import com.android.tools.idea.transport.faketransport.FakeTransportService;
import com.android.tools.profiler.proto.Memory.HeapDumpInfo;
import com.android.tools.profilers.FakeIdeProfilerComponents;
import com.android.tools.profilers.ProfilerClient;
import com.android.tools.profilers.ProfilersTestData;
import com.android.tools.profilers.StudioProfilers;
import com.android.tools.profilers.StudioProfilersView;
import com.android.tools.profilers.TaskProfilersView;
import com.android.tools.profilers.memory.adapters.CaptureObject;
import com.android.tools.profilers.memory.adapters.FakeCaptureObject;
import com.android.tools.profilers.memory.adapters.FakeInstanceObject;
import com.android.tools.profilers.memory.adapters.HeapDumpCaptureObject;
import com.android.tools.profilers.memory.adapters.InstanceObject;
import com.android.tools.profilers.memory.adapters.classifiers.ClassSet;
import com.android.tools.profilers.memory.adapters.classifiers.ClassifierSet;
import com.android.tools.profilers.memory.adapters.classifiers.HeapSet;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.intellij.testFramework.ApplicationRule;
import com.intellij.testFramework.DisposableRule;
import com.intellij.testFramework.EdtRule;
import com.intellij.testFramework.RunsInEdt;
import java.io.File;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests how the {@link CapturePanel} (hosted by {@link AllocationStageView} and {@link MemoryCaptureStageView}) reflects the capture,
 * heap, class set and instance selection of its {@link MemoryCaptureSelection}.
 */
@RunsInEdt
public final class CapturePanelSelectionTest extends MemoryProfilerTestBase {
  @NotNull private final FakeTransportService myTransportService = new FakeTransportService(myTimer);
  @Rule public FakeGrpcChannel myGrpcChannel = new FakeGrpcChannel("CapturePanelSelectionTestChannel", myTransportService);
  @Rule public final ApplicationRule myApplicationRule = new ApplicationRule();
  @Rule public final DisposableRule myDisposableRule = new DisposableRule();
  @Rule public final EdtRule myEdtRule = new EdtRule();

  private StudioProfilersView myProfilersView;
  private CapturePanel myCapturePanel;

  @Override
  protected FakeGrpcChannel getGrpcChannel() {
    return myGrpcChannel;
  }

  @Override
  protected void onProfilersCreated(StudioProfilers profilers) {
    myProfilersView = new TaskProfilersView(profilers, new FakeIdeProfilerComponents(), myDisposableRule.getDisposable());
  }

  @Before
  public void setup() {
    // MemoryProfilerTestBase#setupBase is already run as a @Before method of the base class.
    myCapturePanel = new CapturePanel(myProfilersView, myStage.getCaptureSelection(), null,
                                      myStage.getRangeSelectionModel().getSelectionRange(), new FakeIdeProfilerComponents(),
                                      myStage.getTimeline(), false);
  }

  @Test
  public void testCaptureAndHeapSelection() {
    final String sampleClassName1 = "SAMPLE_CLASS1";
    final String sampleClassName2 = "SAMPLE_CLASS2";

    Map<Integer, String> heapIdMap = ImmutableMap.of(0, "heap1", 1, "heap2");

    FakeCaptureObject fakeCapture1 = new FakeCaptureObject.Builder()
      .setCaptureName("SAMPLE_CAPTURE1")
      .setHeapIdToNameMap(heapIdMap)
      .setStartTime(5)
      .setEndTime(10)
      .build();
    InstanceObject fakeInstance1 = new FakeInstanceObject.Builder(fakeCapture1, 1, sampleClassName1)
      .setName("SAMPLE_INSTANCE1")
      .setHeapId(0)
      .setDepth(4)
      .setShallowSize(5)
      .setRetainedSize(6)
      .build();
    InstanceObject fakeInstance2 = new FakeInstanceObject.Builder(fakeCapture1, 2, sampleClassName2)
      .setName("SAMPLE_INSTANCE2")
      .setDepth(1)
      .setShallowSize(2)
      .setRetainedSize(3)
      .build();
    fakeCapture1.addInstanceObjects(ImmutableSet.of(fakeInstance1, fakeInstance2));

    FakeCaptureObject fakeCapture2 = new FakeCaptureObject.Builder()
      .setCaptureName("SAMPLE_CAPTURE2")
      .setHeapIdToNameMap(heapIdMap)
      .setStartTime(5)
      .setEndTime(10)
      .build();
    InstanceObject fakeInstance3 = new FakeInstanceObject.Builder(fakeCapture2, 1, sampleClassName1)
      .setName("SAMPLE_INSTANCE1")
      .setHeapId(0)
      .setDepth(4)
      .setShallowSize(5)
      .setRetainedSize(6)
      .build();
    InstanceObject fakeInstance4 = new FakeInstanceObject.Builder(fakeCapture2, 2, sampleClassName2)
      .setName("SAMPLE_INSTANCE2")
      .setDepth(1)
      .setShallowSize(2)
      .setRetainedSize(3)
      .build();
    fakeCapture2.addInstanceObjects(ImmutableSet.of(fakeInstance3, fakeInstance4));

    MemoryClassifierView classifierView = myCapturePanel.getClassifierView();

    assertView(null, null, null, null, false);

    myStage.selectCaptureDuration(makeCaptureDurationData(fakeCapture1), null);
    assertView(fakeCapture1, null, null, null, true);
    myAspectObserver.assertAndResetCounts(0, 1, 0, 0, 0, 0, 0, 0);
    myMockLoader.runTask();

    JTree classifierTree = classifierView.getTree();
    assertThat(classifierTree).isNotNull();
    HeapSet selectedHeap = myStage.getCaptureSelection().getSelectedHeapSet();
    assertThat(selectedHeap).isNotNull();
    assertView(fakeCapture1, selectedHeap, null, null, false);
    myAspectObserver.assertAndResetCounts(0, 0, 1, 0, 1, 0, 0, 0);

    // Tests selecting a capture which loads immediately.
    myMockLoader.setReturnImmediateFuture(true);
    myStage.selectCaptureDuration(makeCaptureDurationData(fakeCapture2), null);
    classifierTree = classifierView.getTree();
    assertThat(classifierTree).isNotNull();
    selectedHeap = myStage.getCaptureSelection().getSelectedHeapSet();
    // 2 heap changes: 1 from changing the capture, the other from the auto-selection after the capture is loaded.
    assertView(fakeCapture2, selectedHeap, null, null, false);
    myAspectObserver.assertAndResetCounts(0, 1, 1, 0, 2, 0, 0, 0);

    myStage.getCaptureSelection().selectHeapSet(fakeCapture2.getHeapSet(0));
    assertSelection(fakeCapture2, fakeCapture2.getHeapSet(0), null, null);
    myAspectObserver.assertAndResetCounts(0, 0, 0, 0, 0, 0, 0, 0);

    ClassSet selectedClassSet =
      findDescendantClassSetNodeWithInstance(getRootClassifierSet(classifierTree).getAdapter(), fakeInstance3);
    assertThat(selectedClassSet).isNotNull();
    myStage.getCaptureSelection().selectClassSet(selectedClassSet);
    assertView(fakeCapture2, fakeCapture2.getHeapSet(0), selectedClassSet, null, false);
    myAspectObserver.assertAndResetCounts(0, 0, 0, 0, 0, 1, 0, 0);

    assertThat(myStage.getCaptureSelection().getClassGrouping()).isEqualTo(ARRANGE_BY_CLASS);
    myStage.getCaptureSelection().setClassGrouping(ARRANGE_BY_PACKAGE);
    assertThat(myStage.getCaptureSelection().getClassGrouping()).isEqualTo(ARRANGE_BY_PACKAGE);
    myAspectObserver.assertAndResetCounts(0, 0, 0, 1, 0, 1, 0, 0);

    MemoryObjectTreeNode<ClassifierSet> memoryClassRoot = getRootClassifierSet(classifierTree);
    MemoryObjectTreeNode<ClassSet> targetSet = findChildClassSetNodeWithClassName(memoryClassRoot, sampleClassName1);
    classifierTree.setSelectionPath(new TreePath(new Object[]{memoryClassRoot, targetSet}));
    assertSelection(fakeCapture2, fakeCapture2.getHeapSet(0), targetSet.getAdapter(), null);

    myStage.getCaptureSelection().selectInstanceObject(fakeInstance3);
    assertSelection(fakeCapture2, fakeCapture2.getHeapSet(0), targetSet.getAdapter(), fakeInstance3);
    assertView(fakeCapture2, fakeCapture2.getHeapSet(0), targetSet.getAdapter(), fakeInstance3, false);

    myStage.selectCaptureDuration(null, null);
    assertView(null, null, null, null, false);
  }

  @Test
  public void testLoadingNewCaptureWithExistingLoad() {
    Map<Integer, String> heapIdMap = ImmutableMap.of(0, "heap1", 1, "heap2");

    FakeCaptureObject fakeCapture1 =
      new FakeCaptureObject.Builder().setCaptureName("SAMPLE_CAPTURE1").setHeapIdToNameMap(heapIdMap).setStartTime(5).setEndTime(10)
        .build();
    FakeCaptureObject fakeCapture2 =
      new FakeCaptureObject.Builder().setCaptureName("SAMPLE_CAPTURE2").setHeapIdToNameMap(heapIdMap).setStartTime(10).setEndTime(15)
        .build();
    InstanceObject fakeInstance1 =
      new FakeInstanceObject.Builder(fakeCapture2, 1, "SAMPLE_CLASS").setName("SAMPLE_INSTANCE1").setDepth(4).setShallowSize(5)
        .setRetainedSize(6).build();
    fakeCapture2.addInstanceObjects(ImmutableSet.of(fakeInstance1));

    myStage
      .selectCaptureDuration(new CaptureDurationData<>(1, false, false, new CaptureEntry<CaptureObject>(new Object(), () -> fakeCapture1)),
                             null);
    assertView(fakeCapture1, null, null, null, true);
    myAspectObserver.assertAndResetCounts(0, 1, 0, 0, 0, 0, 0, 0);

    // Select a new capture before the first is loaded.
    myStage
      .selectCaptureDuration(new CaptureDurationData<>(1, false, false, new CaptureEntry<CaptureObject>(new Object(), () -> fakeCapture2)),
                             null);
    assertView(fakeCapture2, null, null, null, true);
    myAspectObserver.assertAndResetCounts(0, 1, 0, 0, 0, 0, 0, 0);
    myMockLoader.runTask();
    assertView(fakeCapture2, fakeCapture2.getHeapSet(0), null, null, false);
    myAspectObserver.assertAndResetCounts(0, 0, 1, 0, 1, 0, 0, 0);
  }

  @Test
  public void testCaptureInfoMessage_showsWhenLoadingCaptureWithMessage_hiddenWhenLoadingHeapDump() {
    Executor joiner = MoreExecutors.directExecutor();
    myMockLoader.setReturnImmediateFuture(true);

    // Load a fake capture with a non-null info message and verify the message is displayed.
    FakeCaptureObject fakeCapture =
      new FakeCaptureObject.Builder().setCaptureName("SAMPLE_CAPTURE1").setStartTime(0).setEndTime(10).setInfoMessage("Foo").build();
    myStage.selectCaptureDuration(
      new CaptureDurationData<>(1, false, false, new CaptureEntry<>(new Object(), () -> fakeCapture)), joiner);
    // FakeCaptureObject's load() is a no-op, so force refresh here.
    myStage.getCaptureSelection().refreshSelectedHeap();
    assertThat(myCapturePanel.getCaptureInfoMessage().isVisible()).isTrue();

    // Load a heap dump capture and verify the message is hidden.
    HeapDumpInfo heapDumpInfo = HeapDumpInfo.newBuilder()
      .setStartTime(TimeUnit.MICROSECONDS.toNanos(3))
      .setEndTime(TimeUnit.MICROSECONDS.toNanos(4))
      .build();
    HeapDumpCaptureObject heapDumpCapture = new HeapDumpCaptureObject(new ProfilerClient(getGrpcChannel().getChannel()),
                                                                      ProfilersTestData.SESSION_DATA,
                                                                      heapDumpInfo,
                                                                      null,
                                                                      myIdeProfilerServices.getFeatureTracker(),
                                                                      myStage.getStudioProfilers().getIdeServices(),
                                                                      () -> new File("dummy"));
    myStage.selectCaptureDuration(
      new CaptureDurationData<>(1, false, false, new CaptureEntry<>(new Object(), () -> heapDumpCapture)), joiner);
    // Manually refresh the UI, since the test loader bypasses the real async-load notification pipeline.
    myStage.getCaptureSelection().refreshSelectedHeap();
    assertThat(myCapturePanel.getCaptureInfoMessage().isVisible()).isFalse();
  }

  private static CaptureDurationData<CaptureObject> makeCaptureDurationData(CaptureObject capture) {
    return new CaptureDurationData<>(1,
                                     false,
                                     false,
                                     new CaptureEntry<>(new Object(), () -> capture));
  }

  private void assertSelection(@Nullable CaptureObject expectedCaptureObject,
                               @Nullable HeapSet expectedHeapSet,
                               @Nullable ClassSet expectedClassSet,
                               @Nullable InstanceObject expectedInstanceObject) {
    assertThat(myStage.getCaptureSelection().getSelectedCapture()).isEqualTo(expectedCaptureObject);
    assertThat(myStage.getCaptureSelection().getSelectedHeapSet()).isEqualTo(expectedHeapSet);
    assertThat(myStage.getCaptureSelection().getSelectedClassSet()).isEqualTo(expectedClassSet);
    assertThat(myStage.getCaptureSelection().getSelectedInstanceObject()).isEqualTo(expectedInstanceObject);
  }


  private void assertView(@Nullable CaptureObject expectedCaptureObject,
                          @Nullable HeapSet expectedHeapSet,
                          @Nullable ClassSet expectedClassSet,
                          @Nullable InstanceObject expectedInstanceObject,
                          boolean isCaptureLoading) {
    if (expectedCaptureObject == null) {
      assertThat(myCapturePanel.getClassifierView().getTree()).isNull();
      assertThat(myCapturePanel.getClassSetView().getComponent().isVisible()).isFalse();
      assertThat(myCapturePanel.getInstanceDetailsView().getComponent().isVisible()).isFalse();
      return;
    }

    // Nothing to check while loading, as components are not yet populated.
    if (!isCaptureLoading) {
      assertThat(myStage.getCaptureSelection().getSelectedHeapSet()).isEqualTo(expectedHeapSet);
    }

    if (expectedHeapSet == null) {
      assertThat(myCapturePanel.getClassifierView().getTree()).isNull();
      return;
    }

    JTree classifierTree = myCapturePanel.getClassifierView().getTree();
    assertThat(classifierTree).isNotNull();

    if (expectedClassSet == null) {
      assertThat(classifierTree.getLastSelectedPathComponent()).isNull();
      assertThat(myCapturePanel.getClassSetView().getComponent().isVisible()).isFalse();
      assertThat(myCapturePanel.getInstanceDetailsView().getComponent().isVisible()).isFalse();
      return;
    }

    Object selectedClassNode = classifierTree.getLastSelectedPathComponent();
    assertThat(selectedClassNode).isInstanceOf(MemoryObjectTreeNode.class);
    assertThat(((MemoryObjectTreeNode)selectedClassNode).getAdapter()).isInstanceOf(ClassSet.class);
    //noinspection unchecked
    MemoryObjectTreeNode<ClassSet> selectedClassObject = (MemoryObjectTreeNode<ClassSet>)selectedClassNode;
    assertThat(selectedClassObject.getAdapter()).isEqualTo(expectedClassSet);

    assertThat(myCapturePanel.getClassSetView().getComponent().isVisible()).isTrue();
    JTree classSetTree = myCapturePanel.getClassSetView().getTree();
    assertThat(classSetTree).isNotNull();

    if (expectedInstanceObject == null) {
      assertThat(classSetTree.getLastSelectedPathComponent()).isNull();
      assertThat(myCapturePanel.getInstanceDetailsView().getComponent().isVisible()).isFalse();
      return;
    }

    Object selectedInstanceNode = classSetTree.getLastSelectedPathComponent();
    assertThat(selectedInstanceNode).isInstanceOf(MemoryObjectTreeNode.class);
    assertThat(((MemoryObjectTreeNode)selectedInstanceNode).getAdapter()).isInstanceOf(InstanceObject.class);
    //noinspection unchecked
    MemoryObjectTreeNode<InstanceObject> selectedInstanceObject = (MemoryObjectTreeNode<InstanceObject>)selectedInstanceNode;
    assertThat(selectedInstanceObject.getAdapter()).isEqualTo(expectedInstanceObject);

    boolean detailsViewVisible = expectedInstanceObject.getCallStackDepth() > 0 || !expectedInstanceObject.getReferences().isEmpty();
    assertThat(myCapturePanel.getInstanceDetailsView().getComponent().isVisible()).isEqualTo(detailsViewVisible);
  }
}
