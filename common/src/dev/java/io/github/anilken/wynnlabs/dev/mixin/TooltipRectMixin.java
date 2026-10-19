package io.github.anilken.wynnlabs.dev.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev builds only (wynnlabs.dev.mixins.json): where a tooltip is drawn while the screenshot tool
 * captures, so a tooltip shot's crop takes it in even past the window's frame. The background
 * reaches 3 px of padding and a 9 px frame margin around the text box.
 */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRectMixin {

    @Inject(method = "renderTooltipBackground", at = @At("HEAD"))
    private static void wynnlabs$tooltipRect(GuiGraphics g, int x, int y, int w, int h, Identifier background, CallbackInfo ci) {
        io.github.anilken.wynnlabs.Shots.tooltip(g.pose(), x - 12, y - 12, w + 24, h + 24);
    }
}
