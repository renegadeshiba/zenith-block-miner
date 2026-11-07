package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.entity.EntityStandard;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalY;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.impl.KillAura;
import com.zenith.util.ItemUtil;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
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
        CONFIG.client.extra.killAura.enabled = false;
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

    private void failPathsUntil(String status) throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (module.status().startsWith(status) || module.status().startsWith("Stopped")) break;
            waitForPath();
            pathEnds(false);
        }
        tick();
    }

    private void failPathsUntilClimbing() throws Exception {
        failPathsUntil("Climbing");
    }

    private void failPathsUntilWalkingAway() throws Exception {
        failPathsUntil("Walking away");
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
            TestWorld.putBotAt(9.5 + round, -30, 8.5);
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
        assertEquals(BlockScan.avoidingVines(new GoalY(-24)), currentGoal());
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
    void walkingAwayComesBeforeErrandsToo() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();

        for (int slot = INVENTORY_SLOT; slot < 36; slot++) setSlot(slot, ItemRegistry.COBBLED_DEEPSLATE);
        for (int slot = 37; slot <= LAST_SLOT; slot++) setSlot(slot, ItemRegistry.COBBLED_DEEPSLATE);
        tick(5);

        assertFalse(module.errandRunning());
        assertTrue(followingAPath());
        assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    private static void toolInHand(ItemData item, int durabilityLeft) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.DAMAGE, ItemUtil.getMaxDamage(new ItemStack(item.id(), 1)) - durabilityLeft);
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().setItemStack(36, new ItemStack(item.id(), 1, components));
    }

    /** Walking away can be 100 blocks of digging, and that would finish off the pickaxe with a long way still to go. */
    @Test
    void walkingAwayWaitsForAToolThatsAboutToBreakAndThenCarriesOn() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        Goal walkingAway = currentGoal();

        toolInHand(ItemRegistry.NETHERITE_PICKAXE, 5);
        tick();

        assertFalse(followingAPath(), "it should have stopped digging");

        toolInHand(ItemRegistry.NETHERITE_PICKAXE, 1500);
        tickUntil(() -> !module.errandRunning(), "finishing the errand");
        waitForPath();

        assertEquals(walkingAway, currentGoal(), "it should carry on the way it was going");
    }

    /** It can be climbing because an errand had nowhere to put a shulker box down, so that has to come first. */
    @Test
    void climbingOutDoesntStopForAToolThatsAboutToBreak() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilClimbing();

        toolInHand(ItemRegistry.NETHERITE_PICKAXE, 5);
        tick(5);

        assertFalse(module.errandRunning());
        assertTrue(followingAPath());
        assertEquals("Climbing out from [8, -30, 8]", module.status());
    }

    // ----------------------------------------------------------------------------------------
    // not getting anything done
    // ----------------------------------------------------------------------------------------

    /** What it did all night on top of an ender chest: every walk was over at once, and that counted as getting there. */
    @Test
    void arrivingWithoutMovingAgainAndAgainCountsAsNotGettingThere() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (int arrival = 0; arrival < 3; arrival++) {
            waitForPath();
            pathEnds(true);
        }

        assertFalse(outOfReach().isEmpty(), "it should give up on what it was going for");
        assertEquals("Waiting: can't find a way to anything it can mine from here", module.status());
    }

    @Test
    void arrivingWithoutMovingOnceOrTwiceIsFine() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (int arrival = 0; arrival < 2; arrival++) {
            waitForPath();
            pathEnds(true);
        }

        assertTrue(outOfReach().isEmpty());
    }

    @Test
    void goingSomewhereStartsTheCountOfArrivalsAgain() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (double x : new double[]{8.5, 8.5, 9.5, 9.5, 9.5 - 1, 8.5, 8.5}) {
            waitForPath();
            TestWorld.putBotAt(x, -30, 8.5);
            pathEnds(true);
        }

        assertTrue(outOfReach().isEmpty());
    }

    /** It breaks a block without moving, and that really is getting something done. */
    @Test
    void miningABlockOverheadIsntArrivingWithoutMoving() throws Exception {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();

        for (int block = 0; block < 3; block++) {
            waitForMiningABlock();
            pathEnds(true);
        }

        assertTrue(outOfReach().isEmpty());
    }

    /** Arriving where it already is, and then mining what's above it from there, which really is getting something done. */
    @Test
    void miningABlockOverheadAfterArrivingWithoutMovingStartsTheCountAgain() throws Exception {
        want("cobbled_deepslate");
        putBotOnTheTopLevel();
        for (int y = -5; y <= -1; y++) world.set(8, y, 8, BlockRegistry.AIR);

        waitForPath();
        pathEnds(true);
        waitForPath();
        for (int y = -5; y <= -1; y++) world.set(8, y, 8, BlockRegistry.DEEPSLATE);
        pathEnds(true);
        waitForMiningABlock();
        pathEnds(true);

        assertTrue(outOfReach().isEmpty(), "that was two arrivals and then a block mined");
    }

    /** Every walk goes somewhere and ends well, so that nothing else ever notices it's not getting anywhere. */
    private int ticksGoingBackAndForth(java.util.function.IntConsumer eachWalk) throws Exception {
        int ticks = 0;
        for (int walk = 0; walk < 60; walk++) {
            waitForPath();
            eachWalk.accept(walk);
            tick(100);
            ticks += 100;
            if (module.status().startsWith("Walking away")) break;
            pathEnds(true);
        }
        return ticks;
    }

    @Test
    void walksAwayAfterMinutesOfGoingBackAndForthWithNothingToShowForIt() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        int ticks = ticksGoingBackAndForth(walk -> TestWorld.putBotAt(walk % 2 == 0 ? 9.5 : 8.5, -30, 8.5));

        assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
        assertTrue(ticks >= 3 * A_MINUTE - 200, "that was too soon, after " + ticks);
        assertTrue(ticks <= 3 * A_MINUTE + 200, "that was too late, after " + ticks);
    }

    @Test
    void goingSomewhereIsGettingSomethingDone() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        ticksGoingBackAndForth(walk -> TestWorld.putBotAt(walk % 2 == 0 ? 16.5 : 8.5, -30, 8.5));

        assertFalse(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    @Test
    void pickingSomethingUpIsGettingSomethingDone() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        ticksGoingBackAndForth(walk -> {
            TestWorld.putBotAt(walk % 2 == 0 ? 9.5 : 8.5, -30, 8.5);
            CACHE.getPlayerCache().getInventoryCache().getPlayerInventory()
                .setItemStack(INVENTORY_SLOT, new ItemStack(ItemRegistry.COBBLED_DEEPSLATE.id(), 1 + walk % 64));
        });

        assertFalse(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    @Test
    void aFightDoesntCountAsGettingNothingDone() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        for (int walk = 0; walk < 30; walk++) {
            waitForPath();
            TestWorld.putBotAt(walk % 2 == 0 ? 9.5 : 8.5, -30, 8.5);
            tick(100);
            pathEnds(true);
        }

        killAuraHasATarget(true);
        tick(2 * A_MINUTE);
        killAuraHasATarget(false);
        waitForPath();

        assertFalse(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    private final KillAura killAura = new KillAura();
    private final EntityStandard piglin = new EntityStandard();

    /** Zenith's real kill aura, as it is when it has something in reach. */
    private void killAuraHasATarget(boolean hasATarget) throws ReflectiveOperationException {
        CONFIG.client.extra.killAura.enabled = hasATarget;
        MODULE.registerModule(killAura);
        Field target = KillAura.class.getDeclaredField("attackTarget");
        target.setAccessible(true);
        target.set(killAura, new WeakReference<EntityLiving>(hasATarget ? piglin : null));
    }

    @Test
    void stopsMiningForAFightAndGoesBackToItAfterwards() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        killAuraHasATarget(true);
        tick();

        assertFalse(followingAPath(), "it should stand there and let the kill aura get on with it");
        assertTrue(module.status().startsWith("Leaving it to the kill aura"), "status: " + module.status());
        tick(20);
        assertFalse(followingAPath(), "it shouldn't start anything else either");

        killAuraHasATarget(false);
        waitForPath();
        assertFalse(module.status().startsWith("Leaving it to the kill aura"), "status: " + module.status());
    }

    @Test
    void aFightWaitsForNoErrand() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        killAuraHasATarget(true);

        // Now it's full, which would normally send it off to store things.
        for (int slot = INVENTORY_SLOT; slot < 36; slot++) setSlot(slot, ItemRegistry.COBBLED_DEEPSLATE);
        for (int slot = 37; slot <= LAST_SLOT; slot++) setSlot(slot, ItemRegistry.COBBLED_DEEPSLATE);
        tick(5);

        assertFalse(module.errandRunning(), "storing things can wait until the fight's over");
        assertFalse(followingAPath());
        killAuraHasATarget(false);
    }

    @Test
    void aFightDoesntCountAsAPathThatFailed() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        List<BlockPos> goingFor = blocksItsGoingFor();

        killAuraHasATarget(true);
        tick(3);
        assertEquals(Set.of(), outOfReach(), "it could have got there, if it hadn't stopped to fight");
        killAuraHasATarget(false);
        waitForPath();

        assertEquals(goingFor, blocksItsGoingFor());
    }

    @Test
    void walkingAwayPicksUpAfterAFight() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        Goal walkingAway = currentGoal();

        killAuraHasATarget(true);
        tick();
        assertFalse(followingAPath());

        killAuraHasATarget(false);
        waitForPath();
        assertEquals(walkingAway, currentGoal(), "it should carry on the way it was going");
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

        // Walking away works every time, but it never gets any mining done.
        for (int time = 0; time < 5; time++) {
            failPathsUntilWalkingAway();
            assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
            waitForPath();
            pathEnds(true);
            // It gets some of the way to the top of the new section, which isn't getting any mining done.
            waitForPath();
            pathEnds(true);
        }
        failPathsUntil("Walking away");

        assertFalse(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
        assertTrue(module.status().startsWith("Stopped: It keeps getting stuck"), "status: " + module.status());
    }

    @Test
    void gettingSomeMiningDoneMeansItsNotStuckAnyMore() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);

        for (int time = 0; time < 8; time++) {
            module.blocksChanged();
            TestWorld.putBotAt(8.5, -30, 8.5);
            failPathsUntilWalkingAway();
            waitForPath();
            // It got to the top of a section, and mines one thing.
            putBotOnTheTopLevel();
            pathEnds(true);
            waitForMiningABlock();
            pathEnds(true);
        }

        assertTrue(BlockMinerPlugin.PLUGIN_CONFIG.enabled, "status: " + module.status());
    }

    // ----------------------------------------------------------------------------------------
    // building only when it has to
    // ----------------------------------------------------------------------------------------

    @Test
    void doesntBuildUntilItHasTo() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        waitForPath();

        assertFalse(allowedToBuildWith("cobbled_deepslate"), "it would climb by building pillars out in the open");
    }

    @Test
    void triesAgainWithBlocksBeforeGivingUpOnAnything() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        pathEnds(false);

        assertTrue(allowedToBuildWith("cobbled_deepslate"));
        assertTrue(outOfReach().isEmpty(), "it might be able to get there now");
        assertEquals("Waiting: can't find a way without building, so trying again with blocks", module.status());
        waitForPath();
        assertTrue(currentGoal().isInGoal(8, -7, 8), "the same place as before");
    }

    @Test
    void givesUpOnThemIfItStillCantGetThereWithBlocks() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        pathEnds(false);
        waitForPath();

        pathEnds(false);

        assertFalse(outOfReach().isEmpty());
        waitForPath();
        assertFalse(currentGoal().isInGoal(8, -7, 8));
        assertTrue(allowedToBuildWith("cobbled_deepslate"), "until something works");
    }

    @Test
    void doesntTryAgainIfItCouldBuildAlready() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.STONE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        pathEnds(false);

        assertFalse(outOfReach().isEmpty(), "stone is on the list already, so nothing's changed");
    }

    @Test
    void goesBackToDiggingOnceAPathWorks() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        pathEnds(false);
        waitForPath();
        assertTrue(allowedToBuildWith("cobbled_deepslate"));

        pathEnds(true);

        assertFalse(allowedToBuildWith("cobbled_deepslate"));
    }

    @Test
    void triesBuildingAgainTheNextTimeItCantFindAWay() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        pathEnds(false);
        waitForPath();
        pathEnds(true);
        waitForPath();

        pathEnds(false);

        assertTrue(allowedToBuildWith("cobbled_deepslate"));
        assertTrue(outOfReach().isEmpty());
    }

    /** A walk that has to build to get there, with building allowed after a path that couldn't be found without. */
    private void buildsToGetThere(Runnable onTheWay) throws Exception {
        waitForPath();
        pathEnds(false);
        waitForPath();
        assertTrue(allowedToBuildWith("cobbled_deepslate"));
        world.set(9, -30, 8, BlockRegistry.AIR);
        clicksOn(9, -31, 8, Direction.UP);
        onTheWay.run();
        TestWorld.putBotAt(9.5, -30, 8.5);
        pathEnds(true);
    }

    @Test
    void keepsBuildingForWhatsNextAfterAWalkThatHadToBuild() throws Exception {
        want("obsidian");
        world.set(10, -30, 8, BlockRegistry.OBSIDIAN);
        world.set(13, -30, 8, BlockRegistry.OBSIDIAN);
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        buildsToGetThere(() -> world.set(10, -30, 8, BlockRegistry.AIR));
        waitForPath();

        assertEquals(List.of(new BlockPos(13, -30, 8)), blocksItsGoingFor());
        assertTrue(allowedToBuildWith("cobbled_deepslate"), "it'll most likely need to for what's next to it, too");
    }

    @Test
    void stopsBuildingOnceAWalkDoesntNeedTo() throws Exception {
        want("obsidian");
        world.set(10, -30, 8, BlockRegistry.OBSIDIAN);
        world.set(13, -30, 8, BlockRegistry.OBSIDIAN);
        world.set(16, -30, 8, BlockRegistry.OBSIDIAN);
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        buildsToGetThere(() -> world.set(10, -30, 8, BlockRegistry.AIR));

        waitForPath();
        assertTrue(allowedToBuildWith("cobbled_deepslate"), "it's only next to it");
        world.set(13, -30, 8, BlockRegistry.AIR);
        TestWorld.putBotAt(12.5, -30, 8.5);
        pathEnds(true);

        assertFalse(allowedToBuildWith("cobbled_deepslate"), "that one didn't need it");
    }

    /** Any further, and building means pillars out in the open. */
    @Test
    void stopsBuildingToGoAnywhereFurtherThanJustNextToIt() throws Exception {
        want("obsidian");
        world.set(10, -30, 8, BlockRegistry.OBSIDIAN);
        world.set(40, -30, 40, BlockRegistry.OBSIDIAN);
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        buildsToGetThere(() -> world.set(10, -30, 8, BlockRegistry.AIR));
        waitForPath();

        assertEquals(List.of(new BlockPos(40, -30, 40)), blocksItsGoingFor());
        assertFalse(allowedToBuildWith("cobbled_deepslate"));
    }

    @Test
    void stopsBuildingToGoLookingForMore() throws Exception {
        want("obsidian");
        world.set(10, -30, 8, BlockRegistry.OBSIDIAN);
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        buildsToGetThere(() -> world.set(10, -30, 8, BlockRegistry.AIR));
        waitForPath();

        assertTrue(module.status().startsWith("Looking for"), "status: " + module.status());
        assertFalse(allowedToBuildWith("cobbled_deepslate"));
    }

    private void clicksOn(int x, int y, int z, Direction face) {
        module.getClientPacketHandlerCodec().getStateCodecs().get(ProtocolState.GAME).handlePostOutgoing(
            new ServerboundUseItemOnPacket(x, y, z, face, Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f, false, false, 0), null);
    }

    @Test
    void leavesAloneABlockItPlaced() throws Exception {
        want("cobbled_deepslate");
        // It clicks on the top of the block below, and then the one it put there turns up.
        world.set(8, -5, 8, BlockRegistry.AIR);
        clicksOn(8, -6, 8, Direction.UP);
        world.set(8, -5, 8, BlockRegistry.COBBLED_DEEPSLATE);
        putBotOnTheTopLevel();

        // Whatever it does first: it would give up on mining a block here in the end, as nothing can break it.
        tickUntil(() -> followingAPath() || miningABlock(), "doing something");

        assertFalse(miningABlock(), "that's the first thing above it");
        assertEquals("Mining", module.status());
        assertFalse(blocksItsGoingFor().contains(new BlockPos(8, -5, 8)));
    }

    @Test
    void stillMinesTheSameBlockIfItDidntPlaceIt() throws Exception {
        want("cobbled_deepslate");
        world.set(8, -5, 8, BlockRegistry.COBBLED_DEEPSLATE);
        putBotOnTheTopLevel();

        waitForMiningABlock();

        assertEquals(List.of(new BlockPos(8, -5, 8)), blocksItsGoingFor());
    }

    // ----------------------------------------------------------------------------------------
    // what's left of a level
    // ----------------------------------------------------------------------------------------

    private void onTheTopLevelWithNothingAboveIt() {
        for (int y = -5; y <= -1; y++) world.set(8, y, 8, BlockRegistry.AIR);
        putBotOnTheTopLevel();
    }

    @Test
    void whatsLeftOfALevelThatItCantGetToDoesntMakeItStuck() throws Exception {
        want("cobbled_deepslate");
        onTheTopLevelWithNothingAboveIt();

        for (int attempt = 0; attempt < 3; attempt++) {
            waitForPath();
            assertEquals("Mining", module.status());
            pathEnds(false);
            assertEquals("Waiting: can't get to what's left of this level", module.status());
        }

        waitForPath();
        assertEquals(WALKING, module.status());
        assertEquals(-14, miningArea().level(), "it's moved on to the next level");
        assertTrue(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
    }

    @Test
    void onlyGivesUpOnALevelAfterFailingSeveralTimesInARow() throws Exception {
        want("cobbled_deepslate");
        onTheTopLevelWithNothingAboveIt();
        waitForPath();
        pathEnds(false);
        waitForPath();
        pathEnds(false);
        waitForPath();
        pathEnds(true);
        waitForPath();

        pathEnds(false);

        assertEquals(-7, miningArea().level());
        assertEquals(3 * 64, outOfReach().size(), "just what it's failed to get to");
    }

    @Test
    void startsCountingAgainOnTheNextLevel() throws Exception {
        want("cobbled_deepslate");
        onTheTopLevelWithNothingAboveIt();
        for (int attempt = 0; attempt < 3; attempt++) {
            waitForPath();
            pathEnds(false);
        }
        // It's ended up on the next level with nothing having gone right yet, such as by falling down a hole.
        for (int y = -12; y <= -8; y++) world.set(8, y, 8, BlockRegistry.AIR);
        TestWorld.putBotAt(8.5, -14, 8.5);
        waitForPath();
        assertEquals("Mining", module.status());
        assertEquals(-14, miningArea().level());
        int before = outOfReach().size();

        pathEnds(false);

        assertEquals(-14, miningArea().level());
        assertEquals(before + 64, outOfReach().size(), "it hasn't given up on this one as well");
    }

    @Test
    void itsStuckIfItCantGetToTheNextLevelEither() throws Exception {
        want("cobbled_deepslate");
        onTheTopLevelWithNothingAboveIt();
        for (int attempt = 0; attempt < 3; attempt++) {
            waitForPath();
            pathEnds(false);
        }

        failPathsUntilWalkingAway();

        assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    // ----------------------------------------------------------------------------------------
    // walking away
    // ----------------------------------------------------------------------------------------

    @Test
    void goingForTheNearestTakesMoreFailuresToBeStuck() throws Exception {
        // Only a few at a time get tried, so that's not many blocks that it can't get to.
        for (int x = 20; x <= 40; x++) {
            for (int z = 0; z <= 4; z++) world.set(x, -30, z, BlockRegistry.ANCIENT_DEBRIS);
        }
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (int attempt = 0; attempt < 11; attempt++) {
            waitForPath();
            pathEnds(false);
            assertTrue(module.status().startsWith("Waiting"), "status after " + attempt + ": " + module.status());
        }
        waitForPath();
        pathEnds(false);

        assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    @Test
    void minesOreAboveItWithoutWalking() throws Exception {
        world.set(8, -28, 8, BlockRegistry.ANCIENT_DEBRIS);
        want("ancient_debris");
        TestWorld.putBotAt(8.5, -30, 8.5);

        waitForMiningABlock();

        assertEquals(List.of(new BlockPos(8, -28, 8)), blocksItsGoingFor());
        assertEquals("Mining", module.status());
    }

    // ----------------------------------------------------------------------------------------
    // not getting anywhere
    // ----------------------------------------------------------------------------------------

    private static final int A_MINUTE = 20 * 60;

    @Test
    void givesUpOnAWalkThatsGettingNowhere() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        tick(A_MINUTE - 5);
        assertTrue(followingAPath(), "that's not long enough to give up");
        tick(10);

        assertFalse(followingAPath());
        assertFalse(outOfReach().isEmpty(), "it can't get there, as far as it's concerned");
        assertEquals("Waiting: can't find a way to anything it can mine from here", module.status());
    }

    @Test
    void anythingLikeClimbingAVineGetsItOutInTheEnd() throws Exception {
        // The pathfinder never says that it's failed, so each of these has to find out for itself.
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        for (int walk = 0; walk < 3; walk++) {
            waitForPath();
            tick(A_MINUTE + 5);
        }

        assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
    }

    @Test
    void aLongWalkIsFineAsLongAsItsGettingSomewhere() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        for (int block = 1; block <= 5; block++) {
            tick(A_MINUTE - 100);
            TestWorld.putBotAt(8.5 + block * 4, -30, 8.5);
        }
        tick(5);

        assertTrue(followingAPath());
        assertTrue(outOfReach().isEmpty());
    }

    @Test
    void goingBackAndForthIsntGettingAnywhere() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();

        for (int time = 0; time < 2 * A_MINUTE; time += 100) {
            TestWorld.putBotAt(time % 200 == 0 ? 8.5 : 9.5, -30, 8.5);
            tick(100);
        }

        assertFalse(outOfReach().isEmpty(), "it should have given up by now");
    }

    @Test
    void everyWalkGetsAFreshStart() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        waitForPath();
        TestWorld.putBotAt(9.5, -30, 8.5);
        tick(5);
        TestWorld.putBotAt(8.5, -30, 8.5);
        tick(A_MINUTE - 50);
        pathEnds(true);
        waitForPath();

        // Back over the same ground, which is all new to this walk.
        tick(A_MINUTE - 100);
        TestWorld.putBotAt(9.5, -30, 8.5);
        tick(A_MINUTE - 100);

        assertTrue(followingAPath());
        assertTrue(outOfReach().isEmpty());
    }

    @Test
    void walkingAwayGivesUpTooAndTriesAnotherWay() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        waitForPath();
        assertTrue(currentGoal().isInGoal(108, -30, 8));

        tick(A_MINUTE + 5);
        waitForPath();

        assertTrue(currentGoal().isInGoal(8, -30, 108), "the next way");
    }

    @Test
    void walksAwayWhenItCantGetToAnything() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);

        failPathsUntilWalkingAway();

        assertEquals("Walking away from [8, -30, 8], as it can't get to anything from there", module.status());
        waitForPath();
        assertTrue(currentGoal().isInGoal(108, -30, 8));
        assertTrue(currentGoal().isInGoal(108, 40, 8), "at any height");
        assertFalse(currentGoal().isInGoal(108, -59, 8), "apart from below the lowest level");
        assertTrue(BlockMinerPlugin.PLUGIN_CONFIG.enabled);
    }

    @Test
    void startsANewSectionFromWhereverItWalksTo() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        waitForPath();
        assertFalse(outOfReach().isEmpty());

        TestWorld.putBotAt(70.5, -30, 8.5);
        pathEnds(true);

        assertSectionCentredOn(70, 8);
        assertEquals(-7, miningArea().level(), "from the top");
        assertTrue(outOfReach().isEmpty(), "nothing that was out of reach from where it was has to be from here");
        assertEquals(WALKING, module.status());
    }

    @Test
    void itsWalkedAwayIfItGotFarEnoughBeforeThePathFailed() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        waitForPath();

        TestWorld.putBotAt(32.5, -30, 8.5);
        pathEnds(false);

        assertSectionCentredOn(32, 8);
        assertEquals(WALKING, module.status());
    }

    @Test
    void itHasntWalkedAwayIfItDidntGetFarEnough() throws Exception {
        want("cobbled_deepslate");
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        waitForPath();

        TestWorld.putBotAt(31.5, -30, 8.5);
        pathEnds(false);

        assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
        assertFalse(outOfReach().isEmpty());
    }

    @Test
    void triesEveryWayBeforeBuildingUp() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();

        int[][] ends = {{108, 8}, {8, 108}, {-92, 8}, {8, -92}};
        for (int[] end : ends) {
            assertTrue(module.status().startsWith("Walking away"), "status: " + module.status());
            waitForPath();
            assertTrue(currentGoal().isInGoal(end[0], -30, end[1]), "should be heading for " + end[0] + " " + end[1]);
            pathEnds(false);
        }

        assertEquals("Climbing out from [8, -30, 8]", module.status());
        waitForPath();
        assertEquals(BlockScan.avoidingVines(new GoalY(-24)), currentGoal());
        assertTrue(allowedToBuildWith("cobbled_deepslate"));
    }

    @Test
    void canBuildToWalkAway() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        failPathsUntilWalkingAway();

        assertTrue(allowedToBuildWith("cobbled_deepslate"));
    }

    @Test
    void stopsBuildingOnceItsWalkedAway() throws Exception {
        want("cobbled_deepslate");
        setSlot(INVENTORY_SLOT, ItemRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);
        failPathsUntilWalkingAway();
        waitForPath();

        TestWorld.putBotAt(70.5, -30, 8.5);
        pathEnds(true);

        assertFalse(allowedToBuildWith("cobbled_deepslate"));
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
        assertEquals(BlockScan.avoidingVines(new GoalY(-24)), currentGoal());
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
        assertEquals(BlockScan.avoidingVines(new GoalY(-24)), currentGoal());
    }
}
