package io.github.anilken.wynnlabs.mixin;

import io.github.anilken.wynnlabs.ui.GroundGlow;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The outline colour of a glowing ground item: its rarity colour. */
@Mixin(Entity.class)
public class EntityTeamColorMixin {

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void wynninv$groundGlowColour(CallbackInfoReturnable<Integer> cir) {
        long t = io.github.anilken.wynnlabs.core.Perf.start();
        try {
            wynninv$colour(cir);
        } finally {
            io.github.anilken.wynnlabs.core.Perf.end("glow.colour", t);
        }
    }

    private void wynninv$colour(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof ItemEntity item) {
            Integer c = GroundGlow.colour(item);
            if (c != null) {
                cir.setReturnValue(c);
            }
        }
    }
}
