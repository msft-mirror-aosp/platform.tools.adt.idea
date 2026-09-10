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
package com.android.tools.idea.play.findings.ui

import com.android.tools.idea.findings.model.FindingSeverity
import icons.StudioIcons
import javax.swing.Icon

// TODO(b/517599039): Confirm final severity icons with UX (FATAL uses a transparent cutout whereas Common icons use solid white fill).
val FindingSeverity.icon: Icon
  get() =
    when (this) {
      FindingSeverity.BLOCKING -> StudioIcons.AppQualityInsights.FATAL
      FindingSeverity.SEVERE -> StudioIcons.Common.ERROR
      FindingSeverity.WARNING -> StudioIcons.Common.WARNING
      FindingSeverity.INFO -> StudioIcons.Common.INFO
    }
