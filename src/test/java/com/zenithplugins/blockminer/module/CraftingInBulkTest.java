package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.cache.data.inventory.InventoryCache;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import com.zenithplugins.blockminer.rules.CompactRecipes;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.RecipeDisplayEntry;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapedCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.CompositeSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.SlotDisplay;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPlaceRecipePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the bot's real crafting and ZenithProxy's real click handling against a pretend crafting table that
 * follows vanilla's rules. What this can't show is a real server's own quirks.
 */
class CraftingInBulkTest {

    private static final int CONTAINER_ID = 7;
    private static final int RESULT = 0;
    private static final int GRID_START = 1;
    private static final int GRID_END = 10;
    private static final int PLAYER_START = GRID_END;
    private static final int ALL_SLOTS = PLAYER_START + 36;
    private static final int STACK = 64;

    /** Holds the true contents, and tells the client about changes a little later, like a real one. */
    private static final class FakeCraftingTable {
        final ItemStack[] slots = new ItemStack[ALL_SLOTS];
        ItemStack cursor;
        final long latencyMs;
        boolean refusesRecipeBook;
        int recipeBookUses;
        int clicks;
        final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FakeCraftingTable");
            thread.setDaemon(true);
            return thread;
        });

        FakeCraftingTable(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        void receive(Packet packet) {
            if (packet instanceof ServerboundContainerClickPacket click) {
                // Zenith applies its own prediction as the packet goes out.
                inventory().handleContainerClick(click);
                clicks++;
                if (click.getActionType() == ContainerActionType.SHIFT_CLICK_ITEM) {
                    shiftClick(click.getSlot());
                } else {
                    click(click.getSlot(), (ClickItemAction) click.getActionParam());
                }
                tellClientAboutEverything();
            } else if (packet instanceof ServerboundPlaceRecipePacket place) {
                recipeBookUses++;
                if (!refusesRecipeBook) placeRecipe(place);
                tellClientAboutEverything();
            }
        }

        private static int amount(ItemStack stack) {
            return stack == null ? 0 : stack.getAmount();
        }

        private static ItemStack withAmount(ItemStack stack, int amount) {
            return amount <= 0 ? null : new ItemStack(stack.getId(), amount);
        }

        private void click(int slot, ClickItemAction action) {
            ItemStack inSlot = slots[slot];
            boolean sameItem = cursor != null && inSlot != null && cursor.getId() == inSlot.getId();
            if (action == ClickItemAction.LEFT_CLICK) {
                if (sameItem) {
                    int moved = Math.min(amount(cursor), STACK - amount(inSlot));
                    slots[slot] = withAmount(inSlot, amount(inSlot) + moved);
                    cursor = withAmount(cursor, amount(cursor) - moved);
                } else {
                    slots[slot] = cursor;
                    cursor = inSlot;
                }
            } else if (cursor == null) {
                if (inSlot == null) return;
                int taken = (amount(inSlot) + 1) / 2;
                cursor = withAmount(inSlot, taken);
                slots[slot] = withAmount(inSlot, amount(inSlot) - taken);
            } else if (inSlot == null || (sameItem && amount(inSlot) < STACK)) {
                slots[slot] = withAmount(cursor, amount(inSlot) + 1);
                cursor = withAmount(cursor, amount(cursor) - 1);
            } else {
                slots[slot] = cursor;
                cursor = inSlot;
            }
            updateResult();
        }

        private void shiftClick(int slot) {
            if (slot == RESULT) {
                craftUntilItRunsOut();
            } else if (slot >= GRID_START && slot < GRID_END && slots[slot] != null) {
                ItemStack moving = slots[slot];
                slots[slot] = null;
                intoInventory(moving, false);
                updateResult();
            }
        }

        /** Vanilla tells the client about the result after every single craft, as it goes. */
        private void craftUntilItRunsOut() {
            while (slots[RESULT] != null) {
                ItemStack made = slots[RESULT];
                if (!hasRoomFor(made)) return;
                intoInventory(made, true);
                for (int grid = GRID_START; grid < GRID_END; grid++) {
                    if (slots[grid] != null) slots[grid] = withAmount(slots[grid], amount(slots[grid]) - 1);
                }
                updateResult();
                tellClient(RESULT);
            }
        }

        private boolean hasRoomFor(ItemStack stack) {
            int room = 0;
            for (int slot = PLAYER_START; slot < ALL_SLOTS; slot++) {
                if (slots[slot] == null) room += STACK;
                else if (slots[slot].getId() == stack.getId()) room += STACK - amount(slots[slot]);
            }
            return room >= amount(stack);
        }

        /** Vanilla's rule: on top of matching stacks first, then the first empty slot, from the end for a result. */
        private void intoInventory(ItemStack stack, boolean fromTheEnd) {
            int left = amount(stack);
            for (int pass = 0; pass < 2 && left > 0; pass++) {
                for (int i = 0; i < ALL_SLOTS - PLAYER_START && left > 0; i++) {
                    int slot = fromTheEnd ? ALL_SLOTS - 1 - i : PLAYER_START + i;
                    ItemStack inSlot = slots[slot];
                    if (pass == 0) {
                        if (inSlot == null || inSlot.getId() != stack.getId() || amount(inSlot) >= STACK) continue;
                        int moved = Math.min(left, STACK - amount(inSlot));
                        slots[slot] = withAmount(inSlot, amount(inSlot) + moved);
                        left -= moved;
                    } else if (inSlot == null) {
                        slots[slot] = withAmount(stack, left);
                        left = 0;
                    }
                }
            }
        }

        private void placeRecipe(ServerboundPlaceRecipePacket place) {
            int input = CompactRecipes.ALL.stream()
                .map(recipe -> ItemRegistry.REGISTRY.get(recipe.input()).id())
                .filter(id -> id == place.getRecipe())
                .findFirst()
                .orElseThrow();
            for (int grid = GRID_START; grid < GRID_END; grid++) {
                if (slots[grid] != null) {
                    ItemStack moving = slots[grid];
                    slots[grid] = null;
                    intoInventory(moving, false);
                }
            }
            int total = amountOf(input);
            int each = place.isUseMaxItems() ? Math.min(STACK, total / 9) : 1;
            if (total < 9) return;
            for (int grid = GRID_START; grid < GRID_END; grid++) {
                int needed = each;
                for (int slot = PLAYER_START; slot < ALL_SLOTS && needed > 0; slot++) {
                    if (slots[slot] == null || slots[slot].getId() != input) continue;
                    int taken = Math.min(needed, amount(slots[slot]));
                    slots[slot] = withAmount(slots[slot], amount(slots[slot]) - taken);
                    needed -= taken;
                }
                slots[grid] = new ItemStack(input, each);
            }
            updateResult();
        }

        private void updateResult() {
            List<ItemStack> inGrid = new ArrayList<>();
            for (int grid = GRID_START; grid < GRID_END; grid++) {
                if (slots[grid] != null) inGrid.add(slots[grid]);
            }
            slots[RESULT] = null;
            if (inGrid.isEmpty()) return;
            int id = inGrid.getFirst().getId();
            if (inGrid.stream().anyMatch(stack -> stack.getId() != id)) return;
            for (CompactRecipes.Recipe recipe : CompactRecipes.ALL) {
                if (inGrid.size() == 9 && ItemRegistry.REGISTRY.get(recipe.input()).id() == id) {
                    slots[RESULT] = new ItemStack(ItemRegistry.REGISTRY.get(recipe.output()).id(), 1);
                } else if (inGrid.size() == 1 && ItemRegistry.REGISTRY.get(recipe.output()).id() == id) {
                    slots[RESULT] = new ItemStack(ItemRegistry.REGISTRY.get(recipe.input()).id(), 9);
                }
            }
        }

        private void tellClient(int slot) {
            ItemStack inSlot = slots[slot];
            network.schedule(
                () -> inventory().setItemStack(CONTAINER_ID, slot, inSlot == null ? Container.EMPTY_STACK : inSlot),
                latencyMs, TimeUnit.MILLISECONDS);
        }

        private void tellClientAboutEverything() {
            for (int slot = 0; slot < ALL_SLOTS; slot++) tellClient(slot);
            ItemStack onCursor = cursor;
            network.schedule(
                () -> inventory().setMouseStack(onCursor == null ? Container.EMPTY_STACK : onCursor),
                latencyMs, TimeUnit.MILLISECONDS);
        }

        void settle() throws InterruptedException {
            network.shutdown();
            assertTrue(network.awaitTermination(10, TimeUnit.SECONDS));
        }

        int amountOf(int item) {
            int total = 0;
            for (int slot = PLAYER_START; slot < ALL_SLOTS; slot++) {
                if (slots[slot] != null && slots[slot].getId() == item) total += amount(slots[slot]);
            }
            return total;
        }

        int inGrid() {
            int total = 0;
            for (int grid = GRID_START; grid < GRID_END; grid++) total += amount(slots[grid]);
            return total;
        }
    }

    private static InventoryCache inventory() {
        return CACHE.getPlayerCache().getInventoryCache();
    }

    private static final ItemData COAL = ItemRegistry.COAL;
    private static final ItemData COAL_BLOCK = ItemRegistry.COAL_BLOCK;

    private FakeCraftingTable table;
    private CraftingErrand crafting;
    private BlockMinerConfig previousConfig;
    private long startedAt;

    private void startWith(long latencyMs) {
        table = new FakeCraftingTable(latencyMs);
        BlockMinerModule module = new BlockMinerModule() {
            @Override
            public void sendClientPacketAsync(Packet packet) {
                table.receive(packet);
            }
        };
        Bot bot = new Bot(module, () -> true);
        crafting = new CraftingErrand(module, bot, new InventorySpace(module, bot));
        inventory().reset();
        inventory().openContainer(CONTAINER_ID, ContainerType.CRAFTING, Component.text("Crafting"));
    }

    @BeforeEach
    void setUp() {
        previousConfig = BlockMinerPlugin.PLUGIN_CONFIG;
        BlockMinerPlugin.PLUGIN_CONFIG = new BlockMinerConfig();
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of("coal"));
        CACHE.getRecipeCache().getRecipeBookEntries().clear();
    }

    @AfterEach
    void tearDown() {
        inventory().reset();
        CACHE.getRecipeCache().getRecipeBookEntries().clear();
        BlockMinerPlugin.PLUGIN_CONFIG = previousConfig;
    }

    /**
     * What a real server's recipe book has in it: each ingredient is a list of what would do, even for a list of
     * one, and the result is a stack. The pretend server goes by the input's item ID as the recipe's ID.
     */
    private static void recipeBookKnows(CompactRecipes.Recipe... recipes) {
        for (CompactRecipes.Recipe recipe : recipes) {
            int input = ItemRegistry.REGISTRY.get(recipe.input()).id();
            int output = ItemRegistry.REGISTRY.get(recipe.output()).id();
            SlotDisplay ingredient = new CompositeSlotDisplay(List.of(new ItemSlotDisplay(input)));
            var display = new ShapedCraftingRecipeDisplay(
                3, 3, Collections.nCopies(9, ingredient),
                new ItemStackSlotDisplay(new ItemStack(output, 1)),
                new ItemSlotDisplay(ItemRegistry.CRAFTING_TABLE.id()));
            CACHE.getRecipeCache().getRecipeBookEntries().put(input, new RecipeDisplayEntry(input, display, OptionalInt.empty(), 0, List.of()));
        }
    }

    private static final CompactRecipes.Recipe COAL_RECIPE = new CompactRecipes.Recipe("coal", "coal_block");

    private void put(int inventorySlot, ItemData item, int amount) {
        int slot = PLAYER_START + inventorySlot;
        ItemStack stack = new ItemStack(item.id(), amount);
        table.slots[slot] = stack;
        inventory().setItemStack(CONTAINER_ID, slot, stack);
    }

    private void carry(int fullStacks, ItemData item) {
        for (int stack = 0; stack < fullStacks; stack++) put(stack, item, STACK);
    }

    private void craft() throws InterruptedException {
        startedAt = System.currentTimeMillis();
        crafting.craftEverything();
        table.settle();
    }

    private void assertMade(int blocks, int coalTotal) {
        assertEquals(blocks, table.amountOf(COAL_BLOCK.id()), "blocks made");
        assertEquals(coalTotal - blocks * 9, table.amountOf(COAL.id()), "coal left, with nothing lost");
        assertEquals(0, table.inGrid(), "left in the grid");
        assertNull(table.cursor, "left on the cursor");
    }

    // ----------------------------------------------------------------------------------------
    // with the recipe book
    // ----------------------------------------------------------------------------------------

    @Test
    void craftsAllOfAFullInventoryInAFewGoes() throws InterruptedException {
        startWith(30);
        recipeBookKnows(COAL_RECIPE);
        carry(30, COAL);

        craft();

        // Everything beyond a stack of it: (30 * 64 - 64) / 9.
        assertMade(206, 30 * STACK);
        assertTrue(table.recipeBookUses <= 5, "used the recipe book " + table.recipeBookUses + " times");
    }

    @Test
    void itWorksOnALaggyConnectionToo() throws InterruptedException {
        startWith(300);
        recipeBookKnows(COAL_RECIPE);
        carry(20, COAL);

        craft();

        assertMade(135, 20 * STACK);
    }

    @Test
    void keepsAStackOfWhatItsMiningWhateverThereIs() throws InterruptedException {
        // Just over enough for one, a couple of stacks, and just under a full grid.
        int[][] coalAndBlocks = {{73, 1}, {128, 7}, {192, 14}, {575, 56}, {640, 64}, {641, 64}, {649, 65}};
        for (int[] expected : coalAndBlocks) {
            startWith(5);
            recipeBookKnows(COAL_RECIPE);
            int left = expected[0];
            for (int slot = 0; left > 0; slot++) {
                put(slot, COAL, Math.min(left, STACK));
                left -= STACK;
            }

            craft();

            assertMade(expected[1], expected[0]);
            assertTrue(table.amountOf(COAL.id()) >= STACK, "only kept " + table.amountOf(COAL.id()) + " of " + expected[0]);
        }
    }

    @Test
    void leavesItAloneWithNotEnoughToCraftOne() throws InterruptedException {
        startWith(5);
        recipeBookKnows(COAL_RECIPE);
        put(0, COAL, STACK);
        put(1, COAL, 8);

        craft();

        assertMade(0, 72);
        assertEquals(0, table.recipeBookUses);
        assertEquals(0, table.clicks);
    }

    @Test
    void craftsAllOfWhatItsNotKeepingAnyOfIncludingTheLastStack() throws InterruptedException {
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of("cobbled_deepslate"));
        startWith(5);
        recipeBookKnows(COAL_RECIPE);
        carry(10, COAL);

        craft();

        assertMade(71, 10 * STACK);
    }

    @Test
    void goesAllTheWayUpFromNuggetsAndKeepsAStackOfThem() throws InterruptedException {
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of("gold_nugget"));
        startWith(5);
        recipeBookKnows(new CompactRecipes.Recipe("gold_nugget", "gold_ingot"), new CompactRecipes.Recipe("gold_ingot", "gold_block"));
        carry(20, ItemRegistry.GOLD_NUGGET);

        craft();

        int nuggets = table.amountOf(ItemRegistry.GOLD_NUGGET.id());
        int ingots = table.amountOf(ItemRegistry.GOLD_INGOT.id());
        int blocks = table.amountOf(ItemRegistry.GOLD_BLOCK.id());
        assertEquals(20 * STACK, nuggets + ingots * 9 + blocks * 81, "nothing lost");
        assertTrue(nuggets >= STACK && nuggets < STACK + 9, "kept " + nuggets);
        assertTrue(ingots < 9, "ingots left " + ingots);
        assertTrue(blocks > 0);
    }

    // ----------------------------------------------------------------------------------------
    // without it
    // ----------------------------------------------------------------------------------------

    @Test
    void usesWholeStacksByHandWhenTheRecipeBookDoesntHaveIt() throws InterruptedException {
        startWith(5);
        carry(30, COAL);

        craft();

        assertMade(206, 30 * STACK);
        assertEquals(0, table.recipeBookUses);
        // One at a time would take over 2,000 clicks.
        assertTrue(table.clicks < 300, "took " + table.clicks + " clicks");
    }

    @Test
    void goesByHandIfTheRecipeBookIsnDoingAnything() throws InterruptedException {
        startWith(5);
        recipeBookKnows(COAL_RECIPE);
        table.refusesRecipeBook = true;
        carry(30, COAL);

        craft();

        assertMade(206, 30 * STACK);
        assertEquals(1, table.recipeBookUses, "it shouldn't have to wait for that more than once");
    }

    @Test
    void getsThereByHandWithAFewBigStacksAndLotsOfSmallOnes() throws InterruptedException {
        startWith(5);
        for (int stack = 0; stack < 3; stack++) put(stack, COAL, STACK);
        for (int single = 3; single < 23; single++) put(single, COAL, 1);

        craft();

        assertMade(16, 3 * STACK + 20);
        // Moving nine at a time from stacks that small would take a lot more.
        assertTrue(table.clicks < 250, "took " + table.clicks + " clicks");
    }

    @Test
    void getsThereByHandWithWholeStacksOfDifferentSizes() throws InterruptedException {
        // It crafts until the 50 runs out, with 14 left in each of the others, and there's still more to do with those.
        startWith(5);
        carry(8, COAL);
        put(8, COAL, 50);

        craft();

        assertMade(55, 8 * STACK + 50);
    }

    @Test
    void clearsTheGridFirstGoingByHand() throws InterruptedException {
        // Like after the recipe book has taken too long, and then done something after all.
        startWith(5);
        carry(10, COAL);
        ItemStack inGrid = new ItemStack(COAL.id(), 5);
        table.slots[GRID_START] = inGrid;
        inventory().setItemStack(CONTAINER_ID, GRID_START, inGrid);

        craft();

        assertMade(64, 10 * STACK + 5);
    }

    @Test
    void getsThereByHandWithOnlySmallStacks() throws InterruptedException {
        startWith(5);
        for (int stack = 0; stack < 20; stack++) put(stack, COAL, 5);

        craft();

        assertEquals(100, table.amountOf(COAL.id()) + table.amountOf(COAL_BLOCK.id()) * 9);
        assertMade(4, 100);
    }

    @Test
    void doesntTakeLongToCraftAFullInventory() throws InterruptedException {
        startWith(30);
        recipeBookKnows(COAL_RECIPE);
        carry(30, COAL);

        craft();

        long took = System.currentTimeMillis() - startedAt;
        // It takes a second or two. One at a time took over two minutes, and that's what this is here to catch.
        assertTrue(took < 20_000, "took " + took + "ms");
    }
}
