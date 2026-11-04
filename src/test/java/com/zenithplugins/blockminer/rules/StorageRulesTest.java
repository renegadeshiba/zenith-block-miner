package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
