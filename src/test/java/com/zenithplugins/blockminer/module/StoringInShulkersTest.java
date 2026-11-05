package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.cache.data.inventory.InventoryCache;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the bot's real storing and ZenithProxy's real container handling against a pretend server that
 * follows vanilla's rules for shift-clicks. What this can't show is a real server's own quirks, or the
 * parts around it, like placing the shulker box and picking it back up.
 */
class StoringInShulkersTest {

    private static final int CONTAINER_ID = 5;
    private static final int SHULKER_SLOTS = 27;
    private static final int FIRST_PLAYER_SLOT = SHULKER_SLOTS;
    private static final int ALL_SLOTS = SHULKER_SLOTS + 36;
    private static final int LATENCY_MS = 30;

    /** Holds the true contents, and tells the client about changes a little later, like a real one. */
    private static final class FakeServer {
        final ItemStack[] slots = new ItemStack[ALL_SLOTS];
        final List<Integer> clicked = new ArrayList<>();
        final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FakeServer");
            thread.setDaemon(true);
            return thread;
        });

        void receive(Packet packet) {
            if (!(packet instanceof ServerboundContainerClickPacket click)) return;
            // Zenith applies its own prediction as the packet goes out.
            inventory().handleContainerClick(click);
            assertEquals(ContainerActionType.SHIFT_CLICK_ITEM, click.getActionType());
            clicked.add(click.getSlot());
            shiftClick(click.getSlot());
        }

        /** Vanilla's rule: on top of matching stacks first, then into the first empty slot. */
        private void shiftClick(int from) {
            ItemStack moving = slots[from];
            if (moving == null) return;
            int left = moving.getAmount();
            for (int slot = 0; slot < SHULKER_SLOTS && left > 0; slot++) {
                ItemStack inSlot = slots[slot];
                if (inSlot == null || inSlot.getId() != moving.getId() || inSlot.getAmount() >= 64) continue;
                int moved = Math.min(left, 64 - inSlot.getAmount());
                set(slot, new ItemStack(moving.getId(), inSlot.getAmount() + moved));
                left -= moved;
            }
            for (int slot = 0; slot < SHULKER_SLOTS && left > 0; slot++) {
                if (slots[slot] != null) continue;
                set(slot, new ItemStack(moving.getId(), left));
                left = 0;
            }
            set(from, left > 0 ? new ItemStack(moving.getId(), left) : null);
        }

        private void set(int slot, ItemStack stack) {
            slots[slot] = stack;
            network.schedule(
                () -> inventory().setItemStack(CONTAINER_ID, slot, stack == null ? Container.EMPTY_STACK : stack),
                LATENCY_MS, TimeUnit.MILLISECONDS);
        }

        void settle() throws InterruptedException {
            network.shutdown();
            assertTrue(network.awaitTermination(10, TimeUnit.SECONDS));
        }

        int amountOf(ItemData item, int fromSlot, int toSlot) {
            int total = 0;
            for (int slot = fromSlot; slot < toSlot; slot++) {
                if (slots[slot] != null && slots[slot].getId() == item.id()) total += slots[slot].getAmount();
            }
            return total;
        }
    }

    private static InventoryCache inventory() {
        return CACHE.getPlayerCache().getInventoryCache();
    }

    private FakeServer server;
    private BlockMinerConfig previousConfig;
    private StorageErrand storage;

    @BeforeEach
    void openAShulkerBox() {
        previousConfig = BlockMinerPlugin.PLUGIN_CONFIG;
        BlockMinerPlugin.PLUGIN_CONFIG = new BlockMinerConfig();
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of("cobbled_deepslate"));

        server = new FakeServer();
        BlockMinerModule module = new BlockMinerModule() {
            @Override
            public void sendClientPacketAsync(Packet packet) {
                server.receive(packet);
            }
        };
        Bot bot = new Bot(module, () -> true);
        InventorySpace space = new InventorySpace(module, bot);
        storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));

        inventory().reset();
        inventory().openContainer(CONTAINER_ID, ContainerType.SHULKER_BOX, Component.text("Shulker Box"));
    }

    @AfterEach
    void closeIt() {
        inventory().reset();
        BlockMinerPlugin.PLUGIN_CONFIG = previousConfig;
    }

    private void put(int slot, ItemData item, int amount) {
        ItemStack stack = new ItemStack(item.id(), amount);
        server.slots[slot] = stack;
        inventory().setItemStack(CONTAINER_ID, slot, stack);
    }

    private void putInShulker(int stacks, ItemData item, int amount) {
        for (int slot = 0; slot < stacks; slot++) put(slot, item, amount);
    }

    private void carry(int stacks, ItemData item) {
        for (int slot = 0; slot < stacks; slot++) put(FIRST_PLAYER_SLOT + slot, item, 64);
    }

    private int inShulker(ItemData item) {
        return server.amountOf(item, 0, SHULKER_SLOTS);
    }

    private int carried(ItemData item) {
        return server.amountOf(item, FIRST_PLAYER_SLOT, ALL_SLOTS);
    }

    @Test
    void addsToWhatsAlreadyInAStartedBoxKeepingAStackBack() throws InterruptedException {
        putInShulker(3, ItemRegistry.COBBLED_DEEPSLATE, 64);
        carry(5, ItemRegistry.COBBLED_DEEPSLATE);

        storage.moveStorableItemsIntoOpenContainer();
        server.settle();

        assertEquals(64 * 7, inShulker(ItemRegistry.COBBLED_DEEPSLATE));
        assertEquals(64, carried(ItemRegistry.COBBLED_DEEPSLATE), "a stack stays to fill the slots with");
    }

    @Test
    void fillsARoomyBoxAndLeavesWhatDoesntFit() throws InterruptedException {
        carry(30, ItemRegistry.COBBLED_DEEPSLATE);

        storage.moveStorableItemsIntoOpenContainer();
        server.settle();

        assertEquals(64 * SHULKER_SLOTS, inShulker(ItemRegistry.COBBLED_DEEPSLATE));
        assertEquals(64 * 3, carried(ItemRegistry.COBBLED_DEEPSLATE));
        assertEquals(SHULKER_SLOTS, server.clicked.size(), "shouldn't try the ones that won't fit");
    }

    @Test
    void topsUpTheLastStackThenStopsTryingOnceItsFull() throws InterruptedException {
        putInShulker(SHULKER_SLOTS, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(SHULKER_SLOTS - 1, ItemRegistry.COBBLED_DEEPSLATE, 60);
        carry(3, ItemRegistry.COBBLED_DEEPSLATE);

        storage.moveStorableItemsIntoOpenContainer();
        server.settle();

        assertEquals(64 * SHULKER_SLOTS, inShulker(ItemRegistry.COBBLED_DEEPSLATE), "it's really full now");
        assertEquals(64 * 3 - 4, carried(ItemRegistry.COBBLED_DEEPSLATE));
        assertEquals(1, server.clicked.size(), "only the first had anywhere to go");
    }

    @Test
    void passesOverAStackWithNowhereToGoAndMovesTheNext() throws InterruptedException {
        // No empty slots, and room for more cobbled deepslate but not for tuff.
        BlockMinerPlugin.PLUGIN_CONFIG.stillToStore = new ArrayList<>(List.of("tuff"));
        putInShulker(SHULKER_SLOTS, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(0, ItemRegistry.COBBLED_DEEPSLATE, 10);
        put(FIRST_PLAYER_SLOT, ItemRegistry.TUFF, 64);
        put(FIRST_PLAYER_SLOT + 1, ItemRegistry.COBBLED_DEEPSLATE, 64);
        put(FIRST_PLAYER_SLOT + 2, ItemRegistry.COBBLED_DEEPSLATE, 64);

        storage.moveStorableItemsIntoOpenContainer();
        server.settle();

        assertEquals(64 * SHULKER_SLOTS, inShulker(ItemRegistry.COBBLED_DEEPSLATE));
        assertEquals(64, carried(ItemRegistry.TUFF), "stays for the next box");
        assertEquals(1, server.clicked.size());
        assertFalse(server.clicked.contains(FIRST_PLAYER_SLOT), "shouldn't waste time trying the tuff");
    }

    @Test
    void onlyWhatItWasAskedForGoesInWhateverElseItsCarrying() throws InterruptedException {
        carry(3, ItemRegistry.COBBLED_DEEPSLATE);
        put(FIRST_PLAYER_SLOT + 3, ItemRegistry.COAL, 64);
        put(FIRST_PLAYER_SLOT + 4, ItemRegistry.RAW_IRON, 20);
        put(FIRST_PLAYER_SLOT + 5, ItemRegistry.TUFF, 64);
        put(FIRST_PLAYER_SLOT + 6, ItemRegistry.BONE, 5);
        put(FIRST_PLAYER_SLOT + 7, ItemRegistry.DIAMOND, 2);

        storage.moveStorableItemsIntoOpenContainer();
        server.settle();

        assertEquals(64 * 2, inShulker(ItemRegistry.COBBLED_DEEPSLATE));
        for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
            if (server.slots[slot] != null) {
                assertEquals(ItemRegistry.COBBLED_DEEPSLATE.id(), server.slots[slot].getId(), "slot " + slot + " has junk in it");
            }
        }
        assertEquals(2, server.clicked.size());
    }

    @Test
    void saysSoIfNothingFitsAtAll() throws InterruptedException {
        putInShulker(SHULKER_SLOTS, ItemRegistry.COBBLED_DEEPSLATE, 64);
        carry(3, ItemRegistry.COBBLED_DEEPSLATE);

        ErrandFailed failure = assertThrows(ErrandFailed.class, () -> storage.moveStorableItemsIntoOpenContainer());
        server.settle();

        assertTrue(failure.worthRetrying());
        assertEquals(0, server.clicked.size());
    }
}
