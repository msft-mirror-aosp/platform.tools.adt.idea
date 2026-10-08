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
package com.android.tools.idea.insights

import com.android.tools.idea.insights.client.runGrpcCatching
import com.google.common.truth.Truth
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import java.io.IOException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class RunGrpcCatchingTest {

  private val logs = mutableListOf<LogRecord>()

  @Before
  fun setup() {
    val logger = Logger.getLogger("GrpcUtils")
    logger.addHandler(
      object : Handler() {
        override fun publish(record: LogRecord?) {
          record?.let { logs.add(it) }
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    )
  }

  @Test
  fun `runGrpcCatching when block returns ready should return its result`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    val result = runGrpcCatching(LoadingState.Unauthorized("unauthorized")) { ready }
    Truth.assertThat(result).isEqualTo(ready)
  }

  @Test
  fun `runGrpcCatching when block returns failure return its result`() = runBlocking {
    val failure = LoadingState.UnknownFailure("msg")
    val result = runGrpcCatching(LoadingState.Unauthorized("unauthorized")) { failure }
    Truth.assertThat(result).isEqualTo(failure)
  }

  @Test
  fun `runGrpcCatching when block throws NOT_FOUND should return fallbackValue`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    val result = runGrpcCatching(ready) { throw StatusRuntimeException(Status.NOT_FOUND) }
    Truth.assertThat(result).isEqualTo(ready)
  }

  @Test
  fun `runGrpcCatching when block throws UNAUTHENTICATED should return Unauthorized`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    val result = runGrpcCatching(ready) { throw StatusRuntimeException(Status.UNAUTHENTICATED) }
    Truth.assertThat(result).isInstanceOf(LoadingState.Unauthorized::class.java)
  }

  @Test
  fun `runGrpcCatching when block throws other grpc exception should return UnknownFailure`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    val result = runGrpcCatching(ready) { throw StatusRuntimeException(Status.INTERNAL) }
    Truth.assertThat(result).isInstanceOf(LoadingState.UnknownFailure::class.java)
  }

  @Test
  fun `runGrpcCatching when block throws other grpc exception with status proto should return UnknownFailure with status`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    val statusProto = com.google.rpc.Status.newBuilder().setCode(3).setMessage("invalid argument").build()
    val exception = StatusProto.toStatusRuntimeException(statusProto)
    val result = runGrpcCatching(ready) { throw exception }
    Truth.assertThat(result).isEqualTo(LoadingState.UnknownFailure(exception.message, exception, statusProto))
  }

  @Test
  fun `runGrpcCatching when block throws IOException should return UnknownFailure`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    val result = runGrpcCatching(ready) { throw IOException() }
    Truth.assertThat(result).isInstanceOf(LoadingState.UnknownFailure::class.java)
  }

  @Test
  fun `runGrpcCatching logs failing grpc`() = runBlocking {
    val ready = LoadingState.Ready("hello")
    // Call using helper method since the stack will otherwise contain "invokeSuspend" from the
    // CoroutineScope of runBlocking
    callGrpcHelper(ready)
    Truth.assertThat(logs.size).isEqualTo(1)
    val log = logs.first()
    Truth.assertThat(log.level).isEqualTo(Level.WARNING)
    Truth.assertThat(log.message).isEqualTo("callGrpcHelper - Got exception: null")
  }

  private suspend fun callGrpcHelper(ready: LoadingState.Done<String>) = runGrpcCatching(ready) { throw RuntimeException() }
}
