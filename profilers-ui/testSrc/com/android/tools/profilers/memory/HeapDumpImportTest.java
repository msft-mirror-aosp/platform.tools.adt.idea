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
package com.android.tools.profilers.memory;

import static com.google.common.truth.Truth.assertThat;

import com.android.tools.adtui.model.FakeTimer;
import com.android.tools.idea.protobuf.ByteString;
import com.android.tools.idea.transport.TransportService;
import com.android.tools.idea.transport.TransportServiceUtils;
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel;
import com.android.tools.idea.transport.faketransport.FakeTransportService;
import com.android.tools.idea.transport.faketransport.TransportServiceTestImpl;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.Transport;
import com.android.tools.profilers.sessions.SessionsManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.ApplicationRule;
import com.intellij.testFramework.ServiceContainerUtil;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for importing a heap dump file into the memory profiler.
 *
 * <p>These tests run off the EDT because loading the imported heap dump reads the trace file,
 * which must not happen on the EDT. In production, {@code IdeProfilerServices#runAsync} loads
 * the file on a pooled thread, but the test fake runs it synchronously on the calling thread.
 */
public final class HeapDumpImportTest extends MemoryProfilerTestBase {
  @NotNull private final FakeTransportService myTransportService = new FakeTransportService(myTimer);
  @Rule
  public FakeGrpcChannel myGrpcChannel =
    new FakeGrpcChannel("HeapDumpImportTestChannel", myTransportService);
  @Rule public final ApplicationRule myApplicationRule = new ApplicationRule();

  @Override
  protected FakeGrpcChannel getGrpcChannel() {
    return myGrpcChannel;
  }

  @Before
  public void setup() {
    super.setupBase();
    ServiceContainerUtil.registerServiceInstance(ApplicationManager.getApplication(), TransportService.class,
                                                 new TransportServiceTestImpl(myTransportService));
  }

  @Test
  public void testLoadHeapDumpFromFile() throws Exception {
    myIdeProfilerServices.enableTaskBasedUx(false);

    SessionsManager sessionsManager = myProfilers.getSessionsManager();

    // Create a temp file
    String data = "random_string_~!@#$%^&*()_+";
    File file = TransportServiceUtils.createTempFile("fake.heap.dump", ".hprof",
                                                     ByteString.copyFrom(data, Charset.defaultCharset()));
    // Import heap dump from file
    assertThat(sessionsManager.importSessionFromFile(file)).isTrue();
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    assertThat(myProfilers.getStage()).isInstanceOf(MemoryCaptureStage.class);

    Common.Session session = sessionsManager.getSelectedSession();
    long dumpTime = session.getStartTimestamp();
    Transport.BytesRequest request = Transport.BytesRequest.newBuilder()
      .setStreamId(session.getStreamId())
      .setId(Long.toString(dumpTime))
      .build();
    Transport.FileResponse response = myProfilers.getClient().getTransportClient().getFile(request);
    ByteString fileContents = ByteString.copyFrom(Files.readAllBytes(new File(response.getFilePath()).toPath()));
    assertThat(fileContents).isEqualTo(ByteString.copyFrom(data, Charset.defaultCharset()));
  }

  @Test
  public void testLoadHeapDumpFromProfFile() throws Exception {
    myIdeProfilerServices.enableTaskBasedUx(false);

    SessionsManager sessionsManager = myProfilers.getSessionsManager();

    // Create a temp file
    String data = "random_string_~!@#$%^&*()_+";
    File file = TransportServiceUtils.createTempFile("fake.heap.dump", ".prof",
                                                     ByteString.copyFrom(data, Charset.defaultCharset()));
    // Import heap dump from file
    assertThat(sessionsManager.importSessionFromFile(file)).isTrue();
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    assertThat(myProfilers.getStage()).isInstanceOf(MemoryCaptureStage.class);

    Common.Session session = sessionsManager.getSelectedSession();
    long dumpTime = session.getStartTimestamp();
    Transport.BytesRequest request = Transport.BytesRequest.newBuilder()
      .setStreamId(session.getStreamId())
      .setId(Long.toString(dumpTime))
      .build();
    Transport.FileResponse response = myProfilers.getClient().getTransportClient().getFile(request);
    ByteString fileContents = ByteString.copyFrom(Files.readAllBytes(new File(response.getFilePath()).toPath()));
    assertThat(fileContents).isEqualTo(ByteString.copyFrom(data, Charset.defaultCharset()));
  }
}
