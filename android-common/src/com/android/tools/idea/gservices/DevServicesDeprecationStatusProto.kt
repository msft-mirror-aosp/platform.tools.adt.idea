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
package com.android.tools.idea.gservices

import com.android.tools.idea.serverflags.protos.DevServicesDeprecationMetadata

fun DevServicesDeprecationStatus.Companion.fromProto(status: DevServicesDeprecationMetadata.Status) =
  when (status) {
    // Unknown implies the flag is unavailable. To be on the safe side, we let the service stay
    // SUPPORTED.
    DevServicesDeprecationMetadata.Status.STATUS_UNKNOWN,
    DevServicesDeprecationMetadata.Status.SUPPORTED -> DevServicesDeprecationStatus.SUPPORTED
    DevServicesDeprecationMetadata.Status.DEPRECATED -> DevServicesDeprecationStatus.DEPRECATED
    DevServicesDeprecationMetadata.Status.UNSUPPORTED -> DevServicesDeprecationStatus.UNSUPPORTED
  }
