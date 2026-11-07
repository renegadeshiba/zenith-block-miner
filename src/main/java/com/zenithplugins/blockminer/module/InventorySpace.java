package com.zenithplugins.blockminer.module;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.InventoryPlan;
import com.zenithplugins.blockminer.rules.InventoryPlan.SlotStack;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.zenith.Globals.CONFIG;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Keeps junk out by filling every empty slot with one target item: with nowhere else to go, the only
 * thing the bot can pick up is more of what it's mining. Errands need a slot now and then to carry a
 * shulker box, so this also makes that room by merging stacks.
 */
final class InventorySpace {

    // Below this it isn't worth stopping mining to fill slots, unless it would fill all of them.
    private static final int MIN_SLOTS_WORTH_FILLING = 4;

    private final BlockMinerModule module;
    private final Bot bot;

    InventorySpace(BlockMinerModule module, Bot bot) {
        this.module = module;
        this.bot = bot;
    }

    private static Set<String> targetItems() {
        return TargetItems.itemsFor(PLUGIN_CONFIG.wantedItems);
    }

    /**
     * A kind of target item the bot has none of still needs an empty slot to get into, or it could
     * never be picked up. Junk can land there too, until the next time the bot stores things.
     */
    private int slotsToLeaveOpen() {
        return TargetItems.kindsNotHeld(PLUGIN_CONFIG.wantedItems, InventoryPlan.heldItemNames(bot.playerStacks()));
    }

    /** Blocks the pathfinder bridges with can't be picked up any more, so one stack gets held back. */
    Set<String> bridgingItems() {
        return CONFIG.client.extra.pathfinder.allowPlace
            ? CONFIG.client.extra.pathfinder.acceptableThrowawayItems
            : Set.of();
    }

    /** How many of what it's after it's carrying. */
    int targetItemsCarried() {
        Set<String> targets = targetItems();
        return bot.playerStacks().stream()
            .filter(entry -> targets.contains(nameOf(entry.stack())))
            .mapToInt(entry -> entry.stack().getAmount())
            .sum();
    }

    private List<Integer> junkSlots() {
        return InventoryPlan.slotsToThrowAway(
            bot.playerStacks(), targetItems(), PLUGIN_CONFIG.keepItems, bridgingItems(), PLUGIN_CONFIG.stillToStore);
    }

    /** Items taken off the list stop being protected from the junk rules once the bot has none left. */
    void forgetItemsNoLongerCarried() {
        Set<String> held = InventoryPlan.heldItemNames(bot.playerStacks());
        PLUGIN_CONFIG.stillToStore.removeIf(item -> !held.contains(item));
    }

    boolean hasJunk() {
        return !junkSlots().isEmpty();
    }

    /**
     * Thrown junk can be picked straight back up by a bot with empty slots. So it's only thrown away
     * once every slot it leaves behind can be filled, or when it's crowding out what the bot is after,
     * which stops it collecting anything, and the bot moves on from what it threw away.
     */
    boolean isWorthThrowingJunk() {
        int junk = junkSlots().size();
        if (junk == 0) return false;
        int free = bot.freeSlots();
        List<SlotStack> stacks = bot.playerStacks();
        if (InventoryPlan.needsStoring(stacks, free, targetItems(), PLUGIN_CONFIG.stacksOfRoomToKeep, true)) return true;
        if (!PLUGIN_CONFIG.fillEmptySlots) return false;

        int open = slotsToLeaveOpen();
        int toFill = Math.max(0, free + junk - open);
        return InventoryPlan.fillableSlots(stacks, free + junk, targetItems(), open) == toFill;
    }

    /** Throws away all the known junk. Call this right before filling slots, so it can't come back. */
    void throwAwayJunk() {
        bot.closeContainer();
        for (int slot : junkSlots()) {
            ItemStack stack = bot.stackAt(slot);
            if (bot.dropStack(slot)) {
                module.info("Threw away {} {}", stack.getAmount(), nameOf(stack));
            } else {
                module.warn("Couldn't throw away the {} in slot {}", nameOf(stack), slot);
            }
        }
    }

    boolean isWorthFilling() {
        if (!PLUGIN_CONFIG.fillEmptySlots) return false;
        int wanted = bot.freeSlots() - slotsToLeaveOpen();
        int fillable = InventoryPlan.fillableSlots(bot.playerStacks(), bot.freeSlots(), targetItems(), slotsToLeaveOpen());
        return fillable > 0 && (fillable >= MIN_SLOTS_WORTH_FILLING || fillable == wanted);
    }

    /** Puts one target item in each empty slot, sharing the slots between the different target items. */
    void fillEmptySlots() {
        if (!PLUGIN_CONFIG.fillEmptySlots) return;
        bot.closeContainer();

        int toFill = bot.freeSlots() - slotsToLeaveOpen();
        if (toFill <= 0) return;
        Deque<Integer> emptySlots = new ArrayDeque<>(bot.emptyPlayerSlots());

        // The item with the fewest to spare goes first, so what it can't fill goes to the next one.
        Map<Integer, Integer> spareByItem = spareTargetItems();
        List<Integer> itemIds = new ArrayList<>(spareByItem.keySet());
        itemIds.sort(Comparator.comparingInt(spareByItem::get));
        for (int i = 0; i < itemIds.size() && toFill > 0; i++) {
            int kindsLeft = itemIds.size() - i;
            int share = (toFill + kindsLeft - 1) / kindsLeft;
            toFill -= spread(itemIds.get(i), share, emptySlots);
        }
        // Clears the cursor, in case an item landed in a slot just before it was clicked.
        bot.closeContainer();
    }

    private Map<Integer, Integer> spareTargetItems() {
        Set<String> targets = targetItems();
        Map<Integer, Integer> spareByItem = new TreeMap<>();
        for (SlotStack entry : bot.playerStacks()) {
            ItemData item = ItemRegistry.REGISTRY.get(entry.stack().getId());
            if (item == null || !targets.contains(item.name()) || item.stackSize() <= 1) continue;
            spareByItem.merge(item.id(), entry.stack().getAmount() - 1, Integer::sum);
        }
        return spareByItem;
    }

    /**
     * Right-clicking a stack picks up half of it and right-clicking an empty slot puts down one, so the
     * stack being taken from is never left empty for a passing item to land in. Each click waits for
     * the server to confirm it, because Zenith doesn't predict putting one down, and on a laggy
     * connection a click based on an out-of-date cursor gets refused. Returns how many slots it filled.
     */
    private int spread(int itemId, int wanted, Deque<Integer> emptySlots) {
        int filled = 0;
        while (filled < wanted && !emptySlots.isEmpty()) {
            SlotStack source = largestStackOf(itemId);
            if (source == null || source.stack().getAmount() < 2) break;

            int pickedUp = (source.stack().getAmount() + 1) / 2;
            int toPlace = Math.min(pickedUp, Math.min(wanted - filled, emptySlots.size()));
            bot.clickAndConfirm(source.slot(), ClickItemAction.RIGHT_CLICK, () -> bot.cursorAmount() == pickedUp);
            int onCursor = pickedUp;
            int placed = 0;
            while (placed < toPlace && !emptySlots.isEmpty()) {
                int slot = emptySlots.poll();
                if (!bot.isEmptySlot(slot)) continue;
                int left = onCursor - 1;
                bot.clickAndConfirm(slot, ClickItemAction.RIGHT_CLICK, () -> !bot.isEmptySlot(slot) && bot.cursorAmount() == left);
                onCursor = left;
                placed++;
            }
            if (onCursor > 0) bot.clickAndConfirm(source.slot(), ClickItemAction.LEFT_CLICK, bot::cursorIsEmpty);
            filled += placed;
            if (placed == 0) break;
        }
        return filled;
    }

    private SlotStack largestStackOf(int itemId) {
        return bot.playerStacks().stream()
            .filter(entry -> entry.stack().getId() == itemId)
            .max(Comparator.comparingInt(entry -> entry.stack().getAmount()))
            .orElse(null);
    }

    /**
     * Makes sure one slot is free, for a shulker box or anything else on its way in. Merging partly
     * full stacks does it without losing anything, and storing starts while there's still room to.
     * Failing that, a stack goes on the ground, with junk going before target items. Works with a
     * container open, so it can be done at the last moment, before junk has a chance to fill the slot.
     */
    void ensureFreeSlot() {
        if (bot.freeSlots() > 0 || mergeStacks()) return;

        // Junk first, and that doesn't include what it's crafted, even though that isn't a target item.
        Set<String> wanted = InventoryPlan.itemsToStore(targetItems(), PLUGIN_CONFIG.stillToStore);
        List<SlotStack> droppable = new ArrayList<>(bot.playerStacks().stream()
            .filter(entry -> StorageRules.isStorable(entry.stack(), PLUGIN_CONFIG.keepItems))
            .toList());
        droppable.sort(Comparator
            .<SlotStack, Boolean>comparing(entry -> wanted.contains(nameOf(entry.stack())))
            .thenComparingInt(entry -> entry.stack().getAmount()));
        for (SlotStack entry : droppable) {
            if (bot.dropStack(entry.slot())) {
                module.warn("Dropped {} {} to free up an inventory slot", entry.stack().getAmount(), nameOf(entry.stack()));
                return;
            }
        }
        throw ErrandFailed.stop("The inventory is full of things it isn't allowed to store or drop, so it can't"
            + " carry a shulker box. Make room, or check the keep list with `blockminer`");
    }

    private static String nameOf(ItemStack stack) {
        ItemData item = ItemRegistry.REGISTRY.get(stack.getId());
        return item == null ? "unknown" : item.name();
    }

    /** Empties the smallest stack it can into other stacks of the same item. */
    private boolean mergeStacks() {
        List<SlotStack> stacks = new ArrayList<>(bot.playerStacks().stream()
            .filter(entry -> StorageRules.isStorable(entry.stack(), PLUGIN_CONFIG.keepItems))
            .toList());
        stacks.sort(Comparator.comparingInt(entry -> entry.stack().getAmount()));

        for (SlotStack smallest : stacks) {
            ItemData item = ItemRegistry.REGISTRY.get(smallest.stack().getId());
            if (item == null || item.stackSize() <= 1) continue;
            List<SlotStack> others = stacks.stream()
                .filter(other -> other.slot() != smallest.slot()
                    && other.stack().getId() == smallest.stack().getId()
                    && other.stack().getAmount() < item.stackSize()
                    && Objects.equals(other.stack().getDataComponents(), smallest.stack().getDataComponents()))
                .toList();
            int room = others.stream().mapToInt(other -> item.stackSize() - other.stack().getAmount()).sum();
            if (room < smallest.stack().getAmount()) continue;

            bot.click(smallest.slot(), ClickItemAction.LEFT_CLICK);
            int onCursor = smallest.stack().getAmount();
            for (SlotStack other : others) {
                if (onCursor <= 0) break;
                int fits = item.stackSize() - other.stack().getAmount();
                bot.click(other.slot(), ClickItemAction.LEFT_CLICK);
                onCursor -= fits;
            }
            return bot.waitFor(() -> bot.freeSlots() > 0 && bot.cursorIsEmpty(), Bot.ACTION_TIMEOUT_MS);
        }
        return false;
    }

    /** Breaks a block the bot placed and collects it, making room for it however it can. */
    void breakAndPickUp(int[] pos, ItemData item) {
        bot.breakAndPickUp(pos, item, this::ensureFreeSlot);
    }

    /** Collects a shulker box lying on the ground near {@code pos}. False if there isn't one. */
    boolean collectDroppedShulker(int[] pos) {
        return bot.collectDropNear(pos, StorageRules::isShulker, this::ensureFreeSlot);
    }
}
