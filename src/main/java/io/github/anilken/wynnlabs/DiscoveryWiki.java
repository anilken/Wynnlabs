package io.github.anilken.wynnlabs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A secret discovery's wiki page cut into access steps, by the same rules as the build-time
 * tool (tools/discoveries/fetch.py), so the steps' hash matches the one shipped in en.json while
 * the page is unchanged. Keep both in step: DiscoveryWikiTest compares them on every page the
 * tool fetched last.
 *
 * <p>A step is one line of prose in the Access section (or the sections named like it);
 * dialogue lines, quotes after "… the following dialogue:", tables, galleries and templates
 * spread over lines are left out. A dialogue block adds a "Choose …" step with its purple
 * options unless the step before it names them all. MapLink and RenderLocation give a place.
 */
final class DiscoveryWiki {

    /** {@code at} is {x, y, z}, y = {@link Integer#MIN_VALUE} when the page gives none; null without a place. */
    record Step(String text, int[] at) {}

    static final int NO_Y = Integer.MIN_VALUE;

    // UNIX_LINES: only "\n" ends a line, as in Python
    private static final int U = Pattern.UNIX_LINES;
    private static final String[][] ENTITIES = {{"&nbsp;", " "}, {"&quot;", "\""}, {"&#39;", "'"}, {"&ndash;", "\u2013"},
            {"&mdash;", "\u2014"}, {"&lt;", "<"}, {"&gt;", ">"}, {"&amp;", "&"}};
    private static final Pattern MAPLINK = Pattern.compile("\\{\\{MapLink(\\|[^{}]*)}}", U);
    private static final Pattern RENDER = Pattern.compile("\\{\\{RenderLocation([^{}]*)}}", U);
    private static final Pattern COLOUR = Pattern.compile("\\{\\{c\\|[^|{}]*\\|([^{}]*)}}", U);
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{[^{}]*}}", U);
    private static final Pattern FILE_LINK = Pattern.compile("\\[\\[(?:File|Image|file|image):[^\\[\\]]*]]", U);
    private static final Pattern LINK = Pattern.compile("\\[\\[(?:[^|\\[\\]]*\\|)?([^\\[\\]]*)]]", U);
    private static final Pattern EXTERNAL = Pattern.compile("\\[https?://[^ \\]]+ ([^\\]]*)]", U);
    private static final Pattern TAG = Pattern.compile("<[^<>]*>", U);
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", U | Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIPLE = Pattern.compile(
            "(?<![\\w.])(-?\\d{1,5})(?:\\s*,\\s*|\\s+)(-?\\d{1,5})(?:\\s*,\\s*|\\s+)(-?\\d{1,5})(?!\\w|\\.\\d)", U);
    private static final Pattern XZ = Pattern.compile("\\bX:\\s*(-?\\d{1,5}),?\\s*Z:\\s*(-?\\d{1,5})", U);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", U | Pattern.DOTALL);
    private static final Pattern REF = Pattern.compile("<ref[^<>]*?/>|<ref[^<>]*>.*?</ref>", U | Pattern.DOTALL);
    private static final Pattern GALLERY = Pattern.compile("<gallery.*?</gallery>", U | Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE = Pattern.compile("^\\{\\|.*?^\\|}", U | Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern CONDITIONAL = Pattern.compile("appears (?:when|if)|available (?:when|if)|is available",
            U | Pattern.CASE_INSENSITIVE);
    private static final Pattern CHOICE = Pattern.compile("^[*:#]+\\s*\\{\\{c\\|[^|{}]*purple[^|{}]*\\|", U);
    private static final Pattern SPEAKER = Pattern.compile("^[*:#]*\\s*(?:'''[^']*?:\\s*'''|'''[^']*'''\\s*:|\\{\\{Dialogue\\|)",
            U | Pattern.CASE_INSENSITIVE);
    private static final Pattern ITALIC_ONLY = Pattern.compile("^[*:#]*\\s*''[^'].*[^']''$", U);
    private static final Pattern BOLD_ONLY = Pattern.compile("^[*:#]*\\s*'''[^']*'''$", U);
    private static final Pattern HEADING = Pattern.compile("^(=+)\\s*(.*?)\\s*=+\\s*$", U);
    private static final Pattern SECTIONS = Pattern.compile(
            "^(?:access|the discovery|finding the city|unlocking the library|the four towers|stage \\d+)$", U | Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTE_INTRO = Pattern.compile("(?:dialogue|says?|message|reads?|text|lines?)\\b[^.]*:$",
            U | Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT = Pattern.compile("\\{\\{Input\\|([a-z]+)}}", U);
    private static final Map<String, String> CLICKS = Map.of("lmb", "Left-click", "rmb", "Right-click");
    private static final Pattern BULLETS = Pattern.compile("^[*#:;]+\\s*", U);
    private static final Pattern SPACES = Pattern.compile("\\s+", U);
    private static final Pattern NUMBER = Pattern.compile("^(-?\\d+)(?:\\.\\d+)?$", U);
    private static final Pattern DIGITS = Pattern.compile("-?\\d+", U);
    private static final String COND = " {{cond}}";

    private DiscoveryWiki() {}

    /** A template's parameters ("|x=1|y=2" or "|1|2|3"); positional ones keyed "1", "2", "3". */
    static Map<String, String> params(String body) {
        Map<String, String> out = new HashMap<>();
        String[] parts = body.split("\\|", -1);
        int n = 0;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq >= 0) {
                out.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
            } else {
                n++;
                out.put(String.valueOf(n), part.trim());
            }
        }
        return out;
    }

    /** "-2206" or "11.5" (→ 11); null when it is not a number. */
    private static Integer number(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(s.trim());
        return m.matches() ? Integer.valueOf(Integer.parseInt(m.group(1))) : null;
    }

    private static String get(Map<String, String> p, String key, String alt) {
        return p.containsKey(key) ? p.get(key) : p.get(alt);
    }

    /** x/y/z (named or positional, or "coordinates=x, y, z"); y may be {@link #NO_Y}. */
    static int[] point(Map<String, String> p) {
        if (p.containsKey("coordinates")) {
            List<Integer> c = new ArrayList<>();
            Matcher m = DIGITS.matcher(p.get("coordinates"));
            while (m.find()) {
                c.add(Integer.parseInt(m.group()));
            }
            return c.size() == 3 ? new int[] {c.get(0), c.get(1), c.get(2)} : null;
        }
        Integer x = number(get(p, "x", "1"));
        Integer y = number(get(p, "y", "2"));
        Integer z = number(get(p, "z", "3"));
        return x != null && z != null ? new int[] {x, y == null ? NO_Y : y, z} : null;
    }

    private static String mapLinkText(java.util.regex.MatchResult m) {
        int[] p = point(params(m.group(1)));
        if (p == null) {
            return "";
        }
        return p[1] != NO_Y ? p[0] + ", " + p[1] + ", " + p[2] : p[0] + ", " + p[2];
    }

    /** One line of wikitext as plain text; the same as fetch.py's clean(). */
    static String clean(String s) {
        for (String[] e : ENTITIES) {
            s = s.replace(e[0], e[1]);
        }
        s = s.replace("{{--}}", "\u2014");
        s = INPUT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(CLICKS.getOrDefault(m.group(1), "")));
        s = MAPLINK.matcher(s).replaceAll(m -> Matcher.quoteReplacement(mapLinkText(m)));
        s = BR.matcher(s).replaceAll(" ");
        s = FILE_LINK.matcher(s).replaceAll("");
        while (true) {
            String before = s;
            s = COLOUR.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
            s = TEMPLATE.matcher(s).replaceAll("");
            s = LINK.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
            if (s.equals(before)) {
                break;
            }
        }
        s = EXTERNAL.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("'''", "").replace("''", "");
        s = BULLETS.matcher(s.trim()).replaceFirst("");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** The first place a line gives: MapLink or RenderLocation, else "x, y, z" or "X: x, Z: z" in its text. */
    private static int[] firstPoint(String raw, String text) {
        int bestAt = -1;
        int[] best = null;
        for (Pattern pat : new Pattern[] {MAPLINK, RENDER}) {
            Matcher m = pat.matcher(raw);
            while (m.find()) {
                int[] p = point(params(m.group(1)));
                if (p != null) {
                    if (best == null || m.start() < bestAt) {
                        best = p;
                        bestAt = m.start();
                    }
                    break;
                }
            }
        }
        if (best != null) {
            return best;
        }
        Matcher t = TRIPLE.matcher(text);
        while (t.find()) {
            int x = Integer.parseInt(t.group(1));
            int y = Integer.parseInt(t.group(2));
            int z = Integer.parseInt(t.group(3));
            if (Math.max(Math.abs(x), Math.abs(z)) >= 50) {
                return new int[] {x, y, z};
            }
        }
        Matcher xz = XZ.matcher(text);
        return xz.find() ? new int[] {Integer.parseInt(xz.group(1)), NO_Y, Integer.parseInt(xz.group(2))} : null;
    }

    /** Just past the "}}" closing the "{{" at start, or -1. */
    private static int matchingEnd(String s, int start) {
        int depth = 0;
        int j = start;
        while (j < s.length() - 1) {
            if (s.startsWith("{{", j)) {
                depth++;
                j += 2;
            } else if (s.startsWith("}}", j)) {
                depth--;
                j += 2;
                if (depth == 0) {
                    return j;
                }
            } else {
                j++;
            }
        }
        return -1;
    }

    private static String dropBlockTemplates(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (true) {
            int start = s.indexOf("{{", i);
            if (start < 0) {
                out.append(s, i, s.length());
                break;
            }
            int end = matchingEnd(s, start);
            if (end < 0 || s.substring(start, end).indexOf('\n') < 0) {
                out.append(s, i, start + 2);
                i = start + 2;
            } else {
                out.append(s, i, start);
                i = end;
            }
        }
        return out.toString();
    }

    /** {{HideSpoiler|text|label|…}} → text. */
    private static String unspoil(String s) {
        while (true) {
            int start = s.indexOf("{{HideSpoiler");
            if (start < 0) {
                return s;
            }
            int end = matchingEnd(s, start);
            if (end < 0) {
                return s.substring(0, start) + s.substring(start + "{{HideSpoiler".length());
            }
            String inner = s.substring(start + 2, end - 2);
            List<Integer> cut = new ArrayList<>();
            int depth = 0;
            int k = 0;
            while (k < inner.length()) {
                if (inner.startsWith("{{", k) || inner.startsWith("[[", k)) {
                    depth++;
                    k += 2;
                } else if (inner.startsWith("}}", k) || inner.startsWith("]]", k)) {
                    depth--;
                    k += 2;
                } else {
                    if (inner.charAt(k) == '|' && depth == 0) {
                        cut.add(k);
                    }
                    k++;
                }
            }
            String text = cut.isEmpty() ? "" : inner.substring(cut.get(0) + 1, cut.size() > 1 ? cut.get(1) : inner.length());
            if (text.startsWith("1=")) {
                text = text.substring(2);
            }
            s = s.substring(0, start) + text + s.substring(end);
        }
    }

    private static String section(String text) {
        String[] lines = text.split("\n", -1);
        Matcher[] heads = new Matcher[lines.length];
        boolean access = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher h = HEADING.matcher(lines[i]);
            heads[i] = h.matches() ? h : null;
            if (heads[i] != null && heads[i].group(1).length() == 2 && heads[i].group(2).toLowerCase(Locale.ROOT).equals("access")) {
                access = true;
            }
        }
        List<String> picked = new ArrayList<>();
        boolean taking = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher h = heads[i];
            if (h != null && h.group(1).length() == 2) {
                String name = h.group(2);
                taking = access ? name.toLowerCase(Locale.ROOT).equals("access") : SECTIONS.matcher(name).matches();
            } else if (h == null && taking) {
                picked.add(lines[i]);
            }
        }
        return String.join("\n", picked);
    }

    private static String prepare(String page) {
        String text = page.replace("\r\n", "\n");
        text = COMMENT.matcher(text).replaceAll("");
        text = REF.matcher(text).replaceAll(m -> !m.group().endsWith("/>") && CONDITIONAL.matcher(m.group()).find()
                ? Matcher.quoteReplacement(COND) : "");
        text = GALLERY.matcher(text).replaceAll("");
        text = TABLE.matcher(text).replaceAll("");
        return dropBlockTemplates(unspoil(text));
    }

    /** A page's access steps. */
    static List<Step> steps(String page) {
        List<Step> out = new ArrayList<>();
        int[] pending = null;
        List<String> choices = new ArrayList<>();
        boolean inCollapse = false;
        boolean quoting = false;
        for (String raw : section(prepare(page)).split("\n", -1)) {
            String l = raw.trim();
            if (l.startsWith("{{Collapse top")) {
                inCollapse = true;
                continue;
            }
            if (l.startsWith("{{Collapse bottom")) {
                inCollapse = false;
                flush(out, choices);
                continue;
            }
            if (CHOICE.matcher(l).lookingAt()) {
                String c = clean(l);
                if (!c.isEmpty() && !c.startsWith("[+") && !c.startsWith("[-") && !l.contains(COND) && !choices.contains(c)) {
                    choices.add(c);
                }
                continue;
            }
            if (quoting && l.startsWith("*")) {
                continue;
            }
            quoting = false;
            if (inCollapse || l.isEmpty() || "{|!".indexOf(l.charAt(0)) >= 0 && !l.startsWith("{{")
                    || SPEAKER.matcher(l).lookingAt() || ITALIC_ONLY.matcher(l).matches() || BOLD_ONLY.matcher(l).matches()) {
                continue;
            }
            String c = clean(l);
            if (c.isEmpty()) {
                Matcher m = RENDER.matcher(l);
                while (m.find()) {
                    int[] p = point(params(m.group(1)));
                    if (p != null) {
                        pending = p;
                        break;
                    }
                }
                continue;
            }
            if (c.endsWith(":") && c.split(" ").length <= 4 || c.endsWith("=") || c.startsWith("[+")
                    || c.startsWith("[-") || c.startsWith("Category:")) {
                continue;
            }
            flush(out, choices);
            int[] at = firstPoint(l, c);
            out.add(new Step(c, at != null ? at : pending));
            pending = null;
            quoting = QUOTE_INTRO.matcher(c).find();
        }
        flush(out, choices);
        return out;
    }

    private static void flush(List<Step> out, List<String> choices) {
        if (choices.isEmpty()) {
            return;
        }
        String prose = out.isEmpty() ? "" : out.get(out.size() - 1).text().toLowerCase(Locale.ROOT);
        boolean named = choices.stream().allMatch(c -> prose.contains(c.toLowerCase(Locale.ROOT)));
        if (!named) {
            List<String> quoted = choices.stream().map(c -> "\"" + c + "\"").toList();
            out.add(new Step("Choose " + String.join(", then ", quoted) + ".", null));
        }
        choices.clear();
    }

    /** SHA-1 of "text<TAB>x,y,z" lines (y empty when unknown, nothing after the tab without a place). */
    static String hash(List<Step> steps) {
        List<String> lines = new ArrayList<>();
        for (Step s : steps) {
            int[] at = s.at();
            String where = at == null ? "" : at[0] + "," + (at[1] == NO_Y ? "" : String.valueOf(at[1])) + "," + at[2];
            lines.add(s.text() + "\t" + where);
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return "";
        }
    }
}
