package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.market.Prices;

import java.util.List;

/**
 * One way to get an item, for the card's "how do I get it" list. Language-neutral: the UI turns
 * {@link #type} into words; {@link #name} is a proper name (mob, place, quest, bank page) in English.
 *
 * @param areas    where to go, nearest first once ranked (empty when there is no place)
 * @param effort   how hard it is (lower is easier), from {@link SourceRank#effort}
 * @param badge    the icon name under {@code textures/gui/sprites/icon/} ("coin", "shopping-cart")
 * @param count    how many the player has (bag and bank rows); 0 otherwise
 * @param price    the Trade Market price (market row only), else null
 * @param minLevel the mob level band of a normal drop or loot chest row ("Lv 73–81"); 0 otherwise
 * @param maxLevel see minLevel
 * @param cost     what a merchant asks ("4 Antikythera chips"), filled from the wiki; null when unknown
 */
public record Source(Type type, String name, List<Area> areas, int effort, String badge, int count,
        Prices.Price price, int minLevel, int maxLevel, String cost) {

    /** The first (nearest) area, or null when the row has no place. */
    public Area main() {
        return areas.isEmpty() ? null : areas.get(0);
    }

    public boolean hasPlace() {
        return !areas.isEmpty();
    }

    /** The same row with a merchant cost (from the wiki). */
    public Source withCost(String c) {
        return new Source(type, name, areas, effort, badge, count, price, minLevel, maxLevel, c);
    }

    /**
     * What kind of source a row is. {@code obtain} is the matching Wynntils obtain type name
     * ("DUNGEON_MERCHANT"), which the home page's badges go by; null when it has none.
     */
    public enum Type {
        BAG(null), BANK(null), MARKET("TRADE_MARKET"),
        MERCHANT("MERCHANT"), DUNGEON_MERCHANT("DUNGEON_MERCHANT"), TINKERING(null),
        MOB("SPECIFIC_MOB_DROP"), MINIBOSS("MINIBOSS"), ALTAR("BOSS_ALTAR"), CAVE("CAVE_COMPLETION"),
        GATHERING(null), DUNGEON("DUNGEON"), QUEST("QUEST"), CHALLENGE("CHALLENGE"), LOOTRUN(null),
        EVENT(null), WORLD_EVENT("WORLD_EVENT"), GUILD(null), FORGERY_CHEST(null), RAID("RAID"),
        MOB_BAND("NORMAL_MOB_DROP"), LOOT_CHEST("LOOT_CHEST"), OTHER(null), UNAVAILABLE(null), UNKNOWN(null);

        public final String obtain;

        Type(String obtain) {
            this.obtain = obtain;
        }

        /** The type for a word of the cache files ("dungeonMerchant", "worldEvent"); OTHER when unknown. */
        public static Type of(String word) {
            if (word == null) {
                return OTHER;
            }
            return switch (word) {
                case "merchant" -> MERCHANT;
                case "dungeonMerchant" -> DUNGEON_MERCHANT;
                case "tinkering" -> TINKERING;
                case "mob", "specificMobDrop", "mobDropRegion" -> MOB;
                case "miniboss" -> MINIBOSS;
                case "altar", "bossAltar" -> ALTAR;
                case "caveCompletion", "cave" -> CAVE;
                case "gathering" -> GATHERING;
                case "dungeon" -> DUNGEON;
                case "quest" -> QUEST;
                case "challenge" -> CHALLENGE;
                case "lootrun" -> LOOTRUN;
                case "event" -> EVENT;
                case "worldEvent" -> WORLD_EVENT;
                case "guild" -> GUILD;
                case "forgeryChest" -> FORGERY_CHEST;
                case "raid" -> RAID;
                case "normalMobDrop" -> MOB_BAND;
                case "lootChest" -> LOOT_CHEST;
                case "unavailable" -> UNAVAILABLE;
                default -> OTHER;
            };
        }
    }
}
