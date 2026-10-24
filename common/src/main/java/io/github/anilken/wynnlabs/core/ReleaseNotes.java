package io.github.anilken.wynnlabs.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A release's markdown notes as plain lines for the update screen: headings bold, "* " items as
 * bullets, links reduced to their text, the "(#66)" pull request and commit hash links dropped.
 */
public final class ReleaseNotes {

    /** One line: its text, whether it is a heading, whether it is a list item. */
    public record Line(String text, boolean heading, boolean bullet) {}

    /** " ([#66](…))" and " ([8ee7330](…))": the pull request and the commit, of no use in game. */
    private static final Pattern REFS = Pattern.compile("\\s*\\(\\[(#\\d+|[0-9a-f]{7,40})]\\([^)]*\\)\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");

    private ReleaseNotes() {}

    public static List<Line> lines(String markdown) {
        List<Line> out = new ArrayList<>();
        if (markdown == null) {
            return out;
        }
        for (String raw : markdown.replace("\r", "").split("\n")) {
            String s = raw.strip();
            if (s.isEmpty()) {
                continue;
            }
            boolean heading = s.startsWith("#");
            boolean bullet = !heading && (s.startsWith("* ") || s.startsWith("- "));
            if (heading) {
                s = s.replaceFirst("^#+\\s*", "");
            } else if (bullet) {
                s = s.substring(2);
            }
            s = REFS.matcher(s).replaceAll("");
            s = LINK.matcher(s).replaceAll("$1");
            s = s.replace("**", "").replace("__", "").replace("`", "").strip();
            if (!s.isEmpty()) {
                out.add(new Line(s, heading, bullet));
            }
        }
        return out;
    }
}
