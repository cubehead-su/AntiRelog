package ru.leymooo.antirelog.util;

import fun.cubehead.visualcooldown.api.CooldownCategory;
import fun.cubehead.visualcooldown.api.VisualCooldown;
import org.bukkit.entity.Player;

public final class VisualCooldownUtils {
    private VisualCooldownUtils() {
    }

    public static void setPotionCooldown(Player player, int ticks) {
        VisualCooldown.getApi().ifPresent(api -> api.startCooldown(player, CooldownCategory.POTION, ticks));
    }
}
