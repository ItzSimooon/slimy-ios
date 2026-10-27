package de.hyprnode.slimy.ui.screens.onboarding

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.HabitatScene
import de.hyprnode.slimy.art.LiveSlimeView
import de.hyprnode.slimy.art.PixelEgg
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.art.rememberReduceMotion
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.LocalIsland
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.platform.IslandController
import de.hyprnode.slimy.platform.Notifier
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.findActivity
import de.hyprnode.slimy.ui.screens.common.swiftSpring
import kotlinx.coroutines.launch
import java.time.Instant

/** The steps of the first launch. */
enum class OnboardingStep { egg, chooseName, meet, feed, island, done }

private const val tapsToHatch = 5

/**
 * First launch: an egg, a name, a hatching, meeting the slime, a first meal,
 * and its place outside the app (the slime notification). Notifications are
 * asked for last, after a screen that says what they are for
 * (App/UI/Screens/OnboardingView.swift).
 *
 * On Android the slime notification needs the notification permission, so
 * "Keep It in Notifications" asks for it first.
 */
@Composable
fun OnboardingScreen() {
    val store = LocalGameStore.current
    val island = LocalIsland.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val focus = LocalFocusManager.current
    val s by store.state.collectAsStateWithLifecycle()

    var step by rememberSaveable { mutableStateOf(if (store.state.value.hatched) OnboardingStep.meet else OnboardingStep.egg) }
    var cracks by rememberSaveable { mutableIntStateOf(0) }
    var wobble by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var skin by rememberSaveable { mutableStateOf(Skin.lime) }
    var naming by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (store.state.value.hatched && step == OnboardingStep.egg) step = OnboardingStep.meet
    }

    fun finish() {
        store.markTip("onboarded")
        Notifier.reschedule(store.state.value)
    }

    /** The notification step, unless Android already has an answer (a slime hatched again after the first one was released). */
    suspend fun afterIsland() {
        if (OnboardingRules.notificationsUndecided(Notifier.isAuthorized(), store.state.value.settings.notifications.asked, Build.VERSION.SDK_INT)) {
            step = OnboardingStep.done
        } else {
            store.update { it.settings.notifications.asked = true }
            finish()
        }
    }

    fun hatch() {
        focus.clearFocus()
        naming = false
        val trimmed = name.trimmedSwift()
        store.hatch(if (trimmed.isEmpty()) OnboardingRules.suggestion("") else trimmed, skin)
        step = OnboardingStep.meet
    }

    Box(Modifier.fillMaxSize()) {
        HabitatScene(
            habitat = Habitat.room,
            furniture = emptyMap(),
            date = Instant.now(),
            animate = !reduceMotion,
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val minHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            if (reduceMotion) {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            } else {
                                (fadeIn(swiftSpring(0.5, 0.8)) + scaleIn(swiftSpring(0.5, 0.8), initialScale = 0.97f)) togetherWith fadeOut(tween(150))
                            }
                        },
                        label = "onboarding",
                    ) { shown ->
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            when (shown) {
                                OnboardingStep.egg -> {
                                    Egg(cracks, wobble, reduceMotion) {
                                        cracks += 1
                                        wobble = !wobble
                                        Feedback.shared.play(if (cracks >= tapsToHatch) Sound.pop else Sound.tap)
                                        if (cracks >= tapsToHatch) step = OnboardingStep.chooseName
                                    }
                                    Panel {
                                        Text(
                                            if (cracks == 0) "Something is wobbling. Tap the egg." else "Almost! ${tapsToHatch - cracks} more.",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Theme.ink,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                OnboardingStep.chooseName -> {
                                    val eggWidth by animateDpAsState(if (naming) 70.dp else 110.dp, tween(200), label = "egg")
                                    PixelEgg(cracks = tapsToHatch, modifier = Modifier.size(width = eggWidth, height = eggWidth * 140f / 110f))
                                    Panel {
                                        Heading("What should it be called?", title = false)
                                        NameField(
                                            name = name,
                                            onName = { name = it.prefixChars(16) },
                                            onFocus = { naming = it },
                                            onDone = { focus.clearFocus() },
                                            onDice = {
                                                Feedback.shared.play(Sound.tap)
                                                name = OnboardingRules.suggestion(name)
                                            },
                                        )
                                        Text("And which colour?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.inkSoft)
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            for (option in Skin.hatchChoices) {
                                                SkinButton(option, selected = skin == option, modifier = Modifier.weight(1f)) {
                                                    Feedback.shared.play(Sound.tap)
                                                    skin = option
                                                }
                                            }
                                        }
                                        AppButton(title = "Hatch") { hatch() }
                                    }
                                }
                                OnboardingStep.meet -> {
                                    val slime = s.slime
                                    LiveSlimeView(height = 150.dp)
                                    Panel {
                                        Heading("Hi, ${slime.name}!")
                                        Text(
                                            "${slime.name} is ${slime.personality.top.joinToString(" and ") { it.title }}.",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Theme.ink,
                                        )
                                        Text(slime.personality.dominant.blurb, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft)
                                        Text("Tap it or rub it. It likes that, and it'll tell you how it feels.", style = MaterialTheme.typography.bodyLarge, color = Theme.ink)
                                        AppButton(title = "Next") { step = OnboardingStep.feed }
                                    }
                                }
                                OnboardingStep.feed -> {
                                    LiveSlimeView(height = 150.dp)
                                    Panel {
                                        Heading("It's hungry")
                                        Text(
                                            "Slimes have five needs: food, fun, energy, cleanliness and love. Start with an apple.",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Theme.inkSoft,
                                        )
                                        AppButton(title = "Give an Apple", icon = "apple") {
                                            store.feed(Food.apple)
                                            if (OnboardingRules.skipIsland(island.presence, store.state.value.settings.notifications.asked)) {
                                                // Notifications are off for Slimy: nothing to set up.
                                                scope.launch { afterIsland() }
                                            } else {
                                                step = OnboardingStep.island
                                            }
                                        }
                                    }
                                }
                                OnboardingStep.island -> {
                                    LiveSlimeView(height = 130.dp)
                                    Panel {
                                        Heading("Always with you")
                                        Text(OnboardingRules.islandText(s.slime.name), style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft)
                                        AppButton(title = "Keep It in Notifications", icon = "starJelly") {
                                            scope.launch {
                                                // The slime notification needs the notification permission on Android.
                                                Notifier.requestPermission(context.findActivity())
                                                island.ensureRunning(restart = true)
                                                afterIsland()
                                            }
                                        }
                                        AppButton(title = "Later", kind = AppButtonKind.quiet) {
                                            // Don't bring it back on its own; Settings can turn it on.
                                            island.keepAlive.value = false
                                            scope.launch { afterIsland() }
                                        }
                                    }
                                }
                                OnboardingStep.done -> {
                                    LiveSlimeView(height = 130.dp)
                                    Panel {
                                        Heading("One last thing")
                                        Text(
                                            "Next, Slimy asks whether it may tell you when ${s.slime.name} needs something. A few times a day at most, never at night. You can change this any time in Settings.",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Theme.inkSoft,
                                        )
                                        // Always leads to the system prompt, which has its own "Don't allow".
                                        AppButton(title = "Continue") {
                                            scope.launch {
                                                Notifier.requestPermission(context.findActivity())
                                                finish()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Bar colour behind the status bar, so the clock stays readable over the pixel world.
        Spacer(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)),
        )
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Theme.card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun Heading(text: String, title: Boolean = true) {
    Text(
        text,
        style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Theme.ink,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Egg(cracks: Int, wobble: Boolean, reduceMotion: Boolean, onTap: () -> Unit) {
    val angle by animateFloatAsState(
        if (reduceMotion) 0f else if (wobble) 5f else -5f,
        if (reduceMotion) tween(0) else swiftSpring(0.15, 0.25),
        label = "wobble",
    )
    PixelEgg(
        cracks = cracks,
        modifier = Modifier
            .size(width = 150.dp, height = 190.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationZ = angle
            }
            .semantics {
                contentDescription = "Egg"
                if (cracks > 0) stateDescription = "$cracks of $tapsToHatch taps"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Help it hatch",
                onClick = onTap,
            ),
    )
}

@Composable
private fun NameField(name: String, onName: (String) -> Unit, onFocus: (Boolean) -> Unit, onDone: () -> Unit, onDice: () -> Unit) {
    TextField(
        value = name,
        onValueChange = onName,
        placeholder = { Text("Name", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        leadingIcon = { Spacer(Modifier.size(48.dp)) },
        trailingIcon = {
            IconButton(onClick = onDice) {
                Icon(Icons.Filled.Casino, contentDescription = "Suggest a name", tint = Theme.accent)
            }
        },
        shape = RoundedCornerShape(Theme.smallRadius),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Theme.well,
            unfocusedContainerColor = Theme.well,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocus(it.isFocused) },
    )
}

@Composable
private fun SkinButton(option: Skin, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme.smallRadius)
    Box(
        modifier
            .clip(shape)
            .background(Theme.well)
            .then(if (selected) Modifier.border(2.5.dp, Theme.accent, shape) else Modifier)
            .semantics { contentDescription = option.title }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelSlime(
            frame = SlimeSprites.frames(Stage.baby, SpriteAnim.idle).firstOrNull() ?: SlimeSprites.blank,
            skin = option,
            modifier = Modifier.size(width = 60.dp, height = 50.dp),
        )
    }
}

/** The onboarding's rules and words, kept apart from the views so they can be tested. */
object OnboardingRules {
    private val suggestions = listOf(
        "Mochi", "Blub", "Pudding", "Gloop", "Jelly", "Bean", "Pip", "Squish",
        "Wobble", "Kiwi", "Noodle", "Dumpling", "Gummy", "Tofu", "Pebble", "Boba",
    )

    /** A name idea, never the one already in the field. */
    fun suggestion(current: String): String = suggestions.filter { it != current }.randomOrNull() ?: "Mochi"

    /** Where the slime lives outside the app on Android. */
    fun islandText(name: String): String =
        "$name can live in your notifications. It wobbles there, shows what it needs, and you can feed it without opening the app."

    /**
     * Skip the slime notification step only when notifications are off for
     * Slimy and Android won't ask again here (the player already answered).
     */
    fun skipIsland(presence: IslandController.Presence, asked: Boolean): Boolean =
        presence == IslandController.Presence.unavailable && asked

    /** Whether the system hasn't been asked about notifications yet (Android 13 and later ask; older ones allow). */
    fun notificationsUndecided(authorized: Boolean, asked: Boolean, sdk: Int): Boolean =
        !authorized && !asked && sdk >= 33
}
