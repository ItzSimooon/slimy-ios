package de.hyprnode.slimy.arcade

import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.SplitMix
import de.hyprnode.slimy.model.arcade.GameSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.hypot

class ArcadeRulesTest {
    // MARK: Energy

    @Test
    fun roundsFollowTheEnergyCost() {
        assertEquals(0, ArcadeRules.rounds(11.9))
        assertEquals(1, ArcadeRules.rounds(12.0))
        assertEquals(1, ArcadeRules.rounds(19.9))
        assertEquals(2, ArcadeRules.rounds(20.0))
        // (100 - 12) / 8 = 11 more rounds after the first.
        assertEquals(12, ArcadeRules.rounds(100.0))
    }

    @Test
    fun energyNoteSaysHowManyRoundsAreLeft() {
        assertEquals("Mochi is too tired to play. A nap or some star jelly helps.", ArcadeRules.energyNote(5.0, "Mochi", canPlay = false))
        assertEquals("Enough for one more round.", ArcadeRules.energyNote(15.0, "Mochi", canPlay = true))
        assertEquals("Enough for about 12 rounds.", ArcadeRules.energyNote(100.0, "Mochi", canPlay = true))
    }

    // MARK: Results

    @Test
    fun headlinePrefersARecord() {
        assertEquals("New record!", ArcadeRules.headline(record = true, paid = true, score = 1, goodScore = 40))
        assertEquals("Round over", ArcadeRules.headline(record = false, paid = false, score = 0, goodScore = 40))
        assertEquals("Great round", ArcadeRules.headline(record = false, paid = true, score = 40, goodScore = 40))
        assertEquals("Round over", ArcadeRules.headline(record = false, paid = true, score = 39, goodScore = 40))
    }

    @Test
    fun pauseCardCopy() {
        assertEquals("1 point so far", ArcadeRules.pointsSoFar(1))
        assertEquals("0 points so far", ArcadeRules.pointsSoFar(0))
        assertEquals("7 points so far", ArcadeRules.pointsSoFar(7))
        assertEquals("The round ends with 7 points and counts.", ArcadeRules.quitWarning(7))
        assertEquals("No points means no reward, but it costs no energy either.", ArcadeRules.quitWarning(0))
    }

    @Test
    fun challengeOutcomeNamesTheWinner() {
        assertEquals("You beat Ana: 12 to 10.", ArcadeRules.challengeOutcome("Ana", mine = 12, theirs = 10))
        assertEquals("Ana stays ahead: 10 to 10.", ArcadeRules.challengeOutcome("Ana", mine = 10, theirs = 10))
        assertEquals("Ana stays ahead: 30 to 4.", ArcadeRules.challengeOutcome("Ana", mine = 4, theirs = 30))
    }

    @Test
    fun hostSubtitle() {
        assertEquals("Good from 100 points", ArcadeRules.hostSubtitle(100, isDaily = true, challengeFriend = "Ana", challengeScore = 5, best = 50))
        assertEquals("Beat Ana: 5", ArcadeRules.hostSubtitle(40, isDaily = false, challengeFriend = "Ana", challengeScore = 5, best = 50))
        assertEquals("Best 50", ArcadeRules.hostSubtitle(40, isDaily = false, challengeFriend = null, challengeScore = 0, best = 50))
        assertEquals("Good from 40 points", ArcadeRules.hostSubtitle(40, isDaily = false, challengeFriend = null, challengeScore = 0, best = 0))
    }

    @Test
    fun relativeTimesReadLikeSwiftNamedPresentation() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        assertEquals("now", ArcadeRules.relative(now.plusSeconds(30), now))
        assertEquals("in 1 minute", ArcadeRules.relative(now.plusSeconds(60), now))
        assertEquals("in 5 minutes", ArcadeRules.relative(now.plusSeconds(5 * 60 + 10), now))
        assertEquals("in 1 hour", ArcadeRules.relative(now.plusSeconds(3600), now))
        assertEquals("in 3 hours", ArcadeRules.relative(now.plusSeconds(3 * 3600 + 5), now))
        assertEquals("tomorrow", ArcadeRules.relative(now.plusSeconds(30 * 3600), now))
        assertEquals("in 3 days", ArcadeRules.relative(now.plusSeconds(3 * 86_400 + 60), now))
        assertEquals("2 hours ago", ArcadeRules.relative(now.minusSeconds(2 * 3600), now))
        assertEquals("yesterday", ArcadeRules.relative(now.minusSeconds(30 * 3600), now))
    }

    // MARK: Status lines

    @Test
    fun statusLines() {
        assertEquals("+1.2 s", ArcadeRules.racingGap(1.23))
        assertEquals("+0.0 s", ArcadeRules.racingGap(0.0))
        assertEquals("-0.4 s", ArcadeRules.racingGap(-0.44))
        assertEquals("1 block", ArcadeRules.blocks(1))
        assertEquals("0 blocks", ArcadeRules.blocks(0))
        assertEquals("12 blocks", ArcadeRules.blocks(12))
        assertEquals("x1.0", ArcadeRules.multiplier(1.0))
        assertEquals("x1.3", ArcadeRules.multiplier(1.3))
        assertEquals("x3.0", ArcadeRules.multiplier(3.0))
    }

    @Test
    fun rhythmLaneUnderATap() {
        assertEquals(0, ArcadeRules.rhythmLane(-10.0))
        assertEquals(0, ArcadeRules.rhythmLane(0.0))
        assertEquals(0, ArcadeRules.rhythmLane(20.0))
        assertEquals(0, ArcadeRules.rhythmLane(34.9))
        assertEquals(1, ArcadeRules.rhythmLane(35.0))
        assertEquals(1, ArcadeRules.rhythmLane(50.0))
        assertEquals(2, ArcadeRules.rhythmLane(80.0))
        assertEquals(2, ArcadeRules.rhythmLane(120.0))
    }

    // MARK: Bath

    @Test
    fun mudSpotsGrowWithDirt() {
        assertNull(ArcadeRules.mudSpots(96.0))
        assertNull(ArcadeRules.mudSpots(100.0))
        assertEquals(3, ArcadeRules.mudSpots(95.0))
        assertEquals(4, ArcadeRules.mudSpots(50.0))
        assertEquals(9, ArcadeRules.mudSpots(0.0))
    }

    @Test
    fun bathCopy() {
        assertEquals("Already clean", ArcadeRules.bathHeadline(alreadyClean = true, finished = false, spotsLeft = 0))
        assertEquals("Sparkling clean", ArcadeRules.bathHeadline(alreadyClean = false, finished = true, spotsLeft = 0))
        assertEquals("1 mud spot left", ArcadeRules.bathHeadline(alreadyClean = false, finished = false, spotsLeft = 1))
        assertEquals("4 mud spots left", ArcadeRules.bathHeadline(alreadyClean = false, finished = false, spotsLeft = 4))
        assertEquals("Come back when it gets muddy.", ArcadeRules.bathHint(alreadyClean = true, finished = false))
        assertEquals("All the mud is gone.", ArcadeRules.bathHint(alreadyClean = false, finished = true))
        assertEquals("Rub the mud off with your finger.", ArcadeRules.bathHint(alreadyClean = false, finished = false))
    }

    @Test
    fun mudBlobIsLumpyAndStable() {
        val blob = ArcadeRules.mudBlob(0)
        assertEquals(10, blob.size)
        assertEquals(blob, ArcadeRules.mudBlob(0))
        assertNotEquals(blob, ArcadeRules.mudBlob(1))
        // The first corner sits straight to the right of the centre.
        assertEquals(0.0, blob[0].second, 1e-9)
        for ((x, y) in blob) {
            val r = hypot(x, y)
            assertTrue("radius $r", r >= 0.36 - 1e-9 && r <= 0.5 + 1e-9)
        }
    }

    // MARK: Scatter and geometry

    @Test
    fun scatterFollowsSplitMix() {
        val points = ArcadeRules.scatter(77uL, 26)
        assertEquals(26, points.size)
        val rng = SplitMix(77uL)
        for (p in points) {
            assertEquals(rng.unit(), p.first, 0.0)
            assertEquals(rng.unit(), p.second, 0.0)
            assertTrue(p.first in 0.0..1.0 && p.second in 0.0..1.0)
        }
        assertEquals(ArcadeRules.values(502uL, 6), ArcadeRules.values(502uL, 6))
        assertEquals(6, ArcadeRules.values(502uL, 6).size)
    }

    @Test
    fun positiveRemainderWraps() {
        assertEquals(13.0, ArcadeRules.positiveRemainder(-5.0, 18.0), 1e-9)
        assertEquals(4.0, ArcadeRules.positiveRemainder(40.0, 18.0), 1e-9)
        assertEquals(0.0, ArcadeRules.positiveRemainder(36.0, 18.0), 1e-9)
        assertEquals(0.0, ArcadeRules.positiveRemainder(5.0, 0.0), 1e-9)
    }

    @Test
    fun pixelSizeKeepsWholePixels() {
        assertEquals(8.0, ArcadeRules.pixelSize(12, 12, 100.0, 100.0), 0.0)
        // The narrower side decides.
        assertEquals(4.0, ArcadeRules.pixelSize(24, 20, 100.0, 90.0), 0.0)
        // Smaller than the art: a fraction is all that fits.
        assertTrue(abs(ArcadeRules.pixelSize(24, 20, 12.0, 10.0) - 0.5) < 1e-9)
        assertEquals(0.0, ArcadeRules.pixelSize(0, 20, 12.0, 10.0), 0.0)
        assertEquals(0.0, ArcadeRules.pixelSize(24, 20, 0.0, 10.0), 0.0)
    }

    // MARK: Session

    @Test
    fun sessionStateMirrorsTheRound() {
        val round = GameSession(GameID.snackCatcher, seed = 42uL)
        val session = SessionState(round)
        assertEquals(42uL, session.seed)
        assertEquals(round.id, session.id)
        assertTrue(session.running)

        session.score = 17
        session.status = "0:12"
        assertEquals(17, round.score)
        assertEquals("0:12", round.status)

        session.paused = true
        assertTrue(round.paused)
        assertFalse(session.running)
        session.paused = false

        session.end()
        session.end()
        assertTrue(session.ended)
        assertTrue(round.ended)
        assertFalse(session.running)
        val result = round.result()
        assertEquals(17, result.score)
        assertEquals(round.id, result.session)
    }
}
