package io.github.anilken.wynnlabs.mixin;

import io.github.anilken.wynnlabs.ui.BoxKey;
import io.github.anilken.wynnlabs.ui.SearchKey;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The search key while a screen is open (the bank, the Trade Market, a chest, the inventory,
 * Wynntils' guides): with an item under the mouse it opens the search tab on that item; the box
 * contents key over a gear box opens its window. Any other press goes on to the screen as before. Key mappings only fire with no screen open, so
 * this is the one place that sees the key then.
 */
@Mixin(KeyboardHandler.class)
public class SearchKeyMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void wynnlabs$searchKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action != 1) {
            return;                                 // presses only, not repeats or releases
        }
        try {
            var screen = Minecraft.getInstance().screen;
            if (SearchKey.onScreenKey(screen, event) || BoxKey.onScreenKey(screen, event)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // the key must never break typing
        }
    }
}
