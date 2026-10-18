package io.github.anilken.wynnlabs.mixin;

import io.github.anilken.wynnlabs.GroundGlow;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Items on the ground get the vanilla glowing outline when {@link GroundGlow} picks a colour. */
@Mixin(Minecraft.class)
public class MinecraftGlowMixin {

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void wynninv$groundGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        long t = io.github.anilken.wynnlabs.Perf.start();
        if (entity instanceof ItemEntity item && GroundGlow.colour(item) != null) {
            cir.setReturnValue(true);
        }
        io.github.anilken.wynnlabs.Perf.end("glow.appears", t);
    }
}
