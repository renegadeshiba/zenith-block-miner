package com.zenithplugins.blockminer;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration POJO for the plugin.
 *
 * ZenithProxy saves/loads this automatically to/from a JSON file whenever a command runs, or the
 * proxy starts/stops (see PluginAPI#registerConfig). All fields must be public and mutable.
 */
public class BlockMinerConfig {

    /** Master on/off switch for mining. */
    public boolean enabled = false;

    /** Block names to mine, such as "ancient_debris". Ores and their deepslate forms are separate blocks. */
    public List<String> blocks = new ArrayList<>();

    /**
     * Fill every empty inventory slot with one of the item being mined, so nothing else can be picked
     * up and junk stays on the ground.
     */
    public boolean fillEmptySlots = true;

    /** Craft items like diamonds into blocks, 9 at a time, before storing them. */
    public boolean craftIntoBlocks = true;

    /** Swap the tool in hand for a fresh one once it has this much durability left, or less. */
    public int toolDurabilityThreshold = 5;

    /**
     * Start storing when there's only room left for this many stacks of what's being mined. At least
     * 2, because a slot has to be cleared by merging stacks to carry the shulker box.
     */
    public int stacksOfRoomToKeep = 2;

    /**
     * Items never stored, by name. Anything with durability, food and shulker boxes are always kept
     * too, so this is for the rest of what the bot needs to carry.
     */
    public List<String> keepItems = new ArrayList<>(List.of("ender_chest", "crafting_table", "totem_of_undying"));

    /**
     * A shulker box the bot placed and hasn't picked back up yet. It can hold a lot of items, so this
     * is remembered across disconnects and the bot goes back for it. Not meant to be hand-edited.
     */
    public @Nullable PlacedBlock placedShulker = null;

    public static class PlacedBlock {
        public String world = "";
        public int x;
        public int y;
        public int z;

        public PlacedBlock() {
        }

        public PlacedBlock(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
