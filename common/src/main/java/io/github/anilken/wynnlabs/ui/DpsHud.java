package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.coach.DpsMeter;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The live DPS meter as a small card in the top-right corner (setting dpsHud, off by default):
 * the DPS of the last 10 seconds, the best 10 seconds of the session and the current fight's
 * damage. Hidden while a screen is open, with F1, and before you have dealt any damage.
 */
public final class DpsHud {

    private DpsHud() {}

    private static long textUntil;
    private static Component head;
    private static String best;
    private static String fight;
    private static int width;

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.on("dpsHud") || mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        DpsMeter.State s = DpsMeter.LIVE.state();
        if (s.sessionDamage() <= 0) {
            return;
        }
        Font font = UiFont.get();
        long now = System.currentTimeMillis();
        if (now >= textUntil) {
            // the numbers four times a second: steady to read, and no formatting every frame
            textUntil = now + 250;
            head = L.c("wynnlabs.dps.hud_dps", QuestAdvisor.fmt(Math.round(s.dps()))).withStyle(UiFont::bold);
            best = L.t("wynnlabs.dps.hud_best", QuestAdvisor.fmt(Math.round(s.best())));
            fight = L.t(s.fighting() ? "wynnlabs.dps.hud_fight" : "wynnlabs.dps.hud_last_fight",
                    QuestAdvisor.fmt(Math.round(s.fightDamage())), Math.round(s.fightSeconds()));
            width = Math.max(font.width(head), Math.max(font.width(best), font.width(fight))) + 10;
        }
        Component head = DpsHud.head;
        String best = DpsHud.best;
        String fight = DpsHud.fight;
        int w = width;
        int h = 34;
        int x = g.guiWidth() - w - 4;
        int y = 4;
        // below the game's status effect icons, which sit in the same corner
        if (mc.player.getActiveEffects().stream().anyMatch(e -> e.showIcon())) {
            y += 50;
        }
        g.fill(x, y, x + w, y + h, 0xA0101017);
        g.fill(x, y, x + 2, y + h, s.fighting() ? 0xFFEF9F27 : 0xFF5F5E5A);
        g.drawString(font, head, x + 6, y + 3, s.fighting() ? 0xFFFAC775 : 0xFFB4B2A9);
        g.drawString(font, best, x + 6, y + 13, 0xFF9C9A92);
        g.drawString(font, fight, x + 6, y + 23, 0xFF9C9A92);
    }
}
