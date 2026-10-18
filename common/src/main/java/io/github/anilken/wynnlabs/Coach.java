package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * <li>Hasar ölçer: damage numbers shortly after each cast, against what the model expected;
 * after enough casts the model's spell damage is calibrated to what you really deal. Every
 * hit also goes to the live DPS meter ({@link DpsMeter}).</li>
 * <li>Mana koçu: casts that failed for mana while fighting.</li>
 * <li>Av: "/av &lt;eşya&gt;" counts kills and time until the item shows up in your bag, with
 * the market price to compare against.</li>
 * <li>Oturum raporu: what the session brought, saved when you leave, shown in Bugün.</li>
 * <li>Takıldın mı: the tracked quest's step unchanged for 10 minutes.</li>
 * <li>Burası düştü: the XP rate at your spot well below your best known spot.</li>
 * </ul>
 */
final class Coach {

    private Coach() {}

    // ---- toasts ----

    /** A one-line notice through Wynntils' notification overlay (falls back to the action bar). */
    static void toast(Component c) {
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
        if (left > 4.5 || now - warnedAt < 4000) {
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
    static void renderHud(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
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

    private static long castAt;
    private static double castDamage;
    private static boolean castOpen;

    /** A spell was cast (Wynntils' event, through {@link WynntilsEvents}). */
    static void onCast() {
        closeCast();
        castAt = System.currentTimeMillis();
        castDamage = 0;
        castOpen = true;
    }

    /** Damage numbers shown: each value is one damage type of the hit. */
    static void onDamage(java.util.Collection<Long> damages) {
        long hit = 0;
        for (Long v : damages) {
            hit += v;
        }
        DpsMeter.LIVE.hit(hit);                 // every hit counts for the live meter
        if (!castOpen || System.currentTimeMillis() - castAt > 1500) {
            return;
        }
        castDamage += hit;
    }

    /** A cast's window is over: book its damage (casts that hit nothing are not counted). */
    private static void closeCast() {
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
        // what the model expected for one cast, without the calibration we apply ourselves
        Build.Plan plan = Build.plan();
        if (plan != null && plan.current() != null && plan.current().dmgPerCast() > 0) {
            double cal = Rules.sub("model", "spellCalibration", 1.0);
            double model = plan.current().dmgPerCast() / cal;
            m.addProperty("modelPerCast", model);
            if (n + 1 >= 30 && (n + 1) % 10 == 0) {
                JsonObject change = calibrationChange(cal, avg, model);
                if (change != null) {
                    Rules.update(change);           // only a real change: every write rebuilds all advice
                }
            }
        }
        saveMeasured(m);
    }

    private static JsonObject measuredCache;
    private static String measuredFor = "";

    /**
     * The rules change that moves the spell calibration to what was measured, or null when the
     * rounded value is the one already stored. Calibrates gently and within bounds: summons and
     * damage over time land outside the cast window.
     */
    static JsonObject calibrationChange(double current, double avgPerCast, double modelPerCast) {
        double ratio = Math.max(0.7, Math.min(1.4, avgPerCast / modelPerCast));
        double rounded = Math.round(ratio * 100) / 100.0;
        if (Math.abs(rounded - current) < 1e-9) {
            return null;
        }
        JsonObject change = new JsonObject();
        JsonObject model2 = new JsonObject();
        model2.addProperty("spellCalibration", rounded);
        change.add("model", model2);
        return change;
    }

    static JsonObject measured() {
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
    static void onManaFail() {
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

    static int manaFailsLast5Min() {
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
    static void onXp() {
        if (huntTarget != null) {
            huntKills++;                        // one XP gain per kill, near enough
        }
    }

    /** "/av Durum's Serenity" starts, "/av" alone stops. Returns the line to show. */
    static String hunt(String target) {
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
    static void saveSession(long now) {
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
    static List<String> sessionTips(JsonObject last) {
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
    static JsonObject lastSession() {
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

    static void onTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            watchHealth(mc, now);
            if (castOpen && now - castAt > 1500) {
                closeCast();
            }
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
