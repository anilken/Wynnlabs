package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The hand-written checks that replaced per-call regexes answer exactly like the regexes. */
class NoRegexTest {

    private static final List<String> NAMES = List.of("", "T1", " T1", "a T1", "Runner's Bandages T1", "Runner's Bandages T",
            "Runner's Bandages T12", "Runner's BandagesT1", "Runner's Bandages t1", "x T٣", "Gold Ingot", "a  T9", "a\tT1");

    @Test
    void tierCheckMatchesTheRegex() {
        for (String n : NAMES) {
            assertEquals(n.matches(".* T\\d$"), Prices.hasTier(n), n);
        }
    }
}
