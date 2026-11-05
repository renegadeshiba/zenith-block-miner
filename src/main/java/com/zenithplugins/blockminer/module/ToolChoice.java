package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.pathfinder.util.ToolSet;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenithplugins.blockminer.rules.TargetItems;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import com.zenithplugins.blockminer.rules.ToolRules;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;

/**
 * Whether the pathfinder will mine with the right tool. It can't be told which one to use: it takes
 * whichever on the hotbar breaks a block fastest, and with a tie it doesn't care about Fortune. So the
 * only way to steer it is by what's on the hotbar, and the check is simply to ask it what it would pick.
 */
final class ToolChoice {

    private static final int OWN_HOTBAR_START = 36;

    private ToolChoice() {
    }

    /** The hotbar slot, 0 to 8, that the pathfinder would mine this block with. */
    static int hotbarSlotFor(Block block) {
        return new ToolSet().getBestSlot(block, CONFIG.client.extra.pathfinder.preferSilkTouch);
    }

    private static ItemStack hotbarStack(int hotbarSlot) {
        return CACHE.getPlayerCache().getPlayerInventory().get(OWN_HOTBAR_START + hotbarSlot);
    }

    /** The blocks for these items where the tool matters, skipping any name that isn't a block. */
    static List<Block> blocksNeedingARightTool(Collection<String> items) {
        return TargetItems.blocksNeedingARightTool(items).stream()
            .map(BlockRegistry.REGISTRY::get)
            .filter(Objects::nonNull)
            .toList();
    }

    /** The first of these blocks that the pathfinder would mine with the wrong tool, or null if none. */
    static @Nullable Block firstWithWrongTool(List<Block> blocks, ToolNeed need) {
        if (need == ToolNeed.ANY) return null;
        for (Block block : blocks) {
            if (!suits(hotbarStack(hotbarSlotFor(block)), block, need)) return block;
        }
        return null;
    }

    /**
     * True for a tool that gives the item that's wanted and really is for this block, since an axe that
     * happens not to have Silk Touch won't get any cobbled deepslate.
     */
    static boolean suits(@Nullable ItemStack tool, Block block, ToolNeed need) {
        return worksOn(tool, block) && ToolRules.meets(need, tool);
    }

    /** True for a mining tool that breaks this block faster than a bare hand. */
    static boolean worksOn(@Nullable ItemStack tool, Block block) {
        if (tool == null || ToolRules.miningToolType(tool) == null) return false;
        var interactions = BOT.getInteractions();
        return interactions.blockBreakSpeed(block, tool) > interactions.blockBreakSpeed(block, Container.EMPTY_STACK);
    }
}
