package io.github.anilken.wynnlabs.ui;

import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.game.TestGame;
import io.github.anilken.wynnlabs.model.Klass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped ability maps: connector icons to arms, and every class has one. */
class TreeConnectorsTest {

    @org.junit.jupiter.api.BeforeEach
    void boot() {
        TestGame.boot();
    }

    @AfterEach
    void reset() {
        for (Klass k : Klass.values()) {
            TreeConnectors.use(k, null);
        }
    }

    @Test
    void anIconNamesItsArms() {
        assertArrayEquals(new boolean[] {true, false, true, false}, TreeConnectors.arms("connector_up_down"));
        assertArrayEquals(new boolean[] {false, true, true, true}, TreeConnectors.arms("connector_right_down_left"));
        assertArrayEquals(new boolean[] {true, true, true, true}, TreeConnectors.arms("connector_up_right_down_left"));
        assertNull(TreeConnectors.arms("abilityTree.nodeShaman"));
        assertNull(TreeConnectors.arms(null));
    }

    @Test
    void theShippedFileReads() {
        String json = """
                {"format": 1, "cells": {"1:1:4": "connector_up_down", "1:2:5": "connector_right_left", "2:0:4": "connector_right_down", "bad": "connector_up"}}
                """;
        Map<String, boolean[]> cells = TreeConnectors.parseShipped(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(3, cells.size(), "a key must be page:row:col");
        assertArrayEquals(new boolean[] {true, false, true, false}, cells.get(TreeLayout.connectorKey(1, 1, 4)));
        assertArrayEquals(new boolean[] {false, true, true, false}, cells.get(TreeLayout.connectorKey(2, 0, 4)));
    }

    @Test
    void everyClassShipsItsTree() {
        for (Klass k : Klass.values()) {
            if (k == Klass.NONE) {
                continue;
            }
            Map<String, boolean[]> cells = TreeConnectors.get(k);
            assertTrue(cells.size() > 100, k + " has " + cells.size() + " pieces");
        }
        // the Shaman's first page as the game shows it: Totem at the top, the piece below it
        assertArrayEquals(new boolean[] {true, false, true, false}, TreeConnectors.get(Klass.SHAMAN).get(TreeLayout.connectorKey(1, 1, 4)));
    }
}
