package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Profile;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * When a worn piece does not work because a skill is short, says in chat how to move the skill
 * points so that everything worn works: which skills to take points back from (in a town) and
 * which to give them to, with the total the skill menu will then show. Uses the same solver as
 * the build plan ({@link Build#minPoints}), so the other worn pieces keep working. Once per
 * situation; reads only.
 */
public final class WearCheck {

    private static int tick;
    private static String lastSaid = "";

    private WearCheck() {}

    public static void onTick(Minecraft mc) {
        if (mc.player == null || ++tick % 40 != 0 || !Profile.settled() || !Settings.on("chatSkills")) {
            return;
        }
        try {
            check(mc);
        } catch (Throwable t) {
            System.out.println("[WynnLabs] wear check failed: " + t);
        }
    }

    private static void check(Minecraft mc) {
        GameState state = GameState.current();
        if (!state.skillsKnown()) {
            return;                             // assigned points unknown: any advice would be a guess
        }
        List<ItemStack> worn = new ArrayList<>();
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            worn.add(state.armour(s));
        }
        worn.addAll(state.accessories());
        worn.add(state.mainHand());

        List<Damage.Piece> pieces = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (ItemStack stack : worn) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            var view = state.view(stack);
            if (view.isEmpty() || !view.get().isGear()) {
                continue;
            }
            Damage.Piece p = Damage.piece(stack);
            if (p == null) {
                continue;
            }
            // the hand counts only when it holds a weapon
            if (stack == state.mainHand() && !p.type().isWeapon()) {
                continue;
            }
            pieces.add(p);
            ItemView.Marks m = view.get().rolled() == null ? null : view.get().rolled().marks();
            if (m != null && m.known() && !m.all() && !p.reqs().isEmpty()) {
                broken.add(p.name());
            }
        }
        if (broken.isEmpty()) {
            lastSaid = "";
            return;
        }

        int[] target = Build.minPoints(pieces);
        int n = SkillKind.values().length;
        int[] now = new int[n];
        int[] gear = new int[n];
        int sumTarget = 0;
        int sumNow = 0;
        for (SkillKind s : SkillKind.values()) {
            int i = s.ordinal();
            now[i] = state.assignedSeen(s);
            // what the menu adds on top of assigned points: worn armour and accessories (weapons
            // never count), tomes and set bonuses
            String api = "raw" + s.name().charAt(0) + s.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            double fromGear = 0;
            for (Damage.Piece p : pieces) {
                if (p.helpsRequirements()) {
                    fromGear += p.stats().getOrDefault(api, 0.0);
                }
            }
            gear[i] = (int) Math.round(fromGear) + state.tomePoints(s) + state.setBonusPoints(s);
            sumTarget += target[i];
            sumNow += now[i];
        }
        int earned = Math.max(0, (Math.min(state.level(), 101) - 1) * 2);
        String names = String.join(", ", broken);
        String text;
        // the message: a head line, then step lines (what to take back in red, what to give in green)
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        var head = L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                .append(net.minecraft.network.chat.Component.literal(names).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        // what was said is remembered as its facts, not its words: a language switch alone does
        // not say it again
        if (sumTarget > earned) {
            text = "cannot|" + names + "|" + sumTarget + "|" + earned;
            lines.add(head.append(L.c("wynnlabs.wear.cannot_head").withStyle(ChatFormatting.YELLOW)));
            lines.add(L.c("wynnlabs.wear.cannot_why", sumTarget, earned).withStyle(ChatFormatting.GRAY));
        } else {
            // give: what each skill still needs; take: points above what the worn pieces need,
            // only as many as the free points do not already cover
            List<String> give = new ArrayList<>();
            StringBuilder facts = new StringBuilder(names);
            int need = 0;
            for (SkillKind s : SkillKind.values()) {
                int i = s.ordinal();
                if (target[i] > now[i]) {
                    int add = target[i] - now[i];
                    need += add;
                    give.add(L.t("wynnlabs.wear.give", add, s.displayName(), target[i] + gear[i]));
                    facts.append("|+").append(add).append(s.name()).append('=').append(target[i] + gear[i]);
                }
            }
            if (give.isEmpty()) {
                lastSaid = "";
                return;                         // points add up: the game will catch up
            }
            int free = Math.max(0, earned - sumNow);
            int short_ = need - free;
            List<String> take = new ArrayList<>();
            // take from the skills with the most to spare first
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(now[b] - target[b], now[a] - target[a]));
            for (int i : order) {
                if (short_ <= 0) {
                    break;
                }
                int spare = now[i] - target[i];
                if (spare > 0) {
                    int t = Math.min(spare, short_);
                    short_ -= t;
                    take.add(L.t("wynnlabs.wear.take", t, SkillKind.values()[i].displayName(), now[i] - t + gear[i]));
                    facts.append("|-").append(t).append(SkillKind.values()[i].name()).append('=').append(now[i] - t + gear[i]);
                }
            }
            text = facts.toString();
            lines.add(head.append(L.c("wynnlabs.wear.head").withStyle(ChatFormatting.YELLOW)));
            if (take.isEmpty()) {
                lines.add(step(L.t("wynnlabs.wear.step_spend", free), give, ChatFormatting.GREEN));
            } else {
                lines.add(step(L.t("wynnlabs.wear.step_take"), take, ChatFormatting.RED));
                lines.add(step(L.t("wynnlabs.wear.step_give"), give, ChatFormatting.GREEN));
            }
        }
        if (text.equals(lastSaid)) {
            return;
        }
        lastSaid = text;
        lines.forEach(mc.gui.getChat()::addMessage);
    }

    /** "  1. Şehirde geri al:  −20 Agility (12 kalır) · −13 Intelligence (16 kalır)", the items in one colour. */
    private static net.minecraft.network.chat.Component step(String label, List<String> items, ChatFormatting colour) {
        return net.minecraft.network.chat.Component.literal("  " + label + "  ").withStyle(ChatFormatting.GRAY)
                .append(net.minecraft.network.chat.Component.literal(String.join(" · ", items)).withStyle(colour));
    }
}
