package com.zenithplugins.blockminer.rules;

import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapedCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapelessCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.CompositeSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.TagSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.WithRemainderSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.SlotDisplay;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeMatcherTest {

    private static final int NUGGET = 1;
    private static final int INGOT = 2;
    private static final int BLOCK = 3;

    private static SlotDisplay item(int id) {
        return new ItemSlotDisplay(id);
    }

    private static ShapedCraftingRecipeDisplay shaped(int input, int output) {
        List<SlotDisplay> ingredients = Collections.nCopies(9, item(input));
        return new ShapedCraftingRecipeDisplay(3, 3, ingredients, item(output), item(99));
    }

    @Test
    void matchesTheResultAndTheIngredients() {
        assertTrue(RecipeMatcher.matches(shaped(INGOT, BLOCK), INGOT, BLOCK));
    }

    @Test
    void theSameResultFromAnotherIngredientDoesNotMatch() {
        assertFalse(RecipeMatcher.matches(shaped(BLOCK, INGOT), NUGGET, INGOT));
        assertTrue(RecipeMatcher.matches(shaped(NUGGET, INGOT), NUGGET, INGOT));
    }

    @Test
    void aMixOfIngredientsDoesNotMatch() {
        List<SlotDisplay> ingredients = List.of(item(INGOT), item(NUGGET));
        var display = new ShapedCraftingRecipeDisplay(2, 1, ingredients, item(BLOCK), item(99));
        assertFalse(RecipeMatcher.matches(display, INGOT, BLOCK));
    }

    /** How a real server sends a block's recipe: each ingredient a list of one, and the result a stack. */
    private static ShapedCraftingRecipeDisplay asARealServerSendsIt(int input, int output) {
        SlotDisplay ingredient = new CompositeSlotDisplay(List.of(item(input)));
        return new ShapedCraftingRecipeDisplay(
            3, 3, Collections.nCopies(9, ingredient), new ItemStackSlotDisplay(new ItemStack(output, 1)), item(99));
    }

    @Test
    void matchesARecipeTheWayARealServerSendsIt() {
        assertTrue(RecipeMatcher.matches(asARealServerSendsIt(INGOT, BLOCK), INGOT, BLOCK));
    }

    @Test
    void doesntMatchTheWrongIngredientOrResultTheWayARealServerSendsIt() {
        assertFalse(RecipeMatcher.matches(asARealServerSendsIt(NUGGET, BLOCK), INGOT, BLOCK));
        assertFalse(RecipeMatcher.matches(asARealServerSendsIt(INGOT, NUGGET), INGOT, BLOCK));
    }

    @Test
    void anIngredientWithSomeLeftBehindStillCounts() {
        SlotDisplay ingredient = new WithRemainderSlotDisplay(item(INGOT), item(NUGGET));
        var display = new ShapedCraftingRecipeDisplay(1, 1, List.of(ingredient), item(BLOCK), item(99));

        assertTrue(RecipeMatcher.matches(display, INGOT, BLOCK));
    }

    @Test
    void aTagIsntAnItemItCanCheck() {
        SlotDisplay ingredient = new TagSlotDisplay(net.kyori.adventure.key.Key.key("minecraft:planks"));
        var display = new ShapedCraftingRecipeDisplay(1, 1, List.of(ingredient), item(BLOCK), item(99));

        assertFalse(RecipeMatcher.matches(display, INGOT, BLOCK));
    }

    @Test
    void shapelessRecipesCountToo() {
        var display = new ShapelessCraftingRecipeDisplay(List.of(item(BLOCK)), item(INGOT), item(99));
        assertTrue(RecipeMatcher.matches(display, BLOCK, INGOT));
    }
}
