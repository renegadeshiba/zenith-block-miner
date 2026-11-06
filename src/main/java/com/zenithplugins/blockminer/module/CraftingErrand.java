package com.zenithplugins.blockminer.module;

import com.zenith.feature.inventory.actions.PlaceRecipe;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.CompactRecipes;
import com.zenithplugins.blockminer.rules.RecipeMatcher;
import com.zenithplugins.blockminer.rules.TargetItems;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static com.zenith.Globals.CACHE;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Crafts items like diamonds into blocks at a crafting table, filling the grid as full as it'll go and
 * shift-clicking the result to craft it all at once. A stack of the material being mined stays uncrafted,
 * because that's what fills the inventory's empty slots and keeps junk out.
 */
final class CraftingErrand {

    private static final int RESULT_SLOT = 0;
    private static final int FIRST_GRID_SLOT = 1;
    private static final int GRID_SLOTS = 9;
    private static final int END_OF_GRID = FIRST_GRID_SLOT + GRID_SLOTS;
    private static final int TABLE_SEARCH_RADIUS = 5;
    // A full inventory only takes a few goes. This is just so that it can't go on forever if something's wrong.
    private static final int MAX_BATCHES_PER_RECIPE = 300;
    private static final int RECIPE_BOOK_WAIT_MS = 3_000;
    // Below this, moving nine whole stacks is more clicks than putting them in one at a time.
    private static final int SMALLEST_WHOLE_STACK = 3;

    private final BlockMinerModule module;
    private final Bot bot;
    private final InventorySpace space;
    private boolean recipeBookWorks;

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
            craftEverything();
        } catch (ErrandFailed e) {
            collectTable(table, tableItem);
            throw e;
        }
        collectTable(table, tableItem);
    }

    /** With a crafting table already open. */
    void craftEverything() {
        recipeBookWorks = true;
        for (CompactRecipes.Recipe recipe : CompactRecipes.ALL) craftAll(recipe);
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
        if (input == null || output == null || craftsToMake(input) == 0) return;

        for (int batch = 0; batch < MAX_BATCHES_PER_RECIPE && craftsToMake(input) > 0; batch++) {
            boolean crafted = craftBatch(input, output);
            // Stacks of different sizes don't all run out at once, and what's in the grid doesn't get counted.
            returnGridToInventory();
            if (!crafted) {
                module.warn("Couldn't craft {} into {}; storing what's left as it is", input.name(), output.name());
                break;
            }
        }
        if (isTarget(input)) getBackWhatItsKeeping(input, output);
    }

    private boolean craftBatch(ItemData input, ItemData output) {
        int before = bot.countItem(output.id());
        if (!fillGrid(input, output)) return false;
        // Shift-clicking the result keeps crafting until the grid runs out, which is when the result goes.
        if (!bot.shiftClick(RESULT_SLOT)) return false;
        return bot.waitFor(() -> bot.countItem(output.id()) > before && !resultIs(output), Bot.ACTION_TIMEOUT_MS);
    }

    /** The recipe book only has recipes the server has unlocked for this account. Without one, it goes by hand. */
    private boolean fillGrid(ItemData input, ItemData output) {
        if (recipeBookWorks) {
            int recipeId = findRecipe(input.id(), output.id());
            var packet = recipeId == -1 ? null : new PlaceRecipe(bot.openContainerId(), recipeId, true).packet();
            if (packet != null) {
                module.sendClientPacketAsync(packet);
                if (bot.waitFor(() -> resultIs(output), RECIPE_BOOK_WAIT_MS)) return true;
                // It would wait this long every time otherwise, and the rest of the recipes won't be any different.
                module.warn("The recipe book isn't doing anything for {}, so crafting by hand", output.name());
                recipeBookWorks = false;
            }
        }
        return fillGridByHand(input) && bot.waitFor(() -> resultIs(output), Bot.ACTION_TIMEOUT_MS);
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

    private boolean resultIs(ItemData item) {
        ItemStack result = bot.stackAt(RESULT_SLOT);
        return result != null && result.getId() == item.id() && result.getAmount() > 0;
    }

    private boolean gridIsEmpty() {
        for (int slot = FIRST_GRID_SLOT; slot < END_OF_GRID; slot++) {
            if (amountAt(slot) > 0) return false;
        }
        return true;
    }

    private int amountAt(int slot) {
        ItemStack stack = bot.stackAt(slot);
        return stack == null ? 0 : stack.getAmount();
    }

    private List<Integer> stacksOf(ItemData item) {
        return bot.playerSlots(stack -> stack.getId() == item.id()).stream()
            .sorted(Comparator.comparingInt(slot -> -amountAt(slot)))
            .toList();
    }

    /** Whole stacks if it can, and if not, a few from each stack it has, one click at a time. */
    private boolean fillGridByHand(ItemData input) {
        returnGridToInventory();
        if (!gridIsEmpty()) return false;

        List<Integer> stacks = stacksOf(input);
        int crafts = craftsToMake(input);
        if (stacks.size() >= GRID_SLOTS) {
            int smallest = amountAt(stacks.get(GRID_SLOTS - 1));
            if (smallest >= SMALLEST_WHOLE_STACK && smallest <= crafts) {
                for (int i = 0; i < GRID_SLOTS; i++) moveWholeStack(stacks.get(i), FIRST_GRID_SLOT + i);
                return true;
            }
        }
        int fromTheLargest = stacks.isEmpty() ? 0 : amountAt(stacks.getFirst()) / GRID_SLOTS;
        return putInEachGridSlot(Math.max(1, Math.min(crafts, fromTheLargest)), stacks);
    }

    private void moveWholeStack(int from, int to) {
        int amount = amountAt(from);
        bot.clickAndConfirm(from, ClickItemAction.LEFT_CLICK, () -> bot.cursorAmount() == amount);
        bot.clickAndConfirm(to, ClickItemAction.LEFT_CLICK, () -> bot.cursorIsEmpty() && amountAt(to) == amount);
    }

    /** Picks up each stack, right-clicks that many into each grid slot, and puts back what's left of the stack. */
    private boolean putInEachGridSlot(int each, List<Integer> stacks) {
        int gridSlot = FIRST_GRID_SLOT;
        int inThisSlot = 0;
        for (int stack : stacks) {
            int onCursor = amountAt(stack);
            int pickedUp = onCursor;
            bot.clickAndConfirm(stack, ClickItemAction.LEFT_CLICK, () -> bot.cursorAmount() == pickedUp);
            while (onCursor > 0 && gridSlot < END_OF_GRID) {
                int target = gridSlot;
                int left = onCursor - 1;
                int nowInSlot = inThisSlot + 1;
                bot.clickAndConfirm(target, ClickItemAction.RIGHT_CLICK,
                    () -> amountAt(target) == nowInSlot && bot.cursorAmount() == left);
                onCursor = left;
                inThisSlot = nowInSlot;
                if (inThisSlot == each) {
                    gridSlot++;
                    inThisSlot = 0;
                }
            }
            if (onCursor > 0) bot.clickAndConfirm(stack, ClickItemAction.LEFT_CLICK, bot::cursorIsEmpty);
            if (gridSlot == END_OF_GRID) return true;
        }
        return false;
    }

    private void returnGridToInventory() {
        if (gridIsEmpty()) return;
        for (int slot = FIRST_GRID_SLOT; slot < END_OF_GRID; slot++) {
            if (amountAt(slot) > 0) bot.shiftClick(slot);
        }
        bot.waitFor(this::gridIsEmpty, Bot.ACTION_TIMEOUT_MS);
    }

    /**
     * The recipe book shares out all of a material, which takes what it means to keep, so it turns as many blocks
     * as that takes back into the material. It's only a few clicks, and it needs no recipe book to do it. If that
     * doesn't work it's no disaster: the blocks are fine where they are, and mining soon gets it more to keep.
     */
    private void getBackWhatItsKeeping(ItemData input, ItemData output) {
        int blocks = CompactRecipes.blocksToGetBack(bot.countItem(input.id()), input.stackSize());
        List<Integer> stacks = stacksOf(output);
        if (blocks == 0 || stacks.isEmpty()) return;

        int stack = stacks.getFirst();
        int amount = amountAt(stack);
        int using = Math.min(blocks, amount);
        int before = bot.countItem(input.id());
        bot.clickAndConfirm(stack, ClickItemAction.LEFT_CLICK, () -> bot.cursorAmount() == amount);
        for (int placed = 1; placed <= using; placed++) {
            int inSlot = placed;
            bot.clickAndConfirm(FIRST_GRID_SLOT, ClickItemAction.RIGHT_CLICK,
                () -> amountAt(FIRST_GRID_SLOT) == inSlot && bot.cursorAmount() == amount - inSlot);
        }
        if (amount > using) bot.clickAndConfirm(stack, ClickItemAction.LEFT_CLICK, bot::cursorIsEmpty);

        boolean gotIt = bot.waitFor(() -> resultIs(input), Bot.ACTION_TIMEOUT_MS)
            && bot.shiftClick(RESULT_SLOT)
            && bot.waitFor(() -> bot.countItem(input.id()) > before && !resultIs(input), Bot.ACTION_TIMEOUT_MS);
        if (!gotIt) {
            module.warn("Couldn't turn {} back into {} to keep some, so it has less to fill slots with", output.name(), input.name());
            returnGridToInventory();
        }
    }
}
