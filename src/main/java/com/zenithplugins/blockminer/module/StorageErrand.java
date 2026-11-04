package com.zenithplugins.blockminer.module;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.InventoryPlan;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;
import java.util.Set;

import static com.zenith.Globals.CONFIG;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Empties the inventory into shulker boxes: crafts what it can into blocks, takes an empty shulker
 * box from the ender chest, fills it, and puts it back, until everything is stored. A stack of each
 * target item stays behind, ready to fill the inventory's empty slots again.
 */
final class StorageErrand {

    // A full inventory fills two shulker boxes at most; this only stops a run that isn't getting anywhere.
    private static final int MAX_SHULKERS_PER_RUN = 4;
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
        // Blocks the pathfinder bridges with can't be picked up any more, so one stack gets held back.
        Set<String> bridgingItems = CONFIG.client.extra.pathfinder.allowPlace
            ? CONFIG.client.extra.pathfinder.acceptableThrowawayItems
            : Set.of();
        return InventoryPlan.slotsToStore(
            bot.playerStacks(),
            TargetItems.itemsFor(PLUGIN_CONFIG.blocks),
            PLUGIN_CONFIG.keepItems,
            bridgingItems,
            PLUGIN_CONFIG.craftIntoBlocks
        );
    }

    boolean isNeeded() {
        return InventoryPlan.needsStoring(
            bot.playerStacks(),
            bot.freeSlots(),
            TargetItems.itemsFor(PLUGIN_CONFIG.blocks),
            PLUGIN_CONFIG.stacksOfRoomToKeep,
            !slotsToStore().isEmpty()
        );
    }

    /** True when the bot can't pick up anything it's mining, and storing wouldn't change that. */
    boolean isStuck() {
        return InventoryPlan.hasNoRoom(
            bot.playerStacks(),
            bot.freeSlots(),
            TargetItems.itemsFor(PLUGIN_CONFIG.blocks),
            !slotsToStore().isEmpty()
        );
    }

    void run() {
        space.ensureFreeSlot();
        craftIfEnabled();
        store();
        space.fillEmptySlots();
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

            ItemData shulkerItem = takeEmptyShulker();
            int[] pos = bot.placeBeside(shulkerItem);
            bot.rememberPlacedShulker(pos);
            bot.openContainer(pos, "The shulker box");
            boolean everythingFit = moveStorableItemsIntoOpenContainer();
            space.breakAndPickUp(pos, shulkerItem);
            bot.forgetPlacedShulker();
            returnCarriedShulkers();
            if (everythingFit) return;
        }
        throw ErrandFailed.retry("Still had items to store after filling " + MAX_SHULKERS_PER_RUN + " shulker boxes");
    }

    private ItemData takeEmptyShulker() {
        bot.openEnderChest();
        int emptyShulkerSlot = -1;
        for (int slot : bot.containerSlots()) {
            if (StorageRules.isEmptyShulker(bot.stackAt(slot))) {
                emptyShulkerSlot = slot;
                break;
            }
        }
        if (emptyShulkerSlot == -1) {
            bot.closeContainer();
            throw ErrandFailed.stop("The ender chest has no empty shulker boxes left");
        }

        space.ensureFreeSlot();
        ItemData shulkerItem = ItemRegistry.REGISTRY.get(bot.stackAt(emptyShulkerSlot).getId());
        int carriedBefore = carriedShulkers();
        if (!bot.shiftClick(emptyShulkerSlot)
            || !bot.waitFor(() -> carriedShulkers() > carriedBefore, Bot.ACTION_TIMEOUT_MS)) {
            bot.closeContainer();
            throw ErrandFailed.retry("Couldn't take an empty shulker box out of the ender chest");
        }
        bot.closeContainer();
        return shulkerItem;
    }

    /**
     * Returns true if everything went in, or false if the shulker box filled up first and the rest
     * needs another one. Anything picked up while this runs just waits for the next trip.
     */
    private boolean moveStorableItemsIntoOpenContainer() {
        int moved = 0;
        for (int slot : slotsToStore()) {
            int amountBefore = bot.stackAt(slot).getAmount();
            if (!bot.shiftClick(slot) || !bot.waitFor(() -> amountAt(slot) < amountBefore, STACK_MOVE_TIMEOUT_MS)) {
                if (moved == 0) throw ErrandFailed.retry("Couldn't move anything into the shulker box");
                return false;
            }
            moved++;
            bot.sleep(Bot.CLICK_PACING_MS);
        }
        return true;
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
