package com.orijua.overgearedmod.gametest;

import com.orijua.overgearedmod.Overgearedmod;
import com.orijua.overgearedmod.core.config.Balance;
import com.orijua.overgearedmod.core.registry.ModAttachments;
import com.orijua.overgearedmod.core.registry.ModBlocks;
import com.orijua.overgearedmod.core.registry.ModItems;
import com.orijua.overgearedmod.feature.capsule.CapsuleRules;
import com.orijua.overgearedmod.feature.capsule.CapsuleService;
import com.orijua.overgearedmod.feature.capsule.CapsuleState;
import com.orijua.overgearedmod.feature.capsule.LevelProbe;
import com.orijua.overgearedmod.feature.capsule.SafeSpot;
import com.orijua.overgearedmod.feature.capsule.StarterCapsule;
import com.orijua.overgearedmod.feature.capsule.WorldSeparation;
import com.orijua.overgearedmod.feature.pagma.PagmaBook;
import com.orijua.overgearedmod.feature.progression.PlayerStats;
import com.orijua.overgearedmod.feature.progression.ProgressionService;
import com.orijua.overgearedmod.feature.world.Satisfy;
import com.orijua.overgearedmod.feature.world.WorldSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The VR capsule: lying down, the "Connecting…" wait and its cancellations, where a connection lands and where a
 * disconnection leaves you. The test server has no Satisfy dimension, so its overworld plays it ({@link Satisfy#standIn});
 * a fake player ignores teleport packets, so what moves is checked through the saved state, not the player's position,
 * and a real dimension crossing cannot be tried with one (its connection has no channel): that is left to a manual test.
 * The tests that clear the global connection tables ({@code forgetAllConnections}) are only safe because their bodies
 * are synchronous: no other test can run in the middle of one.
 */
@GameTestHolder(Overgearedmod.MODID)
@PrefixGameTestTemplate(false)
public final class CapsuleTests {
    private CapsuleTests() {
    }

    /** Runs {@code body} with the test level playing Satisfy and a saved Bairan landing, then puts everything back. */
    static void inSatisfy(GameTestHelper helper, BlockPos landing, Runnable body) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        Optional<BlockPos> landingBefore = WorldSites.landingFor(seed);
        try {
            Satisfy.standIn(level);
            WorldSites.rememberLanding(seed, landing);
            body.run();
        } finally {
            Satisfy.restoreStandIn();
            landingBefore.ifPresentOrElse(pos -> WorldSites.rememberLanding(seed, pos), () -> WorldSites.forgetLanding(seed));
        }
    }

    /** Runs {@code body} with no level playing Satisfy (whatever another test left), and puts the test default back (the overworld plays Satisfy) afterwards. */
    static void outsideSatisfy(GameTestHelper helper, Runnable body) {
        try {
            Satisfy.standIn(null);
            helper.assertFalse(Satisfy.is(helper.getLevel()), "the test level is the real world here");
            body.run();
        } finally {
            Satisfy.restoreStandIn();
        }
    }

    private static GlobalPos at(GameTestHelper helper, BlockPos relative) {
        return GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(relative));
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void firstConnectionLandsInBairanAndLeavesTheBodyInTheCapsule(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        BlockPos bairan = helper.absolutePos(new BlockPos(1, 1, 1));
        inSatisfy(helper, bairan, () -> {
            helper.assertTrue(CapsuleService.state(player).equals(CapsuleState.EMPTY), "a new player has no capsule state");
            CapsuleRules.Spawn spawn = CapsuleService.spawnFor(player, helper.getLevel()).orElseThrow();
            helper.assertTrue(spawn.source() == CapsuleRules.Source.BAIRAN && spawn.pos().equals(bairan), "the first time: Bairan's plaza");

            GlobalPos capsule = at(helper, new BlockPos(0, 1, 0));
            GlobalPos lyingAt = GlobalPos.of(helper.getLevel().dimension(), player.blockPosition());
            helper.assertTrue(CapsuleService.connect(player, capsule), "it connects");
            CapsuleState state = CapsuleService.state(player);
            helper.assertTrue(state.capsule().equals(Optional.of(capsule)), "the body stays in the capsule");
            helper.assertTrue(state.exit().equals(Optional.of(lyingAt)), "and the spot to step out to is where they lay down");
            helper.assertTrue(state.lastSave().isEmpty(), "nothing saved yet");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void starterCapsuleIsGivenOnlyOnce(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.assertTrue(StarterCapsule.giveIfFirstTime(player), "the first time it is given");
        helper.assertTrue(player.getInventory().countItem(ModItems.CAPSULE.get()) == 1, "one capsule in the inventory");
        helper.assertFalse(StarterCapsule.giveIfFirstTime(player), "never again");
        helper.assertTrue(player.getInventory().countItem(ModItems.CAPSULE.get()) == 1, "still one capsule");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void starterCapsuleFlagSurvivesDeath(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        StarterCapsule.giveIfFirstTime(player);
        CapsuleService.died(player);
        helper.assertTrue(player.getData(ModAttachments.STARTER_CAPSULE_GIVEN.get()), "dying does not clear the flag");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void disconnectingSavesTheSpotAndTheNextConnectionStartsThere(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        BlockPos bairan = helper.absolutePos(new BlockPos(1, 1, 1));
        inSatisfy(helper, bairan, () -> {
            GlobalPos capsule = at(helper, new BlockPos(0, 1, 0));
            BlockPos lyingAt = player.blockPosition();
            helper.assertTrue(CapsuleService.connect(player, capsule), "it connects");
            BlockPos exit = CapsuleService.state(player).exit().orElseThrow().pos();
            helper.assertTrue(exit.equals(lyingAt), "they will step out where they had lain down");

            BlockPos wandered = helper.absolutePos(new BlockPos(2, 1, 2));
            player.setPos(wandered.getX() + 0.5, wandered.getY(), wandered.getZ() + 0.5);
            helper.assertTrue(CapsuleService.disconnect(player, CapsuleService.Reason.MANUAL), "in Satisfy: it disconnects");

            CapsuleState state = CapsuleService.state(player);
            helper.assertTrue(state.capsule().isEmpty() && state.exit().isEmpty(), "the body is back, no capsule in use");
            helper.assertTrue(state.lastSave().equals(Optional.of(wandered)), "the spot where they left is saved");

            CapsuleRules.Spawn next = CapsuleService.spawnFor(player, helper.getLevel()).orElseThrow();
            helper.assertTrue(next.source() == CapsuleRules.Source.LAST_SAVE && next.pos().equals(wandered),
                    "the next time they appear where they left, not in Bairan");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void disconnectingWhenNotInSatisfyDoesNothing(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        outsideSatisfy(helper, () -> {
            helper.assertFalse(CapsuleService.disconnect(player, CapsuleService.Reason.DEBUG), "the real world: nothing to disconnect from");
            helper.assertTrue(CapsuleService.state(player).equals(CapsuleState.EMPTY), "and nothing is saved");
        });
        helper.succeed();
    }

    /** Without an exit point (they came in some other way) the player goes to the respawn point and the spot is still saved. */
    @GameTest(template = TestSupport.EMPTY)
    public static void disconnectingWithoutAnExitPointFallsBackToTheRespawn(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            helper.assertTrue(CapsuleService.state(player).equals(CapsuleState.EMPTY), "no capsule, no exit point");
            BlockPos where = player.blockPosition();
            helper.assertTrue(CapsuleService.disconnect(player, CapsuleService.Reason.DEBUG), "in Satisfy: it disconnects");
            helper.assertTrue(CapsuleService.state(player).lastSave().equals(Optional.of(where)), "the spot is saved");
            helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and there is no body");
        });
        helper.succeed();
    }

    /** Own design: the Nether and the End are the real world too, but the capsule only connects from the overworld. */
    @GameTest(template = TestSupport.EMPTY)
    public static void theCapsuleOnlyConnectsFromTheOverworld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the test server has a Nether");
        ServerPlayer inNether = TestSupport.fakePlayerIn(nether);
        BlockPos spot = inNether.blockPosition();
        outsideSatisfy(helper, () -> {
            helper.assertTrue(CapsuleService.begin(inNether, spot) == CapsuleService.Begin.NOT_OVERWORLD, "lying down in the Nether does nothing");
            helper.assertFalse(CapsuleService.isConnecting(inNether), "and no connection starts");
            ServerLevel end = server.getLevel(Level.END);
            if (end != null) {
                ServerPlayer inEnd = TestSupport.fakePlayerIn(end);
                helper.assertTrue(CapsuleService.begin(inEnd, inEnd.blockPosition()) == CapsuleService.Begin.NOT_OVERWORLD, "nor in the End");
                helper.assertFalse(CapsuleService.isConnecting(inEnd), "no connection there either");
            }
            ServerPlayer inOverworld = TestSupport.fakePlayer(helper);
            helper.assertTrue(CapsuleService.begin(inOverworld, helper.absolutePos(new BlockPos(0, 1, 0))) != CapsuleService.Begin.NOT_OVERWORLD,
                    "the overworld is still allowed");
            CapsuleService.cancel(inOverworld, false);
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void withoutSatisfyOrLandingThereIsNowhereToConnect(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        outsideSatisfy(helper, () -> {
            helper.assertTrue(Satisfy.of(helper.getLevel().getServer()).isEmpty(), "an old world has no Satisfy dimension");
            helper.assertTrue(CapsuleService.begin(player, helper.absolutePos(new BlockPos(0, 1, 0))) == CapsuleService.Begin.NO_SATISFY,
                    "so lying down has nowhere to go");
        });

        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        Optional<BlockPos> landingBefore = WorldSites.landingFor(seed);
        try {
            Satisfy.standIn(level);
            WorldSites.forgetLanding(seed);
            helper.assertTrue(CapsuleService.begin(player, helper.absolutePos(new BlockPos(0, 1, 0))) == CapsuleService.Begin.IN_SATISFY,
                    "already in Satisfy: lying down in a capsule does nothing");
            helper.assertTrue(CapsuleService.spawnFor(player, level).isEmpty(), "no saved spot and no Bairan: nowhere to appear");
            helper.assertFalse(CapsuleService.connect(player, at(helper, new BlockPos(0, 1, 0))), "so it does not connect");
            helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and the body is not left in the capsule");
        } finally {
            Satisfy.restoreStandIn();
            landingBefore.ifPresent(pos -> WorldSites.rememberLanding(seed, pos));
        }
        helper.succeed();
    }

    /** The wait takes {@link CapsuleRules#CONNECT_TICKS}, and only then does the player connect. */
    @GameTest(template = TestSupport.EMPTY)
    public static void connectingTakesThreeSecondsThenConnects(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        BlockPos bairan = helper.absolutePos(new BlockPos(1, 1, 1));
        inSatisfy(helper, bairan, () -> {
            CapsuleService.start(player, at(helper, new BlockPos(0, 1, 1)));
            helper.assertTrue(CapsuleService.isConnecting(player), "lying down starts the wait");
            tickMany(player, CapsuleRules.CONNECT_TICKS - 1);
            helper.assertTrue(CapsuleService.isConnecting(player), "still waiting one tick before the end");
            helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "not connected yet");
            CapsuleService.tick(player);
            helper.assertFalse(CapsuleService.isConnecting(player), "the wait is over");
            helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "and the player is connected");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void losingTheCapsuleOrBeingOutOfReachCancelsTheConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        BlockPos capsule = new BlockPos(0, 1, 1);
        helper.setBlock(capsule, ModBlocks.CAPSULE.get());
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            player.setPos(helper.absoluteVec(new Vec3(0.5, 1, 0.5)));
            CapsuleService.start(player, at(helper, capsule));
            helper.setBlock(capsule, Blocks.AIR);
            CapsuleService.tick(player);
            helper.assertFalse(CapsuleService.isConnecting(player), "the capsule gone cancels");
            helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and nothing connected");

            // Standing still but already out of reach (a capsule farther than MAX_DISTANCE): cancelled too.
            BlockPos far = new BlockPos(0, 1, 6);
            helper.setBlock(far, ModBlocks.CAPSULE.get());
            player.setPos(helper.absoluteVec(new Vec3(0.5, 1, 0.5)));
            helper.assertFalse(CapsuleRules.inReach(player.distanceToSqr(helper.absolutePos(far).getCenter())),
                    "the capsule is out of reach");
            CapsuleService.start(player, at(helper, far));
            CapsuleService.tick(player);
            helper.assertFalse(CapsuleService.isConnecting(player), "out of reach without moving cancels");
            helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and nothing connected");
        });
        helper.succeed();
    }

    private static final Vec3 SPOT = new Vec3(0.5, 1, 0.5);

    /** The wait starts with the player at {@link #SPOT} beside a capsule at (0, 1, 1). */
    private static GlobalPos startWaiting(GameTestHelper helper, ServerPlayer player) {
        BlockPos capsule = new BlockPos(0, 1, 1);
        helper.setBlock(capsule, ModBlocks.CAPSULE.get());
        player.setPos(helper.absoluteVec(SPOT));
        GlobalPos global = at(helper, capsule);
        CapsuleService.start(player, global);
        return global;
    }

    private static void shiftTo(GameTestHelper helper, ServerPlayer player, double dx, double dy, double dz) {
        player.setPos(helper.absoluteVec(SPOT.add(dx, dy, dz)));
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void movingDuringTheWaitCancelsTheConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            double[][] shifts = {{0.6, 0, 0}, {0, 0.6, 0}, {0, -0.6, 0}, {0, 0, 0.6}, {0.4, 0.4, 0}, {0.3, 0.3, 0.3}};
            try {
                for (double[] shift : shifts) {
                    startWaiting(helper, player);
                    shiftTo(helper, player, shift[0], shift[1], shift[2]);
                    CapsuleService.tick(player);
                    helper.assertFalse(CapsuleService.isConnecting(player), "moving cancels (" + shift[0] + ", " + shift[1] + ", " + shift[2] + ")");
                    helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and nothing connected");
                    tickMany(player, CapsuleRules.CONNECT_TICKS + 5);
                    helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "it never connects afterwards");
                }
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void turningAndSmallShiftsDoNotCancelTheConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                startWaiting(helper, player);
                player.setYRot(123f);
                player.setXRot(-40f);
                player.setYHeadRot(77f);
                shiftTo(helper, player, 0.4, 0, 0);
                tickMany(player, CapsuleRules.CONNECT_TICKS - 1);
                helper.assertTrue(CapsuleService.isConnecting(player), "turning and a small shift keep waiting");
                CapsuleService.tick(player);
                helper.assertFalse(CapsuleService.isConnecting(player), "the wait is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "and it connects");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aSmallDiagonalShiftDoesNotCancelTheConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                startWaiting(helper, player);
                shiftTo(helper, player, 0.3, 0.3, 0);
                tickMany(player, CapsuleRules.CONNECT_TICKS - 1);
                helper.assertTrue(CapsuleService.isConnecting(player), "a small diagonal shift keeps waiting");
                CapsuleService.tick(player);
                helper.assertFalse(CapsuleService.isConnecting(player), "the wait is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "and it connects");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void creepingAwayIsMeasuredFromTheStart(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                startWaiting(helper, player);
                shiftTo(helper, player, 0.3, 0, 0);
                CapsuleService.tick(player);
                helper.assertTrue(CapsuleService.isConnecting(player), "0.3 from the start: still waiting");
                shiftTo(helper, player, 0.6, 0, 0);
                CapsuleService.tick(player);
                helper.assertFalse(CapsuleService.isConnecting(player), "0.6 from the start (0.3 more): cancelled");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and nothing connected");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void movingOnTheLastTickCancelsTheConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                startWaiting(helper, player);
                tickMany(player, CapsuleRules.CONNECT_TICKS - 1);
                helper.assertTrue(CapsuleService.isConnecting(player), "one tick before the end");
                shiftTo(helper, player, 0.6, 0, 0);
                CapsuleService.tick(player);
                helper.assertFalse(CapsuleService.isConnecting(player), "moving on the last tick cancels");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and it does not connect");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void lyingDownAgainAfterMovingWorks(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                GlobalPos capsule = startWaiting(helper, player);
                shiftTo(helper, player, 0.6, 0, 0);
                CapsuleService.tick(player);
                helper.assertFalse(CapsuleService.isConnecting(player), "moving cancels");
                CapsuleService.start(player, capsule);
                tickMany(player, CapsuleRules.CONNECT_TICKS);
                helper.assertFalse(CapsuleService.isConnecting(player), "the new wait is over");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "and it connects from the new spot");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void cancellingForgetsTheConnectionAndCanStartAgain(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            GlobalPos capsule = at(helper, new BlockPos(0, 1, 1));
            CapsuleService.start(player, capsule);
            CapsuleService.cancel(player, false);
            helper.assertFalse(CapsuleService.isConnecting(player), "damage or logging out cancels");
            tickMany(player, CapsuleRules.CONNECT_TICKS + 5);
            helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "a cancelled connection never completes");
            CapsuleService.start(player, capsule);
            helper.assertTrue(CapsuleService.isConnecting(player), "and lying down again works");
            CapsuleService.cancel(player, false);
        });
        helper.succeed();
    }

    // ---- Hardening: stale connections, double clicks, fall damage, death and login ----

    /** A level other than the test's, to play Satisfy while the player stays "in the real world". Null if there is none. */
    static ServerLevel otherLevel(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether != null && nether != helper.getLevel()) {
            return nether;
        }
        for (ServerLevel candidate : helper.getLevel().getServer().getAllLevels()) {
            if (candidate != helper.getLevel()) {
                return candidate;
            }
        }
        return null;
    }

    static void tickMany(ServerPlayer player, int ticks) {
        TestSupport.tickMany(player, ticks, CapsuleService::tick);
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void takingDamageCancelsTheWait(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                CapsuleService.start(player, at(helper, new BlockPos(0, 1, 1)));
                TestSupport.hurt(player, 1f);
                helper.assertFalse(CapsuleService.isConnecting(player), "damage wakes you up");
                tickMany(player, CapsuleRules.CONNECT_TICKS + 5);
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and nothing connects");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingWhileConnectingDoesNotConnectOnRespawn(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                GlobalPos capsule = at(helper, new BlockPos(0, 1, 1));
                CapsuleService.start(player, capsule);
                NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic()));
                helper.assertFalse(CapsuleService.isConnecting(player), "dying ends the wait");
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(player, false));
                tickMany(player, CapsuleRules.CONNECT_TICKS + 5);
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "the new body does not connect by itself");

                CapsuleService.start(player, capsule);
                player.setHealth(0.0f);
                try {
                    CapsuleService.tick(player);
                } finally {
                    player.setHealth(player.getMaxHealth());
                }
                helper.assertFalse(CapsuleService.isConnecting(player), "a dead player's wait is cancelled by the tick");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void lyingDownTwiceStartsOnce(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        ServerLevel other = otherLevel(helper);
        if (other == null) {
            helper.fail("BLOCKED: no second level to play Satisfy");
            return;
        }
        BlockPos capsule = new BlockPos(0, 1, 1);
        helper.setBlock(capsule, ModBlocks.CAPSULE.get());
        // Another level plays Satisfy while the clicks happen; the test level takes over for the arrival (a fake player cannot cross dimensions).
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                Satisfy.standIn(other);
                helper.assertTrue(CapsuleService.begin(player, helper.absolutePos(capsule)) == CapsuleService.Begin.STARTED, "the first click starts");
                tickMany(player, 10);
                helper.assertTrue(CapsuleService.begin(player, helper.absolutePos(capsule)) == CapsuleService.Begin.ALREADY_CONNECTING, "the second does not");
                helper.assertTrue(CapsuleService.isConnecting(player), "still connecting");
                Satisfy.standIn(helper.getLevel());
                tickMany(player, CapsuleRules.CONNECT_TICKS - 1 - 10);
                helper.assertTrue(CapsuleService.isConnecting(player) && CapsuleService.state(player).capsule().isEmpty(),
                        "one tick before CONNECT_TICKS counted from the first click: still waiting");
                tickMany(player, 1);
                helper.assertFalse(CapsuleService.isConnecting(player), "the count was not restarted: done CONNECT_TICKS after the first click");
                helper.assertTrue(CapsuleService.state(player).capsule().isPresent(), "and the player is connected");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    /** A respawn alone (no death before it) also drops a pending connection: the new entity has the same UUID. */
    @GameTest(template = TestSupport.EMPTY)
    public static void respawnAloneDropsAPendingConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                CapsuleService.start(player, at(helper, new BlockPos(0, 1, 1)));
                helper.assertTrue(CapsuleService.isConnecting(player), "the wait is running");
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(player, false));
                helper.assertFalse(CapsuleService.isConnecting(player), "the respawn drops it");
                tickMany(player, CapsuleRules.CONNECT_TICKS + 5);
                helper.assertFalse(CapsuleService.isConnecting(player), "and it does not come back");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "nothing connects");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    /** A capsule in another dimension than the player's cannot be waited on. */
    @GameTest(template = TestSupport.EMPTY)
    public static void aCapsuleInAnotherDimensionCancelsTheWait(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        outsideSatisfy(helper, () -> {
            try {
                GlobalPos elsewhere = GlobalPos.of(helper.getLevel().dimension() == Level.END ? Level.NETHER : Level.END, helper.absolutePos(new BlockPos(0, 1, 1)));
                CapsuleService.start(player, elsewhere);
                helper.assertTrue(CapsuleService.isConnecting(player), "the wait starts");
                CapsuleService.tick(player);
                helper.assertFalse(CapsuleService.isConnecting(player), "the capsule is in another dimension: cancelled");
                helper.assertTrue(CapsuleService.state(player).capsule().isEmpty(), "and nothing connected");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void changingDimensionCancelsThePendingConnection(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        outsideSatisfy(helper, () -> {
            try {
                CapsuleService.start(player, at(helper, new BlockPos(0, 1, 1)));
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(player, Level.OVERWORLD, Level.NETHER));
                helper.assertFalse(CapsuleService.isConnecting(player), "changing dimension forgets the wait");
            } finally {
                CapsuleService.cancel(player, false);
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void lyingDownWhileConnectedIsRefused(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        ServerLevel other = otherLevel(helper);
        if (other == null) {
            helper.fail("BLOCKED: no second level to play Satisfy");
            return;
        }
        try {
            Satisfy.standIn(other);
            player.setData(ModAttachments.CAPSULE.get(), CapsuleState.EMPTY.connected(at(helper, new BlockPos(0, 1, 0)), at(helper, new BlockPos(1, 1, 0))));
            helper.assertTrue(CapsuleService.begin(player, helper.absolutePos(new BlockPos(0, 1, 1))) == CapsuleService.Begin.ALREADY_CONNECTED,
                    "already connected: refused");
            helper.assertFalse(CapsuleService.isConnecting(player), "and no wait starts");
        } finally {
            Satisfy.restoreStandIn();
            CapsuleService.cancel(player, false);
        }
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void connectingTwiceKeepsTheFirstExit(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            GlobalPos capsule = at(helper, new BlockPos(0, 1, 0));
            helper.assertTrue(CapsuleService.connect(player, capsule), "it connects");
            CapsuleState first = CapsuleService.state(player);
            BlockPos moved = helper.absolutePos(new BlockPos(3, 1, 3));
            player.setPos(moved.getX() + 0.5, moved.getY(), moved.getZ() + 0.5);
            helper.assertFalse(CapsuleService.connect(player, at(helper, new BlockPos(2, 1, 2))), "a second connection is refused");
            helper.assertTrue(CapsuleService.state(player).equals(first), "the first exit and capsule stay");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void arrivingResetsTheFall(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            player.fallDistance = 10f;
            helper.assertTrue(CapsuleService.connect(player, at(helper, new BlockPos(0, 1, 0))), "it connects");
            helper.assertTrue(player.fallDistance == 0f, "no fall damage on arrival");
            player.fallDistance = 10f;
            helper.assertTrue(CapsuleService.disconnect(player, CapsuleService.Reason.MANUAL), "it disconnects");
            helper.assertTrue(player.fallDistance == 0f, "nor on leaving");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingInSatisfyFreesTheBodyAndKeepsTheLastSave(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        GlobalPos capsule = at(helper, new BlockPos(0, 1, 0));
        BlockPos saved = helper.absolutePos(new BlockPos(2, 1, 2));
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            helper.assertTrue(CapsuleService.connect(player, capsule), "it connects");
            player.setPos(saved.getX() + 0.5, saved.getY(), saved.getZ() + 0.5);
            helper.assertTrue(CapsuleService.disconnect(player, CapsuleService.Reason.MANUAL), "it disconnects");
            helper.assertTrue(CapsuleService.connect(player, capsule), "and connects again");
            BlockPos deathSpot = helper.absolutePos(new BlockPos(4, 1, 4));
            player.setPos(deathSpot.getX() + 0.5, deathSpot.getY(), deathSpot.getZ() + 0.5);

            NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic()));
            CapsuleState state = CapsuleService.state(player);
            helper.assertTrue(state.capsule().isEmpty() && state.exit().isEmpty(), "the body leaves the capsule");
            helper.assertTrue(state.lastSave().equals(Optional.of(saved)), "the last save is kept, not the place of death");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingOutsideSatisfyLeavesTheStateAlone(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        outsideSatisfy(helper, () -> {
            CapsuleState connected = CapsuleState.EMPTY.connected(at(helper, new BlockPos(0, 1, 0)), at(helper, new BlockPos(1, 1, 0)));
            player.setData(ModAttachments.CAPSULE.get(), connected);
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic()));
            helper.assertTrue(CapsuleService.state(player).equals(connected), "dying in the real world does not touch the state");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aDeathTheLegendTitlePreventsDoesNotFreeTheBody(GameTestHelper helper) {
        GlobalPos capsule = at(helper, new BlockPos(0, 1, 0));
        ServerPlayer legend = TestSupport.fakePlayer(helper);
        legend.getInventory().add(new ItemStack(ModItems.PAGMA_RARE_BOOK.get()));
        PagmaBook.read(legend);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            helper.assertTrue(CapsuleService.connect(legend, capsule), "the legend connects");
            CapsuleState before = CapsuleService.state(legend);
            LivingDeathEvent death = NeoForge.EVENT_BUS.post(new LivingDeathEvent(legend, legend.damageSources().generic()));
            helper.assertTrue(death.isCanceled(), "the legend is saved");
            helper.assertTrue(CapsuleService.state(legend).equals(before), "so the body stays in the capsule");
        });
        helper.succeed();
    }

    // ---- Separate worlds: the real world and Satisfy ----

    private static boolean travelCancelled(Entity entity, ResourceKey<Level> to) {
        return NeoForge.EVENT_BUS.post(new EntityTravelToDimensionEvent(entity, to)).isCanceled();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aPlayerCannotCrossBetweenTheRealWorldAndSatisfyOnItsOwn(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        outsideSatisfy(helper, () -> {
            helper.assertTrue(travelCancelled(player, Satisfy.LEVEL), "real world to Satisfy: cancelled");
            helper.assertFalse(travelCancelled(player, Level.NETHER), "real world to the Nether: not touched");
        });
        try {
            Satisfy.standIn(helper.getLevel());
            helper.assertTrue(travelCancelled(player, Level.NETHER), "Satisfy to the real world: cancelled");
            helper.assertFalse(travelCancelled(player, Satisfy.LEVEL), "Satisfy to Satisfy: not touched");
        } finally {
            Satisfy.restoreStandIn();
        }
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void otherEntitiesCannotCrossEitherAndTheCapsuleFlagLetsThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Entity zombie = EntityType.ZOMBIE.create(level);
        Entity item = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.STICK));
        try {
            Satisfy.standIn(level);
            helper.assertTrue(travelCancelled(zombie, Level.NETHER), "a zombie from Satisfy to the Nether: cancelled");
            helper.assertTrue(travelCancelled(item, Level.NETHER), "an item too");
            WorldSeparation.crossing(() -> {
                helper.assertFalse(travelCancelled(zombie, Level.NETHER), "inside crossing(...): allowed");
                helper.assertFalse(travelCancelled(item, Level.NETHER), "also for an item");
            });
            helper.assertTrue(travelCancelled(zombie, Level.NETHER), "and cancelled again afterwards");
        } finally {
            Satisfy.restoreStandIn();
        }
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void theCrossingFlagIsOnlyOnInsideAndAlwaysCleared(GameTestHelper helper) {
        helper.assertFalse(WorldSeparation.crossingAllowed(), "off by default");
        WorldSeparation.crossing(() -> helper.assertTrue(WorldSeparation.crossingAllowed(), "on inside"));
        helper.assertFalse(WorldSeparation.crossingAllowed(), "off after");
        boolean thrown = false;
        try {
            WorldSeparation.crossing(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            thrown = true;
        }
        helper.assertTrue(thrown, "the exception comes out");
        helper.assertFalse(WorldSeparation.crossingAllowed(), "off even if the body throws");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void satisfyIsAgreesForLevelsAndKeys(GameTestHelper helper) {
        try {
            for (ServerLevel candidate : helper.getLevel().getServer().getAllLevels()) {
                Satisfy.standIn(null);
                helper.assertTrue(Satisfy.is(candidate) == Satisfy.is(candidate.dimension()), "without a stand-in: " + candidate.dimension().location());
                Satisfy.standIn(helper.getLevel());
                helper.assertTrue(Satisfy.is(candidate) == Satisfy.is(candidate.dimension()), "with a stand-in: " + candidate.dimension().location());
            }
            helper.assertTrue(Satisfy.is(Satisfy.LEVEL), "the real key is Satisfy");
            helper.assertTrue(Satisfy.is(helper.getLevel().dimension()), "and so is the stand-in's key");
            Satisfy.standIn(null);
            helper.assertFalse(Satisfy.is(helper.getLevel().dimension()), "not without the stand-in");
        } finally {
            Satisfy.restoreStandIn();
        }
        helper.succeed();
    }

    // ---- Deaths only count in Satisfy ----

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingInTheRealWorldCostsNoXpButInSatisfyItDoes(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        Balance.withOverride(Balance.current().withDeathXpLoss(0.5), () -> {
            outsideSatisfy(helper, () -> {
                ProgressionService.set(player, PlayerStats.DEFAULT.withProgress(4, 50, 0));
                NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic()));
                helper.assertTrue(ProgressionService.get(player).xp() == 50, "the real world: XP untouched");
            });
            inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
                ProgressionService.set(player, PlayerStats.DEFAULT.withProgress(4, 50, 0));
                NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().generic()));
                helper.assertTrue(ProgressionService.get(player).xp() == 25, "Satisfy: half of 50 XP lost, now " + ProgressionService.get(player).xp());
            });
        });
        helper.succeed();
    }

    // ---- Free spot (LevelProbe) ----

    /**
     * Lava and fire have no collision, so they are never a floor for that reason alone (there is no separate branch for
     * them in {@link LevelProbe#isSafeFloor}); magma has collision, so it is the one block its own check rejects.
     */
    @GameTest(template = TestSupport.EMPTY)
    public static void theLevelProbeTellsAirStoneAndFluidsApart(GameTestHelper helper) {
        LevelProbe probe = new LevelProbe(helper.getLevel());
        BlockPos at = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(at);
        try {
            helper.setBlock(at, Blocks.AIR);
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.AIR);
            helper.assertTrue(probe.isOpen(pos) && !probe.isSafeFloor(pos), "air: open, not a floor");
            helper.setBlock(at, Blocks.STONE);
            helper.assertTrue(!probe.isOpen(pos) && probe.isSafeFloor(pos), "stone: not open, a floor");
            helper.setBlock(at, Blocks.WATER);
            helper.assertTrue(!probe.isOpen(pos) && !probe.isSafeFloor(pos), "water: neither");
            helper.setBlock(at, Blocks.LAVA);
            helper.assertTrue(!probe.isOpen(pos) && !probe.isSafeFloor(pos), "lava: neither (no collision, and a fluid)");
            helper.setBlock(at, Blocks.MAGMA_BLOCK);
            helper.assertTrue(!probe.isOpen(pos) && !probe.isSafeFloor(pos), "magma has collision but is not a safe floor (its own check)");
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.NETHERRACK); // fire needs something to burn on (the box floor is air)
            helper.setBlock(at, Blocks.FIRE);
            helper.assertTrue(!probe.isOpen(pos) && !probe.isSafeFloor(pos), "fire: neither");
        } finally {
            helper.setBlock(at, Blocks.AIR);
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.AIR);
        }
        helper.succeed();
    }

    /** Blocks with no collision that hurt, trap or move you are not free to stand in. */
    @GameTest(template = TestSupport.EMPTY)
    public static void harmfulBlocksWithoutCollisionAreNotOpen(GameTestHelper helper) {
        LevelProbe probe = new LevelProbe(helper.getLevel());
        BlockPos at = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(at);
        try {
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.NETHERRACK);
            helper.setBlock(at, Blocks.TORCH);
            helper.assertTrue(probe.isOpen(pos), "control: a torch has no collision and is harmless: open");
            for (net.minecraft.world.level.block.Block block : new net.minecraft.world.level.block.Block[]{
                    Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
                    Blocks.NETHER_PORTAL, Blocks.END_PORTAL}) {
                helper.setBlock(new BlockPos(1, 0, 1), block == Blocks.SOUL_FIRE ? Blocks.SOUL_SOIL : Blocks.NETHERRACK);
                helper.setBlock(at, block);
                helper.assertFalse(probe.isOpen(pos), block + " is not open");
                helper.assertFalse(probe.isSafeFloor(pos), block + " is not a floor either");
            }
        } finally {
            helper.setBlock(at, Blocks.AIR);
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.AIR);
        }
        helper.succeed();
    }

    /** A saved spot whose feet are inside stone resolves to a free spot nearby, valid for the probe. */
    @GameTest(template = TestSupport.EMPTY)
    public static void aLastSaveInsideStoneResolvesToAFreeSpot(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        LevelProbe probe = new LevelProbe(helper.getLevel());
        BlockPos[] stone = {new BlockPos(1, 0, 1), new BlockPos(1, 1, 1), new BlockPos(1, 2, 1)};
        BlockPos wanted = helper.absolutePos(new BlockPos(1, 1, 1));
        inSatisfy(helper, helper.absolutePos(new BlockPos(2, 1, 2)), () -> {
            try {
                for (BlockPos block : stone) {
                    helper.setBlock(block, Blocks.STONE);
                }
                player.setData(ModAttachments.CAPSULE.get(), CapsuleState.EMPTY.disconnected(wanted));
                CapsuleRules.Spawn spawn = CapsuleService.spawnFor(player, helper.getLevel()).orElseThrow();
                helper.assertTrue(spawn.source() == CapsuleRules.Source.LAST_SAVE && spawn.pos().equals(wanted), "the last save is the wanted spot");
                helper.assertFalse(probe.isOpen(wanted), "whose feet are inside stone");
                BlockPos free = CapsuleService.freeSpot(helper.getLevel(), spawn.pos(), "test", player);
                helper.assertFalse(free.equals(wanted), "so it is not used as it is: " + free);
                helper.assertTrue(probe.isOpen(free) && probe.isOpen(free.above()) && probe.isSafeFloor(free.below()),
                        "the spot found fits a player: " + free);
            } finally {
                for (BlockPos block : stone) {
                    helper.setBlock(block, Blocks.AIR);
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aValidOriginIsKeptByTheSearch(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.assertTrue(SafeSpot.find(new LevelProbe(helper.getLevel()), origin).equals(Optional.of(origin)),
                "feet and head free over stone: the origin itself");
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.AIR);
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void theCapsuleStateIsSavedWithThePlayer(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        CapsuleState state = CapsuleState.EMPTY.disconnected(helper.absolutePos(new BlockPos(2, 1, 2)))
                .connected(at(helper, new BlockPos(0, 1, 0)), at(helper, new BlockPos(1, 1, 0)));
        player.setData(ModAttachments.CAPSULE.get(), state);
        CompoundTag tag = player.saveWithoutId(new CompoundTag());
        ServerPlayer other = TestSupport.fakePlayer(helper);
        other.load(tag);
        helper.assertTrue(CapsuleService.state(other).equals(state), "the state is read back from the saved player");
        helper.succeed();
    }

    /** Calls {@code reconcile} directly (what the login event does), not the event. */
    @GameTest(template = TestSupport.EMPTY)
    public static void loggingInReconcilesTheState(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        BlockPos saved = helper.absolutePos(new BlockPos(2, 1, 2));
        GlobalPos capsule = at(helper, new BlockPos(0, 1, 0));
        GlobalPos exit = at(helper, new BlockPos(1, 1, 0));

        outsideSatisfy(helper, () -> {
            player.setData(ModAttachments.CAPSULE.get(), CapsuleState.EMPTY.disconnected(saved).connected(capsule, exit));
            CapsuleService.reconcile(player);
            CapsuleState outside = CapsuleService.state(player);
            helper.assertTrue(outside.capsule().isEmpty() && outside.exit().isEmpty() && outside.lastSave().equals(Optional.of(saved)),
                    "connected but outside Satisfy: the body is released, the last save kept");
        });

        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            CapsuleState disconnected = CapsuleState.EMPTY.disconnected(saved);
            player.setData(ModAttachments.CAPSULE.get(), disconnected);
            CapsuleService.reconcile(player);
            helper.assertTrue(CapsuleService.state(player).equals(disconnected), "inside and disconnected: nothing changes");

            CapsuleState connected = disconnected.connected(capsule, exit);
            player.setData(ModAttachments.CAPSULE.get(), connected);
            CapsuleService.reconcile(player);
            helper.assertTrue(CapsuleService.state(player).equals(connected), "inside and connected: nothing changes");
        });
        helper.succeed();
    }

    /** Calls {@code forgetAllConnections} directly (what the server stopping/starting events do), not the events. */
    @GameTest(template = TestSupport.EMPTY)
    public static void stoppingTheServerForgetsEveryConnection(GameTestHelper helper) {
        ServerPlayer first = TestSupport.fakePlayer(helper);
        ServerPlayer second = TestSupport.fakePlayer(helper);
        helper.setBlock(new BlockPos(0, 1, 1), ModBlocks.CAPSULE.get());
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                CapsuleService.start(first, at(helper, new BlockPos(0, 1, 1)));
                CapsuleService.start(second, at(helper, new BlockPos(0, 1, 1)));
                CapsuleService.forgetAllConnections();
                helper.assertFalse(CapsuleService.isConnecting(first) || CapsuleService.isConnecting(second), "nobody is connecting any more");
                tickMany(first, CapsuleRules.CONNECT_TICKS + 5);
                tickMany(second, CapsuleRules.CONNECT_TICKS + 5);
                helper.assertTrue(CapsuleService.state(first).capsule().isEmpty() && CapsuleService.state(second).capsule().isEmpty(),
                        "and nobody connects");
            } finally {
                CapsuleService.forgetAllConnections();
            }
        });
        helper.succeed();
    }

    // ---- Where a disconnection leads ----

    private static ServerLevel levelOtherThanTheOverworld(GameTestHelper helper) {
        for (ServerLevel candidate : helper.getLevel().getServer().getAllLevels()) {
            if (candidate != helper.getLevel().getServer().overworld()) {
                return candidate;
            }
        }
        return null;
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aDisconnectionWithoutAnExitGoesToTheRespawnOrTheSpawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        BlockPos respawn = helper.absolutePos(new BlockPos(2, 1, 2));
        ResourceKey<Level> nowhere = ResourceKey.create(Registries.DIMENSION, Overgearedmod.id("nowhere"));
        outsideSatisfy(helper, () -> {
            CapsuleService.Destination bed = CapsuleService.destination(server, Optional.empty(), Level.OVERWORLD, respawn);
            helper.assertTrue(bed.level() == overworld && bed.pos().equals(respawn), "a bed in the overworld: there");
            CapsuleService.Destination noBed = CapsuleService.destination(server, Optional.empty(), Level.OVERWORLD, null);
            helper.assertTrue(noBed.level() == overworld && noBed.pos().equals(overworld.getSharedSpawnPos()), "no bed: the world's spawn");
            CapsuleService.Destination gone = CapsuleService.destination(server, Optional.empty(), nowhere, respawn);
            helper.assertTrue(gone.level() == overworld && gone.pos().equals(overworld.getSharedSpawnPos()),
                    "a respawn dimension that does not exist: the overworld's spawn, not the bed position");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aDisconnectionWithAnExitGoesBesideTheCapsule(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        BlockPos exitPos = helper.absolutePos(new BlockPos(1, 1, 0));
        BlockPos respawn = helper.absolutePos(new BlockPos(2, 1, 2));
        ResourceKey<Level> nowhere = ResourceKey.create(Registries.DIMENSION, Overgearedmod.id("nowhere"));
        outsideSatisfy(helper, () -> {
            CapsuleService.Destination there = CapsuleService.destination(server,
                    Optional.of(GlobalPos.of(Level.OVERWORLD, exitPos)), Level.OVERWORLD, respawn);
            helper.assertTrue(there.level() == overworld && there.pos().equals(exitPos), "the exit point, not the respawn");
            CapsuleService.Destination missing = CapsuleService.destination(server,
                    Optional.of(GlobalPos.of(nowhere, exitPos)), Level.OVERWORLD, respawn);
            helper.assertTrue(missing.level() == overworld && missing.pos().equals(respawn),
                    "an exit in a dimension that no longer exists: falls back to the respawn");
        });
        helper.succeed();
    }

    /** A bed slept in Satisfy must not send a disconnection from Satisfy to Satisfy. */
    @GameTest(template = TestSupport.EMPTY)
    public static void aRespawnInSatisfyLeadsToTheRealWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel notOverworld = levelOtherThanTheOverworld(helper);
        if (notOverworld == null) {
            helper.fail("BLOCKED: no level besides the overworld to play Satisfy");
            return;
        }
        BlockPos bed = helper.absolutePos(new BlockPos(2, 1, 2));
        try {
            Satisfy.standIn(notOverworld);
            CapsuleService.Destination destination = CapsuleService.destination(server, Optional.empty(), notOverworld.dimension(), bed);
            helper.assertFalse(Satisfy.is(destination.level()), "never Satisfy");
            helper.assertTrue(destination.level() == overworld, "the overworld");
            helper.assertTrue(destination.pos().equals(overworld.getSharedSpawnPos()), "at its spawn, not at the bed of Satisfy");
        } finally {
            Satisfy.restoreStandIn();
        }
        helper.succeed();
    }

    // ---- Beds in Satisfy and trips that do not happen ----

    private static boolean spawnSetCancelled(GameTestHelper helper, ServerPlayer player, ResourceKey<Level> dimension) {
        return NeoForge.EVENT_BUS.post(new PlayerSetSpawnEvent(player, dimension, helper.absolutePos(new BlockPos(1, 1, 1)), false)).isCanceled();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aBedInSatisfyDoesNotSetTheRespawn(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        ResourceKey<Level> real = helper.getLevel().dimension() == Level.OVERWORLD ? Level.NETHER : Level.OVERWORLD;
        try {
            Satisfy.standIn(null);
            helper.assertTrue(spawnSetCancelled(helper, player, Satisfy.LEVEL), "the real Satisfy key: cancelled");
            helper.assertFalse(spawnSetCancelled(helper, player, Level.OVERWORLD), "the overworld: not touched");
            Satisfy.standIn(helper.getLevel());
            helper.assertTrue(spawnSetCancelled(helper, player, helper.getLevel().dimension()), "the stand-in's key: cancelled");
            helper.assertFalse(spawnSetCancelled(helper, player, real), "another real dimension: not touched");
        } finally {
            Satisfy.restoreStandIn();
        }
        helper.succeed();
    }

    /** Another mod cancels the trip (simulated): the player stays in the real world and so must the saved state. */
    @GameTest(template = TestSupport.EMPTY)
    public static void aConnectionWhoseTripIsCancelledIsUndone(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        ServerLevel other = otherLevel(helper);
        if (other == null) {
            helper.fail("BLOCKED: no second level to play Satisfy");
            return;
        }
        Consumer<EntityTravelToDimensionEvent> canceller = event -> event.setCanceled(true);
        inSatisfy(helper, helper.absolutePos(new BlockPos(1, 1, 1)), () -> {
            try {
                Satisfy.standIn(other);
                NeoForge.EVENT_BUS.addListener(canceller);
                CapsuleState before = CapsuleService.state(player);
                helper.assertFalse(CapsuleService.connect(player, at(helper, new BlockPos(0, 1, 0))), "the trip did not happen: not connected");
                helper.assertTrue(CapsuleService.state(player).equals(before), "and the saved state is back as it was");
                helper.assertFalse(CapsuleService.state(player).isConnected(), "not left connected in the real world");
            } finally {
                NeoForge.EVENT_BUS.unregister(canceller);
                Satisfy.standIn(helper.getLevel());
            }
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void nestedCrossingsKeepTheOuterFlagOn(GameTestHelper helper) {
        WorldSeparation.crossing(() -> {
            WorldSeparation.crossing(() -> helper.assertTrue(WorldSeparation.crossingAllowed(), "on in the inner one"));
            helper.assertTrue(WorldSeparation.crossingAllowed(), "the inner one does not turn the outer flag off");
        });
        helper.assertFalse(WorldSeparation.crossingAllowed(), "off after the outer one");
        helper.succeed();
    }
}
