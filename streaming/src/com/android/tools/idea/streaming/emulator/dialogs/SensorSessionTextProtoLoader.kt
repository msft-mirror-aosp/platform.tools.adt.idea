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
package com.android.tools.idea.streaming.emulator.dialogs

import com.android.tools.idea.protobuf.TextFormat
import com.google.android.apps.automotive.sensing.sensorreplay.SensorSession
import com.google.android.apps.automotive.sensing.sensorreplay.SensorSession.SensorRecord
import com.google.android.apps.automotive.sensing.sensorreplay.SensorSession.SessionMetadata
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Streams a textproto (`TextFormat`) `SensorSession` file by slicing top-level `session_metadata` and `sensor_records` entries from a
 * buffered [Reader] and parsing each individual message with Protobuf's [TextFormat.Parser].
 *
 * Avoiding `TextFormat.Parser.merge(Readable, ...)` on the whole file prevents buffering the entire file into a single `StringBuilder` and
 * allows arbitrarily large sessions to stream through [SensorSessionAccumulator] and spill to disk when needed.
 */
internal object SensorSessionTextProtoLoader {
  private const val MAX_IDENTIFIER_CHARS = 1024
  private const val MAX_SINGLE_MESSAGE_CHARS = 64 * 1024 * 1024

  private val TEXT_FORMAT_PARSER: TextFormat.Parser =
    TextFormat.Parser.newBuilder().setAllowUnknownFields(true).setAllowUnknownExtensions(true).setRecursionLimit(MAX_GROUP_NESTING).build()

  fun loadTextProtoSensorSession(
    file: Path,
    maxInMemoryBytes: Int,
    checkpointIntervalBytes: Int,
    maxMergeFanIn: Int,
    checkCancelled: () -> Unit,
  ): SensorSessionItem {
    return SensorSessionAccumulator(maxInMemoryBytes, checkpointIntervalBytes, maxMergeFanIn, checkCancelled).use { accumulator ->
      val decoder =
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      InputStreamReader(Files.newInputStream(file, StandardOpenOption.READ), decoder).use { reader ->
        val scanner = TopLevelTextProtoScanner(reader, checkCancelled)
        val scratch = StringBuilder(4096)

        while (scanner.skipWhitespaceAndComments()) {
          checkCancelled()
          when (val ch = scanner.peekChar()) {
            '[' -> {
              scanner.readUnknownTopLevelField(scratch)
              TEXT_FORMAT_PARSER.merge(scratch, SensorSession.newBuilder())
            }
            else -> {
              if (!TopLevelTextProtoScanner.isIdentifierChar(ch)) {
                throw scanner.parseException("Expected identifier. Found '$ch'")
              }
              when (val fieldName = scanner.readIdentifier()) {
                "session_metadata" -> {
                  accumulator.markSessionMetadataPresent()
                  scanner.tryConsumeOptionalColon()
                  scanner.readSingleMessageBody(scratch)
                  scanner.consumeOptionalTopLevelSeparator()
                  val parsedMetadata = SessionMetadata.newBuilder()
                  TEXT_FORMAT_PARSER.merge(scratch, parsedMetadata)
                  mergeStrippedSessionMetadata(parsedMetadata.build(), accumulator.metadataBuilder)
                }
                "sensor_records" -> {
                  scanner.tryConsumeOptionalColon()
                  scanner.forEachRepeatedMessageBody(scratch) { body ->
                    accumulator.addRecord(parseAndStripSensorRecord(body))
                  }
                  scanner.consumeOptionalTopLevelSeparator()
                }
                else -> {
                  scanner.readUnknownTopLevelFieldAfterName(fieldName, scratch)
                  TEXT_FORMAT_PARSER.merge(scratch, SensorSession.newBuilder())
                }
              }
            }
          }
        }
      }
      accumulator.buildSessionItem(file)
    }
  }

  private fun parseAndStripSensorRecord(body: CharSequence): SensorRecord {
    val builder = SensorRecord.newBuilder()
    try {
      TEXT_FORMAT_PARSER.merge(body, builder)
    } catch (e: TextFormat.ParseException) {
      val normalized = rewriteUnsignedE7Coordinates(body) ?: throw e
      builder.clear()
      TEXT_FORMAT_PARSER.merge(normalized, builder)
    }
    return buildStrippedSensorRecord(builder.build())
  }

  private fun buildStrippedSensorRecord(parsed: SensorRecord): SensorRecord {
    val stripped = SensorRecord.newBuilder()
    if (parsed.hasTimestampNs()) {
      stripped.timestampNs = parsed.timestampNs
    }
    if (parsed.locationsCount > 0) {
      stripped.addAllLocations(parsed.locationsList)
    }
    if (parsed.carPropertyValuesCount > 0) {
      stripped.putAllCarPropertyValues(parsed.carPropertyValuesMap)
    }
    if (parsed.sensorEventsCount > 0) {
      stripped.putAllSensorEvents(parsed.sensorEventsMap)
    }
    return stripped.build()
  }

  /**
   * Rewrites legacy unsigned 32-bit `fixed32` literals (`2147483648..4294967295`, including hex and octal forms) assigned to `lat_e7` or
   * `lng_e7` into their two's-complement signed 32-bit `sfixed32` equivalents. Returns `null` if no such literals exist in [body].
   */
  private fun rewriteUnsignedE7Coordinates(body: CharSequence): String? {
    var out: StringBuilder? = null
    var lastCopied = 0
    var i = 0
    val len = body.length

    while (i < len) {
      when (val ch = body[i]) {
        '#' -> i = skipCommentInCharSequence(body, i)
        '"',
        '\'' -> i = skipQuotedInCharSequence(body, i)
        else -> {
          if (TopLevelTextProtoScanner.isIdentifierChar(ch)) {
            val idStart = i
            i++
            while (i < len && TopLevelTextProtoScanner.isIdentifierChar(body[i])) {
              i++
            }
            if (isCoordinateFieldName(body, idStart, i)) {
              var cursor = skipWhitespaceAndCommentsInCharSequence(body, i)
              if (cursor < len && body[cursor] == ':') {
                cursor = skipWhitespaceAndCommentsInCharSequence(body, cursor + 1)
              }
              if (cursor < len && body[cursor] in '0'..'9') {
                val numStart = cursor
                cursor++
                while (cursor < len && TopLevelTextProtoScanner.isScalarTokenContinuation(body[cursor])) {
                  cursor++
                }
                val signedEquivalent = parseUnsignedFixed32OverflowAsSigned(body, numStart, cursor)
                if (signedEquivalent != null) {
                  val sb = out ?: StringBuilder(len + 16).also { out = it }
                  sb.append(body, lastCopied, numStart)
                  sb.append(signedEquivalent)
                  lastCopied = cursor
                  i = cursor
                }
              }
            }
          } else {
            i++
          }
        }
      }
    }

    val sb = out ?: return null
    sb.append(body, lastCopied, len)
    return sb.toString()
  }

  private fun isCoordinateFieldName(body: CharSequence, start: Int, end: Int): Boolean =
    end - start == 6 && (body.regionMatches(start, "lat_e7", 0, 6) || body.regionMatches(start, "lng_e7", 0, 6))

  private fun skipCommentInCharSequence(body: CharSequence, start: Int): Int {
    var i = start + 1
    while (i < body.length) {
      if (body[i++] == '\n') break
    }
    return i
  }

  private fun skipQuotedInCharSequence(body: CharSequence, start: Int): Int {
    val quote = body[start]
    var i = start + 1
    while (i < body.length) {
      val ch = body[i++]
      if (ch == quote || ch == '\n') break
      if (ch == '\\' && i < body.length) i++
    }
    return i
  }

  private fun skipWhitespaceAndCommentsInCharSequence(body: CharSequence, start: Int): Int {
    var i = start
    while (i < body.length) {
      val ch = body[i]
      if (TopLevelTextProtoScanner.isProtoWhitespace(ch)) {
        i++
      } else if (ch == '#') {
        i = skipCommentInCharSequence(body, i)
      } else {
        break
      }
    }
    return i
  }

  private fun parseUnsignedFixed32OverflowAsSigned(body: CharSequence, start: Int, end: Int): Int? {
    val len = end - start
    if (len <= 0) return null
    val radix: Int
    val digitStart: Int
    if (len >= 3 && body[start] == '0' && (body[start + 1] == 'x' || body[start + 1] == 'X')) {
      radix = 16
      digitStart = start + 2
    } else if (len >= 2 && body[start] == '0') {
      radix = 8
      digitStart = start + 1
    } else {
      radix = 10
      digitStart = start
    }

    var value = 0L
    for (k in digitStart until end) {
      val digit = asciiDigitValue(body[k], radix)
      if (digit < 0) return null
      value = value * radix + digit
      if (value > 0xFFFF_FFFFL) return null
    }
    return if (value > Int.MAX_VALUE.toLong()) value.toInt() else null
  }

  private fun asciiDigitValue(ch: Char, radix: Int): Int {
    val d =
      when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> return -1
      }
    return if (d < radix) d else -1
  }

  private fun mergeStrippedSessionMetadata(parsed: SessionMetadata, target: SessionMetadata.Builder) {
    if (parsed.hasVersion()) {
      target.mergeVersion(parsed.version)
    }
    for (propId in parsed.carPropertyConfigsMap.keys) {
      target.putCarPropertyConfigs(propId, SessionMetadata.CarPropertyConfig.getDefaultInstance())
    }
    for ((sensorKey, sensor) in parsed.subscribedSensorsMap) {
      val strippedSensor = SessionMetadata.Sensor.newBuilder()
      if (sensor.hasName()) strippedSensor.name = sensor.name
      if (sensor.hasType()) strippedSensor.type = sensor.type
      target.putSubscribedSensors(sensorKey, strippedSensor.build())
    }
  }

  private class TopLevelTextProtoScanner(
    private val reader: Reader,
    private val checkCancelled: () -> Unit,
  ) {
    private val buffer = CharArray(IO_BUFFER_SIZE)
    private var pos = 0
    private var limit = 0
    private var eof = false
    private var line = 1
    private var column = 1
    private val delimiterStack = CharArray(MAX_GROUP_NESTING)

    init {
      if (ensureChar() && buffer[pos] == '\uFEFF') {
        advanceChar()
      }
    }

    fun peekChar(): Char = buffer[pos]

    fun parseException(message: String): TextFormat.ParseException = TextFormat.ParseException(line, column, message)

    /** Skips whitespace and `#` comments; returns `true` if another character is available, or `false` at EOF. */
    fun skipWhitespaceAndComments(): Boolean {
      while (ensureChar()) {
        val ch = buffer[pos]
        if (isProtoWhitespace(ch)) {
          advanceChar()
        } else if (ch == '#') {
          skipCommentLine()
        } else {
          return true
        }
      }
      return false
    }

    fun readIdentifier(): String {
      val sb = StringBuilder(32)
      while (ensureChar() && isIdentifierChar(buffer[pos])) {
        if (sb.length >= MAX_IDENTIFIER_CHARS) {
          throw parseException("Identifier exceeds maximum length of $MAX_IDENTIFIER_CHARS characters.")
        }
        sb.append(advanceChar())
      }
      if (sb.isEmpty()) {
        throw parseException("Expected identifier.")
      }
      return sb.toString()
    }

    fun tryConsumeOptionalColon(): Boolean {
      if (!skipWhitespaceAndComments()) return false
      if (buffer[pos] == ':') {
        advanceChar()
        return true
      }
      return false
    }

    fun consumeOptionalTopLevelSeparator() {
      if (!skipWhitespaceAndComments()) return
      if (buffer[pos] == ';' || buffer[pos] == ',') {
        advanceChar()
      }
    }

    fun readSingleMessageBody(out: StringBuilder) {
      if (!skipWhitespaceAndComments()) {
        throw parseException("Expected \"{\".")
      }
      val open = buffer[pos]
      if (open != '{' && open != '<') {
        throw parseException("Expected \"{\", found '$open'.")
      }
      advanceChar()
      out.setLength(0)
      appendBalancedBodyUntilClose(open, out, appendClosingDelimiter = false)
    }

    inline fun forEachRepeatedMessageBody(out: StringBuilder, onBody: (CharSequence) -> Unit) {
      if (!skipWhitespaceAndComments()) {
        throw parseException("Expected \"{\".")
      }
      if (buffer[pos] == '[') {
        advanceChar()
        if (!skipWhitespaceAndComments()) {
          throw parseException("Expected \"]\".")
        }
        if (buffer[pos] == ']') {
          advanceChar()
          return
        }
        while (true) {
          readSingleMessageBody(out)
          onBody(out)
          if (!skipWhitespaceAndComments()) {
            throw parseException("Expected \"]\".")
          }
          when (buffer[pos]) {
            ']' -> {
              advanceChar()
              return
            }
            ',' -> advanceChar()
            else -> throw parseException("Expected \",\" or \"]\", found '${buffer[pos]}'.")
          }
        }
      } else {
        readSingleMessageBody(out)
        onBody(out)
      }
    }

    fun readUnknownTopLevelField(out: StringBuilder) {
      out.setLength(0)
      // Consume '[type.name]' header.
      appendSafe(out, advanceChar()) // '['
      while (true) {
        if (!ensureChar()) {
          throw parseException("Expected \"]\".")
        }
        val ch = buffer[pos]
        if (ch == '#') {
          skipCommentLine()
          appendSafe(out, '\n')
          continue
        }
        if (ch == '"' || ch == '\'' || ch == '{' || ch == '<') {
          throw parseException("Expected \"]\".")
        }
        appendSafe(out, advanceChar())
        if (ch == ']') break
      }
      appendUnknownFieldTail(out)
    }

    fun readUnknownTopLevelFieldAfterName(fieldName: String, out: StringBuilder) {
      out.setLength(0)
      out.append(fieldName)
      appendUnknownFieldTail(out)
    }

    private fun appendUnknownFieldTail(out: StringBuilder) {
      if (tryConsumeOptionalColon()) {
        appendSafe(out, ':')
      }
      if (skipWhitespaceAndComments()) {
        when (val ch = buffer[pos]) {
          '{',
          '<' -> {
            appendSafe(out, advanceChar())
            appendBalancedBodyUntilClose(ch, out, appendClosingDelimiter = true)
          }
          '[' -> {
            appendSafe(out, advanceChar())
            appendBalancedBodyUntilClose('[', out, appendClosingDelimiter = true)
          }
          '"',
          '\'' -> {
            while (skipWhitespaceAndComments() && (buffer[pos] == '"' || buffer[pos] == '\'')) {
              appendSafe(out, ' ')
              appendQuotedLiteral(out)
            }
          }
          else -> {
            if (isScalarWordChar(ch)) {
              appendSafe(out, ' ')
              while (ensureChar() && isScalarWordChar(buffer[pos])) {
                appendSafe(out, advanceChar())
              }
            }
          }
        }
      }
      if (skipWhitespaceAndComments() && (buffer[pos] == ';' || buffer[pos] == ',')) {
        appendSafe(out, advanceChar())
      }
    }

    private fun appendBalancedBodyUntilClose(
      openDelimiter: Char,
      out: StringBuilder,
      appendClosingDelimiter: Boolean,
    ) {
      delimiterStack[0] = matchingClose(openDelimiter)
      var depth = 1
      while (depth > 0) {
        if (!ensureChar()) {
          throw parseException("Expected \"${delimiterStack[depth - 1]}\".")
        }
        when (val ch = buffer[pos]) {
          '#' -> {
            skipCommentLine()
            appendSafe(out, '\n')
          }
          '"',
          '\'' -> appendQuotedLiteral(out)
          '{',
          '<',
          '[' -> {
            if (depth >= MAX_GROUP_NESTING) {
              throw parseException("Message is nested too deep")
            }
            delimiterStack[depth++] = matchingClose(ch)
            appendSafe(out, advanceChar())
          }
          '}',
          '>',
          ']' -> {
            val expected = delimiterStack[depth - 1]
            if (ch != expected) {
              throw parseException("Expected \"$expected\", found \"$ch\".")
            }
            advanceChar()
            depth--
            if (depth > 0 || appendClosingDelimiter) {
              appendSafe(out, ch)
            }
          }
          else -> appendSafe(out, advanceChar())
        }
      }
    }

    private fun appendQuotedLiteral(out: StringBuilder) {
      val quote = advanceChar()
      appendSafe(out, quote)
      while (true) {
        if (!ensureChar()) {
          throw parseException("String missing ending quote.")
        }
        val ch = advanceChar()
        if (ch == '\n') {
          throw parseException("String missing ending quote.")
        }
        appendSafe(out, ch)
        if (ch == quote) {
          return
        }
        if (ch == '\\') {
          if (!ensureChar()) {
            throw parseException("String missing ending quote.")
          }
          val esc = advanceChar()
          if (esc == '\n') {
            throw parseException("String missing ending quote.")
          }
          appendSafe(out, esc)
        }
      }
    }

    private fun skipCommentLine() {
      advanceChar() // '#'
      while (ensureChar()) {
        if (advanceChar() == '\n') return
      }
    }

    private fun appendSafe(out: StringBuilder, ch: Char) {
      if (out.length >= MAX_SINGLE_MESSAGE_CHARS) {
        throw parseException("Single textproto message exceeds maximum supported length.")
      }
      out.append(ch)
    }

    private fun advanceChar(): Char {
      val ch = buffer[pos++]
      if (ch == '\n') {
        line++
        column = 1
      } else {
        column++
      }
      return ch
    }

    private fun ensureChar(): Boolean {
      if (pos < limit) return true
      if (eof) return false
      pos = 0
      limit = 0
      while (limit == 0 && !eof) {
        checkCancelled()
        val readCount = reader.read(buffer, 0, buffer.size)
        if (readCount < 0) {
          eof = true
        } else {
          limit = readCount
        }
      }
      return limit > 0
    }

    companion object {

      fun isIdentifierChar(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '.'

      private fun matchingClose(open: Char): Char {
        return when (open) {
          '<' -> '>'
          '[' -> ']'
          else -> '}'
        }
      }

      fun isProtoWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000B' || ch == '\u000C'

      fun isScalarTokenContinuation(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == '.'

      private fun isScalarWordChar(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == '.' || ch == '+' || ch == '-'
    }
  }
}
