package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * {@link GameState} from a recorded fixture folder. Every recorded stack becomes a stand-in
 * stack (paper with the recorded name and count) that the state reads as the recorded
 * {@link ItemView}; a stack worn and carried is one stand-in in both places, as in game.
 */
final class FixtureState implements GameState {

    /** A bank slot: where it is ("bank char:char0002:… p. 3#6"), the recorded stack and its reading. */
    record BankItem(String where, String name, int count, ItemView view) {}

    final String name;
    final Fixture.Data data;
    private final List<ItemStack> stacks = new ArrayList<>();
    private final Map<ItemStack, ItemView> views = new IdentityHashMap<>();
    private final List<ItemStack> inventory = new ArrayList<>();
    private final Map<EquipmentSlot, ItemStack> armour = new HashMap<>();
    private final List<ItemStack> accessories = new ArrayList<>();
    private ItemStack mainHand;
    private final List<BankItem> bank = new ArrayList<>();
    private final Map<String, ItemData> gearByName = new HashMap<>();
    /** Prices the logic asked for that the recording does not have. */
    final Set<String> missingPrices = new TreeSet<>();

    private FixtureState(String name, Fixture.Data data) {
        this.name = name;
        this.data = data;
        data.gearDb().gear().forEach(g -> gearByName.putIfAbsent(g.name(), g));
        for (Fixture.Held h : data.items().stacks()) {
            ItemStack s = new ItemStack(Items.PAPER, Math.max(1, h.count()));
            s.set(DataComponents.CUSTOM_NAME, Component.literal(h.name()));
            stacks.add(s);
            if (h.view() != null) {
                views.put(s, same(h.view()));
            }
        }
        data.items().inventory().forEach(i -> inventory.add(stack(i)));
        for (EquipmentSlot slot : Fixture.ARMOUR) {
            armour.put(slot, stack(data.items().armour().get(slot.name())));
        }
        data.items().accessories().forEach(i -> accessories.add(stack(i)));
        mainHand = stack(data.items().mainHand());
        for (Fixture.BankSlot b : data.items().bank()) {
            bank.add(new BankItem("bank " + b.page() + "#" + b.slot(), b.name(), b.count(),
                    b.view() == null ? null : same(b.view())));
        }
    }

    static FixtureState load(Path dir) throws IOException {
        TestGame.boot();
        return new FixtureState(dir.getFileName().toString(), Fixture.read(dir));
    }

    private ItemStack stack(Integer ref) {
        return ref == null ? ItemStack.EMPTY : stacks.get(ref);
    }

    /** The view with its gear data as the database's own object, like WynntilsItems hands it out. */
    private ItemView same(ItemView v) {
        ItemData db = v.data() == null ? null : gearByName.get(v.data().name());
        if (db == null || db == v.data() || !db.equals(v.data())) {
            return v;
        }
        return new ItemView(v.kind(), v.type(), v.plain(), v.name(), v.level(), v.professions(), db, v.rolled(),
                v.crafted(), v.box(), v.qualityTier(), v.powder(), v.potion(), v.dungeonKey(), v.skillPoint(),
                v.crystalCount(), v.abilityName());
    }

    /**
     * Makes this the game for the logic, in English ("en") or Turkish ("tr"): settings and the
     * character's rules from the fixture, then the build plan as the game would have it.
     */
    void install(String language) {
        JsonObject settings = data.settings().deepCopy();
        settings.addProperty("langOverride", language.equals("tr") ? 2 : 1);
        Settings.use(settings);
        JsonObject saved = data.rules().get(data.state().characterId());
        JsonObject rules = saved == null ? new JsonObject() : saved.deepCopy();
        // the game searches wide (about 20 s a map); tests take the narrow search unless
        // -Dwynnlabs.fullBeam=true, or the suite would run for many minutes
        if (!Boolean.getBoolean("wynnlabs.fullBeam")) {
            JsonObject rm = rules.has("roadmap") ? rules.getAsJsonObject("roadmap") : new JsonObject();
            rm.addProperty("jointPool", 6);
            rm.addProperty("beamWidth", 12);
            rm.addProperty("beamFinal", 4);
            rm.addProperty("beamExact", 0);
            rm.addProperty("exactPool", 0);         // RoadmapEveryLevelTest turns it on
            rules.add("roadmap", rm);
        }
        Rules.use(rules);
        GameState.install(this);
        // the made-up tree has no pieces in the shipped map: none, or its lines would follow the real tree
        if (data.tree() == null) {
            TreeConnectors.use(data.state().klass(), java.util.Map.of());
        }
        Build.use(Build.compute());
    }

    static void uninstall() {
        GameState.install(null);
        for (io.github.anilken.wynnlabs.model.Klass k : io.github.anilken.wynnlabs.model.Klass.values()) {
            TreeConnectors.use(k, null);
        }
        Build.use(null);
        Settings.use(new JsonObject());
        Rules.use(new JsonObject());
    }

    /** The bag slots (0-35) that hold something, by index. */
    Map<Integer, ItemStack> bag() {
        Map<Integer, ItemStack> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 36 && i < inventory.size(); i++) {
            if (!inventory.get(i).isEmpty()) {
                out.put(i, inventory.get(i));
            }
        }
        return out;
    }

    List<BankItem> bank() {
        return bank;
    }

    String nameOf(ItemStack s) {
        return ItemJson.clean(s.getHoverName().getString());
    }

    // ---- GameState ----

    @Override
    public boolean running() {
        return false;
    }

    @Override
    public boolean inGame() {
        return data.state().inGame();
    }

    @Override
    public long now() {
        return data.state().recordedAt();
    }

    /** A class to play the recorded character as (the role tests try every class); null: the recorded one. */
    private Klass playAs;

    FixtureState as(Klass k) {
        playAs = k;
        return this;
    }

    /** A level the recorded character is played at instead (0: the recorded one). */
    private int levelAs;

    /** The recorded character at another level (0: the recorded one), everything else as recorded. */
    FixtureState atLevel(int level) {
        levelAs = level;
        return this;
    }

    /**
     * The recorded character as a new one: Lv 1, nothing worn, carried or banked (its rules
     * and the game's data stay), to see what the mod tells a player who just started.
     */
    FixtureState fresh() {
        levelAs = 1;
        inventory.clear();
        armour.clear();
        accessories.clear();
        mainHand = ItemStack.EMPTY;
        bank.clear();
        return this;
    }

    @Override
    public Klass classType() {
        return playAs != null ? playAs : data.state().klass();
    }

    @Override
    public int level() {
        return levelAs > 0 ? levelAs : data.state().level();
    }

    @Override
    public String characterId() {
        return data.state().characterId();
    }

    @Override
    public JsonObject characters() {
        return data.state().characters().deepCopy();
    }

    @Override
    public List<String> characterIds() {
        return data.state().characterIds();
    }

    @Override
    public JsonObject characterFile(String id, String file) {
        JsonObject o = switch (file) {
            case "rules.json" -> data.rules().get(id);
            case "today.json" -> data.state().today().get(id);
            default -> null;
        };
        return o == null ? null : o.deepCopy();
    }

    private Fixture.Skill skill(SkillKind s) {
        Fixture.Skill k = data.state().skills().get(s);
        return k == null ? new Fixture.Skill(0, 0, 0, 0, 0) : k;
    }

    @Override
    public int assigned(SkillKind s) {
        return skill(s).assigned();
    }

    @Override
    public int assignedSeen(SkillKind s) {
        return skill(s).seen();
    }

    @Override
    public boolean skillsKnown() {
        return data.state().skillsKnown();
    }

    @Override
    public int unspent() {
        return data.state().unspent();
    }

    @Override
    public int gearPoints(SkillKind s) {
        return skill(s).gear();
    }

    @Override
    public int tomePoints(SkillKind s) {
        return skill(s).tome();
    }

    @Override
    public int setBonusPoints(SkillKind s) {
        return skill(s).setBonus();
    }

    @Override
    public int professionLevel(String profession) {
        Integer lv = data.state().professions().get(profession);
        if (lv == null) {
            // what ProfessionType.valueOf does in game for a name it does not know
            throw new IllegalArgumentException("No profession " + profession);
        }
        return lv;
    }

    @Override
    public List<QuestBook.Entry> quests() {
        return new ArrayList<>(data.state().quests());
    }

    /** Content book discoveries found, for the preview (fixtures record none). */
    java.util.Set<String> discoveries = java.util.Set.of();

    @Override
    public java.util.Set<String> completedDiscoveries() {
        return discoveries;
    }

    /** The recorded bank as stacks, by page key ("char:char0001:Character Bank p. 1"), read like the bag's. */
    Map<String, List<ItemStack>> bankStacks() {
        Map<String, List<ItemStack>> out = new java.util.LinkedHashMap<>();
        for (Fixture.BankSlot b : data.items().bank()) {
            ItemStack s = new ItemStack(Items.PAPER, Math.max(1, b.count()));
            s.set(DataComponents.CUSTOM_NAME, Component.literal(b.name()));
            if (b.view() != null) {
                views.put(s, same(b.view()));
            }
            out.computeIfAbsent(b.page(), k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    @Override
    public Map<String, Integer> abilityPlan() {
        return new java.util.TreeMap<>(data.state().abilityPlan());
    }

    /** The recorded tree, or the made-up one for a fixture recorded before trees were kept. */
    @Override
    public List<io.github.anilken.wynnlabs.model.AbilityNode> abilityTree() {
        return data.tree() != null ? data.tree().nodes() : SyntheticTree.nodes();
    }

    @Override
    public List<String> unlockedAbilities() {
        return data.tree() != null ? data.tree().unlocked() : SyntheticTree.unlocked();
    }

    @Override
    public Optional<ItemView> view(ItemStack stack) {
        return Optional.ofNullable(stack == null ? null : views.get(stack));
    }

    @Override
    public List<ItemStack> inventory() {
        return inventory;
    }

    @Override
    public ItemStack armour(EquipmentSlot slot) {
        return armour.getOrDefault(slot, ItemStack.EMPTY);
    }

    @Override
    public List<ItemStack> accessories() {
        return accessories;
    }

    @Override
    public ItemStack mainHand() {
        return mainHand;
    }

    @Override
    public int selectedSlot() {
        return data.state().selectedSlot();
    }

    @Override
    public Stream<ItemData> allGear() {
        return data.gearDb().gear().stream();
    }

    @Override
    public List<ItemData> possibleGears(ItemView.Box box) {
        List<String> names = data.gearDb().boxes().get(Fixture.boxKey(box));
        if (names == null) {
            return List.of();
        }
        List<ItemData> out = new ArrayList<>();
        names.forEach(n -> {
            ItemData g = gearByName.get(n);
            if (g != null) {
                out.add(g);
            }
        });
        return out;
    }

    @Override
    public List<Obtain> obtain(ItemData d) {
        return data.gearDb().obtain().getOrDefault(d.name(), List.of());
    }

    @Override
    public Prices.Price price(String name) {
        if (name == null) {
            return null;
        }
        if (!data.prices().containsKey(name)) {
            missingPrices.add(name);
        }
        return data.prices().get(name);
    }

    @Override
    public void requestPrices(Collection<String> names) {
        // no network in tests
    }

    @Override
    public boolean pricesInstalled() {
        return data.state().pricesInstalled();
    }

    @Override
    public double[] position() {
        double[] p = data.state().position();
        return p == null || p.length < 2 ? new double[] {0, 0} : p.clone();
    }

    @Override
    public List<Grind.Spot> spots() {
        return data.state().spots();
    }

    @Override
    public double xpRate(String spot) {
        return data.state().xpRates().getOrDefault(spot, -1.0);
    }
}
