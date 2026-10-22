package io.github.anilken.wynnlabs.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The preview's layout check: while {@code LayoutCheck} records, every string and sprite drawn
 * reports its box (and the scissor in force). Does nothing in the game.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsBoundsMixin {

    private GuiGraphics self() {
        return (GuiGraphics) (Object) this;
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"))
    private void wynnlabs$string(Font font, String s, int x, int y, int colour, boolean shadow, CallbackInfo ci) {
        if (s != null && io.github.anilken.wynnlabs.ui.LayoutCheck.on()) {
            io.github.anilken.wynnlabs.ui.LayoutCheck.record(self().pose(), x, y, font.width(s), font.lineHeight, s, true);
        }
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"))
    private void wynnlabs$sequence(Font font, FormattedCharSequence s, int x, int y, int colour, boolean shadow, CallbackInfo ci) {
        if (s != null && io.github.anilken.wynnlabs.ui.LayoutCheck.on()) {
            StringBuilder sb = new StringBuilder();
            s.accept((i, style, cp) -> {
                sb.appendCodePoint(cp);
                return sb.length() < 400;
            });
            io.github.anilken.wynnlabs.ui.LayoutCheck.record(self().pose(), x, y, font.width(s), font.lineHeight, sb.toString(), true);
        }
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"))
    private void wynnlabs$component(Font font, Component c, int x, int y, int colour, boolean shadow, CallbackInfo ci) {
        if (c != null && io.github.anilken.wynnlabs.ui.LayoutCheck.on()) {
            io.github.anilken.wynnlabs.ui.LayoutCheck.record(self().pose(), x, y, font.width(c), font.lineHeight, c.getString(), true);
        }
    }

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"))
    private void wynnlabs$sprite(RenderPipeline p, Identifier id, int x, int y, int w, int h, int tint, CallbackInfo ci) {
        if (io.github.anilken.wynnlabs.ui.LayoutCheck.on()) {
            io.github.anilken.wynnlabs.ui.LayoutCheck.record(self().pose(), x, y, w, h, id.getPath(), false);
        }
    }

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"))
    private void wynnlabs$spritePlain(RenderPipeline p, Identifier id, int x, int y, int w, int h, CallbackInfo ci) {
        if (io.github.anilken.wynnlabs.ui.LayoutCheck.on()) {
            io.github.anilken.wynnlabs.ui.LayoutCheck.record(self().pose(), x, y, w, h, id.getPath(), false);
        }
    }

    @Inject(method = "enableScissor", at = @At("HEAD"))
    private void wynnlabs$scissor(int x0, int y0, int x1, int y1, CallbackInfo ci) {
        io.github.anilken.wynnlabs.ui.LayoutCheck.scissor(self().pose(), x0, y0, x1, y1);
    }

    @Inject(method = "disableScissor", at = @At("HEAD"))
    private void wynnlabs$unscissor(CallbackInfo ci) {
        io.github.anilken.wynnlabs.ui.LayoutCheck.unscissor();
    }
}
