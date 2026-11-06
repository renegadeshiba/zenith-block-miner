package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactRecipesTest {

    @Test
    void whatSomethingCraftsIntoGoesAllTheWayUp() {
        assertEquals(java.util.Set.of("gold_ingot", "gold_block"), CompactRecipes.craftedFrom(java.util.Set.of("gold_nugget")));
        assertEquals(java.util.Set.of("diamond_block"), CompactRecipes.craftedFrom(java.util.Set.of("diamond")));
        assertEquals(java.util.Set.of(), CompactRecipes.craftedFrom(java.util.Set.of("cobbled_deepslate")));
    }

    @Test
    void everyItemNameExists() {
        for (CompactRecipes.Recipe recipe : CompactRecipes.ALL) {
            assertNotNull(ItemRegistry.REGISTRY.get(recipe.input()), recipe.input());
            assertNotNull(ItemRegistry.REGISTRY.get(recipe.output()), recipe.output());
        }
    }

    @Test
    void anythingMadeByARecipeIsCraftedBeforeItIsUsedByAnother() {
        for (int i = 0; i < CompactRecipes.ALL.size(); i++) {
            String output = CompactRecipes.ALL.get(i).output();
            for (int j = 0; j < i; j++) {
                assertFalse(CompactRecipes.ALL.get(j).input().equals(output),
                    output + " is used before the recipe that makes it");
            }
        }
    }

    @Test
    void aStackOfTheMaterialBeingMinedStaysUncrafted() {
        assertEquals(0, CompactRecipes.craftsToMake(64, true, 64));
        assertEquals(0, CompactRecipes.craftsToMake(72, true, 64));
        assertEquals(1, CompactRecipes.craftsToMake(73, true, 64));
        assertEquals(64, CompactRecipes.craftsToMake(640, true, 64));
    }

    @Test
    void materialThatIsNotBeingMinedAllGetsCrafted() {
        assertEquals(0, CompactRecipes.craftsToMake(8, false, 64));
        assertEquals(7, CompactRecipes.craftsToMake(64, false, 64));
    }

    @Test
    void getsBackEnoughBlocksToEndUpWithAStack() {
        assertEquals(0, CompactRecipes.blocksToGetBack(64, 64));
        assertEquals(0, CompactRecipes.blocksToGetBack(100, 64));
        assertEquals(1, CompactRecipes.blocksToGetBack(63, 64));
        assertEquals(1, CompactRecipes.blocksToGetBack(55, 64));
        assertEquals(2, CompactRecipes.blocksToGetBack(54, 64));
        assertEquals(8, CompactRecipes.blocksToGetBack(0, 64));
    }

    @Test
    void recognisesInputsButNotBlocks() {
        assertTrue(CompactRecipes.isInput("diamond"));
        assertTrue(CompactRecipes.isInput("gold_nugget"));
        assertFalse(CompactRecipes.isInput("diamond_block"));
        assertFalse(CompactRecipes.isInput("netherrack"));
    }
}
