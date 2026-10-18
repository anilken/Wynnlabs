package io.github.anilken.wynnlabs;

import com.wynntils.core.text.StyledText;
import com.wynntils.models.activities.type.ActivityInfo;
import com.wynntils.models.activities.type.ActivityRewardType;
import com.wynntils.models.elements.type.Element;
import com.wynntils.models.elements.type.Powder;
import com.wynntils.models.elements.type.Skill;
import com.wynntils.models.gear.type.GearAttackSpeed;
import com.wynntils.models.gear.type.GearInfo;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.gear.type.GearRequirements;
import com.wynntils.models.gear.type.GearTier;
import com.wynntils.models.gear.type.GearType;
import com.wynntils.models.gear.type.SetInfo;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.CraftedGearItem;
import com.wynntils.models.items.items.game.DungeonKeyItem;
import com.wynntils.models.items.items.game.GearBoxItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.items.items.game.IngredientItem;
import com.wynntils.models.items.items.game.MaterialItem;
import com.wynntils.models.items.items.game.PotionItem;
import com.wynntils.models.items.items.game.PowderItem;
import com.wynntils.models.items.items.gui.ActivityItem;
import com.wynntils.models.items.items.gui.SkillCrystalItem;
import com.wynntils.models.items.items.gui.SkillPointItem;
import com.wynntils.models.items.properties.LeveledItemProperty;
import com.wynntils.models.items.properties.NamedItemProperty;
import com.wynntils.models.items.properties.ProfessionItemProperty;
import com.wynntils.models.stats.type.DamageType;
import com.wynntils.models.stats.type.FixedStats;
import com.wynntils.models.stats.type.StatActualValue;
import com.wynntils.models.stats.type.StatPossibleValues;
import com.wynntils.models.stats.type.StatType;
import com.wynntils.screens.guides.gear.GuideGearItemStack;
import com.wynntils.utils.type.CappedValue;
import com.wynntils.utils.type.Pair;
import com.wynntils.utils.type.RangedValue;
import io.github.anilken.wynnlabs.model.Activity;
import io.github.anilken.wynnlabs.model.BaseStats;
import io.github.anilken.wynnlabs.model.Capped;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Turns Wynntils' item objects into the mod's own ({@link ItemData}, {@link ItemView}). The
 * rest of the mod reads only those, so a second source of item data (the Wynncraft API) can
 * fill the same types later. Every call into Wynntils still goes through {@link Wyn}'s guard.
 *
 * <p>Gear data is kept per name, stack views per stack: each is checked against the Wynntils
 * object it came from, so a reloaded database or a re-read stack is converted again.
 */
final class WynntilsItems {

    private WynntilsItems() {}

    private record Known(GearInfo info, ItemData data) {}

    private static final Map<String, Known> BY_NAME = new ConcurrentHashMap<>();
    /** Back to Wynntils' object, for what only Wynntils can do with it (icons, obtain sources). */
    private static final Map<ItemData, GearInfo> ORIGIN = Collections.synchronizedMap(new IdentityHashMap<>());

    /** The Wynntils item is held weakly: it keeps a reference to its stack, the map's key. */
    private record Seen(WeakReference<WynnItem> item, ItemView view) {}

    private static final Map<ItemStack, Seen> BY_STACK = Collections.synchronizedMap(new WeakHashMap<>());

    // ---- gear data ----

    static ItemData data(GearInfo info) {
        if (info == null) {
            return null;
        }
        Known k = BY_NAME.get(info.name());
        if (k != null && k.info() == info) {
            return k.data();
        }
        ItemData d = new ItemData(info.name(), Slot.of(info.type().name()), Tier.of(info.tier().name()),
                info.powderSlots(), requirement(info.requirements()), base(info), ranges(info),
                info.setInfo().map(WynntilsItems::set), info.metaInfo().restrictions().name());
        BY_NAME.put(info.name(), new Known(info, d));
        ORIGIN.put(d, info);
        return d;
    }

    static Stream<ItemData> allGear() {
        return Wyn.allGear().map(WynntilsItems::data);
    }

    static ItemData gearByName(String name) {
        return data(Wyn.gearByName(name));
    }

    /** What can come out of a gear box; empty when unknown. */
    static List<ItemData> possibleGears(ItemView.Box box) {
        GearBoxItem b;
        try {
            b = new GearBoxItem(GearType.valueOf(box.slot().name()), GearTier.valueOf(box.tier().name()),
                    RangedValue.of(box.low(), box.high()));
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
        return Wyn.possibleGears(b).stream().map(WynntilsItems::data).toList();
    }

    /** Where a piece comes from: the database's sources plus what players reported. */
    static List<Obtain> obtain(ItemData data) {
        GearInfo info = ORIGIN.get(data);
        if (info == null) {
            return List.of();
        }
        List<Obtain> out = new ArrayList<>();
        Wyn.obtainInfo(info).forEach(o -> out.add(new Obtain(o.sourceType().name(), o.name())));
        return out;
    }

    /** The stack the J menu draws for a piece: Wynntils' guide stack (with its tooltip), else its plain item. */
    static ItemStack icon(ItemData data) {
        GearInfo info = ORIGIN.get(data);
        try {
            return new GuideGearItemStack(info);
        } catch (Throwable t) {
            return info.metaInfo().material().itemStack();
        }
    }

    /** The tier a name's colour shows, or null. */
    static Tier tierOf(Component name) {
        GearTier t = Wyn.guard(Wyn.Feature.ITEMS, () -> GearTier.fromComponent(name), null);
        return t == null ? null : Tier.of(t.name());
    }

    private static Requirement requirement(GearRequirements r) {
        List<Requirement.SkillReq> skills = new ArrayList<>();
        r.skills().forEach(p -> skills.add(new Requirement.SkillReq(skill(p.a().name()), p.b())));
        return new Requirement(r.level(), r.classType().map(c -> klass(c.name())), List.copyOf(skills), r.quest());
    }

    private static BaseStats base(GearInfo info) {
        var f = info.fixedStats();
        return new BaseStats(averageDps(f), f.healthBuff(), f.attackSpeed().map(Enum::name),
                f.majorIds().map(m -> m.name()), damages(f.damages()), defences(f.defences()));
    }

    private static List<BaseStats.Damage> damages(List<Pair<DamageType, RangedValue>> l) {
        List<BaseStats.Damage> out = new ArrayList<>();
        l.forEach(d -> out.add(new BaseStats.Damage(d.a().name(), d.b().low(), d.b().high())));
        return List.copyOf(out);
    }

    private static List<BaseStats.Defence> defences(List<Pair<Element, Integer>> l) {
        List<BaseStats.Defence> out = new ArrayList<>();
        l.forEach(d -> out.add(new BaseStats.Defence(d.a().name(), d.b())));
        return List.copyOf(out);
    }

    private static List<StatRange> ranges(GearInfo info) {
        List<StatRange> out = new ArrayList<>();
        for (StatPossibleValues v : info.getPossibleValueList()) {
            out.add(range(v));
        }
        return List.copyOf(out);
    }

    private static StatRange range(StatPossibleValues v) {
        var t = v.statType();
        return new StatRange(t.getApiName(), t.getDisplayName(), t.getUnit().name(), t.treatAsInverted(),
                v.range().low(), v.range().high());
    }

    // FixedStats.averageDps(): 4.1.4 on (the database's own figure). Until 4.1.3 it is worked out
    // the way the database does: the average of each damage range, times hits per second.
    private static final String AVERAGE_DPS = "averageDps";

    private static int averageDps(FixedStats f) {
        Optional<Method> m = Wyn.method(FixedStats.class, AVERAGE_DPS);
        return m.isPresent() ? (Integer) Wyn.call(m.get(), f) : dps(f.damages(), f.attackSpeed());
    }

    /** Average damage per second from damage ranges and attack speed; 0 without an attack speed. */
    private static int dps(List<Pair<DamageType, RangedValue>> damages, Optional<GearAttackSpeed> speed) {
        if (speed.isEmpty()) {
            return 0;
        }
        double perHit = 0;
        for (var d : damages) {
            perHit += (d.b().low() + d.b().high()) / 2.0;
        }
        return (int) Math.round(perHit * hitsPerSecond(speed.get()));
    }

    // GearAttackSpeed.getHitsPerSecond(): 4.1.4 on. Until 4.1.3 the speed has no number, so the
    // game's own hits per second (the same for every release) stand in.
    private static final String HITS_PER_SECOND = "getHitsPerSecond";
    private static final Map<String, Double> GAME_HITS_PER_SECOND = Map.of("SUPER_FAST", 4.3, "VERY_FAST", 3.1,
            "FAST", 2.5, "NORMAL", 2.05, "SLOW", 1.5, "VERY_SLOW", 0.83, "SUPER_SLOW", 0.51);

    private static double hitsPerSecond(GearAttackSpeed speed) {
        Optional<Method> m = Wyn.method(GearAttackSpeed.class, HITS_PER_SECOND);
        return m.isPresent() ? (Double) Wyn.call(m.get(), speed) : GAME_HITS_PER_SECOND.getOrDefault(speed.name(), 0.0);
    }

    // SetInfo, by release: bonuses() is a Map<Integer, SetBonus> (pieces worn -> major and minor
    // bonuses) and cleanName() is there from 4.2.5. Until 4.2.3 bonuses() is a list of minor
    // bonuses (index 0 = one piece), there are no major ones, and the clean name is the name
    // without apostrophes, which is what cleanName() holds.
    private static final String BONUSES = "bonuses";
    private static final String CLEAN_NAME = "cleanName";

    @SuppressWarnings("unchecked")
    private static SetData set(SetInfo s) {
        Map<Integer, SetData.Bonus> bonuses = new LinkedHashMap<>();
        Object all = Wyn.call(s, BONUSES);
        if (all instanceof Map<?, ?> m) {
            m.forEach((n, b) -> bonuses.put((Integer) n, new SetData.Bonus(List.copyOf((List<String>) Wyn.call(b, "major")),
                    minor((Map<StatType, Integer>) Wyn.call(b, "minor")))));
        } else {
            List<Map<StatType, Integer>> l = (List<Map<StatType, Integer>>) all;
            for (int i = 0; i < l.size(); i++) {
                bonuses.put(i + 1, new SetData.Bonus(List.of(), minor(l.get(i))));
            }
        }
        Optional<Method> clean = Wyn.method(SetInfo.class, CLEAN_NAME);
        String cleanName = clean.isPresent() ? (String) Wyn.call(clean.get(), s) : s.name().replace("'", "");
        return new SetData(s.name(), cleanName, s.items().size(), Collections.unmodifiableMap(bonuses));
    }

    private static List<SetData.StatValue> minor(Map<StatType, Integer> m) {
        List<SetData.StatValue> minor = new ArrayList<>();
        m.forEach((st, v) -> minor.add(new SetData.StatValue(st.getApiName(), v)));
        return List.copyOf(minor);
    }

    static Klass klass(String name) {
        try {
            return Klass.valueOf(name);
        } catch (RuntimeException e) {
            return Klass.NONE;
        }
    }

    static SkillKind skill(String name) {
        return SkillKind.valueOf(name);
    }

    // ---- stacks ----

    /** Whether Wynntils has read this stack (cheap: nothing is converted). */
    static boolean known(ItemStack stack) {
        return Wyn.item(stack).isPresent();
    }

    /** What the mod knows about a stack; empty when Wynntils has not read it. */
    static Optional<ItemView> view(ItemStack stack) {
        Optional<WynnItem> p = Wyn.item(stack);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        WynnItem item = p.get();
        if (stack != null) {
            Seen s = BY_STACK.get(stack);
            if (s != null && s.item().get() == item) {
                return Optional.of(s.view());
            }
        }
        ItemView v = convert(item, stack);
        if (stack != null) {
            BY_STACK.put(stack, new Seen(new WeakReference<>(item), v));
        }
        return Optional.of(v);
    }

    /** One part of a view; null when Wynntils cannot give it (an odd item), the rest still counts. */
    private static <T> T part(Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ItemView convert(WynnItem item, ItemStack stack) {
        String type = item.getClass().getSimpleName();
        boolean plain = item.getClass() == WynnItem.class;
        String name = item instanceof NamedItemProperty n ? part(n::getName) : null;
        Integer level = item instanceof LeveledItemProperty l ? part(l::getLevel) : null;
        List<String> profs = item instanceof ProfessionItemProperty pp
                ? part(() -> pp.getProfessionTypes().stream().map(Enum::name).toList()) : null;
        ItemView.Kind kind = ItemView.Kind.OTHER;
        ItemData data = null;
        ItemView.Rolled rolled = null;
        ItemView.Crafted crafted = null;
        ItemView.Box box = null;
        Integer quality = null;
        ItemView.Powder powder = null;
        ItemView.Potion potion = null;
        ItemView.DungeonKey key = null;
        ItemView.SkillPoint sp = null;
        Integer crystal = null;
        String ability = null;
        if (item instanceof GearItem g) {
            kind = ItemView.Kind.GEAR;
            data = data(g.getItemInfo());
            GearInfo info = g.getItemInfo();
            rolled = g.getItemInstance().map(gi -> rolled(info, gi)).orElse(null);
        } else if (item instanceof GearBoxItem b) {
            kind = ItemView.Kind.BOX;
            box = part(() -> new ItemView.Box(Slot.of(b.getGearType().name()), Tier.of(b.getGearTier().name()),
                    b.getLevelRange().low(), b.getLevelRange().high()));
        } else if (item instanceof CraftedGearItem c) {
            kind = ItemView.Kind.CRAFTED;
            crafted = part(() -> crafted(c));
        } else if (item instanceof IngredientItem i) {
            kind = ItemView.Kind.INGREDIENT;
            quality = part(i::getQualityTier);
        } else if (item instanceof MaterialItem m) {
            kind = ItemView.Kind.MATERIAL;
            quality = part(m::getQualityTier);
        } else if (item instanceof PowderItem pw) {
            kind = ItemView.Kind.POWDER;
            powder = part(() -> new ItemView.Powder(pw.getPowderProfile().element().name(), pw.getTier()));
        } else if (item instanceof PotionItem po) {
            kind = ItemView.Kind.POTION;
            potion = part(() -> new ItemView.Potion(po.getType().name(), po.getLevel(), capped(po.getUses())));
        } else if (item instanceof DungeonKeyItem dk) {
            kind = ItemView.Kind.DUNGEON_KEY;
            key = part(() -> {
                boolean[] marks = keyMarks(dk, stack);
                boolean corrupted = marks[0];
                var d = corrupted ? dk.getDungeon().getCorruptedDungeonData() : dk.getDungeon().getDungeonData();
                return new ItemView.DungeonKey(dk.getDungeon().getName(), corrupted, marks[1],
                        d.map(x -> x.getCombatLevel()).orElse(null), d.map(x -> x.getXPos()).orElse(null),
                        d.map(x -> x.getZPos()).orElse(null));
            });
        } else if (item instanceof SkillPointItem s) {
            kind = ItemView.Kind.SKILL_POINT;
            sp = part(() -> new ItemView.SkillPoint(skill(s.getSkill().name()), assignedAmount(s)));
        } else if (item instanceof SkillCrystalItem c) {
            kind = ItemView.Kind.SKILL_CRYSTAL;
            crystal = c.hasCount() ? c.getCount() : null;
        } else if (isAbilityNode(item)) {
            kind = ItemView.Kind.ABILITY_NODE;
            ability = part(() -> ((StyledText) Wyn.call(item, ABILITY_NODE_NAME)).getStringWithoutFormatting());
        } else if (item instanceof ActivityItem) {
            kind = ItemView.Kind.ACTIVITY;
        }
        return new ItemView(kind, type, plain, name, level, profs, data, rolled, crafted, box, quality, powder,
                potion, key, sp, crystal, ability);
    }

    private static ItemView.Rolled rolled(GearInfo info, GearInstance gi) {
        List<Roll> rolls = new ArrayList<>();
        for (StatActualValue v : gi.identifications()) {
            StatPossibleValues possible = info.getPossibleValues(v.statType());
            StatRange r = possible == null || possible.range() == null ? null : range(possible);
            rolls.add(roll(v, r));
        }
        return new ItemView.Rolled(List.copyOf(rolls), powders(gi.powders()), gi.rerolls(),
                gi.overallQuality().orElse(null), gi.shinyStat().isPresent(), marks(gi));
    }

    // GearInstance.gearInstanceRequirements(): 4.2.10 on, a GearInstanceRequirements record with
    // each checkmark (and UNKNOWN when they could not be read). Until 4.2.9 there is only
    // meetsRequirements(), the same "can wear it" answer without the single checkmarks; the
    // marks then say nothing about single skills or the quest, as when they are unknown.
    private static final String GEAR_REQUIREMENTS = "gearInstanceRequirements";
    private static final String MEETS_REQUIREMENTS = "meetsRequirements";
    private static final String REQUIREMENTS_UNKNOWN = "UNKNOWN";
    private static volatile Object unknownRequirements;

    @SuppressWarnings("unchecked")
    private static ItemView.Marks marks(GearInstance gi) {
        Optional<Method> m = Wyn.method(GearInstance.class, GEAR_REQUIREMENTS);
        if (m.isEmpty()) {
            Method meets = Wyn.method(GearInstance.class, MEETS_REQUIREMENTS)
                    .orElseThrow(() -> Wyn.missing("GearInstance." + GEAR_REQUIREMENTS));
            return new ItemView.Marks(true, (Boolean) Wyn.call(meets, gi), Map.of(), true);
        }
        Object req = Wyn.call(m.get(), gi);
        if (req == null) {
            return null;
        }
        Map<SkillKind, Boolean> met = new EnumMap<>(SkillKind.class);
        ((Map<Skill, Boolean>) Wyn.call(req, "skillReqsMet")).forEach((s, ok) -> met.put(skill(s.name()), ok));
        return new ItemView.Marks(req != unknownRequirements(req.getClass()), (Boolean) Wyn.call(req, "meetsAllRequirements"),
                Collections.unmodifiableMap(met), (Boolean) Wyn.call(req, "questReqMet"));
    }

    private static Object unknownRequirements(Class<?> type) {
        Object u = unknownRequirements;
        if (u == null) {
            try {
                u = type.getField(REQUIREMENTS_UNKNOWN).get(null);
            } catch (ReflectiveOperationException e) {
                throw new NoSuchFieldError(type.getSimpleName() + "." + REQUIREMENTS_UNKNOWN + ": " + e);
            }
            unknownRequirements = u;
        }
        return u;
    }

    // DungeonKeyItem.isBroken(): 4.2.5 on. Until 4.2.3 a broken key is read as a corrupted one
    // (isCorrupted() is true for both); the key's name tells them apart, as Wynntils does later.
    private static final String IS_BROKEN = "isBroken";

    /** {corrupted, broken} for a dungeon key. */
    private static boolean[] keyMarks(DungeonKeyItem dk, ItemStack stack) {
        Optional<Method> broken = Wyn.method(DungeonKeyItem.class, IS_BROKEN);
        if (broken.isPresent()) {
            return new boolean[] {dk.isCorrupted(), (Boolean) Wyn.call(broken.get(), dk)};
        }
        String name = stack == null ? "" : stack.getHoverName().getString();
        boolean isBroken = name.contains("Broken ");
        return new boolean[] {isBroken ? name.contains("Corrupted ") : dk.isCorrupted(), isBroken};
    }

    // SkillPointItem.getAssignedAmount(): 4.1.20 on. Until 4.1.19 the item has only the menu's
    // number (getSkillPoints(), points from gear and tomes included), and Wynntils' skill point
    // model takes those extra points off to get the assigned ones; the same is done here.
    private static final String ASSIGNED_AMOUNT = "getAssignedAmount";

    private static int assignedAmount(SkillPointItem s) {
        Optional<Method> m = Wyn.method(SkillPointItem.class, ASSIGNED_AMOUNT);
        if (m.isPresent()) {
            return (Integer) Wyn.call(m.get(), s);
        }
        SkillKind k = skill(s.getSkill().name());
        return s.getSkillPoints() - (Wyn.totalPoints(k) - Wyn.assignedPoints(k));
    }

    // AbilityTreeNodeItem (a node in the ability tree menu, with getName()): 4.2.5 on. Releases
    // before do not read the nodes in that menu, so there no stack is one.
    private static final String ABILITY_NODE_ITEM = "com.wynntils.models.items.items.gui.AbilityTreeNodeItem";
    private static final String ABILITY_NODE_NAME = "getName";

    private static boolean isAbilityNode(WynnItem item) {
        return Wyn.type(ABILITY_NODE_ITEM).map(t -> t.isInstance(item)).orElse(false);
    }

    private static Roll roll(StatActualValue v, StatRange range) {
        var t = v.statType();
        return new Roll(t.getApiName(), t.getDisplayName(), t.getUnit().name(), v.value(), range);
    }

    private static List<String> powders(List<Powder> l) {
        return l.stream().map(Enum::name).toList();
    }

    private static ItemView.Crafted crafted(CraftedGearItem c) {
        List<Roll> rolls = new ArrayList<>();
        c.getIdentifications().forEach(v -> rolls.add(roll(v, null)));
        var cls = c.getRequiredClass();
        return new ItemView.Crafted(c.getName(), c.getGearType() == null ? null : Slot.of(c.getGearType().name()),
                Tier.of(c.getGearTier().name()), c.getLevel(), craftedDps(c), c.getHealth(), damages(c.getDamages()),
                requirement(c.getRequirements()), List.copyOf(rolls), cls == null ? null : klass(cls.name()),
                powders(c.getPowders()), c.getPowderSlots(), capped(c.getDurability()));
    }

    // CraftedGearItem.getDps(): 4.2.9 on, read from the tooltip. Before, worked out from the
    // piece's damage ranges and attack speed, as the game does.
    private static final String CRAFTED_DPS = "getDps";

    private static int craftedDps(CraftedGearItem c) {
        Optional<Method> m = Wyn.method(CraftedGearItem.class, CRAFTED_DPS);
        return m.isPresent() ? (Integer) Wyn.call(m.get(), c) : dps(c.getDamages(), c.getAttackSpeed());
    }

    static Capped capped(CappedValue v) {
        return v == null ? null : new Capped(v.current(), v.max());
    }

    // ---- the content book ----

    /** The content book entry this stack shows, read when asked (only the quest book wants it). */
    static Optional<Activity> activity(ItemStack stack) {
        var item = Wyn.asItem(stack, ActivityItem.class);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        ActivityInfo a = item.get().getActivityInfo();
        if (a == null) {
            return Optional.of(new Activity(null, false, null, null, false, null, null, List.of(), List.of(), null,
                    null, "", null, null));
        }
        var req = a.requirements();
        Integer level = null;
        Boolean levelMet = null;
        List<Boolean> profs = new ArrayList<>();
        List<Boolean> quests = new ArrayList<>();
        if (req != null) {
            if (req.level() != null && req.level().a() != null) {
                level = req.level().a();
                levelMet = req.level().b();
            }
            for (var p : req.professionLevels()) {
                profs.add(p.b());
            }
            for (var p : req.quests()) {
                quests.add(p.b());
            }
        }
        List<String> xp = null;
        var rewards = a.rewards();
        if (rewards != null && rewards.get(ActivityRewardType.XP) != null) {
            xp = new ArrayList<>();
            for (var line : rewards.get(ActivityRewardType.XP)) {
                xp.add(line.getStringWithoutFormatting());
            }
        }
        return Optional.of(new Activity(a.type() == null ? null : a.type().name(),
                a.type() != null && a.type().isQuest(), a.name(), a.status() == null ? null : a.status().name(),
                req != null, level, levelMet, profs, quests, xp,
                a.description().map(d -> d.getStringWithoutFormatting()).orElse(null), a.distanceInfo().orElse(""),
                a.length().map(Enum::name).orElse(null), a.difficulty().map(Enum::name).orElse(null)));
    }
}
