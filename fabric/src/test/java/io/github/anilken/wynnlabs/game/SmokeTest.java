package io.github.anilken.wynnlabs.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/** The logic classes load without the game, Wynntils or Fabric API: everything else builds on that. */
class SmokeTest {

    @Test
    void logicLoads() throws Exception {
        TestGame.boot();
        for (String name : new String[] {"advice.AutoAdvice", "build.Build", "build.roadmap.Roadmap", "market.Shopping",
                "build.Damage", "game.ItemJson"}) {
            Class.forName("io.github.anilken.wynnlabs." + name, true, SmokeTest.class.getClassLoader());
        }
        assertNull(net.minecraft.client.Minecraft.getInstance(), "no client in tests");
    }
}
