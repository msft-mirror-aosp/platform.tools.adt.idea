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

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import org.junit.Test

class SentimentCheckerTest {

  private val userId = "test-user-id"
  private val answeredWait = 90
  private val declinedWait = 7

  @Test
  fun `shouldRequest returns true when answered cooldown has passed`() {
    val cycle = 180
    val startDate = createDate(2026, Calendar.JANUARY, 1)

    // Find a magic date for this user
    val magicDayOffset =
      (0 until cycle).first { days ->
        SentimentChecker.shouldRequest(addDays(startDate, days), null, null, cycle, declinedWait, userId)
      }

    val magicDate = addDays(startDate, magicDayOffset)
    val answeredDate = addDays(magicDate, -(cycle + 1)) // Answered more than one cycle ago

    val result =
      SentimentChecker.shouldRequest(
        now = magicDate,
        lastSentimentAnswerDate = answeredDate,
        lastSentimentQuestionDate = null,
        surveyAnsweredWaitInterval = cycle,
        surveyDeclinedWaitInterval = declinedWait,
        userId = userId,
      )

    assertThat(result).isTrue()
  }

  @Test
  fun `shouldRequest returns false when answered cooldown has not passed`() {
    val answeredDate = createDate(2026, Calendar.JANUARY, 10)
    // Only 5 days later
    val now = createDate(2026, Calendar.JANUARY, 15)

    val result =
      SentimentChecker.shouldRequest(
        now = now,
        lastSentimentAnswerDate = answeredDate,
        lastSentimentQuestionDate = null,
        surveyAnsweredWaitInterval = answeredWait,
        surveyDeclinedWaitInterval = declinedWait,
        userId = userId,
      )

    assertThat(result).isFalse()
  }

  @Test
  fun `shouldRequest respects declined cooldown when question was asked but not answered`() {
    val questionDate = createDate(2026, Calendar.JANUARY, 1)

    // Within cooldown (6 days later)
    val tooSoon = createDate(2026, Calendar.JANUARY, 1 + declinedWait - 1)
    assertThat(
        SentimentChecker.shouldRequest(
          now = tooSoon,
          lastSentimentAnswerDate = null,
          lastSentimentQuestionDate = questionDate,
          surveyAnsweredWaitInterval = answeredWait,
          surveyDeclinedWaitInterval = declinedWait,
          userId = userId,
        )
      )
      .isFalse()

    // At cooldown boundary (7 days later)
    val enoughTime = createDate(2026, Calendar.JANUARY, 1 + declinedWait)
    assertThat(
        SentimentChecker.shouldRequest(
          now = enoughTime,
          lastSentimentAnswerDate = null,
          lastSentimentQuestionDate = questionDate,
          surveyAnsweredWaitInterval = answeredWait,
          surveyDeclinedWaitInterval = declinedWait,
          userId = userId,
        )
      )
      .isTrue()
  }

  @Test
  fun `shouldRequest uses magic date for new user`() {
    // For a new user, exactly one day in the cycle should return true.
    val year = 2026
    val cycle = 180
    val startDate = createDate(year, Calendar.JANUARY, 1)

    val results =
      (0 until cycle).map { days ->
        SentimentChecker.shouldRequest(
          now = addDays(startDate, days),
          lastSentimentAnswerDate = null,
          lastSentimentQuestionDate = null,
          surveyAnsweredWaitInterval = cycle,
          surveyDeclinedWaitInterval = declinedWait,
          userId = userId,
        )
      }

    assertThat(results.count { it }).isEqualTo(1)
  }

  @Test
  fun `different users have different magic dates`() {
    val date = createDate(2026, Calendar.JANUARY, 1)
    val cycle = 365

    fun findMagicDay(uid: String): Int {
      return (0 until cycle).first { days ->
        SentimentChecker.shouldRequest(addDays(date, days), null, null, cycle, declinedWait, uid)
      }
    }

    val magic1 = findMagicDay("user-1")
    val magic2 = findMagicDay("user-2")

    assertThat(magic1).isNotEqualTo(magic2)
  }

  private fun createDate(y: Int, m: Int, d: Int): Date {
    return GregorianCalendar(y, m, d).time
  }

  private fun addDays(date: Date, days: Int): Date {
    val cal = Calendar.getInstance()
    cal.time = date
    cal.add(Calendar.DATE, days)
    return cal.time
  }
}
