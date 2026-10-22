package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Shopping;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which quest to do next, decided in the mod: quests from the content book ({@link QuestBook})
 * that you can start or have started, ranked by combat XP per hour of play (reward and quest
 * length), with a bonus for quests that unlock an item on the shopping list (Creeper
 * Infiltration → Bush Mask) or that you have already begun, and a small penalty for a long
 * walk. Starts or tracks nothing.
 */
public final class QuestAdvisor {

    public record Suggestion(QuestBook.Entry quest, double score, int distance, List<String> reasons) {

        boolean started() {
            return "STARTED".equals(quest.status());
        }
    }

    private QuestAdvisor() {}

    /** The best quests to do now, best first; call on the client thread. */
    public static List<Suggestion> suggest(int limit) {
        List<Suggestion> all = list("RECOMMENDED");
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /** The list's filters, in the order of the chips. */
    public static final String[] FILTERS = {"RECOMMENDED", "SHORT", "NEAR", "XP", "ITEM", "MINI", "ABOVE", "DONE"};
    /** Language keys for the chips, in the order of {@link #FILTERS}; show with {@code L.t(FILTER_NAMES[i])}. */
    public static final String[] FILTER_NAMES = {"wynnlabs.questadv.filter_recommended", "wynnlabs.questadv.filter_short",
            "wynnlabs.questadv.filter_near", "wynnlabs.questadv.filter_xp", "wynnlabs.questadv.filter_item",
            "wynnlabs.questadv.filter_mini", "wynnlabs.questadv.filter_above", "wynnlabs.questadv.filter_done"};

    /**
     * Every quest a filter keeps, scored and ordered for it: RECOMMENDED (the advisor's order),
     * SHORT, NEAR (closest first), XP (most first), ITEM (unlocks gear on the shopping list),
     * MINI (mini-quests), ABOVE (1-5 levels above you: soon) and DONE. No limit.
     */
    public static List<Suggestion> list(String filter) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return List.of();
        }
        Map<String, List<String>> unlocks = new HashMap<>();
        try {
            for (Shopping.Pick p : Shopping.list()) {
                p.info().requirements().quest().ifPresent(q ->
                        unlocks.computeIfAbsent(norm(q), k -> new ArrayList<>()).add(p.info().name()));
            }
        } catch (Throwable ignored) {
        }
        int level = Wyn.level();
        List<QuestBook.Entry> candidates = new ArrayList<>();
        long maxRate = 1;
        for (QuestBook.Entry q : QuestBook.all()) {
            boolean started = "STARTED".equals(q.status());
            boolean mini = "MINI_QUEST".equals(q.type());
            boolean keep = switch (filter) {
                case "DONE" -> "COMPLETED".equals(q.status());
                // the game says UNAVAILABLE for anything above your level: those count here (soon)
                case "ABOVE" -> !"COMPLETED".equals(q.status()) && !mini && q.level() > level && q.level() <= level + 5;
                case "MINI" -> mini && (started || "AVAILABLE".equals(q.status()) && q.level() <= level);
                default -> (started || "AVAILABLE".equals(q.status()) && q.met() && q.level() <= level)
                        && (!mini || started || Settings.on("miniQuests"));
            };
            if (!keep) {
                continue;
            }
            candidates.add(q);
            maxRate = Math.max(maxRate, (long) (q.xp() / hours(q)));
        }
        List<Suggestion> out = new ArrayList<>();
        for (QuestBook.Entry q : candidates) {
            List<String> reasons = new ArrayList<>();
            // 0..1: XP for the time it takes, on a log scale, so one huge reward (Tower of Ascension)
            // does not drown everything else; safety below weighs as much as the XP
            double score = Math.log1p(q.xp() / hours(q)) / Math.log1p(maxRate);
            if (q.xp() > 0) {
                reasons.add(L.t("wynnlabs.questadv.reason_xp", fmt(q.xp()), lengthName(q)));
            }
            // Survivable first: a quest at your own level (or marked hard) kills you, so it
            // drops well behind one you out-level by a few levels
            int margin = level - q.level();
            int want = (int) Rules.num("questLevelMargin", 4);
            boolean hard = "HARD".equals(q.difficulty());
            if (margin < 0) {
                reasons.add(L.n("wynnlabs.questadv.reason_levels_later", -margin));
            } else if (margin < want) {
                score -= 0.25 * (want - margin);
                reasons.add(margin == 0 ? L.t("wynnlabs.questadv.reason_at_level") : L.t("wynnlabs.questadv.reason_near_level"));
            } else {
                reasons.add(L.n("wynnlabs.questadv.reason_comfortable", margin));
            }
            if (hard) {
                // hard quests kill: only near the top once you clearly out-level them
                score -= margin >= 8 ? 0.4 : 1.2;
                reasons.add(margin >= 8 ? L.t("wynnlabs.questadv.reason_hard") : L.t("wynnlabs.questadv.reason_hard_underlevel"));
            } else if ("EASY".equals(q.difficulty())) {
                score += 0.15;
            }
            if ("STARTED".equals(q.status())) {
                score += margin >= want && !hard ? 0.4 : 0.1;
                reasons.add(L.t("wynnlabs.questadv.reason_started"));
            }
            List<String> items = unlocks.get(norm(q.name()));
            if (items != null) {
                score += 1.0;
                reasons.add(L.t("wynnlabs.questadv.reason_unlocks", String.join(", ", items)));
            }
            int dist = -1;
            if (q.x() != null) {
                dist = (int) Math.round(Math.hypot(q.x() - mc.player.getX(), q.z() - mc.player.getZ()));
            } else if (q.bookDistance() != null) {
                dist = q.bookDistance();                // as the book said when it was open
            }
            if (dist >= 0) {
                score -= Math.min(0.4, dist / 4000.0);  // a long walk costs a little
            }
            if (filter.equals("ITEM") && items == null || filter.equals("SHORT") && !"SHORT".equals(q.length())) {
                continue;
            }
            out.add(new Suggestion(q, score, dist, reasons));
        }
        switch (filter) {
            case "NEAR" -> out.sort(Comparator.comparingInt(s -> s.distance() < 0 ? Integer.MAX_VALUE : s.distance()));
            case "XP" -> out.sort(Comparator.comparingLong((Suggestion s) -> s.quest().xp()).reversed());
            case "ABOVE" -> out.sort(Comparator.comparingInt(s -> s.quest().level()));
            case "DONE" -> out.sort(Comparator.comparing(s -> s.quest().name()));
            default -> out.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        }
        return out;
    }

    /** Minutes a quest takes, by its length, plus the walk there (about 5 blocks a second). */
    public static int minutes(Suggestion s) {
        int base = s.quest().length() == null ? 25 : switch (lengthKey(s.quest())) {
            case "SHORT" -> 12;
            case "MEDIUM" -> 22;
            case "LONG" -> 38;
            default -> 25;
        };
        return base + (s.distance() > 0 ? s.distance() / 300 : 0);
    }

    public static int known() {
        return QuestBook.size();
    }

    private static volatile List<Suggestion> top = List.of();
    private static volatile long topAt;

    /** The best three, recomputed at most every two seconds (for glows and tooltips). */
    public static List<Suggestion> top() {
        long now = System.currentTimeMillis();
        if (now - topAt > 2000) {
            topAt = now;
            try {
                top = suggest(3);
            } catch (Throwable t) {
                top = List.of();
            }
        }
        return top;
    }

    /** 0..2 when this stack (a content book entry) is one of the best three quests, else -1. */
    public static int rankOf(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        String name = norm(ItemJson.clean(stack.getHoverName().getString()));
        if (name.isEmpty()) {
            return -1;
        }
        List<Suggestion> t = top();
        for (int i = 0; i < t.size(); i++) {
            if (name.equals(norm(t.get(i).quest().name()))) {
                return i;
            }
        }
        return -1;
    }

    /** Rough play time by quest length. */
    private static double hours(QuestBook.Entry q) {
        if (q.length() == null) {
            return 1.5;
        }
        return switch (q.length()) {
            case "SHORT" -> 0.5;
            case "MEDIUM" -> 1.0;
            case "LONG" -> 2.0;
            default -> 1.5;
        };
    }

    public static String lengthName(QuestBook.Entry q) {
        if (q.length() == null) {
            return L.t("wynnlabs.questadv.length_unknown");
        }
        return switch (lengthKey(q)) {
            case "SHORT" -> L.t("wynnlabs.questadv.length_short");
            case "MEDIUM" -> L.t("wynnlabs.questadv.length_medium");
            case "LONG" -> L.t("wynnlabs.questadv.length_long");
            default -> q.length().toLowerCase(Locale.ROOT);
        };
    }

    /** The quest's length as "SHORT", "MEDIUM", "LONG": the book and the wiki do not agree on case ("Medium"). */
    private static String lengthKey(QuestBook.Entry q) {
        return q.length().trim().toUpperCase(Locale.ROOT);
    }

    public static String fmt(long xp) {
        if (xp >= 1_000_000) {
            return Math.round(xp / 100_000.0) / 10.0 + "M";
        }
        if (xp >= 1000) {
            return Math.round(xp / 100.0) / 10.0 + "k";
        }
        return String.valueOf(xp);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("questsKnown", known());
        JsonArray arr = new JsonArray();
        for (Suggestion s : suggest(15)) {
            QuestBook.Entry q = s.quest();
            JsonObject j = new JsonObject();
            j.addProperty("name", q.name());
            j.addProperty("level", q.level());
            j.addProperty("status", q.status());
            j.addProperty("length", lengthName(q));
            j.addProperty("xp", q.xp());
            j.addProperty("score", Math.round(s.score() * 100) / 100.0);
            if (s.distance() >= 0) {
                j.addProperty("distance", s.distance());
                j.addProperty("compass", "/compass at " + q.x() + " " + q.y() + " " + q.z());
            }
            if (q.task() != null) {
                j.addProperty("nextTask", q.task());
            }
            JsonArray why = new JsonArray();
            s.reasons().forEach(why::add);
            j.add("why", why);
            arr.add(j);
        }
        o.add("suggestions", arr);
        return o;
    }
}
