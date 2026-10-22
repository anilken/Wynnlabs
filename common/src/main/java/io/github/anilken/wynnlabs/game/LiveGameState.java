package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.coach.XpMeter;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.quests.DiscoveryBook;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** {@link GameState} from the running game: the same calls the logic made before it had a seam. */
final class LiveGameState implements GameState {

    static final LiveGameState INSTANCE = new LiveGameState();

    /** While a fixture is recorded: every price the logic asked for, by name (null: unknown). */
    static volatile Map<String, Prices.Price> askedPrices;

    private LiveGameState() {}

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    @Override
    public boolean running() {
        return Minecraft.getInstance() != null;
    }

    @Override
    public boolean inGame() {
        return player() != null;
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public Klass classType() {
        return Wyn.classType();
    }

    @Override
    public int level() {
        return Wyn.level();
    }

    @Override
    public String characterId() {
        return Profile.id();
    }

    @Override
    public JsonObject characters() {
        return Profile.list();
    }

    @Override
    public List<String> characterIds() {
        List<String> out = new ArrayList<>();
        try (var dirs = Files.list(Dirs.config().resolve("characters"))) {
            dirs.forEach(d -> out.add(d.getFileName().toString()));
        } catch (Exception ignored) {
            // no characters yet
        }
        return out;
    }

    @Override
    public JsonObject characterFile(String id, String file) {
        Path f = Dirs.config().resolve("characters").resolve(id).resolve(file);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int assigned(SkillKind s) {
        return MenuWatcher.assigned(s);
    }

    @Override
    public int assignedSeen(SkillKind s) {
        synchronized (MenuWatcher.ASSIGNED) {
            return MenuWatcher.ASSIGNED.getOrDefault(s, Wyn.assignedPoints(s));
        }
    }

    @Override
    public boolean skillsKnown() {
        return MenuWatcher.skillsKnown();
    }

    @Override
    public int unspent() {
        return MenuWatcher.unspent;
    }

    @Override
    public int gearPoints(SkillKind s) {
        return Wyn.gearPoints(s);
    }

    @Override
    public int tomePoints(SkillKind s) {
        return Wyn.tomePoints(s);
    }

    @Override
    public int setBonusPoints(SkillKind s) {
        return Wyn.setBonusPoints(s);
    }

    @Override
    public int professionLevel(String profession) {
        return Wyn.professionLevel(profession);
    }

    @Override
    public List<QuestBook.Entry> quests() {
        return QuestBook.all();
    }

    @Override
    public java.util.Set<String> completedDiscoveries() {
        // Wynntils' list fills only after its own content-book scan; the book the player opened counts too
        java.util.Set<String> out = new java.util.HashSet<>(Wyn.completedDiscoveries());
        out.addAll(DiscoveryBook.found());
        return out;
    }

    @Override
    public Map<String, Integer> abilityPlan() {
        return Advice.abilityPlan();
    }

    @Override
    public List<io.github.anilken.wynnlabs.model.AbilityNode> abilityTree() {
        return Wyn.abilityTree(Wyn.classType());
    }

    @Override
    public List<String> unlockedAbilities() {
        return Wyn.unlockedAbilities();
    }

    @Override
    public Optional<ItemView> view(ItemStack stack) {
        return WynntilsItems.view(stack);
    }

    @Override
    public List<ItemStack> inventory() {
        LocalPlayer p = player();
        if (p == null) {
            return List.of();
        }
        var inv = p.getInventory();
        List<ItemStack> out = new ArrayList<>(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            out.add(inv.getItem(i));
        }
        return out;
    }

    @Override
    public ItemStack armour(EquipmentSlot slot) {
        LocalPlayer p = player();
        return p == null ? ItemStack.EMPTY : WornGear.armour(p, slot);
    }

    @Override
    public List<ItemStack> accessories() {
        LocalPlayer p = player();
        return p == null ? List.of() : Wyn.accessories(p);
    }

    @Override
    public ItemStack mainHand() {
        LocalPlayer p = player();
        return p == null ? ItemStack.EMPTY : p.getMainHandItem();
    }

    @Override
    public int selectedSlot() {
        LocalPlayer p = player();
        return p == null ? 0 : p.getInventory().getSelectedSlot();
    }

    @Override
    public Stream<ItemData> allGear() {
        return WynntilsItems.allGear();
    }

    @Override
    public List<ItemData> possibleGears(ItemView.Box box) {
        return WynntilsItems.possibleGears(box);
    }

    @Override
    public List<Obtain> obtain(ItemData data) {
        return WynntilsItems.obtain(data);
    }

    @Override
    public Prices.Price price(String name) {
        Prices.Price p = Prices.of(name);
        Map<String, Prices.Price> asked = askedPrices;
        if (asked != null && name != null) {
            asked.put(name, p);
        }
        return p;
    }

    @Override
    public void requestPrices(Collection<String> names) {
        Prices.request(names);
    }

    @Override
    public boolean pricesInstalled() {
        return Prices.installed();
    }

    @Override
    public double[] position() {
        LocalPlayer p = player();
        return p == null ? new double[] {0, 0} : new double[] {p.getX(), p.getZ()};
    }

    @Override
    public List<Grind.Spot> spots() {
        return Grind.spots();
    }

    @Override
    public double xpRate(String spot) {
        return XpMeter.rate(spot);
    }
}
