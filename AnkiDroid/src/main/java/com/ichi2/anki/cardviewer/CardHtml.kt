/*
 *  Copyright (c) 2021 David Allison <davidallisongithub@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.cardviewer

import android.content.Context
import com.ichi2.anki.R
import com.ichi2.anki.TtsParser
import com.ichi2.libanki.*
import com.ichi2.libanki.Sound.SingleSoundSide
import com.ichi2.libanki.Sound.SingleSoundSide.ANSWER
import com.ichi2.libanki.Sound.SingleSoundSide.QUESTION
import com.ichi2.libanki.template.MathJax
import net.ankiweb.rsdroid.RustCleanup
import org.json.JSONObject
import timber.log.Timber
import java.util.regex.Pattern

@RustCleanup("transition to an instance of TemplateRenderOutput")
class CardHtml(
    @RustCleanup("legacy")
    private val beforeSoundTemplateExpansion: String,
    private val ord: Int,
    private val context: HtmlGenerator,
    /** The side that [beforeSoundTemplateExpansion] was generated from */
    private val side: Side,
    @RustCleanup("Slow function, only used with legacy code")
    private val getAnswerContentWithoutFrontSideSlow: (() -> String),
    @RustCleanup("too many variables, combine once we move away from backend")
    private var questionSound: List<SoundOrVideoTag>? = null,
    private var answerSound: List<SoundOrVideoTag>? = null
) {
    fun getSoundTags(sideFor: Side): List<SoundOrVideoTag> {
        if (sideFor == this.side) {
            return getSoundsForCurrentSide()
        }

        if (sideFor == Side.BACK && side == Side.FRONT) {
            if (answerSound == null) {
                answerSound = Sound.extractTagsFromLegacyContent(getAnswerContentWithoutFrontSideSlow())
            }
            return answerSound!!
        }

        // Back wanting front, only possible if questionAv != null
        if (questionSound != null) {
            return questionSound!!
        }

        throw IllegalStateException("Attempted to get the front of the card when viewing back using legacy system")
    }

    @RustCleanup("unnecessarily complex")
    private fun getSoundsForCurrentSide(): List<SoundOrVideoTag> {
        // beforeSoundTemplateExpansion refers to the current side
        return if (this.side == Side.FRONT) {
            if (questionSound == null) {
                questionSound = Sound.extractTagsFromLegacyContent(beforeSoundTemplateExpansion)
            }
            questionSound!!
        } else {
            if (answerSound == null) {
                answerSound = Sound.extractTagsFromLegacyContent(getAnswerContentWithoutFrontSideSlow())
            }
            return answerSound!!
        }
    }

    fun getTemplateHtml(): String {
        val content = getContent()

        val requiresMathjax = MathJax.textContainsMathjax(content)

        val style = getStyle()
        val script = getScripts(requiresMathjax)
        val cardClass = getCardClass(requiresMathjax)

        Timber.v("content card = \n %s", content)
        Timber.v("::style:: / %s", style)

        return context.cardTemplate.render(content, style, script, cardClass)
    }

    private fun getContent(): String {
        var content = context.expandSounds(beforeSoundTemplateExpansion)
        content = CardAppearance.fixBoldStyle(content)
        return content
    }

    private fun getStyle(): String {
        return context.cardAppearance.style
    }

    private fun getCardClass(requiresMathjax: Boolean): String {
        // CSS class for card-specific styling
        return if (requiresMathjax) {
            context.cardAppearance.getCardClass(ord + 1) + " mathjax-needs-to-render"
        } else {
            context.cardAppearance.getCardClass(ord + 1)
        }
    }

    private fun getScripts(requiresMathjax: Boolean): String {
        return when (requiresMathjax) {
            false -> ""
            true ->
                """        <script src="/assets/mathjax/conf.js"> </script>
        <script src="/assets/mathjax/tex-chtml.js"> </script>"""
        }
    }

    companion object {
        fun createInstance(card: Card, side: Side, context: HtmlGenerator): CardHtml {
            val content = displayString(card, side, context)

            val renderOutput = card.renderOutput()
            val questionAv = renderOutput.questionAvTags
            val answerAv = renderOutput.answerAvTags
            val questionSound: List<SoundOrVideoTag> =
                questionAv.filterIsInstance(SoundOrVideoTag::class.java)
            val answerSound: List<SoundOrVideoTag> = answerAv.filterIsInstance(SoundOrVideoTag::class.java)

            // legacy (slow) function to return the answer without the front side
            fun getAnswerWithoutFrontSideLegacy(): String = removeFrontSideAudio(card, card.answer())

            return CardHtml(content, card.ord, context, side, ::getAnswerWithoutFrontSideLegacy, questionSound, answerSound)
        }

        /**
         * String, as it will be displayed in the web viewer.
         * Sound/video removed, image escaped...
         * Or warning if required
         * TODO: This is no longer entirely true as more post-processing occurs
         */
        private fun displayString(card: Card, side: Side, context: HtmlGenerator): String {
            var content: String = if (side == Side.FRONT) card.question() else card.answer()
            content = card.col.media.escapeMediaFilenames(content)
            content = context.filterTypeAnswer(content, side)
            Timber.v("question: '%s'", content)
            return enrichWithQADiv(content)
        }

        /**
         * Adds a div html tag around the contents to have an indication, where answer/question is displayed
         *
         * @param content The content to surround with tags.
         * @return The enriched content
         */
        fun enrichWithQADiv(content: String?): String {
            val sb = StringBuilder()
            sb.append("""<div id="qa">""")
            sb.append(content)
            sb.append("</div>")
            return sb.toString()
        }

        /**
         * @return the answer part of this card's template as entered by user, without any parsing
         */
        private fun getAnswerFormat(card: Card): String {
            val model = card.model()
            val template: JSONObject = if (model.isStd) {
                model.getJSONArray("tmpls").getJSONObject(card.ord)
            } else {
                model.getJSONArray("tmpls").getJSONObject(0)
            }
            return template.getString("afmt")
        }

        /**
         * Removes first occurrence in answerContent of any audio that is present due to use of
         * {{FrontSide}} on the answer.
         * @param card              The card to strip content from
         * @param answerContent     The content from which to remove front side audio.
         * @return The content stripped of audio due to {{FrontSide}} inclusion.
         */
        fun removeFrontSideAudio(card: Card, answerContent: String): String {
            val answerFormat = getAnswerFormat(card)
            var newAnswerContent = answerContent
            if (answerFormat.contains("{{FrontSide}}")) { // possible audio removal necessary
                val frontSideFormat = card.renderOutput(false).questionText
                val audioReferences = Sound.SOUND_PATTERN.matcher(frontSideFormat)
                // remove the first instance of audio contained in "{{FrontSide}}"
                while (audioReferences.find()) {
                    newAnswerContent = newAnswerContent.replaceFirst(Pattern.quote(audioReferences.group()).toRegex(), "")
                }
            }
            return newAnswerContent
        }

        fun legacyGetTtsTags(card: Card, cardSide: SingleSoundSide, context: Context): List<TTSTag> {
            val cardSideContent: String = when (cardSide) {
                QUESTION -> card.question(true)
                ANSWER -> card.pureAnswer
            }
            return TtsParser.getTextsToRead(cardSideContent, context.getString(R.string.reviewer_tts_cloze_spoken_replacement))
        }
    }
}
