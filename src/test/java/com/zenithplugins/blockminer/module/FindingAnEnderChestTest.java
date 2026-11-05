package com.zenithplugins.blockminer.module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The bot doesn't go below the lowest level for anything, and that includes an ender chest it left there. */
class FindingAnEnderChestTest {

    private TestWorld world;
    private Bot bot;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        world = new TestWorld(4);
        bot = new Bot(new BlockMinerModule(), () -> true);
        TestWorld.putBotAt(0.5, -45, 0.5);
    }

    @AfterEach
    void tearDown() {
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
    }

    @Test
    void usesTheNearestOne() {
        world.enderChestAt(30, -30, 3);
        world.enderChestAt(3, -40, 3);

        assertArrayEquals(new int[]{3, -40, 3}, bot.nearestEnderChest());
    }

    @Test
    void canUseOneOnTheLowestLevel() {
        world.enderChestAt(3, -50, 3);

        assertArrayEquals(new int[]{3, -50, 3}, bot.nearestEnderChest());
    }

    @Test
    void ignoresOneBelowTheLowestLevel() {
        world.enderChestAt(3, -51, 3);

        assertNull(bot.nearestEnderChest());
    }

    @Test
    void goesFurtherForOneRatherThanBelowTheLowestLevel() {
        world.enderChestAt(3, -51, 3);
        world.enderChestAt(30, -30, 3);

        assertArrayEquals(new int[]{30, -30, 3}, bot.nearestEnderChest());
    }
}
