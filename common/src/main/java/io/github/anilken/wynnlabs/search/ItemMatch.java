package io.github.anilken.wynnlabs.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Matches typed text against the names of items and things, the mobs that drop items and the
 * stats they roll.
 * Text is normalised once (lower case, accents folded, apostrophes dropped, other punctuation a
 * space; stat keys split: "manaSteal" → "mana steal"). Tiers, best first: exact name, name prefix,
 * every typed word a word prefix, substring, small typos (Damerau–Levenshtein ≤ 1 for 4–7
 * letters, ≤ 2 for 8+, only from 4 typed letters), mob name, stat name.
 *
 * <p>A linear scan: names are matched entry by entry, mobs and stats once per distinct name and
 * then mapped to their items. Pure; no game types.
 */
public final class ItemMatch {

    public static final int EXACT = 1000;
    public static final int PREFIX = 900;
    public static final int WORD = 800;
    public static final int SUBSTRING = 700;
    public static final int TYPO = 600;
    public static final int MOB = 500;
    public static final int STAT = 400;

    private ItemMatch() {}

    /** Names, mobs and stats normalised once per index. */
    static final class Prepared {
        final String[] names;
        final String[][] tokens;
        /** Each token's letters as a bit set ({@link #mask}), to skip tokens a typo cannot reach. */
        final int[][] masks;
        final String[] mobNorm;
        final String[][] mobTokens;
        final String[] mobName;
        final int[][] mobEntries;
        final String[] statNorm;
        final String[][] statTokens;
        final String[] statKey;
        final int[][] statEntries;

        Prepared(String[] names, String[][] tokens, String[] mobNorm, String[] mobName, int[][] mobEntries,
                String[] statNorm, String[] statKey, int[][] statEntries) {
            this.names = names;
            this.tokens = tokens;
            this.masks = new int[tokens.length][];
            for (int i = 0; i < tokens.length; i++) {
                masks[i] = new int[tokens[i].length];
                for (int j = 0; j < tokens[i].length; j++) {
                    masks[i][j] = mask(tokens[i][j]);
                }
            }
            this.mobNorm = mobNorm;
            this.mobTokens = split(mobNorm);
            this.mobName = mobName;
            this.mobEntries = mobEntries;
            this.statNorm = statNorm;
            this.statTokens = split(statNorm);
            this.statKey = statKey;
            this.statEntries = statEntries;
        }

        private static String[][] split(String[] s) {
            String[][] out = new String[s.length][];
            for (int i = 0; i < s.length; i++) {
                out[i] = s[i].split(" ");
            }
            return out;
        }
    }

    static Prepared prepare(List<Entry> entries) {
        int n = entries.size();
        String[] names = new String[n];
        String[][] tokens = new String[n][];
        Map<String, List<Integer>> mobs = new LinkedHashMap<>();
        Map<String, String> mobShown = new HashMap<>();
        Map<String, List<Integer>> stats = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            names[i] = normalize(e.name());
            tokens[i] = names[i].isEmpty() ? new String[0] : names[i].split(" ");
            for (Entry.Origin o : e.origins()) {
                if (o.type().equals("mob") && o.name() != null) {
                    String k = normalize(o.name());
                    List<Integer> l = mobs.computeIfAbsent(k, x -> new ArrayList<>());
                    if (l.isEmpty() || l.get(l.size() - 1) != i) {
                        l.add(i);
                    }
                    mobShown.putIfAbsent(k, o.name());
                }
            }
            for (Entry.Stat s : e.stats()) {
                List<Integer> l = stats.computeIfAbsent(s.key(), x -> new ArrayList<>());
                if (l.isEmpty() || l.get(l.size() - 1) != i) {
                    l.add(i);
                }
            }
        }
        String[] mobNorm = mobs.keySet().toArray(new String[0]);
        String[] mobName = new String[mobNorm.length];
        int[][] mobEntries = new int[mobNorm.length][];
        for (int i = 0; i < mobNorm.length; i++) {
            mobName[i] = mobShown.get(mobNorm[i]);
            mobEntries[i] = mobs.get(mobNorm[i]).stream().mapToInt(Integer::intValue).toArray();
        }
        String[] statKey = stats.keySet().toArray(new String[0]);
        String[] statNorm = new String[statKey.length];
        int[][] statEntries = new int[statKey.length][];
        for (int i = 0; i < statKey.length; i++) {
            statNorm[i] = normalize(splitKey(statKey[i]));
            statEntries[i] = stats.get(statKey[i]).stream().mapToInt(Integer::intValue).toArray();
        }
        return new Prepared(names, tokens, mobNorm, mobName, mobEntries, statNorm, statKey, statEntries);
    }

    /** Lower case, accents folded, apostrophes dropped, other punctuation a single space, trimmed. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                t = Normalizer.normalize(s.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD);
                break;
            }
        }
        StringBuilder b = new StringBuilder(t.length());
        boolean space = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\'' || c == '’' || c == '`' || Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                b.append(Character.toLowerCase(c));
                space = false;
            } else if (!space) {
                b.append(' ');
                space = true;
            }
        }
        int len = b.length();
        if (len > 0 && b.charAt(len - 1) == ' ') {
            b.setLength(len - 1);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** A camelCase stat key as words: "raw1stSpellCost" → "raw 1st Spell Cost". */
    public static String splitKey(String key) {
        StringBuilder b = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            char prev = i > 0 ? key.charAt(i - 1) : ' ';
            boolean cut = i > 0 && (Character.isUpperCase(c) && !Character.isUpperCase(prev)
                    || Character.isDigit(c) && Character.isLetter(prev));
            if (cut) {
                b.append(' ');
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Every entry the text matches, as hits in index order (unsorted). Each entry appears once with
     * its best match. Empty text matches nothing here ({@link SearchQuery} lists all instead).
     */
    static List<Hit> match(ItemIndex index, String text) {
        String q = normalize(text);
        List<Hit> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        Prepared p = index.prepared;
        List<Entry> entries = index.all();
        String[] qt = q.split(" ");
        int[] qm = new int[qt.length];
        for (int w = 0; w < qt.length; w++) {
            qm[w] = mask(qt[w]);
        }
        int letters = q.length() - (qt.length - 1);
        int[] best = new int[entries.size()];
        Hit.Match[] how = new Hit.Match[entries.size()];
        String[] detail = new String[entries.size()];
        for (int i = 0; i < best.length; i++) {
            String name = p.names[i];
            if (name.equals(q)) {
                best[i] = EXACT;
                how[i] = Hit.Match.EXACT;
            } else if (name.startsWith(q)) {
                best[i] = PREFIX;
                how[i] = Hit.Match.PREFIX;
            } else if (wordPrefixes(qt, p.tokens[i])) {
                best[i] = WORD;
                how[i] = Hit.Match.WORD;
            } else if (name.contains(q)) {
                best[i] = SUBSTRING;
                how[i] = Hit.Match.SUBSTRING;
            } else if (letters >= 4) {
                int d = typo(qt, qm, p.tokens[i], p.masks[i]);
                if (d > 0) {
                    best[i] = TYPO - 40 * d;
                    how[i] = Hit.Match.TYPO;
                }
            }
        }
        if (q.length() >= 3) {
            for (int m = 0; m < p.mobNorm.length; m++) {
                String mob = p.mobNorm[m];
                int s = mob.equals(q) ? MOB + 20 : mob.startsWith(q) ? MOB + 10
                        : wordPrefixes(qt, p.mobTokens[m]) ? MOB : -1;
                if (s > 0) {
                    for (int i : p.mobEntries[m]) {
                        if (s > best[i]) {
                            best[i] = s;
                            how[i] = Hit.Match.MOB;
                            detail[i] = p.mobName[m];
                        }
                    }
                }
            }
            for (int st = 0; st < p.statNorm.length; st++) {
                String stat = p.statNorm[st];
                int s = stat.equals(q) ? STAT + 20 : stat.startsWith(q) ? STAT + 10
                        : wordPrefixes(qt, p.statTokens[st]) ? STAT : -1;
                if (s > 0) {
                    for (int i : p.statEntries[st]) {
                        if (s > best[i]) {
                            best[i] = s;
                            how[i] = Hit.Match.STAT;
                            detail[i] = p.statKey[st];
                        }
                    }
                }
            }
        }
        for (int i = 0; i < best.length; i++) {
            if (best[i] > 0) {
                out.add(new Hit(entries.get(i), best[i], how[i], detail[i]));
            }
        }
        return out;
    }

    /** Every typed word is the start of some word of the name. */
    private static boolean wordPrefixes(String[] qt, String[] tokens) {
        for (String w : qt) {
            boolean found = false;
            for (String t : tokens) {
                if (t.startsWith(w)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * The total typo distance when every typed word matches a word of the name by prefix or by a
     * small typo of its start (words under 4 letters must match exactly as a prefix); 0 when the
     * words do not all match or none needed a typo.
     */
    static int typo(String[] qt, String[] tokens) {
        int[] qm = new int[qt.length];
        int[] tm = new int[tokens.length];
        for (int i = 0; i < qt.length; i++) {
            qm[i] = mask(qt[i]);
        }
        for (int i = 0; i < tokens.length; i++) {
            tm[i] = mask(tokens[i]);
        }
        return typo(qt, qm, tokens, tm);
    }

    /** {@link #typo(String[], String[])} with the words' and tokens' {@link #mask}s worked out already. */
    private static int typo(String[] qt, int[] qm, String[] tokens, int[] tm) {
        int total = 0;
        for (int wi = 0; wi < qt.length; wi++) {
            String w = qt[wi];
            int bestD = Integer.MAX_VALUE;
            for (int ti = 0; ti < tokens.length; ti++) {
                String t = tokens[ti];
                if (t.startsWith(w)) {
                    bestD = 0;
                    break;
                }
                if (w.length() < 4) {
                    continue;
                }
                int max = w.length() >= 8 ? 2 : 1;
                // each letter of the word that the token lacks costs an edit: too many, no typo
                if (Integer.bitCount(qm[wi] & ~tm[ti]) > max) {
                    continue;
                }
                for (int len = w.length() - max; len <= w.length() + max; len++) {
                    if (len < 1 || len > t.length()) {
                        continue;
                    }
                    int d = distance(w, t, len, max);
                    if (d < bestD) {
                        bestD = d;
                    }
                }
            }
            if (bestD == Integer.MAX_VALUE) {
                return 0;
            }
            total += bestD;
        }
        return total;
    }

    /** The letters and digits of a word as bits (two characters may share one: a bound, never a miss). */
    static int mask(String s) {
        int m = 0;
        for (int i = 0; i < s.length(); i++) {
            m |= 1 << (s.charAt(i) & 31);
        }
        return m;
    }

    /**
     * Damerau–Levenshtein (optimal string alignment) distance between {@code a} and the first
     * {@code len} letters of {@code b}; anything above {@code max} comes back as MAX_VALUE.
     */
    static int distance(String a, String b, int len, int max) {
        int n = a.length();
        if (Math.abs(n - len) > max) {
            return Integer.MAX_VALUE;
        }
        int[] prev2 = new int[len + 1];
        int[] prev = new int[len + 1];
        int[] cur = new int[len + 1];
        for (int j = 0; j <= len; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= len; j++) {
                char cb = b.charAt(j - 1);
                int cost = ca == cb ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) {
                return Integer.MAX_VALUE;
            }
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[len] <= max ? prev[len] : Integer.MAX_VALUE;
    }
}
