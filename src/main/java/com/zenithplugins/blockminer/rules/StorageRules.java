package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.ItemUtil;
import com.zenithplugins.blockminer.rules.InventoryPlan.SlotStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** What counts as a shulker box, what's inside one, and what can be stored at all. */
public final class StorageRules {

    private static final int SHULKER_SLOTS = 27;

    private StorageRules() {
    }

    public static boolean isShulkerName(String itemName) {
        return itemName.endsWith("shulker_box");
    }

    public static boolean isShulker(@Nullable ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return false;
        ItemData item = ItemRegistry.REGISTRY.get(stack.getId());
        return item != null && isShulkerName(item.name());
    }

    /** The non-empty stacks inside a shulker box item. */
    public static List<ItemStack> contents(ItemStack shulker) {
        List<ItemStack> contents = new ArrayList<>();
        List<ItemStack> stored = shulker.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
        if (stored == null) return contents;
        for (ItemStack stack : stored) {
            if (stack != null && stack.getAmount() > 0) contents.add(stack);
        }
        return contents;
    }

    public static boolean isEmptyShulker(@Nullable ItemStack stack) {
        return isShulker(stack) && contents(stack).isEmpty();
    }

    /** True if some of this stack would fit alongside these, in an empty slot or topping up a matching stack. */
    public static boolean hasRoomFor(List<ItemStack> contents, ItemStack stack) {
        if (contents.size() < SHULKER_SLOTS) return true;
        ItemData item = ItemRegistry.REGISTRY.get(stack.getId());
        if (item == null) return false;
        for (ItemStack inside : contents) {
            if (inside.getId() == stack.getId() && inside.getAmount() < item.stackSize()) return true;
        }
        return false;
    }

    /**
     * The shulker box in the ender chest to fill next, or -1 if there's none. One it's already started
     * comes first, fullest first, so that it doesn't leave a trail of part-full ones behind it. A box only
     * counts as started if it holds nothing but {@code ownItems}, which keeps it out of anything else
     * stored in there, like a tool shulker box, and has room for something in {@code storing}.
     */
    public static int shulkerToFill(List<SlotStack> chest, List<ItemStack> storing, Set<String> ownItems) {
        int fullestStarted = -1;
        int mostItems = -1;
        int firstEmpty = -1;
        for (SlotStack entry : chest) {
            if (isEmptyShulker(entry.stack())) {
                if (firstEmpty == -1) firstEmpty = entry.slot();
            } else if (isShulker(entry.stack())) {
                List<ItemStack> contents = contents(entry.stack());
                if (!holdsOnly(contents, ownItems)) continue;
                if (storing.stream().noneMatch(stack -> hasRoomFor(contents, stack))) continue;
                int items = contents.stream().mapToInt(ItemStack::getAmount).sum();
                if (items > mostItems) {
                    mostItems = items;
                    fullestStarted = entry.slot();
                }
            }
        }
        return fullestStarted != -1 ? fullestStarted : firstEmpty;
    }

    private static boolean holdsOnly(List<ItemStack> contents, Set<String> itemNames) {
        for (ItemStack stack : contents) {
            ItemData item = ItemRegistry.REGISTRY.get(stack.getId());
            if (item == null || !itemNames.contains(item.name())) return false;
        }
        return true;
    }

    /**
     * False for what always stays with the bot: gear (anything with durability), food, shulker boxes
     * and the keep list.
     */
    public static boolean isStorable(ItemStack stack, Collection<String> keepItems) {
        ItemData item = ItemRegistry.REGISTRY.get(stack.getId());
        if (item == null || isShulker(stack) || keepItems.contains(item.name())) return false;
        if (ItemUtil.getMaxDamage(stack) > 0) return false;
        return item.components() == null || !item.components().contains(DataComponentTypes.FOOD);
    }
}
