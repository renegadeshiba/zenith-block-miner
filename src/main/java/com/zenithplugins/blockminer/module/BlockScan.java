package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.PlayerCache;
import com.zenith.cache.data.chunk.Chunk;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalAvoidBlocks;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalComposite;
import com.zenith.feature.pathfinder.goals.GoalTwoBlocks;
import com.zenith.feature.pathfinder.goals.GoalXZ;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.feature.pathfinder.process.MineProcess;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.dimension.DimensionData;
import com.zenith.util.math.MathHelper;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.Palette;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.IntStream;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;

/**
 * Decides where the bot goes next, looking through the loaded chunks while holding on to very little.
 *
 * <p>This is instead of Zenith's miner, {@code BARITONE.mine}, which can't be told where not to go:
 * <ul>
 * <li>It goes for the cheapest block, and with blocks all around that's always the one under the bot's
 * feet, since going sideways means breaking two. So it digs straight down until it's in the bedrock,
 * where it can end up in a pit it can't get out of, and keeps trying forever.
 * <li>With none of its blocks loaded, it goes exploring at Y -59, among the lava lakes.
 * <li>Its search only stops early once it finds a match within 10 blocks of the bot's height, and until
 * then keeps every match in every loaded chunk in memory. For a block that's everywhere, that's millions
 * of positions, every few seconds, until the whole proxy runs out of memory.
 * </ul>
 */
final class BlockScan {

    // Enough to still have choices after leaving out the ones that it can't or shouldn't get to. The
    // pathfinder gets as many as Zenith's miner gives it.
    private static final int GOAL_CANDIDATES = 256;
    private static final int GOAL_BLOCKS = 64;
    private static final int MAX_CHUNKS_AWAY = 32;

    // Y -50 in the overworld. Bedrock goes up to 4 above the bottom of the world, and the overworld's lava
    // lakes to 10 above that.
    private static final int LOWEST_LEVEL_ABOVE_WORLD_BOTTOM = 14;
    // About 10,000 blocks of walking, so no real way around is ever rejected in favour of going lower.
    private static final double BELOW_LOWEST_LEVEL_PENALTY = 50_000;
    // Per block further down, so a bot that's already down there heads up.
    private static final double DEEPER_PENALTY = 20;
    private static final double PRESSING_ON_WEIGHT = 6;

    // Around 0.7% of the blocks in range. Stone-like blocks are far above that, and even the commonest
    // ores are below it, so it tells a block that's everywhere from one worth going to look for.
    private static final int PLENTIFUL_RANGE = 16;
    private static final int PLENTIFUL_COUNT = 256;

    private static final int LEVEL_CHECK_RANGE = 4;

    // Errands use an ender chest within 48 blocks rather than placing another one, and no two places in
    // an area this size are further apart than that. Left to go wherever's nearest, the bot tunnels off
    // in a line and leaves a trail of ender chests behind it, until it has none left.
    static final int MINING_AREA_RADIUS = 24;

    // Far enough for a good look at what comes into range, before deciding whether to go any further.
    private static final int EXPLORING_BLOCKS = 32;
    private static final int[][] EXPLORING_DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /** Where the bot keeps its mining to, for a block that's everywhere. */
    record MiningArea(int centreX, int centreZ, int radius) {
        boolean contains(int x, int z) {
            return distanceSq(x, z) <= (long) radius * radius;
        }

        /** True if the bot isn't anywhere near this any more, such as after dying or being moved. */
        boolean isFarFrom(int x, int z) {
            return distanceSq(x, z) > 4L * radius * radius;
        }

        boolean touchesChunk(int baseX, int baseZ) {
            long dx = distanceToSection(centreX, baseX);
            long dz = distanceToSection(centreZ, baseZ);
            return dx * dx + dz * dz <= (long) radius * radius;
        }

        private long distanceSq(int x, int z) {
            long dx = x - centreX;
            long dz = z - centreZ;
            return dx * dx + dz * dz;
        }
    }

    /**
     * Where to go next. {@code blocks} are what it's going for, {@code onBotsLevel} is whether that's
     * mining on the level the bot is on rather than still getting somewhere, and {@code leavesArea} is
     * whether this takes it away from its area, so it should start a new one from wherever it ends up.
     */
    record MiningGoal(Goal goal, List<BlockPos> blocks, boolean onBotsLevel, boolean leavesArea) {
    }

    /**
     * {@code goal}, somewhere the bot can stand without being below the lowest level, on a way there that
     * only goes below it as a last resort. A goal can't stop the pathfinder's search going anywhere, but
     * the search always looks next at the place with the lowest "cost so far plus heuristic", so a huge
     * heuristic means it tries everything else first.
     *
     * <p>{@code weight} makes the search press on towards the goal. Heuristics are measured in walking, and
     * digging costs several times that, so where the only way is through rock, it otherwise spends all its
     * time exploring and can give up having got nowhere. It might dig where going round would be cheaper,
     * but this is a miner.
     */
    private record AboveLowestLevel(Goal goal, int lowestLevel, double weight) implements Goal {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return y >= lowestLevel && goal.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double penalty = y < lowestLevel ? BELOW_LOWEST_LEVEL_PENALTY + (lowestLevel - y) * DEEPER_PENALTY : 0;
            return goal.heuristic(x, y, z) * weight + penalty;
        }

        @Override
        public double heuristic() {
            return goal.heuristic();
        }

        @Override
        public String toString() {
            return goal + ", above y " + lowestLevel;
        }
    }

    private BlockScan() {
    }

    /** The lowest the bot goes in the current dimension, whatever it's after. */
    static int lowestLevel() {
        return World.getCurrentDimension().minY() + LOWEST_LEVEL_ABOVE_WORLD_BOTTOM;
    }

    /**
     * True if there's so much of this close to the bot that it's a block that's everywhere, rather than
     * one worth going to look for. Blocks below the lowest level don't count.
     */
    static boolean isPlentifulNearby(BlockOptionalMetaLookup filter) {
        IntSet states = filter.getBlockStateIds();
        PlayerCache player = CACHE.getPlayerCache();
        int botX = MathHelper.floorI(player.getX());
        int botY = MathHelper.floorI(player.getY());
        int botZ = MathHelper.floorI(player.getZ());
        int lowestY = Math.max(botY - PLENTIFUL_RANGE, lowestLevel());
        int highestY = botY + PLENTIFUL_RANGE;
        int minY = World.getCurrentDimension().minY();

        int found = 0;
        for (int chunkX = (botX - PLENTIFUL_RANGE) >> 4; chunkX <= (botX + PLENTIFUL_RANGE) >> 4; chunkX++) {
            for (int chunkZ = (botZ - PLENTIFUL_RANGE) >> 4; chunkZ <= (botZ + PLENTIFUL_RANGE) >> 4; chunkZ++) {
                Chunk chunk = World.getChunk(chunkX, chunkZ);
                if (chunk == null) continue;
                ChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    int baseY = minY + (index << 4);
                    if (baseY > highestY || baseY + 15 < lowestY || !mightHold(sections[index], states)) continue;
                    for (int y = Math.max(lowestY, baseY); y <= Math.min(highestY, baseY + 15); y++) {
                        for (int z = 0; z < 16; z++) {
                            if (Math.abs((chunkZ << 4 | z) - botZ) > PLENTIFUL_RANGE) continue;
                            for (int x = 0; x < 16; x++) {
                                if (Math.abs((chunkX << 4 | x) - botX) > PLENTIFUL_RANGE) continue;
                                if (states.contains(sections[index].getBlock(x, y - baseY, z)) && ++found >= PLENTIFUL_COUNT) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Where to send the pathfinder next, to stand in a block and so break it. Nothing below the lowest
     * level counts, whatever it is.
     *
     * <p>For a block worth looking for, that's the nearest ones anywhere in the loaded chunks. For a block
     * that's everywhere, it's on the bot's own level, at its feet or head, so it mines outwards along a
     * flat floor. Once that's used up in its area, or there's not much of what it wants on this level, it
     * goes down a level, and once it's used up its area all the way down, it looks further out.
     *
     * @param outOfReach blocks it's already failed to find a way to
     * @return null if there's nothing it can go for in the loaded chunks
     */
    static @Nullable MiningGoal miningGoal(BlockOptionalMetaLookup filter, MiningArea area, Set<BlockPos> outOfReach) {
        PlayerCache player = CACHE.getPlayerCache();
        int botX = MathHelper.floorI(player.getX());
        int botY = MathHelper.floorI(player.getY());
        int botZ = MathHelper.floorI(player.getZ());

        // On the same setting Zenith's miner does this on. Everything else can wait.
        if (CONFIG.client.extra.pathfinder.mineProcessExitLavaGoal && MovementHelper.isPlayerTouchingLava()) {
            return new MiningGoal(new GoalAvoidBlocks(botX, botY, botZ, BlockRegistry.LAVA), List.of(), false, false);
        }

        int lowestLevel = lowestLevel();
        CalculationContext context = new CalculationContext();
        if (isPlentifulNearby(filter)) {
            if (botY >= lowestLevel && levelIsWorthMining(filter, botX, botY, botZ)) {
                List<BlockPos> blocks = choose(nearest(filter, GOAL_CANDIDATES, botY, botY + 1, area), outOfReach, context);
                if (!blocks.isEmpty()) return new MiningGoal(goalOnLevel(blocks, botY, lowestLevel), blocks, true, false);
            }
            // Two down rather than one, as one down it would have already mined at head height.
            List<BlockPos> blocks = choose(nearest(filter, GOAL_CANDIDATES, lowestLevel, botY - 2, area), outOfReach, context);
            if (!blocks.isEmpty()) return new MiningGoal(goalInBlocks(blocks, lowestLevel), blocks, false, false);

            // Nothing higher than it can reach, as a ceiling it's dug out from under it is too far up.
            int highestReachable = Math.max(botY + 1, lowestLevel + 1);
            blocks = choose(nearest(filter, GOAL_CANDIDATES, lowestLevel, highestReachable, area), outOfReach, context);
            if (!blocks.isEmpty()) return new MiningGoal(goalInBlocks(blocks, lowestLevel), blocks, false, false);
            blocks = choose(nearest(filter, GOAL_CANDIDATES, lowestLevel, highestReachable, null), outOfReach, context);
            if (!blocks.isEmpty()) return new MiningGoal(goalInBlocks(blocks, lowestLevel), blocks, false, true);
        }
        List<BlockPos> blocks = choose(nearest(filter, GOAL_CANDIDATES, lowestLevel, Integer.MAX_VALUE, null), outOfReach, context);
        return blocks.isEmpty() ? null : new MiningGoal(goalInBlocks(blocks, lowestLevel), blocks, false, true);
    }

    /** Somewhere further off in this direction, to bring more chunks into range when none of them help. */
    static Goal exploringGoal(int direction) {
        PlayerCache player = CACHE.getPlayerCache();
        int[] heading = EXPLORING_DIRECTIONS[Math.floorMod(direction, EXPLORING_DIRECTIONS.length)];
        return new AboveLowestLevel(
            new GoalXZ(
                MathHelper.floorI(player.getX()) + heading[0] * EXPLORING_BLOCKS,
                MathHelper.floorI(player.getZ()) + heading[1] * EXPLORING_BLOCKS),
            lowestLevel(),
            PRESSING_ON_WEIGHT);
    }

    /**
     * True unless most of what's solid around the bot, at its own height, isn't what it's after. Where
     * one block is giving way to another, such as stone to deepslate, staying level would mean digging
     * through mostly the wrong one.
     */
    private static boolean levelIsWorthMining(BlockOptionalMetaLookup filter, int botX, int botY, int botZ) {
        int wanted = 0;
        int unwanted = 0;
        for (int x = botX - LEVEL_CHECK_RANGE; x <= botX + LEVEL_CHECK_RANGE; x++) {
            for (int z = botZ - LEVEL_CHECK_RANGE; z <= botZ + LEVEL_CHECK_RANGE; z++) {
                for (int y = botY; y <= botY + 1; y++) {
                    Block block = World.getBlock(new BlockPos(x, y, z));
                    if (filter.has(block)) {
                        wanted++;
                    } else if (!block.isAir()) {
                        unwanted++;
                    }
                }
            }
        }
        return wanted >= unwanted;
    }

    private static List<BlockPos> choose(List<BlockPos> candidates, Set<BlockPos> outOfReach, CalculationContext context) {
        return candidates.stream()
            .filter(pos -> !outOfReach.contains(pos))
            .filter(pos -> MineProcess.plausibleToBreak(context, pos))
            .limit(GOAL_BLOCKS)
            .toList();
    }

    /**
     * To stand in the column of one of these blocks, on exactly this level. Going for a block at head
     * height by stepping up onto the one below it would save breaking a block, but then it's mining one
     * layer at a time, at half the speed.
     */
    private static Goal goalOnLevel(List<BlockPos> blocks, int level, int lowestLevel) {
        return new AboveLowestLevel(
            new GoalComposite(blocks.stream()
                .map(pos -> new GoalBlock(pos.x(), level, pos.z()))
                .distinct()
                .toArray(Goal[]::new)),
            lowestLevel,
            1);
    }

    private static Goal goalInBlocks(List<BlockPos> blocks, int lowestLevel) {
        return new AboveLowestLevel(
            new GoalComposite(blocks.stream().map(GoalTwoBlocks::new).toArray(Goal[]::new)),
            lowestLevel,
            PRESSING_ON_WEIGHT);
    }

    /**
     * The blocks closest to the bot, closest first, up to {@code count}. Never holds on to more than
     * that, however many there are, and doesn't read further out than could change the answer.
     */
    static List<BlockPos> nearest(BlockOptionalMetaLookup filter, int count) {
        return nearest(filter, count, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
    }

    /** {@link #nearest(BlockOptionalMetaLookup, int)}, looking only between these heights and in this area. */
    static List<BlockPos> nearest(
        BlockOptionalMetaLookup filter, int count, int lowestY, int highestY, @Nullable MiningArea area
    ) {
        PlayerCache player = CACHE.getPlayerCache();
        DimensionData dimension = World.getCurrentDimension();
        ClosestBlocks closest = new ClosestBlocks(
            MathHelper.floorI(player.getX()), MathHelper.floorI(player.getY()), MathHelper.floorI(player.getZ()),
            filter.getBlockStateIds(), count, lowestY, highestY, area);
        int[] sections = sectionsNearestFirst(dimension, (closest.botY - dimension.minY()) >> 4);

        int centreChunkX = closest.botX >> 4;
        int centreChunkZ = closest.botZ >> 4;
        for (int away = 0; away <= MAX_CHUNKS_AWAY; away++) {
            boolean anyLoaded = false;
            for (int dx = -away; dx <= away; dx++) {
                for (int dz = -away; dz <= away; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != away) continue;
                    int chunkX = centreChunkX + dx;
                    int chunkZ = centreChunkZ + dz;
                    Chunk chunk = World.getChunk(chunkX, chunkZ);
                    if (chunk == null) continue;
                    anyLoaded = true;
                    if (area != null && !area.touchesChunk(chunkX << 4, chunkZ << 4)) continue;
                    closest.lookThrough(chunk, chunkX << 4, chunkZ << 4, sections, dimension.minY());
                }
            }
            if (!anyLoaded) break;
        }
        return closest.closestFirst();
    }

    private static final class ClosestBlocks {
        final int botX;
        final int botY;
        final int botZ;
        final IntSet states;
        final int count;
        final int lowestY;
        final int highestY;
        final @Nullable MiningArea area;
        final PriorityQueue<BlockPos> furthestOnTop;

        ClosestBlocks(
            int botX, int botY, int botZ, IntSet states, int count, int lowestY, int highestY, @Nullable MiningArea area
        ) {
            this.botX = botX;
            this.botY = botY;
            this.botZ = botZ;
            this.states = states;
            this.count = count;
            this.lowestY = lowestY;
            this.highestY = highestY;
            this.area = area;
            this.furthestOnTop = new PriorityQueue<>(count + 1, byDistance().reversed());
        }

        private Comparator<BlockPos> byDistance() {
            return Comparator.comparingInt(pos -> distanceSq(pos.x(), pos.y(), pos.z()));
        }

        private int distanceSq(int x, int y, int z) {
            int dx = x - botX;
            int dy = y - botY;
            int dz = z - botZ;
            return dx * dx + dy * dy + dz * dz;
        }

        void lookThrough(Chunk chunk, int baseX, int baseZ, int[] sections, int minY) {
            ChunkSection[] chunkSections = chunk.getSections();
            for (int index : sections) {
                int baseY = minY + (index << 4);
                if (baseY > highestY || baseY + 15 < lowestY || cantImproveOn(baseX, baseY, baseZ)) continue;
                ChunkSection section = chunkSections[index];
                if (!mightHold(section, states)) continue;
                for (int y = Math.max(lowestY, baseY); y <= Math.min(highestY, baseY + 15); y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (states.contains(section.getBlock(x, y - baseY, z))) offer(baseX | x, y, baseZ | z);
                        }
                    }
                }
            }
        }

        /** True if even the closest corner of this section is no closer than what's already been found. */
        private boolean cantImproveOn(int baseX, int baseY, int baseZ) {
            if (furthestOnTop.size() < count) return false;
            BlockPos furthest = furthestOnTop.peek();
            int dx = distanceToSection(botX, baseX);
            int dy = distanceToSection(botY, baseY);
            int dz = distanceToSection(botZ, baseZ);
            return dx * dx + dy * dy + dz * dz >= distanceSq(furthest.x(), furthest.y(), furthest.z());
        }

        private void offer(int x, int y, int z) {
            // Standing in it already, so the walk would end at once without breaking it.
            if (x == botX && z == botZ && (y == botY || y == botY + 1)) return;
            if (area != null && !area.contains(x, z)) return;
            if (furthestOnTop.size() == count) {
                BlockPos furthest = furthestOnTop.peek();
                if (distanceSq(x, y, z) >= distanceSq(furthest.x(), furthest.y(), furthest.z())) return;
                furthestOnTop.poll();
            }
            furthestOnTop.add(new BlockPos(x, y, z));
        }

        List<BlockPos> closestFirst() {
            List<BlockPos> sorted = new ArrayList<>(furthestOnTop);
            sorted.sort(byDistance());
            return sorted;
        }
    }

    private static int distanceToSection(int coordinate, int base) {
        if (coordinate < base) return base - coordinate;
        return Math.max(0, coordinate - (base + 15));
    }

    private static int[] sectionsNearestFirst(DimensionData dimension, int botSection) {
        return IntStream.range(0, dimension.height() / 16)
            .boxed()
            .sorted(Comparator.comparingInt(section -> Math.abs(section - botSection)))
            .mapToInt(Integer::intValue)
            .toArray();
    }

    private static boolean mightHold(@Nullable ChunkSection section, IntSet states) {
        if (section == null || section.isBlockCountEmpty()) return false;
        Palette palette = section.getChunkData().getPalette();
        for (IntIterator state = states.iterator(); state.hasNext(); ) {
            if (palette.contains(state.nextInt())) return true;
        }
        return false;
    }
}
