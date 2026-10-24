package io.github.anilken.wynnlabs.api;

import io.github.anilken.wynnlabs.coach.Today;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The link to an AI assistant, for the settings' "AI assistant" panel: whether the local API
 * listens and is being used (last request, today's count), the endpoint table the API's index
 * also serves, and the skill file the mod ships, exported or installed for Claude Code only when
 * the player clicks.
 */
public final class AiLink {

    /** One read endpoint: path, its English line for the API index, its language key for the panel. */
    public record Endpoint(String path, String en, String key) {}

    public static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("/status", "in game?, class, combat level", "wynnlabs.ai.ep_status"),
            new Endpoint("/equipped", "armour, accessories, weapon in hand", "wynnlabs.ai.ep_equipped"),
            new Endpoint("/inventory", "all 36 inventory slots", "wynnlabs.ai.ep_inventory"),
            new Endpoint("/character", "class, level, skill points per skill (assigned / gear / total)", "wynnlabs.ai.ep_character"),
            new Endpoint("/abilities", "ability points, unlocked abilities, the class's whole tree", "wynnlabs.ai.ep_abilities"),
            new Endpoint("/build", "best build from worn and carried gear with skill points", "wynnlabs.ai.ep_build"),
            new Endpoint("/damage", "model vs measured damage per cast, where the DPS comes from, what-ifs, live DPS meter",
                    "wynnlabs.ai.ep_damage"),
            new Endpoint("/roadmap", "milestones ahead: gear to get, ability nodes, places to level", "wynnlabs.ai.ep_roadmap"),
            new Endpoint("/roadmap/trace", "why each candidate for a slot was kept or dropped (?level=120&slot=RING)", "wynnlabs.ai.ep_trace"),
            new Endpoint("/screenshot", "the game's screen as a PNG", "wynnlabs.ai.ep_screenshot"),
            new Endpoint("/shopping", "best gear per slot to buy or hunt for your level and build", "wynnlabs.ai.ep_shopping"),
            new Endpoint("/quests", "tracked quest and every quest from the quest book", "wynnlabs.ai.ep_quests"),
            new Endpoint("/grind", "caves, dungeons, boss altars and grind spots near your level", "wynnlabs.ai.ep_grind"),
            new Endpoint("/discoveries", "secret discoveries in the chosen order: done, level, distance, requirements",
                    "wynnlabs.ai.ep_discoveries"),
            new Endpoint("/vault", "every bank page you opened, with verdicts and what to sell", "wynnlabs.ai.ep_vault"),
            new Endpoint("/prices", "Trade Market prices the mod knows", "wynnlabs.ai.ep_prices"),
            new Endpoint("/characters", "every character seen, with class, level and goal", "wynnlabs.ai.ep_characters"),
            new Endpoint("/rules", "the rules behind the verdicts (POST merges, DELETE resets; needs writing on)", "wynnlabs.ai.ep_rules"),
            new Endpoint("/advice", "your own verdicts (POST {items:[{key,verdict,note}]}; needs writing on)", "wynnlabs.ai.ep_advice"),
            new Endpoint("/compass", "point the compass and map pin: POST {x,y,z,label} or {points:[{x,y,z,label}]} (one at a time; with keep:true all at once and lasting), DELETE clears (?keep=1 the lasting ones); needs writing on",
                    "wynnlabs.ai.ep_compass"));

    private AiLink() {}

    // ---- the server and its traffic ----

    /** "off", "listening" or "busy" (the port could not be opened; see {@link #error}). */
    public static volatile String state = "off";
    static volatile String error;
    public static volatile int port = 47591;
    private static volatile long lastAt;
    private static volatile String lastPath = "";
    private static volatile int today;
    private static volatile String day = "";

    /** Called for every API request. */
    static void seen(String path) {
        String d = Today.utcDate();
        if (!d.equals(day)) {
            day = d;
            today = 0;
        }
        today++;
        lastAt = System.currentTimeMillis();
        lastPath = path;
    }

    public static long lastAt() {
        return lastAt;
    }

    public static String lastPath() {
        return lastPath;
    }

    public static int today() {
        return Today.utcDate().equals(day) ? today : 0;
    }

    public static String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    // ---- the skill file ----

    /**
     * The shipped skill (assets/wynnlabs/ai/SKILL.md, copied from ai/skills/wynnlabs at build),
     * with its address on the port in use: the file is written for the default 47591.
     */
    static String skill() throws Exception {
        try (InputStream in = AiLink.class.getResourceAsStream("/assets/wynnlabs/ai/SKILL.md")) {
            if (in == null) {
                throw new IllegalStateException("SKILL.md missing from the mod");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("127.0.0.1:47591", "127.0.0.1:" + port);
        }
    }

    /** Writes the skill under config/wynnlabs/ai/wynnlabs/; returns the file. */
    public static Path export() throws Exception {
        Path f = Dirs.config().resolve("ai").resolve("wynnlabs").resolve("SKILL.md");
        Files.createDirectories(f.getParent());
        Files.writeString(f, skill(), StandardCharsets.UTF_8);
        return f;
    }

    /** Copies the skill into Claude Code's skills folder (only its own wynnlabs folder); returns the file. */
    public static Path installClaude() throws Exception {
        Path f = Path.of(System.getProperty("user.home"), ".claude", "skills", "wynnlabs", "SKILL.md");
        Files.createDirectories(f.getParent());
        Files.writeString(f, skill(), StandardCharsets.UTF_8);
        return f;
    }

    /** A short instruction block for any chat assistant that can call a local URL. */
    public static String prompt() {
        StringBuilder eps = new StringBuilder();
        for (Endpoint e : ENDPOINTS) {
            eps.append("- GET ").append(e.path()).append(": ").append(e.en()).append('\n');
        }
        return L.t("wynnlabs.ai.prompt_text", url(), eps.toString().strip());
    }
}
