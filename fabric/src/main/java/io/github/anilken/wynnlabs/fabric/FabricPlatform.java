package io.github.anilken.wynnlabs.fabric;

import io.github.anilken.wynnlabs.Platform;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** {@link Platform} on Fabric: Fabric Loader for paths and mods, Fabric API's client events. */
public final class FabricPlatform implements Platform {

    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    @Override
    public Optional<String> modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(c -> c.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public void onEndTick(Consumer<Minecraft> listener) {
        ClientTickEvents.END_CLIENT_TICK.register(listener::accept);
    }

    @Override
    public void onGameMessage(BiConsumer<Component, Boolean> listener) {
        ClientReceiveMessageEvents.GAME.register(listener::accept);
    }

    @Override
    public void onTooltip(BiConsumer<ItemStack, List<Component>> listener) {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> listener.accept(stack, lines));
    }

    @Override
    public void addHud(Identifier id, HudLayer layer) {
        HudElementRegistry.addLast(id, layer::render);
    }

    @Override
    public void onDisconnect(Runnable listener) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> listener.run());
    }

    @Override
    public KeyMapping.Category keyCategory(Identifier id) {
        return KeyMapping.Category.register(id);
    }

    @Override
    public KeyMapping registerKey(KeyMapping key) {
        return KeyBindingHelper.registerKeyBinding(key);
    }

    @Override
    public void registerClientCommand(ClientCommand command) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(command.<FabricClientCommandSource>build(FabricClientCommandSource::sendFeedback)));
    }

    @Override
    public void onClientStarted(Runnable listener) {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> listener.run());
    }

    @Override
    public void onClientStopping(Runnable listener) {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> listener.run());
    }
}
