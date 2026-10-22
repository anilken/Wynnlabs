package io.github.anilken.wynnlabs.coach;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.core.Words;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.MenuWatcher;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.quests.Grind;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ölüm koçu: every death ("You have died at [x, y, z]" in chat) is saved with what led to it —
 * how fast the health went, the area and its level, the build's effective HP, potions left, the
 * mobs around — and the mod answers with one rule-based fix in chat. Deaths repeated in one
 * place mark that area risky for the grind list; a run of deaths nudges the model toward
 * survival (capped, like learning from swaps) and how fast you died raises the health floor
 * the road map and the build plan keep. Reads only; sends nothing.
 */
public final class DeathCoach {

    /**
     * One death. {@code area}: the territory, or with {@code near} the nearest spot's name ("near
     * X" is worded when shown). {@code advice}: the coach's lines as language keys, worded when
     * shown, so they follow the menu's language.
     */
    public record Death(long time, int x, int y, int z, int level, String area, boolean near, int areaLevel, double ehp,
            int hpMax, double secondsToZero, int potions, List<String> mobs, List<Words> advice) {

        Words placeWords() {
            return near ? Words.of("wynnlabs.death.area_near", area) : Words.literal(area);
        }

        /** The place in the language the mod speaks now ("" when unknown). */
        public String place() {
            return area.isEmpty() ? "" : placeWords().text();
        }

        /** The advice lines in the language the mod speaks now. */
        public List<String> adviceText() {
            List<String> out = new ArrayList<>(advice.size());
            advice.forEach(a -> out.add(a.text()));
            return out;
        }
    }

    /** Every line the coach gives, for matching advice an older version saved already worded. */
    private static final List<String> ADVICE_KEYS = List.of("wynnlabs.death.repeat_here", "wynnlabs.death.area_level",
            "wynnlabs.death.burst", "wynnlabs.death.add_defence", "wynnlabs.death.no_potions", "wynnlabs.death.one_off");

    private record Sample(long time, int hp, int max) {}

    private static final Pattern DIED = Pattern.compile("You have died at \\[(-?\\d+), (-?\\d+), (-?\\d+)]");
    private static final Pattern CHARGES = Pattern.compile("\\[(\\d+)/(\\d+)]");
    private static final Deque<Sample> RECENT = new ArrayDeque<>();
    private static final List<Death> DEATHS = new ArrayList<>();
    private static String loadedFor;
    private static int tick;
    private static volatile String pending;           // death line seen, handled on the next tick

    private DeathCoach() {}

    /** For the no-game preview: these deaths for the character in play. */
    public static void preview(List<Death> deaths) {
        loadedFor = Profile.id();
        DEATHS.clear();
        DEATHS.addAll(deaths);
    }

    public static List<Death> all() {
        load();
        return new ArrayList<>(DEATHS);
    }

    // ---- watching ----

    public static void onTick(Minecraft mc) {
        if (mc.player == null || ++tick % 2 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            Wyn.health().ifPresent(h -> RECENT.addLast(new Sample(now, h.current(), h.max())));
        } catch (Throwable ignored) {
        }
        while (!RECENT.isEmpty() && now - RECENT.peekFirst().time() > 12_000) {
            RECENT.removeFirst();
        }
        String line = pending;
        if (line != null) {
            pending = null;
            Matcher m = DIED.matcher(line);
            if (m.find()) {
                record(mc, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
        } else if (tick % 1200 == 0) {
            relearn(now);                       // once a minute: lessons age without a new death
        }
    }

    public static void onChat(Component message, boolean overlay) {
        if (overlay || message == null) {
            return;
        }
        String text = ItemJson.clean(message.getString());
        if (text.contains("You have died at")) {
            pending = text;
        }
    }

    private static void record(Minecraft mc, int x, int y, int z) {
        load();
        long now = System.currentTimeMillis();
        int level = Wyn.level();
        // how fast it went: from the highest health in the last 10 s down to nothing
        int hpMax = 0;
        long fullAt = now;
        int peak = -1;
        for (Sample s : RECENT) {
            hpMax = Math.max(hpMax, s.max());
            if (s.hp() >= peak) {
                peak = s.hp();
                fullAt = s.time();
            }
        }
        double seconds = peak <= 0 ? -1 : Math.max(0.1, (now - fullAt) / 1000.0);
        Build.Plan plan = Build.plan();
        double ehp = plan != null && plan.current() != null ? plan.current().ehp() : 0;
        String area = "";
        boolean near = false;
        try {
            String t = Wyn.territoryAt(mc.player.position());
            if (t != null) {
                area = t;
            }
        } catch (Throwable ignored) {
        }
        // the nearest known spot gives the area's level
        Grind.Spot spot = Grind.spots().stream()
                .min(java.util.Comparator.comparingDouble(s -> Math.hypot(s.x() - x, s.z() - z)))
                .orElse(null);
        int areaLevel = spot != null && Math.hypot(spot.x() - x, spot.z() - z) < 300 ? spot.level() : 0;
        if (area.isEmpty() && spot != null) {
            area = spot.name();                 // "near X", worded when shown
            near = true;
        }
        Death d = new Death(now, x, y, z, level, area, near, areaLevel, ehp, hpMax, seconds, potions(mc), mobs(mc),
                new ArrayList<>());
        d.advice().addAll(advise(d, mc));
        DEATHS.add(d);
        while (DEATHS.size() > 100) {
            DEATHS.remove(0);
        }
        save();
        learn();
        if (!d.advice().isEmpty()) {
            mc.gui.getChat().addMessage(L.c("wynnlabs.death.chat_prefix").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(String.join(" · ", d.adviceText())).withStyle(ChatFormatting.GRAY)));
        }
    }

    /** Healing potion charges in the bag ("Potions of Healing [24/30]"). */
    private static int potions(Minecraft mc) {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            String name = ItemJson.clean(inv.getItem(i).getHoverName().getString());
            if (name.toLowerCase(java.util.Locale.ROOT).contains("healing")) {
                Matcher m = CHARGES.matcher(name);
                n += m.find() ? Integer.parseInt(m.group(1)) : inv.getItem(i).getCount();
            }
        }
        return n;
    }

    /** Names on the mob nameplates within 16 blocks, most common first (top 3). */
    private static List<String> mobs(Minecraft mc) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Display.TextDisplay t) || e.getId() < 0 || e.distanceTo(mc.player) > 16) {
                continue;
            }
            String first = ItemJson.clean(t.getText().getString()).split("\n")[0]
                    .replaceAll("[^\\p{L}\\p{N} '\\-]", "").trim();
            if (first.length() < 3 || first.length() > 32 || first.contains("Loot") || first.contains("Ganimet")) {
                continue;
            }
            seen.merge(first, 1, Integer::sum);
        }
        return seen.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                .map(Map.Entry::getKey).toList();
    }

    // ---- deciding ----

    private static List<Words> advise(Death d, Minecraft mc) {
        return advise(d, DEATHS, MenuWatcher.assigned(io.github.anilken.wynnlabs.model.SkillKind.DEFENCE));
    }

    /** The coach's lines for a death, given the deaths before it and the Defence points assigned. */
    public static List<Words> advise(Death d, List<Death> before, int defence) {
        List<Words> out = new ArrayList<>();
        int here = (int) before.stream()
                .filter(o -> Math.hypot(o.x() - d.x(), o.z() - d.z()) < 200 && d.time() - o.time() < 3 * 3600_000L)
                .count() + 1;
        if (here >= 2) {
            out.add(Words.of("wynnlabs.death.repeat_here", here));
        }
        if (d.areaLevel() > d.level()) {
            out.add(Words.of("wynnlabs.death.area_level", d.areaLevel(), d.level()));
        }
        if (d.secondsToZero() > 0 && d.secondsToZero() <= 3) {
            out.add(Words.of("wynnlabs.death.burst", String.format(java.util.Locale.ROOT, "%.1f", d.secondsToZero()),
                    Math.round(d.ehp())));
            if (defence < 10) {
                out.add(Words.of("wynnlabs.death.add_defence", defence));
            }
        }
        if (d.potions() == 0) {
            out.add(Words.of("wynnlabs.death.no_potions"));
        }
        if (out.isEmpty()) {
            out.add(Words.of("wynnlabs.death.one_off"));
        }
        return out;
    }

    /**
     * Three deaths within half an hour: survival weighs more in the model (rules.model.survivalLearned
     * ×1.15, stored up to 2, read at most 1.2 on the Damage goal by Damage.deathCap; a key of its
     * own, so choosing a goal keeps it). Then the health floor
     * from the deaths ({@link #floorRatio}) and the lesson's decay ({@link #decay}) are written.
     */
    private static void learn() {
        if (!Settings.on("learning")) {
            return;
        }
        long now = System.currentTimeMillis();
        long recent = DEATHS.stream().filter(o -> now - o.time() < 30 * 60_000L).count();
        if (recent >= 3) {
            double w = Rules.sub("model", "survivalLearned", 1);
            double next = Math.min(2, Math.max(1, w) * 1.15);
            if (next > w + 1e-6) {
                JsonObject change = new JsonObject();
                JsonObject model = new JsonObject();
                model.addProperty("survivalLearned", Math.round(next * 1000) / 1000.0);
                model.addProperty("survivalLearnedAt", now);
                change.add("model", model);
                Rules.update(change);
            }
        }
        relearn(now);
    }

    /**
     * Brings the rules up to the deaths on file: the lesson halves after a week without a death,
     * the floor from deaths follows the last week's. Written only when something changed (a
     * change makes the road map out of date).
     */
    private static void relearn(long now) {
        if (!Settings.on("learning") || Profile.id().isEmpty()) {
            return;
        }
        load();
        long last = DEATHS.isEmpty() ? 0 : DEATHS.get(DEATHS.size() - 1).time();
        double learned = Rules.sub("model", "survivalLearned", 1);
        long at = (long) Rules.sub("model", "survivalLearnedAt", 0);
        double[] next = decay(learned, at, last, now);
        double ratio = Math.round(floorRatio(DEATHS, now) * 100) / 100.0;
        JsonObject model = new JsonObject();
        if (next[0] != learned) {
            model.addProperty("survivalLearned", next[0]);
            model.addProperty("survivalLearnedAt", (long) next[1]);
        }
        if (Math.abs(ratio - Rules.sub("model", "deathFloorRatio", 0)) > 1e-6) {
            model.addProperty("deathFloorRatio", ratio);
        }
        if (!model.isEmpty()) {
            JsonObject change = new JsonObject();
            change.add("model", model);
            Rules.update(change);
        }
    }

    /**
     * The survival lesson after time without dying: {learned, since}. A week with no death
     * since the last change halves what is above 1 (back to 1 below 1.02) and starts the next week.
     */
    public static double[] decay(double learned, long since, long lastDeath, long now) {
        long week = 7 * 24 * 3600_000L;
        if (learned <= 1 || now - Math.max(since, lastDeath) < week) {
            return new double[] {learned, since};
        }
        double half = 1 + (learned - 1) / 2;
        return new double[] {half < 1.02 ? 1 : Math.round(half * 1000) / 1000.0, now};
    }

    /**
     * How far the health floor should rise for what kills you (Damage.ehpFloor), as a share of
     * the floor's table at each death's level: the effective health lost per second in the last
     * week's fights (mobs around, at least half a second from the top: falls and one-shots say
     * little) times rules.model.deathSurviveSeconds (4 s to react), the 75th percentile of them.
     * 0 with fewer than three such deaths.
     */
    public static double floorRatio(List<Death> deaths, long now) {
        double hold = Rules.sub("model", "deathSurviveSeconds", 4);
        List<Double> ratios = new ArrayList<>();
        for (Death d : deaths) {
            double table = Damage.ehpTable(d.level());
            double health = d.ehp() > 0 ? d.ehp() : d.hpMax();
            if (now - d.time() > 7 * 24 * 3600_000L || d.mobs().isEmpty() || d.secondsToZero() < 0.5
                    || health <= 0 || table <= 0) {
                continue;
            }
            ratios.add(health / d.secondsToZero() * hold / table);
        }
        if (ratios.size() < 3) {
            return 0;
        }
        ratios.sort(null);
        return Math.min(2, ratios.get((int) Math.ceil(0.75 * ratios.size()) - 1));
    }

    /** Deaths within 150 blocks of a spot in the last week: the grind list treats it as harder. */
    public static int deathsNear(int x, int z) {
        load();
        long now = System.currentTimeMillis();
        return (int) DEATHS.stream()
                .filter(o -> Math.hypot(o.x() - x, o.z() - z) < 150 && now - o.time() < 7 * 24 * 3600_000L)
                .count();
    }

    // ---- files ----

    private static Path file() {
        return Profile.dir().resolve("deaths.json");
    }

    private static void load() {
        String id = Profile.id();
        if (id.equals(loadedFor)) {
            return;
        }
        loadedFor = id;
        DEATHS.clear();
        try {
            if (id.isEmpty() || !Files.exists(file())) {
                return;
            }
            DEATHS.addAll(read(Files.readString(file())));
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read deaths.json: " + e);
        }
    }

    /**
     * deaths.json's deaths. The advice is read from "notes" (language keys); a file from an older
     * version has only "advice", already worded in the language of the day: each line is matched
     * back to its key (in English or Turkish), so it follows the menu's language too. A line no
     * key matches stays as it was. The same for "near X" in the area.
     */
    public static List<Death> read(String text) {
        List<Death> out = new ArrayList<>();
        for (var el : JsonParser.parseString(text).getAsJsonArray()) {
            var o = el.getAsJsonObject();
            List<String> mobs = new ArrayList<>();
            o.getAsJsonArray("mobs").forEach(m -> mobs.add(m.getAsString()));
            List<Words> adv = new ArrayList<>();
            if (o.has("notes")) {
                o.getAsJsonArray("notes").forEach(m -> {
                    Words w = Words.fromJson(m);
                    if (w != null) {
                        adv.add(w);
                    }
                });
            } else if (o.has("advice")) {
                o.getAsJsonArray("advice").forEach(m -> {
                    String line = m.getAsString();
                    Words w = Words.unword(line, ADVICE_KEYS);
                    adv.add(w != null ? w : Words.literal(line));
                });
            }
            String area = o.get("area").getAsString();
            boolean near = o.has("near") && o.get("near").getAsBoolean();
            if (!o.has("near")) {
                Words w = Words.unword(area, List.of("wynnlabs.death.area_near"));
                if (w != null) {
                    area = String.valueOf(w.args().get(0));
                    near = true;
                }
            }
            out.add(new Death(o.get("time").getAsLong(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                    o.get("z").getAsInt(), o.get("level").getAsInt(), area, near,
                    o.get("areaLevel").getAsInt(), o.get("ehp").getAsDouble(), o.get("hpMax").getAsInt(),
                    o.get("seconds").getAsDouble(), o.get("potions").getAsInt(), mobs, adv));
        }
        return out;
    }

    /**
     * deaths.json's text: the advice as language keys ("notes"). "advice" keeps the lines worded
     * as well, only so an older version of the mod can still read the file.
     */
    public static String write(List<Death> deaths) {
        JsonArray arr = new JsonArray();
        for (Death d : deaths) {
            JsonObject o = new JsonObject();
            o.addProperty("time", d.time());
            o.addProperty("x", d.x());
            o.addProperty("y", d.y());
            o.addProperty("z", d.z());
            o.addProperty("level", d.level());
            o.addProperty("area", d.area());
            o.addProperty("near", d.near());
            o.addProperty("areaLevel", d.areaLevel());
            o.addProperty("ehp", Math.round(d.ehp()));
            o.addProperty("hpMax", d.hpMax());
            o.addProperty("seconds", d.secondsToZero());
            o.addProperty("potions", d.potions());
            JsonArray mobs = new JsonArray();
            d.mobs().forEach(mobs::add);
            o.add("mobs", mobs);
            JsonArray notes = new JsonArray();
            d.advice().forEach(a -> notes.add(a.json()));
            o.add("notes", notes);
            JsonArray adv = new JsonArray();
            d.adviceText().forEach(adv::add);
            o.add("advice", adv);
            arr.add(o);
        }
        return arr.toString();
    }

    private static void save() {
        if (Profile.id().isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), write(DEATHS));
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save deaths.json: " + e);
        }
    }

    public static JsonObject json() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Death d : all()) {
            JsonObject j = new JsonObject();
            j.addProperty("time", java.time.Instant.ofEpochMilli(d.time()).toString());
            j.addProperty("at", d.x() + ", " + d.y() + ", " + d.z());
            j.addProperty("area", d.place());
            j.addProperty("areaLevel", d.areaLevel());
            j.addProperty("level", d.level());
            j.addProperty("ehp", Math.round(d.ehp()));
            j.addProperty("seconds", d.secondsToZero());
            j.addProperty("potions", d.potions());
            JsonArray mobs = new JsonArray();
            d.mobs().forEach(mobs::add);
            j.add("mobs", mobs);
            JsonArray adv = new JsonArray();
            d.adviceText().forEach(adv::add);
            j.add("advice", adv);
            arr.add(j);
        }
        o.add("deaths", arr);
        return o;
    }
}
