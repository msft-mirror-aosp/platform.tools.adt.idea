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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.ide.common.util.PathString;
import com.android.testutils.TestUtils;
import com.android.tools.apk.analyzer.ResourceIdResolver;
import com.android.tools.res.apk.ApkTestUtil;
import com.google.common.base.Ticker;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link FileResourceReader}.
 */
public class FileResourceReaderTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @After
  public void tearDown() {
    FileResourceReader.resetForTesting();
  }

  @Test
  public void testReadBytes() throws Exception {
    Path resApkPath =
        TestUtils.resolveWorkspacePath(ApkTestUtil.TEST_DATA_DIR + "apk-all-resources.ap_");
    String resourcePath = resApkPath + "!/res/drawable-mdpi-v4/abc_btn_check_to_on_mtrl_000.png";
    PathString pathString = new PathString("apk", resourcePath);

    byte[] bytes = FileResourceReader.readBytes(pathString, ResourceIdResolver.NO_RESOLUTION);
    assertNotNull(bytes);
    assertEquals(214, bytes.length);

    bytes = FileResourceReader.readBytes("apk:" + resourcePath);
    assertNotNull(bytes);
    assertEquals(214, bytes.length);
  }

  @Test
  public void testReadBytes_whenThreadIsInterrupted_succeedsAndPreservesInterruptFlag() throws Exception {
    Path resApkPath =
        TestUtils.resolveWorkspacePath(ApkTestUtil.TEST_DATA_DIR + "apk-all-resources.ap_");
    String resourcePath = resApkPath + "!/res/drawable-mdpi-v4/abc_btn_check_to_on_mtrl_000.png";
    PathString pathString = new PathString("apk", resourcePath);

    Thread.currentThread().interrupt();
    try {
      byte[] bytes = FileResourceReader.readBytes(pathString, ResourceIdResolver.NO_RESOLUTION);
      assertNotNull(bytes);
      assertEquals(214, bytes.length);
      assertTrue(
          "Thread interrupt status should be preserved after readBytes",
          Thread.currentThread().isInterrupted());

      bytes = FileResourceReader.readBytes("apk:" + resourcePath);
      assertNotNull(bytes);
      assertEquals(214, bytes.length);
      assertTrue(
          "Thread interrupt status should remain set", Thread.currentThread().isInterrupted());
    }
    finally {
      Thread.interrupted();
    }
  }

  @Test
  public void testReadBytes_forBinaryEncodedApkEntries() throws Exception {
    Path resApkPath =
        TestUtils.resolveWorkspacePath(ApkTestUtil.TEST_DATA_DIR + "apk-for-local-test.ap_");
    String resourcePath = resApkPath + "!/res/drawable-anydpi-v24/ic_placeholder_default.xml";
    PathString pathString = new PathString("apk", resourcePath);

    byte[] bytes = FileResourceReader.readBytes(pathString, ResourceIdResolver.NO_RESOLUTION);
    assertNotNull(bytes);

    assertEquals(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<vector\n"
            + "    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    xmlns:aapt=\"http://schemas.android.com/aapt\"\n"
            + "    android:height=\"182dp\"\n"
            + "    android:width=\"364dp\"\n"
            + "    android:viewportWidth=\"364\"\n"
            + "    android:viewportHeight=\"182\">\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"#FCFCFC\"\n"
            + "        android:pathData=\"M0,0h364v182h-364z\" />\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"#7E7576\"\n"
            + "        android:pathData=\"M0,0h364v182h-364z\"\n"
            + "        android:fillAlpha=\"0.02\" />\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"#8C4190\"\n"
            + "        android:pathData=\"M0,0h364v182h-364z\"\n"
            + "        android:fillAlpha=\"0.11\" />\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"#00000000\"\n"
            + "        android:pathData=\"M171,119L326,119A25,25 0,0 1,351 144L351,144A25,25 0,0"
            + " 1,326 169L171,169A25,25 0,0 1,146 144L146,144A25,25 0,0 1,171 119z\"\n"
            + "        android:strokeColor=\"#5DD4FB\"\n"
            + "        android:strokeWidth=\"2\" />\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"@ref/0x7f060000\"\n"
            + "        android:pathData=\"M156.023,33.705C154.247,37.971 153.333,42.543"
            + " 153.333,47.161L188.666,47.161L224,47.161C224,42.543 223.086,37.971"
            + " 221.31,33.705C219.535,29.44 216.932,25.563 213.651,22.298C210.37,19.033"
            + " 206.475,16.444 202.188,14.677C197.901,12.91 193.307,12 188.667,12C184.026,12"
            + " 179.432,12.91 175.145,14.677C170.858,16.444 166.963,19.033"
            + " 163.682,22.298C160.401,25.563 157.798,29.44"
            + " 156.023,33.705ZM153.333,47.161C153.333,51.778 152.409,56.35"
            + " 150.615,60.616C148.821,64.882 146.192,68.758 142.877,72.023C139.562,75.288"
            + " 135.627,77.878 131.296,79.645C126.965,81.412 122.323,82.322"
            + " 117.635,82.322C112.947,82.322 108.305,81.412 103.974,79.645C99.643,77.878"
            + " 95.708,75.288 92.393,72.023C89.078,68.758 86.449,64.882 84.655,60.616C82.861,56.35"
            + " 81.938,51.778 81.938,47.161L117.635,47.161L153.333,47.161ZM12,47.161C12,42.543"
            + " 12.904,37.971 14.661,33.705C16.419,29.439 18.994,25.563 22.242,22.298C25.489,19.033"
            + " 29.344,16.443 33.586,14.676C37.829,12.909 42.376,12 46.968,12C51.561,12"
            + " 56.108,12.909 60.351,14.676C64.593,16.443 68.448,19.033 71.695,22.298C74.942,25.563"
            + " 77.518,29.439 79.276,33.705C81.033,37.971 81.938,42.543"
            + " 81.938,47.161L46.968,47.161L12,47.161Z\"\n"
            + "        android:fillType=\"1\" />\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"#00000000\"\n"
            + "       "
            + " android:pathData=\"M317.068,82.136L317.065,47.103L309.777,81.37L317.057,47.102L302.805,79.105L317.051,47.099L296.456,75.439L317.045,47.094L291.007,70.534L317.04,47.089L286.698,64.602L317.036,47.083L283.716,57.905L317.034,47.076L282.192,50.734L317.033,47.068L282.192,43.403L317.034,47.061L283.716,36.232L317.036,47.054L286.698,29.534L317.04,47.048L291.007,23.603L317.045,47.042L296.456,18.697L317.051,47.038L302.805,15.032L317.057,47.035L309.777,12.766L317.065,47.033L317.068,12L317.072,47.033L324.359,12.766L317.079,47.035L331.332,15.032L317.086,47.038L337.681,18.697L317.092,47.042L343.129,23.603L317.097,47.048L347.438,29.534L317.1,47.054L350.42,36.232L317.102,47.061L351.944,43.403L317.103,47.068L351.944,50.734L317.102,47.076L350.42,57.905L317.1,47.083L347.438,64.602L317.097,47.089L343.129,70.534L317.092,47.094L337.681,75.439L317.086,47.099L331.332,79.105L317.079,47.102L324.359,81.37L317.072,47.103L317.068,82.136Z\"\n"
            + "        android:strokeColor=\"#FF8B5E\"\n"
            + "        android:strokeWidth=\"1\"\n"
            + "        android:strokeLineJoin=\"1\" />\n"
            + "\n"
            + "    <path\n"
            + "        android:fillColor=\"#FFA8FF\"\n"
            + "        android:pathData=\"M38.205,170L38.975,170A26.205,26.205 89.056,0 0,65.18"
            + " 143.795L65.18,143.795A26.205,26.205 89.056,0 0,38.975"
            + " 117.59L38.205,117.59A26.205,26.205 89.056,0 0,12 143.795L12,143.795A26.205,26.205"
            + " 89.056,0 0,38.205 170z\" />\n"
            + "</vector>\n",
        new String(bytes, StandardCharsets.US_ASCII));
  }

  @Test
  public void testReadBytes_reloadsZipWhenModifiedInPlace() throws Exception {
    Path zipPath = temporaryFolder.newFile("file-resource-reader-test.ap_").toPath();
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "first-v1"},
          {"res/raw/second.txt", "second-v1"},
        });
    FileTime initialMtime = Files.getLastModifiedTime(zipPath);
    long initialSize = Files.size(zipPath);

    assertEquals("first-v1", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals("second-v1", readEntry(zipPath, "res/raw/second.txt"));

    // Rewrite with STORED entries of identical length and bump mtime so mtime alone triggers
    // reload.
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "first-v2"},
          {"res/raw/second.txt", "second-v2"},
        });
    assertEquals(initialSize, Files.size(zipPath));
    Files.setLastModifiedTime(zipPath, FileTime.fromMillis(initialMtime.toMillis() + 5_000));

    assertEquals("first-v2", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals("second-v2", readEntry(zipPath, "res/raw/second.txt"));

    // Rewrite the zip in place with shifted entry offsets, updated content, and a new entry.
    writeZip(
        zipPath,
        ZipEntry.DEFLATED,
        new String[][] {
          {"res/raw/first.txt", "first-v3-with-longer-content-to-shift-offsets"},
          {"res/raw/second.txt", "second-v3"},
          {"res/raw/third.txt", "third-added"},
        });
    Files.setLastModifiedTime(zipPath, FileTime.fromMillis(initialMtime.toMillis() + 10_000));

    assertEquals(
        "first-v3-with-longer-content-to-shift-offsets", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals("second-v3", readEntry(zipPath, "res/raw/second.txt"));
    assertEquals("third-added", readEntry(zipPath, "res/raw/third.txt"));
  }

  @Test
  public void testReadBytes_reloadsOnMissingEntryAndShiftedStoredOffsetsWithinThrottleWindow()
      throws Exception {
    AtomicInteger statCount = new AtomicInteger();
    FileResourceReader.setAttributeReaderForTesting(
        path -> {
          statCount.incrementAndGet();
          return Files.readAttributes(path, BasicFileAttributes.class);
        });
    FileResourceReader.setTickerForTesting(
        new Ticker() {
          @Override
          public long read() {
            return 0L;
          }
        });

    Path zipPath = temporaryFolder.newFile("within-throttle.ap_").toPath();
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "first-v1"},
          {"res/raw/second.txt", "second-v1"},
        });

    // Cold miss performs a single stat inside CacheLoader.load; warm hit performs none.
    assertEquals("first-v1", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals(1, statCount.get());
    assertEquals("second-v1", readEntry(zipPath, "res/raw/second.txt"));
    assertEquals(1, statCount.get());

    // Rewrite with STORED entries where first.txt grows so second.txt's offset shifts while its
    // own uncompressed content stays identical, all while the ticker has not advanced.
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "first-v2-longer-content-shifting-stored-offsets"},
          {"res/raw/second.txt", "second-v1"},
        });

    // Reading second.txt first catches the shifted STORED offset via CRC mismatch and reloads.
    assertEquals("second-v1", readEntry(zipPath, "res/raw/second.txt"));
    assertEquals(2, statCount.get());
    assertEquals(
        "first-v2-longer-content-shifting-stored-offsets", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals(2, statCount.get());

    // Rewrite adding a brand-new entry while still inside the throttle window.
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "first-v2-longer-content-shifting-stored-offsets"},
          {"res/raw/second.txt", "second-v1"},
          {"res/raw/third.txt", "third-added"},
        });

    // Missing entry IllegalArgumentException triggers cache reload and retry.
    assertEquals("third-added", readEntry(zipPath, "res/raw/third.txt"));
    assertEquals(3, statCount.get());

    // A genuinely absent entry still throws IllegalArgumentException after reloading.
    assertThrows(
        IllegalArgumentException.class, () -> readEntry(zipPath, "res/raw/nonexistent.txt"));

    // Truncating the archive on disk must not silently return zero-filled STORED bytes.
    Files.write(zipPath, new byte[0]);
    assertThrows(RuntimeException.class, () -> readEntry(zipPath, "res/raw/first.txt"));
  }

  @Test
  public void testReadBytes_throttlesStatCallsDuringRapidReadsAndDetectsUpdatesAfterInterval()
      throws Exception {
    AtomicInteger statCount = new AtomicInteger();
    AtomicLong fakeNanos = new AtomicLong(1_000_000L);
    FileResourceReader.setAttributeReaderForTesting(
        path -> {
          statCount.incrementAndGet();
          return Files.readAttributes(path, BasicFileAttributes.class);
        });
    FileResourceReader.setTickerForTesting(
        new Ticker() {
          @Override
          public long read() {
            return fakeNanos.get();
          }
        });

    Path zipPath = temporaryFolder.newFile("throttled-stat.ap_").toPath();
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "unchanged"},
          {"res/raw/removed_later.txt", "present"},
        });
    FileTime initialMtime = Files.getLastModifiedTime(zipPath);

    assertEquals("unchanged", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals(1, statCount.get());

    // 50 rapid reads spaced 1ms apart (< STAT_CHECK_INTERVAL_NS) incur zero additional stats.
    for (int i = 0; i < 50; i++) {
      fakeNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
      assertEquals("unchanged", readEntry(zipPath, "res/raw/first.txt"));
      assertEquals("present", readEntry(zipPath, "res/raw/removed_later.txt"));
    }
    assertEquals(1, statCount.get());

    // Once STAT_CHECK_INTERVAL_NS elapses on an unchanged archive, one stat re-verifies in place
    // without re-parsing ZipMap, and subsequent rapid reads again incur zero stats.
    fakeNanos.addAndGet(FileResourceReader.STAT_CHECK_INTERVAL_NS);
    for (int i = 0; i < 20; i++) {
      assertEquals("unchanged", readEntry(zipPath, "res/raw/first.txt"));
      fakeNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    }
    assertEquals(2, statCount.get());

    // Rewrite archive keeping first.txt identical at offset 0 while removing removed_later.txt.
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/first.txt", "unchanged"},
        });
    Files.setLastModifiedTime(zipPath, FileTime.fromMillis(initialMtime.toMillis() + 5_000));

    // After STAT_CHECK_INTERVAL_NS, reading first.txt detects the attribute change via stat (1 stat
    // in getValidatedZipMap + 1 stat in CacheLoader.load) and replaces the cached ZipMap.
    fakeNanos.addAndGet(FileResourceReader.STAT_CHECK_INTERVAL_NS);
    assertEquals("unchanged", readEntry(zipPath, "res/raw/first.txt"));
    assertEquals(4, statCount.get());
    assertThrows(
        IllegalArgumentException.class, () -> readEntry(zipPath, "res/raw/removed_later.txt"));
  }

  @Test
  public void testReadBytes_concurrentReadsAndStaleEvictionRaceSafety() throws Exception {
    AtomicInteger statCount = new AtomicInteger();
    AtomicLong sharedNanos = new AtomicLong(0L);
    FileResourceReader.setAttributeReaderForTesting(
        path -> {
          statCount.incrementAndGet();
          return Files.readAttributes(path, BasicFileAttributes.class);
        });
    FileResourceReader.setTickerForTesting(
        new Ticker() {
          @Override
          public long read() {
            return sharedNanos.get();
          }
        });

    Path zipPath = temporaryFolder.newFile("concurrent.ap_").toPath();
    writeZip(
        zipPath,
        ZipEntry.STORED,
        new String[][] {
          {"res/raw/entry.txt", "v1"},
        });
    FileTime initialMtime = Files.getLastModifiedTime(zipPath);

    assertEquals("v1", readEntry(zipPath, "res/raw/entry.txt"));
    assertEquals(1, statCount.get());

    // Part 1: 8 threads hit an expired interval simultaneously; only one stat should execute.
    sharedNanos.addAndGet(FileResourceReader.STAT_CHECK_INTERVAL_NS);
    int threadCount = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  return readEntry(zipPath, "res/raw/entry.txt");
                }));
      }
      for (Future<String> future : futures) {
        assertEquals("v1", future.get(5, TimeUnit.SECONDS));
      }
      assertEquals(2, statCount.get());

      // Part 2: Deterministic test for the stale-eviction race. Thread 1 enters slow-path attribute
      // validation on cachedV1 and pauses while Thread 2 reloads sZipCache with cachedV2. When
      // Thread 1 resumes and calls reloadCachedZipMap(zipFile, cachedV1), it must not evict
      // cachedV2.
      writeZip(
          zipPath,
          ZipEntry.STORED,
          new String[][] {
            {"res/raw/entry.txt", "v2"},
            {"res/raw/added.txt", "new"},
          });
      Files.setLastModifiedTime(zipPath, FileTime.fromMillis(initialMtime.toMillis() + 5_000));

      AtomicReference<Thread> slowThread = new AtomicReference<>();
      CountDownLatch t1EnteredStat = new CountDownLatch(1);
      CountDownLatch t2PopulatedV2 = new CountDownLatch(1);
      long baseNanos = sharedNanos.get();

      FileResourceReader.setTickerForTesting(
          new Ticker() {
            @Override
            public long read() {
              return Thread.currentThread() == slowThread.get()
                  ? baseNanos + FileResourceReader.STAT_CHECK_INTERVAL_NS
                  : baseNanos;
            }
          });
      FileResourceReader.setAttributeReaderForTesting(
          path -> {
            statCount.incrementAndGet();
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (Thread.currentThread() == slowThread.get()) {
              t1EnteredStat.countDown();
              try {
                assertTrue(t2PopulatedV2.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for concurrent reload", e);
              }
            }
            return attrs;
          });

      Future<String> t1Future =
          pool.submit(
              () -> {
                slowThread.set(Thread.currentThread());
                return readEntry(zipPath, "res/raw/entry.txt");
              });
      assertTrue(t1EnteredStat.await(5, TimeUnit.SECONDS));

      Future<String> t2Future = pool.submit(() -> readEntry(zipPath, "res/raw/added.txt"));
      assertEquals("new", t2Future.get(5, TimeUnit.SECONDS));
      int statsAfterT2Reload = statCount.get();
      // 2 prior stats + 1 from Thread 1's paused stat + 1 from Thread 2's CacheLoader.load(v2) = 4.
      assertEquals(4, statsAfterT2Reload);

      t2PopulatedV2.countDown();
      assertEquals("v2", t1Future.get(5, TimeUnit.SECONDS));
      // Thread 1 reused Thread 2's cachedV2 without evicting or re-parsing it.
      assertEquals(4, statCount.get());
      assertEquals("v2", readEntry(zipPath, "res/raw/entry.txt"));
      assertEquals(4, statCount.get());
    } finally {
      pool.shutdownNow();
    }
  }

  private static String readEntry(Path zipPath, String entryPath) throws IOException {
    return new String(
        FileResourceReader.readBytes("apk:" + zipPath + "!/" + entryPath), StandardCharsets.UTF_8);
  }

  private static void writeZip(Path zipPath, int method, String[][] entries) throws IOException {
    try (OutputStream out = Files.newOutputStream(zipPath);
        ZipOutputStream zos = new ZipOutputStream(out)) {
      zos.setMethod(method);
      for (String[] entry : entries) {
        byte[] data = entry[1].getBytes(StandardCharsets.UTF_8);
        ZipEntry zipEntry = new ZipEntry(entry[0]);
        zipEntry.setMethod(method);
        if (method == ZipEntry.STORED) {
          zipEntry.setSize(data.length);
          zipEntry.setCompressedSize(data.length);
          CRC32 crc = new CRC32();
          crc.update(data);
          zipEntry.setCrc(crc.getValue());
        }
        zos.putNextEntry(zipEntry);
        zos.write(data);
        zos.closeEntry();
      }
    }
  }
}
