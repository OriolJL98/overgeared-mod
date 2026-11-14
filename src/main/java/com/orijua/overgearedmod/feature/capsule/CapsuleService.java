package com.orijua.overgearedmod.feature.capsule;

import com.orijua.overgearedmod.Overgearedmod;
import com.orijua.overgearedmod.core.registry.ModAttachments;
import com.orijua.overgearedmod.core.registry.ModBlocks;
import com.orijua.overgearedmod.core.config.FatigueBalance;
import com.orijua.overgearedmod.feature.character.Characters;
import com.orijua.overgearedmod.feature.fatigue.FatigueRules;
import com.orijua.overgearedmod.feature.fatigue.FatigueService;
import com.orijua.overgearedmod.feature.character.WorldSide;
import com.orijua.overgearedmod.feature.world.Satisfy;
import com.orijua.overgearedmod.feature.world.WorldSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entering and leaving Satisfy through the capsule. Lying down starts a short "Connecting…" wait (cancelled by taking
 * damage, moving or walking away); when it ends the player appears in Satisfy, where they last disconnected or, the first time,
 * on Bairan's plaza. Disconnecting puts them back beside their capsule. Both trips look for a free spot near the wanted
 * one ({@link SafeSpot}) and are the only way across between the real world and Satisfy ({@link WorldSeparation}).
 * The {@code /satisfy logout} countdown ({@link LogoutService}), fatigue, the sleeping body and death all end up
 * calling {@link #disconnect} with their own {@link Reason}. A connection in progress is forgotten
 * when the player dies, respawns, changes dimension, logs out or the server stops, and on login the saved state is
 * reconciled with where the player really is.
 */
public final class CapsuleService {
    /** The connections in progress, by player. Normally only the server thread touches it; it is concurrent just in case. */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(GlobalPos capsule, int ticksLeft, Vec3 anchor) {
        Pending tick() {
            return new Pending(capsule, ticksLeft - 1, anchor);
        }
    }

    /** Why a player was taken out of Satisfy. */
    public enum Reason { MANUAL, DEBUG, LOGOUT, FATIGUE }

    /** What lying down in a capsule did. */
    public enum Begin { STARTED, ALREADY_CONNECTING, IN_SATISFY, ALREADY_CONNECTED, NOT_OVERWORLD, NO_SATISFY, TOO_TIRED }

    private CapsuleService() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(CapsuleService::onTick);
        gameBus.addListener(CapsuleService::onDamaged);
        gameBus.addListener(CapsuleService::onLoggedOut);
        // LOWEST and not cancelled: a death something else prevented (the legend title) must not free the body.
        gameBus.addListener(EventPriority.LOWEST, CapsuleService::onDeath);
        gameBus.addListener(CapsuleService::onRespawn);
        gameBus.addListener(CapsuleService::onChangedDimension);
        gameBus.addListener(CapsuleService::onLoggedIn);
        gameBus.addListener(CapsuleService::onServerStopping);
        gameBus.addListener(CapsuleService::onServerStarting);
    }

    public static CapsuleState state(ServerPlayer player) {
        return player.getData(ModAttachments.CAPSULE.get());
    }

    /** Lying down in the capsule at {@code capsule}: the connection starts. */
    public static Begin begin(ServerPlayer player, BlockPos capsule) {
        MinecraftServer server = player.server;
        if (Satisfy.is(player.level())) {
            return Begin.IN_SATISFY;
        }
        if (state(player).isConnected()) {
            return Begin.ALREADY_CONNECTED;
        }
        // Own design: the capsule connects only from the overworld; the Nether and the End are the real world too, but
        // nobody lies down there.
        if (player.level().dimension() != Level.OVERWORLD) {
            return Begin.NOT_OVERWORLD;
        }
        if (Satisfy.of(server).isEmpty()) {
            return Begin.NO_SATISFY;
        }
        if (PENDING.containsKey(player.getUUID())) {
            return Begin.ALREADY_CONNECTING;
        }
        // Own design: below the minimum energy the capsule refuses; the real world refills it.
        if (!FatigueRules.canConnect(FatigueBalance.current(), FatigueService.energy(player))) {
            return Begin.TOO_TIRED;
        }
        start(player, GlobalPos.of(player.level().dimension(), capsule));
        return Begin.STARTED;
    }

    /** The wait begins (the checks of {@link #begin} are done): the player is lying in {@code capsule}. */
    public static void start(ServerPlayer player, GlobalPos capsule) {
        PENDING.put(player.getUUID(), new Pending(capsule, CapsuleRules.CONNECT_TICKS, player.position()));
        PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(CapsuleRules.CONNECT_TICKS));
        player.level().playSound(null, capsule.pos(), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.6f, 1.4f);
    }

    public static boolean isConnecting(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /**
     * Stops a connection in progress (damage, moving, getting out of reach, the capsule gone, death...). The "Connecting…"
     * screen is always closed; the "cancelled" message only if {@code tellPlayer}.
     */
    public static void cancel(ServerPlayer player, boolean tellPlayer) {
        cancel(player, tellPlayer ? "message.overgearedmod.capsule.cancelled" : null);
    }

    private static void cancel(ServerPlayer player, @Nullable String messageKey) {
        if (PENDING.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(0));
            if (messageKey != null) {
                player.displayClientMessage(Component.translatable(messageKey), true);
            }
        }
    }

    private static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tick(player);
        }
    }

    /** One tick of the player's connection, if any. */
    public static void tick(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return;
        }
        if (!player.isAlive()) {
            cancel(player, false);
            return;
        }
        BlockPos pos = pending.capsule().pos();
        boolean here = player.level().dimension() == pending.capsule().dimension();
        boolean capsuleStillThere = here && player.level().getBlockState(pos).getBlock() instanceof CapsuleBlock;
        if (!capsuleStillThere) {
            cancel(player, true);
            return;
        }
        if (!CapsuleRules.stayedStill(player.position().distanceToSqr(pending.anchor()))) {
            cancel(player, "message.overgearedmod.capsule.cancelled_moved");
            return;
        }
        if (!CapsuleRules.inReach(player.distanceToSqr(pos.getCenter()))) {
            cancel(player, true);
            return;
        }
        Pending next = pending.tick();
        if (next.ticksLeft() > 0) {
            PENDING.put(player.getUUID(), next);
            return;
        }
        PENDING.remove(player.getUUID());
        connect(player, pending.capsule());
    }

    /** Taking damage wakes you up before you are connected. */
    private static void onDamaged(LivingDamageEvent.Post event) {
        if (event.getNewDamage() > 0 && event.getEntity() instanceof ServerPlayer player && isConnecting(player)) {
            cancel(player, true);
        }
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // The player is leaving: nobody is left to close a screen for.
            PENDING.remove(player.getUUID());
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            died(player);
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            respawned(player);
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cancel(player, true);
        }
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            reconcile(player);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        forgetAllConnections();
    }

    /** In case the previous server went down without stopping cleanly. */
    private static void onServerStarting(ServerStartingEvent event) {
        forgetAllConnections();
    }

    /** No connection in progress survives the server stopping. */
    public static void forgetAllConnections() {
        PENDING.clear();
    }

    /**
     * The player died: any connection in progress ends, and if they died in Satisfy the body leaves the capsule
     * (the last save is kept, and nobody is teleported).
     */
    public static void died(ServerPlayer player) {
        cancel(player, false);
        CapsuleState state = state(player);
        if (Satisfy.is(player.level())) {
            // The wait for the next rested bonus (RESTED_COOLDOWN) is kept on purpose: dying is not a way around it.
            player.setData(ModAttachments.RESTED_SECONDS.get(), 0);
        }
        if (Satisfy.is(player.level()) && state.isConnected()) {
            player.setData(ModAttachments.CAPSULE.get(), state.withoutBody());
            Overgearedmod.LOGGER.info("{} died in Satisfy: the body leaves the capsule", player.getGameProfile().getName());
        }
    }

    /** The new entity has the same UUID: a connection of the old one must not resume. */
    public static void respawned(ServerPlayer player) {
        cancel(player, false);
    }

    /** On login, the saved state is brought in line with where the player is. The last save is never touched. */
    public static void reconcile(ServerPlayer player) {
        CapsuleState state = state(player);
        switch (CapsuleRules.coherence(state, Satisfy.is(player.level()))) {
            case CONNECTED_OUTSIDE -> {
                player.setData(ModAttachments.CAPSULE.get(), state.withoutBody());
                // RESTED_COOLDOWN is kept: logging in outside Satisfy is not a way around the wait either.
                player.setData(ModAttachments.RESTED_SECONDS.get(), 0);
                Overgearedmod.LOGGER.warn("{} logged in outside Satisfy ({}) still connected to {}: the body is released",
                        player.getGameProfile().getName(), player.level().dimension().location(), state.capsule().orElse(null));
            }
            case DISCONNECTED_INSIDE -> Overgearedmod.LOGGER.warn("{} logged in inside Satisfy ({}) without a body in a capsule",
                    player.getGameProfile().getName(), player.level().dimension().location());
            case OK -> {
            }
        }
    }

    /** Where the player would appear in Satisfy now. */
    public static Optional<CapsuleRules.Spawn> spawnFor(ServerPlayer player, ServerLevel satisfy) {
        return CapsuleRules.spawn(state(player).lastSave(), WorldSites.landingFor(satisfy.getSeed()));
    }

    /** The tier of the capsule block at {@code capsule}; the basic one if there is no capsule there. */
    public static CapsuleTier tierAt(MinecraftServer server, GlobalPos capsule) {
        ServerLevel level = server.getLevel(capsule.dimension());
        if (level != null && level.getBlockState(capsule.pos()).getBlock() instanceof CapsuleBlock block) {
            return block.tier();
        }
        return CapsuleTier.BASIC;
    }

    /** The body stays in {@code capsule}; the player appears in Satisfy. Returns false if already connected or there is nowhere to appear. */
    public static boolean connect(ServerPlayer player, GlobalPos capsule) {
        if (state(player).isConnected()) {
            PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(0));
            Overgearedmod.LOGGER.warn("{} tried to connect while already connected", player.getGameProfile().getName());
            return false;
        }
        Optional<ServerLevel> satisfy = Satisfy.of(player.server);
        if (satisfy.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(0));
            return false;
        }
        Optional<CapsuleRules.Spawn> spawn = spawnFor(player, satisfy.get());
        if (spawn.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.overgearedmod.capsule.nowhere"), true);
            PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(0));
            return false;
        }
        if (!Characters.canCross(player, WorldSide.SATISFY)) {
            player.displayClientMessage(Component.translatable("message.overgearedmod.capsule.character_unreadable"), true);
            PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(0));
            Overgearedmod.LOGGER.error("{} cannot connect: the stored character cannot be read", player.getGameProfile().getName());
            return false;
        }
        // The tier is read from the block before the trip; a capsule that is gone by now counts as the basic one.
        CapsuleTier tier = tierAt(player.server, capsule);
        GlobalPos exit = GlobalPos.of(player.level().dimension(), player.blockPosition());
        // The state is saved before the trip (the arrival events may need it) and put back if the trip does not happen.
        CapsuleState before = state(player);
        player.setData(ModAttachments.CAPSULE.get(), before.connected(capsule, exit));
        BlockPos at = spawn.get().pos();
        Overgearedmod.LOGGER.info("{} connects to Satisfy at {} ({})", player.getGameProfile().getName(), at, spawn.get().source());
        BlockPos free = freeSpot(satisfy.get(), at, "connection", player);
        WorldSeparation.cross(player, () ->
                player.teleportTo(satisfy.get(), free.getX() + 0.5, free.getY(), free.getZ() + 0.5, player.getYRot(), 0.0f));
        if (!Satisfy.is(player.level())) {
            // Something (another mod) cancelled the trip: the player is still in the real world.
            player.setData(ModAttachments.CAPSULE.get(), before);
            PacketDistributor.sendToPlayer(player, new CapsuleConnectingPayload(0));
            player.displayClientMessage(Component.translatable("message.overgearedmod.capsule.nowhere"), true);
            Overgearedmod.LOGGER.warn("{} did not arrive in Satisfy: the connection is undone", player.getGameProfile().getName());
            return false;
        }
        player.resetFallDistance();
        player.setData(ModAttachments.CAPSULE_TIER.get(), tier);
        FatigueService.onConnected(player);
        player.displayClientMessage(Component.translatable("message.overgearedmod.capsule.connected"), true);
        return true;
    }

    /**
     * Takes the player out of Satisfy and back beside their capsule (or, without a known one, to their respawn point).
     * Where they were becomes where the next connection starts. Returns false if they were not in Satisfy, or if the trip did not happen (the state is then put back).
     */
    public static boolean disconnect(ServerPlayer player, Reason reason) {
        if (!Satisfy.is(player.level())) {
            return false;
        }
        CapsuleState before = state(player);
        player.setData(ModAttachments.CAPSULE.get(), before.disconnected(player.blockPosition()));
        Overgearedmod.LOGGER.info("{} disconnects from Satisfy ({})", player.getGameProfile().getName(), reason);

        Destination destination = destination(player.server, before.exit(), player.getRespawnDimension(), player.getRespawnPosition());
        ServerLevel target = destination.level();
        BlockPos free = freeSpot(target, destination.pos(), "disconnection", player);
        WorldSeparation.cross(player, () ->
                player.teleportTo(target, free.getX() + 0.5, free.getY(), free.getZ() + 0.5, player.getYRot(), 0.0f));
        if (player.level() != target) {
            // Something (another mod) cancelled the trip: the player is still in Satisfy, so the body stays where it was.
            player.setData(ModAttachments.CAPSULE.get(), before);
            Overgearedmod.LOGGER.warn("{} did not leave Satisfy: the disconnection is undone", player.getGameProfile().getName());
            return false;
        }
        player.resetFallDistance();
        // The tier stays (the recovery keeps using it); the rested bonus does not outlive the connection, but the wait
        // for the next one (RESTED_COOLDOWN) does: disconnecting and connecting again must not restart the bonus.
        player.setData(ModAttachments.RESTED_SECONDS.get(), 0);
        player.displayClientMessage(Component.translatable(reason == Reason.FATIGUE
                ? "message.overgearedmod.capsule.disconnected_tired" : "message.overgearedmod.capsule.disconnected"), true);
        return true;
    }

    /** Where a disconnection leads: a level and the spot wanted in it (before looking for a free one nearby). */
    public record Destination(ServerLevel level, BlockPos pos) {
    }

    /**
     * Where a player leaving Satisfy goes. Beside their capsule if the exit point is known and its dimension exists;
     * otherwise to their respawn point, or the world's spawn. A respawn point in Satisfy (a bed slept in there) is
     * never used: that would be a trip from Satisfy to Satisfy, so the overworld's spawn is used instead.
     */
    public static Destination destination(MinecraftServer server, Optional<GlobalPos> exit,
                                          ResourceKey<Level> respawnDimension, @Nullable BlockPos respawnPosition) {
        ServerLevel exitLevel = exit.map(GlobalPos::dimension).map(server::getLevel).orElse(null);
        if (exit.isPresent() && exitLevel != null) {
            return new Destination(exitLevel, exit.get().pos());
        }
        ServerLevel level = Optional.ofNullable(server.getLevel(respawnDimension)).orElse(server.overworld());
        if (Satisfy.is(level)) {
            level = server.overworld();
        }
        BlockPos pos = respawnPosition != null && level.dimension() == respawnDimension ? respawnPosition : level.getSharedSpawnPos();
        return new Destination(level, pos);
    }

    /** The nearest free spot to {@code wanted} (see {@link SafeSpot}); if there is none, {@code wanted} itself. */
    public static BlockPos freeSpot(ServerLevel level, BlockPos wanted, String what, ServerPlayer player) {
        Optional<BlockPos> found = SafeSpot.find(new LevelProbe(level), wanted);
        if (found.isEmpty()) {
            Overgearedmod.LOGGER.warn("No free spot near {} in {} for the {} of {}: using it as it is", wanted,
                    level.dimension().location(), what, player.getGameProfile().getName());
            return wanted;
        }
        return found.get();
    }
}
