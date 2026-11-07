package com.zenithplugins.blockminer.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FightsTest {

    private boolean auraHasTarget;
    private long now = 1_000_000;
    private int timesOutOfPatience;
    private int timesNotHitting;
    private final Fights fights = new Fights(() -> auraHasTarget, () -> now, () -> timesOutOfPatience++, () -> timesNotHitting++);

    /** The kill aura hitting what it's fighting every couple of seconds, for this long, with the bot looking in on it. */
    private void hittingFor(long ms) {
        for (long passed = 0; passed < ms; passed += 2_000) {
            fights.hitLanded();
            fights.underWay();
            now += Math.min(2_000, ms - passed);
        }
    }

    @Test
    void thereIsNoFightWithoutATarget() {
        assertFalse(fights.underWay());
        now += 100_000;
        assertFalse(fights.underWay());
    }

    @Test
    void aTargetStartsAFight() {
        auraHasTarget = true;
        assertTrue(fights.underWay());
    }

    /** A hit knocks a mob out of reach for a moment, and walking off in that moment would leave it behind. */
    @Test
    void itStaysUnderWayForAMomentAfterTheLastTarget() {
        auraHasTarget = true;
        assertTrue(fights.underWay());

        auraHasTarget = false;
        now += 2_000;
        assertTrue(fights.underWay());

        now += 1;
        assertFalse(fights.underWay());
    }

    @Test
    void aMobComingBackIsTheSameFight() {
        auraHasTarget = true;
        fights.underWay();
        fights.hitLanded();
        auraHasTarget = false;
        now += 1_500;
        assertTrue(fights.underWay());

        auraHasTarget = true;
        now += 1_000;
        fights.hitLanded();
        assertTrue(fights.underWay());

        auraHasTarget = false;
        now += 1_500;
        assertTrue(fights.underWay(), "it's been less time than that since it last had a target");
    }

    /** What happened with piglins at the edge of its reach: a target over and over, and the bot stood there being shot. */
    @Test
    void itOnlyStandsStillWhileTheKillAuraIsGettingHitsIn() {
        auraHasTarget = true;
        assertTrue(fights.underWay());

        now += 3_000;
        assertTrue(fights.underWay(), "it gets a moment to get the first one in");
        now += 1;
        assertFalse(fights.underWay());

        fights.hitLanded();
        assertTrue(fights.underWay(), "and it's back to standing still once it does");
        now += 3_000;
        assertTrue(fights.underWay());
        now += 1;
        assertFalse(fights.underWay());
    }

    @Test
    void aHitCarriesItOverTheMomentsWhenTheKillAuraHasNoTarget() {
        auraHasTarget = true;
        fights.underWay();
        now += 2_900;
        fights.underWay();
        fights.hitLanded();
        auraHasTarget = false;
        now += 1_500;

        assertTrue(fights.underWay(), "the mob just got knocked back");
    }

    @Test
    void saysWhenTheKillAuraIsntGettingHitsInButNotOverAndOver() {
        auraHasTarget = true;
        fights.underWay();
        now += 3_001;
        assertFalse(fights.underWay());
        assertFalse(fights.underWay());
        assertEquals(1, timesNotHitting);

        // Another fight that goes the same way, soon after.
        auraHasTarget = false;
        now += 2_001;
        fights.underWay();
        auraHasTarget = true;
        fights.underWay();
        now += 3_001;
        assertFalse(fights.underWay());
        assertEquals(1, timesNotHitting, "that's still only a few seconds later");

        now += 5 * 60_000;
        assertFalse(fights.underWay());
        assertEquals(2, timesNotHitting);
    }

    @Test
    void itWontWaitForeverOnSomethingItCantKill() {
        auraHasTarget = true;
        fights.underWay();

        hittingFor(60_000);
        fights.hitLanded();
        assertTrue(fights.underWay());

        now += 1;
        fights.hitLanded();
        assertFalse(fights.underWay());
        assertEquals(1, timesOutOfPatience);

        hittingFor(10_000);
        fights.hitLanded();
        assertFalse(fights.underWay());
        assertEquals(1, timesOutOfPatience, "once is enough to say so");
    }

    @Test
    void theNextFightGetsAsLongAsTheFirst() {
        auraHasTarget = true;
        fights.underWay();
        hittingFor(60_001);
        fights.hitLanded();
        assertFalse(fights.underWay());

        auraHasTarget = false;
        now += 2_001;
        assertFalse(fights.underWay());

        auraHasTarget = true;
        fights.hitLanded();
        assertTrue(fights.underWay());
        hittingFor(60_000);
        fights.hitLanded();
        assertTrue(fights.underWay());
        now += 1;
        fights.hitLanded();
        assertFalse(fights.underWay());
        assertEquals(2, timesOutOfPatience);
    }

    @Test
    void noFightsMeansNoFights() {
        assertFalse(Fights.none().underWay());
    }
}
