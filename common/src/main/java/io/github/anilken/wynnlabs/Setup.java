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
     * nodes; none yet below Lv 10 with no archetype node, see {@link TreePlanner#mostUnlocked})
     * and the Damage goal. With none, rules.tree.guess asks {@link #reguess} to pick it once the
     * first archetype node is bought.
     */
    static void newCharacter() {
        String arch = TreePlanner.mostUnlocked();
        JsonObject tree = new JsonObject();
        tree.addProperty("archetype", arch);
        if (arch.isEmpty()) {
            tree.addProperty("guess", 1);
        }
        JsonObject change = new JsonObject();
        change.add("tree", tree);
        Rules.update(change);
        ClassProfiles.apply(arch, true);
        setGoal("DAMAGE");
        System.out.println("[WynnLabs] new character: archetype " + (arch.isEmpty() ? "-" : arch) + ", goal DAMAGE");
    }

    /**
     * A character that leaned to no archetype yet (rules.tree.guess): once it does (an archetype
     * node bought, or Lv 10 reached), the archetype is set and the class profile follows it, as
     * for a new character. Returns the archetype set, or "" when nothing changed. Choosing an
     * archetype yourself ends the guessing ({@link TreePlanner#cycle}).
     */
    static String reguess() {
        if (Rules.sub("tree", "guess", 0) <= 0 || !TreePlanner.chosen().isEmpty()) {
            return "";
        }
        String arch = TreePlanner.mostUnlocked();
        if (arch.isEmpty()) {
            return "";
        }
        JsonObject tree = new JsonObject();
        tree.addProperty("archetype", arch);
        tree.add("guess", com.google.gson.JsonNull.INSTANCE);
        JsonObject change = new JsonObject();
        change.add("tree", tree);
        Rules.update(change);
        ClassProfiles.apply(arch, false);
        System.out.println("[WynnLabs] archetype now " + arch);
        return arch;
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
     * of the last one behind. What deaths taught (rules.model.survivalLearned) is a key of its own
     * and stays.
     */
    static void setGoal(String preset) {
        double[] w = weights(preset);
        int questMargin = switch (preset) {
            case "BALANCED", "TANK", "BOSS" -> 5;
            case "SAFE", "HEALER" -> 7;
            default -> 4;
        };
        String[] keys = keys();
        JsonObject model = new JsonObject();
        for (int i = 0; i < keys.length; i++) {
            model.addProperty(keys[i], w[i]);
        }
        model.addProperty("survivalModel", SURVIVAL_MODEL);
        JsonObject change = new JsonObject();
        change.addProperty("goalPreset", preset);
        change.addProperty("goal", preset.toLowerCase(java.util.Locale.ROOT) + " build"
                + (TreePlanner.chosen().isEmpty() ? "" : ", " + TreePlanner.chosen()));
        change.addProperty("questLevelMargin", questMargin);
        change.add("model", model);
        Rules.update(change);
    }

    /** The model weights a goal writes, in {@link #weights} order. */
    private static String[] keys() {
        return new String[] {"survivalWeight", "regenWeight", "manaWeight", "healWeight", "damageWeight",
            "survivalPower", "goalXp", "goalSpeed", "goalSpeedRoom", "burstWeight", "lootWeight", "ehpFloorScale",
            "xpLog"};
    }

    /** The survival weights' version: rules written before it get their goal's survival weights again ({@link #upgrade}). */
    static final int SURVIVAL_MODEL = 2;

    /**
     * A preset's model weights: survival, regen, mana, heal, damage, survival power, then the
     * goal's factors on the XP and walk speed weights (feedback nudges those, so the goal
     * multiplies them instead of overwriting), the room above the speed ceiling, burst, loot, the
     * health floor's scale (Damage.ehpFloor) and whether XP counts with diminishing returns (1)
     * or in a straight line (0). Health counts twice, as a log that flattens (survival) and a
     * power that keeps paying (survival power), so each is about half what it was alone.
     */
    static double[] weights(String preset) {
        return switch (preset) {
            case "BALANCED" -> new double[] {0.4, 0.2, 0.3, 0, 1.0, 0.4, 1, 1, 1, 0, 0, 1.15, 0};
            case "SAFE" -> new double[] {0.7, 0.3, 0.3, 0, 1.0, 0.6, 1, 1, 1, 0, 0, 1.3, 1};
            // a tank holds the line: health counts in full, damage little
            case "TANK" -> new double[] {1.4, 0.6, 0.3, 0, 0.4, 1.0, 1, 1, 1, 0, 0, 1.6, 0};
            // a healer keeps casting heals: max health × Healing Efficiency × casts, then survival
            case "HEALER" -> new double[] {0.5, 0.3, 0.6, 1.5, 0.3, 0.4, 1, 1, 1, 0, 0, 1.3, 0};
            // one big target: the hit per cast, walk speed and XP matter less
            case "BOSS" -> new double[] {0.3, 0.2, 0.3, 0, 1.0, 0.3, 0.5, 0.5, 0.6, 1.2, 0, 1.15, 1};
            // many weak targets: run from pack to pack and keep casting
            case "MOBBING" -> new double[] {0.25, 0.3, 0.6, 0, 1.0, 0.25, 1, 2.3, 2.0, 0, 0, 1.0, 0};
            // levelling: XP bonus and walk speed, enough damage and health to keep killing
            case "XP" -> new double[] {0.25, 0.2, 0.3, 0, 0.8, 0.2, 3, 2.0, 1.7, 0, 0, 1.0, 0};
            // loot runs: Loot Bonus and Loot Quality
            case "LOOT" -> new double[] {0.25, 0.2, 0.3, 0, 0.8, 0.2, 0.5, 1.3, 1.4, 0, 2.0, 1.0, 0};
            default -> new double[] {0.2, 0.2, 0.3, 0, 1.0, 0.25, 1, 1, 1, 0, 0, 1.0, 1};
        };
    }

    /**
     * As the rules are read: rules from before {@link #SURVIVAL_MODEL} get their goal's survival
     * weights, health floor and XP curve (a weight set by hand, rules.model.byHand, stays). A
     * survival weight the death coach had raised over its base becomes rules.model.survivalLearned,
     * so that lesson is kept.
     */
    static void upgrade(JsonObject rules) {
        JsonObject model = rules.getAsJsonObject("model");
        if (model == null || model.has("survivalModel") && model.get("survivalModel").getAsInt() >= SURVIVAL_MODEL) {
            return;
        }
        String g = rules.has("goalPreset") ? rules.get("goalPreset").getAsString() : "DAMAGE";
        double[] w = weights(GOALS.contains(g) ? g : "DAMAGE");
        java.util.Set<String> byHand = new java.util.HashSet<>();
        if (model.has("byHand") && model.get("byHand").isJsonArray()) {
            model.getAsJsonArray("byHand").forEach(e -> byHand.add(e.getAsString()));
        }
        if (model.has("survivalWeightBase") && model.has("survivalWeight")) {
            double base = model.get("survivalWeightBase").getAsDouble();
            double now = model.get("survivalWeight").getAsDouble();
            if (base > 0 && now > base * 1.01) {
                model.addProperty("survivalLearned", Math.round(Math.min(2, now / base) * 1000) / 1000.0);
                model.addProperty("survivalLearnedAt", System.currentTimeMillis());
            }
            model.remove("survivalWeightBase");
        }
        List<String> keys = List.of(keys());
        for (String k : new String[] {"survivalWeight", "survivalPower", "ehpFloorScale", "xpLog"}) {
            if (!byHand.contains(k)) {
                model.addProperty(k, w[keys.indexOf(k)]);
            }
        }
        model.addProperty("survivalModel", SURVIVAL_MODEL);
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
