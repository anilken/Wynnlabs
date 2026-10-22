package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.Tuning;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The build window's text: an item's tooltip from the item data, type and tier names, a tuning in one line. */
final class BuildText {

    private BuildText() {}

    static String nice(String upper) {
        return upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
    }

    static String typeOf(ItemData i) {
        Slot s = i.slot();
        return switch (s) {
            case HELMET -> L.t("wynnlabs.road.slot_helmet");
            case CHESTPLATE -> L.t("wynnlabs.road.slot_chestplate");
            case LEGGINGS -> L.t("wynnlabs.road.slot_leggings");
            case BOOTS -> L.t("wynnlabs.road.slot_boots");
            case RING -> L.t("wynnlabs.road.slot_ring");
            case BRACELET -> L.t("wynnlabs.road.slot_bracelet");
            case NECKLACE -> L.t("wynnlabs.road.slot_necklace");
            default -> nice(s.name());
        };
    }

    /**
     * An item's tooltip from the item data, for when Wynntils has no stack to draw one (the
     * preview, a Wynntils change): its name in its tier's colour, tier, type and level, the
     * class and skill requirements, the base stats and the identifications' ranges.
     */
    static List<Component> itemTooltip(ItemData info) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(info.name()).withStyle(info.tier().colour()).withStyle(UiFont::bold));
        out.add(Component.literal(nice(info.tier().name()) + " " + typeOf(info)).withStyle(info.tier().colour()));
        out.add(Component.literal("Combat Lv. Min: " + info.level()).withStyle(ChatFormatting.GRAY));
        info.requirements().klass().ifPresent(k -> out.add(Component.literal("Class Req: " + nice(k.name())).withStyle(ChatFormatting.GRAY)));
        for (var r : info.requirements().skills()) {
            out.add(Component.literal(r.skill().displayName() + " Min: " + r.points()).withStyle(ChatFormatting.GRAY));
        }
        var base = info.base();
        if (base.health() != 0) {
            out.add(Component.literal("❤ Health: " + (base.health() > 0 ? "+" : "") + base.health()).withStyle(ChatFormatting.DARK_RED));
        }
        for (var d : base.damages()) {
            out.add(Component.literal(nice(d.type()) + " Damage: " + d.low() + "-" + d.high()).withStyle(ChatFormatting.GOLD));
        }
        for (var d : base.defences()) {
            out.add(Component.literal(nice(d.element()) + " Defence: " + (d.value() > 0 ? "+" : "") + d.value()).withStyle(ChatFormatting.GRAY));
        }
        base.attackSpeed().ifPresent(a -> out.add(Component.literal(nice(a.replace('_', ' ')) + " Attack Speed").withStyle(ChatFormatting.GRAY)));
        int shown = 0;
        for (var st : info.stats()) {
            if (shown++ >= 14) {
                out.add(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            String unit = "PERCENT".equals(st.unit()) ? "%" : "";
            String range = st.fixed() ? signed(st.min()) + unit : signed(st.min()) + unit + " to " + signed(st.max()) + unit;
            boolean good = st.inverted() ? st.max() <= 0 : st.min() >= 0;
            out.add(Component.literal(range + " " + st.name()).withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        base.majorId().ifPresent(m -> out.add(Component.literal("+" + m).withStyle(ChatFormatting.AQUA)));
        return out;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    /** "Balanced · Mana · Budget: 10 EB": a tuning in one line. */
    static String summary(Tuning.Choice t) {
        String f = L.t(t.focus() == Tuning.DAMAGE ? "wynnlabs.pick.focus_damage" : t.focus() == Tuning.TANKY ? "wynnlabs.pick.focus_tanky"
                : "wynnlabs.pick.focus_balanced");
        StringBuilder s = new StringBuilder(f);
        if (t.mana()) {
            s.append(" · ").append(L.t("wynnlabs.pick.mana"));
        }
        if (t.speed()) {
            s.append(" · ").append(L.t("wynnlabs.pick.speed"));
        }
        s.append(" · ").append(L.t("wynnlabs.pick.budget", budgetText(t.budget())));
        return s.toString();
    }

    static String budgetText(long budget) {
        return budget <= 0 ? L.t("wynnlabs.pick.budget_none") : Prices.fmt(budget);
    }
}
