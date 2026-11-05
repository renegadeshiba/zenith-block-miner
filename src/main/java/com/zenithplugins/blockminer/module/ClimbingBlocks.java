package com.zenithplugins.blockminer.module;

import com.zenithplugins.blockminer.rules.InventoryPlan;

import java.util.HashSet;
import java.util.Set;

import static com.zenith.Globals.CONFIG;

/**
 * Lets the pathfinder build with what the bot is carrying, for when it can't get out of somewhere
 * without it. It only builds with blocks on a list in its settings, which leaves out things like cobbled
 * deepslate, so a bot with an inventory full of them can still be stuck in a pit. This adds them for just
 * as long as it takes to get out, and takes off only what it added.
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

    /** Returns true if the pathfinder now has something it can build with. */
    boolean allow() {
        if (!CONFIG.client.extra.pathfinder.allowPlace) return false;
        Set<String> list = CONFIG.client.extra.pathfinder.acceptableThrowawayItems;
        Set<String> held = InventoryPlan.heldItemNames(bot.playerStacks());
        for (String name : held) {
            if (USABLE.contains(name) && list.add(name)) added.add(name);
        }
        return held.stream().anyMatch(list::contains);
    }

    void stopAllowing() {
        CONFIG.client.extra.pathfinder.acceptableThrowawayItems.removeAll(added);
        added.clear();
    }
}
