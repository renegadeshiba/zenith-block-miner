package com.zenithplugins.blockminer.module;

import com.zenithplugins.blockminer.rules.InventoryPlan;

import java.util.HashSet;
import java.util.Set;

import static com.zenith.Globals.CONFIG;

/**
 * Lets the pathfinder build with what the bot is carrying, for when it can't find a way without it. It only
 * builds with blocks on a list in its settings, which leaves out things like cobbled deepslate, so a bot with
 * an inventory full of them can still be stuck in a pit. This adds them for just as long as it needs them, and
 * takes off only what it added.
 *
 * <p>It's only ever for when digging won't do. Left on the list, the pathfinder climbs between levels by building
 * pillars out in the open instead of digging stairs through the rock, and an arrow or a creeper up there is a
 * long fall with the whole inventory.
 */
final class ClimbingBlocks {

    // Common, solid, and won't fall or hurt the bot.
    private static final Set<String> USABLE = Set.of(
        "cobblestone", "stone", "cobbled_deepslate", "deepslate", "tuff", "andesite", "diorite", "granite",
        "calcite", "dripstone_block", "dirt", "coarse_dirt", "sandstone", "red_sandstone", "netherrack",
        "blackstone", "basalt", "smooth_basalt", "end_stone"
    );

    private final Bot bot;
    private final Set<String> added = new HashSet<>();

    ClimbingBlocks(Bot bot) {
        this.bot = bot;
    }

    /** Returns true if this gave the pathfinder something to build with, when it had nothing before. */
    boolean allow() {
        if (!CONFIG.client.extra.pathfinder.allowPlace) return false;
        boolean couldBuildAlready = canBuild();
        Set<String> list = CONFIG.client.extra.pathfinder.acceptableThrowawayItems;
        for (String name : InventoryPlan.heldItemNames(bot.playerStacks())) {
            if (USABLE.contains(name) && list.add(name)) added.add(name);
        }
        return !couldBuildAlready && canBuild();
    }

    /** True if the pathfinder has something it can build with. */
    boolean canBuild() {
        if (!CONFIG.client.extra.pathfinder.allowPlace) return false;
        Set<String> list = CONFIG.client.extra.pathfinder.acceptableThrowawayItems;
        return InventoryPlan.heldItemNames(bot.playerStacks()).stream().anyMatch(list::contains);
    }

    void stopAllowing() {
        CONFIG.client.extra.pathfinder.acceptableThrowawayItems.removeAll(added);
        added.clear();
    }
}
