package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advice the assistant leaves on items, shown at the bottom of their tooltips.
 *
 * <p>Keyed by {@link ItemJson#keyOf}, saved to {@code config/wynnlabs/advice.json} so it
 * survives a restart. Only text in your own tooltip; nothing is sent anywhere.
 */
final class Advice {

    record Entry(Verdict verdict, String note) {

        /** From saved files and tools: old Turkish words and enum names both work. */
        Entry(String token, String note) {
            this(Verdict.of(token), Verdict.parse(token) == null && token != null && !token.isBlank()
                    ? token.strip() + (note == null || note.isBlank() ? "" : ": " + note) : note);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final int MAX_NOTE = 160;

    private Advice() {}

    private static Path file() {
        return Profile.dir().resolve("advice.json");
    }

    /** Goes up whenever the entries change (load, merge, clear …): cached slot drawings rebuild. */
    static volatile int version;

    static void load() {
        version++;
        ENTRIES.clear();                        // another character starts with its own advice
        try {
            Path f = file();
            if (!Files.exists(f)) {
                return;
            }
            JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
            o.entrySet().forEach(e -> {
                JsonObject v = e.getValue().getAsJsonObject();
                ENTRIES.put(e.getKey(), new Entry(str(v, "verdict"), str(v, "note")));
            });
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read advice.json: " + e);
        }
    }

    private static void save() {
        version++;
        try {
            JsonObject o = new JsonObject();
            ENTRIES.forEach((k, v) -> {
                JsonObject e = new JsonObject();
                e.addProperty("verdict", v.verdict().name());
                e.addProperty("note", v.note());
                o.add(k, e);
            });
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save advice.json: " + e);
        }
    }

    /** {"items":[{"key":"...","verdict":"SAT","note":"..."}]}; an empty verdict removes the key. */
    static int merge(JsonObject body) {
        JsonElement items = body.get("items");
        if (items == null || !items.isJsonArray()) {
            throw new IllegalArgumentException("expected {\"items\":[{\"key\",\"verdict\",\"note\"}]}");
        }
        int n = 0;
        for (JsonElement el : items.getAsJsonArray()) {
            JsonObject e = el.getAsJsonObject();
            String key = str(e, "key");
            if (key == null || key.isBlank()) {
                continue;
            }
            String verdict = str(e, "verdict");
            if (verdict == null || verdict.isBlank()) {
                ENTRIES.remove(key);
            } else {
                String note = str(e, "note");
                if (note != null && note.length() > MAX_NOTE) {
                    note = note.substring(0, MAX_NOTE);
                }
                ENTRIES.put(key, new Entry(verdict, note));
            }
            n++;
        }
        save();
        return n;
    }

    /**
     * Plan nodes noted "Lv. N" that your level has reached turn from SONRA AL into AL; returns
     * their names. Nodes Wynntils knows you already have are skipped.
     */
    static List<String> promoteAbilities(int level) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> have = new java.util.HashSet<>();
        try {
            have.addAll(Wyn.unlockedAbilities());
        } catch (Throwable ignored) {
            // unknown until the tree is opened
        }
        java.util.regex.Pattern lv = LEVEL_NOTE;
        ENTRIES.forEach((k, v) -> {
            if (!k.startsWith("ability:") || v.verdict() != Verdict.BUY_LATER || v.note() == null) {
                return;
            }
            String name = k.substring("ability:".length());
            var m = lv.matcher(v.note());
            if (m.find() && Integer.parseInt(m.group(1)) <= level && !have.contains(name)) {
                ENTRIES.put(k, new Entry(Verdict.BUY, v.note()));
                out.add(name);
            }
        });
        if (!out.isEmpty()) {
            save();
        }
        return out;
    }

    private static final java.util.regex.Pattern LEVEL_NOTE = java.util.regex.Pattern.compile("Lv\\. (\\d+)");
    private static final java.util.regex.Pattern PLAN_STEP = java.util.regex.Pattern.compile("(\\d+)/\\d+");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

    /** The step an ability's note gives ("Plan 3/9 …" → 3), or 0. */
    static int planStep(Entry e) {
        if (e == null || e.note() == null) {
            return 0;
        }
        var m = PLAN_STEP.matcher(e.note());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** The ability plan: node name → the level its note says ("Lv. N"), for nodes still to take. */
    static Map<String, Integer> abilityPlan() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        java.util.regex.Pattern lv = LEVEL_NOTE;
        ENTRIES.forEach((k, v) -> {
            if (!k.startsWith("ability:") || v.note() == null
                    || !(v.verdict() == Verdict.BUY || v.verdict() == Verdict.BUY_LATER)) {
                return;
            }
            var m = lv.matcher(v.note());
            if (m.find()) {
                out.put(k.substring("ability:".length()), Integer.parseInt(m.group(1)));
            }
        });
        return out;
    }

    static void clear() {
        ENTRIES.clear();
        save();
    }

    /**
     * Drops the ability entries whose node is not in this class's tree: another class's plan
     * left behind in the file. Returns how many went.
     */
    static int dropAbilitiesExcept(java.util.Set<String> names) {
        int n = 0;
        for (String k : new ArrayList<>(ENTRIES.keySet())) {
            if (k.startsWith("ability:") && !names.contains(k.substring("ability:".length()))) {
                ENTRIES.remove(k);
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    static JsonObject list() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        ENTRIES.forEach((k, v) -> {
            JsonObject e = new JsonObject();
            e.addProperty("key", k);
            e.addProperty("verdict", v.verdict().name());
            e.addProperty("note", v.note());
            arr.add(e);
        });
        o.add("items", arr);
        return o;
    }

    /** The advice for this stack if its key passes the filter, or null. */
    static Entry find(ItemStack stack, java.util.function.Predicate<String> keyFilter) {
        if (ENTRIES.isEmpty() || stack == null || stack.isEmpty()) {
            return null;
        }
        String key = ItemJson.keyOf(stack);
        return key != null && keyFilter.test(key) ? ENTRIES.get(key) : null;
    }

    /** ARGB colour of a verdict, for the glow in menus. */
    static int rgb(Verdict verdict) {
        Integer c = verdict.colour().getColor();
        return c == null ? 0xFFFFFF : c;
    }

    /** One stack's tooltip lines while they are still good. */
    private record Lines(long until, int rules, int advice, boolean shift, List<Component> lines) {}

    private static final java.util.WeakHashMap<ItemStack, Lines> LINES = new java.util.WeakHashMap<>();

    /**
     * {@link #lines} for a tooltip being drawn: both tooltip hooks ask every frame, so the lines
     * are kept per stack for about a quarter second (staggered per stack), and rebuilt at once
     * when the rules, settings or advice change or Shift is pressed or let go. Client thread only.
     */
    static List<Component> linesCached(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        boolean shift = net.minecraft.client.Minecraft.getInstance().hasShiftDown();
        Lines l = LINES.get(stack);
        if (l != null && now < l.until() && l.rules() == Stamp.now() && l.shift() == shift) {
            return l.lines();
        }
        int rules = Stamp.now();
        int advice = version;
        List<Component> out = List.copyOf(lines(stack));
        LINES.put(stack, new Lines(now + 250 + (System.identityHashCode(stack) & 127), rules, advice, shift, out));
        return out;
    }

    /** Lines appended to the tooltip of this stack, or none. */
    static List<Component> lines(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !Rules.adviceHere()) {
            return List.of();
        }
        if (!Wyn.up(Wyn.Feature.ITEMS)) {
            return List.of(L.c("wynnlabs.wyn.items_off", Wyn.version()).withStyle(ChatFormatting.DARK_GRAY));
        }
        // a bank item shown in the J menu: its saved verdict and place, not a live guess
        List<Component> saved = Vault.tipExtra(stack);
        if (saved != null) {
            return saved;
        }
        String key = ItemJson.keyOf(stack);
        // Menu buttons (ability nodes, skill buttons) and items have their own switch
        boolean menu = key != null && (key.startsWith("ability:") || key.startsWith("name:"));
        boolean showAdvice = Settings.on(menu ? "menuAdvice" : "itemAdvice");
        Entry e = ENTRIES.isEmpty() || !showAdvice ? null : ENTRIES.get(key);
        String tag = null;
        String caution = null;
        ItemView gear = gearOf(stack);
        boolean own = Shopping.isOwn(stack);
        boolean inBank = !own && Vault.inOpenBank(stack);
        if (e == null && gear != null && !own && !inBank && showAdvice) {
            // In a shop, the Trade Market or a chest: is it on the shopping list?
            Shopping.Pick pick = Shopping.find(gear.data().name());
            if (pick != null) {
                int lv = pick.info().requirements().level();
                int now = Wyn.level();
                Prices.Price price = Prices.of(pick.info().name());
                String cost = price != null && price.typical() != null ? ", ~" + Prices.fmt(price.typical()) : "";
                String head = pick.alternative() ? L.t("wynnlabs.advice.shop_alternative", pick.slot())
                        : L.t("wynnlabs.advice.shop_rank", pick.slot(), pick.rank());
                String later = lv > now ? L.t("wynnlabs.advice.shop_wear_at", lv) : "";
                e = new Entry(lv > now ? Verdict.BUY_LATER : Verdict.BUY, L.t("wynnlabs.advice.shop_note", head,
                        String.valueOf(Math.round(pick.gain() * 10) / 10.0), cost, later));
                tag = L.t("wynnlabs.advice.tag_shopping");
                caution = Cautions.line(pick.info());
            }
        }
        if (e == null && (own || inBank || gear == null) && showAdvice) {
            // the rules judge your own items (bag and bank); in shops and chests only the shopping list speaks
            e = AutoAdvice.cached(stack);
            tag = inBank ? L.t("wynnlabs.advice.tag_rule_bank") : L.t("wynnlabs.advice.tag_rule");
        }
        List<Component> out = new ArrayList<>();
        // what keeps you from wearing it, first and loud: level, class, skill points, a quest
        if (gear != null && showAdvice) {
            var info = gear.data();
            var req = info.requirements();
            List<String> why = new ArrayList<>();
            int lvNow = Wyn.level();
            if (req.level() > lvNow) {
                why.add(L.t("wynnlabs.advice.why_level", req.level(), lvNow));
            }
            var needCls = req.klass().orElse(info.slot().klass());
            if (needCls != null && needCls != Klass.NONE
                    && needCls != Wyn.classType()) {
                why.add(L.t("wynnlabs.advice.why_class", AutoAdvice.nice(needCls.name())));
            }
            // skill points: only from the game's own checkmarks (Wynntils reads them); a guess here
            // gave false alarms, so without them no skill line at all
            var marks = gear.rolled() != null ? gear.rolled().marks() : null;
            if (marks != null && marks.known()) {
                for (var pr : req.skills()) {
                    Boolean ok = marks.skills().get(pr.skill());
                    if (ok != null && !ok) {
                        why.add(pr.skill().displayName().substring(0, 3) + " " + pr.points());
                    }
                }
                if (!marks.quest() && req.quest().isPresent()) {
                    boolean listed = false;
                    for (String w : why) {
                        listed |= w.contains(req.quest().get());
                    }
                    if (!listed) {
                        why.add(L.t("wynnlabs.advice.why_quest", req.quest().get()));
                    }
                }
            }
            if (req.quest().isPresent()) {
                for (QuestBook.Entry q : QuestBook.all()) {
                    if (q.name().equalsIgnoreCase(req.quest().get()) && !"COMPLETED".equals(q.status())) {
                        why.add(L.t("wynnlabs.advice.why_quest", req.quest().get()));
                        break;
                    }
                }
            }
            if (!why.isEmpty()) {
                out.add(Component.empty());
                for (String line : wrap(L.t("wynnlabs.advice.cannot_wear", String.join(" · ", why)), 38)) {
                    out.add(Component.literal(line).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                }
            }
        }
        if (e != null) {
            out.add(Component.empty());
            var head = Component.literal(TooltipListener.marker()).withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(e.verdict().label()).withStyle(e.verdict().colour(), ChatFormatting.BOLD));
            // Where to sell, in the big line: the note says it too, but in small print
            String where = sellPlace(e);
            if (where != null) {
                head.append(Component.literal(" → " + where).withStyle(
                        where.startsWith("Trade") ? ChatFormatting.GOLD : ChatFormatting.WHITE, ChatFormatting.BOLD));
            }
            if (tag != null) {
                head.append(Component.literal(tag).withStyle(ChatFormatting.DARK_GRAY));
            }
            out.add(head);
            if (e.note() != null && !e.note().isBlank()) {
                for (String line : wrap(e.note(), 38)) {
                    out.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
                }
            }
            if (caution != null) {
                for (String line : wrap(caution, 38)) {
                    out.add(Component.literal(line).withStyle(ChatFormatting.RED));
                }
            }
        }
        if (!own && !inBank && showAdvice) {
            // Merchants that want materials besides (or instead of) emeralds: say so plainly
            List<String> wants = extraCost(stack);
            if (!wants.isEmpty()) {
                if (out.isEmpty()) {
                    out.add(Component.empty());
                }
                for (String line : wrap(L.t("wynnlabs.advice.also_wants", String.join(", ", wants)), 38)) {
                    out.add(Component.literal(line).withStyle(ChatFormatting.GOLD));
                }
            }
        }
        if (e == null && gear != null && own && Settings.on("wornHint") && AutoAdvice.isWorn(stack)) {
            // What you wear: point at the next upgrade for this slot from the shopping list
            Shopping.Pick next = Shopping.best(gear.data().slot());
            Roadmap.Gear road = Roadmap.lastUpgrade(gear.data().slot());
            out.add(Component.empty());
            out.add(Component.literal(TooltipListener.marker()).withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(L.c("wynnlabs.advice.worn").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
            // the J menu's damage model first (it also knows untradable merchant items), then the shopping list
            String note = road != null && !Double.isNaN(road.gain())
                    ? L.t("wynnlabs.advice.next_target_gain", road.info().name(), road.info().requirements().level(),
                            road.source(), Math.round(road.gain()))
                    : next == null ? L.t("wynnlabs.advice.nothing_better")
                    : L.t("wynnlabs.advice.next_target", next.info().name(), next.info().requirements().level(),
                            Shopping.where(next.source()));
            var gi = gear.rolled();
            String tier = gear.data().tier().name();
            if (gi != null && gi.hasOverall()
                    && (tier.equals("LEGENDARY") || tier.equals("FABLED") || tier.equals("MYTHIC"))
                    && gi.overallPercent() < Rules.num("rerollBelowPercent", 30)) {
                note += L.t("wynnlabs.advice.bad_roll", Math.round(gi.overallPercent()));
            }
            for (String line : wrap(note, 38)) {
                out.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
            }
        }
        if (gear != null && own && Settings.on("powderHint")) {
            String hint = AutoAdvice.powderHint(gear);
            if (hint != null) {
                if (out.isEmpty()) {
                    out.add(Component.empty());
                }
                out.add(Component.literal("◆ " + hint).withStyle(ChatFormatting.DARK_AQUA));
            }
        }
        if (Settings.on("bankHint") && !inBank && !Vault.bankOpen() && !AutoAdvice.isWorn(stack)) {
            // the same thing kept in the bank (pages you opened): how many and on which page
            String in = Vault.tooltipLine(nameOf(stack, gear));
            if (in != null && !in.isEmpty()) {
                if (out.isEmpty()) {
                    out.add(Component.empty());
                }
                for (String line : wrap("▣ " + in, 38)) {
                    out.add(Component.literal(line).withStyle(ChatFormatting.DARK_AQUA));
                }
            }
        }
        int quest = Settings.on("glowQuest") && !own ? QuestAdvisor.rankOf(stack) : -1;
        if (quest >= 0) {
            // A quest in the content book that the advisor ranks among the best three
            QuestAdvisor.Suggestion q = QuestAdvisor.top().get(quest);
            out.add(Component.empty());
            out.add(Component.literal(TooltipListener.marker()).withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(L.c("wynnlabs.advice.quest_rank", quest + 1).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(L.c("wynnlabs.advice.tag_suggestion").withStyle(ChatFormatting.DARK_GRAY)));
            for (String line : wrap(String.join(", ", q.reasons()), 38)) {
                out.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
            }
        }
        // the price and its listings ("◆ Fiyat … / Saat … itibarıyla N ilan"); boxes keep their own list
        if (Settings.on("tooltipPrice")) {
            var parsedForPrice = WynntilsItems.view(stack);
            if (!(parsedForPrice.isPresent()
                    && parsedForPrice.get().isBox())) {
                priceLines(stack, gear, out);
            }
        }
        if (out.size() <= 5 || net.minecraft.client.Minecraft.getInstance().hasShiftDown()) {
            return out;
        }
        // Short by default: each verdict with its first two note lines, the first downside (red),
        // what a shop also wants, prices and the bank line; the rest behind Shift.
        List<Component> shortList = new ArrayList<>();
        String markerText = TooltipListener.marker();
        String wornWord = L.t("wynnlabs.advice.worn");
        String wantsPrefix = L.t("wynnlabs.advice.also_wants", "").strip();
        int sinceHead = 99;
        int reds = 0;
        int dropped = 0;
        for (Component c : out) {
            String t = c.getString();
            var colour = c.getStyle().getColor();
            boolean red = colour != null && colour.equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.RED));
            boolean keep;
            if (t.isEmpty()) {
                keep = shortList.isEmpty() || !shortList.get(shortList.size() - 1).getString().isEmpty();
            } else if (t.contains(markerText) || t.startsWith(wornWord)) {
                keep = true;
                sinceHead = 0;
            } else if (red) {
                keep = reds++ == 0;
            } else if (t.startsWith("◆ ") || t.startsWith("▣ ") || (!wantsPrefix.isEmpty() && t.startsWith(wantsPrefix))) {
                keep = true;
            } else if (t.startsWith("   ") && !shortList.isEmpty()
                    && shortList.get(shortList.size() - 1).getString().startsWith("◆ ")) {
                keep = true;                    // the listing line under a price ("Saat 14:05 itibarıyla …")
            } else {
                keep = ++sinceHead <= 2;
            }
            if (keep) {
                shortList.add(c);
            } else if (!t.isEmpty()) {
                dropped++;
            }
        }
        if (dropped > 0) {
            shortList.add(L.c("wynnlabs.advice.shift_more", dropped).withStyle(ChatFormatting.DARK_GRAY));
        }
        return shortList;
    }

    /**
     * "Trade Market" or "Blacksmith" for a sell verdict, from its note; null otherwise. These two
     * are game names and the same in every language, so callers may compare them. For an IDENTIFY
     * verdict that sells on the Trade Market afterwards it returns display text ("sonra Trade
     * Market" / "then Trade Market"), which no caller compares.
     */
    static String sellPlace(Entry e) {
        if (e == null || e.note() == null || !(e.verdict() == Verdict.SELL || e.verdict() == Verdict.IDENTIFY)) {
            return null;
        }
        String place = placeIn(e.note());
        if ("Blacksmith".equals(place)) {
            return e.verdict() == Verdict.SELL ? "Blacksmith" : null;
        }
        if ("Trade Market".equals(place)) {
            return e.verdict() == Verdict.SELL ? "Trade Market" : L.t("wynnlabs.advice.sell_place_later", "Trade Market");
        }
        return null;
    }

    private static final java.util.regex.Pattern SELL_WORD =
            java.util.regex.Pattern.compile("(?i)\\bsell\\b");

    /**
     * Where a note says to sell: the Turkish phrases ("Blacksmith'e sat", "Trade Market'te sat"),
     * else in an English note the first "Blacksmith" / "Trade Market" after the word "sell".
     * Notes come from the rules, saved files and tools, in either language.
     */
    private static String placeIn(String n) {
        if (n.contains("Blacksmith'e sat")) {
            return "Blacksmith";
        }
        if (n.contains("Trade Market'te sat")) {
            return "Trade Market";
        }
        var m = SELL_WORD.matcher(n);
        if (!m.find()) {
            return null;
        }
        String after = n.substring(m.start());
        int b = after.indexOf("Blacksmith");
        int t = after.indexOf("Trade Market");
        if (b < 0 && t < 0) {
            return null;
        }
        return t < 0 || (b >= 0 && b < t) ? "Blacksmith" : "Trade Market";
    }

    private static final java.util.regex.Pattern COST_LINE =
            java.util.regex.Pattern.compile("([✖✔])\\s*(\\d+)\\s*x\\s+(.+)");

    /**
     * The materials a shop asks for in the item's "Price" block ("✖ 1x Time Fragment"), each
     * as "1× Time Fragment (yok)" or "(var)". Empty for plain emerald prices and other items.
     */
    static List<String> extraCost(ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        boolean inPrice = false;
        for (Component c : lore.lines()) {
            String line = ItemJson.clean(c.getString()).trim();
            if (line.contains("Price") || line.contains("Fiyat")) {
                inPrice = true;
                continue;
            }
            if (!inPrice) {
                continue;
            }
            var m = COST_LINE.matcher(line);
            if (m.find()) {
                String what = m.group(3).trim();
                boolean have = m.group(1).equals("✔");
                String state = have ? L.t("wynnlabs.advice.cost_have") : L.t("wynnlabs.advice.cost_missing");
                if (!have) {
                    // not in the bag, but maybe in the bank: say which page
                    Vault.Group g = Vault.find(what);
                    if (g != null && g.reachable()) {
                        state = L.t("wynnlabs.advice.cost_in_bank", g.total(), Vault.where(g));
                    }
                }
                out.add(m.group(2) + "× " + what + state);
            } else if (!out.isEmpty()) {
                break;                          // the price block is over
            }
        }
        return out;
    }

    /**
     * Trade Market price under the advice: "◆ Market: ~4 EB", and for a box every item that can
     * come out of it with its own price, so you see what the box is worth at a glance.
     */
    private static void priceLines(ItemStack stack, ItemView gear, List<Component> out) {
        try {
            var parsed = WynntilsItems.view(stack);
            // gear, and anything else with a name the market knows (ingredients, materials, powders…)
            String priced = gear != null ? gear.data().name()
                    : parsed.isPresent() && parsed.get().name() != null
                            && !parsed.get().isBox() ? parsed.get().name() : null;
            if (priced != null) {
                Prices.refreshSoon(priced);           // hovered: ask for its listings if 10+ minutes old
                String p = Prices.text(priced, gear != null && gear.unidentified());
                if (p != null) {
                    if (out.isEmpty()) {
                        out.add(Component.empty());
                    }
                    out.add(L.c("wynnlabs.advice.price", p).withStyle(ChatFormatting.GOLD));
                    Prices.Price pr = Prices.of(priced);
                    String when = pr == null ? null : pr.when();
                    if (when != null) {
                        boolean doubt = pr.doubt() != null;
                        out.add(Component.literal("   " + when).withStyle(doubt ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
                    }
                }
                return;
            }
            if (parsed.isEmpty()
                    || !parsed.get().isBox()) {
                return;
            }
            var infos = new ArrayList<>(WynntilsItems.possibleGears(parsed.get().box()));
            infos.sort(java.util.Comparator.comparingInt(i -> i.requirements().level()));
            if (infos.isEmpty()) {
                return;
            }
            if (out.isEmpty()) {
                out.add(Component.empty());
            }
            out.add(L.c("wynnlabs.advice.box_prices").withStyle(ChatFormatting.GOLD));
            int shown = 0;
            for (var info : infos) {
                if (shown++ == 8) {
                    out.add(L.c("wynnlabs.advice.box_more", infos.size() - 8)
                            .withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                String p = Prices.text(info.name(), true);
                out.add(Component.literal("   Lv. " + info.requirements().level() + " " + info.name() + ": ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(p == null ? "?" : p)
                                .withStyle(p == null ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW)));
            }
        } catch (Throwable ignored) {
            // a price line must never break the tooltip
        }
    }

    /** The item's own name (gear, ingredient, material…), without counts or stars. */
    private static String nameOf(ItemStack stack, ItemView gear) {
        if (gear != null) {
            return gear.data().name();
        }
        try {
            var p = WynntilsItems.view(stack);
            if (p.isPresent() && p.get().name() != null) {
                return p.get().name();
            }
        } catch (Throwable ignored) {
        }
        return ItemJson.clean(stack.getHoverName().getString());
    }

    private static ItemView gearOf(ItemStack stack) {
        try {
            var p = WynntilsItems.view(stack);
            return p.isPresent() && p.get().isGear() ? p.get() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : SPACES.split(text.strip())) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
