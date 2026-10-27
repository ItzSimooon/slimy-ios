package de.hyprnode.slimy.platform

import de.hyprnode.slimy.model.EmoteReel
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Need
import de.hyprnode.slimy.model.PhoneEvent
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.model.hatch
import de.hyprnode.slimy.model.handle
import de.hyprnode.slimy.model.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The slime notification's content (the port of SlimeActivityAttributes.ContentState). */
class IslandStateTest {
    private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
    private val noon = berlin.date(2026, 10, 25, 12)

    private fun hatched(): GameState {
        val state = GameState()
        state.hatch("Testi", Skin.lime, 42uL, noon)
        return state
    }

    private fun friend(id: String, name: String = id.uppercase()) =
        FriendSlime(id = id, name = name, owner = "Owner $id", skin = Skin.lime, stage = Stage.young, mood = Mood.idle)

    @Test
    fun soloState() {
        val game = hatched()
        game.slime.needs.hunger = 41.6
        val s = IslandState.of(game, noon)
        assertEquals("Testi", s.name)
        assertEquals(42, s.hunger)
        assertEquals(42, s.value(Need.hunger))
        assertEquals(game.line(noon), s.line)
        assertNull(s.baseLine)
        assertNull(s.friends)
        assertNull(s.partner)
        assertEquals(noon, s.anchor)
        assertEquals(Need.hunger, s.wish)
        assertEquals(EmoteReel.hunger, s.emote)
        assertEquals("Level ${game.slime.level} · ${game.wallet.coins} coins", s.detail)
        assertEquals("Feed", s.actionTitle(Need.hunger))
        assertEquals("Sleep", s.actionTitle(Need.energy))
    }

    @Test
    fun aMomentWithAFriendBeyondTheFirstThree() {
        val game = hatched()
        val friends = listOf(friend("a"), friend("b"), friend("c"), friend("d", "Pip"))
        val s = IslandState.of(game, noon, friends, moment = SharedMoment.hug, momentFriend = "d")
        // The friend sharing the moment is always shown, first.
        assertEquals(listOf("d", "a", "b"), s.friends!!.map { it.id })
        assertEquals("Pip", s.partner!!.name)
        assertEquals("Pip: hug!", s.line)
        assertEquals(game.line(noon), s.baseLine)
        assertEquals(SpriteAnim.love, s.anim)
        assertEquals(EmoteReel.love, s.emote)
        assertEquals("with Pip", s.detail)

        val solo = s.solo
        assertNull(solo.moment)
        assertNull(solo.partner)
        assertEquals(game.line(noon), solo.line)
    }

    @Test
    fun friendsWithoutAMoment() {
        val s = IslandState.of(hatched(), noon, listOf(friend("a"), friend("b")))
        assertEquals(2, s.friends!!.size)
        assertNull(s.partner)
        assertEquals("Level ${s.level} · 2 friends", s.detail)
    }

    @Test
    fun phoneEventsAndReactions() {
        val game = hatched()
        game.handle(PhoneEvent.chargerConnected, noon)
        val s = IslandState.of(game, noon.plusSeconds(2))
        assertEquals(PhoneEvent.chargerConnected, s.event)
        assertEquals(Reaction.zap, s.reaction)
        assertEquals(SpriteAnim.zap, s.anim)
        assertEquals(game.slime.reactionAt, s.anchor)
        assertEquals(EmoteReel.zap, s.emote)
        assertEquals(true, s.detail.endsWith(" · Charger plugged in"))
        // After the event window the event is gone and the reaction is over.
        val later = IslandState.of(game, noon.plusSeconds(10 * 60))
        assertNull(later.event)
        assertEquals(Reaction.none, later.reaction)
        assertNull(s.solo.event)
    }

    @Test
    fun asleep() {
        val game = hatched()
        game.slime.isAsleep = true
        game.slime.say = null
        val s = IslandState.of(game, noon, animated = false)
        assertEquals(Mood.sleep, s.mood)
        assertEquals(EmoteReel.sleepy, s.emote)
        assertEquals("Wake", s.actionTitle(Need.energy))
        assertEquals(false, s.isAnimated)
        assertEquals("zzz", s.line)
    }
}
