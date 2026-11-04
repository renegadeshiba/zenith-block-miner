package com.zenithplugins.blockminer.rules;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The items the bot actually wants: what its chosen blocks drop, which often isn't the block itself. */
public final class TargetItems {

    /** Blocks that drop something other than themselves, unless they're mined with Silk Touch. */
    private static final Map<String, String> DROPS = Map.ofEntries(
        Map.entry("coal_ore", "coal"),
        Map.entry("deepslate_coal_ore", "coal"),
        Map.entry("iron_ore", "raw_iron"),
        Map.entry("deepslate_iron_ore", "raw_iron"),
        Map.entry("copper_ore", "raw_copper"),
        Map.entry("deepslate_copper_ore", "raw_copper"),
        Map.entry("gold_ore", "raw_gold"),
        Map.entry("deepslate_gold_ore", "raw_gold"),
        Map.entry("redstone_ore", "redstone"),
        Map.entry("deepslate_redstone_ore", "redstone"),
        Map.entry("lapis_ore", "lapis_lazuli"),
        Map.entry("deepslate_lapis_ore", "lapis_lazuli"),
        Map.entry("diamond_ore", "diamond"),
        Map.entry("deepslate_diamond_ore", "diamond"),
        Map.entry("emerald_ore", "emerald"),
        Map.entry("deepslate_emerald_ore", "emerald"),
        Map.entry("nether_quartz_ore", "quartz"),
        Map.entry("nether_gold_ore", "gold_nugget"),
        Map.entry("gilded_blackstone", "gold_nugget"),
        Map.entry("stone", "cobblestone"),
        Map.entry("deepslate", "cobbled_deepslate"),
        Map.entry("glowstone", "glowstone_dust"),
        Map.entry("clay", "clay_ball"),
        Map.entry("amethyst_cluster", "amethyst_shard")
    );

    private TargetItems() {
    }

    private static String mainDrop(String blockName) {
        return DROPS.getOrDefault(blockName, blockName);
    }

    /** Everything these blocks can drop, including the blocks themselves, which is what Silk Touch gives. */
    public static Set<String> itemsFor(Collection<String> blockNames) {
        Set<String> items = new HashSet<>();
        for (String block : blockNames) {
            items.add(block);
            items.add(mainDrop(block));
        }
        return items;
    }

    /**
     * How many different things these blocks drop that the bot isn't holding any of. Ore and deepslate
     * ore count as one, since they drop the same item.
     */
    public static int kindsNotHeld(Collection<String> blockNames, Set<String> heldItems) {
        Map<String, Boolean> heldByDrop = new HashMap<>();
        for (String block : blockNames) {
            String drop = mainDrop(block);
            boolean held = heldItems.contains(drop) || heldItems.contains(block);
            heldByDrop.merge(drop, held, Boolean::logicalOr);
        }
        return (int) heldByDrop.values().stream().filter(held -> !held).count();
    }
}
