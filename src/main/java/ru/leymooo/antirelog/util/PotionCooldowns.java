package ru.leymooo.antirelog.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;

public final class PotionCooldowns {
    private final Map<NamespacedKey, ItemStack> groups = new HashMap<>();

    public void track(ItemStack item) {
        if (item == null || !isPotion(item.getType()) || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasUseCooldown()) return;
        NamespacedKey group = meta.getUseCooldown().getCooldownGroup();
        if (group != null && !groups.containsKey(group)) {
            groups.put(group, item.clone());
        }
    }

    public void synchronize(Player player, int ticks) {
        for (ItemStack item : player.getInventory().getContents()) {
            track(item);
        }
        for (ItemStack item : groups.values()) {
            if (Math.abs(player.getCooldown(item) - ticks) > 2) {
                player.setCooldown(item, ticks);
            }
        }
    }

    public void clear(Player player) {
        for (ItemStack item : groups.values()) {
            player.setCooldown(item, 0);
        }
        groups.clear();
    }

    private static boolean isPotion(Material material) {
        return material == Material.POTION || material == Material.SPLASH_POTION || material == Material.LINGERING_POTION;
    }
}
