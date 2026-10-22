package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Settings;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * A short column of coloured dust above glowing drops, in the drop's rarity colour. Shader packs
 * (Iris) often skip the vanilla glowing outline; particles always show. Client-side only.
 */
public final class GroundMarkers {

    private static int tick;

    private GroundMarkers() {}

    /** Push the colour towards full saturation and brightness so it reads at night too. */
    private static int brighten(int rgb) {
        float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        return java.awt.Color.HSBtoRGB(hsb[0], Math.min(1f, hsb[1] * 1.3f + 0.1f), 1f) & 0xFFFFFF;
    }

    public static void onTick(Minecraft mc) {
        if (++tick % 4 != 0 || mc.player == null || mc.level == null
                || !Settings.on("groundGlow") || !Settings.on("groundBeam")) {
            return;
        }
        try {
            for (Entity e : mc.level.entitiesForRendering()) {
                // only real drops: item displays are mostly decoration (props, mannequins)
                if (!(e instanceof ItemEntity ie)) {
                    continue;
                }
                ItemStack stack = ie.getItem();
                if (e.distanceTo(mc.player) > 48) {
                    continue;
                }
                Integer colour = GroundGlow.colour(e.getId(), stack);
                if (colour == null) {
                    continue;
                }
                // bigger dust and a taller column (about 5 blocks), drifting up slowly
                DustParticleOptions dust = new DustParticleOptions(brighten(colour), 2.2f);
                for (int i = 0; i < 10; i++) {
                    double y = e.getY() + 0.3 + i * 0.5 + (tick % 10) * 0.05;
                    mc.level.addParticle(dust, true, true, e.getX(), y, e.getZ(), 0, 0.03, 0);
                }
            }
        } catch (Throwable ignored) {
            // a marker must never break the game
        }
    }
}
