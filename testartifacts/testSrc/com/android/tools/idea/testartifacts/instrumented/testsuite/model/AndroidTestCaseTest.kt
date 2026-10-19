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
package com.android.tools.idea.testartifacts.instrumented.testsuite.model

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [AndroidTestCase]. */
@RunWith(JUnit4::class)
class AndroidTestCaseTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun nullLogcatFileReturnsEmptyString() {
    val testCase = AndroidTestCase("id", "method", "class", "package")
    assertThat(testCase.logcat).isEmpty()
    assertThat(testCase.logcatFile).isNull()
  }

  @Test
  fun logcatReturnsContentFromLogcatFileLazily() {
    val logcatFile = temporaryFolder.newFile("test-logcat.txt")
    logcatFile.writeText("hello from logcat file")

    val testCase = AndroidTestCase("id", "method", "class", "package", logcatFile = logcatFile)
    assertThat(testCase.logcat).isEqualTo("hello from logcat file")
    assertThat(testCase.logcatFile).isEqualTo(logcatFile)
  }

  @Test
  fun nonExistentLogcatFileReturnsEmptyString() {
    val nonExistentFile = temporaryFolder.root.resolve("does_not_exist.txt")
    val testCase = AndroidTestCase("id", "method", "class", "package", logcatFile = nonExistentFile)
    assertThat(testCase.logcat).isEmpty()
  }

  @Test
  fun emptyLogcatFileReturnsEmptyString() {
    val emptyFile = temporaryFolder.newFile("empty.txt")
    val testCase = AndroidTestCase("id", "method", "class", "package", logcatFile = emptyFile)
    assertThat(testCase.logcat).isEmpty()
  }

  @Test
  fun largeLogcatFileIsTruncatedFromTail() {
    val file = temporaryFolder.newFile("large-logcat.txt")
    val content = "Line 1: preamble\nLine 2: first part\nLine 3: second part\nLine 4: tail content\n"
    file.writeText(content)

    // Read with a small maxChars limit (e.g. 25 chars)
    val result = readLogcatFromFile(file, maxChars = 25)
    assertThat(result).contains("[Android Studio omitted")
    assertThat(result).contains("Line 4: tail content\n")
    assertThat(result).doesNotContain("Line 1: preamble")
  }

  @Test
  fun dataClassPropertiesAndEquality() {
    val file = temporaryFolder.newFile("logcat.txt")
    val testCase1 =
      AndroidTestCase(
        id = "id",
        methodName = "method",
        className = "class",
        packageName = "package",
        logcatFile = file,
        errorStackTrace = "stackTrace",
        benchmark = "bench",
        retentionInfo = file,
        retentionSnapshot = file,
      )
    val testCase2 = testCase1.copy()

    assertThat(testCase1).isEqualTo(testCase2)
    assertThat(testCase1.hashCode()).isEqualTo(testCase2.hashCode())
    val str = testCase1.toString()
    assertThat(str).contains("id=id")
    assertThat(str).contains("methodName=method")
    assertThat(str).contains("className=class")
    assertThat(str).contains("packageName=package")
    assertThat(str).contains("logcatFile=")
    assertThat(str).contains("errorStackTrace=stackTrace")
    assertThat(str).contains("benchmark=bench")
    assertThat(str).contains("retentionInfo=")
    assertThat(str).contains("retentionSnapshot=")

    val testCase3 = testCase1.copy(methodName = "otherMethod")
    assertThat(testCase1).isNotEqualTo(testCase3)
  }

  @Test
  fun largeFileTruncatedOutputDoesNotExceedMaxChars() {
    val file = temporaryFolder.newFile("very-large-logcat.txt")
    // Write 1.5MB of data
    val line = "A".repeat(100) + "\n"
    file.bufferedWriter().use { writer ->
      repeat(15_000) {
        writer.write(line)
      }
    }

    val result = readLogcatFromFile(file, maxChars = MAX_LOGCAT_RETAINED_CHARS)
    assertThat(result.length).isAtMost(MAX_LOGCAT_RETAINED_CHARS)
    assertThat(result).contains("[Android Studio omitted")
    assertThat(result).contains("bytes of output to limit memory usage]")
  }

  @Test
  fun truncationWithoutNewlineAndUtf8MultiByteAvoidsBrokenCodePoints() {
    val file = temporaryFolder.newFile("multibyte-logcat.txt")
    // Write 3-byte UTF-8 Japanese characters (each character \u3042 is 3 bytes: 0xE3, 0x81, 0x82)
    // No newlines in file
    val content = "\u3042".repeat(50) // 150 bytes
    file.writeText(content, Charsets.UTF_8)

    // Read with maxChars = 20 (not divisible by 3)
    val result = readLogcatFromFile(file, maxChars = 20)
    assertThat(result).doesNotContain("\uFFFD")
    assertThat(result).contains("[Android Studio omitted")
  }

  @Test
  fun truncationWhenNewlineIsAtEndOfBufferDoesNotDropContent() {
    val file = temporaryFolder.newFile("newline-at-end.txt")
    val content = "1234567890\n" // 11 bytes
    file.writeText(content)

    // With maxChars = 5, the last 5 bytes are "7890\n". The newline is at the end of the buffer.
    // The reader must not discard the whole buffer to align to a non-existent next line.
    val result = readLogcatFromFile(file, maxChars = 5)
    assertThat(result).contains("[Android Studio omitted")
    assertThat(result).contains("7890\n")
  }

  @Test
  fun readLogcatFromFileWithZeroOrNegativeMaxCharsReturnsEmpty() {
    val file = temporaryFolder.newFile("test.txt")
    file.writeText("some logcat text")

    assertThat(readLogcatFromFile(file, maxChars = 0)).isEmpty()
    assertThat(readLogcatFromFile(file, maxChars = -10)).isEmpty()
  }

  @Test
  fun readLogcatFromFileWithNonExistentFileReturnsEmpty() {
    val nonExistent = File(temporaryFolder.root, "does-not-exist.txt")
    assertThat(readLogcatFromFile(nonExistent)).isEmpty()
  }

  @Test
  fun inMemoryLogcatSupportedViaSecondaryConstructor() {
    val testCase = AndroidTestCase("id", "method", "class", "package", logcat = "in-memory logcat")
    assertThat(testCase.logcatFile).isNull()
    assertThat(testCase.logcat).isEqualTo("in-memory logcat")
  }

  @Test
  fun inMemoryLogcatBoundedToMaxChars() {
    val largeLog = "X".repeat(MAX_LOGCAT_RETAINED_CHARS + 1000)
    val testCase = AndroidTestCase("id", "method", "class", "package", logcat = largeLog)
    assertThat(testCase.logcat.length).isAtMost(MAX_LOGCAT_RETAINED_CHARS)
    assertThat(testCase.logcat).contains("[Android Studio omitted")
    assertThat(testCase.logcat).contains("bytes of output to limit memory usage]")
  }

  @Test
  fun settingLogcatClearsLogcatFileAndSetsInMemoryLogcat() {
    val file = temporaryFolder.newFile("test.txt")
    file.writeText("file logcat")
    val testCase = AndroidTestCase("id", "method", "class", "package", logcatFile = file)
    assertThat(testCase.logcat).isEqualTo("file logcat")

    testCase.logcat = "updated in-memory logcat"
    assertThat(testCase.logcatFile).isNull()
    assertThat(testCase.logcat).isEqualTo("updated in-memory logcat")
  }

  @Test
  fun boundInMemoryLogcatWithZeroOrNegativeLimitReturnsEmpty() {
    assertThat(boundInMemoryLogcat("hello world", maxChars = 0)).isEmpty()
    assertThat(boundInMemoryLogcat("hello world", maxChars = -5)).isEmpty()
  }

  @Test
  fun boundInMemoryLogcatDoesNotSplitSurrogatePair() {
    val text = "abc\uD83D\uDE00def"
    val result = boundInMemoryLogcat(text, maxChars = 4)
    assertThat(result).doesNotContain("\uDE00")
    assertThat(result).endsWith("def")
  }
}
