package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.MenuKind;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Kasa: everything in every bank page you open is remembered (vault.json, shared by all
 * characters): gear, materials, ingredients, potions, boxes, with counts. With it the mod says
 * which stored piece would help the character you play now, where a thing you need is kept
 * (tooltips, shop costs, quests), and what in the bank is only taking room (the rules' SAT
 * verdict, decided while the page is open). Only pages you open yourself; never opens anything.
 */
final class Vault {

    /**
     * One stack in a bank page. {@code verdict}/{@code note}: the rules' advice when the page
     * was last open (null when they have none), {@code noteLang}: the language the note is worded
     * in (null in files from before it was kept), {@code by}: the class that judged it.
     * {@code prof}: the professions an ingredient or material is for ("COOKING,ALCHEMISM").
     */
    record Stored(String name, int level, String page, long seen, int count, boolean gear, String kind,
            String verdict, String note, String noteLang, String by, String prof, int slot, String item, String box) {

        /** Items with one name but different boxes (tier, levels) stay apart: "name|box". */
        String key() {
            return box == null ? name : name + "|" + box;
        }
    }

    record Find(Stored item, double gain) {}

    /** One item over all pages: total count and where. */
    record Group(String name, int total, List<Stored> places, boolean reachable) {

        String key() {
            return places.get(0).key();
        }

        Stored first() {
            return places.get(0);
        }

        /** The SAT verdict from any page (the rules judge a name the same everywhere). */
        Stored sell() {
            return places.stream().filter(s -> Verdict.of(s.verdict()) == Verdict.SELL).findFirst().orElse(null);
        }
    }

    private static final Map<String, List<Stored>> PAGES = new LinkedHashMap<>();
    private static boolean loaded;
    private static String lastRead = "";
    /** What the bank reader is doing, built only when asked (json()); null: no bank opened yet. */
    private static java.util.function.Supplier<String> status = null;
    static int version;                   // bumps on every change; screens cache on it

    private Vault() {}

    static void onTick(Minecraft mc) {
        // a read every tick: two in a row that agree (a page whose items have all arrived) save
        // it, about a tenth of a second after it opens
        if (mc.player == null || mc.screen == null) {
            return;
        }
        var menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            return;
        }
        // Wynntils knows which bank this is (character / account) and which page
        MenuKind c = Wyn.container();
        boolean own = c != null && c.characterBank();
        boolean acct = c != null && c.accountBank();
        if (!own && !acct) {
            String type = c == null ? null : c.type();
            var title = mc.screen.getTitle();
            status = () -> L.t("wynnlabs.vault.status_not_bank", type == null ? L.t("wynnlabs.vault.status_unknown")
                    : type, title.getString());
            return;
        }
        if (own && !Profile.settled()) {
            status = () -> L.t("wynnlabs.vault.status_switching");
            return;
        }
        load();
        // the page key saved in vault.json (data, read back by pageNumber/label): never translated
        String title = (own ? "Character Bank" : "Account Bank") + PAGE + page(c, menu);
        // two banks: the character bank only this character can open, the account bank all can
        String key = (own ? "char:" + Profile.id() + ":" : "acct:") + title;
        // first only names and counts: a page is read in full (verdicts, the item itself) once
        // two reads agree, not four times a second while it is open
        StringBuilder names = new StringBuilder(key).append('|');
        int shown = 0;
        for (int index : c.slots()) {
            if (index >= menu.slots.size()) {
                continue;
            }
            ItemStack stack = menu.slots.get(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name;
            try {
                name = nameOf(stack);
            } catch (Throwable t) {
                name = clean(stack.getHoverName().getString());
            }
            if (name == null) {
                continue;
            }
            names.append(shown++ == 0 ? "" : ";").append(name).append('x').append(stack.getCount());
        }
        String seenNow = names.toString();
        boolean stable = seenNow.equals(lastRead);
        lastRead = seenNow;
        int count = shown;
        status = stable ? () -> L.n("wynnlabs.vault.status_saved", count, key, count)
                : () -> L.n("wynnlabs.vault.status_waiting", count, key, count);
        if (!stable) {
            return;
        }
        List<Stored> items = new ArrayList<>();
        long now = System.currentTimeMillis();
        String by = Wyn.classType().name();
        encodeBudget = 4;
        encodeDeferred = false;
        // only the storage slots (Wynntils knows them), not the page arrows and buttons
        for (int index : c.slots()) {
            if (index >= menu.slots.size()) {
                continue;
            }
            ItemStack stack = menu.slots.get(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            try {
                Stored st = read(stack, key, now, by, index);
                if (st != null) {
                    items.add(st);
                }
            } catch (Throwable t) {
                // keep the name at least; a broken item must not stop the page being saved
                items.add(new Stored(clean(stack.getHoverName().getString()), 0, key, now, stack.getCount(),
                        false, "unknown", null, null, null, null, "", index, encode(stack), null));
            }
        }
        // the page number and the items arrive together; saved as soon as two reads agreed (above,
        // names and counts only: a verdict's note can change while the build plan is recomputed).
        // Stacks not encoded yet are saved without their item and filled in by the next reads, so
        // flipping through the pages quickly still saves every page.
        List<Stored> old = PAGES.get(key);
        if (encodeDeferred && old != null) {
            items = keepItems(old, items);      // a stack already kept from an earlier visit stays kept
        }
        if (old == null || !same(old, items)) {
            PAGES.put(key, items);
            version++;
            save();
        }
    }

    /** The name a bank stack is stored under, or null for none (what {@link #read} uses). */
    private static String nameOf(ItemStack stack) {
        var parsed = GameState.current().view(stack);      // Wynntils in the game, the recording in the preview
        String name = parsed.isPresent() && parsed.get().name() != null ? parsed.get().name()
                : ItemJson.clean(stack.getHoverName().getString());
        name = clean(name);
        return name.isBlank() ? null : name;
    }

    /** One bank stack as stored: its name, count, kind and the rules' verdict right now. */
    private static Stored read(ItemStack stack, String key, long now, String by, int slot) {
        var parsed = GameState.current().view(stack);      // Wynntils in the game, the recording in the preview
        String name = nameOf(stack);
        if (name == null) {
            return null;
        }
        boolean gear = parsed.isPresent() && parsed.get().isGear();
        int level = parsed.isPresent() && parsed.get().isGear() ? parsed.get().data().level()
                : parsed.isPresent() && parsed.get().level() != null
                        ? parsed.get().level() : 0;
        String kind = parsed.map(AutoAdvice::kindOf).orElse("unknown");
        String prof = parsed.isPresent() && parsed.get().professions() != null
                ? String.join(",", parsed.get().professions()) : "";
        Advice.Entry e = AutoAdvice.cached(stack);
        // a box: its type, tier and levels, to tell boxes apart and list what can come out
        ItemView.Box b = parsed.isPresent() && parsed.get().isBox() ? parsed.get().box() : null;
        String box = b != null
                ? b.slot().name() + "|" + b.tier().name() + "|" + b.low() + "|"
                        + b.high() : null;
        return new Stored(name, level, key, now, stack.getCount(), gear, kind,
                e == null ? null : e.verdict().name(), e == null ? null : e.note(), e == null || e.note() == null ? null : L.code(),
                by, prof, slot, encode(stack), box);
    }

    /** Each stack's JSON, by identity: a page stays open for many reads, its stacks do not change. */
    private static final java.util.WeakHashMap<ItemStack, String> ENCODED = new java.util.WeakHashMap<>();

    /** The stack as JSON (Minecraft's item codec), to draw its icon and tooltip later; null if not possible. */
    /** Item codec runs left in this read: a full page is encoded over a few reads, not in one tick. */
    private static int encodeBudget;
    private static boolean encodeDeferred;

    private static String encode(ItemStack stack) {
        String known = ENCODED.get(stack);
        if (known != null) {
            return known;
        }
        if (encodeBudget <= 0) {
            encodeDeferred = true;              // the page waits for the next read
            return null;
        }
        encodeBudget--;
        String json = encodeNow(stack);
        if (json != null) {
            ENCODED.put(stack, json);
        }
        return json;
    }

    private static String encodeNow(ItemStack stack) {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) {
                return null;
            }
            var ops = level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            return ItemStack.CODEC.encodeStart(ops, stack).result().map(Object::toString).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Map<String, ItemStack> STACKS = new java.util.HashMap<>();

    /** The stored item to draw: the real one when it was saved, else a named paper. */
    static ItemStack stackOf(Stored s) {
        String k = s.item() == null ? "name:" + s.name() : s.item();
        return STACKS.computeIfAbsent(k, x -> {
            try {
                var level = Minecraft.getInstance().level;
                if (s.item() != null && level != null) {
                    var ops = level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                    var r = ItemStack.CODEC.parse(ops, JsonParser.parseString(s.item())).result();
                    if (r.isPresent()) {
                        annotate(r.get());
                        return r.get();
                    }
                }
            } catch (Throwable ignored) {
            }
            ItemStack paper = new ItemStack(net.minecraft.world.item.Items.PAPER, Math.max(1, Math.min(64, s.count())));
            paper.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(s.name()));
            return paper;
        });
    }

    /** The stored box (type, tier, levels), or null when it is not one. */
    static ItemView.Box boxOf(Stored s) {
        if (s.box() == null) {
            return null;
        }
        try {
            String[] p = s.box().split("\\|");
            return new ItemView.Box(Slot.valueOf(p[0]), Tier.valueOf(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lets Wynntils read a saved copy as it reads items in a container, so its tooltip shows
     * what it shows in the bank (possible items, stat ranges). Its annotate method is private.
     */
    static volatile String annotateProblem;          // null while it works

    private static void annotate(ItemStack stack) {
        if (!Wyn.annotate(stack)) {
            // the plain tooltip then, with the mod's own lines; settings say so
            annotateProblem = Wyn.down().getOrDefault(Wyn.Feature.ITEMS, "annotate");
        }
    }

    private static final Map<ItemStack, List<net.minecraft.network.chat.Component>> TIP_EXTRA =
            new java.util.IdentityHashMap<>();

    /** The mod's lines for a saved copy shown in the J menu (set while it is hovered). */
    static void setTipExtra(ItemStack stack, List<net.minecraft.network.chat.Component> lines) {
        TIP_EXTRA.clear();
        TIP_EXTRA.put(stack, lines);
    }

    static List<net.minecraft.network.chat.Component> tipExtra(ItemStack stack) {
        return TIP_EXTRA.get(stack);
    }

    /** Every saved page: account bank by number, then your character bank, then the others. */
    /** For the no-game preview: bank pages as if they were opened (page key → its stacks in slot order). */
    static void preview(Map<String, List<ItemStack>> pages) {
        loaded = true;
        PAGES.clear();
        long now = System.currentTimeMillis();
        for (var e : pages.entrySet()) {
            List<Stored> list = new ArrayList<>();
            for (int i = 0; i < e.getValue().size(); i++) {
                Stored s = read(e.getValue().get(i), e.getKey(), now, "preview", i);
                if (s != null) {
                    list.add(s);
                }
            }
            PAGES.put(e.getKey(), list);
        }
        version++;                              // a screen open before the pages came reads them again
    }

    static List<String> pageKeys() {
        load();
        List<String> keys = new ArrayList<>(PAGES.keySet());
        keys.sort(java.util.Comparator.comparingInt((String k) -> k.startsWith("acct:") ? 0 : reachable(k) ? 1 : 2)
                .thenComparing(k -> k.startsWith("char:") ? k.substring(0, k.indexOf(':', 5)) : "")
                .thenComparingInt(Vault::pageNumber));
        return keys;
    }

    /** Between a saved page key's bank and its number: "acct:Account Bank p. 3". */
    private static final String PAGE = " p. ";

    static int pageNumber(String key) {
        try {
            return Integer.parseInt(key.substring(key.lastIndexOf(PAGE) + PAGE.length()).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** "Account", "Character" or "Mage bank" (another character's): the group a page chip sits in. */
    static String bankName(String key) {
        if (key.startsWith("acct:")) {
            return L.t("wynnlabs.vault.bank_account");
        }
        if (key.startsWith("char:" + Profile.id() + ":")) {
            return L.t("wynnlabs.vault.bank_character");
        }
        String id = key.length() > 5 && key.indexOf(':', 5) > 0 ? key.substring(5, key.indexOf(':', 5)) : "";
        String cls = classOf(id);
        return cls == null ? L.t("wynnlabs.vault.bank_other_character")
                : L.t("wynnlabs.vault.bank_other", AutoAdvice.nice(cls));
    }

    static List<Stored> page(String key) {
        load();
        return PAGES.getOrDefault(key, List.of());
    }

    /** The widest and tallest slot layout seen in any page: {columns, rows}. */
    static int[] gridSize() {
        int maxCol = 7;
        int maxRow = 4;
        for (List<Stored> l : PAGES.values()) {
            for (Stored s : l) {
                if (s.slot() >= 0) {
                    maxCol = Math.max(maxCol, s.slot() % 9);
                    maxRow = Math.max(maxRow, s.slot() / 9);
                }
            }
        }
        return new int[] {maxCol + 1, maxRow + 1};
    }

    /** True when this stack sits in a storage slot of the bank page open now. */
    static boolean inOpenBank(ItemStack stack) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.player == null || stack == null || stack.isEmpty()
                    || !(Wyn.container() instanceof MenuKind c) || !c.storage()) {
                return false;
            }
            var menu = mc.player.containerMenu;
            for (int index : c.slots()) {
                if (index < menu.slots.size() && menu.slots.get(index).getItem() == stack) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** True while any bank page is open (the "in the bank" tooltip line is then not needed). */
    static boolean bankOpen() {
        try {
            return Wyn.container() instanceof MenuKind c && c.storage();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The bank page shown now. Wynntils' bank model sets it from the page arrows when the page's
     * items arrive (the storage feature's copy is taken when the screen opens and can be the
     * last bank's page); "?" if unknown.
     */
    private static String page(MenuKind c, net.minecraft.world.inventory.AbstractContainerMenu menu) {
        // the arrows name the page they lead to ("Page 2", or translated): the previous one +1,
        // the next one -1; a bank with no arrows has one page. Wynntils' own page is kept when
        // there are no arrows, so a one-page character bank got the account bank's last page.
        Integer prev = arrowPage(menu, c.previousSlot());
        if (prev != null) {
            return String.valueOf(prev + 1);
        }
        Integer next = arrowPage(menu, c.nextSlot());
        if (next != null) {
            return String.valueOf(Math.max(1, next - 1));
        }
        return "1";
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("(\\d+)");

    private static Integer arrowPage(net.minecraft.world.inventory.AbstractContainerMenu menu, int slot) {
        if (slot < 0 || slot >= menu.slots.size()) {
            return null;
        }
        ItemStack st = menu.slots.get(slot).getItem();
        if (st.isEmpty()) {
            return null;
        }
        var m = NUMBER.matcher(clean(st.getHoverName().getString()));
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** The new read with each not-yet-encoded stack's item taken from the same stack saved before. */
    private static List<Stored> keepItems(List<Stored> old, List<Stored> now) {
        Map<String, String> kept = new java.util.HashMap<>();
        for (Stored o : old) {
            if (o.item() != null) {
                kept.put(o.slot() + "|" + o.name() + "|" + o.count(), o.item());
            }
        }
        List<Stored> out = new ArrayList<>(now.size());
        for (Stored n : now) {
            String item = n.item() == null ? kept.get(n.slot() + "|" + n.name() + "|" + n.count()) : null;
            out.add(item == null ? n : new Stored(n.name(), n.level(), n.page(), n.seen(), n.count(), n.gear(),
                    n.kind(), n.verdict(), n.note(), n.noteLang(), n.by(), n.prof(), n.slot(), item, n.box()));
        }
        return out;
    }

    private static boolean same(List<Stored> a, List<Stored> b) {
        return a.stream().map(Vault::sig).toList().equals(b.stream().map(Vault::sig).toList());
    }

    private static String sig(Stored s) {
        // the slot and whether the item itself is kept count too: a page saved before icons
        // existed must be saved again when you open it, even if nothing in it moved
        return s.name() + "x" + s.count() + "@" + s.slot() + (s.item() == null ? "-" : "+") + (s.box() == null ? "" : s.box()) + ":" + s.verdict() + ":" + s.note() + ":" + s.noteLang();
    }

    static int count() {
        load();
        return PAGES.values().stream().mapToInt(List::size).sum();
    }

    static int pages() {
        load();
        return PAGES.size();
    }

    /** True for the account bank and this character's own bank: the ones you can open now. */
    private static boolean reachable(String key) {
        return key.startsWith("acct:") || key.startsWith("char:" + Profile.id() + ":");
    }

    /** "Account p. 3", "Character p. 1", or "Mage bank p. 2" for another character's bank. */
    static String label(String key) {
        // the key itself keeps " p. N" in every language (saved data); only the shown label changes
        return key.contains(PAGE) ? L.t("wynnlabs.vault.page_label", bankName(key),
                key.substring(key.lastIndexOf(PAGE) + PAGE.length())) : bankName(key);
    }

    /**
     * Each character's class by id, from its meta.json, read in the background so a tooltip
     * never reads a file: all characters at start, and a character not seen yet when first asked.
     */
    private static final Map<String, java.util.Optional<String>> CLASSES = new java.util.concurrent.ConcurrentHashMap<>();
    /** How many times a meta.json was actually read (for /perf). */
    static final java.util.concurrent.atomic.AtomicInteger META_READS = new java.util.concurrent.atomic.AtomicInteger();

    /** A character's class as the profile switch just wrote it to meta.json. */
    static void knownClass(String id, String cls) {
        CLASSES.put(id, java.util.Optional.ofNullable(cls));
    }

    /** Reads every character's class in the background (at start). */
    static void preloadClasses() {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (var dirs = Files.list(Dirs.config().resolve("characters"))) {
                dirs.filter(Files::isDirectory).forEach(d -> CLASSES.put(d.getFileName().toString(), readClass(d.getFileName().toString())));
            } catch (Exception ignored) {
                // no characters yet
            }
        });
    }

    private static String classOf(String id) {
        java.util.Optional<String> known = CLASSES.get(id);
        if (known != null) {
            return known.orElse(null);
        }
        if (CLASSES.putIfAbsent(id, java.util.Optional.empty()) == null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> CLASSES.put(id, readClass(id)));
        }
        return null;                            // unknown for now: bankName says "another character"
    }

    private static java.util.Optional<String> readClass(String id) {
        try {
            // meta.json is written by the profile switch itself, so its class is always right
            Path p = Dirs.config().resolve("characters").resolve(id).resolve("meta.json");
            if (Files.exists(p)) {
                META_READS.incrementAndGet();
                return java.util.Optional.of(JsonParser.parseString(Files.readString(p)).getAsJsonObject().get("class").getAsString());
            }
        } catch (Exception ignored) {
        }
        return java.util.Optional.empty();
    }

    /** Every stored item by name, reachable banks first, then by total count. */
    static List<Group> groups() {
        load();
        String stamp = version + ":" + Profile.id();
        if (stamp.equals(groupsStamp)) {
            return groupsCache;
        }
        Map<String, List<Stored>> by = new LinkedHashMap<>();
        PAGES.forEach((k, list) -> list.forEach(s -> by.computeIfAbsent(s.key(), n -> new ArrayList<>()).add(s)));
        List<Group> out = new ArrayList<>();
        by.forEach((n, l) -> {
            l.sort((a, b) -> Boolean.compare(reachable(b.page()), reachable(a.page())));
            out.add(new Group(l.get(0).name(), l.stream().mapToInt(Stored::count).sum(), l,
                    l.stream().anyMatch(s -> reachable(s.page()))));
        });
        out.sort((a, b) -> a.reachable() != b.reachable() ? Boolean.compare(b.reachable(), a.reachable())
                : a.name().compareToIgnoreCase(b.name()));
        groupsCache = List.copyOf(out);
        groupsStamp = stamp;
        return groupsCache;
    }

    private static List<Group> groupsCache = List.of();
    private static String groupsStamp = "";

    /** Pages of this group with counts: "Account p. 3 (40), Character p. 1 (24)". */
    static String where(Group g) {
        Map<String, Integer> per = new LinkedHashMap<>();
        g.places().forEach(s -> per.merge(label(s.page()), s.count(), Integer::sum));
        return per.entrySet().stream().map(e -> e.getKey() + (g.places().size() > 1 || e.getValue() > 1
                ? " (" + e.getValue() + ")" : "")).collect(Collectors.joining(", "));
    }

    /** The group for this item name, or null when the bank does not hold it. */
    static Group find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // tooltips ask every frame: the groups by normalised name are built once per groups list
        List<Group> all = groups();
        if (all != byNameFor) {
            Map<String, Group> m = new java.util.HashMap<>();
            for (Group g : all) {
                m.putIfAbsent(norm(g.name()), g);
            }
            byName = m;
            byNameFor = all;
        }
        return byName.get(norm(name));
    }

    private static Map<String, Group> byName = Map.of();
    private static List<Group> byNameFor;
    private static final java.util.regex.Pattern BRACKETS = java.util.regex.Pattern.compile("\\s*\\[.*?]\\s*");

    /** Without Wynncraft's private-use icon glyphs (shown as ▯ in plain text). */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(c -> !(c >= 0xE000 && c <= 0xF8FF) && c < 0xF0000 && !Character.isISOControl(c))
                .forEach(b::appendCodePoint);
        return ItemJson.clean(b.toString()).trim();
    }

    private static String norm(String s) {
        return BRACKETS.matcher(clean(s)).replaceAll("").replace("À", "").trim().toLowerCase(Locale.ROOT);
    }

    /** "In the bank ×64: Account p. 3, Character p. 1" for a tooltip, or null. Reachable pages only. */
    static String tooltipLine(String name) {
        Group g = find(name);
        if (g == null) {
            return null;
        }
        List<Stored> here = g.places().stream().filter(s -> reachable(s.page())).toList();
        if (here.isEmpty()) {
            return L.t("wynnlabs.vault.in_bank_other", where(g));
        }
        Group mine = new Group(g.name(), here.stream().mapToInt(Stored::count).sum(), here, true);
        return L.t("wynnlabs.vault.in_bank", mine.total(), where(mine));
    }

    // ---- quests ----

    /** Stored items a quest's text mentions ("Bring 5 Gudgeon Meat …"): the quest is easier. */
    static List<Group> forQuest(QuestBook.Entry q) {
        String text = q.task() == null ? "" : q.task().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return List.of();
        }
        List<Group> out = new ArrayList<>();
        for (Group g : groups()) {
            String n = norm(g.name());
            if (g.reachable() && n.length() >= 4 && text.contains(n)) {
                out.add(g);
            }
        }
        return out;
    }

    /** Open quests whose text mentions this item: keep it, a quest wants it. */
    static List<String> questsFor(Group g) {
        String n = norm(g.name());
        if (n.length() < 4) {
            return List.of();
        }
        return QuestBook.all().stream()
                .filter(q -> !"COMPLETED".equals(q.status()) && q.task() != null
                        && q.task().toLowerCase(Locale.ROOT).contains(n))
                .map(QuestBook.Entry::name).toList();
    }

    // ---- clean-up ----

    /** What the bank holds that the rules say to sell, Trade Market ones first. */
    static List<Group> junk() {
        List<Group> out = new ArrayList<>();
        for (Group g : groups()) {
            if (g.reachable() && g.sell() != null && questsFor(g).isEmpty()) {
                out.add(g);
            }
        }
        out.sort((a, b) -> Boolean.compare("Trade Market".equals(place(b)), "Trade Market".equals(place(a))));
        return out;
    }

    /** "Trade Market" / "Blacksmith" for a sell group, from the stored verdict. */
    static String place(Group g) {
        Stored s = g.sell();
        return s == null ? null : Advice.sellPlace(new Advice.Entry(s.verdict(), s.note()));
    }

    /**
     * The stack's note to show, in the language the mod speaks now. A note is worded when its
     * page is read; one worded in another language (or by a version that did not keep the
     * language) is not shown as it is: the rules judge the kept item again, for display only,
     * when the class that judged it is the one in play and the verdict comes out the same. Else
     * none, until the page is opened again and its notes are worded anew.
     */
    static String noteNow(Stored s) {
        if (s.note() == null) {
            return null;
        }
        if (L.code().equals(s.noteLang())) {
            return s.note();
        }
        try {
            GameState state = GameState.current();
            if (s.item() != null && s.by() != null && state.inGame() && s.by().equals(state.classType().name())) {
                Advice.Entry e = AutoAdvice.cached(stackOf(s));
                if (e != null && e.verdict().name().equals(s.verdict())) {
                    return e.note();
                }
            }
        } catch (Throwable ignored) {
            // no game, no item: nothing to judge again
        }
        return null;
    }

    /** Lowest Trade Market listing of a group (all of it), or null when unknown. */
    static Double value(Group g) {
        Prices.Price p = Prices.of(g.name());
        return p == null || p.typical() == null ? null : p.typical() * g.total();
    }

    /** Professions an ingredient or material is for, nicely: "Cooking, Alchemism (sen Lv. 12)". */
    static String professions(Stored s) {
        if (s.prof() == null || s.prof().isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String p : s.prof().split(",")) {
            String lv = "";
            try {
                lv = " Lv. " + Wyn.professionLevel(p);
            } catch (Throwable ignored) {
            }
            parts.add(AutoAdvice.nice(p) + lv);
        }
        return String.join(", ", parts);
    }

    /** Stored pieces that would make the current build stronger, best first (up to 3). */
    static List<Find> forMe() {
        load();
        List<Find> out = new ArrayList<>();
        Build.Plan plan = Build.plan();
        if (plan == null || plan.result().objective() <= 0) {
            return out;
        }
        int level = Wyn.level();
        for (var entry : PAGES.entrySet()) {
            // the account bank, or this character's own bank; never another character's
            if (!reachable(entry.getKey())) {
                continue;
            }
            for (Stored st : entry.getValue()) {
                if (!st.gear() || st.level() > level) {
                    continue;
                }
                var info = WynntilsItems.gearByName(st.name());
                if (info == null) {
                    continue;
                }
                Slot t = info.slot();
                var need = info.requirements().klass().orElse(t.klass());
                if (need != null && need != Klass.NONE
                        && need != Wyn.classType()) {
                    continue;
                }
                double best = Double.NaN;
                for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
                    if (slot == null) {
                        continue;
                    }
                    double v = Build.trySlot(plan.pieces(), plan.allocation(), slot, Damage.piece(info), level, plan.earned());
                    if (!Double.isNaN(v) && (Double.isNaN(best) || v > best)) {
                        best = v;
                    }
                }
                if (!Double.isNaN(best)) {
                    double gain = (best / plan.result().objective() - 1) * 100;
                    if (gain > 2) {
                        out.add(new Find(st, gain));
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.gain(), a.gain()));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static Path file() {
        return Dirs.config().resolve("vault.json");
    }

    private static String str(JsonObject j, String k) {
        return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : null;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path marker = file().resolveSibling("vault-fix-pages");
            if (!Files.exists(marker)) {
                // pages before this were saved under the previous bank's page number: start over
                Files.createDirectories(file().getParent());
                Files.deleteIfExists(file());
                Files.writeString(marker, "pages were keyed by a stale page number; vault.json reset");
                return;
            }
            if (!Files.exists(file())) {
                return;
            }
            JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            boolean renamed = false;
            for (String k : new ArrayList<>(o.keySet())) {
                // page keys were Turkish before the mod spoke English: same pages, new names
                String n = k.replace("Karakter Deposu s. ", "Character Bank" + PAGE)
                        .replace("Hesap Deposu s. ", "Account Bank" + PAGE);
                if (!n.equals(k)) {
                    o.add(n, o.remove(k));
                    renamed = true;
                }
            }
            o.entrySet().forEach(e -> {
                List<Stored> l = new ArrayList<>();
                e.getValue().getAsJsonArray().forEach(el -> {
                    var j = el.getAsJsonObject();
                    boolean gear = !j.has("gear") || j.get("gear").getAsBoolean();
                    l.add(new Stored(clean(j.get("name").getAsString()), j.get("level").getAsInt(), e.getKey(),
                            j.get("seen").getAsLong(), j.has("count") ? j.get("count").getAsInt() : 1, gear,
                            Objects.requireNonNullElse(str(j, "kind"), gear ? "gear" : "unknown"),
                            str(j, "verdict") == null ? null : Verdict.of(str(j, "verdict")).name(), str(j, "note"), str(j, "noteLang"),
                            str(j, "by"), Objects.requireNonNullElse(str(j, "prof"), ""),
                            j.has("slot") ? j.get("slot").getAsInt() : -1, j.has("item") ? j.get("item").toString() : null,
                            str(j, "box")));
                });
                PAGES.put(e.getKey(), l);
            });
            Path fix2 = file().resolveSibling("vault-fix-charpage");
            if (!Files.exists(fix2)) {
                // character banks with one page were saved under the account bank's last page
                PAGES.keySet().removeIf(k -> k.startsWith("char:"));
                Files.writeString(fix2, "character bank pages dropped once: page number came from the account bank");
                save();
            } else if (renamed) {
                save();
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read vault.json: " + e);
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            PAGES.forEach((k, v) -> {
                JsonArray a = new JsonArray();
                v.forEach(st -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("name", st.name());
                    j.addProperty("level", st.level());
                    j.addProperty("seen", st.seen());
                    j.addProperty("count", st.count());
                    j.addProperty("gear", st.gear());
                    j.addProperty("kind", st.kind());
                    if (st.verdict() != null) {
                        j.addProperty("verdict", st.verdict());
                        j.addProperty("note", st.note());
                        if (st.noteLang() != null) {
                            j.addProperty("noteLang", st.noteLang());
                        }
                        j.addProperty("by", st.by());
                    }
                    if (st.prof() != null && !st.prof().isEmpty()) {
                        j.addProperty("prof", st.prof());
                    }
                    j.addProperty("slot", st.slot());
                    if (st.box() != null) {
                        j.addProperty("box", st.box());
                    }
                    if (st.item() != null) {
                        j.add("item", JsonParser.parseString(st.item()));
                    }
                    a.add(j);
                });
                o.add(k, a);
            });
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save vault.json: " + e);
        }
    }

    /** /vault: every page with its items, verdicts and the clean-up list. */
    static JsonObject json() {
        load();
        JsonObject o = new JsonObject();
        JsonObject pages = new JsonObject();
        PAGES.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            v.forEach(s -> {
                JsonObject j = new JsonObject();
                j.addProperty("name", s.name());
                j.addProperty("count", s.count());
                j.addProperty("kind", s.kind());
                if (s.level() > 0) {
                    j.addProperty("level", s.level());
                }
                if (s.verdict() != null) {
                    j.addProperty("verdict", s.verdict());
                    j.addProperty("note", s.note());
                }
                if (!s.prof().isEmpty()) {
                    j.addProperty("professions", s.prof());
                }
                a.add(j);
            });
            pages.add(label(k), a);
        });
        o.addProperty("status", status == null ? L.t("wynnlabs.vault.status_none") : status.get());
        o.add("pages", pages);
        JsonArray junk = new JsonArray();
        for (Group g : junk()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", g.name());
            j.addProperty("count", g.total());
            j.addProperty("where", where(g));
            j.addProperty("sellAt", place(g));
            j.addProperty("note", g.sell().note());
            junk.add(j);
        }
        o.add("sell", junk);
        return o;
    }
}
