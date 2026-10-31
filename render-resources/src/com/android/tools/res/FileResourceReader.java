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
package com.android.tools.res;

import static com.android.SdkConstants.FILE_PROTOCOL;
import static com.android.SdkConstants.JAR_PROTOCOL;
import static com.android.SdkConstants.JAR_SEPARATOR;

import com.android.SdkConstants;
import com.android.ide.common.util.PathString;
import com.android.tools.apk.analyzer.BinaryXmlParser;
import com.android.tools.apk.analyzer.ResourceIdResolver;
import com.android.zipflinger.Entry;
import com.android.zipflinger.ZipMap;
import com.android.zipflinger.ZipRepo;
import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Shorts;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.devrel.gmscore.tools.apk.arsc.Chunk;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Methods for working with Android file resources.
 */
public class FileResourceReader {
  private static final String APK_PROTOCOL = "apk";

  /**
   * Maximum number of retries if the current thread receives a new interrupt while reading a zip
   * entry via {@link java.nio.channels.FileChannel}.
   */
  private static final int MAX_INTERRUPT_RETRIES = 3;

  /**
   * Minimum interval between filesystem attribute checks ({@link Files#readAttributes}) for a
   * cached {@link ZipMap}. Rendering a Compose preview or XML layout can resolve hundreds or
   * thousands of zip-backed resources within a few milliseconds; throttling attribute checks avoids
   * per-read {@code stat} syscalls while still catching external archive updates between refreshes.
   */
  @VisibleForTesting static final long STAT_CHECK_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(250);

  @FunctionalInterface
  @VisibleForTesting
  interface AttributeReader {
    @NotNull
    BasicFileAttributes readAttributes(@NotNull Path path) throws IOException;
  }

  private static final AttributeReader DEFAULT_ATTRIBUTE_READER =
      path -> Files.readAttributes(path, BasicFileAttributes.class);

  private static volatile AttributeReader sAttributeReader = DEFAULT_ATTRIBUTE_READER;
  private static volatile Ticker sTicker = Ticker.systemTicker();

  /**
   * Cache to store {@link ZipMap} objects created from zip files, along with the file's
   * last-modified timestamp, size, and file key so in-place rewrites are detected. Keys are the
   * normalized absolute {@link Path} to the zip file from which the {@link ZipMap} is created.
   */
  private static final LoadingCache<Path, CachedZipMap> sZipCache =
      CacheBuilder.newBuilder()
          .maximumSize(10)
          .expireAfterAccess(5, TimeUnit.MINUTES)
          .build(
              new CacheLoader<Path, CachedZipMap>() {
                @Override
                public CachedZipMap load(@NotNull Path zipFile) throws IOException {
                  BasicFileAttributes attrs = sAttributeReader.readAttributes(zipFile);
                  ZipMap zipMap = ZipMap.from(zipFile);
                  return new CachedZipMap(
                      zipMap,
                      attrs.lastModifiedTime(),
                      attrs.size(),
                      attrs.fileKey(),
                      sTicker.read());
                }
              });

  /**
   * Cached {@link ZipMap} paired with the file metadata observed before it was loaded and the
   * monotonic timestamp of the last filesystem attribute check.
   *
   * <p>Inherits {@link Object#equals} (reference identity) so conditional removal via {@code
   * sZipCache.asMap().remove(zipFile, stale)} only evicts the exact stale instance and never a
   * newer entry populated concurrently.
   */
  private static final class CachedZipMap {
    @NotNull final ZipMap zipMap;
    @NotNull private final FileTime lastModifiedTime;
    private final long size;
    @Nullable private final Object fileKey;
    volatile long lastVerifiedNanos;

    CachedZipMap(
        @NotNull ZipMap zipMap,
        @NotNull FileTime lastModifiedTime,
        long size,
        @Nullable Object fileKey,
        long verifiedNanos) {
      this.zipMap = zipMap;
      this.lastModifiedTime = lastModifiedTime;
      this.size = size;
      this.fileKey = fileKey;
      this.lastVerifiedNanos = verifiedNanos;
    }

    boolean matches(@NotNull BasicFileAttributes attrs) {
      return this.size == attrs.size()
          && this.lastModifiedTime.equals(attrs.lastModifiedTime())
          && Objects.equals(this.fileKey, attrs.fileKey());
    }
  }

  /**
   * Reads and returns the contents of a resource. The resource path can point either to a file on
   * disk, or to a ZIP file entry. In the latter case the URI of the resource path contains a path
   * pointing to the ZIP file and has "apk" scheme. The path part of the resource path corresponds
   * to the path of the ZIP entry.
   *
   * @param resourcePath the path to a file or a zip entry
   * @return the contents of the file resource
   * @throws FileNotFoundException if the resource doesn't exist
   * @throws IOException in case of an I/O error
   */
  @NotNull
  public static byte[] readBytes(
    @NotNull PathString resourcePath,
    @NotNull ResourceIdResolver resourceIdResolver
  ) throws IOException {
    String scheme = resourcePath.getFilesystemUri().getScheme();
    switch (scheme) {
      case FILE_PROTOCOL:
        return readFileBytes(resourcePath.getRawPath());

      case APK_PROTOCOL:
      case JAR_PROTOCOL: {
        String path = resourcePath.getRawPath();
        int separatorPos = path.indexOf(JAR_SEPARATOR);
        int separatorEnd = separatorPos + JAR_SEPARATOR.length();
        if (separatorPos <= 0 || separatorEnd == path.length()) {
          throw new IllegalArgumentException("Invalid path in \"" + resourcePath + "\"");
        }
        byte[] rawContent = readZipEntryBytes(path.substring(0, separatorPos), path.substring(separatorEnd));
        if (isBinaryEncoded(scheme, path, rawContent)) {
          return BinaryXmlParser.decodeXml(rawContent, resourceIdResolver);
        }
        return rawContent;
      }

      default:
        throw new IllegalArgumentException("Unknown schema in \"" + resourcePath + "\"");
    }
  }

  private static boolean isBinaryEncoded(@NotNull String scheme, @NotNull String path, @NotNull byte[] resourceBytes) {
    return scheme.equals(APK_PROTOCOL) &&
           path.endsWith(SdkConstants.DOT_XML) &&
           resourceBytes.length > 2 &&
           Shorts.fromBytes(resourceBytes[1], resourceBytes[0]) == Chunk.Type.XML.code();
  }

  /**
   * Reads and returns the contents of a resource. The resource path can point either to a file on
   * disk, or to a ZIP file entry. In the latter case the resource path has the following format:
   * "apk:<i>path_to_zip_file</i>!/<i>path_to_zip_entry</i>.
   *
   * @param resourcePath the path to a file or a zip entry
   * @return the contents of the file resource
   * @throws FileNotFoundException if the resource doesn't exist
   * @throws IOException in case of an I/O error
   */
  @NotNull
  public static byte[] readBytes(@NotNull String resourcePath) throws IOException {
    if (resourcePath.startsWith("apk:") || resourcePath.startsWith("jar:")) {
      int prefixLength = "apk:".length(); // "jar:" has the same length as "apk:".
      if (resourcePath.startsWith("//", prefixLength)) {
        prefixLength += "//".length();
      }
      int separatorPos = resourcePath.lastIndexOf(JAR_SEPARATOR);
      if (separatorPos < prefixLength) {
        throw new IllegalArgumentException("Invalid resource path \"" + resourcePath + "\"");
      }
      return readZipEntryBytes(resourcePath.substring(prefixLength, separatorPos),
                               resourcePath.substring(separatorPos + JAR_SEPARATOR.length()));
    }

    if (resourcePath.startsWith("file:")) {
      int prefixLength = "file:".length();
      if (resourcePath.startsWith("//", prefixLength)) {
        prefixLength += "//".length();
      }
      resourcePath = resourcePath.substring(prefixLength);
    }
    return readFileBytes(resourcePath);
  }

  @NotNull
  private static byte[] readFileBytes(String filePath) throws IOException {
    try (FileInputStream fileStream = new FileInputStream(filePath)) {
      ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
      ByteStreams.copy(fileStream, byteOutputStream);
      return byteOutputStream.toByteArray();
    }
  }

  @NotNull
  private static byte[] readZipEntryBytes(String zipPath, String zipEntryPath) throws IOException {
    // ZipMap and ZipRepo use FileChannel (an InterruptibleChannel), which throws
    // ClosedByInterruptException if the thread's interrupt flag is set (for example after
    // RenderTask.executeCallbacks's 500ms timeout interrupts the render thread while callbacks
    // continue executing). Clear the interrupt status during the read and restore it afterwards so
    // zip-backed resource reads behave consistently with FileInputStream-backed readFileBytes.
    boolean interrupted = Thread.interrupted();
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          return readZipEntryBytesOnce(zipPath, zipEntryPath);
        }
        catch (ClosedByInterruptException e) {
          interrupted = true;
          Thread.interrupted();
          if (attempt >= MAX_INTERRUPT_RETRIES) {
            throw e;
          }
        }
      }
    }
    finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @NotNull
  private static byte[] readZipEntryBytesOnce(String zipPath, String zipEntryPath) throws IOException {
    // Cache ZipMap as its creation is time consuming, throttling attribute checks so rendering
    // bursts do not stat on every resource read. If the archive was modified within the throttle
    // window (or concurrently during the read), missing entries, I/O errors, or uncompressed/CRC
    // mismatches trigger a conditional cache reload and a single retry.
    Path zipFile = Paths.get(zipPath).toAbsolutePath().normalize();
    CachedZipMap cached = getValidatedZipMap(zipFile);
    try {
      return readEntryFromZipRepo(cached.zipMap, zipEntryPath);
    } catch (ClosedByInterruptException e) {
      throw e;
    } catch (IllegalArgumentException | IOException e) {
      CachedZipMap reloaded = reloadCachedZipMap(zipFile, cached);
      return readEntryFromZipRepo(reloaded.zipMap, zipEntryPath);
    }
  }

  @NotNull
  private static CachedZipMap getValidatedZipMap(@NotNull Path zipFile) throws IOException {
    CachedZipMap cached = getCachedZipMap(zipFile);
    for (int i = 0; i < 2; i++) {
      long lastVerified = cached.lastVerifiedNanos;
      if (sTicker.read() - lastVerified < STAT_CHECK_INTERVAL_NS) {
        return cached;
      }

      CachedZipMap replaced = null;
      boolean matchesDisk = false;
      synchronized (cached) {
        long verifiedUnderLock = cached.lastVerifiedNanos;
        if (sTicker.read() - verifiedUnderLock < STAT_CHECK_INTERVAL_NS) {
          return cached;
        }
        CachedZipMap current = sZipCache.getIfPresent(zipFile);
        if (current != cached) {
          replaced = current;
        } else {
          final BasicFileAttributes attrs;
          try {
            attrs = sAttributeReader.readAttributes(zipFile);
          } catch (ClosedByInterruptException e) {
            throw e;
          } catch (IOException e) {
            sZipCache.asMap().remove(zipFile, cached);
            throw e;
          }
          if (cached.matches(attrs)) {
            cached.lastVerifiedNanos = sTicker.read();
            matchesDisk = true;
          }
        }
      }

      if (matchesDisk) {
        return cached;
      }
      if (replaced != null) {
        cached = replaced;
        continue;
      }
      return reloadCachedZipMap(zipFile, cached);
    }
    return cached;
  }

  @NotNull
  private static CachedZipMap reloadCachedZipMap(@NotNull Path zipFile, @NotNull CachedZipMap stale)
      throws IOException {
    sZipCache.asMap().remove(zipFile, stale);
    return getCachedZipMap(zipFile);
  }

  @NotNull
  private static byte[] readEntryFromZipRepo(@NotNull ZipMap zipMap, @NotNull String zipEntryPath)
      throws IOException {
    Entry entry = zipMap.getEntries().get(zipEntryPath);
    if (entry == null) {
      throw new IllegalArgumentException(
          String.format("No entry '%s' in file '%s'", zipEntryPath, zipMap.getPath()));
    }
    try (ZipRepo zipRepo = new ZipRepo(zipMap)) {
      ByteBuffer entryContent = zipRepo.getContent(zipEntryPath);
      byte[] bytes = new byte[entryContent.remaining()];
      entryContent.get(bytes);
      verifyEntryBytes(entry, bytes, zipMap.getPath());
      return bytes;
    }
  }

  private static void verifyEntryBytes(
      @NotNull Entry entry, @NotNull byte[] bytes, @NotNull Path zipPath) throws ZipException {
    if (bytes.length != entry.getUncompressedSize()) {
      throw new ZipException(
          String.format(
              "Entry '%s' size mismatch in '%s': expected %d, got %d",
              entry.getName(), zipPath, entry.getUncompressedSize(), bytes.length));
    }
    CRC32 crc32 = new CRC32();
    crc32.update(bytes);
    if ((int) crc32.getValue() != entry.getCrc()) {
      throw new ZipException(
          String.format("Entry '%s' CRC mismatch in '%s'", entry.getName(), zipPath));
    }
  }

  @NotNull
  private static CachedZipMap getCachedZipMap(@NotNull Path zipFile) throws IOException {
    try {
      return sZipCache.get(zipFile);
    }
    catch (ExecutionException e) {
      Throwable nested = e.getCause();
      if (nested instanceof IOException) {
        throw (IOException)nested;
      }
      if (nested instanceof RuntimeException) {
        throw (RuntimeException)nested;
      }
      if (nested instanceof Error) {
        throw (Error)nested;
      }
      throw new UncheckedExecutionException(nested);
    }
  }

  @VisibleForTesting
  static void setAttributeReaderForTesting(@NotNull AttributeReader attributeReader) {
    sAttributeReader = attributeReader;
  }

  @VisibleForTesting
  static void setTickerForTesting(@NotNull Ticker ticker) {
    sTicker = ticker;
  }

  @VisibleForTesting
  static void resetForTesting() {
    sAttributeReader = DEFAULT_ATTRIBUTE_READER;
    sTicker = Ticker.systemTicker();
    sZipCache.invalidateAll();
  }

  /**
   * Creates a {@link XmlPullParser} for the given XML file resource.
   *
   * @param resourcePath the path to a file or a zip entry
   * @return the parser for the resource, or null if the resource does not exist
   * @throws IOException in case of an I/O error
   */
  @Nullable
  public static XmlPullParser createXmlPullParser(
    @NotNull PathString resourcePath,
    @NotNull ResourceIdResolver resIdResolver
  ) throws IOException {
    try {
      byte[] contents = readBytes(resourcePath, resIdResolver);
      return createXmlPullParser(contents);
    }
    catch (FileNotFoundException e) {
      return null;
    }
  }

  /**
   * Creates a {@link XmlPullParser} for the contents of an XML file resource.
   *
   * @param contents the contents of a file resource
   * @return the parser for the resource
   */
  @NotNull
  public static XmlPullParser createXmlPullParser(@NotNull byte[] contents) {
    try {
      XmlPullParser parser = new KXmlParser();
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      parser.setInput(new ByteArrayInputStream(contents), null);
      return parser;
    } catch (XmlPullParserException e) {
      throw new Error("Internal error", e); // Should not happen unless there is a bug.
    }
  }

  /** Do not instantiate - all methods are static. */
  private FileResourceReader() {}
}
