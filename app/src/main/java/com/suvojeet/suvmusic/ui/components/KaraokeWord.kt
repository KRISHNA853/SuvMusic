package com.suvojeet.suvmusic.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Apple Music style word-by-word lyric ("karaoke") rendering.
 *
 *  - The word is filled left -> right with a SOFT (feathered) edge, driven by a smooth clock.
 *  - Every word that is being sung gets a small lift, a tiny scale-up and a gentle glow.
 *  - A "held" word (long note such as "Aaaaa", "Ooooh", or any word sung for ~0.8s+)
 *    grows bigger and gets a strong glow + halo that breathes with the note.
 */

/** Real word timing: a word sung at least this long counts as a held note. */
private const val HELD_NOTE_MIN_DURATION_MS = 800L

/** 3+ repeated letters in a row: "Aaaaa", "Ooooh", "Yeaaah", "Heyyy" ... */
private val STRETCHED_REGEX = Regex("(\\p{L})\\1{2,}", RegexOption.IGNORE_CASE)

/** Short vocal-only syllables that are almost always sustained when sung. */
private val VOCALISE_REGEX = Regex("^[\\s(\\[]*(a+h*|o+h*|u+h*|e+h*|ah+|oh+|ooh+|aah+|ha+|la+|na+|आ+|ओ+|अ+|हो+|आह+)[\\s)\\].,!?]*$", RegexOption.IGNORE_CASE)

fun isHeldNote(word: LyricsWord, wordTimingIsReal: Boolean): Boolean {
    val text = word.text.trim()
    if (text.isEmpty()) return false
    val duration = word.endTimeMs - word.startTimeMs
    if (STRETCHED_REGEX.containsMatchIn(text)) return true
    if (wordTimingIsReal) {
        if (duration >= HELD_NOTE_MIN_DURATION_MS) return true
        if (duration >= 500L && VOCALISE_REGEX.matches(text)) return true
    } else {
        if (VOCALISE_REGEX.matches(text) && text.length >= 2) return true
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

/**
 * @param timeProvider smooth playback clock (ms). It is only read in the draw phase and inside
 * derivedStateOf, so a word never recomposes every frame.
 */
@Composable
fun KaraokeWord(
    word: LyricsWord,
    timeProvider: () -> Long,
    isLineActive: Boolean,
    wordTimingIsReal: Boolean,
    baseColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    glowColor: Color = baseColor,
) {
    val start = word.startTimeMs
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
    val held = remember(word, wordTimingIsReal) { isHeldNote(word, wordTimingIsReal) }
    val lineActive by rememberUpdatedState(isLineActive)

    // 0 = not singing, 1 = normal word, 2..6 = held note (peaks in the middle of the note).
    val level by remember(word, held) {
        derivedStateOf {
            val t = timeProvider()
            if (!lineActive || t < start || t >= start + duration) {
                0
            } else if (!held) {
                1
            } else {
                val p = (t - start).toFloat() / duration
                2 + (sin(PI.toFloat() * p) * 4f).roundToInt()
            }
        }
    }
    val singing = level > 0

    val scale by animateFloatAsState(
        targetValue = when {
            singing && held -> 1.2f
            singing -> 1.05f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "karaokeScale"
    )
    val lift by animateFloatAsState(
        targetValue = if (singing) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "karaokeLift"
    )
    val glow by animateFloatAsState(
        targetValue = when {
            level == 0 -> 0f
            level == 1 -> 0.4f
            else -> 0.6f + 0.4f * ((level - 2) / 4f)
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "karaokeGlow"
    )

    val dimColor = baseColor.copy(alpha = if (isLineActive) 0.38f else 0.3f)
    val text = word.text + " "
    val litStyle = textStyle.copy(
        shadow = if (glow > 0.01f) {
            Shadow(
                color = glowColor.copy(alpha = (0.95f * glow).coerceAtMost(0.95f)),
                offset = Offset.Zero,
                blurRadius = 8f + 34f * glow
            )
        } else null
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = -2.dp.toPx() * lift
            }
            .drawBehind {
                // Soft halo behind the word (works outside the text bounds).
                if (glow > 0.01f) {
                    val padX = size.width * 0.35f + 12.dp.toPx()
                    val padY = size.height * 0.45f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = 0.28f * glow), Color.Transparent),
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

        if (isLineActive) {
            // 2) soft edge: translucent copies clipped at staggered positions -> smooth feather
            Text(
                text = text,
                style = litStyle,
                color = baseColor.copy(alpha = 0.4f),
                modifier = Modifier.drawWithContent {
                    val p = ((timeProvider() - start).toFloat() / duration).coerceIn(0f, 1f)
                    if (p <= 0f || p >= 1f) return@drawWithContent
                    val feather = minOf(size.width * 0.45f, 40.dp.toPx()).coerceAtLeast(10f)
                    val edge = p * (size.width + feather)
                    for (k in 0 until 4) {
                        val right = edge - feather + feather * (k + 1) / 4f
                        if (right <= 0f) continue
                        clipRect(left = -10000f, top = -10000f, right = right, bottom = 10000f) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
            )
            // 3) solid, fully lit part behind the feather
            Text(
                text = text,
                style = litStyle,
                color = baseColor,
                modifier = Modifier.drawWithContent {
                    val p = ((timeProvider() - start).toFloat() / duration).coerceIn(0f, 1f)
                    if (p <= 0f) return@drawWithContent
                    val feather = minOf(size.width * 0.45f, 40.dp.toPx()).coerceAtLeast(10f)
                    val right = if (p >= 1f) 100000f else p * (size.width + feather) - feather
                    if (right <= 0f) return@drawWithContent
                    clipRect(left = -10000f, top = -10000f, right = right, bottom = 10000f) {
                        this@drawWithContent.drawContent()
                    }
                }
            )
        }
    }
}
