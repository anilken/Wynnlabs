package io.github.anilken.wynnlabs;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One hover or click area of the menu, drawn this frame: an item's tooltip ({@code stack}), lines
 * of text ({@code tip}), an action or text to copy ({@code copy}), a quest to track, and lines
 * over an item's own tooltip ({@code lead}). Any of them may be null.
 */
record MenuHit(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String copy,
        QuestBook.Entry quest, List<Component> lead) {

    MenuHit(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String copy, QuestBook.Entry quest) {
        this(x, y, w, h, stack, tip, copy, quest, null);
    }

    MenuHit(int x, int y, int w, int h, ItemStack stack, List<Component> tip, String copy) {
        this(x, y, w, h, stack, tip, copy, null, null);
    }
}
