package com.zenithplugins.blockminer.rules;

import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolType;
import com.zenith.util.ItemUtil;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolRulesTest {

    private static final int THRESHOLD = 5;

    /** A tool with this much durability left. */
    static ItemStack tool(ItemData item, int durabilityLeft) {
        int maxDamage = ItemUtil.getMaxDamage(new ItemStack(item.id(), 1));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.DAMAGE, maxDamage - durabilityLeft);
        return new ItemStack(item.id(), 1, components);
    }

    /** A fresh tool with these enchantments, at level 3. */
    public static ItemStack enchanted(ItemData item, EnchantmentData... enchantments) {
        Int2IntOpenHashMap levels = new Int2IntOpenHashMap();
        for (EnchantmentData enchantment : enchantments) levels.put(enchantment.id(), 3);
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.DAMAGE, 0);
        components.put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.builder().enchantments(levels).showInTooltip(true).build());
        return new ItemStack(item.id(), 1, components);
    }

    private static ItemStack fortunePick() {
        return enchanted(ItemRegistry.NETHERITE_PICKAXE, EnchantmentRegistry.FORTUNE.get());
    }

    private static ItemStack silkPick() {
        return enchanted(ItemRegistry.NETHERITE_PICKAXE, EnchantmentRegistry.SILK_TOUCH.get());
    }

    private static ItemStack plainPick() {
        return new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1);
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
    void anyToolWillDoWhenItDoesntMatter() {
        assertTrue(ToolRules.meets(ToolNeed.ANY, fortunePick()));
        assertTrue(ToolRules.meets(ToolNeed.ANY, silkPick()));
        assertTrue(ToolRules.meets(ToolNeed.ANY, plainPick()));
    }

    @Test
    void fortuneMeansAFortuneToolAndNothingElse() {
        assertTrue(ToolRules.meets(ToolNeed.FORTUNE, fortunePick()));
        assertFalse(ToolRules.meets(ToolNeed.FORTUNE, silkPick()));
        assertFalse(ToolRules.meets(ToolNeed.FORTUNE, plainPick()));
        assertFalse(ToolRules.meets(ToolNeed.FORTUNE, null));
    }

    @Test
    void silkTouchMeansASilkTouchToolAndNothingElse() {
        assertTrue(ToolRules.meets(ToolNeed.SILK_TOUCH, silkPick()));
        assertFalse(ToolRules.meets(ToolNeed.SILK_TOUCH, fortunePick()));
        assertFalse(ToolRules.meets(ToolNeed.SILK_TOUCH, plainPick()));
        assertFalse(ToolRules.meets(ToolNeed.SILK_TOUCH, null));
    }

    @Test
    void noSilkTouchMeansAnythingWithoutIt() {
        assertTrue(ToolRules.meets(ToolNeed.NO_SILK_TOUCH, fortunePick()));
        assertTrue(ToolRules.meets(ToolNeed.NO_SILK_TOUCH, plainPick()));
        assertFalse(ToolRules.meets(ToolNeed.NO_SILK_TOUCH, silkPick()));
    }

    @Test
    void wornToolsAndOtherKindsOfToolCannotReplaceOne() {
        ItemStack worn = tool(ItemRegistry.NETHERITE_PICKAXE, 5);
        assertEquals(-1, ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_PICKAXE, 3), worn, THRESHOLD, ToolNeed.ANY));
        assertEquals(-1, ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_AXE, 2000), worn, THRESHOLD, ToolNeed.ANY));
        assertEquals(-1, ToolRules.replacementScore(new ItemStack(ItemRegistry.DIAMOND.id(), 1), worn, THRESHOLD, ToolNeed.ANY));
        assertEquals(-1, ToolRules.replacementScore(null, worn, THRESHOLD, ToolNeed.ANY));
    }

    @Test
    void theSameItemIsPreferredThenWhicheverHasMoreDurability() {
        ItemStack worn = tool(ItemRegistry.NETHERITE_PICKAXE, 5);
        long sameItemFresh = ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_PICKAXE, 2000), worn, THRESHOLD, ToolNeed.ANY);
        long sameItemUsed = ToolRules.replacementScore(tool(ItemRegistry.NETHERITE_PICKAXE, 100), worn, THRESHOLD, ToolNeed.ANY);
        long otherItem = ToolRules.replacementScore(tool(ItemRegistry.DIAMOND_PICKAXE, 1500), worn, THRESHOLD, ToolNeed.ANY);

        assertTrue(sameItemFresh > sameItemUsed);
        assertTrue(sameItemUsed > otherItem);
        assertTrue(otherItem > -1);
    }

    @Test
    void aReplacementHasToGiveTheItemThatsWanted() {
        ItemStack worn = tool(ItemRegistry.NETHERITE_PICKAXE, 5);
        assertEquals(-1, ToolRules.replacementScore(silkPick(), worn, THRESHOLD, ToolNeed.FORTUNE));
        assertEquals(-1, ToolRules.replacementScore(plainPick(), worn, THRESHOLD, ToolNeed.FORTUNE));
        assertTrue(ToolRules.replacementScore(fortunePick(), worn, THRESHOLD, ToolNeed.FORTUNE) > -1);

        assertEquals(-1, ToolRules.replacementScore(fortunePick(), worn, THRESHOLD, ToolNeed.SILK_TOUCH));
        assertTrue(ToolRules.replacementScore(silkPick(), worn, THRESHOLD, ToolNeed.SILK_TOUCH) > -1);

        assertEquals(-1, ToolRules.replacementScore(silkPick(), worn, THRESHOLD, ToolNeed.NO_SILK_TOUCH));
        assertTrue(ToolRules.replacementScore(plainPick(), worn, THRESHOLD, ToolNeed.NO_SILK_TOUCH) > -1);
    }
}
