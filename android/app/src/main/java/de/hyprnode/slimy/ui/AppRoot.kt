package de.hyprnode.slimy.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.hyprnode.slimy.SlimyApplication
import de.hyprnode.slimy.arcade.ArcadeScreen
import de.hyprnode.slimy.arcade.BathScreen
import de.hyprnode.slimy.core.LocalChatStore
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.LocalIsland
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.enumOf
import de.hyprnode.slimy.ui.screens.about.AboutScreen
import de.hyprnode.slimy.ui.screens.about.LicenseScreen
import de.hyprnode.slimy.ui.screens.about.WhatsNew
import de.hyprnode.slimy.ui.screens.about.WhatsNewScreen
import de.hyprnode.slimy.ui.screens.care.CareTogetherScreen
import de.hyprnode.slimy.ui.screens.chat.ChatScreen
import de.hyprnode.slimy.ui.screens.common.rememberIsResumed
import de.hyprnode.slimy.ui.screens.crates.CrateOpenScreen
import de.hyprnode.slimy.ui.screens.crates.CrateShelfScreen
import de.hyprnode.slimy.ui.screens.crates.DropRatesScreen
import de.hyprnode.slimy.ui.screens.friends.FriendsScreen
import de.hyprnode.slimy.ui.screens.friends.GoOnlineScreen
import de.hyprnode.slimy.ui.screens.help.HelpScreen
import de.hyprnode.slimy.ui.screens.home.HomeScreen
import de.hyprnode.slimy.ui.screens.journal.JournalScreen
import de.hyprnode.slimy.ui.screens.kitchen.KitchenScreen
import de.hyprnode.slimy.ui.screens.onboarding.OnboardingScreen
import de.hyprnode.slimy.ui.screens.photo.PhotoBoothScreen
import de.hyprnode.slimy.ui.screens.root.CelebrationOverlay
import de.hyprnode.slimy.ui.screens.root.ToastLayer
import de.hyprnode.slimy.ui.screens.settings.SettingsScreen
import de.hyprnode.slimy.ui.screens.shop.ShopScreen
import de.hyprnode.slimy.ui.screens.streak.StreakScreen
import kotlinx.coroutines.delay

/**
 * The intent extra (a String) that asks the app to open a place, sent by
 * notifications, widgets, shortcuts and the slime notification's actions.
 * Allowed values ([EXTRA_OPEN_VALUES]): "home", "arcade", "bath", "chat",
 * "friends", "care", "kitchen", "shop", "crates", "journal", "settings".
 * `slimy://<value>` links open the same places; invite links
 * (slimy://f/CODE, https://slime.hyprnode.de/f/CODE) open "friends".
 */
const val EXTRA_OPEN = "slimy.open"

/** The values [EXTRA_OPEN] may carry; anything else is ignored. */
val EXTRA_OPEN_VALUES: Set<String> = setOf(
    "home", "arcade", "bath", "chat", "friends", "care", "kitchen", "shop", "crates", "journal", "settings",
)

/**
 * Every place in the app: the home screen and what opens over it (the
 * iPhone app's sheets, full-screen covers and pushed screens).
 */
enum class Destination(val route: String) {
    home("home"),
    kitchen("kitchen"),
    shop("shop"),
    crates("crates"),
    journal("journal"),
    friends("friends"),
    settings("settings"),
    chat("chat"),
    /** The daily gift (StreakView). */
    gift("gift"),
    care("care"),
    arcade("arcade"),
    bath("bath"),
    photoBooth("photoBooth"),
    /** Drop rates pushed from the crate shelf. */
    dropRates("dropRates"),
    /** Drop rates on their own, from the shop. */
    shopDropRates("shopDropRates"),
    help("help"),
    about("about"),
    license("license"),
    whatsNew("whatsNew"),
    goOnline("goOnline"),
    crateOpen("crateOpen/{kind}");

    companion object {
        /** The route that opens a crate of [kind]. */
        fun crateOpenRoute(kind: CrateKind): String = "crateOpen/${kind.name}"

        /** The place an [EXTRA_OPEN] value (or `slimy://<value>` link) asks for, or null. */
        fun forOpenRequest(value: String): Destination? {
            if (value !in EXTRA_OPEN_VALUES) return null
            return entries.firstOrNull { it.route == value }
        }

        /** Places a friend's invite shouldn't interrupt: a game or a bath in progress. */
        val covers: Set<String> = setOf(arcade.route, bath.route)
    }
}

/** Root-level rules, kept apart from the views so they can be tested. */
object AppRules {
    /** Home shows once the slime has hatched and settled in; before that, onboarding. */
    fun isOnboarded(hatched: Boolean, tips: Set<String>): Boolean = hatched && tips.contains("onboarded")
}

/**
 * The app's UI root, called by `MainActivity`: provides every store
 * (`LocalGameStore`, `LocalDailyStore`, `LocalFriendsStore`,
 * `LocalChatStore`, `LocalIsland`) and shows the app: onboarding until the
 * slime has hatched and settled in, then its home and everything that opens
 * from there. Celebrations and toasts float above it all; What's New comes
 * once after an update (App/UI/RootView.swift).
 *
 * [openRequest] is one of [EXTRA_OPEN_VALUES] (or null): the place an intent
 * asked for. Once it has been opened, [onOpenHandled] clears it, so the same
 * request can come again.
 */
@Composable
fun AppRoot(openRequest: String?, onOpenHandled: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as SlimyApplication
    CompositionLocalProvider(
        LocalGameStore provides app.store,
        LocalDailyStore provides app.dailyStore,
        LocalFriendsStore provides app.friends,
        LocalChatStore provides app.chat,
        LocalIsland provides app.island,
    ) {
        SlimyRoot(openRequest, onOpenHandled)
    }
}

@Composable
private fun SlimyRoot(openRequest: String?, onOpenHandled: () -> Unit) {
    val store = LocalGameStore.current
    val context = LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val onboarded = AppRules.isOnboarded(state.hatched, state.player.tips)
    val handled by rememberUpdatedState(onOpenHandled)
    var wasOnboarded by remember { mutableStateOf(onboarded) }

    LaunchedEffect(onboarded) {
        // Someone who hatched their slime in this version has seen it all.
        if (onboarded && !wasOnboarded) WhatsNew.markSeen(context)
        wasOnboarded = onboarded
    }
    LaunchedEffect(openRequest, onboarded) {
        // Before the slime has settled in there is only onboarding to show.
        if (openRequest != null && !onboarded) handled()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Crossfade(targetState = onboarded, animationSpec = tween(400), label = "onboarded") { ready ->
            if (ready) AppNavigation(openRequest, onOpenHandled) else OnboardingScreen()
        }
        CelebrationOverlay()
        ToastLayer()
    }
}

/** Home and every place that opens over it. */
@Composable
private fun AppNavigation(openRequest: String?, onOpenHandled: () -> Unit) {
    val store = LocalGameStore.current
    val friends = LocalFriendsStore.current
    val context = LocalContext.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val handled by rememberUpdatedState(onOpenHandled)
    val resumed = rememberIsResumed()
    val isResumed by rememberUpdatedState(resumed)

    fun go(destination: Destination) = nav.navigate(destination.route) { launchSingleTop = true }
    fun goRoute(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }
    fun home() {
        nav.popBackStack(Destination.home.route, inclusive = false)
    }

    NavHost(
        navController = nav,
        startDestination = Destination.home.route,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Destination.home.route) { HomeScreen(open = { go(it) }) }
        composable(Destination.kitchen.route) { KitchenScreen(onClose = ::back) }
        composable(Destination.shop.route) {
            ShopScreen(
                onClose = ::back,
                onOpenCrate = { goRoute(Destination.crateOpenRoute(it)) },
                onDropRates = { go(Destination.shopDropRates) },
            )
        }
        composable(Destination.crates.route) {
            CrateShelfScreen(
                onClose = ::back,
                onOpen = { goRoute(Destination.crateOpenRoute(it)) },
                onDropRates = { go(Destination.dropRates) },
            )
        }
        composable(Destination.dropRates.route) { DropRatesScreen(onClose = ::back, pushed = true) }
        composable(Destination.shopDropRates.route) { DropRatesScreen(onClose = ::back, pushed = false) }
        composable(
            Destination.crateOpen.route,
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) { backStack ->
            val kind = enumOf<CrateKind>(backStack.arguments?.getString("kind")) ?: CrateKind.wooden
            CrateOpenScreen(kind = kind, onDone = ::back)
        }
        composable(Destination.journal.route) { JournalScreen(onClose = ::back, onPhotoBooth = { go(Destination.photoBooth) }) }
        composable(Destination.photoBooth.route) { PhotoBoothScreen(onClose = ::back) }
        composable(Destination.gift.route) { StreakScreen(onClose = ::back, onOpenCrate = { goRoute(Destination.crateOpenRoute(it)) }) }
        composable(Destination.friends.route) { FriendsScreen(onClose = ::back) }
        composable(Destination.goOnline.route) { GoOnlineScreen(onDone = ::back) }
        composable(Destination.chat.route) { ChatScreen(onClose = ::back) }
        composable(Destination.care.route) { CareTogetherScreen(onClose = ::back) }
        composable(Destination.arcade.route) { ArcadeScreen(onClose = ::back) }
        composable(Destination.bath.route) { BathScreen(onClose = ::back) }
        composable(Destination.settings.route) {
            SettingsScreen(
                onClose = ::back,
                onCareTogether = { go(Destination.care) },
                onGoOnline = { go(Destination.goOnline) },
                onHelp = { go(Destination.help) },
                onAbout = { go(Destination.about) },
            )
        }
        composable(Destination.help.route) { HelpScreen(onBack = ::back) }
        composable(Destination.about.route) {
            AboutScreen(onBack = ::back, onWhatsNew = { go(Destination.whatsNew) }, onLicense = { go(Destination.license) })
        }
        composable(Destination.license.route) { LicenseScreen(onBack = ::back) }
        composable(Destination.whatsNew.route) {
            WhatsNewScreen(onClose = {
                WhatsNew.markSeen(context)
                back()
            })
        }
    }

    // A place a notification, widget, shortcut or link asked for.
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        val destination = Destination.forOpenRequest(request)
        // An invite link waits in pendingInviteCode; the invite handling below opens Friends when it may.
        val invite = request == Destination.friends.route && friends.pendingInviteCode.value != null
        if (destination != null && !invite) {
            home()
            if (destination != Destination.home) go(destination)
        }
        handled()
    }

    // An invite link (slime.hyprnode.de/f/CODE) opens Friends, which fills in
    // the code. A game or the bath in progress isn't interrupted: Friends
    // opens once it closes. The same code doesn't open Friends twice.
    val pendingInvite by friends.pendingInviteCode.collectAsStateWithLifecycle()
    var inviteShown by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteWaiting by remember { mutableStateOf(false) }
    LaunchedEffect(pendingInvite, route) {
        val code = pendingInvite ?: return@LaunchedEffect
        if (code == inviteShown || route == null) return@LaunchedEffect
        if (route in Destination.covers) {
            inviteWaiting = true
            return@LaunchedEffect
        }
        if (inviteWaiting) {
            inviteWaiting = false
            // Let the game slide away before Friends comes up.
            delay(450)
        }
        inviteShown = code
        if (route != Destination.friends.route) go(Destination.friends)
    }

    // A challenge the player chose to play: the arcade opens with it.
    val challenge by friends.playChallenge.collectAsStateWithLifecycle()
    LaunchedEffect(challenge?.id) {
        if (challenge == null) return@LaunchedEffect
        home()
        delay(450)
        go(Destination.arcade)
    }

    // What's New, once nothing else is on screen (a game, a celebration). If
    // it never gets the chance, it stays due for the next launch.
    LaunchedEffect(Unit) {
        if (!WhatsNew.isDue(context, store.appVersion)) return@LaunchedEffect
        repeat(600) {
            delay(1_000)
            val calm = store.celebrations.value.isEmpty() && isResumed &&
                nav.currentDestination?.route == Destination.home.route
            if (calm) {
                go(Destination.whatsNew)
                return@LaunchedEffect
            }
        }
    }
}
