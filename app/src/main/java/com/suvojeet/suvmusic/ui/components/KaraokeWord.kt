package com.suvojeet.suvmusic.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.suvojeet.suvmusic.providers.lyrics.LyricsLine
import com.suvojeet.suvmusic.providers.lyrics.LyricsWord
import kotlin.math.PI
import kotlin.math.sin

/**
 * Apple Music style word-by-word lyric ("karaoke") rendering.
 *
 *  - The word is filled left -> right exactly in sync with [currentTimeMs].
 *  - A "held" word (long note such as "Aaaaa", "Ooooh", or any word sung for >= 1s)
 *    grows a little bigger and gets a soft glow while it is being sung.
 */

/** Minimum duration (ms) for a word with real word timestamps to count as a held note. */
private const val HELD_NOTE_MIN_DURATION_MS = 1000L

/** 3+ repeated letters in a row: "Aaaaa", "Ooooh", "Yeaaah", "Heyyy" ... */
private val STRETCHED_REGEX = Regex("(\\p{L})\\1{2,}", RegexOption.IGNORE_CASE)

/** Short vocal-only syllables that are almost always sustained when sung. */
private val VOCALISE_REGEX = Regex("^[\\s(\\[]*(a+h*|o+h*|u+h*|e+h*|ah+|oh+|ooh+|aah+|ha+|la+|na+)[\\s)\\].,!?]*$", RegexOption.IGNORE_CASE)

fun isHeldNote(word: LyricsWord, wordTimingIsReal: Boolean): Boolean {
    val text = word.text.trim()
    if (text.isEmpty()) return false
    val duration = word.endTimeMs - word.startTimeMs
    if (STRETCHED_REGEX.containsMatchIn(text)) return true
    if (wordTimingIsReal) {
        // Real per-word timing available: a long note is a long note.
        if (duration >= HELD_NOTE_MIN_DURATION_MS) return true
        if (duration >= 600L && VOCALISE_REGEX.matches(text)) return true
    } else {
        // Timing is only estimated, so rely on the text itself.
        if (VOCALISE_REGEX.matches(text) && text.length >= 3) return true
    }
    return false
}

/**
 * Returns the words of a line together with a flag telling if their timing is real.
 * Plain line-level LRC has no word timestamps, so the line time is split between the
 * words (weighted by length) to still get a smooth word-by-word effect.
 */
fun LyricsLine.karaokeWords(): Pair<List<LyricsWord>, Boolean>? {
    val real = words
    if (!real.isNullOrEmpty()) return real to true
    if (text.isBlank() || isHeader) return null
    val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val start = startTimeMs
    val end = if (endTimeMs > start) endTimeMs else start + parts.size * 400L
    val total = (end - start).coerceAtLeast(1L)
    val weightSum = parts.sumOf { it.length + 1 }.toFloat()
    var cursor = start.toFloat()
    val out = parts.map { p ->
        val span = total * ((p.length + 1) / weightSum)
        val w = LyricsWord(text = p, startTimeMs = cursor.toLong(), endTimeMs = (cursor + span).toLong())
        cursor += span
        w
    }
    return out to false
}

@Composable
fun KaraokeWord(
    word: LyricsWord,
    currentTimeMs: Long,
    isLineActive: Boolean,
    wordTimingIsReal: Boolean,
    baseColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    glowColor: Color = baseColor,
) {
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
    val rawProgress = ((currentTimeMs - word.startTimeMs).toFloat() / duration).coerceIn(0f, 1f)

    // Past words of the active line stay fully lit, future words stay dim.
    val progress = if (isLineActive) rawProgress else 0f
    val isSinging = isLineActive && currentTimeMs >= word.startTimeMs && currentTimeMs < word.endTimeMs
    val held = isHeldNote(word, wordTimingIsReal)

    // Bell curve 0 -> 1 -> 0 across the word: glow breathes with the note.
    val bell = if (isSinging) sin(PI.toFloat() * rawProgress).coerceIn(0f, 1f) else 0f

    val targetScale = when {
        isSinging && held -> 1.18f
        isSinging -> 1.04f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "karaokeScale"
    )

    val targetGlow = if (isSinging && held) 0.55f + 0.45f * bell else 0f
    val glow by animateFloatAsState(
        targetValue = targetGlow,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "karaokeGlow"
    )

    val dimColor = baseColor.copy(alpha = if (isLineActive) 0.38f else 0.3f)
    val litColor = baseColor

    val text = word.text + " "

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                // Soft halo behind the word (works outside the text bounds).
                if (glow > 0.01f) {
                    val padX = size.width * 0.35f + 12.dp.toPx()
                    val padY = size.height * 0.45f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = 0.32f * glow), Color.Transparent),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = (size.width / 2f + padX).coerceAtLeast(1f)
                        ),
                        topLeft = Offset(-padX, -padY),
                        size = Size(size.width + padX * 2f, size.height + padY * 2f)
                    )
                }
            }
    ) {
        // 1) dim base text
        Text(text = text, style = textStyle, color = dimColor)

        // 2) bright text revealed from left to right by the sung progress
        if (progress > 0f) {
            Text(
                text = text,
                style = textStyle.copy(
                    shadow = if (glow > 0.01f) {
                        Shadow(
                            color = glowColor.copy(alpha = 0.9f * glow),
                            offset = Offset.Zero,
                            blurRadius = 10f + 26f * glow
                        )
                    } else null
                ),
                color = litColor,
                modifier = Modifier.drawWithContent {
                    val right = if (progress >= 1f) Float.MAX_VALUE / 4f else size.width * progress
                    // Big bounds on the other sides so the glow is not clipped.
                    clipRect(left = -10000f, top = -10000f, right = right, bottom = 10000f) {
                        this@drawWithContent.drawContent()
                    }
                }
            )
        }
    }
}
