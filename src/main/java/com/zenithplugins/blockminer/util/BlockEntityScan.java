package com.zenithplugins.blockminer.util;

import com.zenith.cache.data.chunk.Chunk;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;

import java.util.ArrayList;
import java.util.List;

import static com.zenith.Globals.CACHE;

/**
 * Finds block entities in the chunks ZenithProxy currently has loaded. Zenith keeps each chunk's
 * block entity list up to date as blocks change, so this is far cheaper than scanning block states.
 */
public final class BlockEntityScan {

    private BlockEntityScan() {
    }

    /** {x, y, z} of every loaded block entity of this type. Callers should confirm the block first. */
    public static List<int[]> find(BlockEntityType type) {
        List<Chunk> chunks;
        try {
            chunks = new ArrayList<>(CACHE.getChunkCache().getCache().values());
        } catch (RuntimeException e) {
            // The map is owned by the network thread; a concurrent chunk load can trip the copy.
            return List.of();
        }

        List<int[]> hits = new ArrayList<>();
        for (Chunk chunk : chunks) {
            var blockEntities = chunk.getBlockEntities();
            synchronized (blockEntities) {
                for (BlockEntityInfo info : blockEntities) {
                    if (info.getType() != type) continue;
                    // Block entity x/z are stored relative to their chunk.
                    hits.add(new int[]{
                        (chunk.getX() << 4) + (info.getX() & 15),
                        info.getY(),
                        (chunk.getZ() << 4) + (info.getZ() & 15)
                    });
                }
            }
        }
        return hits;
    }
}
