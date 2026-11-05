package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalY;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticks the real module against ZenithProxy's real pathfinder processes. Nothing here is connected to a
 * server, so the pathfinder never moves the bot: the tests move it, and say how each path turned out.
 */
class ChoosingHowToMineTest {

    private static final int INVENTORY_SLOT = 9;
    private static final int LAST_SLOT = 44;
    private static final String WALKING = "Walking to the nearest deepslate or cobbled_deepslate";

    private TestWorld world;
    private BlockMinerConfig previousConfig;
    private Set<String> previousThrowaways;
    private BlockMinerModule module;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        world = new TestWorld(4);
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        previousConfig = BlockMinerPlugin.PLUGIN_CONFIG;
        previousThrowaways = new HashSet<>(CONFIG.client.extra.pathfinder.acceptableThrowawayItems);
        BlockMinerPlugin.PLUGIN_CONFIG = new BlockMinerConfig();
        BlockMinerPlugin.PLUGIN_CONFIG.enabled = true;
        BlockMinerPlugin.PLUGIN_CONFIG.fillEmptySlots = false;
        setSlot(36, ItemRegistry.NETHERITE_PICKAXE);
        module = new BlockMinerModule();
        module.syncEnabledFromConfig();
    }

    @AfterEach
    void tearDown() {
        boolean zenithsMinerRan = BARITONE.getMineProcess().isActive();
        BlockMinerPlugin.PLUGIN_CONFIG.enabled = false;
        module.syncEnabledFromConfig();
        BARITONE.stop();
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
        for (int slot = INVENTORY_SLOT; slot <= LAST_SLOT; slot++) setSlot(slot, null);
        BlockMinerPlugin.PLUGIN_CONFIG = previousConfig;
        assertFalse(zenithsMinerRan, "Zenith's miner can't be told to stay above the lowest level");
        assertEquals(previousThrowaways, new HashSet<>(CONFIG.client.extra.pathfinder.acceptableThrowawayItems),
            "left something on the pathfinder's list of blocks to build with");
    }

    private static void setSlot(int slot, ItemData item) {
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory()
            .setItemStack(slot, item == null ? Container.EMPTY_STACK : new ItemStack(item.id(), 64));
    }

    private static void want(String item) {
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of(item));
    }

    private void tick() {
        module.handleTick(ClientBotTick.INSTANCE);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) tick();
    }

    private void tickUntil(BooleanSupplier done, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "never got to: " + what + ", status: " + module.status());
            tick();
            Thread.sleep(2);
        }
    }

    private static boolean followingAPath() {
        return BARITONE.getCustomGoalProcess().isActive();
    }

    private static Goal currentGoal() {
        return BARITONE.getCustomGoalProcess().getGoal();
    }

    private void waitForPath() throws InterruptedException {
        tickUntil(ChoosingHowToMineTest::followingAPath, "following a path");
    }

    /** Ends the path it's on the way the pathfinder does, then lets the module see how it went. */
    private void pathEnds(boolean reachedTheGoal) throws ReflectiveOperationException {
        if (reachedTheGoal) {
            Field mining = BlockMinerModule.class.getDeclaredField("mining");
            mining.setAccessible(true);
            ((PathingRequestFuture) mining.get(module)).complete(true);
        }
        // Completes it as failed, if it hasn't completed already.
        BARITONE.stop();
        tick();
    }

    @SuppressWarnings("unchecked")
    private Set<BlockPos> outOfReach() throws ReflectiveOperationException {
        Field field = BlockMinerModule.class.getDeclaredField("outOfReach");
        field.setAccessible(true);
        return (Set<BlockPos>) field.get(module);
    }

    private BlockScan.MiningArea miningArea() throws ReflectiveOperationException {
        Field field = BlockMinerModule.class.getDeclaredField("miningArea");
        field.setAccessible(true);
        return (BlockScan.MiningArea) field.get(module);
    }

    private void assertSectionCentredOn(int x, int z) throws ReflectiveOperationException {
        assertEquals(x, miningArea().centreX());
        assertEquals(z, miningArea().centreZ());
    }

    private List<BlockPos> blocksItsGoingFor() throws ReflectiveOperationException {
        Field field = BlockMinerModule.class.getDeclaredField("currentGoal");
        field.setAccessible(true);
        return ((BlockScan.MiningGoal) field.get(module)).blocks();
    }

    private static boolean miningABlock() {
        return BARITONE.getInteractWithProcess().isActive();
    }

    private void waitForMiningABlock() throws InterruptedException {
        tickUntil(ChoosingHowToMineTest::miningABlock, "mining a block");
    }

    /** The bot on the level that reaches the top layer, which is at -1, from its floor. */
    private void putBotOnTheTopLevel() {
        TestWorld.putBotAt(8.5, -7, 8.5);
    }

    private void askForRoom() throws ReflectiveOperationException {
        Field roomNeeded = BlockMinerModule.class.getDeclaredField("roomNeeded");
        roomNeeded.setAccessible(true);
        roomNeeded.set(module, true);
    }

    // ----------------------------------------------------------------------------------------
    // where it goes
    // ----------------------------------------------------------------------------------------

    @Test
    void walksToTheBlocksFromAboveTheLayer() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);

        waitForPath();

        assertEquals(WALKING, module.status());
    }

    @Test
    void goesToTheTopOfTheSectionFromAmongTheBlocks() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        waitForPath();

        assertEquals(WALKING, module.status());
        assertTrue(currentGoal().isInGoal(8, -7, 8));
        assertFalse(currentGoal().isInGoal(8, -30, 8));
    }

    @Test
    void minesWhatsAboveItOnceItsThereWithoutWalking() throws Exception {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();

        waitForMiningABlock();

        assertEquals("Mining", module.status());
        assertFalse(followingAPath());
        assertEquals(List.of(new BlockPos(8, -5, 8)), blocksItsGoingFor());
    }

    @Test
    void walksToTheNextSpotOnceTheresNothingLeftAboveIt() throws Exception {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();

        for (int y = -5; y <= -1; y++) {
            waitForMiningABlock();
            assertEquals(List.of(new BlockPos(8, y, 8)), blocksItsGoingFor());
            world.set(8, y, 8, BlockRegistry.AIR);
            pathEnds(true);
        }

        waitForPath();
        assertEquals("Mining", module.status());
        assertTrue(currentGoal().isInGoal(9, -7, 8));
        assertFalse(currentGoal().isInGoal(8, -8, 8), "not under its feet");
    }

    @Test
    void movesOnFromABlockAboveItThatItCantMine() throws Exception {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();
        waitForMiningABlock();

        pathEnds(false);

        assertTrue(outOfReach().contains(new BlockPos(8, -5, 8)));
        waitForPath();
        assertTrue(currentGoal().isInGoal(9, -7, 8));
        assertTrue(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
    }

    @Test
    void givesUpOnABlockAboveItThatsTakingForever() throws Exception {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();
        waitForMiningABlock();

        tick(20 * 30);
        assertTrue(miningABlock(), "that's long enough for anything it could mine");
        tick(2);

        assertFalse(miningABlock());
        assertTrue(outOfReach().contains(new BlockPos(8, -5, 8)));
    }

    @Test
    void stoppingMiningStopsMiningABlockToo() throws InterruptedException {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();
        waitForMiningABlock();

        module.blocksChanged();

        assertFalse(miningABlock());
    }

    @Test
    void goesForARareBlockFromAnywhere() throws InterruptedException {
        world.set(60, -40, 8, BlockRegistry.ANCIENT_DEBRIS);
        want("ancient_debris");
        TestWorld.putBotAt(8.5, 40, 8.5);

        waitForPath();

        assertEquals("Walking to the nearest ancient_debris", module.status());
        assertTrue(currentGoal().isInGoal(60, -40, 8));
    }

    @Test
    void wontGoForABlockBelowTheLowestLevel() throws InterruptedException {
        world.set(10, -59, 8, BlockRegistry.ANCIENT_DEBRIS);
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -40, 8.5);

        waitForPath();

        assertFalse(currentGoal().isInGoal(10, -59, 8));
        assertTrue(module.status().startsWith("Looking for ancient_debris"), "status: " + module.status());
    }

    @Test
    void goesStraightOnToTheNextBlockAfterGettingToOne() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);
        waitForPath();

        pathEnds(true);

        assertTrue(followingAPath());
        assertEquals(WALKING, module.status());
    }

    @Test
    void stoppingMiningStopsThePathToo() throws InterruptedException {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, 40, 8.5);
        waitForPath();

        module.blocksChanged();

        assertFalse(followingAPath());
    }

    @Test
    void movesOnFromAnAreaOnlyWhenTheBotHasToLeaveIt() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        assertSectionCentredOn(8, 8);

        TestWorld.putBotAt(20.5, -30, 12.5);
        pathEnds(true);
        assertSectionCentredOn(8, 8);

        // Moved a long way, such as by dying or by someone playing as the bot.
        TestWorld.putBotAt(-60.5, -30, -60.5);
        pathEnds(true);
        assertSectionCentredOn(-61, -61);
        assertEquals(-7, miningArea().level(), "it starts again from the top");
    }

    @Test
    void goesOnToTheTopOfTheNextSectionOnceItsFinishedThisOne() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -40, 8.5);
        waitForPath();
        assertSectionCentredOn(8, 8);
        assertEquals(-7, miningArea().level());

        // Everything in its section is gone, all the way down to the lowest level.
        for (int x = -16; x <= 32; x++) {
            for (int z = -16; z <= 32; z++) {
                for (int y = -58; y <= -1; y++) {
                    world.set(x, y, z, BlockRegistry.AIR);
                }
            }
        }
        TestWorld.putBotAt(8.5, -58, 8.5);
        pathEnds(true);

        assertTrue(followingAPath());
        assertSectionCentredOn(56, 8);
        assertEquals(-7, miningArea().level());
        assertEquals(WALKING, module.status());
    }

    @Test
    void startsANewSectionFromWhereverItEndsUpAfterGoingForTheNearest() throws Exception {
        world.set(60, 20, 8, BlockRegistry.ANCIENT_DEBRIS);
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -40, 8.5);
        waitForPath();

        assertNull(miningArea(), "it's not keeping to anywhere for this");
    }

    // ----------------------------------------------------------------------------------------
    // looking for more
    // ----------------------------------------------------------------------------------------

    @Test
    void goesLookingWhenThereAreNoneLoaded() throws Exception {
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -40, 8.5);

        waitForPath();

        assertEquals("Looking for ancient_debris, as there's none in the loaded chunks", module.status());
        assertTrue(currentGoal().isInGoal(40, -40, 8));

        TestWorld.putBotAt(40.5, -40, 8.5);
        pathEnds(true);

        assertTrue(currentGoal().isInGoal(72, -40, 8), "keeps going the same way");
    }

    @Test
    void stopsLookingOnceSomeComeIntoRange() throws Exception {
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -40, 8.5);
        waitForPath();

        world.set(50, -40, 8, BlockRegistry.ANCIENT_DEBRIS);
        TestWorld.putBotAt(40.5, -40, 8.5);
        pathEnds(true);

        assertEquals("Walking to the nearest ancient_debris", module.status());
        assertTrue(currentGoal().isInGoal(50, -40, 8));
    }

    @Test
    void triesAnotherWayIfItCantGoThatWay() throws Exception {
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -40, 8.5);
        waitForPath();

        pathEnds(false);
        waitForPath();

        assertTrue(currentGoal().isInGoal(8, -40, 40), "turned to the next direction");
        assertEquals(0, outOfReach().size(), "exploring isn't going for any block");
    }

    // ----------------------------------------------------------------------------------------
    // not being able to get somewhere
    // ----------------------------------------------------------------------------------------

    private void failPathsUntilClimbing() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            waitForPath();
            pathEnds(false);
        }
        tick();
    }

    private static boolean allowedToBuildWith(String item) {
        return CONFIG.client.extra.pathfinder.acceptableThrowawayItems.contains(item);
    }

    @Test
    void waitsABitAndTriesAgainWhenThereIsNoPath() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        pathEnds(false);

        assertEquals("Waiting: can't find a way to anything it can mine from here", module.status());
        assertFalse(followingAPath());
        tick(10);
        assertFalse(followingAPath(), "the pathfinder won't search again this soon");
        waitForPath();
    }

    @Test
    void goesForOtherBlocksAfterFailingToGetToSome() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        assertTrue(currentGoal().isInGoal(8, -7, 8));

        pathEnds(false);
        waitForPath();

        assertFalse(outOfReach().isEmpty());
        assertFalse(currentGoal().isInGoal(8, -7, 8), "that one was out of reach");
    }

    @Test
    void forgetsWhatWasOutOfReachAfterAWhile() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        pathEnds(false);
        waitForPath();
        assertFalse(outOfReach().isEmpty());

        tick(20 * 60 * 5 + 1);
        pathEnds(true);

        assertTrue(outOfReach().isEmpty());
        assertTrue(currentGoal().isInGoal(8, -7, 8), "worth another try");
    }

    @Test
    void aPathThatWorksMeansStartingAgain() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (int round = 0; round < 3; round++) {
            waitForPath();
            pathEnds(false);
            waitForPath();
            pathEnds(false);
            waitForPath();
            pathEnds(true);
        }

        assertFalse(module.status().startsWith("Climbing"), "status: " + module.status());
        assertFalse(allowedToBuildWith("cobbled_deepslate"));
    }

    @Test
    void buildsItsWayOutWhenThereIsNoWayToAnything() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        failPathsUntilClimbing();

        assertEquals("Climbing out from [8, -30, 8]", module.status());
        assertEquals(new GoalY(-24), currentGoal());
        assertTrue(allowedToBuildWith("cobbled_deepslate"), "so that the pathfinder can use what the bot has");
    }

    @Test
    void gettingOutComesBeforeErrands() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilClimbing();

        // Now it's full, which would normally send it off to store things.
        for (int slot = INVENTORY_SLOT; slot < 36; slot++) setSlot(slot, ItemRegistry.COBBLED_DEEPSLATE);
        for (int slot = 37; slot <= LAST_SLOT; slot++) setSlot(slot, ItemRegistry.COBBLED_DEEPSLATE);
        tick(5);

        assertFalse(module.errandRunning(), "storing things can wait until it's out");
        assertTrue(followingAPath());
        assertEquals("Climbing out from [8, -30, 8]", module.status());
    }

    @Test
    void goesBackToMiningAfterClimbingOut() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilClimbing();
        assertFalse(outOfReach().isEmpty());

        TestWorld.putBotAt(8.5, -24, 8.5);
        pathEnds(true);

        assertFalse(allowedToBuildWith("cobbled_deepslate"), "it's only for getting out");
        assertTrue(outOfReach().isEmpty(), "it can see things differently from here");
        assertTrue(followingAPath());
        assertFalse(module.status().startsWith("Climbing"), "status: " + module.status());
    }

    @Test
    void takesOffOnlyWhatItAddedToTheListOfBlocksToBuildWith() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.STONE);
        setSlot(INVENTORY_SLOT + 1, ItemRegistry.TUFF);
        TestWorld.putBotAt(8.5, -30, 8.5);

        failPathsUntilClimbing();
        assertTrue(allowedToBuildWith("tuff"));
        assertTrue(allowedToBuildWith("stone"));

        pathEnds(true);

        assertFalse(allowedToBuildWith("tuff"));
        assertTrue(allowedToBuildWith("stone"), "stone was on the list already");
    }

    @Test
    void doesntBuildWithWhatsNotCheapAndSolid() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.GRAVEL);
        setSlot(INVENTORY_SLOT + 1, ItemRegistry.DIAMOND_BLOCK);
        TestWorld.putBotAt(8.5, -30, 8.5);

        failPathsUntilClimbing();

        assertFalse(BlockMinerPlugin.PLUGIN_CONFIG.enabled, "gravel falls, and diamond blocks aren't for building with");
        assertFalse(allowedToBuildWith("gravel"));
        assertFalse(allowedToBuildWith("diamond_block"));
    }

    @Test
    void stopsAndSaysSoIfItHasNothingToClimbWith() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        failPathsUntilClimbing();

        assertFalse(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
        assertFalse(followingAPath());
        assertTrue(module.status().startsWith("Stopped: It's stuck at [8, -30, 8]"), "status: " + module.status());
    }

    @Test
    void stopsAndSaysSoIfClimbingFails() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilClimbing();

        pathEnds(false);

        assertFalse(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
        assertFalse(allowedToBuildWith("cobbled_deepslate"));
        assertTrue(module.status().startsWith("Stopped: It's stuck at"), "status: " + module.status());
    }

    @Test
    void stopsAndSaysSoIfItKeepsGettingStuck() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (int time = 0; time < 3; time++) {
            failPathsUntilClimbing();
            assertTrue(module.status().startsWith("Climbing"), "status: " + module.status());
            pathEnds(true);
        }
        failPathsUntilClimbing();

        assertFalse(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
        assertTrue(module.status().startsWith("Stopped: It keeps getting stuck"), "status: " + module.status());
    }

    @Test
    void climbsIfAnErrandHasNowhereToPutSomethingDown() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        askForRoom();

        tick(3);

        assertEquals("Climbing to find room for its errand", module.status());
        assertEquals(new GoalY(-24), currentGoal());
        assertTrue(allowedToBuildWith("cobbled_deepslate"));
    }

    @Test
    void triesDiggingItsWayUpForRoomWithNothingToBuildWith() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        askForRoom();

        tick(3);

        assertTrue(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
        assertEquals("Climbing to find room for its errand", module.status());
        assertEquals(new GoalY(-24), currentGoal());
    }
}
