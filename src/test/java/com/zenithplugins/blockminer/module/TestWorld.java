package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.cache.data.chunk.ChunkCache;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.dimension.DimensionRegistry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.PalettedWorldState;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static com.zenith.Globals.CACHE;

/** A made-up overworld held in ZenithProxy's real chunk cache, so its real code reads it like any other. */
final class TestWorld {

    private static final PalettedWorldState WORLD_STATE = new PalettedWorldState(24, 27914, 0, 65, 0);

    private final Long2ObjectOpenHashMap<Chunk> chunks;
    final int chunksFromCentre;

    /** Loads the chunks up to {@code chunksFromCentre} from chunk (0, 0) in each direction, all empty. */
    @SuppressWarnings("unchecked")
    TestWorld(int chunksFromCentre) throws ReflectiveOperationException {
        this.chunksFromCentre = chunksFromCentre;
        ChunkCache chunkCache = CACHE.getChunkCache();
        chunkCache.setCurrentDimension(DimensionRegistry.OVERWORLD.get());
        Field field = ChunkCache.class.getDeclaredField("cache");
        field.setAccessible(true);
        chunks = (Long2ObjectOpenHashMap<Chunk>) field.get(chunkCache);
        chunks.clear();
        for (int chunkX = -chunksFromCentre; chunkX <= chunksFromCentre; chunkX++) {
            for (int chunkZ = -chunksFromCentre; chunkZ <= chunksFromCentre; chunkZ++) {
                chunks.put(Chunk.chunkPosToLong(chunkX, chunkZ),
                    new Chunk(chunkX, chunkZ, new ChunkSection[24], 20, -4, new ArrayList<>(), null));
            }
        }
    }

    void clear() {
        chunks.clear();
    }

    int minX() {
        return -chunksFromCentre * 16;
    }

    int maxX() {
        return chunksFromCentre * 16 + 15;
    }

    void set(int x, int y, int z, Block block) {
        Chunk chunk = chunks.get(Chunk.chunkPosToLong(x >> 4, z >> 4));
        if (chunk == null) return;
        int index = chunk.getSectionIndex(y);
        ChunkSection section = chunk.getSections()[index];
        if (section == null) {
            section = new ChunkSection(WORLD_STATE);
            // A new section's palette is empty until something is set, so it can't even say it holds air.
            section.setBlock(0, 0, 0, BlockRegistry.AIR.minStateId());
            chunk.getSections()[index] = section;
        }
        section.setBlock(x & 15, y & 15, z & 15, block.minStateId());
    }

    /** Fills these heights across the whole world. */
    void layer(int minY, int maxY, Block block) {
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minX(); z <= maxX(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    set(x, y, z, block);
                }
            }
        }
    }

    static void putBotAt(double x, double y, double z) {
        CACHE.getPlayerCache().setX(x).setY(y).setZ(z);
    }
}
