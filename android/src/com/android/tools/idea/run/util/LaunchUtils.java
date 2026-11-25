/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.run.util;

import static com.android.SdkConstants.VALUE_FALSE;
import static com.android.SdkConstants.VALUE_TRUE;
import static com.android.tools.idea.projectsystem.ProjectSystemUtil.getModuleSystem;

import com.android.annotations.concurrency.Slow;
import com.android.annotations.concurrency.WorkerThread;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.NullOutputReceiver;
import com.android.tools.idea.model.AndroidManifestIndex;
import com.android.tools.idea.projectsystem.SourceProviders;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.dom.manifest.UsesFeature;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;

public class LaunchUtils {
  /**
   * Returns whether the given application can be debugged on the given device.
   */
  public static boolean canDebugAppOnDevice(@NotNull AndroidFacet facet, @NotNull IDevice device) {
    return (canDebugApp(facet) || isDebuggableDevice(device));
  }

  public static boolean canDebugApp(@NotNull AndroidFacet facet) {
    return getModuleSystem(facet).isDebuggable();
  }

  public static boolean isDebuggableDevice(@NotNull IDevice device) {
    String buildType = device.getProperty(IDevice.PROP_BUILD_TYPE);
    return ("userdebug".equals(buildType) || "eng".equals(buildType));
  }

  /**
   * Returns whether the watch hardware feature is required for the given facet.
   */
  @Slow
  @WorkerThread
  public static boolean isWatchFeatureRequired(@NotNull AndroidFacet facet) {
    Application application = ApplicationManager.getApplication();
    if (!ExternalSystemUtil.isNoBackgroundMode()) {
      // Assert only when not in the headless mode (fails in headless).
      application.assertIsNonDispatchThread();
    }

    if (facet.isDisposed() || facet.getModule().isDisposed()) {
      return false;
    }
    Project project = facet.getModule().getProject();
    if (project.isDisposed()) {
      return false;
    }
    Computable<Boolean> computation = () -> {
      if (facet.isDisposed() || facet.getModule().isDisposed() || project.isDisposed()) {
        return false;
      }
      if (AndroidFacet.getInstance(facet.getModule()) == null) {
        Logger.getInstance(LaunchUtils.class).warn("calling isWatchFeatureRequired when facet is not ready yet");
        return false;
      }
      if (DumbService.isDumb(project)) {
        // indexing in progress, check all current manifests (including flavor and build type ones like debug).
        return SourceProviders.getInstance(facet).getCurrentSourceProviders().stream()
          .flatMap(provider -> {
            ProgressManager.checkCanceled();
            return StreamSupport.stream(provider.getManifestFiles().spliterator(), false);
          })
          .map(file -> AndroidUtils.loadDomElement(facet.getModule(), file, Manifest.class))
          .filter(Objects::nonNull)
          .anyMatch(manifest ->
                      manifest.getUsesFeatures().stream().anyMatch(feature ->
                                                                     UsesFeature.HARDWARE_TYPE_WATCH.equals(feature.getName().getValue()) &&
                                                                     isRequired(feature))
          );
      }
      // indexing completed, can check merged manifest
      return AndroidManifestIndex.getDataForMergedManifestContributors(facet)
        .flatMap((it) -> it.getUsedFeatures().stream())
        .filter((it) -> UsesFeature.HARDWARE_TYPE_WATCH.equals(it.getName()))
        .anyMatch((it) -> !VALUE_FALSE.equals(it.getRequired()));
    };

    if (application.isReadAccessAllowed()) {
      return Boolean.TRUE.equals(computation.compute());
    }
    if (application.isDispatchThread()) {
      return Boolean.TRUE.equals(application.runReadAction(computation));
    }
    return Boolean.TRUE.equals(ReadAction.nonBlocking(computation::compute).executeSynchronously());
  }

  /**
   * Checks if uses-feature is required (either no `required` attribute used, or set to true).
   */
  private static boolean isRequired(@NotNull UsesFeature feature) {
    return !feature.getRequired().exists() || VALUE_TRUE.equals(feature.getRequired().getStringValue());
  }


  public static void initiateDismissKeyguard(@NotNull final IDevice device) {
    // From Version 23 onwards (in the emulator, possibly later on devices), we can dismiss the keyguard
    // with "adb shell wm dismiss-keyguard". This allows the application to show up without the user having
    // to manually dismiss the keyguard.
    if (device.getVersion().isAtLeast(23)) {
      // It is not necessary to wait for the keyguard to be dismissed. On a slow emulator, this seems
      // to take a while (6s on my machine)
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        try {
          device.executeShellCommand("wm dismiss-keyguard", new NullOutputReceiver(), 10, TimeUnit.SECONDS);
        }
        catch (Exception e) {
          Logger.getInstance(LaunchUtils.class).warn("Unable to dismiss keyguard before launching activity");
        }
      });
    }
  }
}
