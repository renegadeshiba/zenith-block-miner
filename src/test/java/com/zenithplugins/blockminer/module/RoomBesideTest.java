package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.pathfinder.process.InteractWithProcess;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.entity.EntityRegistry;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs over ZenithProxy's real chunks and block rules, with pretend hands that change the world the way
 * mining and placing would. The bot is in a tunnel one block wide and two high, running along x, with the
 * way it came behind it. What this can't show is a real server: whether a block in reach really gets mined.
 */
class RoomBesideTest {

    private static final int[] FEET = {8, -30, 8};
    private static final int[] BEHIND = {7, -30, 8};
    private static final int[] AHEAD = {9, -30, 8};
    private static final int[] LEFT = {8, -30, 9};
    private static final int[] RIGHT = {8, -30, 7};

    private TestWorld world;
    private final List<String> done = new ArrayList<>();

    @BeforeEach
    void digATunnel() throws ReflectiveOperationException {
        world = new TestWorld(1);
        world.layer(-64, 8, BlockRegistry.DEEPSLATE);
        clear(FEET);
        clear(BEHIND);
        TestWorld.putBotAt(FEET[0] + 0.5, FEET[1], FEET[2] + 0.5);
        setPickaxe(new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1));
    }

    @AfterEach
    void unload() {
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
        setPickaxe(Container.EMPTY_STACK);
    }

    private static void setPickaxe(ItemStack stack) {
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().setItemStack(36, stack);
    }

    private void clear(int[] pos) {
        world.set(pos[0], pos[1], pos[2], BlockRegistry.AIR);
        world.set(pos[0], pos[1] + 1, pos[2], BlockRegistry.AIR);
    }

    private void set(int[] pos, Block block) {
        world.set(pos[0], pos[1], pos[2], block);
    }

    private static int[] above(int[] pos) {
        return new int[]{pos[0], pos[1] + 1, pos[2]};
    }

    private static int[] below(int[] pos) {
        return new int[]{pos[0], pos[1] - 1, pos[2]};
    }

    private static String named(String action, int[] pos) {
        return action + " " + pos[0] + " " + pos[1] + " " + pos[2];
    }

    private final Predicate<int[]> mine = pos -> {
        done.add(named("mine", pos));
        set(pos, BlockRegistry.AIR);
        return true;
    };

    private final Predicate<int[]> place = pos -> {
        done.add(named("place", pos));
        set(pos, BlockRegistry.SHULKER_BOX);
        return true;
    };

    private int[] placeIt() {
        return RoomBeside.placeBeside(FEET, mine, place);
    }

    private void everySideIs(Block block) {
        set(BEHIND, block);
        set(AHEAD, block);
        set(LEFT, block);
        set(RIGHT, block);
    }

    private void cornersAre(Block block) {
        for (int[] corner : new int[][] {{9, -30, 9}, {9, -30, 7}, {7, -30, 9}, {7, -30, 7}}) {
            set(corner, block);
        }
    }

    // ----------------------------------------------------------------------------------------
    // only mining if it has to
    // ----------------------------------------------------------------------------------------

    @Test
    void usesASpotThatsReadyWithoutMiningAnything() {
        assertArrayEquals(BEHIND, placeIt());
        assertEquals(List.of(named("place", BEHIND)), done);
    }

    @Test
    void minesOutASpotOnceTheOnlyOpenOneIsTakenByTheEnderChest() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);

        assertArrayEquals(AHEAD, placeIt());

        assertEquals(List.of(named("mine", above(AHEAD)), named("mine", AHEAD), named("place", AHEAD)), done);
        assertEquals(BlockRegistry.SHULKER_BOX, World.getBlock(new BlockPos(AHEAD[0], AHEAD[1], AHEAD[2])));
        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(new BlockPos(AHEAD[0], AHEAD[1] - 1, AHEAD[2])), "still has its floor");
    }

    @Test
    void leavesTheBlockAboveAloneIfItsNotInTheWay() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        set(above(AHEAD), BlockRegistry.AIR);

        assertArrayEquals(AHEAD, placeIt());

        assertEquals(List.of(named("mine", AHEAD), named("place", AHEAD)), done);
    }

    @Test
    void minesJustTheBlockAboveIfThatsAllThatsInTheWay() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        set(AHEAD, BlockRegistry.AIR);

        assertArrayEquals(AHEAD, placeIt());

        assertEquals(List.of(named("mine", above(AHEAD)), named("place", AHEAD)), done);
    }

    @Test
    void goesForWhicheverSpotNeedsTheLeastMined() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        set(above(RIGHT), BlockRegistry.AIR);

        assertArrayEquals(RIGHT, placeIt());

        assertEquals(List.of(named("mine", RIGHT), named("place", RIGHT)), done);
    }

    @Test
    void aReadySpotAtACornerComesBeforeMiningOne() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        clear(new int[]{9, -30, 9});

        assertArrayEquals(new int[]{9, -30, 9}, placeIt());

        assertEquals(1, done.size());
    }

    @Test
    void minesBesideItBeforeMiningACorner() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        // Needs less mining, but it can't see past the blocks beside it to get to it.
        set(above(new int[]{9, -30, 9}), BlockRegistry.AIR);

        assertArrayEquals(AHEAD, placeIt());
    }

    @Test
    void minesTheTopFirstSoItDoesntMineTheBottomForNothing() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        Predicate<int[]> topFails = pos -> {
            done.add(named("mine", pos));
            return false;
        };

        assertNull(RoomBeside.placeBeside(FEET, topFails, place));

        assertEquals(named("mine", above(AHEAD)), done.getFirst());
        assertTrue(done.stream().noneMatch(step -> step.equals(named("mine", AHEAD))), "gave up on that spot: " + done);
    }

    // ----------------------------------------------------------------------------------------
    // what it won't mine
    // ----------------------------------------------------------------------------------------

    private void assertWontMine(Block block) {
        everySideIs(block);
        cornersAre(block);

        assertNull(placeIt(), block.name());
        assertEquals(List.of(), done, block.name());
    }

    @Test
    void wontMineBedrock() {
        assertWontMine(BlockRegistry.BEDROCK);
    }

    @Test
    void wontMineAnEnderChest() {
        assertWontMine(BlockRegistry.ENDER_CHEST);
    }

    @Test
    void wontMineAShulkerBox() {
        assertWontMine(BlockRegistry.SHULKER_BOX);
    }

    @Test
    void wontMineAChest() {
        assertWontMine(BlockRegistry.CHEST);
    }

    @Test
    void wontMineACraftingTable() {
        assertWontMine(BlockRegistry.CRAFTING_TABLE);
    }

    @Test
    void wontMineAFurnace() {
        assertWontMine(BlockRegistry.FURNACE);
    }

    @Test
    void wontMineGravelBecauseMoreWouldFallIn() {
        assertWontMine(BlockRegistry.GRAVEL);
    }

    @Test
    void wontMineUnderSomethingThatFalls() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        for (int[] spot : new int[][] {AHEAD, LEFT, RIGHT, {9, -30, 9}, {9, -30, 7}, {7, -30, 9}, {7, -30, 7}}) {
            world.set(spot[0], spot[1] + 2, spot[2], BlockRegistry.GRAVEL);
        }

        assertNull(placeIt());

        assertEquals(List.of(), done);
    }

    @Test
    void wontMineNextToLavaAndLetItOut() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        // Lava beyond the block ahead, which would pour in on the bot.
        world.set(10, -30, 8, BlockRegistry.LAVA);

        assertArrayEquals(LEFT, placeIt());

        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(new BlockPos(AHEAD[0], AHEAD[1], AHEAD[2])));
    }

    @Test
    void wontMineUnderWater() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        world.set(9, -28, 8, BlockRegistry.WATER);

        assertArrayEquals(LEFT, placeIt());

        assertEquals(BlockRegistry.DEEPSLATE, World.getBlock(new BlockPos(9, -29, 8)));
    }

    @Test
    void wontUseASpotWithNothingUnderIt() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        set(below(AHEAD), BlockRegistry.AIR);

        assertArrayEquals(LEFT, placeIt());

        assertEquals(List.of(named("mine", above(LEFT)), named("mine", LEFT), named("place", LEFT)), done,
            "shouldn't mine anything where it can't go");
    }

    @Test
    void wontUseAnOpenSpotWithNothingUnderIt() {
        set(below(BEHIND), BlockRegistry.AIR);

        assertArrayEquals(AHEAD, placeIt());
    }

    // ----------------------------------------------------------------------------------------
    // when something goes wrong
    // ----------------------------------------------------------------------------------------

    @Test
    void comesBackWithNothingIfThereIsNowhereAtAll() {
        everySideIs(BlockRegistry.BEDROCK);
        cornersAre(BlockRegistry.BEDROCK);

        assertNull(placeIt());
    }

    @Test
    void triesTheNextSpotIfMiningOneDoesntWork() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        Predicate<int[]> aheadFails = pos -> {
            if (pos[0] == AHEAD[0] && pos[2] == AHEAD[2]) return false;
            return mine.test(pos);
        };

        assertArrayEquals(LEFT, RoomBeside.placeBeside(FEET, aheadFails, place));
    }

    @Test
    void triesTheNextSpotIfPlacingInOneDoesntWork() {
        Predicate<int[]> behindFails = pos -> {
            if (pos[0] == BEHIND[0] && pos[2] == BEHIND[2]) return false;
            return place.test(pos);
        };

        assertArrayEquals(AHEAD, RoomBeside.placeBeside(FEET, mine, behindFails));
    }

    @Test
    void doesntPlaceIfMiningTurnsOutNotToHaveMadeRoom() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        // Mining ahead doesn't clear it, such as if something falls in that it didn't know about.
        Predicate<int[]> aheadStaysFull = pos -> {
            done.add(named("mine", pos));
            if (pos[0] == AHEAD[0] && pos[2] == AHEAD[2]) return true;
            set(pos, BlockRegistry.AIR);
            return true;
        };

        assertArrayEquals(LEFT, RoomBeside.placeBeside(FEET, aheadStaysFull, place));

        assertTrue(done.stream().noneMatch(step -> step.equals(named("place", AHEAD))), "shouldn't have tried: " + done);
    }

    @Test
    void aSpotWithBothBlocksClearAndAFloorIsReady() {
        assertTrue(RoomBeside.isReady(BEHIND));
        assertEquals(false, RoomBeside.isReady(AHEAD));
    }

    // ----------------------------------------------------------------------------------------
    // the bot being in the way of itself
    // ----------------------------------------------------------------------------------------

    @Test
    void wontPlaceInASpotTheBotIsStillPartlyIn() {
        // Just crossed into this block from behind, as a walk ends as soon as its feet do.
        TestWorld.putBotAt(8.15, -30, 8.5);

        assertArrayEquals(AHEAD, placeIt());

        assertEquals(List.of(named("mine", above(AHEAD)), named("mine", AHEAD), named("place", AHEAD)), done);
    }

    @Test
    void wontMineOutASpotTheBotIsLeaningIntoEither() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        TestWorld.putBotAt(8.85, -30, 8.5);

        assertArrayEquals(LEFT, placeIt(), "ahead is the first choice, but the bot is partly in it");
    }

    @Test
    void usesASpotBehindItOnceItsClearOfIt() {
        TestWorld.putBotAt(8.31, -30, 8.5);

        assertArrayEquals(BEHIND, placeIt());
    }

    // ----------------------------------------------------------------------------------------
    // ZenithProxy's own rules for what it can reach and place in
    // ----------------------------------------------------------------------------------------

    @Test
    void zenithAgreesTheBotCanReachWhatItWouldMine() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);

        for (RoomBeside.Spot spot : RoomBeside.spotsAround(FEET)) {
            for (int[] block : spot.inTheWay()) {
                assertTrue(new InteractWithProcess.BreakBlock(block[0], block[1], block[2], true).canInteract(),
                    "can't reach " + block[0] + " " + block[1] + " " + block[2]);
            }
        }
    }

    @Test
    void zenithCanPlaceInASpotOnceItsBeenMinedOut() {
        set(BEHIND, BlockRegistry.ENDER_CHEST);
        var inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        inventory.setItemStack(9, new ItemStack(ItemRegistry.SHULKER_BOX.id(), 1));
        try {
            var placing = new InteractWithProcess.PlaceBlock(AHEAD[0], AHEAD[1], AHEAD[2], ItemRegistry.SHULKER_BOX);
            assertFalse(placing.targetValid(), "full of deepslate to begin with");

            RoomBeside.placeBeside(FEET, mine, pos -> false);

            assertTrue(placing.targetValid());
            assertFalse(placing.findPlaceTargets().isEmpty(), "nothing to place it against");
            assertFalse(placing.selfInPlaceTarget());
            assertFalse(placing.entityInPlaceTarget());
        } finally {
            inventory.setItemStack(9, Container.EMPTY_STACK);
        }
    }

    /** With a full inventory, what it mines to make room stays where it fell, in the spot it just made. */
    @Test
    void whatItDropsMiningOutASpotWontGetInTheWayOfPlacing() {
        assertFalse(EntityRegistry.ITEM.blocksBuilding());
        assertTrue(EntityRegistry.ZOMBIE.blocksBuilding(), "if nothing did, this wouldn't be saying anything");
    }
}
