package io.github.anilken.wynnlabs.fabric;

import io.github.anilken.wynnlabs.WynnLabs;
import net.fabricmc.api.ClientModInitializer;

/** The Fabric entrypoint (fabric.mod.json): hands the Fabric platform to the shared start-up. */
public final class WynnLabsFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        WynnLabs.init(new FabricPlatform());
    }
}
