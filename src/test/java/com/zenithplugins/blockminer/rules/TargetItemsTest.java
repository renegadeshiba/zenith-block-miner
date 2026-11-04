package com.zenithplugins.blockminer.rules;

import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetItemsTest {

    @Test
    void oresTargetTheirDropAndTheOreItselfForSilkTouch() {
        assertEquals(Set.of("diamond", "diamond_ore"), TargetItems.itemsFor(List.of("diamond_ore")));
        assertEquals(Set.of("raw_iron", "iron_ore"), TargetItems.itemsFor(List.of("iron_ore")));
    }

    @Test
    void blocksThatDropThemselvesTargetJustThemselves() {
        assertEquals(Set.of("ancient_debris"), TargetItems.itemsFor(List.of("ancient_debris")));
    }

    @Test
    void everyNameMatchesARealBlockAndItem() {
        List<String> blocks = List.of(
            "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore", "copper_ore", "deepslate_copper_ore",
            "gold_ore", "deepslate_gold_ore", "redstone_ore", "deepslate_redstone_ore", "lapis_ore",
            "deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
            "nether_quartz_ore", "nether_gold_ore", "gilded_blackstone", "stone", "deepslate", "glowstone", "clay",
            "amethyst_cluster"
        );
        for (String block : blocks) {
            assertNotNull(BlockRegistry.REGISTRY.get(block), block);
            for (String item : TargetItems.itemsFor(List.of(block))) {
                assertNotNull(ItemRegistry.REGISTRY.get(item), item);
            }
            assertTrue(TargetItems.itemsFor(List.of(block)).size() == 2, block + " should drop something else");
        }
    }

    @Test
    void oreAndDeepslateOreAreOneKindOfTarget() {
        List<String> blocks = List.of("diamond_ore", "deepslate_diamond_ore");
        assertEquals(1, TargetItems.kindsNotHeld(blocks, Set.of()));
        assertEquals(0, TargetItems.kindsNotHeld(blocks, Set.of("diamond")));
        assertEquals(0, TargetItems.kindsNotHeld(blocks, Set.of("deepslate_diamond_ore")));
    }

    @Test
    void differentDropsAreCountedSeparately() {
        List<String> blocks = List.of("diamond_ore", "coal_ore");
        assertEquals(2, TargetItems.kindsNotHeld(blocks, Set.of()));
        assertEquals(1, TargetItems.kindsNotHeld(blocks, Set.of("coal")));
        assertEquals(0, TargetItems.kindsNotHeld(blocks, Set.of("coal", "diamond")));
    }
}
