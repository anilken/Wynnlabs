package io.github.anilken.wynnlabs.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.game.WynntilsItems;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * /ground: what lies around you within 12 blocks, to see how Wynncraft shows drops (item
 * entities, item displays, armour stands) and what Wynntils makes of them. Reading only.
 */
public final class GroundDebug {

    private GroundDebug() {}

    static JsonObject json() {
        return json(12);
    }

    /** Wider than 12 blocks only text labels are listed, to keep it short. */
    public static JsonObject json(int radius) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return o;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.distanceTo(mc.player) > radius || e == mc.player) {
                continue;
            }
            if (e.distanceTo(mc.player) > 12 && !(e instanceof Display.TextDisplay)) {
                continue;
            }
            ItemStack stack = null;
            if (e instanceof ItemEntity ie) {
                stack = ie.getItem();
            } else if (e instanceof Display.ItemDisplay id) {
                stack = id.getSlot(0).get();
            } else if (e instanceof ArmorStand as) {
                stack = as.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            } else if (!(e instanceof Display)) {
                continue;
            }
            JsonObject j = new JsonObject();
            j.addProperty("entity", e.getClass().getSimpleName());
            if (e instanceof Display.TextDisplay t) {
                j.addProperty("text", t.getText().getString());
            }
            j.addProperty("distance", Math.round(e.distanceTo(mc.player) * 10) / 10.0);
            j.addProperty("customName", e.getCustomName() == null ? null : e.getCustomName().getString());
            j.addProperty("glowing", mc.shouldEntityAppearGlowing(e));
            if (stack != null && !stack.isEmpty()) {
                j.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                j.addProperty("hoverName", stack.getHoverName().getString());
                j.addProperty("nameStyle", String.valueOf(stack.getHoverName().getStyle().getColor()));
                j.addProperty("components", stack.getComponents().keySet().toString());
                var parsed = WynntilsItems.view(stack);
                j.addProperty("wynntils", parsed.map(w -> w.type()).orElse("none"));
                if (e instanceof ItemEntity ie) {
                    j.addProperty("ourColour", String.valueOf(GroundGlow.colour(ie)));
                }
            }
            arr.add(j);
        }
        o.add("entities", arr);
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b) {
            var pos = b.getBlockPos();
            JsonObject look = new JsonObject();
            look.addProperty("pos", pos.toShortString());
            look.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
            JsonArray around = new JsonArray();     // the block and its four sides, then the one above
            for (var q : new net.minecraft.core.BlockPos[] {pos, pos.north(), pos.south(), pos.east(), pos.west(), pos.above()}) {
                around.add(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(q).getBlock()).toString());
            }
            look.add("plus", around);
            o.add("lookingAt", look);
        }
        return o;
    }
}
