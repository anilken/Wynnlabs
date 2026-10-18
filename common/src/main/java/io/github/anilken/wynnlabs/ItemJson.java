package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.StatRange;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** Turns an ItemStack into JSON, using the mod's reading of it ({@link ItemView}) when there is one. */
final class ItemJson {

    private ItemJson() {}

    /** GET /inventory: the 36 bag slots, empty ones left out. */
    static JsonObject inventory() {
        JsonObject o = new JsonObject();
        GameState state = GameState.current();
        if (!state.inGame()) {
            o.addProperty("inGame", false);
            return o;
        }
        List<ItemStack> inv = state.inventory();
        JsonArray slots = new JsonArray();
        for (int i = 0; i < 36 && i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (!stack.isEmpty()) {
                slots.add(of(stack, i < 9 ? "hotbar_" + i : "inventory_" + i));
            }
        }
        o.add("items", slots);
        o.addProperty("note", "Empty slots are omitted. Slots 9-12 hold Wynncraft accessories.");
        return o;
    }

    /** GET /equipped: armour, accessories and the weapon in hand. */
    static JsonObject equipped() {
        JsonObject o = new JsonObject();
        GameState state = GameState.current();
        if (!state.inGame()) {
            o.addProperty("inGame", false);
            return o;
        }
        JsonArray armour = new JsonArray();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            armour.add(of(state.armour(slot), slot.getName()));
        }
        o.add("armour", armour);

        JsonArray accessories = new JsonArray();
        try {
            List<ItemStack> acc = state.accessories();
            for (int i = 0; i < acc.size(); i++) {
                accessories.add(of(acc.get(i), "accessory_" + i));
            }
        } catch (Throwable t) {
            o.addProperty("accessoriesError", t.toString());
        }
        o.add("accessories", accessories);

        o.add("weaponInHand", of(state.mainHand(), "hotbar_" + state.selectedSlot()));
        return o;
    }

    static JsonObject of(ItemStack stack, String slot) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        if (stack == null || stack.isEmpty()) {
            o.addProperty("empty", true);
            return o;
        }
        o.addProperty("name", clean(stack.getHoverName().getString()));
        o.addProperty("count", stack.getCount());
        o.addProperty("key", keyOf(stack));
        try {
            Prices.Price price = GameState.current().price(clean(stack.getHoverName().getString()));
            if (price != null && price.typical() != null) {
                o.addProperty("marketPrice", Math.round(price.typical()));
                o.addProperty("marketPriceText", Prices.fmt(price.typical()));
            }
        } catch (Throwable ignored) {
            // no price
        }
        try {
            Advice.Entry rule = AutoAdvice.cached(stack);
            if (rule != null) {
                JsonObject r = new JsonObject();
                r.addProperty("verdict", rule.verdict().name());
                r.addProperty("note", rule.note());
                o.add("ruleAdvice", r);
            }
        } catch (Throwable ignored) {
            // no rule verdict
        }

        Optional<ItemView> parsed;
        try {
            parsed = GameState.current().view(stack);
        } catch (Throwable t) {
            parsed = Optional.empty();
        }
        if (parsed.isEmpty()) {
            o.addProperty("kind", "unknown");
            return o;
        }
        ItemView item = parsed.get();
        try {
            if (item.isGear()) {
                gear(o, item);
            } else if (item.isBox()) {
                gearBox(o, item.box());
            } else if (item.kind() == ItemView.Kind.INGREDIENT) {
                o.addProperty("kind", "ingredient");
                o.addProperty("name", item.name());
                o.addProperty("tier", item.qualityTier());
                o.addProperty("level", item.level());
                JsonArray profs = new JsonArray();
                item.professions().forEach(profs::add);
                o.add("professions", profs);
            } else if (item.kind() == ItemView.Kind.MATERIAL) {
                o.addProperty("kind", "material");
                o.addProperty("name", item.name());
                o.addProperty("tier", item.qualityTier());
                o.addProperty("level", item.level());
                JsonArray profs = new JsonArray();
                item.professions().forEach(profs::add);
                o.add("professions", profs);
            } else if (item.kind() == ItemView.Kind.POTION) {
                ItemView.Potion potion = item.potion();
                o.addProperty("kind", "potion");
                o.addProperty("potionType", potion.type());
                o.addProperty("level", potion.level());
                o.addProperty("usesLeft", potion.uses().current());
                o.addProperty("usesMax", potion.uses().max());
            } else {
                // Everything else Wynntils knows (emeralds, scrolls, keys, ...): its type name is enough
                o.addProperty("kind", item.type()
                        .replace("Item", "").toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Throwable t) {
            o.addProperty("parseError", t.getClass().getSimpleName());
        }
        return o;
    }

    private static void gear(JsonObject o, ItemView gear) {
        ItemData info = gear.data();
        o.addProperty("kind", "gear");
        o.addProperty("name", info.name());
        o.addProperty("type", info.slot().name());
        o.addProperty("tier", info.tier().name());
        o.addProperty("level", info.level());
        o.addProperty("class", info.requirements().klass().map(Klass::name).orElse(null));
        o.addProperty("unidentified", gear.unidentified());
        o.addProperty("powderSlots", info.powderSlots());

        JsonObject req = new JsonObject();
        req.addProperty("level", info.requirements().level());
        info.requirements().klass().ifPresent(c -> req.addProperty("class", c.name()));
        info.requirements().quest().ifPresent(q -> req.addProperty("quest", q));
        JsonObject skills = new JsonObject();
        info.requirements().skills().forEach(p -> skills.addProperty(p.skill().name(), p.points()));
        if (!skills.isEmpty()) {
            req.add("skills", skills);
        }
        o.add("requirements", req);

        o.addProperty("identifyCost", info.tier().identificationCost(info.level()));
        o.add("baseStats", baseStats(info));

        if (gear.unidentified()) {
            o.add("possibleStats", possibleStats(info));
            return;
        }
        ItemView.Rolled gi = gear.rolled();
        if (gi.hasOverall()) {
            o.addProperty("overallPercent", Math.round(gi.overallPercent() * 10) / 10.0);
        }
        o.addProperty("rerolls", gi.rerolls());
        JsonArray powders = new JsonArray();
        for (String p : gi.powders()) {
            powders.add(p);
        }
        o.add("powders", powders);
        o.addProperty("meetsRequirements", gi.marks().all());

        JsonArray ids = new JsonArray();
        for (Roll v : gi.rolls()) {
            JsonObject s = new JsonObject();
            s.addProperty("stat", v.name());
            s.addProperty("key", v.key());
            s.addProperty("value", v.value());
            s.addProperty("unit", v.unit());
            StatRange possible = v.range();
            if (possible != null && !possible.fixed()) {
                s.addProperty("min", possible.min());
                s.addProperty("max", possible.max());
            }
            ids.add(s);
        }
        o.add("identifications", ids);
    }

    /**
     * "Unidentified Relik" style boxes: type, tier and level range only. Wynntils knows which
     * gear can come out of them (the "Possibilities" list in the tooltip).
     */
    private static void gearBox(JsonObject o, ItemView.Box box) {
        o.addProperty("kind", "gearbox");
        o.addProperty("type", box.slot().name());
        o.addProperty("tier", box.tier().name());
        JsonObject range = new JsonObject();
        range.addProperty("min", box.low());
        range.addProperty("max", box.high());
        o.add("levelRange", range);
        JsonArray possible = new JsonArray();
        try {
            for (ItemData info : GameState.current().possibleGears(box)) {
                possible.add(summary(info));
            }
        } catch (Throwable t) {
            o.addProperty("possibilitiesError", t.getClass().getSimpleName());
        }
        o.add("possibilities", possible);
    }

    /** Everything known about a gear piece before it is identified. */
    private static JsonObject summary(ItemData info) {
        JsonObject g = new JsonObject();
        g.addProperty("name", info.name());
        g.addProperty("type", info.slot().name());
        g.addProperty("tier", info.tier().name());
        g.addProperty("level", info.requirements().level());
        info.requirements().klass().ifPresent(c -> g.addProperty("class", c.name()));
        JsonObject skills = new JsonObject();
        info.requirements().skills().forEach(p -> skills.addProperty(p.skill().name(), p.points()));
        if (!skills.isEmpty()) {
            g.add("skillRequirements", skills);
        }
        info.requirements().quest().ifPresent(q -> g.addProperty("questRequirement", q));
        g.addProperty("identifyCost", info.tier().identificationCost(info.requirements().level()));
        g.addProperty("powderSlots", info.powderSlots());
        g.add("baseStats", baseStats(info));
        g.add("possibleStats", possibleStats(info));
        return g;
    }

    private static JsonObject baseStats(ItemData info) {
        JsonObject b = new JsonObject();
        try {
            var fixed = info.base();
            if (fixed.health() != 0) {
                b.addProperty("health", fixed.health());
            }
            if (fixed.averageDps() != 0) {
                b.addProperty("averageDps", fixed.averageDps());
            }
            fixed.attackSpeed().ifPresent(a -> b.addProperty("attackSpeed", a));
            fixed.majorId().ifPresent(m -> b.addProperty("majorId", m));
            JsonObject dmg = new JsonObject();
            fixed.damages().forEach(p -> dmg.addProperty(p.type(), p.low() + "-" + p.high()));
            if (!dmg.isEmpty()) {
                b.add("damage", dmg);
            }
            JsonObject def = new JsonObject();
            fixed.defences().forEach(p -> def.addProperty(p.element(), p.value()));
            if (!def.isEmpty()) {
                b.add("defence", def);
            }
        } catch (Throwable t) {
            b.addProperty("error", t.getClass().getSimpleName());
        }
        return b;
    }

    private static JsonArray possibleStats(ItemData info) {
        JsonArray out = new JsonArray();
        for (StatRange v : info.stats()) {
            JsonObject s = new JsonObject();
            s.addProperty("stat", v.name());
            s.addProperty("key", v.key());
            s.addProperty("min", v.min());
            s.addProperty("max", v.max());
            s.addProperty("unit", v.unit());
            out.add(s);
        }
        return out;
    }

    /**
     * A stable key for advice: same key = same advice in the tooltip.
     * Gear boxes are keyed by type, tier and level range, so a Unique and a Legendary
     * "Unidentified Relik" stay apart; everything else by its parsed or cleaned name.
     */
    static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Optional<ItemView> parsed = GameState.current().view(stack);
            if (parsed.isPresent()) {
                return keyOf(parsed.get(), clean(stack.getHoverName().getString()));
            }
        } catch (Throwable ignored) {
            // fall back to the name
        }
        return "name:" + clean(stack.getHoverName().getString());
    }

    /** {@link #keyOf(ItemStack)} from the mod's reading of an item and its cleaned name. */
    static String keyOf(ItemView item, String name) {
        try {
            if (item.isBox()) {
                ItemView.Box box = item.box();
                return "gearbox:" + box.slot().name() + ":" + box.tier().name()
                        + ":" + box.low() + "-" + box.high();
            }
            if (item.isGear()) {
                // Two copies of the same gear can roll differently (one at 50 %, one
                // "Defective" at 0 %), so the rolled values are part of the key.
                if (item.unidentified()) {
                    return "gear:" + item.data().name() + ":unidentified";
                }
                StringBuilder k = new StringBuilder("gear:").append(item.data().name());
                for (Roll v : item.rolled().rolls()) {
                    k.append(':').append(v.key()).append('=').append(v.value());
                }
                return k.toString();
            }
            if (item.kind() == ItemView.Kind.INGREDIENT) {
                return "ingredient:" + item.name();
            }
            if (item.kind() == ItemView.Kind.MATERIAL) {
                return "material:" + item.name() + ":" + item.qualityTier();
            }
            if (item.kind() == ItemView.Kind.ABILITY_NODE) {
                return "ability:" + abilityName(item.abilityName());
            }
        } catch (Throwable ignored) {
            // fall back to the name
        }
        return "name:" + name;
    }

    /** Node names in the tree menu can carry an "Unlock" prefix; the key is the bare name. */
    static String abilityName(String s) {
        String n = clean(s);
        if (n.startsWith("Unlock ")) {
            n = n.substring("Unlock ".length()).strip();
        }
        return n;
    }

    /** Hover names carry § colour codes and Wynncraft's private-use glyphs. */
    static String clean(String s) {
        String plain = ChatFormatting.stripFormatting(s);
        if (plain == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(plain.length());
        plain.codePoints()
                .filter(cp -> {
                    int type = Character.getType(cp);
                    return type != Character.PRIVATE_USE && type != Character.UNASSIGNED;
                })
                .forEach(out::appendCodePoint);
        // Wynncraft pads names with runs of "À" ("DecrepitÀÀÀSewers Key"); a stripped glyph
        // between two words leaves a double space ("Upgrade your  Strength")
        return SPACES.matcher(PADDING.matcher(out.toString()).replaceAll(" ")).replaceAll(" ").strip();
    }

    // compiled once: names are cleaned for every tooltip, every frame
    private static final java.util.regex.Pattern PADDING = java.util.regex.Pattern.compile("À{2,}");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s{2,}");
}
