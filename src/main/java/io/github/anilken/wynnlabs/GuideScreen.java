package io.github.anilken.wynnlabs;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Görev çözümü: the quest's walkthrough from the Wynncraft wiki (wynncraft.wiki.gg), stage by
 * stage, with the stage you are on highlighted and every coordinate one click from the compass.
 * Fetched once in the background (the wiki, not the game server); dialogue is left out.
 */
final class GuideScreen extends Screen {

    private record Line(String text, int colour, int[] at, boolean stage) {}

    private static final Pattern COORDS = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");

    private final String quest;
    private final String task;
    private volatile List<Line> lines;
    private volatile String error;
    private int scroll;
    private int current = -1;
    /** The step shown on top: the game's line, or the matched stage's task in Turkish. */
    private volatile String step;
    private final List<Object[]> hits = new ArrayList<>();
    private String flash = "";
    private long flashUntil;

    GuideScreen(String quest, String task) {
        super(Component.literal(quest));
        this.quest = quest;
        this.task = task == null ? "" : task;
        this.step = this.task;
        CompletableFuture.runAsync(this::fetch);
    }

    @Override
    protected void init() {
        UiFont.apply(this);
        super.init();
    }

    private void fetch() {
        if (L.code().startsWith("tr")) {
            // the guide shipped in Turkish (offline too)
            QuestGuides.Guide g = QuestGuides.find(quest);
            QuestWiki.Info info = g == null || g.translated() == null ? null : QuestWiki.get(quest);
            if (info != null) {                           // changed since or not: the Turkish stages
                lines = shipped(info, g.english());
                return;
            }
        }
        if (!QuestWiki.on()) {
            error = L.t("wynnlabs.guide.off");
            return;
        }
        try {
            // the same page QuestWiki keeps on disk for a week: one request per quest per week
            String text = QuestWiki.page(quest);
            if (text == null) {
                error = L.t("wynnlabs.guide.no_page");
                return;
            }
            lines = parse(text);
        } catch (Exception e) {
            error = L.t("wynnlabs.guide.unreachable", e.getClass().getSimpleName());
        }
    }

    /** An NPC's line in a shipped guide ("Worid: …"), told by its English hint. */
    private static final Pattern SPEAKER = Pattern.compile("^[A-Z?][^:]{0,40}: ");

    /** A shipped guide's stages: heading, the task (») and its hints, without dialogue; the stage you are on marked. */
    private List<Line> shipped(QuestWiki.Info info, List<QuestWiki.Stage> english) {
        List<Line> out = new ArrayList<>();
        int cur = QuestWiki.currentStage(info, task);
        for (int i = 0; i < info.stages().size(); i++) {
            QuestWiki.Stage st = info.stages().get(i);
            out.add(new Line(QuestWiki.stageTitle(st.title()), 0xFFCECBF6, null, true));
            if (!st.task().isBlank()) {
                int[] at = st.at() != null ? st.at() : st.points().isEmpty() ? null : st.points().get(0);
                if (i == cur) {
                    current = out.size();
                    step = st.task();
                }
                out.add(new Line("» " + st.task(), 0xFFFFFFFF, at, false));
            }
            List<String> en = i < english.size() ? english.get(i).hints() : List.of();
            for (int j = 0; j < st.hints().size(); j++) {
                if (j < en.size() && SPEAKER.matcher(en.get(j)).find()) {
                    continue;
                }
                List<QuestWiki.Place> at = QuestWiki.places(new QuestWiki.Info("", "", "", 0, 0, 0, List.of(),
                        List.of(new QuestWiki.Stage("", "", List.of(st.hints().get(j)), null, List.of(), List.of())), List.of()), 0);
                String h = st.hints().get(j);
                if (h.startsWith("▸ ")) {
                    out.add(new Line(h, 0xFF9C8BD8, null, false));     // a part's heading: "▸ Bulmaca"
                    continue;
                }
                out.add(new Line("• " + QuestWiki.shown(h), 0xFFB4B2A9, at.isEmpty() ? null : at.get(0).at(), false));
            }
        }
        return out;
    }

    /** NPC dialogue lines ("* {{Dialogue|…}}"): the guide shows tasks, not conversations. */
    private static final Pattern DIALOGUE = Pattern.compile("[*#:]*\\s*\\{\\{Dialogue\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOUR = Pattern.compile("\\{\\{c\\|[^|{}]*\\|([^{}]*)}}");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{[^{}]*}}");

    /**
     * Templates in a line, innermost first so nested ones come apart cleanly: coloured text
     * ({{c|colour|text}}) keeps its text, any other template is dropped.
     */
    static String templates(String l) {
        String prev;
        do {
            prev = l;
            l = COLOUR.matcher(l).replaceAll(r -> Matcher.quoteReplacement(r.group(1)));
            l = TEMPLATE.matcher(l).replaceAll("");
        } while (!l.equals(prev));
        return l.replace("{{", "").replace("}}", "");
    }

    /** Wikitext → stage headings, the task lines (») and the hidden solutions; no dialogue or tables. */
    private List<Line> parse(String text) {
        List<Line> out = new ArrayList<>();
        text = text.replaceAll("(?s)<ref.*?</ref>", "").replaceAll("(?s)<!--.*?-->", "");
        // spoilers: keep their content, drop the wrapper and its label
        text = text.replaceAll("(?s)\\{\\{HideSpoiler\\|(.*?)\\|[^|{}]*}}", "$1");
        text = QuestWiki.dropBlockTemplates(text);  // {{Infobox/Items … }} and the like
        boolean inTable = false;
        boolean skip = false;
        String taskLow = task.toLowerCase(Locale.ROOT);
        int bestScore = 0;
        for (String raw : text.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("{|") || l.startsWith("{{MobTable") || l.startsWith("{{Infobox")) {
                inTable = true;
                continue;
            }
            if (inTable) {
                if (l.startsWith("|}") || l.equals("}}")) {
                    inTable = false;
                }
                continue;
            }
            if (l.startsWith("==")) {
                String h = l.replaceAll("=", "").trim();
                skip = h.equalsIgnoreCase("Navigation") || h.equalsIgnoreCase("Trivia") || h.equalsIgnoreCase("Rewards");
                if (!skip) {
                    out.add(new Line(QuestWiki.stageTitle(h), 0xFFCECBF6, null, true));
                }
                continue;
            }
            if (skip || l.isEmpty() || l.startsWith("[[File:") || l.startsWith("[[Category") || l.startsWith("'''Dialogue")
                    || l.startsWith("*'''") || l.startsWith("{{") || l.startsWith("|") || DIALOGUE.matcher(l).lookingAt()) {
                continue;
            }
            String clean = templates(l.replaceAll("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*)]]", "$1").replaceAll("'''?", ""))
                    .replaceAll("<[^>]+>", "").replaceFirst("^[*#:]+\\s*", "• ").trim();
            if (clean.isEmpty() || clean.equals("•")) {
                continue;
            }
            int[] at = null;
            Matcher m = COORDS.matcher(clean);
            if (m.find()) {
                at = new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            }
            boolean taskLine = clean.startsWith("»");
            out.add(new Line(clean, taskLine ? 0xFFFFFFFF : 0xFFB4B2A9, at, false));
            // the stage you are on: the task line that shares the most words with the tracked task
            if (taskLine && !taskLow.isEmpty()) {
                int score = 0;
                for (String word : clean.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (word.length() > 3 && taskLow.contains(word)) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    current = out.size() - 1;
                }
            }
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // at the menu scale (MenuScale): a logical size, a scaled pose, logical mouse positions
        MenuScale.render(this, g, mx, my, (x, y) -> draw(g, x, y, pt));
    }

    private void draw(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        hits.clear();
        // more of the walkthrough at once on a large screen
        boolean large = MenuLayout.of(width, height).large();
        int w = Math.min(width - 20, large ? 720 : 460);
        int h = Math.min(height - 20, large ? 480 : 320);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        // the J menu's look: a wooden frame, parchment, dark ink without shadows
        Kit.window(g, x - 5, y - 5, w + 10, h + 10);
        Kit.paper(g, x, y, w, h);
        int[] cr = closeRect;
        boolean closeHover = cr != null && mx >= cr[0] && mx < cr[0] + cr[2] && my >= cr[1] && my < cr[1] + cr[3];
        closeRect = Kit.closeOutside(g, width, x, y, w, closeHover);
        Kit.text(g, font, Component.literal(QuestGuides.title(quest)).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), x + 10, y + 8, Theme.INK);
        String src = "wynncraft.wiki.gg";
        Kit.text(g, font, src, x + w - font.width(src) - 10, y + 8, Theme.INK_3);
        if (!task.isEmpty()) {
            Kit.text(g, font, font.plainSubstrByWidth(L.t("wynnlabs.guide.current_step", step), w - 20), x + 10, y + 20, Theme.PRICE);
        }
        g.fill(x + 10, y + 30, x + w - 10, y + 31, Theme.LINE);
        int top = y + 34;
        int bottom = y + h - 16;
        if (lines == null) {
            Kit.text(g, font, error != null ? error : L.t("wynnlabs.guide.loading"), x + 10, top + 4, error != null ? Theme.BAD : Theme.INK_2);
            return;
        }
        // wrap everything once per frame width; scroll to the current stage the first time
        List<Object[]> rows = new ArrayList<>();             // {text, colour, at, isCurrent}
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            boolean cur = i == current;
            List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(Component.literal(l.text()), w - 70);
            for (int k = 0; k < wrapped.size(); k++) {
                rows.add(new Object[] {wrapped.get(k), l.stage() ? Theme.ACCENT : cur ? Theme.GOOD : Theme.paperRgb(l.colour()),
                        k == 0 ? l.at() : null, cur, l.stage()});
            }
        }
        if (scroll < 0) {
            scroll = 0;
        }
        int lineH = 10;
        int fit = (bottom - top) / lineH;
        if (current >= 0 && scroll == 0 && firstFrame) {
            int idx = 0;
            for (int i = 0; i < rows.size(); i++) {
                if ((boolean) rows.get(i)[3]) {
                    idx = i;
                    break;
                }
            }
            scroll = Math.max(0, idx - 3);
        }
        firstFrame = false;
        scroll = Math.min(scroll, Math.max(0, rows.size() - fit));
        int ry = top;
        for (int i = scroll; i < rows.size() && i < scroll + fit; i++) {
            Object[] r = rows.get(i);
            boolean cur = (boolean) r[3];
            boolean stage = (boolean) r[4];
            if (cur) {
                g.fill(x + 6, ry - 1, x + 8, ry + 9, Theme.GOOD);
            }
            g.drawString(font, Kit.ink((net.minecraft.util.FormattedCharSequence) r[0]), x + (stage ? 10 : 14), ry, (int) r[1], false);
            int[] at = (int[]) r[2];
            if (at != null) {
                String go = L.t("wynnlabs.guide.go");
                int gx = x + w - 14 - font.width(go);
                boolean hover = mx >= gx - 2 && mx < x + w - 8 && my >= ry - 1 && my < ry + 9;
                Kit.text(g, font, go, gx, ry, hover ? Theme.GOLD_EDGE : Theme.LINK);
                hits.add(new Object[] {new int[] {gx - 2, ry - 1, font.width(go) + 6, 10}, at, r[0]});
            }
            ry += lineH;
        }
        String foot = System.currentTimeMillis() < flashUntil ? flash
                : current >= 0 ? L.t("wynnlabs.guide.footer_current") : L.t("wynnlabs.guide.footer");
        Kit.text(g, font, foot, x + 10, y + h - 12, System.currentTimeMillis() < flashUntil ? Theme.GOOD : Theme.INK_3);
    }

    private int[] closeRect;

    private boolean firstFrame = true;

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        x = MenuScale.at(x);
        y = MenuScale.at(y);
        scroll = Math.max(0, scroll - 3 * (int) Math.signum(v));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        event = MenuScale.event(event);
        int[] cr = closeRect;
        if (cr != null && event.x() >= cr[0] && event.x() < cr[0] + cr[2] && event.y() >= cr[1] && event.y() < cr[1] + cr[3]) {
            onClose();
            return true;
        }
        for (Object[] hit : hits) {
            int[] r = (int[]) hit[0];
            if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                int[] at = (int[]) hit[1];
                if (Waypoints.go(at[0], at[1], at[2], QuestGuides.title(quest))) {
                    flash = L.t("wynnlabs.guide.showing_way", at[0] + ", " + at[1] + ", " + at[2]);
                    flashUntil = System.currentTimeMillis() + 2500;
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
