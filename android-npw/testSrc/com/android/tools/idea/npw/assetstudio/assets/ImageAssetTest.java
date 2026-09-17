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
package com.android.tools.idea.npw.assetstudio.assets;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static com.google.common.truth.Truth.assertThat;

import com.android.tools.adtui.validation.Validator;
import com.android.tools.idea.npw.assetstudio.wizard.PersistentState;
import com.android.tools.idea.observable.BatchInvoker;
import com.android.tools.idea.observable.TestInvokeStrategy;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.RunsInEdt;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.RandomAccessFile;
import javax.imageio.ImageIO;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@RunsInEdt
public class ImageAssetTest {
  @Rule
  public final EdtAndroidProjectRule myProjectRule = onEdt(AndroidProjectRule.inMemory());

  @Rule
  public final TemporaryFolder myTemporaryFolder = new TemporaryFolder();

  private final TestInvokeStrategy myInvokeStrategy = new TestInvokeStrategy();

  @Before
  public void setUp() {
    BatchInvoker.setOverrideStrategy(myInvokeStrategy);
  }

  @After
  public void tearDown() {
    BatchInvoker.clearOverrideStrategy();
  }

  @Test
  public void validSmallImageLoadsSuccessfully() throws Exception {
    File imageFile = myTemporaryFolder.newFile("valid.png");
    BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    ImageIO.write(img, "png", imageFile);

    ImageAsset asset = new ImageAsset();
    asset.setRole("monochrome image");
    asset.imagePath().setValue(imageFile);

    ListenableFuture<BufferedImage> future = asset.toImage();
    assertThat(future).isNotNull();
    BufferedImage loaded = PlatformTestUtil.waitForFuture(future);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getWidth()).isEqualTo(64);
    assertThat(loaded.getHeight()).isEqualTo(64);

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    myInvokeStrategy.updateAllSteps();
    assertThat(asset.getValidityState().get().getSeverity()).isEqualTo(Validator.Severity.OK);
    assertThat(asset.isResizable().get()).isTrue();
  }

  @Test
  public void largeFileSizeExceedingLimitFailsValidationAndAbortsProcessing() throws Exception {
    File largeFile = myTemporaryFolder.newFile("large_image.png");
    // Create a sparse file exceeding 10MB
    try (RandomAccessFile raf = new RandomAccessFile(largeFile, "rw")) {
      raf.setLength(ImageAsset.MAX_FILE_SIZE_BYTES + 1024);
    }

    ImageAsset asset = new ImageAsset();
    asset.setRole("monochrome image");
    asset.imagePath().setValue(largeFile);

    // Processing should be aborted immediately (toImage returns null)
    ListenableFuture<BufferedImage> future = asset.toImage();
    assertThat(future).isNull();

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    myInvokeStrategy.updateAllSteps();
    Validator.Result validityState = asset.getValidityState().get();
    assertThat(validityState.getSeverity()).isEqualTo(Validator.Severity.ERROR);
    assertThat(validityState.getMessage()).contains("monochrome image");
    assertThat(validityState.getMessage()).contains("too large");
    assertThat(validityState.getMessage()).contains("10 MB");
    assertThat(asset.isResizable().get()).isFalse();
  }

  @Test
  public void largeImageDimensionsExceedingLimitFailsValidation() throws Exception {
    File hugeDimensionFile = myTemporaryFolder.newFile("huge_dimension.png");
    BufferedImage img = new BufferedImage(ImageAsset.MAX_IMAGE_DIMENSION + 100, 10, BufferedImage.TYPE_INT_ARGB);
    ImageIO.write(img, "png", hugeDimensionFile);

    ImageAsset asset = new ImageAsset();
    asset.setRole("monochrome image");
    asset.imagePath().setValue(hugeDimensionFile);

    ListenableFuture<BufferedImage> future = asset.toImage();
    assertThat(future).isNotNull();
    BufferedImage loaded = PlatformTestUtil.waitForFuture(future);
    assertThat(loaded).isNull();

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    myInvokeStrategy.updateAllSteps();
    Validator.Result validityState = asset.getValidityState().get();
    assertThat(validityState.getSeverity()).isEqualTo(Validator.Severity.ERROR);
    assertThat(validityState.getMessage()).contains("resolution");
    assertThat(validityState.getMessage()).contains("too large");
  }

  @Test
  public void nonExistentFileFailsValidation() throws Exception {
    File nonExistent = new File(myTemporaryFolder.getRoot(), "does_not_exist.png");
    ImageAsset asset = new ImageAsset();
    asset.setRole("monochrome image");
    asset.imagePath().setValue(nonExistent);

    ListenableFuture<BufferedImage> future = asset.toImage();
    assertThat(future).isNull();

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    myInvokeStrategy.updateAllSteps();
    Validator.Result validityState = asset.getValidityState().get();
    assertThat(validityState.getSeverity()).isEqualTo(Validator.Severity.ERROR);
    assertThat(validityState.getMessage()).contains("does not exist");
  }

  @Test
  public void directorySelectedShowsWarning() throws Exception {
    File dir = myTemporaryFolder.newFolder("some_folder");
    ImageAsset asset = new ImageAsset();
    asset.setRole("monochrome image");
    asset.imagePath().setValue(dir);

    ListenableFuture<BufferedImage> future = asset.toImage();
    assertThat(future).isNull();

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    myInvokeStrategy.updateAllSteps();
    Validator.Result validityState = asset.getValidityState().get();
    assertThat(validityState.getSeverity()).isEqualTo(Validator.Severity.WARNING);
    assertThat(validityState.getMessage()).contains("Please select a monochrome image file");
  }

  @Test
  public void loadStateRejectsUncPathAndFallsBackToDefault() throws Exception {
    File defaultFile = myTemporaryFolder.newFile("default.png");
    ImageAsset asset = new ImageAsset();
    asset.setDefaultImagePath(defaultFile.toPath());

    PersistentState state = new PersistentState();
    state.set("imagePath", "\\\\attacker.com\\share\\image.png");
    asset.loadState(state);

    assertThat(asset.imagePath().getValueOrNull()).isEqualTo(defaultFile);
  }

  @Test
  public void changingImagePathAfterValidationErrorResetsValidityStateAndResizability() throws Exception {
    // First, set a non-existent file and trigger validation so that validityState becomes ERROR
    // and isResizable() evaluates to false.
    File nonExistentFile = new File(myTemporaryFolder.getRoot(), "missing_initial_asset.xml");
    ImageAsset asset = new ImageAsset();
    asset.setRole("foreground image");
    asset.imagePath().setValue(nonExistentFile);

    ListenableFuture<String> xmlFuture = asset.getXmlDrawable();
    assertThat(xmlFuture).isNull();

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    myInvokeStrategy.updateAllSteps();
    assertThat(asset.getValidityState().get().getSeverity()).isEqualTo(Validator.Severity.ERROR);
    assertThat(asset.isResizable().get()).isFalse();

    // Next, update imagePath to a raster image candidate. Even before toImage() is invoked,
    // changing the path must clear the stale ERROR state so that UI bindings (such as resize
    // sliders in ConfigureAdaptiveIconPanel) immediately recognize the new candidate as resizable.
    File newRasterCandidate = new File(myTemporaryFolder.getRoot(), "new_icon.png");
    asset.imagePath().setValue(newRasterCandidate);
    myInvokeStrategy.updateAllSteps();

    assertThat(asset.getValidityState().get().getSeverity()).isEqualTo(Validator.Severity.OK);
    assertThat(asset.isResizable().get()).isTrue();
  }
}
