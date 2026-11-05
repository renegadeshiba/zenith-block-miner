# Block Miner

A ZenithProxy plugin for Minecraft 1.21.4 that mines for the items you choose and keeps going by
itself. You say what you want, and it works out which blocks to mine and what tool to mine them with.
It keeps junk out of its inventory entirely by filling every empty slot with one of the item it's
after. When its stacks fill up, it crafts items like diamonds into blocks and stores everything in
shulker boxes from the ender chest. When a tool is nearly broken, it swaps it for a fresh one from a
tool shulker box in the ender chest.

## Requirements

- ZenithProxy's Java release channel for Minecraft 1.21.4
- Java 21 or newer

## Install

Install or update directly from the Zenith terminal (or Discord as the account owner):

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.0.0/BlockMiner-1.0.0.jar
restart
```

Alternatively, download `BlockMiner-1.0.0.jar` from the
[GitHub release](https://github.com/renegadeshiba/zenith-block-miner/releases/tag/v1.0.0), copy it into
the `plugins/` directory beside your ZenithProxy jar, and restart ZenithProxy.

## Getting started

Before turning it on:

- Put these in the bot's ender chest:
  - **Empty shulker boxes** of any colour, for storing what it mines.
  - **A shulker box of fresh tools**, for replacing worn ones. Include the right kind for what you're
    after: see [Choosing what to mine](#choosing-what-to-mine).
- Give the bot an **ender chest** in case none is loaded nearby, and a **crafting table** if it should
  craft items into blocks and there isn't one nearby.
- Give the bot **a stack of what it's mining**, such as diamonds for diamond ore. It's optional, but
  it means junk is kept out from the very start.
- Don't leave other shulker boxes in the bot's inventory. Any it's carrying when it starts an errand go
  in the ender chest.
- Run it well away from wardens, with nothing valuable on the bot for now. See
  [Things to know](#things-to-know).

Then say what you want and turn it on:

```
blockminer add diamond
blockminer on
```

## Choosing what to mine

Ask for the item you want to end up with. That decides which blocks get mined and with what tool:

| You ask for | It mines | With |
|---|---|---|
| `diamond` | `diamond_ore`, `deepslate_diamond_ore` | a Fortune tool |
| `diamond_ore` | `diamond_ore` | a Silk Touch tool |
| `cobbled_deepslate` | `deepslate`, `cobbled_deepslate` | a tool without Silk Touch |
| `deepslate` | `deepslate` | a Silk Touch tool |
| `ancient_debris` | `ancient_debris` | any tool |

- **Fortune tool:** `coal`, `raw_iron`, `raw_copper`, `raw_gold`, `redstone`, `lapis_lazuli`, `diamond`,
  `emerald`, `quartz`, `gold_nugget`, `glowstone_dust`, `amethyst_shard` and `prismarine_crystals`. These
  all come from both forms of their ore where there are two.
- **No Silk Touch:** `cobblestone`, `cobbled_deepslate` and `clay_ball`. Fortune doesn't get more of these,
  so a plain tool is just as good as a Fortune one.
- **Silk Touch tool:** the blocks those items come from, such as `diamond_ore`, `stone`, `deepslate` and
  `glowstone`. Also `ice`, `packed_ice`, `blue_ice`, `glass`, `bookshelf`, `sculk` and `sculk_catalyst`, which
  drop nothing useful without it.
- **Anything else** that's a block is mined as that block with any tool, such as `obsidian` or `oak_log`.
  Something that isn't a block and isn't listed here can't be added.

`blockminer add` tells you what it will mine and with what, and `blockminer` shows it too.

**Getting the right tool:** the pathfinder picks whichever tool on the hotbar breaks a block fastest, and
it only looks at the hotbar. So before mining, the bot moves any wrong tool off the hotbar and the right
one onto it. If it isn't carrying the right one, it fetches it from a tool shulker box in the ender chest,
leaving the wrong one there. If there isn't one there either, it stops and tells you. The pathfinder's
`autoTool` setting has to be on for this, which is the default.

**Only one kind of tool at a time:** the pathfinder uses one tool for everything it's mining, so you can't
ask for `diamond` and `diamond_ore` together, as one needs Fortune and the other Silk Touch. `blockminer add`
tells you if that's the case. Asking for `diamond` and `cobbled_deepslate` is fine, because a Fortune tool
suits both.

## Commands

```
blockminer on/off
blockminer add <item>
blockminer remove <item>
blockminer clear
blockminer fill on/off
blockminer craft on/off
blockminer toolDurability <count>
blockminer room <stacks>
blockminer keep add <item>
blockminer keep remove <item>
```

Running `blockminer` on its own shows the settings and what the bot is doing. Turning it off during
an errand lets the errand finish first, so a shulker box isn't left on the ground.

## How it works

### Mining

The bot mines the nearest block on its list in the loaded chunks, using ZenithProxy's pathfinder, which
walks into each block it mines and so collects what drops. If there's nothing to mine, it looks again
every 30 seconds, but it doesn't wander off to find more. The pathfinder's `allowBreak` setting has to be
on.

### Keeping junk out

A player can only pick up an item if it fits in an empty slot or tops up a matching stack. So the bot
puts one of its target item in every empty slot. With no empty slots left, the only thing it can pick
up is more of what it's mining, and netherrack, stone and the like stay on the ground.

The target items are exactly the items you asked for, so when you ask for `diamond`, the cobbled deepslate
from mining down to it stays on the ground.

**Until it has enough to fill the slots**, it can still pick up junk, which gets stored like anything
else. Every 30 seconds it fills as many slots as it can with what it has, so this eases off as it goes.
Giving it a stack before it starts avoids this.

**Mining several different items:** the slots are shared between them. An item it has none of yet needs
an empty slot to get in, so one is left open for it, and junk can use that slot too until the next time
it stores things.

Turn this off with `blockminer fill off`.

### When to store

Free slots can't be the signal any more, so the bot stores when there's only room left for 2 more stacks
of its target item, across all of its stacks. Change that with `blockminer room <stacks>`. It also stores
if junk has filled the inventory before the slots could be filled. When it's time, the bot:

1. Merges two partly full stacks to free up a slot, then crafts items into blocks, if crafting is on.
2. Takes an empty shulker box from the ender chest.
3. Places it beside itself and fills it with what it's carrying.
4. Breaks it, picks it back up, and puts it back in the ender chest.
5. Repeats with another shulker box if there's more to store.
6. Fills the empty slots with its target item again, then walks back and carries on mining.

These stay with the bot rather than being stored:

- **a stack of each target item**, to fill the slots with next time
- **one stack of a block the pathfinder can bridge with**, such as netherrack, since it can't pick up
  any more of them
- anything with durability, such as tools and armour
- food
- shulker boxes
- the keep list: `ender_chest`, `crafting_table` and `totem_of_undying` to start with

Add anything else it needs to carry, such as `firework_rocket`, with `blockminer keep add <item>`.

For the ender chest, the bot uses the nearest loaded one within 48 blocks, or further away if it isn't
carrying one. Otherwise it places one beside itself and leaves it there for later.

### Crafting into blocks

Nine of any of these become one block, or an ingot for nuggets, which can then become blocks too:

`gold_nugget`, `iron_nugget`, `coal`, `raw_iron`, `raw_copper`, `raw_gold`, `iron_ingot`, `copper_ingot`,
`gold_ingot`, `netherite_ingot`, `diamond`, `emerald`, `lapis_lazuli`, `redstone`

The stack it keeps is the material, not the block, because diamonds are what can merge with the diamonds
it mines. So when mining diamond ore, it crafts everything beyond one stack of diamonds into blocks, and
the blocks go in the shulker box. A few left over that aren't enough for a block stay with that stack.

The bot uses a crafting table within 5 blocks, or places a carried one. Either way, it breaks the table
and collects it afterwards, even if crafting fails, so it has one for next time. That includes a table
that was already there, so don't count on one you've set up staying put. If it has no table at all, it
warns you and stores the items uncrafted. Turn crafting off with `blockminer craft off`.

### When a tool wears out

When the tool in hand has `toolDurability` or less left (5 by default), the bot:

1. Takes the shulker box with the best replacement from the ender chest.
2. Places it and swaps the worn tool for the fresh one, leaving the worn tool in the shulker box.
3. Breaks it, picks it back up, and puts it back in the ender chest.

It looks for the same tool with the same enchantments first, then the same tool, then the same kind
of tool (a diamond pickaxe for a netherite one, say), and takes whichever has the most durability. It
only takes one that's right for what you asked for, so a worn Fortune pickaxe won't be replaced with a
Silk Touch one when you're after diamonds. This covers pickaxes, axes, shovels and hoes.

### Collecting the shulker box

A broken shulker box only counts as collected once it's really in the inventory. A player only picks up
what's within about 1.3 blocks of their centre, which a drop can be just out of reach of. So if it doesn't
land in the inventory within a second, the bot walks right onto it, using its exact position, or as
close as it can get if it can't stand there. It tries up to six times, logging `Walking to [...] to
collect ...` each time, and makes room again before each try.

If that still doesn't work, the bot stops and notifies you with where it is, because dropped items
disappear after about 5 minutes.

### Making room for a shulker box

The inventory is meant to be full, but a shulker box needs a slot to come out into and to be picked back
up. The bot merges two partly full stacks to free one, and stores before its stacks get too full to do
that. If it ever can't, it drops the smallest stack on the ground, junk before target items, because
that's better than leaving a full shulker box on the ground with nowhere to go.

### When something goes wrong

The bot stops mining, turns itself off and sends a notification when only you can fix the problem:

- no empty shulker boxes left
- no fresh tool left
- no tool of the kind that what you asked for needs
- no ender chest available
- the inventory is full of things it isn't allowed to store

Anything else, such as a path it can't find, gets tried again after 30 seconds, up to three times in a
row before it stops the same way. Run `blockminer on` once it's sorted.

If the bot disconnects or a player takes over with a shulker box still placed, or just after breaking
one, it remembers where and goes back for it when it next runs, whether it's still standing or lying on
the ground.

## Things to know

- **1.0.0 hasn't been run on a live server yet.** The tests only cover its decisions, such as which
  recipe to use or when to swap a tool, not the mining, crafting and inventory handling themselves.
  Expect some bugs at first.
- **It doesn't avoid wardens.** ZenithProxy's pathfinder ignores mobs and picks its own path when
  mining, so the bot can walk straight past or into a warden. A warden could kill it, and it would drop
  whatever it's carrying.
- Run this on its own. The pathfinder can only do one thing at a time, so another plugin moving the bot
  at the same time will conflict with it. If the sign plugin is installed, run both `signplacer off` and
  `restock off`, because the restocker starts by itself whenever the bot is low on signs.
- A drop that flies off somewhere can get left behind, unless it's also a block on the mining list. The
  pathfinder only goes back for drops named after a block it's mining, so it goes back for cobbled
  deepslate but not for diamonds. Normally that's not an issue, because it walks into each block it mines.
- Junk it leaves on the ground can land in a slot the moment one frees up, such as while it's storing
  things. That's harmless: it just gets stored on the next trip.
- A placed ender chest stays where the bot put it, so it can get broken if it ends up in the way of
  mining, which only drops obsidian without Silk Touch.

## Configuration

ZenithProxy creates `plugins/config/block-miner.json` after the plugin first loads. The commands above
change everything in it. To edit it by hand, stop ZenithProxy first.

## Building

```
./gradlew build
```

The jar goes to `build/libs/`. Pushing a `release/*` branch builds it on GitHub and publishes a release
for the version in `gradle.properties`, using `.github/release-notes.md` as the description.
