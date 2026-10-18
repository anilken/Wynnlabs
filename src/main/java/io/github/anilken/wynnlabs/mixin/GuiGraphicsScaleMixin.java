package io.github.anilken.wynnlabs.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The menu scale's tooltips: while a screen drawn at another scale draws its tooltip under its
 * scaled pose, the GUI size reads as that screen's logical size, so the tooltip is placed inside
 * the window as the menu sees it.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsScaleMixin {

    @Inject(method = "guiWidth", at = @At("HEAD"), cancellable = true)
    private void wynnlabs$width(CallbackInfoReturnable<Integer> cir) {
        int w = io.github.anilken.wynnlabs.MenuScale.tooltipWidth();
        if (w > 0) {
            cir.setReturnValue(w);
        }
    }

    @Inject(method = "guiHeight", at = @At("HEAD"), cancellable = true)
    private void wynnlabs$height(CallbackInfoReturnable<Integer> cir) {
        int h = io.github.anilken.wynnlabs.MenuScale.tooltipHeight();
        if (h > 0) {
            cir.setReturnValue(h);
        }
    }
}
