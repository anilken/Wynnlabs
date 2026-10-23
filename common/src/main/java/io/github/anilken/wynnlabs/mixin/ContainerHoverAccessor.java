package io.github.anilken.wynnlabs.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The slot under the mouse in a container screen, for the search key over a hovered item. Read only. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerHoverAccessor {

    @Accessor("hoveredSlot")
    Slot wynnlabs$hoveredSlot();
}
