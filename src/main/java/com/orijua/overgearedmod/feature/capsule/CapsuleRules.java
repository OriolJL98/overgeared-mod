package com.orijua.overgearedmod.feature.capsule;

import com.orijua.overgearedmod.core.config.CapsuleBalance;
import com.orijua.overgearedmod.core.config.FatigueBalance;
import com.orijua.overgearedmod.feature.fatigue.FatigueRules.SessionModifiers;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * The pure rules of the VR capsule (own design: the canon only says a capsule is how Satisfy is entered and left; the
 * waiting time and the spawn rules below are the mod's adaptation to Minecraft).
 */
public final class CapsuleRules {
    /** Lying down and connecting takes 3 seconds. */
    public static final int CONNECT_TICKS = 60;
    /** How far the player may get from the capsule while connecting before it is cancelled. */
    public static final double MAX_DISTANCE = 3.0;
    /**
     * How far the player may move (3D, measured from where the wait or countdown began, not tick to tick) and still
     * count as standing still.
     */
    public static final double STILL_TOLERANCE = 0.5;

    private CapsuleRules() {
    }

    /** Where a connection lands. */
    public enum Source { LAST_SAVE, BAIRAN }

    public record Spawn(BlockPos pos, Source source) {
    }

    /**
     * Where the player appears in Satisfy: where they last disconnected, or, the first time (or if that is not known),
     * Bairan's plaza. Empty if the world has neither (an old world without the Satisfy sites).
     */
    public static Optional<Spawn> spawn(Optional<BlockPos> lastSave, Optional<BlockPos> bairan) {
        if (lastSave.isPresent()) {
            return Optional.of(new Spawn(lastSave.get(), Source.LAST_SAVE));
        }
        return bairan.map(pos -> new Spawn(pos, Source.BAIRAN));
    }

    /** Whether the saved state agrees with where the player is. */
    public enum Coherence { OK, CONNECTED_OUTSIDE, DISCONNECTED_INSIDE }

    /** OK: connected and in Satisfy, or disconnected and outside it. */
    public static Coherence coherence(CapsuleState state, boolean inSatisfy) {
        if (state.isConnected()) {
            return inSatisfy ? Coherence.OK : Coherence.CONNECTED_OUTSIDE;
        }
        return inSatisfy ? Coherence.DISCONNECTED_INSIDE : Coherence.OK;
    }

    /**
     * Whether a trip between dimensions is cancelled: the real world and Satisfy are separate worlds, and the only ways
     * across are the capsule (connect/disconnect) and the debug goto, which are {@code allowed}.
     */
    public static boolean crossingBlocked(boolean fromSatisfy, boolean toSatisfy, boolean allowed) {
        return fromSatisfy != toSatisfy && !allowed;
    }

    /** Whether the player is still close enough to the capsule to keep connecting. */
    public static boolean inReach(double distanceSquared) {
        return distanceSquared <= MAX_DISTANCE * MAX_DISTANCE;
    }

    /**
     * Whether the player stayed still: the squared 3D distance from where they started is within {@link #STILL_TOLERANCE}.
     * Turning the camera does not count as moving; NaN counts as moved.
     */
    public static boolean stayedStill(double distanceSquared) {
        return distanceSquared <= STILL_TOLERANCE * STILL_TOLERANCE;
    }

    /**
     * How the capsule of this tier changes the session. The basic capsule is exactly {@link SessionModifiers#NEUTRAL}; the
     * others stretch the session and speed up the recovery relative to the fatigue's numbers, and only the diamond one
     * carries rested seconds.
     */
    public static SessionModifiers modifiers(CapsuleTier tier, FatigueBalance fatigue, CapsuleBalance capsule) {
        if (tier == CapsuleTier.BASIC) {
            return SessionModifiers.NEUTRAL;
        }
        double durationMul = capsule.sessionMinutes(tier, fatigue) / fatigue.sessionMinutes();
        double recoveryMul = fatigue.recoveryMinutes() / capsule.recoveryMinutes(tier, fatigue);
        int restedSeconds = tier == CapsuleTier.DIAMOND ? capsule.restedMinutes() * 60 : 0;
        return new SessionModifiers(durationMul, recoveryMul, restedSeconds);
    }

    /**
     * The rested seconds a connection starts with: those of the modifiers if the energy reaches the minimum (compared as
     * floats, like the saved energy and {@code FatigueRules.canConnect}), otherwise none. It never adds to what the
     * player already had.
     */
    public static int restedOnConnect(SessionModifiers modifiers, double energy, CapsuleBalance capsule) {
        return (float) energy >= (float) capsule.restedMinEnergy() ? modifiers.restedSeconds() : 0;
    }

    /** What a connection does with the rested bonus: the seconds it starts with and the seconds of wait that follow. */
    public record RestedGrant(int restedSeconds, int cooldownSeconds) {
    }

    /**
     * What connecting gives. While a wait is running ({@code cooldownLeft} > 0) there is no bonus and the wait goes on as
     * it is. Otherwise the bonus is the one of {@link #restedOnConnect} and, only if it was granted (more than 0 seconds),
     * the wait starts; a connection that grants nothing (low energy, another tier) never opens one.
     */
    public static RestedGrant onConnect(SessionModifiers modifiers, double energy, int cooldownLeft, CapsuleBalance capsule) {
        if (cooldownLeft > 0) {
            return new RestedGrant(0, cooldownLeft);
        }
        int granted = restedOnConnect(modifiers, energy, capsule);
        return new RestedGrant(granted, granted > 0 ? capsule.restedCooldownMinutes() * 60 : 0);
    }

    /**
     * The wait that really remains: what is saved, within 0 and the configured wait (lowering the config shortens a wait
     * already running, and 0 frees it).
     */
    public static int cooldownLeft(int saved, CapsuleBalance capsule) {
        return Math.max(0, Math.min(saved, capsule.restedCooldownMinutes() * 60));
    }

    /** One second of the wait goes by; it never drops below 0 (nor wraps around for the smallest int). */
    public static int tickDown(int seconds) {
        return seconds <= 0 ? 0 : seconds - 1;
    }
}
