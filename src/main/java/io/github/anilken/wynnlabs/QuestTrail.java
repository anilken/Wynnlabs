package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quest places learned while playing, for quests whose wiki page names few or none: every
 * coordinate the tracked step shows ("Talk to Ranol at [768, 45, -5425]"), and the spot where a
 * step was done (a lever, a puzzle, a door the step's line never placed). Kept for every
 * character in quest-places.json; "Mark points" puts them on the map with the wiki's.
 * Only reads the tracked quest and the player's position; never clicks or sends anything.
 */
final class QuestTrail {

    /** A learned place: the quest, the game's (English) step line, where, and whether the line named it or the step ended there. */
    record Spot(String quest, String task, int x, int y, int z, boolean done) {}

    private static final Pattern COORDS = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");
    private static List<Spot> spots;
    private static String lastQuest = "";
    private static String lastTask = "";

    private QuestTrail() {}

    private static Path file() {
        return Dirs.config().resolve("quest-places.json");
    }

    /** Once a second: what the tracked step names, and where the previous step ended. */
    static void watch(Minecraft mc) {
        if (!Wyn.tracking()) {
            lastQuest = "";
            lastTask = "";
            return;
        }
        String quest = Wyn.trackedName();
        String task = Wyn.trackedTask();
        if (quest == null || quest.isBlank() || task == null) {
            return;
        }
        Matcher m = COORDS.matcher(task);
        while (m.find()) {
            add(new Spot(quest, task, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), false));
        }
        if (quest.equals(lastQuest) && !task.equals(lastTask) && !lastTask.isBlank() && mc.player != null) {
            // the step just changed: it was done here, unless its line already placed it nearby
            add(new Spot(quest, lastTask, mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), true));
        }
        lastQuest = quest;
        lastTask = task;
    }

    /** The places learned for a quest, in the order they were met. */
    static List<Spot> of(String quest) {
        List<Spot> out = new ArrayList<>();
        for (Spot s : all()) {
            if (s.quest().equals(quest)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * A quest's places to pin: the wiki's, then the learned ones they lack, each at the stage its
     * step line matches (from the stage you are on), labelled with that stage in your language.
     */
    static List<QuestWiki.Place> places(String quest, QuestWiki.Info info, int from) {
        List<QuestWiki.Place> out = new ArrayList<>(QuestWiki.places(info, from));
        int last = info == null ? 0 : Math.max(0, info.stages().size() - 1);
        for (Spot s : of(quest)) {
            boolean known = false;
            for (QuestWiki.Place p : out) {
                known |= Math.abs(p.at()[0] - s.x()) <= 3 && Math.abs(p.at()[1] - s.y()) <= 3 && Math.abs(p.at()[2] - s.z()) <= 3;
            }
            int stage = info == null ? -1 : QuestWiki.currentStage(info, s.task());
            if (known || stage >= 0 && stage < from) {
                continue;
            }
            String what = QuestWiki.placeText(info == null ? s.task() : QuestWiki.stepText(info, s.task()));
            out.add(new QuestWiki.Place(stage < 0 ? last : stage, new int[] {s.x(), s.y(), s.z()}, what));
        }
        out.sort(java.util.Comparator.comparingInt(QuestWiki.Place::stage));
        return out;
    }

    private static void add(Spot s) {
        List<Spot> list = all();
        for (Spot o : list) {
            if (o.quest().equals(s.quest()) && Math.abs(o.x() - s.x()) <= 12 && Math.abs(o.y() - s.y()) <= 8
                    && Math.abs(o.z() - s.z()) <= 12) {
                return;                                 // known: the same place, or a step done beside it
            }
        }
        list.add(s);
        save();
    }

    private static synchronized List<Spot> all() {
        if (spots == null) {
            spots = new ArrayList<>();
            try {
                if (Files.exists(file())) {
                    for (var el : JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        spots.add(new Spot(o.get("quest").getAsString(), o.get("task").getAsString(), o.get("x").getAsInt(),
                                o.get("y").getAsInt(), o.get("z").getAsInt(), o.has("done") && o.get("done").getAsBoolean()));
                    }
                }
            } catch (Exception e) {
                System.out.println("[WynnLabs] could not read quest-places.json: " + e);
            }
        }
        return spots;
    }

    private static void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Spot s : all()) {
                JsonObject o = new JsonObject();
                o.addProperty("quest", s.quest());
                o.addProperty("task", s.task());
                o.addProperty("x", s.x());
                o.addProperty("y", s.y());
                o.addProperty("z", s.z());
                o.addProperty("done", s.done());
                arr.add(o);
            }
            Files.createDirectories(file().getParent());
            Files.writeString(file(), arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save quest-places.json: " + e);
        }
    }

    /** For tests: these places instead of the file's (nothing is saved). */
    static void use(List<Spot> list) {
        spots = new ArrayList<>(list);
    }

    /** For tests: forget what was read. */
    static void reset() {
        spots = null;
        lastQuest = "";
        lastTask = "";
    }
}
