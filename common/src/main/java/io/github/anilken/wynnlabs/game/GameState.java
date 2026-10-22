package io.github.anilken.wynnlabs.game;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.coach.XpMeter;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestBook;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Everything the advice, build, road map and shopping list read from the game, in one place.
 * In game that is {@link LiveGameState} (Wynntils, the player, the mod's own files); a test
 * installs a recorded fixture instead, so the same logic runs without the game.
 *
 * <p>Wyn and WynntilsItems stay the Wynntils bridge: this only decides who answers the logic.
 */
public interface GameState {

    /** The live game, unless a test has installed a fixture. */
    static GameState current() {
        GameState s = Installed.state;
        return s != null ? s : LiveGameState.INSTANCE;
    }

    /** Tests only: answer from this state from now on (null: the live game again). */
    static void install(GameState state) {
        Installed.state = state;
    }

    /** Holds the installed state: an interface cannot keep a field that changes. */
    final class Installed {
        private static volatile GameState state;

        private Installed() {}
    }

    /** True while the game itself runs; false in tests. */
    boolean running();

    /** A player is in a world. */
    boolean inGame();

    /** Now, in epoch milliseconds. */
    long now();

    // ---- character ----

    Klass classType();

    int level();

    /** The character's folder name, "" until one is known ({@link Profile#id}). */
    String characterId();

    /** Every character seen so far ({@link Profile#list}). */
    JsonObject characters();

    /** The names of the character folders (config/wynnlabs/characters/), in folder order. */
    List<String> characterIds();

    /** One file of a character's folder ("rules.json", "today.json"); null when it has none. */
    JsonObject characterFile(String id, String file);

    /** Assigned points as the skill advice reads them ({@link MenuWatcher#assigned}). */
    int assigned(SkillKind s);

    /** What the Character Info menu showed last for a skill, else Wynntils' number. */
    int assignedSeen(SkillKind s);

    boolean skillsKnown();

    /** Points not spent yet, -1 until the menu was seen. */
    int unspent();

    int gearPoints(SkillKind s);

    int tomePoints(SkillKind s);

    int setBonusPoints(SkillKind s);

    /** A profession's level by name ("COOKING"); an unknown name throws. */
    int professionLevel(String profession);

    List<QuestBook.Entry> quests();

    /** Secret discoveries Wynntils lists as found for this character; empty until it read the content book. */
    java.util.Set<String> completedDiscoveries();

    /** Ability → the level the plan takes it at ({@link Advice#abilityPlan}). */
    Map<String, Integer> abilityPlan();

    /** The class's ability tree, or null when it is not known yet. */
    List<io.github.anilken.wynnlabs.model.AbilityNode> abilityTree();

    /** The names of the nodes the character has unlocked. */
    List<String> unlockedAbilities();

    // ---- items ----

    Optional<ItemView> view(ItemStack stack);

    /** Every slot of the player's inventory, by index (36 bag slots first). */
    List<ItemStack> inventory();

    /** What the mod counts as worn in an armour slot ({@link AutoAdvice#armour}). */
    ItemStack armour(EquipmentSlot slot);

    /** Ring, ring, bracelet, necklace. */
    List<ItemStack> accessories();

    ItemStack mainHand();

    int selectedSlot();

    // ---- gear database ----

    Stream<ItemData> allGear();

    List<ItemData> possibleGears(ItemView.Box box);

    List<Obtain> obtain(ItemData data);

    // ---- prices ----

    Prices.Price price(String name);

    void requestPrices(Collection<String> names);

    boolean pricesInstalled();

    // ---- map ----

    /** The player's x and z. */
    double[] position();

    List<Grind.Spot> spots();

    /** XP per hour measured at a spot, -1 when unknown ({@link XpMeter#rate}). */
    double xpRate(String spot);
}
