package com.zenithplugins.blockminer.module;

import com.zenith.feature.pathfinder.calc.AStarPathFinder;
import com.zenith.feature.pathfinder.calc.IPath;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.process.MineProcess;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.feature.pathfinder.util.Favoring;
import com.zenith.feature.pathfinder.util.PathCalculationResult;
import com.zenith.feature.pathfinder.util.WorldScanner;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs next to ZenithProxy's real search, path finder and chunk code, over a made-up world. What this
 * can't show is the bot actually walking anywhere, as nothing here is connected to a server.
 */
class BlockScanTest {

    private static final int LAYER_LAYERS = 64;
    private static final BlockOptionalMetaLookup DEEPSLATE =
        new BlockOptionalMetaLookup(BlockRegistry.DEEPSLATE, BlockRegistry.COBBLED_DEEPSLATE);

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

    /** What the miner asks the scanner for. Only ever run on worlds small enough to survive it. */
    private static List<BlockPos> zenithsRealScan(BlockOptionalMetaLookup filter) {
        return WorldScanner.scanChunkRadius(filter, 64, 10, 32);
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

    // ----------------------------------------------------------------------------------------
    // what goes wrong in Zenith's search, and that it's seen coming
    // ----------------------------------------------------------------------------------------

    @Test
    void zenithsSearchCollectsEveryBlockFromAboveTheLayer() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, 40, 8.5);

        // 77 of the 81 chunks are inside the circle it searches. A real server loads several times more.
        assertEquals(77 * LAYER_LAYERS * 256, zenithsRealScan(DEEPSLATE).size());
        assertFalse(BlockScan.zenithSearchStaysSmall(DEEPSLATE));
    }

    @Test
    void zenithsSearchIsSmallFromAmongTheBlocks() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, -30, 8.5);

        int size = zenithsRealScan(DEEPSLATE).size();
        assertTrue(size < BlockScan.ZENITH_SEARCH_TOO_BIG, "found " + size);
        assertTrue(BlockScan.zenithSearchStaysSmall(DEEPSLATE));
    }

    @Test
    void everyHeightInTheLayerIsSmallEnough() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        for (int y = -63; y <= 8; y++) {
            TestWorld.putBotAt(8.5, y, 8.5);
            int size = zenithsRealScan(DEEPSLATE).size();
            assertTrue(size < BlockScan.ZENITH_SEARCH_TOO_BIG, "found " + size + " from y " + y);
            assertTrue(BlockScan.zenithSearchStaysSmall(DEEPSLATE), "from y " + y);
        }
    }

    @Test
    void justAboveTheLayerIsWhereItStartsToGoWrong() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);

        // Blocks within 10 of the bot's height are what makes the search stop early.
        TestWorld.putBotAt(8.5, 8, 8.5);
        assertTrue(BlockScan.zenithSearchStaysSmall(DEEPSLATE));
        TestWorld.putBotAt(8.5, 9, 8.5);
        assertFalse(BlockScan.zenithSearchStaysSmall(DEEPSLATE));
    }

    @Test
    void aRareBlockIsSafeFromAnyHeight() {
        realisticWorld();
        BlockOptionalMetaLookup diamonds = new BlockOptionalMetaLookup(BlockRegistry.DEEPSLATE_DIAMOND_ORE);
        for (int y : new int[] {-60, -30, 0, 40, 100, 200}) {
            TestWorld.putBotAt(8.5, y, 8.5);
            assertTrue(BlockScan.zenithSearchStaysSmall(diamonds), "from y " + y);
        }
    }

    @Test
    void countsExactlyWhatTheRealSearchCollects() {
        realisticWorld();
        List<BlockOptionalMetaLookup> filters = List.of(
            DEEPSLATE,
            new BlockOptionalMetaLookup(BlockRegistry.STONE, BlockRegistry.COBBLESTONE),
            new BlockOptionalMetaLookup(BlockRegistry.DEEPSLATE_DIAMOND_ORE),
            new BlockOptionalMetaLookup(BlockRegistry.DIAMOND_BLOCK));
        // Off to one side and in the corner too, so it runs off the edge of the loaded chunks unevenly.
        double[][] places = {{8.5, 8.5}, {-40.5, 20.5}, {70.5, -60.5}};
        for (BlockOptionalMetaLookup filter : filters) {
            for (double[] place : places) {
                for (int y = -64; y <= 100; y += 7) {
                    TestWorld.putBotAt(place[0], y, place[1]);
                    assertEquals(zenithsRealScan(filter).size(), BlockScan.zenithSearchSize(filter, Integer.MAX_VALUE),
                        filter.getBlockSet() + " from " + place[0] + " " + y + " " + place[1]);
                }
            }
        }
    }

    @Test
    void countingGivesUpInsteadOfGoingThroughThemAll() {
        world.layer(-64, -1, BlockRegistry.DEEPSLATE);
        TestWorld.putBotAt(8.5, 40, 8.5);

        int counted = BlockScan.zenithSearchSize(DEEPSLATE, 100_000);

        assertTrue(counted >= 100_000 && counted <= 100_000 + 4096, "counted " + counted);
    }

    // ----------------------------------------------------------------------------------------
    // finding something to walk to
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
        assertNull(BlockScan.walkToNearest(DEEPSLATE));
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
    void walkingGoalLeavesOutBlocksItShouldntBreak() {
        // Sitting on lava, which breaking it would let out.
        world.set(0, 19, 0, BlockRegistry.DEEPSLATE);
        world.set(0, 20, 0, BlockRegistry.LAVA);
        TestWorld.putBotAt(0.5, 30, 0.5);
        assertFalse(MineProcess.plausibleToBreak(new CalculationContext(), new BlockPos(0, 19, 0)));
        assertNull(BlockScan.walkToNearest(DEEPSLATE));

        world.set(10, 19, 0, BlockRegistry.DEEPSLATE);
        Goal goal = BlockScan.walkToNearest(DEEPSLATE);
        assertNotNull(goal);
        assertTrue(goal.isInGoal(10, 19, 0));
        assertFalse(goal.isInGoal(0, 19, 0));
    }

    // ----------------------------------------------------------------------------------------
    // getting there, and handing over to Zenith's miner
    // ----------------------------------------------------------------------------------------

    /** Searches the way the bot does, carrying on from the end of each part-way path. */
    private static BlockPos walk(BlockPos start, Goal goal) {
        BlockPos at = start;
        for (int searches = 0; searches < 40; searches++) {
            CalculationContext context = new CalculationContext(goal);
            AStarPathFinder search =
                new AStarPathFinder(at, at.x(), at.y(), at.z(), goal, new Favoring(null, context), context);
            // Zenith's own timeouts, so that this shows what the bot gets on the real thing.
            PathCalculationResult result = search.calculate(500, 2000);
            if (result.getPath().isEmpty()) return at;
            IPath path = result.getPath().get();
            BlockPos end = path.getDest();
            if (goal.isInGoal(end.x(), end.y(), end.z()) || end.equals(at)) return end;
            at = end;
        }
        return at;
    }

    @Test
    void realSearchDigsDownToTheLayerFromTheSurface() {
        realisticWorld();
        BlockPos start = new BlockPos(8, 61, 8);
        TestWorld.putBotAt(8.5, 61, 8.5);
        assertFalse(BlockScan.zenithSearchStaysSmall(DEEPSLATE), "should need to walk from here");

        Goal goal = BlockScan.walkToNearest(DEEPSLATE);
        assertNotNull(goal);
        BlockPos end = walk(start, goal);

        assertTrue(goal.isInGoal(end.x(), end.y(), end.z()), "only got to " + end + " from " + start);
        TestWorld.putBotAt(end.x() + 0.5, end.y(), end.z() + 0.5);
        assertTrue(BlockScan.zenithSearchStaysSmall(DEEPSLATE), "still not safe at " + end);
    }

    @Test
    void realSearchWalksAcrossAnOpenFloorToTheBlocks() {
        world.layer(60, 60, BlockRegistry.STONE);
        for (int y = 40; y <= 59; y++) {
            for (int x = 40; x <= 44; x++) {
                for (int z = 40; z <= 44; z++) {
                    world.set(x, y, z, BlockRegistry.COBBLED_DEEPSLATE);
                }
            }
        }
        BlockPos start = new BlockPos(0, 61, 0);
        TestWorld.putBotAt(0.5, 61, 0.5);

        Goal goal = BlockScan.walkToNearest(DEEPSLATE);
        assertNotNull(goal);
        BlockPos end = walk(start, goal);

        assertTrue(goal.isInGoal(end.x(), end.y(), end.z()), "only got to " + end);
    }

    @Test
    void zenithsRealMinerSearchWorksFromWhereTheWalkEnds() {
        realisticWorld();
        TestWorld.putBotAt(8.5, -30, 8.5);

        List<BlockPos> found = MineProcess.searchWorld(
            new CalculationContext(), DEEPSLATE, 64, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        assertEquals(64, found.size());
        for (BlockPos pos : found) {
            assertTrue(DEEPSLATE.has(World.getBlock(pos)), "found " + World.getBlock(pos) + " at " + pos);
        }
    }
}
