package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.quests.QuestBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

/** The quest tile's small compass: a round dial with a pixel arrow to the quest's start. */
final class HomeDial {

    private HomeDial() {}

    /**
     * A round dial with an arrow from where you look to the quest's start (up: straight ahead);
     * false and nothing drawn when its place or yours is unknown.
     */
    static boolean draw(GuiGraphics g, QuestBook.Entry e, int x, int y, int size) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null || e.x() == null || e.z() == null) {
            return false;
        }
        Art.box(g, x, y, size, size, 4, Theme.WELL, Theme.LINE);
        // the bearing in the game's yaw (0 south, 90 west) less where you face, clockwise on screen,
        // to the nearest of eight directions
        double bearing = Math.toDegrees(Math.atan2(-(e.x() - me.getX()), e.z() - me.getZ()));
        int dir = Math.floorMod((int) Math.round((bearing - me.getYRot()) / 45.0), 8);
        String[] art = dir % 2 == 0 ? ARROW_N : ARROW_NE;
        int px = (size - 8) / 7;
        int ox = x + (size - 7 * px) / 2;
        int oy = y + (size - 7 * px) / 2;
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                if (art[r].charAt(c) != '#') {
                    continue;
                }
                // a quarter turn clockwise per two steps: (row, col) → (col, 6 - row)
                int rr = r;
                int cc = c;
                for (int t = 0; t < dir / 2; t++) {
                    int n = cc;
                    cc = 6 - rr;
                    rr = n;
                }
                g.fill(ox + cc * px, oy + rr * px, ox + (cc + 1) * px, oy + (rr + 1) * px, Theme.LINK);
            }
        }
        return true;
    }

    /** The dial's arrows as 7×7 pixel art, straight up and up-right; the other six are these turned. */
    private static final String[] ARROW_N = {
        "...#...",
        "..###..",
        ".#.#.#.",
        "#..#..#",
        "...#...",
        "...#...",
        "...#..."};
    private static final String[] ARROW_NE = {
        "..#####",
        ".....##",
        "....#.#",
        "...#..#",
        "..#....",
        ".#.....",
        "#......"};
}
