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

#include "keyguard_manager.h"

#include "accessors/agent_context.h"
#include "accessors/service_manager.h"
#include "agent.h"
#include "log.h"

namespace screensharing {

using namespace std;

bool KeyguardManager::InitializeStatics(Jni jni) {
  if (Agent::feature_level() < 33) {
    return false;  // KeyguardLockedStateListener was introduced in API 33.
  }

  unique_lock lock(static_initialization_mutex_);
  if (!statics_initialized_) {
    statics_initialized_ = true;
    const JObject& context = AgentContext::context();
    if (context.IsNull()) {
      return false;
    }

    // Initialize WindowManagerGlobal.sWindowManagerService to avoid WindowManagerGlobal.getWindowManagerService()
    // accessing uninitialized ApplicationSharedMemory on API 37+.
    if (Agent::feature_level() >= 37) {
      JClass window_manager_global_class = jni.GetClass("android/view/WindowManagerGlobal");
      jfieldID window_manager_service_field =
          jni->GetStaticFieldID(window_manager_global_class.ref(), "sWindowManagerService", "Landroid/view/IWindowManager;");
      if (window_manager_service_field == nullptr) {
        jni.CheckAndClearException();
      } else if (JObject(jni, jni->GetStaticObjectField(window_manager_global_class.ref(), window_manager_service_field)).IsNull()) {
        JObject window_manager = ServiceManager::GetServiceAsInterface(jni, "window", "android/view/IWindowManager", true, true);
        if (window_manager.IsNotNull()) {
          jni->SetStaticObjectField(window_manager_global_class.ref(), window_manager_service_field, window_manager.ref());
        }
      }
    }

    JClass keyguard_manager_class = jni.GetClass("android/app/KeyguardManager");
    JClass context_class = jni.GetClass("android/content/Context");
    jmethodID get_system_service_method = context_class.GetMethod(jni, "getSystemService", "(Ljava/lang/Class;)Ljava/lang/Object;");
    JObject keyguard_manager = context.CallObjectMethod(jni, get_system_service_method, keyguard_manager_class.ref());
    if (keyguard_manager.IsNull()) {
      Log::W(jni.GetAndClearException(), "KeyguardManager is not available");
      return false;
    }

    is_keyguard_locked_method_ = keyguard_manager_class.GetMethod(jni, "isKeyguardLocked", "()Z");
    add_listener_method_ = keyguard_manager_class.GetMethod(
        jni, "addKeyguardLockedStateListener",
        "(Ljava/util/concurrent/Executor;Landroid/app/KeyguardManager$KeyguardLockedStateListener;)V");
    remove_listener_method_ = keyguard_manager_class.GetMethod(
        jni, "removeKeyguardLockedStateListener",
        "(Landroid/app/KeyguardManager$KeyguardLockedStateListener;)V");
    listener_class_ = jni.GetClass("com/android/tools/screensharing/KeyguardLockedStateListener");
    listener_constructor_ = listener_class_.GetConstructor(jni, "()V");

    keyguard_manager_ = std::move(keyguard_manager).ToGlobal();
    listener_class_.MakeGlobal();
  }

  return keyguard_manager_.IsNotNull();
}

void KeyguardManager::AddKeyguardLockedStateListener(Jni jni, KeyguardLockedStateListener* listener) {
  if (!InitializeStatics(jni)) {
    return;
  }

  unique_lock lock(listener_mutex_);
  bool locked;
  if (keyguard_listeners_.Add(listener) == 1) {
    JObject java_listener = listener_class_.NewObject(jni, listener_constructor_);
    keyguard_manager_.CallVoidMethod(jni, add_listener_method_, java_listener.ref(), java_listener.ref());
    JThrowable exception = jni.GetAndClearException();
    if (exception.IsNotNull()) {
      Log::W(std::move(exception), "Unable to register KeyguardLockedStateListener");
      keyguard_listeners_.Remove(listener);
      return;
    }

    java_listener_ = std::move(java_listener).ToGlobal();
    bool initial_locked = keyguard_manager_.CallBooleanMethod(jni, is_keyguard_locked_method_);
    exception = jni.GetAndClearException();
    bool has_initial_locked = exception.IsNull();
    if (!has_initial_locked) {
      Log::W(std::move(exception), "Unable to query KeyguardManager.isKeyguardLocked");
    }
    {
      unique_lock state_lock(state_mutex_);
      if (has_initial_locked && !has_callback_) {
        keyguard_locked_ = initial_locked;
      }
      locked = keyguard_locked_;
    }
    Log::D("KeyguardManager::AddKeyguardLockedStateListener: keyguard_locked_=%s", locked ? "true" : "false");
  } else {
    unique_lock state_lock(state_mutex_);
    locked = keyguard_locked_;
  }
  listener->OnKeyguardLockedStateChanged(locked);
}

void KeyguardManager::RemoveKeyguardLockedStateListener(KeyguardLockedStateListener* listener) {
  unique_lock lock(listener_mutex_);
  if (keyguard_listeners_.Remove(listener) == 0 && java_listener_.IsNotNull()) {
    Jni jni = Jvm::GetJni();
    keyguard_manager_.CallVoidMethod(jni, remove_listener_method_, java_listener_.ref());
    jni.CheckAndClearException();
    java_listener_.Reset();
    unique_lock state_lock(state_mutex_);
    keyguard_locked_ = false;
    has_callback_ = false;
  }
}

void KeyguardManager::OnKeyguardLockedStateChanged(bool is_keyguard_locked) {
  bool changed = false;
  {
    unique_lock state_lock(state_mutex_);
    has_callback_ = true;
    if (keyguard_locked_ != is_keyguard_locked) {
      keyguard_locked_ = is_keyguard_locked;
      changed = true;
    }
  }
  if (changed) {
    keyguard_listeners_.ForEach([is_keyguard_locked](auto listener) {
      listener->OnKeyguardLockedStateChanged(is_keyguard_locked);
    });
  }
}

mutex KeyguardManager::static_initialization_mutex_;
bool KeyguardManager::statics_initialized_ = false;
JObject KeyguardManager::keyguard_manager_;
jmethodID KeyguardManager::is_keyguard_locked_method_ = nullptr;
jmethodID KeyguardManager::add_listener_method_ = nullptr;
jmethodID KeyguardManager::remove_listener_method_ = nullptr;
JClass KeyguardManager::listener_class_;
jmethodID KeyguardManager::listener_constructor_ = nullptr;
mutex KeyguardManager::listener_mutex_;
JObject KeyguardManager::java_listener_;
ConcurrentList<KeyguardManager::KeyguardLockedStateListener> KeyguardManager::keyguard_listeners_;
mutex KeyguardManager::state_mutex_;
bool KeyguardManager::keyguard_locked_ = false;
bool KeyguardManager::has_callback_ = false;

extern "C"
JNIEXPORT void JNICALL
Java_com_android_tools_screensharing_KeyguardLockedStateListener_onKeyguardLockedStateChanged(
    JNIEnv* jni_env, jobject thiz, jboolean is_keyguard_locked) {
  Log::D("KeyguardLockedStateListener.onKeyguardLockedStateChanged(%s)", is_keyguard_locked ? "true" : "false");
  KeyguardManager::OnKeyguardLockedStateChanged(is_keyguard_locked != JNI_FALSE);
}

}  // namespace screensharing
