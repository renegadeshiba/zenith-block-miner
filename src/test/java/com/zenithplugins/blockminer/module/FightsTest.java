package com.zenithplugins.blockminer.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FightsTest {

    private boolean auraHasTarget;
    private long now = 1_000_000;
    private int timesOutOfPatience;
    private final Fights fights = new Fights(() -> auraHasTarget, () -> now, () -> timesOutOfPatience++);

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
        auraHasTarget = false;
        now += 1_500;
        assertTrue(fights.underWay());

        auraHasTarget = true;
        now += 1_500;
        assertTrue(fights.underWay());

        auraHasTarget = false;
        now += 1_500;
        assertTrue(fights.underWay(), "it's been less time than that since it last had a target");
    }

    @Test
    void itWontWaitForeverOnSomethingItCantKill() {
        auraHasTarget = true;
        fights.underWay();

        now += 60_000;
        assertTrue(fights.underWay());

        now += 1;
        assertFalse(fights.underWay());
        assertEquals(1, timesOutOfPatience);

        now += 60_000;
        assertFalse(fights.underWay());
        assertEquals(1, timesOutOfPatience, "once is enough to say so");
    }

    @Test
    void theNextFightGetsAsLongAsTheFirst() {
        auraHasTarget = true;
        fights.underWay();
        now += 60_001;
        assertFalse(fights.underWay());

        auraHasTarget = false;
        now += 2_001;
        assertFalse(fights.underWay());

        auraHasTarget = true;
        assertTrue(fights.underWay());
        now += 60_000;
        assertTrue(fights.underWay());
        now += 1;
        assertFalse(fights.underWay());
        assertEquals(2, timesOutOfPatience);
    }

    @Test
    void noFightsMeansNoFights() {
        assertFalse(Fights.none().underWay());
    }
}
