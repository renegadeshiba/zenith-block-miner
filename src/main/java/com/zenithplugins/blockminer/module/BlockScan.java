package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.cache.data.PlayerCache;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalComposite;
import com.zenith.feature.pathfinder.goals.GoalTwoBlocks;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.process.MineProcess;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
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
import java.util.stream.IntStream;

import static com.zenith.Globals.CACHE;

/**
 * Looks through the loaded chunks for blocks while holding on to very little.
 *
 * <p>Zenith's miner can't be left alone with a block that's everywhere. Its search only stops early
 * once it finds a match within 10 blocks of the bot's height, and until then it keeps every match in
 * every loaded chunk in memory. Asked for deepslate by a bot standing above the deepslate layer, that's
 * millions of positions, every few seconds, until the whole proxy runs out of memory. A plugin can't
 * limit that search, but it can see that coming with {@link #zenithSearchStaysSmall}, and can use
 * {@link #walkToNearest} to get the bot to where it won't happen.
 */
final class BlockScan {

    // What MineProcess gives WorldScanner.scanChunkRadius. BlockScanTest holds the counting below to
    // the real search, so a Zenith update that changes how it works shows up when this is next built.
    private static final int ZENITH_WANTS = 64;
    private static final int ZENITH_HEIGHT_RANGE = 10;
    private static final int ZENITH_CHUNK_RADIUS = 32;

    // Zenith's search finds up to about 60,000 when the bot is in among the blocks: 25 layers of 9 chunks.
    // This many costs it around 15 MB, for as long as it takes to sort through them.
    static final int ZENITH_SEARCH_TOO_BIG = 100_000;

    // Enough to still have choices after leaving out the ones that wouldn't be safe to break. The
    // pathfinder gets as many as Zenith's miner gives it.
    private static final int WALK_CANDIDATES = 256;
    private static final int WALK_GOALS = 64;
    private static final int MAX_CHUNKS_AWAY = 32;

    private BlockScan() {
    }

    /** False if Zenith's miner, searching from where the bot is now, would collect a dangerous number of blocks. */
    static boolean zenithSearchStaysSmall(BlockOptionalMetaLookup filter) {
        return zenithSearchSize(filter, ZENITH_SEARCH_TOO_BIG) < ZENITH_SEARCH_TOO_BIG;
    }

    /**
     * How many positions Zenith's search would hold in memory if it ran now, giving up soon after
     * reaching {@code limit}. Goes through the chunks just as {@code WorldScanner.scanChunkRadius} does,
     * but only counts. That's what stops counting them being as dangerous as collecting them.
     */
    static int zenithSearchSize(BlockOptionalMetaLookup filter, int limit) {
        IntSet states = filter.getBlockStateIds();
        if (states.isEmpty()) return 0;

        PlayerCache player = CACHE.getPlayerCache();
        DimensionData dimension = World.getCurrentDimension();
        int centreChunkX = MathHelper.floorI(player.getX()) >> 4;
        int centreChunkZ = MathHelper.floorI(player.getZ()) >> 4;
        int botHeight = MathHelper.floorI(player.getY()) - dimension.minY();
        int[] sections = sectionsNearestFirst(dimension, botHeight >> 4);

        int[] found = {0};
        boolean foundNearBotHeight = false;
        for (int distanceSq = 0; ; distanceSq++) {
            boolean ringHasChunks = false;
            boolean ringIsLoaded = false;
            int reach = (int) Math.sqrt(distanceSq);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dz * dz != distanceSq) continue;
                    ringHasChunks = true;
                    Chunk chunk = World.getChunk(centreChunkX + dx, centreChunkZ + dz);
                    if (chunk == null) continue;
                    ringIsLoaded = true;
                    if (countChunk(chunk, sections, botHeight, states, found, limit)) foundNearBotHeight = true;
                    if (found[0] >= limit) return found[0];
                }
            }
            if (ringHasChunks && !ringIsLoaded) return found[0];
            if (found[0] >= ZENITH_WANTS
                && (distanceSq > ZENITH_CHUNK_RADIUS * ZENITH_CHUNK_RADIUS || (distanceSq > 1 && foundNearBotHeight))) {
                return found[0];
            }
        }
    }

    /** Returns true if this chunk gave Zenith's search what it wants, a match near the bot's height. */
    private static boolean countChunk(Chunk chunk, int[] sections, int botHeight, IntSet states, int[] found, int limit) {
        ChunkSection[] chunkSections = chunk.getSections();
        boolean foundNearBotHeight = false;
        for (int index : sections) {
            ChunkSection section = chunkSections[index];
            if (!mightHold(section, states)) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!states.contains(section.getBlock(x, y, z))) continue;
                        if (found[0] >= ZENITH_WANTS) {
                            if (Math.abs((index << 4 | y) - botHeight) < ZENITH_HEIGHT_RANGE) {
                                foundNearBotHeight = true;
                            } else if (foundNearBotHeight) {
                                return true;
                            }
                        }
                        found[0]++;
                    }
                }
            }
            if (found[0] >= limit) break;
        }
        return foundNearBotHeight;
    }

    /**
     * Somewhere to send the pathfinder: to stand in one of the blocks closest to the bot, which means
     * breaking it. Null if there's nothing it could break in the loaded chunks.
     */
    static @Nullable Goal walkToNearest(BlockOptionalMetaLookup filter) {
        CalculationContext context = new CalculationContext();
        Goal[] goals = nearest(filter, WALK_CANDIDATES).stream()
            .filter(pos -> MineProcess.plausibleToBreak(context, pos))
            .limit(WALK_GOALS)
            .map(GoalTwoBlocks::new)
            .toArray(Goal[]::new);
        return goals.length == 0 ? null : new GoalComposite(goals);
    }

    /**
     * The blocks closest to the bot, closest first, up to {@code count}. Never holds on to more than
     * that, however many there are, and doesn't read further out than could change the answer.
     */
    static List<BlockPos> nearest(BlockOptionalMetaLookup filter, int count) {
        PlayerCache player = CACHE.getPlayerCache();
        DimensionData dimension = World.getCurrentDimension();
        ClosestBlocks closest = new ClosestBlocks(
            MathHelper.floorI(player.getX()), MathHelper.floorI(player.getY()), MathHelper.floorI(player.getZ()),
            filter.getBlockStateIds(), count);
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
        final PriorityQueue<BlockPos> furthestOnTop;

        ClosestBlocks(int botX, int botY, int botZ, IntSet states, int count) {
            this.botX = botX;
            this.botY = botY;
            this.botZ = botZ;
            this.states = states;
            this.count = count;
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
                if (cantImproveOn(baseX, baseY, baseZ)) continue;
                ChunkSection section = chunkSections[index];
                if (!mightHold(section, states)) continue;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (states.contains(section.getBlock(x, y, z))) offer(baseX | x, baseY + y, baseZ | z);
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

        private static int distanceToSection(int coordinate, int base) {
            if (coordinate < base) return base - coordinate;
            return Math.max(0, coordinate - (base + 15));
        }

        private void offer(int x, int y, int z) {
            // Standing in it already, so the walk would end at once without breaking it.
            if (x == botX && z == botZ && (y == botY || y == botY + 1)) return;
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
