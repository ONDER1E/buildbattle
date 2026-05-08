package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

public class PvPReadyCommand implements CommandExecutor {
    private final GameManager gameManager;
    private final Random random = new Random();

    private static final Material[] WOOLS = {
        Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
        Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
        Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
        Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL
    };

    public PvPReadyCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (!gameManager.isLobbyPvPEnabled()) {
            player.sendMessage(Component.text("PvP equipment is currently disabled!", NamedTextColor.RED));
            return true;
        }

        PlayerInventory inv = player.getInventory();
        inv.clear();

        // --- Armor & Offhand ---
        inv.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
        inv.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
        inv.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));

        // --- Specific Inventory Slots (Row above Hotbar) ---
        // inventory.0 -> Slot 9
        inv.setItem(9, new ItemStack(Material.ELYTRA));

        // inventory.1 -> Slot 10 (Long Poison Arrows)
        ItemStack tippedArrow = new ItemStack(Material.TIPPED_ARROW, 64);
        PotionMeta arrowMeta = (PotionMeta) tippedArrow.getItemMeta();
        if (arrowMeta != null) {
            // true, false = Extended (Long) Poison
            arrowMeta.setBasePotionData(new PotionData(PotionType.POISON, true, false));
            tippedArrow.setItemMeta(arrowMeta);
        }
        inv.setItem(10, tippedArrow);

        // inventory.2 -> Slot 11 (Normal Arrow)
        inv.setItem(11, new ItemStack(Material.ARROW, 1));

        // --- Hotbar Items ---
        // Using addItem now will fill the Hotbar (Slots 0-8) first because they are empty
        inv.addItem(new ItemStack(Material.NETHERITE_SWORD));
        inv.addItem(new ItemStack(Material.NETHERITE_AXE));

        // Infinity Bow
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta bowMeta = bow.getItemMeta();
        if (bowMeta != null) {
            Enchantment infinity = Enchantment.getByKey(NamespacedKey.minecraft("infinity"));
            if (infinity != null) {
                bowMeta.addEnchant(infinity, 1, true);
            }
            bow.setItemMeta(bowMeta);
        }
        inv.addItem(bow);

        // Food & Rockets
        inv.addItem(new ItemStack(Material.COOKED_BEEF, 64));
        inv.addItem(new ItemStack(Material.FIREWORK_ROCKET, 64));

        // Random Wool
        Material randomWool = WOOLS[random.nextInt(WOOLS.length)];
        inv.addItem(new ItemStack(randomWool, 64));

        player.sendMessage(Component.text("Lobby PvP Loadout Equipped!", NamedTextColor.GREEN));
        return true;
    }
}