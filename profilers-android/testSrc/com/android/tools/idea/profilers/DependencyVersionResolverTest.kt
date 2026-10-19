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
package com.android.tools.idea.profilers

import com.android.ide.common.repository.GoogleMavenArtifactId
import com.android.tools.idea.projectsystem.RegisteredDependencyCompatibilityResult
import com.android.tools.idea.projectsystem.RegisteredDependencyId
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier
import java.util.function.Supplier
import kotlin.test.assertFailsWith
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

class DependencyVersionResolverTest {

  private val resolver = DependencyVersionResolver()
  private val key = DependencyVersionResolver.Key("app", GoogleMavenArtifactId.LEAKCANARY)
  private val unresolvedId = FakeDependencyId("com.android.tools.studio.leakcanary:leakcanary:+")
  private val stableId = FakeDependencyId("com.android.tools.studio.leakcanary:leakcanary:1.0.0")
  private val previewId = FakeDependencyId("com.android.tools.studio.leakcanary:leakcanary:1.1.0-alpha01")

  @Test
  fun testIsPinnedStableVersion() {
    assertThat(DependencyVersionResolver.isPinnedStableVersion(stableId)).isTrue()
    assertThat(DependencyVersionResolver.isPinnedStableVersion(FakeDependencyId("com.android.tools.studio.leakcanary:leakcanary:1.2.3")))
      .isTrue()
    for (rejected in
      listOf(
        "com.android.tools.studio.leakcanary:leakcanary:+",
        "com.android.tools.studio.leakcanary:leakcanary:1.+",
        "com.android.tools.studio.leakcanary:leakcanary:[1.0,2.0)",
        "com.android.tools.studio.leakcanary:leakcanary:1.1.0-alpha01",
        "com.android.tools.studio.leakcanary:leakcanary:1.0.0-beta01",
        "com.android.tools.studio.leakcanary:leakcanary:1.1.0-rc01",
        "com.android.tools.studio.leakcanary:leakcanary:1.0.0-SNAPSHOT",
        "com.android.tools.studio.leakcanary:leakcanary:latest.release",
        "com.android.tools.studio.leakcanary:leakcanary",
      )) {
      assertWithMessage(rejected).that(DependencyVersionResolver.isPinnedStableVersion(FakeDependencyId(rejected))).isFalse()
    }
    assertThat(DependencyVersionResolver.isPinnedStableVersion(mock<RegisteredDependencyId>())).isFalse()
  }

  @Test
  fun testResolveReturnsResolvedVersionAndCachesIt() {
    var startCount = 0
    val start = startReturning {
      startCount++
      Futures.immediateFuture(compatibilityResult(unresolvedId to stableId))
    }

    assertThat(resolver.resolve(key, unresolvedId, start, EmptyProgressIndicator())).isSameAs(stableId)
    // The second try uses the cached version, no new lookup.
    assertThat(resolver.resolve(key, unresolvedId, start, EmptyProgressIndicator())).isSameAs(stableId)
    assertThat(startCount).isEqualTo(1)
  }

  @Test
  fun testCachedVersionExpiresAfterTtl() {
    var nowNs = 1_000L
    val resolver = DependencyVersionResolver(LongSupplier { nowNs }, TimeUnit.SECONDS.toMillis(20))
    assertThat(resolver.cache(key, stableId)).isTrue()
    assertThat(resolver.getCached(key)).isSameAs(stableId)

    nowNs += DependencyVersionResolver.CACHE_TTL_NS + 1
    assertThat(resolver.getCached(key)).isNull()
  }

  @Test
  fun testResolveFallsBackWithoutCachingWhenLookupFailsOrIsNull() {
    val failed = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    failed.setException(RuntimeException("Network failure"))
    assertThat(resolver.resolve(key, unresolvedId, startReturning { failed }, EmptyProgressIndicator())).isSameAs(unresolvedId)
    assertThat(failed.isCancelled).isFalse()
    assertThat(resolver.getCached(key)).isNull()

    assertThat(resolver.resolve(key, unresolvedId, startReturning { null }, EmptyProgressIndicator())).isSameAs(unresolvedId)
  }

  @Test
  fun testTimeoutDoesNotCancelLookupAndRetryUsesLateResult() {
    val resolver = DependencyVersionResolver(System::nanoTime, 50)
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    var startCount = 0
    val start = startReturning {
      startCount++
      lookup
    }

    // The lookup doesn't finish in time: we stop waiting, but the lookup keeps running.
    assertThat(resolver.resolve(key, unresolvedId, start, EmptyProgressIndicator())).isSameAs(unresolvedId)
    assertThat(lookup.isCancelled).isFalse()

    // It finishes later with nobody waiting. The retry uses its result, no new lookup.
    lookup.set(compatibilityResult(unresolvedId to stableId))
    assertThat(resolver.resolve(key, unresolvedId, start, EmptyProgressIndicator())).isSameAs(stableId)
    assertThat(startCount).isEqualTo(1)
  }

  @Test
  fun testResolveRethrowsCancellationWithoutCancelingLookup() {
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    val canceledIndicator = mock<ProgressIndicator> { on { checkCanceled() } doThrow ProcessCanceledException() }

    assertFailsWith<ProcessCanceledException> { resolver.resolve(key, unresolvedId, startReturning { lookup }, canceledIndicator) }

    // The lookup is left running so a retry can reuse it.
    assertThat(lookup.isCancelled).isFalse()
    assertThat(resolver.getOrStart(key, unresolvedId) { error("should reuse the running lookup") }).isNotNull()
  }

  @Test
  fun testGetOrStartReusesRunningLookupPerKey() {
    val running = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    var startCount = 0
    val start = startReturning {
      startCount++
      running
    }

    // A retry while the lookup is still running reuses it.
    val first = resolver.getOrStart(key, unresolvedId, start)
    val second = resolver.getOrStart(key, unresolvedId, start)
    assertThat(startCount).isEqualTo(1)
    assertThat(second).isSameAs(first)

    // A different artifact starts its own lookup immediately.
    val appCompatKey = DependencyVersionResolver.Key("app", GoogleMavenArtifactId.ANDROIDX_APPCOMPAT)
    resolver.getOrStart(
      appCompatKey,
      unresolvedId,
      startReturning {
        startCount++
        SettableFuture.create()
      },
    )
    assertThat(startCount).isEqualTo(2)

    // Once the lookup has finished, the next attempt starts a fresh one.
    running.set(compatibilityResult())
    resolver.getOrStart(key, unresolvedId, start)
    assertThat(startCount).isEqualTo(3)
  }

  @Test
  fun testFinishedLookupIsCachedEvenIfNobodyIsWaiting() {
    // E.g. the user canceled. Nobody waits, but the result is still cached.
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    resolver.getOrStart(key, unresolvedId) { lookup }
    assertThat(resolver.getCached(key)).isNull()

    lookup.set(compatibilityResult(unresolvedId to stableId))
    assertThat(resolver.getCached(key)).isSameAs(stableId)

    // A preview result is not cached, so the next try looks it up again.
    val otherKey = DependencyVersionResolver.Key("other", GoogleMavenArtifactId.LEAKCANARY)
    val previewLookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    resolver.getOrStart(otherKey, unresolvedId) { previewLookup }
    previewLookup.set(compatibilityResult(unresolvedId to previewId))
    assertThat(resolver.getCached(otherKey)).isNull()
  }

  @Test
  fun testGetOrStartUsesResultCachedAfterCallersCheck() {
    // The lookup finished (cached, entry removed) after the caller checked the cache: no new lookup.
    assertThat(resolver.cache(key, stableId)).isTrue()

    val pending = resolver.getOrStart(key, unresolvedId) { error("should use the cached version") }

    assertThat(Futures.getDone(pending!!.future()).compatible[unresolvedId]).isSameAs(stableId)
  }

  @Test
  fun testErrorWhileStartingLookupDoesNotLeaveStuckEntry() {
    assertFailsWith<AssertionError> { resolver.getOrStart(key, unresolvedId) { throw AssertionError("start failed") } }

    // The next attempt starts a fresh lookup instead of waiting on the failed one.
    var started = false
    resolver.getOrStart(key, unresolvedId) {
      started = true
      SettableFuture.create()
    }
    assertThat(started).isTrue()
  }

  @Test
  fun testCancelingPendingFutureDoesNotCancelSharedLookup() {
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    val pending = resolver.getOrStart(key, unresolvedId) { lookup }!!

    pending.future().cancel(true)

    assertThat(lookup.isCancelled).isFalse()
  }

  @Test
  fun testDisposeClearsPendingAndCacheAndLateResultIsNotCached() {
    val otherKey = DependencyVersionResolver.Key("other", GoogleMavenArtifactId.LEAKCANARY)
    assertThat(resolver.cache(key, stableId)).isTrue()
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    val pending = resolver.getOrStart(otherKey, unresolvedId) { lookup }

    resolver.dispose()

    // The cache and the pending lookup are cleared: the next attempt doesn't reuse the old lookup.
    assertThat(resolver.getCached(key)).isNull()
    assertThat(resolver.getOrStart(otherKey, unresolvedId) { SettableFuture.create() }).isNotSameAs(pending)
    // A lookup that finishes after dispose doesn't put anything back, and it was never canceled.
    lookup.set(compatibilityResult(unresolvedId to stableId))
    assertThat(resolver.getCached(otherKey)).isNull()
    assertThat(lookup.isCancelled).isFalse()
  }

  /** A [RegisteredDependencyId] whose [toString] returns a Gradle coordinate, mirroring `GradleRegisteredDependencyId`. */
  private class FakeDependencyId(private val coordinate: String) : RegisteredDependencyId {
    override fun toString(): String = coordinate
  }

  private fun startReturning(lookup: () -> ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>?) =
    Supplier<ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>?> { lookup() }

  private fun compatibilityResult(
    vararg compatible: Pair<RegisteredDependencyId, RegisteredDependencyId>
  ): RegisteredDependencyCompatibilityResult<RegisteredDependencyId> =
    RegisteredDependencyCompatibilityResult(compatible = mapOf(*compatible), incompatible = listOf(), warning = "")
}
