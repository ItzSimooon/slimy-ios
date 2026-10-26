package de.hyprnode.slimy

import android.app.Application

/** Process-wide setup: the stores live here so the activity, widgets,
 *  workers and notification actions all share one game. */
class SlimyApplication : Application()
