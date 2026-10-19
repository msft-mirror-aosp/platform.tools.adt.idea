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
package com.android.tools.idea.stats

import com.google.common.hash.Hashing
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.abs

object SentimentChecker {

  /**
   * Determines whether the sentiment survey should be requested at this time.
   *
   * @param now The current date.
   * @param lastSentimentAnswerDate The date the user last answered the survey.
   * @param lastSentimentQuestionDate The date the user was last asked the survey.
   * @param surveyAnsweredWaitInterval The wait interval in days for requesting after an answered survey.
   * @param surveyDeclinedWaitInterval The wait interval in days for requesting after a declined or canceled survey.
   * @param userId The unique user ID for hashing.
   */
  internal fun shouldRequest(
    now: Date,
    lastSentimentAnswerDate: Date?,
    lastSentimentQuestionDate: Date?,
    surveyAnsweredWaitInterval: Int,
    surveyDeclinedWaitInterval: Int,
    userId: String,
  ): Boolean {
    // 1. Evaluate cooldown from previous explicit answer.
    if (lastSentimentAnswerDate != null) {
      val deadline = daysFromNow(now, -surveyAnsweredWaitInterval)
      if (lastSentimentAnswerDate.after(deadline)) {
        return false
      }
    }

    // 2. Cooldown for asked but not answered survey.
    if (lastSentimentQuestionDate != null) {
      val startOfWaitForRequest = daysFromNow(now, -surveyDeclinedWaitInterval)
      return !lastSentimentQuestionDate.after(startOfWaitForRequest)
    }

    // 3. New User: Calculate cyclic "magic date" based on user hash bucket
    val startOfYear = GregorianCalendar(now.year + 1900, 0, 1)
    startOfYear.timeZone = TimeZone.getTimeZone(ZoneOffset.UTC)

    val daysSinceJanFirst = ChronoUnit.DAYS.between(startOfYear.toInstant(), now.toInstant())
    val offset = abs(Hashing.farmHashFingerprint64().hashString(userId, StandardCharsets.UTF_8).asLong()) % surveyAnsweredWaitInterval
    return daysSinceJanFirst == offset
  }

  fun daysFromNow(now: Date, days: Int): Date {
    val calendar = Calendar.getInstance()
    calendar.time = now
    calendar.add(Calendar.DATE, days)
    return calendar.time
  }
}
