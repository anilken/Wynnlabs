package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * One folder per character ({@code config/wynnlabs/characters/<id>/}) for its rules and
 * advice, switched when you pick another character, plus the level-up guide in chat.
 *
 * <p>Files from before profiles existed ({@code config/wynnlabs/rules.json}, advice.json)
 * move to the first character whose class matches their "class" field.
 */
final class Profile {

    /** What a character id keeps in a folder name. */
    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[^A-Za-z0-9_-]");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile String current = "";
    private static volatile String currentClass = "";
    private static int lastLevel = -1;
    private static int tick;

    private Profile() {}

    private static Path root() {
        return Dirs.config();
    }

    /** Where this character's files live; the old shared folder until a character is known. */
    static Path dir() {
        return current.isEmpty() ? root() : root().resolve("characters").resolve(current);
    }

    static String id() {
        return current;
    }

    static void onTick(Minecraft mc) {
        if (++tick % 20 != 0 || mc.player == null) {
            return;
        }
        try {
            if (!Wyn.hasCharacter()) {
                return;
            }
            String id = Wyn.characterId();
            // Wynntils says "-" until it has read the character; wait for the real id
            if (id == null || id.isBlank() || id.equals("-")) {
                return;
            }
            id = SAFE_ID.matcher(id).replaceAll("_");
            if (id.equals(current)) {
                levelGuide(mc);
                return;
            }
            current = id;
            // name(), not toString(): a reskinned Shaman prints as "Shaman/Skyseer"
            currentClass = Wyn.classType().name();
            migrate();
            Rules.load();
            Advice.load();
            MenuWatcher.reset();
            ManaMeter.reset();
            // A character seen for the first time gets its class profile from the tree data
            if (Rules.fresh || Rules.get().has("goal") && Rules.get().get("goal").getAsString().startsWith("unset")) {
                Setup.newCharacter();
            } else if (!Rules.get().has("model")
                    || !Rules.get().getAsJsonObject("model").has("mainAttackClassMult")) {
                // characters made before the per-class model settings: give them theirs once
                ClassProfiles.apply(TreePlanner.chosen(), false);
            }
            lastLevel = -1;
            Wyn.selfTest();                         // which Wynntils features this version still has
            writeMeta();
            System.out.println("[WynnLabs] profile " + current + " (" + currentClass + ")");
        } catch (Throwable t) {
            System.out.println("[WynnLabs] profile switch failed: " + t);
        }
    }

    /** Hands the shared pre-profile files to the first character of the class they were made for. */
    private static void migrate() throws Exception {
        Path rules = root().resolve("rules.json");
        Path dir = dir();
        if (Files.exists(dir.resolve("rules.json")) || !Files.exists(rules)) {
            return;
        }
        JsonObject r = GSON.fromJson(Files.readString(rules, StandardCharsets.UTF_8), JsonObject.class);
        String forClass = r.has("class") ? r.get("class").getAsString() : "";
        if (!forClass.equalsIgnoreCase(currentClass)) {
            return;
        }
        Files.createDirectories(dir);
        for (String name : List.of("rules.json", "advice.json")) {
            Path from = root().resolve(name);
            if (Files.exists(from)) {
                Files.move(from, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void writeMeta() {
        try {
            JsonObject m = new JsonObject();
            m.addProperty("id", current);
            m.addProperty("class", currentClass);
            m.addProperty("reskinned", Wyn.reskinned());
            m.addProperty("level", Wyn.level());
            m.addProperty("lastSeen", java.time.Instant.now().toString());
            m.addProperty("goal", Rules.get().has("goal") ? Rules.get().get("goal").getAsString() : "");
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("meta.json"), GSON.toJson(m), StandardCharsets.UTF_8);
            Vault.knownClass(dir().getFileName().toString(), currentClass);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save meta.json: " + e);
        }
    }

    /**
     * True once the profile is the character you play: right after a switch Wynntils can
     * already report the new class while the id is still the old one (or the other way round),
     * and files written then land in the wrong character's folder.
     */
    static boolean settled() {
        try {
            return !current.isEmpty() && Wyn.hasCharacter()
                    && currentClass.equals(Wyn.classType().name())
                    && current.equals(SAFE_ID.matcher(Wyn.characterId()).replaceAll("_"));
        } catch (Throwable t) {
            return false;
        }
    }

    static String currentClass() {
        return currentClass;
    }

    /** Every character seen so far, from their meta.json files. */
    static JsonObject list() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        Path chars = root().resolve("characters");
        if (Files.isDirectory(chars)) {
            try (Stream<Path> dirs = Files.list(chars)) {
                dirs.forEach(d -> {
                    try {
                        Path meta = d.resolve("meta.json");
                        if (Files.exists(meta)) {
                            JsonObject m = GSON.fromJson(Files.readString(meta, StandardCharsets.UTF_8), JsonObject.class);
                            m.addProperty("active", d.getFileName().toString().equals(current));
                            arr.add(m);
                        }
                    } catch (Exception ignored) {
                        // skip a broken folder
                    }
                });
            } catch (Exception ignored) {
                // no characters yet
            }
        }
        o.addProperty("active", current);
        o.add("characters", arr);
        return o;
    }

    // ---- level-up guide -------------------------------------------------------------------

    /** On a level up, a short to-do list in your own chat (client side only). */
    private static void levelGuide(Minecraft mc) {
        int level = Wyn.level();
        if (level <= 0) {
            return;
        }
        if (lastLevel < 0 || level <= lastLevel) {
            lastLevel = Math.max(lastLevel, level);
            return;
        }
        int from = lastLevel;
        lastLevel = level;
        writeMeta();
        if (!Settings.on("chatLevelUp")) {
            Advice.promoteAbilities(level);     // the tree still moves on, just no chat
            return;
        }
        List<String> todo = new ArrayList<>();

        // Ability points: plan nodes whose level has come
        int apNow = Wyn.abilityPointsAt(level);
        int apBefore = Wyn.abilityPointsAt(from);
        List<String> ready = Advice.promoteAbilities(level);
        if (!ready.isEmpty()) {
            todo.add(L.t("wynnlabs.profile.levelup_abilities", String.join(", ", ready)));
        } else if (apNow > apBefore) {
            todo.add(L.t("wynnlabs.profile.levelup_ability_points", apNow - apBefore));
        }

        // Skill points: to the build skill that has the fewest
        List<String> preferred = Rules.list("shopping", "preferredSkills");
        SkillKind lowest = null;
        int lowestPts = Integer.MAX_VALUE;
        for (SkillKind s : SkillKind.values()) {
            if (!preferred.contains(s.name())) {
                continue;
            }
            int pts = MenuWatcher.assigned(s);
            if (pts < lowestPts) {
                lowest = s;
                lowestPts = pts;
            }
        }
        int sp = (Math.min(level, 101) - Math.min(from, 101)) * 2;   // 2 a level, none after 101
        if (sp > 0) {
            todo.add(L.t("wynnlabs.profile.levelup_skill_points", sp,
                    lowest == null ? L.t("wynnlabs.profile.levelup_build_skills") : lowest.displayName()));
        }

        // Gear that becomes wearable now
        List<String> gear = new ArrayList<>();
        for (Shopping.Pick p : Shopping.list()) {
            int lv = p.info().requirements().level();
            if (lv > from && lv <= level && p.rank() == 1) {
                gear.add(p.info().name() + " (" + Shopping.where(p.source()) + ")");
            }
        }
        if (!gear.isEmpty()) {
            todo.add(L.t("wynnlabs.profile.levelup_wearable", String.join(", ", gear.subList(0, Math.min(3, gear.size())))));
        }

        // Where to level next
        List<Grind.Spot> spots = Grind.near(level, 2);
        if (!spots.isEmpty()) {
            List<String> names = new ArrayList<>();
            spots.forEach(s -> names.add(Grind.line(s)));
            todo.add(L.t("wynnlabs.profile.levelup_grind", String.join("; ", names)));
        }

        mc.player.displayClientMessage(L.c("wynnlabs.profile.levelup_title", level)
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), false);
        for (String t : todo) {
            mc.player.displayClientMessage(Component.literal("  • ").withStyle(ChatFormatting.DARK_PURPLE)
                    .append(Component.literal(t).withStyle(ChatFormatting.GRAY)), false);
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
