package de.hyprnode.slimy.art

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.model.isBirthday
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.timeIntervalSince
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The slime in the app: animated, jiggly, pokeable. Tap to poke it, rub it
 * to pet it, pull it to stretch it. When it's idle, how it feels shows now
 * and then: a wave when it's happy, a sulk when it's cross, a yawn when it's
 * sleepy. With reduced motion it holds still in the pose that fits, checked
 * once a second so reactions still end.
 *
 * @param override shows a specific slime instead of the player's (friends, previews); it can't be petted.
 * @param showsToast whether petting shows its line as a toast; off where the slime's speech is already on screen.
 */
@Composable
fun LiveSlimeView(
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    interactive: Boolean = true,
    override: Slime? = null,
    showsToast: Boolean = true,
) {
    val store = LocalGameStore.current
    val game by store.state.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val clock by if (reduceMotion) rememberClock(1000L) else rememberFrameClock(maxFps = 30)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val poke = remember { Animatable(0f) }
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val hearts = remember { mutableStateListOf<FloatingHeart>() }
    val gestures = remember { GestureState() }

    val current by rememberUpdatedState(override ?: game.slime)
    val reduce by rememberUpdatedState(reduceMotion)
    val toast by rememberUpdatedState(showsToast)
    val isOverride by rememberUpdatedState(override != null)

    fun pet() {
        if (isOverride) return
        store.pet(toast = toast)
        // Floating hearts are motion; with reduced motion the pet reaction says it.
        if (reduce) return
        val now = Instant.now()
        hearts.add(FloatingHeart(x = Random.nextDouble(-50.0, 50.0).toFloat(), born = now))
        hearts.removeAll { now.timeIntervalSince(it.born) > 1.5 }
    }

    fun pokeIt() {
        gestures.taps += 1
        Feedback.shared.play(Sound.pop)
        if (!reduce) {
            scope.launch {
                poke.snapTo(1f)
                poke.animateTo(0f, spring(dampingRatio = 0.17f, stiffness = 320f))
            }
        }
        if (gestures.taps % 5 == 0) pet()
    }

    val s = current
    val nowMillis = clock
    val now = Instant.ofEpochMilli(nowMillis)
    val t = referenceSeconds(nowMillis)
    val still = reduceMotion
    val anim = LiveSlimeView.animation(s, now)
    val frames = SlimeSprites.frames(s.stage, anim)
    val anchors = SlimeSprites.anchors(s.stage, anim)
    val count = max(1, frames.size)
    val i = if (still) anim.posterIndex(now) % count else ((t * 2.5).toLong() % count).toInt()
    val breathe = if (still) 0f else (sin(t * 2.4) * (if (s.isAsleep) 0.035 else 0.022)).toFloat()
    val dragDp = with(density) { Offset(drag.value.x.toDp().value, drag.value.y.toDp().value) }
    val pull = (-dragDp.y / 500f).coerceIn(-0.3f, 0.3f)
    val worn = s.equipped.copy()
    if (override == null && worn.hat == null && game.isBirthday(now)) worn.hat = "hat.party"

    val interaction = if (interactive) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pokeIt() })
            }
            .pointerInput(Unit) {
                var total = Offset.Zero
                var last = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        total = Offset.Zero
                        last = Offset.Zero
                    },
                    onDragEnd = { settle(scope, drag, reduce) },
                    onDragCancel = { settle(scope, drag, reduce) },
                    onDrag = { change, amount ->
                        change.consume()
                        total += amount
                        val limitX = 120.dp.toPx()
                        val limitY = 160.dp.toPx()
                        scope.launch {
                            drag.snapTo(Offset(total.x.coerceIn(-limitX, limitX), total.y.coerceIn(-limitY, limitY)))
                        }
                        val step = total - last
                        last = total
                        gestures.rubbed += sqrt(step.x * step.x + step.y * step.y) / density.density
                        if (gestures.rubbed > 420f) {
                            gestures.rubbed = 0f
                            pet()
                        }
                    },
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier
            .size(width = height * SlimeAspect * 1.1f, height = height * 1.1f)
            .then(interaction)
            .semantics {
                contentDescription = "${s.name}, your slime"
                stateDescription = SlimeMoodWord.phrase(s)
                if (interactive) {
                    onClick(label = "Poke") {
                        pokeIt()
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction("Pet") {
                        pet()
                        true
                    })
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .size(width = height * 0.8f, height = max(3f, height.value * 0.04f).dp)
                .background(Color.Black.copy(alpha = 0.18f)),
        )

        PixelSlime(
            frame = frames[min(i, frames.size - 1).coerceAtLeast(0)],
            skin = s.skin,
            accessories = worn,
            stage = s.stage,
            anchor = anchors.getOrNull(i),
            modifier = Modifier.size(width = height * SlimeAspect, height = height),
            squashX = 1 + breathe + poke.value * 0.14f - pull * 0.45f,
            squashY = 1 - breathe - poke.value * 0.16f + pull,
            shiftX = drag.value.x * 0.18f,
        )

        val effect = s.equipped.effect
        if (effect != null && !still) {
            EffectParticles(effect = effect, t = t, height = height)
        }

        for (heart in hearts) {
            val age = now.timeIntervalSince(heart.born)
            PixelItem(
                name = "heart",
                modifier = Modifier
                    .offset(x = heart.x.dp, y = -(height * 0.8f) - (age * 60).toFloat().dp)
                    .size(width = 22.dp, height = 17.dp),
                alpha = max(0.0, 1 - age / 1.4).toFloat(),
            )
        }
    }
}

/** Mutable bits of an ongoing gesture that don't need to redraw anything. */
private class GestureState {
    var taps = 0
    var rubbed = 0f
}

private fun settle(scope: kotlinx.coroutines.CoroutineScope, drag: Animatable<Offset, *>, reduceMotion: Boolean) {
    scope.launch {
        if (reduceMotion) {
            drag.animateTo(Offset.Zero, tween(150, easing = FastOutSlowInEasing))
        } else {
            drag.animateTo(Offset.Zero, spring(dampingRatio = 0.24f, stiffness = 220f, visibilityThreshold = Offset(0.5f, 0.5f)))
        }
    }
}

private class FloatingHeart(val x: Float, val born: Instant)

/** What the live slime shows when (shared with other screens that animate a slime the same way). */
object LiveSlimeView {
    /** Length of one feeling moment, in seconds. */
    private const val feelingSlot = 8.0

    /**
     * A reaction plays first; asleep or with a need run low, it shows that.
     * Only an idle slime shows how it feels, and its usual idle variety fills
     * the moments in between.
     */
    fun animation(s: Slime, now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): SpriteAnim {
        val reaction = SpriteAnim.of(s.reaction)
        if (reaction != null && now.timeIntervalSince(s.reactionAt) < 5) return reaction
        if (s.mood != Mood.idle || s.isAsleep) return SpriteAnim.of(s.mood)
        feeling(s, now)?.let { return it }
        // Idle variety: stretch in the morning, blush when close, curious now and then.
        val cycle = ((referenceSeconds(now) / 9).toLong() % 9).toInt()
        val hour = calendar.hour(now)
        if (cycle == 3 && hour in 6 until 10) return SpriteAnim.stretch
        if (cycle == 5 && s.bond >= 50) return SpriteAnim.blush
        if (cycle == 7 && s.personality.curious > 0.5) return SpriteAnim.curious
        return SpriteAnim.idle
    }

    /**
     * Whether an idle slime shows its strongest feeling during this moment,
     * and how. A mild feeling shows rarely, a strong one often; the choice
     * holds for the whole moment, so the pose doesn't flicker.
     */
    fun feeling(s: Slime, now: Instant): SpriteAnim? {
        val feelings = s.emotions
        val strength = min(1.0, max(0.0, feelings.intensity))
        if (strength < 0.3) return null
        val slot = (referenceSeconds(now) / feelingSlot).toLong()
        val rng = SplitMix(slot.toULong() + 0x5EEDuL)
        if (rng.unit() >= min(0.6, (strength - 0.25) * 0.9)) return null
        val big = strength >= 0.6 && rng.unit() < 0.4
        val coin = rng.unit() < 0.5
        return when (feelings.dominant) {
            Emotion.joy -> if (big) SpriteAnim.celebrate else SpriteAnim.wave
            Emotion.sadness -> SpriteAnim.sad
            Emotion.anger -> SpriteAnim.sulk
            Emotion.fear -> SpriteAnim.hide
            Emotion.love -> SpriteAnim.blush
            Emotion.curiosity -> SpriteAnim.curious
            Emotion.boredom -> SpriteAnim.stretch
            Emotion.excitement -> if (big || coin) SpriteAnim.celebrate else SpriteAnim.play
            // Dozes off for a moment without going to bed.
            Emotion.sleepiness -> SpriteAnim.sleep
        }
    }
}

/**
 * How the slime feels, in a word: under its name on the home screen and
 * read out by TalkBack wherever the slime is. The chat uses the same words
 * and cut-offs; keep the two the same.
 */
object SlimeMoodWord {
    /** "cheerful", "grumpy", "asleep" … A faint feeling reads as "calm". */
    fun word(slime: Slime): String {
        if (slime.isAsleep) return "asleep"
        val feelings = slime.emotions
        val strength = feelings.intensity
        if (strength < 0.15) return "calm"
        val strong = strength >= 0.55
        return when (feelings.dominant) {
            Emotion.joy -> if (strong) "cheerful" else "content"
            Emotion.sadness -> if (strong) "sad" else "glum"
            Emotion.anger -> if (strong) "cross" else "grumpy"
            Emotion.fear -> if (strong) "scared" else "nervous"
            Emotion.love -> if (strong) "cuddly" else "fond"
            Emotion.curiosity -> if (strong) "fascinated" else "curious"
            Emotion.boredom -> if (strong) "bored" else "a bit bored"
            Emotion.excitement -> if (strong) "excited" else "eager"
            Emotion.sleepiness -> if (strong) "sleepy" else "drowsy"
        }
    }

    /** For TalkBack: "feeling cheerful", or "asleep". */
    fun phrase(slime: Slime): String = if (slime.isAsleep) "asleep" else "feeling ${word(slime)}"
}

/** Particles for the equipped effect (sparkle, hearts, bubbles), rising around the slime. */
@Composable
fun EffectParticles(effect: String, t: Double, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.BottomCenter) {
        for (i in 0 until 6) {
            val phase = ((t * 0.6 + i / 6.0) % 1.0).let { if (it < 0) it + 1 else it }
            val x = (sin(i * 2.1 + t * 0.8) * height.value * 0.55).toFloat()
            val y = -(height.value * (0.2 + phase * 0.9)).toFloat()
            val particle = Modifier
                .offset(x = x.dp, y = y.dp)
                .size(height * 0.09f)
                .alpha((1 - phase).toFloat().coerceIn(0f, 1f))
            when (effect) {
                "effect.hearts" -> PixelItem("heart", particle)
                "effect.bubbles" -> PixelItem("bubbleItem", particle)
                else -> PixelIcon(SlimeSprites.emoteIcons["sparkle_small"] ?: emptyList(), Color(0xFFFFD166), particle)
            }
        }
    }
}
