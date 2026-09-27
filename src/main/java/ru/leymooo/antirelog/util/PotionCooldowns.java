package ru.leymooo.antirelog.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class PotionCooldowns {
    private static final NamespacedKey ORIGINAL_COOLDOWN = new NamespacedKey("antirelog", "original_potion_cooldown");
    private final Plugin plugin;
    private final Map<ItemStack, PotionCooldown> cooldowns = new HashMap<>();
    private Map<String, Long> bedrockCooldowns = Collections.emptyMap();
    private long blockedUntil;

    public PotionCooldowns(Plugin plugin) {
        this.plugin = plugin;
    }

    public static ItemStack key(ItemStack item) {
        ItemStack key = item.clone();
        restore(key);
        key.setAmount(1);
        return key;
    }

    public static void restore(ItemStack item) {
        if (!isPotion(item) || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        String original = meta.getPersistentDataContainer().get(ORIGINAL_COOLDOWN, PersistentDataType.STRING);
        if (original == null) return;
        if (original.isEmpty()) {
            meta.setUseCooldown(null);
        } else {
            int separator = original.lastIndexOf('|');
            UseCooldownComponent cooldown = meta.getUseCooldown();
            cooldown.setCooldownSeconds(Float.parseFloat(original.substring(separator + 1)));
            cooldown.setCooldownGroup(separator == 0 ? null : NamespacedKey.fromString(original.substring(0, separator)));
            meta.setUseCooldown(cooldown);
        }
        meta.getPersistentDataContainer().remove(ORIGINAL_COOLDOWN);
        item.setItemMeta(meta);
    }

    public void add(ItemStack item, long expiresAt) {
        ItemStack key = key(item);
        PotionCooldown cooldown = cooldowns.get(key);
        if (cooldown == null) {
            ItemStack visualItem = key.clone();
            ItemMeta meta = visualItem.getItemMeta();
            UseCooldownComponent component = meta.getUseCooldown();
            String original = "";
            if (meta.hasUseCooldown()) {
                NamespacedKey group = component.getCooldownGroup();
                original = (group == null ? "" : group.toString()) + "|" + component.getCooldownSeconds();
            } else {
                component.setCooldownSeconds(0.05F);
            }
            component.setCooldownGroup(new NamespacedKey(plugin, "potion/" + UUID.randomUUID()));
            meta.setUseCooldown(component);
            meta.getPersistentDataContainer().set(ORIGINAL_COOLDOWN, PersistentDataType.STRING, original);
            visualItem.setItemMeta(meta);
            cooldown = new PotionCooldown(visualItem, expiresAt);
            cooldowns.put(key, cooldown);
        }
        cooldown.expiresAt = expiresAt;
    }

    public void blockAll(long expiresAt) {
        blockedUntil = expiresAt;
    }

    public boolean synchronize(Player player, long now) {
        Iterator<PotionCooldown> iterator = cooldowns.values().iterator();
        while (iterator.hasNext()) {
            PotionCooldown cooldown = iterator.next();
            if (cooldown.expiresAt <= now) {
                player.setCooldown(cooldown.item, 0);
                iterator.remove();
            }
        }

        Map<String, Long> visibleCooldowns = new HashMap<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (synchronizeItem(item, now, visibleCooldowns)) {
                player.getInventory().setItem(slot, item);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (synchronizeItem(cursor, now, visibleCooldowns)) {
            player.setItemOnCursor(cursor);
        }

        for (PotionCooldown cooldown : cooldowns.values()) {
            int ticks = toTicks(cooldown.expiresAt - now);
            if (Math.abs((long) player.getCooldown(cooldown.item) - ticks) > 2) {
                player.setCooldown(cooldown.item, ticks);
            }
        }
        synchronizeBedrock(player, visibleCooldowns, now);
        return blockedUntil > now || !cooldowns.isEmpty();
    }

    private boolean synchronizeItem(ItemStack item, long now, Map<String, Long> visibleCooldowns) {
        if (!isPotion(item)) return false;
        ItemStack key = key(item);
        if (blockedUntil > now) {
            add(key, blockedUntil);
        }
        PotionCooldown cooldown = cooldowns.get(key);
        if (cooldown != null) {
            String group = cooldown.item.getItemMeta().getUseCooldown().getCooldownGroup().toString();
            visibleCooldowns.put(group, cooldown.expiresAt);
        }
        ItemMeta meta = cooldown == null ? key.getItemMeta() : cooldown.item.getItemMeta();
        if (meta.equals(item.getItemMeta())) return false;
        item.setItemMeta(meta);
        return true;
    }

    private void synchronizeBedrock(Player player, Map<String, Long> visibleCooldowns, long now) {
        if (bedrockCooldowns.equals(visibleCooldowns)) return;
        if (!plugin.getServer().getPluginManager().isPluginEnabled("VisualCooldown")) {
            bedrockCooldowns = Collections.emptyMap();
            return;
        }
        Map<String, Integer> ticks = new HashMap<>();
        visibleCooldowns.forEach((group, expiresAt) -> ticks.put(group, toTicks(expiresAt - now)));
        if (VisualCooldownUtils.syncPotionCooldowns(player, ticks)) {
            bedrockCooldowns = visibleCooldowns;
        }
    }

    public void clear(Player player) {
        blockedUntil = 0;
        for (PotionCooldown cooldown : cooldowns.values()) {
            player.setCooldown(cooldown.item, 0);
        }
        cooldowns.clear();
        synchronize(player, System.currentTimeMillis());
    }

    public static boolean isPotion(ItemStack item) {
        if (item == null) return false;
        Material material = item.getType();
        return material == Material.POTION || material == Material.SPLASH_POTION || material == Material.LINGERING_POTION;
    }

    private static int toTicks(long duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(Math.max(0, duration) / 50.0));
    }

    private static final class PotionCooldown {
        private final ItemStack item;
        private long expiresAt;

        private PotionCooldown(ItemStack item, long expiresAt) {
            this.item = item;
            this.expiresAt = expiresAt;
        }
    }
}
