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

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin
import kotlin.text.Charsets.US_ASCII
import kotlin.text.Charsets.UTF_8
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [EnvironmentFileAnalyzer]. */
class EnvironmentFileAnalyzerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testReadXmpMetadata() {
    val title = "My Custom Title"
    val jpegBytes = createMockJpegWithXmp(title, isDefault = true)

    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)

    val metadata = EnvironmentFileAnalyzer.readXmpMetadata(tempFile)
    assertThat(metadata).isNotNull()
    assertThat(metadata!!.title).isEqualTo(title)
    assertThat(metadata.isDefault).isTrue()
  }

  @Test
  fun testReadXmpMetadataNotDefault() {
    val title = "Another Title"
    val jpegBytes = createMockJpegWithXmp(title, isDefault = false)

    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)

    val metadata = EnvironmentFileAnalyzer.readXmpMetadata(tempFile)
    assertThat(metadata).isNotNull()
    assertThat(metadata!!.title).isEqualTo(title)
    assertThat(metadata.isDefault).isFalse()
  }

  @Test
  fun testReadXmpMetadataFailsGracefully() {
    val tempFile = tempFolder.newFile("invalid.jpg").toPath()
    Files.write(tempFile, byteArrayOf(1, 2, 3, 4))
    assertThat(EnvironmentFileAnalyzer.readXmpMetadata(tempFile)).isNull()
  }

  @Test
  fun testScanEnvironments() {
    val tempDir = tempFolder.newFolder("scan_test").toPath()
    val file1 = tempDir.resolve("env1.jpg")
    val file2 = tempDir.resolve("env2.jpg")
    val ignored = tempDir.resolve("ignored.jpg")
    val nonImage = tempDir.resolve("notes.txt")

    Files.write(file1, createMockJpegWithXmp("Env 1", isDefault = false))
    Files.write(file2, createMockJpegWithXmp("Env 2", isDefault = true))
    Files.write(ignored, createMockJpegWithXmp("Ignored", isDefault = false))
    Files.write(nonImage, "some text".toByteArray(UTF_8))

    val allEnvs = EnvironmentFileAnalyzer.scanEnvironments(tempDir)
    assertThat(allEnvs.map { it.title }).containsExactly("Env 1", "Env 2", "Ignored")

    val filteredEnvs = EnvironmentFileAnalyzer.scanEnvironments(tempDir) { it.fileName.toString() != "ignored.jpg" }
    assertThat(filteredEnvs.map { it.title }).containsExactly("Env 1", "Env 2")
  }

  @Test
  fun testIs360Image_withProjectionTypeElement() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
         <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
        </rdf:Description>
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpegBytes = createMockJpegWithXml(xml)
    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isTrue()
  }

  @Test
  fun testIs360Image_withProjectionTypeAttribute() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpegBytes = createMockJpegWithXml(xml)
    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isTrue()
  }

  @Test
  fun testIs360Image_withUsePanoramaViewerElement() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
         <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
        </rdf:Description>
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpegBytes = createMockJpegWithXml(xml)
    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isTrue()
  }

  @Test
  fun testIs360Image_withUsePanoramaViewerAttribute() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:UsePanoramaViewer="true" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpegBytes = createMockJpegWithXml(xml)
    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isTrue()
  }

  @Test
  fun testIs360Image_falseForNormalImage() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
         <dc:title>Some Title</dc:title>
        </rdf:Description>
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpegBytes = createMockJpegWithXml(xml)
    val tempFile = tempFolder.newFile("test.jpg").toPath()
    Files.write(tempFile, jpegBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isFalse()
  }

  private fun createMockJpegWithXml(xml: String): ByteArray {
    val xmlBytes = xml.toByteArray(UTF_8)
    val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(UTF_8)
    val payloadLength = xmpHeader.size + xmlBytes.size
    val totalLength = payloadLength + 2

    val jpeg = ByteArray(2 + 4 + payloadLength + 2)
    jpeg[0] = 0xFF.toByte()
    jpeg[1] = 0xD8.toByte() // SOI

    jpeg[2] = 0xFF.toByte()
    jpeg[3] = 0xE1.toByte() // APP1
    jpeg[4] = ((totalLength shr 8) and 0xFF).toByte()
    jpeg[5] = (totalLength and 0xFF).toByte()

    System.arraycopy(xmpHeader, 0, jpeg, 6, xmpHeader.size)
    System.arraycopy(xmlBytes, 0, jpeg, 6 + xmpHeader.size, xmlBytes.size)

    val eoiIdx = 6 + payloadLength
    jpeg[eoiIdx] = 0xFF.toByte()
    jpeg[eoiIdx + 1] = 0xD9.toByte() // EOI

    return jpeg
  }

  companion object {
    internal fun createMockJpegWithXmp(title: String, isDefault: Boolean, is360: Boolean = false): ByteArray {
      val androidemulatorAttribute = if (isDefault) " xmlns:androidemulator=\"urn:androidemulator:metadata:private:1.0\"" else ""
      val gpanoAttribute = if (is360) " xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\"" else ""
      val defaultTag = if (isDefault) "\n         <androidemulator:isDefault>true</androidemulator:isDefault>" else ""
      val gpanoTag = if (is360) "\n         <GPano:ProjectionType>equirectangular</GPano:ProjectionType>" else ""

      val xml =
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"$androidemulatorAttribute$gpanoAttribute>
           <dc:title>
            <rdf:Alt>
             <rdf:li xml:lang="x-default">$title</rdf:li>
            </rdf:Alt>
           </dc:title>$defaultTag$gpanoTag
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
      """
          .trimIndent()

      val xmlBytes = xml.toByteArray(UTF_8)
      val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(UTF_8)
      val payloadLength = xmpHeader.size + xmlBytes.size
      val totalLength = payloadLength + 2

      val jpeg = ByteArray(2 + 4 + payloadLength + 2)
      jpeg[0] = 0xFF.toByte()
      jpeg[1] = 0xD8.toByte() // SOI

      jpeg[2] = 0xFF.toByte()
      jpeg[3] = 0xE1.toByte() // APP1
      jpeg[4] = ((totalLength shr 8) and 0xFF).toByte()
      jpeg[5] = (totalLength and 0xFF).toByte()

      System.arraycopy(xmpHeader, 0, jpeg, 6, xmpHeader.size)
      System.arraycopy(xmlBytes, 0, jpeg, 6 + xmpHeader.size, xmlBytes.size)

      val eoiIdx = 6 + payloadLength
      jpeg[eoiIdx] = 0xFF.toByte()
      jpeg[eoiIdx + 1] = 0xD9.toByte() // EOI

      return jpeg
    }

    internal fun mp4Box(type: String, vararg children: ByteArray): ByteArray {
      val payloadSize = children.sumOf { it.size }
      val buffer = ByteBuffer.allocate(8 + payloadSize).order(ByteOrder.BIG_ENDIAN)
      buffer.putInt(8 + payloadSize)
      buffer.put(type.toByteArray(US_ASCII))
      for (child in children) {
        buffer.put(child)
      }
      return buffer.array()
    }

    internal fun createMockMp4WithSv3d(
      handlerType: String = "vide",
      projectionFourcc: String = "equi",
      bounds: IntArray = intArrayOf(0, 0, 0, 0),
    ): ByteArray {
      val projChild =
        if (projectionFourcc == "equi") {
          val equiPayload =
            ByteBuffer.allocate(20)
              .order(ByteOrder.BIG_ENDIAN)
              .putInt(0) // version 0, flags 0
              .putInt(bounds[0])
              .putInt(bounds[1])
              .putInt(bounds[2])
              .putInt(bounds[3])
              .array()
          mp4Box("equi", equiPayload)
        } else {
          val cbmpPayload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).putInt(0).array()
          mp4Box(projectionFourcc, cbmpPayload)
        }
      val sv3d = mp4Box("sv3d", mp4Box("proj", projChild))
      return createMockMp4WithVisualSampleChildren(handlerType, sampleEntryVersion = 0, sv3d)
    }

    internal fun createMockMp4WithVisualSampleChildren(
      handlerType: String = "vide",
      sampleEntryVersion: Int = 0,
      vararg sampleChildren: ByteArray,
    ): ByteArray {
      val ftyp = mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(US_ASCII))
      val hdlrPayload =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).put(handlerType.toByteArray(US_ASCII)).array()
      val hdlr = mp4Box("hdlr", hdlrPayload)
      val sampleHeader = ByteArray(78)
      sampleHeader[8] = ((sampleEntryVersion shr 8) and 0xFF).toByte()
      sampleHeader[9] = (sampleEntryVersion and 0xFF).toByte()
      val avc1 = mp4Box("avc1", sampleHeader, *sampleChildren)
      val stsdHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(1).array()
      val stsd = mp4Box("stsd", stsdHeader, avc1)
      val moov = mp4Box("moov", mp4Box("trak", mp4Box("mdia", hdlr, mp4Box("minf", mp4Box("stbl", stsd)))))
      return ftyp + moov
    }

    internal fun createMockMp4WithSphericalV1Uuid(xml: String, trailingZeros: Int = 0): ByteArray {
      val uuid =
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
      val ftyp = mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(US_ASCII))
      val uuidBox = mp4Box("uuid", uuid, xml.toByteArray(UTF_8), ByteArray(trailingZeros))
      val moov = mp4Box("moov", mp4Box("trak", uuidBox))
      return ftyp + moov
    }

    internal fun createMockWebm(
      projectionType: Int? = 1,
      projectionPrivate: ByteArray? = null,
      unknownSegmentSize: Boolean = false,
    ): ByteArray {
      fun ebmlElement(id: ByteArray, vararg payloads: ByteArray): ByteArray {
        val size = payloads.sumOf { it.size }
        val sizeBytes =
          if (size < 127) {
            byteArrayOf((0x80 or size).toByte())
          } else {
            byteArrayOf((0x40 or (size shr 8)).toByte(), (size and 0xFF).toByte())
          }
        val out = ByteArrayOutputStream()
        out.write(id)
        out.write(sizeBytes)
        for (p in payloads) {
          out.write(p)
        }
        return out.toByteArray()
      }

      val ebmlHeader =
        ebmlElement(
          byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
          ebmlElement(byteArrayOf(0x42, 0x82.toByte()), "webm".toByteArray(US_ASCII)),
        )

      val videoChildren = mutableListOf<ByteArray>()
      if (projectionType != null) {
        val projChildren = mutableListOf<ByteArray>()
        projChildren.add(ebmlElement(byteArrayOf(0x76, 0x71), byteArrayOf(projectionType.toByte())))
        if (projectionPrivate != null) {
          projChildren.add(ebmlElement(byteArrayOf(0x76, 0x72), projectionPrivate))
        }
        videoChildren.add(ebmlElement(byteArrayOf(0x76, 0x70), *projChildren.toTypedArray()))
      }
      val video = ebmlElement(byteArrayOf(0xE0.toByte()), *videoChildren.toTypedArray())
      val trackEntry = ebmlElement(byteArrayOf(0xAE.toByte()), video)
      val tracks = ebmlElement(byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), trackEntry)
      val cluster = ebmlElement(byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75), byteArrayOf(0xE7.toByte(), 0x81.toByte(), 0x00))

      val segmentId = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
      val segment =
        if (unknownSegmentSize) {
          val unknownSize =
            byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
          segmentId + unknownSize + tracks + cluster
        } else {
          ebmlElement(segmentId, tracks, cluster)
        }
      return ebmlHeader + segment
    }
  }

  @Test
  fun testIs360Image_pngUncompressed() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val pngBytes = createMockPngWithXml(xml, compressed = false)
    val tempFile = tempFolder.newFile("test.png").toPath()
    Files.write(tempFile, pngBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isTrue()
  }

  @Test
  fun testIs360Image_pngCompressed() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
         <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
        </rdf:Description>
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val pngBytes = createMockPngWithXml(xml, compressed = true)
    val tempFile = tempFolder.newFile("test.png").toPath()
    Files.write(tempFile, pngBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isTrue()
  }

  @Test
  fun testIs360Image_pngNot360() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
         <dc:title>Just a Title</dc:title>
        </rdf:Description>
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val pngBytes = createMockPngWithXml(xml, compressed = false)
    val tempFile = tempFolder.newFile("test.png").toPath()
    Files.write(tempFile, pngBytes)
    assertThat(EnvironmentFileAnalyzer.is360Image(tempFile)).isFalse()
  }

  private fun createMockPngWithXml(xml: String, compressed: Boolean = false): ByteArray {
    val bos = ByteArrayOutputStream()
    // Signature
    bos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    // IHDR
    writePngChunk(bos, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))

    // iTXt
    val chunkBos = ByteArrayOutputStream()
    chunkBos.write("XML:com.adobe.xmp\u0000".toByteArray(UTF_8))
    if (compressed) {
      chunkBos.write(1) // compression flag
      chunkBos.write(0) // compression method
      chunkBos.write(0) // language tag null
      chunkBos.write(0) // translated keyword null
      val deflatedBytes = ByteArray(1024)
      val compressedBos = ByteArrayOutputStream()
      Deflater().use { deflater ->
        deflater.setInput(xml.toByteArray(UTF_8))
        deflater.finish()
        while (!deflater.finished()) {
          val count = deflater.deflate(deflatedBytes)
          compressedBos.write(deflatedBytes, 0, count)
        }
      }
      chunkBos.write(compressedBos.toByteArray())
    } else {
      chunkBos.write(0) // compression flag
      chunkBos.write(0) // compression method
      chunkBos.write(0) // language tag null
      chunkBos.write(0) // translated keyword null
      chunkBos.write(xml.toByteArray(UTF_8))
    }
    writePngChunk(bos, "iTXt", chunkBos.toByteArray())

    // IEND
    writePngChunk(bos, "IEND", ByteArray(0))

    return bos.toByteArray()
  }

  private fun writePngChunk(bos: ByteArrayOutputStream, type: String, data: ByteArray) {
    val length = data.size
    bos.write((length shr 24) and 0xFF)
    bos.write((length shr 16) and 0xFF)
    bos.write((length shr 8) and 0xFF)
    bos.write(length and 0xFF)
    bos.write(type.toByteArray(US_ASCII))
    bos.write(data)
    // CRC (dummy)
    bos.write(byteArrayOf(0, 0, 0, 0))
  }

  @Test
  fun testIs360ImageCandidate_2To1SeamlessBoundary() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }

    val tempFile = tempFolder.newFile("seamless.png").toPath()
    ImageIO.write(img, "png", tempFile.toFile())

    assertThat(EnvironmentFileAnalyzer.is360ImageCandidate(tempFile)).isTrue()
  }

  @Test
  fun testIs360ImageCandidate_2To1DiscontinuousBoundary() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val color = if (x < w / 2) 0x000000 else 0xFFFFFF
        img.setRGB(x, y, color)
      }
    }

    val tempFile = tempFolder.newFile("discontinuous.png").toPath()
    ImageIO.write(img, "png", tempFile.toFile())

    assertThat(EnvironmentFileAnalyzer.is360ImageCandidate(tempFile)).isFalse()
  }

  @Test
  fun testIs360ImageCandidate_16To9Image() {
    val img = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB)
    val tempFile = tempFolder.newFile("not2to1.png").toPath()
    ImageIO.write(img, "png", tempFile.toFile())

    assertThat(EnvironmentFileAnalyzer.is360ImageCandidate(tempFile)).isFalse()
  }

  @Test
  fun testHasCyclicBoundaryContinuity_seamless() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }
    assertThat(EnvironmentFileAnalyzer.hasCyclicBoundaryContinuity(img, threshold = 0.6)).isTrue()

    val tempFile = tempFolder.newFile("seamless.png").toPath()
    ImageIO.write(img, "png", tempFile.toFile())
    assertThat(EnvironmentFileAnalyzer.hasCyclicBoundaryContinuity(tempFile, threshold = 0.6)).isTrue()
  }

  @Test
  fun testHasCyclicBoundaryContinuity_discontinuous() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val color = if (x < w / 2) 0x000000 else 0xFFFFFF
        img.setRGB(x, y, color)
      }
    }
    assertThat(EnvironmentFileAnalyzer.hasCyclicBoundaryContinuity(img, threshold = 0.6)).isFalse()

    val tempFile = tempFolder.newFile("discontinuous.png").toPath()
    ImageIO.write(img, "png", tempFile.toFile())
    assertThat(EnvironmentFileAnalyzer.hasCyclicBoundaryContinuity(tempFile, threshold = 0.6)).isFalse()
  }

  @Test
  fun testIs3dSceneFile() {
    val tempDir = tempFolder.root.toPath()
    val objFile = tempDir.resolve("scene.obj")
    val txtFile = tempDir.resolve("scene.txt")
    assertThat(EnvironmentFileAnalyzer.is3dSceneFile(objFile)).isTrue()
    assertThat(EnvironmentFileAnalyzer.is3dSceneFile(txtFile)).isFalse()
  }

  @Test
  fun testIsVideoFile() {
    val tempDir = tempFolder.root.toPath()
    val mp4File = tempDir.resolve("video.mp4")
    val webmFile = tempDir.resolve("video.webm")
    val jpgFile = tempDir.resolve("image.jpg")
    assertThat(EnvironmentFileAnalyzer.isVideoFile(mp4File)).isTrue()
    assertThat(EnvironmentFileAnalyzer.isVideoFile(webmFile)).isTrue()
    assertThat(EnvironmentFileAnalyzer.isVideoFile(jpgFile)).isFalse()
  }

  @Test
  fun testIsWavefrontObjFile() {
    val tempDir = tempFolder.root.toPath()
    val validObj = tempDir.resolve("valid.obj")
    Files.writeString(validObj, "# Wavefront OBJ file\nv 1.0 2.0 3.0\nf 1 2 3\n")
    assertThat(EnvironmentFileAnalyzer.isWavefrontObjFile(validObj)).isTrue()

    val invalidObj = tempDir.resolve("invalid.obj")
    Files.writeString(invalidObj, "This is not a valid OBJ file")
    assertThat(EnvironmentFileAnalyzer.isWavefrontObjFile(invalidObj)).isFalse()
  }

  @Test
  fun testIs360Video_mp4WithSv3dEquirectangular() {
    val tempFile = tempFolder.newFile("video360_sv3d.mp4").toPath()
    Files.write(tempFile, createMockMp4WithSv3d())
    assertThat(EnvironmentFileAnalyzer.is360Video(tempFile)).isTrue()
  }

  @Test
  fun testIs360Video_mp4WithSv3dCroppedVr180() {
    val tempFile = tempFolder.newFile("video_vr180.mp4").toPath()
    // Left and right bounds cropped by 25% each (VR180) -> not a 360-degree equirectangular video.
    Files.write(tempFile, createMockMp4WithSv3d(bounds = intArrayOf(0, 0, 0x40000000, 0x40000000)))
    assertThat(EnvironmentFileAnalyzer.is360Video(tempFile)).isFalse()
  }

  @Test
  fun testIs360Video_mp4WithSv3dCubemap() {
    val tempFile = tempFolder.newFile("video_cbmp.mp4").toPath()
    Files.write(tempFile, createMockMp4WithSv3d(projectionFourcc = "cbmp"))
    assertThat(EnvironmentFileAnalyzer.is360Video(tempFile)).isFalse()
  }

  @Test
  fun testIs360Video_mp4WithVexuEquirectangular() {
    val prjiEqui = mp4Box("prji", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).put("equi".toByteArray(US_ASCII)).array())
    val vexuEqui = mp4Box("vexu", mp4Box("proj", prjiEqui))
    val equiFile = tempFolder.newFile("video360_vexu.mp4").toPath()
    Files.write(equiFile, createMockMp4WithVisualSampleChildren("vide", sampleEntryVersion = 0, vexuEqui))
    assertThat(EnvironmentFileAnalyzer.is360Video(equiFile)).isTrue()

    val equiQtV1File = tempFolder.newFile("video360_vexu_qtv1.mp4").toPath()
    Files.write(equiQtV1File, createMockMp4WithVisualSampleChildren("vide", sampleEntryVersion = 1, vexuEqui))
    assertThat(EnvironmentFileAnalyzer.is360Video(equiQtV1File)).isTrue()

    val equiQtV2File = tempFolder.newFile("video360_vexu_qtv2.mp4").toPath()
    Files.write(equiQtV2File, createMockMp4WithVisualSampleChildren("vide", sampleEntryVersion = 2, vexuEqui))
    assertThat(EnvironmentFileAnalyzer.is360Video(equiQtV2File)).isTrue()

    val prjiHalfEqui =
      mp4Box("prji", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).put("hequ".toByteArray(US_ASCII)).array())
    val vexuHalfEqui = mp4Box("vexu", mp4Box("proj", prjiHalfEqui))
    val halfEquiFile = tempFolder.newFile("video180_vexu.mp4").toPath()
    Files.write(halfEquiFile, createMockMp4WithVisualSampleChildren("vide", sampleEntryVersion = 0, vexuHalfEqui))
    assertThat(EnvironmentFileAnalyzer.is360Video(halfEquiFile)).isFalse()
  }

  @Test
  fun testIs360Video_mp4WithSphericalV1Uuid() {
    val validXml =
      """
      <rdf:SphericalVideo xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                          xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
        <GSpherical:Spherical>true</GSpherical:Spherical>
        <GSpherical:Stitched>true</GSpherical:Stitched>
        <GSpherical:CustomSources>
          <rdf:Seq>
            <rdf:li>lens0</rdf:li>
            <rdf:li>lens1</rdf:li>
          </rdf:Seq>
        </GSpherical:CustomSources>
        <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
      </rdf:SphericalVideo>
      """
        .trimIndent()
    val tempFile = tempFolder.newFile("video360_v1.mp4").toPath()
    Files.write(tempFile, createMockMp4WithSphericalV1Uuid(validXml, trailingZeros = 2))
    assertThat(EnvironmentFileAnalyzer.is360Video(tempFile)).isTrue()
  }

  @Test
  fun testIs360Video_mp4WithSphericalV1FalseOrCropped() {
    val falseXml =
      """
      <rdf:SphericalVideo xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                          xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
        <GSpherical:Spherical>false</GSpherical:Spherical>
        <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
      </rdf:SphericalVideo>
      """
        .trimIndent()
    val falseFile = tempFolder.newFile("video_v1_false.mp4").toPath()
    Files.write(falseFile, createMockMp4WithSphericalV1Uuid(falseXml))
    assertThat(EnvironmentFileAnalyzer.is360Video(falseFile)).isFalse()

    val croppedXml =
      """
      <rdf:SphericalVideo xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                          xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
        <GSpherical:Spherical>true</GSpherical:Spherical>
        <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
        <GSpherical:CroppedAreaImageWidthPixels>1920</GSpherical:CroppedAreaImageWidthPixels>
        <GSpherical:FullPanoWidthPixels>3840</GSpherical:FullPanoWidthPixels>
      </rdf:SphericalVideo>
      """
        .trimIndent()
    val croppedFile = tempFolder.newFile("video_v1_cropped.mp4").toPath()
    Files.write(croppedFile, createMockMp4WithSphericalV1Uuid(croppedXml))
    assertThat(EnvironmentFileAnalyzer.is360Video(croppedFile)).isFalse()
  }

  @Test
  fun testIs360Video_mp4WithAdobeXmpUuid() {
    val xmpUuid =
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
    val xmp =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val mp4Bytes =
      mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(US_ASCII)) +
        mp4Box("moov", mp4Box("udta", mp4Box("uuid", xmpUuid, xmp.toByteArray(UTF_8))))
    val tempFile = tempFolder.newFile("video360_xmp.mp4").toPath()
    Files.write(tempFile, mp4Bytes)
    assertThat(EnvironmentFileAnalyzer.is360Video(tempFile)).isTrue()

    // A malformed top-level XMP uuid box before moov must not abort scanning of valid sv3d metadata inside moov.
    val malformedXmpThenSv3d = mp4Box("uuid", xmpUuid, "<unclosed_xml".toByteArray(UTF_8)) + createMockMp4WithSv3d()
    val malformedThenValidFile = tempFolder.newFile("video360_bad_xmp_then_sv3d.mp4").toPath()
    Files.write(malformedThenValidFile, malformedXmpThenSv3d)
    assertThat(EnvironmentFileAnalyzer.is360Video(malformedThenValidFile)).isTrue()

    // Cropped partial-panorama GPano XMP in a video must not be classified as full 360.
    val croppedGpanoXmp =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
          GPano:UsePanoramaViewer="true"
          GPano:ProjectionType="equirectangular"
          GPano:CroppedAreaImageWidthPixels="1920"
          GPano:FullPanoWidthPixels="3840" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val croppedGpanoMp4 =
      mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(US_ASCII)) +
        mp4Box("moov", mp4Box("udta", mp4Box("uuid", xmpUuid, croppedGpanoXmp.toByteArray(UTF_8))))
    val croppedGpanoFile = tempFolder.newFile("video_cropped_gpano.mp4").toPath()
    Files.write(croppedGpanoFile, croppedGpanoMp4)
    assertThat(EnvironmentFileAnalyzer.is360Video(croppedGpanoFile)).isFalse()

    // Non-leaf extension elements in GPano must not abort parsing of valid GPano properties.
    val gpanoWithNestedElementsXmp =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
         <GPano:SourcePhotos>
          <rdf:Seq>
           <rdf:li>frame1.jpg</rdf:li>
           <rdf:li>frame2.jpg</rdf:li>
          </rdf:Seq>
         </GPano:SourcePhotos>
         <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
        </rdf:Description>
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val gpanoNestedMp4 =
      mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(US_ASCII)) +
        mp4Box("moov", mp4Box("udta", mp4Box("uuid", xmpUuid, gpanoWithNestedElementsXmp.toByteArray(UTF_8))))
    val gpanoNestedFile = tempFolder.newFile("video360_gpano_nested.mp4").toPath()
    Files.write(gpanoNestedFile, gpanoNestedMp4)
    assertThat(EnvironmentFileAnalyzer.is360Video(gpanoNestedFile)).isTrue()
  }

  @Test
  fun testIs360Video_mp4FlatOrNonVideoTrack() {
    val flatFile = tempFolder.newFile("flat.mp4").toPath()
    Files.write(flatFile, createMockMp4WithVisualSampleChildren("vide"))
    assertThat(EnvironmentFileAnalyzer.is360Video(flatFile)).isFalse()

    val soundTrackFile = tempFolder.newFile("audio_only.mp4").toPath()
    Files.write(soundTrackFile, createMockMp4WithSv3d(handlerType = "soun"))
    assertThat(EnvironmentFileAnalyzer.is360Video(soundTrackFile)).isFalse()
  }

  @Test
  fun testIs360Video_webmEquirectangular() {
    val webmFile = tempFolder.newFile("video360.webm").toPath()
    Files.write(webmFile, createMockWebm(projectionType = 1))
    assertThat(EnvironmentFileAnalyzer.is360Video(webmFile)).isTrue()

    val unknownSizeSegmentFile = tempFolder.newFile("video360_streamed.webm").toPath()
    Files.write(unknownSizeSegmentFile, createMockWebm(projectionType = 1, projectionPrivate = ByteArray(20), unknownSegmentSize = true))
    assertThat(EnvironmentFileAnalyzer.is360Video(unknownSizeSegmentFile)).isTrue()
  }

  @Test
  fun testIs360Video_webmFlatOrCropped() {
    val flatWebm = tempFolder.newFile("flat.webm").toPath()
    Files.write(flatWebm, createMockWebm(projectionType = null))
    assertThat(EnvironmentFileAnalyzer.is360Video(flatWebm)).isFalse()

    val rectWebm = tempFolder.newFile("rect.webm").toPath()
    Files.write(rectWebm, createMockWebm(projectionType = 0))
    assertThat(EnvironmentFileAnalyzer.is360Video(rectWebm)).isFalse()

    val croppedPriv =
      ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).putInt(0).putInt(0x40000000).putInt(0x40000000).array()
    val croppedWebm = tempFolder.newFile("vr180.webm").toPath()
    Files.write(croppedWebm, createMockWebm(projectionType = 1, projectionPrivate = croppedPriv))
    assertThat(EnvironmentFileAnalyzer.is360Video(croppedWebm)).isFalse()
  }

  @Test
  fun testIs360Video_invalidOrMissingFile() {
    val tempDir = tempFolder.root.toPath()
    assertThat(EnvironmentFileAnalyzer.is360Video(tempDir.resolve("missing.mp4"))).isFalse()

    val invalidFile = tempFolder.newFile("corrupt.mp4").toPath()
    Files.write(invalidFile, byteArrayOf(1, 2, 3))
    assertThat(EnvironmentFileAnalyzer.is360Video(invalidFile)).isFalse()
  }

  @Test
  fun testIs360Video_largeFileOver2GbAnd4Gb() {
    val validMp4 = createMockMp4WithSv3d()
    val flatMp4 = createMockMp4WithVisualSampleChildren("vide")
    for (mdatPayloadSize in listOf(2_500_000_000L, (1L shl 32) + 2L)) {
      assertThat(EnvironmentFileAnalyzer.is360Video(largeMp4Channel(validMp4, mdatPayloadSize))).isTrue()
      assertThat(EnvironmentFileAnalyzer.is360Video(largeMp4Channel(flatMp4, mdatPayloadSize))).isFalse()
    }
  }

  private fun largeMp4Channel(mp4Bytes: ByteArray, mdatPayloadSize: Long): SeekableByteChannel {
    val ftypSize = ByteBuffer.wrap(mp4Bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
    val ftyp = mp4Bytes.copyOfRange(0, ftypSize)
    val moov = mp4Bytes.copyOfRange(ftypSize, mp4Bytes.size)
    val mdatBoxSize = 16L + mdatPayloadSize
    val mdatHeader =
      ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(1).put("mdat".toByteArray(US_ASCII)).putLong(mdatBoxSize).array()
    val prefix = ftyp + mdatHeader
    val suffixStart = prefix.size + mdatPayloadSize
    val totalSize = suffixStart + moov.size
    return object : SeekableByteChannel {
      private var pos = 0L
      private var open = true

      override fun isOpen(): Boolean = open

      override fun close() {
        open = false
      }

      override fun position(): Long = pos

      override fun position(newPosition: Long): SeekableByteChannel {
        pos = newPosition
        return this
      }

      override fun size(): Long = totalSize

      override fun read(dst: ByteBuffer): Int {
        if (pos >= totalSize) return -1
        val toRead = minOf(dst.remaining().toLong(), totalSize - pos).toInt()
        for (i in 0 until toRead) {
          val offset = pos + i
          val byte =
            when {
              offset < prefix.size -> prefix[offset.toInt()]
              offset >= suffixStart -> moov[(offset - suffixStart).toInt()]
              else -> 0
            }
          dst.put(byte)
        }
        pos += toRead
        return toRead
      }

      override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()

      override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
    }
  }
}
