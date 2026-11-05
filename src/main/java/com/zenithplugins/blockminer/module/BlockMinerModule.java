package com.zenithplugins.blockminer.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalY;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.saveConfigAsync;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * Keeps the bot mining for the chosen items, one pathfinder goal at a time (see {@link BlockScan}), and
 * sends it on an errand when the inventory fills up or the tool in hand is nearly broken. Errands run on
 * their own thread (see {@link Bot}), while this module only watches and decides from the client tick.
 */
public class BlockMinerModule extends Module {

    // The pathfinder won't search again any sooner than this after failing to find a path.
    private static final int NO_PATH_RETRY_TICKS = 20;
    private static final int NO_PATH_TRIES = 3;
    // Blocks it couldn't get to might be fine from somewhere else, once it's moved on.
    private static final int OUT_OF_REACH_MEMORY_TICKS = 20 * 60 * 5;
    // Enough to be clear of what it was stuck in, and it mines on from there.
    private static final int CLIMB_BLOCKS = 6;
    private static final int MAX_TIMES_STUCK = 3;
    // Getting stuck again after this long isn't part of the same problem.
    private static final int STUCK_MEMORY_TICKS = 20 * 60 * 5;
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
    private final ClimbingBlocks climbingBlocks = new ClimbingBlocks(bot);

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
    private volatile long nextMineTick;
    private boolean reportedBreakingOff;
    private boolean reportedNothingChosen;

    private BlockScan.@Nullable MiningArea miningArea;
    // Null while exploring, when there's nothing to go for.
    private BlockScan.@Nullable MiningGoal currentGoal;
    private int exploringDirection;
    private boolean reportedExploring;
    private final Set<BlockPos> outOfReach = new HashSet<>();
    private long lastOutOfReachTick;
    private int noPathsInARow;

    private volatile boolean climbingOut;
    private volatile boolean roomNeeded;
    private int timesStuck;
    private long lastStuckTick;

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
        noPathsInARow = 0;
        timesStuck = 0;
        roomNeeded = false;
        forgetWhereItWasMining();
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
        stopClimbing();
        forgetWhereItWasMining();
        // The world and inventory are still arriving, so what the bot sees isn't to be trusted yet.
        nextMineTick = tick + STARTUP_WAIT_TICKS;
        nextErrandTick = tick + STARTUP_WAIT_TICKS;
    }

    private void handleStopped(ClientBotTick.Stopped event) {
        mining = null;
        stopClimbing();
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
        if (roomNeeded) {
            // An errand had nowhere to put something down, and won't until the bot is somewhere else.
            // If it can't climb, the errand tries again a few times and then says what's wrong.
            roomNeeded = false;
            stopMining();
            if (climbingBlocks.allow()) startClimbingOut("Climbing to find room for its errand");
        }
        // Getting out comes first, as nothing else can get done from where it's stuck.
        if (climbingOut) {
            keepMining();
        } else if (needsErrand()) {
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
        forgetWhereItWasMining();
        nextMineTick = 0;
        nextToolCheckTick = 0;
    }

    private void forgetWhereItWasMining() {
        miningArea = null;
        outOfReach.clear();
        reportedExploring = false;
    }

    // ----------------------------------------------------------------------------------------
    // mining
    // ----------------------------------------------------------------------------------------

    private void keepMining() {
        // A command on another thread can clear this at any moment.
        PathingRequestFuture current = mining;
        if (current != null) {
            if (!current.isCompleted()) return;
            miningEnded(current.getNow());
        }
        // Finding out why it ended can turn the module off.
        if (!PLUGIN_CONFIG.enabled || tick < nextMineTick || BARITONE.isActive()) return;

        if (climbingOut) {
            mining = BARITONE.pathTo(new GoalY(bot.position()[1] + CLIMB_BLOCKS));
            return;
        }

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

    /** Sends the pathfinder after the next block, or further off if there's nothing to go for yet. */
    private void startMining(Block[] blocks) {
        int[] position = bot.position();
        if (miningArea == null || miningArea.isFarFrom(position[0], position[2])) {
            miningArea = new BlockScan.MiningArea(position[0], position[2], BlockScan.MINING_AREA_RADIUS);
        }
        if (tick - lastOutOfReachTick > OUT_OF_REACH_MEMORY_TICKS) outOfReach.clear();

        String names = Arrays.stream(blocks).map(Block::name).collect(Collectors.joining(" or "));
        currentGoal = BlockScan.miningGoal(new BlockOptionalMetaLookup(blocks), miningArea, outOfReach);
        if (currentGoal == null) {
            if (!reportedExploring) info("None of {} in the loaded chunks, so going to look for some", names);
            reportedExploring = true;
            miningArea = null;
            mining = BARITONE.pathTo(BlockScan.exploringGoal(exploringDirection));
            status = "Looking for " + names + ", as there's none in the loaded chunks";
            return;
        }
        reportedExploring = false;
        // Wherever it ends up is where it carries on from.
        if (currentGoal.leavesArea()) miningArea = null;
        mining = BARITONE.pathTo(currentGoal.goal());
        status = currentGoal.onBotsLevel() ? "Mining" : "Walking to the nearest " + names;
    }

    private void noPathFound() {
        noPathsInARow++;
        if (noPathsInARow >= NO_PATH_TRIES) {
            noPathsInARow = 0;
            gotStuck();
            return;
        }
        status = "Waiting: can't find a way to anything it can mine from here";
        nextMineTick = tick + NO_PATH_RETRY_TICKS;
    }

    /** It can't get to anything from here, so it builds its way out if it can, and asks for help if not. */
    private void gotStuck() {
        String where = whereTheBotIs();
        timesStuck = tick - lastStuckTick > STUCK_MEMORY_TICKS ? 1 : timesStuck + 1;
        lastStuckTick = tick;
        stopMining();
        if (timesStuck > MAX_TIMES_STUCK) {
            stopAndNotify("It keeps getting stuck, most recently at " + where + ", so it needs moving somewhere else");
        } else if (!climbingBlocks.allow()) {
            stopAndNotify("It's stuck at " + where + ", with no way to dig to anything it can mine, and no blocks"
                + " that it can climb out with. Give it some, or get it out");
        } else {
            warn("Stuck at {}, so building its way out", where);
            startClimbingOut("Climbing out from " + where);
        }
    }

    private void startClimbingOut(String reason) {
        climbingOut = true;
        nextMineTick = 0;
        status = reason;
    }

    private void stopClimbing() {
        climbingOut = false;
        climbingBlocks.stopAllowing();
    }

    private String whereTheBotIs() {
        int[] position = bot.position();
        return "[" + position[0] + ", " + position[1] + ", " + position[2] + "]";
    }

    private void miningEnded(boolean succeeded) {
        mining = null;
        if (climbingOut) {
            stopClimbing();
            if (succeeded) {
                noPathsInARow = 0;
                // Nothing that was out of reach from where it was stuck has to be from here.
                outOfReach.clear();
                nextMineTick = 0;
            } else {
                stopAndNotify("It's stuck at " + whereTheBotIs() + ", and even with blocks to build with it couldn't"
                    + " find a way out. Get it out");
            }
            return;
        }
        if (succeeded) {
            noPathsInARow = 0;
            nextMineTick = 0;
            return;
        }
        if (currentGoal != null) {
            outOfReach.addAll(currentGoal.blocks());
            lastOutOfReachTick = tick;
        } else {
            exploringDirection++;
        }
        noPathFound();
    }

    private void stopMining() {
        if (mining != null) BARITONE.stop();
        mining = null;
        stopClimbing();
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
                roomNeeded = e.needsRoom();
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
