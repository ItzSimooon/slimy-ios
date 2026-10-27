package de.hyprnode.slimy.model

import java.io.File

/** Loads the real sprite data (Shared/Sprites/sprites.json) once, for tests that check art. */
object TestSprites {
    fun ensureLoaded() {
        if (SlimeSprites.isLoaded) return
        val path = System.getProperty("slimy.sprites")
            ?: error("The test task sets slimy.sprites to Shared/Sprites/sprites.json")
        SlimeSprites.install(SlimeSprites.parse(File(path).readText()))
    }
}

/** Reads a fixture from src/test/resources/fixtures. */
object Fixtures {
    fun text(name: String): String =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
