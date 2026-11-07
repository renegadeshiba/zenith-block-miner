package com.zenithplugins.blockminer.module;

import com.zenith.module.impl.KillAura;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static com.zenith.Globals.MODULE;

/**
 * Whether Zenith's kill aura is in the middle of a fight, which the bot stops and gives way to. The aura outranks the
 * pathfinder, but it only does anything when it's ready for its next hit. In between, the pathfinder gets a turn: it
 * walks off, leaving what it's fighting out of reach, and it swaps back to its pickaxe.
 *
 * <p>That's only worth it while the aura is getting hits in. A mob at the edge of its reach can give it a target over
 * and over without it ever landing one, like a piglin with a crossbow keeping its distance, and then all standing
 * still does is leave the bot there being shot.
 */
final class Fights {

    // A hit knocks a mob back out of reach, and the aura forgets about it until it comes back.
    private static final long AFTER_THE_LAST_TARGET_MS = 2_000;
    // How long it gives the aura to get a hit in, from the start of a fight and from each hit. Its own hits come
    // every half a second or so.
    private static final long BETWEEN_HITS_MS = 3_000;
    // Something it can't kill, like a mob it can hit but not hurt, can't be allowed to stop the bot for good.
    private static final long PATIENCE_MS = 60_000;
    // It can go on happening, so it's not worth saying any more often than this.
    private static final long BETWEEN_REPORTS_OF_NO_HITS_MS = 5 * 60_000;
    private static final long NOT_FIGHTING = -1;
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final BooleanSupplier auraHasTarget;
    private final LongSupplier clock;
    private final Runnable whenOutOfPatience;
    private final Runnable whenNotHitting;
    private long startedMs = NOT_FIGHTING;
    private long lastTargetMs;
    private long lastHitMs = NEVER;
    private long lastReportedNoHitsMs = NEVER;
    private boolean outOfPatience;

    Fights(Runnable whenOutOfPatience, Runnable whenNotHitting) {
        this(Fights::killAuraHasTarget, System::currentTimeMillis, whenOutOfPatience, whenNotHitting);
    }

    Fights(BooleanSupplier auraHasTarget, LongSupplier clock, Runnable whenOutOfPatience, Runnable whenNotHitting) {
        this.auraHasTarget = auraHasTarget;
        this.clock = clock;
        this.whenOutOfPatience = whenOutOfPatience;
        this.whenNotHitting = whenNotHitting;
    }

    /** For when there's nothing to give way to. */
    static Fights none() {
        return new Fights(() -> false, () -> 0, () -> { }, () -> { });
    }

    private static boolean killAuraHasTarget() {
        KillAura killAura = MODULE.get(KillAura.class);
        return killAura != null && killAura.isActive();
    }

    /** The bot has just attacked something, which is the kill aura getting a hit in. Called from the network thread. */
    synchronized void hitLanded() {
        lastHitMs = clock.getAsLong();
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
        if (now - Math.max(startedMs, lastHitMs) > BETWEEN_HITS_MS) {
            if (now - lastReportedNoHitsMs > BETWEEN_REPORTS_OF_NO_HITS_MS) {
                lastReportedNoHitsMs = now;
                whenNotHitting.run();
            }
            return false;
        }
        if (now - startedMs <= PATIENCE_MS) return true;
        if (!outOfPatience) whenOutOfPatience.run();
        outOfPatience = true;
        return false;
    }
}
