package com.zenithplugins.blockminer.module;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.ToolRules;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Replaces a worn tool: takes the shulker box with the best fresh tool out of the ender chest, swaps
 * the worn tool for it, and puts the shulker box back with the worn tool inside.
 */
final class ToolSwapErrand {

    private final BlockMinerModule module;
    private final Bot bot;
    private final InventorySpace space;
    private final StorageErrand storage;

    ToolSwapErrand(BlockMinerModule module, Bot bot, InventorySpace space, StorageErrand storage) {
        this.module = module;
        this.bot = bot;
        this.space = space;
        this.storage = storage;
    }

    void run(ItemStack worn) {
        int threshold = PLUGIN_CONFIG.toolDurabilityThreshold;
        ItemData wornItem = ItemRegistry.REGISTRY.get(worn.getId());

        ItemData shulkerItem = takeToolShulker(worn, threshold, wornItem);
        int[] pos = bot.placeBeside(shulkerItem);
        bot.rememberPlacedShulker(pos);
        bot.openContainer(pos, "The tool shulker box");
        swapInOpenContainer(worn, threshold);
        space.breakAndPickUp(pos, shulkerItem);
        bot.forgetPlacedShulker();
        storage.returnCarriedShulkers();
        module.info("Swapped the worn {} for a fresh one", wornItem.name());
    }

    private ItemData takeToolShulker(ItemStack worn, int threshold, ItemData wornItem) {
        bot.openEnderChest();
        int bestSlot = -1;
        long bestScore = -1;
        for (int slot : bot.containerSlots()) {
            ItemStack stack = bot.stackAt(slot);
            if (!StorageRules.isShulker(stack)) continue;
            for (ItemStack tool : StorageRules.contents(stack)) {
                long score = ToolRules.replacementScore(tool, worn, threshold);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
        }
        if (bestSlot == -1) {
            bot.closeContainer();
            throw ErrandFailed.stop("No shulker box in the ender chest has a fresh replacement for the worn " + wornItem.name());
        }

        // The inventory is usually full on purpose, and the shulker box needs a slot to come out into.
        space.ensureFreeSlot();
        ItemData shulkerItem = ItemRegistry.REGISTRY.get(bot.stackAt(bestSlot).getId());
        int carriedBefore = bot.playerSlots(StorageRules::isShulker).size();
        if (!bot.shiftClick(bestSlot) || !bot.waitFor(
            () -> bot.playerSlots(StorageRules::isShulker).size() > carriedBefore, Bot.ACTION_TIMEOUT_MS)) {
            bot.closeContainer();
            throw ErrandFailed.retry("Couldn't take the tool shulker box out of the ender chest");
        }
        bot.closeContainer();
        return shulkerItem;
    }

    /**
     * Swaps with three clicks rather than shift-clicking, because it works with a full inventory or a
     * full shulker box: pick up the fresh tool, click it onto the worn one to pick that up instead, and
     * put the worn one down where the fresh one was. The fresh tool ends up in the worn one's slot.
     */
    private void swapInOpenContainer(ItemStack worn, int threshold) {
        int freshSlot = bestFreshToolSlot(worn, threshold);
        if (freshSlot == -1) throw ErrandFailed.retry("The tool shulker box no longer has a fresh tool in it");

        // Found again here because placing the shulker box can move things around the hotbar.
        var wornSlots = bot.playerSlots(stack -> stack.getId() == worn.getId() && ToolRules.isWorn(stack, threshold));
        if (wornSlots.isEmpty()) throw ErrandFailed.retry("Couldn't find the worn tool in the inventory");
        int wornSlot = wornSlots.getFirst();

        bot.click(freshSlot, ClickItemAction.LEFT_CLICK);
        bot.click(wornSlot, ClickItemAction.LEFT_CLICK);
        bot.click(freshSlot, ClickItemAction.LEFT_CLICK);

        boolean swapped = bot.waitFor(
            () -> bot.cursorIsEmpty()
                && ToolRules.isWorn(bot.stackAt(freshSlot), threshold)
                && !ToolRules.isWorn(bot.stackAt(wornSlot), threshold)
                && ToolRules.miningToolType(bot.stackAt(wornSlot)) != null,
            Bot.ACTION_TIMEOUT_MS);
        if (!swapped) throw ErrandFailed.retry("The tool swap didn't go through");
    }

    private int bestFreshToolSlot(ItemStack worn, int threshold) {
        int bestSlot = -1;
        long bestScore = -1;
        for (int slot : bot.containerSlots()) {
            long score = ToolRules.replacementScore(bot.stackAt(slot), worn, threshold);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }
}
