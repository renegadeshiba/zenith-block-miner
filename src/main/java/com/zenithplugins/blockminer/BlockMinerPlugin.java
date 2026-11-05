package com.zenithplugins.blockminer;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenithplugins.blockminer.command.BlockMinerCommand;
import com.zenithplugins.blockminer.module.BlockMinerModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * Mines for the items you choose around the bot. When the inventory fills up it crafts items like diamonds
 * into blocks and stores everything in shulker boxes from the ender chest, and it swaps worn tools
 * for fresh ones from a tool shulker in the ender chest.
 *
 * Settings are in BlockMinerConfig (plugins/config/block-miner.json next to the ZenithProxy jar).
 */
@Plugin(
    id = BlockMinerPlugin.PLUGIN_ID,
    version = BlockMinerPlugin.VERSION,
    description = "Mines for the items you choose, stores what it mines in shulker boxes from the ender chest, "
        + "and swaps worn tools for fresh ones.",
    url = "https://github.com/renegadeshiba/zenith-block-miner",
    authors = {"renegadeshiba"},
    mcVersions = {BlockMinerPlugin.MC_VERSION}
)
public class BlockMinerPlugin implements ZenithProxyPlugin {

    public static final String PLUGIN_ID = "block-miner";
    public static final String VERSION = "1.1.3";
    public static final String MC_VERSION = "1.21.4";

    public static BlockMinerConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("BlockMiner loading...");

        PLUGIN_CONFIG = pluginAPI.registerConfig(PLUGIN_ID, BlockMinerConfig.class);

        pluginAPI.registerModule(new BlockMinerModule());
        pluginAPI.registerCommand(new BlockMinerCommand());

        LOG.info("BlockMiner loaded!");
    }
}
