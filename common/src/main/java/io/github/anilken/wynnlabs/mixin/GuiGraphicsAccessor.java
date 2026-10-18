package io.github.anilken.wynnlabs.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tooltip GuiGraphics keeps for the end of the frame, for the menu scale to draw at its own scale. */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccessor {

    @Accessor("deferredTooltip")
    Runnable wynnlabs$deferred();

    @Accessor("deferredTooltip")
    void wynnlabs$setDeferred(Runnable tooltip);
}
