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

import com.android.tools.profilers.memory.adapters.CaptureObject;
import com.android.tools.profilers.memory.adapters.HeapDumpCaptureObject;
import com.android.tools.profilers.memory.adapters.InstanceObject;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;

/**
 * Classifies {@link InstanceObject}s based on its package name. Primitive arrays are classified as leaf nodes directly under the root.
 */
public class PackageSet extends ClassifierSet {
  @NotNull private final CaptureObject myCaptureObject;
  private final int myPackageNameIndex;
  private final int myHeapId;

  @NotNull
  public static Classifier createDefaultClassifier(@NotNull CaptureObject captureObject, int heapId) {
    return packageClassifier(captureObject, 0, heapId);
  }

  @NotNull
  public static Classifier createDefaultClassifier(@NotNull CaptureObject captureObject) {
    return createDefaultClassifier(captureObject, 0);
  }

  public PackageSet(@NotNull CaptureObject captureObject, @NotNull String packageElementName, int packageNameIndex, int heapId) {
    super(packageElementName);
    myCaptureObject = captureObject;
    myPackageNameIndex = packageNameIndex;
    myHeapId = heapId;
  }

  /**
   * In Trace Processor captures, class-level retained sizes cannot be summed across classes in a package without overcounting.
   */
  private boolean isTraceProcessorCapture() {
    return myCaptureObject instanceof HeapDumpCaptureObject && ((HeapDumpCaptureObject)myCaptureObject).isTraceProcessor();
  }

  @Override
  public long getTotalRetainedSize() {
    return isTraceProcessorCapture() ? NOT_APPLICABLE_SIZE : super.getTotalRetainedSize();
  }

  @Override
  public long getTotalRetainedNativeSize() {
    return isTraceProcessorCapture() ? NOT_APPLICABLE_SIZE : super.getTotalRetainedNativeSize();
  }

  @Override
  public boolean isRetainedSizeCached() {
    return isTraceProcessorCapture() || super.isRetainedSizeCached();
  }

  @Override
  public boolean isRetainedNativeSizeCached() {
    return isTraceProcessorCapture() || super.isRetainedNativeSizeCached();
  }

  @Override
  public long getRetainedSizeCache() {
    return isTraceProcessorCapture() ? NOT_APPLICABLE_SIZE : super.getRetainedSizeCache();
  }

  @Override
  public long getRetainedNativeSizeCache() {
    return isTraceProcessorCapture() ? NOT_APPLICABLE_SIZE : super.getRetainedNativeSizeCache();
  }

  @NotNull
  @Override
  public Classifier createSubClassifier() {
    return packageClassifier(myCaptureObject, myPackageNameIndex + 1, myHeapId);
  }

  private static Classifier packageClassifier(CaptureObject captureObject, int packageNameIndex, int heapId) {
    return new Classifier.Join<>(packageElementAt(packageNameIndex), elem -> new PackageSet(captureObject, elem, packageNameIndex, heapId),
                                 ClassSet.createDefaultClassifier(captureObject, heapId));
  }

  private static Function1<InstanceObject, String> packageElementAt(int packageNameIndex) {
    return inst -> packageNameIndex < inst.getClassEntry().getSplitPackageName().length
                   ? inst.getClassEntry().getSplitPackageName()[packageNameIndex]
                   : null;
  }
}
