package io.github.anilken.wynnlabs.search;

/**
 * A place in the world: a mob's spawn area, a merchant, a cave, a town, a gathering node or a
 * loot chest. {@code radius} is 0 for a point; {@code level} is -1 when unknown; {@code stars} is
 * a loot chest's tier (1–4, 0 when not recorded) and 0 for everything else; {@code name} is the
 * place's own name (English, as Wynntils has it) or null.
 */
public record Area(int x, int y, int z, int radius, int level, int stars, String name) {

    public static Area point(int x, int y, int z) {
        return new Area(x, y, z, 0, -1, 0, null);
    }
}
