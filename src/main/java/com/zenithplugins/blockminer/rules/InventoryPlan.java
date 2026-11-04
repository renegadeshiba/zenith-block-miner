package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decisions about the inventory while the bot keeps junk out of it. Every empty slot holds one target
 * item, so with nowhere for anything else to go, junk stays on the ground and only target items can be
 * picked up. That means free slots can't say when it's time to store, and the bot has to hold on to a
 * stack of its target items to fill the slots again afterwards.
 */
public final class InventoryPlan {

    private static final int FULL_STACK = 64;

    /** A slot holding something, and what's in it. */
    public record SlotStack(int slot, ItemStack stack) {
    }

    private InventoryPlan() {
    }

    private static boolean isTarget(SlotStack entry, Set<String> targetItems) {
        ItemData item = ItemRegistry.REGISTRY.get(entry.stack().getId());
        return item != null && targetItems.contains(item.name());
    }

    private static int amountOf(SlotStack entry) {
        return entry.stack().getAmount();
    }

    private static SlotStack largest(List<SlotStack> stacks) {
        return stacks.stream().max(Comparator.comparingInt(InventoryPlan::amountOf)).orElseThrow();
    }

    /**
     * The slots to put in a shulker box. A stack of each target item stays behind to fill slots with.
     * So does one stack of a block the pathfinder can bridge with, since it can't pick up any more.
     * With crafting on, up to 8 extra of a material stay too, as they aren't enough to craft with yet.
     */
    public static List<Integer> slotsToStore(
        List<SlotStack> inventory,
        Set<String> targetItems,
        Collection<String> keepItems,
        Collection<String> bridgingItems,
        boolean crafting
    ) {
        Map<Integer, List<SlotStack>> targetsByItem = new HashMap<>();
        List<SlotStack> bridging = new ArrayList<>();
        List<Integer> toStore = new ArrayList<>();
        for (SlotStack entry : inventory) {
            if (!StorageRules.isStorable(entry.stack(), keepItems)) continue;
            String name = ItemRegistry.REGISTRY.get(entry.stack().getId()).name();
            if (targetItems.contains(name)) {
                targetsByItem.computeIfAbsent(entry.stack().getId(), id -> new ArrayList<>()).add(entry);
            } else if (bridgingItems.contains(name)) {
                bridging.add(entry);
            } else {
                toStore.add(entry.slot());
            }
        }

        targetsByItem.forEach((itemId, stacks) -> {
            ItemData item = ItemRegistry.REGISTRY.get(itemId);
            int total = stacks.stream().mapToInt(InventoryPlan::amountOf).sum();
            int spareMaterial = crafting && CompactRecipes.isInput(item.name()) ? CompactRecipes.INPUT_COUNT - 1 : 0;
            if (total > item.stackSize() + spareMaterial) addAllButLargest(stacks, toStore);
        });
        if (!bridging.isEmpty()) addAllButLargest(bridging, toStore);
        return toStore;
    }

    private static void addAllButLargest(List<SlotStack> stacks, List<Integer> toStore) {
        SlotStack keep = largest(stacks);
        for (SlotStack entry : stacks) {
            if (entry != keep) toStore.add(entry.slot());
        }
    }

    /**
     * True when it's time to store: there's something to store, and either target items are about to
     * run out of room, or a target item with a full stack can't spread out because others are in the
     * way. Without anything to store, a trip wouldn't change anything.
     */
    public static boolean needsStoring(
        List<SlotStack> inventory,
        int emptySlots,
        Set<String> targetItems,
        int stacksOfRoom,
        boolean anythingToStore
    ) {
        if (!anythingToStore) return false;

        Map<Integer, Integer> totalByItem = new HashMap<>();
        Map<Integer, Integer> roomByItem = new HashMap<>();
        int totalRoom = emptySlots * FULL_STACK;
        for (SlotStack entry : inventory) {
            if (!isTarget(entry, targetItems)) continue;
            int itemId = entry.stack().getId();
            int room = ItemRegistry.REGISTRY.get(itemId).stackSize() - amountOf(entry);
            totalByItem.merge(itemId, amountOf(entry), Integer::sum);
            roomByItem.merge(itemId, room, Integer::sum);
            totalRoom += room;
        }
        if (totalRoom <= stacksOfRoom * FULL_STACK) return true;

        for (var total : totalByItem.entrySet()) {
            int stackSize = ItemRegistry.REGISTRY.get(total.getKey()).stackSize();
            int room = roomByItem.get(total.getKey()) + emptySlots * stackSize;
            if (total.getValue() >= stackSize && room <= stacksOfRoom * stackSize) return true;
        }
        return false;
    }

    /** True when target items can't be picked up at all, with nothing to store that would change that. */
    public static boolean hasNoRoom(
        List<SlotStack> inventory,
        int emptySlots,
        Set<String> targetItems,
        boolean anythingToStore
    ) {
        // An empty list with no empty slots just means the inventory hasn't loaded yet.
        if (anythingToStore || emptySlots > 0 || inventory.isEmpty()) return false;
        for (SlotStack entry : inventory) {
            if (!isTarget(entry, targetItems)) continue;
            if (amountOf(entry) < ItemRegistry.REGISTRY.get(entry.stack().getId()).stackSize()) return false;
        }
        return true;
    }

    /** How many empty slots can be filled, leaving {@code openSlots} empty, with one item left in each stack. */
    public static int fillableSlots(List<SlotStack> inventory, int emptySlots, Set<String> targetItems, int openSlots) {
        int spare = 0;
        for (SlotStack entry : inventory) {
            if (!isTarget(entry, targetItems)) continue;
            if (ItemRegistry.REGISTRY.get(entry.stack().getId()).stackSize() > 1) spare += amountOf(entry) - 1;
        }
        return Math.max(0, Math.min(emptySlots - openSlots, spare));
    }

    public static Set<String> heldItemNames(List<SlotStack> inventory) {
        Set<String> names = new HashSet<>();
        for (SlotStack entry : inventory) {
            ItemData item = ItemRegistry.REGISTRY.get(entry.stack().getId());
            if (item != null) names.add(item.name());
        }
        return names;
    }
}
