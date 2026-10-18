package io.github.anilken.wynnlabs.model;

/** A waypoint on the map and compass: its marker name and block position. */
public record Pin(String name, int x, int y, int z) {}
