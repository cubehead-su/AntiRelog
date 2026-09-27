package ru.leymooo.antirelog.util;

import fun.cubehead.visualcooldown.api.CooldownCategory;
import fun.cubehead.visualcooldown.api.VisualCooldown;
import org.bukkit.entity.Player;
import java.util.Map;

public final class VisualCooldownUtils {
    private VisualCooldownUtils() {
    }

    public static void setPotionCooldown(Player player, int ticks) {
        VisualCooldown.getApi().ifPresent(api -> api.startCooldown(player, CooldownCategory.POTION, ticks));
    }

    public static boolean syncPotionCooldowns(Player player, Map<String, Integer> cooldowns) {
        return VisualCooldown.getApi().map(api -> api.syncPotionCooldowns(player, cooldowns)).orElse(false);
    }
}
