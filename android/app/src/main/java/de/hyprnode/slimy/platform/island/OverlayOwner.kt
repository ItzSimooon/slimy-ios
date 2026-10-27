package de.hyprnode.slimy.platform.island

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * What a ComposeView needs around it when there is no activity: a
 * lifecycle, saved state and a ViewModel store. The overlay resumes it while
 * the island is on screen and stops it while hidden, which also pauses the
 * view's recomposer and every animation in it.
 */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
