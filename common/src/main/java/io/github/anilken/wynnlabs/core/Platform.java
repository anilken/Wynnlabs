package io.github.anilken.wynnlabs.core;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.anilken.wynnlabs.api.WynnLabs;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Everything the mod needs from its mod loader, so that the shared code never names Fabric or
 * NeoForge. Each loader module has one implementation ({@code fabric.FabricPlatform},
 * {@code neoforge.NeoForgePlatform}); its entrypoint hands it to {@link WynnLabs#init}.
 *
 * <p>Every hook only listens or shows something on this client: none of them sends anything to
 * the server.
 */
public interface Platform {

    /** "fabric" or "neoforge", for diagnostics only. */
    String loaderName();

    /** The game's {@code config/} folder. */
    Path configDir();

    /** The game (instance) folder. */
    Path gameDir();

    boolean isModLoaded(String id);

    /** The version a mod declares, when it is loaded. */
    Optional<String> modVersion(String id);

    /** Runs at the end of every client tick, in the order registered. */
    void onEndTick(Consumer<Minecraft> listener);

    /** A game (system) message reached the chat or the action bar ({@code true} for the overlay). */
    void onGameMessage(BiConsumer<Component, Boolean> listener);

    /** An item tooltip is being built: lines added to the list show at its bottom. */
    void onTooltip(BiConsumer<ItemStack, List<Component>> listener);

    /** A layer drawn above the vanilla HUD, after the ones added before it. */
    void addHud(Identifier id, HudLayer layer);

    /** The client left a server (or a world). */
    void onDisconnect(Runnable listener);

    /** A key binding category shown in Controls. */
    KeyMapping.Category keyCategory(Identifier id);

    /** Adds a key binding to Controls; returns the same mapping, to poll in a tick listener. */
    KeyMapping registerKey(KeyMapping key);

    /** A command that runs on this client only, never sent to the server. */
    void registerClientCommand(ClientCommand command);

    /** Once, when the client has started (the title screen is about to show). */
    void onClientStarted(Runnable listener);

    /** Once, when the game is closing. */
    void onClientStopping(Runnable listener);

    /** A HUD layer: both loaders call it with the frame's graphics and timing. */
    @FunctionalInterface
    interface HudLayer {
        void render(GuiGraphics graphics, DeltaTracker delta);
    }

    /**
     * A client command as a Brigadier tree over the loader's own command source type {@code S};
     * {@code feedback} shows a line to the player for that source.
     */
    @FunctionalInterface
    interface ClientCommand {
        <S> LiteralArgumentBuilder<S> build(BiConsumer<S, Component> feedback);
    }

    /** The loader's platform: the one the entrypoint installed, else the one on the classpath. */
    static Platform get() {
        Platform p = Holder.current;
        if (p != null) {
            return p;
        }
        synchronized (Holder.class) {
            if (Holder.current == null) {
                Holder.current = Holder.find();
            }
            return Holder.current;
        }
    }

    /** Called by the loader's entrypoint (through {@link WynnLabs#init}) before anything else. */
    static void install(Platform platform) {
        synchronized (Holder.class) {
            Holder.current = platform;
        }
    }

    /** The installed platform; tests and static initialisers find it by name on the classpath. */
    final class Holder {
        private static final String[] KNOWN = {
            "io.github.anilken.wynnlabs.fabric.FabricPlatform",
            "io.github.anilken.wynnlabs.neoforge.NeoForgePlatform"
        };

        private static volatile Platform current;

        private Holder() {}

        private static Platform find() {
            for (String name : KNOWN) {
                Class<?> c;
                try {
                    c = Class.forName(name, true, Platform.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                try {
                    return (Platform) c.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot create " + name, e);
                }
            }
            throw new IllegalStateException("no Wynn Labs platform on the classpath");
        }
    }
}
