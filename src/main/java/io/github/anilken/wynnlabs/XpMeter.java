package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Gerçek XP/saat: combat XP you gain is measured as you play and booked to the grind spot you
 * are at (within 150 blocks), so the grind list can show — and rank by — what a spot actually
 * gives you, not a guess. Also the XP of this session. Per character, in xprates.json.
 */
final class XpMeter {

    private static final Map<String, double[]> RATES = new HashMap<>();   // spot -> {xp, seconds}
    private static String loadedFor;
    private static int tick;
    private static int lastLevel = -1;
    private static long lastXp = -1;
    private static long lastMax;
    private static long lastAt;
    private static long sessionXp;
    private static long sessionStart;
    /** Recent gains {time, xp} for the last-10-minutes rate. */
    private static final java.util.ArrayDeque<long[]> RECENT = new java.util.ArrayDeque<>();
    private static boolean dirty;
    private static long savedAt;

    private XpMeter() {}

    static void onTick(Minecraft mc) {
        if (mc.player == null || ++tick % 100 != 0 || Profile.id().isEmpty()) {
            return;
        }
        try {
            load();
            int level = Wyn.level();
            var xp = Wyn.xp();
            long cur = xp.current();
            long max = xp.max();
            long now = System.currentTimeMillis();
            if (sessionStart == 0) {
                sessionStart = now;
            }
            if (lastXp >= 0 && now - lastAt < 30_000) {
                long gained = level == lastLevel ? cur - lastXp
                        : level == lastLevel + 1 ? (lastMax - lastXp) + cur : 0;
                if (gained > 0 && gained < 50_000_000) {
                    sessionXp += gained;
                    RECENT.addLast(new long[] {now, gained});
                    String spot = spotHere(mc);
                    if (spot != null) {
                        double[] r = RATES.computeIfAbsent(spot, k -> new double[2]);
                        r[0] += gained;
                        r[1] += (now - lastAt) / 1000.0;
                        dirty = true;
                    }
                } else if (gained == 0) {
                    String spot = spotHere(mc);
                    if (spot != null && RATES.containsKey(spot)) {
                        RATES.get(spot)[1] += (now - lastAt) / 1000.0;   // time there without XP counts too
                    }
                }
            }
            while (!RECENT.isEmpty() && now - RECENT.peekFirst()[0] > 10 * 60_000L) {
                RECENT.removeFirst();
            }
            if (dirty && now - savedAt > 60_000) {
                save();
                dirty = false;
                savedAt = now;
            }
            lastLevel = level;
            lastXp = cur;
            lastMax = max;
            lastAt = now;
        } catch (Throwable ignored) {
        }
    }

    /** XP per hour over the last 10 minutes (needs 5 minutes of data), or -1. */
    static double recentRate() {
        if (RECENT.isEmpty()) {
            return -1;
        }
        long span = System.currentTimeMillis() - RECENT.peekFirst()[0];
        if (span < 5 * 60_000L) {
            return -1;
        }
        long sum = 0;
        for (long[] g : RECENT) {
            sum += g[1];
        }
        return sum / (span / 3_600_000.0);
    }

    static String spotHereName(Minecraft mc) {
        return spotHere(mc);
    }

    private static String spotHere(Minecraft mc) {
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        return Grind.spots().stream()
                .filter(s -> Math.hypot(s.x() - px, s.z() - pz) < 150)
                .min(Comparator.comparingDouble(s -> Math.hypot(s.x() - px, s.z() - pz)))
                .map(Grind.Spot::name).orElse(null);
    }

    /** XP per hour measured at this spot, or -1 with less than 3 minutes there. */
    static double rate(String spot) {
        load();
        double[] r = RATES.get(spot);
        return r == null || r[1] < 180 ? -1 : r[0] / r[1] * 3600;
    }

    static long sessionXp() {
        return sessionXp;
    }

    static double sessionRate() {
        long t = System.currentTimeMillis() - sessionStart;
        return sessionStart == 0 || t < 120_000 ? -1 : sessionXp / (t / 3_600_000.0);
    }

    private static Path file() {
        return Profile.dir().resolve("xprates.json");
    }

    private static void load() {
        String id = Profile.id();
        if (id.equals(loadedFor)) {
            return;
        }
        loadedFor = id;
        RATES.clear();
        lastXp = -1;
        sessionXp = 0;
        sessionStart = 0;
        RECENT.clear();
        try {
            if (Files.exists(file())) {
                JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                o.entrySet().forEach(e -> {
                    var a = e.getValue().getAsJsonArray();
                    RATES.put(e.getKey(), new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()});
                });
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read xprates.json: " + e);
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            RATES.forEach((k, v) -> {
                var a = new com.google.gson.JsonArray();
                a.add(Math.round(v[0]));
                a.add(Math.round(v[1]));
                o.add(k, a);
            });
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save xprates.json: " + e);
        }
    }
}
