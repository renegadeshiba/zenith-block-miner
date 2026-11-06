package com.zenithplugins.blockminer.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.cache.data.inventory.InventoryCache;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.InventoryManager;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.BlockStateInterface;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.network.client.handler.incoming.inventory.ContainerSetContentHandler;
import com.zenith.network.client.handler.incoming.inventory.ContainerSetSlotHandler;
import com.zenith.network.client.handler.incoming.inventory.SetCursorItemHandler;
import com.zenith.util.ItemUtil;
import com.zenith.util.RequestFuture;
import com.zenithplugins.blockminer.BlockMinerConfig;
import com.zenithplugins.blockminer.BlockMinerPlugin;
import com.zenithplugins.blockminer.rules.StorageRules;
import com.zenithplugins.blockminer.rules.TargetItems.ToolNeed;
import com.zenithplugins.blockminer.rules.ToolRules;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a whole trip to swap a tool: the bot's real errand, ZenithProxy's real clicks and its real handlers for what a
 * server sends back, against a pretend server that follows vanilla's rules for an inventory, an ender chest and a
 * placed shulker box. What this can't show is a real server's own quirks, or the walking, which it takes as done.
 */
class SwappingAWornToolTest {

    private static final int OWN_SLOTS = 46;
    private static final int MAIN_START = 9;
    private static final int HOTBAR_START = 36;
    private static final int SLOT_END = 45;
    private static final int CONTAINER_SLOTS = 27;
    private static final int STACK = 64;
    private static final int FEET_Y = -45;
    private static final int[] ENDER_CHEST = {3, FEET_Y, 3};
    private static final int WORN = 5;
    private static final int FRESH = 1500;

    /** Holds the true contents, and tells the client about changes a little later, as vanilla does. */
    private final class FakeServer {
        final ItemStack[] own = new ItemStack[OWN_SLOTS];
        final ItemStack[] enderChest = new ItemStack[CONTAINER_SLOTS];
        final Map<String, ItemStack[]> shulkersPlaced = new HashMap<>();
        final List<ItemStack> onTheGround = new ArrayList<>();
        int heldSlot;
        ItemStack cursor;
        // How long the contents of the containers it opens take to turn up, in the order it opens them, for the
        // slow ones. Whatever else it opens is as quick as anything else.
        long[] contentsDelaysMs = {};
        long latencyMs = 30;
        // Blocks dug through by the next walk, which wear down whatever's in hand.
        int blocksOnTheWay;

        private ItemStack[] open;
        private ContainerType openType;
        private int openId;
        private int lastId;
        private int opened;
        private int stateId;
        private int ownStateId = 1;
        private final ItemStack[] ownRemote = new ItemStack[OWN_SLOTS];
        private ItemStack[] openRemote;
        private ItemStack remoteCursor;

        final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FakeServer");
            thread.setDaemon(true);
            return thread;
        });

        private int size() {
            return open != null ? CONTAINER_SLOTS + 36 : OWN_SLOTS;
        }

        private ItemStack get(int slot) {
            if (open == null) return own[slot];
            return slot < CONTAINER_SLOTS ? open[slot] : own[slot - CONTAINER_SLOTS + MAIN_START];
        }

        private void set(int slot, ItemStack stack) {
            if (open == null) {
                own[slot] = stack;
            } else if (slot < CONTAINER_SLOTS) {
                open[slot] = stack;
            } else {
                own[slot - CONTAINER_SLOTS + MAIN_START] = stack;
            }
        }

        synchronized void receive(Packet packet) {
            if (packet instanceof ServerboundContainerClickPacket click) {
                // Zenith applies its own prediction as the packet goes out.
                inventory().handleContainerClick(click);
                if (click.getContainerId() != openId) return;
                boolean outOfDate = click.getStateId() != (open != null ? stateId : ownStateId);
                boolean takingOutAShulkerBox = open != null && click.getSlot() < CONTAINER_SLOTS
                    && click.getActionType() == ContainerActionType.SHIFT_CLICK_ITEM && StorageRules.isShulker(get(click.getSlot()));
                boolean clickInAShulkerBox = open != null && openType == ContainerType.SHULKER_BOX
                    && click.getActionType() == ContainerActionType.CLICK_ITEM;
                apply(click);
                if (takingOutAShulkerBox && fightAfterTakingOutAShulkerBox) {
                    fightAfterTakingOutAShulkerBox = false;
                    startAFight();
                }
                if (clickInAShulkerBox && fightAfterClicksInAShulkerBox > 0 && --fightAfterClicksInAShulkerBox == 0) startAFight();
                ItemStack[] remote = open != null ? openRemote : ownRemote;
                click.getChangedSlots().forEach((slot, stack) -> remote[slot] = stack);
                remoteCursor = click.getCarriedItem();
                if (outOfDate) sendEverything(0); else sendChanges();
            } else if (packet instanceof ServerboundContainerClosePacket) {
                close();
            } else if (packet instanceof ServerboundSetCarriedItemPacket held) {
                heldSlot = held.getSlot();
                CACHE.getPlayerCache().setHeldItemSlot(heldSlot);
            }
        }

        private void apply(ServerboundContainerClickPacket click) {
            int slot = click.getSlot();
            switch (click.getActionType()) {
                case CLICK_ITEM -> {
                    assertEquals(ClickItemAction.LEFT_CLICK, click.getActionParam(), "only left clicks are pretended");
                    leftClick(slot);
                }
                case SHIFT_CLICK_ITEM -> quickMove(slot);
                case MOVE_TO_HOTBAR_SLOT -> swapWithHotbar(slot, ((MoveToHotbarAction) click.getActionParam()).getId());
                case DROP_ITEM -> {
                    onTheGround.add(get(slot));
                    set(slot, null);
                }
                default -> throw new AssertionError("Didn't expect " + click.getActionType());
            }
        }

        private boolean canGoIn(int slot, ItemStack stack) {
            return !(open != null && openType == ContainerType.SHULKER_BOX && slot < CONTAINER_SLOTS && StorageRules.isShulker(stack));
        }

        private void leftClick(int slot) {
            ItemStack inSlot = get(slot);
            if (inSlot == null) {
                if (cursor != null && canGoIn(slot, cursor)) {
                    set(slot, cursor);
                    cursor = null;
                }
            } else if (cursor == null) {
                cursor = inSlot;
                set(slot, null);
            } else if (!canGoIn(slot, cursor)) {
                return;
            } else if (stacksWith(inSlot, cursor)) {
                int moved = Math.min(cursor.getAmount(), maxStack(inSlot) - inSlot.getAmount());
                set(slot, withAmount(inSlot, inSlot.getAmount() + moved));
                cursor = cursor.getAmount() > moved ? withAmount(cursor, cursor.getAmount() - moved) : null;
            } else {
                set(slot, cursor);
                cursor = inSlot;
            }
        }

        private void swapWithHotbar(int slot, int hotbarIndex) {
            ItemStack inHotbar = own[HOTBAR_START + hotbarIndex];
            own[HOTBAR_START + hotbarIndex] = get(slot);
            set(slot, inHotbar);
        }

        /** Vanilla's rule: onto matching stacks first, then the first empty slot. Into the inventory it starts from the end. */
        private void quickMove(int from) {
            ItemStack moving = get(from);
            if (moving == null) return;
            assertNotNull(open, "only shift-clicks with something open are pretended");
            boolean intoInventory = from < CONTAINER_SLOTS;
            List<Integer> slots = new ArrayList<>();
            if (intoInventory) {
                for (int slot = size() - 1; slot >= CONTAINER_SLOTS; slot--) slots.add(slot);
            } else {
                for (int slot = 0; slot < CONTAINER_SLOTS; slot++) slots.add(slot);
            }
            int left = moving.getAmount();
            for (int slot : slots) {
                ItemStack inSlot = get(slot);
                if (left == 0 || inSlot == null || !stacksWith(inSlot, moving) || !canGoIn(slot, moving)) continue;
                int moved = Math.min(left, maxStack(inSlot) - inSlot.getAmount());
                set(slot, withAmount(inSlot, inSlot.getAmount() + moved));
                left -= moved;
            }
            for (int slot : slots) {
                if (left == 0 || get(slot) != null || !canGoIn(slot, moving)) continue;
                set(slot, withAmount(moving, left));
                left = 0;
            }
            set(from, left > 0 ? withAmount(moving, left) : null);
        }

        private int nextState() {
            if (open != null) return ++stateId;
            return ++ownStateId;
        }

        private void sendChanges() {
            ItemStack[] remote = open != null ? openRemote : ownRemote;
            for (int slot = 0; slot < size(); slot++) {
                ItemStack now = get(slot);
                if (Objects.equals(remote[slot], now)) continue;
                remote[slot] = now;
                ClientboundContainerSetSlotPacket packet = new ClientboundContainerSetSlotPacket(openId, nextState(), slot, now);
                later(latencyMs, () -> new ContainerSetSlotHandler().applyAsync(packet, null));
            }
            if (!Objects.equals(remoteCursor, cursor)) {
                remoteCursor = cursor;
                ClientboundSetCursorItemPacket packet = new ClientboundSetCursorItemPacket(cursor);
                later(latencyMs, () -> new SetCursorItemHandler().applyAsync(packet, null));
            }
        }

        private void sendEverything(long extraDelayMs) {
            ItemStack[] remote = open != null ? openRemote : ownRemote;
            ItemStack[] items = new ItemStack[size()];
            for (int slot = 0; slot < size(); slot++) {
                items[slot] = get(slot);
                remote[slot] = items[slot];
            }
            remoteCursor = cursor;
            ClientboundContainerSetContentPacket packet = new ClientboundContainerSetContentPacket(openId, nextState(), items, cursor);
            later(latencyMs + extraDelayMs, () -> new ContainerSetContentHandler().applyAsync(packet, null));
        }

        private void later(long delayMs, Runnable onTheClient) {
            network.schedule(() -> {
                try {
                    onTheClient.run();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }

        synchronized void openContainer(ItemStack[] contents, ContainerType type) {
            open = contents;
            openType = type;
            openId = ++lastId;
            stateId = 0;
            openRemote = new ItemStack[size()];
            int id = openId;
            later(latencyMs, () -> inventory().openContainer(id, type, Component.text("Container")));
            sendEverything(opened < contentsDelaysMs.length ? contentsDelaysMs[opened] : 0);
            opened++;
        }

        private synchronized void close() {
            if (open != null) {
                // Vanilla carries over what it thinks the client has for the slots they share.
                for (int slot = 0; slot < 36; slot++) ownRemote[MAIN_START + slot] = openRemote[CONTAINER_SLOTS + slot];
            }
            open = null;
            openId = 0;
            if (cursor != null) {
                pickUp(cursor);
                cursor = null;
            }
            // The client clears its own cursor when it closes a screen.
            remoteCursor = null;
            sendChanges();
        }

        /** Vanilla's rule: onto a matching stack, then the first empty slot, starting from the hotbar. */
        private boolean pickUp(ItemStack stack) {
            List<Integer> slots = new ArrayList<>();
            for (int slot = HOTBAR_START; slot < SLOT_END; slot++) slots.add(slot);
            for (int slot = MAIN_START; slot < HOTBAR_START; slot++) slots.add(slot);
            int left = stack.getAmount();
            for (int slot : slots) {
                ItemStack inSlot = own[slot];
                if (left == 0 || inSlot == null || !stacksWith(inSlot, stack)) continue;
                int moved = Math.min(left, maxStack(inSlot) - inSlot.getAmount());
                own[slot] = withAmount(inSlot, inSlot.getAmount() + moved);
                left -= moved;
            }
            for (int slot : slots) {
                if (left == 0 || own[slot] != null) continue;
                own[slot] = withAmount(stack, left);
                left = 0;
            }
            return left == 0;
        }

        synchronized void useHeldTool() {
            ItemStack held = own[HOTBAR_START + heldSlot];
            if (held == null || ToolRules.miningToolType(held) == null) return;
            int left = ItemUtil.getDamageUntilBreak(held) - 1;
            own[HOTBAR_START + heldSlot] = left > 0 ? tool(held, left) : null;
            sendChanges();
        }

        synchronized boolean place(int x, int y, int z, ItemData item) {
            ItemStack held = own[HOTBAR_START + heldSlot];
            if (held == null || held.getId() != item.id()) return false;
            own[HOTBAR_START + heldSlot] = held.getAmount() > 1 ? withAmount(held, held.getAmount() - 1) : null;
            if (StorageRules.isShulker(held)) {
                ItemStack[] contents = new ItemStack[CONTAINER_SLOTS];
                List<ItemStack> stored = held.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
                for (int slot = 0; stored != null && slot < stored.size(); slot++) contents[slot] = stored.get(slot);
                shulkersPlaced.put(key(x, y, z), contents);
                world.set(x, y, z, BlockRegistry.REGISTRY.get(item.name()));
            } else {
                world.enderChestAt(x, y, z);
            }
            sendChanges();
            return true;
        }

        synchronized boolean rightClick(int x, int y, int z) {
            ItemStack[] shulker = shulkersPlaced.get(key(x, y, z));
            if (shulker != null) {
                openContainer(shulker, ContainerType.SHULKER_BOX);
            } else {
                openContainer(enderChest, ContainerType.GENERIC_9X3);
            }
            return true;
        }

        synchronized void breakBlock(int x, int y, int z) {
            useHeldTool();
            Block block = BlockStateInterface.getBlock(x, y, z);
            world.set(x, y, z, BlockRegistry.AIR);
            ItemStack[] shulker = shulkersPlaced.remove(key(x, y, z));
            if (shulker == null) return;
            ItemStack item = shulkerItem(ItemRegistry.REGISTRY.get(block.name()), shulker);
            network.schedule(() -> {
                synchronized (this) {
                    if (!pickUp(item)) onTheGround.add(item);
                    sendChanges();
                }
            }, latencyMs, TimeUnit.MILLISECONDS);
        }

        void walked() {
            for (; blocksOnTheWay > 0; blocksOnTheWay--) useHeldTool();
        }

        void settle() throws InterruptedException {
            network.shutdown();
            assertTrue(network.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /** Does what it's asked straight away, as the real one would a moment later, after walking there. */
    private final class PretendPathfinder extends Baritone {
        private volatile PathingRequestFuture walkInterrupted;

        private static PathingRequestFuture finished(boolean worked) {
            PathingRequestFuture future = new PathingRequestFuture();
            future.complete(worked);
            return future;
        }

        @Override
        public PathingRequestFuture pathTo(Goal goal) {
            assertFalse(fights.underWay(), "it shouldn't set off anywhere during a fight");
            pathsStarted++;
            if (fightsWhenWalkingToADrop > 0 || fightOnTheWay) {
                // It doesn't get there. A fight starts along the way, and it's over once the bot has stopped for it.
                if (fightsWhenWalkingToADrop > 0) fightsWhenWalkingToADrop--;
                fightOnTheWay = false;
                fightGoingOn = true;
                fightStartedMs = System.currentTimeMillis();
                walkInterrupted = new PathingRequestFuture();
                return walkInterrupted;
            }
            server.walked();
            return finished(true);
        }

        @Override
        public PathingRequestFuture rightClickBlock(int x, int y, int z) {
            return finished(server.rightClick(x, y, z));
        }

        @Override
        public PathingRequestFuture placeBlock(int x, int y, int z, ItemData item) {
            boolean placed = server.place(x, y, z, item);
            if (fightJustAsItsPlaced) {
                // It goes down, but the fight stops the pathfinder before it can say that it worked.
                fightJustAsItsPlaced = false;
                startAFight();
                walkInterrupted = new PathingRequestFuture();
                return walkInterrupted;
            }
            return finished(placed);
        }

        @Override
        public PathingRequestFuture breakBlock(int x, int y, int z, boolean autoTool) {
            server.breakBlock(x, y, z);
            return finished(true);
        }

        @Override
        public void stop() {
            if (walkInterrupted == null) return;
            walkInterrupted.complete(false);
            walkInterrupted = null;
            fightGoingOn = false;
            slowestToStopForAFightMs = Math.max(slowestToStopForAFightMs, System.currentTimeMillis() - fightStartedMs);
        }
    }

    private final class PretendInventoryManager extends InventoryManager {
        @Override
        public synchronized RequestFuture submit(InventoryActionRequest request) {
            assertFalse(fights.underWay(), "the kill aura would outrank this, and it would never get anywhere");
            for (InventoryAction action : request.getActions()) {
                var packet = action.packet();
                if (packet != null) server.receive(packet);
            }
            return RequestFuture.immediateFuture(true);
        }
    }

    /** It won't last long. The bot still waits a little while after the last target, as it does for any fight. */
    private void startAFight() {
        fightGoingOn = true;
        server.network.schedule(() -> fightGoingOn = false, 500, TimeUnit.MILLISECONDS);
    }

    private static InventoryCache inventory() {
        return CACHE.getPlayerCache().getInventoryCache();
    }

    private static ItemStack withAmount(ItemStack stack, int amount) {
        return new ItemStack(stack.getId(), amount, stack.getDataComponents());
    }

    private static boolean stacksWith(ItemStack one, ItemStack other) {
        return one.getId() == other.getId()
            && Objects.equals(one.getDataComponentsOrEmpty(), other.getDataComponentsOrEmpty())
            && maxStack(one) > 1;
    }

    private static int maxStack(ItemStack stack) {
        return ItemRegistry.REGISTRY.get(stack.getId()).stackSize();
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static ItemStack enchantedTool(ItemData item, int durabilityLeft, EnchantmentData... enchantments) {
        int maxDamage = ItemUtil.getMaxDamage(new ItemStack(item.id(), 1));
        Int2IntOpenHashMap levels = new Int2IntOpenHashMap();
        for (EnchantmentData enchantment : enchantments) levels.put(enchantment.id(), 3);
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.DAMAGE, maxDamage - durabilityLeft);
        components.put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.builder().enchantments(levels).showInTooltip(true).build());
        return new ItemStack(item.id(), 1, components);
    }

    /** The same tool with a different amount of durability left. */
    private static ItemStack tool(ItemStack tool, int durabilityLeft) {
        DataComponents components = new DataComponents(new HashMap<>(tool.getDataComponents().getDataComponents()));
        components.put(DataComponentTypes.DAMAGE, ItemUtil.getMaxDamage(tool) - durabilityLeft);
        return new ItemStack(tool.getId(), 1, components);
    }

    private static ItemStack fortunePick(int durabilityLeft) {
        return enchantedTool(ItemRegistry.NETHERITE_PICKAXE, durabilityLeft, EnchantmentRegistry.FORTUNE.get());
    }

    private static ItemStack silkPick(int durabilityLeft) {
        return enchantedTool(ItemRegistry.NETHERITE_PICKAXE, durabilityLeft, EnchantmentRegistry.SILK_TOUCH.get());
    }

    private static ItemStack shulkerItem(ItemData item, ItemStack... contents) {
        boolean anything = Arrays.stream(contents).anyMatch(Objects::nonNull);
        if (!anything) return new ItemStack(item.id(), 1);
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CONTAINER, new ArrayList<>(Arrays.asList(contents)));
        return new ItemStack(item.id(), 1, components);
    }

    private Fights fights;
    private volatile boolean fightGoingOn;
    // Fights that break out between one step of an errand and the next.
    private volatile boolean fightAfterTakingOutAShulkerBox;
    private volatile boolean fightJustAsItsPlaced;
    private volatile int fightAfterClicksInAShulkerBox;
    private volatile boolean fightOnTheWay;
    private volatile int fightsWhenWalkingToADrop;
    private volatile long fightStartedMs;
    private volatile long slowestToStopForAFightMs;
    private volatile int pathsStarted;
    private TestWorld world;
    private FakeServer server;
    private BlockMinerConfig previousConfig;
    private Bot bot;
    private ToolSwapErrand toolSwap;
    private InventorySpace space;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        previousConfig = BlockMinerPlugin.PLUGIN_CONFIG;
        BlockMinerPlugin.PLUGIN_CONFIG = new BlockMinerConfig();
        BlockMinerPlugin.PLUGIN_CONFIG.wantedItems = new ArrayList<>(List.of("coal"));

        world = new TestWorld(1);
        world.layer(FEET_Y - 3, FEET_Y - 1, BlockRegistry.STONE);
        world.enderChestAt(ENDER_CHEST[0], ENDER_CHEST[1], ENDER_CHEST[2]);
        TestWorld.putBotAt(0.5, FEET_Y, 0.5);

        server = new FakeServer();
        BlockMinerModule module = new BlockMinerModule() {
            @Override
            public void sendClientPacketAsync(Packet packet) {
                server.receive(packet);
            }
        };
        inventory().reset();
        CACHE.getPlayerCache().setHeldItemSlot(0);
        fights = new Fights(() -> fightGoingOn, System::currentTimeMillis, () -> { });
        bot = new Bot(module, () -> true, new PretendPathfinder(), new PretendInventoryManager(), fights);
        space = new InventorySpace(module, bot);
        StorageErrand storage = new StorageErrand(module, bot, space, new CraftingErrand(module, bot, space));
        toolSwap = new ToolSwapErrand(module, bot, space, storage);
    }

    @AfterEach
    void tearDown() {
        server.network.shutdownNow();
        inventory().reset();
        CACHE.getPlayerCache().setHeldItemSlot(0);
        world.clear();
        TestWorld.putBotAt(0, 0, 0);
        BlockMinerPlugin.PLUGIN_CONFIG = previousConfig;
    }

    /** Both sides start out agreeing on what the bot's carrying. */
    private void carry(int slot, ItemStack stack) {
        server.own[slot] = stack;
        server.ownRemote[slot] = stack;
        inventory().getPlayerInventory().setItemStack(slot, stack);
    }

    /** The inventory as the bot keeps it while mining: nowhere for junk to land, with the odd stack building up. */
    private void carryAFullInventoryOfCoal() {
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            if (server.own[slot] == null) carry(slot, new ItemStack(ItemRegistry.COAL.id(), slot % 5 == 0 ? 40 : 1));
        }
    }

    private int coalCarried() {
        int total = 0;
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            if (server.own[slot] != null && server.own[slot].getId() == ItemRegistry.COAL.id()) total += server.own[slot].getAmount();
        }
        return total;
    }

    private ItemStack toolShulker(ItemStack... tools) {
        return shulkerItem(ItemRegistry.SHULKER_BOX, tools);
    }

    private List<ItemStack> toolsInTheEnderChest() {
        List<ItemStack> tools = new ArrayList<>();
        for (ItemStack stack : server.enderChest) {
            if (StorageRules.isShulker(stack)) tools.addAll(StorageRules.contents(stack));
        }
        return tools;
    }

    private int shulkersInTheEnderChest() {
        return (int) Arrays.stream(server.enderChest).filter(StorageRules::isShulker).count();
    }

    /** The same tool, give or take a little wear from breaking the shulker box with it. */
    private static boolean sameTool(ItemStack tool, ItemStack other) {
        return other != null && tool.getId() == other.getId()
            && Objects.equals(
                tool.getDataComponents().get(DataComponentTypes.ENCHANTMENTS),
                other.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS))
            && Math.abs(ItemUtil.getDamageUntilBreak(tool) - ItemUtil.getDamageUntilBreak(other)) <= 5;
    }

    private void assertOnTheHotbar(ItemStack tool) {
        for (int slot = HOTBAR_START; slot < SLOT_END; slot++) {
            if (sameTool(tool, server.own[slot])) return;
        }
        fail("The tool isn't on the hotbar, which holds " + Arrays.toString(Arrays.copyOfRange(server.own, HOTBAR_START, SLOT_END))
            + ", and the main inventory " + Arrays.toString(Arrays.copyOfRange(server.own, MAIN_START, HOTBAR_START)));
    }

    private void assertNothingLeftOut() {
        assertEquals(0, server.shulkersPlaced.size(), "no shulker box should be left on the ground");
        assertEquals(List.of(), server.onTheGround, "nothing should be left on the ground");
        assertNull(server.cursor, "nothing should be left on the cursor");
        for (int slot = MAIN_START; slot < SLOT_END; slot++) {
            assertFalse(StorageRules.isShulker(server.own[slot]), "no shulker box should be left in slot " + slot);
        }
    }

    private void swapWhatsInHand(ToolNeed need) {
        assertTrue(ToolRules.isWorn(bot.heldStack(), BlockMinerPlugin.PLUGIN_CONFIG.toolDurabilityThreshold), "it's meant to be worn");
        toolSwap.run(bot.heldStack(), need);
    }

    @Test
    void swapsAWornPickaxeForAFreshOne() throws InterruptedException {
        ItemStack worn = fortunePick(WORN);
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, worn);
        carryAFullInventoryOfCoal();
        int coal = coalCarried();
        server.enderChest[4] = toolShulker(fresh);

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(worn), toolsInTheEnderChest());
        assertEquals(1, shulkersInTheEnderChest());
        assertEquals(coal, coalCarried());
        assertNothingLeftOut();
    }

    @Test
    void swapsAToolWhenWhatsBeingMinedCanComeOffAnyTool() throws InterruptedException {
        ItemStack worn = enchantedTool(ItemRegistry.NETHERITE_PICKAXE, WORN);
        ItemStack fresh = enchantedTool(ItemRegistry.NETHERITE_PICKAXE, FRESH);
        carry(HOTBAR_START, worn);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        swapWhatsInHand(ToolNeed.ANY);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(worn), toolsInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void swapsOnASlowConnection() throws InterruptedException {
        server.latencyMs = 400;
        ItemStack worn = fortunePick(WORN);
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, worn);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(worn), toolsInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void anEnderChestThatsSlowEveryTimeStillGetsTheShulkerBoxBack() throws InterruptedException {
        server.contentsDelaysMs = new long[]{3_000, 3_000, 3_000};
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, fortunePick(WORN));
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertNothingLeftOut();
        assertEquals(1, shulkersInTheEnderChest());
    }

    @Test
    void waitsForAShulkerBoxesContentsBeforeSayingTheToolsGone() throws InterruptedException {
        server.contentsDelaysMs = new long[]{0, 6_000};
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, fortunePick(WORN));
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertNothingLeftOut();
    }

    @Test
    void waitsForAnEnderChestsContentsBeforeSayingThereIsNoTool() throws InterruptedException {
        server.contentsDelaysMs = new long[]{6_000};
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, fortunePick(WORN));
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertNothingLeftOut();
    }

    @Test
    void carriesOnIfTheWornToolBreaksOnTheWayToTheEnderChest() throws InterruptedException {
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, fortunePick(WORN));
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);
        server.blocksOnTheWay = WORN;

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(), toolsInTheEnderChest());
        assertEquals(1, shulkersInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void waitsForAFightOnTheWayToTheEnderChestAndThenCarriesOn() throws InterruptedException {
        ItemStack worn = fortunePick(WORN);
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, worn);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);
        fightOnTheWay = true;

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertTrue(pathsStarted >= 2, "it should set off again after the fight");
        assertOnTheHotbar(fresh);
        assertEquals(List.of(worn), toolsInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void waitsForAFightGoingOnBeforeTakingAnythingOut() throws InterruptedException {
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, fortunePick(WORN));
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);
        fightGoingOn = true;
        server.network.schedule(() -> fightGoingOn = false, 500, TimeUnit.MILLISECONDS);

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertNothingLeftOut();
    }

    private void assertASwapStillWorksWith(Runnable aFight) throws InterruptedException {
        ItemStack worn = fortunePick(WORN);
        ItemStack fresh = fortunePick(FRESH);
        carry(HOTBAR_START, worn);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);
        aFight.run();

        swapWhatsInHand(ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(worn), toolsInTheEnderChest());
        assertEquals(1, shulkersInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void waitsForAFightBeforeGettingTheShulkerBoxOutToPutDown() throws InterruptedException {
        assertASwapStillWorksWith(() -> fightAfterTakingOutAShulkerBox = true);
    }

    @Test
    void waitsForAFightBeforeMovingTheFreshToolOntoTheHotbar() throws InterruptedException {
        assertASwapStillWorksWith(() -> fightAfterClicksInAShulkerBox = 3);
    }

    /** Starting again finds the spot taken and nothing left to place, which shouldn't look like it never went down. */
    @Test
    void aShulkerBoxPutDownJustAsAFightStartsStillCounts() throws InterruptedException {
        assertASwapStillWorksWith(() -> fightJustAsItsPlaced = true);
    }

    @Test
    void fetchingAToolLeavesAWornOneBehind() throws InterruptedException {
        ItemStack worn = fortunePick(WORN);
        ItemStack fresh = fortunePick(FRESH);
        carry(MAIN_START + 3, worn);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        toolSwap.ensureRightTool(List.of(BlockRegistry.COAL_ORE), ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(worn), toolsInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void aFightWhileWalkingToADropDoesntUseUpATry() {
        fightsWhenWalkingToADrop = 2;
        int[] near = {0, FEET_Y, 0};

        boolean collected = bot.collectDrop(near, ItemRegistry.SHULKER_BOX.id(), 0, () -> { });

        assertFalse(collected);
        assertEquals(0, fightsWhenWalkingToADrop, "it should have sat them all out");
        assertEquals(6 + 2, pathsStarted, "with all of its tries still to come after them");
        assertTrue(slowestToStopForAFightMs < 5_000, "it should stop walking as soon as a fight starts, but took "
            + slowestToStopForAFightMs + "ms");
    }

    @Test
    void turnsOffWithAClearMessageIfThereReallyIsNoFreshTool() throws InterruptedException {
        carry(HOTBAR_START, fortunePick(WORN));
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(silkPick(FRESH), fortunePick(2));

        ErrandFailed failure = assertThrows(ErrandFailed.class, () -> swapWhatsInHand(ToolNeed.FORTUNE));
        server.settle();

        assertFalse(failure.worthRetrying());
        assertTrue(failure.getMessage().contains("Fortune"), failure.getMessage());
        assertEquals(1, shulkersInTheEnderChest(), "it shouldn't have taken anything out");
    }

    @Test
    void fetchesAToolItHasNoneOf() throws InterruptedException {
        ItemStack fresh = fortunePick(FRESH);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(fresh);

        toolSwap.ensureRightTool(List.of(BlockRegistry.COAL_ORE), ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(fresh);
        assertEquals(List.of(), toolsInTheEnderChest());
        assertNothingLeftOut();
    }

    @Test
    void swapsTheWrongToolForTheRightOne() throws InterruptedException {
        ItemStack wrong = silkPick(FRESH);
        ItemStack right = fortunePick(FRESH);
        carry(HOTBAR_START, wrong);
        carryAFullInventoryOfCoal();
        server.enderChest[0] = toolShulker(right);

        toolSwap.ensureRightTool(List.of(BlockRegistry.COAL_ORE), ToolNeed.FORTUNE);
        server.settle();

        assertOnTheHotbar(right);
        assertEquals(List.of(wrong), toolsInTheEnderChest());
        assertNothingLeftOut();
    }
}
