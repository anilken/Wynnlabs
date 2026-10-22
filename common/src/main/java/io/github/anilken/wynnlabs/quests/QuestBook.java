package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.game.ContentBook;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.model.Activity;
import io.github.anilken.wynnlabs.model.Quest;
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
public final class QuestBook {

    public record Entry(String name, String status, int level, String length, long xp, boolean met, String task,
            Integer x, Integer y, Integer z, Integer bookDistance, long seen, String type, String difficulty) {}

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Pattern COORDS = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");
    private static String loadedFor = null;
    private static int tick;

    private QuestBook() {}

    public static List<Entry> all() {
        load();
        return previewHidden ? new ArrayList<>() : new ArrayList<>(ENTRIES.values());
    }

    /** For the no-game preview: a quest as if it was read from the book. */
    public static void put(Entry e) {
        load();
        ENTRIES.put(e.name(), e);
        readAt = Math.max(readAt, e.seen());
    }

    // ---- "read quests with Wynntils" (the quests tab's button and its "Yenile" chip) ----

    /** How long the button says "reading" when no fresh list arrives. */
    static final long RESCAN_TIMEOUT = 30_000;
    /** How long after the press Wynntils' lists are taken in (its quest and mini-quest scans come one after the other). */
    private static final long RESCAN_WINDOW = 120_000;
    /** When the quest list was last read (the open book, or Wynntils after a press); 0 when never. */
    private static volatile long readAt;
    /** When the player last pressed the button; 0 when not this session. */
    private static volatile long askedAt;
    private static boolean previewHidden;
    private static boolean previewRescanning;

    /** When the quest list was last read, in epoch ms; 0 when it never was. */
    public static long readAt() {
        load();
        return readAt;
    }

    /** True from the player's press until the list updates or {@link #RESCAN_TIMEOUT} passes. */
    public static boolean rescanning() {
        if (previewRescanning) {
            return true;
        }
        long asked = askedAt;
        return asked > 0 && System.currentTimeMillis() - asked < RESCAN_TIMEOUT && readAt < asked;
    }

    /**
     * The player pressed "read quests with Wynntils": Wynntils' own refresh reads the content book
     * (see {@link Wyn#rescanQuests}). False when it is already running or Wynntils cannot.
     * Only ever called from that press.
     */
    public static boolean askWynntils() {
        load();
        if (rescanning() || !Wyn.canRescanQuests()) {
            return false;
        }
        if (!Wyn.rescanQuests()) {
            return false;
        }
        askedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * Wynntils posted a fresh quest or mini-quest list. Taken in only after the player's press
     * (otherwise the open book, read in {@link #onTick}, stays the source): new and changed quests
     * go in, quests Wynntils did not list stay as they were. Game thread.
     */
    public static void fromWynntils() {
        long asked = askedAt;
        if (asked == 0 || System.currentTimeMillis() - asked > RESCAN_WINDOW) {
            return;
        }
        List<Quest> quests = Wyn.questsAndMiniQuests();
        if (quests.isEmpty()) {
            return;                                 // nothing read: the press times out
        }
        load();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (Quest q : quests) {
            Entry old = ENTRIES.get(q.name());
            Entry e = entry(q, old, now);
            if (old == null || !same(old, e)) {
                ENTRIES.put(e.name(), e);
                changed = true;
            }
        }
        readAt = now;
        if (changed) {
            save();
        }
        System.out.println("[WynnLabs] took " + quests.size() + " quests from Wynntils' content book scan");
    }

    /** A quest from Wynntils' list as a book entry; the book's own kind and distance kept from {@code old}. */
    private static Entry entry(Quest q, Entry old, long now) {
        boolean met = true;
        int level = q.level();
        if (q.requirements() != null) {
            Quest.Requirements r = q.requirements();
            if (r.level() != null) {
                level = r.level();
                met = r.levelMet() == null || r.levelMet();
            }
            for (Quest.Profession p : r.professions()) {
                met &= p.met() == null || p.met();
            }
            for (Quest.Need n : r.quests()) {
                met &= n.met() == null || n.met();
            }
        }
        long xp = 0;
        List<String> xpLines = q.rewards() == null ? null : q.rewards().get("XP");
        if (xpLines != null) {
            for (String line : xpLines) {
                String digits = line.replaceAll("[^0-9]", "");
                if (!digits.isEmpty() && digits.length() < 12) {
                    xp = Math.max(xp, Long.parseLong(digits));
                }
            }
        }
        String task = q.nextTask() == null ? null : ItemJson.clean(q.nextTask());
        Integer x = null, y = null, z = null;
        if (task != null) {
            Matcher m = COORDS.matcher(task);
            if (m.find()) {
                x = Integer.parseInt(m.group(1));
                y = Integer.parseInt(m.group(2));
                z = Integer.parseInt(m.group(3));
            }
        }
        String type = old != null ? old.type() : q.mini() ? "MINI_QUEST" : "QUEST";
        return new Entry(q.name(), q.status() == null ? "UNKNOWN" : q.status(), level, q.length(), xp, met, task,
                x, y, z, x == null && old != null ? old.bookDistance() : null, now, type, q.difficulty());
    }

    /** For the J menu preview: the tab as if no quest was read yet. */
    public static void previewHidden(boolean on) {
        previewHidden = on;
    }

    /** For the J menu preview: the button as if it was pressed a moment ago. */
    public static void previewRescanning(boolean on) {
        previewRescanning = on;
    }

    /**
     * Track this quest the way clicking it in the content book does: Wynntils opens the book in
     * the background and selects it, and the game then shows the way. Only on the player's click.
     */
    public static boolean track(Entry e) {
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
     * Chat: "[Quest Completed]" (or "[Görev Tamamlandı]") and the quest's name. The finished quest leaves the
     * suggestions at once, without waiting for the content book to be opened again.
     */
    public static void onChat(net.minecraft.network.chat.Component message, boolean overlay) {
        if (overlay || message == null) {
            return;
        }
        String text = ItemJson.clean(message.getString()).trim();
        if (text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // the banner in the game's English and in its Turkish translation
        if (text.contains("[Quest Completed]") || text.contains("[Görev Tamamlandı]")) {
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
            // the game's Turkish translation names the quest by its translated title
            boolean named = e.name().equalsIgnoreCase(name) || QuestGuides.title(e.name()).equalsIgnoreCase(name);
            if (named && !"COMPLETED".equals(e.status())) {
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
        return previewHidden ? 0 : ENTRIES.size();
    }

    public static void onTick(Minecraft mc) {
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
                readAt = System.currentTimeMillis();    // the book is open on quests: the list is being read
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
        readAt = 0;
        askedAt = 0;
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
                readAt = Math.max(readAt, e.seen());   // the file keeps no read time: the newest entry's
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
