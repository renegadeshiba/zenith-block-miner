package com.zenithplugins.blockminer.module;

import com.zenith.mc.block.Block;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.ItemUtil;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import com.zenithplugins.blockminer.rules.ToolRules;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Gets the bot the tool it needs. Replaces a worn tool with a fresh one from a shulker box in the ender
 * chest, leaving the worn one inside, and sorts out the hotbar when what's on it would give the wrong
 * item, such as a Silk Touch pickaxe when the bot is after diamonds.
 */
final class ToolSwapErrand {

    private static final int HOTBAR_SLOTS = 9;
    // Not finding a tool turns the bot off, and on a struggling server a container can look empty for a while.
    private static final int TOOL_LOOK_MS = 3_000;

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

    void run(ItemStack worn, ToolNeed need) {
        int threshold = PLUGIN_CONFIG.toolDurabilityThreshold;
        ItemData wornItem = ItemRegistry.REGISTRY.get(worn.getId());
        ToLongFunction<ItemStack> score = tool -> ToolRules.replacementScore(tool, worn, threshold, need);

        fromToolShulker(
            score,
            "No shulker box in the ender chest has a fresh replacement for the worn " + wornItem.name()
                + (need == ToolNeed.ANY ? "" : " that's " + need.description()),
            // There's nothing to leave behind if it broke on the way, digging to the ender chest or making room.
            () -> bot.playerSlots(stack -> stack.getId() == worn.getId() && ToolRules.isWorn(stack, threshold))
        );
        module.info("Swapped the worn {} for a fresh one", wornItem.name());
    }

    /**
     * Gets the right tool onto the hotbar and any tool that would be picked instead off it. Fetches the
     * tool from a shulker box in the ender chest if the bot isn't carrying one.
     */
    void ensureRightTool(List<Block> blocks, ToolNeed need) {
        int threshold = PLUGIN_CONFIG.toolDurabilityThreshold;
        // One pass per hotbar slot is plenty, since each one moves a wrong tool off the hotbar.
        for (int pass = 0; pass <= HOTBAR_SLOTS; pass++) {
            Block block = ToolChoice.firstWithWrongTool(blocks, need);
            if (block == null) return;

            int hotbarSlot = ToolChoice.hotbarSlotFor(block);
            List<Integer> carried = bot.mainInventorySlots(stack ->
                ToolChoice.suits(stack, block, need) && !ToolRules.isWorn(stack, threshold));
            if (!carried.isEmpty()) {
                bot.swapWithHotbar(carried.getFirst(), hotbarSlot);
                continue;
            }

            if (bot.hotbarSlots(stack -> ToolChoice.suits(stack, block, need)).isEmpty()) {
                fetchTool(block, need, threshold);
                continue;
            }

            // The right tool is on the hotbar, but a faster wrong one is getting picked ahead of it.
            List<Integer> notTools = bot.mainInventorySlots(stack -> ToolRules.miningToolType(stack) == null);
            if (notTools.isEmpty()) {
                throw ErrandFailed.stop("Couldn't make room in the inventory to move a wrong tool off the hotbar");
            }
            bot.swapWithHotbar(notTools.getFirst(), hotbarSlot);
        }
        throw ErrandFailed.retry("Couldn't get the hotbar sorted out for mining");
    }

    private void fetchTool(Block block, ToolNeed need, int threshold) {
        ToLongFunction<ItemStack> score = tool -> {
            if (!ToolChoice.suits(tool, block, need) || ToolRules.isWorn(tool, threshold)) return -1;
            return ItemUtil.getDamageUntilBreak(tool);
        };
        fromToolShulker(
            score,
            "Mining " + block.name() + " for what you asked for takes "
                + (need == ToolNeed.ANY ? "a tool that works on it" : need.description())
                + ", but there isn't a fresh one on the bot or in a shulker box in the ender chest",
            // A wrong or worn tool for this block goes in the shulker box to make room. Other tools stay put.
            () -> bot.playerSlots(stack -> ToolChoice.worksOn(stack, block)
                && (!ToolRules.meets(need, stack) || ToolRules.isWorn(stack, threshold)))
        );
        module.info("Fetched {} to mine {}", need.description(), block.name());
    }

    /**
     * Takes the tool scoring highest out of a shulker box from the ender chest. It takes the place of
     * the first of {@code toReplace}, which goes in the shulker box, or goes in an empty slot if that's
     * empty, and then onto the hotbar.
     */
    private void fromToolShulker(ToLongFunction<ItemStack> score, String ifNone, Supplier<List<Integer>> toReplace) {
        ItemData shulkerItem = takeToolShulker(score, ifNone);
        int[] pos = bot.placeBeside(shulkerItem);
        bot.rememberPlacedShulker(pos);
        bot.openContainer(pos, "The tool shulker box");
        takeOutOfOpenContainer(score, toReplace);
        moveOntoHotbar(score);
        space.breakAndPickUp(pos, shulkerItem);
        bot.forgetPlacedShulker();
        storage.returnCarriedShulkers();
    }

    private ItemData takeToolShulker(ToLongFunction<ItemStack> score, String ifNone) {
        bot.openEnderChest();
        bot.waitFor(() -> shulkerWithBestTool(score) != -1, TOOL_LOOK_MS);
        int bestSlot = shulkerWithBestTool(score);
        if (bestSlot == -1) {
            bot.closeContainer();
            throw ErrandFailed.stop(ifNone);
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

    private int shulkerWithBestTool(ToLongFunction<ItemStack> score) {
        int bestSlot = -1;
        long bestScore = -1;
        for (int slot : bot.containerSlots()) {
            ItemStack stack = bot.stackAt(slot);
            if (!StorageRules.isShulker(stack)) continue;
            for (ItemStack tool : StorageRules.contents(stack)) {
                long toolScore = score.applyAsLong(tool);
                if (toolScore > bestScore) {
                    bestScore = toolScore;
                    bestSlot = slot;
                }
            }
        }
        return bestSlot;
    }

    private int bestToolInOpenContainer(ToLongFunction<ItemStack> score) {
        int bestSlot = -1;
        long bestScore = -1;
        for (int slot : bot.containerSlots()) {
            long toolScore = score.applyAsLong(bot.stackAt(slot));
            if (toolScore > bestScore) {
                bestScore = toolScore;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private void takeOutOfOpenContainer(ToLongFunction<ItemStack> score, Supplier<List<Integer>> toReplace) {
        bot.waitFor(() -> bestToolInOpenContainer(score) != -1, TOOL_LOOK_MS);
        int freshSlot = bestToolInOpenContainer(score);
        if (freshSlot == -1) throw ErrandFailed.retry("The tool shulker box no longer has that tool in it");

        // Found here because placing the shulker box can move things around the hotbar.
        List<Integer> replaced = toReplace.get();
        if (replaced.isEmpty()) {
            space.ensureFreeSlot();
            moveToEmptySlot(freshSlot);
        } else {
            swapInOpenContainer(freshSlot, replaced.getFirst(), score);
        }
    }

    /**
     * The pathfinder only looks on the hotbar. Putting the shulker box down can have taken the old tool's place
     * there, leaving the fresh one in the main inventory where the old one ended up.
     */
    private void moveOntoHotbar(ToLongFunction<ItemStack> score) {
        if (!bot.hotbarSlots(stack -> score.applyAsLong(stack) >= 0).isEmpty()) return;
        List<Integer> carried = bot.mainInventorySlots(stack -> score.applyAsLong(stack) >= 0);
        if (carried.isEmpty()) throw ErrandFailed.retry("The fresh tool isn't in the inventory after taking it out");
        bot.swapWithHotbar(carried.getFirst(), bot.hotbarSlotFor(bot.stackAt(carried.getFirst()).getId()));
    }

    private void moveToEmptySlot(int freshSlot) {
        int emptySlot = bot.emptyPlayerSlots().getFirst();
        bot.click(freshSlot, ClickItemAction.LEFT_CLICK);
        bot.click(emptySlot, ClickItemAction.LEFT_CLICK);
        boolean moved = bot.waitFor(
            () -> bot.cursorIsEmpty() && bot.isEmptySlot(freshSlot) && !bot.isEmptySlot(emptySlot),
            Bot.ACTION_TIMEOUT_MS);
        if (!moved) throw ErrandFailed.retry("Moving the tool out of the shulker box didn't go through");
    }

    /**
     * Swaps with three clicks rather than shift-clicking, because it works with a full inventory or a
     * full shulker box: pick up the fresh tool, click it onto the old one to pick that up instead, and
     * put the old one down where the fresh one was. The fresh tool ends up in the old one's slot.
     */
    private void swapInOpenContainer(int freshSlot, int oldSlot, ToLongFunction<ItemStack> score) {
        bot.click(freshSlot, ClickItemAction.LEFT_CLICK);
        bot.click(oldSlot, ClickItemAction.LEFT_CLICK);
        bot.click(freshSlot, ClickItemAction.LEFT_CLICK);

        boolean swapped = bot.waitFor(
            () -> bot.cursorIsEmpty() && score.applyAsLong(bot.stackAt(oldSlot)) >= 0,
            Bot.ACTION_TIMEOUT_MS);
        if (!swapped) throw ErrandFailed.retry("The tool swap didn't go through");
    }
}
