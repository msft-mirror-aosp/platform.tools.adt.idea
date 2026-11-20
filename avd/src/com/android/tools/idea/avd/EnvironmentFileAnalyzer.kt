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
package com.android.tools.idea.avd

import com.intellij.openapi.diagnostic.thisLogger
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.text.Charsets.US_ASCII
import kotlin.text.Charsets.UTF_8
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

data class EnvironmentImage(val path: Path, val title: String, val isDefault: Boolean)

internal data class XmpMetadata(val title: String, val isDefault: Boolean)

/** Functions for scanning directories and extracting XMP metadata from emulator virtual scene environment image files. */
object EnvironmentFileAnalyzer {
  /**
   * Scans the specified directory for image files, parses their XMP metadata, and returns a list of [EnvironmentImage] records for files
   * that have a valid XMP title.
   */
  internal fun scanEnvironments(dir: Path, fileFilter: (Path) -> Boolean = { true }): List<EnvironmentImage> {
    if (!Files.exists(dir)) return emptyList()
    val result = mutableListOf<EnvironmentImage>()
    Files.list(dir).use { stream ->
      stream
        // The emulator supports PNG environment images (e.g. for custom environments), so isImageFile
        // returns true for PNGs, even though built-in environments are currently only JPEGs.
        .filter { fileFilter(it) && Files.isRegularFile(it) && isImageFile(it) }
        .forEach { file ->
          val metadata = readXmpMetadata(file)
          if (metadata != null) {
            result.add(EnvironmentImage(file, metadata.title, metadata.isDefault))
          }
        }
    }
    return result
  }

  private fun isImageFile(file: Path): Boolean {
    val name = file.fileName.toString()
    return name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true)
  }

  /**
   * Reads XMP metadata from the specified image file. Extracts the 'dc:title' and the custom 'studio:isDefault' properties. Returns null if
   * XMP metadata is missing or invalid.
   */
  internal fun readXmpMetadata(path: Path): XmpMetadata? {
    return try {
      Files.newInputStream(path).use { inputStream ->
        val xmpData = extractXmpData(inputStream) ?: return null
        parseXmpMetadata(xmpData)
      }
    } catch (e: Exception) {
      thisLogger().warn("Failed to read XMP metadata from $path", e)
      null
    }
  }

  private fun extractXmpData(inputStream: InputStream): ByteArray? {
    val bufferedStream = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
    bufferedStream.mark(8)
    val signature = ByteArray(8)
    val bytesRead = bufferedStream.readNBytes(signature, 0, 8)
    bufferedStream.reset()
    if (bytesRead < 2) return null

    if (signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte()) {
      return extractXmpFromJpeg(bufferedStream)
    }
    if (bytesRead == 8 && signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
      return extractXmpFromPng(bufferedStream)
    }
    return null
  }

  private fun extractXmpFromJpeg(inputStream: InputStream): ByteArray? {
    val header = ByteArray(2)
    if (inputStream.readNBytes(header, 0, 2) != 2 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) {
      return null
    }
    while (true) {
      val markerHeader = ByteArray(4)
      val readMarker = inputStream.readNBytes(markerHeader, 0, 4)
      if (readMarker != 4) break
      if (markerHeader[0] != 0xFF.toByte()) {
        break
      }
      val marker = markerHeader[1].toInt() and 0xFF
      val length = ((markerHeader[2].toInt() and 0xFF) shl 8) or (markerHeader[3].toInt() and 0xFF)
      if (length < 2) break

      val payloadLength = length - 2
      if (marker == 0xE1) { // APP1
        val payload = inputStream.readNBytes(payloadLength)
        if (payload.size == payloadLength) {
          val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(UTF_8)
          if (payload.size > xmpHeader.size && payload.sliceArray(xmpHeader.indices).contentEquals(xmpHeader)) {
            return payload.sliceArray(xmpHeader.size until payload.size)
          }
        }
      } else {
        skipNBytes(inputStream, payloadLength.toLong())
      }
    }
    return null
  }

  private fun extractXmpFromPng(inputStream: InputStream): ByteArray? {
    val signature = ByteArray(8)
    if (inputStream.readNBytes(signature, 0, 8) != 8) return null
    val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (!signature.contentEquals(expectedSig)) return null

    val buffer = ByteArray(4)
    while (true) {
      if (inputStream.readNBytes(buffer, 0, 4) != 4) break
      val length =
        ((buffer[0].toInt() and 0xFF) shl 24) or
          ((buffer[1].toInt() and 0xFF) shl 16) or
          ((buffer[2].toInt() and 0xFF) shl 8) or
          (buffer[3].toInt() and 0xFF)

      val typeBytes = ByteArray(4)
      if (inputStream.readNBytes(typeBytes, 0, 4) != 4) break
      val type = String(typeBytes, US_ASCII)

      if (type == "iTXt") {
        val data = inputStream.readNBytes(length)
        if (data.size != length) break
        val xmp = parseITXtChunk(data)
        if (xmp != null) {
          // Skip CRC
          skipNBytes(inputStream, 4)
          return xmp
        }
      } else {
        skipNBytes(inputStream, length.toLong())
      }

      // Read CRC
      if (inputStream.readNBytes(buffer, 0, 4) != 4) break

      if (type == "IEND") break
    }
    return null
  }

  private fun parseITXtChunk(data: ByteArray): ByteArray? {
    var pos = 0
    // 1. Keyword (null-terminated UTF-8/Latin-1)
    val keywordStart = pos
    while (pos < data.size && data[pos] != 0.toByte()) {
      pos++
    }
    if (pos >= data.size) return null
    val keyword = String(data, keywordStart, pos - keywordStart, UTF_8)
    pos++ // skip null byte

    if (keyword != "XML:com.adobe.xmp") return null

    if (pos + 2 > data.size) return null
    val compressionFlag = data[pos].toInt()
    pos++
    val compressionMethod = data[pos].toInt()
    pos++

    // Language tag (null-terminated ASCII)
    while (pos < data.size && data[pos] != 0.toByte()) {
      pos++
    }
    if (pos >= data.size) return null
    pos++ // Skip null byte.

    // Translated keyword (null-terminated UTF-8)
    while (pos < data.size && data[pos] != 0.toByte()) {
      pos++
    }
    if (pos >= data.size) return null
    pos++ // Skip null byte.

    val textLength = data.size - pos
    if (textLength <= 0) return null

    val textBytes = data.sliceArray(pos until data.size)
    return if (compressionFlag == 1) {
      if (compressionMethod != 0) return null
      decompressZlib(textBytes)
    } else {
      textBytes
    }
  }

  private fun decompressZlib(compressedData: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
      inflater.setInput(compressedData)
      val outputStream = ByteArrayOutputStream(compressedData.size)
      val buffer = ByteArray(1024)
      while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        if (count == 0 && inflater.needsInput()) break
        outputStream.write(buffer, 0, count)
      }
      return outputStream.toByteArray()
    } finally {
      inflater.end()
    }
  }

  private fun skipNBytes(inputStream: InputStream, n: Long) {
    var remaining = n
    while (remaining > 0) {
      val skipped = inputStream.skip(remaining)
      if (skipped > 0) {
        remaining -= skipped
      } else {
        if (inputStream.read() == -1) {
          break
        }
        remaining--
      }
    }
  }

  private fun parseXmpMetadata(xmpData: ByteArray): XmpMetadata? {
    val parser =
      KXmlParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(xmpData.inputStream(), UTF_8.name())
      }

    var insideTitle = false
    var insideAlt = false
    var title: String? = null
    var isDefault = false

    while (true) {
      val type = parser.next()
      if (type == XmlPullParser.END_DOCUMENT) break

      if (type == XmlPullParser.START_TAG) {
        val ns = parser.namespace
        val name = parser.name
        if (ns == "http://purl.org/dc/elements/1.1/" && name == "title") {
          insideTitle = true
        } else if (insideTitle && ns == "http://www.w3.org/1999/02/22-rdf-syntax-ns#" && name == "Alt") {
          insideAlt = true
        } else if (insideAlt && ns == "http://www.w3.org/1999/02/22-rdf-syntax-ns#" && name == "li") {
          title = parser.nextText()
        } else if (ns == "urn:androidemulator:metadata:private:1.0" && name == "isDefault") {
          isDefault = parser.nextText().toBoolean()
        }
      } else if (type == XmlPullParser.END_TAG) {
        val ns = parser.namespace
        val name = parser.name
        if (ns == "http://purl.org/dc/elements/1.1/" && name == "title") {
          insideTitle = false
          insideAlt = false
        } else if (ns == "http://www.w3.org/1999/02/22-rdf-syntax-ns#" && name == "Alt") {
          insideAlt = false
        }
      }
    }
    if (title != null) {
      return XmpMetadata(title, isDefault)
    }
    return null
  }

  /** Checks if the image is a 360 image by looking at its XMP metadata. */
  fun is360Image(path: Path): Boolean {
    return try {
      Files.newInputStream(path).use { inputStream ->
        val xmpData = extractXmpData(inputStream) ?: return false
        parseIs360(xmpData)
      }
    } catch (e: Exception) {
      thisLogger().warn("Failed to check if image is 360: $path", e)
      false
    }
  }

  private fun parseIs360(xmpData: ByteArray): Boolean {
    val parser =
      KXmlParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(xmpData.inputStream(), UTF_8.name())
      }

    while (true) {
      val type = parser.next()
      if (type == XmlPullParser.END_DOCUMENT) break

      if (type == XmlPullParser.START_TAG) {
        val ns = parser.namespace
        val name = parser.name
        if (ns == "http://ns.google.com/photos/1.0/panorama/") {
          if (name == "ProjectionType") {
            val text = parser.nextText()
            if (text == "equirectangular") {
              return true
            }
          } else if (name == "UsePanoramaViewer") {
            val text = parser.nextText()
            if (text.equals("true", ignoreCase = true)) {
              return true
            }
          }
        }
        // Also check attributes.
        for (i in 0 until parser.attributeCount) {
          val attrNamespace = parser.getAttributeNamespace(i)
          val attrName = parser.getAttributeName(i)
          val attrValue = parser.getAttributeValue(i)
          if (attrNamespace == "http://ns.google.com/photos/1.0/panorama/") {
            if (attrName == "ProjectionType" && attrValue == "equirectangular") {
              return true
            }
            if (attrName == "UsePanoramaViewer" && attrValue.equals("true", ignoreCase = true)) {
              return true
            }
          }
        }
      }
    }
    return false
  }

  /**
   * Checks if an image file without XMP metadata is a 360-degree candidate by checking for a 2:1 or greater aspect ratio and performing a
   * cyclic boundary test (left-right edge continuity using SSIM).
   */
  fun is360ImageCandidate(path: Path): Boolean {
    return try {
      Files.newInputStream(path).use { inputStream ->
        val image = ImageIO.read(inputStream) ?: return false
        is360ImageCandidate(image)
      }
    } catch (e: Exception) {
      thisLogger().warn("Failed to check if image is 360 candidate: $path", e)
      false
    }
  }

  private fun is360ImageCandidate(image: BufferedImage): Boolean =
    hasAtLeastTwoToOneAspectRatio(image.width, image.height) && hasCyclicBoundaryContinuity(image)

  private fun hasAtLeastTwoToOneAspectRatio(width: Int, height: Int): Boolean = width > 0 && height > 0 && width >= height * 2

  /** Checks if an image file has cyclic boundary continuity (left and right edges match seamlessly) using SSIM with the given threshold. */
  fun hasCyclicBoundaryContinuity(path: Path, threshold: Double = 0.6): Boolean {
    return try {
      Files.newInputStream(path).use { inputStream ->
        val image = ImageIO.read(inputStream) ?: return false
        hasCyclicBoundaryContinuity(image, threshold)
      }
    } catch (e: Exception) {
      thisLogger().warn("Failed to check cyclic boundary continuity: $path", e)
      false
    }
  }

  /** Checks if an image has cyclic boundary continuity (left and right edges match seamlessly) using SSIM with the given threshold. */
  fun hasCyclicBoundaryContinuity(image: BufferedImage, threshold: Double = 0.6): Boolean {
    if (image.width < 2) return false

    val leftStrip = image.getSubimage(0, 0, 1, image.height)
    val rightStrip = image.getSubimage(image.width - 1, 0, 1, image.height)

    val ssim = computeSsim(leftStrip, rightStrip)
    return ssim >= threshold
  }

  /**
   * Computes the Structural Similarity Index Measure (SSIM) between two images of equal dimensions, weighted across the Red, Green, and
   * Blue color channels using perceptual Rec. 601 weights.
   *
   * The returned value ranges from -1.0 to 1.0, where 1.0 indicates identical visual structure and color.
   *
   * @param img1 the first image strip
   * @param img2 the second image strip
   * @return the SSIM score between [img1] and [img2], or 0.0 if dimensions differ or are invalid
   */
  private fun computeSsim(img1: BufferedImage, img2: BufferedImage): Double {
    val w = img1.width
    val h = img1.height
    if (w != img2.width || h != img2.height || w <= 0 || h <= 0) return 0.0

    val ssimR = computeChannelSsim(img1, img2, shift = 16)
    val ssimG = computeChannelSsim(img1, img2, shift = 8)
    val ssimB = computeChannelSsim(img1, img2, shift = 0)

    // Combine channel SSIMs using Rec. 601 luma weights based on human visual sensitivity (Red: 29.9%, Green: 58.7%, Blue: 11.4%).
    return 0.299 * ssimR + 0.587 * ssimG + 0.114 * ssimB
  }

  private fun computeChannelSsim(img1: BufferedImage, img2: BufferedImage, shift: Int): Double {
    val w = img1.width
    val h = img1.height
    val n = (w * h).toDouble()

    var sumX = 0.0
    var sumY = 0.0
    var sumX2 = 0.0
    var sumY2 = 0.0
    var sumXY = 0.0

    for (y in 0 until h) {
      for (x in 0 until w) {
        val val1 = ((img1.getRGB(x, y) shr shift) and 0xFF).toDouble()
        val val2 = ((img2.getRGB(x, y) shr shift) and 0xFF).toDouble()
        sumX += val1
        sumY += val2
        sumX2 += val1 * val1
        sumY2 += val2 * val2
        sumXY += val1 * val2
      }
    }

    val meanX = sumX / n
    val meanY = sumY / n
    val varX = (sumX2 / n) - (meanX * meanX)
    val varY = (sumY2 / n) - (meanY * meanY)
    val covXY = (sumXY / n) - (meanX * meanY)

    val k1 = 0.01
    val k2 = 0.03
    val l = 255.0
    val c1 = (k1 * l) * (k1 * l)
    val c2 = (k2 * l) * (k2 * l)

    val numerator = (2.0 * meanX * meanY + c1) * (2.0 * covXY + c2)
    val denominator = (meanX * meanX + meanY * meanY + c1) * (varX + varY + c2)

    return numerator / denominator
  }

  /** Checks if the specified file is a 3D scene file (.obj). */
  fun is3dSceneFile(file: Path): Boolean = file.fileName?.toString()?.endsWith(".obj", ignoreCase = true) ?: false

  /** Checks if the specified file is a video file (.mp4 or .webm). */
  fun isVideoFile(file: Path): Boolean {
    val name = file.fileName.toString()
    return name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".webm", ignoreCase = true)
  }

  /** Checks if the specified video file (.mp4 or .webm) has 360-degree equirectangular metadata. */
  fun is360Video(path: Path): Boolean {
    if (!Files.isRegularFile(path)) return false
    return try {
      Files.newByteChannel(path).use { channel -> is360Video(channel) }
    } catch (e: Exception) {
      thisLogger().warn("Failed to check if video is 360: $path", e)
      false
    }
  }

  internal fun is360Video(channel: SeekableByteChannel): Boolean {
    val size = channel.size()
    if (size < 8) return false
    val boundedChannel = BoundedChannel(channel)
    val header = readChannelBytes(boundedChannel, 0, 4) ?: return false
    return if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) {
      is360Webm(boundedChannel, size)
    } else {
      scanIsobmffBoxes(boundedChannel, 0, size, inVideoTrack = false, depth = 0)
    }
  }

  private fun scanIsobmffBoxes(
    channel: SeekableByteChannel,
    start: Long,
    end: Long,
    inVideoTrack: Boolean,
    depth: Int,
  ): Boolean {
    if (depth > 16) return false
    var found = false
    forEachIsobmffBox(channel, start, end) { type, payloadStart, boxEnd ->
      found =
        when (type) {
          "moov",
          "trak",
          "minf",
          "stbl",
          "udta" -> scanIsobmffBoxes(channel, payloadStart, boxEnd, inVideoTrack, depth + 1)
          "mdia" -> {
            val isVideo = hasIsobmffVideoHandler(channel, payloadStart, boxEnd)
            scanIsobmffBoxes(channel, payloadStart, boxEnd, inVideoTrack = isVideo, depth + 1)
          }
          "stsd" -> inVideoTrack && scanIsobmffStsd(channel, payloadStart, boxEnd, depth + 1)
          "sv3d" -> isEquirectangularSv3d(channel, payloadStart, boxEnd)
          "vexu" -> isEquirectangularVexu(channel, payloadStart, boxEnd)
          "uuid" -> is360IsobmffUuid(channel, payloadStart, boxEnd)
          "XMP_" -> is360XmlPayload(channel, payloadStart, boxEnd - payloadStart, checkGpano = true)
          else -> false
        }
      found
    }
    return found
  }

  private fun hasIsobmffVideoHandler(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var isVideo = false
    forEachIsobmffBox(channel, start, end) { type, payloadStart, boxEnd ->
      if (type == "hdlr" && boxEnd - payloadStart >= 12) {
        isVideo = readAsciiString(channel, payloadStart + 8, 4) == "vide"
        true
      } else {
        false
      }
    }
    return isVideo
  }

  private fun scanIsobmffStsd(channel: SeekableByteChannel, payloadStart: Long, boxEnd: Long, depth: Int): Boolean {
    if (boxEnd - payloadStart <= 8) return false
    var found = false
    forEachIsobmffBox(channel, payloadStart + 8, boxEnd) { _, entryPayloadStart, entryEnd ->
      if (entryEnd - entryPayloadStart > VISUAL_SAMPLE_ENTRY_HEADER_BYTES) {
        found =
          scanIsobmffBoxes(
            channel,
            entryPayloadStart + VISUAL_SAMPLE_ENTRY_HEADER_BYTES,
            entryEnd,
            inVideoTrack = true,
            depth = depth + 1,
          )
      }
      found
    }
    return found
  }

  private fun isEquirectangularSv3d(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var is360 = false
    forEachIsobmffBox(channel, start, end) { type, projPayloadStart, projEnd ->
      if (type == "proj") {
        forEachIsobmffBox(channel, projPayloadStart, projEnd) { childType, childPayloadStart, childEnd ->
          if (childType == "equi" && childEnd - childPayloadStart >= 20) {
            val data = readChannelBytes(channel, childPayloadStart, 20)
            if (data != null && data[0].toInt() == 0 && (4 until 20).all { data[it].toInt() == 0 }) {
              is360 = true
            }
            true
          } else {
            false
          }
        }
      }
      is360
    }
    return is360
  }

  private fun isEquirectangularVexu(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var is360 = false
    forEachIsobmffBox(channel, start, end) { type, projPayloadStart, projEnd ->
      if (type == "proj") {
        forEachIsobmffBox(channel, projPayloadStart, projEnd) { childType, childPayloadStart, childEnd ->
          if (childType == "prji" && childEnd - childPayloadStart >= 8) {
            val data = readChannelBytes(channel, childPayloadStart, 8)
            if (data != null && (0 until 4).all { data[it].toInt() == 0 } && String(data, 4, 4, US_ASCII) == "equi") {
              is360 = true
            }
            true
          } else {
            false
          }
        }
      }
      is360
    }
    return is360
  }

  private fun is360IsobmffUuid(channel: SeekableByteChannel, payloadStart: Long, boxEnd: Long): Boolean {
    if (boxEnd - payloadStart <= 16) return false
    val uuid = readChannelBytes(channel, payloadStart, 16) ?: return false
    val xmlStart = payloadStart + 16
    val xmlLen = boxEnd - xmlStart
    return when {
      uuid.contentEquals(SPHERICAL_VIDEO_V1_UUID) -> is360XmlPayload(channel, xmlStart, xmlLen, checkGpano = false)
      uuid.contentEquals(ADOBE_XMP_UUID) -> is360XmlPayload(channel, xmlStart, xmlLen, checkGpano = true)
      else -> false
    }
  }

  private fun is360XmlPayload(channel: SeekableByteChannel, start: Long, length: Long, checkGpano: Boolean): Boolean {
    if (length <= 0 || length > MAX_METADATA_BYTES) return false
    val rawBytes = readChannelBytes(channel, start, length.toInt()) ?: return false
    var trimmedLen = rawBytes.size
    while (trimmedLen > 0 && rawBytes[trimmedLen - 1] == 0.toByte()) {
      trimmedLen--
    }
    if (trimmedLen == 0) return false
    val xmlBytes = if (trimmedLen == rawBytes.size) rawBytes else rawBytes.copyOf(trimmedLen)
    if (parseSphericalXml(xmlBytes, GSPHERICAL_NAMESPACE, "Spherical")) return true
    return checkGpano && parseSphericalXml(xmlBytes, GPANO_NAMESPACE, "UsePanoramaViewer")
  }

  private fun parseSphericalXml(xmlData: ByteArray, targetNamespace: String, sphericalFlagName: String): Boolean {
    return try {
      val parser =
        KXmlParser().apply {
          setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
          setInput(ByteArrayInputStream(xmlData), UTF_8.name())
        }

      var spherical: Boolean? = null
      var projectionType: String? = null
      var croppedWidth: Long? = null
      var croppedHeight: Long? = null
      var fullPanoWidth: Long? = null
      var fullPanoHeight: Long? = null

      fun recordTag(name: String, attrValue: String? = null) {
        when (name) {
          sphericalFlagName -> spherical = getPropertyValue(attrValue, parser).equals("true", ignoreCase = true)
          "ProjectionType" -> projectionType = getPropertyValue(attrValue, parser)
          "CroppedAreaImageWidthPixels" -> croppedWidth = getPropertyValue(attrValue, parser).toLongOrNull()
          "CroppedAreaImageHeightPixels" -> croppedHeight = getPropertyValue(attrValue, parser).toLongOrNull()
          "FullPanoWidthPixels" -> fullPanoWidth = getPropertyValue(attrValue, parser).toLongOrNull()
          "FullPanoHeightPixels" -> fullPanoHeight = getPropertyValue(attrValue, parser).toLongOrNull()
        }
      }

      while (true) {
        val type = parser.next()
        if (type == XmlPullParser.END_DOCUMENT) break
        if (type == XmlPullParser.START_TAG) {
          for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNamespace(i) == targetNamespace) {
              recordTag(parser.getAttributeName(i), parser.getAttributeValue(i))
            }
          }
          if (parser.namespace == targetNamespace) {
            recordTag(parser.name)
          }
        }
      }

      val isSpherical = spherical
      val projType = projectionType
      val cropW = croppedWidth
      val cropH = croppedHeight
      val fullW = fullPanoWidth
      val fullH = fullPanoHeight
      if (isSpherical == false) return false
      if (isSpherical != true && projType == null) return false
      if (projType != null && !projType.equals("equirectangular", ignoreCase = true)) return false
      if (cropW != null && fullW != null && fullW > 0 && cropW != fullW) return false
      if (cropH != null && fullH != null && fullH > 0 && cropH != fullH) return false
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun getPropertyValue(attrValue: String?, parser: KXmlParser): String = (attrValue ?: parser.nextText()).trim()

  private inline fun forEachIsobmffBox(
    channel: SeekableByteChannel,
    start: Long,
    end: Long,
    stopIfTrue: (type: String, payloadStart: Long, boxEnd: Long) -> Boolean,
  ) {
    var pos = start
    while (pos + 8 <= end) {
      val header = readChannelBytes(channel, pos, 8) ?: break
      val size32 = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
      val type = String(header, 4, 4, US_ASCII)
      val headerSize: Long
      val boxSize: Long
      when {
        size32 == 1L -> {
          if (pos + 16 > end) break
          val ext = readChannelBytes(channel, pos + 8, 8) ?: break
          val largeSize = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
          if (largeSize < 16L) break
          headerSize = 16L
          boxSize = largeSize
        }
        size32 == 0L -> {
          headerSize = 8L
          boxSize = end - pos
        }
        size32 < 8L -> break
        else -> {
          headerSize = 8L
          boxSize = size32
        }
      }
      val payloadStart = pos + headerSize
      val boxEnd = pos + boxSize
      if (boxEnd < payloadStart || boxEnd > end) break
      if (stopIfTrue(type, payloadStart, boxEnd)) return
      pos = boxEnd
    }
  }

  private fun is360Webm(channel: SeekableByteChannel, fileSize: Long): Boolean {
    var pos = 0L
    while (pos < fileSize) {
      val element = readEbmlElement(channel, pos, fileSize) ?: break
      if (element.id == EBML_ID_SEGMENT) {
        if (scanWebmSegment(channel, element.payloadStart, element.elementEnd)) return true
      }
      if (element.unknownSize) break
      pos = element.elementEnd
    }
    return false
  }

  private fun scanWebmSegment(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var pos = start
    var sawTracks = false
    while (pos < end) {
      val element = readEbmlElement(channel, pos, end) ?: break
      when (element.id) {
        EBML_ID_TRACKS -> {
          sawTracks = true
          if (scanWebmTracks(channel, element.payloadStart, element.elementEnd)) return true
        }
        EBML_ID_CLUSTER -> if (sawTracks || element.unknownSize) break
      }
      if (element.unknownSize) break
      pos = element.elementEnd
    }
    return false
  }

  private fun scanWebmTracks(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var pos = start
    while (pos < end) {
      val trackEntry = readEbmlElement(channel, pos, end) ?: break
      if (trackEntry.id == EBML_ID_TRACK_ENTRY) {
        if (scanWebmTrackEntry(channel, trackEntry.payloadStart, trackEntry.elementEnd)) return true
      }
      if (trackEntry.unknownSize) break
      pos = trackEntry.elementEnd
    }
    return false
  }

  private fun scanWebmTrackEntry(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var pos = start
    while (pos < end) {
      val element = readEbmlElement(channel, pos, end) ?: break
      if (element.id == EBML_ID_VIDEO) {
        if (scanWebmVideo(channel, element.payloadStart, element.elementEnd)) return true
      }
      if (element.unknownSize) break
      pos = element.elementEnd
    }
    return false
  }

  private fun scanWebmVideo(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var pos = start
    while (pos < end) {
      val element = readEbmlElement(channel, pos, end) ?: break
      if (element.id == EBML_ID_PROJECTION) {
        if (isEquirectangularWebmProjection(channel, element.payloadStart, element.elementEnd)) return true
      }
      if (element.unknownSize) break
      pos = element.elementEnd
    }
    return false
  }

  private fun isEquirectangularWebmProjection(channel: SeekableByteChannel, start: Long, end: Long): Boolean {
    var projectionType = 0L
    var projectionPrivate: ByteArray? = null
    var projectionPrivateInvalid = false
    var pos = start
    while (pos < end) {
      val element = readEbmlElement(channel, pos, end) ?: break
      if (element.unknownSize) break
      val size = element.elementEnd - element.payloadStart
      when (element.id) {
        EBML_ID_PROJECTION_TYPE -> {
          if (size in 1..8) {
            val bytes = readChannelBytes(channel, element.payloadStart, size.toInt()) ?: return false
            var v = 0L
            for (b in bytes) {
              v = (v shl 8) or (b.toLong() and 0xFF)
            }
            projectionType = v
          } else if (size == 0L) {
            projectionType = 0L
          } else {
            return false
          }
        }
        EBML_ID_PROJECTION_PRIVATE -> {
          if (size <= 1024) {
            projectionPrivate = readChannelBytes(channel, element.payloadStart, size.toInt()) ?: return false
          } else {
            projectionPrivateInvalid = true
          }
        }
      }
      pos = element.elementEnd
    }
    if (projectionType != WEBM_PROJECTION_TYPE_EQUIRECTANGULAR || projectionPrivateInvalid) return false
    val priv = projectionPrivate
    if (priv == null || priv.isEmpty()) return true
    // Spherical Video V2 EquirectangularProjectionPrivate: 1-byte version (0), 3-byte flags, and four 32-bit crop bounds (top, bottom,
    // left, right).
    return priv.size == 20 && priv[0].toInt() == 0 && (4 until 20).all { priv[it].toInt() == 0 }
  }

  private class EbmlElement(val id: Long, val payloadStart: Long, val elementEnd: Long, val unknownSize: Boolean)

  private fun readEbmlElement(channel: SeekableByteChannel, pos: Long, limit: Long): EbmlElement? {
    val available = minOf(12L, limit - pos).toInt()
    if (available < 2) return null
    val buf = readChannelBytes(channel, pos, available) ?: return null
    val b0 = buf[0].toInt() and 0xFF
    val idLen =
      when {
        b0 and 0x80 != 0 -> 1
        b0 and 0x40 != 0 -> 2
        b0 and 0x20 != 0 -> 3
        b0 and 0x10 != 0 -> 4
        else -> return null
      }
    if (idLen >= available) return null
    var id = 0L
    for (i in 0 until idLen) {
      id = (id shl 8) or (buf[i].toLong() and 0xFF)
    }

    val s0 = buf[idLen].toInt() and 0xFF
    if (s0 == 0) return null
    var sizeLen = 1
    while (sizeLen <= 8 && (s0 and (0x80 shr (sizeLen - 1))) == 0) {
      sizeLen++
    }
    if (idLen + sizeLen > available) return null
    val markerMask = 0x80 shr (sizeLen - 1)
    var dataSize = (s0 and (markerMask - 1)).toLong()
    var allOnes = (s0 and (markerMask - 1)) == (markerMask - 1)
    for (i in 1 until sizeLen) {
      val b = buf[idLen + i].toInt() and 0xFF
      dataSize = (dataSize shl 8) or b.toLong()
      if (b != 0xFF) allOnes = false
    }

    val payloadStart = pos + idLen + sizeLen
    val elementEnd = if (allOnes) limit else payloadStart + dataSize
    if (!allOnes && (elementEnd < payloadStart || elementEnd > limit)) return null
    return EbmlElement(id, payloadStart, elementEnd, allOnes)
  }

  private fun readAsciiString(channel: SeekableByteChannel, position: Long, length: Int): String? {
    val bytes = readChannelBytes(channel, position, length) ?: return null
    return String(bytes, US_ASCII)
  }

  private class BoundedChannel(
    private val delegate: SeekableByteChannel,
    private var remainingReads: Int = MAX_VIDEO_SCAN_READS,
  ) : SeekableByteChannel by delegate {
    fun tryConsumeRead(): Boolean {
      if (remainingReads <= 0) return false
      remainingReads--
      return true
    }
  }

  private fun readChannelBytes(channel: SeekableByteChannel, position: Long, length: Int): ByteArray? {
    if (length == 0) return ByteArray(0)
    if (channel is BoundedChannel && !channel.tryConsumeRead()) return null
    channel.position(position)
    val bytes = ByteArray(length)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) return null
    }
    return bytes
  }

  private const val VISUAL_SAMPLE_ENTRY_HEADER_BYTES = 78L
  private const val MAX_METADATA_BYTES = 1024 * 1024L
  private const val MAX_VIDEO_SCAN_READS = 100_000
  private const val GSPHERICAL_NAMESPACE = "http://ns.google.com/videos/1.0/spherical/"
  private const val GPANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/"
  private const val EBML_ID_SEGMENT = 0x18538067L
  private const val EBML_ID_TRACKS = 0x1654AE6BL
  private const val EBML_ID_CLUSTER = 0x1F43B675L
  private const val EBML_ID_TRACK_ENTRY = 0xAEL
  private const val EBML_ID_VIDEO = 0xE0L
  private const val EBML_ID_PROJECTION = 0x7670L
  private const val EBML_ID_PROJECTION_TYPE = 0x7671L
  private const val EBML_ID_PROJECTION_PRIVATE = 0x7672L
  private const val WEBM_PROJECTION_TYPE_EQUIRECTANGULAR = 1L

  private val SPHERICAL_VIDEO_V1_UUID =
    byteArrayOf(
      0xFF.toByte(),
      0xCC.toByte(),
      0x82.toByte(),
      0x63,
      0xF8.toByte(),
      0x55,
      0x4A,
      0x93.toByte(),
      0x88.toByte(),
      0x14,
      0x58,
      0x7A,
      0x02,
      0x52,
      0x1F,
      0xDD.toByte(),
    )

  private val ADOBE_XMP_UUID =
    byteArrayOf(
      0xBE.toByte(),
      0x7A,
      0xCF.toByte(),
      0xCB.toByte(),
      0x97.toByte(),
      0xA9.toByte(),
      0x42,
      0xE8.toByte(),
      0x9C.toByte(),
      0x71,
      0x99.toByte(),
      0x94.toByte(),
      0x91.toByte(),
      0xE3.toByte(),
      0xAF.toByte(),
      0xAC.toByte(),
    )

  /** Checks if the specified file is a valid Wavefront OBJ file. */
  fun isWavefrontObjFile(path: Path): Boolean {
    return try {
      val maxBytes = 4096
      val bytes =
        Files.newInputStream(path).use { stream ->
          val buffer = ByteArray(maxBytes)
          val read = stream.read(buffer)
          if (read <= 0) return false
          buffer.copyOf(read)
        }
      if (bytes.contains(0.toByte())) {
        return false
      }
      val text = String(bytes, UTF_8)
      val lines = text.lines()
      val linesToCheck = if (bytes.size == maxBytes) lines.dropLast(1) else lines

      var hasVertices = false
      var hasFaces = false
      var hasComments = false
      var hasOtherKeywords = false

      val knownKeywords =
        setOf(
          "v",
          "vt",
          "vn",
          "vp",
          "f",
          "g",
          "o",
          "s",
          "usemtl",
          "mtllib",
          "l",
          "p",
          "deg",
          "bmt",
          "step",
          "cstype",
          "parm",
          "trim",
          "hole",
          "scrv",
          "sp",
          "end",
          "con",
          "bevel",
          "c_tech",
          "d_tech",
          "lod",
          "shadow_obj",
          "trace_obj",
          "ctech",
          "dtech",
        )

      for (line in linesToCheck) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("#")) {
          hasComments = true
          continue
        }
        val parts = trimmed.split(Regex("\\s+"), 2)
        val keyword = parts[0]
        if (keyword in knownKeywords) {
          when (keyword) {
            "v" -> hasVertices = true
            "f" -> hasFaces = true
            else -> hasOtherKeywords = true
          }
        } else {
          if (!hasVertices && !hasFaces && !hasComments && !hasOtherKeywords) {
            return false
          }
        }
      }
      hasVertices || hasFaces || hasComments
    } catch (_: Exception) {
      false
    }
  }
}
