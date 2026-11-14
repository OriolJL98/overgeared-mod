package com.orijua.overgearedmod.gametest;

import com.orijua.overgearedmod.Overgearedmod;
import com.orijua.overgearedmod.core.registry.ModAttachments;
import com.orijua.overgearedmod.feature.capsule.CapsuleState;
import com.orijua.overgearedmod.feature.character.CharacterSnapshot;
import com.orijua.overgearedmod.feature.character.CharacterVault;
import com.orijua.overgearedmod.feature.character.WorldSide;
import com.orijua.overgearedmod.feature.capsule.CapsuleService;
import com.orijua.overgearedmod.feature.character.Characters;
import com.orijua.overgearedmod.feature.economy.Account;
import com.orijua.overgearedmod.feature.economy.AccountService;
import com.orijua.overgearedmod.feature.world.Satisfy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * The two characters, step 1: the data model. The vault is saved with the player and survives death, a snapshot reads
 * back exactly what it captured (enchantments, names, damage, shulker contents, Ender chest), a damaged tag is
 * recognised before anything is touched, and "reset all" leaves the vault alone. Nothing swaps yet.
 */
@GameTestHolder(Overgearedmod.MODID)
@PrefixGameTestTemplate(false)
public final class CharacterTests {
    private CharacterTests() {
    }

    /** A sword with everything a naive codec would lose: an enchantment, a name and damage. */
    private static ItemStack fancySword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Espada de Grid"));
        sword.setDamageValue(7);
        return sword;
    }

    private static ItemStack loadedShulker() {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
        return shulker;
    }

    /** The first test: the vault is a "copy on death" attachment, so the old player's vault reaches the new one. */
    @GameTest(template = TestSupport.EMPTY)
    public static void theVaultSurvivesRespawn(GameTestHelper helper) {
        ServerPlayer old = TestSupport.fakePlayer(helper);
        old.getInventory().setItem(0, fancySword(helper));
        CharacterVault vault = CharacterVault.of(WorldSide.SATISFY, CharacterSnapshot.capture(old));
        old.setData(ModAttachments.CHARACTER_VAULT.get(), vault);

        ServerPlayer fresh = TestSupport.fakePlayer(helper);
        helper.assertTrue(fresh.getData(ModAttachments.CHARACTER_VAULT.get()).equals(CharacterVault.EMPTY), "a new player has no vault");
        fresh.restoreFrom(old, false);
        helper.assertTrue(fresh.getData(ModAttachments.CHARACTER_VAULT.get()).equals(vault), "the vault is copied to the player who respawns");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void theVaultIsSavedWithThePlayer(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.getInventory().setItem(3, fancySword(helper));
        CharacterVault vault = CharacterVault.of(WorldSide.REAL, CharacterSnapshot.capture(player));
        player.setData(ModAttachments.CHARACTER_VAULT.get(), vault);

        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        ServerPlayer other = TestSupport.fakePlayer(helper);
        other.load(saved);
        helper.assertTrue(other.getData(ModAttachments.CHARACTER_VAULT.get()).equals(vault), "the vault is read back from the saved player");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aSnapshotReadsBackExactlyWhatItCaptured(GameTestHelper helper) {
        ServerPlayer source = TestSupport.fakePlayer(helper);
        ItemStack sword = fancySword(helper);
        ItemStack shulker = loadedShulker();
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        ItemStack offhand = new ItemStack(Items.SHIELD);
        source.getInventory().setItem(0, sword.copy());
        source.getInventory().setItem(8, shulker.copy());
        source.getInventory().armor.set(3, helmet.copy());
        source.getInventory().offhand.set(0, offhand.copy());
        source.getEnderChestInventory().setItem(5, new ItemStack(Items.EMERALD, 12));
        CompoundTag snapshot = CharacterSnapshot.capture(source);

        ServerPlayer target = TestSupport.fakePlayer(helper);
        target.getInventory().setItem(1, new ItemStack(Items.DIRT, 64));
        target.getEnderChestInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        CharacterSnapshot.apply(target, snapshot);

        helper.assertTrue(ItemStack.matches(target.getInventory().getItem(0), sword), "the enchanted, named, damaged sword comes back identical");
        helper.assertTrue(ItemStack.matches(target.getInventory().getItem(8), shulker), "the shulker keeps its contents");
        helper.assertTrue(ItemStack.matches(target.getInventory().armor.get(3), helmet), "the armor comes back");
        helper.assertTrue(ItemStack.matches(target.getInventory().offhand.get(0), offhand), "the offhand comes back");
        helper.assertTrue(target.getEnderChestInventory().getItem(5).is(Items.EMERALD) && target.getEnderChestInventory().getItem(5).getCount() == 12,
                "the Ender chest comes back");
        helper.assertTrue(target.getInventory().getItem(1).isEmpty(), "what the target carried before is replaced, not merged");
        helper.assertTrue(target.getEnderChestInventory().getItem(0).isEmpty(), "the target's old Ender chest is replaced too");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aSnapshotKeepsNoSharedState(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        CompoundTag snapshot = CharacterSnapshot.capture(player);
        CharacterVault vault = CharacterVault.of(WorldSide.REAL, snapshot);
        snapshot.putInt("tampered", 1);
        helper.assertFalse(vault.stored().orElseThrow().contains("tampered"), "the vault holds its own copy of the tag");
        vault.stored().orElseThrow().putInt("tampered", 1);
        helper.assertFalse(vault.stored().orElseThrow().contains("tampered"), "what the vault hands out is a copy too");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aDamagedTagIsRecognisedBeforeAnythingIsTouched(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        helper.assertFalse(CharacterSnapshot.isReadable(new CompoundTag()), "an empty tag is not a snapshot");
        helper.assertTrue(CharacterSnapshot.isReadable(CharacterSnapshot.capture(player)), "a captured snapshot is readable");
        helper.assertTrue(CharacterSnapshot.isReadable(CharacterSnapshot.empty()), "the empty character is readable");
        CharacterSnapshot.apply(player, CharacterSnapshot.empty());
        helper.assertTrue(player.getInventory().isEmpty() && player.getEnderChestInventory().isEmpty(), "applying the empty character leaves nothing");
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void worldSideSeparatesSatisfyFromEverythingElse(GameTestHelper helper) {
        com.orijua.overgearedmod.feature.world.Satisfy.standIn(helper.getLevel());
        try {
            helper.assertTrue(WorldSide.of(helper.getLevel()) == WorldSide.SATISFY, "the level that plays Satisfy is SATISFY");
        } finally {
            com.orijua.overgearedmod.feature.world.Satisfy.restoreStandIn();
        }
        com.orijua.overgearedmod.feature.world.Satisfy.standIn(null);
        try {
            helper.assertTrue(WorldSide.of(helper.getLevel()) == WorldSide.REAL, "any other level is REAL");
        } finally {
            com.orijua.overgearedmod.feature.world.Satisfy.restoreStandIn();
        }
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void resetAllLeavesTheVaultAlone(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.getInventory().setItem(0, fancySword(helper));
        CharacterVault vault = CharacterVault.of(WorldSide.SATISFY, CharacterSnapshot.capture(player));
        player.setData(ModAttachments.CHARACTER_VAULT.get(), vault);
        player.setData(ModAttachments.CAPSULE.get(), CapsuleState.EMPTY.disconnected(player.blockPosition()));

        ModAttachments.resetAll(player);

        helper.assertTrue(player.getData(ModAttachments.CHARACTER_VAULT.get()).equals(vault), "reset all does not touch the other world's belongings");
        helper.assertTrue(player.getData(ModAttachments.CAPSULE.get()).equals(CapsuleState.EMPTY), "but it does reset the rest");
        helper.succeed();
    }

    // ---- Step 2: settle and the hooks ----

    /** Runs {@code body} with the test level playing the given world, then puts the test default back. */
    private static void in(GameTestHelper helper, WorldSide side, Runnable body) {
        try {
            Satisfy.standIn(side == WorldSide.SATISFY ? helper.getLevel() : null);
            body.run();
        } finally {
            Satisfy.restoreStandIn();
        }
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        return player.getInventory().countItem(item);
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void theFirstSettleAdoptsWhereThePlayerStands(GameTestHelper helper) {
        for (WorldSide side : WorldSide.values()) {
            ServerPlayer player = TestSupport.fakePlayer(helper);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 9));
            in(helper, side, () -> {
                helper.assertTrue(Characters.settle(player) == Characters.Result.ADOPTED, "the first time nothing moves (" + side + ")");
                helper.assertTrue(count(player, Items.IRON_INGOT) == 9, "the player keeps what they carried (" + side + ")");
                helper.assertTrue(Characters.vault(player).live().equals(Optional.of(side)), "they now carry this world's character (" + side + ")");
                helper.assertTrue(Characters.vault(player).stored().isEmpty(), "and the other world has nothing stored yet (" + side + ")");
            });
        }
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void goingToSatisfyAndBackSwapsThemAndConservesEverything(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.getInventory().setItem(0, fancySword(helper));
        player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 5));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
        ItemStack sword = fancySword(helper);

        in(helper, WorldSide.REAL, () -> Characters.settle(player));

        in(helper, WorldSide.SATISFY, () -> {
            helper.assertTrue(Characters.settle(player) == Characters.Result.SWAPPED, "entering Satisfy swaps");
            helper.assertTrue(player.getInventory().isEmpty(), "Satisfy starts with nothing: the real things stay behind");
            helper.assertTrue(player.getEnderChestInventory().isEmpty(), "and so does its Ender chest");
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setItem(2, new ItemStack(Items.COAL, 30));
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
            helper.assertTrue(Characters.settle(player) == Characters.Result.UNCHANGED, "a second settle in the same world does nothing");
            helper.assertTrue(count(player, Items.COAL) == 30, "and loses nothing");
        });

        in(helper, WorldSide.REAL, () -> {
            helper.assertTrue(Characters.settle(player) == Characters.Result.SWAPPED, "coming back swaps");
            helper.assertTrue(ItemStack.matches(player.getInventory().getItem(0), sword), "the real sword is back, enchanted, named and damaged");
            helper.assertTrue(count(player, Items.DIAMOND) == 5 && count(player, Items.COAL) == 0 && count(player, Items.IRON_SWORD) == 0,
                    "the real inventory is back and none of Satisfy's leaked");
            helper.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.EMERALD) && player.getEnderChestInventory().countItem(Items.GOLD_INGOT) == 0,
                    "the Ender chests stay separate");
            helper.assertTrue(Characters.settle(player) == Characters.Result.UNCHANGED, "idempotent here too");
        });

        in(helper, WorldSide.SATISFY, () -> {
            Characters.settle(player);
            helper.assertTrue(count(player, Items.COAL) == 30 && count(player, Items.IRON_SWORD) == 1 && count(player, Items.DIAMOND) == 0,
                    "Satisfy's character came back untouched the second time");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aCursorItemThatDoesNotFitFallsNextToThePlayerInsteadOfVanishing(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            }
            player.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 2));
            Characters.prepareCrossing(player);
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "the cursor is emptied");
            helper.assertTrue(count(player, Items.DIAMOND) == 0, "it did not fit");
            int dropped = player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    player.getBoundingBox().inflate(4), e -> e.getItem().is(Items.DIAMOND)).stream().mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(dropped == 2, "both diamonds fell next to the player (found " + dropped + ")");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void aCursorItemThatFitsGoesBackToTheInventoryEvenWhenItStacks(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
            player.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
            Characters.prepareCrossing(player);
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "the cursor is emptied");
            helper.assertTrue(count(player, Items.DIAMOND) == 15, "all 15 diamonds are in the inventory");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void theCursorAndTheCraftingGridGoWithTheWorldTheyAreIn(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            player.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 2));
            player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            Characters.prepareCrossing(player);
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "the cursor is emptied before crossing");
            helper.assertTrue(player.inventoryMenu.getCraftSlots().isEmpty(), "and so is the 2x2 grid");
            helper.assertTrue(count(player, Items.DIAMOND) == 2 && count(player, Items.OAK_PLANKS) == 4, "both went back to the real inventory");
        });
        in(helper, WorldSide.SATISFY, () -> {
            // Picked up while still carrying the real character: it leaves with it.
            player.containerMenu.setCarried(new ItemStack(Items.STONE, 3));
            Characters.settle(player);
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "a settle closes the menu too");
            helper.assertTrue(count(player, Items.DIAMOND) == 0, "the real things did not follow");
        });
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            helper.assertTrue(count(player, Items.DIAMOND) == 2 && count(player, Items.OAK_PLANKS) == 4, "the real things are back");
            helper.assertTrue(count(player, Items.STONE) == 3, "and what was on the cursor when the world changed went with the character that carried it");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingInSatisfyBringsTheRealCharacterBack(GameTestHelper helper) {
        ServerPlayer old = TestSupport.fakePlayer(helper);
        old.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        in(helper, WorldSide.REAL, () -> Characters.settle(old));
        in(helper, WorldSide.SATISFY, () -> {
            Characters.settle(old);
            old.getInventory().setItem(0, new ItemStack(Items.COAL, 30));
        });

        // The death: the new player has an empty inventory (Satisfy's things drop there) and the old one's vault.
        ServerPlayer fresh = TestSupport.fakePlayer(helper);
        fresh.restoreFrom(old, false);
        helper.assertTrue(fresh.getInventory().isEmpty(), "the new player carries nothing");
        in(helper, WorldSide.REAL, () -> {
            helper.assertTrue(Characters.settle(fresh) == Characters.Result.SWAPPED, "respawning in the real world swaps");
            helper.assertTrue(count(fresh, Items.DIAMOND) == 5 && count(fresh, Items.COAL) == 0, "the real inventory is back, with none of Satisfy's");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingInTheRealWorldChangesNothing(GameTestHelper helper) {
        ServerPlayer old = TestSupport.fakePlayer(helper);
        old.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        in(helper, WorldSide.REAL, () -> Characters.settle(old));
        in(helper, WorldSide.SATISFY, () -> {
            Characters.settle(old);
            old.getInventory().setItem(0, new ItemStack(Items.COAL, 30));
        });
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(old);
            ServerPlayer fresh = TestSupport.fakePlayer(helper);
            fresh.restoreFrom(old, false);
            helper.assertTrue(Characters.settle(fresh) == Characters.Result.UNCHANGED, "no swap: they already carried the real character");
            CharacterVault vault = Characters.vault(fresh);
            helper.assertTrue(vault.live().equals(Optional.of(WorldSide.REAL)) && vault.stored().isPresent(), "Satisfy's character stays stored");
        });
        in(helper, WorldSide.SATISFY, () -> {
            ServerPlayer again = TestSupport.fakePlayer(helper);
            again.restoreFrom(old, false);
            Characters.settle(again);
            helper.assertTrue(count(again, Items.COAL) == 30, "and it was not lost");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void anUnreadableStoredCharacterMovesNothing(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        CharacterVault broken = CharacterVault.of(WorldSide.REAL, new CompoundTag());
        player.setData(ModAttachments.CHARACTER_VAULT.get(), broken);
        in(helper, WorldSide.SATISFY, () -> {
            helper.assertTrue(Characters.settle(player) == Characters.Result.FAILED, "a damaged tag is not applied");
            helper.assertTrue(count(player, Items.DIAMOND) == 5, "the player keeps what they carry");
            helper.assertTrue(Characters.vault(player).equals(broken), "and the vault is exactly as it was");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void connectingAndDisconnectingThroughTheCapsuleSwapsThem(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        net.minecraft.core.BlockPos bairan = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        net.minecraft.core.GlobalPos capsule = net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new net.minecraft.core.BlockPos(0, 1, 0)));
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        in(helper, WorldSide.REAL, () -> Characters.settle(player));

        CapsuleTests.inSatisfy(helper, bairan, () -> {
            helper.assertTrue(CapsuleService.connect(player, capsule), "it connects");
            helper.assertTrue(player.getInventory().isEmpty(), "connecting leaves the real things behind");
            helper.assertTrue(Characters.vault(player).live().equals(Optional.of(WorldSide.SATISFY)), "and the player now carries Satisfy's character");
            player.getInventory().setItem(0, new ItemStack(Items.COAL, 30));
        });
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            helper.assertTrue(count(player, Items.DIAMOND) == 5 && count(player, Items.COAL) == 0, "back in the real world each has their own");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void connectingIsRefusedWhenTheStoredCharacterCannotBeRead(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        net.minecraft.core.BlockPos bairan = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        net.minecraft.core.GlobalPos capsule = net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new net.minecraft.core.BlockPos(0, 1, 0)));
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        CharacterVault broken = CharacterVault.of(WorldSide.REAL, new CompoundTag());
        player.setData(ModAttachments.CHARACTER_VAULT.get(), broken);

        helper.assertFalse(Characters.canCross(player, WorldSide.SATISFY), "crossing needs a readable stored character");
        helper.assertTrue(Characters.canCross(player, WorldSide.REAL), "staying in the same world needs nothing");
        CapsuleTests.inSatisfy(helper, bairan, () -> {
            helper.assertFalse(CapsuleService.connect(player, capsule), "the connection is refused");
            helper.assertTrue(count(player, Items.DIAMOND) == 5, "nothing moved");
            helper.assertTrue(Characters.vault(player).equals(broken), "the vault is as it was");
            helper.assertFalse(CapsuleService.state(player).isConnected(), "and the body did not leave the real world");
        });
        helper.succeed();
    }

    // ---- Step 3: vanilla XP, health, effects and hunger ----

    @GameTest(template = TestSupport.EMPTY)
    public static void xpHealthHungerAndEffectsAreSeparatedByWorld(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.experienceLevel = 12;
        player.experienceProgress = 0.5f;
        player.totalExperience = 300;
        player.setHealth(7.0f);
        player.getFoodData().setFoodLevel(5);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 6000, 1));
        in(helper, WorldSide.REAL, () -> Characters.settle(player));

        in(helper, WorldSide.SATISFY, () -> {
            helper.assertTrue(Characters.settle(player) == Characters.Result.SWAPPED, "entering Satisfy swaps");
            helper.assertTrue(player.experienceLevel == 0 && player.totalExperience == 0 && player.experienceProgress == 0.0f, "a new character has no vanilla XP");
            helper.assertTrue(player.getHealth() == player.getMaxHealth(), "full health");
            helper.assertTrue(player.getFoodData().getFoodLevel() == 20, "full hunger");
            helper.assertTrue(player.getActiveEffects().isEmpty(), "and no effects: the speed stayed behind");
            player.experienceLevel = 3;
            player.totalExperience = 40;
            player.setHealth(10.0f);
            player.getFoodData().setFoodLevel(12);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 300));
        });

        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            helper.assertTrue(player.experienceLevel == 12 && player.experienceProgress == 0.5f && player.totalExperience == 300, "the real XP is back");
            helper.assertTrue(player.getHealth() == 7.0f, "the real health is back, not refilled");
            helper.assertTrue(player.getFoodData().getFoodLevel() == 5, "the real hunger is back");
            helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED), "the real effect is back");
            helper.assertFalse(player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION), "and Satisfy's did not follow");
            helper.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED).getAmplifier() == 1, "with its strength");
        });

        in(helper, WorldSide.SATISFY, () -> {
            Characters.settle(player);
            helper.assertTrue(player.experienceLevel == 3 && player.totalExperience == 40, "Satisfy's XP is back the second time");
            helper.assertTrue(player.getHealth() == 10.0f && player.getFoodData().getFoodLevel() == 12, "and its health and hunger");
            helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION), "and its effect");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void storedEffectsDoNotRunWhileTheyAreStored(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 6000));
        in(helper, WorldSide.REAL, () -> Characters.settle(player));
        in(helper, WorldSide.SATISFY, () -> Characters.settle(player));
        for (int i = 0; i < 40; i++) {
            player.tick();
        }
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            int left = player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED).getDuration();
            helper.assertTrue(left == 6000, "the duration was frozen while stored (left " + left + ")");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void restoredHealthIsCappedAtTheMaximumOfTheWorld(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        in(helper, WorldSide.REAL, () -> Characters.settle(player));
        CompoundTag stored = CharacterSnapshot.empty();
        stored.putFloat("health", 500.0f);
        player.setData(ModAttachments.CHARACTER_VAULT.get(), CharacterVault.of(WorldSide.REAL, stored));
        in(helper, WorldSide.SATISFY, () -> {
            Characters.settle(player);
            helper.assertTrue(player.getHealth() <= player.getMaxHealth() && player.getHealth() > 0, "capped at the maximum, not above it");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void dyingInSatisfyReturnsTheRealBodyWithItsOwnVitals(GameTestHelper helper) {
        ServerPlayer old = TestSupport.fakePlayer(helper);
        old.setHealth(9.0f);
        old.getFoodData().setFoodLevel(6);
        old.experienceLevel = 20;
        old.totalExperience = 700;
        in(helper, WorldSide.REAL, () -> Characters.settle(old));
        in(helper, WorldSide.SATISFY, () -> Characters.settle(old));

        ServerPlayer fresh = TestSupport.fakePlayer(helper);
        fresh.restoreFrom(old, false);
        fresh.setHealth(fresh.getMaxHealth());
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(fresh);
            helper.assertTrue(fresh.getHealth() == 9.0f, "the real body did not die: its health is the one it had");
            helper.assertTrue(fresh.getFoodData().getFoodLevel() == 6 && fresh.experienceLevel == 20, "and its hunger and XP");
        });
        helper.succeed();
    }

    @GameTest(template = TestSupport.EMPTY)
    public static void settleLeavesTheAccountAlone(GameTestHelper helper) {
        ServerPlayer player = TestSupport.fakePlayer(helper);
        player.setData(ModAttachments.ACCOUNT.get(), new Account(1234, 50));
        Account expected = new Account(1234, 50);

        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            helper.assertTrue(AccountService.get(player).equals(expected), "the account is intact after adopting the real world");
        });
        in(helper, WorldSide.SATISFY, () -> {
            helper.assertTrue(Characters.settle(player) == Characters.Result.SWAPPED, "entering Satisfy swaps");
            helper.assertTrue(AccountService.get(player).equals(expected), "the account is intact in Satisfy");
        });
        in(helper, WorldSide.REAL, () -> {
            Characters.settle(player);
            helper.assertTrue(AccountService.get(player).equals(expected), "and back in the real world");
        });
        helper.succeed();
    }
}
