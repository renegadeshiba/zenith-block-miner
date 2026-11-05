package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.cache.data.inventory.InventoryCache;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the bot's real slot filling and ZenithProxy's real click handling against a pretend server that
 * follows vanilla's rules for clicks. What this can't show is a real server's own quirks.
 */
class FillingSlotsTest {

    private static final int MAIN_START = 9;
    private static final int HOTBAR_START = 36;
    private static final int SLOT_END = 45;
    private static final int PICKAXE_SLOT = HOTBAR_START;
    private static final int STACK_SLOT = MAIN_START;
    private static final int LATENCY_MS = 300;

    /** Holds the true inventory, and tells the client about changes a little later, like a real one. */
    private static final class FakeServer {
        final Map<Integer, ItemStack> slots = new TreeMap<>();
        ItemStack cursor;
        final long latencyMs;
        final List<Packet> received = new ArrayList<>();
        final List<ItemStack> thrown = new ArrayList<>();
        final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FakeServer");
            thread.setDaemon(true);
            return thread;
        });

        FakeServer(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        void receive(Packet packet) {
            received.add(packet);
            if (packet instanceof ServerboundContainerClickPacket click) {
                // Zenith applies its own prediction as the packet goes out.
                inventory().handleContainerClick(click);
                if (click.getActionType() == ContainerActionType.DROP_ITEM) {
                    thrown.add(slots.put(click.getSlot(), null));
                } else {
                    click(click.getSlot(), (ClickItemAction) click.getActionParam());
                }
                tellClient(click.getSlot());
            } else if (packet instanceof ServerboundContainerClosePacket) {
                int slot = returnCursor();
                if (slot != -1) tellClient(slot);
            }
        }

        private static int amount(ItemStack stack) {
            return stack == null ? 0 : stack.getAmount();
        }

        private static ItemStack withAmount(ItemStack stack, int amount) {
            return amount <= 0 ? null : new ItemStack(stack.getId(), amount);
        }

        private void click(int slot, ClickItemAction action) {
            ItemStack inSlot = slots.get(slot);
            boolean sameItem = cursor != null && inSlot != null && cursor.getId() == inSlot.getId();
            if (action == ClickItemAction.LEFT_CLICK) {
                if (sameItem) {
                    int moved = Math.min(amount(cursor), 64 - amount(inSlot));
                    slots.put(slot, withAmount(inSlot, amount(inSlot) + moved));
                    cursor = withAmount(cursor, amount(cursor) - moved);
                } else {
                    slots.put(slot, cursor);
                    cursor = inSlot;
                }
            } else if (cursor == null) {
                if (inSlot == null) return;
                int taken = (amount(inSlot) + 1) / 2;
                cursor = withAmount(inSlot, taken);
                slots.put(slot, withAmount(inSlot, amount(inSlot) - taken));
            } else if (inSlot == null || (sameItem && amount(inSlot) < 64)) {
                slots.put(slot, withAmount(cursor, amount(inSlot) + 1));
                cursor = withAmount(cursor, amount(cursor) - 1);
            } else {
                slots.put(slot, cursor);
                cursor = inSlot;
            }
        }

        /** What's on the cursor goes back into the inventory when the screen closes. */
        private int returnCursor() {
            if (cursor == null) return -1;
            for (int slot = MAIN_START; slot < SLOT_END; slot++) {
                ItemStack inSlot = slots.get(slot);
                if (inSlot == null || inSlot.getId() == cursor.getId()) {
                    slots.put(slot, withAmount(cursor, amount(inSlot) + amount(cursor)));
                    cursor = null;
                    return slot;
                }
            }
            return -1;
        }

        private void tellClient(int slot) {
            ItemStack inSlot = slots.get(slot);
            ItemStack onCursor = cursor;
            network.schedule(() -> {
                inventory().getPlayerInventory().setItemStack(slot, inSlot == null ? Container.EMPTY_STACK : inSlot);
                inventory().setMouseStack(onCursor == null ? Container.EMPTY_STACK : onCursor);
            }, latencyMs, TimeUnit.MILLISECONDS);
        }

        void settle() throws InterruptedException {
            network.shutdown();
            assertTrue(network.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static InventoryCache inventory() {
        return CACHE.getPlayerCache().getInventoryCache();
    }

    private FakeServer server;
    private BlockMinerModule module;
    private Bot bot;
    private InventorySpace space;

    private void startWith(long latencyMs) {
        server = new FakeServer(latencyMs);
        module = new BlockMinerModule() {
            @Override
            public void sendClientPacketAsync(Packet packet) {
                server.receive(packet);
            }
        };
        bot = new Bot(module, () -> true);
        space = new InventorySpace(module, bot);
    }

    private void put(int slot, ItemData item, int amount) {
        ItemStack stack = new ItemStack(item.id(), amount);
        server.slots.put(slot, stack);
        inventory().getPlayerInventory().setItemStack(slot, stack);
    }

    private static void wanting(String... items) {
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of(items));
    }

    private int amountOnServer(int slot) {
        return FakeServer.amount(server.slots.get(slot));
    }

    private int totalOf(ItemData item) {
        return server.slots.values().stream()
            .filter(stack -> stack != null && stack.getId() == item.id())
            .mapToInt(ItemStack::getAmount)
            .sum();
    }

    /** The client ends up believing what the server says, or later clicks would be based on the wrong thing. */
    private void assertClientAgreesWithServer() {
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            ItemStack seen = inventory().getPlayerInventory().getItemStack(slot);
            int seenAmount = seen == null || seen == Container.EMPTY_STACK ? 0 : seen.getAmount();
            assertEquals(amountOnServer(slot), seenAmount, "slot " + slot);
        }
    }

    @BeforeEach
    void emptyInventory() {
        BlockMinerPlugin.PLUGIN_CONFIG = new BlockMinerConfig();
        for (int slot = 0; slot < SLOT_END + 1; slot++) {
            inventory().getPlayerInventory().setItemStack(slot, Container.EMPTY_STACK);
        }
        inventory().setMouseStack(Container.EMPTY_STACK);
    }

    @AfterEach
    void clearUp() {
        emptyInventory();
        BlockMinerPlugin.PLUGIN_CONFIG = null;
    }

    @Test
    void aStackGetsSpreadThroughEveryEmptySlotAndTheRestStaysPut() throws InterruptedException {
        startWith(0);
        wanting("cobbled_deepslate");
        put(PICKAXE_SLOT, ItemRegistry.NETHERITE_PICKAXE, 1);
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);

        space.fillEmptySlots();
        server.settle();

        assertEquals(64, totalOf(ItemRegistry.COBBLED_DEEPSLATE), "items went missing");
        assertEquals(64 - 34, amountOnServer(STACK_SLOT), "the stack that's kept");
        assertEquals(1, amountOnServer(PICKAXE_SLOT), "the pickaxe");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            if (slot != STACK_SLOT && slot != PICKAXE_SLOT) assertEquals(1, amountOnServer(slot), "slot " + slot);
        }
        assertEquals(null, server.cursor, "something was left on the cursor");
        assertClientAgreesWithServer();
    }

    @Test
    void itWorksOnALaggyConnectionToo() throws InterruptedException {
        startWith(LATENCY_MS);
        wanting("cobbled_deepslate");
        put(PICKAXE_SLOT, ItemRegistry.NETHERITE_PICKAXE, 1);
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);
        // Leaves 9 empty slots, so the first half of the stack is more than enough for them.
        for (int slot = MAIN_START + 1; slot < MAIN_START + 26; slot++) put(slot, ItemRegistry.NETHERITE_PICKAXE, 1);

        space.fillEmptySlots();
        server.settle();

        assertEquals(64, totalOf(ItemRegistry.COBBLED_DEEPSLATE), "items went missing");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            assertTrue(amountOnServer(slot) >= 1, "slot " + slot + " is still empty");
        }
        assertEquals(64 - 9, amountOnServer(STACK_SLOT), "the stack that's kept");
        assertEquals(null, server.cursor, "something was left on the cursor");
    }

    @Test
    void aLaggyConnectionWorksWhenItTakesMoreThanOnePickUpToo() throws InterruptedException {
        startWith(LATENCY_MS);
        wanting("cobbled_deepslate");
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 8);
        // Leaves 6 empty slots, which is more than half of the stack.
        for (int slot = MAIN_START + 1; slot < MAIN_START + 30; slot++) put(slot, ItemRegistry.NETHERITE_PICKAXE, 1);

        space.fillEmptySlots();
        server.settle();

        assertEquals(8, totalOf(ItemRegistry.COBBLED_DEEPSLATE), "items went missing");
        assertEquals(null, server.cursor, "something was left on the cursor");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            assertTrue(amountOnServer(slot) >= 1, "slot " + slot + " is still empty");
        }
    }

    @Test
    void aSmallStackFillsWhatItCanAndKeepsOneBack() throws InterruptedException {
        startWith(0);
        wanting("cobbled_deepslate");
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 5);

        space.fillEmptySlots();
        server.settle();

        assertEquals(5, totalOf(ItemRegistry.COBBLED_DEEPSLATE), "items went missing");
        long filled = server.slots.values().stream().filter(stack -> stack != null).count();
        assertEquals(5, filled, "one item in each of five slots");
        assertEquals(null, server.cursor, "something was left on the cursor");
    }

    @Test
    void twoWantedItemsShareTheSlots() throws InterruptedException {
        startWith(0);
        wanting("diamond", "coal");
        put(MAIN_START, ItemRegistry.DIAMOND, 64);
        put(MAIN_START + 1, ItemRegistry.COAL, 64);

        space.fillEmptySlots();
        server.settle();

        assertEquals(64, totalOf(ItemRegistry.DIAMOND), "diamonds went missing");
        assertEquals(64, totalOf(ItemRegistry.COAL), "coal went missing");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            assertTrue(amountOnServer(slot) >= 1, "slot " + slot + " is still empty");
        }
        long diamondSlots = server.slots.values().stream().filter(stack -> stack.getId() == ItemRegistry.DIAMOND.id()).count();
        long coalSlots = server.slots.values().stream().filter(stack -> stack.getId() == ItemRegistry.COAL.id()).count();
        assertTrue(Math.abs(diamondSlots - coalSlots) <= 1, diamondSlots + " diamond slots, " + coalSlots + " coal slots");
    }

    /** What happened on the live server: everything counted as junk, got stored, and nothing was kept. */
    @Test
    void withNothingChosenTheInventoryIsLeftAlone() throws InterruptedException {
        startWith(0);
        wanting();
        for (int slot = MAIN_START; slot < SLOT_END; slot++) put(slot, ItemRegistry.COBBLED_DEEPSLATE, 64);

        StorageErrand storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));
        assertFalse(storage.isNeeded(), "it wants to store everything");
        assertFalse(storage.isStuck(), "it thinks it's stuck");
        assertFalse(space.isWorthFilling(), "it wants to fill slots");

        space.fillEmptySlots();
        server.settle();
        assertTrue(server.received.isEmpty(), "it clicked something");
    }

    private void withStackAndThreeJunkStacks() {
        put(PICKAXE_SLOT, ItemRegistry.NETHERITE_PICKAXE, 1);
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(MAIN_START + 1, ItemRegistry.TUFF, 12);
        put(MAIN_START + 2, ItemRegistry.GRAVEL, 7);
        put(MAIN_START + 3, ItemRegistry.ANDESITE, 64);
    }

    @Test
    void junkGetsThrownAwayAndItsSlotsFilledSoItCantComeBack() throws InterruptedException {
        startWith(LATENCY_MS);
        wanting("cobbled_deepslate");
        withStackAndThreeJunkStacks();
        // Leaves only a handful of empty slots, to keep the test quick on a laggy connection.
        for (int slot = MAIN_START + 4; slot < MAIN_START + 30; slot++) put(slot, ItemRegistry.NETHERITE_PICKAXE, 1);

        assertTrue(space.isWorthThrowingJunk());
        space.throwAwayJunk();
        space.fillEmptySlots();
        server.settle();

        assertEquals(List.of(ItemRegistry.TUFF.id(), ItemRegistry.GRAVEL.id(), ItemRegistry.ANDESITE.id()),
            server.thrown.stream().map(ItemStack::getId).toList());
        assertEquals(0, totalOf(ItemRegistry.TUFF) + totalOf(ItemRegistry.GRAVEL) + totalOf(ItemRegistry.ANDESITE));
        assertEquals(64, totalOf(ItemRegistry.COBBLED_DEEPSLATE), "what you asked for went missing");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            assertTrue(amountOnServer(slot) >= 1, "slot " + slot + " was left empty for junk to get back into");
        }
    }

    @Test
    void junkStaysPutUntilItsSlotsCanBeFilled() {
        startWith(0);
        wanting("cobbled_deepslate");
        // Plenty of empty slots and nothing to fill them with, so thrown junk would just come back.
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 3);
        put(MAIN_START + 1, ItemRegistry.TUFF, 12);
        assertFalse(space.isWorthThrowingJunk());
    }

    @Test
    void junkCrowdingOutWhatYouWantGetsThrownAnyway() {
        startWith(0);
        wanting("diamond");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) put(slot, ItemRegistry.TUFF, 64);

        StorageErrand storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));
        assertTrue(space.isWorthThrowingJunk(), "the bot can't pick up diamonds like this");
        assertFalse(storage.isStuck(), "throwing it away will solve this, so it isn't stuck");
        assertFalse(storage.isNeeded(), "there's nothing worth a trip to the ender chest");
    }

    @Test
    void whatStaysWithTheBotIsLeftAloneWhenJunkIsThrownAway() throws InterruptedException {
        startWith(0);
        wanting("cobbled_deepslate");
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(MAIN_START + 1, ItemRegistry.TUFF, 12);
        put(MAIN_START + 2, ItemRegistry.ENDER_CHEST, 2);
        put(MAIN_START + 3, ItemRegistry.NETHERITE_PICKAXE, 1);
        put(MAIN_START + 4, ItemRegistry.GOLDEN_CARROT, 5);
        put(MAIN_START + 5, ItemRegistry.SHULKER_BOX, 1);

        space.throwAwayJunk();
        server.settle();

        assertEquals(List.of(ItemRegistry.TUFF.id()), server.thrown.stream().map(ItemStack::getId).toList());
        assertEquals(2, totalOf(ItemRegistry.ENDER_CHEST));
        assertEquals(1, totalOf(ItemRegistry.NETHERITE_PICKAXE));
        assertEquals(5, totalOf(ItemRegistry.GOLDEN_CARROT));
        assertEquals(1, totalOf(ItemRegistry.SHULKER_BOX));
        assertEquals(64, totalOf(ItemRegistry.COBBLED_DEEPSLATE));
    }

    @Test
    void anythingItWasntAskedForIsJunkHoweverValuableItLooks() throws InterruptedException {
        startWith(0);
        wanting("cobbled_deepslate");
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(MAIN_START + 1, ItemRegistry.DIAMOND, 12);
        put(MAIN_START + 2, ItemRegistry.RAW_IRON, 3);

        space.throwAwayJunk();
        server.settle();

        assertEquals(List.of(ItemRegistry.DIAMOND.id(), ItemRegistry.RAW_IRON.id()), server.thrown.stream().map(ItemStack::getId).toList());
        assertEquals(64, totalOf(ItemRegistry.COBBLED_DEEPSLATE));
    }

    @Test
    void theKeepListProtectsJunkFromBeingThrownAway() throws InterruptedException {
        startWith(0);
        wanting("cobbled_deepslate");
        BlockMinerPlugin.PLUGIN_CONFIG.keepItems.add("tuff");
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(MAIN_START + 1, ItemRegistry.TUFF, 12);

        assertFalse(space.isWorthThrowingJunk());
        space.throwAwayJunk();
        server.settle();
        assertTrue(server.thrown.isEmpty());
    }

    @Test
    void switchingToSomethingElseStoresWhatWasMinedInsteadOfThrowingItAway() {
        startWith(0);
        wanting("diamond");
        BlockMinerPlugin.PLUGIN_CONFIG.stillToStore.add("cobbled_deepslate");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) put(slot, ItemRegistry.COBBLED_DEEPSLATE, 64);

        StorageErrand storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));
        assertFalse(space.hasJunk(), "it wants to throw away what you spent ages mining");
        assertTrue(storage.isNeeded(), "it should take it all to the ender chest");
        assertFalse(storage.isStuck());
    }

    @Test
    void anItemIsOnlyForgottenAboutOnceTheBotHasNoneLeft() {
        startWith(0);
        wanting("diamond");
        BlockMinerPlugin.PLUGIN_CONFIG.stillToStore.add("cobbled_deepslate");
        BlockMinerPlugin.PLUGIN_CONFIG.stillToStore.add("tuff");
        put(STACK_SLOT, ItemRegistry.COBBLED_DEEPSLATE, 64);

        space.forgetItemsNoLongerCarried();

        assertEquals(List.of("cobbled_deepslate"), BlockMinerPlugin.PLUGIN_CONFIG.stillToStore);
    }

    @Test
    void nothingIsThrownAwayWithNothingChosen() throws InterruptedException {
        startWith(0);
        wanting();
        for (int slot = MAIN_START; slot < SLOT_END; slot++) put(slot, ItemRegistry.TUFF, 64);

        assertFalse(space.hasJunk());
        assertFalse(space.isWorthThrowingJunk());
        space.throwAwayJunk();
        server.settle();
        assertTrue(server.thrown.isEmpty());
        assertTrue(server.received.isEmpty());
    }

    @Test
    void aFullInventoryOfWhatYouWantIsStoredAndOneStackKept() {
        startWith(0);
        wanting("cobbled_deepslate");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) put(slot, ItemRegistry.COBBLED_DEEPSLATE, 64);

        StorageErrand storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));
        assertTrue(storage.isNeeded());
    }

    @Test
    void aPartlyFullInventoryOfWhatYouWantIsNotStoredYet() {
        startWith(0);
        wanting("cobbled_deepslate");
        // Ten full stacks, and one in every other slot, is the normal state after filling.
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            put(slot, ItemRegistry.COBBLED_DEEPSLATE, slot < MAIN_START + 10 ? 64 : 1);
        }

        StorageErrand storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));
        assertFalse(storage.isNeeded());
    }
}
