package io.github.anilken.wynnlabs.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Listings;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.search.BoxMarket;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The box contents window without the game ({@code -PpreviewOnly=box}): the recorded Shaman's
 * unique leggings box (Lv 66-70) over the inventory, with canned Wynnventory answers for two of
 * its items and no network; shots with live lookups on and off, the unidentified filter, GUI
 * scales 2 and 4, the window for one item, and for one ingredient (no rolls: the one-line table,
 * where it comes from and what the player holds; the item index read from the search fixtures).
 */
public class BoxPreview implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!"box".equals(System.getProperty("wynnlabs.previewOnly"))) {
            return;
        }
        Path out = Path.of(System.getProperty("wynnlabs.preview", "preview"));
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            Files.createDirectories(out);
            SearchPreview.loadIndex();
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
                try {
                    FixtureState.load(Fixtures.root().resolve("shaman-44")).install(System.getProperty("wynnlabs.previewLang", "tr"));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                Wyn.clearDown();
                canned("Atoll", 1, "listings-atoll-p1.json");
                canned("Canopy", 1, "listings-canopy.json");
                live(false);
            });
            ItemView.Box box = new ItemView.Box(Slot.LEGGINGS, Tier.UNIQUE, 66, 70);
            ctx.runOnClient(mc -> mc.setScreen(new BoxScreen(box, new InventoryScreen(mc.player))));
            ctx.getInput().setCursorPos(2, 2);
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> ((BoxScreen) mc.screen).selected = "Atoll");
            ctx.waitTicks(3);
            shot(ctx, out, "box-window-offline");
            ctx.runOnClient(mc -> live(true));
            ctx.waitTicks(3);
            shot(ctx, out, "box-window");
            ctx.runOnClient(mc -> canned("Atoll", 2, "listings-atoll-p2.json"));     // "Daha fazla" fetched
            ctx.waitTicks(3);
            shot(ctx, out, "box-window-page2");
            ctx.runOnClient(mc -> ((BoxScreen) mc.screen).filter = BoxMarket.Filter.UNID);
            ctx.waitTicks(3);
            shot(ctx, out, "box-window-unid");
            ctx.runOnClient(mc -> {
                ((BoxScreen) mc.screen).filter = BoxMarket.Filter.ALL;
                ((BoxScreen) mc.screen).sort = BoxMarket.Sort.PRICE;
            });
            ctx.waitTicks(3);
            shot(ctx, out, "box-window-price");
            for (int scale : new int[] {2, 4}) {
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(scale);
                    mc.resizeDisplay();
                });
                ctx.waitTicks(5);
                shot(ctx, out, "box-window-gui" + scale);
            }
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
                BoxMarket.Item atoll = new BoxMarket.Item("Atoll", Slot.LEGGINGS, Tier.UNIQUE, 68, Klass.NONE, 0, true);
                mc.setScreen(new BoxScreen(new BoxScreen.Single(atoll, 72.4f, false), new ItemStack(Items.LEATHER_LEGGINGS),
                        new InventoryScreen(mc.player)));
            });
            ctx.waitTicks(5);
            shot(ctx, out, "box-window-item");
            ctx.waitFor(mc -> !"loading".equals(io.github.anilken.wynnlabs.search.ItemIndex.get().missing()), 20 * 60);
            ctx.runOnClient(mc -> {
                canned("Accursed Effigy", 1, "listings-effigy.json");
                BoxMarket.Item effigy = new BoxMarket.Item("Accursed Effigy", Slot.UNKNOWN, Tier.NORMAL, 76, Klass.NONE, 0, true);
                mc.setScreen(new BoxScreen(new BoxScreen.Single(effigy, null, false), new ItemStack(Items.POTION),
                        new InventoryScreen(mc.player)));
            });
            ctx.waitTicks(5);
            shot(ctx, out, "box-window-material");
            for (int scale : new int[] {2, 4}) {
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(scale);
                    mc.resizeDisplay();
                });
                ctx.waitTicks(5);
                shot(ctx, out, "box-window-material-gui" + scale);
            }
            ctx.runOnClient(mc -> mc.setScreen(null));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A canned answer through the store's own reader, as if Wynnventory had sent it. */
    private static void canned(String name, int page, String file) {
        try {
            String text = Files.readString(Fixtures.root().resolve("wynnventory").resolve(file), StandardCharsets.UTF_8);
            Method m = Listings.class.getDeclaredMethod("accept", String.class, int.class, JsonObject.class);
            m.setAccessible(true);
            JsonObject body = new Gson().fromJson(text, JsonObject.class);
            // the answers were recorded at 12:05 UTC: their times moved to now, so the ages read as minutes
            long shift = System.currentTimeMillis() - java.time.Instant.parse("2026-10-24T12:05:00Z").toEpochMilli();
            for (var el : body.getAsJsonArray("data")) {
                JsonObject l = el.getAsJsonObject();
                long at = java.time.Instant.parse(l.get("timestamp").getAsString()).toEpochMilli() + shift;
                l.addProperty("timestamp", java.time.Instant.ofEpochMilli(at).toString());
            }
            m.invoke(null, name, page, body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Live lookups on or off, with nothing ever sent: a sender that only says whether it is live. */
    private static void live(boolean on) {
        try {
            Field f = Listings.class.getDeclaredField("sender");
            f.setAccessible(true);
            Class<?> type = f.getType();
            Object sender = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "live" -> on;
                        case "queue" -> false;
                        case "hashCode" -> 0;
                        case "equals" -> proxy == args[0];
                        default -> "preview";
                    });
            f.set(null, sender);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out));
    }
}
