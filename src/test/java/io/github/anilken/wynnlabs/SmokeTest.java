package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/** The logic classes load without the game, Wynntils or Fabric API: everything else builds on that. */
class SmokeTest {

    @Test
    void logicLoads() throws Exception {
        TestGame.boot();
        for (String name : new String[] {"AutoAdvice", "Build", "Roadmap", "Shopping", "Damage", "ItemJson"}) {
            Class.forName("io.github.anilken.wynnlabs." + name, true, SmokeTest.class.getClassLoader());
        }
        assertNull(net.minecraft.client.Minecraft.getInstance(), "no client in tests");
    }
}
