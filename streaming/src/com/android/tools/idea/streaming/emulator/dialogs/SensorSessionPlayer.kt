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

import com.android.emulator.control.SensorSession.SensorRecord
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.tools.idea.io.grpc.StatusException
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionAccumulator.Companion.formatDuration
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionAccumulator.SensorRecordStore.Companion.mergeRecordIntoAggregate
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

/** Playback speed multipliers supported by [SensorReplayDialog]. */
internal enum class SensorReplaySpeed(val multiplier: Float, private val displayName: String) {
  SPEED_1X(1f, "1X"),
  SPEED_2X(2f, "2X"),
  SPEED_5X(5f, "5X"),
  SPEED_10X(10f, "10X"),
  SPEED_100X(100f, "100X"),
  SPEED_0_5X(0.5f, "0.5X"),
  SPEED_0_25X(0.25f, "0.25X"),
  SPEED_0_1X(0.1f, "0.1X");

  override fun toString(): String = displayName
}

/** Manages playback timing, record batching, and seeking for [SensorReplayDialog]. */
internal class SensorSessionPlayer(
  private val scope: CoroutineScope,
  private val uiContext: CoroutineContext,
  private val dispatchRecord: suspend (SensorRecord, SessionMetadata) -> Unit,
  private val isSliderAdjusting: () -> Boolean,
  private val onProgressChanged: (position: Duration, duration: Duration, updateSliderValue: Boolean) -> Unit,
  private val onPlaybackStateChanged: () -> Unit,
  private val onStateModified: () -> Unit,
  private val onClearError: () -> Unit,
  private val onError: (@Nls String) -> Unit,
) {
  var selectedSession: SensorSessionItem? = null
    private set

  var playbackSpeed: SensorReplaySpeed = SensorReplaySpeed.SPEED_1X
    private set

  private var currentPosition: Duration = Duration.ZERO
  private var needsSeekAggregateOnPlay: Boolean = false
  private var seekSequence: Long = 0L
  private val operationMutex = Mutex()
  private var playbackJob: Job? = null
  private var activePlaybackTiming: ActivePlaybackTiming? = null
  private var resumePlaybackAfterAdjusting: Boolean = false
  private var seekJob: Job? = null

  val isPlaying: Boolean
    get() = playbackJob != null || resumePlaybackAfterAdjusting

  fun selectSession(session: SensorSessionItem?) {
    selectedSession = session
    currentPosition = Duration.ZERO
    needsSeekAggregateOnPlay = false
    seekSequence++
    seekJob?.cancel()
  }

  fun setSpeed(speed: SensorReplaySpeed) {
    playbackSpeed = speed
    activePlaybackTiming?.updateSpeed(speed.multiplier)
  }

  fun onSliderChanged(sliderValue: Int, valueIsAdjusting: Boolean) {
    val session = selectedSession ?: return
    currentPosition = sliderValueToPosition(sliderValue, session.duration)
    needsSeekAggregateOnPlay = currentPosition.isPositive()
    val seq = ++seekSequence
    onProgressChanged(currentPosition, session.duration, false)

    if (valueIsAdjusting) {
      seekJob?.cancel()
      val job = playbackJob
      if (job != null) {
        playbackJob = null
        activePlaybackTiming = null
        resumePlaybackAfterAdjusting = true
        job.cancel()
      }
    } else if (isPlaying) {
      val job = playbackJob
      playbackJob = null
      activePlaybackTiming = null
      resumePlaybackAfterAdjusting = false
      job?.cancel()
      startPlayback(fromSeek = true)
    } else {
      seekToPosition(session, currentPosition, seq)
    }
  }

  fun togglePlayback() {
    if (isPlaying) {
      pausePlayback()
    } else {
      startPlayback()
    }
  }

  fun stopPlayback(resetPosition: Boolean, cancelJob: Boolean = true) {
    val job = playbackJob
    playbackJob = null
    activePlaybackTiming = null
    resumePlaybackAfterAdjusting = false
    if (cancelJob) {
      job?.cancel()
    }
    if (resetPosition) {
      currentPosition = Duration.ZERO
      needsSeekAggregateOnPlay = false
      val duration = selectedSession?.duration ?: Duration.ZERO
      updatePlaybackProgressUi(Duration.ZERO, duration)
    }
    onPlaybackStateChanged()
  }

  fun cancel() {
    stopPlayback(resetPosition = false)
    seekJob?.cancel()
  }

  private fun pausePlayback() {
    val timing = activePlaybackTiming
    stopPlayback(resetPosition = false)
    if (timing != null && timing.isStarted()) {
      val pausedPos = timing.computePausedPosition(currentPosition)
      updatePlaybackProgressUi(pausedPos, timing.duration)
    }
  }

  private fun seekToPosition(session: SensorSessionItem, targetOffset: Duration, seq: Long) {
    seekJob?.cancel()
    if (session.hasNoEvents) return
    onClearError()
    seekJob = scope.launch {
      operationMutex.withLock {
        try {
          val aggregated = withContext(Dispatchers.IO) { session.aggregateRecordsUpTo(targetOffset) }
          currentCoroutineContext().ensureActive()
          if (aggregated != null) {
            val isCurrentSeek =
              withContext(uiContext) {
                if (seekSequence == seq) {
                  onStateModified()
                  true
                } else {
                  false
                }
              }
            if (!isCurrentSeek) return@withLock
            dispatchRecord(aggregated, session.metadata)
          }
          withContext(uiContext) {
            if (seekSequence == seq) {
              needsSeekAggregateOnPlay = false
            }
          }
        } catch (e: IOException) {
          thisLogger().warn("Unable to read sensor session '${session.path}'", e)
          showAsyncError(e.message)
        } catch (e: StatusException) {
          showAsyncError(e.message)
        } catch (e: StatusRuntimeException) {
          showAsyncError(e.message)
        } catch (e: EmulatorController.RetryException) {
          showAsyncError(e.message)
        }
      }
    }
  }

  private fun startPlayback(fromSeek: Boolean = false) {
    val session = selectedSession ?: return
    if (session.hasNoEvents) return
    seekJob?.cancel()
    onClearError()
    onStateModified()

    val duration = session.duration
    val startOffset =
      when {
        fromSeek -> currentPosition.coerceIn(Duration.ZERO, duration)
        currentPosition > Duration.ZERO && currentPosition < duration -> currentPosition
        else -> Duration.ZERO
      }
    val aggregateBeforeStart = startOffset.isPositive() && needsSeekAggregateOnPlay
    val pendingUiPosition = AtomicReference(startOffset)
    val uiUpdateScheduled = AtomicBoolean(false)
    val timing =
      ActivePlaybackTiming(
        initialOffset = startOffset,
        initialSpeedMultiplier = playbackSpeed.multiplier,
        duration = duration,
      )

    lateinit var job: Job
    job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          operationMutex.withLock {
            if (aggregateBeforeStart) {
              val aggregated = withContext(Dispatchers.IO) { session.aggregateRecordsUpTo(startOffset) }
              currentCoroutineContext().ensureActive()
              if (aggregated != null) {
                dispatchRecord(aggregated, session.metadata)
              }
              withContext(uiContext) {
                if (playbackJob === job) {
                  needsSeekAggregateOnPlay = false
                }
              }
            }

            timing.start()
            var lastUiUpdateMark: TimeMark? = null
            var lastFlushMark: TimeMark = TimeSource.Monotonic.markNow()
            var pendingSingleRecord: SensorRecord? = null
            var pendingAggregateBuilder: SensorRecord.Builder? = null

            suspend fun flushPendingRecords() {
              val batch = pendingAggregateBuilder?.build() ?: pendingSingleRecord ?: return
              pendingSingleRecord = null
              pendingAggregateBuilder = null

              dispatchRecord(batch, session.metadata)

              val pos = batch.timestampNs.nanoseconds.coerceIn(Duration.ZERO, duration)
              timing.onRecordDispatched(pos)
              val nowMark = TimeSource.Monotonic.markNow()
              lastFlushMark = nowMark
              if (lastUiUpdateMark == null || lastUiUpdateMark!!.elapsedNow() >= UI_UPDATE_INTERVAL) {
                lastUiUpdateMark = nowMark
                schedulePlaybackProgressUiUpdate(job, pendingUiPosition, uiUpdateScheduled, pos, duration)
              }
            }

            session.forEachPlaybackRecord(startOffset) { record ->
              val recordTimestamp = record.timestampNs.nanoseconds
              currentCoroutineContext().ensureActive()
              while (true) {
                val (remainingWallTime, _) = timing.computeWaitAndProgress(recordTimestamp)
                if (!remainingWallTime.isPositive()) break

                if (pendingSingleRecord != null || pendingAggregateBuilder != null) {
                  flushPendingRecords()
                  continue
                }

                timing.setCurrentRecordTarget(recordTimestamp)
                delay(minOf(remainingWallTime, UI_UPDATE_INTERVAL))
                currentCoroutineContext().ensureActive()
                if (lastUiUpdateMark == null || lastUiUpdateMark!!.elapsedNow() >= UI_UPDATE_INTERVAL) {
                  lastUiUpdateMark = TimeSource.Monotonic.markNow()
                  val (_, pos) = timing.computeWaitAndProgress(recordTimestamp)
                  schedulePlaybackProgressUiUpdate(job, pendingUiPosition, uiUpdateScheduled, pos, duration)
                }
              }
              currentCoroutineContext().ensureActive()

              if (pendingSingleRecord == null && pendingAggregateBuilder == null) {
                timing.setCurrentRecordTarget(recordTimestamp)
                pendingSingleRecord = record
              } else {
                val builder =
                  pendingAggregateBuilder
                    ?: SensorRecord.newBuilder().also {
                      mergeRecordIntoAggregate(it, pendingSingleRecord!!)
                      pendingSingleRecord = null
                      pendingAggregateBuilder = it
                    }
                builder.timestampNs = record.timestampNs
                mergeRecordIntoAggregate(builder, record)
              }

              if (lastFlushMark.elapsedNow() >= UI_UPDATE_INTERVAL) {
                flushPendingRecords()
              }
            }

            flushPendingRecords()
          }
        } catch (e: IOException) {
          thisLogger().warn("Unable to read sensor session '${session.path}'", e)
          finishPlaybackWithError(job, e.message)
        } catch (e: StatusException) {
          finishPlaybackWithError(job, e.message)
        } catch (e: StatusRuntimeException) {
          finishPlaybackWithError(job, e.message)
        } catch (e: EmulatorController.RetryException) {
          finishPlaybackWithError(job, e.message)
        } finally {
          withContext(NonCancellable + uiContext) {
            if (playbackJob === job) {
              stopPlayback(resetPosition = true, cancelJob = false)
            }
          }
        }
      }

    playbackJob = job
    activePlaybackTiming = timing
    onPlaybackStateChanged()
    job.start()
  }

  private suspend fun showAsyncError(@Nls errorMessage: String?) {
    if (errorMessage == null) return
    withContext(uiContext) { onError(errorMessage) }
  }

  private suspend fun finishPlaybackWithError(job: Job, @Nls errorMessage: String?) {
    withContext(uiContext) {
      if (playbackJob === job) {
        stopPlayback(resetPosition = true, cancelJob = false)
      }
      errorMessage?.let(onError)
    }
  }

  private fun schedulePlaybackProgressUiUpdate(
    job: Job,
    pendingUiPosition: AtomicReference<Duration>,
    uiUpdateScheduled: AtomicBoolean,
    position: Duration,
    duration: Duration,
  ) {
    pendingUiPosition.set(position)
    if (uiUpdateScheduled.compareAndSet(false, true)) {
      scope.launch(uiContext) {
        try {
          while (playbackJob === job && !isSliderAdjusting()) {
            val pos = pendingUiPosition.get()
            updatePlaybackProgressUi(pos, duration)
            uiUpdateScheduled.set(false)
            if (playbackJob !== job || isSliderAdjusting() || pendingUiPosition.get() == pos) break
            if (!uiUpdateScheduled.compareAndSet(false, true)) break
          }
        } finally {
          uiUpdateScheduled.set(false)
        }
      }
    }
  }

  private fun updatePlaybackProgressUi(position: Duration, duration: Duration) {
    currentPosition = position
    onProgressChanged(position, duration, true)
  }

  private class ActivePlaybackTiming(
    private val initialOffset: Duration,
    initialSpeedMultiplier: Float,
    val duration: Duration,
  ) {
    private val lock = Any()
    private var anchorMark: TimeMark? = null
    private var anchorSessionPos: Duration = initialOffset
    private var speedMultiplier: Float = initialSpeedMultiplier
    private var lastReportedPosition: Duration = initialOffset
    private var lastDispatchedPosition: Duration = initialOffset
    private var currentRecordTarget: Duration = initialOffset

    fun start() =
      synchronized(lock) {
        anchorSessionPos = initialOffset
        anchorMark = TimeSource.Monotonic.markNow()
        lastReportedPosition = initialOffset
        lastDispatchedPosition = initialOffset
        currentRecordTarget = initialOffset
      }

    fun isStarted(): Boolean = synchronized(lock) { anchorMark != null }

    private fun rawSessionPositionLocked(): Duration {
      val mark = anchorMark ?: return anchorSessionPos
      val elapsedWall = mark.elapsedNow().coerceAtLeast(Duration.ZERO)
      val deltaSession = elapsedWall * speedMultiplier.toDouble()
      return (anchorSessionPos + deltaSession).coerceAtLeast(anchorSessionPos)
    }

    fun updateSpeed(newSpeedMultiplier: Float) =
      synchronized(lock) {
        if (anchorMark != null) {
          val minPos = maxOf(lastReportedPosition, lastDispatchedPosition).coerceIn(Duration.ZERO, duration)
          val maxPos = (currentRecordTarget - 1.nanoseconds).coerceIn(minPos, duration)
          anchorSessionPos = rawSessionPositionLocked().coerceIn(minPos, maxPos)
          lastReportedPosition = anchorSessionPos
          anchorMark = TimeSource.Monotonic.markNow()
        }
        speedMultiplier = newSpeedMultiplier
      }

    fun setCurrentRecordTarget(recordTimestamp: Duration) =
      synchronized(lock) {
        currentRecordTarget = recordTimestamp
      }

    fun onRecordDispatched(dispatchedPos: Duration) =
      synchronized(lock) {
        lastDispatchedPosition = dispatchedPos
        if (dispatchedPos > lastReportedPosition) {
          lastReportedPosition = dispatchedPos
        }
        currentRecordTarget = dispatchedPos
      }

    fun computeWaitAndProgress(recordTimestamp: Duration): Pair<Duration, Duration> =
      synchronized(lock) {
        val rawPos = rawSessionPositionLocked()
        val remainingSession = recordTimestamp - rawPos
        val remainingWall =
          if (!remainingSession.isPositive()) {
            Duration.ZERO
          } else {
            remainingSession / speedMultiplier.toDouble()
          }
        val minPos = maxOf(lastReportedPosition, lastDispatchedPosition).coerceIn(Duration.ZERO, duration)
        val maxGapPos = (recordTimestamp - 1.nanoseconds).coerceIn(minPos, duration)
        val clampedPos = rawPos.coerceIn(minPos, maxGapPos)
        lastReportedPosition = clampedPos
        remainingWall to clampedPos
      }

    fun computePausedPosition(currentUiPosition: Duration): Duration =
      synchronized(lock) {
        val minPos = maxOf(currentUiPosition, maxOf(lastReportedPosition, lastDispatchedPosition)).coerceIn(Duration.ZERO, duration)
        val maxPos = (currentRecordTarget - 1.nanoseconds).coerceIn(minPos, duration)
        val pausedPos = rawSessionPositionLocked().coerceIn(minPos, maxPos)
        lastReportedPosition = pausedPos
        pausedPos
      }
  }

  companion object {
    const val SLIDER_MAX = 1000
    private val UI_UPDATE_INTERVAL = 16.milliseconds

    fun formatTimeProgress(current: Duration, total: Duration): String = "${formatDuration(current)} / ${formatDuration(total)}"

    fun sliderValueToPosition(sliderValue: Int, duration: Duration): Duration {
      if (!duration.isPositive()) return Duration.ZERO
      return duration * (sliderValue.coerceIn(0, SLIDER_MAX).toDouble() / SLIDER_MAX)
    }

    fun positionToSliderValue(position: Duration, duration: Duration): Int {
      if (!duration.isPositive()) return 0
      return ((position.coerceIn(Duration.ZERO, duration) / duration) * SLIDER_MAX).roundToInt()
    }
  }
}
