package io.github.anilken.wynnlabs.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The glyph provider behind a Font, so the UI font can wrap the game's. */
@Mixin(Font.class)
public interface FontAccessor {

    @Accessor("provider")
    Font.Provider wynnlabs$provider();
}
