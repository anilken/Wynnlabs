package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.Activity;
import io.github.anilken.wynnlabs.model.QuestKind;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quests as seen in the content book. Wynntils parses every entry of the open book (read here as
 * an {@link Activity}) but keeps no list of them, so whenever the book is open (Wynntils' own
 * view or the plain one) the quests on screen are collected here and saved per character in
 * quests.json; scrolling through the book fills it up. Reads the open menu only; never clicks.
 */
final class QuestBook {

    record Entry(String name, String status, int level, String length, long xp, boolean met, String task,
            Integer x, Integer y, Integer z, Integer bookDistance, long seen, String type, String difficulty) {}

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Pattern COORDS = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");
    private static String loadedFor = null;
    private static int tick;

    private QuestBook() {}

    static List<Entry> all() {
        load();
        return new ArrayList<>(ENTRIES.values());
    }

    /** For the no-game preview: a quest as if it was read from the book. */
    static void put(Entry e) {
        load();
        ENTRIES.put(e.name(), e);
    }

    /**
     * Track this quest the way clicking it in the content book does: Wynntils opens the book in
     * the background and selects it, and the game then shows the way. Only on the player's click.
     */
    static boolean track(Entry e) {
        try {
            QuestKind type = QuestKind.valueOf(e.type());
            Wyn.startTracking(e.name(), type);
            return true;
        } catch (Throwable t) {
            System.out.println("[WynnLabs] could not track " + e.name() + ": " + t);
            return false;
        }
    }

    private static long completedAt;

    /**
     * Chat: "[Quest Completed]" followed by the quest's name. The finished quest leaves the
     * suggestions at once, without waiting for the content book to be opened again.
     */
    static void onChat(net.minecraft.network.chat.Component message, boolean overlay) {
        if (overlay || message == null) {
            return;
        }
        String text = ItemJson.clean(message.getString()).trim();
        if (text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (text.contains("[Quest Completed]")) {
            // the game sends the quest's name first, then "[Quest Completed]" (Frost Bite stayed
            // open when only a name after the banner counted)
            if (now - lastLineAt < 3000 && complete(lastLine)) {
                return;
            }
            completedAt = now;
            return;
        }
        if (now - completedAt <= 3000 && complete(text)) {
            completedAt = 0;
            return;
        }
        lastLine = text;
        lastLineAt = now;
    }

    private static String lastLine = "";
    private static long lastLineAt;

    /** Marks the quest named {@code name} completed; false when no open quest has that name. */
    private static boolean complete(String name) {
        load();
        for (Entry e : ENTRIES.values()) {
            if (e.name().equalsIgnoreCase(name) && !"COMPLETED".equals(e.status())) {
                ENTRIES.put(e.name(), new Entry(e.name(), "COMPLETED", e.level(), e.length(), e.xp(), e.met(), e.task(),
                        e.x(), e.y(), e.z(), e.bookDistance(), System.currentTimeMillis(), e.type(), e.difficulty()));
                save();
                return true;
            }
        }
        return false;
    }

    static int size() {
        load();
        return ENTRIES.size();
    }

    static void onTick(Minecraft mc) {
        if (++tick % 10 != 0 || mc.player == null || mc.screen == null) {
            return;
        }
        var menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            return;
        }
        try {
            load();
            boolean changed = false;
            for (Activity a : ContentBook.activities(mc)) {
                if (a.type() == null || !a.quest()) {
                    continue;
                }
                Entry e = entry(a);
                Entry old = ENTRIES.get(e.name());
                if (old == null || !same(old, e)) {
                    ENTRIES.put(e.name(), e);
                    changed = true;
                }
            }
            if (changed) {
                save();
            }
        } catch (Throwable t) {
            System.out.println("[WynnLabs] quest book read failed: " + t);
        }
    }

    private static boolean same(Entry a, Entry b) {
        return a.status().equals(b.status()) && a.met() == b.met() && a.level() == b.level()
                && java.util.Objects.equals(a.difficulty(), b.difficulty())
                && java.util.Objects.equals(a.task(), b.task());
    }

    private static Entry entry(Activity a) {
        int level = 0;
        boolean met = true;
        if (a.hasRequirements()) {
            if (a.level() != null) {
                level = a.level();
                if (a.levelMet() != null && !a.levelMet()) {
                    met = false;
                }
            }
            for (Boolean p : a.professionsMet()) {
                met &= p;
            }
            for (Boolean p : a.questsMet()) {
                met &= p;
            }
        }
        long xp = 0;
        if (a.xp() != null) {
            for (String line : a.xp()) {
                String digits = line.replaceAll("[^0-9]", "");
                if (!digits.isEmpty() && digits.length() < 12) {
                    xp = Math.max(xp, Long.parseLong(digits));
                }
            }
        }
        String task = a.description() == null ? null : ItemJson.clean(a.description());
        Integer x = null, y = null, z = null;
        Integer distAtSeen = null;
        if (task != null) {
            Matcher m = COORDS.matcher(task);
            if (m.find()) {
                x = Integer.parseInt(m.group(1));
                y = Integer.parseInt(m.group(2));
                z = Integer.parseInt(m.group(3));
            }
        }
        if (x == null) {
            // No coordinates in the text: keep the book's own distance ("239 Blok") as a hint
            String d = a.distance();
            String digits = d.replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() < 7) {
                distAtSeen = Integer.parseInt(digits);
            }
        }
        String length = a.length();
        return new Entry(a.name(), a.status() == null ? "UNKNOWN" : a.status(), level, length, xp, met, task,
                x, y, z, distAtSeen, System.currentTimeMillis(), a.type(),
                a.difficulty());
    }

    private static Path file() {
        return Profile.dir().resolve("quests.json");
    }

    private static void load() {
        String id = Profile.id();
        if (id.equals(loadedFor)) {
            return;
        }
        loadedFor = id;
        ENTRIES.clear();
        try {
            if (id.isEmpty() || !Files.exists(file())) {
                return;
            }
            for (var el : JsonParser.parseString(Files.readString(file())).getAsJsonArray()) {
                var o = el.getAsJsonObject();
                Entry e = new Entry(o.get("name").getAsString(), o.get("status").getAsString(), o.get("level").getAsInt(),
                        o.has("length") ? o.get("length").getAsString() : null, o.get("xp").getAsLong(),
                        o.get("met").getAsBoolean(), o.has("task") ? o.get("task").getAsString() : null,
                        o.has("x") ? o.get("x").getAsInt() : null, o.has("y") ? o.get("y").getAsInt() : null,
                        o.has("z") ? o.get("z").getAsInt() : null,
                        o.has("bookDistance") ? o.get("bookDistance").getAsInt() : null, o.get("seen").getAsLong(),
                        o.has("type") ? o.get("type").getAsString() : "QUEST",
                        o.has("difficulty") ? o.get("difficulty").getAsString() : null);
                ENTRIES.put(e.name(), e);
            }
        } catch (Exception ex) {
            System.out.println("[WynnLabs] could not read quests.json: " + ex);
        }
    }

    private static void save() {
        if (Profile.id().isEmpty()) {
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (Entry e : ENTRIES.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.name());
                o.addProperty("status", e.status());
                o.addProperty("level", e.level());
                if (e.length() != null) {
                    o.addProperty("length", e.length());
                }
                o.addProperty("xp", e.xp());
                o.addProperty("met", e.met());
                if (e.task() != null) {
                    o.addProperty("task", e.task());
                }
                if (e.x() != null) {
                    o.addProperty("x", e.x());
                    o.addProperty("y", e.y());
                    o.addProperty("z", e.z());
                }
                if (e.bookDistance() != null) {
                    o.addProperty("bookDistance", e.bookDistance());
                }
                o.addProperty("seen", e.seen());
                o.addProperty("type", e.type());
                if (e.difficulty() != null) {
                    o.addProperty("difficulty", e.difficulty());
                }
                arr.add(o);
            }
            Files.createDirectories(file().getParent());
            Files.writeString(file(), arr.toString());
        } catch (Exception ex) {
            System.out.println("[WynnLabs] could not save quests.json: " + ex);
        }
    }
}
