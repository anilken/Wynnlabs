package io.github.anilken.wynnlabs.neoforge;

import io.github.anilken.wynnlabs.WynnLabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/** The NeoForge entrypoint (client only): hands the NeoForge platform to the shared start-up. */
@Mod(value = "wynnlabs", dist = Dist.CLIENT)
public final class WynnLabsNeoForge {

    public WynnLabsNeoForge(IEventBus modBus, ModContainer container) {
        WynnLabs.init(new NeoForgePlatform(modBus));
    }
}
