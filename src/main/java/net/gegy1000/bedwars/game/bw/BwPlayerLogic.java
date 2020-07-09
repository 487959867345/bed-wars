package net.gegy1000.bedwars.game.bw;

import net.gegy1000.bedwars.util.ItemUtil;
import net.gegy1000.bedwars.game.GameTeam;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.function.Predicate;

public final class BwPlayerLogic {
    private final BedWars game;

    private long lastEnchantmentCheck;

    BwPlayerLogic(BedWars game) {
        this.game = game;
    }

    public void setupPlayers() {
        this.game.state.participants().forEach(participant -> {
            ServerPlayerEntity player = participant.player();
            if (player == null) {
                return;
            }
            this.setupPlayer(player);
        });
    }

    public void tick() {
        long time = this.game.world.getTime();

        this.game.state.participants().forEach(participant -> {
            ServerPlayerEntity player = participant.player();
            if (player == null) return;

            HungerManager hungerManager = player.getHungerManager();
            if (hungerManager.isNotFull()) {
                hungerManager.setFoodLevel(20);
            }

            if (participant.isRespawning() && time >= participant.respawnTime) {
                this.spawnPlayer(player, participant.respawningAt);
                participant.stopRespawning();
            }
        });

        if (time - this.lastEnchantmentCheck > 20) {
            this.game.state.participants().forEach(participant -> {
                ServerPlayerEntity player = participant.player();
                if (player != null) {
                    this.applyEnchantments(player, participant);
                }
            });

            this.lastEnchantmentCheck = time;
        }
    }

    private void setupPlayer(ServerPlayerEntity player) {
        player.inventory.clear();
        player.getEnderChestInventory().clear();
        player.clearStatusEffects();
        player.setHealth(20.0F);
        player.getHungerManager().setFoodLevel(20);
        player.fallDistance = 0.0F;
    }

    public void spawnPlayer(ServerPlayerEntity player, BwMap.TeamSpawn spawn) {
        player.inventory.clear();

        BwState.Participant participant = this.game.state.getParticipant(player);
        if (participant != null) {
            this.equipDefault(player, participant);
        }

        player.setHealth(20.0F);
        player.getHungerManager().setFoodLevel(20);
        player.setGameMode(GameMode.SURVIVAL);

        spawn.placePlayer(player);
    }

    public void applyEnchantments(ServerPlayerEntity player, BwState.Participant participant) {
        BwState.TeamState teamState = this.game.state.getTeam(participant.team);
        if (teamState == null) {
            return;
        }

        this.applyEnchantments(player, stack -> stack.getItem() instanceof SwordItem, Enchantments.SHARPNESS, teamState.swordSharpness);
        this.applyEnchantments(player, stack -> stack.getItem() instanceof ArmorItem, Enchantments.PROTECTION, teamState.armorProtection);
    }

    private void applyEnchantments(ServerPlayerEntity player, Predicate<ItemStack> predicate, Enchantment enchantment, int level) {
        if (level <= 0) return;

        PlayerInventory inventory = player.inventory;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                int existingLevel = ItemUtil.getEnchantLevel(stack, enchantment);
                if (existingLevel != level) {
                    ItemUtil.removeEnchant(stack, enchantment);
                    stack.addEnchantment(enchantment, level);
                }
            }
        }
    }

    public void equipDefault(ServerPlayerEntity player, BwState.Participant participant) {
        this.equipArmor(player, participant);
        player.inventory.setStack(0, ItemUtil.unbreakable(new ItemStack(Items.WOODEN_SWORD)));

        this.applyEnchantments(player, participant);
    }

    public void upgradeArmorTo(BwState.Participant participant, ArmorLevel level) {
        if (participant.armorLevel.isUpgradeTo(level)) {
            participant.armorLevel = level;
        }

        ServerPlayerEntity player = participant.player();
        this.equipArmor(player, participant);
    }

    private void equipArmor(ServerPlayerEntity player, BwState.Participant participant) {
        GameTeam team = participant.team;
        ArmorLevel armorLevel = participant.armorLevel;

        ItemStack[] armorStacks = new ItemStack[] {
                new ItemStack(Items.LEATHER_HELMET),
                new ItemStack(armorLevel.chest),
                new ItemStack(Items.LEATHER_LEGGINGS),
                new ItemStack(armorLevel.feet)
        };

        EquipmentSlot[] armorSlots = new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET
        };

        for (int i = 0; i < armorSlots.length; i++) {
            int slot = armorSlots[i].getEntitySlotId();
            ItemStack stack = ItemUtil.unbreakable(team.dye(armorStacks[i]));
            player.inventory.armor.set(slot, stack);
        }
    }

    public void spawnSpectator(ServerPlayerEntity player) {
        player.inventory.clear();
        player.setHealth(20.0F);
        player.getHungerManager().setFoodLevel(20);
        player.setGameMode(GameMode.SPECTATOR);

        Vec3d center = this.game.map.getCenter();
        player.teleport(center.x, center.y, center.z);
    }

    public void respawnOnTimer(ServerPlayerEntity player, BwMap.TeamSpawn spawn) {
        this.spawnSpectator(player);

        BwState.Participant participant = this.game.state.getParticipant(player);
        if (participant != null) {
            participant.startRespawning(spawn);
            player.sendMessage(new LiteralText("You will respawn in " + BedWars.RESPAWN_TIME_SECONDS + " seconds..").formatted(Formatting.BOLD), false);
        }
    }
}
