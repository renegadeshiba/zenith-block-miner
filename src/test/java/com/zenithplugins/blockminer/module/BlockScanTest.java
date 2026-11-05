package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalAvoidBlocks;
import com.zenith.feature.pathfinder.goals.GoalComposite;
import com.zenith.feature.pathfinder.goals.GoalTwoBlocks;
import com.zenith.feature.pathfinder.goals.GoalY;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.process.InteractWithProcess;
import com.zenith.feature.pathfinder.process.MineProcess;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
    private static final BlockOptionalMetaLookup STONE = new BlockOptionalMetaLookup(BlockRegistry.STONE);
    private static final Set<BlockPos> NOTHING_OUT_OF_REACH = Set.of();
    private static final int LOWEST_LEVEL = -58;
    private static final int RADIUS = BlockScan.MINING_AREA_RADIUS;

    private TestWorld world;
    private BlockScan.MiningArea area;

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

    private static BlockScan.MiningArea sectionAroundBot() {
        return new BlockScan.MiningArea(
            MathHelper.floorI(CACHE.getPlayerCache().getX()), MathHelper.floorI(CACHE.getPlayerCache().getZ()), RADIUS);
    }

    /** What it would do next, with nothing done yet in the section around it. */
    private static BlockScan.MiningGoal goalAnywhere(BlockOptionalMetaLookup filter) {
        return BlockScan.miningGoal(filter, sectionAroundBot(), NOTHING_OUT_OF_REACH);
    }

    private static Integer topOfSection(BlockOptionalMetaLookup filter) {
        return BlockScan.highestLayerWorthMining(filter, sectionAroundBot(), Integer.MAX_VALUE);
    }

    /** Does what the bot would do next, and carries on from there with the section it's left with, as the module does. */
    private BlockScan.MiningGoal mineNext(PretendBot bot, BlockOptionalMetaLookup filter) {
        BlockScan.MiningGoal goal = BlockScan.miningGoal(filter, area, NOTHING_OUT_OF_REACH);
        assertNotNull(goal, "nothing to mine at " + bot.at);
        area = goal.area();
        assertTrue(bot.carryOut(goal), "stuck at " + bot.at + " going for " + goal.blocks());
        return goal;
    }

    private BlockScan.MiningGoal mineNext(PretendBot bot) {
        return mineNext(bot, DEEPSLATE);
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
    // what counts as the top of a section, and tells a block that's everywhere from one worth looking for
    // ----------------------------------------------------------------------------------------

    @Test
    void tellsABlockThatsEverywhereFromAnOre() {
        realisticWorld();
        TestWorld.putBotAt(8.5, -30, 8.5);

        assertNotNull(topOfSection(DEEPSLATE));
        assertNull(topOfSection(DIAMONDS));
    }

    @Test
    void theTopIsTheHighestLayerThatsMostlyWhatItWants() {
        realisticWorld();
        TestWorld.putBotAt(8.5, -30, 8.5);

        // Deepslate thins out going up from 0 to 8, and it's under half of a layer from 5 up.
        assertEquals(4, topOfSection(DEEPSLATE));
    }

    @Test
    void theTopIsTheSameWhereverTheBotIs() {
        realisticWorld();

        TestWorld.putBotAt(8.5, 70, 8.5);
        assertEquals(4, topOfSection(DEEPSLATE));
        TestWorld.putBotAt(8.5, LOWEST_LEVEL, 8.5);
        assertEquals(4, topOfSection(DEEPSLATE));
    }

    /** About 3.5% of a section. */
    private void patchOfDeepslateAt(int y) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                world.set(x, y, z, BlockRegistry.DEEPSLATE);
            }
        }
    }

    @Test
    void airDoesntCountAgainstALayer() {
        // Like the top of a hill: not much of the layer, but all that's solid in it.
        patchOfDeepslateAt(30);
        TestWorld.putBotAt(8.5, 40, 8.5);

        assertEquals(30, topOfSection(DEEPSLATE));
    }

    @Test
    void anythingElseSolidCountsAgainstALayer() {
        world.layer(30, 30, BlockRegistry.STONE);
        patchOfDeepslateAt(30);
        TestWorld.putBotAt(8.5, 40, 8.5);

        assertNull(topOfSection(DEEPSLATE));
    }

    @Test
    void aFewOddBlocksDontCountAsTheTop() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        // Like part of someone's build.
        for (int x = 0; x < 5; x++) world.set(x, 40, 0, BlockRegistry.COBBLED_DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        assertEquals(-1, topOfSection(DEEPSLATE));
    }

    @Test
    void onlyWhatsInTheSectionCounts() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        patchOfDeepslateAt(30);
        TestWorld.putBotAt(50.5, -30, 8.5);
        assertEquals(30, BlockScan.highestLayerWorthMining(DEEPSLATE, new BlockScan.MiningArea(8, 8, RADIUS), Integer.MAX_VALUE));

        assertEquals(-1, topOfSection(DEEPSLATE));
    }

    @Test
    void theCornersOfASquareAroundTheSectionArentInIt() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        for (int x = 27; x <= 32; x++) {
            for (int z = 27; z <= 32; z++) {
                world.set(x, 30, z, BlockRegistry.DEEPSLATE);
            }
        }
        TestWorld.putBotAt(8.5, -30, 8.5);

        assertEquals(-1, topOfSection(DEEPSLATE));
    }

    @Test
    void theTopCanBeAtTheVeryTopOfTheWorld() {
        patchOfDeepslateAt(319);
        TestWorld.putBotAt(8.5, -40, 8.5);

        assertEquals(319, topOfSection(DEEPSLATE));
    }

    @Test
    void theresNoLimitOnHowHighUpItStartsASection() {
        for (int y = 285; y <= 300; y++) patchOfDeepslateAt(y);
        TestWorld.putBotAt(8.5, -40, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);

        assertNotNull(goal);
        assertEquals(294, goal.area().level());
    }

    @Test
    void layersBelowTheLowestLevelDontCount() {
        world.layer(-64, LOWEST_LEVEL - 1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, -45, 8.5);
        assertNull(topOfSection(DEEPSLATE));

        world.layer(LOWEST_LEVEL, LOWEST_LEVEL, BlockRegistry.DEEPSLATE);
        assertEquals(LOWEST_LEVEL, topOfSection(DEEPSLATE));
    }

    @Test
    void looksNoHigherThanItsTold() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        assertEquals(-10, BlockScan.highestLayerWorthMining(DEEPSLATE, sectionAroundBot(), -10));
    }

    @Test
    void findsTheTopWithChunksMissingAroundTheEdgeOfTheWorldItsLoaded() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(70.5, -30, 70.5);

        assertEquals(-1, topOfSection(DEEPSLATE));
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

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DIAMONDS, sectionAroundBot(), Set.of(new BlockPos(3, 20, 0)));

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

        BlockScan.MiningArea section = sectionAroundBot();

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, section, NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertEquals(new GoalAvoidBlocks(8, -30, 8, BlockRegistry.LAVA), goal.goal());
        assertFalse(goal.onBotsLevel());
        assertEquals(section, goal.area(), "it gets back to what it was doing afterwards");
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
        assertNull(goal.area(), "it starts a new section from wherever it ends up");
        assertFalse(goal.onBotsLevel());
    }

    @Test
    void theresNoLimitOnHowHighUpItGoesForAnOre() {
        world.set(8, 300, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(8.5, -40, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(8, 300, 8)), goal.blocks());
        assertTrue(goal.goal().isInGoal(8, 300, 8));
    }

    @Test
    void reallyClimbsAllTheWayUpToAnOreThroughSolidRock() {
        world.layer(-64, 90, BlockRegistry.DEEPSLATE);
        world.set(30, 80, 20, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        BlockPos start = new BlockPos(8, -40, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DIAMONDS);

        BlockScan.MiningGoal goal = goalAnywhere(DIAMONDS);
        assertNotNull(goal);
        assertTrue(bot.followPathTo(goal.goal()), "stuck at " + bot.at);

        assertEquals(1, bot.wantedBroken);
        assertTrue(bot.at.y() >= 79, "got to " + bot.at);
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
        // Five normally, and six with the machine flat out. Without pressing on through rock it never gets there.
        assertTrue(bot.searches <= 12, "took " + bot.searches + " searches");
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
    // starting a section from the top
    // ----------------------------------------------------------------------------------------

    @Test
    void goesUpToTheTopFirstIfItStartsBelowIt() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = sectionAroundBot();

        BlockScan.MiningGoal goal = mineNext(bot);

        assertFalse(goal.onBotsLevel());
        assertEquals(-7, bot.at.y(), "low enough to reach the top layer, at -1, from its floor");
        assertEquals(-7, area.level());
        assertEquals(0, bot.bedrockBroken);
    }

    @Test
    void comesDownToTheTopIfItStartsAboveIt() {
        realisticWorld();
        PretendBot bot = new PretendBot(world, new BlockPos(8, 61, 8), DEEPSLATE);
        area = sectionAroundBot();

        BlockScan.MiningGoal goal = mineNext(bot);

        assertFalse(goal.onBotsLevel());
        assertEquals(-2, bot.at.y(), "low enough to reach the top layer, at 4, from its floor");
    }

    @Test
    void getsOnWithTheTopLevelOnceItsThere() {
        realisticWorld();
        PretendBot bot = new PretendBot(world, new BlockPos(8, 61, 8), DEEPSLATE);
        area = sectionAroundBot();
        mineNext(bot);
        int wantedBefore = bot.wantedBroken;
        int brokenBefore = bot.blocksBroken;

        for (int step = 0; step < 300; step++) {
            BlockScan.MiningGoal goal = mineNext(bot);
            assertTrue(goal.onBotsLevel(), "gave up on its level at " + bot.at);
            assertEquals(-2, bot.at.y());
        }

        int wanted = bot.wantedBroken - wantedBefore;
        int broken = bot.blocksBroken - brokenBefore;
        assertTrue(wanted * 2 >= broken, "only " + wanted + " of the " + broken + " it broke were what it wanted");
    }

    @Test
    void standsHigherIfWhatsBelowTheTopIsMostlySomethingElse() {
        // Stone giving way to deepslate, and it's after the stone, so it shouldn't dig about in the deepslate.
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.layer(9, 9, BlockRegistry.STONE);
        world.layer(10, 10, BlockRegistry.STONE);
        TestWorld.putBotAt(8.5, 20, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(STONE);

        assertNotNull(goal);
        assertEquals(9, goal.area().level());
    }

    @Test
    void standsAsHighAsItHasToIfTheTopIsAllTheresLeft() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        world.layer(9, 9, BlockRegistry.STONE);
        TestWorld.putBotAt(8.5, 20, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(STONE);

        assertNotNull(goal);
        assertEquals(8, goal.area().level(), "its feet in the deepslate, and its head in the stone");
    }

    @Test
    void doesntGoBelowTheLowestLevelToReachTheTop() {
        world.layer(-64, LOWEST_LEVEL + 2, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, LOWEST_LEVEL + 3, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);

        assertNotNull(goal);
        assertEquals(LOWEST_LEVEL, goal.area().level());
    }

    @Test
    void standsOnTheLowestLevelIfThatsTheOnlyLayerLeft() {
        world.layer(-64, LOWEST_LEVEL, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, LOWEST_LEVEL + 3, 8.5);

        BlockScan.MiningGoal goal = goalAnywhere(DEEPSLATE);

        assertNotNull(goal);
        assertEquals(LOWEST_LEVEL, goal.area().level());
        assertFalse(goal.goal().isInGoal(9, LOWEST_LEVEL - 1, 8));
    }

    @Test
    void climbsUpToTheLowestLevelFromBelowIt() {
        deepslateDownToBedrock(2);
        world.layer(LOWEST_LEVEL + 2, 8, BlockRegistry.AIR);
        BlockPos start = new BlockPos(8, LOWEST_LEVEL - 2, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = sectionAroundBot();

        BlockScan.MiningGoal goal = mineNext(bot);

        assertFalse(goal.onBotsLevel(), "too low to mine here");
        assertEquals(LOWEST_LEVEL, bot.at.y());
        assertEquals(0, bot.bedrockBroken);
    }

    // ----------------------------------------------------------------------------------------
    // what's above it
    // ----------------------------------------------------------------------------------------

    private static final int ON_LEVEL = -30;
    private static final BlockPos ON_THE_LEVEL = new BlockPos(8, ON_LEVEL, 8);

    /** In solid rock, with room to stand, and already getting on with this level. */
    private PretendBot botOnALevel() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        airAt(ON_THE_LEVEL);
        area = new BlockScan.MiningArea(8, 8, RADIUS).onLevel(ON_LEVEL);
        return new PretendBot(world, ON_THE_LEVEL, DEEPSLATE);
    }

    private void setAbove(int height, com.zenith.mc.block.Block block) {
        world.set(8, ON_LEVEL + height, 8, block);
    }

    private static BlockPos above(int height) {
        return new BlockPos(8, ON_LEVEL + height, 8);
    }

    private BlockScan.MiningGoal goalNow() {
        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
        assertNotNull(goal);
        return goal;
    }

    /** Mines without moving for as long as it wants to, and then says what it mined. */
    private List<BlockPos> minedOverhead(PretendBot bot) {
        List<BlockPos> mined = new ArrayList<>();
        BlockScan.MiningGoal goal = goalNow();
        while (goal.goal() == null) {
            assertTrue(goal.onBotsLevel());
            assertTrue(bot.carryOut(goal), "couldn't reach " + goal.blocks());
            mined.addAll(goal.blocks());
            goal = goalNow();
        }
        return mined;
    }

    @Test
    void minesAllItCanReachAboveItBeforeMovingOn() {
        PretendBot bot = botOnALevel();

        assertEquals(List.of(above(2), above(3), above(4), above(5), above(6)), minedOverhead(bot));

        assertEquals(ON_THE_LEVEL, bot.at, "without moving");
        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(above(7)), "as that's too high up");
        assertEquals(5, bot.wantedBroken);
    }

    @Test
    void walksOnToTheNextSpotAfterThat() {
        PretendBot bot = botOnALevel();
        minedOverhead(bot);

        mineNext(bot);

        assertEquals(ON_LEVEL, bot.at.y());
        assertEquals(1, Math.abs(bot.at.x() - 8) + Math.abs(bot.at.z() - 8), "the next spot along, at " + bot.at);
        assertEquals(List.of(new BlockPos(bot.at.x(), ON_LEVEL + 2, bot.at.z())), goalNow().blocks(), "and does the same again");
    }

    @Test
    void theRoomIsAsHighAsItCanReachFromAnywhereInTheSpot() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        for (int height = 0; height <= 5; height++) setAbove(height, BlockRegistry.AIR);

        // A walk ends as soon as its feet cross into a spot, so it can be near the edge of it.
        double[][] standingAt = {{0.5, 0.5}, {0.02, 0.5}, {0.98, 0.5}, {0.5, 0.02}, {0.5, 0.98}, {0.02, 0.02}, {0.98, 0.98}};
        for (double[] spot : standingAt) {
            TestWorld.putBotAt(8 + spot[0], ON_LEVEL, 8 + spot[1]);
            assertTrue(new InteractWithProcess.BreakBlock(8, ON_LEVEL + 6, 8, true).canInteract(), "from " + spot[0] + " " + spot[1]);
        }
        setAbove(6, BlockRegistry.AIR);
        TestWorld.putBotAt(8.5, ON_LEVEL, 8.5);
        assertFalse(new InteractWithProcess.BreakBlock(8, ON_LEVEL + 7, 8, true).canInteract());
    }

    @Test
    void reachesPastAirAboveIt() {
        PretendBot bot = botOnALevel();
        setAbove(2, BlockRegistry.AIR);
        setAbove(3, BlockRegistry.AIR);

        assertEquals(List.of(above(4), above(5), above(6)), minedOverhead(bot));
    }

    @Test
    void goesThroughWhatItDoesntWantToGetToWhatItDoes() {
        PretendBot bot = botOnALevel();
        setAbove(3, BlockRegistry.TUFF);

        assertEquals(List.of(above(2), above(3), above(4), above(5), above(6)), minedOverhead(bot));
    }

    @Test
    void leavesWhatItDoesntWantIfThereIsNothingItWantsAboveIt() {
        PretendBot bot = botOnALevel();
        setAbove(3, BlockRegistry.TUFF);
        setAbove(4, BlockRegistry.TUFF);
        setAbove(5, BlockRegistry.TUFF);
        setAbove(6, BlockRegistry.AIR);

        assertEquals(List.of(above(2)), minedOverhead(bot));

        assertEquals(BlockRegistry.TUFF, World.getBlock(above(3)));
    }

    @Test
    void leavesSomethingThatWouldFallOnIt() {
        PretendBot bot = botOnALevel();
        setAbove(4, BlockRegistry.GRAVEL);

        assertEquals(List.of(above(2)), minedOverhead(bot), "and what's holding it up");

        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(above(3)));
    }

    @Test
    void leavesWhatsHoldingUpSomethingThatFallsAtTheVeryTop() {
        PretendBot bot = botOnALevel();
        setAbove(7, BlockRegistry.GRAVEL);

        assertEquals(List.of(above(2), above(3), above(4), above(5)), minedOverhead(bot));

        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(above(6)));
    }

    @Test
    void leavesWhatWouldLetLavaOut() {
        PretendBot bot = botOnALevel();
        world.set(9, ON_LEVEL + 3, 8, BlockRegistry.LAVA);

        assertEquals(List.of(above(2)), minedOverhead(bot));

        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(above(3)));
    }

    @Test
    void leavesBedrock() {
        PretendBot bot = botOnALevel();
        setAbove(3, BlockRegistry.BEDROCK);

        assertEquals(List.of(above(2)), minedOverhead(bot));

        assertEquals(0, bot.bedrockBroken);
    }

    @Test
    void leavesAContainer() {
        PretendBot bot = botOnALevel();
        world.enderChestAt(8, ON_LEVEL + 3, 8);

        assertEquals(List.of(), minedOverhead(bot), "or what it's standing on");

        assertEquals(BlockRegistry.ENDER_CHEST, World.getBlock(above(3)));
    }

    @Test
    void leavesWhatItsAlreadyFailedToMine() {
        botOnALevel();

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, Set.of(above(2)));

        assertNotNull(goal);
        assertNotNull(goal.goal(), "it walks on instead");
    }

    @Test
    void leavesWhatZenithSaysItCantReach() {
        botOnALevel();
        // Zenith goes by where it thinks the bot's eyes are, so this puts it a long way out of reach of them.
        TestWorld.putBotAt(8.5, ON_LEVEL - 10, 8.5);
        CACHE.getPlayerCache().setY(ON_LEVEL);

        assertNotNull(goalNow().goal(), "it walks on instead");
    }

    @Test
    void onlyMinesOverheadOnTheLevelItsWorkingOn() {
        // On its way to its level, and in the same column as it.
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        BlockPos above = new BlockPos(8, ON_LEVEL + 10, 8);
        airAt(above);
        PretendBot bot = new PretendBot(world, above, DEEPSLATE);
        area = new BlockScan.MiningArea(8, 8, RADIUS).onLevel(ON_LEVEL);

        BlockScan.MiningGoal goal = mineNext(bot);

        assertNotNull(goal.goal());
        assertFalse(goal.onBotsLevel());
        assertEquals(ON_THE_LEVEL, bot.at);
    }

    @Test
    void wontGoForABlockWithNothingToStandOn() {
        botOnALevel();
        for (int height = 2; height <= 6; height++) setAbove(height, BlockRegistry.AIR);
        area = new BlockScan.MiningArea(8, 8, 2).onLevel(ON_LEVEL);
        // A cave under the level, all around it, apart from one spot.
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                if (x != 9 || z != 8) world.set(x, ON_LEVEL - 1, z, BlockRegistry.AIR);
            }
        }

        BlockScan.MiningGoal goal = goalNow();

        assertEquals(List.of(new BlockPos(9, ON_LEVEL, 8), new BlockPos(9, ON_LEVEL + 1, 8)), goal.blocks());
    }

    // ----------------------------------------------------------------------------------------
    // working down through a section
    // ----------------------------------------------------------------------------------------

    /** Goes after a block the way the bot does. Fails the test if it's ever stuck, or leaves {@code level}. */
    private void mineOnLevel(PretendBot bot, int level, int steps) {
        area = new BlockScan.MiningArea(bot.at.x(), bot.at.z(), RADIUS).onLevel(level);
        for (int step = 0; step < steps; step++) {
            BlockScan.MiningGoal goal = mineNext(bot);
            assertTrue(goal.onBotsLevel(), "looked for another level at " + bot.at);
            assertEquals(level, bot.at.y(), "left its level on step " + step);
        }
    }

    /** Mines until it would move on to another section, and says which levels it worked on, in order. */
    private List<Integer> mineUntilTheSectionIsFinished(PretendBot bot) {
        BlockScan.MiningArea section = area;
        List<Integer> levels = new ArrayList<>();
        for (int step = 0; step < 50_000; step++) {
            BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, NOTHING_OUT_OF_REACH);
            assertNotNull(goal, "nothing to mine at " + bot.at);
            if (goal.area() == null || goal.area().centreX() != section.centreX() || goal.area().centreZ() != section.centreZ()) {
                return levels;
            }
            area = goal.area();
            assertTrue(bot.carryOut(goal), "stuck at " + bot.at + " on step " + step);
            if (levels.isEmpty() || levels.getLast() != bot.at.y()) levels.add(bot.at.y());
            assertTrue(area.isFarFrom(bot.at.x(), bot.at.z()) == false, "wandered off to " + bot.at);
        }
        throw new AssertionError("never finished the section, got to " + bot.at);
    }

    private int leftInSection(BlockScan.MiningArea section, int lowestY, int highestY) {
        int left = 0;
        for (int x = section.centreX() - section.radius(); x <= section.centreX() + section.radius(); x++) {
            for (int z = section.centreZ() - section.radius(); z <= section.centreZ() + section.radius(); z++) {
                if (!section.contains(x, z)) continue;
                for (int y = lowestY; y <= highestY; y++) {
                    if (DEEPSLATE.has(World.getBlock(new BlockPos(x, y, z)))) left++;
                }
            }
        }
        return left;
    }

    private static final List<Integer> LEVELS_FROM_THE_TOP =
        List.of(2, -5, -12, -19, -26, -33, -40, -47, -54, LOWEST_LEVEL);
    private static final int SPOTS_IN_A_SMALL_SECTION = 29;

    @Test
    void clearsASectionFromTheTopDown() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, 2, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = new BlockScan.MiningArea(8, 8, 3);
        BlockScan.MiningArea section = area;

        List<Integer> levels = mineUntilTheSectionIsFinished(bot);

        // A room's height at a time from the top, at 8, and then what's left above the lowest level.
        assertEquals(LEVELS_FROM_THE_TOP, levels);
        assertEquals(0, bot.bedrockBroken);
        assertEquals(LOWEST_LEVEL, bot.lowestY);
        assertEquals(0, leftInSection(section, LOWEST_LEVEL, 8), "it got all of it");
        assertEquals(SPOTS_IN_A_SMALL_SECTION * (8 - LOWEST_LEVEL + 1) - 2, bot.blocksBroken,
            "and nothing outside the section, or anything else. It didn't have to break what it was standing in to start");
        assertEquals(bot.blocksBroken, bot.wantedBroken);
    }

    @Test
    void leavesLittleBehindAfterClimbingThroughTheSectionToItsTop() {
        // What's over the stairs it's dug to get to the top has nothing under it to stand on.
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = new BlockScan.MiningArea(8, 8, 3);
        BlockScan.MiningArea section = area;

        assertEquals(LEVELS_FROM_THE_TOP, mineUntilTheSectionIsFinished(bot));

        assertLeftLittleIn(section);
    }

    /**
     * In a section this small, the stairs have to zigzag through it to gain that much height, and just how much
     * they cut up depends on how far the path search gets in its time. It's nothing like this in a real one.
     */
    private void assertLeftLittleIn(BlockScan.MiningArea section) {
        int left = leftInSection(section, LOWEST_LEVEL, 8);
        assertTrue(left * 4 < SPOTS_IN_A_SMALL_SECTION * (8 - LOWEST_LEVEL + 1), "left " + left);
    }

    @Test
    void leavesTheFloorItStandsOnAlone() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        mineOnLevel(bot, -30, 500);

        for (int x = world.minX(); x <= world.maxX(); x++) {
            for (int z = world.minX(); z <= world.maxX(); z++) {
                assertTrue(DEEPSLATE.has(World.getBlock(new BlockPos(x, -31, z))), "dug into the floor at " + x + " " + z);
            }
        }
    }

    @Test
    void getsSevenBlocksFromEachSpotOnItsLevel() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);

        // Five above the first spot, and then two for walking into each next one and five above it.
        mineOnLevel(bot, -30, 5 + 6 * 100);

        assertEquals(0, bot.bedrockBroken);
        assertEquals(bot.blocksBroken, bot.wantedBroken, "everything around it was what it wanted");
        assertTrue(bot.blocksBroken >= 5 + 7 * 95, "broke " + bot.blocksBroken);
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
    void keepsToItsSection() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -30, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = new BlockScan.MiningArea(8, 8, RADIUS).onLevel(-30);

        for (int step = 0; step < 1000; step++) {
            BlockScan.MiningGoal goal = mineNext(bot);
            assertTrue(goal.onBotsLevel(), "gave up on its level at " + bot.at + ", step " + step);
            assertEquals(-30, bot.at.y());
            assertTrue(area.contains(bot.at.x(), bot.at.z()), "left its section at " + bot.at + " on step " + step);
        }
        assertEquals(0, bot.bedrockBroken);
        assertEquals(bot.blocksBroken, bot.wantedBroken);
    }

    @Test
    void aSectionIsSmallEnoughForOneEnderChestToReachAllOfALevel() {
        int reachOfAnEnderChest = 48;
        assertTrue(BlockScan.MINING_AREA_RADIUS * 2 <= reachOfAnEnderChest);
    }

    private void clearLevelAroundTheBot() {
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                for (int y = ON_LEVEL; y <= ON_LEVEL + 6; y++) {
                    world.set(x, y, z, BlockRegistry.AIR);
                }
            }
        }
    }

    @Test
    void goesDownARoomsHeightOnceItsLevelIsUsedUp() {
        PretendBot bot = botOnALevel();
        clearLevelAroundTheBot();
        area = new BlockScan.MiningArea(8, 8, 2).onLevel(ON_LEVEL);

        BlockScan.MiningGoal goal = mineNext(bot);

        assertFalse(goal.onBotsLevel());
        assertEquals(new BlockPos(8, ON_LEVEL - 7, 8), bot.at, "straight down");
        assertEquals(ON_LEVEL - 7, area.level());
        assertEquals(7, bot.blocksBroken, "it hasn't got to go anywhere else to get there");
    }

    @Test
    void getsThroughWhatsOutOfReachOnItsLevelByGoingDown() {
        botOnALevel();
        for (int height = 2; height <= 6; height++) setAbove(height, BlockRegistry.AIR);
        area = new BlockScan.MiningArea(8, 8, 1).onLevel(ON_LEVEL);
        Set<BlockPos> outOfReach = new HashSet<>();
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                outOfReach.add(new BlockPos(x, ON_LEVEL, z));
                outOfReach.add(new BlockPos(x, ON_LEVEL + 1, z));
            }
        }

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, area, outOfReach);

        assertNotNull(goal);
        assertFalse(goal.onBotsLevel());
        assertEquals(ON_LEVEL - 7, goal.area().level());
        assertEquals(8, goal.area().centreX(), "there's still more to do in this section");
        assertTrue(goal.goal().isInGoal(8, ON_LEVEL - 7, 8));
    }

    @Test
    void staysOnItsLevelUntilThatsDoneWhateverHappensAboveIt() {
        PretendBot bot = botOnALevel();
        // Everything above its head is gone, so the top of the section isn't where it was when it got here.
        world.layer(ON_LEVEL + 2, 8, BlockRegistry.AIR);

        for (int step = 0; step < 50; step++) {
            BlockScan.MiningGoal goal = mineNext(bot);
            assertTrue(goal.onBotsLevel());
            assertEquals(ON_LEVEL, area.level());
            assertEquals(ON_LEVEL, bot.at.y());
        }
    }

    @Test
    void getsBackToItsLevelIfItEndsUpSomewhereElse() {
        // Like walking back from an ender chest and not quite getting there.
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        BlockPos start = new BlockPos(8, ON_LEVEL + 1, 8);
        airAt(start);
        airAt(ON_THE_LEVEL);
        TestWorld.putBotAt(8.5, ON_LEVEL + 1, 8.5);
        area = new BlockScan.MiningArea(8, 8, RADIUS).onLevel(ON_LEVEL);

        BlockScan.MiningGoal goal = goalNow();

        assertFalse(goal.onBotsLevel());
        assertEquals(ON_LEVEL, goal.area().level());
        assertNotNull(goal.goal());
        assertFalse(goal.goal().isInGoal(8, ON_LEVEL + 1, 8));
    }

    @Test
    void doesntGoBackUpForWhatItLeftBehind() {
        PretendBot bot = botOnALevel();
        clearLevelAroundTheBot();
        area = new BlockScan.MiningArea(8, 8, 2).onLevel(ON_LEVEL);

        mineNext(bot);

        assertEquals(ON_LEVEL - 7, area.level());
        mineNext(bot);
        assertEquals(ON_LEVEL - 7, area.level(), "there's plenty above that it never reached, but it's not going back for it");
    }

    // ----------------------------------------------------------------------------------------
    // moving on to the next section
    // ----------------------------------------------------------------------------------------

    @Test
    void movesOnToTheTopOfTheNextSectionOnceItsFinished() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -50, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = new BlockScan.MiningArea(8, 8, 3);
        mineUntilTheSectionIsFinished(bot);
        assertEquals(LOWEST_LEVEL, bot.at.y());

        BlockScan.MiningGoal goal = mineNext(bot);

        assertFalse(goal.onBotsLevel());
        assertEquals(14, area.centreX(), "the section touching the one it was on");
        assertEquals(8, area.centreZ());
        assertEquals(2, area.level(), "as high up as it can be and still reach the top, at 8, from its floor");
        assertEquals(2, bot.at.y(), "it climbed all the way back up");
        assertTrue(area.contains(bot.at.x(), bot.at.z()), "got to " + bot.at);
        assertEquals(0, bot.bedrockBroken);
    }

    @Test
    void carriesOnWithTheNextSectionTheSameWay() {
        deepslateDownToBedrock(1);
        BlockPos start = new BlockPos(8, -50, 8);
        airAt(start);
        PretendBot bot = new PretendBot(world, start, DEEPSLATE);
        area = new BlockScan.MiningArea(8, 8, 3);
        mineUntilTheSectionIsFinished(bot);
        mineNext(bot);
        BlockScan.MiningArea second = area;

        assertEquals(LEVELS_FROM_THE_TOP, mineUntilTheSectionIsFinished(bot));

        assertLeftLittleIn(second);
        mineNext(bot);
        assertEquals(20, area.centreX(), "and on again in a line");
        assertEquals(8, area.centreZ());
    }

    private void clearAround(int fromX, int toX, int fromZ, int toZ) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = LOWEST_LEVEL; y <= 8; y++) {
                    world.set(x, y, z, BlockRegistry.AIR);
                }
            }
        }
    }

    @Test
    void turnsIfTheNextSectionIsAlreadyMinedOut() {
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        // The one it's finished, and the one past it.
        clearAround(5, 17, 5, 11);
        TestWorld.putBotAt(8.5, LOWEST_LEVEL, 8.5);
        BlockScan.MiningArea section = new BlockScan.MiningArea(8, 8, 3).onLevel(LOWEST_LEVEL);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, section, NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertEquals(8, goal.area().centreX());
        assertEquals(14, goal.area().centreZ(), "it turned");
        assertEquals(20, goal.area().next(0).centreZ(), "and it keeps going that way from now on");
    }

    @Test
    void goesForTheNearestThereIsWhenThereAreNoSectionsLeftAroundIt() {
        TestWorld.putBotAt(8.5, LOWEST_LEVEL, 8.5);
        world.set(30, -40, 8, BlockRegistry.DEEPSLATE);
        BlockScan.MiningArea section = new BlockScan.MiningArea(8, 8, 3).onLevel(LOWEST_LEVEL);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, section, NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(30, -40, 8)), goal.blocks());
        assertNull(goal.area(), "it starts a new section from wherever it ends up");
    }

    @Test
    void goesForTheNearestRatherThanASectionNextToOneItsOnlyJustCometo() {
        // Plenty in the one next to it, but none in the one it's in.
        for (int x = 12; x <= 40; x++) {
            for (int z = 0; z <= 16; z++) {
                for (int y = 20; y <= 30; y++) {
                    world.set(x, y, z, BlockRegistry.DEEPSLATE);
                }
            }
        }
        TestWorld.putBotAt(8.5, 25, 8.5);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DEEPSLATE, new BlockScan.MiningArea(8, 8, 3), NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertNull(goal.area(), "it starts a new section from wherever it ends up");
        assertTrue(goal.goal().isInGoal(12, 25, 8));
    }

    @Test
    void doesntLookAtOtherSectionsForAnOre() {
        // Enough for a layer, if it was going to look at sections for this.
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        world.set(30, -40, 8, BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        BlockScan.MiningGoal goal = BlockScan.miningGoal(DIAMONDS, new BlockScan.MiningArea(8, 8, 3), NOTHING_OUT_OF_REACH);

        assertNotNull(goal);
        assertEquals(List.of(new BlockPos(30, -40, 8)), goal.blocks());
        assertNull(goal.area());
    }

    @Test
    void sectionsTouchAndKeepGoingTheSameWayUntilTheyTurn() {
        BlockScan.MiningArea section = new BlockScan.MiningArea(8, 8, RADIUS).onLevel(-30);

        BlockScan.MiningArea next = section.next(0);
        assertEquals(8 + 2 * RADIUS, next.centreX());
        assertEquals(8, next.centreZ());
        assertFalse(next.hasStarted());
        assertEquals(8 + 4 * RADIUS, next.next(0).centreX());

        BlockScan.MiningArea turned = section.next(1);
        assertEquals(8, turned.centreX());
        assertEquals(8 + 2 * RADIUS, turned.centreZ());
        assertEquals(8 + 4 * RADIUS, turned.next(0).centreZ());
    }

    @Test
    void tellsWhereASectionEndsAndWhenTheBotIsNowhereNearIt() {
        BlockScan.MiningArea section = new BlockScan.MiningArea(100, -100, 24);

        assertTrue(section.contains(100, -76));
        assertFalse(section.contains(100, -75));
        assertFalse(section.isFarFrom(100, -4));
        assertTrue(section.isFarFrom(100, -3));
    }

    @Test
    void theBotIsntTooFarFromTheNextSectionWhenItSetsOff() {
        BlockScan.MiningArea section = new BlockScan.MiningArea(8, 8, RADIUS);

        assertFalse(section.next(0).isFarFrom(8 - RADIUS, 8), "from the far side of the one it's finished");
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
        area = sectionAroundBot();
        mineNext(bot);
        assertEquals(2, bot.at.y(), "it's back to the top of the section");
        assertEquals(0, bot.bedrockBroken);
    }
}
