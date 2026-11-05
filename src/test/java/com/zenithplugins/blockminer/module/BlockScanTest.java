package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalAvoidBlocks;
import com.zenith.feature.pathfinder.goals.GoalComposite;
import com.zenith.feature.pathfinder.goals.GoalTwoBlocks;
import com.zenith.feature.pathfinder.goals.GoalY;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.process.MineProcess;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs next to ZenithProxy's real path search and chunk code, over a made-up world. What this can't show
 * is the bot on a real server, as nothing here is connected to one: see {@link PretendBot}.
 */
class BlockScanTest {

    private static final BlockOptionalMetaLookup DEEPSLATE =
        new BlockOptionalMetaLookup(BlockRegistry.DEEPSLATE, BlockRegistry.COBBLED_DEEPSLATE);
    private static final BlockOptionalMetaLookup DIAMONDS =
        new BlockOptionalMetaLookup(BlockRegistry.DIAMOND_ORE, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
    private static final BlockScan.MiningArea WHOLE_WORLD = new BlockScan.MiningArea(0, 0, 10_000);
    private static final Set<BlockPos> NOTHING_OUT_OF_REACH = Set.of();
    private static final int LOWEST_LEVEL = -50;

    private TestWorld world;

    @BeforeEach
    void loadChunks() throws ReflectiveOperationException {
        world = new TestWorld(4);
        setHotbarPickaxe(true);
    }

    @AfterEach
    void unloadChunks() {
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
        setHotbarPickaxe(false);
    }

    private static void setHotbarPickaxe(boolean held) {
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory()
            .setItemStack(36, held ? new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1) : Container.EMPTY_STACK);
    }

    private static BlockScan.MiningGoal goalAnywhere(BlockOptionalMetaLookup filter) {
        return BlockScan.miningGoal(filter, WHOLE_WORLD, NOTHING_OUT_OF_REACH);
    }

    /** Deepslate at the bottom and stone above it, mixing where they meet, with ore dotted about. */
    private void realisticWorld() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        world.layer(0, 60, BlockRegistry.STONE);
        Random random = new Random(42);
        for (int x = world.minX(); x <= world.maxX(); x++) {
            for (int z = world.minX(); z <= world.maxX(); z++) {
                for (int y = 0; y <= 8; y++) {
                    if (random.nextInt(9) >= y) world.set(x, y, z, BlockRegistry.DEEPSLATE);
                }
                if (random.nextInt(40) == 0) {
                    world.set(x, -60 + random.nextInt(70), z, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
                }
            }
        }
    }

    /** Bedrock the way vanilla lays it out: solid at the bottom, thinning out over the next four layers. */
    private void deepslateDownToBedrock(int seed) {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        Random random = new Random(seed);
        for (int x = world.minX(); x <= world.maxX(); x++) {
            for (int z = world.minX(); z <= world.maxX(); z++) {
                world.set(x, -64, z, BlockRegistry.BEDROCK);
                for (int height = 1; height <= 4; height++) {
                    if (random.nextInt(5) >= height) world.set(x, -64 + height, z, BlockRegistry.BEDROCK);
                }
            }
        }
    }

    private void airAt(BlockPos pos) {
        world.set(pos.x(), pos.y(), pos.z(), BlockRegistry.AIR);
        world.set(pos.x(), pos.y() + 1, pos.z(), BlockRegistry.AIR);
    }

    // ----------------------------------------------------------------------------------------
    // finding blocks
    // ----------------------------------------------------------------------------------------

    private static int distanceSq(BlockPos pos, int x, int y, int z) {
        int dx = pos.x() - x;
        int dy = pos.y() - y;
        int dz = pos.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Test
    void findsTheClosestOnesWhereverTheyAre() {
        Random random = new Random(7);
        List<BlockPos> placed = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            int x = world.minX() + random.nextInt(world.maxX() - world.minX() + 1);
            int y = -64 + random.nextInt(320);
            int z = world.minX() + random.nextInt(world.maxX() - world.minX() + 1);
            BlockPos pos = new BlockPos(x, y, z);
            if (placed.contains(pos)) continue;
            placed.add(pos);
            world.set(x, y, z, BlockRegistry.DEEPSLATE);
        }

        int[][] places = {{8, 70, 8}, {-50, -40, 33}, {70, 200, -60}};
        for (int[] place : places) {
            TestWorld.putBotAt(place[0] + 0.5, place[1], place[2] + 0.5);
            List<Integer> expected = placed.stream()
                .map(pos -> distanceSq(pos, place[0], place[1], place[2]))
                .sorted()
                .limit(50)
                .toList();

            List<BlockPos> found = BlockScan.nearest(DEEPSLATE, 50);

            // Blocks the same distance away could come in either order, so compare distances.
            assertEquals(expected, found.stream().map(pos -> distanceSq(pos, place[0], place[1], place[2])).toList());
        }
    }

    @Test
    void comesBackWithFewerIfThereAreFewer() {
        world.set(3, 20, 3, BlockRegistry.DEEPSLATE);
        world.set(40, 20, 3, BlockRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(0.5, 20, 0.5);

        assertEquals(List.of(new BlockPos(3, 20, 3), new BlockPos(40, 20, 3)), BlockScan.nearest(DEEPSLATE, 50));
    }

    @Test
    void findsNothingInAnEmptyWorld() {
        TestWorld.putBotAt(0.5, 20, 0.5);
        assertTrue(BlockScan.nearest(DEEPSLATE, 50).isEmpty());
        assertNull(goalAnywhere(DEEPSLATE));
    }

    @Test
    void leavesOutABlockTheBotIsAlreadyStandingIn() {
        // Walking to it would be over before it started, without breaking anything.
        world.set(0, 20, 0, BlockRegistry.DEEPSLATE);
        world.set(0, 21, 0, BlockRegistry.DEEPSLATE);
        world.set(2, 20, 0, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(0.5, 20, 0.5);

        assertEquals(List.of(new BlockPos(2, 20, 0)), BlockScan.nearest(DEEPSLATE, 50));
    }

    @Test
    void onlyLooksBetweenTheHeightsItsToldTo() {
        world.set(3, 19, 3, BlockRegistry.DEEPSLATE);
        world.set(9, 20, 3, BlockRegistry.DEEPSLATE);
        world.set(9, 21, 9, BlockRegistry.DEEPSLATE);
        world.set(3, 22, 3, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(0.5, 20, 0.5);

        assertEquals(List.of(new BlockPos(9, 20, 3), new BlockPos(9, 21, 9)), BlockScan.nearest(DEEPSLATE, 50, 20, 21, null));
    }

    @Test
    void holdsOnToNoMoreThanItsAskedForHoweverManyThereAre() {
        // Over a million of them, which is what ran Zenith's search out of memory.
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, 40, 8.5);

        assertEquals(256, BlockScan.nearest(DEEPSLATE, 256).size());
    }

    // ----------------------------------------------------------------------------------------
    // telling a block that's everywhere from one worth looking for
    // ----------------------------------------------------------------------------------------

    @Test
    void tellsABlockThatsEverywhereFromAnOre() {
        realisticWorld();
        TestWorld.putBotAt(8.5, -30, 8.5);

        assertTrue(BlockScan.isPlentifulNearby(DEEPSLATE));
        assertFalse(BlockScan.isPlentifulNearby(DIAMONDS));
    }

    @Test
    void aBlockFarAboveOrBelowTheBotIsntNearby() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);

        TestWorld.putBotAt(8.5, 40, 8.5);
        assertFalse(BlockScan.isPlentifulNearby(DEEPSLATE));
        TestWorld.putBotAt(8.5, 10, 8.5);
        assertTrue(BlockScan.isPlentifulNearby(DEEPSLATE));
    }

    @Test
    void blocksBelowTheLowestLevelDontCountAsNearby() {
        world.layer(-64, -51, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, -45, 8.5);
        assertFalse(BlockScan.isPlentifulNearby(DEEPSLATE));

        world.layer(-50, -50, BlockRegistry.DEEPSLATE);
        assertTrue(BlockScan.isPlentifulNearby(DEEPSLATE));
    }

    // ----------------------------------------------------------------------------------------
    // what it won't go for
    // ----------------------------------------------------------------------------------------

    @Test
    void leavesOutBlocksItShouldntBreak() {
        // Sitting on lava, which breaking it would let out.
        world.set(0, 19, 0, BlockRegistry.DEEPSLATE);
        world.set(0, 20, 0, BlockRegistry.LAVA);
        TestWorld.putBotAt(0.5, 30, 0.5);
        assertFalse(MineProcess.plausibleToBreak(new CalculationContext(), new BlockPos(0, 19, 0)));
        assertNull(goalAnywhere(DEEPSLATE));

        world.set(10, 19, 0, BlockRegistry.DEEPSLATE);
        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
        assertNotNull(goal);
        assertTrue(goal.goal().isInGoal(10, 19, 0));
        assertFalse(goal.goal().isInGoal(0, 19, 0));
    }

    @Test
    void leavesOutBlocksItCouldntGetTo() {
        world.set(3, 20, 0, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        world.set(9, 20, 0, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(0.5, 20, 0.5);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DIAMONDS, WHOLE_WORLD, Set.of(new BlockPos(3, 20, 0)));

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(9, 20, 0)), goal.blocks());
        assertFalse(goal.goal().isInGoal(3, 20, 0));
    }

    @Test
    void getsOutOfLavaBeforeAnythingElse() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        airAt(new BlockPos(8, -30, 8));
        world.set(8, -30, 8, BlockRegistry.LAVA);
        TestWorld.putBotAt(8.5, -30, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);

        assertNotNull(goal);
        assertEquals(new GoalAvoidBlocks(8, -30, 8, BlockRegistry.LAVA), goal.goal());
        assertFalse(goal.onBotsLevel());
        assertFalse(goal.leavesArea());
    }

    // ----------------------------------------------------------------------------------------
    // what goes wrong if the bot goes wherever's cheapest, as Zenith's miner does
    // ----------------------------------------------------------------------------------------

    /** The same kind of goal Zenith's miner builds: the nearest of them, at any height. */
    private static Goal cheapestWhereverItIs() {
        CalculationContext context = new CalculationContext();
        return new GoalComposite(BlockScan.nearest(DEEPSLATE, 256).stream()
            .filter(pos -> MineProcess.plausibleToBreak(context, pos))
            .limit(64)
            .map(GoalTwoBlocks::new)
            .toArray(Goal[]::new));
    }

    /**
     * What this is all here to avoid. If this stops being true, Zenith's miner may not need replacing for
     * this any more. See {@link BlockScan}.
     */
    @Test
    void goingWherevesCheapestDigsStraightDownAndGetsStuckInTheBedrock() throws ReflectiveOperationException {
        for (int seed : new int[] {1, 2, 3}) {
            world.clear();
            world = new TestWorld(4);
            deepslateDownToBedrock(seed);
            BlockPos start = new BlockPos(8, -30, 8);
            airAt(start);
            PretendBot bot = new PretendBot(world, start, DEEPSLATE);

            boolean stuck = false;
            for (int step = 0; step < 300 && !stuck; step++) {
                stuck = !bot.followPathTo(cheapestWhereverItIs());
                if (step < 20) assertTrue(bot.at.x() == 8 && bot.at.z() == 8, "left the shaft at " + bot.at);
            }

            assertTrue(stuck, "seed " + seed + " never got stuck, ended at " + bot.at);
            assertTrue(bot.at.y() <= -60, "seed " + seed + " got stuck at " + bot.at);
            assertFalse(new CalculationContext().hasThrowaway, "it can't build with cobbled deepslate");
        }
    }

    // ----------------------------------------------------------------------------------------
    // the lowest level, which applies to everything
    // ----------------------------------------------------------------------------------------

    @Test
    void doesntGoForAnythingBelowTheLowestLevel() {
        world.set(0, LOWEST_LEVEL - 1, 0, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(0.5, -40, 0.5);

        assertNull(goalAnywhere(DIAMONDS));
    }

    @Test
    void goesForAFarOreRatherThanACloseOneBelowTheLowestLevel() {
        world.set(0, LOWEST_LEVEL - 1, 0, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        world.set(60, -40, 0, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(0.5, LOWEST_LEVEL, 0.5);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(60, -40, 0)), goal.blocks());
    }

    @Test
    void goesAsFarDownAsTheLowestLevelForAnOre() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.set(8, LOWEST_LEVEL, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        BlockPos start = new BlockPos(8, -40, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DIAMONDS);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);
        assertNotNull(goal);
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);

        assertEquals(1, bot.wantedBroken);
        assertEquals(LOWEST_LEVEL, bot.at.y());
        assertEquals(LOWEST_LEVEL, bot.lowestY);
    }

    @Test
    void cantStopWithItsFeetBelowTheLowestLevelEvenWithItsHeadInABlock() {
        world.set(8, LOWEST_LEVEL, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(0.5, -40, 0.5);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);

        assertNotNull(goal);
        assertTrue(goal.goal().isInGoal(8, LOWEST_LEVEL, 8));
        assertFalse(goal.goal().isInGoal(8, LOWEST_LEVEL - 1, 8));
    }

    /** A long, tall wall between the bot and an ore, that it's much cheaper to tunnel under than walk around. */
    private BlockPos wallInTheWay() {
        world.layer(-64, LOWEST_LEVEL - 1, BlockRegistry.DEEPSLATE);
        for (int z = -30; z <= 30; z++) {
            for (int y = LOWEST_LEVEL; y <= LOWEST_LEVEL + 10; y++) {
                world.set(5, y, z, BlockRegistry.BEDROCK);
            }
        }
        world.set(10, LOWEST_LEVEL, 0, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        return new BlockPos(0, LOWEST_LEVEL, 0);
    }

    @Test
    void goesTheLongWayRoundRatherThanBelowTheLowestLevel() {
        PretendBot bot = new PretendBot(world, wallInTheWay(), DIAMONDS);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);
        assertNotNull(goal);
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);

        assertEquals(LOWEST_LEVEL, bot.lowestY, "went below the lowest level on the way");
        assertEquals(1, bot.wantedBroken);
    }

    @Test
    void wouldHaveGoneUnderIfItWasntForTheLowestLevel() {
        PretendBot bot = new PretendBot(world, wallInTheWay(), DIAMONDS);

        assertTrue(bot.followPathTo(new GoalTwoBlocks(10, LOWEST_LEVEL, 0)), "stuck at " + bot.at);

        assertTrue(bot.lowestY < LOWEST_LEVEL, "the test above doesn't test anything unless this is the cheaper way");
    }

    // ----------------------------------------------------------------------------------------
    // a block that's worth looking for
    // ----------------------------------------------------------------------------------------

    @Test
    void goesForAnOreWhereverItIsRatherThanKeepingToAnArea() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.set(70, -40, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(8.5, -40, 8.5);
        BlockScan.MiningArea area = new BlockScan.MiningArea(8, 8, BlockScan.MINING_AREA_RADIUS);
        assertFalse(area.contains(70, 8));

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DIAMONDS, area, NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(70, -40, 8)), goal.blocks());
        assertTrue(goal.leavesArea());
        assertFalse(goal.onBotsLevel());
    }

    @Test
    void anOreAboveItIsAsGoodAsOneBelowIt() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.set(8, -35, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        world.set(8, -48, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(8.5, -40, 8.5);
        BlockScan.MiningArea area = new BlockScan.MiningArea(8, 8, BlockScan.MINING_AREA_RADIUS);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DIAMONDS, area, NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(8, -35, 8), new BlockPos(8, -48, 8)), goal.blocks());
    }

    @Test
    void reallyGetsToAnOreThroughSolidRock() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.set(30, -35, 20, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        world.set(-20, -42, -14, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        BlockPos start = new BlockPos(8, -40, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DIAMONDS);

        for (int found = 1; found <= 2; found++) {
            BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);
            assertNotNull(goal, "should still have one to go for, at " + bot.at);
            assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);
            assertEquals(found, bot.wantedBroken);
        }

        assertNull(goalAnywhere(DIAMONDS), "that was all of them");
        assertEquals(0, bot.bedrockBroken);
    }

    @Test
    void getsToAFarOffOreThroughSolidRockWithoutHavingToSearchAndSearch() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.set(70, -35, 70, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        BlockPos start = new BlockPos(-50, -40, -50);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DIAMONDS);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);
        assertNotNull(goal);
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);

        assertEquals(1, bot.wantedBroken);
        assertTrue(bot.searches <= 5, "took " + bot.searches + " searches");
    }

    // ----------------------------------------------------------------------------------------
    // looking for more when none are loaded
    // ----------------------------------------------------------------------------------------

    @Test
    void exploresOffInTheDirectionItsToldAtAnyHeight() {
        TestWorld.putBotAt(8.5, 40, 8.5);

        assertTrue(BlockScan.exploringGoal(0).isInGoal(40, 40, 8));
        assertTrue(BlockScan.exploringGoal(0).isInGoal(40, 60, 8));
        assertTrue(BlockScan.exploringGoal(1).isInGoal(8, 40, 40));
        assertTrue(BlockScan.exploringGoal(2).isInGoal(-24, 40, 8));
        assertTrue(BlockScan.exploringGoal(3).isInGoal(8, 40, -24));
        assertTrue(BlockScan.exploringGoal(4).isInGoal(40, 40, 8), "and round to the first direction again");
    }

    @Test
    void exploringStaysAboveTheLowestLevel() {
        TestWorld.putBotAt(8.5, 40, 8.5);

        assertFalse(BlockScan.exploringGoal(0).isInGoal(40, LOWEST_LEVEL - 1, 8));
        assertTrue(BlockScan.exploringGoal(0).isInGoal(40, LOWEST_LEVEL, 8));
    }

    @Test
    void reallyTunnelsAwayWhenExploring() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, LOWEST_LEVEL + 2, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DIAMONDS);

        assertTrue(bot.followPathTo(BlockScan.exploringGoal(1)), "stuck at " + bot.at);

        assertEquals(40, bot.at.z());
        assertTrue(bot.lowestY >= LOWEST_LEVEL, "went down to " + bot.lowestY);
    }

    // ----------------------------------------------------------------------------------------
    // mining a block that's everywhere, level by level
    // ----------------------------------------------------------------------------------------

    /** Goes after a block the way the bot does. Fails the test if it's ever stuck, or leaves {@code level}. */
    private static void mineOnLevel(PretendBot bot, int level, int steps) {
        BlockScan.MiningArea area = new BlockScan.MiningArea(bot.at.x(), bot.at.z(), BlockScan.MINING_AREA_RADIUS);
        for (int step = 0; step < steps; step++) {
            BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
            assertNotNull(goal, "nothing to mine at " + bot.at);
            assertTrue(goal.onBotsLevel(), "looked for another level at " + bot.at);
            assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at + " on step " + step);
            assertEquals(level, bot.at.y(), "left its level on step " + step);
        }
    }

    @Test
    void staysOnItsLevelAmongBlocksAllAroundIt() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        mineOnLevel(bot, -30, 500);

        assertEquals(0, bot.bedrockBroken);
        assertEquals(bot.blocksBroken, bot.wantedBroken, "everything around it was what it wanted");
        // Two for each step, apart from the odd block left by itself.
        assertTrue(bot.blocksBroken >= 800, "broke " + bot.blocksBroken);
    }

    @Test
    void leavesTheFloorItStandsOnAlone() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        mineOnLevel(bot, -30, 200);

        for (int x = world.minX(); x <= world.maxX(); x++) {
            for (int z = world.minX(); z <= world.maxX(); z++) {
                assertTrue(DEEPSLATE.has(World.getBlock(new BlockPos(x, -31, z))), "dug into the floor at " + x + " " + z);
            }
        }
    }

    @Test
    void canMineOnTheLowestLevel() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, LOWEST_LEVEL, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        mineOnLevel(bot, LOWEST_LEVEL, 100);

        assertEquals(0, bot.bedrockBroken);
        assertEquals(LOWEST_LEVEL, bot.lowestY);
    }

    @Test
    void climbsUpToTheLowestLevelFromBelowIt() {
        deepslateDownToBedrock(2);
        BlockPos start = new BlockPos(8, -58, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
        assertNotNull(goal);
        assertFalse(goal.onBotsLevel(), "too low to mine here");
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);
        assertEquals(LOWEST_LEVEL, bot.at.y());

        mineOnLevel(bot, LOWEST_LEVEL, 100);
        assertEquals(0, bot.bedrockBroken);
    }

    @Test
    void keepsToItsArea() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        BlockScan.MiningArea area = new BlockScan.MiningArea(8, 8, BlockScan.MINING_AREA_RADIUS);

        for (int step = 0; step < 1000; step++) {
            BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
            assertNotNull(goal);
            assertTrue(goal.onBotsLevel() && !goal.leavesArea(), "gave up on its level at " + bot.at + ", step " + step);
            assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at + " on step " + step);
            assertEquals(-30, bot.at.y());
            assertTrue(area.contains(bot.at.x(), bot.at.z()), "left its area at " + bot.at + " on step " + step);
        }
        assertEquals(0, bot.bedrockBroken);
        assertEquals(bot.blocksBroken, bot.wantedBroken);
    }

    @Test
    void anAreaIsSmallEnoughForOneEnderChestToReachAllOfIt() {
        int reachOfAnEnderChest = 48;
        assertTrue(BlockScan.MINING_AREA_RADIUS * 2 <= reachOfAnEnderChest);
    }

    @Test
    void usesUpItsAreaLevelByLevelThenMovesOn() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -47, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        BlockScan.MiningArea area = new BlockScan.MiningArea(8, 8, 3);

        int lowest = start.y();
        boolean movedOn = false;
        int steps = 0;
        Set<Integer> levels = new TreeSet<>();
        for (; steps < 400 && !movedOn; steps++) {
            BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
            assertNotNull(goal);
            movedOn = goal.leavesArea();
            if (movedOn) break;
            assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at + " on step " + steps);
            assertTrue(area.contains(bot.at.x(), bot.at.z()), "left its area early at " + bot.at);
            lowest = Math.min(lowest, bot.at.y());
            levels.add(bot.at.y());
        }

        assertTrue(movedOn, "still hasn't moved on, at " + bot.at);
        assertEquals(LOWEST_LEVEL, lowest, "went as far down as it's allowed, and no further");
        assertEquals(0, bot.bedrockBroken);
        assertEquals(Set.of(-50, -49, -47), levels, "two at a time, down to the lowest it can go");
        assertTrue(bot.blocksBroken >= steps * 1.5, bot.blocksBroken + " blocks in " + steps + " steps");
        BlockScan.MiningGoal next = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
        assertNotNull(next);
        assertTrue(bot.followPathTo(next.goal()), "couldn't move on from " + bot.at);
        assertFalse(area.contains(bot.at.x(), bot.at.z()), "moved on, but only to " + bot.at);
        assertTrue(bot.lowestY >= LOWEST_LEVEL, "went down to " + bot.lowestY);
    }

    @Test
    void goesDownTwoLevelsSoThatItsHeadIsInBlocksToMine() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        // Nothing left on this level in its area.
        BlockScan.MiningArea area = new BlockScan.MiningArea(8, 8, 2);
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                airAt(new BlockPos(x, -30, z));
            }
        }
        PretendBot bot = new PretendBot(world, new BlockPos(8, -30, 8), DEEPSLATE);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
        assertNotNull(goal);
        assertFalse(goal.leavesArea());
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);

        assertEquals(new BlockPos(8, -32, 8), bot.at);
    }

    @Test
    void movesOnToTheNextLevelWhenWhatsLeftOnThisOneIsOutOfReach() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        TestWorld.putBotAt(8.5, -30, 8.5);
        BlockScan.MiningArea area = new BlockScan.MiningArea(8, 8, 1);
        Set<BlockPos> outOfReach = new HashSet<>();
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                outOfReach.add(new BlockPos(x, -30, z));
                outOfReach.add(new BlockPos(x, -29, z));
            }
        }

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, outOfReach);

        assertNotNull(goal);
        assertFalse(goal.onBotsLevel());
        assertFalse(goal.leavesArea(), "there's still the next level down in its area");
        assertTrue(goal.goal().isInGoal(8, -32, 8));
    }

    @Test
    void tellsWhereAnAreaEndsAndWhenTheBotIsNowhereNearIt() {
        BlockScan.MiningArea area = new BlockScan.MiningArea(100, -100, 24);

        assertTrue(area.contains(100, -76));
        assertFalse(area.contains(100, -75));
        assertFalse(area.isFarFrom(100, -52));
        assertTrue(area.isFarFrom(100, -51));
    }

    @Test
    void comesDownFromTheSurfaceToWhereItsMostlyWhatItWants() {
        realisticWorld();
        BlockPos start = new BlockPos(8, 61, 8);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        boolean onLevel = false;
        for (int step = 0; step < 100 && !onLevel; step++) {
            BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
            assertNotNull(goal);
            onLevel = goal.onBotsLevel();
            if (!onLevel) assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);
        }
        assertTrue(onLevel, "never settled on a level, got to " + bot.at);
        assertTrue(bot.at.y() >= -2 && bot.at.y() <= 8, "settled on a level at " + bot.at);

        int settledAt = bot.at.y();
        int wantedBefore = bot.wantedBroken;
        int brokenBefore = bot.blocksBroken;
        for (int step = 0; step < 200; step++) {
            BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
            assertNotNull(goal);
            assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);
        }
        assertTrue(bot.at.y() >= settledAt - 8, "drifted from " + settledAt + " to " + bot.at);
        assertTrue(bot.at.y() >= -2, "dug down to " + bot.at);
        int wanted = bot.wantedBroken - wantedBefore;
        int broken = bot.blocksBroken - brokenBefore;
        assertTrue(wanted * 2 >= broken, "only " + wanted + " of the " + broken + " it broke were what it wanted");
    }

    @Test
    void walksAcrossAnOpenFloorToBlocksUnderIt() {
        world.layer(60, 60, BlockRegistry.STONE);
        for (int y = 40; y <= 59; y++) {
            for (int x = 40; x <= 44; x++) {
                for (int z = 40; z <= 44; z++) {
                    world.set(x, y, z, BlockRegistry.COBBLED_DEEPSLATE);
                }
            }
        }
        PretendBot bot = new PretendBot(world, new BlockPos(0, 61, 0), DEEPSLATE);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
        assertNotNull(goal);
        assertFalse(goal.onBotsLevel());
        assertTrue(bot.followPathTo(goal.goal()), "only got to " + bot.at);
    }

    // ----------------------------------------------------------------------------------------
    // getting out of a pit
    // ----------------------------------------------------------------------------------------

    /** A shaft ending in bedrock on every side, too high to step out of, like the one that got reported. */
    private BlockPos bedrockPit() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                for (int y = -64; y <= -60; y++) {
                    world.set(x, y, z, BlockRegistry.BEDROCK);
                }
            }
        }
        for (int y = -63; y <= -30; y++) {
            world.set(8, y, 8, BlockRegistry.AIR);
        }
        return new BlockPos(8, -63, 8);
    }

    @Test
    void cantGetOutOfAPitWithoutBlocksToBuildWith() {
        PretendBot bot = new PretendBot(world, bedrockPit(), DEEPSLATE);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
        assertNotNull(goal);
        assertFalse(bot.followPathTo(goal.goal()));
        assertEquals(new BlockPos(8, -63, 8), bot.at);
    }

    @Test
    void buildsItsWayOutOfAPitAndGoesBackToMiningOnASafeLevel() {
        PretendBot bot = new PretendBot(world, bedrockPit(), DEEPSLATE);
        var inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        inventory.setItemStack(9, new ItemStack(ItemRegistry.COBBLED_DEEPSLATE.id(), 64));
        Set<String> list = CONFIG.client.extra.pathfinder.acceptableThrowawayItems;
        list.add("cobbled_deepslate");
        try {
            assertTrue(bot.followPathTo(new GoalY(-57)), "still stuck at " + bot.at);
        } finally {
            list.remove("cobbled_deepslate");
            inventory.setItemStack(9, Container.EMPTY_STACK);
        }
        assertEquals(-57, bot.at.y());

        // With nothing to build with any more, it has to dig its way up from here.
        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);
        assertNotNull(goal);
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);
        assertEquals(LOWEST_LEVEL, bot.at.y());

        mineOnLevel(bot, LOWEST_LEVEL, 100);
        assertEquals(0, bot.bedrockBroken);
    }
}
