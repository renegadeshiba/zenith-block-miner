package com.zenithplugins.blockminer.rules;

import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetItemsTest {

    private static final List<String> ITEMS_FROM_OTHER_BLOCKS = List.of(
        "coal", "raw_iron", "raw_copper", "raw_gold", "redstone", "lapis_lazuli", "diamond", "emerald", "quartz",
        "gold_nugget", "glowstone_dust", "amethyst_shard", "prismarine_crystals", "cobblestone", "cobbled_deepslate",
        "clay_ball"
    );

    @Test
    void diamondsComeFromBothKindsOfOreWithFortune() {
        assertEquals(List.of("diamond_ore", "deepslate_diamond_ore"), TargetItems.blocksFor("diamond"));
        assertEquals(ToolNeed.FORTUNE, TargetItems.toolNeedFor("diamond"));
    }

    @Test
    void theOreItselfComesFromJustThatOreWithSilkTouch() {
        assertEquals(List.of("diamond_ore"), TargetItems.blocksFor("diamond_ore"));
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor("diamond_ore"));
        assertEquals(List.of("deepslate_diamond_ore"), TargetItems.blocksFor("deepslate_diamond_ore"));
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor("deepslate_diamond_ore"));
    }

    @Test
    void cobbledDeepslateComesFromDeepslateWithoutSilkTouch() {
        assertTrue(TargetItems.blocksFor("cobbled_deepslate").contains("deepslate"));
        assertEquals(ToolNeed.NO_SILK_TOUCH, TargetItems.toolNeedFor("cobbled_deepslate"));
    }

    @Test
    void deepslateItselfTakesSilkTouch() {
        assertEquals(List.of("deepslate"), TargetItems.blocksFor("deepslate"));
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor("deepslate"));
    }

    @Test
    void cobblestoneAndStoneWorkTheSameWay() {
        assertTrue(TargetItems.blocksFor("cobblestone").contains("stone"));
        assertEquals(ToolNeed.NO_SILK_TOUCH, TargetItems.toolNeedFor("cobblestone"));
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor("stone"));
    }

    /** The pathfinder only goes after a stray drop if a block with its name is on the list. */
    @Test
    void blocksThatDropThemselvesAreMinedForThemselvesToo() {
        assertTrue(TargetItems.blocksFor("cobbled_deepslate").contains("cobbled_deepslate"));
        assertTrue(TargetItems.blocksFor("cobblestone").contains("cobblestone"));
    }

    @Test
    void anythingElseIsItsOwnBlockAndAnyToolDoes() {
        assertEquals(List.of("ancient_debris"), TargetItems.blocksFor("ancient_debris"));
        assertEquals(ToolNeed.ANY, TargetItems.toolNeedFor("ancient_debris"));
        assertEquals(ToolNeed.ANY, TargetItems.toolNeedFor("obsidian"));
        assertEquals(ToolNeed.ANY, TargetItems.toolNeedFor("diamond_block"));
    }

    @Test
    void blocksThatDropNothingWithoutSilkTouchNeedIt() {
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor("blue_ice"));
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor("glass"));
    }

    @Test
    void itKnowsWhichItemsComeFromAnotherBlock() {
        assertTrue(TargetItems.comesFromAnotherBlock("diamond"));
        assertTrue(TargetItems.comesFromAnotherBlock("cobbled_deepslate"));
        assertFalse(TargetItems.comesFromAnotherBlock("diamond_ore"));
        assertFalse(TargetItems.comesFromAnotherBlock("diamond_sword"));
    }

    @Test
    void everyNameMatchesARealBlockOrItem() {
        for (String item : ITEMS_FROM_OTHER_BLOCKS) {
            assertNotNull(ItemRegistry.REGISTRY.get(item), item);
            assertTrue(TargetItems.comesFromAnotherBlock(item), item + " is missing from the rules");
            for (String block : TargetItems.blocksFor(item)) {
                assertNotNull(BlockRegistry.REGISTRY.get(block), block);
                // Every block has to be something you can also ask for, whichever tool that takes.
                assertNotNull(ItemRegistry.REGISTRY.get(block), block);
            }
        }
        for (String block : List.of("ice", "packed_ice", "blue_ice", "glass", "bookshelf", "sculk", "sculk_catalyst")) {
            assertNotNull(BlockRegistry.REGISTRY.get(block), block);
            assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor(block), block);
        }
    }

    @Test
    void everySourceBlockTakesSilkTouchUnlessItDropsItself() {
        for (String item : ITEMS_FROM_OTHER_BLOCKS) {
            for (String block : TargetItems.blocksFor(item)) {
                if (block.equals(item)) continue;
                assertEquals(ToolNeed.SILK_TOUCH, TargetItems.toolNeedFor(block), block);
            }
        }
    }

    @Test
    void allTheBlocksForSeveralItemsGetMined() {
        assertEquals(
            Set.of("diamond_ore", "deepslate_diamond_ore", "ancient_debris"),
            TargetItems.blocksToMine(List.of("diamond", "ancient_debris"))
        );
    }

    @Test
    void onlyBlocksWhereItMattersNeedTheRightTool() {
        assertEquals(
            Set.of("diamond_ore", "deepslate_diamond_ore"),
            TargetItems.blocksNeedingARightTool(List.of("diamond", "ancient_debris"))
        );
        assertTrue(TargetItems.blocksNeedingARightTool(List.of("ancient_debris")).isEmpty());
    }

    @Test
    void itemsThatDontMindAgreeWithAnything() {
        assertEquals(ToolNeed.ANY, TargetItems.combinedToolNeed(List.of()));
        assertEquals(ToolNeed.ANY, TargetItems.combinedToolNeed(List.of("ancient_debris", "obsidian")));
        assertEquals(ToolNeed.FORTUNE, TargetItems.combinedToolNeed(List.of("ancient_debris", "diamond")));
        assertEquals(ToolNeed.SILK_TOUCH, TargetItems.combinedToolNeed(List.of("diamond_ore", "ancient_debris")));
    }

    @Test
    void aFortuneToolSuitsItemsThatJustCantHaveSilkTouch() {
        assertEquals(ToolNeed.FORTUNE, TargetItems.combinedToolNeed(List.of("diamond", "cobbled_deepslate")));
        assertEquals(ToolNeed.FORTUNE, TargetItems.combinedToolNeed(List.of("cobbled_deepslate", "diamond")));
        assertEquals(ToolNeed.NO_SILK_TOUCH, TargetItems.combinedToolNeed(List.of("cobblestone", "cobbled_deepslate")));
    }

    @Test
    void silkTouchCantBeMixedWithItemsThatMustNotHaveIt() {
        assertNull(TargetItems.combinedToolNeed(List.of("diamond", "diamond_ore")));
        assertNull(TargetItems.combinedToolNeed(List.of("diamond_ore", "diamond")));
        assertNull(TargetItems.combinedToolNeed(List.of("cobbled_deepslate", "deepslate")));
        assertNull(TargetItems.combinedToolNeed(List.of("deepslate", "coal")));
    }

    @Test
    void theBotKeepsExactlyTheItemsYouAskedFor() {
        assertEquals(Set.of("diamond"), TargetItems.itemsFor(List.of("diamond")));
        assertEquals(Set.of("diamond_ore"), TargetItems.itemsFor(List.of("diamond_ore")));
    }

    @Test
    void itCountsTheItemsItHasNoneOf() {
        List<String> items = List.of("diamond", "coal");
        assertEquals(2, TargetItems.kindsNotHeld(items, Set.of()));
        assertEquals(1, TargetItems.kindsNotHeld(items, Set.of("coal")));
        assertEquals(0, TargetItems.kindsNotHeld(items, Set.of("coal", "diamond")));
        assertEquals(1, TargetItems.kindsNotHeld(List.of("diamond", "diamond"), Set.of()));
    }
}
