package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wynntils.core.components.Handlers;
import com.wynntils.core.components.Managers;
import com.wynntils.core.components.Models;
import com.wynntils.models.abilitytree.AbilityTreeModel;
import com.wynntils.models.abilitytree.type.AbilityTreeConnectionType;
import com.wynntils.models.abilitytree.type.AbilityTreeInfo;
import com.wynntils.models.abilitytree.type.AbilityTreeNodeState;
import com.wynntils.models.abilitytree.type.AbilityTreeSkillNode;
import com.wynntils.models.activities.quests.QuestInfo;
import com.wynntils.models.activities.type.ActivitySortOrder;
import com.wynntils.models.activities.type.ActivityType;
import com.wynntils.models.activities.type.DiscoveryType;
import com.wynntils.models.character.type.ClassType;
import com.wynntils.models.containers.Container;
import com.wynntils.models.containers.containers.personal.AccountBankContainer;
import com.wynntils.models.containers.containers.personal.CharacterBankContainer;
import com.wynntils.models.containers.containers.personal.PersonalStorageContainer;
import com.wynntils.models.containers.containers.trademarket.TradeMarketSellContainer;
import com.wynntils.models.elements.type.Skill;
import com.wynntils.models.containers.containers.RaidRewardPreviewContainer;
import com.wynntils.models.gear.type.GearInfo;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.AspectItem;
import com.wynntils.models.items.items.game.GearBoxItem;
import com.wynntils.models.lootrun.type.LootrunningState;
import com.wynntils.models.lootrun.type.MissionType;
import com.wynntils.models.marker.MarkerModel;
import com.wynntils.models.marker.UserWaypointMarkerProvider;
import com.wynntils.models.objectives.WynnObjective;
import com.wynntils.models.profession.type.ProfessionType;
import com.wynntils.models.raid.RaidModel;
import com.wynntils.models.territories.profile.TerritoryProfile;
import com.wynntils.models.wynnitem.type.ItemObtainInfo;
import com.wynntils.utils.mc.type.Location;
import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Capped;
import io.github.anilken.wynnlabs.model.Goal;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.LootrunRun;
import io.github.anilken.wynnlabs.model.MenuKind;
import io.github.anilken.wynnlabs.model.Pin;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.Quest;
import io.github.anilken.wynnlabs.model.QuestKind;
import io.github.anilken.wynnlabs.model.RaidDry;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Position;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Every call Wynn Labs makes into Wynntils goes through here. Wynntils' internals are not a public
 * API: when an update renames or removes something, the call fails with a LinkageError at run
 * time. Each method below runs its call through {@link #guard}, which returns a safe default
 * instead, marks the feature that depends on it as down, and logs the first failure, so a
 * Wynntils change costs one feature and never crashes the game. What it hands back is in the
 * mod's own types (the model package); items are converted by {@link WynntilsItems}. Only these
 * two files and the event hooks in {@link WynntilsEvents} see Wynntils' types (the build checks).
 */
final class Wyn {

    /** What a Wynntils call is for; a down feature is shown to the player. */
    enum Feature {
        CHARACTER, ITEMS, GEAR_DB, QUESTS, ABILITIES, SKILLS, WAYPOINTS, EVENTS, OBJECTIVES,
        PROFESSIONS, CONTAINERS, STATS, WORLD, NOTIFY, RAIDS, LOOTRUNS, ASPECTS
    }

    private static final Map<Feature, String> DOWN = new EnumMap<>(Feature.class);

    private Wyn() {}

    /**
     * Runs one Wynntils call: its value, or {@code fallback} when Wynntils no longer has what
     * we call (or throws from inside); the first failure of a feature is logged with its trace.
     */
    static <T> T guard(Feature f, Supplier<T> call, T fallback) {
        if (!GameState.current().running()) {
            return fallback;                    // a recorded game (tests, the J menu preview): no Wynntils
        }
        try {
            return call.get();
        } catch (LinkageError e) {
            // Wynntils no longer has what we call: this feature is off for the session
            boolean first;
            synchronized (DOWN) {
                first = !DOWN.containsKey(f);
                DOWN.putIfAbsent(f, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            if (first) {
                System.out.println("[WynnLabs] Wynntils " + version() + ": " + f + " is off");
                e.printStackTrace(System.out);
            }
            return fallback;
        } catch (RuntimeException e) {
            // an ordinary failure inside Wynntils (an odd item, a menu not loaded yet): this call
            // only; logged once per feature so a real problem is still visible
            synchronized (ODD) {
                if (ODD.add(f)) {
                    System.out.println("[WynnLabs] Wynntils " + f + " call threw (feature stays on): " + e);
                }
            }
            return fallback;
        }
    }

    private static final java.util.Set<Feature> ODD = java.util.EnumSet.noneOf(Feature.class);

    private static void run(Feature f, Runnable call) {
        guard(f, () -> {
            call.run();
            return null;
        }, null);
    }

    static boolean up(Feature f) {
        synchronized (DOWN) {
            return !DOWN.containsKey(f);
        }
    }

    /** The features that failed this session, with why. */
    static Map<Feature, String> down() {
        synchronized (DOWN) {
            return new EnumMap<>(DOWN);
        }
    }

    /** For the J menu preview: forget what was marked down before the recorded game was in. */
    static void clearDown() {
        synchronized (DOWN) {
            DOWN.clear();
        }
    }

    /** For the debug toggle and tests: mark a feature down by hand. */
    static void markDown(Feature f, String why) {
        synchronized (DOWN) {
            DOWN.putIfAbsent(f, why);
        }
    }

    static String version() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("wynntils")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        } catch (Throwable t) {
            return "?";                             // never let the guard itself fail
        }
    }

    // ---- members that not every supported Wynntils release has ----
    //
    // Wynn Labs compiles against the newest Wynntils but runs on every release since 4.1.0, the
    // first with Wynncraft 2.2.0 (Fruma). A member that some of those releases lack is looked up
    // here by name, once; the caller then uses that release's own equivalent. Each caller names
    // the member and the releases that have it. Where no equivalent exists, the caller throws
    // {@link #missing}, which its guard turns into "feature off" like any other API change.

    private static final Map<String, Optional<Class<?>>> TYPES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    /** A Wynntils class by its full name, or empty when this release has none. */
    static Optional<Class<?>> type(String name) {
        return TYPES.computeIfAbsent(name, n -> {
            try {
                return Optional.of(Class.forName(n, false, Models.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                return Optional.empty();
            }
        });
    }

    /** A public method of {@code owner}, or empty when this release lacks it. */
    static Optional<Method> method(Class<?> owner, String name, Class<?>... params) {
        return METHODS.computeIfAbsent(owner.getName() + '#' + name + Arrays.toString(params), k -> {
            try {
                return Optional.of(owner.getMethod(name, params));
            } catch (NoSuchMethodException | LinkageError e) {
                return Optional.empty();
            }
        });
    }

    /** Calls a method from {@link #method}; what it throws comes out as from a direct call. */
    static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IncompatibleClassChangeError(m + ": " + e);
        }
    }

    /** Calls a no-argument method of {@code target}'s own class, or throws {@link #missing}. */
    static Object call(Object target, String name) {
        return call(method(target.getClass(), name).orElseThrow(() -> missing(target.getClass().getSimpleName() + "." + name)),
                target);
    }

    /** Neither the newest member nor an older equivalent is in this Wynntils. */
    static NoSuchMethodError missing(String what) {
        return new NoSuchMethodError(what + " is not in this Wynntils");
    }

    // ---- self-test and telling the player ----

    private static final java.util.Set<Feature> ANNOUNCED = java.util.EnumSet.noneOf(Feature.class);
    private static int tick;

    /** Once per world join: one cheap read of every Wynntils feature the mod uses. */
    static void selfTest() {
        level();
        xp();
        Klass cls = classType();
        hasCharacter();
        item(ItemStack.EMPTY);
        guard(Feature.GEAR_DB, () -> Models.Gear.getAllGearInfos().findAny().isPresent(), false);
        tracking();
        quests();
        abilityTree(cls);
        unlockedAbilities();
        assignedPoints(SkillKind.STRENGTH);
        waypoints();
        objectives();
        professionLevel(ProfessionType.values()[0]);
        container();
        raidDry();
        lootrunState();
        guard(Feature.ASPECTS, () -> Models.Aspect.getAllAspectInfos().findAny().isPresent(), false);
        classAspects(cls);
    }

    /** Every few seconds: one chat line per session for features that went down. */
    static void onTick(net.minecraft.client.Minecraft mc) {
        if (++tick % 100 != 0 || mc.player == null) {
            return;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        synchronized (DOWN) {
            for (Feature f : DOWN.keySet()) {
                if (ANNOUNCED.add(f)) {
                    names.add(name(f));
                }
            }
        }
        if (!names.isEmpty()) {
            mc.gui.getChat().addMessage(L.c("wynnlabs.wyn.down", String.join(", ", names), version())
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    /** A feature's name in the player's language. */
    static String name(Feature f) {
        return switch (f) {
            case CHARACTER -> L.t("wynnlabs.wyn.f_character");
            case ITEMS -> L.t("wynnlabs.wyn.f_items");
            case GEAR_DB -> L.t("wynnlabs.wyn.f_gear_db");
            case QUESTS -> L.t("wynnlabs.wyn.f_quests");
            case ABILITIES -> L.t("wynnlabs.wyn.f_abilities");
            case SKILLS -> L.t("wynnlabs.wyn.f_skills");
            case WAYPOINTS -> L.t("wynnlabs.wyn.f_waypoints");
            case EVENTS -> L.t("wynnlabs.wyn.f_events");
            case OBJECTIVES -> L.t("wynnlabs.wyn.f_objectives");
            case PROFESSIONS -> L.t("wynnlabs.wyn.f_professions");
            case CONTAINERS -> L.t("wynnlabs.wyn.f_containers");
            case STATS -> L.t("wynnlabs.wyn.f_stats");
            case WORLD -> L.t("wynnlabs.wyn.f_world");
            case NOTIFY -> L.t("wynnlabs.wyn.f_notify");
            case RAIDS -> L.t("wynnlabs.wyn.f_raids");
            case LOOTRUNS -> L.t("wynnlabs.wyn.f_lootruns");
            case ASPECTS -> L.t("wynnlabs.wyn.f_aspects");
        };
    }


    // ---- character ----

    static int level() {
        if (!GameState.current().running()) {
            return GameState.current().level();
        }
        return guard(Feature.CHARACTER, () -> Models.CombatXp.getCombatLevel().current(), 0);
    }

    static Capped xp() {
        return guard(Feature.CHARACTER, () -> WynntilsItems.capped(Models.CombatXp.getXp()), Capped.EMPTY);
    }

    static Klass classType() {
        if (!GameState.current().running()) {
            return GameState.current().classType();
        }
        return guard(Feature.CHARACTER, () -> {
            ClassType c = Models.Character.getClassType();
            return c == null ? Klass.NONE : WynntilsItems.klass(c.name());
        }, Klass.NONE);
    }

    static boolean hasCharacter() {
        return guard(Feature.CHARACTER, () -> Models.Character.hasCharacter(), false);
    }

    static String characterId() {
        return guard(Feature.CHARACTER, () -> Models.Character.getId(), "");
    }

    static boolean reskinned() {
        return guard(Feature.CHARACTER, () -> Models.Character.isReskinned(), false);
    }

    static Optional<Capped> health() {
        return guard(Feature.STATS, () -> Models.CharacterStats.getHealth().map(WynntilsItems::capped), Optional.empty());
    }

    static Optional<Capped> mana() {
        return guard(Feature.STATS, () -> Models.CharacterStats.getMana().map(WynntilsItems::capped), Optional.empty());
    }

    // ---- items and gear (the rest of the mod reads them through WynntilsItems) ----

    static Optional<WynnItem> item(ItemStack stack) {
        return guard(Feature.ITEMS, () -> Models.Item.getWynnItem(stack), Optional.empty());
    }

    static <T extends WynnItem> Optional<T> asItem(ItemStack stack, Class<T> type) {
        return guard(Feature.ITEMS, () -> Models.Item.asWynnItem(stack, type), Optional.empty());
    }

    private static java.lang.reflect.Method annotate;

    /**
     * Has Wynntils parse a stack it has not seen (a bank page stored as JSON, a copied stack):
     * its private ItemHandler.annotate, by reflection. False when that is gone.
     */
    static boolean annotate(ItemStack stack) {
        return guard(Feature.ITEMS, () -> {
            try {
                if (annotate == null) {
                    annotate = com.wynntils.handlers.item.ItemHandler.class.getDeclaredMethod("annotate", ItemStack.class);
                    annotate.setAccessible(true);
                }
                annotate.invoke(Handlers.Item, stack);
                return true;
            } catch (ReflectiveOperationException e) {
                // the private method is gone or changed: an API change like any other
                throw new IncompatibleClassChangeError("ItemHandler.annotate unavailable: " + e);
            }
        }, false);
    }

    static int emeralds() {
        return guard(Feature.ITEMS, () -> Models.Emerald.getAmountInInventory(), 0);
    }

    static List<ItemStack> accessories(Player player) {
        return guard(Feature.ITEMS, () -> com.wynntils.utils.wynn.InventoryUtils.getAccessories(player), List.of());
    }

    static Stream<GearInfo> allGear() {
        return guard(Feature.GEAR_DB, () -> Models.Gear.getAllGearInfos(), Stream.empty());
    }

    static GearInfo gearByName(String name) {
        return guard(Feature.GEAR_DB, () -> Models.Gear.getGearInfoFromDisplayName(name), null);
    }

    static List<GearInfo> possibleGears(GearBoxItem box) {
        return guard(Feature.GEAR_DB, () -> Models.Gear.getPossibleGears(box), List.of());
    }

    static List<ItemObtainInfo> obtainInfo(GearInfo info) {
        return guard(Feature.GEAR_DB, () -> Models.Gear.getObtainInfo(info), List.of());
    }

    // ---- skill points ----

    static int assignedPoints(SkillKind s) {
        return guard(Feature.SKILLS, () -> Models.SkillPoint.getAssignedSkillPoints(skill(s)), 0);
    }

    static int gearPoints(SkillKind s) {
        return guard(Feature.SKILLS, () -> Models.SkillPoint.getGearSkillPoints(skill(s)), 0);
    }

    static int tomePoints(SkillKind s) {
        return guard(Feature.SKILLS, () -> Models.SkillPoint.getTomeSkillPoints(skill(s)), 0);
    }

    static int setBonusPoints(SkillKind s) {
        return guard(Feature.SKILLS, () -> Models.SkillPoint.getSetBonusSkillPoints(skill(s)), 0);
    }

    static int totalPoints(SkillKind s) {
        return guard(Feature.SKILLS, () -> Models.SkillPoint.getTotalSkillPoints(skill(s)), 0);
    }

    private static Skill skill(SkillKind s) {
        return Skill.valueOf(s.name());
    }

    // ---- quests and activities ----

    static boolean tracking() {
        return guard(Feature.QUESTS, () -> Models.Activity.isTracking(), false);
    }

    static String trackedName() {
        return guard(Feature.QUESTS, () -> Models.Activity.getTrackedName(), "");
    }

    /** The tracked task as plain text ("" when none). */
    static String trackedTask() {
        return guard(Feature.QUESTS, () -> {
            var t = Models.Activity.getTrackedTask();
            return t == null ? "" : t.getStringWithoutFormatting();
        }, "");
    }

    static QuestKind trackedType() {
        return guard(Feature.QUESTS, () -> {
            ActivityType t = Models.Activity.getTrackedType();
            return t == null ? null : QuestKind.valueOf(t.name());
        }, null);
    }

    /** Tracks a content book entry. A kind Wynntils does not have throws here, outside the guard. */
    static boolean startTracking(String name, QuestKind kind) {
        ActivityType type = ActivityType.valueOf(kind.name());
        return guard(Feature.QUESTS, () -> {
            Models.Activity.startTracking(name, type);
            return true;
        }, false);
    }

    /**
     * The secret discoveries Wynntils lists as found for this character, by name. Empty until its
     * content book scan read them (and whenever the call fails).
     */
    static java.util.Set<String> completedDiscoveries() {
        return guard(Feature.QUESTS, () -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            Models.Discovery.getAllDiscoveriesForType(DiscoveryType.SECRET).forEach(d -> {
                if (d.discovered()) {
                    out.add(d.name());
                }
            });
            return out;
        }, java.util.Set.of());
    }

    /** Quests as Wynntils last read them from the content book. */
    static List<Quest> quests() {
        return guard(Feature.QUESTS, () -> {
            List<Quest> out = new ArrayList<>();
            for (QuestInfo q : Models.Quest.getQuestsRaw()) {
                out.add(quest(q));
            }
            return out;
        }, List.of());
    }

    /** Quests and mini-quests as Wynntils last read them from the content book, by level. */
    static List<Quest> questsAndMiniQuests() {
        return guard(Feature.QUESTS, () -> {
            List<Quest> out = new ArrayList<>();
            for (QuestInfo q : Models.Quest.getSortedQuests(ActivitySortOrder.LEVEL, true, true)) {
                out.add(quest(q));
            }
            return out;
        }, List.of());
    }

    /**
     * Whether the quests tab's "read quests with Wynntils" can run: the quest calls work and a
     * character is selected. The J menu preview (no Wynntils) draws it as in a game.
     */
    static boolean canRescanQuests() {
        if (!up(Feature.QUESTS)) {
            return false;
        }
        if (!GameState.current().running()) {
            return true;
        }
        return hasCharacter();
    }

    /**
     * Wynntils' own quest book refresh: it opens the content book for a moment, reads every
     * quest and mini-quest page, then posts the new lists (taken in by {@link QuestBook#fromWynntils}).
     * False when unavailable. Same signature in every supported release (4.1.0 to 4.2.14).
     * <p>
     * The one place the mod asks Wynntils to open a menu to read it, and only on the player's
     * press of the quests tab's button or "Yenile" chip ({@link QuestBook#askWynntils}). Never call
     * it automatically, on a timer or when a tab opens. (Tracking a quest, {@link #startTracking},
     * also has Wynntils open the book, likewise only on the player's click.)
     */
    static boolean rescanQuests() {
        return guard(Feature.QUESTS, () -> {
            Models.Quest.rescanQuestBook(true, true);
            return true;
        }, false);
    }

    private static Quest quest(QuestInfo q) {
        var req = q.additionalRequirements();
        Quest.Requirements r = null;
        if (req != null) {
            Integer lv = req.level() == null ? null : req.level().a();
            Boolean met = req.level() == null ? null : req.level().b();
            List<Quest.Profession> profs = new ArrayList<>();
            for (var p : req.professionLevels()) {
                profs.add(new Quest.Profession(p.a().a().name(), p.a().b(), p.b()));
            }
            List<Quest.Need> qs = new ArrayList<>();
            for (var p : req.quests()) {
                qs.add(new Quest.Need(p.a(), p.b()));
            }
            r = new Quest.Requirements(lv, met, profs, qs);
        }
        Map<String, List<String>> rewards = null;
        if (q.rewards() != null) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            q.rewards().forEach((type, list) -> {
                List<String> l = new ArrayList<>();
                list.forEach(t -> l.add(t.getStringWithoutFormatting()));
                m.put(type.name(), l);
            });
            rewards = m;
        }
        String next = q.nextTask() != null && !q.nextTask().isBlank() ? q.nextTask().getStringWithoutFormatting() : null;
        return new Quest(q.name(), q.isMiniQuest(), q.status() == null ? null : q.status().name(), q.level(),
                q.difficulty() == null ? null : q.difficulty().name(), q.length() == null ? null : q.length().name(),
                next, r, rewards);
    }

    static List<Goal> objectives() {
        return guard(Feature.OBJECTIVES, () -> {
            List<Goal> out = new ArrayList<>();
            for (WynnObjective o : Models.Objectives.getPersonalObjectives()) {
                out.add(new Goal(o.getGoal(), WynntilsItems.capped(o.getScore())));
            }
            return out;
        }, List.of());
    }

    // ---- ability tree, professions, world ----

    // AbilityTreeModel.getUnlockedAbilities(): 4.2.5 on. getNodeState(AbilityTreeSkillNode): until
    // 4.2.3, the state of a node in the tree Wynntils last parsed from the menu.
    private static final String GET_UNLOCKED_ABILITIES = "getUnlockedAbilities";
    private static final String GET_NODE_STATE = "getNodeState";

    @SuppressWarnings("unchecked")
    /** The names in your party (Wynntils' party list), you included; empty when not in one. */
    static List<String> partyMembers() {
        return guard(Feature.CHARACTER, () -> Models.Party.isInParty() ? List.copyOf(Models.Party.getPartyMembers()) : List.<String>of(),
                List.of());
    }

    static List<String> unlockedAbilities() {
        return guard(Feature.ABILITIES, () -> {
            Optional<Method> unlocked = method(AbilityTreeModel.class, GET_UNLOCKED_ABILITIES);
            if (unlocked.isPresent()) {
                return (List<String>) call(unlocked.get(), Models.AbilityTree);
            }
            Method state = method(AbilityTreeModel.class, GET_NODE_STATE, AbilityTreeSkillNode.class)
                    .orElseThrow(() -> missing("AbilityTreeModel." + GET_UNLOCKED_ABILITIES));
            ClassType c = Models.Character.getClassType();
            AbilityTreeInfo tree = c == null ? null : Models.AbilityTree.getAbilityTree(c);
            if (tree == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (AbilityTreeSkillNode n : tree.nodes()) {
                if (call(state, Models.AbilityTree, n) == AbilityTreeNodeState.UNLOCKED) {
                    out.add(n.name());
                }
            }
            return out;
        }, List.of());
    }

    private static AbilityTreeInfo lastTree;
    private static List<AbilityNode> lastNodes;

    /** The class's ability tree, or null when Wynntils has none for it. Converted once per tree. */
    static List<AbilityNode> abilityTree(Klass cls) {
        return guard(Feature.ABILITIES, () -> {
            ClassType c = cls == null ? null : ClassType.valueOf(cls.name());
            AbilityTreeInfo tree = Models.AbilityTree.getAbilityTree(c);
            if (tree == null) {
                return null;
            }
            synchronized (Wyn.class) {
                if (tree != lastTree) {
                    List<AbilityNode> nodes = new ArrayList<>();
                    for (AbilityTreeSkillNode n : tree.nodes()) {
                        nodes.add(node(n, c));
                    }
                    lastNodes = java.util.Collections.unmodifiableList(nodes);
                    lastTree = tree;
                }
                return lastNodes;
            }
        }, null);
    }

    // AbilityTreeSkillNode, by release:
    //   abilityTreeNodeType(), willBlock(), requiredLevel(): 4.1.22 on. Until 4.1.21 the node has
    //     no type (its spells are the nodes with a click combo), blocks() for willBlock(), and its
    //     data has no level requirement (0, as for most nodes now).
    //   archetypeInfo().archetype(): 4.2.5 on; until 4.2.3 archetype(), the same name.
    //   description(): component JSON lines (List<JsonArray>) from 4.2.5, before that formatted
    //     text lines (List<String>).
    private static final String NODE_TYPE = "abilityTreeNodeType";
    private static final String WILL_BLOCK = "willBlock";
    private static final String BLOCKS = "blocks";
    private static final String REQUIRED_LEVEL = "requiredLevel";
    private static final String ARCHETYPE_INFO = "archetypeInfo";
    private static final String ARCHETYPE = "archetype";
    private static final String ARCHETYPE_COLOUR = "color";

    @SuppressWarnings("unchecked")
    private static AbilityNode node(AbilityTreeSkillNode n, ClassType c) {
        var ra = n.requiredArchetype();
        var loc = n.location();
        List<JsonArray> description = description(n.description());
        String type;
        Optional<Method> nodeType = method(AbilityTreeSkillNode.class, NODE_TYPE);
        if (nodeType.isPresent()) {
            Object t = call(nodeType.get(), n);
            type = t == null ? null : ((Enum<?>) t).name();
        } else {
            type = c != null && isSpell(description) ? c.name() + "_ABILITY_LOCKED" : null;
        }
        Optional<Method> willBlock = method(AbilityTreeSkillNode.class, WILL_BLOCK);
        List<String> blocks = (List<String>) call(willBlock.isPresent() ? willBlock.get()
                : method(AbilityTreeSkillNode.class, BLOCKS).orElseThrow(() -> missing("AbilityTreeSkillNode." + WILL_BLOCK)), n);
        Optional<Method> level = method(AbilityTreeSkillNode.class, REQUIRED_LEVEL);
        int requiredLevel = level.isPresent() ? (Integer) call(level.get(), n) : 0;
        String archetype;
        String colour = null;
        Optional<Method> info = method(AbilityTreeSkillNode.class, ARCHETYPE_INFO);
        if (info.isPresent()) {
            Object ai = call(info.get(), n);
            archetype = ai == null ? null : (String) call(ai, ARCHETYPE);
            colour = ai == null ? null : (String) call(ai, ARCHETYPE_COLOUR);
        } else {
            archetype = (String) call(n, ARCHETYPE);
        }
        return new AbilityNode(n.id(), n.name(), type, description, n.cost(), blocks, n.requiredAbility(),
                ra == null ? null : ra.name(), ra == null ? 0 : ra.required(), requiredLevel, archetype, colour,
                loc == null ? null : new AbilityNode.Place(loc.page(), loc.row(), loc.col()), n.connections());
    }

    // AbilityTreeConnectionType.fromItemStack: 4.2 on (the piece by its model data); before that
    // the pieces were told apart by damage, which no item carries now.
    private static final String CONNECTOR_FROM_STACK = "fromItemStack";

    /**
     * A connector piece for an item of the tree menu: its type's name and its arms (up, right,
     * down, left), or null when the item is no piece.
     */
    static TreeConnectors.Piece connector(ItemStack stack) {
        return guard(Feature.ABILITIES, () -> {
            Method m = method(AbilityTreeConnectionType.class, CONNECTOR_FROM_STACK, ItemStack.class).orElse(null);
            if (m == null || stack == null || stack.isEmpty()) {
                return null;
            }
            Object t = call(m, null, stack);
            if (!(t instanceof AbilityTreeConnectionType type)) {
                return null;
            }
            boolean[] d = type.getPossibleDirections();
            return d == null || d.length < 4 ? null : new TreeConnectors.Piece(type.name(), d.clone());
        }, null);
    }

    private static final Map<String, ItemStack> CONNECTOR_ICONS = new ConcurrentHashMap<>();

    /**
     * The game's own picture of a connector piece with these arms (up, right, down, left),
     * the given ones lit, as the tree menu draws it; null without the pack's model data or
     * when Wynntils has no piece of that shape.
     */
    static ItemStack connectorIcon(boolean[] arms, boolean[] lit) {
        if (arms == null || arms.length < 4) {
            return null;
        }
        ItemStack s = CONNECTOR_ICONS.computeIfAbsent(Arrays.toString(arms) + Arrays.toString(lit), k -> guard(Feature.ABILITIES, () -> {
            AbilityTreeConnectionType t = null;
            for (AbilityTreeConnectionType c : AbilityTreeConnectionType.values()) {
                if (Arrays.equals(c.getPossibleDirections(), arms)) {
                    t = c;
                    break;
                }
            }
            if (t == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = t.getItemStack(lit);
            if (stack == null || stack.isEmpty()
                    || !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA)) {
                return ItemStack.EMPTY;
            }
            var model = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
            if (model == null || model.floats().isEmpty() || model.floats().get(0) < 0) {
                return ItemStack.EMPTY;         // -1: the pack has no model for it
            }
            return stack.copy();
        }, ItemStack.EMPTY));
        return s.isEmpty() ? null : s;
    }

    // AbilityTreeNodeType (4.1.22 on): every node type in every state, each with the model data
    // of the game's own icon when the Wynncraft pack is loaded.
    private static final String NODE_TYPE_CLASS = "com.wynntils.models.abilitytree.type.AbilityTreeNodeType";
    private static final String GET_CUSTOM_MODEL_DATA = "getCustomModelData";
    private static final String[] NODE_STATES = {"_LOCKED", "_UNLOCKABLE", "_UNLOCKED", "_BLOCKED"};
    private static final Map<String, ItemStack> NODE_ICONS = new ConcurrentHashMap<>();

    /**
     * The game's own icon for a node type ({@link AbilityNode#type}) in a state ("LOCKED",
     * "UNLOCKABLE", "UNLOCKED", "BLOCKED"): a stack the pack repaints, as the tree menu shows it.
     * Null without the pack's model data, or when Wynntils has no such type (a state the type
     * lacks falls back to LOCKED).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ItemStack nodeIcon(String type, String state) {
        if (type == null) {
            return null;
        }
        ItemStack s = NODE_ICONS.computeIfAbsent(type + "|" + state, k -> guard(Feature.ABILITIES, () -> {
            String base = type;
            for (String suffix : NODE_STATES) {
                if (base.endsWith(suffix)) {
                    base = base.substring(0, base.length() - suffix.length());
                }
            }
            Class<?> t = type(NODE_TYPE_CLASS).orElse(null);
            if (t == null || !t.isEnum()) {
                return ItemStack.EMPTY;
            }
            Object v;
            try {
                v = Enum.valueOf((Class<Enum>) t, base + "_" + state);
            } catch (IllegalArgumentException e) {
                try {
                    v = Enum.valueOf((Class<Enum>) t, base + "_LOCKED");
                } catch (IllegalArgumentException e2) {
                    return ItemStack.EMPTY;
                }
            }
            Optional<Float> model = (Optional<Float>) call(v, GET_CUSTOM_MODEL_DATA);
            if (model == null || model.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(net.minecraft.world.item.Items.POTION);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                    new net.minecraft.world.item.component.CustomModelData(List.of(model.get()), List.of(), List.of(), List.of()));
            return stack;
        }, ItemStack.EMPTY));
        return s.isEmpty() ? null : s;
    }

    /** A node's description as component JSON lines; a formatted text line becomes one plain part. */
    @SuppressWarnings("unchecked")
    private static List<JsonArray> description(List<?> lines) {
        if (lines == null || lines.stream().allMatch(l -> l instanceof JsonArray)) {
            return (List<JsonArray>) lines;          // 4.2.5 on: already component JSON
        }
        List<JsonArray> out = new ArrayList<>(lines.size());
        for (Object line : lines) {
            if (line instanceof JsonArray a) {
                out.add(a);
            } else if (line != null) {
                JsonObject part = new JsonObject();
                String plain = ChatFormatting.stripFormatting(line.toString());
                part.addProperty("text", plain == null ? "" : plain);
                JsonArray a = new JsonArray();
                a.add(part);
                out.add(a);
            }
        }
        return out;
    }

    /** Whether a node is a spell: its description shows the click combo that casts it. */
    private static boolean isSpell(List<JsonArray> description) {
        if (description != null) {
            for (JsonArray line : description) {
                for (var e : line) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("text")
                            && e.getAsJsonObject().get("text").getAsString().contains("Click Combo")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // AbilityPointProgression.getPointsAtLevel(int): 4.2.0 on. Releases before have no table of
    // ability points per level, so there this call is off.
    private static final String POINT_PROGRESSION = "com.wynntils.models.abilitytree.type.AbilityPointProgression";
    private static final String GET_POINTS_AT_LEVEL = "getPointsAtLevel";

    /** Ability points a character has at this level. */
    static int abilityPointsAt(int level) {
        return guard(Feature.ABILITIES, () -> {
            Method m = type(POINT_PROGRESSION).flatMap(t -> method(t, GET_POINTS_AT_LEVEL, int.class))
                    .orElseThrow(() -> missing("AbilityPointProgression." + GET_POINTS_AT_LEVEL));
            return (Integer) call(m, null, level);
        }, 0);
    }

    /** A profession's level by its name ("COOKING"). An unknown name throws here, outside the guard. */
    /** Wynntils' ingredients as the calculator's plain records, read once; empty while Wynntils has none. */
    private static volatile java.util.List<io.github.anilken.wynnlabs.crafting.Ingredient> ingredients = java.util.List.of();

    static java.util.List<io.github.anilken.wynnlabs.crafting.Ingredient> craftIngredients() {
        java.util.List<io.github.anilken.wynnlabs.crafting.Ingredient> have = ingredients;
        if (!have.isEmpty()) {
            return have;
        }
        java.util.List<io.github.anilken.wynnlabs.crafting.Ingredient> read = guard(Feature.GEAR_DB, () -> {
            java.util.List<io.github.anilken.wynnlabs.crafting.Ingredient> out = new java.util.ArrayList<>();
            Models.Ingredient.getAllIngredientInfos().forEach(info -> out.add(ingredient(info)));
            out.sort(java.util.Comparator.comparing(io.github.anilken.wynnlabs.crafting.Ingredient::name));
            return java.util.List.copyOf(out);
        }, java.util.List.<io.github.anilken.wynnlabs.crafting.Ingredient>of());
        ingredients = read;
        return read;
    }

    private static io.github.anilken.wynnlabs.crafting.Ingredient ingredient(
            com.wynntils.models.ingredients.type.IngredientInfo info) {
        java.util.List<String> profs = new java.util.ArrayList<>();
        info.professions().forEach(p -> profs.add(p.name().toLowerCase(java.util.Locale.ROOT)));
        java.util.Map<String, io.github.anilken.wynnlabs.crafting.Ingredient.Stat> ids = new java.util.LinkedHashMap<>();
        info.variableStats().forEach(pair -> {
            var st = pair.a();
            var r = pair.b();
            String unit = st.getUnit().getDisplayName();
            ids.put(st.getApiName(), new io.github.anilken.wynnlabs.crafting.Ingredient.Stat(
                    Math.min(r.low(), r.high()), Math.max(r.low(), r.high()), st.getDisplayName(), unit));
        });
        int[] pos = new int[6];
        info.positionModifiers().forEach((p, v) -> {
            int at = switch (p) {
                case LEFT -> io.github.anilken.wynnlabs.crafting.Ingredient.LEFT;
                case RIGHT -> io.github.anilken.wynnlabs.crafting.Ingredient.RIGHT;
                case ABOVE -> io.github.anilken.wynnlabs.crafting.Ingredient.ABOVE;
                case UNDER -> io.github.anilken.wynnlabs.crafting.Ingredient.UNDER;
                case TOUCHING -> io.github.anilken.wynnlabs.crafting.Ingredient.TOUCHING;
                case NOT_TOUCHING -> io.github.anilken.wynnlabs.crafting.Ingredient.NOT_TOUCHING;
            };
            pos[at] = v;
        });
        int[] reqs = new int[5];
        info.skillRequirements().forEach(pair -> reqs[pair.a().ordinal()] = pair.b());
        return new io.github.anilken.wynnlabs.crafting.Ingredient(info.name(), info.tier(), info.level(),
                java.util.List.copyOf(profs), java.util.Collections.unmodifiableMap(ids), pos, info.durabilityModifier(),
                info.charges(), info.duration(), reqs);
    }

    static int professionLevel(String profession) {
        return professionLevel(ProfessionType.valueOf(profession));
    }

    private static int professionLevel(ProfessionType p) {
        return guard(Feature.PROFESSIONS, () -> Models.Profession.getLevel(p), 0);
    }

    /** A profession's XP into its level and the XP the level needs ({0, 0} when unknown). */
    static int[] professionXp(String profession) {
        return guard(Feature.PROFESSIONS, () -> {
            var xp = Models.Profession.getXP(ProfessionType.valueOf(profession));
            return new int[] {xp.current(), xp.max()};
        }, new int[2]);
    }

    // ServiceLocation.ServiceKind, MapLocation and MapDataService.getFeaturesForCategory(String):
    // 4.1.4 on. Before that the map has no stations to read: the list is empty and WAYPOINTS
    // stays on (the compass pins do not need them).
    private static final String SERVICE_KIND = "com.wynntils.services.mapdata.features.builtin.ServiceLocation$ServiceKind";
    private static final String MAP_LOCATION = "com.wynntils.services.mapdata.features.type.MapLocation";
    private static final String FEATURES_FOR_CATEGORY = "getFeaturesForCategory";

    /** Every crafting station of a profession ("WOODWORKING") on Wynntils' map, as {x, y, z}. */
    static List<int[]> stations(String profession) {
        return guard(Feature.WAYPOINTS, () -> {
            Optional<Class<?>> kind = type(SERVICE_KIND);
            Optional<Class<?>> location = type(MAP_LOCATION);
            Optional<Method> features = method(com.wynntils.services.mapdata.MapDataService.class,
                    FEATURES_FOR_CATEGORY, String.class);
            if (kind.isEmpty() || location.isEmpty() || features.isEmpty()) {
                return List.<int[]>of();
            }
            Method valueOf = method(kind.get(), "valueOf", String.class).orElseThrow(() -> missing("ServiceKind.valueOf"));
            Method mapDataId = method(kind.get(), "getMapDataId").orElseThrow(() -> missing("ServiceKind.getMapDataId"));
            Method getLocation = method(location.get(), "getLocation").orElseThrow(() -> missing("MapLocation.getLocation"));
            String id = (String) call(mapDataId, call(valueOf, null, profession + "_STATION"));
            List<int[]> out = new ArrayList<>();
            ((Stream<?>) call(features.get(), com.wynntils.core.components.Services.MapData, "wynntils:service:" + id))
                    .forEach(f -> {
                        if (location.get().isInstance(f)) {
                            Location l = (Location) call(getLocation, f);
                            out.add(new int[] {l.x(), l.y(), l.z()});
                        }
                    });
            return out;
        }, List.of());
    }

    /** The name of the territory at this position, or null. */
    static String territoryAt(Position pos) {
        return guard(Feature.WORLD, () -> {
            TerritoryProfile t = Models.Territory.getTerritoryProfileForPosition(pos);
            return t == null ? null : t.getName();
        }, null);
    }

    // The Blacksmith's sell menu: BlacksmithSellContainer from 4.1.16. Until 4.1.15 the same menu
    // (same title) is BlacksmithContainer; repair got its own class when sell was renamed.
    private static final String BLACKSMITH_SELL = "com.wynntils.models.containers.containers.BlacksmithSellContainer";
    private static final String BLACKSMITH_OLD = "com.wynntils.models.containers.containers.BlacksmithContainer";

    /** The menu open now, or null when Wynntils does not know it. */
    /** The last container {@link #container} described, and what it said: one container, one answer. */
    private static Object lastContainer;
    private static MenuKind lastKind;

    static MenuKind container() {
        return guard(Feature.CONTAINERS, () -> {
            Container c = Models.Container.getCurrentContainer();
            if (c == null) {
                return null;
            }
            if (c == lastContainer) {
                return lastKind;
            }
            Class<?> blacksmith = type(BLACKSMITH_SELL).or(() -> type(BLACKSMITH_OLD))
                    .orElseThrow(() -> new NoClassDefFoundError(BLACKSMITH_SELL + " is not in this Wynntils"));
            PersonalStorageContainer ps = c instanceof PersonalStorageContainer p ? p : null;
            MenuKind kind = new MenuKind(c.getClass().getSimpleName(), c instanceof CharacterBankContainer,
                    c instanceof AccountBankContainer, ps != null, blacksmith.isInstance(c),
                    c instanceof TradeMarketSellContainer, ps != null ? List.copyOf(ps.getBounds().getSlots()) : List.of(),
                    ps != null ? ps.getPreviousItemSlot() : -1, ps != null ? ps.getNextItemSlot() : -1);
            lastKind = kind;
            lastContainer = c;
            return kind;
        }, null);
    }

    // ---- raids, lootruns and aspects (the Endgame tab) ----
    //
    // RaidModel's four dry counters: every release since 4.1.0. Each is looked up by name, so a
    // release that drops one shows that counter as unknown and keeps the others; only when all
    // four are gone is raid tracking off. trackRaids (a public Config field): 4.2.12 on; before
    // that Wynntils always tracks raids.
    private static final String[] RAID_DRY = {"getRaidsWithoutMythicAspect", "getAspectPullsWithoutMythicAspect",
            "getRaidsWithoutMythicTome", "getRewardPullsWithoutMythicTome"};
    private static final String TRACK_RAIDS = "trackRaids";
    private static final String CONFIG_GET = "get";
    private static final int LOOTRUN_MISSIONS = 4;
    private static final int ASPECT_SLOTS = 5;
    private static volatile Optional<java.lang.reflect.Field> trackRaidsField;

    /** Wynntils' raid dry counters (whole account); {@link RaidDry#UNKNOWN} when unavailable. */
    static RaidDry raidDry() {
        return guard(Feature.RAIDS, () -> {
            int[] v = new int[RAID_DRY.length];
            boolean any = false;
            for (int i = 0; i < RAID_DRY.length; i++) {
                Optional<Method> m = method(RaidModel.class, RAID_DRY[i]);
                v[i] = m.isPresent() ? ((Number) call(m.get(), Models.Raid)).intValue() : -1;
                any |= m.isPresent();
            }
            if (!any) {
                throw missing("RaidModel." + RAID_DRY[0]);
            }
            return new RaidDry(v[0], v[1], v[2], v[3]);
        }, RaidDry.UNKNOWN);
    }

    /** Wynntils' best time for a raid (full name or abbreviation), milliseconds; -1 when none or unknown. */
    static long raidBestTime(String raid) {
        if (raid == null || raid.isBlank()) {
            return -1L;
        }
        return guard(Feature.RAIDS, () -> Models.Raid.getRaidBestTime(raid), -1L);
    }

    /**
     * Whether Wynntils' own raid tracking ("trackRaids") is on. True when this release has no
     * such switch, and when it cannot be read (the feature's self-test says whether raids work).
     */
    static boolean raidTrackingOn() {
        return guard(Feature.RAIDS, () -> {
            Optional<java.lang.reflect.Field> field = trackRaidsField;
            if (field == null) {
                try {
                    field = Optional.of(RaidModel.class.getField(TRACK_RAIDS));
                } catch (NoSuchFieldException e) {
                    field = Optional.empty();
                }
                trackRaidsField = field;
            }
            if (field.isEmpty()) {
                return true;
            }
            Object config;
            try {
                config = field.get().get(Models.Raid);
            } catch (IllegalAccessException e) {
                throw new IncompatibleClassChangeError("RaidModel." + TRACK_RAIDS + ": " + e);
            }
            return config == null || !Boolean.FALSE.equals(call(config, CONFIG_GET));
        }, true);
    }

    /** Wynntils' lootrun state; UNKNOWN when it cannot be read. */
    static LootrunRun.State lootrunState() {
        return guard(Feature.LOOTRUNS, () -> {
            LootrunningState s = Models.Lootrun.getState();
            if (s == null) {
                return LootrunRun.State.UNKNOWN;
            }
            // by name: a state a later release adds counts as running or idle, never fails
            return switch (s.name()) {
                case "NOT_RUNNING" -> LootrunRun.State.IDLE;
                case "CHOOSING_BEACON" -> LootrunRun.State.CHOOSING_BEACON;
                case "IN_TASK" -> LootrunRun.State.IN_TASK;
                default -> s.isRunning() ? LootrunRun.State.IN_TASK : LootrunRun.State.IDLE;
            };
        }, LootrunRun.State.UNKNOWN);
    }

    /**
     * The missions of the lootrun going on, by their English names ("Hoarder"), in order; the
     * slots Wynntils does not know yet are left out. Empty when none or unavailable.
     */
    static List<String> lootrunMissions() {
        return guard(Feature.LOOTRUNS, () -> {
            String unknown = MissionType.UNKNOWN.getName();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < LOOTRUN_MISSIONS; i++) {
                String m = plain(Models.Lootrun.getMissionStatus(i, false));
                if (!m.isEmpty() && !m.equalsIgnoreCase(unknown)) {
                    out.add(m);
                }
            }
            return out;
        }, List.of());
    }

    /** Wynntils' own lootrun pulls without a mythic (whole account); -1 when unavailable. */
    static int lootrunDryPulls() {
        return guard(Feature.LOOTRUNS, () -> {
            Integer v = Models.Lootrun.dryPulls.get();
            return v == null ? -1 : v;
        }, -1);
    }

    /**
     * Every aspect of a class from Wynntils' downloaded data, in its order, with rarity, tier
     * count and thresholds; ability names are left empty (the aspect book adds them). Empty for
     * NONE, while the data is not downloaded, or when unavailable.
     */
    static List<AspectEntry> classAspects(Klass cls) {
        if (cls == null || cls == Klass.NONE) {
            return List.of();
        }
        return guard(Feature.ASPECTS, () -> {
            String c = cls.name();
            List<AspectEntry> out = new ArrayList<>();
            Models.Aspect.getAllAspectInfos().forEach(a -> {
                if (a.classType() == null || !a.classType().name().equals(c)) {
                    return;
                }
                List<Integer> thresholds = new ArrayList<>();
                int tiers = a.effects() == null ? 0 : a.effects().size();
                if (a.effects() != null) {
                    for (var e : a.effects()) {
                        thresholds.add(e.a() == null ? 0 : e.a());
                    }
                }
                Tier rarity = a.gearTier() == null ? Tier.NORMAL : Tier.of(a.gearTier().name());
                out.add(new AspectEntry(plain(a.name()), cls, rarity, tiers, thresholds, List.of()));
            });
            return out;
        }, List.of());
    }

    /**
     * The tier of an aspect the player owns (1 on), as Wynntils last read it from the Aspects
     * menu or a raid reward chest; 0 when Wynntils lists it as not owned; -1 when unavailable.
     * Owned aspects are kept per Minecraft account, not per character.
     */
    static int aspectTier(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        return guard(Feature.ASPECTS, () -> Models.Aspect.getAspectTierByName(name).orElse(0), -1);
    }

    /**
     * The aspects this character has equipped (up to five), without formatting, as Wynntils
     * stores them: a name may carry a prefix, so match it with {@code endsWith}. Empty when
     * none is known or unavailable.
     */
    static List<String> equippedAspects() {
        return guard(Feature.ASPECTS, () -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < ASPECT_SLOTS; i++) {
                Models.Aspect.getEquippedAspect(i).map(Wyn::plain).filter(s -> !s.isEmpty()).ifPresent(out::add);
            }
            return out;
        }, List.of());
    }

    /**
     * The aspects in the reward slots of the raid reward preview menu, when the player has it
     * open and its items have loaded; empty otherwise. Only reads the open menu.
     */
    static List<PoolAspect> poolAspects() {
        return guard(Feature.ASPECTS, () -> {
            if (!(Models.Container.getCurrentContainer() instanceof RaidRewardPreviewContainer preview)
                    || !(net.minecraft.client.Minecraft.getInstance().screen
                            instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)) {
                return List.<PoolAspect>of();
            }
            var slots = screen.getMenu().slots;
            List<PoolAspect> out = new ArrayList<>();
            for (int i : preview.getRewardBounds().getSlots()) {
                if (i < 0 || i >= slots.size()) {
                    continue;
                }
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                Models.Item.asWynnItem(stack, AspectItem.class).ifPresent(a -> out.add(new PoolAspect(plain(a.getName()),
                        a.getRequiredClass() == null ? Klass.NONE : WynntilsItems.klass(a.getRequiredClass().name()),
                        a.getGearTier() == null ? Tier.NORMAL : Tier.of(a.getGearTier().name()), Math.max(1, a.getTier()))));
            }
            return out;
        }, List.of());
    }

    /** Text without formatting codes, trimmed; "" for null. */
    private static String plain(String s) {
        if (s == null) {
            return "";
        }
        String p = ChatFormatting.stripFormatting(s);
        return p == null ? "" : p.trim();
    }

    // ---- waypoints, notifications, events ----

    /** Wynntils' user waypoints (map, compass, beacon); null when unavailable. */
    static Pins waypoints() {
        return guard(Feature.WAYPOINTS, () -> {
            UserWaypointMarkerProvider p = MarkerModel.USER_WAYPOINTS_PROVIDER;
            return p == null ? null : new Pins(p);
        }, null);
    }

    /** The waypoint list: its pins, and adding or removing one by position. */
    static final class Pins {
        private final UserWaypointMarkerProvider provider;

        private Pins(UserWaypointMarkerProvider provider) {
            this.provider = provider;
        }

        List<Pin> all() {
            List<Pin> out = new ArrayList<>();
            provider.getMarkerInfos().forEach(m -> {
                Location l = m.location();
                // every user waypoint is named "Waypoint"; the label we gave is its additional text
                // (null when Wynntils' "show additional text in world" is off)
                String label = m.additionalText() != null ? m.additionalText() : m.name();
                out.add(new Pin(label, l.x(), l.y(), l.z()));
            });
            return out;
        }

        void remove(Pin p) {
            provider.removeLocation(new Location(p.x(), p.y(), p.z()));
        }

        void add(int x, int y, int z, String label) {
            provider.addLocation(new Location(x, y, z), label);
        }
    }

    /** A Wynntils toast; false when unavailable (the caller may use chat instead). */
    static boolean toast(Component c) {
        return guard(Feature.NOTIFY, () -> {
            Managers.Notification.queueMessage(c);
            return true;
        }, false);
    }

    static boolean listen(Object listener) {
        return listen(Feature.EVENTS, listener);
    }

    /** Registers a listener on Wynntils' bus; when its events are gone, {@code f} is off. */
    static boolean listen(Feature f, Object listener) {
        return guard(f, () -> {
            com.wynntils.core.WynntilsMod.registerEventListener(listener);
            return true;
        }, false);
    }
}
