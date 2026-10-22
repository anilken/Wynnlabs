package io.github.anilken.wynnlabs.neoforge;

import io.github.anilken.wynnlabs.core.Platform;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@link Platform} on NeoForge: FML for paths and mods, NeoForge's client events on the game bus.
 * Key mappings, their category and the HUD layers can only be added inside their mod-bus events,
 * so they are queued while {@code WynnLabs.init} runs (in the mod constructor) and handed over
 * once, when the event fires.
 *
 * <p>Every hook only listens or draws on this client; none sends anything to the server.
 */
public final class NeoForgePlatform implements Platform {

    private record Hud(Identifier id, HudLayer layer) {}

    private final List<KeyMapping.Category> categories = new ArrayList<>();
    private final List<KeyMapping> keys = new ArrayList<>();
    private final List<Hud> huds = new ArrayList<>();
    private boolean keysFlushed;
    private boolean hudsFlushed;

    private final List<Runnable> started = new ArrayList<>();
    private boolean startedFired;

    /** For {@code Platform.get()} outside the game (no mod bus: nothing to register then). */
    public NeoForgePlatform() {}

    /** The entrypoint's platform: the queued registrations go out on {@code modBus}'s events. */
    NeoForgePlatform(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, this::flushKeys);
        modBus.addListener(RegisterGuiLayersEvent.class, this::flushHuds);
        // "client started": once, before the first tick's work (the title screen is about to show)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientTickEvent.Pre.class, e -> {
            if (startedFired) {
                return;
            }
            startedFired = true;
            started.forEach(Runnable::run);
        });
    }

    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    @Override
    public Optional<String> modVersion(String id) {
        return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString());
    }

    @Override
    public void onEndTick(Consumer<Minecraft> listener) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> listener.accept(Minecraft.getInstance()));
    }

    @Override
    public void onGameMessage(BiConsumer<Component, Boolean> listener) {
        // last, and never for a cancelled message: the player sees what this listener sees
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ClientChatReceivedEvent.System.class,
                e -> listener.accept(e.getMessage(), e.isOverlay()));
    }

    @Override
    public void onTooltip(BiConsumer<ItemStack, List<Component>> listener) {
        NeoForge.EVENT_BUS.addListener(ItemTooltipEvent.class, e -> listener.accept(e.getItemStack(), e.getToolTip()));
    }

    @Override
    public void addHud(Identifier id, HudLayer layer) {
        if (hudsFlushed) {
            throw new IllegalStateException("HUD layer " + id + " added after RegisterGuiLayersEvent");
        }
        huds.add(new Hud(id, layer));
    }

    @Override
    public void onDisconnect(Runnable listener) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> listener.run());
    }

    @Override
    public KeyMapping.Category keyCategory(Identifier id) {
        if (keysFlushed) {
            throw new IllegalStateException("key category " + id + " added after RegisterKeyMappingsEvent");
        }
        // not KeyMapping.Category.register: NeoForge sorts modded categories in itself
        var category = new KeyMapping.Category(id);
        categories.add(category);
        return category;
    }

    @Override
    public KeyMapping registerKey(KeyMapping key) {
        if (keysFlushed) {
            throw new IllegalStateException("key " + key.getName() + " added after RegisterKeyMappingsEvent");
        }
        keys.add(key);
        return key;
    }

    @Override
    public void registerClientCommand(ClientCommand command) {
        // the client dispatcher: the command never reaches the server; feedback goes to the local chat
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e ->
                e.getDispatcher().register(command.<CommandSourceStack>build(CommandSourceStack::sendSystemMessage)));
    }

    @Override
    public void onClientStarted(Runnable listener) {
        started.add(listener);
    }

    @Override
    public void onClientStopping(Runnable listener) {
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, e -> listener.run());
    }

    private void flushKeys(RegisterKeyMappingsEvent event) {
        if (keysFlushed) {
            return;
        }
        keysFlushed = true;
        for (var category : categories) {
            event.registerCategory(category);
            devLog("key category " + category.id());
        }
        for (var key : keys) {
            event.register(key);
            devLog("key " + key.getName());
        }
    }

    private void flushHuds(RegisterGuiLayersEvent event) {
        if (hudsFlushed) {
            return;
        }
        hudsFlushed = true;
        for (var hud : huds) {
            event.registerAboveAll(hud.id(), hud.layer()::render);
            devLog("HUD layer " + hud.id());
        }
    }

    private static void devLog(String what) {
        if (!FMLEnvironment.isProduction()) {
            System.out.println("[WynnLabs] registered " + what);
        }
    }
}
