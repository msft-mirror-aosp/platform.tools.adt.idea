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

#pragma once

#include <mutex>

#include "common.h"
#include "concurrent_list.h"
#include "jvm.h"

namespace screensharing {

// Provides access to the android.app.KeyguardManager class.
class KeyguardManager {
public:
  struct KeyguardLockedStateListener {
    virtual void OnKeyguardLockedStateChanged(bool is_keyguard_locked) = 0;
    virtual ~KeyguardLockedStateListener() = default;
  };

  static void AddKeyguardLockedStateListener(Jni jni, KeyguardLockedStateListener* listener);
  static void RemoveKeyguardLockedStateListener(KeyguardLockedStateListener* listener);

  static void OnKeyguardLockedStateChanged(bool is_keyguard_locked);

  KeyguardManager() = delete;

private:
  // Initializes the static fields of this class. Returns true if KeyguardManager is available.
  static bool InitializeStatics(Jni jni);

  static std::mutex static_initialization_mutex_;
  static bool statics_initialized_;  // GUARDED_BY(static_initialization_mutex_)
  static JObject keyguard_manager_;  // GUARDED_BY(static_initialization_mutex_)
  static jmethodID is_keyguard_locked_method_;  // GUARDED_BY(static_initialization_mutex_)
  static jmethodID add_listener_method_;  // GUARDED_BY(static_initialization_mutex_)
  static jmethodID remove_listener_method_;  // GUARDED_BY(static_initialization_mutex_)
  static JClass listener_class_;  // GUARDED_BY(static_initialization_mutex_)
  static jmethodID listener_constructor_;  // GUARDED_BY(static_initialization_mutex_)

  static std::mutex listener_mutex_;
  static JObject java_listener_;  // GUARDED_BY(listener_mutex_)
  static ConcurrentList<KeyguardLockedStateListener> keyguard_listeners_;

  static std::mutex state_mutex_;
  static bool keyguard_locked_;  // GUARDED_BY(state_mutex_)
  static bool has_callback_;  // GUARDED_BY(state_mutex_)

  DISALLOW_COPY_AND_ASSIGN(KeyguardManager);
};

}  // namespace screensharing
