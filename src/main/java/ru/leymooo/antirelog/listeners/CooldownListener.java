package ru.leymooo.antirelog.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.floodgate.api.FloodgateApi;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.event.PvpStartedEvent;
import ru.leymooo.antirelog.event.PvpStoppedEvent;
import ru.leymooo.antirelog.manager.CooldownManager;
import ru.leymooo.antirelog.manager.CooldownManager.CooldownType;
import ru.leymooo.antirelog.manager.PvPManager;
import ru.leymooo.antirelog.util.Utils;
import ru.leymooo.antirelog.util.VersionUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CooldownListener implements Listener {

    private final CooldownManager cooldownManager;
    private final PvPManager pvpManager;
    private final Settings settings;
    private final boolean floodgateEnabled;
    private final Map<UUID, ItemStack> brokenElytraPlayers = new HashMap<>();

    public CooldownListener(Plugin plugin, CooldownManager cooldownManager, PvPManager pvpManager, Settings settings) {
        this.cooldownManager = cooldownManager;
        this.pvpManager = pvpManager;
        this.settings = settings;
        this.floodgateEnabled = plugin.getServer().getPluginManager().isPluginEnabled("floodgate");
        registerEntityResurrectEvent(plugin);
        registerEndCrystalEvent(plugin);
    }

    private void registerEntityResurrectEvent(Plugin plugin) {
        if (VersionUtils.isVersion(11)) {
            plugin.getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
                public void onResurrect(EntityResurrectEvent event) {
                    if (event.getEntityType() != EntityType.PLAYER) {
                        return;
                    }
                    Player player = (Player) event.getEntity();
                    long cooldownTime = settings.getTotemCooldown();
                    if (cooldownTime == 0 || pvpManager.isBypassed(player)) {
                        return;
                    }
                    if (cooldownTime <= -1) {
                        cancelEventIfInPvp(event, CooldownType.TOTEM, player);
                        return;
                    }
                    cooldownTime = cooldownTime * 1000;
                    if (checkCooldown(player, CooldownType.TOTEM, cooldownTime)) {
                        event.setCancelled(true);
                        return;
                    }
                    cooldownManager.addCooldown(player, CooldownType.TOTEM);
                    addItemCooldownIfNeeded(player, CooldownType.TOTEM);
                }
            }, plugin);
        }
    }

    // EntityPlaceEvent появился в 1.14
    private void registerEndCrystalEvent(Plugin plugin) {
        if (VersionUtils.isVersion(14)) {
            plugin.getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
                public void onEndCrystalPlace(EntityPlaceEvent event) {
                    if (event.getEntityType() != EntityType.END_CRYSTAL) return;

                    Player player = event.getPlayer();
                    if (player == null) return;
                    if (pvpManager.isBypassed(player)) return;

                    long cooldownTime = settings.getEndCrystalCooldown();
                    if (cooldownTime == 0) return;

                    if (cooldownTime <= -1) {
                        cancelEventIfInPvp(event, CooldownType.END_CRYSTAL, player);
                        return;
                    }

                    cooldownTime = cooldownTime * 1000;

                    if (checkCooldown(player, CooldownType.END_CRYSTAL, cooldownTime)) {
                        event.setCancelled(true);
                        return;
                    }

                    cooldownManager.addCooldown(player, CooldownType.END_CRYSTAL);
                    addItemCooldownIfNeeded(player, CooldownType.END_CRYSTAL);
                }
            }, plugin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemEat(PlayerItemConsumeEvent event) {
        ItemStack consumeItem = event.getItem();

        CooldownType cooldownType = null;
        long cooldownTime = 0;

        if (isChorus(consumeItem)) {
            cooldownType = CooldownType.CHORUS;
            cooldownTime = settings.getСhorusCooldown();
        }
        if (isGoldenOrEnchantedApple(consumeItem)) {
            boolean enchanted = isEnchantedGoldenApple(consumeItem);
            cooldownType = enchanted ? CooldownType.ENC_GOLDEN_APPLE : CooldownType.GOLDEN_APPLE;
            cooldownTime = enchanted ? settings.getEnchantedGoldenAppleCooldown() : settings.getGoldenAppleCooldown();
        }
        if (isDrinkablePotion(consumeItem)) {
            cooldownType = CooldownType.POTION;
            cooldownTime = settings.getPotionCooldown();
        }

        if (cooldownType != null) {
            if (cooldownTime == 0 || pvpManager.isBypassed(event.getPlayer())) {
                return;
            }
            if (cooldownTime <= -1) {
                cancelEventIfInPvp(event, cooldownType, event.getPlayer());
                return;
            }
            cooldownTime = cooldownTime * 1000;
            if (checkCooldown(event.getPlayer(), cooldownType, cooldownTime, consumeItem)) {
                event.setCancelled(true);
                return;
            }
            if (cooldownType == CooldownType.POTION) return;
            cooldownManager.addCooldown(event.getPlayer(), cooldownType);
            addItemCooldownIfNeeded(event.getPlayer(), cooldownType);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionConsumed(PlayerItemConsumeEvent event) {
        if (isDrinkablePotion(event.getItem())) {
            recordPotionUse(event.getPlayer(), event.getItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPerlLaunch(ProjectileLaunchEvent e) {
        if (settings.getEnderPearlCooldown() > 0 && e.getEntityType() == EntityType.ENDER_PEARL && e.getEntity().getShooter() instanceof Player) {
            Player p = (Player) e.getEntity().getShooter();
            if (!pvpManager.isBypassed(p)) {
                cooldownManager.addCooldown(p, CooldownType.ENDER_PEARL);
                addItemCooldownIfNeeded(p, CooldownType.ENDER_PEARL);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireworkLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Firework)) return;

        Player player;
        if (e.getEntity().getShooter() instanceof Player) {
            player = (Player) e.getEntity().getShooter();
        } else {
            // При использовании с элитрой shooter может быть null — ищем ближайшего игрока
            player = e.getEntity().getNearbyEntities(1, 1, 1).stream()
                    .filter(entity -> entity instanceof Player)
                    .map(entity -> (Player) entity)
                    .findFirst().orElse(null);
            if (player == null) return;
        }
        if (pvpManager.isBypassed(player)) return;

        long cooldownTime = settings.getFireworkCooldown();
        if (cooldownTime == 0) return;

        if (cooldownTime <= -1) {
            cancelEventIfInPvp(e, CooldownType.FIREWORK, player);
            if (pvpManager.isInPvP(player) && isBedrockPlayer(player)) {
                breakElytra(player);
            }
            return;
        }

        if (checkCooldown(player, CooldownType.FIREWORK, cooldownTime * 1000)) {
            e.setCancelled(true);
            return;
        }

        cooldownManager.addCooldown(player, CooldownType.FIREWORK);
        addItemCooldownIfNeeded(player, CooldownType.FIREWORK);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof ThrownPotion)) return;
        if (!(e.getEntity().getShooter() instanceof Player)) return;

        Player player = (Player) e.getEntity().getShooter();
        if (pvpManager.isBypassed(player)) return;

        long cooldownTime = settings.getPotionCooldown();
        if (cooldownTime == 0) return;

        if (cooldownTime <= -1) {
            cancelEventIfInPvp(e, CooldownType.POTION, player);
            return;
        }

        ItemStack potion = ((ThrownPotion) e.getEntity()).getItem();
        if (checkCooldown(player, CooldownType.POTION, cooldownTime * 1000, potion)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionLaunched(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion)) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;

        recordPotionUse((Player) event.getEntity().getShooter(), ((ThrownPotion) event.getEntity()).getItem());
    }

    private void recordPotionUse(Player player, ItemStack potion) {
        if (settings.getPotionCooldown() > 0 && !pvpManager.isBypassed(player)) {
            cooldownManager.addPotionCooldown(player, potion);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (pvpManager.isBypassed(event.getPlayer())) return;

        // --- Остальные предметы требуют наличия итема в руке ---
        if (!event.hasItem()) return;

        Material itemType = event.getItem().getType();

        if (settings.getEnderPearlCooldown() != 0 && itemType == Material.ENDER_PEARL) {
            if (settings.getEnderPearlCooldown() <= -1) {
                cancelEventIfInPvp(event, CooldownType.ENDER_PEARL, event.getPlayer());
                return;
            }
            if (checkCooldown(event.getPlayer(), CooldownType.ENDER_PEARL, settings.getEnderPearlCooldown() * 1000)) {
                event.setCancelled(true);
            }
        } else if (isFirework(event.getItem()) && settings.getFireworkCooldown() != 0) {
            long cooldownTime = settings.getFireworkCooldown();
            if (cooldownTime <= -1) {
                cancelEventIfInPvp(event, CooldownType.FIREWORK, event.getPlayer());
                if (pvpManager.isInPvP(event.getPlayer()) && isBedrockPlayer(event.getPlayer())) {
                    breakElytra(event.getPlayer());
                }
                return;
            }
            if (checkCooldown(event.getPlayer(), CooldownType.FIREWORK, cooldownTime * 1000)) {
                event.setCancelled(true);
                return;
            }
            cooldownManager.addCooldown(event.getPlayer(), CooldownType.FIREWORK);
            addItemCooldownIfNeeded(event.getPlayer(), CooldownType.FIREWORK);
        } else if (VersionUtils.isVersion(16) && settings.getRespawnAnchorCooldown() != 0
                && itemType == Material.RESPAWN_ANCHOR
                && event.getAction().name().startsWith("RIGHT_CLICK")) {
            long cooldownTime = settings.getRespawnAnchorCooldown();
            if (cooldownTime <= -1) {
                cancelEventIfInPvp(event, CooldownType.RESPAWN_ANCHOR, event.getPlayer());
                return;
            }
            if (checkCooldown(event.getPlayer(), CooldownType.RESPAWN_ANCHOR, cooldownTime * 1000)) {
                event.setCancelled(true);
                return;
            }
            cooldownManager.addCooldown(event.getPlayer(), CooldownType.RESPAWN_ANCHOR);
            addItemCooldownIfNeeded(event.getPlayer(), CooldownType.RESPAWN_ANCHOR);
        } else if ((isDrinkablePotion(event.getItem()) || isThrownPotionItem(event.getItem()))
                && settings.getPotionCooldown() != 0 && event.getAction().name().startsWith("RIGHT_CLICK")) {
            long cooldownTime = settings.getPotionCooldown();
            if (cooldownTime <= -1) {
                cancelEventIfInPvp(event, CooldownType.POTION, event.getPlayer());
                return;
            }
            if (checkCooldown(event.getPlayer(), CooldownType.POTION, cooldownTime * 1000, event.getItem())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldownManager.remove(event.getPlayer());
        restoreElytra(event.getPlayer());
    }

    @EventHandler
    public void onPvpStart(PvpStartedEvent event) {
        switch (event.getPvpStatus()) {
            case ALL_NOT_IN_PVP:
                cooldownManager.enteredToPvp(event.getDefender());
                cooldownManager.enteredToPvp(event.getAttacker());
                break;
            case ATTACKER_IN_PVP:
                cooldownManager.enteredToPvp(event.getDefender());
                break;
            case DEFENDER_IN_PVP:
                cooldownManager.enteredToPvp(event.getAttacker());
                break;
        }
    }

    @EventHandler
    public void onPvpStop(PvpStoppedEvent event) {
        cooldownManager.removedFromPvp(event.getPlayer());
        restoreElytra(event.getPlayer());
    }

    private boolean isChorus(ItemStack itemStack) {
        return VersionUtils.isVersion(9) && itemStack.getType() == Material.CHORUS_FRUIT;
    }

    private boolean isGoldenOrEnchantedApple(ItemStack itemStack) {
        return isGoldenApple(itemStack) || isEnchantedGoldenApple(itemStack);
    }

    private boolean isGoldenApple(ItemStack itemStack) {
        return itemStack.getType() == Material.GOLDEN_APPLE;
    }

    private boolean isEnchantedGoldenApple(ItemStack itemStack) {
        return (VersionUtils.isVersion(13) && itemStack.getType() == Material.ENCHANTED_GOLDEN_APPLE)
                || (isGoldenApple(itemStack) && itemStack.getDurability() >= 1);
    }

    public boolean isFirework(ItemStack itemStack) {
        return VersionUtils.isVersion(13) ? itemStack.getType() == Material.FIREWORK_ROCKET : itemStack.getType() == Material.getMaterial("FIREWORK");
    }

    private boolean isDrinkablePotion(ItemStack itemStack) {
        return itemStack.getType() == Material.POTION;
    }

    private boolean isThrownPotionItem(ItemStack itemStack) {
        return VersionUtils.isVersion(9) && (itemStack.getType() == Material.SPLASH_POTION || itemStack.getType() == Material.LINGERING_POTION);
    }

    public void cancelEventIfInPvp(Cancellable event, CooldownType type, Player player) {
        if (pvpManager.isInPvP(player)) {
            event.setCancelled(true);
            String message = type == CooldownType.TOTEM ? settings.getMessages().getTotemDisabledInPvp() :
                    settings.getMessages().getItemDisabledInPvp();
            if (!message.isEmpty()) {
                player.sendMessage(Utils.color(message));
            }
        }
    }

    private boolean checkCooldown(Player player, CooldownType cooldownType, long cooldownTime) {
        return checkCooldown(player, cooldownType, cooldownTime, null);
    }

    private boolean checkCooldown(Player player, CooldownType cooldownType, long cooldownTime, ItemStack item) {
        boolean cooldownActive = !pvpManager.isPvPModeEnabled() || pvpManager.isInPvP(player);
        if (!cooldownActive) return false;

        long remaining;
        if (cooldownType == CooldownType.POTION) {
            remaining = cooldownManager.getPotionRemaining(player, item, cooldownTime);
        } else if (cooldownManager.hasCooldown(player, cooldownType, cooldownTime)) {
            remaining = cooldownManager.getRemaining(player, cooldownType, cooldownTime);
        } else {
            return false;
        }
        if (remaining > 0) {
            int remainingInt = (int) TimeUnit.MILLISECONDS.toSeconds(remaining);
            String message = cooldownType == CooldownType.TOTEM ? settings.getMessages().getTotemCooldown() :
                    settings.getMessages().getItemCooldown();
            if (!message.isEmpty()) {
                player.sendMessage(Utils.color(Utils.replaceTime(message.replace("%time%",
                        Math.round(remaining / 1000) + ""), remainingInt)));
            }
            return true;
        }
        return false;
    }

    private void addItemCooldownIfNeeded(Player player, CooldownType cooldownType) {
        if (pvpManager.isPvPModeEnabled()) {
            if (pvpManager.isInPvP(player)) {
                cooldownManager.addItemCooldown(player, cooldownType, cooldownType.getCooldown(settings) * 1000);
            }
        } else {
            cooldownManager.addItemCooldown(player, cooldownType, cooldownType.getCooldown(settings) * 1000);
        }
    }

    private boolean isBedrockPlayer(Player player) {
        if (!floodgateEnabled) return false;
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    private void breakElytra(Player player) {
        // Не ломаем повторно если уже сломана этим плагином
        if (brokenElytraPlayers.containsKey(player.getUniqueId())) return;

        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate == null || chestplate.getType() != Material.ELYTRA) return;
        ItemMeta meta = chestplate.getItemMeta();
        if (!(meta instanceof Damageable)) return;

        // Сохраняем оригинал (клон с исходным дамагом)
        brokenElytraPlayers.put(player.getUniqueId(), chestplate.clone());

        ((Damageable) meta).setDamage(chestplate.getType().getMaxDurability() - 1);
        chestplate.setItemMeta(meta);
        player.getInventory().setChestplate(chestplate);
        player.updateInventory();
    }

    private void restoreElytra(Player player) {
        ItemStack original = brokenElytraPlayers.remove(player.getUniqueId());
        if (original == null) return;

        int brokenDamage = original.getType().getMaxDurability() - 1;
        int originalDamage = ((Damageable) original.getItemMeta()).getDamage();

        // Проверяем слот нагрудника первым
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
            ItemMeta meta = chestplate.getItemMeta();
            if (meta instanceof Damageable && ((Damageable) meta).getDamage() == brokenDamage) {
                ((Damageable) meta).setDamage(originalDamage);
                chestplate.setItemMeta(meta);
                player.getInventory().setChestplate(chestplate);
                player.updateInventory();
                return;
            }
        }

        // Если перенесли в основной инвентарь — явно ищем по слотам и применяем setItem
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != Material.ELYTRA) continue;
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable)) continue;
            if (((Damageable) meta).getDamage() == brokenDamage) {
                ((Damageable) meta).setDamage(originalDamage);
                item.setItemMeta(meta);
                player.getInventory().setItem(i, item);
                player.updateInventory();
                return;
            }
        }
        // Если не нашли (выбросил/отдал) — запись уже удалена, ничего не делаем
    }
}
