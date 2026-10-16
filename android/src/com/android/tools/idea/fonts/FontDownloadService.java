/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.fonts;

import com.android.ide.common.fonts.FontDetail;
import com.android.ide.common.fonts.FontFamily;
import com.android.tools.fonts.DownloadableFontCacheServiceImpl;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.MessageType;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.download.DownloadableFileDescription;
import com.intellij.util.download.DownloadableFileService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link FontDownloadService} is a service for downloading individual font (*.ttf) files.
 * For each request a list of {@link FontFamily} are specified and if we only want to
 * download the menu file (i.e. a *.ttf file for displaying the name of the font),
 * or if we want all the fonts in the family {@link FontDetail} to be downloaded as well.
 * The menu font file is usually a smaller font file (~4k).
 */
public class FontDownloadService {
  private static final String PROBLEM_NOTIFICATION_GROUP_ID = "Font Downloading Problems";
  private static final AtomicBoolean ourSanitizerUnavailableNotified = new AtomicBoolean(false);

  private final DownloadableFontCacheServiceImpl myCacheService;
  private final File myFontPath;
  private final List<FontFamily> myFontsToDownload;
  private final boolean myDownloadMenuFontsOnly;
  private final Runnable mySuccess;
  private final Runnable myFailure;

  public static void download(@NotNull List<FontFamily> fontsToDownload,
                              boolean menuFontsOnly,
                              @Nullable Runnable success,
                              @Nullable Runnable failure) {
    FontDownloadService service = new FontDownloadService(fontsToDownload, menuFontsOnly, success, failure);
    service.download();
  }

  private FontDownloadService(@NotNull List<FontFamily> fontsToDownload,
                              boolean menuFontsOnly,
                              @Nullable Runnable success,
                              @Nullable Runnable failure) {
    myCacheService = StudioDownloadableFontCacheService.getInstance();
    myFontPath = myCacheService.getFontPath();
    myFontsToDownload = fontsToDownload;
    myDownloadMenuFontsOnly = menuFontsOnly;
    mySuccess = success;
    myFailure = failure;
  }

  public void download() {
    ApplicationManager.getApplication().executeOnPooledThread(this::performDownload);
  }

  private void performDownload() {
    List<DownloadableFileDescription> files = new ArrayList<>();
    for (FontFamily fontFamily : myFontsToDownload) {
      addFontFamily(files, fontFamily);
    }
    if (myFontPath == null) {
      notify(myFailure);
      return;
    }
    FontFileDownloader downloader = new FontFileDownloader(files);
    try {
      downloader.download(myFontPath);
      notify(mySuccess);
    }
    catch (Exception ex) {
      // The multiple file download failed.
      // Attempt to download the files one by one.
      if (performSingleFileDownloads(myFontPath, files)) {
        notify(mySuccess);
      }
      else if (myFailure != null) {
        notify(myFailure);
      }
    }
  }

  private static void notify(@Nullable Runnable callback) {
    if (callback != null) {
      callback.run();
    }
  }

  private static boolean performSingleFileDownloads(@NotNull File fontPath, @NotNull List<DownloadableFileDescription> files) {
    return performSingleFileDownloads(
      files,
      file -> new FontFileDownloader(Collections.singletonList(file)).download(fontPath),
      message -> ApplicationManager.getApplication().invokeLater(() ->
        NotificationGroupManager.getInstance().getNotificationGroup(PROBLEM_NOTIFICATION_GROUP_ID)
          .createNotification(message, MessageType.WARNING).notify(null)
      ),
      ourSanitizerUnavailableNotified);
  }

  @VisibleForTesting
  interface SingleFileDownload {
    void download(@NotNull DownloadableFileDescription file) throws Exception;
  }

  /**
   * Downloads {@code files} one by one. Failures are combined into a single notification. If the font sanitizer is not available, all
   * downloads would fail the same way, so the remaining downloads are skipped and only one notification is shown per session.
   *
   * @return true if all files were downloaded
   */
  @VisibleForTesting
  static boolean performSingleFileDownloads(@NotNull List<DownloadableFileDescription> files,
                                            @NotNull SingleFileDownload download,
                                            @NotNull Consumer<String> showProblem,
                                            @NotNull AtomicBoolean sanitizerUnavailableNotified) {
    List<String> failedUrls = new ArrayList<>();
    for (DownloadableFileDescription file : files) {
      try {
        download.download(file);
      }
      catch (Exception ex) {
        if (ExceptionUtil.findCause(ex, FontSanitizerUnavailableException.class) != null) {
          String message = "Fonts cannot be downloaded: the font sanitizer is not available";
          Logger.getInstance(FontDownloadService.class).warn(message, ex);
          if (sanitizerUnavailableNotified.compareAndSet(false, true)) {
            showProblem.accept(message);
          }
          return false;
        }
        Logger.getInstance(FontDownloadService.class).warn("Unable to download: " + file.getDownloadUrl(), ex);
        failedUrls.add(file.getDownloadUrl());
      }
    }
    if (failedUrls.isEmpty()) {
      return true;
    }
    showProblem.accept(failedUrls.size() == 1
                       ? "Unable to download: " + failedUrls.get(0)
                       : "Unable to download " + failedUrls.size() + " font files");
    return false;
  }

  private boolean cachedFileExists(@NotNull String relativeCachedPath) {
    File file = new File(myFontPath, relativeCachedPath);
    return file.exists();
  }

  private void addFontFamily(@NotNull List<DownloadableFileDescription> files, @NotNull FontFamily fontFamily) {
    String relativePath = myCacheService.getRelativeCachedMenuFile(fontFamily);
    if (relativePath != null && !cachedFileExists(relativePath)) {
      files.add(createFileDescription(fontFamily.getMenu(), relativePath));
    }
    if (!myDownloadMenuFontsOnly) {
      for (FontDetail font : fontFamily.getFonts()) {
        addFont(files, font);
      }
    }
  }

  private void addFont(@NotNull List<DownloadableFileDescription> files, @NotNull FontDetail font) {
    String relativePath = myCacheService.getRelativeFontFile(font);
    if (relativePath != null && !cachedFileExists(relativePath)) {
      files.add(createFileDescription(font.getFontUrl(), relativePath));
    }
  }

  @NotNull
  private static DownloadableFileDescription createFileDescription(@NotNull String url, @NotNull String relativePath) {
    return DownloadableFileService.getInstance().createFileDescription(url, relativePath);
  }
}
