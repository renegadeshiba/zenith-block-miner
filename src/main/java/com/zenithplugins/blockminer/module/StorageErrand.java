package com.zenithplugins.blockminer.module;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.InventoryPlan;
import com.zenithplugins.blockminer.rules.InventoryPlan.SlotStack;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;

import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Empties the inventory into shulker boxes: crafts what it can into blocks, takes a shulker box from
 * the ender chest, fills it, and puts it back, until everything is stored. It tops up the ones it's
 * started before taking an empty one. A stack of each target item stays behind, ready to fill the
 * inventory's empty slots again.
 */
final class StorageErrand {

    // As many as an ender chest holds, since any number of them could be part-full with little room left.
    private static final int MAX_SHULKERS_PER_RUN = 27;
    private static final int STACK_MOVE_TIMEOUT_MS = 3_000;

    private final BlockMinerModule module;
    private final Bot bot;
    private final InventorySpace space;
    private final CraftingErrand crafting;

    StorageErrand(BlockMinerModule module, Bot bot, InventorySpace space, CraftingErrand crafting) {
        this.module = module;
        this.bot = bot;
        this.space = space;
        this.crafting = crafting;
    }

    private List<Integer> slotsToStore() {
        return InventoryPlan.slotsToStore(
            bot.playerStacks(),
            TargetItems.itemsFor(PLUGIN_CONFIG.wantedItems),
            PLUGIN_CONFIG.keepItems,
            space.bridgingItems(),
            PLUGIN_CONFIG.craftIntoBlocks,
            PLUGIN_CONFIG.stillToStore
        );
    }

    boolean isNeeded() {
        return InventoryPlan.needsStoring(
            bot.playerStacks(),
            bot.freeSlots(),
            TargetItems.itemsFor(PLUGIN_CONFIG.wantedItems),
            PLUGIN_CONFIG.stacksOfRoomToKeep,
            !slotsToStore().isEmpty()
        );
    }

    /** True when the bot can't pick up anything it's mining, and storing wouldn't change that. */
    boolean isStuck() {
        return InventoryPlan.hasNoRoom(
            bot.playerStacks(),
            bot.freeSlots(),
            TargetItems.itemsFor(PLUGIN_CONFIG.wantedItems),
            !slotsToStore().isEmpty() || space.hasJunk()
        );
    }

    /** Filling the slots again is left to the caller, along with throwing away any junk first. */
    void run() {
        space.ensureFreeSlot();
        craftIfEnabled();
        store();
    }

    /** The inventory still needs emptying if crafting doesn't work out, so that doesn't end the errand. */
    private void craftIfEnabled() {
        if (!PLUGIN_CONFIG.craftIntoBlocks) return;
        try {
            crafting.run();
        } catch (ErrandFailed e) {
            if (!e.worthRetrying()) throw e;
            module.warn("{}; storing items uncrafted this time", e.getMessage());
            bot.closeContainer();
        }
    }

    private void store() {
        for (int shulker = 0; shulker < MAX_SHULKERS_PER_RUN; shulker++) {
            if (slotsToStore().isEmpty()) return;

            ItemData shulkerItem = takeShulkerToFill();
            int[] pos = bot.placeBeside(shulkerItem);
            bot.rememberPlacedShulker(pos);
            bot.openContainer(pos, "The shulker box");
            moveStorableItemsIntoOpenContainer();
            space.breakAndPickUp(pos, shulkerItem);
            bot.forgetPlacedShulker();
            returnCarriedShulkers();
        }
        if (slotsToStore().isEmpty()) return;
        throw ErrandFailed.retry("Still had items to store after filling " + MAX_SHULKERS_PER_RUN + " shulker boxes");
    }

    private ItemData takeShulkerToFill() {
        bot.openEnderChest();
        List<ItemStack> storing = slotsToStore().stream().map(bot::stackAt).toList();
        List<SlotStack> chest = bot.containerSlots().stream().map(slot -> new SlotStack(slot, bot.stackAt(slot))).toList();
        int shulkerSlot = StorageRules.shulkerToFill(
            chest, storing, InventoryPlan.itemsToStore(TargetItems.itemsFor(PLUGIN_CONFIG.wantedItems), PLUGIN_CONFIG.stillToStore));
        if (shulkerSlot == -1) {
            bot.closeContainer();
            throw ErrandFailed.stop("The ender chest has no shulker boxes with room left");
        }

        space.ensureFreeSlot();
        ItemData shulkerItem = ItemRegistry.REGISTRY.get(bot.stackAt(shulkerSlot).getId());
        int carriedBefore = carriedShulkers();
        if (!bot.shiftClick(shulkerSlot)
            || !bot.waitFor(() -> carriedShulkers() > carriedBefore, Bot.ACTION_TIMEOUT_MS)) {
            bot.closeContainer();
            throw ErrandFailed.retry("Couldn't take a shulker box out of the ender chest");
        }
        bot.closeContainer();
        return shulkerItem;
    }

    /**
     * Moves in as much as fits. A stack that doesn't fit stays for the next shulker box, as a part-full one
     * can have room for one thing and not another. Anything picked up while this runs just waits for the
     * next trip.
     */
    void moveStorableItemsIntoOpenContainer() {
        int moved = 0;
        for (int slot : slotsToStore()) {
            ItemStack stack = bot.stackAt(slot);
            if (!StorageRules.hasRoomFor(openContainerContents(), stack)) continue;

            int amountBefore = stack.getAmount();
            if (!bot.shiftClick(slot) || !bot.waitFor(() -> amountAt(slot) < amountBefore, STACK_MOVE_TIMEOUT_MS)) break;
            moved++;
            bot.sleep(Bot.CLICK_PACING_MS);
        }
        if (moved == 0) throw ErrandFailed.retry("Couldn't move anything into the shulker box");
    }

    private List<ItemStack> openContainerContents() {
        return bot.containerSlots().stream()
            .map(bot::stackAt)
            .filter(stack -> stack != null && stack.getAmount() > 0)
            .toList();
    }

    private int amountAt(int slot) {
        ItemStack stack = bot.stackAt(slot);
        return stack == null ? 0 : stack.getAmount();
    }

    private int carriedShulkers() {
        return bot.playerSlots(StorageRules::isShulker).size();
    }

    /**
     * Puts every carried shulker box in the ender chest. That includes one left over from an errand
     * that was interrupted, which could be holding a lot of items.
     */
    void returnCarriedShulkers() {
        if (carriedShulkers() == 0) return;
        bot.openEnderChest();
        for (int slot : bot.playerSlots(StorageRules::isShulker)) {
            int before = carriedShulkers();
            if (!bot.shiftClick(slot) || !bot.waitFor(() -> carriedShulkers() < before, Bot.ACTION_TIMEOUT_MS)) {
                bot.closeContainer();
                throw ErrandFailed.stop("The ender chest is full, so a shulker box can't go back in it");
            }
            bot.sleep(Bot.CLICK_PACING_MS);
        }
        bot.closeContainer();
    }
}
