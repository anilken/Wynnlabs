package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers what the Character Info menu showed last time it was open.
 *
 * <p>Wynntils only re-reads assigned skill points on its own schedule, so after the player
 * spends points they stay at the old value. While a menu with skill point items is open this
 * reads the numbers straight from those items. Only looks at the open menu; never clicks.
 */
final class MenuWatcher {

    static final Map<SkillKind, Integer> ASSIGNED = new EnumMap<>(SkillKind.class);
    /** Advice keys of the "Upgrade your … skill" buttons, as the menu really names them. */
    static final Map<SkillKind, String> BUTTON_KEYS = new EnumMap<>(SkillKind.class);
    /** Points not spent yet: the number on the Skill Crystal. -1 until seen. */
    static volatile int unspent = -1;
    /** Every item of that menu as "name xcount", to make sense of the other icons. */
    static volatile List<String> lastMenuItems = List.of();
    static volatile long lastSeen;

    private static int tick;

    /**
     * Profession levels as Character Info's profession item lists them ("Lv. 42 Woodworking"),
     * by Wynntils' upper-case name. Wynntils reads them only through its own menu query on
     * joining, which can be off or fail; then its levels stay 0.
     */
    private static final Map<String, Integer> PROFESSIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean professionsLoaded;
    private static final java.util.regex.Pattern PROFESSION_LINE =
            java.util.regex.Pattern.compile("Lv\\.\\s*(\\d+)\\s+([A-Za-z]+)");

    /** A profession's level from the menu (now or a saved read for this character), or 0. */
    static int profession(String upper) {
        if (!professionsLoaded) {
            professionsLoaded = true;
            try {
                java.nio.file.Path f = Profile.dir().resolve("professions.json");
                Saver.settle(f);
                if (java.nio.file.Files.exists(f)) {
                    String text = java.nio.file.Files.readString(f);
                    Saver.known(f, text);
                    var o = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                    for (var e : o.entrySet()) {
                        PROFESSIONS.putIfAbsent(e.getKey(), e.getValue().getAsInt());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return PROFESSIONS.getOrDefault(upper, 0);
    }

    /** Reads the profession lines of the menu's items; true when it found any. */
    private static boolean readProfessions(AbstractContainerScreen<?> screen, Minecraft mc) {
        Map<String, Integer> found = new java.util.HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory() || slot.getItem().isEmpty()) {
                continue;
            }
            var lore = slot.getItem().get(net.minecraft.core.component.DataComponents.LORE);
            if (lore == null) {
                continue;
            }
            for (var line : lore.lines()) {
                var m = PROFESSION_LINE.matcher(line.getString());
                if (m.find()) {
                    String p = m.group(2).toUpperCase(java.util.Locale.ROOT);
                    if (Professions.ALL.contains(p)) {
                        found.put(p, Integer.parseInt(m.group(1)));
                    }
                }
            }
        }
        if (found.size() < 4) {
            return false;                       // not the profession item: some other lore
        }
        professionsLoaded = true;
        PROFESSIONS.putAll(found);
        var o = new com.google.gson.JsonObject();
        new java.util.TreeMap<>(PROFESSIONS).forEach(o::addProperty);
        String text = o.toString();
        Saver.mark(Profile.dir().resolve("professions.json"), () -> text);
        return true;
    }

    /** Forget what the menus showed: another character is playing now. */
    static void reset() {
        PROFESSIONS.clear();
        professionsLoaded = false;
        synchronized (ASSIGNED) {
            ASSIGNED.clear();
            BUTTON_KEYS.clear();
        }
        unspent = -1;
        ANNOUNCED.clear();
        announcedFor = null;
    }

    /**
     * True once this character's assigned points come from Character Info (now or a saved read)
     * or Wynntils has read them; until then skill advice is approximate.
     */
    static boolean skillsKnown() {
        try {
            java.nio.file.Path f = Profile.dir().resolve("skills.json");
            if (Saver.pending(f) || java.nio.file.Files.exists(f)) {
                return true;
            }
            int sum = 0;
            for (SkillKind k : SkillKind.values()) {
                sum += Wyn.assignedPoints(k);
            }
            return sum > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Assigned points of a skill: the menu's number, else Wynntils'. */
    static int assigned(SkillKind s) {
        synchronized (ASSIGNED) {
            if (ASSIGNED.isEmpty()) {
                // not seen in the menu since the game started: the points last read for this
                // character (skills.json), else what Wynntils knows (0 until its own read)
                try {
                    java.nio.file.Path f = Profile.dir().resolve("skills.json");
                    Saver.settle(f);
                    if (java.nio.file.Files.exists(f)) {
                        String text = java.nio.file.Files.readString(f);
                        Saver.known(f, text);           // the same points are not written back
                        var o = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                        for (SkillKind k : SkillKind.values()) {
                            if (o.has(k.name())) {
                                ASSIGNED.put(k, o.get(k.name()).getAsInt());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                if (ASSIGNED.isEmpty()) {
                    for (SkillKind k : SkillKind.values()) {
                        ASSIGNED.put(k, Wyn.assignedPoints(k));
                    }
                }
            }
            return ASSIGNED.getOrDefault(s, Wyn.assignedPoints(s));
        }
    }

    private MenuWatcher() {}

    /** Upgrades already announced in chat, by item key; seeded quietly on join. */
    private static final java.util.Set<String> ANNOUNCED = new java.util.HashSet<>();
    private static Object announcedFor;

    /**
     * Says so in your own chat (client side only, nothing is sent) when an item in your
     * inventory turns out to be an upgrade: picked up, dropped or bought.
     */
    private static void announce(Minecraft mc) {
        if (mc.player == null || !Settings.on("chatUpgrade") || !Rules.adviceHere()) {
            return;
        }
        boolean quiet = announcedFor != mc.player;     // new login or character: just remember
        if (quiet) {
            ANNOUNCED.clear();
            announcedFor = mc.player;
        }
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            Advice.Entry e = AutoAdvice.cached(s);
            if (e == null || !(e.verdict() == Verdict.WEAR || e.verdict() == Verdict.SPEND)) {
                continue;
            }
            String key = ItemJson.keyOf(s);
            if (ANNOUNCED.add(key) && !quiet) {
                var msg = L.c("wynnlabs.menu.assistant")
                        .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)
                        .append(net.minecraft.network.chat.Component.literal(
                                ItemJson.clean(s.getHoverName().getString()) + " → " + e.verdict().label())
                                .withStyle(net.minecraft.ChatFormatting.GREEN))
                        .append(net.minecraft.network.chat.Component.literal(
                                " " + (e.note() == null ? "" : e.note()))
                                .withStyle(net.minecraft.ChatFormatting.GRAY));
                mc.player.displayClientMessage(msg, false);
            }
        }
    }

    static void onTick(Minecraft mc) {
        if (++tick % 40 == 0) {
            try {
                announce(mc);
            } catch (Throwable ignored) {
                // a chat note must never break the game
            }
        }
        if (tick % 10 != 0 || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        try {
            TreeConnectors.capture(screen);     // the ability tree's pieces, when its menu is open
        } catch (Throwable ignored) {
            // reading a menu must never break the game
        }
        try {
            Map<SkillKind, Integer> found = new EnumMap<>(SkillKind.class);
            Map<SkillKind, String> keys = new EnumMap<>(SkillKind.class);
            int crystal = -1;
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container == mc.player.getInventory()) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                Optional<ItemView> item = WynntilsItems.view(stack);
                if (item.isPresent() && item.get().skillPoint() != null) {
                    ItemView.SkillPoint sp = item.get().skillPoint();
                    found.put(sp.skill(), sp.assigned());
                    keys.put(sp.skill(), ItemJson.keyOf(stack));
                } else if (item.isPresent() && item.get().crystalCount() != null) {
                    crystal = item.get().crystalCount();
                }
            }
            if (found.size() == SkillKind.values().length) {
                synchronized (ASSIGNED) {
                    ASSIGNED.clear();
                    ASSIGNED.putAll(found);
                    BUTTON_KEYS.clear();
                    BUTTON_KEYS.putAll(keys);
                }
                // remembered per character, so the next game start knows them before the menu opens
                // (in the background, and only when the points changed: the menu is read every half second)
                var o = new com.google.gson.JsonObject();
                for (var e : found.entrySet()) {
                    o.addProperty(e.getKey().name(), e.getValue());
                }
                String text = o.toString();
                Saver.mark(Profile.dir().resolve("skills.json"), () -> text);
                unspent = Math.max(crystal, 0);
                // every item of Character Info, only now that it is that menu (not in every menu)
                List<String> items = new ArrayList<>();
                for (Slot slot : screen.getMenu().slots) {
                    ItemStack stack = slot.getItem();
                    if (slot.container != mc.player.getInventory() && !stack.isEmpty()) {
                        items.add(slot.index + ": " + ItemJson.clean(stack.getHoverName().getString())
                                + " x" + stack.getCount());
                    }
                }
                lastMenuItems = List.copyOf(items);
                lastSeen = System.currentTimeMillis();
                readProfessions(screen, mc);
            }
        } catch (Throwable ignored) {
            // reading a menu must never break the game
        }
    }
}
