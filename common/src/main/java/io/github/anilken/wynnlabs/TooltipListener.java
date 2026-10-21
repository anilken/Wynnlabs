package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.MenuKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds the advice lines to tooltips that Wynntils builds itself.
 *
 * <p>For identified gear Wynntils replaces the vanilla tooltip with its own, so lines added
 * through Fabric's {@code ItemTooltipCallback} are lost there. Wynntils' tooltip event carries the
 * final list ({@link WynntilsEvents} hands it here), so appending at the lowest priority puts the
 * advice under whatever Wynntils (or another mod) drew. Boxes and other items keep the Fabric
 * lines, so it checks for them first.
 */
public final class TooltipListener {

    /** The head of every advice line ("✦ Öneri: " / "✦ Advice: "), in the game's language. */
    static String marker() {
        return L.t("wynnlabs.tooltip.marker");
    }

    /**
     * A pulsing glow behind ability tree nodes and menu buttons that have advice, so the
     * recommended ones stand out in the tree, and a gold one behind shopping list gear in shops,
     * the Trade Market and chests. Your own gear is left alone: Wynntils already tints it.
     */
    /**
     * What a slot draws for one stack, worked out once and reused: the red "cannot wear" frame,
     * the glow colour, the tree step number, and the badge (a word for boxes, a price for
     * identified gear). Rebuilt when the rules, settings or advice change, when the stack moves
     * between the bag and a menu, and about every half second (staggered per stack) for what
     * is not tracked (level, prices, worn gear). Client thread only.
     */
    private record SlotFacts(long until, int rules, int advice, boolean inBag, boolean cannot, int glow,
                             int step, int stepColour) {}

    /** The badge part; also drawn by the J menu preview without a slot, and on the hotbar. */
    record BadgeFacts(long until, int rules, int advice, Component word, int wordWidth, float wordScale,
                              int colour, String where, Component price, int priceWidth) {}

    private static final java.util.WeakHashMap<net.minecraft.world.item.ItemStack, SlotFacts> SLOT_FACTS = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<net.minecraft.world.item.ItemStack, BadgeFacts> BADGE_FACTS = new java.util.WeakHashMap<>();
    private static final int[][] OUTLINE = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** How long a stack's drawing facts live: 500 ms plus up to 250 ms, different per stack. */
    private static long lifetime(Object stack) {
        return 500 + (System.identityHashCode(stack) & 255);
    }

    private static SlotFacts slotFacts(Slot slot) {
        var stack = slot.getItem();
        var mc = Minecraft.getInstance();
        boolean inBag = mc.player != null && slot.container == mc.player.getInventory();
        long now = System.currentTimeMillis();
        SlotFacts f = SLOT_FACTS.get(stack);
        if (f != null && now < f.until() && f.rules() == Stamp.now() && f.inBag() == inBag) {
            return f;
        }
        int rules = Stamp.now();
        int advice = Advice.version;
        f = new SlotFacts(now + lifetime(stack), rules, advice, inBag, cannotWear(stack), glowOf(stack, inBag),
                stepOf(stack), stepColourOf(stack));
        SLOT_FACTS.put(stack, f);
        return f;
    }

    /** Gear you cannot wear (level, class, skill points, a quest). */
    private static boolean cannotWear(net.minecraft.world.item.ItemStack reqStack) {
        var reqGear = reqStack.isEmpty() ? java.util.Optional.<ItemView>empty()
                : WynntilsItems.view(reqStack).filter(ItemView::isGear);
        if (reqGear.isEmpty() || !Settings.on("itemAdvice")) {
            return false;
        }
        var info = reqGear.get().data();
        var req = info.requirements();
        boolean cannot = req.level() > Wyn.level();
        var needCls = req.klass().orElse(info.slot().klass());
        if (!cannot && needCls != null && needCls != Klass.NONE
                && needCls != Wyn.classType()) {
            cannot = true;
        }
        // the game's own checkmarks under the item (Wynntils reads them): the truth for skill points
        var inst = reqGear.get().rolled();
        var marks = inst != null ? inst.marks() : null;
        if (marks != null && marks.known()) {
            cannot = !marks.all();
        }
        if (!cannot && req.quest().isPresent()) {
            for (QuestBook.Entry q : QuestBook.all()) {
                if (q.name().equalsIgnoreCase(req.quest().get()) && !"COMPLETED".equals(q.status())) {
                    cannot = true;
                    break;
                }
            }
        }
        return cannot;
    }

    /** The glow colour behind the slot, or -1 for none. */
    private static int glowOf(net.minecraft.world.item.ItemStack stack, boolean inBag) {
        var mc = Minecraft.getInstance();
        Advice.Entry e = Advice.find(stack, k -> k.startsWith("ability:") || k.startsWith("name:"));
        if (e != null) {
            if (!Settings.on("glowTree") || e.verdict() == Verdict.TAKEN) {
                return -1;                          // a taken step keeps only its number
            }
            return Advice.rgb(e.verdict());
        } else if (isSkillButton(stack)) {
            if (!Settings.on("glowTree")) {
                return -1;
            }
            Advice.Entry auto = AutoAdvice.cached(stack);
            return auto == null ? -1 : Advice.rgb(auto.verdict());
        } else if (mc.player != null && inBag && Settings.on("sellGlow") && sellHere(stack) != 0) {
            return sellHere(stack);               // at the Blacksmith / Trade Market: what to sell here
        } else if (mc.player != null && inBag && Settings.on("glowUpgrade") && upgrade(stack)) {
            return 0x55FF55;                      // green: wear this, it beats what you have on
        } else if (mc.player != null && !inBag && Settings.on("glowShop") && onShoppingList(stack)) {
            return 0xFFAA00;                      // gold: on the shopping list, in a shop or chest
        } else if (mc.player != null && !inBag && Settings.on("glowQuest") && QuestAdvisor.rankOf(stack) >= 0) {
            return 0x55FFFF;                      // aqua: a recommended quest in the content book
        }
        return -1;
    }

    /** The plan's step on an ability tree node, or 0. */
    private static int stepOf(net.minecraft.world.item.ItemStack stack) {
        Advice.Entry e = Advice.find(stack, k -> k.startsWith("ability:"));
        if (e == null || e.verdict() != Verdict.BUY && e.verdict() != Verdict.BUY_LATER && e.verdict() != Verdict.TAKEN) {
            return 0;
        }
        return Advice.planStep(e);
    }

    private static int stepColourOf(net.minecraft.world.item.ItemStack stack) {
        Advice.Entry e = Advice.find(stack, k -> k.startsWith("ability:"));
        if (e == null) {
            return 0;
        }
        return e.verdict() == Verdict.BUY ? 0xFFFFE071 : e.verdict() == Verdict.TAKEN ? 0xFFB4B2A9 : 0xFFF3E6C4;
    }

    /**
     * A pulsing glow behind ability tree nodes and menu buttons that have advice, so the
     * recommended ones stand out in the tree, and a gold one behind shopping list gear in shops,
     * the Trade Market and chests. Your own gear is left alone: Wynntils already tints it.
     */
    static void onSlot(GuiGraphics graphics, Slot slot) {
        if (!Rules.adviceHere() || slot.getItem().isEmpty()) {
            return;
        }
        try {
            SlotFacts f = slotFacts(slot);
            // gear you cannot wear (level, class, skill points, a quest): a red slot with a red frame,
            // everywhere (bag, shops, Trade Market, chests), so it is not bought or kept by mistake
            if (f.cannot()) {
                GuiGraphics rg = graphics;
                int rx = slot.x;
                int ry = slot.y;
                rg.fill(rx, ry, rx + 16, ry + 16, 0x55E24B4A);
                rg.fill(rx - 1, ry - 1, rx + 17, ry, 0xFFE24B4A);
                rg.fill(rx - 1, ry + 16, rx + 17, ry + 17, 0xFFE24B4A);
                rg.fill(rx - 1, ry, rx, ry + 16, 0xFFE24B4A);
                rg.fill(rx + 16, ry, rx + 17, ry + 16, 0xFFE24B4A);
            }
            int rgb = f.glow();
            if (rgb < 0) {
                return;
            }
            double t = (System.currentTimeMillis() % 1600L) / 1600.0;
            float pulse = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
            GuiGraphics g = graphics;
            int x = slot.x;
            int y = slot.y;
            // soft halo: a wider faint ring, then the brighter slot fill
            int halo = ((int) (40 + 60 * pulse) << 24) | rgb;
            int fill = ((int) (90 + 110 * pulse) << 24) | rgb;
            g.fill(x - 2, y - 2, x + 18, y - 1, halo);
            g.fill(x - 2, y + 17, x + 18, y + 18, halo);
            g.fill(x - 2, y - 1, x - 1, y + 17, halo);
            g.fill(x + 17, y - 1, x + 18, y + 17, halo);
            g.fill(x - 1, y - 1, x + 17, y + 17, fill);
        } catch (Throwable ignored) {
            // drawing a glow must never break the menu
        }
    }

    /**
     * After the item is drawn, on your own items (bag and an open bank): the verdict's word
     * ("SAT", "SAKLA", "TANIMLA" …) in small type on a dark strip along the slot's bottom, in
     * the verdict's colour, so it reads at a glance without hovering.
     */
    private static void verdictBadge(GuiGraphics g, Slot slot) {
        var stack = slot.getItem();
        var mc = Minecraft.getInstance();
        if (stack.isEmpty() || mc.player == null) {
            return;
        }
        // every menu: the bag, the bank, chests, daily rewards, loot chests, shops
        drawBadge(g, slot.x, slot.y, stack);
    }

    static BadgeFacts badgeFacts(net.minecraft.world.item.ItemStack stack) {
        long now = System.currentTimeMillis();
        BadgeFacts f = BADGE_FACTS.get(stack);
        if (f != null && now < f.until() && f.rules() == Stamp.now()) {
            return f;
        }
        int rules = Stamp.now();
        int advice = Advice.version;
        f = buildBadge(stack, now + lifetime(stack), rules, advice);
        BADGE_FACTS.put(stack, f);
        return f;
    }

    private static BadgeFacts buildBadge(net.minecraft.world.item.ItemStack stack, long until, int rules, int advice) {
        BadgeFacts none = new BadgeFacts(until, rules, advice, null, 0, 0, 0, null, null, 0);
        if (!Settings.on("itemAdvice")) {           // verdictBadges is the menus' own switch: drawBadge
            return none;
        }
        var view = GameState.current().view(stack);
        if (view.isEmpty()) {
            return none;
        }
        var font = Minecraft.getInstance().font;
        boolean box = view.get().isBox() || view.get().isGear() && view.get().unidentified();
        if (!box) {
            // an identified piece: its Trade Market price only, small and green, bottom-right
            if (view.get().isGear()) {
                Prices.Price pr = GameState.current().price(view.get().data().name());
                Double typical = pr == null ? null : pr.typical();
                if (typical != null) {
                    Component c = styled(Prices.fmt(typical).replace(" ", ""));
                    return new BadgeFacts(until, rules, advice, null, 0, 0, 0, null, c, font.width(c));
                }
            }
            return none;
        }
        // a box or unidentified piece: what to do with it, and where to sell it
        Advice.Entry e = Advice.find(stack, k -> !k.startsWith("ability:") && !k.startsWith("name:"));
        if (e == null) {
            e = AutoAdvice.cached(stack);
        }
        if (e == null || e.verdict() == null || e.verdict() == Verdict.NOTE) {
            return none;
        }
        // a short word that fits the slot at two-thirds size: SAT, TUT, TANI ...
        String word = L.t("wynnlabs.badge." + e.verdict().name().toLowerCase(java.util.Locale.ROOT));
        // a sale says where: the Trade Market in gold, the Blacksmith in red, and the same
        // colour behind a small icon bottom-left (cart / anvil)
        String where = null;
        if (e.verdict() == Verdict.SELL && e.note() != null) {
            String place = Advice.sellPlace(e);
            where = "Blacksmith".equals(place) ? "hammer" : "Trade Market".equals(place) ? "shopping-cart" : null;
        }
        int colour = 0xFF000000 | ("shopping-cart".equals(where) ? 0xFFC23A : e.verdict().rgb());
        int tw = font.width(word);
        Component c = styled(word);
        return new BadgeFacts(until, rules, advice, c, font.width(c), tw * 2f / 3f <= 18 ? 2f / 3f : 16f / tw, colour, where,
                null, 0);
    }

    private static Component styled(String text) {
        return Component.literal(text).withStyle(st -> st.withBold(false));
    }

    /** The word and the where-to-sell icon on a slot at x, y (the J menu preview draws them too). */
    static void drawBadge(GuiGraphics g, int x, int y, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !Settings.on("verdictBadges")) {
            return;
        }
        BadgeFacts f = badgeFacts(stack);
        var font = Minecraft.getInstance().font;
        if (f.price() != null) {
            g.nextStratum();
            outlined(g, font, f.price(), f.priceWidth(), x + 17, y + 16, 0.42f, 0xFF55FF55, true);
            return;
        }
        if (f.word() == null) {
            return;
        }
        // items are drawn at the end of their layer, over anything drawn with them: a layer of
        // our own on top, so the item picture (and the pack's overlay on it) stays under the word
        g.nextStratum();
        // top-left, like Wynntils' tier numerals: the game's own font, outlined,
        // two-thirds size (sharp at GUI scale 3)
        outlined(g, font, f.word(), f.wordWidth(), x - 1, y - 1, f.wordScale(), f.colour(), false);
        if (f.where() != null) {
            // a dark disc with the icon in the word's colour: reads on any item
            Art.box(g, x - 1, y + 8, 9, 9, 4, 0xE0101010, f.colour());
            Art.icon(g, f.where(), x, y + 9, 7, f.colour());
        }
    }

    /**
     * Text at a scale with a dark outline all round (a drop shadow sits off the letters when
     * scaled). {@code rightBottom}: x, y are the bottom-right corner, else the top-left.
     */
    static void outlined(GuiGraphics g, net.minecraft.client.gui.Font font, Component c, int w, float x, float y,
            float scale, int colour, boolean rightBottom) {
        float s = rightBottom ? Math.min(scale, 17f / Math.max(1, w)) : scale;
        g.pose().pushMatrix();
        g.pose().translate(rightBottom ? x - w * s : x, rightBottom ? y - 8 * s : y);
        g.pose().scale(s, s);
        for (int[] d : OUTLINE) {
            g.drawString(font, c, d[0], d[1], 0xFF1A0A0A, false);
        }
        g.drawString(font, c, 0, 0, colour, false);
        g.pose().popMatrix();
    }

    static void onSlotAfter(GuiGraphics g, Slot slot) {
        if (slot.getItem().isEmpty()) {
            return;
        }
        if (Rules.adviceHere()) {
            try {
                verdictBadge(g, slot);
            } catch (Throwable ignored) {
                // a badge must never break the menu
            }
        }
        // the tree's nodes fill the menu's first 6 rows (9 x 6 = 54 slots); the archetype and
        // point icons below carry node names too, and get no number
        if (!Rules.adviceHere() || !Settings.on("glowTree") || slot.index >= 54
                || Minecraft.getInstance().player != null && slot.container == Minecraft.getInstance().player.getInventory()) {
            return;
        }
        SlotFacts f = slotFacts(slot);
        int step = f.step();
        if (step <= 0) {
            return;
        }
        g.nextStratum();                            // over the node's picture
        var font = Minecraft.getInstance().font;
        String s = String.valueOf(step);
        int w = font.width(s);
        int x = slot.x + 17 - w;
        int y = slot.y - 2;
        g.fill(x - 2, y - 1, x + w + 1, y + 9, 0xC0000000);
        g.drawString(font, s, x, y, f.stepColour(), true);
    }

    private static boolean isSkillButton(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var p = WynntilsItems.view(stack);
        return p.isPresent() && p.get().kind() == ItemView.Kind.SKILL_POINT;
    }

    private static boolean upgrade(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Advice.Entry e = AutoAdvice.cached(stack);
        return e != null && (e.verdict() == Verdict.WEAR || e.verdict() == Verdict.SPEND);
    }

    /**
     * In the Blacksmith's sell menu: red for what the rules sell to the Blacksmith. In the Trade
     * Market's sell menu: gold for what they sell there. 0 elsewhere or for anything else.
     */
    private static int sellHere(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        MenuKind c = Wyn.container();
        boolean smith = c != null && c.blacksmithSell();
        boolean market = c != null && c.marketSell();
        if (!smith && !market) {
            return 0;
        }
        Advice.Entry e = AutoAdvice.cached(stack);
        String where = Advice.sellPlace(e);
        if (smith && "Blacksmith".equals(where)) {
            return 0xFF5555;
        }
        if (market && "Trade Market".equals(where)) {
            return 0xFFAA00;
        }
        return 0;
    }

    private static boolean onShoppingList(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var p = WynntilsItems.view(stack);
        return p.isPresent() && p.get().isGear() && Shopping.find(p.get().data().name()) != null;
    }

    /** The tooltip with the mod's lines added, or null to leave it as it is. */
    /**
     * The final tooltip for a stack, kept a quarter second: Wynntils asks every frame, and adding
     * the price lines reads every line's text. Rebuilt at once when the stamp (rules, prices,
     * advice, what you carry) or Shift changes, or when Wynntils hands a different number of lines.
     */
    private record Tip(long until, int stamp, boolean shift, int inSize, List<Component> out) {}

    private static final java.util.WeakHashMap<net.minecraft.world.item.ItemStack, Tip> TIPS = new java.util.WeakHashMap<>();

    static List<Component> onTooltip(net.minecraft.world.item.ItemStack stack, List<Component> lines) {
        long now = System.currentTimeMillis();
        int stamp = Stamp.now();
        boolean shift = Minecraft.getInstance().hasShiftDown();
        Tip t = TIPS.get(stack);
        if (t != null && now < t.until() && t.stamp() == stamp && t.shift() == shift && t.inSize() == lines.size()) {
            return t.out() == null ? null : new ArrayList<>(t.out());
        }
        List<Component> out = buildTooltip(stack, lines);
        TIPS.put(stack, new Tip(now + 250 + (System.identityHashCode(stack) & 127), stamp, shift, lines.size(),
                out == null ? null : List.copyOf(out)));
        return out;
    }

    private static List<Component> buildTooltip(net.minecraft.world.item.ItemStack stack, List<Component> lines) {
        try {
            debugDump(stack, lines);
            List<Component> out = new ArrayList<>(lines);
            boolean changed = withPrices(stack, out);
            boolean present = false;
            String marker = marker();
            for (Component line : lines) {
                String text = line.getString();
                if (text.contains(marker) || text.startsWith("◆ ") || text.startsWith("▣ ")) {
                    present = true;               // already there via ItemTooltipCallback
                    break;
                }
            }
            if (!present) {
                // only when the Fabric hook's lines are not there already: Wynntils' own tooltips
                List<Component> advice = Advice.linesCached(stack);
                if (!advice.isEmpty()) {
                    out.addAll(advice);
                    changed = true;
                }
            }
            return changed ? out : null;
        } catch (Throwable ignored) {
            // a broken advice line must never break the tooltip
            return null;
        }
    }

    private static final String PRICE_TAG = "◇ ";
    private static long debugCheckedAt;
    private static boolean debugOn;
    private static String lastDumped = "";

    /**
     * While the Debug setting is on, writes the lines of the last hovered item
     * (text, font and the component JSON) to tooltip-debug.txt, to see how a tooltip is built.
     */
    private static void debugDump(net.minecraft.world.item.ItemStack stack, List<Component> lines) {
        try {
            var dir = Dirs.config();
            long now = System.currentTimeMillis();
            if (now - debugCheckedAt > 5000) {
                debugCheckedAt = now;
                debugOn = Settings.on("debug");
            }
            if (!debugOn) {
                return;
            }
            String name = stack.getHoverName().getString();
            if (name.equals(lastDumped)) {
                return;
            }
            lastDumped = name;
            StringBuilder sb = new StringBuilder("item: " + name + "\n");
            var ops = Minecraft.getInstance().level == null ? null
                    : Minecraft.getInstance().level.registryAccess().createSerializationContext(
                            com.mojang.serialization.JsonOps.INSTANCE);
            for (int i = 0; i < lines.size(); i++) {
                Component c = lines.get(i);
                sb.append(i).append(" | ").append(c.getString().replace("\n", "\\n")).append('\n');
                if (ops != null) {
                    var json = net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(ops, c);
                    json.result().ifPresent(j -> sb.append("    ").append(j.toString(), 0,
                            Math.min(400, j.toString().length())).append('\n'));
                }
            }
            java.nio.file.Files.writeString(dir.resolve("tooltip-debug.txt"), sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // debugging only
        }
    }

    /**
     * Trade Market price right under the item name, and for a box "(15 EB)" after each item in
     * the possibilities list Wynntils draws. Returns whether it changed anything.
     */
    private static boolean withPrices(net.minecraft.world.item.ItemStack stack, List<Component> out) {
        if (stack.isEmpty() || out.isEmpty()) {
            return false;
        }
        for (Component c : out) {
            if (c.getString().contains(PRICE_TAG)) {
                return false;                       // done already
            }
        }
        var parsed = WynntilsItems.view(stack);
        if (parsed.isEmpty()) {
            return false;
        }
        if (parsed.get().isGear()) {
            ItemView g = parsed.get();
            if (!Settings.on("tooltipPrice")) {
                return false;
            }
            String p = Prices.text(g.data().name(), g.unidentified());
            if (p == null) {
                return false;
            }
            p = p.contains(" (") ? p.substring(0, p.indexOf(" (")) : p;   // just the price, short
            // Line 0 is a hidden copy of the name; the visible header ("Dusk Painter [57.9%]",
            // with the emblem) is the next line holding it. The price goes at its end.
            int header = headerLine(out, g.data().name());
            Component price = Component.literal("  " + PRICE_TAG + p).withStyle(net.minecraft.ChatFormatting.GOLD);
            if (header >= 0) {
                out.set(header, out.get(header).copy().append(price));
            } else {
                out.add(Math.min(1, out.size()), price);
            }
            return true;
        }
        if (!Settings.on("boxPrices")
                || !parsed.get().isBox()) {
            return false;
        }
        boolean changed = false;
        var possible = WynntilsItems.possibleGears(parsed.get().box());
        Prices.request(possible.stream().map(ItemData::name).toList());
        // One summary line as well: names can be translated, so matching lines may fail
        List<String> worth = new ArrayList<>();
        possible.stream()
                .map(i -> new Object[] {i.name(), Prices.of(i.name()) == null ? null : Prices.of(i.name()).typical()})
                .filter(o -> o[1] != null)
                .sorted((x, y) -> Double.compare((Double) y[1], (Double) x[1]))
                .limit(3)
                .forEach(o -> worth.add(o[0] + " ~" + Prices.fmt((Double) o[1])));
        String marketHead = PRICE_TAG + L.t("wynnlabs.tooltip.box_market", "").strip();
        // each name in the list gets its price right after it, before the comma of the next one:
        // "Coral Charm (12 EB), Crash (18 EB)"
        java.util.Map<String, String> priceOf = new java.util.LinkedHashMap<>();
        for (var info : possible) {
            String p = Prices.text(info.name(), true);
            if (p != null) {
                String shortP = p.contains(" (") ? p.substring(0, p.indexOf(" (")) : p;
                priceOf.put(info.name(), " (" + shortP.replace("~", "") + ")");
            }
        }
        int priced = 0;
        for (int i = 0; i < out.size() && !priceOf.isEmpty(); i++) {
            String text = out.get(i).getString();
            boolean has = false;
            boolean done = false;
            for (var pe : priceOf.entrySet()) {
                if (text.contains(pe.getKey())) {
                    has = true;
                    done |= text.contains(pe.getKey() + pe.getValue());
                }
            }
            if (!has) {
                continue;
            }
            priced++;
            if (done) {
                continue;                           // priced on an earlier pass
            }
            out.set(i, withPricesAfterNames(out.get(i), priceOf));
            changed = true;
        }
        // one summary line only when the list's lines could not be matched (a translated name):
        // otherwise the prices are on the lines already, and the tooltip stays shorter
        if (priced == 0 && !worth.isEmpty() && out.stream().noneMatch(c -> c.getString().startsWith(marketHead))) {
            out.add(Component.literal(PRICE_TAG + L.t("wynnlabs.tooltip.box_market", String.join(", ", worth)))
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
            changed = true;
        }
        return changed;
    }

    /**
     * The line again, its styles kept, with a gold "(price)" right after every name it shows.
     * Names are matched longest first, so "Charm of the Vampire" is not taken for "Charm".
     */
    private static Component withPricesAfterNames(Component line, java.util.Map<String, String> priceOf) {
        List<String> names = new ArrayList<>(priceOf.keySet());
        names.sort((x, y) -> y.length() - x.length());
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        line.visit((style, text) -> {
            int at = 0;
            while (at < text.length()) {
                int best = -1;
                String hit = null;
                for (String n : names) {
                    int k = text.indexOf(n, at);
                    if (k >= 0 && (best < 0 || k < best || k == best && n.length() > hit.length())) {
                        best = k;
                        hit = n;
                    }
                }
                if (hit == null) {
                    out.append(Component.literal(text.substring(at)).withStyle(style));
                    break;
                }
                int end = best + hit.length();
                out.append(Component.literal(text.substring(at, end)).withStyle(style));
                out.append(Component.literal(priceOf.get(hit)).withStyle(net.minecraft.ChatFormatting.GOLD));
                at = end;
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return out;
    }

    /** The visible header: the last of the first four lines that shows the name, or -1. */
    private static int headerLine(List<Component> lines, String name) {
        int found = -1;
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            if (ItemJson.clean(lines.get(i).getString()).contains(name)) {
                found = i;
            }
        }
        return found;
    }

    /** The index just under the first line that shows this name; 1 if none does. */
    private static int afterName(List<Component> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            if (!name.isEmpty() && ItemJson.clean(lines.get(i).getString()).contains(name)) {
                return i + 1;
            }
        }
        return Math.min(1, lines.size());
    }
}
