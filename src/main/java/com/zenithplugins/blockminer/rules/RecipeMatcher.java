package com.zenithplugins.blockminer.rules;

import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.RecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapedCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapelessCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.CompositeSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.EmptySlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.SlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.WithRemainderSlotDisplay;

import java.util.List;

/**
 * Picks a recipe out of the server's recipe book. Matching on the ingredient as well as the result
 * matters: a gold ingot can also be made from a gold block, or smelted.
 */
public final class RecipeMatcher {

    private RecipeMatcher() {
    }

    /** True for a crafting recipe that makes {@code outputId} and uses nothing but {@code inputId}. */
    public static boolean matches(RecipeDisplay display, int inputId, int outputId) {
        List<SlotDisplay> ingredients;
        SlotDisplay result;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            ingredients = shaped.ingredients();
            result = shaped.result();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            ingredients = shapeless.ingredients();
            result = shapeless.result();
        } else {
            return false;
        }
        if (!isItem(result, outputId)) return false;

        boolean usesInput = false;
        for (SlotDisplay ingredient : ingredients) {
            if (ingredient instanceof EmptySlotDisplay) continue;
            if (!isItem(ingredient, inputId)) return false;
            usesInput = true;
        }
        return usesInput;
    }

    /**
     * A server sends an ingredient as a list of the items that would do, even when that's a list of one, and
     * something that leaves an item behind in the grid, like a bucket, comes wrapped up with that as well.
     */
    private static boolean isItem(SlotDisplay slot, int itemId) {
        if (slot instanceof ItemSlotDisplay item) return item.item() == itemId;
        if (slot instanceof ItemStackSlotDisplay stack) return stack.itemStack().getId() == itemId;
        if (slot instanceof CompositeSlotDisplay composite) {
            return composite.contents().stream().anyMatch(content -> isItem(content, itemId));
        }
        if (slot instanceof WithRemainderSlotDisplay remainder) return isItem(remainder.input(), itemId);
        return false;
    }
}
