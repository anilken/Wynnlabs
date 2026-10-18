package io.github.anilken.wynnlabs.model;

import com.google.gson.JsonArray;

import java.util.List;

/**
 * A node of a class's ability tree. {@code type} is the node type's name ("SHAMAN_ABILITY_1",
 * "…_ULTIMATE" …) or null; {@code description} the text lines as component JSON;
 * {@code requiredArchetype} null when none, with the points it needs; {@code archetype} the
 * node's own archetype or null, {@code colour} that archetype's colour as "#RRGGBB" or null;
 * {@code where} its place in the tree menu (pages from 1, rows 0-5, columns 0-8), or null.
 */
public record AbilityNode(int id, String name, String type, List<JsonArray> description, int cost,
        List<String> willBlock, String requiredAbility, String requiredArchetype, int requiredArchetypePoints,
        int requiredLevel, String archetype, String colour, Place where, List<Integer> connections) {

    public record Place(int page, int row, int col) {}
}
