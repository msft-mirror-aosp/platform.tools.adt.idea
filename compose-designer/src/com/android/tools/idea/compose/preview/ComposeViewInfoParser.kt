/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.compose.preview

import com.android.ide.common.rendering.api.ViewInfo
import com.android.tools.rendering.compose.parseViewInfo as renderingParseViewInfo
import com.intellij.openapi.diagnostic.Logger

typealias SourceLocation = com.android.tools.rendering.compose.SourceLocation

internal typealias SourceLocationImpl = com.android.tools.rendering.compose.SourceLocationImpl

/** Delegates to `com.android.tools.rendering.compose.parseViewInfo`, forwarding diagnostics to IntelliJ [Logger]. */
fun parseViewInfo(rootViewInfo: ViewInfo, logger: Logger): List<ComposeViewInfo> =
  renderingParseViewInfo(
    rootViewInfo = rootViewInfo,
    onWarning = { message, throwable ->
      if (throwable != null) {
        logger.warn(throwable)
      } else {
        logger.warn(message)
      }
    },
    onDebug = { throwable -> logger.debug(throwable) },
  )
