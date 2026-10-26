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
package com.android.tools.idea.streaming.emulator.dialogs

/** 3-component vector used for 3D coordinates, Euler angles, and sensor values. */
internal data class Vec3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

  operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

  companion object {
    val ZERO = Vec3(0f, 0f, 0f)
  }
}
