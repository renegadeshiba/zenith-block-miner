package com.zenithplugins.blockminer.module;

import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the bot has put blocks down, so that it doesn't go back and mine them. It builds with what it's been
 * mining, so to the rest of the plugin they look just like what it's after, and it would pull down whatever it
 * had just built to get somewhere.
 *
 * <p>Clicks come in on a network thread and get looked at from the client tick, hence the concurrent set.
 */
final class PlacedBlocks {

    // Far more than it's likely to ever build, and forgetting them is no disaster: it just mines them.
    private static final int MOST_TO_REMEMBER = 20_000;

    private final Set<BlockPos> positions = ConcurrentHashMap.newKeySet();

    /**
     * The bot has right-clicked this face of a block, which puts what it's holding in the space next to that
     * face. Opening a container looks just the same, but only something it can then see a block in matters.
     */
    void clicked(int x, int y, int z, Direction face) {
        BlockPos next = next(x, y, z, face);
        Block block = World.getBlock(next);
        // Something there already isn't something it's about to put there.
        if (!block.replaceable()) return;
        if (positions.size() >= MOST_TO_REMEMBER) positions.clear();
        positions.add(next);
    }

    private static BlockPos next(int x, int y, int z, Direction face) {
        return switch (face) {
            case DOWN -> new BlockPos(x, y - 1, z);
            case UP -> new BlockPos(x, y + 1, z);
            case NORTH -> new BlockPos(x, y, z - 1);
            case SOUTH -> new BlockPos(x, y, z + 1);
            case WEST -> new BlockPos(x - 1, y, z);
            case EAST -> new BlockPos(x + 1, y, z);
        };
    }

    Set<BlockPos> positions() {
        return positions;
    }
}
