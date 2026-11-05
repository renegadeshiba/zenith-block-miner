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
import com.zenith.feature.pathfinder.process.InteractWithProcess;
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

    // Y -58 in the overworld. Bedrock goes up to 4 above the bottom of the world, so that leaves a block
    // between it and the bot's floor, which keeps it out of the pits in the bedrock.
    private static final int LOWEST_LEVEL_ABOVE_WORLD_BOTTOM = 6;
    // About 10,000 blocks of walking, so no real way around is ever rejected in favour of going lower.
    private static final double BELOW_LOWEST_LEVEL_PENALTY = 50_000;
    // Per block further down, so a bot that's already down there heads up.
    private static final double DEEPER_PENALTY = 20;
    private static final double PRESSING_ON_WEIGHT = 6;

    // Less of a layer than this, as a share of a whole section, is just a few odd blocks, like part of
    // someone's build, and not worth climbing to.
    private static final int LEAST_PERCENT_OF_A_LAYER = 1;

    // Its feet and head, then all that it can reach straight above it from the floor, which is up to the
    // underside of a block. What it mines up there falls on the bot, so it gets picked up without going
    // anywhere.
    private static final int ROOM_HEIGHT = 7;

    // Errands use an ender chest within 48 blocks rather than placing another one, and no two places on
    // a level of a section this size are further apart than that. Left to go wherever's nearest, the bot
    // tunnels off in a line and leaves a trail of ender chests behind it, until it has none left.
    static final int MINING_AREA_RADIUS = 24;

    // Far enough for a good look at what comes into range, before deciding whether to go any further.
    private static final int EXPLORING_BLOCKS = 32;
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /**
     * A section of the world the bot mines out from the top down, for a block that's everywhere. {@code level}
     * is the one it's working on, and {@code heading} is the way it goes once this section is finished.
     */
    record MiningArea(int centreX, int centreZ, int radius, int level, int heading) {
        private static final int NOT_STARTED = Integer.MAX_VALUE;

        MiningArea(int centreX, int centreZ, int radius) {
            this(centreX, centreZ, radius, NOT_STARTED, 0);
        }

        boolean hasStarted() {
            return level != NOT_STARTED;
        }

        MiningArea onLevel(int newLevel) {
            return new MiningArea(centreX, centreZ, radius, newLevel, heading);
        }

        /** The section touching this one, straight ahead, or after this many turns. */
        MiningArea next(int turns) {
            int newHeading = Math.floorMod(heading + turns, DIRECTIONS.length);
            return new MiningArea(
                centreX + DIRECTIONS[newHeading][0] * radius * 2,
                centreZ + DIRECTIONS[newHeading][1] * radius * 2,
                radius,
                NOT_STARTED,
                newHeading);
        }

        boolean contains(int x, int z) {
            return distanceSq(x, z) <= (long) radius * radius;
        }

        /**
         * True if the bot isn't anywhere near this any more, such as after dying or being moved. It sets
         * off for the next section from as much as three radiuses away.
         */
        boolean isFarFrom(int x, int z) {
            return distanceSq(x, z) > 16L * radius * radius;
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
     * What to do next. {@code blocks} are what it's going for. With a {@code goal}, it walks there to stand
     * in one of them. With none, there's just the one block, within reach, to mine from where it stands.
     * {@code onBotsLevel} is whether that's mining on the level it's working on rather than still getting
     * there, and {@code area} is the section to carry on with, or null to start a new one from wherever it
     * ends up.
     */
    record MiningGoal(@Nullable Goal goal, List<BlockPos> blocks, boolean onBotsLevel, @Nullable MiningArea area) {
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
     * What the bot does next. Nothing below the lowest level counts, whatever it is.
     *
     * <p>A block that's everywhere gets mined from the top down, a section at a time. It starts on the highest
     * layer of its section that's mostly what it's after, and at each spot mines all it can reach above it,
     * then outwards along a flat floor. With that level used up it goes down to the next, and when there
     * are none left it moves on to the top of the next section. Going down, it never has to climb until
     * it's finished the whole section.
     *
     * <p>Anything else is worth going to look for, so it goes for the nearest ones in the loaded chunks.
     *
     * @param outOfReach blocks it's already failed to get to
     * @return null if there's nothing it can go for in the loaded chunks
     */
    static @Nullable MiningGoal miningGoal(BlockOptionalMetaLookup filter, MiningArea area, Set<BlockPos> outOfReach) {
        PlayerCache player = CACHE.getPlayerCache();

        // On the same setting Zenith's miner does this on. Everything else can wait.
        if (CONFIG.client.extra.pathfinder.mineProcessExitLavaGoal && MovementHelper.isPlayerTouchingLava()) {
            Goal awayFromLava = new GoalAvoidBlocks(
                MathHelper.floorI(player.getX()), MathHelper.floorI(player.getY()), MathHelper.floorI(player.getZ()),
                BlockRegistry.LAVA);
            return new MiningGoal(awayFromLava, List.of(), false, area);
        }

        CalculationContext context = new CalculationContext();
        MiningGoal goal = goalInSection(filter, area, outOfReach, context);
        if (goal != null) return goal;

        // With nothing worth mining in a section it hasn't even started, this isn't a block that's everywhere
        // around here, so there's no point looking at the sections next to it.
        if (area.hasStarted()) {
            for (int turns = 0; turns < DIRECTIONS.length; turns++) {
                goal = goalInSection(filter, area.next(turns), outOfReach, context);
                if (goal != null) return goal;
            }
        }

        int lowestLevel = lowestLevel();
        List<BlockPos> blocks = choose(nearest(filter, GOAL_CANDIDATES, lowestLevel, Integer.MAX_VALUE, null), outOfReach, context);
        return blocks.isEmpty() ? null : new MiningGoal(goalInBlocks(blocks, lowestLevel), blocks, false, null);
    }

    /** What to do next in this section, or null if there's nothing left to do in it. */
    private static @Nullable MiningGoal goalInSection(
        BlockOptionalMetaLookup filter, MiningArea area, Set<BlockPos> outOfReach, CalculationContext context
    ) {
        Integer level = area.hasStarted() ? Integer.valueOf(area.level()) : levelToClear(filter, area, Integer.MAX_VALUE);
        while (level != null) {
            MiningGoal goal = goalOnLevel(filter, area.onLevel(level), outOfReach, context);
            if (goal != null) return goal;
            level = levelToClear(filter, area, level - 1);
        }
        return null;
    }

    /** What to do next on this section's level, or null if there's nothing left to do on it. */
    private static @Nullable MiningGoal goalOnLevel(
        BlockOptionalMetaLookup filter, MiningArea area, Set<BlockPos> outOfReach, CalculationContext context
    ) {
        PlayerCache player = CACHE.getPlayerCache();
        int botX = MathHelper.floorI(player.getX());
        int botY = MathHelper.floorI(player.getY());
        int botZ = MathHelper.floorI(player.getZ());
        int level = area.level();
        boolean onLevel = botY == level;

        if (onLevel) {
            BlockPos overhead = blockToMineOverhead(filter, botX, botY, botZ, outOfReach, context);
            if (overhead != null) return new MiningGoal(null, List.of(overhead), true, area);
        }
        List<BlockPos> blocks = chooseOnLevel(nearest(filter, GOAL_CANDIDATES, level, level + 1, area), level, outOfReach, context);
        if (blocks.isEmpty()) return null;
        Goal goal = goalOnLevel(blocks, level, lowestLevel(), onLevel ? 1 : PRESSING_ON_WEIGHT);
        return new MiningGoal(goal, blocks, onLevel, area);
    }

    /**
     * The next block to mine straight above the bot, from where it stands, or null if there's nothing more
     * to do up there. That's the lowest block in the way, as long as there's something it's after at or
     * above it. It stops at anything it shouldn't mine rather than reaching past it, and that includes
     * what's holding up something that falls, since that would land on it.
     */
    private static @Nullable BlockPos blockToMineOverhead(
        BlockOptionalMetaLookup filter, int botX, int botY, int botZ, Set<BlockPos> outOfReach, CalculationContext context
    ) {
        BlockPos lowest = null;
        for (int height = 2; height < ROOM_HEIGHT; height++) {
            BlockPos pos = new BlockPos(botX, botY + height, botZ);
            Block block = World.getBlock(pos);
            if (block.isAir()) continue;
            if (outOfReach.contains(pos)
                || World.getBlock(pos.x(), pos.y() + 1, pos.z()).fallingBlock()
                || !RoomBeside.canMine(new int[]{pos.x(), pos.y(), pos.z()}, context)) {
                return null;
            }
            if (lowest == null) lowest = pos;
            if (filter.has(block)) {
                return new InteractWithProcess.BreakBlock(lowest.x(), lowest.y(), lowest.z(), true).canInteract() ? lowest : null;
            }
        }
        return null;
    }

    /**
     * The level to stand on to clear the highest layer worth mining, no higher than {@code highestY}, or null
     * if there isn't one. That's as low as can still reach that layer, unless what's down there is mostly
     * something else, as it would have to dig through that to get around.
     */
    private static @Nullable Integer levelToClear(BlockOptionalMetaLookup filter, MiningArea area, int highestY) {
        Integer top = highestLayerWorthMining(filter, area, highestY);
        if (top == null) return null;
        int lowestLevel = lowestLevel();
        for (int level = Math.max(lowestLevel, top - ROOM_HEIGHT + 1); level < top - 1; level++) {
            LayerCount feet = LayerCount.of(filter, area, level);
            LayerCount head = LayerCount.of(filter, area, level + 1);
            if (feet.wanted() + head.wanted() > feet.unwanted() + head.unwanted()) return level;
        }
        return Math.max(lowestLevel, top - 1);
    }

    /**
     * The highest layer of this section, no higher than {@code highestY} and not below the lowest level, with
     * more than a few of what it's after, making up at least as much as everything else solid there. Even
     * the commonest ores are nothing like that much of a layer, so this is also what tells a block that's
     * everywhere from one worth going to look for.
     *
     * @return null if there are none
     */
    static @Nullable Integer highestLayerWorthMining(BlockOptionalMetaLookup filter, MiningArea area, int highestY) {
        DimensionData dimension = World.getCurrentDimension();
        int minY = dimension.minY();
        int lowestLevel = lowestLevel();
        IntSet states = filter.getBlockStateIds();
        int y = Math.min(highestY, minY + dimension.height() - 1);
        while (y >= lowestLevel) {
            int section = (y - minY) >> 4;
            if (!sectionMightHold(area, section, states)) {
                y = minY + (section << 4) - 1;
                continue;
            }
            LayerCount layer = LayerCount.of(filter, area, y);
            if (layer.wanted() >= layer.unwanted() && layer.wanted() * 100 >= layer.cells() * LEAST_PERCENT_OF_A_LAYER) {
                return y;
            }
            y--;
        }
        return null;
    }

    private static boolean sectionMightHold(MiningArea area, int section, IntSet states) {
        for (int chunkX = (area.centreX() - area.radius()) >> 4; chunkX <= (area.centreX() + area.radius()) >> 4; chunkX++) {
            for (int chunkZ = (area.centreZ() - area.radius()) >> 4; chunkZ <= (area.centreZ() + area.radius()) >> 4; chunkZ++) {
                Chunk chunk = World.getChunk(chunkX, chunkZ);
                if (chunk != null && area.touchesChunk(chunkX << 4, chunkZ << 4) && mightHold(chunk.getSections()[section], states)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How much of a layer of a section is what it's after, and how much is anything else that isn't air. */
    private record LayerCount(int wanted, int unwanted, int cells) {
        static LayerCount of(BlockOptionalMetaLookup filter, MiningArea area, int y) {
            int wanted = 0;
            int unwanted = 0;
            int cells = 0;
            for (int x = area.centreX() - area.radius(); x <= area.centreX() + area.radius(); x++) {
                for (int z = area.centreZ() - area.radius(); z <= area.centreZ() + area.radius(); z++) {
                    if (!area.contains(x, z)) continue;
                    cells++;
                    Block block = World.getBlock(x, y, z);
                    if (filter.has(block)) {
                        wanted++;
                    } else if (!block.isAir()) {
                        unwanted++;
                    }
                }
            }
            return new LayerCount(wanted, unwanted, cells);
        }
    }

    /** Somewhere further off in this direction, to bring more chunks into range when none of them help. */
    static Goal exploringGoal(int direction) {
        PlayerCache player = CACHE.getPlayerCache();
        int[] heading = DIRECTIONS[Math.floorMod(direction, DIRECTIONS.length)];
        return new AboveLowestLevel(
            new GoalXZ(
                MathHelper.floorI(player.getX()) + heading[0] * EXPLORING_BLOCKS,
                MathHelper.floorI(player.getZ()) + heading[1] * EXPLORING_BLOCKS),
            lowestLevel(),
            PRESSING_ON_WEIGHT);
    }

    /** Only ones with a floor to stand on, as nothing else gets it onto the level there. */
    private static List<BlockPos> chooseOnLevel(
        List<BlockPos> candidates, int level, Set<BlockPos> outOfReach, CalculationContext context
    ) {
        return choose(
            candidates.stream().filter(pos -> MovementHelper.canWalkOn(pos.x(), level - 1, pos.z())).toList(),
            outOfReach,
            context);
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
     * height by stepping up onto the one below it would save breaking a block, but then it's working its
     * way up out of the level.
     */
    private static Goal goalOnLevel(List<BlockPos> blocks, int level, int lowestLevel, double weight) {
        return new AboveLowestLevel(
            new GoalComposite(blocks.stream()
                .map(pos -> new GoalBlock(pos.x(), level, pos.z()))
                .distinct()
                .toArray(Goal[]::new)),
            lowestLevel,
            weight);
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
