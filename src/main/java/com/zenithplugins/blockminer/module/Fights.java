package com.zenithplugins.blockminer.module;

import com.zenith.module.impl.KillAura;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static com.zenith.Globals.MODULE;

/**
 * Whether Zenith's kill aura is in the middle of a fight, which the bot stops and gives way to. The aura outranks the
 * pathfinder, but it only does anything when it's ready for its next hit. In between, the pathfinder gets a turn: it
 * walks off, leaving what it's fighting out of reach, and it swaps back to its pickaxe.
 */
final class Fights {

    // A hit knocks a mob back out of reach, and the aura forgets about it until it comes back.
    private static final long AFTER_THE_LAST_TARGET_MS = 2_000;
    // Something it can't kill, like a mob behind a wall, can't be allowed to stop the bot for good.
    private static final long PATIENCE_MS = 60_000;
    private static final long NOT_FIGHTING = -1;

    private final BooleanSupplier auraHasTarget;
    private final LongSupplier clock;
    private final Runnable whenOutOfPatience;
    private long startedMs = NOT_FIGHTING;
    private long lastTargetMs;
    private boolean outOfPatience;

    Fights(Runnable whenOutOfPatience) {
        this(Fights::killAuraHasTarget, System::currentTimeMillis, whenOutOfPatience);
    }

    Fights(BooleanSupplier auraHasTarget, LongSupplier clock, Runnable whenOutOfPatience) {
        this.auraHasTarget = auraHasTarget;
        this.clock = clock;
        this.whenOutOfPatience = whenOutOfPatience;
    }

    /** For when there's nothing to give way to. */
    static Fights none() {
        return new Fights(() -> false, () -> 0, () -> { });
    }

    private static boolean killAuraHasTarget() {
        KillAura killAura = MODULE.get(KillAura.class);
        return killAura != null && killAura.isActive();
    }

    /** True while the bot should leave it to the kill aura. Called from both the tick thread and the errand thread. */
    synchronized boolean underWay() {
        long now = clock.getAsLong();
        if (auraHasTarget.getAsBoolean()) {
            if (startedMs == NOT_FIGHTING) startedMs = now;
            lastTargetMs = now;
        } else if (startedMs == NOT_FIGHTING) {
            return false;
        } else if (now - lastTargetMs > AFTER_THE_LAST_TARGET_MS) {
            startedMs = NOT_FIGHTING;
            outOfPatience = false;
            return false;
        }
        if (now - startedMs <= PATIENCE_MS) return true;
        if (!outOfPatience) whenOutOfPatience.run();
        outOfPatience = true;
        return false;
    }
}
