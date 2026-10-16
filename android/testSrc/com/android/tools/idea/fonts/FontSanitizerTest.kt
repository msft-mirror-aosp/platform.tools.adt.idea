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
package com.android.tools.idea.fonts

import com.android.tools.idea.util.StudioPathManager
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Font
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FontSanitizerTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private val validFontFile: File
    get() = StudioPathManager.resolvePathFromSourcesRoot("tools/adt/idea/android/testData/fonts/Roboto-Black.ttf").toFile()

  @Before
  fun setUp() {
    assumeTrue("Test font Roboto-Black.ttf must exist", validFontFile.exists())
  }

  @Test
  fun testNativeLibraryIsAvailable() {
    // The OTS prebuilts are test data for this test, so the sanitizer must be available on all supported platforms.
    // Every other test also fails with FontSanitizerUnavailableException if it is not.
    FontSanitizer.sanitize(validFontFile.readBytes())
  }

  @Test
  fun testSanitizeValidFontBytes() {
    val sanitized = FontSanitizer.sanitize(validFontFile.readBytes())
    assertThat(sanitized).isNotEmpty()
    assertThat(Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(sanitized))).isNotNull()
  }

  @Test
  fun testSanitizeInvalidBytesThrowsInvalidFontException() {
    val invalidFontBytes = "This is definitely not a font".toByteArray(Charsets.UTF_8)
    assertThrows(InvalidFontException::class.java) { FontSanitizer.sanitize(invalidFontBytes) }
  }

  @Test
  fun testSanitizeEmptyBytesThrowsInvalidFontException() {
    assertThrows(InvalidFontException::class.java) { FontSanitizer.sanitize(ByteArray(0)) }
  }

  @Test
  fun testSanitizeOversizedBytesThrowsInvalidFontException() {
    assertThrows(InvalidFontException::class.java) { FontSanitizer.sanitize(ByteArray(FontSanitizer.MAX_FONT_SIZE + 1)) }
  }

  @Test
  fun testCreateFontFromValidFile() {
    assertThat(FontSanitizer.createFont(validFontFile.toPath())).isNotNull()
  }

  @Test
  fun testCreateFontFromInvalidFileThrowsInvalidFontException() {
    val invalidFile = temporaryFolder.newFile("invalid.ttf")
    invalidFile.writeText("corrupt font content")
    assertThrows(InvalidFontException::class.java) { FontSanitizer.createFont(invalidFile.toPath()) }
  }

  @Test
  fun testCreateFontFromMissingFileThrowsNoSuchFileException() {
    val nonExistent = File(temporaryFolder.root, "non_existent.ttf")
    assertThrows(NoSuchFileException::class.java) { FontSanitizer.createFont(nonExistent.toPath()) }
  }

  @Test
  fun testCreateFontFromDirectoryThrowsNoSuchFileException() {
    assertThrows(NoSuchFileException::class.java) { FontSanitizer.createFont(temporaryFolder.root.toPath()) }
  }

  @Test
  fun testCreateFontFromOversizedFileThrowsInvalidFontException() {
    val oversizedFile = temporaryFolder.newFile("oversized.ttf")
    RandomAccessFile(oversizedFile, "rw").use { it.setLength(FontSanitizer.MAX_FONT_SIZE + 1L) }
    assertThrows(InvalidFontException::class.java) { FontSanitizer.createFont(oversizedFile.toPath()) }
  }

  @Test
  fun testCreateFontFromValidVirtualFile() {
    val mockVirtualFile = mock(VirtualFile::class.java)
    `when`(mockVirtualFile.isValid).thenReturn(true)
    `when`(mockVirtualFile.isDirectory).thenReturn(false)
    `when`(mockVirtualFile.length).thenReturn(validFontFile.length())
    `when`(mockVirtualFile.inputStream).thenAnswer { validFontFile.inputStream() }

    assertThat(FontSanitizer.createFont(mockVirtualFile)).isNotNull()
  }

  @Test
  fun testCreateFontFromInvalidVirtualFileThrowsInvalidFontException() {
    val content = "corrupt font content".toByteArray(Charsets.UTF_8)
    val mockVirtualFile = mock(VirtualFile::class.java)
    `when`(mockVirtualFile.isValid).thenReturn(true)
    `when`(mockVirtualFile.isDirectory).thenReturn(false)
    `when`(mockVirtualFile.length).thenReturn(content.size.toLong())
    `when`(mockVirtualFile.inputStream).thenReturn(ByteArrayInputStream(content))

    assertThrows(InvalidFontException::class.java) { FontSanitizer.createFont(mockVirtualFile) }
  }

  @Test
  fun testMoveValidFont() {
    val source = temporaryFolder.newFile("download.ttf.tmp")
    validFontFile.copyTo(source, overwrite = true)
    val target = File(temporaryFolder.root, "font.ttf")

    FontSanitizer.move(source.toPath(), target.toPath())

    assertThat(target.exists()).isTrue()
    assertThat(target.readBytes()).isEqualTo(FontSanitizer.sanitize(validFontFile.readBytes()))
    assertThat(source.exists()).isFalse()
    assertThat(temporaryFolder.root.list()!!.toList()).containsExactly("font.ttf")
  }

  @Test
  fun testMoveValidFontReplacesExistingTarget() {
    val source = temporaryFolder.newFile("download.ttf.tmp")
    validFontFile.copyTo(source, overwrite = true)
    val target = temporaryFolder.newFile("font.ttf")
    target.writeText("old font")

    FontSanitizer.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

    assertThat(target.readBytes()).isEqualTo(FontSanitizer.sanitize(validFontFile.readBytes()))
    assertThat(source.exists()).isFalse()
  }

  @Test
  fun testMoveWithoutReplaceExistingKeepsExistingTarget() {
    val source = temporaryFolder.newFile("download.ttf.tmp")
    validFontFile.copyTo(source, overwrite = true)
    val target = temporaryFolder.newFile("font.ttf")
    target.writeText("old font")

    assertThrows(FileAlreadyExistsException::class.java) { FontSanitizer.move(source.toPath(), target.toPath()) }

    assertThat(target.readText()).isEqualTo("old font")
    assertThat(source.exists()).isTrue()
    assertThat(temporaryFolder.root.list()!!.toList()).containsExactly("download.ttf.tmp", "font.ttf")
  }

  @Test
  fun testMoveInvalidFontKeepsExistingTarget() {
    val source = temporaryFolder.newFile("download.ttf.tmp")
    source.writeText("corrupt font content")
    val target = temporaryFolder.newFile("font.ttf")
    target.writeText("old font")

    assertThrows(InvalidFontException::class.java) {
      FontSanitizer.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    assertThat(target.readText()).isEqualTo("old font")
    assertThat(source.exists()).isTrue()
    assertThat(temporaryFolder.root.list()!!.toList()).containsExactly("download.ttf.tmp", "font.ttf")
  }

  @Test
  fun testMoveInvalidFontDoesNotCreateTarget() {
    val source = temporaryFolder.newFile("download.ttf.tmp")
    source.writeText("corrupt font content")
    val target = File(temporaryFolder.root, "font.ttf")

    assertThrows(InvalidFontException::class.java) { FontSanitizer.move(source.toPath(), target.toPath()) }

    assertThat(target.exists()).isFalse()
  }

  @Test
  fun testMoveOversizedFontThrowsInvalidFontException() {
    val source = temporaryFolder.newFile("oversized.ttf.tmp")
    RandomAccessFile(source, "rw").use { it.setLength(FontSanitizer.MAX_FONT_SIZE + 1L) }
    val target = File(temporaryFolder.root, "font.ttf")

    assertThrows(InvalidFontException::class.java) { FontSanitizer.move(source.toPath(), target.toPath()) }

    assertThat(target.exists()).isFalse()
  }

  @Test
  fun testMoveToRootIsRejected() {
    val source = temporaryFolder.newFile("download.ttf.tmp")
    validFontFile.copyTo(source, overwrite = true)
    val root = temporaryFolder.root.toPath().root

    assertThrows(IOException::class.java) { FontSanitizer.move(source.toPath(), root, StandardCopyOption.REPLACE_EXISTING) }

    assertThat(source.exists()).isTrue()
  }

  @Test
  fun testMoveToSameFileIsRejected() {
    val font = temporaryFolder.newFile("font.ttf")
    validFontFile.copyTo(font, overwrite = true)
    val content = font.readBytes()

    assertThrows(IOException::class.java) {
      FontSanitizer.move(font.toPath(), font.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    assertThat(font.readBytes()).isEqualTo(content)

    // Non-normalized path with redundant dot segments
    val dotFile = File(font.parentFile, "./${font.name}")
    assertThrows(IOException::class.java) {
      FontSanitizer.move(font.toPath(), dotFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    assertThat(font.readBytes()).isEqualTo(content)
  }

  @Test
  fun testMoveToSymlinkOfSameFileIsRejected() {
    val font = temporaryFolder.newFile("font.ttf")
    validFontFile.copyTo(font, overwrite = true)
    val content = font.readBytes()
    val symlinkFile = File(temporaryFolder.root, "symlink.ttf")
    try {
      Files.createSymbolicLink(symlinkFile.toPath(), font.toPath())
    } catch (e: Exception) {
      assumeNoException("Symbolic links are not supported or permitted in this environment", e)
    }

    assertThrows(IOException::class.java) {
      FontSanitizer.move(font.toPath(), symlinkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    assertThat(font.readBytes()).isEqualTo(content)
  }

  @Test
  fun testIsFontFile() {
    assertThat(FontSanitizer.isFontFile("font.ttf")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.otf")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.woff")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.woff2")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.ttc")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.otc")).isTrue()

    // Case-insensitivity
    assertThat(FontSanitizer.isFontFile("FONT.TTF")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.OTF")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.Woff2")).isTrue()
    assertThat(FontSanitizer.isFontFile("font.OTC")).isTrue()

    // Non-font extensions
    assertThat(FontSanitizer.isFontFile("directory.xml")).isFalse()
    assertThat(FontSanitizer.isFontFile("font.json")).isFalse()
    assertThat(FontSanitizer.isFontFile("icon.svg")).isFalse()
    assertThat(FontSanitizer.isFontFile("image.png")).isFalse()
    assertThat(FontSanitizer.isFontFile("")).isFalse()
  }
}
