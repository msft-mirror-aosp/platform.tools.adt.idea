/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intellij.openapi.updateSettings.impl.BuildInfo;
import com.intellij.openapi.updateSettings.impl.ChannelStatus;
import com.intellij.openapi.updateSettings.impl.PlatformUpdates;
import com.intellij.openapi.updateSettings.impl.UpdateData;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.openapi.updateSettings.impl.UpdateStrategy;
import com.intellij.openapi.util.BuildNumber;
import java.util.stream.Collectors;
import org.intellij.lang.annotations.Language;
import org.junit.Test;

/** Test of {@link UpdateStrategy} with {@link AndroidStudioUpdateStrategyCustomization}. */
public final class AndroidStudioUpdateStrategyTest {

  @Test
  public void testUpdateStrategyDoesNotPreferSamePlatformVersion() throws Exception {
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-2-eap' status='eap'>" +
      "      <build number='AI-183.2153.8.34.5078398' version='3.4 Canary 2'/>" +
      "    </channel>" +
      "    <channel id='AI-2-beta' status='beta'>" +
      "      <build number='AI-182.4892.20.33.5078385' version='3.3 Beta 2'/>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    assertThat(createBuild("AI-182.4505.22.34.5070326", updatesXml, settings).getNumber().asString())
      .isEqualTo("AI-183.2153.8.34.5078398");  // not AI-182.4892.20.33.5078385. This scenario is taken directly from b/117996392.
  }

  @Test
  public void testUpdateStrategyIgnoresLowerAndroidStudioVersion() throws Exception {
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-2-beta' status='beta'>" +
      "      <build number='AI-191.8026.42.35.5781497' version='3.5 RC 3'/>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    assertThat(createBuild("AI-191.7479.19.36.5721125", updatesXml, settings))
      .isNull();  // not AI-191.8026.42.35.5781497. This scenario is taken directly from b/139118534.
  }

  // It's not clear this is important, but it's what overriding haveSameMajorVersion still does now that we also override isNewerVersion.
  @Test
  public void testUpdateStrategyPrefersSameAndroidStudioVersionIfAvailable() throws Exception {
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-2-eap' status='eap'>" +
      "      <build number='AI-191.7479.19.36.5721125' version='3.6 Canary 5'/>" +
      "    </channel>" +
      "    <channel id='AI-2-beta' status='beta'>" +
      "      <build number='AI-191.7479.19.35.5763348' version='3.5 RC 2'/>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    assertThat(createBuild("AI-191.7479.19.35.5717577", updatesXml, settings).getNumber().asString())
      .isEqualTo("AI-191.7479.19.36.5721125");  // not AI-191.7479.19.35.5763348
  }

  @Test
  public void testUpdateStrategyDoesNotChooseMilestone() throws Exception {
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-2-eap' status='eap'>" +
      "      <build number='AI-100.200.30.40.5000000' version='Canary 1'/>" +
      "    </channel>" +
      "    <channel id='AI-2-milestone' status='milestone'>" +
      "      <build number='AI-100.200.30.40.5000001' version='Nightly 20230412'/>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    assertThat(createBuild("AI-100.200.30.40.4900000", updatesXml, settings).getNumber().asString())
      .isEqualTo("AI-100.200.30.40.5000000");  // not AI-100.200.30.40.5000001 (Nightly)
  }

  @Test
  public void testUpdateStrategyPrefersNewestBuildInChannel() throws Exception {
    // JetBrains prefers updates with the same "major" version, whereas we always prefer updating
    // to the latest version in a given channel. See AndroidStudioUpdateStrategyCustomization.haveSameMajorVersion().
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-2-eap' status='eap'>" +
      "      <build number='AI-191.7480.19.35.5721732' version='3.6 Canary 5'/>" +
      "      <build number='AI-191.7480.19.35.5821739' version='3.6 Canary 6'/>" +
      "      <build number='AI-192.7488.20.38.5821125' version='3.6 Canary 7'/>" +
      "    </channel>" +
      "    <channel id='AI-2-beta' status='beta'>" +
      "      <build number='AI-191.7479.19.35.5763348' version='3.5 RC 2'/>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    assertThat(createBuild("AI-191.7479.19.35.5717577", updatesXml, settings).getNumber().asString())
      .isEqualTo("AI-192.7488.20.38.5821125");
  }

  @Test
  public void testUpdateStrategyCrossChannelUpdatesFromSameMajorVersionNotLegal() throws Exception {
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-2-beta' status='beta'>" +
      "      <build number='AI-191.7479.19.35.5763348' version='3.5 RC 2'/>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    assertThat(createBuild("AI-191.7479.19.35.5717577", updatesXml, settings)).isNull();
  }

  /**
   * Regression test for b/564619952: a user on Rabbit 1 Canary 4 is offered Rabbit 1 Canary 5 instead of
   * Rabbit 2 Canary 1, even though a patch chain is available (R1C4 -> R1C5 -> R2C1).
   */
  @Test
  public void testUpdateChainingAcrossFeatureDrops() throws Exception {
    // A trimmed copy of https://dl.google.com/android/studio/patches/updates.xml from September 2026.
    @Language("XML") String updatesXml =
      "<products>" +
      "  <product name='Android Studio'>" +
      "    <code>AI</code>" +
      "    <channel id='AI-1-eap' status='eap'>" +
      "      <build apiVersion='AI-262.10315.125' number='AI-262.10315.125.2622.16370930' version='Rabbit 2 | 2026.2.2 Canary 1'>" +
      "        <patch from='262.9437.185.2621.16309011' size='90'/>" +
      "      </build>" +
      "      <build apiVersion='AI-262.9437.185' number='AI-262.9437.185.2621.16309011' version='Rabbit 1 | 2026.2.1 Canary 5'>" +
      "        <patch from='262.9437.185.2621.16253642' size='68'/>" +
      "        <patch from='262.9437.185.2621.16190720' size='89'/>" +
      "      </build>" +
      "      <build apiVersion='AI-262.9437.185' number='AI-262.9437.185.2621.16253642' version='Rabbit 1 | 2026.2.1 Canary 4'>" +
      "        <patch from='262.9437.185.2621.16190720' size='76'/>" +
      "      </build>" +
      "    </channel>" +
      "  </product>" +
      "</products>";

    UpdateSettings settings = mock(UpdateSettings.class);
    when(settings.getSelectedChannelStatus()).thenReturn(ChannelStatus.EAP);

    PlatformUpdates.Loaded result = checkForUpdates("AI-262.9437.185.2621.16253642", updatesXml, settings);
    assertThat(result).isNotNull();
    assertThat(result.getNewBuild().getNumber().asString()).isEqualTo("AI-262.10315.125.2622.16370930");
    assertThat(result.getPatches()).isNotNull();
    assertThat(result.getPatches().getChain().stream().map(BuildNumber::asStringWithoutProductCode).collect(Collectors.toList()))
      .containsExactly("262.9437.185.2621.16253642", "262.9437.185.2621.16309011", "262.10315.125.2622.16370930")
      .inOrder();
  }

  private static BuildInfo createBuild(String version, String updatesXml, UpdateSettings settings) throws Exception {
    PlatformUpdates.Loaded result = checkForUpdates(version, updatesXml, settings);
    return result != null ? result.getNewBuild() : null;
  }

  private static PlatformUpdates.Loaded checkForUpdates(String version, String updatesXml, UpdateSettings settings) throws Exception {
    PlatformUpdates result = new UpdateStrategy(
      BuildNumber.fromString(version),
      UpdateData.parseUpdateData(updatesXml, "AI"),
      settings,
      new AndroidStudioUpdateStrategyCustomization())
      .checkForUpdates();
    return (result instanceof PlatformUpdates.Loaded) ? (PlatformUpdates.Loaded)result : null;
  }
}
