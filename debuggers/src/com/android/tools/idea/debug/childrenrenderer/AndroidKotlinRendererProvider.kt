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
package com.android.tools.idea.debug.childrenrenderer

import org.jetbrains.annotations.VisibleForTesting

/**
 * A class renderer for Android Kotlin classes
 *
 * Sorts object properties in declaration order.
 *
 * DEX'ing a class stores fiends and methods in alphabetic order which determines the way they are displayed in the debugger.
 */
class AndroidKotlinRendererProvider : BaseRendererProvider(AndroidKotlinClassRenderer()) {
  @VisibleForTesting public override fun getName() = "Android Kotlin Class"

  @VisibleForTesting public override fun getClassName() = "kotlin.Any"
}
