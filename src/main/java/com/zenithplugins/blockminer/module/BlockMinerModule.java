package com.zenithplugins.blockminer.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenithplugins.blockminer.BlockMinerConfig.PlacedBlock;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.ToolRules;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.saveConfigAsync;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Keeps the pathfinder mining the chosen blocks, and sends the bot on an errand when the inventory
 * fills up or the tool in hand is nearly broken. Errands run on their own thread (see {@link Bot}),
 * while this module only watches and decides from the client tick.
 */
public class BlockMinerModule extends Module {

    private static final int NOTHING_FOUND_RETRY_TICKS = 20 * 30;
    private static final int MINING_ENDED_RETRY_TICKS = 20 * 5;
    // Mining that stops this soon after starting never found much to mine.
    private static final int NOTHING_FOUND_TICKS = 20 * 10;
    private static final int FAILED_ERRAND_RETRY_TICKS = 20 * 30;
    private static final int MAX_FAILED_ERRANDS = 3;
    // While the bot is still building up target items, how often it stops to fill more slots with them.
    private static final int FILL_SLOTS_INTERVAL_TICKS = 20 * 30;

    private final Bot bot = new Bot(this);
    private final InventorySpace space = new InventorySpace(this, bot);
    private final CraftingErrand crafting = new CraftingErrand(this, bot, space);
    private final StorageErrand storage = new StorageErrand(this, bot, space, crafting);
    private final ToolSwapErrand toolSwap = new ToolSwapErrand(this, bot, space, storage);

    private volatile @Nullable Thread errand;
    private volatile String status = "Off";
    private volatile long tick;
    private volatile long nextErrandTick;
    private long nextFillTick;
    private int failedErrands;

    private volatile @Nullable PathingRequestFuture mining;
    private long miningStartedTick;
    private volatile long nextMineTick;
    private volatile boolean reportedNothingFound;
    private boolean reportedBreakingOff;

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleTick),
            of(ClientBotTick.Starting.class, this::handleStarting),
            of(ClientBotTick.Stopped.class, this::handleStopped)
        );
    }

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    @Override
    public void onEnable() {
        failedErrands = 0;
        nextErrandTick = 0;
        nextFillTick = 0;
        nextMineTick = 0;
        reportedNothingFound = false;
        status = "Starting";
    }

    @Override
    public void onDisable() {
        stopMining();
        status = errand != null ? "Finishing its current errand, then off" : "Off";
    }

    public String status() {
        return status;
    }

    public boolean errandRunning() {
        return errand != null;
    }

    private void handleStarting(ClientBotTick.Starting event) {
        mining = null;
        nextMineTick = 0;
    }

    private void handleStopped(ClientBotTick.Stopped event) {
        mining = null;
        status = "Paused";
    }

    private void handleTick(ClientBotTick event) {
        tick++;
        if (errand != null) return;
        if (storage.isStuck()) {
            stopAndNotify("The inventory is full of things it isn't allowed to store, so it can't pick up"
                + " what it's mining. Make room, or check the keep list with `blockminer`");
            return;
        }
        if (needsErrand()) {
            // Waiting out a failed errand still means not mining: the next few blocks could be the
            // ones that break the tool.
            stopMining();
            if (tick >= nextErrandTick) startErrand();
        } else {
            keepMining();
        }
    }

    /** Mining carries on with the blocks it started with, so a change to the list needs a restart. */
    public void blocksChanged() {
        stopMining();
        nextMineTick = 0;
        reportedNothingFound = false;
    }

    // ----------------------------------------------------------------------------------------
    // mining
    // ----------------------------------------------------------------------------------------

    private void keepMining() {
        if (mining != null) {
            if (!mining.isCompleted()) return;
            miningEnded();
        }
        if (tick < nextMineTick || BARITONE.isActive()) return;

        if (!CONFIG.client.extra.pathfinder.allowBreak) {
            if (!reportedBreakingOff) warn("The pathfinder's allowBreak setting is off, so the bot can't mine");
            reportedBreakingOff = true;
            status = "Waiting: the pathfinder isn't allowed to break blocks";
            return;
        }
        reportedBreakingOff = false;

        Block[] blocks = PLUGIN_CONFIG.blocks.stream()
            .map(BlockRegistry.REGISTRY::get)
            .filter(Objects::nonNull)
            .toArray(Block[]::new);
        if (blocks.length == 0) {
            status = "Waiting: no blocks to mine";
            return;
        }

        mining = BARITONE.mine(blocks);
        miningStartedTick = tick;
        status = "Mining";
    }

    private void miningEnded() {
        mining = null;
        if (tick - miningStartedTick < NOTHING_FOUND_TICKS) {
            if (!reportedNothingFound) {
                info("Nothing to mine in the loaded chunks; looking again every {} seconds", NOTHING_FOUND_RETRY_TICKS / 20);
            }
            reportedNothingFound = true;
            status = "Waiting: nothing to mine in the loaded chunks";
            nextMineTick = tick + NOTHING_FOUND_RETRY_TICKS;
        } else {
            reportedNothingFound = false;
            nextMineTick = tick + MINING_ENDED_RETRY_TICKS;
        }
    }

    private void stopMining() {
        if (mining != null) BARITONE.stop();
        mining = null;
    }

    // ----------------------------------------------------------------------------------------
    // errands
    // ----------------------------------------------------------------------------------------

    private boolean needsErrand() {
        return ToolRules.isWorn(bot.heldStack(), PLUGIN_CONFIG.toolDurabilityThreshold)
            || storage.isNeeded()
            || bot.placedShulkerIsInThisWorld()
            || (tick >= nextFillTick && space.isWorthFilling());
    }

    private void startErrand() {
        nextFillTick = tick + FILL_SLOTS_INTERVAL_TICKS;
        int[] start = bot.position();
        Thread thread = new Thread(() -> runErrand(start), "BlockMiner-Errand");
        thread.setDaemon(true);
        errand = thread;
        thread.start();
    }

    private void runErrand(int[] start) {
        try {
            collectPlacedShulker();
            storage.returnCarriedShulkers();

            if (ToolRules.isWorn(bot.heldStack(), PLUGIN_CONFIG.toolDurabilityThreshold)) {
                status = "Swapping a worn tool";
                toolSwap.run(bot.heldStack());
            }
            if (storage.isNeeded()) {
                status = "Storing items";
                storage.run();
            }
            status = "Filling inventory slots";
            space.fillEmptySlots();

            bot.walkBackTo(start);
            failedErrands = 0;
            status = PLUGIN_CONFIG.enabled ? "Mining" : "Off";
        } catch (ErrandAborted e) {
            info("Errand interrupted: {}. It'll pick up where it left off", e.getMessage());
            status = "Paused";
        } catch (ErrandFailed e) {
            if (e.worthRetrying()) {
                errandFailed(e.getMessage());
            } else {
                stopAndNotify(e.getMessage());
            }
        } catch (RuntimeException e) {
            error("Errand failed unexpectedly", e);
            errandFailed(String.valueOf(e));
        } finally {
            try {
                bot.closeContainer();
            } catch (RuntimeException e) {
                debug("Couldn't close the container after an errand: {}", e.getMessage());
            }
            BARITONE.stop();
            // Last, because the next tick can start mining as soon as this is clear.
            errand = null;
        }
    }

    /** Goes back for a shulker box left on the ground by an errand that got interrupted. */
    private void collectPlacedShulker() {
        PlacedBlock placed = PLUGIN_CONFIG.placedShulker;
        if (placed == null) return;
        if (!bot.placedShulkerIsInThisWorld()) {
            warn("A shulker box is still placed at [{}, {}, {}] in {}", placed.x, placed.y, placed.z, placed.world);
            return;
        }

        status = "Collecting a shulker box left behind";
        int[] pos = {placed.x, placed.y, placed.z};
        info("Going back for the shulker box left at [{}, {}, {}]", placed.x, placed.y, placed.z);
        bot.walkNear(pos, 3, "Walking back to the shulker box");

        String blockName = bot.blockNameAt(pos);
        ItemData item = blockName == null ? null : ItemRegistry.REGISTRY.get(blockName);
        if (item != null && StorageRules.isShulkerName(item.name())) {
            space.breakAndPickUp(pos, item);
        } else if (!space.collectDroppedShulker(pos)) {
            // Interrupted after breaking it, with no sign of it on the ground either.
            warn("The shulker box left at [{}, {}, {}] isn't there any more", placed.x, placed.y, placed.z);
        }
        bot.forgetPlacedShulker();
    }

    private void errandFailed(String reason) {
        failedErrands++;
        if (failedErrands >= MAX_FAILED_ERRANDS) {
            stopAndNotify(reason + " (tried " + failedErrands + " times)");
            return;
        }
        warn("{}; trying again in {} seconds", reason, FAILED_ERRAND_RETRY_TICKS / 20);
        status = "Waiting to retry: " + reason;
        nextErrandTick = tick + FAILED_ERRAND_RETRY_TICKS;
    }

    /** Turns mining off for something only a person can fix, and tells them why. */
    private void stopAndNotify(String reason) {
        warn("Stopping: {}", reason);
        PLUGIN_CONFIG.enabled = false;
        syncEnabledFromConfig();
        saveConfigAsync();
        status = "Stopped: " + reason;
        discordAndIngameNotification(Embed.builder()
            .title("Block Miner Stopped")
            .description(reason + "\nRun `blockminer on` once that's sorted.")
            .errorColor());
    }
}
