package io.github.anilken.wynnlabs.dev.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Dev builds only (wynnlabs.dev.mixins.json): the screenshot tool's {@code hidename}. While it
 * captures, the player's own name in any string drawn reads "Player". Every drawString (String,
 * Component, sequence) ends in this one. Does nothing otherwise.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsShotsMixin {

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence wynnlabs$hideName(FormattedCharSequence s) {
        return io.github.anilken.wynnlabs.Shots.mask(s);
    }
}
