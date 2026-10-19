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
package com.android.tools.rendering.classloading

import com.google.common.util.concurrent.MoreExecutors
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassLoaderPreloaderTest {

  private class CustomControlFlowException : RuntimeException(), ControlFlowException

  @Test
  fun preloadContinuesOnLinkageError() {
    val loaded = mutableListOf<String>()
    val classLoader =
      object : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
          loaded.add(name)
          if (name == "BadLinkage") {
            throw LinkageError("Verification failed")
          }
          return String::class.java
        }
      }

    preload(classLoader, { true }, listOf("BadLinkage", "GoodClass"), MoreExecutors.directExecutor())
    assertEquals(listOf("BadLinkage", "GoodClass"), loaded)
  }

  @Test
  fun preloadContinuesOnException() {
    val loaded = mutableListOf<String>()
    val classLoader =
      object : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
          loaded.add(name)
          if (name == "BadTransform") {
            throw IllegalStateException("Transformation failed")
          }
          return String::class.java
        }
      }

    preload(classLoader, { true }, listOf("BadTransform", "GoodClass"), MoreExecutors.directExecutor())
    assertEquals(listOf("BadTransform", "GoodClass"), loaded)
  }

  @Test(expected = CancellationException::class)
  fun preloadRethrowsCancellationException() {
    val classLoader =
      object : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
          throw CancellationException("Cancelled")
        }
      }

    preload(classLoader, { true }, listOf("Cancel"), MoreExecutors.directExecutor())
  }

  @Test
  fun preloadStopsWhenNotActive() {
    val loaded = mutableListOf<String>()
    val isActive = AtomicBoolean(true)
    val classLoader =
      object : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
          loaded.add(name)
          isActive.set(false)
          return String::class.java
        }
      }

    preload(classLoader, { isActive.get() }, listOf("FirstClass", "SecondClass"), MoreExecutors.directExecutor())
    assertEquals(listOf("FirstClass"), loaded)
  }

  @Test(expected = ProcessCanceledException::class)
  fun preloadRethrowsProcessCanceledException() {
    val classLoader =
      object : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
          throw ProcessCanceledException()
        }
      }

    preload(classLoader, { true }, listOf("AnyClass"), MoreExecutors.directExecutor())
  }

  @Test(expected = CustomControlFlowException::class)
  fun preloadRethrowsControlFlowException() {
    val classLoader =
      object : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
          throw CustomControlFlowException()
        }
      }

    preload(classLoader, { true }, listOf("AnyClass"), MoreExecutors.directExecutor())
  }
}
