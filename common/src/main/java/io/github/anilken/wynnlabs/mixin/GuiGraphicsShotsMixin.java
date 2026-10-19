package io.github.anilken.wynnlabs.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The screenshot tool's {@code hidename}: while it captures, the player's own name in any string
 * drawn reads "Player". Every drawString (String, Component, sequence) ends in this one. Does
 * nothing otherwise.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsShotsMixin {

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence wynnlabs$hideName(FormattedCharSequence s) {
        return io.github.anilken.wynnlabs.Shots.mask(s);
    }
}
