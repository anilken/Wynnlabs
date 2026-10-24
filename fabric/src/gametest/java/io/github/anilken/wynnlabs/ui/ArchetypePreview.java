package io.github.anilken.wynnlabs.ui;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * The archetype list in the J menu preview: opened from the home's HUD at GUI scale 2, 3 and 4
 * (large, standard, compact) and from the tree's chip.
 */
final class ArchetypePreview {

    private ArchetypePreview() {}

    static void shots(ClientGameTestContext ctx, Path out) throws Exception {
        ctx.getInput().setCursorPos(2, 2);
        for (int scale : new int[] {2, 3, 4}) {
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(scale);
                mc.resizeDisplay();
                set(mc.screen, "tab", 0);
            });
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> ((RoadmapScreen) mc.screen).archPicker.openAt("#qarch:"));
            ctx.waitTicks(3);
            shot(ctx, out, "jmenu-archetypes-gui" + scale);
            ctx.runOnClient(mc -> ((RoadmapScreen) mc.screen).archPicker.close());
        }
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            set(mc.screen, "tab", 9);
        });
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> ((RoadmapScreen) mc.screen).archPicker.openAt("#tree:arch"));
        ctx.waitTicks(3);
        shot(ctx, out, "jmenu-archetypes-tree");
        ctx.runOnClient(mc -> ((RoadmapScreen) mc.screen).archPicker.close());
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out));
    }

    private static void set(Object o, String field, int value) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(o, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
