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
@file:Suppress("OPT_IN_USAGE", "UnstableApiUsage")

package com.android.tools.idea.whatsnew.assistant.v2.ui

import org.commonmark.node.Block
import org.commonmark.node.CustomBlock
import org.commonmark.parser.Parser
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.MarkdownBlockProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor

/**
 * A custom [MarkdownBlock.CustomBlock] representing an HTML comment specifying a Studio feature flag,
 * e.g. `<!-- FEATURE_FLAG=studiobot.local.gemma.enabled -->`.
 */
@OptIn(ExperimentalJewelApi::class)
internal data class FeatureFlagMarkdownBlock(val flagName: String) : MarkdownBlock.CustomBlock

private class FeatureFlagBlock(val flagName: String) : CustomBlock()

private val FEATURE_FLAG_REGEX = Regex("""<!--\s*FEATURE_FLAG\s*=\s*(\S+?)\s*-->""")

/**
 * A [MarkdownProcessorExtension] that parses `<!-- FEATURE_FLAG=... -->` comments into [FeatureFlagMarkdownBlock] marker nodes.
 */
@OptIn(ExperimentalJewelApi::class)
internal object FeatureFlagProcessorExtension : MarkdownProcessorExtension {
  override val parserExtension: Parser.ParserExtension =
    Parser.ParserExtension { parserBuilder -> parserBuilder.customBlockParserFactory(FeatureFlagBlockParser.Factory()) }

  override val blockProcessorExtension: MarkdownBlockProcessorExtension =
    object : MarkdownBlockProcessorExtension {
      override fun canProcess(block: CustomBlock): Boolean = block is FeatureFlagBlock

      override fun processMarkdownBlock(block: CustomBlock, processor: MarkdownProcessor): MarkdownBlock.CustomBlock? =
        (block as? FeatureFlagBlock)?.let { FeatureFlagMarkdownBlock(it.flagName) }
    }
}

private class FeatureFlagBlockParser(flagName: String) : AbstractBlockParser() {
  private val block = FeatureFlagBlock(flagName)

  override fun getBlock(): Block = block

  override fun tryContinue(state: ParserState): BlockContinue? = BlockContinue.none()

  class Factory : AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
      if (state.indent >= 4) {
        return BlockStart.none()
      }
      val line = state.line.content
      val nextNonSpace = state.nextNonSpaceIndex
      val match = FEATURE_FLAG_REGEX.find(line, startIndex = nextNonSpace) ?: return BlockStart.none()
      if (match.range.first != nextNonSpace) {
        return BlockStart.none()
      }
      return BlockStart.of(FeatureFlagBlockParser(match.groupValues[1])).atIndex(line.length)
    }
  }
}
