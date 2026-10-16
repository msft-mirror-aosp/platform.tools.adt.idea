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
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.system.CpuArch
import java.awt.Font
import java.awt.FontFormatException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/** Thrown when OTS (or the size limits of [FontSanitizer]) rejects font data. The font is proven invalid and can be discarded. */
class InvalidFontException(message: String) : IOException(message)

/**
 * Thrown when [FontSanitizer] cannot run, e.g. because the OTS native library is missing or broken. This says nothing about the font
 * itself.
 */
class FontSanitizerUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Validates and sanitizes untrusted font data (TrueType, OpenType, WOFF2) using OpenType Sanitiser (OTS). */
object FontSanitizer {
  private val LOG = Logger.getInstance(FontSanitizer::class.java)

  /**
   * Maximum allowed font stream size to prevent OutOfMemoryError on pathological or malicious inputs (32 MB).
   *
   * Rationale for 32 MB limit:
   * - Standard Latin/Western TrueType and OpenType fonts are typically under 2 MB.
   * - Extremely large variable fonts or comprehensive CJK (Chinese, Japanese, Korean) Pan-Unicode font files (such as Noto Sans CJK or
   *   Source Han Sans) rarely exceed 15-25 MB in size.
   * - A 32 MB upper limit accommodates virtually all legitimate production fonts while safely guarding against unbounded memory allocation
   *   from infinite streams or denial-of-service payloads.
   */
  const val MAX_FONT_SIZE = 32 * 1024 * 1024

  private val FONT_EXTENSIONS = setOf(".ttf", ".otf", ".woff", ".woff2", ".ttc", ".otc")

  /**
   * Checks whether the given [fileName] ends with a supported font extension (case-insensitive).
   *
   * Supported extensions: `.ttf`, `.otf`, `.woff`, `.woff2`, `.ttc`, `.otc`.
   */
  @JvmStatic fun isFontFile(fileName: String): Boolean = FONT_EXTENSIONS.any { fileName.endsWith(it, ignoreCase = true) }

  @Volatile private var isLibraryLoaded: Boolean = false

  init {
    // Object initialization is thread-safe and runs once, so the library is loaded at most once.
    try {
      System.load(getLibLocation().toAbsolutePath().toString())
      isLibraryLoaded = true
    } catch (t: Throwable) {
      LOG.warn("Failed to load OTS native library (ots_jni)", t)
    }
  }

  /**
   * Sanitizes the given font data with OTS.
   *
   * Note: OTS decompresses and transcodes WOFF and WOFF2 data into standard OpenType/TrueType tables, so the result can be loaded directly
   * via AWT's [Font.TRUETYPE_FONT].
   *
   * @param fontBytes the raw, untrusted font data.
   * @return the sanitized font data.
   * @throws InvalidFontException if the data is empty, exceeds [MAX_FONT_SIZE], or is rejected by OTS.
   * @throws FontSanitizerUnavailableException if the OTS native library is not available.
   */
  @JvmStatic
  @Throws(IOException::class)
  fun sanitize(fontBytes: ByteArray): ByteArray {
    if (fontBytes.isEmpty()) {
      throw InvalidFontException("Font data is empty")
    }
    if (fontBytes.size > MAX_FONT_SIZE) {
      throw InvalidFontException("Font data is ${fontBytes.size} bytes, which exceeds the maximum allowed size of $MAX_FONT_SIZE bytes")
    }
    if (!isLibraryLoaded) {
      throw FontSanitizerUnavailableException("OTS native library (ots_jni) is not loaded")
    }
    val sanitized =
      try {
        nSanitize(fontBytes)
      } catch (e: UnsatisfiedLinkError) {
        // The library loaded, but it does not provide the JNI entry point (e.g. a broken native build).
        // Disable sanitization instead of failing the same way on every call.
        isLibraryLoaded = false
        throw FontSanitizerUnavailableException("OTS native library (ots_jni) does not provide nSanitize", e)
      }
    return sanitized ?: throw InvalidFontException("Font data was rejected by the OpenType Sanitizer")
  }

  /**
   * Sanitizes the font at [source] and moves the sanitized font to [target], similar to [Files.move].
   *
   * The sanitized font is first written to a temporary file next to [target], which is then moved to [target] using [options]. [source] is
   * deleted only after [target] has been written. If this method throws, [target] is left untouched and [source] is not deleted.
   *
   * @throws InvalidFontException if the font at [source] is invalid.
   * @throws FontSanitizerUnavailableException if the OTS native library is not available.
   * @throws IOException if [source] and [target] are the same file, or if reading or writing fails. Like [Files.move], this includes
   *   [java.nio.file.FileAlreadyExistsException] if [target] exists and [java.nio.file.StandardCopyOption.REPLACE_EXISTING] is not given.
   */
  @JvmStatic
  @Throws(IOException::class)
  fun move(source: Path, target: Path, vararg options: CopyOption) {
    if (isSameFile(source, target)) {
      throw IOException("Source and target are the same file: $source")
    }
    val sanitized = sanitize(readFontBytes(source))
    val targetName = target.fileName ?: throw IOException("Target is not a file: $target")
    val targetDir = target.toAbsolutePath().parent ?: throw IOException("Target has no parent directory: $target")
    val tempFile = Files.createTempFile(targetDir, "$targetName.", ".tmp")
    try {
      Files.write(tempFile, sanitized)
      Files.move(tempFile, target, *options)
    } finally {
      Files.deleteIfExists(tempFile)
    }
    try {
      Files.deleteIfExists(source)
    } catch (e: IOException) {
      LOG.warn("Failed to delete $source after moving the sanitized font to $target", e)
    }
  }

  /**
   * Sanitizes and creates an AWT [Font] from the font file at [path].
   *
   * @param path the font file on disk.
   * @return the created [Font].
   * @throws InvalidFontException if the font is invalid.
   * @throws FontSanitizerUnavailableException if the OTS native library is not available.
   * @throws IOException if the file cannot be read, or if AWT cannot create a font from the sanitized data.
   */
  @JvmStatic
  @Throws(IOException::class)
  fun createFont(path: Path): Font {
    if (!Files.isRegularFile(path)) {
      throw NoSuchFileException(path.toString(), null, "Not a regular file")
    }
    return createFont(path.toString(), readFontBytes(path))
  }

  /**
   * Sanitizes and creates an AWT [Font] from the given [virtualFile].
   *
   * @param virtualFile the virtual file containing font data.
   * @return the created [Font].
   * @throws InvalidFontException if the font is invalid.
   * @throws FontSanitizerUnavailableException if the OTS native library is not available.
   * @throws IOException if the file cannot be read, or if AWT cannot create a font from the sanitized data.
   */
  @JvmStatic
  @Throws(IOException::class)
  fun createFont(virtualFile: VirtualFile): Font {
    if (!virtualFile.isValid || virtualFile.isDirectory) {
      throw NoSuchFileException(virtualFile.toString(), null, "Not a valid file")
    }
    return createFont(virtualFile.toString(), readFontBytes(virtualFile))
  }

  private fun createFont(description: String, fontBytes: ByteArray): Font {
    val sanitized = sanitize(fontBytes)
    return try {
      // For a font collection (.ttc or .otc), only the first font in the collection is returned. Use Font.createFonts if all of them are
      // ever needed.
      Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(sanitized))
    } catch (e: FontFormatException) {
      // OTS accepted the font, so this is an AWT limitation rather than proof that the font is invalid.
      throw IOException("Cannot create a font from the sanitized data of $description", e)
    }
  }

  /** Reads the font at [path], rejecting files larger than [MAX_FONT_SIZE] before reading them into memory. */
  private fun readFontBytes(path: Path): ByteArray {
    val size = Files.size(path)
    if (size > MAX_FONT_SIZE) {
      throw InvalidFontException("Font file is $size bytes, which exceeds the maximum allowed size of $MAX_FONT_SIZE bytes: $path")
    }
    return Files.readAllBytes(path)
  }

  /** Reads the font in [virtualFile], rejecting files larger than [MAX_FONT_SIZE] before reading them into memory. */
  private fun readFontBytes(virtualFile: VirtualFile): ByteArray {
    val size = virtualFile.length
    if (size > MAX_FONT_SIZE) {
      throw InvalidFontException("Font file is $size bytes, which exceeds the maximum allowed size of $MAX_FONT_SIZE bytes: $virtualFile")
    }
    return virtualFile.inputStream.use { it.readAllBytes() }
  }

  private fun isSameFile(source: Path, target: Path): Boolean {
    // Two paths can only refer to the same file if it exists. Files.isSameFile also handles other spellings of the same file, e.g.
    // redundant path segments, different case on a case-insensitive file system, or a symbolic link.
    return Files.exists(source) && Files.exists(target) && Files.isSameFile(source, target)
  }

  private fun getLibLocation(): Path {
    val libName = System.mapLibraryName("ots_jni")
    val homePath = Paths.get(PathManager.getHomePath())

    // Installed Studio: each platform distribution packages only its own library, directly in the native directory (see ots-bundle in
    // prebuilts/tools/common/ots/BUILD).
    val installedLib = homePath.resolve("plugins/android/resources/native").resolve(libName)
    if (Files.exists(installedLib)) {
      return installedLib
    }
    if (!StudioPathManager.isRunningFromSources()) {
      throw UnsatisfiedLinkError("Unable to locate $libName at $installedLib")
    }

    // Running from sources: the prebuilts contain the libraries for all platforms, in a directory per platform.
    val platformDir =
      when {
        SystemInfo.isLinux -> "linux_x86_64"
        SystemInfo.isMac -> if (CpuArch.isArm64()) "darwin_arm64" else "darwin_x86_64"
        SystemInfo.isWindows -> "windows_x86_64"
        else -> throw UnsatisfiedLinkError("OTS native library is not available for ${SystemInfo.OS_NAME}")
      }
    val prebuiltsLib = StudioPathManager.resolvePathFromSourcesRoot("prebuilts/tools/common/ots").resolve(platformDir).resolve(libName)
    if (Files.exists(prebuiltsLib)) {
      return prebuiltsLib
    }
    throw UnsatisfiedLinkError("Unable to locate $libName at $installedLib or $prebuiltsLib")
  }

  @JvmStatic private external fun nSanitize(input: ByteArray): ByteArray?
}
