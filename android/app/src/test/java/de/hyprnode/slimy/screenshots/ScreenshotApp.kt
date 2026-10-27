package de.hyprnode.slimy.screenshots

import android.app.Application
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.platform.Notifier

/**
 * The application for screenshot tests: the same pixel art and notification
 * channels as `SlimyApplication`, without its process-wide work (WorkManager,
 * the phone monitor, the slime notification, network). Each test builds its
 * own stores in [DemoWorld].
 */
class ScreenshotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SlimeSprites.install(SlimeSprites.parse(assets.open("sprites.json")))
        Notifier.install(this)
    }
}
