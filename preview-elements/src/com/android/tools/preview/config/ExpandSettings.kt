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
package com.android.tools.preview.config

import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_EXPAND_MAX_SIZE_PX
import com.android.tools.preview.config.Preview.DeviceSpec.PARAMETER_EXPAND

/**
 * Settings of a preview whose render area grows to fit its scrollable content, as defined by the [PARAMETER_EXPAND] parameter of a
 * DeviceSpec.
 *
 * @param axis The axis of the render area that grows.
 * @param maxSize The maximum size of the render area along [axis], in [unit]. Null when the DeviceSpec doesn't define it, in which case
 *   [DEFAULT_EXPAND_MAX_SIZE_PX] is used.
 * @param unit The unit of [maxSize], null when [maxSize] is null.
 */
data class ExpandSettings(val axis: ExpandAxis, val maxSize: Float? = null, val unit: DimUnit? = null)
