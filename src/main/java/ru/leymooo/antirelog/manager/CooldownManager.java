package ru.leymooo.antirelog.manager;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.util.PotionCooldowns;
import ru.leymooo.antirelog.util.VersionUtils;
import ru.leymooo.antirelog.util.VisualCooldownUtils;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

public class CooldownManager {

    private final Antirelog plugin;
    private final Settings settings;
    private final LongSupplier clock;
    private final Table<Player, CooldownType, Long> cooldowns = HashBasedTable.create();
    private final Table<Player, CooldownType, ItemCooldown> itemCooldowns = HashBasedTable.create();

    public CooldownManager(Antirelog plugin, Settings settings) {
        this(plugin, settings, System::currentTimeMillis);
    }

    CooldownManager(Antirelog plugin, Settings settings, LongSupplier clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.clock = clock;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickItemCooldowns, 1L, 1L);
    }

    public void addCooldown(Player player, CooldownType type) {
        cooldowns.put(player, type, clock.getAsLong());
    }

    public void addItemCooldown(Player player, CooldownType type, long duration) {
        addItemCooldown(player, type, duration, null);
    }

    public void addItemCooldown(Player player, CooldownType type, long duration, ItemStack usedItem) {
        if (!VersionUtils.isVersion(11)) return;
        if (duration <= 0) {
            removeItemCooldown(player, type);
            return;
        }

        ItemCooldown previous = itemCooldowns.get(player, type);
        PotionCooldowns potions = previous == null ? null : previous.potions;
        if (type == CooldownType.POTION && VersionUtils.isVersion(21, 2) && potions == null) {
            potions = new PotionCooldowns();
        }
        ItemCooldown cooldown = new ItemCooldown(clock.getAsLong() + duration, potions);
        itemCooldowns.put(player, type, cooldown);
        int durationInTicks = toTicks(duration);
        for (Material material : type.getAllMaterials()) {
            player.setCooldown(material, durationInTicks);
        }
        if (potions != null) {
            potions.track(usedItem);
            potions.synchronize(player, durationInTicks);
        }
        sendBedrockCooldown(player, type, durationInTicks);
    }

    public void removeItemCooldown(Player player, CooldownType type) {
        if (!VersionUtils.isVersion(11)) return;

        ItemCooldown cooldown = itemCooldowns.remove(player, type);
        if (cooldown == null) return;
        for (Material material : type.getAllMaterials()) {
            player.setCooldown(material, 0);
        }
        if (cooldown.potions != null) {
            cooldown.potions.clear(player);
        }
        sendBedrockCooldown(player, type, 0);
    }

    private void tickItemCooldowns() {
        long now = clock.getAsLong();
        for (Table.Cell<Player, CooldownType, ItemCooldown> cell : new ArrayList<>(itemCooldowns.cellSet())) {
            Player player = cell.getRowKey();
            CooldownType type = cell.getColumnKey();
            ItemCooldown cooldown = cell.getValue();
            if (itemCooldowns.get(player, type) != cooldown) continue;
            long remaining = cooldown.expiresAt - now;
            if (remaining <= 0) {
                removeItemCooldown(player, type);
            } else if (type == CooldownType.POTION) {
                int ticks = toTicks(remaining);
                for (Material material : type.getAllMaterials()) {
                    if (Math.abs(player.getCooldown(material) - ticks) > 2) {
                        player.setCooldown(material, ticks);
                    }
                }
                if (cooldown.potions != null) {
                    cooldown.potions.synchronize(player, ticks);
                }
            }
        }
    }

    private void sendBedrockCooldown(Player player, CooldownType type, int ticks) {
        if (type == CooldownType.POTION && plugin.getServer().getPluginManager().isPluginEnabled("VisualCooldown")) {
            VisualCooldownUtils.setPotionCooldown(player, ticks);
        }
    }

    private static int toTicks(long duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(duration / 50.0));
    }

    public void enteredToPvp(Player player) {
        for (CooldownType cooldownType : CooldownType.values) {
            int cooldown = cooldownType.getCooldown(settings);
            if (cooldown == 0) {
                continue;
            }
            if (cooldown > 0 && hasCooldown(player, cooldownType, cooldown * 1000L)) {
                addItemCooldown(player, cooldownType, getRemaining(player, cooldownType, cooldown * 1000L));
            }
            if (cooldown < 0) {
                addItemCooldown(player, cooldownType, 300 * 1000);
            }
        }
    }

    public void removedFromPvp(Player player) {
        for (CooldownType cooldownType : CooldownType.values) {
            removeItemCooldown(player, cooldownType);
        }
    }

    public boolean hasCooldown(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        if (added == null) {
            return false;
        }
        return (clock.getAsLong() - added) < duration;
    }

    public long getRemaining(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        return added == null ? 0 : Math.max(0, duration - (clock.getAsLong() - added));
    }

    public void remove(Player player) {
        removedFromPvp(player);
        cooldowns.row(player).clear();
    }

    public void clearAll() {
        cooldowns.clear();
        for (Player player : new ArrayList<>(itemCooldowns.rowKeySet())) {
            removedFromPvp(player);
        }
    }

    public Settings getSettings() {
        return settings;
    }

    private static final class ItemCooldown {
        private final long expiresAt;
        private final PotionCooldowns potions;

        private ItemCooldown(long expiresAt, PotionCooldowns potions) {
            this.expiresAt = expiresAt;
            this.potions = potions;
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
