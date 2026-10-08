/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.testartifacts.instrumented.testsuite.model

import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** The maximum number of characters of logcat output an [AndroidTestCase] reads from a file or retains in memory. */
const val MAX_LOGCAT_RETAINED_CHARS: Int = 1024 * 1024

private const val TRUNCATION_NOTICE = "[Android Studio omitted %,d bytes of output to limit memory usage]"

@VisibleForTesting
fun boundInMemoryLogcat(text: String, maxChars: Int = MAX_LOGCAT_RETAINED_CHARS): String {
  if (maxChars <= 0) return ""
  if (text.length <= maxChars) return text
  val reservedChars = if (maxChars > 512) 256 else 0
  val charsToKeep = maxChars - reservedChars
  var dropped = text.length - charsToKeep
  if (dropped in 1 until text.length && Character.isLowSurrogate(text[dropped])) {
    dropped++
  }
  return TRUNCATION_NOTICE.format(Locale.US, dropped) + "\n" + text.substring(dropped)
}

fun readLogcatFromFile(file: File, maxChars: Int = MAX_LOGCAT_RETAINED_CHARS): String {
  return try {
    if (maxChars <= 0 || file.path.isNullOrEmpty()) {
      return ""
    }
    synchronized(file) {
      RandomAccessFile(file, "r").use { raf ->
        val fileLength = raf.length()
        if (fileLength == 0L) {
          return ""
        }
        if (fileLength <= maxChars) {
          val buffer = ByteArray(fileLength.toInt())
          raf.readFully(buffer)
          String(buffer, Charsets.UTF_8)
        } else {
          val reservedBytes = if (maxChars > 512) 256 else 0
          val bytesToRead = maxChars - reservedBytes
          raf.seek(fileLength - bytesToRead)
          val buffer = ByteArray(bytesToRead)
          raf.readFully(buffer)
          var firstNewline = -1
          val scanLimit = minOf(1024, buffer.size * 3 / 4)
          for (i in 0 until scanLimit) {
            if (buffer[i] == '\n'.code.toByte()) {
              firstNewline = i
              break
            }
          }
          var startIndex = 0
          if (firstNewline != -1) {
            startIndex = firstNewline + 1
          } else {
            while (startIndex < buffer.size && (buffer[startIndex].toInt() and 0xC0) == 0x80) {
              startIndex++
            }
          }
          val extraDropped = startIndex
          val truncatedBytes = fileLength - bytesToRead + extraDropped
          TRUNCATION_NOTICE.format(Locale.US, truncatedBytes) + "\n" + String(buffer, startIndex, buffer.size - startIndex, Charsets.UTF_8)
        }
      }
    }
  } catch (e: Exception) {
    Logger.getInstance(AndroidTestCase::class.java).warn("Failed to read logcat from ${file.path}", e)
    ""
  }
}

/**
 * Encapsulates an Android test case metadata to be displayed in Android test suite view.
 *
 * @param id a test case identifier. This can be arbitrary string as long as it is unique to other test cases.
 * @param methodName a name of the test method
 * @param className a name of the test class
 * @param packageName a name of the tested APP
 * @param result a result of this test case. Null when the test case execution hasn't finished yet.
 * @param logcatFile a file containing logcat output for this test case.
 * @param errorStackTrace an error stack trace. Empty if a test passes.
 * @param startTimestampMillis a timestamp when this test execution starts in milliseconds in unix time.
 * @param endTimestampMillis a timestamp when this test execution finishes in milliseconds in unix time.
 * @param benchmark an output from AndroidX Benchmark library.
 * @param retentionInfo an Android Test Retention info artifact.
 * @param retentionSnapshot an Android Test Retention snapshot artifact.
 * @param additionalTestArtifacts additional test artifacts.
 */
data class AndroidTestCase(
  val id: String,
  val methodName: String,
  val className: String,
  val packageName: String,
  var result: AndroidTestCaseResult = AndroidTestCaseResult.SCHEDULED,
  var logcatFile: File? = null,
  var errorStackTrace: String = "",
  var startTimestampMillis: Long? = null,
  var endTimestampMillis: Long? = null,
  var benchmark: String = "",
  var retentionInfo: File? = null,
  var retentionSnapshot: File? = null,
  val additionalTestArtifacts: MutableMap<String, String> = mutableMapOf(),
) {
  private var _logcat: String = ""

  /** Secondary constructor for creating an [AndroidTestCase] with in-memory logcat without a backing file. */
  constructor(
    id: String,
    methodName: String,
    className: String,
    packageName: String,
    result: AndroidTestCaseResult = AndroidTestCaseResult.SCHEDULED,
    logcat: String,
    errorStackTrace: String = "",
    startTimestampMillis: Long? = null,
    endTimestampMillis: Long? = null,
    benchmark: String = "",
    retentionInfo: File? = null,
    retentionSnapshot: File? = null,
    additionalTestArtifacts: MutableMap<String, String> = mutableMapOf(),
  ) : this(
    id = id,
    methodName = methodName,
    className = className,
    packageName = packageName,
    result = result,
    logcatFile = null,
    errorStackTrace = errorStackTrace,
    startTimestampMillis = startTimestampMillis,
    endTimestampMillis = endTimestampMillis,
    benchmark = benchmark,
    retentionInfo = retentionInfo,
    retentionSnapshot = retentionSnapshot,
    additionalTestArtifacts = additionalTestArtifacts,
  ) {
    _logcat = boundInMemoryLogcat(logcat)
  }

  /**
   * Lazily loads logcat output from [logcatFile] up to [MAX_LOGCAT_RETAINED_CHARS] on demand without retaining the text in memory to
   * prevent OOM (b/548501407), or returns [_logcat] if [logcatFile] is null. Setting [logcat] clears [logcatFile] and sets [_logcat].
   */
  var logcat: String
    get() = logcatFile?.let { readLogcatFromFile(it) } ?: _logcat
    set(value) {
      logcatFile = null
      _logcat = boundInMemoryLogcat(value)
    }
}

/** A result of a test case execution. */
enum class AndroidTestCaseResult(val isTerminalState: Boolean) {
  /** A test case is failed. */
  FAILED(true),

  /** A test case is skipped by test runner. */
  SKIPPED(true),

  /** A test case is passed. */
  PASSED(true),

  /** A test case is in progress. */
  IN_PROGRESS(false),

  /** A test case which is scheduled to run ends up with cancelled. */
  CANCELLED(true),

  /** A test case is scheduled but not started yet. */
  SCHEDULED(false),
}
