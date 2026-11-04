package com.zenithplugins.blockminer.rules;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolType;
import com.zenith.util.ItemUtil;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRulesTest {

    private static final int THRESHOLD = 5;

    /** A tool with this much durability left. */
    static ItemStack tool(ItemData item, int durabilityLeft) {
        int maxDamage = ItemUtil.getMaxDamage(new ItemStack(item.id(), 1));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.DAMAGE, maxDamage - durabilityLeft);
        return new ItemStack(item.id(), 1, components);
    }

    @Test
    void miningToolsHaveATypeButSwordsAndOtherItemsDoNot() {
        assertEquals(ToolType.PICKAXE, ToolRules.miningToolType(new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1)));
        assertEquals(ToolType.SHOVEL, ToolRules.miningToolType(new ItemStack(ItemRegistry.IRON_SHOVEL.id(), 1)));
        assertNull(ToolRules.miningToolType(new ItemStack(ItemRegistry.NETHERITE_SWORD.id(), 1)));
        assertNull(ToolRules.miningToolType(new ItemStack(ItemRegistry.DIAMOND.id(), 1)));
        assertNull(ToolRules.miningToolType(null));
    }

    @Test
    void aToolIsWornAtTheThresholdAndNotAbove() {
        assertTrue(ToolRules.isWorn(tool(ItemRegistry.NETHERITE_PICKAXE, 5), THRESHOLD));
        assertTrue(ToolRules.isWorn(tool(ItemRegistry.NETHERITE_PICKAXE, 1), THRESHOLD));
        assertFalse(ToolRules.isWorn(tool(ItemRegistry.NETHERITE_PICKAXE, 6), THRESHOLD));
        assertFalse(ToolRules.isWorn(new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1), THRESHOLD));
    }

    @Test
    void onlyMiningToolsCanBeWorn() {
        assertFalse(ToolRules.isWorn(tool(ItemRegistry.NETHERITE_SWORD, 1), THRESHOLD));
        assertFalse(ToolRules.isWorn(new ItemStack(ItemRegistry.DIAMOND.id(), 64), THRESHOLD));
        assertFalse(ToolRules.isWorn(null, THRESHOLD));
    }

    @Test
    void wornToolsAndOtherKindsOfToolCannotReplaceOne() {
        ItemStack worn = tool(ItemRegistry.NETHERITE_PICKAXE, 5);
        assertEquals(-1, ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_PICKAXE, 3), worn, THRESHOLD));
        assertEquals(-1, ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_AXE, 2000), worn, THRESHOLD));
        assertEquals(-1, ToolRules.replacementScore(new ItemStack(ItemRegistry.DIAMOND.id(), 1), worn, THRESHOLD));
        assertEquals(-1, ToolRules.replacementScore(null, worn, THRESHOLD));
    }

    @Test
    void theSameItemIsPreferredThenWhicheverHasMoreDurability() {
        ItemStack worn = tool(ItemRegistry.NETHERITE_PICKAXE, 5);
        long sameItemFresh = ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_PICKAXE, 2000), worn, THRESHOLD);
        long sameItemUsed = ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_PICKAXE, 100), worn, THRESHOLD);
        long otherItem = ToolRules.replacementScore(tool(ItemRegistry.DIAMOND_PICKAXE, 1500), worn, THRESHOLD);

        assertTrue(sameItemFresh > sameItemUsed);
        assertTrue(sameItemUsed > otherItem);
        assertTrue(otherItem > -1);
    }
}
