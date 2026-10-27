package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable

@Serializable
enum class Food {
    apple, carrot, berry, mushroom, cookie, soup, honey, pudding, cake, starJelly;

    val id: String get() = name

    val title: String
        get() = when (this) {
            apple -> "Apple"
            carrot -> "Carrot"
            berry -> "Strawberry"
            mushroom -> "Mushroom"
            cookie -> "Cookie"
            soup -> "Soup"
            honey -> "Honey"
            pudding -> "Pudding"
            cake -> "Cake"
            starJelly -> "Star jelly"
        }

    val sprite: String get() = name
    val spriteName: String get() = name

    val price: Int
        get() = when (this) {
            apple -> 0
            carrot -> 2
            berry -> 3
            mushroom -> 4
            cookie -> 6
            soup -> 8
            honey -> 9
            pudding -> 10
            cake -> 14
            starJelly -> 35
        }

    val nutrition: Double
        get() = when (this) {
            apple -> 16.0
            carrot -> 20.0
            berry -> 22.0
            mushroom -> 24.0
            cookie -> 26.0
            soup -> 40.0
            honey -> 28.0
            pudding -> 32.0
            cake -> 45.0
            starJelly -> 100.0
        }

    val funBonus: Double
        get() = when (this) {
            apple, carrot, soup -> 0.0
            berry, mushroom -> 3.0
            cookie, honey -> 8.0
            pudding -> 10.0
            cake -> 12.0
            starJelly -> 25.0
        }

    /** Unlocked in the kitchen from this slime level on. */
    val unlockLevel: Int
        get() = when (this) {
            apple, carrot, berry -> 1
            cookie, mushroom -> 2
            soup -> 3
            honey, pudding -> 5
            cake -> 7
            starJelly -> 10
        }

    companion object {
        val allCases: List<Food> get() = entries

        /** Foods that can be a slime's secret favourite. */
        val discoverable: List<Food> = listOf(carrot, berry, mushroom, cookie, soup, honey, pudding, cake)
    }
}
