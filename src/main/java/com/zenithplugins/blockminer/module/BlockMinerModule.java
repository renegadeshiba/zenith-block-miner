package com.zenithplugins.blockminer.module;

import com.github.rfresh2.EventConsumer;
import com.google.common.collect.Sets;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalY;
import com.zenith.feature.pathfinder.util.BlockOptionalMetaLookup;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.network.codec.PostOutgoingPacketHandler;
import com.zenithplugins.blockminer.BlockMinerConfig.PlacedBlock;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import com.zenithplugins.blockminer.rules.ToolRules;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
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
    // It only goes for a few at a time of a block worth looking for, so it takes more to show that it's stuck.
    private static final int NO_PATH_TRIES_FOR_NEAREST = 12;
    // A block overhead takes moments to mine, or a few seconds without the right tool.
    private static final int MINING_OVERHEAD_TIMEOUT_TICKS = 20 * 30;
    // Long enough to mine anything it's likely to walk through, even with a server that's struggling.
    private static final int GETTING_NOWHERE_TICKS = 20 * 60;
    // Blocks it couldn't get to might be fine from somewhere else, once it's moved on.
    private static final int OUT_OF_REACH_MEMORY_TICKS = 20 * 60 * 5;
    // Enough to be clear of what it was stuck in, and it mines on from there.
    private static final int CLIMB_BLOCKS = 6;
    // In a row, without getting any mining done in between.
    private static final int MAX_TIMES_STUCK = 5;
    private static final int WAYS_TO_WALK_AWAY = 4;
    // Even if it didn't get all the way, it's somewhere else now.
    private static final int FAR_ENOUGH_AWAY = BlockScan.MINING_AREA_RADIUS;
    private static final int FAILED_ERRAND_RETRY_TICKS = 20 * 30;
    private static final int MAX_FAILED_ERRANDS = 3;
    // While the bot is still building up target items, how often it stops to fill more slots with them.
    private static final int FILL_SLOTS_INTERVAL_TICKS = 20 * 30;
    private static final int TOOL_CHECK_INTERVAL_TICKS = 20;
    private static final int STARTUP_WAIT_TICKS = 20 * 5;

    private final Fights fights = new Fights(() -> warn("The kill aura has been fighting for a minute, so carrying on alongside it"));
    private final Bot bot = new Bot(this, fights);
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
    private long miningStartedTick;
    private final Set<BlockPos> placesBeen = new HashSet<>();
    private long lastNewPlaceTick;
    private volatile long nextMineTick;
    private boolean reportedBreakingOff;
    private boolean reportedNothingChosen;

    private BlockScan.@Nullable MiningArea miningArea;
    // Null while exploring, when there's nothing to go for.
    private BlockScan.@Nullable MiningGoal currentGoal;
    private @Nullable BlockOptionalMetaLookup currentBlocks;
    private int exploringDirection;
    private boolean reportedExploring;
    private final Set<BlockPos> outOfReach = new HashSet<>();
    private final PlacedBlocks placedBlocks = new PlacedBlocks();
    private long lastOutOfReachTick;
    private int noPathsInARow;
    private int failuresOnLevel;

    private volatile boolean walkingAway;
    private int waysLeftToWalkAway;
    private int @Nullable [] walkingAwayFrom;
    private volatile boolean climbingOut;
    private volatile boolean roomNeeded;
    private int timesStuck;

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleTick),
            of(ClientBotTick.Starting.class, this::handleStarting),
            of(ClientBotTick.Stopped.class, this::handleStopped)
        );
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("block-miner")
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .postOutbound(
                    ServerboundUseItemOnPacket.class,
                    (PostOutgoingPacketHandler<ServerboundUseItemOnPacket, ClientSession>) (packet, session) ->
                        placedBlocks.clicked(packet.getX(), packet.getY(), packet.getZ(), packet.getFace()))
                .build())
            .build();
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
        failuresOnLevel = 0;
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
        return fights.underWay() ? "Leaving it to the kill aura, which has something to fight. Before that: " + status : status;
    }

    public boolean errandRunning() {
        return errand != null;
    }

    private void handleStarting(ClientBotTick.Starting event) {
        mining = null;
        stopGettingOut();
        forgetWhereItWasMining();
        // The world and inventory are still arriving, so what the bot sees isn't to be trusted yet.
        nextMineTick = tick + STARTUP_WAIT_TICKS;
        nextErrandTick = tick + STARTUP_WAIT_TICKS;
    }

    private void handleStopped(ClientBotTick.Stopped event) {
        mining = null;
        stopGettingOut();
        status = "Paused";
    }

    void handleTick(ClientBotTick event) {
        tick++;
        if (errand != null) return;
        if (fights.underWay()) {
            // It goes back to whatever it was doing afterwards, including walking away or climbing out.
            pauseMining();
            return;
        }
        if (storage.isStuck()) {
            stopAndNotify("The inventory is full of things it isn't allowed to store, so it can't pick up"
                + " what it's mining. Make room, or check the keep list with `blockminer`");
            return;
        }
        if (roomNeeded) {
            // An errand couldn't put something down, even by mining out a spot, so it's got to be
            // somewhere else. Bedrock on every side is about the only way that happens.
            roomNeeded = false;
            stopMining();
            climbingBlocks.allow();
            startClimbingOut("Climbing to find room for its errand");
        }
        // Getting out comes first, as nothing else can get done from where it's stuck. Except for a tool
        // about to break, since walking away can be 100 blocks of digging. It picks up where it left off.
        if (climbingOut || (walkingAway && !toolInHandIsWorn())) {
            keepMining();
        } else if (needsErrand()) {
            // Waiting out a failed errand still means not mining: the next few blocks could be the
            // ones that break the tool.
            if (walkingAway) {
                pauseMining();
            } else {
                stopMining();
            }
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
            if (!current.isCompleted()) {
                // Reads as somewhere it couldn't get to, next time round. Nothing else would end it.
                if (isMiningOverhead() ? tick - miningStartedTick > MINING_OVERHEAD_TIMEOUT_TICKS : isGettingNowhere()) {
                    warn("Hasn't got anywhere from {} in a while, so giving up on that", whereTheBotIs());
                    BARITONE.stop();
                }
                return;
            }
            miningEnded(current.getNow());
        }
        // Finding out why it ended can turn the module off.
        if (!PLUGIN_CONFIG.enabled || tick < nextMineTick || BARITONE.isActive()) return;

        if (climbingOut) {
            walkTo(BlockScan.avoidingVines(new GoalY(bot.position()[1] + CLIMB_BLOCKS)));
            return;
        }
        if (walkingAway) {
            walkTo(BlockScan.walkingAwayGoal(exploringDirection));
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
        currentBlocks = new BlockOptionalMetaLookup(blocks);
        currentGoal = BlockScan.miningGoal(currentBlocks, miningArea, Sets.union(outOfReach, placedBlocks.positions()));
        if (currentGoal == null) {
            if (!reportedExploring) info("None of {} in the loaded chunks, so going to look for some", names);
            reportedExploring = true;
            miningArea = null;
            walkTo(BlockScan.exploringGoal(exploringDirection));
            status = "Looking for " + names + ", as there's none in the loaded chunks";
            return;
        }
        reportedExploring = false;
        miningArea = currentGoal.area();
        miningStartedTick = tick;
        if (currentGoal.goal() == null) {
            BlockPos block = currentGoal.blocks().getFirst();
            mining = BARITONE.breakBlock(block.x(), block.y(), block.z(), true);
            status = "Mining";
            return;
        }
        walkTo(currentGoal.goal());
        status = currentGoal.onBotsLevel() ? "Mining" : "Walking to the nearest " + names;
    }

    private void walkTo(Goal goal) {
        placesBeen.clear();
        mining = BARITONE.pathTo(goal);
    }

    /**
     * The pathfinder can go on forever trying the same thing, like climbing a vine it can't really climb, and that
     * never counts as failing. Going back and forth doesn't count as getting anywhere either, only being somewhere
     * new. Walking a long way, or mining a slow block on the way, is fine.
     */
    private boolean isGettingNowhere() {
        int[] position = bot.position();
        if (placesBeen.add(new BlockPos(position[0], position[1], position[2]))) lastNewPlaceTick = tick;
        return tick - lastNewPlaceTick > GETTING_NOWHERE_TICKS;
    }

    private boolean isMiningOverhead() {
        return !climbingOut && currentGoal != null && currentGoal.goal() == null;
    }

    private void noPathFound() {
        noPathsInARow++;
        boolean goingForNearest = currentGoal != null && currentGoal.area() == null;
        if (noPathsInARow >= (goingForNearest ? NO_PATH_TRIES_FOR_NEAREST : NO_PATH_TRIES)) {
            noPathsInARow = 0;
            gotStuck();
            return;
        }
        status = "Waiting: can't find a way to anything it can mine from here";
        nextMineTick = tick + NO_PATH_RETRY_TICKS;
    }

    /** It can't get to anything from here, so it tries somewhere else altogether, and asks for help if it can't. */
    private void gotStuck() {
        String where = whereTheBotIs();
        String goingFor = currentGoal == null || currentGoal.blocks().isEmpty() ? "somewhere new" : String.valueOf(currentGoal.blocks().getFirst());
        timesStuck++;
        stopMining();
        if (timesStuck > MAX_TIMES_STUCK) {
            stopAndNotify("It keeps getting stuck, most recently at " + where + " going for " + goingFor
                + ", so it needs moving somewhere else");
            return;
        }
        warn("Can't get to anything from {}, going for {}, so walking away from there", where, goingFor);
        walkingAway = true;
        waysLeftToWalkAway = WAYS_TO_WALK_AWAY;
        walkingAwayFrom = bot.position();
        climbingBlocks.allow();
        nextMineTick = 0;
        status = "Walking away from " + where + ", as it can't get to anything from there";
    }

    private void walkingAwayEnded(boolean succeeded) {
        if (succeeded || isFarEnoughAway()) {
            stopGettingOut();
            noPathsInARow = 0;
            forgetWhereItWasMining();
            nextMineTick = 0;
            return;
        }
        // The pathfinder won't search again any sooner, whichever way it's looking.
        nextMineTick = tick + NO_PATH_RETRY_TICKS;
        exploringDirection++;
        waysLeftToWalkAway--;
        if (waysLeftToWalkAway > 0) return;

        String where = whereTheBotIs();
        stopGettingOut();
        climbingBlocks.allow();
        if (climbingBlocks.canBuild()) {
            warn("Can't walk away from {} in any direction, so building its way out", where);
            startClimbingOut("Climbing out from " + where);
        } else {
            stopAndNotify("It's stuck at " + where + ", with no way to dig to anything it can mine, and no blocks"
                + " that it can climb out with. Give it some, or get it out");
        }
    }

    private boolean isFarEnoughAway() {
        int[] from = walkingAwayFrom;
        if (from == null) return false;
        int[] now = bot.position();
        return Math.abs(now[0] - from[0]) + Math.abs(now[2] - from[2]) >= FAR_ENOUGH_AWAY;
    }

    private void startClimbingOut(String reason) {
        climbingOut = true;
        nextMineTick = 0;
        status = reason;
    }

    private void stopGettingOut() {
        climbingOut = false;
        walkingAway = false;
        walkingAwayFrom = null;
        climbingBlocks.stopAllowing();
    }

    private String whereTheBotIs() {
        int[] position = bot.position();
        return "[" + position[0] + ", " + position[1] + ", " + position[2] + "]";
    }

    private void miningEnded(boolean succeeded) {
        mining = null;
        if (walkingAway) {
            walkingAwayEnded(succeeded);
            return;
        }
        if (climbingOut) {
            stopGettingOut();
            if (succeeded) {
                noPathsInARow = 0;
                // Nothing that was out of reach from where it was stuck has to be from here.
                outOfReach.clear();
                nextMineTick = 0;
            } else {
                stopAndNotify("It's stuck at " + whereTheBotIs() + ", and couldn't find a way out. Get it out");
            }
            return;
        }
        if (succeeded) {
            noPathsInARow = 0;
            failuresOnLevel = 0;
            nextMineTick = 0;
            // Only getting some mining done shows that it's got over whatever was stopping it.
            if (currentGoal != null && currentGoal.onBotsLevel()) timesStuck = 0;
            climbingBlocks.stopAllowing();
            return;
        }
        // Before giving up on anything, it sees whether it can get there by building.
        if (climbingBlocks.allow()) {
            status = "Waiting: can't find a way without building, so trying again with blocks";
            nextMineTick = tick + NO_PATH_RETRY_TICKS;
            return;
        }
        if (currentGoal == null) {
            exploringDirection++;
            noPathFound();
            return;
        }
        outOfReach.addAll(currentGoal.blocks());
        lastOutOfReachTick = tick;
        if (currentGoal.onBotsLevel()) {
            couldntGetToItOnItsLevel();
        } else {
            noPathFound();
        }
    }

    /**
     * What's left at the end of a level is often what's hardest to get to, like blocks next to lava, and that
     * doesn't make it stuck. It has the rest of the section to go on to, and if it can't get to that either,
     * then it's stuck.
     */
    private void couldntGetToItOnItsLevel() {
        failuresOnLevel++;
        status = "Waiting: can't get to what's left of this level";
        nextMineTick = tick + NO_PATH_RETRY_TICKS;
        BlockScan.MiningArea area = miningArea;
        BlockOptionalMetaLookup blocks = currentBlocks;
        if (failuresOnLevel < NO_PATH_TRIES || area == null || blocks == null) return;

        failuresOnLevel = 0;
        info("Can't get to what's left of level {}, so moving on from it", area.level());
        outOfReach.addAll(BlockScan.blocksOnLevel(blocks, area));
    }

    private void stopMining() {
        pauseMining();
        stopGettingOut();
    }

    private void pauseMining() {
        if (mining != null) BARITONE.stop();
        mining = null;
    }

    // ----------------------------------------------------------------------------------------
    // errands
    // ----------------------------------------------------------------------------------------

    private boolean needsErrand() {
        // With nothing chosen, the only thing to do is not leave a shulker box lying around.
        if (PLUGIN_CONFIG.wantedItems.isEmpty()) return bot.placedShulkerIsInThisWorld();

        return toolInHandIsWorn()
            || storage.isNeeded()
            || bot.placedShulkerIsInThisWorld()
            || wrongToolOnHotbar()
            || (tick >= nextFillTick && (space.isWorthFilling() || space.isWorthThrowingJunk()));
    }

    private boolean toolInHandIsWorn() {
        return ToolRules.isWorn(bot.heldStack(), PLUGIN_CONFIG.toolDurabilityThreshold);
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
            wrongTool = CONFIG.client.extra.pathfinder.autoTool
                && ToolChoice.firstWithWrongTool(blocksNeedingARightTool(), toolNeed()) != null;
        }
        return wrongTool;
    }

    private static List<Block> blocksNeedingARightTool() {
        if (TargetItems.combinedToolNeed(PLUGIN_CONFIG.wantedItems) == null) return List.of();
        return ToolChoice.blocksNeedingARightTool(PLUGIN_CONFIG.wantedItems);
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
                if (toolInHandIsWorn()) {
                    status = "Swapping a worn tool";
                    toolSwap.run(bot.heldStack(), toolNeed());
                }
                if (CONFIG.client.extra.pathfinder.autoTool) {
                    status = "Getting the right tool";
                    toolSwap.ensureRightTool(blocksNeedingARightTool(), toolNeed());
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
