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
package com.android.tools.screensharing;

import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.os.Build;
import java.util.concurrent.Executor;

/**
 * Implementation of the {@link KeyguardManager.KeyguardLockedStateListener} interface delegating
 * to the native code in keyguard_manager.cc.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
@SuppressWarnings("unused") // Called through JNI.
public class KeyguardLockedStateListener implements KeyguardManager.KeyguardLockedStateListener, Executor {
  @Override
  public native void onKeyguardLockedStateChanged(boolean isKeyguardLocked);

  @Override
  public void execute(Runnable command) {
    command.run();
  }
}
