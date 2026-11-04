package com.zenithplugins.blockminer.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenithplugins.blockminer.module.BlockMinerModule;
import com.zenithplugins.blockminer.rules.TargetItems;

import java.util.Collection;
import java.util.TreeSet;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.BlockArgument.block;
import static com.zenith.command.brigadier.BlockArgument.getBlock;
import static com.zenith.command.brigadier.ItemArgument.getItem;
import static com.zenith.command.brigadier.ItemArgument.item;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * `blockminer on/off`, `blockminer add/remove <block>`, `blockminer clear`, `blockminer fill on/off`,
 * `blockminer craft on/off`, `blockminer toolDurability <count>`, `blockminer room <stacks>`,
 * `blockminer keep add/remove <item>`.
 */
public class BlockMinerCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("blockminer")
            .category(CommandCategory.MODULE)
            .description("""
                Mines chosen blocks around the bot, stores what it mines in shulker boxes from the \
                ender chest, and swaps worn tools for fresh ones from a tool shulker box
                """)
            .usageLines(
                "on/off",
                "add <block>",
                "remove <block>",
                "clear",
                "fill on/off",
                "craft on/off",
                "toolDurability <count>",
                "room <stacks>",
                "keep add <item>",
                "keep remove <item>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("blockminer")
            .then(argument("toggle", toggle()).executes(c -> {
                boolean on = getToggle(c, "toggle");
                if (on && PLUGIN_CONFIG.blocks.isEmpty()) {
                    c.getSource().getEmbed()
                        .title("Nothing To Mine")
                        .description("Choose a block first with `blockminer add <block>`.");
                    return ERROR;
                }
                BlockMinerModule module = MODULE.get(BlockMinerModule.class);
                PLUGIN_CONFIG.enabled = on;
                module.syncEnabledFromConfig();
                var embed = c.getSource().getEmbed().title("Block Miner " + toggleStrCaps(on));
                if (!on && module.errandRunning()) {
                    embed.description("It'll finish its current errand first, so nothing is left on the ground.");
                }
                return OK;
            }))
            .then(literal("add").then(argument("block", block()).executes(c -> {
                String name = getBlock(c, "block").name();
                if (!PLUGIN_CONFIG.blocks.contains(name)) PLUGIN_CONFIG.blocks.add(name);
                MODULE.get(BlockMinerModule.class).blocksChanged();
                c.getSource().getEmbed().title("Added " + name);
            })))
            .then(literal("remove").then(argument("block", block()).executes(c -> {
                String name = getBlock(c, "block").name();
                PLUGIN_CONFIG.blocks.remove(name);
                MODULE.get(BlockMinerModule.class).blocksChanged();
                c.getSource().getEmbed().title("Removed " + name);
            })))
            .then(literal("clear").executes(c -> {
                PLUGIN_CONFIG.blocks.clear();
                MODULE.get(BlockMinerModule.class).blocksChanged();
                c.getSource().getEmbed().title("Block List Cleared");
            }))
            .then(literal("fill").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.fillEmptySlots = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Filling Empty Slots " + toggleStrCaps(PLUGIN_CONFIG.fillEmptySlots));
            })))
            .then(literal("craft").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.craftIntoBlocks = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Crafting Into Blocks " + toggleStrCaps(PLUGIN_CONFIG.craftIntoBlocks));
            })))
            .then(literal("toolDurability").then(argument("count", integer(1, 500)).executes(c -> {
                PLUGIN_CONFIG.toolDurabilityThreshold = getInteger(c, "count");
                c.getSource().getEmbed().title("Tool Durability Set");
            })))
            .then(literal("room").then(argument("stacks", integer(2, 30)).executes(c -> {
                PLUGIN_CONFIG.stacksOfRoomToKeep = getInteger(c, "stacks");
                c.getSource().getEmbed().title("Room To Keep Set");
            })))
            .then(literal("keep")
                .then(literal("add").then(argument("item", item()).executes(c -> {
                    String name = getItem(c, "item").name();
                    if (!PLUGIN_CONFIG.keepItems.contains(name)) PLUGIN_CONFIG.keepItems.add(name);
                    c.getSource().getEmbed().title("Keeping " + name);
                })))
                .then(literal("remove").then(argument("item", item()).executes(c -> {
                    String name = getItem(c, "item").name();
                    PLUGIN_CONFIG.keepItems.remove(name);
                    c.getSource().getEmbed().title("No Longer Keeping " + name);
                }))));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Status", MODULE.get(BlockMinerModule.class).status())
            .addField("Blocks", listOrNone(PLUGIN_CONFIG.blocks))
            .addField("Fill Empty Slots", toggleStr(PLUGIN_CONFIG.fillEmptySlots))
            .addField("Target Items", listOrNone(new TreeSet<>(TargetItems.itemsFor(PLUGIN_CONFIG.blocks))))
            .addField("Craft Into Blocks", toggleStr(PLUGIN_CONFIG.craftIntoBlocks))
            .addField("Swap Tools At Durability", PLUGIN_CONFIG.toolDurabilityThreshold)
            .addField("Store With Room For", PLUGIN_CONFIG.stacksOfRoomToKeep + " stacks")
            .addField("Keep Items", listOrNone(PLUGIN_CONFIG.keepItems));
    }

    private static String listOrNone(Collection<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
