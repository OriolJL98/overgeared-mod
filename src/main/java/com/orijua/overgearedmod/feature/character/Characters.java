package com.orijua.overgearedmod.feature.character;

import com.orijua.overgearedmod.Overgearedmod;
import com.orijua.overgearedmod.core.registry.ModAttachments;
import com.orijua.overgearedmod.feature.progression.ProgressionService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The player has two characters, one per world (see {@link WorldSide}), and carries the one of the world they are in.
 * {@link #settle} is the ONLY place that moves their things: it compares which world's things they carry
 * ({@link CharacterVault#live()}) with the world they stand in, and swaps if they differ. Because it works from that
 * state and not from "a crossing happened", it is idempotent (a second call does nothing), and every way of changing
 * world (capsule, logout, debug goto, and the ones that skip any event, like respawning after dying in Satisfy) only
 * has to call it. Rule: nothing else may move items between the characters; the only documented exception is the
 * purchases of {@code TerminalShop}, which only add entries to the stored inventory.
 *
 * <p>The swap happens on the server thread in one call, so the autosave (which runs between ticks) never sees half of it.
 */
public final class Characters {
    private Characters() {
    }

    /** What {@link #settle} did. */
    public enum Result {
        /** The first time: the player was taken as already carrying this world's things; nothing moved. */
        ADOPTED,
        /** They already carried this world's things. */
        UNCHANGED,
        SWAPPED,
        /** The stored character could not be read: nothing moved and nothing was lost; it is tried again next time. */
        FAILED
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(Characters::onChangedDimension);
        // After ProgressionEvents.onRespawn (normal priority), which sets the health.
        gameBus.addListener(EventPriority.LOWEST, Characters::onRespawn);
        gameBus.addListener(Characters::onLoggedIn);
    }

    public static CharacterVault vault(ServerPlayer player) {
        return player.getData(ModAttachments.CHARACTER_VAULT.get());
    }

    /**
     * Before leaving a world: closes the open menu, which gives the cursor and the 2x2 crafting grid back to the
     * inventory (or drops them) while the player is still in the world they belong to.
     */
    public static void prepareCrossing(ServerPlayer player) {
        closeMenu(player);
    }

    /**
     * Closes the open menu. The cursor goes back to the inventory first, and if it does not fit the player is told it
     * fell to the ground: vanilla drops it silently, in the world they are about to leave.
     */
    private static void closeMenu(ServerPlayer player) {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            player.closeContainer();
            return;
        }
        ItemStack cursor = carried.copy();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        if (!player.getInventory().add(cursor)) {
            player.drop(cursor, false);
            player.displayClientMessage(Component.translatable("message.overgearedmod.character.cursor_dropped"), true);
        }
    }

    /**
     * Whether a crossing into {@code target} would work: false only when it would need to swap in a stored character
     * that cannot be read. Asked BEFORE teleporting, so the crossing is refused instead of leaving the player in a world
     * wearing the other world's things.
     */
    public static boolean canCross(ServerPlayer player, WorldSide target) {
        CharacterVault vault = vault(player);
        if (vault.live().isEmpty() || vault.live().get() == target) {
            return true;
        }
        return vault.stored().map(CharacterSnapshot::isReadable).orElse(true);
    }

    /** Makes the player carry the character of the world they stand in. Safe to call any time, any number of times. */
    public static Result settle(ServerPlayer player) {
        WorldSide want = WorldSide.of(player.level());
        CharacterVault vault = vault(player);
        if (vault.live().isEmpty()) {
            // Adoption: there is no vault yet (a new player). What they carry is taken as this world's character,
            // wherever they happen to be, and nothing is moved or lost.
            player.setData(ModAttachments.CHARACTER_VAULT.get(), vault.adopted(want));
            Overgearedmod.LOGGER.info("{} is taken as carrying the {} character", player.getGameProfile().getName(), want);
            return Result.ADOPTED;
        }
        if (vault.live().get() == want) {
            return Result.UNCHANGED;
        }
        // Everything that can fail is checked before anything is touched.
        CompoundTag incoming = vault.stored().orElseGet(CharacterSnapshot::empty);
        if (!CharacterSnapshot.isReadable(incoming)) {
            Overgearedmod.LOGGER.error("The stored character of {} cannot be read: not swapping", player.getGameProfile().getName());
            return Result.FAILED;
        }
        // The menu closes first so the cursor and the crafting grid are part of the character that leaves.
        closeMenu(player);
        CompoundTag outgoing = CharacterSnapshot.capture(player);
        try {
            CharacterSnapshot.apply(player, incoming);
        } catch (RuntimeException e) {
            // Half applied: put back what was there so nothing is duplicated or lost, and keep the vault as it was.
            Overgearedmod.LOGGER.error("Could not restore the {} character of {}: undoing", want, player.getGameProfile().getName(), e);
            try {
                CharacterSnapshot.apply(player, outgoing);
            } catch (RuntimeException undo) {
                // Nothing more can be done here; the vault is untouched, so the next settle tries again.
                Overgearedmod.LOGGER.error("The undo failed too for {}", player.getGameProfile().getName(), undo);
            }
            return Result.FAILED;
        }
        player.setData(ModAttachments.CHARACTER_VAULT.get(), CharacterVault.of(want, outgoing));
        // Equipment changed, so the bonuses of what they wear are recomputed; only then is the health capped at the
        // maximum of this world.
        ProgressionService.refreshEffects(player);
        CharacterSnapshot.applyHealth(player, incoming);
        Overgearedmod.LOGGER.info("{} now carries the {} character", player.getGameProfile().getName(), want);
        return Result.SWAPPED;
    }

    // Safety nets for the crossings that do not go through WorldSeparation.cross. Fake players (other mods') are left alone.

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            settle(player);
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            settle(player);
        }
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            settle(player);
        }
    }
}
