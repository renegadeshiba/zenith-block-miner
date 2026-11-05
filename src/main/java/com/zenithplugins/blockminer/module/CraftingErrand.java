package com.zenithplugins.blockminer.module;

import com.zenith.feature.inventory.actions.PlaceRecipe;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.CompactRecipes;
import com.zenithplugins.blockminer.rules.RecipeMatcher;
import com.zenithplugins.blockminer.rules.TargetItems;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;

import java.util.Arrays;

import static com.zenith.Globals.CACHE;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Crafts items like diamonds into blocks at a crafting table. A stack of the material being mined
 * stays uncrafted, because that's what fills the inventory's empty slots and keeps junk out.
 */
final class CraftingErrand {

    private static final int RESULT_SLOT = 0;
    private static final int FIRST_GRID_SLOT = 1;
    private static final int GRID_SLOTS = 9;
    private static final int TABLE_SEARCH_RADIUS = 5;
    // The recipe book fills the grid with up to this many crafts at once.
    private static final int MAX_CRAFTS_AT_ONCE = 64;
    // A full inventory of coal takes a few big batches, then up to 64 one at a time.
    private static final int MAX_BATCHES_PER_RECIPE = 100;
    private static final int RECIPE_BOOK_WAIT_MS = 3_000;

    private final BlockMinerModule module;
    private final Bot bot;
    private final InventorySpace space;

    CraftingErrand(BlockMinerModule module, Bot bot, InventorySpace space) {
        this.module = module;
        this.bot = bot;
        this.space = space;
    }

    /**
     * Crafts everything it can at a table nearby or a carried one, then always breaks and collects
     * the table, so the bot has one wherever it is the next time it needs to craft.
     */
    void run() {
        if (!anythingToCraft()) return;

        ItemData tableItem = ItemRegistry.CRAFTING_TABLE;
        int[] table = bot.findBlockNearby(tableItem.name(), TABLE_SEARCH_RADIUS);
        if (table == null) {
            if (bot.countItem(tableItem.id()) == 0) {
                module.warn("No crafting table nearby or in the inventory, so items are being stored uncrafted");
                return;
            }
            table = bot.placeBeside(tableItem);
        }

        try {
            bot.openContainer(table, "The crafting table");
            for (CompactRecipes.Recipe recipe : CompactRecipes.ALL) craftAll(recipe);
        } catch (ErrandFailed e) {
            collectTable(table, tableItem);
            throw e;
        }
        collectTable(table, tableItem);
    }

    /** Losing a crafting table isn't worth ending the errand over, as there's still storing to do. */
    private void collectTable(int[] table, ItemData tableItem) {
        try {
            space.breakAndPickUp(table, tableItem);
        } catch (ErrandFailed e) {
            module.warn("Couldn't collect the crafting table at {}: {}", Arrays.toString(table), e.getMessage());
        }
    }

    private boolean anythingToCraft() {
        for (CompactRecipes.Recipe recipe : CompactRecipes.ALL) {
            ItemData input = ItemRegistry.REGISTRY.get(recipe.input());
            if (input != null && craftsToMake(input) > 0) return true;
        }
        return false;
    }

    private boolean isTarget(ItemData item) {
        return TargetItems.itemsFor(PLUGIN_CONFIG.wantedItems).contains(item.name());
    }

    private int craftsToMake(ItemData input) {
        return CompactRecipes.craftsToMake(bot.countItem(input.id()), isTarget(input), input.stackSize());
    }

    private void craftAll(CompactRecipes.Recipe recipe) {
        ItemData input = ItemRegistry.REGISTRY.get(recipe.input());
        ItemData output = ItemRegistry.REGISTRY.get(recipe.output());
        if (input == null || output == null) return;

        for (int batch = 0; batch < MAX_BATCHES_PER_RECIPE; batch++) {
            if (craftsToMake(input) == 0) return;
            if (!craftBatch(input, output)) {
                module.warn("Couldn't craft {} into {}; storing what's left as it is", input.name(), output.name());
                return;
            }
        }
    }

    private boolean craftBatch(ItemData input, ItemData output) {
        int before = bot.countItem(output.id());
        if (!fillGrid(input, output)) return false;
        // Shift-clicking the result keeps crafting until the grid runs out.
        if (!bot.shiftClick(RESULT_SLOT)) return false;
        if (!bot.waitFor(() -> bot.countItem(output.id()) > before, Bot.ACTION_TIMEOUT_MS)) return false;
        bot.sleep(Bot.CLICK_PACING_MS);
        return true;
    }

    /**
     * The recipe book only has recipes the server has unlocked for this account. Without one, this
     * places the items by hand for a single craft.
     */
    private boolean fillGrid(ItemData input, ItemData output) {
        int recipeId = findRecipe(input.id(), output.id());
        if (recipeId != -1) {
            // Filling the grid takes as many as it can hold, which is fine unless that would eat into
            // the stack being kept. Below that, the recipe book can place a single craft instead.
            boolean fillGridCompletely = !isTarget(input) || craftsToMake(input) >= MAX_CRAFTS_AT_ONCE;
            var packet = new PlaceRecipe(bot.openContainerId(), recipeId, fillGridCompletely).packet();
            if (packet != null) {
                module.sendClientPacketAsync(packet);
                if (bot.waitFor(() -> resultIs(output), RECIPE_BOOK_WAIT_MS)) return true;
            }
        }
        return gridIsEmpty() && fillGridByHand(input)
            && bot.waitFor(() -> resultIs(output), Bot.ACTION_TIMEOUT_MS);
    }

    private int findRecipe(int inputId, int outputId) {
        try {
            var entries = CACHE.getRecipeCache().getRecipeBookEntries();
            for (var entry : entries.int2ObjectEntrySet()) {
                if (RecipeMatcher.matches(entry.getValue().display(), inputId, outputId)) return entry.getIntKey();
            }
        } catch (RuntimeException e) {
            // The recipe book is updated from the network thread; placing by hand always works.
            module.debug("Couldn't read the recipe book: {}", e.getMessage());
        }
        return -1;
    }

    private boolean resultIs(ItemData output) {
        var result = bot.stackAt(RESULT_SLOT);
        return result != null && result.getId() == output.id() && result.getAmount() > 0;
    }

    private boolean gridIsEmpty() {
        for (int slot = FIRST_GRID_SLOT; slot < FIRST_GRID_SLOT + GRID_SLOTS; slot++) {
            var stack = bot.stackAt(slot);
            if (stack != null && stack.getAmount() > 0) return false;
        }
        return true;
    }

    /** Picks up each stack, right-clicks one item into each grid slot, and puts the rest back. */
    private boolean fillGridByHand(ItemData input) {
        int nextGridSlot = FIRST_GRID_SLOT;
        for (int slot : bot.playerSlots(stack -> stack.getId() == input.id())) {
            int amount = bot.stackAt(slot).getAmount();
            int place = Math.min(amount, FIRST_GRID_SLOT + GRID_SLOTS - nextGridSlot);
            bot.click(slot, ClickItemAction.LEFT_CLICK);
            for (int i = 0; i < place; i++) bot.click(nextGridSlot++, ClickItemAction.RIGHT_CLICK);
            if (amount > place) bot.click(slot, ClickItemAction.LEFT_CLICK);
            if (nextGridSlot == FIRST_GRID_SLOT + GRID_SLOTS) return true;
        }
        return false;
    }
}
