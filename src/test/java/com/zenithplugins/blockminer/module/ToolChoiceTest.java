package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import com.zenithplugins.blockminer.rules.ToolRulesTest;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs ZenithProxy's real tool choice over a made-up hotbar, because which tool the bot mines with is
 * entirely up to that. What this can't show is the mining itself, or a server's real enchantment ids.
 */
class ToolChoiceTest {

    private static final int HOTBAR_START = 36;
    private static final List<Block> DEEPSLATE = List.of(BlockRegistry.DEEPSLATE);
    private static final List<Block> DIAMOND_ORE = List.of(BlockRegistry.DIAMOND_ORE, BlockRegistry.DEEPSLATE_DIAMOND_ORE);

    private static ItemStack fortunePick() {
        return ToolRulesTest.enchanted(ItemRegistry.NETHERITE_PICKAXE, EnchantmentRegistry.FORTUNE.get());
    }

    private static ItemStack silkPick() {
        return ToolRulesTest.enchanted(ItemRegistry.NETHERITE_PICKAXE, EnchantmentRegistry.SILK_TOUCH.get());
    }

    private static ItemStack plainPick() {
        return new ItemStack(ItemRegistry.NETHERITE_PICKAXE.id(), 1);
    }

    private static ItemStack stoneFortunePick() {
        return ToolRulesTest.enchanted(ItemRegistry.STONE_PICKAXE, EnchantmentRegistry.FORTUNE.get());
    }

    private static void hotbar(ItemStack... stacks) {
        var inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItemStack(HOTBAR_START + slot, slot < stacks.length ? stacks[slot] : Container.EMPTY_STACK);
        }
    }

    @BeforeEach
    @AfterEach
    void emptyHotbar() {
        hotbar();
    }

    @Test
    void aFortunePickaxeIsRightForDiamonds() {
        hotbar(fortunePick());
        assertNull(ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.FORTUNE));
    }

    @Test
    void aSilkTouchPickaxeIsWrongForDiamonds() {
        hotbar(silkPick());
        assertEquals(BlockRegistry.DIAMOND_ORE, ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.FORTUNE));
    }

    @Test
    void aSilkTouchPickaxeIsRightForTheOreItself() {
        hotbar(silkPick());
        assertNull(ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.SILK_TOUCH));
    }

    @Test
    void aFortunePickaxeIsWrongForTheOreItself() {
        hotbar(fortunePick());
        assertEquals(BlockRegistry.DIAMOND_ORE, ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.SILK_TOUCH));
    }

    @Test
    void cobbledDeepslateJustNeedsNoSilkTouch() {
        hotbar(fortunePick());
        assertNull(ToolChoice.firstWithWrongTool(DEEPSLATE, ToolNeed.NO_SILK_TOUCH));
        hotbar(plainPick());
        assertNull(ToolChoice.firstWithWrongTool(DEEPSLATE, ToolNeed.NO_SILK_TOUCH));
        hotbar(silkPick());
        assertEquals(BlockRegistry.DEEPSLATE, ToolChoice.firstWithWrongTool(DEEPSLATE, ToolNeed.NO_SILK_TOUCH));
    }

    /** This is why the wrong one has to come off the hotbar: Zenith picks by speed and slot, not by what's wanted. */
    @Test
    void zenithPicksTheFirstOfTwoEquallyFastPickaxesWhateverTheyHave() {
        hotbar(silkPick(), fortunePick());
        assertEquals(0, ToolChoice.hotbarSlotFor(BlockRegistry.DIAMOND_ORE));
        assertEquals(BlockRegistry.DIAMOND_ORE, ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.FORTUNE));

        hotbar(fortunePick(), silkPick());
        assertEquals(0, ToolChoice.hotbarSlotFor(BlockRegistry.DIAMOND_ORE));
        assertNull(ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.FORTUNE));
    }

    @Test
    void aFasterWrongPickaxeGetsPickedAheadOfASlowerRightOne() {
        hotbar(stoneFortunePick(), plainPick());
        assertEquals(1, ToolChoice.hotbarSlotFor(BlockRegistry.DIAMOND_ORE));
        assertEquals(BlockRegistry.DIAMOND_ORE, ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.FORTUNE));
    }

    @Test
    void noToolAtAllIsWrongWhenItMatters() {
        hotbar();
        assertEquals(BlockRegistry.DEEPSLATE, ToolChoice.firstWithWrongTool(DEEPSLATE, ToolNeed.NO_SILK_TOUCH));
        hotbar(new ItemStack(ItemRegistry.COBBLED_DEEPSLATE.id(), 1));
        assertEquals(BlockRegistry.DEEPSLATE, ToolChoice.firstWithWrongTool(DEEPSLATE, ToolNeed.NO_SILK_TOUCH));
    }

    @Test
    void nothingIsWrongWhenItDoesntMatter() {
        hotbar(silkPick());
        assertNull(ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.ANY));
        hotbar();
        assertNull(ToolChoice.firstWithWrongTool(DIAMOND_ORE, ToolNeed.ANY));
    }

    @Test
    void aToolHasToBeForTheBlockAsWellAsHaveTheRightEnchantments() {
        ItemStack axe = ToolRulesTest.enchanted(ItemRegistry.NETHERITE_AXE, EnchantmentRegistry.FORTUNE.get());
        assertFalse(ToolChoice.suits(axe, BlockRegistry.DIAMOND_ORE, ToolNeed.FORTUNE));
        assertFalse(ToolChoice.suits(axe, BlockRegistry.DEEPSLATE, ToolNeed.NO_SILK_TOUCH));
        assertTrue(ToolChoice.suits(fortunePick(), BlockRegistry.DIAMOND_ORE, ToolNeed.FORTUNE));
        assertFalse(ToolChoice.suits(new ItemStack(ItemRegistry.DIAMOND.id(), 1), BlockRegistry.DIAMOND_ORE, ToolNeed.NO_SILK_TOUCH));
        assertFalse(ToolChoice.suits(null, BlockRegistry.DIAMOND_ORE, ToolNeed.ANY));
    }

    @Test
    void itLooksUpTheBlocksWhereTheToolMatters() {
        assertEquals(
            List.of(BlockRegistry.DIAMOND_ORE, BlockRegistry.DEEPSLATE_DIAMOND_ORE),
            ToolChoice.blocksNeedingARightTool(List.of("diamond", "ancient_debris"))
        );
        assertEquals(List.of(), ToolChoice.blocksNeedingARightTool(List.of("ancient_debris")));
    }
}
