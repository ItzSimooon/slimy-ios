package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable

@Serializable
enum class ItemKind {
    hat, glasses, scarf, effect, furniture;

    val title: String
        get() = when (this) {
            hat -> "Hats"
            glasses -> "Glasses"
            scarf -> "Scarves"
            effect -> "Effects"
            furniture -> "Furniture"
        }

    companion object {
        val allCases: List<ItemKind> get() = entries
    }
}

/** Where a piece of furniture stands in a habitat. */
@Serializable
enum class FurnitureSlot {
    floorLeft, floorRight, wallLeft, wallRight, rug, ceiling;

    val title: String
        get() = when (this) {
            floorLeft -> "Floor left"
            floorRight -> "Floor right"
            wallLeft -> "Wall left"
            wallRight -> "Wall right"
            rug -> "Rug"
            ceiling -> "Ceiling"
        }

    companion object {
        val allCases: List<FurnitureSlot> get() = entries
    }
}

/** How an item is unlocked besides buying it. */
sealed class Unlock {
    data class level(val n: Int) : Unlock()
    data class achievement(val id: String) : Unlock()
    data class bond(val n: Int) : Unlock()
    data class season(val month: Int) : Unlock()

    /** How a locked item is unlocked, for the shop. */
    val hint: String
        get() = when (this) {
            is level -> "From level $n"
            is achievement -> "Achievement: ${Achievements.achievement(id)?.title ?: id}"
            is bond -> "At bond $n"
            is season -> "${monthNames[maxOf(1, minOf(12, month)) - 1]} only"
        }

    private companion object {
        val monthNames = listOf(
            "January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December",
        )
    }
}

data class CatalogItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val price: Int,
    val unlock: Unlock,
    val slot: FurnitureSlot? = null,
    val rare: Boolean = false,
    /** Shared sprite to show for it, when it has none of its own. */
    val art: String? = null,
) {
    /** Sprite name in SlimeSprites (accessories and furniture share the id). */
    val sprite: String get() = art ?: id
}

object Catalog {
    val items: List<CatalogItem> = listOf(
        // Hats
        CatalogItem("hat.party", ItemKind.hat, "Party hat", 25, Unlock.level(1)),
        CatalogItem("hat.beanie", ItemKind.hat, "Beanie", 40, Unlock.level(2)),
        CatalogItem("hat.flower", ItemKind.hat, "Blossom", 50, Unlock.level(3)),
        CatalogItem("hat.pumpkin", ItemKind.hat, "Pumpkin", 60, Unlock.season(month = 10), rare = true),
        CatalogItem("hat.crown", ItemKind.hat, "Crown", 150, Unlock.level(8), rare = true),
        CatalogItem("hat.wizard", ItemKind.hat, "Wizard hat", 220, Unlock.level(12), rare = true),
        // Glasses
        CatalogItem("glasses.round", ItemKind.glasses, "Round glasses", 35, Unlock.level(2)),
        CatalogItem("glasses.shades", ItemKind.glasses, "Sunglasses", 60, Unlock.level(4)),
        CatalogItem("glasses.star", ItemKind.glasses, "Star glasses", 120, Unlock.level(9), rare = true),
        // Scarves
        CatalogItem("scarf.red", ItemKind.scarf, "Red scarf", 40, Unlock.level(3)),
        CatalogItem("scarf.striped", ItemKind.scarf, "Striped scarf", 70, Unlock.level(6)),
        // Effects
        CatalogItem("effect.sparkle", ItemKind.effect, "Sparkle", 100, Unlock.level(5)),
        CatalogItem("effect.hearts", ItemKind.effect, "Little hearts", 0, Unlock.bond(50), rare = true),
        CatalogItem("effect.bubbles", ItemKind.effect, "Soap bubbles", 0, Unlock.achievement("bather"), rare = true),
        // Furniture
        CatalogItem("furn.bed", ItemKind.furniture, "Little bed", 30, Unlock.level(1), slot = FurnitureSlot.floorLeft),
        CatalogItem("furn.plant", ItemKind.furniture, "Potted plant", 25, Unlock.level(1), slot = FurnitureSlot.floorRight),
        CatalogItem("furn.rug", ItemKind.furniture, "Rug", 35, Unlock.level(2), slot = FurnitureSlot.rug),
        CatalogItem("furn.poster", ItemKind.furniture, "Poster", 20, Unlock.level(2), slot = FurnitureSlot.wallLeft),
        CatalogItem("furn.lamp", ItemKind.furniture, "Floor lamp", 45, Unlock.level(3), slot = FurnitureSlot.floorRight),
        CatalogItem("furn.shelf", ItemKind.furniture, "Shelf", 55, Unlock.level(4), slot = FurnitureSlot.wallRight),
        CatalogItem("furn.lights", ItemKind.furniture, "Fairy lights", 60, Unlock.level(5), slot = FurnitureSlot.ceiling),
        CatalogItem("furn.aquarium", ItemKind.furniture, "Aquarium", 120, Unlock.level(8), slot = FurnitureSlot.floorLeft, rare = true),
        CatalogItem("furn.trophy", ItemKind.furniture, "Trophy", 0, Unlock.achievement("arcadeStar"), slot = FurnitureSlot.wallRight, rare = true),
        // Seasonal furniture
        CatalogItem("furn.jackolantern", ItemKind.furniture, "Jack-o'-lantern", 45, Unlock.season(month = 10),
            slot = FurnitureSlot.floorRight, rare = true, art = "hat.pumpkin"),
        CatalogItem("furn.stars", ItemKind.furniture, "Star garland", 60, Unlock.season(month = 12),
            slot = FurnitureSlot.ceiling, rare = true, art = "starJelly"),
        // Gifts: the seventh day of the gift calendar, and the first birthday
        CatalogItem("furn.chest", ItemKind.furniture, "Treasure chest", 0, Unlock.achievement("gifts7"),
            slot = FurnitureSlot.floorRight, rare = true, art = "chest"),
        CatalogItem("furn.crystal", ItemKind.furniture, "Glow crystal", 0, Unlock.achievement("gifts14"),
            slot = FurnitureSlot.floorLeft, rare = true, art = "gem"),
        CatalogItem("furn.flag", ItemKind.furniture, "Finish flag", 0, Unlock.achievement("gifts21"),
            slot = FurnitureSlot.floorLeft, rare = true, art = "flag"),
        CatalogItem("furn.cake", ItemKind.furniture, "Birthday cake", 0, Unlock.achievement("birthday"),
            slot = FurnitureSlot.floorRight, rare = true, art = "cake"),
    )

    fun item(id: String): CatalogItem? = items.firstOrNull { it.id == id }

    fun items(kind: ItemKind): List<CatalogItem> = items.filter { it.kind == kind }
}

@Serializable
enum class Habitat {
    room, garden, beach, space, crystal;

    val id: String get() = name

    val title: String
        get() = when (this) {
            room -> "Room"
            garden -> "Garden"
            beach -> "Beach"
            space -> "Space"
            crystal -> "Crystal cave"
        }

    val price: Int
        get() = when (this) {
            room -> 0
            garden -> 150
            beach -> 250
            space -> 400
            crystal -> 0
        }

    val unlock: Unlock
        get() = when (this) {
            room -> Unlock.level(1)
            garden -> Unlock.level(5)
            beach -> Unlock.level(9)
            space -> Unlock.level(14)
            crystal -> Unlock.achievement("collector")
        }

    /** Outdoor habitats get weather. */
    val hasWeather: Boolean get() = this == garden || this == beach

    companion object {
        val allCases: List<Habitat> get() = entries
    }
}
