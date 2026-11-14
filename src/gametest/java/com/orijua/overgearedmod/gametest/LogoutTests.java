package com.orijua.overgearedmod.gametest;

import com.orijua.overgearedmod.Overgearedmod;
import com.orijua.overgearedmod.feature.capsule.CapsuleService;
import com.orijua.overgearedmod.feature.capsule.LogoutRules;
import com.orijua.overgearedmod.feature.capsule.LogoutService;
import com.orijua.overgearedmod.feature.world.Satisfy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/**
 * {@code /satisfy logout}: the countdown, the refusal after combat and every way it is cancelled. The test level
 * plays Satisfy; ticks are given by hand (a fake player does not tick). {@code forgetAll()} clears the tables of every
 * player: it is only safe in these tests because their bodies are synchronous (no other test runs in the middle).
 */
@GameTestHolder(Overgearedmod.MODID)
@PrefixGameTestTemplate(false)
public final class LogoutTests {
    private LogoutTests() {
    }

    private static void tickMany(ServerPlayer player, int ticks) {
        TestSupport.tickMany(player, ticks, LogoutService::tick);
    }

    private static GlobalPos capsule(GameTestHelper helper) {
        return GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(0, 1, 0)));
    }

    private static void damage(ServerPlayer player) {
        TestSupport.hurt(player, 1f);
    }

    /** Connected, counting down, then {@code event} happens: the countdown stops and nobody is disconnected. */
    private static void cancelledBy(GameTestHelper helper, String what, Consumer<ServerPlayer> event) {
        cancelledBy(helper, what, true, event);
    }

    /**
     * As above; {@code bodyStays} is whether the body is still in the capsule afterwards (a death frees it, the rest
     * leave it where it was: nobody was disconnected).
     */
    private static void cancelledBy(GameTestHelper helper, String what, boolean bodyStays, Consumer<ServerPlayer> event) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), what + ": connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, what + ": the countdown starts");
                event.accept(player);
                helper.assertFalse(LogoutService.isCountingDown(player), what + ": the countdown is over");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS + 5);
                helper.assertTrue(CapsuleService.state(player).lastSave().isEmpty(), what + ": nothing was saved");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent() == bodyStays,
                        what + ": nobody was disconnected (the body " + (bodyStays ? "is" : "is not") + " in the capsule)");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void outsideSatisfyThereIsNothingToLogOutFrom(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.outsideSatisfy(helper, () -> {
            try {
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.NOT_IN_SATISFY, "the real world");
                helper.assertFalse(LogoutService.isCountingDown(player), "no countdown");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void combatInTheLastThreeSecondsRefusesTheLogout(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                long now = helper.getLevel().getServer().getTickCount();
                LogoutService.markCombat(player, now - (LogoutRules.DANGER_TICKS - 1));
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.IN_DANGER, "59 ticks ago: refused");
                helper.assertFalse(LogoutService.isCountingDown(player), "and nothing starts");
                LogoutService.markCombat(player, now - LogoutRules.DANGER_TICKS);
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "60 ticks ago: allowed");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void damageTakenRefusesTheLogout(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                damage(player);
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.IN_DANGER, "just hurt: refused");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void damageDealtRefusesTheLogout(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                LivingEntity zombie = EntityType.ZOMBIE.create(helper.getLevel());
                NeoForge.EVENT_BUS.post(new LivingDamageEvent.Post(zombie, new DamageContainer(player.damageSources().playerAttack(player), 1f)));
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.IN_DANGER, "just hit a mob: refused");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void theCountdownEndsInTheDisconnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS - 1);
                helper.assertTrue(LogoutService.isCountingDown(player), "still counting at tick 59");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "still connected");
                tickMany(player, 1);
                helper.assertFalse(LogoutService.isCountingDown(player), "the countdown is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "the body is back");
                helper.assertTrue(CapsuleService.state(player).lastSave().isPresent(), "and the spot is saved");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void askingTwiceDoesNotRestartTheCount(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                tickMany(player, 30);
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.ALREADY_RUNNING, "the second call only warns");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS - 31);
                helper.assertTrue(LogoutService.isCountingDown(player), "59 ticks from the first call: still counting");
                tickMany(player, 1);
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "60 ticks from the first call: done");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void damageTakenDuringTheCountdownCancelsIt(GameTestHelper helper) {
        cancelledBy(helper, "damage", LogoutTests::damage);
        helper.succeed();
    }

    private static void moveAndTick(ServerPlayer player, double dx, double dy, double dz) {
        player.setPos(player.getX() + dx, player.getY() + dy, player.getZ() + dz);
        LogoutService.tick(player);
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void movingDuringTheCountdownCancelsIt(GameTestHelper helper) {
        cancelledBy(helper, "move X", player -> moveAndTick(player, 0.6, 0, 0));
        cancelledBy(helper, "move Y", player -> moveAndTick(player, 0, -0.6, 0));
        cancelledBy(helper, "move +Y", player -> moveAndTick(player, 0, 0.6, 0));
        cancelledBy(helper, "move +Z", player -> moveAndTick(player, 0, 0, 0.6));
        cancelledBy(helper, "move -Z", player -> moveAndTick(player, 0, 0, -0.6));
        cancelledBy(helper, "diagonal XY", player -> moveAndTick(player, 0.4, 0.4, 0));
        cancelledBy(helper, "diagonal XYZ", player -> moveAndTick(player, 0.3, 0.3, 0.3));
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void creepingAwayIsMeasuredFromTheStartInLogout(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                Vec3 start = player.position();
                player.setPos(start.add(0.3, 0, 0));
                LogoutService.tick(player);
                helper.assertTrue(LogoutService.isCountingDown(player), "0.3 from the start: still counting");
                player.setPos(start.add(0.6, 0, 0));
                LogoutService.tick(player);
                helper.assertFalse(LogoutService.isCountingDown(player), "0.6 from the start (0.3 more): cancelled");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "nobody was disconnected");
                helper.assertTrue(CapsuleService.state(player).lastSave().isEmpty(), "and nothing was saved");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aSmallDiagonalShiftDoesNotCancelTheCountdown(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                player.setPos(player.getX() + 0.3, player.getY(), player.getZ() + 0.3);
                tickMany(player, LogoutRules.COUNTDOWN_TICKS - 1);
                helper.assertTrue(LogoutService.isCountingDown(player), "still counting at tick 59");
                tickMany(player, 1);
                helper.assertFalse(LogoutService.isCountingDown(player), "the countdown is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "the body is back");
                helper.assertTrue(CapsuleService.state(player).lastSave().isPresent(), "and the spot is saved");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void turningAndSmallShiftsDoNotCancelTheCountdown(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                player.setYRot(123f);
                player.setXRot(-40f);
                player.setYHeadRot(77f);
                player.setPos(player.getX() + 0.4, player.getY(), player.getZ());
                tickMany(player, LogoutRules.COUNTDOWN_TICKS - 1);
                helper.assertTrue(LogoutService.isCountingDown(player), "still counting at tick 59");
                tickMany(player, 1);
                helper.assertFalse(LogoutService.isCountingDown(player), "the countdown is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "the body is back");
                helper.assertTrue(CapsuleService.state(player).lastSave().isPresent(), "and the spot is saved");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void movingOnTheLastTickCancelsTheCountdown(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS - 1);
                helper.assertTrue(LogoutService.isCountingDown(player), "one tick before the end");
                moveAndTick(player, 0.6, 0, 0);
                helper.assertFalse(LogoutService.isCountingDown(player), "moving on the last tick cancels");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "nobody was disconnected");
                helper.assertTrue(CapsuleService.state(player).lastSave().isEmpty(), "and nothing was saved");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void startingAgainAfterMovingCountsFromTheNewSpot(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                moveAndTick(player, 0.6, 0, 0);
                helper.assertFalse(LogoutService.isCountingDown(player), "moving cancels");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "it can start again");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS - 1);
                helper.assertTrue(LogoutService.isCountingDown(player), "still counting from the new spot at tick 59");
                tickMany(player, 1);
                helper.assertFalse(LogoutService.isCountingDown(player), "the countdown is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and the body is back");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void deathDuringTheCountdownCancelsIt(GameTestHelper helper) {
        cancelledBy(helper, "death", false, player -> NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic())));
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void respawningDuringTheCountdownCancelsIt(GameTestHelper helper) {
        cancelledBy(helper, "respawn", player -> NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(player, false)));
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void changingDimensionDuringTheCountdownCancelsIt(GameTestHelper helper) {
        cancelledBy(helper, "dimension", player ->
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(player, Level.OVERWORLD, Level.NETHER)));
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void forgettingThePlayerCancelsTheCountdown(GameTestHelper helper) {
        cancelledBy(helper, "forget", LogoutService::forget);
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void forgettingEveryoneCancelsEveryCountdown(GameTestHelper helper) {
        ServerPlayer first = TestSupport.fakePlayer(helper);
        ServerPlayer second = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(LogoutService.start(first) == LogoutService.Start.STARTED, "first starts");
                helper.assertTrue(LogoutService.start(second) == LogoutService.Start.STARTED, "second starts");
                LogoutService.forgetAll();
                helper.assertFalse(LogoutService.isCountingDown(first) || LogoutService.isCountingDown(second), "nobody counts any more");
                tickMany(first, LogoutRules.COUNTDOWN_TICKS + 5);
                tickMany(second, LogoutRules.COUNTDOWN_TICKS + 5);
                helper.assertTrue(CapsuleService.state(first).lastSave().isEmpty() && CapsuleService.state(second).lastSave().isEmpty(),
                        "and nobody was disconnected");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void damageDealtDuringTheCountdownDoesNotCancelIt(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                LivingEntity zombie = EntityType.ZOMBIE.create(helper.getLevel());
                NeoForge.EVENT_BUS.post(new LivingDamageEvent.Post(zombie, new DamageContainer(player.damageSources().playerAttack(player), 1f)));
                helper.assertTrue(LogoutService.isCountingDown(player), "hitting a mob does not cancel it");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aDeadPlayersCountdownIsCancelledByTheTick(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                player.setHealth(0.0f);
                try {
                    LogoutService.tick(player);
                } finally {
                    player.setHealth(player.getMaxHealth());
                }
                helper.assertFalse(LogoutService.isCountingDown(player), "the tick cancels it");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aTickOutsideSatisfyCancelsTheCountdownWithoutDisconnecting(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                helper.assertTrue(CapsuleService.connect(player, capsule(helper)), "connects");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                Satisfy.standIn(null); // the player is now in the real world as far as the mod can tell
                LogoutService.tick(player);
                helper.assertFalse(LogoutService.isCountingDown(player), "the tick cancels it");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS + 5);
                helper.assertTrue(CapsuleService.state(player).lastSave().isEmpty(), "nothing was saved");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "and nobody was disconnected");
            } finally {
                LogoutService.forgetAll();
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void zeroDamageIsNotCombatAndDoesNotCancel(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                TestSupport.hurt(player, 0f);
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "no combat was marked: allowed");
                TestSupport.hurt(player, 0f);
                helper.assertTrue(LogoutService.isCountingDown(player), "and zero damage does not cancel the count");
                tickMany(player, 5);
                helper.assertTrue(LogoutService.isCountingDown(player), "still counting");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }

    /** Inside Satisfy without a capsule body (no exit point): the countdown still ends in a disconnection, to the respawn. */
    @GameTest(template = TestSupport.EMPTY)
    public static void theCountdownWithoutACapsuleEndsInTheRespawn(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleTests.inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                BlockPos where = player.blockPosition();
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "no capsule connected");
                helper.assertTrue(LogoutService.start(player) == LogoutService.Start.STARTED, "starts");
                tickMany(player, LogoutRules.COUNTDOWN_TICKS);
                helper.assertFalse(LogoutService.isCountingDown(player), "the countdown is over");
                helper.assertTrue(CapsuleService.state(player).lastSave().equals(java.util.Optional.of(where)), "the spot is saved");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and there is still no body");
            } finally {
                LogoutService.forgetAll();
            }
        });
        helper.succeed();
    }
}
