package com.zenithplugins.blockminer.module;

import com.zenith.feature.pathfinder.calc.AStarPathFinder;
import com.zenith.feature.pathfinder.calc.IPath;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.process.InteractWithProcess;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.feature.pathfinder.util.Favoring;
import com.zenith.feature.pathfinder.util.PathCalculationResult;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows the paths from ZenithProxy's real path search through a {@link TestWorld}, breaking and placing
 * what the path says, and using Zenith's own timeouts. That's as near as a test can get to the bot walking
 * around. What it leaves out is a real server: drops, mobs, lag, and blocks that take time to break.
 */
final class PretendBot {

    private final TestWorld world;
    private final BlockOptionalMetaLookup wanted;
    BlockPos at;
    int blocksBroken;
    int wantedBroken;
    int bedrockBroken;
    int lowestY;
    int searches;

    PretendBot(TestWorld world, BlockPos start, BlockOptionalMetaLookup wanted) {
        this.world = world;
        this.wanted = wanted;
        this.lowestY = start.y();
        moveTo(start);
    }

    private void moveTo(BlockPos pos) {
        at = pos;
        TestWorld.putBotAt(pos.x() + 0.5, pos.y(), pos.z() + 0.5);
    }

    /** Does what it's been told to, the same way the module sends it off to. Returns false if it couldn't. */
    boolean carryOut(BlockScan.MiningGoal goal) {
        return goal.goal() == null ? mine(goal.blocks().getFirst()) : followPathTo(goal.goal());
    }

    /** Mines a block from where it stands, if Zenith's own check says that it can. */
    boolean mine(BlockPos block) {
        if (!new InteractWithProcess.BreakBlock(block.x(), block.y(), block.z(), true).canInteract()) return false;
        clear(block.x(), block.y(), block.z());
        return true;
    }

    /** Goes all the way to the goal, one path after another. Returns false if it couldn't get there. */
    boolean followPathTo(Goal goal) {
        for (int tries = 0; tries < 40; tries++) {
            searches++;
            CalculationContext context = new CalculationContext(goal);
            AStarPathFinder search =
                new AStarPathFinder(at, at.x(), at.y(), at.z(), goal, new Favoring(null, context), context);
            PathCalculationResult result = search.calculate(500, 2000);
            if (result.getPath().isEmpty()) return false;
            IPath path = result.getPath().get();
            List<BlockPos> positions = new ArrayList<>(path.positions());
            for (int i = 1; i < positions.size(); i++) {
                step(positions.get(i - 1), positions.get(i));
                lowestY = Math.min(lowestY, positions.get(i).y());
            }
            BlockPos end = path.getDest();
            moveTo(end);
            if (goal.isInGoal(end.x(), end.y(), end.z())) return true;
            if (positions.size() <= 1) return false;
        }
        return false;
    }

    private void step(BlockPos from, BlockPos to) {
        boolean sameColumn = from.x() == to.x() && from.z() == to.z();
        if (sameColumn && to.y() > from.y()) {
            // Pillaring: it jumps, and puts a block where it was standing.
            clear(to.x(), to.y() + 1, to.z());
            world.set(from.x(), from.y(), from.z(), BlockRegistry.COBBLED_DEEPSLATE);
        } else if (sameColumn) {
            clear(to.x(), to.y(), to.z());
        } else {
            // Room for both ends of the move: it has to pass through the higher of the two.
            int top = Math.max(from.y(), to.y());
            for (int y = to.y(); y <= top + 1; y++) clear(to.x(), y, to.z());
            if (to.y() > from.y()) clear(from.x(), from.y() + 2, from.z());
        }
    }

    private void clear(int x, int y, int z) {
        Block block = World.getBlock(new BlockPos(x, y, z));
        if (block == BlockRegistry.BEDROCK) bedrockBroken++;
        if (block.isAir()) return;
        if (wanted.has(block)) wantedBroken++;
        world.set(x, y, z, BlockRegistry.AIR);
        blocksBroken++;
    }
}
