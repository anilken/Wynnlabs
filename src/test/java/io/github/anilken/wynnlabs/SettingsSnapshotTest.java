package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings are read from a lock-free copy that follows every change. */
class SettingsSnapshotTest {

    @AfterEach
    void reset() {
        Settings.use(new JsonObject());
    }

    @Test
    void readsFollowChanges() {
        JsonObject a = new JsonObject();
        a.addProperty("groundGlow", false);
        a.addProperty("menuScale", 3);
        Settings.use(a);
        assertFalse(Settings.on("groundGlow"));
        assertEquals(3, Settings.number("menuScale", 1));

        JsonObject b = new JsonObject();
        b.addProperty("groundGlow", true);
        Settings.use(b);
        assertTrue(Settings.on("groundGlow"));
        assertEquals(1, Settings.number("menuScale", 1), "an unset number falls back");
    }

    @Test
    void defaultsApplyToUnsetSwitches() {
        Settings.use(new JsonObject());
        assertFalse(Settings.on("debug"), "debug starts off");
        assertTrue(Settings.on("groundGlow"), "other switches start on");
    }

    @Test
    void oldFilesWithRemovedFeaturesStillLoad() {
        JsonObject old = new JsonObject();
        old.addProperty("chestGlow", true);
        old.addProperty("partyGlow", false);
        old.addProperty("namelessGlow", true);
        old.addProperty("chestMinStars", 2);
        old.addProperty("chestFarDistance", 400);
        old.addProperty("groundGlow", false);
        old.addProperty("langOverride", 2);
        Settings.use(old);
        assertFalse(Settings.on("groundGlow"), "kept switches still load");
        assertEquals(2, Settings.number("langOverride", 0), "kept numbers still load");
        assertEquals(-1, Settings.number("chestMinStars", -1), "removed numbers are dropped");
        for (String k : Settings.REMOVED) {
            assertFalse(Settings.LABELS.containsKey(k), k + " is no longer a setting");
            assertFalse(Settings.json().has(k), k + " is no longer reported");
        }
    }

    @Test
    void readersSeeAConsistentCopyWhileWritersChangeIt() throws InterruptedException {
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                JsonObject o = new JsonObject();
                o.addProperty("groundGlow", i % 2 == 0);
                Settings.use(o);
            }
        });
        writer.start();
        for (int i = 0; i < 20000; i++) {
            Settings.on("groundGlow");              // must never throw while the maps change
            Settings.number("menuScale", 1);
        }
        writer.join();
    }
}
