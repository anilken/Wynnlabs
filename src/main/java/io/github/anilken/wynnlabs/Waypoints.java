package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.Pin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * "Go there": a Wynntils user waypoint (compass, beacon, map pin), set locally the way its own
 * /compass does; nothing is sent to the server. Our previous pin is replaced, the player's own
 * pins are left alone.
 */
final class Waypoints {

    /**
     * Pins carry only their label ("Stage 6", a quest name), so the map shows that. Which pins are
     * ours is kept in config/wynnlabs/pins-ours.json. Wynntils forgets its pins when the game
     * closes, so {@link #restore} puts ours back on the next join. Pins from older versions are
     * still told apart by their name prefix.
     */
    private static final String[] OLD_GO = {"Wynn Labs: ", "Asistan: "};
    private static final String[] OLD_QUEST = {"Wynn Labs quest: ", "Görev: "};

    /**
     * A pin of ours and which kind: "go" (one at a time), "quest" (a quest's point on the map) or
     * "next" (a quest point waiting its turn: only one quest point is shown at a time, because the
     * world labels of several pins draw over each other).
     */
    private record Mine(String kind, String name, int x, int y, int z) {
        /** The same spot: Wynntils keeps one pin per position, and the label may not be readable. */
        boolean is(Pin p) {
            return x == p.x() && y == p.y() && z == p.z();
        }
    }

    private static List<Mine> mine;

    /**
     * Height check for reaching a pin (pin height minus the player's): flying over it on a mount
     * counts however high; being far below it (a cave under the spot) does not.
     */
    static boolean closeInHeight(double dy) {
        return dy <= 16;
    }

    /** How close counts as reaching a quest point, in blocks. */
    static final double QUEST_RADIUS = 15;

    private Waypoints() {}

    private static Path file() {
        return Dirs.config().resolve("pins-ours.json");
    }

    private static List<Mine> mine() {
        if (mine == null) {
            mine = new ArrayList<>();
            try {
                if (Files.exists(file())) {
                    for (var e : JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                        JsonObject o = e.getAsJsonObject();
                        mine.add(new Mine(o.get("kind").getAsString(), o.get("name").getAsString(),
                                o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
                    }
                }
            } catch (Exception e) {
                System.out.println("[WynnLabs] could not read pins-ours.json: " + e);
            }
        }
        return mine;
    }

    private static void save() {
        JsonArray a = new JsonArray();
        for (Mine m : mine()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", m.kind());
            o.addProperty("name", m.name());
            o.addProperty("x", m.x());
            o.addProperty("y", m.y());
            o.addProperty("z", m.z());
            a.add(o);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), a.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save pins-ours.json: " + e);
        }
    }

    private static boolean kind(Pin p, String kind, String[] oldPrefixes) {
        if (p == null || p.name() == null) {
            return false;
        }
        for (Mine m : mine()) {
            if (m.kind().equals(kind) && m.is(p)) {
                return true;
            }
        }
        for (String prefix : oldPrefixes) {
            if (p.name().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** A "go there" pin of ours (one at a time). */
    static boolean isGo(Pin p) {
        return kind(p, "go", OLD_GO);
    }

    /** A quest pin of ours (a quest's points, or the pins inbox). */
    static boolean isQuest(Pin p) {
        return kind(p, "quest", OLD_QUEST);
    }

    /**
     * A lasting pin of ours ("keep" in pins.txt, keep:true on /compass): not taken off when you
     * reach it, put back when Wynntils clears the waypoints for a new target, gone when you
     * remove it on the map, on "clear keep" (pins.txt), DELETE /compass?keep=1, or a restart.
     */
    static boolean isKeep(Pin p) {
        return kind(p, "keep", new String[0]);
    }

    /** Puts a lasting pin on the map; true when placed. */
    static boolean keep(int x, int y, int z, String label) {
        try {
            var pins = Wyn.waypoints();
            String what = label == null || label.isBlank() ? L.t("wynnlabs.waypoint.default_label")
                    : label.length() > 40 ? label.substring(0, 40) : label;
            pins.add(x, y, z, what);
            mine().add(new Mine("keep", what, x, y, z));
            save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A lasting pin missing from the map: Wynntils removes every user waypoint when a new compass
     * target is set (a map click, /compass, "Click to track") and puts that target on the map in
     * the same step, so with a waypoint of someone else's on the map the pin is put back. With
     * none there the player removed it (or ran /compass clear): it is forgotten. True when any
     * came back.
     */
    static boolean holdKeep(Wyn.Pins pins) {
        List<Pin> onMap = pins.all();
        boolean foreign = onMap.stream().anyMatch(p -> !ours(p));
        boolean back = false;
        boolean changed = false;
        for (Mine m : new java.util.ArrayList<>(mine())) {
            if (!m.kind().equals("keep") || onMap.stream().anyMatch(m::is)) {
                continue;
            }
            if (foreign) {
                pins.add(m.x(), m.y(), m.z(), m.name());
                back = true;
            } else {
                mine().remove(m);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return back;
    }

    /** Removes every lasting pin of ours; how many left the map. */
    static int clearKeep() {
        var pins = Wyn.waypoints();
        int n = 0;
        for (Pin m : pins.all()) {
            if (isKeep(m)) {
                pins.remove(m);
                n++;
            }
        }
        if (mine().removeIf(m -> m.kind().equals("keep"))) {
            save();
        }
        return n;
    }

    /** Any pin of ours. */
    static boolean ours(Pin p) {
        return isGo(p) || isQuest(p);
    }

    /** The pin's label, without the prefix older versions put in front. */
    static String label(String name) {
        for (String[] group : new String[][] {OLD_QUEST, OLD_GO}) {
            for (String p : group) {
                if (name.startsWith(p)) {
                    return name.substring(p.length());
                }
            }
        }
        return name;
    }

    /** Takes a pin off the map and out of our list. */
    static void remove(Wyn.Pins pins, Pin p) {
        pins.remove(p);
        if (mine().removeIf(m -> m.is(p))) {
            save();
        }
    }

    /** Puts a pin of ours on the map. */
    static void add(Wyn.Pins pins, int x, int y, int z, String label, boolean quest) {
        pins.add(x, y, z, label);
        mine().add(new Mine(quest ? "quest" : "go", label, x, y, z));
        save();
    }

    /** Removes every go and quest pin of ours (older prefixed ones too) and the waiting points, not the lasting ones; how many left the map. */
    static int clearAll() {
        var pins = Wyn.waypoints();
        int n = 0;
        for (Pin m : pins.all()) {
            if (ours(m)) {
                pins.remove(m);
                n++;
            }
        }
        mine().removeIf(m -> !m.kind().equals("keep"));     // the lasting pins stay ("clear keep" for them)
        save();
        return n;
    }

    /** Removes every quest pin of ours, and the points still waiting. */
    static void clearQuest() {
        var pins = Wyn.waypoints();
        for (Pin m : pins.all()) {
            if (isQuest(m)) {
                remove(pins, m);
            }
        }
        if (mine().removeIf(m -> m.kind().equals("next"))) {
            save();
        }
    }

    /**
     * Queues quest points in order: the first goes on the map now unless a quest point of ours is
     * already there, the rest wait for {@link #next}.
     */
    static int queue(List<int[]> points, List<String> labels) {
        var pins = Wyn.waypoints();
        boolean shown = pins.all().stream().anyMatch(Waypoints::isQuest);
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            String l = labels.get(i);
            String label = l.length() > 40 ? l.substring(0, 40) : l;
            if (!shown) {
                add(pins, p[0], p[1], p[2], label, true);
                shown = true;
            } else {
                mine().add(new Mine("next", label, p[0], p[1], p[2]));
            }
        }
        save();
        return points.size();
    }

    /**
     * When the player stands at a point still waiting (they took another way in, or did that part
     * first), the shown point and every one before it are done: the point after it goes on the
     * map. Returns the reached point's label, or null.
     */
    static String skipTo(Wyn.Pins pins, double px, double py, double pz, double radius) {
        Mine hit = null;
        for (Mine m : mine()) {
            double dx = m.x() + 0.5 - px;
            double dz = m.z() + 0.5 - pz;
            if (m.kind().equals("next") && dx * dx + dz * dz <= radius * radius && closeInHeight(m.y() - py)) {
                hit = m;
                break;
            }
        }
        if (hit == null) {
            return null;
        }
        for (Pin m : pins.all()) {
            if (isQuest(m)) {
                remove(pins, m);
            }
        }
        // the waiting points up to and including the one reached
        Iterator<Mine> it = mine().iterator();
        while (it.hasNext()) {
            Mine m = it.next();
            if (m.kind().equals("next")) {
                it.remove();
                if (m == hit) {
                    break;
                }
            }
        }
        save();
        return hit.name();
    }

    /**
     * The game was closed and opened again: Wynntils dropped its user waypoints, and so do we,
     * lasting pins and waiting quest points too (a pin from yesterday's session is stale; the
     * player asked for a clean compass at start). Always false: nothing is put back.
     */
    static boolean restore(Wyn.Pins pins) {
        if (!mine().isEmpty()) {
            mine().clear();
            save();
        }
        return false;
    }

    /**
     * A pin of ours that is gone from the map while the game kept running was removed by the
     * player (Wynntils' map, /compass clear): forget it, and with a quest point the points waiting
     * behind it, so nothing brings them back.
     */
    static void forgetRemoved(Wyn.Pins pins) {
        List<Pin> onMap = pins.all();
        boolean[] questGone = {false};
        boolean changed = mine().removeIf(m -> {
            // a lasting pin is never forgotten here: Wynntils drops every user waypoint at once when
            // a new compass target is set (map click, /compass, "Click to track"), and it comes back
            boolean shown = m.kind().equals("go") || m.kind().equals("quest");
            boolean gone = shown && onMap.stream().noneMatch(m::is);
            if (gone && m.kind().equals("quest")) {
                questGone[0] = true;
            }
            return gone;
        });
        if (questGone[0]) {
            mine().removeIf(m -> m.kind().equals("next"));
        }
        if (changed) {
            save();
        }
    }

    /** After a quest point is reached: puts the next waiting one on the map; its label, or null. */
    static String next(Wyn.Pins pins) {
        for (Mine m : mine()) {
            if (m.kind().equals("next")) {
                mine().remove(m);
                add(pins, m.x(), m.y(), m.z(), m.name(), true);
                return m.name();
            }
        }
        return null;
    }

    static boolean go(int x, int y, int z, String label) {
        try {
            var pins = Wyn.waypoints();
            Iterator<Pin> it = pins.all().iterator();
            while (it.hasNext()) {
                Pin m = it.next();
                if (isGo(m)) {
                    remove(pins, m);
                }
            }
            String what = label == null || label.isBlank() ? L.t("wynnlabs.waypoint.default_label")
                    : label.length() > 32 ? label.substring(0, 32) : label;
            add(pins, x, y, z, what, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Every point of a quest, in order, one on the map at a time (the previous quest's points go first). */
    static int all(List<int[]> points, List<String> labels) {
        try {
            clearQuest();
            return queue(points, labels);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Every point of a quest on the map at once (the previous quest's points go first); how many were placed. */
    static int showAll(List<int[]> points, List<String> labels) {
        try {
            clearQuest();
            var pins = Wyn.waypoints();
            for (int i = 0; i < points.size(); i++) {
                int[] p = points.get(i);
                String l = labels.get(i);
                add(pins, p[0], p[1], p[2], l.length() > 40 ? l.substring(0, 39) + "…" : l, true);
            }
            return points.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** "/compass at x y z" → go there; false if the text is not such a command. */
    static boolean go(String compassCommand, String label) {
        if (compassCommand == null || !compassCommand.startsWith("/compass at ")) {
            return false;
        }
        try {
            String[] p = compassCommand.substring("/compass at ".length()).trim().split("\s+");
            return go(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), label);
        } catch (Exception e) {
            return false;
        }
    }
}
