package com.zenithplugins.blockminer.module;

import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedBlocksTest {

    private TestWorld world;
    private PlacedBlocks placed;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        world = new TestWorld(1);
        placed = new PlacedBlocks();
    }

    @AfterEach
    void tearDown() {
        world.clear();
    }

    @Test
    void aBlockGoesNextToTheFaceThatWasClicked() {
        placed.clicked(5, 20, 5, Direction.UP);
        placed.clicked(5, 30, 5, Direction.DOWN);
        placed.clicked(5, 40, 5, Direction.NORTH);
        placed.clicked(5, 50, 5, Direction.SOUTH);
        placed.clicked(5, 60, 5, Direction.WEST);
        placed.clicked(5, 70, 5, Direction.EAST);

        assertEquals(
            Set.of(
                new BlockPos(5, 21, 5),
                new BlockPos(5, 29, 5),
                new BlockPos(5, 40, 4),
                new BlockPos(5, 50, 6),
                new BlockPos(4, 60, 5),
                new BlockPos(6, 70, 5)),
            placed.positions());
    }

    @Test
    void whatsAlreadyThereWasntPlacedByTheBot() {
        // Like clicking on a chest to open it, with a block on the other side of the face it clicked.
        world.set(5, 21, 5, BlockRegistry.DEEPSLATE);

        placed.clicked(5, 20, 5, Direction.UP);

        assertTrue(placed.positions().isEmpty());
    }

    @Test
    void doesntHoldOnToMoreThanItShould() {
        for (int i = 0; i < 20_000; i++) placed.clicked(i, 100, 0, Direction.UP);
        assertEquals(20_000, placed.positions().size());

        placed.clicked(0, 200, 0, Direction.UP);

        assertEquals(Set.of(new BlockPos(0, 201, 0)), placed.positions());
    }
}
