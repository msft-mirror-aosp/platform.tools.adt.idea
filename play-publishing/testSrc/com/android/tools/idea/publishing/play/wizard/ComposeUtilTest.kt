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
package com.android.tools.idea.publishing.play.wizard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import com.android.mockito.kotlin.whenever
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.BrowserUtil
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.linkStyle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

@RunsInEdt
class ComposeUtilTest {
  private val edtRule = EdtRule()
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(edtRule).around(composeTestRule)

  @Test
  fun textWithExternalLinks_customFontScale_preservesDpIconPlaceholderSize() {
    var currentDensity by mutableStateOf(Density(density = 1f, fontScale = 1.5f))
    var expectedIconSize: DpSize = DpSize.Zero

    composeTestRule.setContent {
      expectedIconSize = JewelTheme.linkStyle.metrics.iconSize
      CompositionLocalProvider(LocalDensity provides currentDensity) {
        TextWithExternalLinks {
          append("Read the ")
          appendExternalLink("publishing guide", "https://developer.android.com/publish")
          append(" before continuing.")
        }
      }
    }

    val textNode = composeTestRule.onNodeWithText("Read the publishing guide", substring = true)
    textNode.assertIsDisplayed()

    // Under fontScale = 1.5f, placeholder rect should still match the dp-based iconSize (16px at density = 1f, not 24px).
    val largeFontLayout = textNode.fetchTextLayoutResult()
    val largeFontPlaceholderRect = checkNotNull(largeFontLayout.placeholderRects.single())
    assertThat(largeFontPlaceholderRect.width).isWithin(0.01f).of(with(currentDensity) { expectedIconSize.width.toPx() })
    assertThat(largeFontPlaceholderRect.height).isWithin(0.01f).of(with(currentDensity) { expectedIconSize.height.toPx() })

    // Re-keying remember on density should also resize accurately when fontScale drops below 1f.
    currentDensity = Density(density = 2f, fontScale = 0.75f)
    composeTestRule.waitForIdle()

    val smallFontLayout = textNode.fetchTextLayoutResult()
    val smallFontPlaceholderRect = checkNotNull(smallFontLayout.placeholderRects.single())
    assertThat(smallFontPlaceholderRect.width).isWithin(0.01f).of(with(currentDensity) { expectedIconSize.width.toPx() })
    assertThat(smallFontPlaceholderRect.height).isWithin(0.01f).of(with(currentDensity) { expectedIconSize.height.toPx() })
  }

  @Test
  fun textWithExternalLinks_duplicatePlainTextAndMultipleLinks_clicksAnnotatedLinkTarget() {
    val openedUrls = mutableListOf<String>()

    composeTestRule.setContent {
      TextWithExternalLinks(onOpenLink = openedUrls::add) {
        append("Existing apps in Play Console must be configured first. Go to ")
        appendExternalLink("Play Console", "https://play.google.com/console/")
        append(" or ")
        appendExternalLink("Learn more", "https://developer.android.com/publish")
        append(".")
      }
    }

    val textNode = composeTestRule.onNodeWithText("Existing apps in Play Console", substring = true)

    val playConsoleRange = textNode.findExternalLinkRange("Play Console")
    assertThat((playConsoleRange.item as LinkAnnotation.Url).url).isEqualTo("https://play.google.com/console/")
    textNode.clickLinkRange(playConsoleRange)
    assertThat(openedUrls).containsExactly("https://play.google.com/console/")

    openedUrls.clear()
    val learnMoreRange = textNode.findExternalLinkRange("Learn more")
    assertThat((learnMoreRange.item as LinkAnnotation.Url).url).isEqualTo("https://developer.android.com/publish")
    textNode.clickLinkRange(learnMoreRange)
    assertThat(openedUrls).containsExactly("https://developer.android.com/publish")
  }

  @Test
  fun textWithExternalLinks_recomposition_preservesAnnotatedStringEqualityAndUpdatesCallback() {
    val firstOpenedUrls = mutableListOf<String>()
    val secondOpenedUrls = mutableListOf<String>()
    var recomposeTick by mutableIntStateOf(0)
    var useSecondCallback by mutableStateOf(false)

    composeTestRule.setContent {
      val onOpenLink: (String) -> Unit = if (useSecondCallback) secondOpenedUrls::add else firstOpenedUrls::add
      TextWithExternalLinks(onOpenLink = onOpenLink) {
        // Read recomposeTick inside the inline builder so incrementing it forces TextWithExternalLinks to recompose.
        recomposeTick.let {}
        append("Visit ")
        appendExternalLink("Play Console", "https://play.google.com/console/")
        append(" to continue.")
      }
    }

    val textNode = composeTestRule.onNodeWithText("Visit Play Console", substring = true)
    val initialAnnotatedText = textNode.fetchTextLayoutResult().layoutInput.text
    val initialLinkAnnotation = initialAnnotatedText.getLinkAnnotations(0, initialAnnotatedText.length).single().item

    recomposeTick++
    composeTestRule.waitForIdle()

    val recomposedAnnotatedText = textNode.fetchTextLayoutResult().layoutInput.text
    val recomposedLinkAnnotation = recomposedAnnotatedText.getLinkAnnotations(0, recomposedAnnotatedText.length).single().item
    assertThat(recomposedLinkAnnotation).isEqualTo(initialLinkAnnotation)
    assertThat(recomposedAnnotatedText).isEqualTo(initialAnnotatedText)

    textNode.clickLinkRange(textNode.findExternalLinkRange("Play Console"))
    composeTestRule.waitForIdle()
    assertThat(firstOpenedUrls).containsExactly("https://play.google.com/console/")
    assertThat(secondOpenedUrls).isEmpty()

    val postClickAnnotatedText = textNode.fetchTextLayoutResult().layoutInput.text
    useSecondCallback = true
    composeTestRule.waitForIdle()

    val updatedCallbackAnnotatedText = textNode.fetchTextLayoutResult().layoutInput.text
    assertThat(updatedCallbackAnnotatedText).isEqualTo(postClickAnnotatedText)

    textNode.clickLinkRange(textNode.findExternalLinkRange("Play Console"))
    assertThat(firstOpenedUrls).containsExactly("https://play.google.com/console/")
    assertThat(secondOpenedUrls).containsExactly("https://play.google.com/console/")
  }
}

internal fun SemanticsNodeInteraction.clickExternalLinkAndAssertBrowsedUrl(linkText: String, expectedUrl: String) {
  val linkRange = findExternalLinkRange(linkText)
  Mockito.mockStatic(BrowserUtil::class.java).use { browserUtil ->
    val browsedUrls = mutableListOf<String>()
    browserUtil.whenever<Unit> { BrowserUtil.browse(anyString()) }.thenAnswer { browsedUrls.add(it.arguments[0] as String) }
    clickLinkRange(linkRange)
    assertThat(browsedUrls).containsExactly(expectedUrl)
  }
}

private fun SemanticsNodeInteraction.findExternalLinkRange(linkText: String): AnnotatedString.Range<LinkAnnotation> {
  val annotatedText = fetchTextLayoutResult().layoutInput.text
  return annotatedText.getLinkAnnotations(0, annotatedText.length).single { range ->
    annotatedText.text.substring(range.start, range.end).removeSuffix(" ") == linkText
  }
}

private fun SemanticsNodeInteraction.clickLinkRange(linkRange: AnnotatedString.Range<LinkAnnotation>) {
  val layoutResult = fetchTextLayoutResult()
  performMouseInput { click(layoutResult.getBoundingBox(linkRange.start).center) }
}

private fun SemanticsNodeInteraction.fetchTextLayoutResult(): TextLayoutResult {
  val results = mutableListOf<TextLayoutResult>()
  performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
  return results.single()
}
