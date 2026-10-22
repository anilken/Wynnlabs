package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Pin;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * "Bugün": the day at a glance for every character. Daily objectives with progress (from
 * Wynntils' scoreboard reading), whether the daily reward is waiting (read from the Character
 * Info menu when you open it), event tickets in the bag, and the time to the daily reset (00:00
 * UTC). Each character's day is saved in its folder (today.json), so the other characters'
 * state shows too, as of the last time they were played. Reads only.
 */
final class Today {

    record Objective(String goal, int score, int max) {}

    record Day(String character, String cls, int level, String date, List<Objective> objectives, Boolean reward,
            int tickets, long seen) {}

    private static int tick;
    /** Our pins were put back once this game session (Wynntils keeps them until the game closes). */
    private static boolean pinsRestored;
    private static Boolean reward;              // null: not seen today
    private static String rewardDate = "";

    private Today() {}

    static String utcDate() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    /** Minutes until the next 00:00 UTC. */
    static long minutesToReset() {
        var now = java.time.ZonedDateTime.now(ZoneOffset.UTC);
        var next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return java.time.Duration.between(now, next).toMinutes();
    }

    static void onTick(Minecraft mc) {
        if (mc.player == null || ++tick % 40 != 0 || !Profile.settled()) {
            return;
        }
        // Our pins (Waypoints.ours: go and quest pins, older prefixed names too) go away once you reach them (8 blocks, quest points 15)
        try {
            var pins = Wyn.waypoints();
            if (!pinsRestored) {
                // once per game: Wynntils dropped its waypoints when the game last closed
                pinsRestored = true;
                Waypoints.restore(pins);
            } else {
                // from then on, a pin of ours missing from the map was removed by the player
                Waypoints.forgetRemoved(pins);
                Waypoints.holdKeep(pins);       // lasting pins Wynntils cleared with a new compass target
            }
            java.util.List<Pin> reached = new java.util.ArrayList<>();
            java.util.Iterator<Pin> it = pins.all().iterator();
            while (it.hasNext()) {
                var m = it.next();
                if (!Waypoints.ours(m)) {
                    continue;
                }
                double dx = m.x() + 0.5 - mc.player.getX();
                double dz = m.z() + 0.5 - mc.player.getZ();
                double dy = m.y() - mc.player.getY();
                // quest points come from the wiki and are rough (a cave, a camp): a wider circle
                double r = Waypoints.isQuest(m) ? Waypoints.QUEST_RADIUS : 8;
                if (dx * dx + dz * dz <= r * r && Waypoints.closeInHeight(dy)) {
                    reached.add(m);
                }
            }
            for (var m : reached) {
                boolean quest = Waypoints.isQuest(m);
                Waypoints.remove(pins, m);
                String what = Waypoints.label(m.name());
                String next = quest ? Waypoints.next(pins) : null;
                mc.gui.setOverlayMessage((next == null ? L.c("wynnlabs.daily.arrived", what)
                        : L.c("wynnlabs.daily.arrived_next", what, next))
                        .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            }
            // a point further down the quest's list reached first: the ones before it are done
            String ahead = Waypoints.skipTo(pins, mc.player.getX(), mc.player.getY(), mc.player.getZ(), Waypoints.QUEST_RADIUS);
            if (ahead != null) {
                String next = Waypoints.next(pins);
                mc.gui.setOverlayMessage((next == null ? L.c("wynnlabs.daily.arrived", ahead)
                        : L.c("wynnlabs.daily.arrived_next", ahead, next))
                        .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            }
        } catch (Throwable ignored) {
        }
        // Pins inbox (only while the local API is on): config/wynnlabs/pins.txt, one "x y z label" per line ("clear" removes all of ours,
        // "keep x y z label" a lasting pin that stays when reached, "clear keep" the lasting ones),
        // put on the Wynntils map and compass, then the file is deleted. Local only, like /compass.
        try {
            Path pf = Dirs.config().resolve("pins.txt");
            if (Settings.on("localApi") && Files.exists(pf)) {
                java.util.List<int[]> points = new java.util.ArrayList<>();
                java.util.List<String> labels = new java.util.ArrayList<>();
                int kept = 0;
                for (String line : Files.readAllLines(pf, java.nio.charset.StandardCharsets.UTF_8)) {
                    String[] p = line.trim().split("\\s+", 4);
                    if (p.length >= 2 && p[0].equalsIgnoreCase("clear") && p[1].equalsIgnoreCase("keep")) {
                        Waypoints.clearKeep();          // "clear keep": the lasting pins only
                        continue;
                    }
                    if (p.length >= 4 && p[0].equalsIgnoreCase("keep")) {
                        // "keep x y z label": a lasting pin, not a checkpoint
                        String[] q = line.trim().split("\\s+", 5);
                        if (Waypoints.keep(Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3]),
                                q.length > 4 ? q[4] : L.t("wynnlabs.daily.pin_point"))) {
                            kept++;
                        }
                        continue;
                    }
                    if (p.length >= 1 && p[0].equalsIgnoreCase("clear")) {
                        Waypoints.clearAll();
                        points.clear();
                        labels.clear();
                        continue;
                    }
                    if (p.length < 3) {
                        continue;
                    }
                    points.add(new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                    labels.add(p.length > 3 ? p[3] : L.t("wynnlabs.daily.pin_point"));
                }
                // in the order given, one on the map at a time; the next appears on arrival
                int added = (points.isEmpty() ? 0 : Waypoints.queue(points, labels)) + kept;
                Files.delete(pf);
                if (added > 0) {
                    mc.gui.setOverlayMessage(L.cn("wynnlabs.daily.pins_added", added)
                            .withStyle(net.minecraft.ChatFormatting.GREEN), false);
                }
            }
        } catch (Throwable t) {
            System.out.println("[WynnLabs] pins failed: " + t);
        }
        try {
            readRewardFromMenu(mc);
            save(current(mc));
        } catch (Throwable t) {
            System.out.println("[WynnLabs] today failed: " + t);
        }
    }

    /** The Daily Reward item's name in English and in every language WynnChaYuan translates it to. */
    private static final List<String> REWARD_NAMES = List.of("daily reward", "günlük ödül", "recompensa diaria",
            "ежедневная награда", "デイリー報酬", "每日奖励", "每日獎勵", "일일 보상");

    /**
     * The Character Info menu's Daily Reward item: "Click to Claim" (any language) means it is
     * waiting; "Available in 3 hours" or a red line with a number (the cooldown, also when
     * translated) means it was claimed. Neither: leave what we knew. Its first lore line ("Come
     * back every day…") says nothing either way.
     */
    private static void readRewardFromMenu(Minecraft mc) {
        var menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            return;
        }
        for (var slot : menu.slots) {
            ItemStack st = slot.getItem();
            if (st.isEmpty()) {
                continue;
            }
            String name = ItemJson.clean(st.getHoverName().getString()).toLowerCase(java.util.Locale.ROOT);
            if (REWARD_NAMES.stream().noneMatch(name::contains)) {
                continue;
            }
            var lore = st.get(DataComponents.LORE);
            if (lore == null) {
                return;
            }
            boolean ready = false;
            boolean waiting = false;
            for (net.minecraft.network.chat.Component line : lore.lines()) {
                String t = ItemJson.clean(line.getString()).toLowerCase(java.util.Locale.ROOT);
                if (t.contains("click to claim") || t.contains("almak için tıkla") || t.contains("clic para")
                        || t.contains("нажмите")) {
                    ready = true;
                }
                if (t.contains("available in") || t.contains("kullanılabilir") || redNumber(line)) {
                    waiting = true;
                }
            }
            if (ready || waiting) {
                reward = ready && !waiting;
                rewardDate = utcDate();
            }
            return;
        }
    }

    /** A red line with a digit in it: the reward's cooldown, whatever the language. */
    private static boolean redNumber(net.minecraft.network.chat.Component line) {
        if (!line.getString().matches(".*\\d.*")) {
            return false;
        }
        boolean[] red = {false};
        line.visit((style, part) -> {
            var c = style.getColor();
            if (c != null && !part.isBlank()) {
                int v = c.getValue();
                if (((v >> 16) & 0xFF) > 0xC0 && ((v >> 8) & 0xFF) < 0x80 && (v & 0xFF) < 0x80) {
                    red[0] = true;
                }
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return red[0];
    }

    static Day current(Minecraft mc) {
        List<Objective> objs = new ArrayList<>();
        try {
            for (var o : Wyn.objectives()) {
                var score = o.score();
                objs.add(new Objective(ItemJson.clean(o.goal()), score == null ? 0 : score.current(),
                        score == null ? 0 : score.max()));
            }
        } catch (Throwable ignored) {
        }
        int tickets = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            String n = ItemJson.clean(inv.getItem(i).getHoverName().getString()).toLowerCase();
            if (n.contains("spectral ticket") || n.contains("hayalet bileti")) {
                tickets += inv.getItem(i).getCount();
            }
        }
        Boolean r = utcDate().equals(rewardDate) ? reward : null;
        return new Day(Profile.id(), Wyn.classType().name(),
                Wyn.level(), utcDate(), objs, r, tickets, System.currentTimeMillis());
    }

    private static Path file(String id) {
        return Dirs.config().resolve("characters").resolve(id).resolve("today.json");
    }

    private static void save(Day d) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("class", d.cls());
        o.addProperty("level", d.level());
        o.addProperty("date", d.date());
        var arr = new com.google.gson.JsonArray();
        for (Objective ob : d.objectives()) {
            JsonObject j = new JsonObject();
            j.addProperty("goal", ob.goal());
            j.addProperty("score", ob.score());
            j.addProperty("max", ob.max());
            arr.add(j);
        }
        o.add("objectives", arr);
        if (d.reward() != null) {
            o.addProperty("reward", d.reward());
        }
        o.addProperty("tickets", d.tickets());
        o.addProperty("seen", d.seen());
        o.add("worn", worn(Minecraft.getInstance()));
        JsonObject same = o.deepCopy();
        same.remove("seen");
        String sig = d.character() + same;
        long now = System.currentTimeMillis();
        if (sig.equals(lastSig) && now - lastWrite < 5 * 60_000L) {
            return;                             // nothing new: no disk write every 2 seconds
        }
        lastSig = sig;
        lastWrite = now;
        Files.createDirectories(file(d.character()).getParent());
        Files.writeString(file(d.character()), o.toString());
    }

    private static String lastSig = "";
    private static long lastWrite;

    /** What this character wears, slot → {name, level}, so the other characters can be checked. */
    private static JsonObject worn(Minecraft mc) {
        JsonObject o = new JsonObject();
        if (mc.player == null) {
            return o;
        }
        for (var t : List.of(Slot.HELMET, Slot.CHESTPLATE,
                Slot.LEGGINGS, Slot.BOOTS,
                Slot.RING, Slot.BRACELET,
                Slot.NECKLACE)) {
            int lowest = Integer.MAX_VALUE;
            String name = null;
            for (ItemStack st : WornGear.worn(t)) {
                var g = WynntilsItems.view(st).filter(ItemView::isGear);
                int lv = g.map(v -> v.data().level()).orElse(0);
                if (lv < lowest) {
                    lowest = lv;
                    name = g.map(v -> v.data().name()).orElse(null);
                }
            }
            JsonObject j = new JsonObject();
            j.addProperty("name", name == null ? "" : name);
            j.addProperty("level", lowest == Integer.MAX_VALUE ? 0 : lowest);
            o.add(t.name(), j);
        }
        var w = WynntilsItems.view(WornGear.bestWeapon()).filter(ItemView::isGear);
        JsonObject j = new JsonObject();
        j.addProperty("name", w.map(v -> v.data().name()).orElse(""));
        j.addProperty("level", w.map(v -> v.data().level()).orElse(0));
        o.add("WEAPON", j);
        return o;
    }

    /**
     * Another of your characters this piece would be an upgrade for: right class, wearable at
     * its level, and at least 5 levels newer than what it wears in that slot (or the slot is
     * empty). "Archer Lv. 70 (Bow, giydiği Lv. 52)". Null when none.
     */
    static String forAlt(ItemData info) {
        GameState state = GameState.current();
        int lv = info.requirements().level();
        var t = info.slot();
        try {
            for (String id : state.characterIds()) {
                var o = id.equals(state.characterId()) ? null : state.characterFile(id, "today.json");
                if (o == null) {
                    continue;
                }
                String cls = o.get("class").getAsString();
                int level = o.get("level").getAsInt();
                // too high to wear, or too far below that character's level to be worth a bank slot
                // (a Lv. 37 bracelet "for" a Lv. 107 Mage because its saved slot showed a Lv. 30 one)
                if (lv > level || lv < level - Rules.sub("alts", "maxLevelsBelow", 25) || !o.has("worn")) {
                    continue;
                }
                var need = info.requirements().klass().orElse(t.klass());
                if (need != null && need != Klass.NONE && !need.name().equals(cls)) {
                    continue;
                }
                String slot = t.isWeapon() ? "WEAPON" : t.name();
                var worn = o.getAsJsonObject("worn").getAsJsonObject(slot);
                int wornLv = worn == null ? 0 : worn.get("level").getAsInt();
                if (lv >= wornLv + 5) {
                    return cls.charAt(0) + cls.substring(1).toLowerCase() + " Lv. " + level
                            + " " + (wornLv > 0 ? L.t("wynnlabs.daily.alt_worn", wornLv) : L.t("wynnlabs.daily.alt_slot_empty"));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Every character's last saved day (the current one live), current character first. */
    static List<Day> all(Minecraft mc) {
        List<Day> out = new ArrayList<>();
        if (mc.player != null && !Profile.id().isEmpty()) {
            out.add(current(mc));
        }
        Path root = Dirs.config().resolve("characters");
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                String id = dir.getFileName().toString();
                if (id.equals(Profile.id()) || !Files.exists(dir.resolve("today.json"))) {
                    continue;
                }
                var o = JsonParser.parseString(Files.readString(dir.resolve("today.json"))).getAsJsonObject();
                List<Objective> objs = new ArrayList<>();
                o.getAsJsonArray("objectives").forEach(e -> {
                    var j = e.getAsJsonObject();
                    objs.add(new Objective(j.get("goal").getAsString(), j.get("score").getAsInt(), j.get("max").getAsInt()));
                });
                out.add(new Day(id, o.get("class").getAsString(), o.get("level").getAsInt(), o.get("date").getAsString(),
                        objs, o.has("reward") ? o.get("reward").getAsBoolean() : null, o.get("tickets").getAsInt(),
                        o.get("seen").getAsLong()));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static Component lineFor(Day d) {
        return Component.literal(d.cls().charAt(0) + d.cls().substring(1).toLowerCase() + " Lv. " + d.level());
    }
}
