package io.github.anilken.wynnlabs.coach;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.build.ClassProfiles;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.DamageReport;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.core.Words;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.game.WynntilsEvents;
import io.github.anilken.wynnlabs.game.WynntilsItems;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.quests.QuestTrail;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The live coach: things that watch the fight and the session as they happen, and speak only
 * when there is something to do. Reads Wynntils' models and events; never acts in the game.
 * <ul>
 * <li>Ölmeden önce uyarı: from how fast health is falling, "~2 sn'de ölürsün" with the
 * potions you carry (Wynntils already draws a low-health vignette; this predicts).</li>
 * <li>Hasar ölçer: damage numbers shortly after each cast, less the summons' and main
 * attack's damage heard between casts, against what the model expected of that spell; after
 * enough casts each spell, and the summons, are calibrated to what you really deal. Every
 * hit also goes to the live DPS meter ({@link DpsMeter}).</li>
 * <li>Mana koçu: casts that failed for mana while fighting.</li>
 * <li>Av: "/av &lt;eşya&gt;" counts kills and time until the item shows up in your bag, with
 * the market price to compare against.</li>
 * <li>Oturum raporu: what the session brought, saved when you leave, shown in Bugün.</li>
 * <li>Takıldın mı: the tracked quest's step unchanged for 10 minutes.</li>
 * <li>Burası düştü: the XP rate at your spot well below your best known spot.</li>
 * </ul>
 */
public final class Coach {

    private Coach() {}

    // ---- toasts ----

    /** A one-line notice through Wynntils' notification overlay (falls back to the action bar). */
    public static void toast(Component c) {
        try {
            Wyn.toast(c);
        } catch (Throwable t) {
            Minecraft.getInstance().gui.setOverlayMessage(c, false);
        }
    }

    // ---- survival: warn before dying ----

    private record Sample(long time, int hp) {}

    private static final Deque<Sample> HP = new ArrayDeque<>();
    private static long warnUntil;
    private static long warnedAt;
    private static String warnText = "";

    private static void watchHealth(Minecraft mc, long now) {
        var health = Wyn.health();
        if (health.isEmpty() || !Settings.on("survivalWarn")) {
            HP.clear();
            return;
        }
        int hp = health.get().current();
        int max = Math.max(1, health.get().max());
        HP.addLast(new Sample(now, hp));
        while (!HP.isEmpty() && now - HP.peekFirst().time() > 2500) {
            HP.removeFirst();
        }
        if (HP.size() < 5 || hp <= 0 || hp > max * 0.6) {
            return;
        }
        // damage only: sum the drops, ignore heals, over the window
        double lost = 0;
        Sample prev = null;
        for (Sample s : HP) {
            if (prev != null && s.hp() < prev.hp()) {
                lost += prev.hp() - s.hp();
            }
            prev = s;
        }
        double seconds = (now - HP.peekFirst().time()) / 1000.0;
        if (seconds < 1 || lost <= 0) {
            return;
        }
        double rate = lost / seconds;
        double left = hp / rate;
        // in a place the danger check calls risky, warn earlier: more time to react (the cheap
        // checks first: the danger check walks every grind spot)
        if (left > 4.5) {
            return;
        }
        // in a raid: quiet, and not every few seconds (health drops fast there all the time)
        boolean raid = Wyn.inRaid();
        if (!SurvivalPace.due(now, warnedAt, raid)) {
            return;
        }
        double limit = Danger.riskHere(mc) >= 2 ? 4.5 : 3;
        if (left > limit) {
            return;
        }
        warnedAt = now;
        warnUntil = now + 2500;
        int potions = potions(mc);
        int slot = potionSlot(mc);
        String secs = String.format(Locale.ROOT, "%.1f", left);
        warnText = potions <= 0 ? L.t("wynnlabs.coach.warn_no_potion", secs)
                : slot >= 0 ? L.t("wynnlabs.coach.warn_potion_slot", secs, slot + 1)
                : L.t("wynnlabs.coach.warn_potion_bag", secs);
        if (!SurvivalPace.sound(raid)) {
            return;
        }
        try {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS, 0.7f));
        } catch (Throwable ignored) {
        }
    }

    /** The hotbar slot (0-8) holding a healing potion, or -1. */
    static int potionSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (ItemJson.clean(inv.getItem(i).getHoverName().getString()).contains("Healing")) {
                return i;
            }
        }
        return -1;
    }

    /** Healing potion uses in the bag ("[+300 ❤] Potions of Healing [29/30]" counts 29). */
    static int potions(Minecraft mc) {
        int uses = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            String n = ItemJson.clean(inv.getItem(i).getHoverName().getString());
            if (!n.contains("Healing")) {
                continue;
            }
            var m = java.util.regex.Pattern.compile("\\[(\\d+)/\\d+]").matcher(n);
            uses += m.find() ? Integer.parseInt(m.group(1)) : inv.getItem(i).getCount();
        }
        return uses;
    }

    /** The HUD: the death warning, and the hunt line when a hunt is on. */
    public static void renderHud(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        long now = System.currentTimeMillis();
        var font = mc.font;
        if (now < warnUntil && !warnText.isEmpty()) {
            if (!warnText.equals(warnShown)) {          // built once per warning, not every frame
                warnShown = warnText;
                warnLine = Component.literal(warnText).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
                warnWidth = font.width(warnLine);
            }
            Component c = warnLine;
            int w = warnWidth;
            int x = (g.guiWidth() - w) / 2;
            int y = g.guiHeight() / 2 - 40;
            g.fill(x - 6, y - 4, x + w + 6, y + 12, 0xB0200000);
            g.renderOutline(x - 6, y - 4, w + 12, 16, 0xFFE24B4A);
            g.drawString(font, c, x, y, 0xFFFFFFFF);
        }
        if (huntTarget != null) {
            long min = (now - huntStart) / 60000;
            // the line changes with a kill, a minute or the price: built then, not every frame
            String said = huntTarget + "|" + huntKills + "|" + min + "|" + huntPrice + "|" + L.code();
            if (!said.equals(huntSaid)) {
                huntSaid = said;
                String price = huntPrice == null ? "" : L.t("wynnlabs.coach.hud_price", Prices.fmt(huntPrice));
                huntLine = L.c("wynnlabs.coach.hud_hunt").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(huntTarget).withStyle(ChatFormatting.WHITE))
                        .append(L.cn("wynnlabs.coach.hud_progress", huntKills, huntKills, min, price).withStyle(ChatFormatting.GRAY));
            }
            g.drawString(font, huntLine, 6, 6, 0xFFFFFFFF);
        }
    }

    private static String warnShown;
    private static Component warnLine;
    private static int warnWidth;
    private static String huntSaid;
    private static Component huntLine;

    // ---- damage meter: what a cast really deals ----

    // A cast's window: the damage numbers in it are the cast's (1.5 s; a spell that ticks keeps it
    // open for its duration, up to 6 s, or until the next cast). Every hit outside a window while
    // fighting is the background (summons, Totem, the main attack): its rate is measured, and
    // what it would have dealt during a window is taken off that cast.
    private static long castAt;
    private static long castUntil;
    private static double castDamage;
    private static boolean castOpen;
    private static int castHits;
    private static String castName;
    private static double castExpected;     // the model's whole cast, uncalibrated, 0 when unknown
    private static double castDuration;
    private static boolean castLasting;
    private static int castMana;
    private static long lastHitAt;
    private static long bgTickAt;
    private static double bgDamage;
    private static double bgMillis;

    /** Casts and fight seconds the per-spell and summon calibrations wait for, and widen after. */
    public static final int CAL_CASTS = 30;
    public static final int STEADY_CASTS = 50;
    static final double STEADY_SECONDS = 300;

    /**
     * A spell was cast (Wynntils' event, through {@link WynntilsEvents}): its number (1-4, 0
     * unknown), its name and the mana it took.
     */
    public static void onCast(int spell, String name, int mana) {
        long now = System.currentTimeMillis();
        closeCast(now);
        castAt = now;
        castUntil = now + 1500;
        castDamage = 0;
        castHits = 0;
        castName = name == null || name.isEmpty() ? "spell " + spell : name;
        castExpected = 0;
        castDuration = 0;
        castLasting = false;
        castMana = mana;
        castOpen = true;
        // what the model expects of this spell now, before the calibration we apply ourselves
        try {
            Damage.Detail d = DamageReport.base();
            Damage.SpellPart sp = d == null ? null : spellPart(d.breakdown(), spell);
            if (sp != null) {
                castName = sp.name();
                castExpected = sp.perCast();
                castDuration = sp.duration();
                castLasting = sp.lasting();
                if (!sp.lasting() && sp.duration() > 0) {
                    castUntil = now + (long) Math.max(1500, Math.min(6000, sp.duration() * 1000 + 500));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** The model's spell of this number (1-4) that deals damage, or null. */
    static Damage.SpellPart spellPart(Damage.Breakdown b, int index) {
        for (Damage.SpellPart sp : b.spells()) {
            if (sp.index() == index && index > 0) {
                return sp;
            }
        }
        return null;
    }

    /** Damage numbers shown: each value is one damage type of the hit. */
    public static void onDamage(java.util.Collection<Long> damages) {
        long hit = 0;
        for (Long v : damages) {
            hit += v;
        }
        DpsMeter.LIVE.hit(hit);                 // every hit counts for the live meter
        long now = System.currentTimeMillis();
        lastHitAt = now;
        if (castOpen && now <= castUntil) {
            castDamage += hit;
            castHits++;
        } else {
            bgDamage += hit;
        }
    }

    /** Fight time outside a cast window, for the background rate; folded in now and then. */
    private static void watchBackground(long now) {
        long dt = bgTickAt == 0 ? 0 : Math.min(250, now - bgTickAt);
        bgTickAt = now;
        if (!castOpen && lastHitAt > 0 && now - lastHitAt < 3000) {
            bgMillis += dt;
        }
        if (bgMillis >= 5000) {
            JsonObject m = measured();
            bookBackground(m, bgDamage, bgMillis / 1000.0);
            bgDamage = 0;
            bgMillis = 0;
            saveMeasured(m);
        }
    }

    /** A cast's window is over: book its damage (casts that hit nothing are not counted). */
    private static void closeCast(long now) {
        if (!castOpen) {
            return;
        }
        castOpen = false;
        if (castDamage <= 0) {
            return;
        }
        JsonObject m = measured();
        int n = m.has("casts") ? m.get("casts").getAsInt() : 0;
        double avg = m.has("avgPerCast") ? m.get("avgPerCast").getAsDouble() : 0;
        avg = n == 0 ? castDamage : avg + (castDamage - avg) / Math.min(n + 1, 200);   // running mean, last ~200
        m.addProperty("casts", n + 1);
        m.addProperty("avgPerCast", avg);
        Damage.Detail d = DamageReport.base();
        double perCast = d == null ? 0 : modelPerCast(d.breakdown());
        if (perCast > 0) {
            m.addProperty("modelPerCast", perCast);
        }
        double window = Math.max(0.05, (Math.min(now, castUntil) - castAt) / 1000.0);
        book(m, castName, castDamage, castHits, castMana, window, castLasting ? 0 : castExpected, castDuration,
                d != null && d.breakdown().summonRaw()[0] > 0);
        if (d != null && n + 1 >= CAL_CASTS && (n + 1) % 10 == 0) {
            // the whole modelled main attack comes off the background (the meter hears every hit),
            // so what is left is compared with the model's summons
            JsonObject change = calibrationChanges(m, Rules.get().getAsJsonObject("model"), d.breakdown());
            if (change != null) {
                Rules.update(change);           // only a real change: every write rebuilds all advice
            }
        }
        saveMeasured(m);
    }

    /** The model's average cast before calibration: the spells cast in turn, by their shares. */
    public static double modelPerCast(Damage.Breakdown b) {
        double sum = 0;
        double shares = 0;
        for (Damage.SpellPart sp : b.spells()) {
            if (!sp.lasting()) {
                sum += sp.share() * sp.perCast();
                shares += sp.share();
            }
        }
        return shares > 0 ? sum / shares : 0;
    }

    /**
     * Books one cast's window: the background's share of it taken off, and compared with the
     * model's whole cast ({@code modelCast}, 0 when unknown or lasting) as far as a ticking spell
     * lands within the window. With summons around, a cast is compared only once the background
     * is known (20 s of fighting between casts), as until then their hits would count as the spell's.
     */
    public static void book(JsonObject m, String spell, double raw, int hits, int mana, double windowSeconds, double modelCast,
            double duration, boolean summonsAround) {
        JsonObject bg = m.has("background") ? m.getAsJsonObject("background") : null;
        double bgSeconds = bg == null ? 0 : bg.get("seconds").getAsDouble();
        double net = net(raw, backgroundRate(m), windowSeconds);
        double expected = modelCast <= 0 || summonsAround && bgSeconds < 20 ? 0
                : modelCast * inWindow(windowSeconds, duration);
        bookCast(m, spell, raw, net, hits, expected, mana);
    }

    /** A window's damage less what the background would have dealt in it. */
    public static double net(double windowDamage, double backgroundPerSecond, double windowSeconds) {
        return Math.max(0, windowDamage - Math.max(0, backgroundPerSecond) * windowSeconds);
    }

    /** The share of a ticking spell's damage that lands within a window this long (1 for one go). */
    public static double inWindow(double windowSeconds, double duration) {
        return duration <= 0 || windowSeconds >= duration ? 1 : windowSeconds / duration;
    }

    /** Damage a second between casts while fighting, 0 before any was measured. */
    public static double backgroundRate(JsonObject m) {
        JsonObject bg = m.has("background") ? m.getAsJsonObject("background") : null;
        return bg == null || bg.get("seconds").getAsDouble() < 5 ? 0 : bg.get("perSecond").getAsDouble();
    }

    /** Folds fight seconds outside cast windows and the damage in them into the background rate (last ~10 min). */
    public static void bookBackground(JsonObject m, double damage, double seconds) {
        if (seconds <= 0) {
            return;
        }
        JsonObject bg = m.has("background") ? m.getAsJsonObject("background") : new JsonObject();
        double total = (bg.has("seconds") ? bg.get("seconds").getAsDouble() : 0) + seconds;
        double rate = bg.has("perSecond") ? bg.get("perSecond").getAsDouble() : 0;
        rate += (damage / seconds - rate) * seconds / Math.min(total, 600);
        bg.addProperty("seconds", total);
        bg.addProperty("perSecond", rate);
        m.add("background", bg);
    }

    /**
     * Books one cast under measured.spells.&lt;name&gt;: the window's damage, what is left of it
     * after the background, its hits and mana, and when the model's value for it is known
     * ({@code expected} &gt; 0) the ratio of the two, cast by cast, so a gear change does not make
     * the old casts wrong. Running means over the last ~200 casts, with the ratio's spread.
     */
    static void bookCast(JsonObject m, String spell, double raw, double net, int hits, double expected, int mana) {
        JsonObject all = m.has("spells") ? m.getAsJsonObject("spells") : new JsonObject();
        JsonObject s = all.has(spell) ? all.getAsJsonObject(spell) : new JsonObject();
        int n = (s.has("casts") ? s.get("casts").getAsInt() : 0) + 1;
        double k = Math.min(n, 200);
        s.addProperty("casts", n);
        s.addProperty("raw", mean(s, "raw", raw, k));
        s.addProperty("perCast", mean(s, "perCast", net, k));
        s.addProperty("hits", mean(s, "hits", hits, k));
        if (mana > 0) {
            s.addProperty("mana", mean(s, "mana", mana, k));
        }
        if (expected > 0) {
            int rn = (s.has("ratioCasts") ? s.get("ratioCasts").getAsInt() : 0) + 1;
            double rk = Math.min(rn, 200);
            double x = net / expected;
            double old = s.has("ratio") ? s.get("ratio").getAsDouble() : x;
            double mean = rn == 1 ? x : old + (x - old) / rk;
            double var = s.has("ratioVar") ? s.get("ratioVar").getAsDouble() : 0;
            var = rn == 1 ? 0 : var + ((x - old) * (x - mean) - var) / rk;
            s.addProperty("ratioCasts", rn);
            s.addProperty("ratio", mean);
            s.addProperty("ratioVar", Math.max(0, var));
            s.addProperty("model", expected);
            // steady: enough casts, casts not all over the place (a few huge ones among nothing is a
            // window catching something else) and a mean that no longer moves much; looser to
            // lose than to win
            double cv = mean > 0 ? Math.sqrt(Math.max(0, var)) / mean : 9;
            double sem = cv / Math.sqrt(rk);
            boolean steady = s.has("steady") && s.get("steady").getAsBoolean();
            steady = rn >= STEADY_CASTS && (steady ? sem <= 0.2 && cv <= 1.3 : sem <= 0.1 && cv <= 1.0);
            s.addProperty("steady", steady);
        }
        all.add(spell, s);
        m.add("spells", all);
    }

    private static double mean(JsonObject s, String key, double x, double k) {
        return s.has(key) ? s.get(key).getAsDouble() + (x - s.get(key).getAsDouble()) / k : x;
    }

    /**
     * The rules change the measurements call for, or null: each spell's own calibration
     * (rules.model.spells[i].cal), the summons' (summonCalibration: the background less the whole
     * modelled main attack, over the summons and lasting spells the model has) and the overall
     * spellCalibration the spells without their own fall back to. Kept within 0.7-1.4 until steady
     * (50 casts, or 5 minutes of fighting for summons), then 0.5-10 (summons 0.05-10); a value is
     * written only when it moves by a tenth or more, as each write rebuilds all advice.
     */
    public static JsonObject calibrationChanges(JsonObject m, JsonObject model, Damage.Breakdown b) {
        JsonObject all = m.has("spells") ? m.getAsJsonObject("spells") : new JsonObject();
        JsonObject out = new JsonObject();
        double globalCal = model != null && model.has("spellCalibration") ? model.get("spellCalibration").getAsDouble() : 1.0;
        // each spell on its own
        if (model != null && model.has("spells") && model.get("spells").isJsonArray()) {
            JsonArray spells = model.getAsJsonArray("spells").deepCopy();
            boolean changed = false;
            for (var e : spells) {
                JsonObject sp = e.getAsJsonObject();
                JsonObject meas = sp.has("name") && all.has(sp.get("name").getAsString())
                        ? all.getAsJsonObject(sp.get("name").getAsString()) : null;
                if (meas == null || ClassProfiles.lasting(sp) || !meas.has("ratioCasts")
                        || meas.get("ratioCasts").getAsInt() < CAL_CASTS) {
                    continue;
                }
                boolean steady = meas.has("steady") && meas.get("steady").getAsBoolean();
                double target = bounded(meas.get("ratio").getAsDouble(), steady);
                double current = sp.has("cal") ? sp.get("cal").getAsDouble() : globalCal;
                if (moved(current, target)) {
                    sp.addProperty("cal", target);
                    changed = true;
                }
            }
            if (changed) {
                JsonObject m2 = new JsonObject();
                m2.add("spells", spells);
                out.add("model", m2);
            }
        }
        // the overall fallback: the spells' ratios by their casts
        double sum = 0;
        int casts = 0;
        for (var e : all.entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            if (s.has("ratioCasts")) {
                int c = Math.min(200, s.get("ratioCasts").getAsInt());
                sum += s.get("ratio").getAsDouble() * c;
                casts += c;
            }
        }
        if (casts >= CAL_CASTS) {
            JsonObject g = calibrationChange(globalCal, sum / casts, 1.0);
            if (g != null) {
                model2(out).add("spellCalibration", g.getAsJsonObject("model").get("spellCalibration"));
            }
        }
        // the summons, from the background between casts
        JsonObject bg = m.has("background") ? m.getAsJsonObject("background") : null;
        double summons = b == null ? 0 : b.summonRaw()[0];
        if (bg != null && summons > 0 && bg.get("seconds").getAsDouble() >= 60) {
            // the meter hears every main attack hit: it comes off at its full modelled rate, not
            // at the goal's weight (a score preference)
            double main = b.mainTotal();
            double target = boundedSummon(Math.max(0, bg.get("perSecond").getAsDouble() - main) / summons,
                    bg.get("seconds").getAsDouble() >= STEADY_SECONDS);
            double current = model != null && model.has("summonCalibration") ? model.get("summonCalibration").getAsDouble() : 1.0;
            if (moved(current, target)) {
                model2(out).addProperty("summonCalibration", target);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static JsonObject model2(JsonObject change) {
        if (!change.has("model")) {
            change.add("model", new JsonObject());
        }
        return change.getAsJsonObject("model");
    }

    /** A ratio rounded and kept within bounds: 0.7-1.4 until it is steady, then 0.5-10. */
    public static double bounded(double ratio, boolean steady) {
        double r = steady ? Math.max(0.5, Math.min(10, ratio)) : Math.max(0.7, Math.min(1.4, ratio));
        return Math.round(r * 100) / 100.0;
    }

    /**
     * The summons' calibration rounded and kept within bounds: 0.7-1.4 until it is steady, then
     * 0.05-10, as summons that die or miss can be heard far below what the model gives them.
     */
    public static double boundedSummon(double ratio, boolean steady) {
        double r = steady ? Math.max(0.05, Math.min(10, ratio)) : Math.max(0.7, Math.min(1.4, ratio));
        return Math.round(r * 100) / 100.0;
    }

    /** Worth writing: a tenth or more away from the stored value. */
    private static boolean moved(double current, double target) {
        return Math.abs(target - current) >= 0.1 * Math.max(0.1, current) - 1e-9;
    }

    private static JsonObject measuredCache;
    private static String measuredFor = "";

    /**
     * The rules change that moves the overall spell calibration to what was measured, or null
     * when the rounded value is not a tenth away from the one already stored. Kept within
     * 0.7-1.4: it is the fallback for spells without a calibration of their own.
     */
    public static JsonObject calibrationChange(double current, double avgPerCast, double modelPerCast) {
        double rounded = bounded(avgPerCast / modelPerCast, false);
        if (!moved(current, rounded)) {
            return null;
        }
        JsonObject change = new JsonObject();
        JsonObject model2 = new JsonObject();
        model2.addProperty("spellCalibration", rounded);
        change.add("model", model2);
        return change;
    }

    public static JsonObject measured() {
        if (!Profile.id().equals(measuredFor) || measuredCache == null) {
            measuredFor = Profile.id();
            measuredCache = new JsonObject();
            try {
                Path f = Profile.dir().resolve("measured.json");
                if (Files.exists(f)) {
                    measuredCache = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                }
            } catch (Exception ignored) {
            }
        }
        return measuredCache;
    }

    private static long measuredSavedAt;

    private static void saveMeasured(JsonObject m) {
        long now = System.currentTimeMillis();
        if (now - measuredSavedAt < 30_000) {
            return;                             // at most twice a minute
        }
        measuredSavedAt = now;
        try {
            Files.writeString(Profile.dir().resolve("measured.json"), m.toString());
        } catch (Exception ignored) {
        }
    }

    // ---- mana coach ----

    private static final Deque<Long> MANA_FAILS = new ArrayDeque<>();
    private static long manaToldAt;

    /** A cast failed for want of mana. */
    public static void onManaFail() {
        long now = System.currentTimeMillis();
        MANA_FAILS.addLast(now);
        while (!MANA_FAILS.isEmpty() && now - MANA_FAILS.peekFirst() > 5 * 60_000) {
            MANA_FAILS.removeFirst();
        }
        if (MANA_FAILS.size() >= 8 && now - manaToldAt > 15 * 60_000 && Settings.on("coachToasts")) {
            manaToldAt = now;
            toast(L.c("wynnlabs.coach.mana_short", MANA_FAILS.size()).withStyle(ChatFormatting.AQUA));
        }
    }

    public static int manaFailsLast5Min() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (long t : MANA_FAILS) {
            if (now - t <= 5 * 60_000) {
                n++;
            }
        }
        return n;
    }

    // ---- hunt ----

    private static String huntTarget;
    private static long huntStart;
    private static int huntKills;
    private static Double huntPrice;

    /** Combat XP gained (once per kill, near enough). */
    public static void onXp() {
        if (huntTarget != null) {
            huntKills++;                        // one XP gain per kill, near enough
        }
    }

    /** "/av Durum's Serenity" starts, "/av" alone stops. Returns the line to show. */
    public static String hunt(String target) {
        if (target == null || target.isBlank()) {
            String was = huntTarget;
            huntTarget = null;
            return was == null ? L.t("wynnlabs.coach.hunt_none") : L.n("wynnlabs.coach.hunt_ended", huntKills, was, huntKills);
        }
        huntTarget = target.trim();
        huntStart = System.currentTimeMillis();
        huntKills = 0;
        Prices.Price p = Prices.of(huntTarget);
        huntPrice = p == null ? null : p.typical();
        StringBuilder sb = new StringBuilder(L.t("wynnlabs.coach.hunt_started", huntTarget));
        try {
            var info = WynntilsItems.gearByName(huntTarget);
            if (info != null) {
                List<String> from = new ArrayList<>();
                for (var o : WynntilsItems.obtain(info)) {
                    from.add(o.type().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + o.name().map(n -> " " + n).orElse(""));
                }
                if (!from.isEmpty()) {
                    sb.append(L.t("wynnlabs.coach.hunt_from", String.join(", ", from)));
                }
            }
        } catch (Throwable ignored) {
        }
        if (huntPrice != null) {
            sb.append(L.t("wynnlabs.coach.hunt_price", Prices.fmt(huntPrice)));
        }
        return sb.toString();
    }

    private static void watchHunt(Minecraft mc, long now) {
        if (huntTarget == null) {
            return;
        }
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && ItemJson.clean(st.getHoverName().getString()).toLowerCase(Locale.ROOT)
                    .contains(huntTarget.toLowerCase(Locale.ROOT))) {
                long min = (now - huntStart) / 60000;
                toast(L.cn("wynnlabs.coach.hunt_dropped", huntKills, huntTarget, huntKills, min)
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                huntTarget = null;
                return;
            }
        }
    }

    // ---- session report ----

    private static long sessionStart;
    private static int startLevel;
    private static double startPct;
    private static int startEmeralds;
    private static String sessionFor = "";

    private static void watchSession(Minecraft mc, long now) {
        if (!Profile.settled()) {
            return;
        }
        if (!Profile.id().equals(sessionFor)) {
            if (!sessionFor.isEmpty()) {
                saveSession(now);               // switched character: close the last one's session
            }
            DpsMeter.LIVE.reset();
            sessionFor = Profile.id();
            sessionStart = now;
            startLevel = Wyn.level();
            startPct = Wyn.xp().percentInt();
            startEmeralds = emeralds();
        }
    }

    private static int emeralds() {
        try {
            return Wyn.emeralds();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Called when leaving the server (and on character switch): writes last-session.json. */
    public static void saveSession(long now) {
        if (sessionFor.isEmpty() || now - sessionStart < 5 * 60_000) {
            return;
        }
        try {
            JsonObject o = new JsonObject();
            int level = Wyn.level();
            double pct = Wyn.xp().percentInt();
            o.addProperty("start", sessionStart);
            o.addProperty("end", now);
            o.addProperty("levels", Math.round(((level + pct / 100.0) - (startLevel + startPct / 100.0)) * 100) / 100.0);
            o.addProperty("xp", XpMeter.sessionXp());
            o.addProperty("emeralds", emeralds() - startEmeralds);
            int deaths = 0;
            Words worst = null;
            java.util.Map<Words, Integer> where = new java.util.HashMap<>();
            for (DeathCoach.Death d : DeathCoach.all()) {
                if (d.time() >= sessionStart) {
                    deaths++;
                    where.merge(d.placeWords(), 1, Integer::sum);
                }
            }
            for (var e : where.entrySet()) {
                if (worst == null || e.getValue() > where.get(worst)) {
                    worst = e.getKey();
                }
            }
            if (worst != null && worst.text().isEmpty()) {
                worst = null;
            }
            o.addProperty("deaths", deaths);
            o.addProperty("worstArea", worst == null ? "" : worst.text());
            o.addProperty("manaFails", MANA_FAILS.size());
            List<Words> tips = new ArrayList<>();
            if (deaths >= 2) {
                tips.add(worst == null ? Words.of("wynnlabs.coach.tip_deaths", deaths)
                        : Words.of("wynnlabs.coach.tip_deaths_at", worst));
            }
            if (MANA_FAILS.size() >= 8) {
                tips.add(Words.of("wynnlabs.coach.tip_mana"));
            }
            double rate = XpMeter.sessionRate();
            if (rate > 0) {
                tips.add(Words.of("wynnlabs.coach.tip_rate", QuestAdvisor.fmt(Math.round(rate))));
            }
            // the tips as language keys, worded when shown; "tips" worded too, for older versions
            var notes = new com.google.gson.JsonArray();
            var arr = new com.google.gson.JsonArray();
            for (Words t : tips) {
                notes.add(t.json());
                arr.add(t.text());
            }
            o.add("notes", notes);
            o.add("tips", arr);
            Files.writeString(Profile.dir().getParent().resolve(sessionFor).resolve("last-session.json"), o.toString());
        } catch (Exception e) {
            System.out.println("[WynnLabs] session report failed: " + e);
        }
    }

    private static final List<String> TIP_KEYS = List.of("wynnlabs.coach.tip_deaths", "wynnlabs.coach.tip_deaths_at",
            "wynnlabs.coach.tip_mana", "wynnlabs.coach.tip_rate");

    /**
     * A saved session's tips in the language the mod speaks now: from "notes" (language keys),
     * or in a file from an older version from "tips", matched back to their keys (a tip no key
     * matches stays as it was).
     */
    public static List<String> sessionTips(JsonObject last) {
        List<String> out = new ArrayList<>();
        if (last == null) {
            return out;
        }
        if (last.has("notes")) {
            for (var n : last.getAsJsonArray("notes")) {
                Words w = Words.fromJson(n);
                if (w != null) {
                    out.add(w.text());
                }
            }
        } else if (last.has("tips")) {
            for (var t : last.getAsJsonArray("tips")) {
                Words w = Words.unword(t.getAsString(), TIP_KEYS);
                if (w != null && w.key().equals("wynnlabs.coach.tip_deaths_at")) {
                    Words near = Words.unword(String.valueOf(w.args().get(0)), List.of("wynnlabs.death.area_near"));
                    if (near != null) {
                        w = Words.of(w.key(), near);  // the place was "near X", worded then too
                    }
                }
                out.add(w != null ? w.text() : t.getAsString());
            }
        }
        return out;
    }

    /** The last saved session of this character, or null. */
    public static JsonObject lastSession() {
        try {
            Path f = Profile.dir().resolve("last-session.json");
            return Files.exists(f) ? JsonParser.parseString(Files.readString(f)).getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- stuck on a quest step ----

    private static String stepText = "";
    private static long stepSince;
    private static long stuckToldAt;

    private static void watchQuest(long now) {
        try {
            if (!Wyn.tracking()) {
                stepText = "";
                return;
            }
            String step = Wyn.trackedName() + "|" + Wyn.trackedTask();
            if (!step.equals(stepText)) {
                stepText = step;
                stepSince = now;
                return;
            }
            if (now - stepSince > 10 * 60_000 && now - stuckToldAt > 20 * 60_000 && Settings.on("coachToasts")) {
                stuckToldAt = now;
                toast(L.c("wynnlabs.coach.stuck", QuestGuides.title(Wyn.trackedName())).withStyle(ChatFormatting.YELLOW));
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean stuck() {
        return !stepText.isEmpty() && System.currentTimeMillis() - stepSince > 10 * 60_000;
    }

    // ---- move on: this spot has dried up ----

    private static long spotToldAt;

    private static void watchSpot(Minecraft mc, long now) {
        if (now - spotToldAt < 10 * 60_000 || !Settings.on("coachToasts")) {
            return;
        }
        try {
            if (Wyn.tracking()) {
                return;                         // questing: XP rates are not the point
            }
        } catch (Throwable ignored) {
        }
        double recent = XpMeter.recentRate();
        String here = XpMeter.spotHereName(mc);
        if (recent <= 0 || here == null) {
            return;
        }
        String best = null;
        double bestRate = 0;
        int level = Wyn.level();
        for (Grind.Spot s : Grind.spots()) {
            double r = XpMeter.rate(s.name());
            if (r > bestRate && !s.name().equals(here) && Math.abs(s.level() - level) <= 6) {
                bestRate = r;
                best = s.name();
            }
        }
        if (best != null && recent < bestRate * 0.7) {
            spotToldAt = now;
            toast(L.c("wynnlabs.coach.spot_dropped", QuestAdvisor.fmt(Math.round(recent)), best,
                    QuestAdvisor.fmt(Math.round(bestRate))).withStyle(ChatFormatting.GOLD));
        }
    }

    // ---- tick ----

    private static int tick;

    public static void onTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            watchHealth(mc, now);
            if (castOpen && now > castUntil) {
                closeCast(now);
            }
            watchBackground(now);
            if (++tick % 20 == 0) {
                watchHunt(mc, now);
                watchSession(mc, now);
                watchQuest(now);
                QuestTrail.watch(mc);
            }
            if (tick % 200 == 0) {
                watchSpot(mc, now);
            }
        } catch (Throwable ignored) {
        }
    }
}
