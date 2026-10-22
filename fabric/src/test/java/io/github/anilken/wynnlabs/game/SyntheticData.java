package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.BaseStats;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.StatRange;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestBook;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small made-up fixture in code, for the format and sanitiser tests. {@code activeId} and
 * {@code otherId} are the two characters; {@code player} and {@code pet} are names that must not
 * survive the sanitiser.
 */
public final class SyntheticData {

    private SyntheticData() {}

    static ItemData relik(String name, int level, Optional<SetData> set) {
        return new ItemData(name, Slot.RELIK, Tier.UNIQUE, 2,
                new Requirement(level, Optional.of(Klass.SHAMAN),
                        List.of(new Requirement.SkillReq(SkillKind.INTELLIGENCE, 5)), Optional.empty()),
                new BaseStats(59, 0, Optional.of("NORMAL"), Optional.empty(),
                        List.of(new BaseStats.Damage("NEUTRAL", 20, 30), new BaseStats.Damage("WATER", 10, 14)), List.of()),
                List.of(new StatRange("manaRegen", "Mana Regen", "PER_5_S", false, 1, 4),
                        new StatRange("raw2ndSpellCost", "2nd Spell Cost", "RAW", true, -4, -1)),
                set, "NONE");
    }

    static ItemView gear(ItemData d, int mana) {
        Map<SkillKind, Boolean> marks = new EnumMap<>(SkillKind.class);
        marks.put(SkillKind.INTELLIGENCE, true);
        return new ItemView(ItemView.Kind.GEAR, "GearItem", false, null, null, null, d,
                new ItemView.Rolled(List.of(new Roll("manaRegen", "Mana Regen", "PER_5_S", mana, d.stats().get(0)),
                        new Roll("raw2ndSpellCost", "2nd Spell Cost", "RAW", -3, d.stats().get(1))),
                        List.of("WATER"), 1, 71.5f, false, new ItemView.Marks(true, true, marks, false)),
                null, null, null, null, null, null, null, null, null);
    }

    public static Fixture.Data data(String activeId, String otherId, String player, String pet) {
        SetData set = new SetData("Leaf", "Leaf", 3, Map.of(2, new SetData.Bonus(List.of(),
                List.of(new SetData.StatValue("rawHealth", 10)))));
        ItemData keen = relik("Keen Measure", 17, Optional.empty());
        ItemData leafy = relik("Leafy Relik", 18, Optional.of(set));
        ItemView box = new ItemView(ItemView.Kind.BOX, "GearBoxItem", false, null, null, null, null, null, null,
                new ItemView.Box(Slot.RELIK, Tier.UNIQUE, 16, 20), null, null, null, null, null, null, null);
        ItemView ingredient = new ItemView(ItemView.Kind.INGREDIENT, "IngredientItem", false, pet + " Fur", 12,
                List.of("TAILORING"), null, null, null, null, 1, null, null, null, null, null, null);

        List<Fixture.Held> stacks = new ArrayList<>(List.of(new Fixture.Held("Keen Measure", 1, gear(keen, 3)),
                new Fixture.Held("Unidentified Relik", 1, box), new Fixture.Held(pet + " Fur", 12, ingredient),
                new Fixture.Held("Emerald Pouch", 1, null)));
        List<Integer> inventory = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            inventory.add(i < stacks.size() ? i : null);
        }
        Map<String, Integer> armour = new LinkedHashMap<>();
        for (var s : Fixture.ARMOUR) {
            armour.put(s.name(), null);
        }
        List<Fixture.BankSlot> bank = List.of(
                new Fixture.BankSlot("acct:Account Bank p. 1", 0, "Leafy Relik", 1, gear(leafy, 2)),
                new Fixture.BankSlot("char:" + otherId + ":Character Bank p. 2", 6, "Unidentified Relik", 1, box));
        Fixture.Items items = new Fixture.Items(stacks, inventory, armour, List.of(), 0, bank);

        Map<String, List<Obtain>> obtain = new LinkedHashMap<>();
        obtain.put("Keen Measure", List.of(new Obtain("MERCHANT", Optional.of("Ragni")), new Obtain("LOOT_CHEST", Optional.empty())));
        obtain.put("Leafy Relik", List.of());
        Fixture.GearDb db = new Fixture.GearDb(Fixture.FORMAT, List.of(keen, leafy), obtain,
                Map.of(Fixture.boxKey(box.box()), List.of("Keen Measure", "Leafy Relik")));

        JsonObject characters = new JsonObject();
        JsonArray list = new JsonArray();
        for (String id : List.of(activeId, otherId)) {
            JsonObject m = new JsonObject();
            m.addProperty("id", id);
            m.addProperty("class", id.equals(activeId) ? "SHAMAN" : "MAGE");
            m.addProperty("level", id.equals(activeId) ? 20 : 31);
            m.addProperty("goal", id.equals(activeId) ? "damage, " + player + "'s main" : "");
            m.addProperty("active", id.equals(activeId));
            list.add(m);
        }
        characters.addProperty("active", activeId);
        characters.add("characters", list);
        JsonObject otherDay = new JsonObject();
        otherDay.addProperty("class", "MAGE");
        otherDay.addProperty("level", 31);
        Map<SkillKind, Fixture.Skill> skills = new EnumMap<>(SkillKind.class);
        for (SkillKind s : SkillKind.values()) {
            skills.put(s, new Fixture.Skill(s == SkillKind.INTELLIGENCE ? 10 : 0, 0, 0, 0, 0));
        }
        Fixture.State state = new Fixture.State(Fixture.FORMAT, 1_760_000_000_000L, true, Klass.SHAMAN, 20, activeId,
                characters, List.of(otherId, activeId), Map.of(otherId, otherDay), skills, true, 4,
                Map.of("TAILORING", 14),
                List.of(new QuestBook.Entry("King's Recruit", "COMPLETED", 1, "SHORT", 500, true, null, null, null, null,
                        null, 1_759_000_000_000L, "QUEST", null)),
                Map.of("Totem", 1), new double[] {-875.5, -1520.25},
                List.of(new Grind.Spot(Grind.CAVE, "Nivla Woods Cave", 7, false, -200, 70, -1500, "short, easy, emeralds")),
                Map.of("Nivla Woods Cave", 12000.0), true, 0);

        Map<String, Prices.Price> prices = new LinkedHashMap<>();
        prices.put("Keen Measure", new Prices.Price(320, 400.0, null, 128, null, 1_759_990_000_000L, null, null));
        prices.put("Leafy Relik", null);
        Map<String, JsonObject> rules = new LinkedHashMap<>();
        JsonObject active = new JsonObject();
        active.addProperty("goal", "fast levelling for " + player);
        rules.put(activeId, active);
        rules.put(otherId, new JsonObject());
        JsonObject settings = new JsonObject();
        settings.addProperty("debug", true);
        settings.addProperty("langOverride", 0);
        return new Fixture.Data(state, items, db, prices, rules, settings);
    }
}
