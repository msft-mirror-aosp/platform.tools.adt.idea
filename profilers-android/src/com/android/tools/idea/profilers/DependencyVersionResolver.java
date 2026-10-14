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
package com.android.tools.idea.profilers;

import com.android.ide.common.gradle.Dependency;
import com.android.ide.common.gradle.Version;
import com.android.ide.common.repository.GoogleMavenArtifactId;
import com.android.tools.idea.projectsystem.RegisteredDependencyCompatibilityResult;
import com.android.tools.idea.projectsystem.RegisteredDependencyId;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a dependency's {@code '+'} id into a real version. Shares a running lookup per module and artifact, and keeps a
 * successful stable result for {@link #CACHE_TTL_NS}.
 */
final class DependencyVersionResolver implements Disposable {

  private static Logger getLogger() {
    return Logger.getInstance(DependencyVersionResolver.class);
  }

  /** How long a successfully resolved dependency version is reused before it is looked up again. */
  @VisibleForTesting static final long CACHE_TTL_NS = TimeUnit.MINUTES.toNanos(10);

  /** One version lookup per module and artifact. */
  record Key(@NotNull String moduleName, @NotNull GoogleMavenArtifactId artifact) {
    @NotNull
    static Key of(@NotNull Module module, @NotNull GoogleMavenArtifactId artifact) {
      return new Key(module.getName(), artifact);
    }
  }

  /** A started version lookup and the id it is for. */
  @VisibleForTesting
  record PendingResolution(@NotNull RegisteredDependencyId requestedId,
                           @NotNull ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>> future) {}

  private record CachedResolution(@NotNull RegisteredDependencyId resolvedId, long resolvedAtNs) {}

  /** Lookups still running, so a retry can reuse them. Removed when done. Synchronize on this map to use it. */
  private final Map<Key, PendingResolution> myPending = new HashMap<>();

  /** Versions found recently, kept for {@link #CACHE_TTL_NS}. */
  private final Map<Key, CachedResolution> myCache = new ConcurrentHashMap<>();

  /** How long to wait for a lookup. The first lookup after the IDE starts can take more than 10 seconds. */
  private static final long DEFAULT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);

  /** Clock for the cache period. */
  @NotNull private final LongSupplier myNanoTime;

  /** How long to wait for a lookup. */
  private final long myTimeoutMs;

  DependencyVersionResolver() {
    this(System::nanoTime, DEFAULT_TIMEOUT_MS);
  }

  @VisibleForTesting
  DependencyVersionResolver(@NotNull LongSupplier nanoTime, long timeoutMs) {
    myNanoTime = nanoTime;
    myTimeoutMs = timeoutMs;
  }

  /** Set on dispose, so a lookup that finishes later doesn't cache anything. */
  private volatile boolean myDisposed;

  @Override
  public void dispose() {
    myDisposed = true;
    synchronized (myPending) {
      myPending.clear();
    }
    myCache.clear();
  }

  /**
   * Turns the {@code '+'} id into a real version. Uses a recent result or a running lookup if there is one; otherwise starts
   * one with {@code start}. Returns {@code unresolvedId} if it fails. Rethrows {@link ProcessCanceledException} on cancel.
   */
  @NotNull
  RegisteredDependencyId resolve(
    @NotNull Key key,
    @NotNull RegisteredDependencyId unresolvedId,
    @NotNull Supplier<ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>> start,
    @NotNull ProgressIndicator indicator) {
    GoogleMavenArtifactId artifact = key.artifact();
    RegisteredDependencyId cachedId = getCached(key);
    if (cachedId != null) {
      // Found recently. No need to ask the network again.
      getLogger().info("Using recently resolved version for " + artifact + ": " + cachedId);
      return cachedId;
    }

    // Ask the project system for the version. If an older lookup is still running, wait on it. A new one would
    // just queue behind it on the same single worker thread.
    PendingResolution pending = getOrStart(key, unresolvedId, start);
    if (pending == null) {
      // Fallback for tests or unexpected mock behaviors where the future is null
      getLogger().warn("Compatibility analysis returned null future. Falling back to dynamic version.");
      return unresolvedId;
    }

    // On cancel, timeout or error we stop waiting but don't cancel the lookup. Canceling doesn't stop a network read,
    // and a finished lookup can be reused by a retry. A failed lookup ends, so the next try starts fresh.
    // Known limitation: a hung lookup makes retries time out until it ends (worst case, IDE restart). A new lookup
    // wouldn't help, it would queue behind the hung one. So don't add a cancel back.
    ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>> compatibilityFuture = pending.future();
    try {
      final long pollIntervalMs = 100;
      long startTimeNs = System.nanoTime();
      long timeoutNs = TimeUnit.MILLISECONDS.toNanos(myTimeoutMs);

      // Poll the future, allowing the user to cancel the progress bar
      while (!compatibilityFuture.isDone()) {
        indicator.checkCanceled();

        // Use a timeout to prevent hanging on bad network
        if (System.nanoTime() - startTimeNs > timeoutNs) {
          throw new TimeoutException("Compatibility analysis timed out after " + myTimeoutMs + " ms.");
        }
        try {
          compatibilityFuture.get(pollIntervalMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          // Expected during polling. Keep looping.
        }
      }

      // Use the id the lookup started with (it may be from an older try). The listener does the caching.
      RegisteredDependencyId resolvedId = compatibilityFuture.get().getCompatible().get(pending.requestedId());
      if (resolvedId != null) {
        getLogger().info("Successfully resolved " + artifact + " to concrete version: " + resolvedId);
        return resolvedId;
      }
      getLogger().warn("Failed to resolve exact version for " + artifact + ". Falling back to dynamic version.");
      return unresolvedId;
    } catch (ProcessCanceledException pce) {
      throw pce; // The caller aborts the injection.
    } catch (TimeoutException timeoutEx) {
      getLogger().warn("Network timeout resolving " + artifact + ". Falling back to dynamic version.");
      return unresolvedId;
    } catch (Exception ex) {
      // InterruptedException means this thread was interrupted. (A lookup thread interrupt comes as ExecutionException.)
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt(); // Restore interrupt status
      }
      getLogger().warn("Exception resolving " + artifact + ". Falling back to dynamic version.", ex);
      return unresolvedId;
    }
  }

  /**
   * Returns the running lookup for {@code key}, or starts a new one. Returns null if {@code start} returns null.
   *
   * <p>The slot is reserved under the lock, but {@code start} runs outside it, so a slow start doesn't block others.
   */
  @VisibleForTesting
  @Nullable
  PendingResolution getOrStart(
    @NotNull Key key,
    @NotNull RegisteredDependencyId requestedId,
    @NotNull Supplier<ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>> start) {
    SettableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>> slot;
    PendingResolution started;
    synchronized (myPending) {
      // An entry is removed only after its result is cached, so an entry or the cache always covers a finished lookup.
      PendingResolution existing = myPending.get(key);
      if (existing != null) {
        getLogger().info("Reusing in-progress dependency version lookup for " + key);
        return existing;
      }
      // The lookup may have finished since the caller checked the cache.
      RegisteredDependencyId cachedId = getCached(key);
      if (cachedId != null) {
        return new PendingResolution(requestedId, Futures.immediateFuture(
          new RegisteredDependencyCompatibilityResult<>(Map.of(requestedId, cachedId), List.of(), "")));
      }
      slot = SettableFuture.create();
      started = new PendingResolution(requestedId, slot);
      myPending.put(key, started);
    }

    ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>> future;
    try {
      future = start.get();
    }
    catch (RuntimeException | Error e) {
      // Clean up so the next attempt starts fresh instead of waiting on a slot that never completes.
      removePending(key, started);
      slot.setException(e);
      throw e;
    }
    if (future == null) {
      removePending(key, started);
      slot.setException(new IllegalStateException("Compatibility analysis returned null"));
      return null;
    }

    // When done, cache a good result even if nobody waits anymore, then drop the finished entry.
    // Runs on the shared search thread, so keep it cheap.
    slot.addListener(() -> {
      cacheIfResolved(key, started);
      removePending(key, started);
    }, MoreExecutors.directExecutor());
    // Canceling the slot must never cancel the shared lookup.
    slot.setFuture(Futures.nonCancellationPropagating(future));
    return started;
  }

  private void removePending(@NotNull Key key, @NotNull PendingResolution resolution) {
    synchronized (myPending) {
      myPending.remove(key, resolution);
    }
  }

  private void cacheIfResolved(@NotNull Key key, @NotNull PendingResolution resolution) {
    try {
      // The lookup is done, so this doesn't block.
      RegisteredDependencyId resolvedId = Futures.getDone(resolution.future()).getCompatible().get(resolution.requestedId());
      if (resolvedId != null && cache(key, resolvedId)) {
        getLogger().info("Dependency version lookup for " + key + " finished; remembering " + resolvedId + " for reuse.");
      }
      else {
        getLogger().info("Dependency version lookup for " + key + " finished without a usable version (resolved: " + resolvedId + ").");
      }
    }
    catch (Exception e) {
      // Failed or canceled. Nothing to cache; the next try starts fresh.
      getLogger().info("Dependency version lookup for " + key + " failed; nothing remembered for reuse.");
    }
  }

  /** Returns the cached version for {@code key} if it's still fresh, or null. */
  @VisibleForTesting
  @Nullable
  RegisteredDependencyId getCached(@NotNull Key key) {
    CachedResolution cached = myCache.get(key);
    if (cached == null) {
      return null;
    }
    if (myNanoTime.getAsLong() - cached.resolvedAtNs() > CACHE_TTL_NS) {
      myCache.remove(key, cached);
      return null;
    }
    return cached.resolvedId();
  }

  /** Caches {@code resolvedId} only if it is a stable version and this isn't disposed. Returns true if cached. */
  @VisibleForTesting
  boolean cache(@NotNull Key key, @NotNull RegisteredDependencyId resolvedId) {
    if (myDisposed || !isPinnedStableVersion(resolvedId)) {
      return false;
    }
    myCache.put(key, new CachedResolution(resolvedId, myNanoTime.getAsLong()));
    return true;
  }

  /**
   * Returns true for a fixed stable version like {@code group:artifact:1.0.0}. False for {@code +}, ranges, no version, or alpha/beta.
   *
   * <p>Relies on {@link RegisteredDependencyId#toString()} being {@code group:artifact:version}. If that changes, this returns
   * false and LeakCanary injection stops safely.
   */
  static boolean isPinnedStableVersion(@NotNull RegisteredDependencyId id) {
    try {
      Version version = Dependency.parse(id.toString()).getExplicitSingletonVersion();
      return version != null && !version.isPreview();
    }
    catch (Exception e) {
      getLogger().debug("Unparseable dependency id: " + id, e);
      return false;
    }
  }
}
