package io.github.anilken.wynnlabs;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Satış özeti: what the SAT verdicts in your bag add up to — how many go to the Blacksmith,
 * how many to the Trade Market and roughly what those fetch (lowest listing). When the bag is
 * nearly full a one-line reminder shows above the hotbar (once per 10 minutes).
 */
final class SellSummary {

    record Summary(int free, List<String> blacksmith, List<String> market, double marketValue) {}

    private static int tick;
    private static long warnedAt;

    private SellSummary() {}

    static Summary compute(Minecraft mc) {
        var inv = mc.player.getInventory();
        int free = 0;
        List<String> bs = new ArrayList<>();
        List<String> tm = new ArrayList<>();
        double value = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) {
                free++;
                continue;
            }
            Advice.Entry e = AutoAdvice.cached(st);
            if (e == null || e.verdict() != Verdict.SELL) {
                continue;
            }
            String name = ItemJson.clean(st.getHoverName().getString());
            String where = Advice.sellPlace(e);
            if ("Trade Market".equals(where)) {
                tm.add(name);
                Prices.Price p = Prices.of(name);
                if (p != null && p.typical() != null) {
                    value += p.typical();
                }
            } else {
                bs.add(name);
            }
        }
        return new Summary(free, bs, tm, value);
    }

    static void onTick(Minecraft mc) {
        if (mc.player == null || ++tick % 100 != 0 || !Settings.on("sellSummary")) {
            return;
        }
        try {
            Summary s = compute(mc);
            long now = System.currentTimeMillis();
            if (s.free() > 4 || s.blacksmith().size() + s.market().size() == 0 || now - warnedAt < 10 * 60_000L) {
                return;
            }
            warnedAt = now;
            mc.gui.setOverlayMessage(L.c("wynnlabs.sell.bag_filling", s.free(), s.blacksmith().size(),
                    s.market().size(), s.marketValue() > 0 ? " (~" + Prices.fmt(s.marketValue()) + ")" : "")
                    .withStyle(ChatFormatting.GOLD), false);
        } catch (Throwable ignored) {
        }
    }
}
