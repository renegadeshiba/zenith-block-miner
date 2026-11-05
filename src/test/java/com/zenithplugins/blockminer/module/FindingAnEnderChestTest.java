package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.zenith.Globals.CACHE;
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
        carrying(false);
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
    }

    private static void carrying(boolean carrying) {
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().setItemStack(9,
            carrying ? new ItemStack(ItemRegistry.ENDER_CHEST.id(), 1) : Container.EMPTY_STACK);
    }

    @Test
    void usesTheNearestOne() {
        world.enderChestAt(30, -30, 3);
        world.enderChestAt(3, -40, 3);

        assertArrayEquals(new int[]{3, -40, 3}, bot.nearestEnderChest());
    }

    @Test
    void canUseOneOnTheLowestLevel() {
        world.enderChestAt(3, -58, 3);

        assertArrayEquals(new int[]{3, -58, 3}, bot.nearestEnderChest());
    }

    @Test
    void ignoresOneBelowTheLowestLevel() {
        world.enderChestAt(3, -59, 3);

        assertNull(bot.nearestEnderChest());
    }

    @Test
    void goesFurtherForOneRatherThanBelowTheLowestLevel() {
        world.enderChestAt(3, -59, 3);
        world.enderChestAt(30, -30, 3);

        assertArrayEquals(new int[]{30, -30, 3}, bot.nearestEnderChest());
    }

    @Test
    void placesAnotherRatherThanGoBackUpToALevelItsFinishedWith() {
        carrying(true);
        world.enderChestAt(3, -39, 3);

        assertNull(bot.nearestEnderChest());
    }

    @Test
    void usesOneOnALevelItsFinishedWithIfItHasNoneToPlace() {
        world.enderChestAt(3, -39, 3);

        assertArrayEquals(new int[]{3, -39, 3}, bot.nearestEnderChest());
    }

    @Test
    void usesOneItCanStillGetToWithOneToPlace() {
        carrying(true);
        world.enderChestAt(3, -43, 3);

        assertArrayEquals(new int[]{3, -43, 3}, bot.nearestEnderChest());
    }

    @Test
    void goesFurtherForOneOnItsLevelThanOneOnALevelItsFinishedWith() {
        carrying(true);
        world.enderChestAt(3, -39, 3);
        world.enderChestAt(30, -45, 3);

        assertArrayEquals(new int[]{30, -45, 3}, bot.nearestEnderChest());
    }
}
