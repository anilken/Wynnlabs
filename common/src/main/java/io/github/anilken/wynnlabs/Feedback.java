package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Learns from what you actually wear. When you put on something other than what the plan
 * suggested for a slot, the model's priorities lean a little towards what your choice is
 * better at (more health → survival, more XP → XP, faster → speed, harder melee → main attack).
 *
 * <p>Each nudge is 15 %, capped at three times the default, and written to rules.model so it is
 * visible and can be undone; every decision is also logged in the character's feedback.json.
 */
final class Feedback {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double STEP = 1.15;

    private Feedback() {}

    static void check(Build.Plan before, Build.Plan after) {
        if (before == null || after == null || !Settings.on("learning")) {
            return;
        }
        for (String slot : Build.SLOTS) {
            Damage.Piece was = before.worn().get(slot);
            Damage.Piece now = after.worn().get(slot);
            Damage.Piece suggested = before.pieces().get(slot);
            // Only a swap counts: a piece you wore replaced by one you were carrying
            if (now == null || was == null || was.key().equals(now.key())
                    || !before.carried().contains(now.key())) {
                continue;
            }
            if (suggested != null && suggested.key().equals(now.key())) {
                log(slot, now.name(), suggested.name(), "followed", List.of());
                continue;                       // you took the advice
            }
            learn(slot, before, now, suggested);
        }
    }

    private static void learn(String slot, Build.Plan before, Damage.Piece chosen, Damage.Piece suggested) {
        // Compare the planned build with the same build carrying your choice in that slot
        Map<String, Damage.Piece> mine = new java.util.LinkedHashMap<>(before.pieces());
        mine.put(slot, chosen);
        // scored as the plan was: with the ability tree you have now
        TreeModel.Run tree = TreeModel.run();
        Build.Eval e = TreeModel.with(tree == null ? null : tree.at(Wyn.level()),
                () -> Build.allocate(mine, Wyn.level(), before.earned()));
        if (e == null) {
            return;
        }
        Damage.Result a = before.result();
        Damage.Result b = e.result();
        List<String> nudged = new ArrayList<>();
        JsonObject model = new JsonObject();
        if (b.ehp() > a.ehp() * 1.05 && b.spellDps() <= a.spellDps()) {
            nudge(model, "survivalWeight", 0.4, nudged, L.t("wynnlabs.feedback.survival"));
        }
        if (b.xp() > a.xp()) {
            nudge(model, "xpWeight", 1.0, nudged, L.t("wynnlabs.feedback.xp_bonus"));
        }
        if (b.speed() > a.speed()) {
            nudge(model, "speedWeight", 0.3, nudged, L.t("wynnlabs.feedback.speed"));
        }
        if (b.mainDps() > a.mainDps() * 1.05 && b.spellDps() <= a.spellDps()) {
            // capped against the archetype's own weight: a Summoner's main attack stays a side act
            // (the one you lean to when the planner has none, as the class profile is made for)
            double def = ClassProfiles.mainAttackWeight(Wyn.classType().name(), TreeModel.archetype());
            nudge(model, "mainAttackWeight", def, nudged, L.t("wynnlabs.feedback.main_attack"));
        }
        // the log keeps the model weights nudged, not their names in the language of the day
        log(slot, chosen.name(), suggested == null ? null : suggested.name(), "override", new ArrayList<>(model.keySet()));
        if (nudged.isEmpty()) {
            return;
        }
        JsonObject change = new JsonObject();
        change.add("model", model);
        Rules.update(change);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(L.c("wynnlabs.feedback.assistant").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(L.c("wynnlabs.feedback.noted", chosen.name(), String.join(", ", nudged))
                            .withStyle(ChatFormatting.GRAY)), false);
        }
    }

    static void nudge(JsonObject model, String key, double def, List<String> nudged, String label) {
        double now = Rules.sub("model", key, def);
        // small steps, and never past rules.model.nudgeCap × the default (3× let speed drown out damage)
        double next = Math.min(def * Rules.sub("model", "nudgeCap", 1.5), now * STEP);
        if (next > now) {
            model.addProperty(key, Math.round(next * 1000) / 1000.0);
            nudged.add(label);
        }
    }

    private static void log(String slot, String chosen, String suggested, String kind, List<String> nudged) {
        try {
            Path f = Profile.dir().resolve("feedback.json");
            JsonArray arr = Files.exists(f)
                    ? GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonArray.class) : new JsonArray();
            JsonObject j = new JsonObject();
            j.addProperty("at", java.time.Instant.now().toString());
            j.addProperty("slot", slot);
            j.addProperty("chosen", chosen);
            j.addProperty("suggested", suggested);
            j.addProperty("kind", kind);
            JsonArray n = new JsonArray();
            nudged.forEach(n::add);
            j.add("nudged", n);
            arr.add(j);
            while (arr.size() > 200) {
                arr.remove(0);
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(arr), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save feedback.json: " + e);
        }
    }
}
