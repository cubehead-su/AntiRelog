package ru.leymooo.antirelog.manager;

import com.comphenix.protocol.events.PacketContainer;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.util.ProtocolLibUtils;
import ru.leymooo.antirelog.util.VersionUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class CooldownManager {

    private final Antirelog plugin;
    private final Settings settings;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Table<Player, CooldownType, Long> cooldowns = HashBasedTable.create();
    private final Table<Player, ItemStack, Long> potionCooldowns = HashBasedTable.create();
    private final Table<Player, CooldownType, CooldownRemoval> removalTasks = HashBasedTable.create();

    public CooldownManager(Antirelog plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        if (plugin.isProtocolLibEnabled()) {
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        } else {
            scheduledExecutorService = null;
        }
    }

    public void addCooldown(Player player, CooldownType type) {
        cooldowns.put(player, type, System.currentTimeMillis());
    }

    public void addPotionCooldown(Player player, ItemStack potion) {
        long now = System.currentTimeMillis();
        long duration = settings.getPotionCooldown() * 1000L;
        potionCooldowns.row(player).values().removeIf(added -> now - added >= duration);
        potionCooldowns.put(player, potionKey(potion), now);
    }

    public long getPotionRemaining(Player player, ItemStack potion, long duration) {
        Long added = potionCooldowns.get(player, potionKey(potion));
        return added == null ? 0 : Math.max(0, duration - (System.currentTimeMillis() - added));
    }

    private ItemStack potionKey(ItemStack potion) {
        ItemStack key = potion.clone();
        key.setAmount(1);
        return key;
    }

    public void addItemCooldown(Player player, CooldownType type, long duration) {
        if (!VersionUtils.isVersion(11)) return;
        if (type == CooldownType.POTION && settings.getPotionCooldown() >= 0) return;

        cancelRemovalTask(player, type, false);

        int durationInTicks = (int) Math.ceil(duration / 50.0);
        for (Material material : type.getAllMaterials()) {
            player.setCooldown(material, durationInTicks);
        }

        if (scheduledExecutorService == null) return;

        CooldownRemoval removal = new CooldownRemoval();
        ScheduledFuture<?> future = scheduledExecutorService.schedule(() -> {
            if (!plugin.isEnabled()) return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (removalTasks.get(player, type) == removal) {
                    removeItemCooldown(player, type);
                }
            });
        }, duration, TimeUnit.MILLISECONDS);
        removal.setFuture(future);
        removalTasks.put(player, type, removal);
    }

    public void removeItemCooldown(Player player, CooldownType type) {
        if (!VersionUtils.isVersion(11)) return;

        cancelRemovalTask(player, type, false);
        for (Material material : type.getAllMaterials()) {
            player.setCooldown(material, 0);
        }
    }

    private void cancelRemovalTask(Player player, CooldownType type, boolean mayInterruptIfRunning) {
        CooldownRemoval removal = removalTasks.remove(player, type);
        if (removal != null) {
            removal.cancel(mayInterruptIfRunning);
        }
    }

    public void enteredToPvp(Player player) {
        for (CooldownType cooldownType : CooldownType.values) {
            int cooldown = cooldownType.getCooldown(settings);
            if (cooldown == 0) {
                continue;
            }
            if (cooldown > 0 && hasCooldown(player, cooldownType, cooldown * 1000)) {
                addItemCooldown(player, cooldownType, getRemaining(player, cooldownType, cooldown * 1000));
            }
            if (cooldown < 0) {
                addItemCooldown(player, cooldownType, 300 * 1000);
            }
        }
    }

    public void removedFromPvp(Player player) {
        for (CooldownType cooldownType : CooldownType.values) {
            int cooldown = cooldownType.getCooldown(settings);
            if (cooldown < 0) {
                removeItemCooldown(player, cooldownType);
            } else if (cooldown > 0 && hasCooldown(player, cooldownType, cooldown * 1000)) {
                removeItemCooldown(player, cooldownType);
            }
        }
    }

    public boolean hasCooldown(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        if (added == null) {
            return false;
        }
        return (System.currentTimeMillis() - added) < duration;
    }

    public long getRemaining(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        return duration - (System.currentTimeMillis() - added);
    }

    public void remove(Player player) {
        cooldowns.row(player).clear();
        potionCooldowns.row(player).clear();
        removalTasks.row(player).forEach((ignore, removal) -> removal.cancel(false));
        removalTasks.row(player).clear();
    }

    public void clearAll() {
        removalTasks.rowMap().forEach((player, tasks) -> tasks.forEach((type, removal) -> {
            removal.cancel(true);
            for (Material material : type.getAllMaterials()) {
                player.setCooldown(material, 0);
            }
        }));
        removalTasks.clear();
        cooldowns.clear();
        potionCooldowns.clear();
    }

    public Settings getSettings() {
        return settings;
    }

    private static final class CooldownRemoval {
        private ScheduledFuture<?> future;

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        private void cancel(boolean mayInterruptIfRunning) {
            if (future != null && !future.isCancelled()) {
                future.cancel(mayInterruptIfRunning);
            }
        }
    }

    public enum CooldownType {
        GOLDEN_APPLE(Material.GOLDEN_APPLE, Settings::getGoldenAppleCooldown),
        ENC_GOLDEN_APPLE(VersionUtils.isVersion(13) ? Material.ENCHANTED_GOLDEN_APPLE : Material.GOLDEN_APPLE, Settings::getEnchantedGoldenAppleCooldown),
        ENDER_PEARL(Material.ENDER_PEARL, Settings::getEnderPearlCooldown),
        CHORUS(Material.matchMaterial("CHORUS_FRUIT"), Settings::getСhorusCooldown),
        TOTEM(VersionUtils.isVersion(13) ? Material.TOTEM_OF_UNDYING : Material.matchMaterial("TOTEM"), Settings::getTotemCooldown),
        FIREWORK(VersionUtils.isVersion(13) ? Material.FIREWORK_ROCKET : Material.matchMaterial("FIREWORK"), Settings::getFireworkCooldown),
        RESPAWN_ANCHOR(VersionUtils.isVersion(16) ? Material.RESPAWN_ANCHOR : Material.OBSIDIAN, Settings::getRespawnAnchorCooldown),
        END_CRYSTAL(Material.END_CRYSTAL, Settings::getEndCrystalCooldown),
        POTION(Material.POTION, Settings::getPotionCooldown, additionalPotionMaterials());

        public static CooldownType[] values = values();

        Material material;
        Material[] additionalMaterials;
        Function<Settings, Integer> cooldown;

        CooldownType(Material material, Function<Settings, Integer> cooldown) {
            this(material, cooldown, new Material[0]);
        }

        CooldownType(Material material, Function<Settings, Integer> cooldown, Material[] additionalMaterials) {
            this.material = material;
            this.cooldown = cooldown;
            this.additionalMaterials = additionalMaterials;
        }

        // Взрывные и туманные зелья появились в 1.9, до этого сплэш-зелье было тем же Material.POTION
        private static Material[] additionalPotionMaterials() {
            return VersionUtils.isVersion(9) ? new Material[]{Material.SPLASH_POTION, Material.LINGERING_POTION} : new Material[0];
        }

        public int getCooldown(Settings settings) {
            return cooldown.apply(settings);
        }

        public Material getMaterial() {
            return material;
        }

        public Material[] getAllMaterials() {
            if (additionalMaterials.length == 0) {
                return new Material[]{material};
            }
            Material[] all = new Material[additionalMaterials.length + 1];
            all[0] = material;
            System.arraycopy(additionalMaterials, 0, all, 1, additionalMaterials.length);
            return all;
        }
    }
}
