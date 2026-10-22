/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.profilers.cpu.systemtrace;

import com.android.tools.adtui.model.Range;
import com.android.tools.idea.flags.enums.PowerProfilerDisplayMode;
import com.android.tools.profiler.proto.Trace;
import com.android.tools.profilers.cpu.CpuCapture;
import com.android.tools.profilers.cpu.MainProcessSelector;
import com.android.tools.profilers.cpu.TraceParser;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import trebuchet.model.Model;
import trebuchet.task.ImportTask;
import trebuchet.util.PrintlnImportFeedback;

/**
 * AtraceParser is a minimal implementation parsing the atrace file.
 * The class looks for the first and last lines in the file to get the total time, as well as
 * populates a minimal data structure to pass to the UI.
 * Trebuchet is our parser for atrace (systrace) raw data.
 */
public class AtraceParser implements TraceParser {

  @NotNull
  private final MainProcessSelector processSelector;

  /**
   * For testing purposes, when we don't care about which process in going to be selected as the main one.
   */
  @VisibleForTesting
  public AtraceParser() {
    this(new MainProcessSelector("", 0, null));
  }

  /**
   * This constructor assumes we don't know which process we want to focus on and will use the passed {@code processSelector} to find it.
   */
  public AtraceParser(@NotNull MainProcessSelector processSelector) {
    this.processSelector = processSelector;
  }

  @Override
  public CpuCapture parse(@NotNull File file, long traceId) throws IOException {
    SystemTraceModelAdapter model = parseToModel(file);

    if (model.getProcesses().isEmpty()) {
      throw new IllegalStateException("Invalid trace without any process information.");
    }

    ProcessListSorter sorter = new ProcessListSorter(processSelector.getNameHint());
    Integer selectedProcess = processSelector.apply(sorter.sort(model.getProcesses()));
    if (selectedProcess == null) {
      throw new IllegalStateException("It was not possible to select a process for this trace.");
    }

    SystemTraceCpuCaptureBuilder builder = new SystemTraceCpuCaptureBuilder(model);
    return builder.build(traceId, selectedProcess, new Range(model.getCaptureStartTimestampUs(), model.getCaptureEndTimestampUs()));
  }

  /**
   * Parses the input file and caches off the model to prevent parsing multiple times.
   */
  private SystemTraceModelAdapter parseToModel(@NotNull File file) throws IOException {
    TrebuchetBufferProducer producer = new AtraceProducer();
    if (!producer.parseFile(file)) {
      throw new IOException("Failed to parse file: " + file.getAbsolutePath());
    }

    ImportTask task = new ImportTask(new PrintlnImportFeedback());
    Model trebuchetModel = task.importBuffer(producer);
    return new TrebuchetModelAdapter(trebuchetModel);
  }
}