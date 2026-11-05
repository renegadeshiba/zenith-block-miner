package com.zenithplugins.blockminer.rules;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mined items that craft into a block from 9 of themselves, and back again, so nothing is lost. */
public final class CompactRecipes {

    public static final int INPUT_COUNT = 9;

    public record Recipe(String input, String output) {
    }

    /** In crafting order: nuggets come first, so the ingots they make can go on to become blocks. */
    public static final List<Recipe> ALL = List.of(
        new Recipe("gold_nugget", "gold_ingot"),
        new Recipe("iron_nugget", "iron_ingot"),
        new Recipe("coal", "coal_block"),
        new Recipe("raw_iron", "raw_iron_block"),
        new Recipe("raw_copper", "raw_copper_block"),
        new Recipe("raw_gold", "raw_gold_block"),
        new Recipe("iron_ingot", "iron_block"),
        new Recipe("copper_ingot", "copper_block"),
        new Recipe("gold_ingot", "gold_block"),
        new Recipe("netherite_ingot", "netherite_block"),
        new Recipe("diamond", "diamond_block"),
        new Recipe("emerald", "emerald_block"),
        new Recipe("lapis_lazuli", "lapis_block"),
        new Recipe("redstone", "redstone_block")
    );

    private CompactRecipes() {
    }

    public static boolean isInput(String itemName) {
        return ALL.stream().anyMatch(recipe -> recipe.input().equals(itemName));
    }

    /** What these items craft into, all the way up: gold nuggets into ingots, and those into blocks. */
    public static Set<String> craftedFrom(Collection<String> items) {
        Set<String> crafted = new HashSet<>();
        for (Recipe recipe : ALL) {
            if (items.contains(recipe.input()) || crafted.contains(recipe.input())) crafted.add(recipe.output());
        }
        return crafted;
    }

    /**
     * How many to craft out of {@code total} items. The bot needs a stack of its target material, like
     * diamonds, to fill inventory slots with, so only what's beyond that gets crafted.
     */
    public static int craftsToMake(int total, boolean isTarget, int stackSize) {
        int keep = isTarget ? stackSize : 0;
        return Math.max(0, total - keep) / INPUT_COUNT;
    }
}
