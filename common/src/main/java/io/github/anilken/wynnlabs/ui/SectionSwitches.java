package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;

import java.util.List;
import java.util.Set;

/**
 * The "All off / All on" button on a settings section's title: every switch of the section in
 * one click. Only where silencing a group is a fair thing to do; the look, the rules and the
 * online data each want a choice of their own.
 */
final class SectionSwitches {

    /** The categories whose sections get the button. */
    private static final Set<String> CATS = Set.of("tips", "glow", "coach", "quests", "build");

    private SectionSwitches() {
    }

    /** The section's switches when they get the button: two or more in a category above; else empty. */
    static List<String> keys(String cat, List<String> switches) {
        return CATS.contains(cat) && switches.size() >= 2 ? switches : List.of();
    }

    /** Whether any of them is on: the button then turns them all off. */
    static boolean anyOn(List<String> keys) {
        for (String k : keys) {
            if (Settings.on(k)) {
                return true;
            }
        }
        return false;
    }

    static String label(boolean anyOn) {
        return L.t(anyOn ? "wynnlabs.settings.section_all_off" : "wynnlabs.settings.section_all_on");
    }

    static String tip(boolean anyOn, String section) {
        return L.t(anyOn ? "wynnlabs.settings.section_all_off_tip" : "wynnlabs.settings.section_all_on_tip", section);
    }

    /** All of them off when any is on, else all on; each saved as a click on it would. */
    static void flip(List<String> keys) {
        boolean to = !anyOn(keys);
        for (String k : keys) {
            Settings.set(k, to);
        }
    }
}
