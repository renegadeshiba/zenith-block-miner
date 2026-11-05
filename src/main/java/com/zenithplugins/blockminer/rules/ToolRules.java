package com.zenithplugins.blockminer.rules;

import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolType;
import com.zenith.util.ItemUtil;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Which tools count as worn, and which fresh tool best replaces a worn one. */
public final class ToolRules {

    private static final long SAME_ITEM_SCORE = 200_000L;
    private static final long SAME_ENCHANTMENTS_SCORE = 100_000L;

    private ToolRules() {
    }

    /** The kind of mining tool this is, or null if it isn't one. Swords are left to combat modules. */
    public static @Nullable ToolType miningToolType(@Nullable ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return null;
        ItemData item = ItemRegistry.REGISTRY.get(stack.getId());
        if (item == null || item.toolTag() == null) return null;
        ToolType type = item.toolTag().type();
        return type == ToolType.SWORD ? null : type;
    }

    /** True for a mining tool with {@code threshold} durability left or less. */
    public static boolean isWorn(@Nullable ItemStack stack, int threshold) {
        if (miningToolType(stack) == null) return false;
        if (stack.getDataComponentsOrEmpty().contains(DataComponentTypes.UNBREAKABLE)) return false;
        return ItemUtil.getMaxDamage(stack) > 0 && ItemUtil.getDamageUntilBreak(stack) <= threshold;
    }

    /** True if this tool will give the item that's wanted. */
    public static boolean meets(ToolNeed need, @Nullable ItemStack stack) {
        return switch (need) {
            case ANY -> true;
            case NO_SILK_TOUCH -> !hasEnchantment(stack, EnchantmentRegistry.SILK_TOUCH.get());
            case FORTUNE -> hasEnchantment(stack, EnchantmentRegistry.FORTUNE.get())
                && !hasEnchantment(stack, EnchantmentRegistry.SILK_TOUCH.get());
            case SILK_TOUCH -> hasEnchantment(stack, EnchantmentRegistry.SILK_TOUCH.get());
        };
    }

    private static boolean hasEnchantment(@Nullable ItemStack stack, EnchantmentData enchantment) {
        if (stack == null) return false;
        ItemEnchantments enchantments = stack.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS);
        return enchantments != null && enchantments.getEnchantments().containsKey(enchantment.id());
    }

    /**
     * How well {@code candidate} replaces {@code worn}, higher being better, or -1 if it can't. It has
     * to be the same kind of tool, not worn itself, and still give the item that's wanted. The same
     * item with the same enchantments is preferred, and after that whichever has the most durability.
     */
    public static long replacementScore(@Nullable ItemStack candidate, ItemStack worn, int threshold, ToolNeed need) {
        ToolType type = miningToolType(worn);
        if (type == null || miningToolType(candidate) != type || isWorn(candidate, threshold)) return -1;
        if (!meets(need, candidate)) return -1;
        long score = ItemUtil.getDamageUntilBreak(candidate);
        if (candidate.getId() == worn.getId()) score += SAME_ITEM_SCORE;
        if (Objects.equals(enchantments(candidate), enchantments(worn))) score += SAME_ENCHANTMENTS_SCORE;
        return score;
    }

    private static @Nullable Object enchantments(ItemStack stack) {
        ItemEnchantments enchantments = stack.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS);
        return enchantments == null || enchantments.getEnchantments().isEmpty() ? null : enchantments.getEnchantments();
    }
}
