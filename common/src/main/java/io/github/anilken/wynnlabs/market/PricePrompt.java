package io.github.anilken.wynnlabs.market;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.BiConsumer;

/**
 * Live prices start off, so a new player is asked once instead: "Fetch live Trade Market prices
 * from wynnventory.com? [Yes] [No]", a few seconds into the first world of a run. Each word runs
 * {@code /wynnlabs-prices yes|no} (a client command) and sets the {@code priceApi} switch as the
 * settings tab does. Never asked again once the switch was set by hand or the line was shown.
 */
public final class PricePrompt {

    /** After the update line (100 ticks), so the two never land on the same tick. */
    private static final int DELAY_TICKS = 160;
    /** settings.json's mark that the line was shown, so an ignored question stays answered. */
    static final String ASKED = "pricePromptAsked";

    private static int ticks;
    /** Whether this run already decided (shown, or not needed). */
    private static boolean done;

    private PricePrompt() {}

    public static void onTick(Minecraft mc) {
        if (done || mc.player == null || ++ticks < DELAY_TICKS) {
            return;
        }
        done = true;
        if (due(Prices.installed())) {
            Settings.setNumber(ASKED, 1);
            mc.gui.getChat().addMessage(line());
        }
    }

    /**
     * Ask only with the Wynnventory mod there (without it the switch does nothing), while the
     * player never set the switch and the line was never shown.
     */
    static boolean due(boolean installed) {
        return installed && !Settings.saved("priceApi") && Settings.number(ASKED, 0) == 0;
    }

    /** The question, with its two bold underlined answers. */
    static Component line() {
        return L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                .append(L.c("wynnlabs.price_prompt.ask").withStyle(ChatFormatting.GREEN))
                .append(" ")
                .append(answer("yes", ChatFormatting.GREEN))
                .append(" ")
                .append(answer("no", ChatFormatting.RED));
    }

    private static MutableComponent answer(String word, ChatFormatting colour) {
        return L.c("wynnlabs.price_prompt." + word).withStyle(s -> s
                .withColor(colour)
                .withUnderlined(true)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/wynnlabs-prices " + word))
                .withHoverEvent(new HoverEvent.ShowText(L.c("wynnlabs.price_prompt." + word + "_hover"))));
    }

    /** On this client only: {@code /wynnlabs-prices yes} turns live prices on, {@code no} keeps them off. */
    public static <S> LiteralArgumentBuilder<S> command(BiConsumer<S, Component> feedback) {
        return LiteralArgumentBuilder.<S>literal("wynnlabs-prices")
                .then(LiteralArgumentBuilder.<S>literal("yes").executes(c -> {
                    feedback.accept(c.getSource(), choose(true));
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<S>literal("no").executes(c -> {
                    feedback.accept(c.getSource(), choose(false));
                    return 1;
                }));
    }

    /** The answer saved, as flipping the settings switch would, and the confirmation line. */
    static Component choose(boolean on) {
        Settings.set("priceApi", on);
        Settings.setNumber(ASKED, 1);
        return L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                .append(L.c(on ? "wynnlabs.price_prompt.on" : "wynnlabs.price_prompt.off").withStyle(ChatFormatting.GRAY));
    }
}
