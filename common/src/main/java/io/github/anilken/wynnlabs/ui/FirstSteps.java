package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.MenuWatcher;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The menus Wynn Labs needs opened once per character before its advice is whole: Character
 * Info (skill points), the content book (quests), the ability tree and the bank pages. A step
 * counts once the mod has read that menu; a step seen done is kept in the character's
 * {@code first-steps.txt}, so a menu Wynntils forgets between sessions stays ticked, and once
 * all four are done the home's card is gone for good. Only reads; it opens nothing.
 */
final class FirstSteps {

    /** The steps in the card's order, as written in the file. */
    static final List<String> STEPS = List.of("character", "book", "tree", "bank");
    /** Each step's line on the card, in the order of {@link #STEPS}. */
    static final List<String> KEYS = List.of("wynnlabs.steps.character", "wynnlabs.steps.book", "wynnlabs.steps.tree",
            "wynnlabs.steps.bank");

    /** The preview: these steps done (in the order of {@link #STEPS}) instead of the real ones; never set in the game. */
    static volatile boolean[] preview;

    private static String readFor = "";
    private static Set<String> saved = Set.of();
    private static boolean[] done = new boolean[STEPS.size()];
    private static long at;

    private FirstSteps() {}

    /** Which steps are done, in the order of {@link #STEPS}; read again at most once a second. */
    static boolean[] done() {
        boolean[] p = preview;
        if (p != null) {
            return p;
        }
        long now = System.currentTimeMillis();
        if (now - at < 1000) {
            return done;
        }
        at = now;
        String id = Profile.id();
        if (!id.equals(readFor)) {
            readFor = id;
            saved = id.isEmpty() ? Set.of() : read(file());
        }
        Set<String> all = merge(saved, live());
        if (!id.isEmpty() && !all.equals(saved)) {
            write(file(), all);
            saved = all;
        }
        boolean[] out = new boolean[STEPS.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = all.contains(STEPS.get(i));
        }
        done = out;
        return out;
    }

    /** How many steps are done. */
    static int count(boolean[] done) {
        int n = 0;
        for (boolean b : done) {
            n += b ? 1 : 0;
        }
        return n;
    }

    /** True when the card should show: a character is known and a step is still open. */
    static boolean show() {
        return (preview != null || !Profile.id().isEmpty()) && count(done()) < STEPS.size();
    }

    /** The steps saved, with those done now added; the saved set itself when nothing is new. */
    static Set<String> merge(Set<String> saved, boolean[] live) {
        Set<String> out = new LinkedHashSet<>(saved);
        for (int i = 0; i < live.length && i < STEPS.size(); i++) {
            if (live[i]) {
                out.add(STEPS.get(i));
            }
        }
        return out.equals(saved) ? saved : out;
    }

    /** What the mod knows right now: skill points read, quests in the book, abilities read, bank pages read. */
    private static boolean[] live() {
        boolean[] out = new boolean[STEPS.size()];
        try {
            out[0] = MenuWatcher.skillsKnown();
            out[1] = QuestAdvisor.known() > 0;
            List<String> unlocked = GameState.current().unlockedAbilities();
            out[2] = unlocked != null && !unlocked.isEmpty();
            out[3] = Vault.count() > 0;
        } catch (Throwable ignored) {
            // unknown counts as not done yet
        }
        return out;
    }

    private static Path file() {
        return Profile.dir().resolve("first-steps.txt");
    }

    /** The steps a file lists, one per line; none when it is missing or unreadable. */
    static Set<String> read(Path f) {
        try {
            if (Files.exists(f)) {
                Set<String> out = new LinkedHashSet<>();
                for (String l : Files.readAllLines(f)) {
                    if (STEPS.contains(l.trim())) {
                        out.add(l.trim());
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // read again next time
        }
        return Set.of();
    }

    static void write(Path f, Set<String> steps) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, String.join("\n", steps) + "\n");
        } catch (Exception ignored) {
            // a hint only: asked again next time
        }
    }
}
