package io.github.anilken.wynnlabs.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The tooltip GuiGraphics keeps for the end of the frame, for the menu scale to draw at its own
 * scale; and its textured quad with free UVs, for the kit's own nine-slice.
 */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccessor {

    @Accessor("deferredTooltip")
    Runnable wynnlabs$deferred();

    @Accessor("deferredTooltip")
    void wynnlabs$setDeferred(Runnable tooltip);

    /** One quad from (x0, y0) to (x1, y1) of {@code texture}, UVs from 0 to 1, tinted ARGB. */
    @Invoker("innerBlit")
    void wynnlabs$quad(RenderPipeline pipeline, Identifier texture, int x0, int x1, int y0, int y1,
                       float u0, float u1, float v0, float v1, int colour);
}
