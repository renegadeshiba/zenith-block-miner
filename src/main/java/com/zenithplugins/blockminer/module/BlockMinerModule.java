package com.zenithplugins.blockminer.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenithplugins.blockminer.BlockMinerConfig.PlacedBlock;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import com.zenithplugins.blockminer.rules.ToolRules;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.saveConfigAsync;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Keeps the pathfinder mining for the chosen items, and sends the bot on an errand when the inventory
 * fills up or the tool in hand is nearly broken. Errands run on their own thread (see {@link Bot}),
 * while this module only watches and decides from the client tick.
 */
public class BlockMinerModule extends Module {

    private static final int NOTHING_FOUND_RETRY_TICKS = 20 * 30;
    private static final int MINING_ENDED_RETRY_TICKS = 20 * 5;
    // Mining that stops this soon after starting never found much to mine.
    private static final int NOTHING_FOUND_TICKS = 20 * 10;
    // Zenith's miner searches every 2.5 seconds, so a change has to be caught well within that.
    private static final int SEARCH_CHECK_INTERVAL_TICKS = 10;
    // A check that takes a while waits this many times as long before the next, so it stays in the background.
    private static final int SLOW_SEARCH_CHECK_BACKOFF = 20;
    private static final int NANOS_PER_TICK = 50_000_000;
    private static final int FAILED_ERRAND_RETRY_TICKS = 20 * 30;
    private static final int MAX_FAILED_ERRANDS = 3;
    // While the bot is still building up target items, how often it stops to fill more slots with them.
    private static final int FILL_SLOTS_INTERVAL_TICKS = 20 * 30;
    private static final int TOOL_CHECK_INTERVAL_TICKS = 20;
    private static final int STARTUP_WAIT_TICKS = 20 * 5;

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
    private volatile boolean wrongTool;
    // Zero makes the next tick check, which comes before any mining starts.
    private volatile long nextToolCheckTick;

    private volatile @Nullable PathingRequestFuture mining;
    private long miningStartedTick;
    private volatile long nextMineTick;
    private volatile boolean reportedNothingFound;
    private boolean reportedBreakingOff;
    private boolean reportedNothingChosen;

    // True while walking to the nearest blocks because Zenith's miner isn't safe to use from here.
    private boolean walkingToBlocks;
    private boolean reportedNoWayToBlocks;
    private @Nullable BlockOptionalMetaLookup miningFilter;
    private volatile boolean zenithSearchTooBig;
    private volatile boolean searchCheckRunning;
    private volatile long nextSearchCheckTick;

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
        nextToolCheckTick = 0;
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
        // The world and inventory are still arriving, so what the bot sees isn't to be trusted yet.
        nextMineTick = tick + STARTUP_WAIT_TICKS;
        nextErrandTick = tick + STARTUP_WAIT_TICKS;
    }

    private void handleStopped(ClientBotTick.Stopped event) {
        mining = null;
        status = "Paused";
    }

    void handleTick(ClientBotTick event) {
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
        nextToolCheckTick = 0;
        reportedNothingFound = false;
    }

    // ----------------------------------------------------------------------------------------
    // mining
    // ----------------------------------------------------------------------------------------

    private void keepMining() {
        // A command on another thread can clear this at any moment.
        PathingRequestFuture current = mining;
        if (current != null) {
            if (!current.isCompleted()) {
                switchWayOfMiningIfNeeded();
                return;
            }
            miningEnded(current.getNow());
        }
        if (tick < nextMineTick || BARITONE.isActive()) return;

        if (!CONFIG.client.extra.pathfinder.allowBreak) {
            if (!reportedBreakingOff) warn("The pathfinder's allowBreak setting is off, so the bot can't mine");
            reportedBreakingOff = true;
            status = "Waiting: the pathfinder isn't allowed to break blocks";
            return;
        }
        reportedBreakingOff = false;

        Block[] blocks = TargetItems.blocksToMine(PLUGIN_CONFIG.wantedItems).stream()
            .map(BlockRegistry.REGISTRY::get)
            .filter(Objects::nonNull)
            .toArray(Block[]::new);
        if (blocks.length == 0) {
            status = "Waiting: nothing chosen to mine. Choose something with `blockminer add <item>`";
            if (!reportedNothingChosen) {
                warn("Nothing is chosen to mine, so the bot is waiting. Choose something with `blockminer add <item>`");
                discordAndIngameNotification(Embed.builder()
                    .title("Block Miner Has Nothing To Mine")
                    .description("It's on, but no items are chosen, so it's leaving the inventory alone and waiting."
                        + "\nChoose something with `blockminer add <item>`.")
                    .errorColor());
            }
            reportedNothingChosen = true;
            return;
        }
        reportedNothingChosen = false;

        if (TargetItems.combinedToolNeed(PLUGIN_CONFIG.wantedItems) == null) {
            status = "Waiting: the chosen items need different tools, so remove one with `blockminer remove`";
            return;
        }
        if (toolNeed() != ToolNeed.ANY && !CONFIG.client.extra.pathfinder.autoTool) {
            status = "Waiting: the pathfinder's autoTool setting is off, so it can't use the right tool";
            return;
        }

        startMining(blocks);
    }

    /**
     * Uses Zenith's miner wherever its search is safe to run, as it also explores when there's nothing
     * nearby, picks up drops and gets out of lava. Otherwise walks to the nearest blocks instead, which
     * mines them as it goes, until it is. See {@link BlockScan} for what goes wrong.
     */
    private void startMining(Block[] blocks) {
        BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(blocks);
        miningFilter = filter;
        miningStartedTick = tick;
        nextSearchCheckTick = tick + SEARCH_CHECK_INTERVAL_TICKS;
        zenithSearchTooBig = !BlockScan.zenithSearchStaysSmall(filter);

        if (!zenithSearchTooBig) {
            walkingToBlocks = false;
            reportedNoWayToBlocks = false;
            mining = BARITONE.mine(blocks);
            status = "Mining";
            return;
        }

        String names = Arrays.stream(blocks).map(Block::name).collect(Collectors.joining(" or "));
        if (!walkingToBlocks) {
            info("There's too much {} in the loaded chunks for the pathfinder's own search to cope with from this"
                + " height, so walking to the nearest first", names);
        }
        walkingToBlocks = true;
        Goal goal = BlockScan.walkToNearest(filter);
        if (goal == null) {
            couldntGetToBlocks();
            return;
        }
        mining = BARITONE.pathTo(goal);
        status = "Walking to the nearest " + names;
    }

    /**
     * What's safe changes as the bot moves, and Zenith's miner keeps searching for as long as it runs.
     * Only the check is on another thread, because going through a rare block's chunks takes a while.
     */
    private void switchWayOfMiningIfNeeded() {
        if (zenithSearchTooBig != walkingToBlocks) {
            BARITONE.stop();
            mining = null;
            nextMineTick = 0;
            return;
        }
        if (searchCheckRunning || tick < nextSearchCheckTick) return;

        BlockOptionalMetaLookup filter = miningFilter;
        searchCheckRunning = true;
        EXECUTOR.execute(() -> {
            long started = System.nanoTime();
            try {
                zenithSearchTooBig = !BlockScan.zenithSearchStaysSmall(filter);
            } finally {
                long tookTicks = (System.nanoTime() - started) / NANOS_PER_TICK;
                nextSearchCheckTick = tick + Math.max(SEARCH_CHECK_INTERVAL_TICKS, tookTicks * SLOW_SEARCH_CHECK_BACKOFF);
                searchCheckRunning = false;
            }
        });
    }

    private void couldntGetToBlocks() {
        if (!reportedNoWayToBlocks) {
            warn("Couldn't get to the nearest blocks to mine; trying again every {} seconds", MINING_ENDED_RETRY_TICKS / 20);
        }
        reportedNoWayToBlocks = true;
        status = "Waiting: couldn't get to the nearest blocks to mine";
        nextMineTick = tick + MINING_ENDED_RETRY_TICKS;
    }

    private void miningEnded(boolean succeeded) {
        mining = null;
        if (walkingToBlocks) {
            if (succeeded) {
                reportedNoWayToBlocks = false;
                nextMineTick = 0;
            } else {
                couldntGetToBlocks();
            }
            return;
        }
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
        // With nothing chosen, the only thing to do is not leave a shulker box lying around.
        if (PLUGIN_CONFIG.wantedItems.isEmpty()) return bot.placedShulkerIsInThisWorld();

        return ToolRules.isWorn(bot.heldStack(), PLUGIN_CONFIG.toolDurabilityThreshold)
            || storage.isNeeded()
            || bot.placedShulkerIsInThisWorld()
            || wrongToolOnHotbar()
            || (tick >= nextFillTick && (space.isWorthFilling() || space.isWorthThrowingJunk()));
    }

    /** What the tool has to be like for the chosen items. Conflicting items don't get mined at all. */
    private static ToolNeed toolNeed() {
        ToolNeed need = TargetItems.combinedToolNeed(PLUGIN_CONFIG.wantedItems);
        return need != null ? need : ToolNeed.ANY;
    }

    /**
     * True if the pathfinder would mine with a tool that gives the wrong item. Only looked at now and
     * then, since it has to work out each tool's speed against each block.
     */
    private boolean wrongToolOnHotbar() {
        if (tick >= nextToolCheckTick) {
            nextToolCheckTick = tick + TOOL_CHECK_INTERVAL_TICKS;
            wrongTool = CONFIG.client.extra.pathfinder.autoTool && ToolChoice.firstWithWrongTool(
                ToolChoice.blocksNeedingARightTool(PLUGIN_CONFIG.wantedItems), toolNeed()) != null;
        }
        return wrongTool;
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

            if (!PLUGIN_CONFIG.wantedItems.isEmpty()) {
                if (ToolRules.isWorn(bot.heldStack(), PLUGIN_CONFIG.toolDurabilityThreshold)) {
                    status = "Swapping a worn tool";
                    toolSwap.run(bot.heldStack(), toolNeed());
                }
                if (CONFIG.client.extra.pathfinder.autoTool) {
                    status = "Getting the right tool";
                    toolSwap.ensureRightTool(ToolChoice.blocksNeedingARightTool(PLUGIN_CONFIG.wantedItems), toolNeed());
                    wrongTool = false;
                }
                if (storage.isNeeded()) {
                    status = "Storing items";
                    storage.run();
                }
                space.forgetItemsNoLongerCarried();
                status = "Filling inventory slots";
                if (space.isWorthThrowingJunk()) space.throwAwayJunk();
                space.fillEmptySlots();
            }

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
            // An errand moves things around, so look at the hotbar again before mining.
            nextToolCheckTick = 0;
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
