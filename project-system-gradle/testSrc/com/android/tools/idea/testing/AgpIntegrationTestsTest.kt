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
package com.android.tools.idea.testing

import com.android.tools.idea.gradle.util.AGP_BUILT_IN_KOTLIN_VERSION
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor.Companion.AGP_CURRENT
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgpIntegrationTestsTest {

  @Test
  fun getBuiltInKotlinVersionHonorsExplicitKotlinGradlePluginFlag() {
    assertThat(AGP_CURRENT.getBuiltInKotlinVersion()).isEqualTo(AGP_BUILT_IN_KOTLIN_VERSION)
    assertThat(AGP_CURRENT.withKotlin("2.5.0-Beta2").getBuiltInKotlinVersion()).isEqualTo(AGP_BUILT_IN_KOTLIN_VERSION)
    assertThat(AGP_CURRENT.withKotlin("2.2.10").withExplicitKotlinGradlePlugin(true).getBuiltInKotlinVersion()).isEqualTo("2.2.10")
    assertThat(AGP_CURRENT.withKotlin("2.5.0-Beta2").withExplicitKotlinGradlePlugin(true).getBuiltInKotlinVersion())
      .isEqualTo("2.5.0-Beta2")
    assertThat(AgpVersionSoftwareEnvironmentDescriptor.AGP_9_0.getBuiltInKotlinVersion()).isEqualTo("2.2.10")
    assertThat(AgpVersionSoftwareEnvironmentDescriptor.AGP_8_13.getBuiltInKotlinVersion()).isNull()
  }
}
