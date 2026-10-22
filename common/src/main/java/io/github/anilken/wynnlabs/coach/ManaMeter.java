package io.github.anilken.wynnlabs.coach;

import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.Wyn;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures your real mana while you play and tunes the damage model with it.
 *
 * <p>Every half second it reads mana from Wynntils. Stretches of at least four seconds where
 * mana only rises (no spell cast) and is not full give the regeneration rate; the cap gives
 * the pool. What your gear adds (Mana Regen, Max Mana) is taken out, so the rest is the base
 * the model needs. Once enough stretches agree it writes rules.model.baseManaPer5s and
 * baseMana and says so in chat. Reading only; nothing is sent.
 */
public final class ManaMeter {

    private static final List<Double> RATES = new ArrayList<>();   // mana per 5 s, one per stretch
    private static int tick;
    private static long runStart = -1;
    private static int runStartMana;
    private static int lastMana = -1;
    private static int maxSeen;
    private static long lastTuned;

    private ManaMeter() {}

    public static void reset() {
        RATES.clear();
        runStart = -1;
        lastMana = -1;
        maxSeen = 0;
    }

    public static void onTick(Minecraft mc) {
        if (++tick % 10 != 0 || mc.player == null) {
            return;
        }
        try {
            var mana = Wyn.mana();
            if (mana.isEmpty()) {
                return;
            }
            int cur = mana.get().current();
            int max = mana.get().max();
            long now = System.currentTimeMillis();
            maxSeen = Math.max(maxSeen, max);
            if (lastMana < 0 || cur < lastMana || cur >= max) {
                // a cast (mana dropped) or full: close the current stretch
                closeRun(now, lastMana);
                runStart = cur < max ? now : -1;
                runStartMana = cur;
            } else if (runStart < 0) {
                runStart = now;
                runStartMana = cur;
            }
            lastMana = cur;
            maybeTune(mc);
        } catch (Throwable ignored) {
            // measuring must never break the game
        }
    }

    private static void closeRun(long now, int endMana) {
        if (runStart < 0 || endMana < 0) {
            return;
        }
        double secs = (now - runStart) / 1000.0;
        int gained = endMana - runStartMana;
        if (secs >= 4 && gained > 0) {
            RATES.add(gained / secs * 5.0);
            if (RATES.size() > 40) {
                RATES.remove(0);
            }
        }
        runStart = -1;
    }

    private static void maybeTune(Minecraft mc) {
        if (!Settings.on("manaMeter")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (RATES.size() < 6 || now - lastTuned < 120_000) {
            return;
        }
        List<Double> sorted = new ArrayList<>(RATES);
        sorted.sort(Double::compare);
        double median = sorted.get(sorted.size() / 2);
        // What the worn gear adds is not "base"
        double gearRegen = 0;
        double gearMax = 0;
        Build.Plan plan = Build.plan();
        if (plan != null) {
            for (Damage.Piece p : plan.worn().values()) {
                if (p != null) {
                    gearRegen += p.stats().getOrDefault("manaRegen", 0.0);
                    gearMax += p.stats().getOrDefault("rawMaxMana", 0.0) + p.stats().getOrDefault("maxMana", 0.0);
                }
            }
        }
        double baseRegen = Math.max(1, median - gearRegen);
        double basePool = Math.max(20, maxSeen - gearMax);
        double oldRegen = Rules.sub("model", "baseManaPer5s", 25);
        double oldPool = Rules.sub("model", "baseMana", 100);
        lastTuned = now;
        if (Math.abs(baseRegen - oldRegen) / oldRegen < 0.1 && Math.abs(basePool - oldPool) / oldPool < 0.1) {
            return;                             // the model already matches
        }
        JsonObject model = new JsonObject();
        model.addProperty("baseManaPer5s", Math.round(baseRegen * 10) / 10.0);
        model.addProperty("baseMana", Math.round(basePool));
        model.addProperty("manaMeasuredAt", java.time.Instant.now().toString());
        JsonObject change = new JsonObject();
        change.add("model", model);
        Rules.update(change);
        if (mc.player != null) {
            mc.player.displayClientMessage(L.c("wynnlabs.mana.chat_prefix").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(L.c("wynnlabs.mana.tuned", Math.round(median), maxSeen).withStyle(ChatFormatting.GRAY)), false);
        }
    }
}
