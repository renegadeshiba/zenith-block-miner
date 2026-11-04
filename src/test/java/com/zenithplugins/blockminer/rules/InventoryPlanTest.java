package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.InventoryPlan.SlotStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryPlanTest {

    private static final List<String> KEEP = List.of("ender_chest");
    private static final Set<String> BRIDGING = Set.of("netherrack", "cobblestone");
    private static final Set<String> DIAMONDS = Set.of("diamond", "diamond_ore");
    private static final int ROOM = 2;

    /** Stacks of these amounts, in slots numbered from {@code firstSlot}. */
    private static List<SlotStack> stacks(int firstSlot, ItemData item, int... amounts) {
        List<SlotStack> stacks = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            stacks.add(new SlotStack(firstSlot + i, new ItemStack(item.id(), amounts[i])));
        }
        return stacks;
    }

    private static List<SlotStack> all(List<SlotStack> first, List<SlotStack> second) {
        List<SlotStack> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static int[] repeated(int count, int amount) {
        int[] amounts = new int[count];
        java.util.Arrays.fill(amounts, amount);
        return amounts;
    }

    private static List<Integer> toStore(List<SlotStack> inventory, Set<String> targets, boolean crafting) {
        return InventoryPlan.slotsToStore(inventory, targets, KEEP, BRIDGING, crafting);
    }

    // ------------------------------------------------------------------------------------------
    // what gets stored
    // ------------------------------------------------------------------------------------------

    @Test
    void junkGetsStoredAndGearDoesNot() {
        List<SlotStack> inventory = all(
            stacks(0, ItemRegistry.ANDESITE, 64),
            all(stacks(1, ItemRegistry.NETHERITE_PICKAXE, 1), stacks(2, ItemRegistry.ENDER_CHEST, 3))
        );
        assertEquals(List.of(0), toStore(inventory, DIAMONDS, true));
    }

    @Test
    void aStackOfTheTargetItemStaysBehindAndTheRestIsStored() {
        List<SlotStack> inventory = stacks(0, ItemRegistry.ANCIENT_DEBRIS, 64, 64, 64, 10, 1, 1);
        List<Integer> stored = toStore(inventory, Set.of("ancient_debris"), false);

        assertEquals(5, stored.size());
        assertFalse(stored.contains(0));
    }

    @Test
    void nothingIsStoredFromLessThanAStack() {
        List<SlotStack> singles = stacks(0, ItemRegistry.ANCIENT_DEBRIS, repeated(30, 1));
        assertTrue(toStore(singles, Set.of("ancient_debris"), false).isEmpty());
    }

    @Test
    void theKeptStackOfAMaterialIsTheMaterialNotItsBlock() {
        List<SlotStack> inventory = all(stacks(0, ItemRegistry.DIAMOND, 64), stacks(1, ItemRegistry.DIAMOND_BLOCK, 20));
        assertEquals(List.of(1), toStore(inventory, DIAMONDS, true));
    }

    @Test
    void spareMaterialStaysForTheNextCraftButOnlyWithCraftingOn() {
        List<SlotStack> inventory = stacks(0, ItemRegistry.DIAMOND, 64, 8);
        assertTrue(toStore(inventory, DIAMONDS, true).isEmpty());
        assertEquals(List.of(1), toStore(inventory, DIAMONDS, false));
    }

    @Test
    void aSingleStackOfABridgingBlockStays() {
        List<SlotStack> inventory = all(stacks(0, ItemRegistry.NETHERRACK, 20, 64), stacks(2, ItemRegistry.COBBLESTONE, 10));
        List<Integer> stored = toStore(inventory, DIAMONDS, false);

        assertEquals(2, stored.size());
        assertFalse(stored.contains(1));
    }

    // ------------------------------------------------------------------------------------------
    // when to store
    // ------------------------------------------------------------------------------------------

    @Test
    void junkFillingTheInventoryTriggersStoring() {
        List<SlotStack> junk = stacks(0, ItemRegistry.NETHERRACK, repeated(30, 64));
        assertFalse(InventoryPlan.needsStoring(junk, 3, DIAMONDS, ROOM, true));
        assertTrue(InventoryPlan.needsStoring(junk, 2, DIAMONDS, ROOM, true));
    }

    @Test
    void aFullInventoryOfSinglesIsNotATriggerSinceTheyStillHaveRoom() {
        List<SlotStack> singles = stacks(0, ItemRegistry.DIAMOND, repeated(30, 1));
        assertFalse(InventoryPlan.needsStoring(singles, 0, DIAMONDS, ROOM, false));
        // A little junk that got in isn't worth a shulker box either.
        assertFalse(InventoryPlan.needsStoring(singles, 0, DIAMONDS, ROOM, true));
    }

    @Test
    void targetStacksNearlyFullTriggerStoring() {
        List<SlotStack> nearlyFull = stacks(0, ItemRegistry.DIAMOND, 64, 64, 64, 30, 40);
        assertTrue(InventoryPlan.needsStoring(nearlyFull, 0, DIAMONDS, ROOM, true));

        List<SlotStack> plentyLeft = stacks(0, ItemRegistry.DIAMOND, 64, 64, 1, 1, 1);
        assertFalse(InventoryPlan.needsStoring(plentyLeft, 0, DIAMONDS, ROOM, true));
    }

    @Test
    void aFullStackOfOneItemWithOthersInTheWayTriggersStoring() {
        Set<String> targets = Set.of("diamond", "coal");
        List<SlotStack> inventory = all(stacks(0, ItemRegistry.DIAMOND, 64), stacks(1, ItemRegistry.COAL, repeated(30, 1)));
        assertTrue(InventoryPlan.needsStoring(inventory, 0, targets, ROOM, true));
    }

    @Test
    void aSmallStackOfARareItemDoesNotTriggerStoring() {
        Set<String> targets = Set.of("diamond", "coal");
        List<SlotStack> inventory = all(stacks(0, ItemRegistry.DIAMOND, 3), stacks(1, ItemRegistry.COAL, repeated(30, 1)));
        assertFalse(InventoryPlan.needsStoring(inventory, 0, targets, ROOM, true));
    }

    @Test
    void withNothingToStoreATripWouldNotHelp() {
        List<SlotStack> nearlyFull = stacks(0, ItemRegistry.DIAMOND, 64, 64, 64);
        assertFalse(InventoryPlan.needsStoring(nearlyFull, 0, DIAMONDS, ROOM, false));
    }

    // ------------------------------------------------------------------------------------------
    // running out of room
    // ------------------------------------------------------------------------------------------

    @Test
    void gearFillingEverySlotLeavesNoRoom() {
        List<SlotStack> gear = stacks(0, ItemRegistry.NETHERITE_PICKAXE, repeated(36, 1));
        assertTrue(InventoryPlan.hasNoRoom(gear, 0, DIAMONDS, false));
        assertFalse(InventoryPlan.hasNoRoom(gear, 1, DIAMONDS, false));
    }

    @Test
    void anInventoryThatHasNotLoadedIsNotFull() {
        assertFalse(InventoryPlan.hasNoRoom(List.of(), 0, DIAMONDS, false));
    }

    @Test
    void aFullTargetStackWithNowhereToGoLeavesNoRoom() {
        assertTrue(InventoryPlan.hasNoRoom(stacks(0, ItemRegistry.DIAMOND, 64), 0, DIAMONDS, false));
        assertFalse(InventoryPlan.hasNoRoom(stacks(0, ItemRegistry.DIAMOND, 63), 0, DIAMONDS, false));
        assertFalse(InventoryPlan.hasNoRoom(stacks(0, ItemRegistry.DIAMOND, 64), 0, DIAMONDS, true));
    }

    // ------------------------------------------------------------------------------------------
    // filling slots
    // ------------------------------------------------------------------------------------------

    @Test
    void slotsCanBeFilledWithAllButOneOfEachStack() {
        List<SlotStack> inventory = stacks(0, ItemRegistry.DIAMOND, 5);
        assertEquals(4, InventoryPlan.fillableSlots(inventory, 30, DIAMONDS, 0));
        assertEquals(0, InventoryPlan.fillableSlots(stacks(0, ItemRegistry.DIAMOND, 1), 30, DIAMONDS, 0));
    }

    @Test
    void fillingStopsAtTheEmptySlotsAndLeavesOpenOnesAlone() {
        List<SlotStack> inventory = stacks(0, ItemRegistry.DIAMOND, 64);
        assertEquals(30, InventoryPlan.fillableSlots(inventory, 30, DIAMONDS, 0));
        assertEquals(29, InventoryPlan.fillableSlots(inventory, 30, DIAMONDS, 1));
        assertEquals(0, InventoryPlan.fillableSlots(inventory, 1, DIAMONDS, 1));
    }

    @Test
    void junkIsNeverUsedToFillSlots() {
        assertEquals(0, InventoryPlan.fillableSlots(stacks(0, ItemRegistry.NETHERRACK, 64), 30, DIAMONDS, 0));
    }
}
