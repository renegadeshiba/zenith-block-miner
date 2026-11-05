package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.InventoryPlan.SlotStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRulesTest {

    private static final List<String> KEEP = List.of("ender_chest", "crafting_table");

    private static ItemStack stack(ItemData item) {
        return new ItemStack(item.id(), 1);
    }

    private static ItemStack shulkerHolding(ItemStack... contents) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CONTAINER, Arrays.asList(contents));
        return new ItemStack(ItemRegistry.RED_SHULKER_BOX.id(), 1, components);
    }

    @Test
    void shulkerBoxesOfAnyColourCount() {
        assertTrue(StorageRules.isShulker(stack(ItemRegistry.SHULKER_BOX)));
        assertTrue(StorageRules.isShulker(stack(ItemRegistry.RED_SHULKER_BOX)));
        assertFalse(StorageRules.isShulker(stack(ItemRegistry.CHEST)));
        assertFalse(StorageRules.isShulker(null));
    }

    @Test
    void aShulkerWithNothingInItIsEmpty() {
        assertTrue(StorageRules.isEmptyShulker(stack(ItemRegistry.SHULKER_BOX)));
        assertTrue(StorageRules.isEmptyShulker(shulkerHolding()));
        assertTrue(StorageRules.isEmptyShulker(shulkerHolding((ItemStack) null)));
        assertFalse(StorageRules.isEmptyShulker(shulkerHolding(stack(ItemRegistry.DIAMOND))));
        assertFalse(StorageRules.isEmptyShulker(stack(ItemRegistry.CHEST)));
    }

    @Test
    void contentsSkipEmptySlots() {
        List<ItemStack> contents = StorageRules.contents(shulkerHolding(null, stack(ItemRegistry.DIAMOND), null));
        assertEquals(1, contents.size());
        assertEquals(ItemRegistry.DIAMOND.id(), contents.getFirst().getId());
    }

    // ----------------------------------------------------------------------------------------
    // which shulker box to fill next
    // ----------------------------------------------------------------------------------------

    private static final List<ItemStack> STORING_COBBLED = List.of(stack(ItemRegistry.COBBLED_DEEPSLATE));
    private static final Set<String> COBBLED_ONLY = Set.of("cobbled_deepslate");

    private static ItemStack amount(ItemData item, int amount) {
        return new ItemStack(item.id(), amount);
    }

    private static ItemStack[] stacks(int count, ItemData item, int amount) {
        ItemStack[] stacks = new ItemStack[count];
        Arrays.fill(stacks, amount(item, amount));
        return stacks;
    }

    /** What's in the ender chest, each in the slot matching its place in the list. */
    private static List<SlotStack> chest(ItemStack... contents) {
        List<SlotStack> chest = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) chest.add(new SlotStack(slot, contents[slot]));
        return chest;
    }

    @Test
    void tellsWhetherAStackWouldFitInAShulkerBox() {
        ItemStack cobbled = stack(ItemRegistry.COBBLED_DEEPSLATE);

        assertTrue(StorageRules.hasRoomFor(List.of(), cobbled), "empty");
        assertTrue(StorageRules.hasRoomFor(List.of(stacks(26, ItemRegistry.TUFF, 64)), cobbled), "a slot left");
        assertFalse(StorageRules.hasRoomFor(List.of(stacks(27, ItemRegistry.TUFF, 64)), cobbled), "no slot left");
        assertFalse(StorageRules.hasRoomFor(List.of(stacks(27, ItemRegistry.COBBLED_DEEPSLATE, 64)), cobbled), "all full");

        List<ItemStack> oneNotFull = new ArrayList<>(List.of(stacks(26, ItemRegistry.COBBLED_DEEPSLATE, 64)));
        oneNotFull.add(amount(ItemRegistry.COBBLED_DEEPSLATE, 63));
        assertTrue(StorageRules.hasRoomFor(oneNotFull, cobbled), "room on top of a stack");
        assertFalse(StorageRules.hasRoomFor(oneNotFull, stack(ItemRegistry.TUFF)), "but not for anything else");
    }

    @Test
    void takesOneItsStartedBeforeAnEmptyOne() {
        List<SlotStack> chest = chest(
            stack(ItemRegistry.SHULKER_BOX),
            shulkerHolding(amount(ItemRegistry.COBBLED_DEEPSLATE, 64)));

        assertEquals(1, StorageRules.shulkerToFill(chest, STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void takesTheFullestOfTheOnesItsStarted() {
        List<SlotStack> chest = chest(
            shulkerHolding(amount(ItemRegistry.COBBLED_DEEPSLATE, 64)),
            shulkerHolding(stacks(20, ItemRegistry.COBBLED_DEEPSLATE, 64)),
            shulkerHolding(stacks(3, ItemRegistry.COBBLED_DEEPSLATE, 64)));

        assertEquals(1, StorageRules.shulkerToFill(chest, STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void passesOverOnesThatAreFull() {
        List<SlotStack> chest = chest(
            shulkerHolding(stacks(27, ItemRegistry.COBBLED_DEEPSLATE, 64)),
            stack(ItemRegistry.SHULKER_BOX));

        assertEquals(1, StorageRules.shulkerToFill(chest, STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void takesAnEmptyOneOnceTheOnesItsStartedAreFull() {
        List<SlotStack> chest = chest(
            shulkerHolding(stacks(27, ItemRegistry.COBBLED_DEEPSLATE, 64)),
            shulkerHolding(stacks(27, ItemRegistry.COBBLED_DEEPSLATE, 64)),
            stack(ItemRegistry.RED_SHULKER_BOX),
            stack(ItemRegistry.SHULKER_BOX));

        assertEquals(2, StorageRules.shulkerToFill(chest, STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void neverTouchesAToolShulkerBoxHoweverMuchRoomItHas() {
        List<SlotStack> chest = chest(
            shulkerHolding(stack(ItemRegistry.NETHERITE_PICKAXE), stack(ItemRegistry.NETHERITE_PICKAXE)),
            stack(ItemRegistry.SHULKER_BOX));

        assertEquals(1, StorageRules.shulkerToFill(chest, STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void leavesAloneAnythingWithSomethingElseInIt() {
        List<SlotStack> chest = chest(
            shulkerHolding(amount(ItemRegistry.COBBLED_DEEPSLATE, 64), stack(ItemRegistry.DIAMOND)),
            shulkerHolding(amount(ItemRegistry.OBSIDIAN, 64)),
            stack(ItemRegistry.SHULKER_BOX));

        assertEquals(2, StorageRules.shulkerToFill(chest, STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void comesBackWithNothingIfThereAreNoneToFill() {
        assertEquals(-1, StorageRules.shulkerToFill(chest(), STORING_COBBLED, COBBLED_ONLY));
        assertEquals(-1, StorageRules.shulkerToFill(
            chest(
                shulkerHolding(stack(ItemRegistry.NETHERITE_PICKAXE)),
                shulkerHolding(stacks(27, ItemRegistry.COBBLED_DEEPSLATE, 64)),
                stack(ItemRegistry.DIAMOND),
                null),
            STORING_COBBLED, COBBLED_ONLY));
    }

    @Test
    void onlyTakesAStartedOneIfWhatItsStoringWouldFit() {
        // Every slot taken, with room left on top of the tuff but not the cobbled deepslate it's storing.
        ItemStack[] contents = stacks(27, ItemRegistry.COBBLED_DEEPSLATE, 64);
        contents[26] = amount(ItemRegistry.TUFF, 10);
        List<SlotStack> chest = chest(shulkerHolding(contents), stack(ItemRegistry.SHULKER_BOX));
        Set<String> own = Set.of("cobbled_deepslate", "tuff");

        assertEquals(1, StorageRules.shulkerToFill(chest, STORING_COBBLED, own));
        assertEquals(0, StorageRules.shulkerToFill(chest, List.of(stack(ItemRegistry.TUFF)), own));
    }

    @Test
    void aStartedOneCanHoldWhatItsAfterAlongWithWhatThatCraftsInto() {
        List<SlotStack> chest = chest(
            stack(ItemRegistry.SHULKER_BOX),
            shulkerHolding(amount(ItemRegistry.DIAMOND_BLOCK, 64), amount(ItemRegistry.DIAMOND, 5)));

        assertEquals(1, StorageRules.shulkerToFill(
            chest, List.of(stack(ItemRegistry.DIAMOND_BLOCK)), Set.of("diamond", "diamond_block")));
    }

    @Test
    void minedItemsAreStorable() {
        assertTrue(StorageRules.isStorable(stack(ItemRegistry.NETHERRACK), KEEP));
        assertTrue(StorageRules.isStorable(stack(ItemRegistry.DIAMOND), KEEP));
        assertTrue(StorageRules.isStorable(stack(ItemRegistry.DIAMOND_BLOCK), KEEP));
        assertTrue(StorageRules.isStorable(stack(ItemRegistry.ANCIENT_DEBRIS), KEEP));
    }

    @Test
    void whatTheBotNeedsStaysWithIt() {
        assertFalse(StorageRules.isStorable(stack(ItemRegistry.ENDER_CHEST), KEEP));
        assertFalse(StorageRules.isStorable(stack(ItemRegistry.NETHERITE_PICKAXE), KEEP));
        assertFalse(StorageRules.isStorable(stack(ItemRegistry.ELYTRA), KEEP));
        assertFalse(StorageRules.isStorable(stack(ItemRegistry.COOKED_BEEF), KEEP));
        assertFalse(StorageRules.isStorable(stack(ItemRegistry.SHULKER_BOX), KEEP));
    }
}
