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

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * [InputStream] wrapper over a [SeekableByteChannel] whose [skip] repositions the underlying channel in `O(1)` rather than reading and
 * discarding multi-megabyte payloads.
 */
internal class SeekableChannelInputStream(private val channel: SeekableByteChannel) : InputStream() {
  private val singleByte = ByteBuffer.allocate(1)

  override fun read(): Int {
    singleByte.clear()
    val readCount = channel.read(singleByte)
    return if (readCount <= 0) -1 else singleByte.get(0).toInt() and 0xFF
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    val buffer = ByteBuffer.wrap(b, off, len)
    if (len == 0) return 0
    val readCount = channel.read(buffer)
    return if (readCount <= 0) -1 else readCount
  }

  override fun skip(n: Long): Long {
    if (n <= 0L) return 0L
    val pos = channel.position()
    val size = channel.size()
    val available = (size - pos).coerceAtLeast(0L)
    if (available == 0L) return 0L
    val toSkip = minOf(n, available)
    channel.position(pos + toSkip)
    return toSkip
  }

  override fun close() {
    channel.close()
  }
}
