package com.zenithplugins.blockminer.module;

import com.zenith.feature.pathfinder.BlockStateInterface;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.process.InteractWithProcess;
import com.zenith.feature.pathfinder.process.MineProcess;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemRegistry;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds somewhere beside the bot to put a container down, mining out a spot if there's nowhere ready. In a
 * tunnel one block wide, the only open spot is the one behind the bot, and an ender chest takes that, which
 * leaves nowhere for the shulker box.
 */
final class RoomBeside {

    /** Beside the bot, and then at its corners, which it can't see past the blocks beside it to mine. */
    private static final int[][] BESIDE = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int STRAIGHT_BESIDE = 4;

    /** Where a container could go, and what has to be mined first, in the order to mine it. */
    record Spot(int[] pos, List<int[]> inTheWay, boolean atACorner) {
    }

    private RoomBeside() {
    }

    /**
     * Puts a container down beside the bot, mining out a spot for it only if there's nowhere ready.
     *
     * @param mine mines a block, and returns whether that worked
     * @param place puts the container down, and returns whether that worked
     * @return where it went, or null if it couldn't go anywhere
     */
    static int @Nullable [] placeBeside(int[] feet, Predicate<int[]> mine, Predicate<int[]> place) {
        for (Spot spot : spotsAround(feet)) {
            if (makeRoom(spot, mine) && place.test(spot.pos())) return spot.pos();
        }
        return null;
    }

    private static boolean makeRoom(Spot spot, Predicate<int[]> mine) {
        for (int[] block : spot.inTheWay()) {
            if (!mine.test(block)) return false;
        }
        // Mining one thing can change another, like gravel falling from higher up.
        return spot.inTheWay().isEmpty() || isReady(spot.pos());
    }

    /**
     * The spots around the bot, best first. Ones that are ready come before any that need mining. Of the
     * rest, ones right beside it come before corners, and then whichever needs the least mined.
     */
    static List<Spot> spotsAround(int[] feet) {
        CalculationContext context = new CalculationContext();
        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < BESIDE.length; i++) {
            int[] pos = {feet[0] + BESIDE[i][0], feet[1], feet[2] + BESIDE[i][1]};
            Spot spot = spotAt(pos, i >= STRAIGHT_BESIDE, context);
            if (spot != null) spots.add(spot);
        }
        spots.sort(Comparator
            .<Spot, Boolean>comparing(spot -> !spot.inTheWay().isEmpty())
            .thenComparing(Spot::atACorner)
            .thenComparingInt(spot -> spot.inTheWay().size()));
        return spots;
    }

    /** The bottom one is mined last, so it's not mined for nothing if the one above it turns out to be a problem. */
    private static @Nullable Spot spotAt(int[] pos, boolean atACorner, CalculationContext context) {
        Block support = blockAt(pos[0], pos[1] - 1, pos[2]);
        if (support == null || !support.solidBlock()) return null;
        // A walk ends as soon as its feet cross into a block, so the bot is often still partly in the one behind.
        if (new InteractWithProcess.PlaceBlock(pos[0], pos[1], pos[2], ItemRegistry.SHULKER_BOX).selfInPlaceTarget()) return null;

        List<int[]> inTheWay = new ArrayList<>();
        int[] above = {pos[0], pos[1] + 1, pos[2]};
        if (!isClear(above)) {
            Block higherStill = blockAt(above[0], above[1] + 1, above[2]);
            if (higherStill == null || higherStill.fallingBlock() || !canMine(above, context)) return null;
            inTheWay.add(above);
        }
        if (!isClear(pos)) {
            if (!canMine(pos, context)) return null;
            inTheWay.add(pos);
        }
        return new Spot(pos, inTheWay, atACorner);
    }

    /** Room to stand a container here, including the space above that a shulker box needs to open. */
    static boolean isReady(int[] pos) {
        Block support = blockAt(pos[0], pos[1] - 1, pos[2]);
        return isClear(pos)
            && isClear(new int[]{pos[0], pos[1] + 1, pos[2]})
            && support != null && support.solidBlock();
    }

    static boolean isClear(int[] pos) {
        Block block = blockAt(pos[0], pos[1], pos[2]);
        return block != null && block.replaceable();
    }

    /**
     * Not bedrock, or anything touching lava or water that mining it would let out, as the pathfinder
     * wouldn't do those either. Not anything with something in it, like a chest, a sign or the ender chest
     * itself, or a crafting table that it might have put there. And not anything that falls, since what
     * falls in behind it fills the spot straight back up.
     */
    private static boolean canMine(int[] pos, CalculationContext context) {
        Block block = blockAt(pos[0], pos[1], pos[2]);
        return block != null
            && !block.fallingBlock()
            && block.blockEntityType() == null
            && block != BlockRegistry.CRAFTING_TABLE
            && MineProcess.plausibleToBreak(context, new BlockPos(pos[0], pos[1], pos[2]));
    }

    private static @Nullable Block blockAt(int x, int y, int z) {
        return BlockStateInterface.isLoaded(x, z) ? BlockStateInterface.getBlock(x, y, z) : null;
    }
}
