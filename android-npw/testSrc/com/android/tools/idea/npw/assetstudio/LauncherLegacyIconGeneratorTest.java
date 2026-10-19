/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.npw.assetstudio;

import com.android.ide.common.util.AssetUtil;
import com.android.resources.Density;
import com.android.tools.idea.npw.NewProjectWizardTestUtils;
import com.android.tools.idea.npw.assetstudio.LauncherLegacyIconGenerator.LauncherLegacyOptions;
import com.android.tools.idea.npw.assetstudio.assets.ImageAsset;
import com.android.tools.idea.npw.assetstudio.assets.TextAsset;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.util.io.FileUtil;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.jetbrains.android.AndroidTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * Unit tests for the {@link LauncherLegacyIconGenerator} class.
 */
public class LauncherLegacyIconGeneratorTest extends AndroidTestCase {
  private void checkGraphic(@NotNull IconGeneratorTestUtil.SourceType sourceType, int paddingPercent) throws IOException {
    LauncherLegacyIconGenerator generator = new LauncherLegacyIconGenerator(getProject(), 15, null);
    disposeOnTearDown(generator);
    generator.shape().set(IconGenerator.Shape.CIRCLE);
    generator.backgroundColor().set(new Color(0xFFFF00));
    List<String> expectedFolders = ImmutableList.of("mipmap-xxxhdpi", "mipmap-xxhdpi", "mipmap-xhdpi", "mipmap-hdpi", "mipmap-mdpi");
    IconGeneratorTestUtil.checkGraphic(generator, sourceType, "android_in_circle", paddingPercent, expectedFolders,
                                       paddingPercent >= 0 ? "launcher" : "launcher_cropped");
  }

  public LauncherLegacyIconGeneratorTest() {
    super(NewProjectWizardTestUtils.getAndroidVersion());
  }

  public void testPngPadded() throws Exception {
    checkGraphic(IconGeneratorTestUtil.SourceType.PNG, 5);
  }

  public void testPngCropped() throws Exception {
    checkGraphic(IconGeneratorTestUtil.SourceType.PNG, -10);
  }

  public void testSvgPadded() throws Exception {
    checkGraphic(IconGeneratorTestUtil.SourceType.SVG, 5);
  }

  public void testSvgCropped() throws Exception {
    checkGraphic(IconGeneratorTestUtil.SourceType.SVG, -10);
  }

  public void testClipartPadded() throws Exception {
    checkGraphic(IconGeneratorTestUtil.SourceType.CLIPART, 5);
  }

  public void testClipartCropped() throws Exception {
    checkGraphic(IconGeneratorTestUtil.SourceType.CLIPART, -10);
  }

  @SuppressWarnings("UseJBColor")
  public void testNoneAndSolidBackgrounds() throws IOException {
    LauncherLegacyIconGenerator generator = new LauncherLegacyIconGenerator(getProject(), 15, null);
    disposeOnTearDown(generator);
    generator.shape().set(IconGenerator.Shape.NONE);
    generator.backgroundColor().set(Color.BLACK);

    BufferedImage noBackground = IconGeneratorTestUtil.checkRasterImage(generator);
    generator.shape().set(IconGenerator.Shape.SOLID);
    BufferedImage background = IconGeneratorTestUtil.checkRasterImage(generator);

    assertEquals(0, new Color(noBackground.getRGB(0, 0), true).getAlpha());
    assertEquals(0xFF, new Color(background.getRGB(0, 0), true).getAlpha());
  }

  @SuppressWarnings("UseJBColor")
  public void testCropAndShrinkToFitForRasterImage() throws IOException {
    // Create a wide 200x60 solid blue raster image so its aspect ratio (10:3) differs from the 1:1 square icon target.
    File widePngFile = FileUtil.createTempFile("wide_banner", ".png");
    try {
      BufferedImage wideSourceImage = AssetUtil.newArgbBufferedImage(200, 60);
      Graphics2D graphics = (Graphics2D)wideSourceImage.getGraphics();
      graphics.setColor(Color.BLUE);
      graphics.fillRect(0, 0, 200, 60);
      graphics.dispose();
      ImageIO.write(wideSourceImage, "PNG", widePngFile);

      // Configure a raster ImageAsset with no trimming and zero padding.
      ImageAsset rasterImageAsset = new ImageAsset();
      rasterImageAsset.imagePath().setValue(widePngFile);
      rasterImageAsset.trimmed().set(false);
      rasterImageAsset.paddingPercent().set(0);

      // Set up the legacy icon generator with a white square background.
      LauncherLegacyIconGenerator generator = new LauncherLegacyIconGenerator(getProject(), 15, null);
      disposeOnTearDown(generator);
      generator.sourceAsset().setValue(rasterImageAsset);
      generator.shape().set(IconGenerator.Shape.SQUARE);
      generator.backgroundColor().set(Color.WHITE);

      // Generate the icon in Shrink-to-Fit mode (cropped = false).
      generator.cropped().set(false);
      LauncherLegacyOptions shrinkToFitOptions = generator.createOptions(true);
      shrinkToFitOptions.density = Density.MEDIUM;
      BufferedImage shrinkToFitImage = generator.generateRasterImage(new GraphicGeneratorContext(0), shrinkToFitOptions).getImage();

      // Generate the icon in Center-Crop mode (cropped = true).
      generator.cropped().set(true);
      LauncherLegacyOptions croppedOptions = generator.createOptions(true);
      croppedOptions.density = Density.MEDIUM;
      BufferedImage croppedImage = generator.generateRasterImage(new GraphicGeneratorContext(0), croppedOptions).getImage();

      // In Shrink-to-Fit mode, the 200x60 wide image is letterboxed vertically inside the 38x38 square target area (x=5..42, y=5..42),
      // so the upper interior pixel at (24, 10) remains the white background color while the center (24, 24) is blue.
      assertTrue(new Color(shrinkToFitImage.getRGB(24, 10)).getRed() > 240);
      assertTrue(new Color(shrinkToFitImage.getRGB(24, 24)).getRed() < 20);
      assertTrue(new Color(shrinkToFitImage.getRGB(24, 24)).getBlue() > 240);

      // In Crop mode, the 200x60 wide image is scaled up so its height fills the entire 38x38 square target area,
      // making both the upper interior pixel (24, 10) and the center pixel (24, 24) blue.
      assertTrue(new Color(croppedImage.getRGB(24, 10)).getRed() < 20);
      assertTrue(new Color(croppedImage.getRGB(24, 10)).getBlue() > 240);
      assertTrue(new Color(croppedImage.getRGB(24, 24)).getRed() < 20);
      assertTrue(new Color(croppedImage.getRGB(24, 24)).getBlue() > 240);
    }
    finally {
      //noinspection ResultOfMethodCallIgnored
      widePngFile.delete();
    }
  }

  @SuppressWarnings("UseJBColor")
  public void testCropAndShrinkToFitForClipartDrawable() {
    // Configure a trimmed clipart vector drawable (which has non-square visual bounds inside its 24x24 viewport).
    ImageAsset clipartAsset = new ImageAsset();
    clipartAsset.setClipart(true);
    clipartAsset.imagePath().setValue(new File(getTestDataPath(), "images/vd/ic_android_black_24dp.xml"));
    clipartAsset.color().setValue(new Color(0x2196F3));
    clipartAsset.trimmed().set(true);
    clipartAsset.paddingPercent().set(0);

    // Set up the legacy icon generator with a white square background.
    LauncherLegacyIconGenerator generator = new LauncherLegacyIconGenerator(getProject(), 15, null);
    disposeOnTearDown(generator);
    generator.sourceAsset().setValue(clipartAsset);
    generator.shape().set(IconGenerator.Shape.SQUARE);
    generator.backgroundColor().set(Color.WHITE);

    // Render the clipart icon in Shrink-to-Fit mode (cropped = false).
    generator.cropped().set(false);
    LauncherLegacyOptions shrinkToFitOptions = generator.createOptions(true);
    shrinkToFitOptions.density = Density.XXXHIGH;
    BufferedImage shrinkToFitImage = generator.generateRasterImage(new GraphicGeneratorContext(0), shrinkToFitOptions).getImage();

    // Render the clipart icon in Center-Crop mode (cropped = true).
    generator.cropped().set(true);
    LauncherLegacyOptions croppedOptions = generator.createOptions(true);
    croppedOptions.density = Density.XXXHIGH;
    BufferedImage croppedImage = generator.generateRasterImage(new GraphicGeneratorContext(0), croppedOptions).getImage();

    // Verify that Center-Crop scales the non-square trimmed clipart up to cover a larger area than Shrink-to-Fit.
    int shrinkToFitForegroundPixels = countBlueForegroundPixels(shrinkToFitImage);
    int croppedForegroundPixels = countBlueForegroundPixels(croppedImage);
    assertTrue(
        "Expected cropped clipart icon (" + croppedForegroundPixels + " px) to fill significantly more of the target area than " +
        "shrink-to-fit (" + shrinkToFitForegroundPixels + " px)",
        croppedForegroundPixels > shrinkToFitForegroundPixels * 1.15);
  }

  @SuppressWarnings("UseJBColor")
  public void testCropAndShrinkToFitForTextAsset() {
    // Configure a trimmed multi-character TextAsset whose rendered vector drawable has a wide aspect ratio.
    TextAsset textAsset = new TextAsset();
    textAsset.text().set("Android");
    textAsset.color().setValue(new Color(0x0000FF));
    textAsset.trimmed().set(true);
    textAsset.paddingPercent().set(0);

    // Set up the legacy icon generator with a white square background.
    LauncherLegacyIconGenerator generator = new LauncherLegacyIconGenerator(getProject(), 15, null);
    disposeOnTearDown(generator);
    generator.sourceAsset().setValue(textAsset);
    generator.shape().set(IconGenerator.Shape.SQUARE);
    generator.backgroundColor().set(Color.WHITE);

    // Render the text icon in Shrink-to-Fit mode (cropped = false), where the full word fits horizontally.
    generator.cropped().set(false);
    LauncherLegacyOptions shrinkToFitOptions = generator.createOptions(true);
    shrinkToFitOptions.density = Density.XXXHIGH;
    BufferedImage shrinkToFitImage = generator.generateRasterImage(new GraphicGeneratorContext(0), shrinkToFitOptions).getImage();

    // Render the text icon in Center-Crop mode (cropped = true), where the text height scales up to fill the square target.
    generator.cropped().set(true);
    LauncherLegacyOptions croppedOptions = generator.createOptions(true);
    croppedOptions.density = Density.XXXHIGH;
    BufferedImage croppedImage = generator.generateRasterImage(new GraphicGeneratorContext(0), croppedOptions).getImage();

    // Verify that Center-Crop scales the wide text asset up so it covers more than twice the foreground area of Shrink-to-Fit.
    int shrinkToFitForegroundPixels = countBlueForegroundPixels(shrinkToFitImage);
    int croppedForegroundPixels = countBlueForegroundPixels(croppedImage);
    assertTrue(
        "Expected cropped text icon (" + croppedForegroundPixels + " px) to scale up and fill significantly more of the square target " +
        "area than shrink-to-fit (" + shrinkToFitForegroundPixels + " px)",
        croppedForegroundPixels > shrinkToFitForegroundPixels * 2);
  }

  private static int countBlueForegroundPixels(@NotNull BufferedImage image) {
    int blueForegroundPixelCount = 0;
    for (int pixelY = 0; pixelY < image.getHeight(); pixelY++) {
      for (int pixelX = 0; pixelX < image.getWidth(); pixelX++) {
        Color pixelColor = new Color(image.getRGB(pixelX, pixelY), true);
        if (pixelColor.getAlpha() > 128 && pixelColor.getRed() < 150 && pixelColor.getBlue() > 200) {
          blueForegroundPixelCount++;
        }
      }
    }
    return blueForegroundPixelCount;
  }
}
