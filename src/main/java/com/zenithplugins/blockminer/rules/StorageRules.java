package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.ItemUtil;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** What counts as a shulker box, what's inside one, and what can be stored at all. */
public final class StorageRules {

    /**
     * What mining turns up that nobody wants, which gets thrown away rather than stored. Anything not
     * on this list gets stored, since there's no telling whether it matters. An item the user asked
     * for, or put on the keep list, is never treated as junk.
     */
    private static final Set<String> KNOWN_JUNK = Set.of(
        "cobblestone", "stone", "cobbled_deepslate", "deepslate", "tuff", "andesite", "diorite", "granite",
        "calcite", "dripstone_block", "pointed_dripstone", "gravel", "flint", "dirt", "coarse_dirt", "sand",
        "red_sand", "sandstone", "red_sandstone", "netherrack", "blackstone", "basalt", "smooth_basalt",
        "soul_sand", "soul_soil", "magma_block", "end_stone"
    );

    private StorageRules() {
    }

    public static boolean isKnownJunk(String itemName) {
        return KNOWN_JUNK.contains(itemName);
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
