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
package com.android.tools.profilers.memory.adapters.classifiers;

import com.android.tools.adtui.model.filter.Filter;
import com.android.tools.profilers.memory.ClassGrouping;
import com.android.tools.profilers.memory.adapters.CaptureObject;
import com.android.tools.profilers.memory.adapters.HeapDumpCaptureObject;
import com.android.tools.profilers.memory.adapters.InstanceObject;
import org.jetbrains.annotations.NotNull;

/**
 * Classifies {@link InstanceObject}s based on their allocation's heap ID.
 */
public class HeapSet extends ClassifierSet {
  @NotNull private final CaptureObject myCaptureObject;
  @NotNull protected ClassGrouping myClassGrouping = ClassGrouping.ARRANGE_BY_CLASS;
  private final int myId;
  @NotNull protected volatile Filter myFilter;
  private volatile long myHeapRetainedNativeSize = -1L;
  private volatile long myHeapRetainedSize = -1L;

  public HeapSet(@NotNull CaptureObject captureObject, @NotNull String heapName, int id) {
    super(heapName);
    myCaptureObject = captureObject;
    myId = id;
    myFilter = Filter.EMPTY_FILTER;
    setClassGrouping(ClassGrouping.ARRANGE_BY_CLASS);
  }

  /**
   * Sets precomputed heap-level retained sizes (used by Trace Processor captures to avoid double-counting across classes).
   */
  public void setHeapRetainedSizes(long retainedNativeSize, long retainedSize) {
    myHeapRetainedNativeSize = retainedNativeSize;
    myHeapRetainedSize = retainedSize;
  }

  public long getHeapRetainedNativeSize() {
    return myHeapRetainedNativeSize;
  }

  public long getHeapRetainedSize() {
    return myHeapRetainedSize;
  }

  private boolean isTraceProcessorCapture() {
    return myCaptureObject instanceof HeapDumpCaptureObject && ((HeapDumpCaptureObject)myCaptureObject).isTraceProcessor();
  }

  /**
   * Returns true if this capture uses Trace Processor and has an active text, class, or issue filter.
   */
  public boolean isFilteredInTraceProcessor() {
    if (!isTraceProcessorCapture()) {
      return false;
    }
    return !myFilter.isEmpty() || ((HeapDumpCaptureObject)myCaptureObject).getHasActiveFilter();
  }

  @Override
  public long getTotalRetainedSize() {
    if (isTraceProcessorCapture()) {
      if (isFilteredInTraceProcessor()) {
        return NOT_APPLICABLE_SIZE;
      }
      if (myHeapRetainedSize >= 0L) {
        return myHeapRetainedSize;
      }
    }
    return super.getTotalRetainedSize();
  }

  @Override
  public long getTotalRetainedNativeSize() {
    if (isTraceProcessorCapture()) {
      if (isFilteredInTraceProcessor()) {
        return NOT_APPLICABLE_SIZE;
      }
      if (myHeapRetainedNativeSize >= 0L) {
        return myHeapRetainedNativeSize;
      }
    }
    return super.getTotalRetainedNativeSize();
  }

  @Override
  public boolean isRetainedSizeCached() {
    if (isTraceProcessorCapture() && (isFilteredInTraceProcessor() || myHeapRetainedSize >= 0L)) {
      return true;
    }
    return super.isRetainedSizeCached();
  }

  @Override
  public boolean isRetainedNativeSizeCached() {
    if (isTraceProcessorCapture() && (isFilteredInTraceProcessor() || myHeapRetainedNativeSize >= 0L)) {
      return true;
    }
    return super.isRetainedNativeSizeCached();
  }

  @Override
  public long getRetainedSizeCache() {
    if (isTraceProcessorCapture()) {
      if (isFilteredInTraceProcessor()) {
        return NOT_APPLICABLE_SIZE;
      }
      if (myHeapRetainedSize >= 0L) {
        return myHeapRetainedSize;
      }
    }
    return super.getRetainedSizeCache();
  }

  @Override
  public long getRetainedNativeSizeCache() {
    if (isTraceProcessorCapture()) {
      if (isFilteredInTraceProcessor()) {
        return NOT_APPLICABLE_SIZE;
      }
      if (myHeapRetainedNativeSize >= 0L) {
        return myHeapRetainedNativeSize;
      }
    }
    return super.getRetainedNativeSizeCache();
  }

  public ClassGrouping getClassGrouping() {
    return myClassGrouping;
  }

  public void setClassGrouping(@NotNull ClassGrouping classGrouping) {
    if (myClassGrouping == classGrouping) {
      return;
    }
    myClassGrouping = classGrouping;
    coalesce();
    needsRefiltering = true;
  }

  public int getId() {
    return myId;
  }

  // Select and apply a filter.
  // When there are content changes in HeapSet, we need to re-select the same filter.
  public void selectFilter(@NotNull Filter filter) {
    // If both the old and new filters are empty, no alloc/dealloc events will be filtered out and we do not need to do anything
    // even when HeapSet has content changes.
    // However, if needsRefiltering is true and an issue filter or class filter is active on the capture object, we must re-apply the filter
    // so that child class sets update their filtered match state.
    if (myFilter.isEmpty() && filter.isEmpty() &&
        (!needsRefiltering || (myCaptureObject != null && myCaptureObject.getSelectedInstanceFilters().isEmpty()))) {
      return;
    }

    boolean filterChanged = !myFilter.equals(filter);
    myFilter = filter;
    applyFilter(filterChanged);
  }

  @NotNull
  public Filter getFilter() {
    return myFilter;
  }

  // Filter child ClassSets based on current selected filter string
  // If filterChanged is false, we only update modified classifierSets
  private void applyFilter(boolean filterChanged) {
    applyFilter(myFilter, filterChanged);
  }

  @NotNull
  @Override
  public Classifier createSubClassifier() {
    switch (myClassGrouping) {
      case ARRANGE_BY_CLASS:
        return ClassSet.createDefaultClassifier(myCaptureObject, getId());
      case ARRANGE_BY_PACKAGE:
        return PackageSet.createDefaultClassifier(myCaptureObject, getId());
      case ARRANGE_BY_CALLSTACK:
        return ThreadSet.createDefaultClassifier(myCaptureObject);
      default:
        throw new RuntimeException("Classifier type not implemented: " + myClassGrouping);
    }
  }
}
