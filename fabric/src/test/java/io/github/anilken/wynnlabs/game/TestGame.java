package io.github.anilken.wynnlabs.game;

/** Minecraft's registries for tests: stacks and components need them, the rest of the game does not. */
public final class TestGame {

    private static boolean booted;

    private TestGame() {}

    public static synchronized void boot() {
        if (!booted) {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        }
    }
}
