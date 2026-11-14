package com.orijua.overgearedmod.feature.capsule;

import com.mojang.serialization.JsonOps;
import com.orijua.overgearedmod.core.config.CapsuleBalance;
import com.orijua.overgearedmod.core.config.FatigueBalance;
import com.orijua.overgearedmod.feature.fatigue.FatigueRules;
import com.orijua.overgearedmod.feature.fatigue.FatigueRules.SessionModifiers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapsuleRulesTest {
    private static final BlockPos BAIRAN = new BlockPos(10, 70, 20);
    private static final BlockPos SAVED = new BlockPos(-300, 80, 512);

    @Test
    void theFirstConnectionLandsInBairan() {
        CapsuleRules.Spawn spawn = CapsuleRules.spawn(Optional.empty(), Optional.of(BAIRAN)).orElseThrow();
        assertEquals(BAIRAN, spawn.pos());
        assertEquals(CapsuleRules.Source.BAIRAN, spawn.source());
    }

    @Test
    void laterConnectionsStartWhereYouLastDisconnected() {
        CapsuleRules.Spawn spawn = CapsuleRules.spawn(Optional.of(SAVED), Optional.of(BAIRAN)).orElseThrow();
        assertEquals(SAVED, spawn.pos());
        assertEquals(CapsuleRules.Source.LAST_SAVE, spawn.source());
    }

    @Test
    void aSavedSpotWorksEvenWithoutBairan() {
        assertEquals(CapsuleRules.Source.LAST_SAVE, CapsuleRules.spawn(Optional.of(SAVED), Optional.empty()).orElseThrow().source());
    }

    @Test
    void withNeitherThereIsNowhereToAppear() {
        assertTrue(CapsuleRules.spawn(Optional.empty(), Optional.empty()).isEmpty());
    }

    @Test
    void inReachStopsAtMaxDistance() {
        assertTrue(CapsuleRules.inReach(CapsuleRules.MAX_DISTANCE * CapsuleRules.MAX_DISTANCE));
        assertFalse(CapsuleRules.inReach(CapsuleRules.MAX_DISTANCE * CapsuleRules.MAX_DISTANCE + 0.01));
    }

    @Test
    void stayedStillAllowsTheToleranceAndNothingMore() {
        assertTrue(CapsuleRules.stayedStill(0.0));
        double limit = CapsuleRules.STILL_TOLERANCE * CapsuleRules.STILL_TOLERANCE;
        assertTrue(CapsuleRules.stayedStill(limit));
        assertFalse(CapsuleRules.stayedStill(Math.nextUp(limit)));
        assertFalse(CapsuleRules.stayedStill(0.36));
        assertFalse(CapsuleRules.stayedStill(Double.NaN));
        assertFalse(CapsuleRules.stayedStill(Double.POSITIVE_INFINITY));
    }

    @Test
    void stillToleranceIsSmallerThanTheReach() {
        assertTrue(CapsuleRules.STILL_TOLERANCE < CapsuleRules.MAX_DISTANCE);
    }

    @Test
    void stateRoundTripsThroughItsCodec() {
        GlobalPos capsule = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 2));
        GlobalPos exit = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 3));
        CapsuleState connected = CapsuleState.EMPTY.connected(capsule, exit);
        assertEquals(connected, roundTrip(connected));
        assertEquals(connected.disconnected(SAVED), roundTrip(connected.disconnected(SAVED)));
        assertEquals(CapsuleState.EMPTY, roundTrip(CapsuleState.EMPTY));
    }

    @Test
    void disconnectingSavesTheSpotAndBringsTheBodyBack() {
        GlobalPos capsule = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 2));
        GlobalPos exit = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 3));
        CapsuleState back = CapsuleState.EMPTY.connected(capsule, exit).disconnected(SAVED);
        assertEquals(Optional.of(SAVED), back.lastSave());
        assertTrue(back.capsule().isEmpty() && back.exit().isEmpty(), "the body is back in the real world");
    }

    @Test
    void connectingKeepsTheLastSave() {
        CapsuleState state = CapsuleState.EMPTY.disconnected(SAVED)
                .connected(GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO.above()));
        assertEquals(Optional.of(SAVED), state.lastSave());
    }

    @Test
    void withoutBodyKeepsTheLastSave() {
        GlobalPos capsule = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
        GlobalPos exit = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO.above());
        CapsuleState freed = CapsuleState.EMPTY.disconnected(SAVED).connected(capsule, exit).withoutBody();
        assertTrue(freed.capsule().isEmpty() && freed.exit().isEmpty());
        assertEquals(Optional.of(SAVED), freed.lastSave());
        assertEquals(CapsuleState.EMPTY, CapsuleState.EMPTY.connected(capsule, exit).withoutBody());
    }

    @Test
    void withoutBodyRoundTripsThroughItsCodec() {
        CapsuleState freed = CapsuleState.EMPTY.disconnected(SAVED)
                .connected(GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO.above()))
                .withoutBody();
        assertEquals(freed, roundTrip(freed));
    }

    @Test
    void isConnectedFollowsTheCapsule() {
        GlobalPos spot = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
        assertFalse(CapsuleState.EMPTY.isConnected());
        assertFalse(CapsuleState.EMPTY.disconnected(SAVED).isConnected());
        assertTrue(CapsuleState.EMPTY.connected(spot, spot).isConnected());
    }

    @Test
    void coherenceCoversTheFourCases() {
        GlobalPos spot = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
        CapsuleState connected = CapsuleState.EMPTY.connected(spot, spot);
        assertEquals(CapsuleRules.Coherence.OK, CapsuleRules.coherence(connected, true));
        assertEquals(CapsuleRules.Coherence.CONNECTED_OUTSIDE, CapsuleRules.coherence(connected, false));
        assertEquals(CapsuleRules.Coherence.DISCONNECTED_INSIDE, CapsuleRules.coherence(CapsuleState.EMPTY, true));
        assertEquals(CapsuleRules.Coherence.OK, CapsuleRules.coherence(CapsuleState.EMPTY, false));
    }

    @Test
    void theClientScreenFollowsTheServerWait() {
        try {
            ClientCapsule.connecting(0, 1_000);
            assertEquals(-1.0f, ClientCapsule.fraction(1_000), "nothing connecting");
            ClientCapsule.connecting(-5, 1_000);
            assertEquals(-1.0f, ClientCapsule.fraction(1_000), "negative ticks mean nothing connecting");
            long duration = CapsuleRules.CONNECT_TICKS * 50L;
            ClientCapsule.connecting(CapsuleRules.CONNECT_TICKS, 10_000);
            assertEquals(0.0f, ClientCapsule.fraction(9_000), 1e-6, "before the start: 0");
            assertEquals(0.0f, ClientCapsule.fraction(10_000), 1e-6);
            assertEquals(0.5f, ClientCapsule.fraction(10_000 + duration / 2), 1e-3);
            assertEquals(1.0f, ClientCapsule.fraction(10_000 + duration + 100), 1e-6);
            assertEquals(1.0f, ClientCapsule.fraction(10_000 + duration + 1_000), 1e-6, "last moment the screen stays at 1");
            assertEquals(-1.0f, ClientCapsule.fraction(10_000 + duration + 1_001), "a lost packet must not leave the screen up");
        } finally {
            ClientCapsule.connecting(0, 0);
        }
    }

    @Test
    void onlyTheTwoCrossingsWithoutPermissionAreBlocked() {
        assertFalse(CapsuleRules.crossingBlocked(false, false, false), "real to real");
        assertFalse(CapsuleRules.crossingBlocked(true, true, false), "Satisfy to Satisfy");
        assertTrue(CapsuleRules.crossingBlocked(false, true, false), "real to Satisfy");
        assertTrue(CapsuleRules.crossingBlocked(true, false, false), "Satisfy to real");
        assertFalse(CapsuleRules.crossingBlocked(false, false, true));
        assertFalse(CapsuleRules.crossingBlocked(true, true, true));
        assertFalse(CapsuleRules.crossingBlocked(false, true, true), "allowed: the capsule");
        assertFalse(CapsuleRules.crossingBlocked(true, false, true), "allowed: the capsule");
    }

    private static CapsuleState roundTrip(CapsuleState state) {
        var json = CapsuleState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        return CapsuleState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    private static final FatigueBalance F = FatigueBalance.DEFAULT;
    private static final CapsuleBalance C = CapsuleBalance.DEFAULT;

    private static double sessionMinutes(CapsuleTier tier) {
        SessionModifiers m = CapsuleRules.modifiers(tier, F, C);
        return 1.0 / FatigueRules.drainPerSecond(F, FatigueRules.Activity.NORMAL, m) / 60.0;
    }

    private static double recoveryMinutes(CapsuleTier tier) {
        SessionModifiers m = CapsuleRules.modifiers(tier, F, C);
        return 1.0 / FatigueRules.recoverPerSecond(F, m) / 60.0;
    }

    @Test
    void theBasicCapsuleIsExactlyNeutral() {
        assertEquals(SessionModifiers.NEUTRAL, CapsuleRules.modifiers(CapsuleTier.BASIC, F, C));
        CapsuleBalance odd = new CapsuleBalance(30, 5, 60, 3, 100, 200, 5, 20, 0.5, 30);
        assertEquals(SessionModifiers.NEUTRAL, CapsuleRules.modifiers(CapsuleTier.BASIC, F, odd));
    }

    @Test
    void sessionsLast40And55And75Minutes() {
        assertEquals(40.0, sessionMinutes(CapsuleTier.BASIC), 1e-9);
        assertEquals(55.0, sessionMinutes(CapsuleTier.ADVANCED), 1e-9);
        assertEquals(75.0, sessionMinutes(CapsuleTier.DIAMOND), 1e-9);
    }

    @Test
    void recoveriesTake10And8And6Minutes() {
        assertEquals(10.0, recoveryMinutes(CapsuleTier.BASIC), 1e-9);
        assertEquals(8.0, recoveryMinutes(CapsuleTier.ADVANCED), 1e-9);
        assertEquals(6.0, recoveryMinutes(CapsuleTier.DIAMOND), 1e-9);
    }

    @Test
    void onlyTheDiamondCarriesRestedSeconds() {
        assertEquals(0, CapsuleRules.modifiers(CapsuleTier.BASIC, F, C).restedSeconds());
        assertEquals(0, CapsuleRules.modifiers(CapsuleTier.ADVANCED, F, C).restedSeconds());
        assertEquals(600, CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C).restedSeconds());
    }

    @Test
    void restedNeedsTheMinimumEnergyComparedAsFloats() {
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        assertEquals(600, CapsuleRules.restedOnConnect(diamond, 0.95, C));
        assertEquals(600, CapsuleRules.restedOnConnect(diamond, 0.90, C));
        assertEquals(600, CapsuleRules.restedOnConnect(diamond, (double) 0.90f, C));
        assertEquals(600, CapsuleRules.restedOnConnect(diamond, 1.0, C));
        assertEquals(0, CapsuleRules.restedOnConnect(diamond, 0.8999, C));
        assertEquals(0, CapsuleRules.restedOnConnect(diamond, 0.85, C));
    }

    @Test
    void otherTiersAndAZeroDurationGiveNoRested() {
        assertEquals(0, CapsuleRules.restedOnConnect(CapsuleRules.modifiers(CapsuleTier.ADVANCED, F, C), 1.0, C));
        assertEquals(0, CapsuleRules.restedOnConnect(CapsuleRules.modifiers(CapsuleTier.BASIC, F, C), 1.0, C));
        CapsuleBalance off = new CapsuleBalance(55, 8, 75, 6, 3000, 15000, 0, 10, 0.90, 30);
        assertEquals(0, CapsuleRules.restedOnConnect(CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, off), 1.0, off));
    }

    @Test
    void connectingGrantsTheRestedAndOpensTheWait() {
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        assertEquals(new CapsuleRules.RestedGrant(600, 1800), CapsuleRules.onConnect(diamond, 1.0, 0, C));
        assertEquals(new CapsuleRules.RestedGrant(600, 1800), CapsuleRules.onConnect(diamond, 0.9, -5, C), "a negative wait is none");
    }

    @Test
    void whileTheWaitRunsNothingIsGrantedAndTheWaitIsNotRestarted() {
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        assertEquals(new CapsuleRules.RestedGrant(0, 1234), CapsuleRules.onConnect(diamond, 1.0, 1234, C));
        assertEquals(new CapsuleRules.RestedGrant(0, 1), CapsuleRules.onConnect(diamond, 1.0, 1, C));
    }

    @Test
    void aConnectionThatGrantsNothingOpensNoWait() {
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        assertEquals(new CapsuleRules.RestedGrant(0, 0), CapsuleRules.onConnect(diamond, 0.85, 0, C), "low energy");
        assertEquals(new CapsuleRules.RestedGrant(0, 0),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.ADVANCED, F, C), 1.0, 0, C), "advanced");
        CapsuleBalance off = withRested(0, 0.9);
        assertEquals(new CapsuleRules.RestedGrant(0, 0), CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, off), 1.0, 0, off),
                "restedMinutes 0");
    }

    @Test
    void aWaitOfZeroMinutesAlwaysGrants() {
        CapsuleBalance noWait = new CapsuleBalance(55, 8, 75, 6, 3000, 15000, 10, 10, 0.90, 0);
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, noWait);
        assertEquals(new CapsuleRules.RestedGrant(600, 0), CapsuleRules.onConnect(diamond, 1.0, 0, noWait));
        assertEquals(new CapsuleRules.RestedGrant(600, 0), CapsuleRules.onConnect(diamond, 1.0, CapsuleRules.cooldownLeft(1800, noWait), noWait));
    }

    @Test
    void theWaitLeftIsClampedToTheConfiguredWait() {
        CapsuleBalance fiveMinutes = new CapsuleBalance(55, 8, 75, 6, 3000, 15000, 10, 10, 0.90, 5);
        CapsuleBalance noWait = new CapsuleBalance(55, 8, 75, 6, 3000, 15000, 10, 10, 0.90, 0);
        assertEquals(300, CapsuleRules.cooldownLeft(1800, fiveMinutes));
        assertEquals(120, CapsuleRules.cooldownLeft(120, fiveMinutes));
        assertEquals(0, CapsuleRules.cooldownLeft(-7, C));
        assertEquals(0, CapsuleRules.cooldownLeft(1800, noWait));
        assertEquals(1800, CapsuleRules.cooldownLeft(1800, C));
    }

    @Test
    void theWaitCountsDownToZeroAndStops() {
        assertEquals(1799, CapsuleRules.tickDown(1800));
        assertEquals(0, CapsuleRules.tickDown(1));
        assertEquals(0, CapsuleRules.tickDown(0));
        assertEquals(0, CapsuleRules.tickDown(-3));
    }

    @Test
    void theWaitRulesSurviveTheExtremesOfAnInt() {
        assertEquals(1800, CapsuleRules.cooldownLeft(Integer.MAX_VALUE, C), "a huge saved wait is cut to the configured one");
        assertEquals(0, CapsuleRules.cooldownLeft(Integer.MIN_VALUE, C), "a hugely negative one is none");
        assertEquals(Integer.MAX_VALUE - 1, CapsuleRules.tickDown(Integer.MAX_VALUE));
        assertEquals(0, CapsuleRules.tickDown(Integer.MIN_VALUE), "no wrap-around to a huge wait");
        CapsuleBalance longest = new CapsuleBalance(55, 8, 75, 6, 3000, 15000, 10, 10, 0.90, CapsuleBalance.MAX_RESTED_COOLDOWN_MINUTES);
        assertEquals(86_400, CapsuleRules.cooldownLeft(Integer.MAX_VALUE, longest), "the longest wait still fits an int");
    }

    @Test
    void aRunningWaitIsKeptWhateverTheConnectionWouldHaveGranted() {
        CapsuleBalance noRested = withRested(0, 0.9);
        assertEquals(new CapsuleRules.RestedGrant(0, 500),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, noRested), 1.0, 500, noRested), "rested minutes 0");
        assertEquals(new CapsuleRules.RestedGrant(0, 500),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.ADVANCED, F, C), 1.0, 500, C), "advanced tier");
        assertEquals(new CapsuleRules.RestedGrant(0, 500),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.BASIC, F, C), 0.1, 500, C), "basic tier, low energy");
    }

    @Test
    void anEnergyThatIsNotANumberGrantsNothingAndOpensNoWait() {
        assertEquals(new CapsuleRules.RestedGrant(0, 0),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.ADVANCED, F, C), Double.NaN, 0, C), "advanced");
        assertEquals(new CapsuleRules.RestedGrant(0, 0),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C), Double.NaN, 0, C), "diamond");
        assertEquals(new CapsuleRules.RestedGrant(0, 700),
                CapsuleRules.onConnect(CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C), Double.NaN, 700, C), "a running wait still goes on");
    }

    private static CapsuleBalance withRested(int restedMinutes, double minEnergy) {
        return new CapsuleBalance(55, 8, 75, 6, 3000, 15000, restedMinutes, 10, minEnergy, 30);
    }

    @Test
    void theMultipliersAreExactlyTheRatiosOfTheDesign() {
        SessionModifiers advanced = CapsuleRules.modifiers(CapsuleTier.ADVANCED, F, C);
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        assertAll(
                () -> assertEquals(55.0 / 40.0, advanced.durationMul()),
                () -> assertEquals(10.0 / 8.0, advanced.recoveryMul()),
                () -> assertEquals(75.0 / 40.0, diamond.durationMul()),
                () -> assertEquals(10.0 / 6.0, diamond.recoveryMul()));
    }

    @Test
    void theTiersKeepTheirMinutesWhateverTheFatigueBalanceIs() {
        FatigueBalance other = new FatigueBalance(true, 20.0, 0.8, 2.0, 20.0, 5.0, 0.25, 15, java.util.List.of(0.15, 0.08, 0.02), 0.30);
        for (CapsuleTier tier : new CapsuleTier[]{CapsuleTier.ADVANCED, CapsuleTier.DIAMOND}) {
            SessionModifiers m = CapsuleRules.modifiers(tier, other, C);
            double session = 1.0 / FatigueRules.drainPerSecond(other, FatigueRules.Activity.NORMAL, m) / 60.0;
            double recovery = 1.0 / FatigueRules.recoverPerSecond(other, m) / 60.0;
            assertAll(tier.toString(),
                    () -> assertEquals(C.sessionMinutes(tier, other), session, 1e-9, "session"),
                    () -> assertEquals(C.recoveryMinutes(tier, other), recovery, 1e-9, "recovery"));
        }
        assertEquals(55.0, 1.0 / FatigueRules.drainPerSecond(other, FatigueRules.Activity.NORMAL, CapsuleRules.modifiers(CapsuleTier.ADVANCED, other, C)) / 60.0, 1e-9);
        assertEquals(8.0, 1.0 / FatigueRules.recoverPerSecond(other, CapsuleRules.modifiers(CapsuleTier.ADVANCED, other, C)) / 60.0, 1e-9);
    }

    @Test
    void restedJustBelowTheMinimumIsStillTheMinimumAsAFloat() {
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        assertEquals(600, CapsuleRules.restedOnConnect(diamond, 0.9 - 1e-9, C), "the same float as 0.9");
        assertEquals(0, CapsuleRules.restedOnConnect(diamond, 0.9 - 1e-6, C), "a visible difference as a float");
    }

    @Test
    void restedMinEnergyAtTheEdges() {
        CapsuleBalance zero = withRested(10, 0.0);
        SessionModifiers diamondZero = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, zero);
        CapsuleBalance one = withRested(10, 1.0);
        SessionModifiers diamondOne = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, one);
        assertAll(
                () -> assertEquals(600, CapsuleRules.restedOnConnect(diamondZero, 0.0, zero), "minimum 0, energy 0"),
                () -> assertEquals(600, CapsuleRules.restedOnConnect(diamondZero, 0.5, zero), "minimum 0, energy .5"),
                () -> assertEquals(600, CapsuleRules.restedOnConnect(diamondOne, 1.0, one), "minimum 1, energy 1"),
                () -> assertEquals(0, CapsuleRules.restedOnConnect(diamondOne, 0.999, one), "minimum 1, energy .999"));
    }

    @Test
    void aNonsenseEnergyGivesNothing() {
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, C);
        CapsuleBalance zero = withRested(10, 0.0);
        assertAll(
                () -> assertEquals(0, CapsuleRules.restedOnConnect(diamond, Double.NaN, C), "NaN"),
                () -> assertEquals(0, CapsuleRules.restedOnConnect(diamond, -0.5, C), "negative"),
                () -> assertEquals(0, CapsuleRules.restedOnConnect(diamond, Double.NaN, zero), "NaN, minimum 0"),
                () -> assertEquals(0, CapsuleRules.restedOnConnect(diamond, -0.5, zero), "negative, minimum 0"));
    }

    @Test
    void theLongestRestedIsADayOfSeconds() {
        CapsuleBalance longest = withRested(1440, 0.9);
        SessionModifiers diamond = CapsuleRules.modifiers(CapsuleTier.DIAMOND, F, longest);
        assertEquals(86_400, diamond.restedSeconds());
        assertEquals(86_400, CapsuleRules.restedOnConnect(diamond, 1.0, longest));
    }

    @Test
    void noRestedSecondsInTheModifiersMeansNoneEvenAtFullEnergy() {
        SessionModifiers none = new SessionModifiers(1.4, 1.2, 0);
        assertEquals(0, CapsuleRules.restedOnConnect(none, 1.0, C));
    }
}
