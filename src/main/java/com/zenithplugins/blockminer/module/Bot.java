package com.zenithplugins.blockminer.module;

import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.inventory.Container;
import com.zenith.cache.data.inventory.InventoryCache;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.BlockStateInterface;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.mc.item.ContainerTypeInfoRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import com.zenithplugins.blockminer.BlockMinerConfig.PlacedBlock;
import com.zenithplugins.blockminer.rules.InventoryPlan.SlotStack;
import com.zenithplugins.blockminer.util.BlockEntityScan;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;
import static com.zenith.Globals.saveConfigAsync;
import static com.zenithplugins.blockminer.BlockMinerPlugin.PLUGIN_CONFIG;

/**
 * What the bot can do during an errand: walk, place, break, open containers and move items. Every
 * method blocks until the action has finished, so errands read as a list of steps. They run on their
 * own thread for that reason, and must never be called from the client tick thread.
 */
final class Bot {

    static final int ACTION_TIMEOUT_MS = 10_000;
    static final int CLICK_PACING_MS = 100;
    private static final int PATH_TIMEOUT_MS = 90_000;
    private static final int BREAK_TIMEOUT_MS = 30_000;
    private static final int PICKUP_WITHOUT_WALKING_MS = 1_000;
    private static final int PICKUP_ATTEMPTS = 6;
    private static final int PICKUP_WALK_TIMEOUT_MS = 15_000;
    private static final int PICKUP_AFTER_WALK_MS = 1_500;
    private static final int DROP_SEARCH_RADIUS = 16;
    private static final int DROP_LOOK_MS = 3_000;
    // Where an item entity keeps the stack it's carrying.
    private static final int ITEM_ENTITY_STACK_METADATA = 8;
    private static final int POLL_MS = 50;
    private static final int MAX_OPEN_ATTEMPTS = 5;

    private static final int PLAYER_SLOTS = 36;
    private static final int HOTBAR_SLOTS = 9;
    // The player's own inventory screen: 9-35 main inventory, 36-44 hotbar, 45 offhand.
    private static final int OWN_MAIN_START = 9;
    private static final int OWN_HOTBAR_START = 36;
    private static final int OWN_HOTBAR_END = 45;

    // Signs of a rejected inventory swap show up shortly after it, so a moved item has to stay put
    // for this many checks before it's used. Otherwise whatever is really in that slot gets placed.
    private static final int HOLD_SETTLE_CHECKS = 10;
    private static final int HOLD_TIMEOUT_CHECKS = 200;
    private static final int HOLD_RETRY_CHECKS = 40;

    // Beyond this, placing a carried ender chest is quicker than walking to a loaded one.
    private static final int MAX_ENDER_CHEST_WALK = 48;
    private static final long FAILED_CHEST_COOLDOWN_MS = 10 * 60 * 1000;
    private static final int WALK_BACK_RANGE = 2;
    private static final int[][] BESIDE = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final BlockMinerModule module;
    /** False once the bot goes offline or a player takes over, which ends any errand under way. */
    private final BooleanSupplier inControl;
    /** Chest position -> time it may be tried again. Only touched from the errand thread. */
    private final Map<String, Long> failedChests = new HashMap<>();

    Bot(BlockMinerModule module) {
        this(module, () -> Proxy.getInstance().isConnected() && !Proxy.getInstance().hasActivePlayer());
    }

    Bot(BlockMinerModule module, BooleanSupplier inControl) {
        this.module = module;
        this.inControl = inControl;
    }

    // ----------------------------------------------------------------------------------------
    // waiting
    // ----------------------------------------------------------------------------------------

    private void checkAlive() {
        if (!inControl.getAsBoolean()) throw new ErrandAborted();
    }

    void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrandAborted();
        }
    }

    boolean waitFor(BooleanSupplier condition, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            checkAlive();
            if (condition.getAsBoolean()) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            sleep(POLL_MS);
        }
    }

    /** Starts a pathfinder request and waits for it to finish, failing if it's rejected or too slow. */
    private void run(Supplier<PathingRequestFuture> request, int timeoutMs, String what) {
        PathingRequestFuture future = request.get();
        if (!waitFor(future::isCompleted, timeoutMs)) {
            BARITONE.stop();
            throw ErrandFailed.retry(what + " took too long");
        }
        if (!future.getNow()) throw ErrandFailed.retry(what + " didn't work");
    }

    // ----------------------------------------------------------------------------------------
    // reading the inventory
    // ----------------------------------------------------------------------------------------

    private static InventoryCache inventory() {
        return CACHE.getPlayerCache().getInventoryCache();
    }

    int openContainerId() {
        return inventory().getOpenContainerId();
    }

    /**
     * While a container is open Zenith only updates that container's copy of the player's slots, and
     * copies them back when it closes. So the player's slots have to be read from whichever is current.
     */
    private static Container currentContainer() {
        return inventory().getOpenContainerId() != 0 ? inventory().getOpenContainer() : inventory().getPlayerInventory();
    }

    private static int firstPlayerSlot() {
        return inventory().getOpenContainerId() != 0
            ? inventory().getOpenContainer().getSize() - PLAYER_SLOTS
            : OWN_MAIN_START;
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack == Container.EMPTY_STACK || stack.getAmount() <= 0;
    }

    ItemStack stackAt(int slot) {
        return currentContainer().getItemStack(slot);
    }

    /** Slots of the player's inventory and hotbar in the current container holding a matching stack. */
    List<Integer> playerSlots(Predicate<ItemStack> filter) {
        Container container = currentContainer();
        int first = firstPlayerSlot();
        List<Integer> slots = new ArrayList<>();
        for (int slot = first; slot < first + PLAYER_SLOTS && slot < container.getSize(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (!isEmpty(stack) && filter.test(stack)) slots.add(slot);
        }
        return slots;
    }

    /** Slots of the bot's own main inventory, 9 to 35, holding a matching stack. Nothing can be open. */
    List<Integer> mainInventorySlots(Predicate<ItemStack> filter) {
        closeContainer();
        return ownSlots(OWN_MAIN_START, OWN_HOTBAR_START, filter);
    }

    /** Slots of the bot's own hotbar, 36 to 44, holding a matching stack. Nothing can be open. */
    List<Integer> hotbarSlots(Predicate<ItemStack> filter) {
        closeContainer();
        return ownSlots(OWN_HOTBAR_START, OWN_HOTBAR_END, filter);
    }

    private List<Integer> ownSlots(int from, int to, Predicate<ItemStack> filter) {
        List<ItemStack> own = CACHE.getPlayerCache().getPlayerInventory();
        List<Integer> slots = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = own.get(slot);
            if (!isEmpty(stack) && filter.test(stack)) slots.add(slot);
        }
        return slots;
    }

    /** Everything in the player's inventory and hotbar, with its slot in the current container. */
    List<SlotStack> playerStacks() {
        List<SlotStack> stacks = new ArrayList<>();
        for (int slot : playerSlots(stack -> true)) stacks.add(new SlotStack(slot, stackAt(slot)));
        return stacks;
    }

    /** The empty slots of the player's inventory and hotbar in the current container. */
    List<Integer> emptyPlayerSlots() {
        Container container = currentContainer();
        int first = firstPlayerSlot();
        List<Integer> slots = new ArrayList<>();
        for (int slot = first; slot < first + PLAYER_SLOTS && slot < container.getSize(); slot++) {
            if (isEmpty(container.getItemStack(slot))) slots.add(slot);
        }
        return slots;
    }

    boolean isEmptySlot(int slot) {
        return isEmpty(stackAt(slot));
    }

    /** Slots of the open container itself, not counting the player's slots below them. */
    List<Integer> containerSlots() {
        List<Integer> slots = new ArrayList<>();
        if (openContainerId() == 0) return slots;
        for (int slot = 0; slot < firstPlayerSlot(); slot++) slots.add(slot);
        return slots;
    }

    int count(IntPredicate itemIds) {
        int total = 0;
        for (int slot : playerSlots(stack -> itemIds.test(stack.getId()))) total += stackAt(slot).getAmount();
        return total;
    }

    int countItem(int itemId) {
        return count(id -> id == itemId);
    }

    int freeSlots() {
        return emptyPlayerSlots().size();
    }

    /** The stack in the held hotbar slot. */
    ItemStack heldStack() {
        return CACHE.getPlayerCache().getPlayerInventory().get(OWN_HOTBAR_START + CACHE.getPlayerCache().getHeldItemSlot());
    }

    boolean cursorIsEmpty() {
        return isEmpty(inventory().getMouseStack());
    }

    int cursorAmount() {
        ItemStack cursor = inventory().getMouseStack();
        return isEmpty(cursor) ? 0 : cursor.getAmount();
    }

    // ----------------------------------------------------------------------------------------
    // moving items
    // ----------------------------------------------------------------------------------------

    /** Shift-clicks a slot in the open container. False if Zenith says there's nowhere for it to go. */
    boolean shiftClick(int slot) {
        int containerId = openContainerId();
        if (containerId == 0) return false;
        var packet = new ShiftClick(containerId, slot, ShiftClickItemAction.LEFT_CLICK).packet();
        if (packet == null) return false;
        module.sendClientPacketAsync(packet);
        return true;
    }

    /** Clicks a slot in the open container, or in the player's own inventory if nothing is open. */
    void click(int slot, ClickItemAction action) {
        var packet = new ClickItem(openContainerId(), slot, action).packet();
        if (packet == null) throw ErrandFailed.retry("Couldn't click slot " + slot);
        module.sendClientPacketAsync(packet);
        sleep(CLICK_PACING_MS);
    }

    /** Throws a whole stack on the ground. False if it couldn't be dropped. */
    boolean dropStack(int slot) {
        var packet = new DropItem(openContainerId(), slot, DropItemAction.DROP_SELECTED_STACK).packet();
        if (packet == null) return false;
        module.sendClientPacketAsync(packet);
        return waitFor(() -> isEmptySlot(slot), ACTION_TIMEOUT_MS);
    }

    /**
     * Gets an item into the held hotbar slot before it's placed. Left to Zenith's place request, a
     * rejected swap would place whatever was really in that slot. Requests can also be refused or
     * dropped for a higher priority one, so this keeps sending them until the item is in hand.
     */
    private void hold(int itemId) {
        closeContainer();
        RequestFuture submitted = null;
        int settleChecks = 1;
        int settled = 0;
        int sinceSubmit = 0;
        for (int check = 0; check < HOLD_TIMEOUT_CHECKS; check++) {
            checkAlive();
            int target = hotbarSlotFor(itemId);
            boolean inHand = CACHE.getPlayerCache().getHeldItemSlot() == target
                && isItem(CACHE.getPlayerCache().getPlayerInventory().get(OWN_HOTBAR_START + target), itemId);
            if (inHand) {
                if (++settled >= settleChecks) return;
            } else {
                settled = 0;
                sinceSubmit++;
                boolean finished = submitted == null || submitted.isCompleted();
                boolean refused = submitted != null && finished && !submitted.getNow();
                if (finished && (submitted == null || refused || sinceSubmit >= HOLD_RETRY_CHECKS)) {
                    boolean moving = !isItem(CACHE.getPlayerCache().getPlayerInventory().get(OWN_HOTBAR_START + target), itemId);
                    if (moving) settleChecks = HOLD_SETTLE_CHECKS;
                    submitted = submitHold(itemId, target);
                    sinceSubmit = 0;
                }
            }
            sleep(POLL_MS);
        }
        throw ErrandFailed.retry("Couldn't get " + itemName(itemId) + " into the bot's hand");
    }

    private static boolean isItem(@Nullable ItemStack stack, int itemId) {
        return !isEmpty(stack) && stack.getId() == itemId;
    }

    /**
     * Swaps a stack in the main inventory with a hotbar slot, 0 to 8, and waits until the server has
     * gone along with it. Requests can be refused or dropped for a higher priority one, so it retries.
     */
    void swapWithHotbar(int mainSlot, int hotbarSlot) {
        closeContainer();
        List<ItemStack> own = CACHE.getPlayerCache().getPlayerInventory();
        ItemStack moving = own.get(mainSlot);
        BooleanSupplier arrived = () -> {
            ItemStack now = CACHE.getPlayerCache().getPlayerInventory().get(OWN_HOTBAR_START + hotbarSlot);
            return !isEmpty(now) && now.getId() == moving.getId()
                && Objects.equals(now.getDataComponents(), moving.getDataComponents());
        };
        for (int attempt = 0; attempt < MAX_OPEN_ATTEMPTS; attempt++) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(module)
                .priority(Baritone.getPriority() + 1)
                .actions(new MoveToHotbarSlot(mainSlot, MoveToHotbarAction.from(hotbarSlot)))
                .build());
            if (waitFor(arrived, ACTION_TIMEOUT_MS / MAX_OPEN_ATTEMPTS)) return;
        }
        throw ErrandFailed.retry("Couldn't move " + itemName(moving.getId()) + " onto the hotbar");
    }

    /** The hotbar slot already holding the item, otherwise an empty one, otherwise the held one. */
    private int hotbarSlotFor(int itemId) {
        List<ItemStack> own = CACHE.getPlayerCache().getPlayerInventory();
        int empty = -1;
        for (int slot = OWN_HOTBAR_START; slot < OWN_HOTBAR_END; slot++) {
            if (isItem(own.get(slot), itemId)) return slot - OWN_HOTBAR_START;
            if (empty == -1 && isEmpty(own.get(slot))) empty = slot - OWN_HOTBAR_START;
        }
        return empty != -1 ? empty : CACHE.getPlayerCache().getHeldItemSlot();
    }

    private RequestFuture submitHold(int itemId, int target) {
        List<ItemStack> own = CACHE.getPlayerCache().getPlayerInventory();
        List<InventoryAction> actions = new ArrayList<>();
        if (!isItem(own.get(OWN_HOTBAR_START + target), itemId)) {
            for (int slot = OWN_MAIN_START; slot < OWN_HOTBAR_START; slot++) {
                if (isItem(own.get(slot), itemId)) {
                    actions.add(new MoveToHotbarSlot(slot, MoveToHotbarAction.from(target)));
                    break;
                }
            }
        }
        if (CACHE.getPlayerCache().getHeldItemSlot() != target) actions.add(new SetHeldItem(target));
        if (actions.isEmpty()) return RequestFuture.immediateFuture(true);
        return INVENTORY.submit(InventoryActionRequest.builder()
            .owner(module)
            .priority(Baritone.getPriority() + 1)
            .actions(actions.toArray(new InventoryAction[0]))
            .build());
    }

    private static String itemName(int itemId) {
        ItemData item = ItemRegistry.REGISTRY.get(itemId);
        return item == null ? "the item" : item.name();
    }

    // ----------------------------------------------------------------------------------------
    // containers
    // ----------------------------------------------------------------------------------------

    /** Opens the container at {@code pos} and waits for its contents to arrive. */
    void openContainer(int[] pos, String name) {
        closeContainer();
        for (int attempt = 0; attempt < MAX_OPEN_ATTEMPTS; attempt++) {
            try {
                run(() -> BARITONE.rightClickBlock(pos[0], pos[1], pos[2]), ACTION_TIMEOUT_MS, "Opening " + name);
                if (waitFor(Bot::containerIsReady, ACTION_TIMEOUT_MS)) return;
            } catch (ErrandFailed e) {
                module.debug("{} (attempt {}/{})", e.getMessage(), attempt + 1, MAX_OPEN_ATTEMPTS);
            }
            BARITONE.stop();
            sleep(200);
        }
        throw ErrandFailed.retry(name + " wouldn't open at " + Arrays.toString(pos));
    }

    private static boolean containerIsReady() {
        int openId = inventory().getOpenContainerId();
        if (openId == 0) return false;
        Container container = inventory().getOpenContainer();
        var typeInfo = ContainerTypeInfoRegistry.REGISTRY.get(container.getType());
        int expectedSize = typeInfo == null ? PLAYER_SLOTS : typeInfo.totalSlots();
        return container.getContainerId() == openId && container.getSize() >= expectedSize;
    }

    /**
     * Closes whatever is open. The server puts anything left on the cursor back in the inventory, so
     * this also clears the cursor when only the player's own inventory is in use.
     */
    void closeContainer() {
        InventoryCache inventory = inventory();
        int containerId = inventory.getOpenContainerId();
        if (containerId == 0 && cursorIsEmpty()) return;
        var packet = new CloseContainer(containerId).packet();
        if (packet != null) module.sendClientPacketAsync(packet);
        inventory.closeContainer(containerId);
        inventory.setMouseStack(Container.EMPTY_STACK);
        sleep(CLICK_PACING_MS);
    }

    // ----------------------------------------------------------------------------------------
    // the world
    // ----------------------------------------------------------------------------------------

    int[] position() {
        return new int[]{
            MathHelper.floorI(CACHE.getPlayerCache().getX()),
            MathHelper.floorI(CACHE.getPlayerCache().getY()),
            MathHelper.floorI(CACHE.getPlayerCache().getZ())
        };
    }

    private double distanceTo(int[] pos) {
        double dx = CACHE.getPlayerCache().getX() - (pos[0] + 0.5);
        double dy = CACHE.getPlayerCache().getY() - pos[1];
        double dz = CACHE.getPlayerCache().getZ() - (pos[2] + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean isBlock(int[] pos, String blockName) {
        if (!BlockStateInterface.isLoaded(pos[0], pos[2])) return false;
        var block = BlockStateInterface.getBlock(pos[0], pos[1], pos[2]);
        return block != null && block.name().equals(blockName);
    }

    /** The name of the block at {@code pos}, or null if that chunk isn't loaded. */
    @Nullable String blockNameAt(int[] pos) {
        if (!BlockStateInterface.isLoaded(pos[0], pos[2])) return null;
        var block = BlockStateInterface.getBlock(pos[0], pos[1], pos[2]);
        return block == null ? null : block.name();
    }

    /** The closest block with this name within {@code radius} of the bot, or null. */
    int @Nullable [] findBlockNearby(String blockName, int radius) {
        int[] feet = position();
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = feet[0] - radius; x <= feet[0] + radius; x++) {
            for (int y = feet[1] - radius; y <= feet[1] + radius; y++) {
                for (int z = feet[2] - radius; z <= feet[2] + radius; z++) {
                    int[] pos = {x, y, z};
                    if (!isBlock(pos, blockName)) continue;
                    double distance = distanceTo(pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    void walkNear(int[] pos, int range, String what) {
        run(() -> BARITONE.pathTo(new GoalNear(pos[0], pos[1], pos[2], range)), PATH_TIMEOUT_MS, what);
    }

    /** Walks back to where the errand started. Not reaching it doesn't matter: mining resumes from here. */
    void walkBackTo(int[] start) {
        if (distanceTo(start) <= WALK_BACK_RANGE) return;
        try {
            walkNear(start, WALK_BACK_RANGE, "Walking back");
        } catch (ErrandFailed e) {
            module.warn("Couldn't walk back to {}; carrying on from here", Arrays.toString(start));
        }
    }

    /** Places an item beside the bot and waits for the block to appear. Returns where it went. */
    int[] placeBeside(ItemData item) {
        hold(item.id());
        int[] feet = position();
        for (int[] offset : BESIDE) {
            int[] pos = {feet[0] + offset[0], feet[1], feet[2] + offset[1]};
            if (!hasRoomAt(pos)) continue;
            try {
                run(() -> BARITONE.placeBlock(pos[0], pos[1], pos[2], item), ACTION_TIMEOUT_MS, "Placing " + item.name());
                if (waitFor(() -> isBlock(pos, item.name()), ACTION_TIMEOUT_MS)) return pos;
            } catch (ErrandFailed e) {
                module.debug(e.getMessage());
            }
            BARITONE.stop();
        }
        throw ErrandFailed.noRoom("No room to place " + item.name() + " beside the bot");
    }

    /** Room to stand a container here, including the space above that a shulker box needs to open. */
    private boolean hasRoomAt(int[] pos) {
        var target = BlockStateInterface.getBlock(pos[0], pos[1], pos[2]);
        var above = BlockStateInterface.getBlock(pos[0], pos[1] + 1, pos[2]);
        var support = BlockStateInterface.getBlock(pos[0], pos[1] - 1, pos[2]);
        return target != null && target.replaceable()
            && above != null && above.replaceable()
            && support != null && support.solidBlock();
    }

    /** Breaks a block the bot placed and collects what it drops. */
    void breakAndPickUp(int[] pos, ItemData item, Runnable makeRoom) {
        closeContainer();
        makeRoom.run();
        int before = countItem(item.id());
        run(() -> BARITONE.breakBlock(pos[0], pos[1], pos[2], true), BREAK_TIMEOUT_MS, "Breaking " + item.name());
        if (!waitFor(() -> !isBlock(pos, item.name()), BREAK_TIMEOUT_MS)) {
            throw ErrandFailed.retry(item.name() + " wasn't broken at " + Arrays.toString(pos));
        }
        if (!collectDrop(pos, item.id(), before, makeRoom)) throw couldNotCollect(item.id(), pos);
    }

    private static ErrandFailed couldNotCollect(int itemId, int[] pos) {
        return ErrandFailed.stop(itemName(itemId) + " broke but couldn't be picked up, so it's on the ground near "
            + Arrays.toString(pos) + ". Dropped items disappear after about 5 minutes, so grab it soon");
    }

    /**
     * Collects a drop, and only counts it as collected once it's really in the inventory. A player
     * only picks up what's within about 1.3 blocks of their centre, which a block placed diagonally,
     * or a bot standing on the far side of its own block, can be just out of reach of. So after a
     * moment the bot walks right onto the item, or as close as it can get if it can't stand there.
     * Junk on the ground can land in a free slot before the drop does, so it makes room before each try.
     */
    private boolean collectDrop(int[] near, int itemId, int countBefore, Runnable makeRoom) {
        BooleanSupplier collected = () -> countItem(itemId) > countBefore;
        if (waitFor(collected, PICKUP_WITHOUT_WALKING_MS)) return true;

        for (int attempt = 0; attempt < PICKUP_ATTEMPTS; attempt++) {
            makeRoom.run();
            Drop drop = findDrop(near, stack -> stack.getId() == itemId);
            int[] spot = drop != null ? drop.spot() : near;
            module.info("Walking to {} to collect {} (try {}/{})",
                Arrays.toString(spot), itemName(itemId), attempt + 1, PICKUP_ATTEMPTS);

            Goal goal = attempt % 2 == 0
                ? new GoalBlock(spot[0], spot[1], spot[2])
                : new GoalNear(spot[0], spot[1], spot[2], 1);
            PathingRequestFuture walk = BARITONE.pathTo(goal);
            waitFor(() -> collected.getAsBoolean() || walk.isCompleted(), PICKUP_WALK_TIMEOUT_MS);
            boolean gotIt = waitFor(collected, PICKUP_AFTER_WALK_MS);
            BARITONE.stop();
            if (gotIt) return true;
        }
        return false;
    }

    /** A dropped item on the ground, and the block it's lying in. */
    private record Drop(int[] spot, ItemStack stack) {
    }

    /** The matching dropped item closest to {@code near}, or null if there isn't one in range. */
    private @Nullable Drop findDrop(int[] near, Predicate<ItemStack> filter) {
        Drop closest = null;
        double closestDistance = DROP_SEARCH_RADIUS;
        try {
            for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
                if (entity.getEntityType() != EntityType.ITEM || !(entity instanceof EntityLiving item)) continue;
                ItemStack stack = item.getMetadataValue(ITEM_ENTITY_STACK_METADATA, MetadataTypes.ITEM, ItemStack.class);
                if (isEmpty(stack) || !filter.test(stack)) continue;

                double distance = Math.sqrt(
                    Math.pow(entity.getX() - (near[0] + 0.5), 2)
                        + Math.pow(entity.getY() - near[1], 2)
                        + Math.pow(entity.getZ() - (near[2] + 0.5), 2));
                if (distance <= closestDistance) {
                    closestDistance = distance;
                    closest = new Drop(new int[]{
                        MathHelper.floorI(entity.getX()),
                        MathHelper.floorI(entity.getY()),
                        MathHelper.floorI(entity.getZ())
                    }, stack);
                }
            }
        } catch (RuntimeException e) {
            // The entity map is updated from the network thread; the next look will see it.
            module.debug("Couldn't read the entity cache: {}", e.getMessage());
        }
        return closest;
    }

    /**
     * Collects a matching item from the ground near {@code near}. Returns false if there isn't one,
     * after giving it a moment, since the bot may only just have come back within range of it.
     */
    boolean collectDropNear(int[] near, Predicate<ItemStack> filter, Runnable makeRoom) {
        if (!waitFor(() -> findDrop(near, filter) != null, DROP_LOOK_MS)) return false;
        Drop drop = findDrop(near, filter);
        if (drop == null) return false;

        int itemId = drop.stack().getId();
        if (!collectDrop(near, itemId, countItem(itemId), makeRoom)) throw couldNotCollect(itemId, near);
        return true;
    }

    // ----------------------------------------------------------------------------------------
    // the ender chest
    // ----------------------------------------------------------------------------------------

    /**
     * Opens the nearest loaded ender chest, since they all open the same storage, or places a carried
     * one if none is close. One that can't be reached is ignored for a while so the bot can move on.
     */
    void openEnderChest() {
        ItemData enderChest = ItemRegistry.ENDER_CHEST;
        boolean carrying = countItem(enderChest.id()) > 0;
        int[] nearest = nearestEnderChest();
        if (nearest != null && (!carrying || distanceTo(nearest) <= MAX_ENDER_CHEST_WALK)) {
            try {
                walkNear(nearest, 4, "Walking to the ender chest");
                openContainer(nearest, "The ender chest");
                return;
            } catch (ErrandFailed e) {
                failedChests.put(Arrays.toString(nearest), System.currentTimeMillis() + FAILED_CHEST_COOLDOWN_MS);
                if (!carrying) throw e;
                module.warn("Couldn't use the ender chest at {}; placing one instead", Arrays.toString(nearest));
            }
        }
        if (!carrying) {
            throw ErrandFailed.stop("No ender chest is loaded nearby and the bot isn't carrying one to place");
        }
        openContainer(placeBeside(enderChest), "The ender chest");
    }

    int @Nullable [] nearestEnderChest() {
        failedChests.values().removeIf(expiry -> expiry <= System.currentTimeMillis());
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] chest : BlockEntityScan.find(BlockEntityType.ENDER_CHEST)) {
            if (failedChests.containsKey(Arrays.toString(chest))) continue;
            // It doesn't go below the lowest level for anything.
            if (chest[1] < BlockScan.lowestLevel()) continue;
            if (!isBlock(chest, ItemRegistry.ENDER_CHEST.name())) continue;
            double distance = distanceTo(chest);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chest;
            }
        }
        return best;
    }

    // ----------------------------------------------------------------------------------------
    // the placed shulker box
    // ----------------------------------------------------------------------------------------

    /** Remembers a placed shulker box, so it isn't forgotten if the bot disconnects before collecting it. */
    void rememberPlacedShulker(int[] pos) {
        var worldName = CACHE.getChunkCache().getWorldName();
        PLUGIN_CONFIG.placedShulker = new PlacedBlock(worldName == null ? "" : worldName.toString(), pos[0], pos[1], pos[2]);
        saveConfigAsync();
    }

    void forgetPlacedShulker() {
        PLUGIN_CONFIG.placedShulker = null;
        saveConfigAsync();
    }

    boolean placedShulkerIsInThisWorld() {
        PlacedBlock placed = PLUGIN_CONFIG.placedShulker;
        var worldName = CACHE.getChunkCache().getWorldName();
        return placed != null && worldName != null && placed.world.equals(worldName.toString());
    }
}
