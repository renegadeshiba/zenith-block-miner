package com.zenithplugins.blockminer.rules;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to get the items the user wants: which blocks to mine, and what tool to mine them
 * with. Diamonds come from mining diamond ore with Fortune, while the ore itself takes Silk Touch.
 */
public final class TargetItems {

    /** What the tool mining a block has to be like, for that block to give the item that's wanted. */
    public enum ToolNeed {
        ANY("any tool"),
        NO_SILK_TOUCH("a tool without Silk Touch"),
        FORTUNE("a Fortune tool"),
        SILK_TOUCH("a Silk Touch tool");

        private final String description;

        ToolNeed(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** Items that Fortune gets more of, and the blocks that drop them unless they're mined with Silk Touch. */
    private static final Map<String, List<String>> FORTUNE_DROPS = Map.ofEntries(
        Map.entry("coal", List.of("coal_ore", "deepslate_coal_ore")),
        Map.entry("raw_iron", List.of("iron_ore", "deepslate_iron_ore")),
        Map.entry("raw_copper", List.of("copper_ore", "deepslate_copper_ore")),
        Map.entry("raw_gold", List.of("gold_ore", "deepslate_gold_ore")),
        Map.entry("redstone", List.of("redstone_ore", "deepslate_redstone_ore")),
        Map.entry("lapis_lazuli", List.of("lapis_ore", "deepslate_lapis_ore")),
        Map.entry("diamond", List.of("diamond_ore", "deepslate_diamond_ore")),
        Map.entry("emerald", List.of("emerald_ore", "deepslate_emerald_ore")),
        Map.entry("quartz", List.of("nether_quartz_ore")),
        Map.entry("gold_nugget", List.of("nether_gold_ore", "gilded_blackstone")),
        Map.entry("glowstone_dust", List.of("glowstone")),
        Map.entry("amethyst_shard", List.of("amethyst_cluster")),
        Map.entry("prismarine_crystals", List.of("sea_lantern"))
    );

    /**
     * Items that Fortune makes no difference to, but Silk Touch would stop dropping. Where the item is
     * a block as well, that's included, since it drops itself and the pathfinder only goes after a
     * stray drop if a block with the same name is on its list.
     */
    private static final Map<String, List<String>> PLAIN_DROPS = Map.of(
        "cobblestone", List.of("stone", "cobblestone"),
        "cobbled_deepslate", List.of("deepslate", "cobbled_deepslate"),
        "clay_ball", List.of("clay")
    );

    /** Blocks that drop nothing at all, or only experience, without Silk Touch. */
    private static final Set<String> SILK_TOUCH_ONLY = Set.of(
        "ice", "packed_ice", "blue_ice", "glass", "bookshelf", "sculk", "sculk_catalyst"
    );

    /** Blocks that only drop themselves with Silk Touch, because they normally drop something else. */
    private static final Set<String> SOURCE_BLOCKS = sourceBlocks();

    private TargetItems() {
    }

    private static Set<String> sourceBlocks() {
        Set<String> blocks = new HashSet<>();
        FORTUNE_DROPS.values().forEach(blocks::addAll);
        PLAIN_DROPS.values().forEach(blocks::addAll);
        // These drop themselves whatever they're mined with.
        blocks.removeAll(PLAIN_DROPS.keySet());
        return blocks;
    }

    /** True if the bot knows that this item comes from mining some other block. */
    public static boolean comesFromAnotherBlock(String item) {
        return FORTUNE_DROPS.containsKey(item) || PLAIN_DROPS.containsKey(item);
    }

    /** The blocks to mine to get this item. Anything not known to drop something else is its own block. */
    public static List<String> blocksFor(String item) {
        List<String> blocks = FORTUNE_DROPS.get(item);
        if (blocks == null) blocks = PLAIN_DROPS.get(item);
        return blocks != null ? blocks : List.of(item);
    }

    public static ToolNeed toolNeedFor(String item) {
        if (FORTUNE_DROPS.containsKey(item)) return ToolNeed.FORTUNE;
        if (PLAIN_DROPS.containsKey(item)) return ToolNeed.NO_SILK_TOUCH;
        if (SOURCE_BLOCKS.contains(item) || SILK_TOUCH_ONLY.contains(item)) return ToolNeed.SILK_TOUCH;
        return ToolNeed.ANY;
    }

    /** Every block to mine to get these items. */
    public static Set<String> blocksToMine(Collection<String> items) {
        Set<String> blocks = new LinkedHashSet<>();
        for (String item : items) blocks.addAll(blocksFor(item));
        return blocks;
    }

    /** The blocks to mine for these items where it matters what tool is used. */
    public static Set<String> blocksNeedingARightTool(Collection<String> items) {
        Set<String> blocks = new LinkedHashSet<>();
        for (String item : items) {
            if (toolNeedFor(item) != ToolNeed.ANY) blocks.addAll(blocksFor(item));
        }
        return blocks;
    }

    /**
     * A tool that suits all of these items, or null if there isn't one. The pathfinder uses one tool for
     * everything it's mining, so diamonds and diamond ore can't be gone after at the same time.
     */
    public static @Nullable ToolNeed combinedToolNeed(Collection<String> items) {
        ToolNeed combined = ToolNeed.ANY;
        for (String item : items) {
            ToolNeed need = toolNeedFor(item);
            if (need == ToolNeed.ANY) continue;
            if (combined == ToolNeed.ANY || combined == need) {
                combined = need;
            } else if (combined == ToolNeed.SILK_TOUCH || need == ToolNeed.SILK_TOUCH) {
                return null;
            } else {
                // One wants Fortune and the other just no Silk Touch, which a Fortune tool also is.
                combined = ToolNeed.FORTUNE;
            }
        }
        return combined;
    }

    /** The items the bot keeps a stack of and fills its empty slots with. */
    public static Set<String> itemsFor(Collection<String> items) {
        return new HashSet<>(items);
    }

    /** How many of these items the bot isn't holding any of. */
    public static int kindsNotHeld(Collection<String> items, Set<String> heldItems) {
        return (int) items.stream().distinct().filter(item -> !heldItems.contains(item)).count();
    }
}
