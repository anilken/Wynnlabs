package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.util.List;

/**
 * A character's build choices, made in the game: the archetype the ability tree plan follows and
 * the goal preset (Damage, Balanced, Safe; a role: Tank, Healer; a play style: Boss, Mobbing, XP,
 * Loot) that weighs survival, healing
 * and damage against each other, the same way for every class. A new character
 * gets both automatically, so advice works from the first login with no outside help.
 */
final class Setup {

    static final List<String> GOALS = List.of("DAMAGE", "BALANCED", "SAFE", "TANK", "HEALER", "BOSS", "MOBBING",
            "XP", "LOOT");

    private Setup() {}

    /**
     * A character seen for the first time: the archetype it already leans to (most unlocked
     * nodes; the tree's first when nothing is unlocked yet) and the Damage goal.
     */
    static void newCharacter() {
        String arch = TreePlanner.mostUnlocked();
        JsonObject tree = new JsonObject();
        tree.addProperty("archetype", arch);
        JsonObject change = new JsonObject();
        change.add("tree", tree);
        Rules.update(change);
        ClassProfiles.apply(arch, true);
        setGoal("DAMAGE");
        System.out.println("[WynnLabs] new character: archetype " + (arch.isEmpty() ? "-" : arch) + ", goal DAMAGE");
    }

    static String goal() {
        String g = Rules.get().has("goalPreset") ? Rules.get().get("goalPreset").getAsString() : "DAMAGE";
        return GOALS.contains(g) ? g : "DAMAGE";
    }

    /** The goal's name in the game's language. */
    static String goalName(String preset) {
        return switch (preset) {
            case "BALANCED" -> L.t("wynnlabs.setup.goal_balanced");
            case "SAFE" -> L.t("wynnlabs.setup.goal_safe");
            case "TANK" -> L.t("wynnlabs.setup.goal_tank");
            case "HEALER" -> L.t("wynnlabs.setup.goal_healer");
            case "BOSS" -> L.t("wynnlabs.setup.goal_boss");
            case "MOBBING" -> L.t("wynnlabs.setup.goal_mobbing");
            case "XP" -> L.t("wynnlabs.setup.goal_xp");
            case "LOOT" -> L.t("wynnlabs.setup.goal_loot");
            default -> L.t("wynnlabs.setup.goal_damage");
        };
    }

    /**
     * Writes a preset's rule overrides through the same merge as POST /rules: how much the
     * build score weighs survival, health regen, mana, healing and damage, and how many levels
     * above a quest you want to be. Every preset writes every weight, so a switch leaves nothing
     * of the last one behind.
     */
    static void setGoal(String preset) {
        // survival, regen, mana, heal, damage, survival power, then the goal's factors on the XP and
        // walk speed weights (feedback nudges those, so the goal multiplies them instead of
        // overwriting), the room above the speed ceiling, burst and loot
        double[] w = switch (preset) {
            case "BALANCED" -> new double[] {0.8, 0.2, 0.3, 0, 1.0, 0, 1, 1, 1, 0, 0};
            case "SAFE" -> new double[] {1.4, 0.3, 0.3, 0, 1.0, 0, 1, 1, 1, 0, 0};
            // a tank holds the line: health counts in full, damage little
            case "TANK" -> new double[] {1.4, 0.6, 0.3, 0, 0.4, 1.0, 1, 1, 1, 0, 0};
            // a healer keeps casting heals: max health × Healing Efficiency × casts, then survival
            case "HEALER" -> new double[] {1.0, 0.3, 0.6, 1.5, 0.3, 0, 1, 1, 1, 0, 0};
            // one big target: the hit per cast, walk speed and XP matter less
            case "BOSS" -> new double[] {0.6, 0.2, 0.3, 0, 1.0, 0, 0.5, 0.5, 0.6, 1.2, 0};
            // many weak targets: run from pack to pack and keep casting
            case "MOBBING" -> new double[] {0.5, 0.3, 0.6, 0, 1.0, 0, 1, 2.3, 2.0, 0, 0};
            // levelling: XP bonus and walk speed, enough damage and health to keep killing
            case "XP" -> new double[] {0.5, 0.2, 0.3, 0, 0.8, 0, 3, 2.0, 1.7, 0, 0};
            // loot runs: Loot Bonus and Loot Quality
            case "LOOT" -> new double[] {0.5, 0.2, 0.3, 0, 0.8, 0, 0.5, 1.3, 1.4, 0, 2.0};
            default -> new double[] {0.4, 0.2, 0.3, 0, 1.0, 0, 1, 1, 1, 0, 0};
        };
        int questMargin = switch (preset) {
            case "BALANCED", "TANK", "BOSS" -> 5;
            case "SAFE", "HEALER" -> 7;
            default -> 4;
        };
        String[] keys = {"survivalWeight", "regenWeight", "manaWeight", "healWeight", "damageWeight", "survivalPower",
            "goalXp", "goalSpeed", "goalSpeedRoom", "burstWeight", "lootWeight"};
        JsonObject model = new JsonObject();
        for (int i = 0; i < keys.length; i++) {
            model.addProperty(keys[i], w[i]);
        }
        JsonObject change = new JsonObject();
        change.addProperty("goalPreset", preset);
        change.addProperty("goal", preset.toLowerCase(java.util.Locale.ROOT) + " build"
                + (TreePlanner.chosen().isEmpty() ? "" : ", " + TreePlanner.chosen()));
        change.addProperty("questLevelMargin", questMargin);
        change.add("model", model);
        Rules.update(change);
    }

    static void cycleGoal() {
        setGoal(GOALS.get((GOALS.indexOf(goal()) + 1) % GOALS.size()));
    }

    private static String hintedFor = "";
    private static int tick;

    /**
     * Once per character: if its skill points were never read from Character Info, say so; until
     * then skill advice is marked approximate. A marker file keeps it from coming back.
     */
    static void onTick(Minecraft mc) {
        if (++tick % 200 != 0 || mc.player == null || !Profile.settled()) {
            return;
        }
        String id = Profile.id();
        if (id.isEmpty() || id.equals(hintedFor)) {
            return;
        }
        hintedFor = id;
        try {
            var marker = Profile.dir().resolve("skills-hint-shown");
            if (MenuWatcher.skillsKnown() || Files.exists(marker)) {
                return;
            }
            Coach.toast(L.c("wynnlabs.setup.open_character_info").withStyle(ChatFormatting.YELLOW));
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "shown");
        } catch (Exception ignored) {
            // a hint only
        }
    }
}
