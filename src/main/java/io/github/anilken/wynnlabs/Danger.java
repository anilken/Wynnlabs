package io.github.anilken.wynnlabs;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Tehlike uyarısı: when you walk into an area that is too much for you (the nearest cave,
 * dungeon, altar or grind spot is above your level once its difficulty, your effective HP and
 * your deaths there are counted), a one-line warning shows above the hotbar. Once per place
 * per 10 minutes. Reads only.
 */
final class Danger {

    private static final Map<String, Long> WARNED = new HashMap<>();
    private static int tick;

    private Danger() {}

    /** 0 safe, 1 careful, 2 risky for a player of this level. */
    static int risk(Grind.Spot s, int level) {
        int eff = s.level() + Grind.hardness(s) - level;
        return eff <= 0 ? 0 : eff <= 2 ? 1 : 2;
    }

    /** Risk of the nearest place within 120 blocks for you now (0 when none is near). */
    static int riskHere(Minecraft mc) {
        try {
            int level = Wyn.level();
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            Grind.Spot best = null;
            double bestD = 120;
            for (Grind.Spot s : Grind.spots()) {
                double d = Math.hypot(s.x() - px, s.z() - pz);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            return best == null ? 0 : risk(best, level);
        } catch (Throwable t) {
            return 0;
        }
    }

    static void onTick(Minecraft mc) {
        if (mc.player == null || ++tick % 40 != 0 || !Settings.on("dangerWarn")) {
            return;
        }
        try {
            int level = Wyn.level();
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            Grind.Spot near = Grind.spots().stream()
                    .filter(s -> Math.hypot(s.x() - px, s.z() - pz) < 120)
                    .min(Comparator.comparingDouble(s -> Math.hypot(s.x() - px, s.z() - pz)))
                    .orElse(null);
            if (near == null || risk(near, level) < 2) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = WARNED.get(near.name());
            if (last != null && now - last < 10 * 60_000L) {
                return;
            }
            WARNED.put(near.name(), now);
            int deaths = DeathCoach.deathsNear(near.x(), near.z());
            String msg = deaths >= 2
                    ? L.t("wynnlabs.danger.warn_deaths", near.name(), near.level(), Grind.kindName(near), deaths)
                    : L.t("wynnlabs.danger.warn", near.name(), near.level(), Grind.kindName(near));
            Coach.toast(Component.literal(msg).withStyle(ChatFormatting.RED));
        } catch (Throwable ignored) {
        }
    }
}
