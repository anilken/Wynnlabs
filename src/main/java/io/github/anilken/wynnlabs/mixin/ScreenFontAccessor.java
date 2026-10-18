package io.github.anilken.wynnlabs.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets our own screens draw with the UI font; other screens keep the game's. */
@Mixin(Screen.class)
public interface ScreenFontAccessor {

    @Mutable
    @Accessor("font")
    void wynnlabs$setFont(Font font);
}
