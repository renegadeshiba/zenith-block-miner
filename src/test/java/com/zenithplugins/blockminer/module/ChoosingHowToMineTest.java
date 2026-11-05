package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticks the real module against ZenithProxy's real pathfinder processes. Nothing here is connected to a
 * server, so the pathfinder never moves the bot: the tests move it, and watch which process is in charge.
 */
class ChoosingHowToMineTest {

    private TestWorld world;
    private BlockMinerConfig previousConfig;
    private BlockMinerModule module;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        world = new TestWorld(4);
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        previousConfig = BlockMinerPlugin.PLUGIN_CONFIG;
        BlockMinerPlugin.PLUGIN_CONFIG = new BlockMinerConfig();
        BlockMinerPlugin.PLUGIN_CONFIG.fillEmptySlots = false;
        pickaxe(true);
        module = new BlockMinerModule();
    }

    @AfterEach
    void tearDown() {
        BARITONE.stop();
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
        pickaxe(false);
        BlockMinerPlugin.PLUGIN_CONFIG = previousConfig;
    }

    private static void pickaxe(boolean held) {
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory()
            .setItemStack(36, held ? new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1) : Container.EMPTY_STACK);
    }

    private static void want(String item) {
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of(item));
    }

    private void tickUntil(BooleanSupplier done, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "never got to: " + what + ", status: " + module.status());
            module.handleTick(ClientBotTick.INSTANCE);
            Thread.sleep(2);
        }
    }

    private static boolean zenithMinerRunning() {
        return BARITONE.getMineProcess().isActive();
    }

    private static boolean walkingToAGoal() {
        return BARITONE.getCustomGoalProcess().isActive();
    }

    @Test
    void walksInsteadOfStartingZenithsMinerFromAboveTheLayer() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);

        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking");

        assertFalse(zenithMinerRunning());
        assertEquals("Walking to the nearest deepslate or cobbled_deepslate", module.status());
    }

    @Test
    void usesZenithsMinerFromAmongTheBlocks() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        tickUntil(ChoosingHowToMineTest::zenithMinerRunning, "mining");

        assertFalse(walkingToAGoal());
        assertEquals("Mining", module.status());
    }

    @Test
    void usesZenithsMinerForARareBlockFromAnywhere() throws InterruptedException {
        for (int x = -30; x < 30; x += 5) {
            world.set(x, -40, 8, BlockRegistry.ANCIENT_DEBRIS);
        }
        want("ancient_debris");
        TestWorld.putBotAt(8.5, 40, 8.5);

        tickUntil(ChoosingHowToMineTest::zenithMinerRunning, "mining");

        assertFalse(walkingToAGoal());
    }

    @Test
    void handsOverToZenithsMinerOnceItGetsThere() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);
        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking");

        TestWorld.putBotAt(8.5, 5, 8.5);
        tickUntil(ChoosingHowToMineTest::zenithMinerRunning, "mining");

        assertFalse(walkingToAGoal());
        assertEquals("Mining", module.status());
    }

    @Test
    void takesOverFromZenithsMinerIfTheBotLeavesTheLayer() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        tickUntil(ChoosingHowToMineTest::zenithMinerRunning, "mining");

        TestWorld.putBotAt(8.5, 40, 8.5);
        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking");

        assertFalse(zenithMinerRunning());
    }

    @Test
    void waitsIfEverythingNearbyIsUnsafeToBreak() throws InterruptedException {
        // Every block has lava on top of it, which breaking it would let out.
        for (int y = 0; y < 100; y += 2) {
            world.layer(y, y, BlockRegistry.STONE);
            world.layer(y + 1, y + 1, BlockRegistry.LAVA);
        }
        want("cobblestone");
        TestWorld.putBotAt(8.5, 150, 8.5);

        tickUntil(() -> module.status().startsWith("Waiting"), "waiting");

        assertEquals("Waiting: couldn't get to the nearest blocks to mine", module.status());
        assertFalse(zenithMinerRunning());
        assertFalse(walkingToAGoal());
    }

    @Test
    void waitsAndTriesAgainIfTheWalkFails() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);
        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking");

        // What the pathfinder does when it can't find a way.
        BARITONE.stop();
        tickUntil(() -> module.status().startsWith("Waiting"), "waiting");
        assertFalse(walkingToAGoal());

        for (int tick = 0; tick < 90; tick++) {
            module.handleTick(ClientBotTick.INSTANCE);
        }
        assertFalse(walkingToAGoal(), "tried again too soon");

        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking again");
    }

    @Test
    void goesStraightOnToTheNextBlockAfterGettingToOne() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);
        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking");

        Field mining = BlockMinerModule.class.getDeclaredField("mining");
        mining.setAccessible(true);
        ((PathingRequestFuture) mining.get(module)).complete(true);
        BARITONE.stop();
        module.handleTick(ClientBotTick.INSTANCE);

        assertTrue(walkingToAGoal());
        assertEquals("Walking to the nearest deepslate or cobbled_deepslate", module.status());
    }

    @Test
    void stoppingMiningStopsTheWalkToo() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);
        tickUntil(ChoosingHowToMineTest::walkingToAGoal, "walking");

        module.blocksChanged();

        assertFalse(walkingToAGoal());
    }
}
