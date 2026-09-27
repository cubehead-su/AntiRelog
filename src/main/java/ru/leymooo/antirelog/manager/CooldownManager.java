package ru.leymooo.antirelog.manager;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.util.PotionCooldowns;
import ru.leymooo.antirelog.util.VersionUtils;
import ru.leymooo.antirelog.util.VisualCooldownUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class CooldownManager {

    private final Antirelog plugin;
    private final Settings settings;
    private final boolean potionGroupsSupported;
    private final Table<Player, CooldownType, Long> cooldowns = HashBasedTable.create();
    private final Table<Player, ItemStack, Long> potionCooldowns = HashBasedTable.create();
    private final Table<Player, CooldownType, Long> itemCooldowns = HashBasedTable.create();
    private final Map<Player, PotionCooldowns> potionItemCooldowns = new HashMap<>();
    private BukkitTask itemCooldownTask;

    public CooldownManager(Antirelog plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.potionGroupsSupported = VersionUtils.getMajorVersion() > 21 || VersionUtils.isVersion(21, 2);
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
        if (potionGroupsSupported) return PotionCooldowns.key(potion);
        ItemStack key = potion.clone();
        key.setAmount(1);
        return key;
    }

    public void addPotionItemCooldown(Player player, ItemStack potion, long duration) {
        if (!potionGroupsSupported || duration <= 0) return;
        PotionCooldowns visuals = potionItemCooldowns.computeIfAbsent(player, ignored -> new PotionCooldowns(plugin));
        long now = System.currentTimeMillis();
        visuals.add(potion, now + duration);
        startItemCooldownTask();
    }

    public void restorePotion(ItemStack potion) {
        if (potionGroupsSupported) PotionCooldowns.restore(potion);
    }

    public void addItemCooldown(Player player, CooldownType type, long duration) {
        if (!VersionUtils.isVersion(11)) return;
        if (type == CooldownType.POTION && settings.getPotionCooldown() >= 0) return;
        if (duration <= 0) {
            removeItemCooldown(player, type);
            return;
        }

        long expiresAt = System.currentTimeMillis() + duration;
        itemCooldowns.put(player, type, expiresAt);
        int durationInTicks = toTicks(duration);
        for (Material material : type.getAllMaterials()) {
            player.setCooldown(material, durationInTicks);
        }
        if (type == CooldownType.POTION) {
            if (potionGroupsSupported) {
                potionItemCooldowns.computeIfAbsent(player, ignored -> new PotionCooldowns(plugin)).blockAll(expiresAt);
            }
            if (plugin.getServer().getPluginManager().isPluginEnabled("VisualCooldown")) {
                VisualCooldownUtils.setPotionCooldown(player, durationInTicks);
            }
        }
        startItemCooldownTask();
    }

    public void removeItemCooldown(Player player, CooldownType type) {
        if (!VersionUtils.isVersion(11)) return;

        if (type == CooldownType.POTION) {
            PotionCooldowns visuals = potionItemCooldowns.remove(player);
            if (visuals != null) visuals.clear(player);
        }
        if (itemCooldowns.remove(player, type) == null) return;
        for (Material material : type.getAllMaterials()) {
            player.setCooldown(material, 0);
        }
        if (type == CooldownType.POTION && plugin.getServer().getPluginManager().isPluginEnabled("VisualCooldown")) {
            VisualCooldownUtils.setPotionCooldown(player, 0);
        }
    }

    private void startItemCooldownTask() {
        if (itemCooldownTask == null || itemCooldownTask.isCancelled()) {
            itemCooldownTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickItemCooldowns, 1L, 1L);
        }
    }

    private void tickItemCooldowns() {
        long now = System.currentTimeMillis();
        for (Table.Cell<Player, CooldownType, Long> cell : new ArrayList<>(itemCooldowns.cellSet())) {
            Player player = cell.getRowKey();
            CooldownType type = cell.getColumnKey();
            long remaining = cell.getValue() - now;
            if (!player.isOnline() || remaining <= 0) {
                removeItemCooldown(player, type);
            } else if (type == CooldownType.POTION) {
                int ticks = toTicks(remaining);
                for (Material material : type.getAllMaterials()) {
                    if (Math.abs((long) player.getCooldown(material) - ticks) > 2) {
                        player.setCooldown(material, ticks);
                    }
                }
            }
        }
        for (Player player : new ArrayList<>(potionItemCooldowns.keySet())) {
            PotionCooldowns visuals = potionItemCooldowns.get(player);
            if (!player.isOnline() || !visuals.synchronize(player, now)) {
                visuals.clear(player);
                potionItemCooldowns.remove(player);
            }
        }
        if (itemCooldowns.isEmpty() && potionItemCooldowns.isEmpty()) {
            itemCooldownTask.cancel();
            itemCooldownTask = null;
        }
    }

    private static int toTicks(long duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(Math.max(0, duration) / 50.0));
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
        long duration = settings.getPotionCooldown() * 1000L;
        if (duration > 0) {
            long now = System.currentTimeMillis();
            potionCooldowns.row(player).forEach((potion, added) -> addPotionItemCooldown(player, potion, duration - (now - added)));
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
        return (System.currentTimeMillis() - added) < duration;
    }

    public long getRemaining(Player player, CooldownType type, long duration) {
        Long added = cooldowns.get(player, type);
        return added == null ? 0 : Math.max(0, duration - (System.currentTimeMillis() - added));
    }

    public void remove(Player player) {
        removedFromPvp(player);
        cooldowns.row(player).clear();
        potionCooldowns.row(player).clear();
    }

    public void clearAll() {
        Set<Player> players = new HashSet<>(itemCooldowns.rowKeySet());
        players.addAll(potionItemCooldowns.keySet());
        players.forEach(this::removedFromPvp);
        if (itemCooldownTask != null) {
            itemCooldownTask.cancel();
            itemCooldownTask = null;
        }
        cooldowns.clear();
        potionCooldowns.clear();
    }

    public Settings getSettings() {
        return settings;
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
