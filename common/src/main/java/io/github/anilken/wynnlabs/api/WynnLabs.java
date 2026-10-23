package io.github.anilken.wynnlabs.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.advice.SellSummary;
import io.github.anilken.wynnlabs.advice.WearCheck;
import io.github.anilken.wynnlabs.build.Around;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.ClassProfiles;
import io.github.anilken.wynnlabs.build.DamageReport;
import io.github.anilken.wynnlabs.build.Setup;
import io.github.anilken.wynnlabs.build.TreePlanner;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.coach.Coach;
import io.github.anilken.wynnlabs.coach.Danger;
import io.github.anilken.wynnlabs.coach.DeathCoach;
import io.github.anilken.wynnlabs.coach.DpsMeter;
import io.github.anilken.wynnlabs.coach.ManaMeter;
import io.github.anilken.wynnlabs.coach.Today;
import io.github.anilken.wynnlabs.coach.XpMeter;
import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Perf;
import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.core.Stamp;
import io.github.anilken.wynnlabs.core.UpdateCheck;
import io.github.anilken.wynnlabs.endgame.AspectBook;
import io.github.anilken.wynnlabs.endgame.LootrunLog;
import io.github.anilken.wynnlabs.endgame.RaidLog;
import io.github.anilken.wynnlabs.endgame.RaidPool;
import io.github.anilken.wynnlabs.game.Fixture;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.MenuWatcher;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.game.WornGear;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.game.WynntilsEvents;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.quests.Discoveries;
import io.github.anilken.wynnlabs.quests.DiscoveryBook;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.Waypoints;
import io.github.anilken.wynnlabs.ui.DpsHud;
import io.github.anilken.wynnlabs.ui.GroundDebug;
import io.github.anilken.wynnlabs.ui.GroundMarkers;
import io.github.anilken.wynnlabs.ui.MobTracker;
import io.github.anilken.wynnlabs.ui.HotbarBadges;
import io.github.anilken.wynnlabs.ui.RoadmapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Read-only local HTTP API for your own Wynncraft inventory.
 *
 * <p>Binds to 127.0.0.1 only, so nothing outside this PC can reach it. It never sends packets,
 * clicks slots or changes anything in game: it only reads what the client already shows.
 *
 * <pre>
 *   GET /           endpoint list
 *   GET /status     in game?, class, combat level
 *   GET /equipped   armour, accessories, weapon in hand
 *   GET /inventory  all 36 inventory slots
 *   GET /character  class, level, skill points
 *   GET /abilities  ability points, unlocked abilities, the whole tree of the class
 *   GET /damage     model vs measured damage, DPS breakdown, what-ifs, live DPS meter
 *   GET /quests     tracked quest, quest book (status, level, requirements, next task)
 *   GET /discoveries  secret discoveries in the chosen order: done, level, distance, requirements
 *   GET /advice     advice shown in tooltips
 *   POST /advice    {"items":[{"key","verdict","note"}]}  (header X-Wynninv: 1)
 *   DELETE /advice  clear all advice                   (header X-Wynninv: 1)
 * </pre>
 *
 * The only thing a write changes is text at the bottom of your own tooltips. Writes need the
 * {@code X-Wynninv} header, so a web page in your browser can't post to this port.
 *
 * Port: 47591, or the system property {@code wynninv.port}.
 */
public final class WynnLabs {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private WynnLabs() {}

    /**
     * The mod's start-up, the same on every loader: the loader's entrypoint calls it once with its
     * {@link Platform}, and every listener is registered through that, in this order.
     */
    public static void init(Platform platform) {
        Platform.install(platform);
        Dirs.migrate();                         // before anything reads config
        try {
            var marker = Dirs.config().resolve("ai-write-migrated");
            if (!java.nio.file.Files.exists(marker)) {
                if (Settings.on("localApi")) {
                    Settings.set("apiWrite", true);  // the API was already in use: keep its tools writing
                }
                java.nio.file.Files.createDirectories(marker.getParent());
                java.nio.file.Files.writeString(marker, "done");
            }
        } catch (Exception ignored) {
        }
        Advice.load();
        Rules.load();
        // read in the background now, so tooltips never read these files
        Vault.preloadClasses();
        Prices.key();
        platform.onTooltip((stack, lines) -> {
            long t = Perf.start();
            try {
                lines.addAll(Advice.linesCached(stack));
                // where the item comes from, one short line (setting sourceHint)
                var from = io.github.anilken.wynnlabs.advice.SourceHint.line(stack);
                if (from != null) {
                    lines.add(from);
                }
            } catch (Throwable ignored) {
                // a broken advice line must never break the tooltip
            } finally {
                Perf.end("tooltip.loader", t);
            }
        });
        // the timing sections follow the Debug setting (read once a tick)
        platform.onEndTick(Perf::onTick);
        // what the player carries, as one number caches compare against (before anything reads it)
        platform.onEndTick(Perf.tick("tick.Stamp", Stamp::onTick));
        platform.onEndTick(Perf.tick("tick.MenuWatcher", MenuWatcher::onTick));
        platform.onEndTick(Perf.tick("tick.Profile", Profile::onTick));
        platform.onEndTick(Perf.tick("tick.Setup", Setup::onTick));
        platform.onEndTick(Perf.tick("tick.Wyn", Wyn::onTick));
        platform.onEndTick(Perf.tick("tick.Prices", Prices::onTick));
        platform.onEndTick(Perf.tick("tick.Build", Build::onTick));
        // the road map is built only when asked (the J menu's button, its refresh, a setting you
        // change there); in the background only the applied plan's level-up check runs
        platform.onEndTick(Perf.tick("tick.Roadmap", client -> {
            if (client.player != null && client.player.tickCount % 40 == 0) {
                Around.watch();                     // only with a plan: the better-build check after a level-up
            }
            // model weights brought back under their cap at load: say so once
            java.util.List<String> clamped = Rules.clamped;
            if (client.player != null && !clamped.isEmpty()) {
                Rules.clamped = java.util.List.of();
                client.player.displayClientMessage(L.c("wynnlabs.feedback.assistant").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)
                        .append(L.c("wynnlabs.feedback.clamped", String.join(", ", clamped))
                                .withStyle(net.minecraft.ChatFormatting.GRAY)), false);
            }
        }));
        platform.onEndTick(Perf.tick("tick.ManaMeter", ManaMeter::onTick));
        platform.onEndTick(Perf.tick("tick.TreePlanner", TreePlanner::onTick));
        platform.onEndTick(Perf.tick("tick.GroundMarkers", GroundMarkers::onTick));
        platform.onEndTick(Perf.tick("tick.MobTracker", MobTracker::onTick));
        platform.onEndTick(Perf.tick("tick.QuestBook", QuestBook::onTick));
        platform.onEndTick(Perf.tick("tick.AutoAdvice", WornGear::snapshotArmour));
        platform.onGameMessage(QuestBook::onChat);
        platform.onGameMessage(DeathCoach::onChat);
        platform.onEndTick(Perf.tick("tick.DeathCoach", DeathCoach::onTick));
        platform.onEndTick(Perf.tick("tick.Today", Today::onTick));
        platform.onEndTick(Perf.tick("tick.WearCheck", WearCheck::onTick));
        // once per game run, a few seconds into a world: a chat line when GitHub has a newer release
        platform.onEndTick(Perf.tick("tick.UpdateCheck", UpdateCheck::onTick));
        platform.onEndTick(Perf.tick("tick.DiscoveryBook", DiscoveryBook::onTick));
        platform.onEndTick(Perf.tick("tick.Danger", Danger::onTick));
        platform.onEndTick(Perf.tick("tick.SellSummary", SellSummary::onTick));
        platform.onEndTick(Perf.tick("tick.XpMeter", XpMeter::onTick));
        platform.onEndTick(Perf.tick("tick.Vault", Vault::onTick));
        platform.onEndTick(Perf.tick("tick.Coach", Coach::onTick));
        // the Endgame tab: raid and lootrun history, the reward pool seen, the aspect list
        platform.onEndTick(Perf.tick("tick.RaidLog", RaidLog::onTick));
        platform.onEndTick(Perf.tick("tick.RaidPool", RaidPool::onTick));
        platform.onEndTick(Perf.tick("tick.LootrunLog", LootrunLog::onTick));
        platform.onEndTick(Perf.tick("tick.AspectBook", AspectBook::onTick));
        // the coach's HUD: the death warning and the hunt line
        platform.addHud(net.minecraft.resources.Identifier.fromNamespaceAndPath("wynnlabs", "coach"), (g, dt) -> {
            long t = Perf.start();
            try {
                Coach.renderHud(g, dt);
            } finally {
                Perf.end("hud.Coach", t);
            }
        });
        // the live DPS meter in a corner (setting dpsHud, off by default)
        platform.addHud(net.minecraft.resources.Identifier.fromNamespaceAndPath("wynnlabs", "dps"), (g, dt) -> {
            long t = Perf.start();
            try {
                DpsHud.render(g, dt);
            } finally {
                Perf.end("hud.DpsHud", t);
            }
        });
        // the verdict badges on the hotbar's items (setting hotbarBadges), after the hotbar is drawn
        platform.addHud(net.minecraft.resources.Identifier.fromNamespaceAndPath("wynnlabs", "hotbar_badges"), (g, dt) -> {
            long t = Perf.start();
            try {
                HotbarBadges.render(g, dt);
            } catch (Throwable ignored) {
                // a badge must never break the HUD
            } finally {
                Perf.end("hud.HotbarBadges", t);
            }
        });
        // tracked mobs: the notice on the HUD, the markers in the world (only in plain sight)
        platform.addHud(net.minecraft.resources.Identifier.fromNamespaceAndPath("wynnlabs", "mob_track"), (g, dt) -> {
            long t = Perf.start();
            try {
                MobTracker.renderHud(g, dt);
            } catch (Throwable ignored) {
                // a notice must never break the HUD
            } finally {
                Perf.end("hud.MobTracker", t);
            }
        });
        platform.onWorldRender((pose, buffers) -> {
            long t = Perf.start();
            try {
                MobTracker.renderWorld(pose, buffers);
            } catch (Throwable ignored) {
                // a marker must never break the frame
            } finally {
                Perf.end("world.MobTracker", t);
            }
        });
        // leaving the server closes the session report
        platform.onDisconnect(() -> {
            Coach.saveSession(System.currentTimeMillis());
            DpsMeter.LIVE.reset();
            MobTracker.reset();
            RaidLog.disconnected();
            LootrunLog.disconnected();
        });
        // /av <item>: a hunt (client-side command, never sent to the server)
        platform.registerClientCommand(WynnLabs::huntCommand);
        // dev builds only: the developer tools (the /wynnlabs shots command); nothing in a release jar
        if (DevHooks.ON) {
            DevHooks.register(platform);
        }
        // Y opens the road map (rebindable in Controls; J clashed with Wynntils)
        var category = platform.keyCategory(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("wynnlabs", "main"));
        var roadmapKey = platform.registerKey(
                new net.minecraft.client.KeyMapping("key.wynnlabs.roadmap",
                        com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_Y, category));
        platform.onEndTick(client -> {
            while (roadmapKey.consumeClick()) {
                if (client.player == null || client.screen != null) {
                    continue;
                }
                client.setScreen(new RoadmapScreen());
            }
        });
        // the key under Esc opens the search tab, or the tab on the item under the mouse
        io.github.anilken.wynnlabs.ui.SearchKey.register(platform, category);
        // T over a gear box in a container: the box contents window over it
        io.github.anilken.wynnlabs.ui.BoxKey.register(platform, category);
        // files waiting in the background writer reach the disk before the game closes
        platform.onClientStopping(Saver::flush);
        // Wynntils' event bus only exists once the client has started.
        platform.onClientStarted(() -> {
            try {
                Wyn.listen(new WynntilsEvents());
            } catch (Throwable t) {
                System.out.println("[WynnLabs] tooltip hook unavailable: " + t);
            }
            // tooltips drawn smaller while a menu is open (MenuTooltips)
            try {
                Wyn.listen(new WynntilsEvents.Tooltips());
            } catch (Throwable t) {
                System.out.println("[WynnLabs] menu tooltip size hook unavailable: " + t);
            }
            // the quests tab's "read quests with Wynntils": the lists Wynntils posts after the press
            try {
                Wyn.listen(Wyn.Feature.QUESTS, new WynntilsEvents.Quests());
            } catch (Throwable t) {
                System.out.println("[WynnLabs] quest list hook unavailable: " + t);
            }
            // the Endgame tab's events, each under its own feature
            try {
                Wyn.listen(Wyn.Feature.RAIDS, new WynntilsEvents.Raids());
                Wyn.listen(Wyn.Feature.LOOTRUNS, new WynntilsEvents.Lootruns());
                Wyn.listen(Wyn.Feature.LOOTRUNS, new WynntilsEvents.Valuables());
            } catch (Throwable t) {
                System.out.println("[WynnLabs] raid and lootrun hooks unavailable: " + t);
            }
        });
        // The local API only runs while "Local API for tools" is on; checked once a second, so
        // flipping the setting starts or stops it without a restart
        syncServer();
        platform.onEndTick(client -> {
            if (++serverTick % 20 == 0) {
                syncServer();
            }
        });
    }

    /** {@code /av [item]}: the hunt line, shown on this client only (built once, for any loader's source). */
    private static <S> com.mojang.brigadier.builder.LiteralArgumentBuilder<S> huntCommand(
            java.util.function.BiConsumer<S, net.minecraft.network.chat.Component> feedback) {
        return com.mojang.brigadier.builder.LiteralArgumentBuilder.<S>literal("av")
                .executes(c -> {
                    feedback.accept(c.getSource(), net.minecraft.network.chat.Component.literal(Coach.hunt(null))
                            .withStyle(net.minecraft.ChatFormatting.GOLD));
                    return 1;
                })
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("item",
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(c -> {
                            feedback.accept(c.getSource(), net.minecraft.network.chat.Component.literal(
                                    Coach.hunt(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "item")))
                                    .withStyle(net.minecraft.ChatFormatting.GOLD));
                            return 1;
                        }));
    }

    private static HttpServer server;
    private static java.util.concurrent.ExecutorService serverThreads;
    private static int serverPort;
    private static int serverTick;
    private static long serverRetryAt;

    /** Starts, stops or moves the local API to match the settings. Loopback only. */
    public static synchronized void syncServer() {
        boolean want = Settings.on("localApi");
        int port = Integer.getInteger("wynninv.port", Settings.number("localApiPort", 47591));
        AiLink.port = port;
        if (port != serverPort) {
            serverRetryAt = 0;                  // a new port: try it now
        }
        if (server != null && (!want || port != serverPort)) {
            server.stop(0);
            serverThreads.shutdownNow();
            server = null;
            serverRetryAt = 0;
            System.out.println("[WynnLabs] local API stopped");
        }
        if (!want) {
            AiLink.state = "off";
            AiLink.error = null;
        }
        if (!want || server != null || System.currentTimeMillis() < serverRetryAt) {
            return;
        }
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            s.createContext("/", ex -> route(ex));
            serverThreads = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wynnlabs-api");
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(serverThreads);
            s.start();
            server = s;
            serverPort = port;
            AiLink.state = "listening";
            AiLink.error = null;
            System.out.println("[WynnLabs] local API on http://127.0.0.1:" + port + "/");
        } catch (IOException e) {
            serverRetryAt = System.currentTimeMillis() + 30_000;   // port taken: try again later
            serverPort = port;
            AiLink.state = "busy";
            AiLink.error = e.getMessage();
            System.out.println("[WynnLabs] could not start the local API on port " + port + ": " + e);
        }
    }

    /** Diagnostic routes answer only while the Debug setting is on. */
    private static boolean debugRoute(String path) {
        return path.equals("/perf") || path.equals("/crafting/ingredients") || path.equals("/ground")
                || path.equals("/fixture");
    }

    /** The route being answered on this API thread, for the game-thread timing section. */
    private static final ThreadLocal<String> ROUTE = new ThreadLocal<>();

    private static void route(HttpExchange ex) throws IOException {
        long timed = Perf.start();
        String path = ex.getRequestURI().getPath();
        ROUTE.set(path);
        try {
            routeTimed(ex, path);
        } finally {
            ROUTE.remove();
            Perf.end("api " + path, timed);
        }
    }

    private static void routeTimed(HttpExchange ex, String path) throws IOException {
        try {
            String method = ex.getRequestMethod();
            AiLink.seen(path);
            if (debugRoute(path) && !Settings.on("debug")) {
                send(ex, 404, error("debug routes are off (turn on Debug output in settings)"));
                return;
            }
            if ("/advice".equals(path) && !"GET".equals(method)) {
                // Browsers can't add a custom header to a cross-site request without a
                // preflight we never answer, so this keeps web pages out.
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Wynninv"))) {
                    send(ex, 403, error("writes need the header X-Wynninv: 1"));
                    return;
                }
                if (!Settings.on("apiWrite")) {
                    send(ex, 403, error("writing is switched off: turn on 'AI may write advice and rules' in the Wynn Labs menu (Y)"
                            + " (AI assistant tab)"));
                    return;
                }
                if ("DELETE".equals(method)) {
                    Advice.clear();
                    send(ex, 200, Advice.list());
                } else if ("POST".equals(method)) {
                    String body = new String(ex.getRequestBody().readNBytes(256 * 1024),
                            StandardCharsets.UTF_8);
                    JsonObject in = GSON.fromJson(body, JsonObject.class);
                    JsonObject out = new JsonObject();
                    out.addProperty("updated", Advice.merge(in));
                    send(ex, 200, out);
                } else {
                    send(ex, 405, error("use GET, POST or DELETE"));
                }
                return;
            }
            if ("/rules".equals(path) && !"GET".equals(method)) {
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Wynninv"))) {
                    send(ex, 403, error("writes need the header X-Wynninv: 1"));
                    return;
                }
                if (!Settings.on("apiWrite")) {
                    send(ex, 403, error("writing is switched off: turn on 'AI may write advice and rules' in the Wynn Labs menu (Y)"
                            + " (AI assistant tab)"));
                    return;
                }
                if ("DELETE".equals(method)) {
                    send(ex, 200, Rules.reset());
                } else if ("POST".equals(method)) {
                    String body = new String(ex.getRequestBody().readNBytes(256 * 1024),
                            StandardCharsets.UTF_8);
                    send(ex, 200, Rules.update(GSON.fromJson(body, JsonObject.class)));
                } else {
                    send(ex, 405, error("use GET, POST or DELETE"));
                }
                return;
            }
            if ("/compass".equals(path) && !"GET".equals(method)) {
                // the compass and map pins the J menu's "compass" and "path" buttons set, on the
                // player's own client (Wynntils' compass and map): nothing goes to the server
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Wynninv"))) {
                    send(ex, 403, error("writes need the header X-Wynninv: 1"));
                    return;
                }
                if (!Settings.on("apiWrite")) {
                    send(ex, 403, error("writing is switched off: turn on 'AI may write advice and rules' in the Wynn Labs menu (Y)"
                            + " (AI assistant tab)"));
                    return;
                }
                if ("DELETE".equals(method)) {
                    // ?keep=1 removes the lasting pins; without it the go and quest pins
                    boolean lasting = "keep=1".equals(ex.getRequestURI().getQuery());
                    send(ex, 200, onGameThread(() -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("removed", lasting ? Waypoints.clearKeep() : Waypoints.clearAll());
                        return o;
                    }));
                } else if ("POST".equals(method)) {
                    String body = new String(ex.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
                    JsonObject in = GSON.fromJson(body, JsonObject.class);
                    JsonObject out;
                    if (in != null && in.has("points")) {
                        // a quest's points in order: one on the map at a time, the next when you reach it
                        List<int[]> points = new ArrayList<>();
                        List<String> labels = new ArrayList<>();
                        for (var p : in.getAsJsonArray("points")) {
                            JsonObject o = p.getAsJsonObject();
                            points.add(new int[] {o.get("x").getAsInt(), o.has("y") ? o.get("y").getAsInt() : 64,
                                o.get("z").getAsInt()});
                            labels.add(o.has("label") ? o.get("label").getAsString() : "");
                        }
                        out = onGameThread(() -> {
                            JsonObject o = new JsonObject();
                            o.addProperty("queued", Waypoints.all(points, labels));
                            return o;
                        });
                    } else if (in != null && in.has("x") && in.has("z")) {
                        int x = in.get("x").getAsInt();
                        int y = in.has("y") ? in.get("y").getAsInt() : 64;
                        int z = in.get("z").getAsInt();
                        String label = in.has("label") ? in.get("label").getAsString() : "Wynn Labs";
                        boolean lasting = in.has("keep") && in.get("keep").getAsBoolean();
                        out = onGameThread(() -> {
                            JsonObject o = new JsonObject();
                            // keep:true: a lasting pin that stays when reached and after a restart
                            o.addProperty("set", lasting ? Waypoints.keep(x, y, z, label) : Waypoints.go(x, y, z, label));
                            o.addProperty("keep", lasting);
                            return o;
                        });
                    } else {
                        send(ex, 400, error("send {\"x\":..,\"y\":..,\"z\":..,\"label\":..} or {\"points\":[{x,y,z,label},..]}"));
                        return;
                    }
                    send(ex, 200, out);
                } else {
                    send(ex, 405, error("use POST or DELETE"));
                }
                return;
            }
            if (!"GET".equals(method)) {
                send(ex, 405, error("only GET"));
                return;
            }
            switch (path) {
                case "/" -> send(ex, 200, index());
                case "/status" -> send(ex, 200, onGameThread(WynnLabs::status));
                case "/equipped" -> send(ex, 200, onGameThread(ItemJson::equipped));
                case "/inventory" -> send(ex, 200, onGameThread(ItemJson::inventory));
                case "/character" -> send(ex, 200, onGameThread(WynnLabs::character));
                case "/abilities" -> send(ex, 200, onGameThread(WynnLabs::abilities));
                case "/quests" -> send(ex, 200, onGameThread(WynnLabs::quests));
                case "/advice" -> send(ex, 200, Advice.list());
                case "/rules" -> send(ex, 200, Rules.get());
                case "/shopping" -> send(ex, 200, onGameThread(Shopping::json));
                case "/grind" -> send(ex, 200, onGameThread(Grind::json));
                case "/discoveries" -> send(ex, 200, onGameThread(Discoveries::json));
                case "/prices" -> send(ex, 200, Prices.json());
                case "/build" -> send(ex, 200, Build.json());
                case "/damage" -> send(ex, 200, onGameThread(DamageReport::json));
                case "/settings" -> send(ex, 200, Settings.json());
                case "/profile" -> send(ex, 200, onGameThread(() -> ClassProfiles.rulesFor(TreePlanner.chosen(), false)));
                case "/crafting/ingredients" -> send(ex, 200, onGameThread(WynnLabs::craftIngredientsJson));
                case "/perf" -> {
                    String q = ex.getRequestURI().getQuery();
                    if (q != null && q.contains("reset=1")) {
                        Perf.reset();
                    }
                    send(ex, 200, Perf.json());
                }
                case "/questplan" -> send(ex, 200, onGameThread(QuestAdvisor::json));
                case "/deaths" -> send(ex, 200, onGameThread(DeathCoach::json));
                case "/vault" -> send(ex, 200, onGameThread(Vault::json));
                case "/ground" -> {
                    String q = ex.getRequestURI().getQuery();
                    int r = 12;
                    if (q != null && q.startsWith("r=")) {
                        try {
                            r = Math.max(1, Math.min(128, Integer.parseInt(q.substring(2))));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    int radius = r;
                    send(ex, 200, onGameThread(() -> GroundDebug.json(radius)));
                }
                case "/treeplan" -> send(ex, 200, onGameThread(() -> TreePlanner.json(TreePlanner.chosen())));
                case "/roadmap" -> send(ex, 200, Roadmap.jsonLive());
                case "/roadmap/trace" -> {
                    // ?level=120&slot=RING: why each candidate for that slot was kept or dropped
                    java.util.Map<String, String> q = query(ex);
                    int lv = Integer.parseInt(q.getOrDefault("level", "120"));
                    io.github.anilken.wynnlabs.model.Slot sl = io.github.anilken.wynnlabs.model.Slot.valueOf(
                            q.getOrDefault("slot", "RING").toUpperCase(java.util.Locale.ROOT));
                    send(ex, 200, Roadmap.traceJson(lv, sl));
                }
                case "/screenshot" -> {
                    // the game's screen as a PNG, read only: what the player sees right now
                    byte[] png = screenshot();
                    ex.getResponseHeaders().set("Content-Type", "image/png");
                    ex.sendResponseHeaders(200, png.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(png);
                    }
                }
                case "/golden" -> {
                    // the game's own state on the game thread, the road map (seconds of work) on its builder
                    JsonObject g = onGameThread(WynnLabs::golden);
                    g.add("roadmap", Roadmap.jsonOffThread());
                    send(ex, 200, g);
                }
                case "/fixture" -> {
                    // test data for the build: config/wynnlabs/fixtures/<name>/, personal values replaced
                    String q = ex.getRequestURI().getQuery();
                    String name = q != null && q.startsWith("name=") ? q.substring(5) : "default";
                    try {
                        send(ex, 200, Fixture.record(name));
                    } catch (IOException e) {
                        send(ex, 500, error("could not write the fixture: " + e.getMessage()));
                    }
                }
                case "/characters" -> send(ex, 200, Profile.list());
                default -> send(ex, 404, error("unknown endpoint, see /"));
            }
        } catch (Throwable t) {
            send(ex, 500, error(t.toString()));
        }
    }

    /** {@code a=1&b=2} of a request as a map. */
    private static java.util.Map<String, String> query(HttpExchange ex) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    out.put(kv.substring(0, i), kv.substring(i + 1));
                }
            }
        }
        return out;
    }

    /** The next frame as PNG bytes, taken on the render thread (the same capture as F2, not saved). */
    private record Shot(int width, int height, int[] argb) {}

    /**
     * The game's screen as a PNG. The render thread only copies the pixels; the PNG is encoded
     * here, on the API thread, without a temporary file.
     */
    private static byte[] screenshot() throws Exception {
        java.util.concurrent.CompletableFuture<Shot> done = new java.util.concurrent.CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> {
            try {
                done.complete(new Shot(img.getWidth(), img.getHeight(), img.getPixels()));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            } finally {
                img.close();
            }
        }));
        Shot shot = done.get(10, TimeUnit.SECONDS);
        return png(shot.width(), shot.height(), shot.argb());
    }

    /** Pixels (ARGB, row by row) as an opaque PNG. */
    static byte[] png(int width, int height, int[] argb) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(width * height);
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Game state is only safe to read on the client thread. */
    private static JsonObject onGameThread(Supplier<JsonObject> body) throws Exception {
        String section = "api.gameThread " + ROUTE.get();
        return Minecraft.getInstance().submit(() -> {
            long t = Perf.start();
            try {
                return body.get();
            } finally {
                Perf.end(section, t);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private static JsonObject index() {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Wynn Labs local API (read-only)");
        JsonArray eps = new JsonArray();
        for (AiLink.Endpoint e : AiLink.ENDPOINTS) {
            eps.add(e.path() + " - " + e.en());
        }
        o.add("endpoints", eps);
        return o;
    }

    private static JsonObject status() {
        JsonObject o = new JsonObject();
        LocalPlayer player = Minecraft.getInstance().player;
        o.addProperty("inGame", player != null);
        if (player == null) {
            return o;
        }
        try {
            o.addProperty("hasCharacter", Wyn.hasCharacter());
            o.addProperty("class", Wyn.classType().name());
            o.addProperty("combatLevel", Wyn.level());
            o.addProperty("xpPercent", Wyn.xp().percentInt());
            o.addProperty("characterId", Profile.id());
            o.addProperty("goal", Rules.get().has("goal") ? Rules.get().get("goal").getAsString() : "");
        } catch (Throwable t) {
            o.addProperty("wynntilsError", t.toString());
        }
        return o;
    }

    /**
     * Refactoring check: every item the player has (bag, worn, bank pages) as the API shows it,
     * with its verdict, plus the build and road map. Saved before a refactor and compared after.
     */
    /** Debug: how many ingredients Wynntils gives the crafting tab, and a few of them in full. */
    private static JsonObject craftIngredientsJson() {
        JsonObject o = new JsonObject();
        var all = Wyn.craftIngredients();
        o.addProperty("count", all.size());
        o.addProperty("recipes", io.github.anilken.wynnlabs.crafting.CraftData.all().size());
        JsonArray sample = new JsonArray();
        for (var i : all) {
            if (i.name().equals("Foul Fairy Dust") || i.name().equals("Bob's Tear") || i.name().equals("Obelisk Core")
                    || i.name().equals("Disturbed Dye")) {
                sample.add(GSON.toJsonTree(i));
            }
        }
        o.add("sample", sample);
        return o;
    }

    private static JsonObject golden() {
        JsonObject o = new JsonObject();
        o.add("inventory", ItemJson.inventory());
        o.add("equipped", ItemJson.equipped());
        JsonArray bank = new JsonArray();
        for (String key : Vault.pageKeys()) {
            for (Vault.Stored st : Vault.page(key)) {
                if (st.item() != null) {
                    bank.add(ItemJson.of(Vault.stackOf(st), key + "#" + st.slot()));
                }
            }
        }
        o.add("bank", bank);
        o.add("build", Build.json());
        return o;                               // the road map is added off the game thread (route)
    }

    /**
     * Skill points per skill. Wynntils only knows the assigned ones after the Character Info
     * menu has been read (it does that by itself on login), so they can lag behind a change.
     */
    private static JsonObject character() {
        JsonObject o = status();
        if (Minecraft.getInstance().player == null) {
            return o;
        }
        o.add("professions", Professions.json());
        try {
            Map<SkillKind, Integer> seen;
            Map<SkillKind, String> buttons;
            synchronized (MenuWatcher.ASSIGNED) {
                seen = new java.util.EnumMap<>(MenuWatcher.ASSIGNED);
                buttons = new java.util.EnumMap<>(MenuWatcher.BUTTON_KEYS);
            }
            int assignedSum = 0;
            JsonObject skills = new JsonObject();
            for (SkillKind skill : SkillKind.values()) {
                JsonObject s = new JsonObject();
                // What the menu showed last beats Wynntils' copy, which lags behind changes
                int assigned = seen.getOrDefault(skill, Wyn.assignedPoints(skill));
                assignedSum += assigned;
                s.addProperty("assigned", assigned);
                // Wynntils' gear count reads 0 with the game in Turkish: what you wear says it
                int gear = Math.max(Wyn.gearPoints(skill), Build.wornBonus(skill));
                s.addProperty("gear", gear);
                s.addProperty("tome", Wyn.tomePoints(skill));
                s.addProperty("setBonus", Wyn.setBonusPoints(skill));
                s.addProperty("total", seen.isEmpty() ? Build.totalPoints(skill)
                        : assigned + gear + Wyn.tomePoints(skill) + Wyn.setBonusPoints(skill));
                s.addProperty("element", skill.element());
                // The button in the Character Info menu; post advice to this key to show it there
                s.addProperty("buttonKey", buttons.getOrDefault(skill,
                        "name:Upgrade your " + skill.displayName() + " skill"));
                skills.add(skill.name(), s);
            }
            o.add("skills", skills);
            o.addProperty("assignedSum", assignedSum);
            o.addProperty("assignedSource", seen.isEmpty() ? "wynntils" : "menu");
            if (!seen.isEmpty()) {
                o.addProperty("menuSecondsAgo", (System.currentTimeMillis() - MenuWatcher.lastSeen) / 1000);
                JsonArray menu = new JsonArray();
                MenuWatcher.lastMenuItems.forEach(menu::add);
                o.add("menuItems", menu);
            }
            int level = Wyn.level();
            // Wynncraft gives 2 skill points per level from level 2 on (200 in total)
            int earned = Math.min(200, Math.max(0, (level - 1) * 2));
            o.addProperty("earnedEstimate", earned);
            if (MenuWatcher.unspent >= 0 && !seen.isEmpty()) {
                // the Skill Crystal shows the real number; trust it over the estimate
                o.addProperty("unspent", MenuWatcher.unspent);
                o.addProperty("earned", assignedSum + MenuWatcher.unspent);
            }
            o.addProperty("unspentEstimate", Math.max(0, earned - assignedSum));
            o.addProperty("note", seen.isEmpty()
                    ? "assigned comes from Wynntils and can lag; open Character Info once to read it fresh. "
                            + "earned/unspent are estimates (2 points per level)"
                    : "assigned is read from the Character Info menu the last time it was open; "
                            + "earned/unspent are estimates (2 points per level)");
        } catch (Throwable t) {
            o.addProperty("skillPointsError", t.toString());
        }
        return o;
    }

    /**
     * The ability tree of the current class. Unlocked abilities are only known after the tree
     * has been opened in game once (Wynntils reads them from the menu).
     */
    private static JsonObject abilities() {
        JsonObject o = status();
        if (Minecraft.getInstance().player == null) {
            return o;
        }
        try {
            var classType = Wyn.classType();
            int level = Wyn.level();
            var tree = Wyn.abilityTree(classType);
            java.util.Set<String> unlocked = new java.util.HashSet<>(Wyn.unlockedAbilities());
            int points = Wyn.abilityPointsAt(level);
            o.addProperty("abilityPoints", points);
            int spent = 0;
            JsonArray nodes = new JsonArray();
            if (tree != null) {
                for (var n : tree) {
                    boolean has = unlocked.contains(n.name());
                    if (has) {
                        spent += n.cost();
                    }
                    JsonObject j = new JsonObject();
                    j.addProperty("id", n.id());
                    j.addProperty("name", n.name());
                    j.addProperty("key", "ability:" + ItemJson.abilityName(n.name()));
                    j.addProperty("unlocked", has);
                    j.addProperty("type", n.type());
                    j.addProperty("cost", n.cost());
                    j.addProperty("requiredLevel", n.requiredLevel());
                    if (n.requiredAbility() != null && !n.requiredAbility().isBlank()) {
                        j.addProperty("requiredAbility", n.requiredAbility());
                    }
                    if (n.requiredArchetype() != null
                            && !n.requiredArchetype().isBlank()) {
                        JsonObject ra = new JsonObject();
                        ra.addProperty("archetype", n.requiredArchetype());
                        ra.addProperty("points", n.requiredArchetypePoints());
                        j.add("requiredArchetype", ra);
                    }
                    if (n.archetype() != null
                            && !n.archetype().isBlank()) {
                        j.addProperty("archetype", n.archetype());
                    }
                    if (!n.willBlock().isEmpty()) {
                        JsonArray b = new JsonArray();
                        n.willBlock().forEach(b::add);
                        j.add("blocks", b);
                    }
                    if (n.where() != null) {
                        j.addProperty("page", n.where().page());
                        j.addProperty("row", n.where().row());
                        j.addProperty("col", n.where().col());
                    }
                    j.addProperty("description", describe(n.description()));
                    nodes.add(j);
                }
            }
            o.addProperty("spentEstimate", spent);
            o.addProperty("unlockedCount", unlocked.size());
            if (unlocked.isEmpty()) {
                o.addProperty("note", "No unlocked abilities known yet: open the ability tree in game once "
                        + "so Wynntils can read it.");
            }
            o.add("nodes", nodes);
        } catch (Throwable t) {
            o.addProperty("abilityError", t.toString());
        }
        return o;
    }

    /**
     * Quests as Wynntils last read them from the content book. Nothing is scanned from here:
     * the list fills once the quest book has been opened in game.
     */
    private static JsonObject quests() {
        JsonObject o = status();
        if (Minecraft.getInstance().player == null) {
            return o;
        }
        try {
            if (Wyn.tracking()) {
                JsonObject t = new JsonObject();
                t.addProperty("name", Wyn.trackedName());
                t.addProperty("type", String.valueOf(Wyn.trackedType()));
                String task = Wyn.trackedTask();
                t.addProperty("task", task.isEmpty() ? null : ItemJson.clean(task));
                o.add("tracked", t);
            }
            JsonArray arr = new JsonArray();
            for (var q : Wyn.quests()) {
                JsonObject j = new JsonObject();
                j.addProperty("name", q.name());
                j.addProperty("mini", q.mini());
                j.addProperty("status", q.status());
                j.addProperty("level", q.level());
                j.addProperty("difficulty", q.difficulty());
                j.addProperty("length", q.length());
                if (q.nextTask() != null) {
                    j.addProperty("nextTask", ItemJson.clean(q.nextTask()));
                }
                var req = q.requirements();
                if (req != null) {
                    JsonObject r = new JsonObject();
                    if (req.level() != null && req.level() > 0) {
                        r.addProperty("level", req.level());
                        r.addProperty("levelMet", req.levelMet());
                    }
                    JsonArray profs = new JsonArray();
                    for (var p : req.professions()) {
                        JsonObject pj = new JsonObject();
                        pj.addProperty("profession", p.profession());
                        pj.addProperty("level", p.level());
                        pj.addProperty("met", p.met());
                        profs.add(pj);
                    }
                    if (!profs.isEmpty()) {
                        r.add("professions", profs);
                    }
                    JsonArray qs = new JsonArray();
                    for (var p : req.quests()) {
                        JsonObject qj = new JsonObject();
                        qj.addProperty("quest", p.quest());
                        qj.addProperty("met", p.met());
                        qs.add(qj);
                    }
                    if (!qs.isEmpty()) {
                        r.add("quests", qs);
                    }
                    if (!r.isEmpty()) {
                        j.add("requirements", r);
                    }
                }
                JsonObject rewards = new JsonObject();
                if (q.rewards() != null) {
                    q.rewards().forEach((type, list) -> {
                        JsonArray l = new JsonArray();
                        list.forEach(t -> l.add(ItemJson.clean(t)));
                        rewards.add(type, l);
                    });
                }
                if (!rewards.isEmpty()) {
                    j.add("rewards", rewards);
                }
                arr.add(j);
            }
            // Wynntils' quest model stays empty when it cannot read the content book (seen with
            // the game in Turkish); the mod reads the book itself (QuestBook, quests.json)
            String source = "wynntils";
            if (arr.isEmpty()) {
                source = "questbook";
                for (QuestBook.Entry e : QuestBook.all()) {
                    JsonObject j = new JsonObject();
                    j.addProperty("name", e.name());
                    j.addProperty("type", e.type());
                    j.addProperty("status", e.status());
                    j.addProperty("level", e.level());
                    j.addProperty("levelMet", e.met());
                    j.addProperty("difficulty", e.difficulty());
                    j.addProperty("length", e.length());
                    if (e.xp() > 0) {
                        j.addProperty("xp", e.xp());
                    }
                    if (e.task() != null && !e.task().isBlank()) {
                        j.addProperty("nextTask", ItemJson.clean(e.task()));
                    }
                    if (e.x() != null && e.z() != null) {
                        JsonObject at = new JsonObject();
                        at.addProperty("x", e.x());
                        if (e.y() != null) {
                            at.addProperty("y", e.y());
                        }
                        at.addProperty("z", e.z());
                        j.add("startsAt", at);
                    }
                    arr.add(j);
                }
            }
            o.addProperty("questSource", source);
            o.addProperty("questCount", arr.size());
            if (arr.isEmpty()) {
                o.addProperty("note", "No quests known yet: open the quest book (content book) in game once "
                        + "so Wynntils can read it.");
            }
            o.add("quests", arr);
        } catch (Throwable t) {
            o.addProperty("questError", t.toString());
        }
        return o;
    }

    /** Ability descriptions come as component JSON; keep only the text. */
    private static String describe(List<JsonArray> lines) {
        if (lines == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonArray line : lines) {
            StringBuilder l = new StringBuilder();
            collectText(line, l);
            String t = ItemJson.clean(l.toString());
            if (!t.isBlank()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(t);
            }
        }
        return out.toString();
    }

    private static void collectText(com.google.gson.JsonElement e, StringBuilder out) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonPrimitive()) {
            out.append(e.getAsString());
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> collectText(x, out));
        } else if (e.isJsonObject()) {
            JsonObject obj = e.getAsJsonObject();
            if (obj.has("text")) {
                collectText(obj.get("text"), out);
            }
            if (obj.has("extra")) {
                collectText(obj.get("extra"), out);
            }
        }
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    private static void send(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
