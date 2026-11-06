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
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.7/BlockMiner-1.1.7.jar
restart
```

Alternatively, download `BlockMiner-1.1.7.jar` from the
[GitHub release](https://github.com/renegadeshiba/zenith-block-miner/releases/tag/v1.1.7), copy it into
the `plugins/` directory beside your ZenithProxy jar, and restart ZenithProxy.

## Getting started

Before turning it on:

- Put these in the bot's ender chest:
  - **Empty shulker boxes** of any colour, for storing what it mines.
  - **A shulker box of fresh tools**, for replacing worn ones. Include the right kind for what you're
    after: see [Choosing what to mine](#choosing-what-to-mine).
- Give the bot **some ender chests**, as it leaves one behind on each level it stores things from, and a
  **crafting table** if it should craft items into blocks and there isn't one nearby.
- Give the bot **a stack of what it's mining**, such as diamonds for diamond ore. It's optional, but
  it means junk is kept out from the very start.
- Give the bot **a sword**, and turn on ZenithProxy's kill aura if anything's likely to attack it. See
  [When something attacks it](#when-something-attacks-it).
- Don't leave other shulker boxes in the bot's inventory. Any it's carrying when it starts an errand go
  in the ender chest.
- Run it well away from wardens, with nothing valuable on the bot for now. See
  [Things to know](#things-to-know).
- Start it somewhere it's fine to dig, as it digs from where it's standing.
- Put anything else it should hold on to on the keep list, with `blockminer keep add <item>`. Apart from
  gear, food and shulker boxes, **whatever it's carrying that you haven't asked for gets thrown away**.

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

`blockminer add` tells you what it will mine and with what, and `blockminer` shows it too. If it's on with
nothing chosen, it leaves the inventory alone, sends you a notification and waits.

**Getting the right tool:** the pathfinder picks whichever tool on the hotbar breaks a block fastest, and
it only looks at the hotbar. So before mining, the bot moves any wrong tool off the hotbar and the right
one onto it. If it isn't carrying the right one, it fetches it from a tool shulker box in the ender chest,
leaving the wrong one there. If there isn't one there either, it stops and tells you. The pathfinder's
`autoTool` setting has to be on for this, which is the default. It does this for a block that gives nothing
to a bare hand as well, even if any tool will do: if it's after `obsidian` with no pickaxe on the hotbar, it
goes and gets one.

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

**The bot never goes below Y -58**, whatever it's after, which keeps its floor above the bedrock. That's 6
blocks above the bottom of the world, so it's Y 6 in the Nether. If it starts lower than that, it climbs up
first. Blocks below that are left alone, and so is an ender chest down there. The one exception is that if
there's no other way past something, like lava all around it, a path can pass just below that on its way
somewhere. Lava is common that far down. It won't break anything touching lava, but it's the most dangerous
place it goes.

The pathfinder's `allowBreak` setting has to be on. The bot decides where to go itself rather than using
ZenithProxy's own miner, which can't be told to stay above a height. Left alone, that digs straight down
until it's stuck in the bedrock, explores at Y -59, and with a block that's everywhere, its search can run
the whole proxy out of memory.

What it does next depends on what you asked for.

**Blocks that are everywhere**, such as deepslate, stone or netherrack, get mined from the top down, one
section at a time. A section is everything within 24 blocks of where it starts.

1. It goes to the top of the section, which is the highest layer that's mostly what you asked for. It digs
   its way there from wherever it is, and the status says `Walking to the nearest ...` until it arrives.
2. At each spot it mines everything it can reach straight above it without moving, which clears a room 7
   blocks high. What it mines falls on it, so it collects it. Then it moves to the next spot along a flat
   floor, collecting what's at its feet and head as it walks in.
3. Once that level is used up, it digs down to the next one, 7 blocks lower.
4. Once the section is used up all the way down to Y -58, it digs back up to the top of the next section
   along, and starts again. It carries on in a straight line, turning if the next one is already mined out.

**It keeps off vines of every kind.** Zenith's pathfinder treats them like ladders, which is much cheaper than
digging, but if the bot can't really climb one, the pathfinder just plans the same way up again, forever. So it
digs its way around them, and only goes on one if there's no other way at all.

A few odd blocks don't count as the top, and neither does a layer that's mostly something else. So for
deepslate it starts about where stone gives way to deepslate, not at a stray block higher up.

At each spot, it mines through something you didn't ask for if there's more of what you want above it,
the same as it does when it walks. It stops there if it comes to something that would fall on it, anything
touching lava or water, or a container. It doesn't go back for what that leaves, or for blocks with a
cave under them that it can't stand on, so expect a few bits left hanging.

**Blocks worth looking for**, such as ores, aren't kept to a section, since there wouldn't be many in it. It
goes for the nearest few in the loaded chunks, at any height, and clears what's around it before moving on. It
mines anything it can reach straight above it without moving, digging through what's in the way, and for ore
in a ceiling, it stands underneath. If none are loaded, it goes looking for
more, heading off in a straight line until some come into range, and the status says `Looking for ...`.
So don't leave the bot mining somewhere it shouldn't dig.

### Keeping junk out

A player can only pick up an item if it fits in an empty slot or tops up a matching stack. So the bot
puts one of its target item in every empty slot. With no empty slots left, the only thing it can pick
up is more of what it's mining, and netherrack, stone and the like stay on the ground.

The target items are exactly the items you asked for, so when you ask for `diamond`, the cobbled deepslate
from mining down to it stays on the ground.

**Until it has enough to fill the slots**, it can still pick up junk. Every 30 seconds it fills as many
slots as it can with what it has, so this eases off as it goes. Giving it a stack before it starts avoids
this.

**Mining several different items:** the slots are shared between them. An item it has none of yet needs
an empty slot to get in, so one is left open for it, and junk can use that slot too until it's next
thrown away.

Turn filling off with `blockminer fill off`.

### Throwing junk away

**Only what you asked for goes in your shulker boxes**, along with what that crafts into, such as diamond
blocks when you asked for diamonds. Everything else that gets in the inventory is junk, and gets thrown on
the ground, where it disappears after about 5 minutes.

That includes anything valuable it happens to dig through: if you only asked for `cobbled_deepslate`, a
diamond is junk. To hold on to something, ask for it too, or put it on the keep list with
`blockminer keep add <item>`.

It won't throw away:

- an item you've asked for, or what that crafts into
- gear, meaning anything with durability, or food, or shulker boxes
- anything on the keep list
- the one stack it holds back for the pathfinder to bridge with
- an item you've just removed from your list, while it's still carrying some, so switching from
  `cobbled_deepslate` to `diamond` stores what it's mined instead of throwing it away
- anything at all if no items are chosen

A thrown item can be picked straight back up by a bot with empty slots. So it waits until it can fill every
slot the junk leaves behind, and refills them straight away. The exception is when junk fills the inventory
before it has enough to do that: it throws it away anyway so it can pick up what it's after, and some may
come back if the bot stays close to where it fell.

### When to store

Free slots can't be the signal any more, so the bot stores when there's only room left for 2 more stacks
of its target item, across all of its stacks. Change that with `blockminer room <stacks>`. When it's
time, the bot:

1. Merges two partly full stacks to free up a slot, then crafts items into blocks, if crafting is on.
2. Takes a shulker box from the ender chest: one it's already started if it can, or else an empty one.
3. Places it beside itself and fills it with what it's carrying.
4. Breaks it, picks it back up, and puts it back in the ender chest.
5. Repeats with another shulker box if there's more to store.
6. Fills the empty slots with its target item again, then walks back and carries on mining.

If there's no room beside it for the ender chest or a shulker box, it mines out a spot: the block next to
it, and the one above that as well if it's in the way, as a shulker box needs that clear to open. It leaves
the block underneath alone, and won't mine bedrock, anything with something in it, such as a chest or a
sign, a crafting table, gravel or sand, or anything touching lava or water.

A shulker box counts as started if it still has room and everything in it is what you asked for or what
that crafts into. The fullest goes first, so that they get filled one at a time instead of being left
part-full. Anything else in the ender chest is left alone, including your tool shulker box.

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
carrying one. Otherwise it places one beside itself and leaves it there for later. It won't use one below
Y -58. If it's carrying one, it won't go back up for one that's more than 2 blocks above it, since that's on
a level it's finished with and the floor it stood on is gone. So it gets through about one ender chest for
each level it stores things from.

### Crafting into blocks

Nine of any of these become one block, or an ingot for nuggets, which can then become blocks too:

`gold_nugget`, `iron_nugget`, `coal`, `raw_iron`, `raw_copper`, `raw_gold`, `iron_ingot`, `copper_ingot`,
`gold_ingot`, `netherite_ingot`, `diamond`, `emerald`, `lapis_lazuli`, `redstone`

The stack it keeps is the material, not the block, because diamonds are what can merge with the diamonds
it mines. So when mining diamond ore, it crafts everything beyond one stack of diamonds into blocks, and
the blocks go in the shulker box. A few left over that aren't enough for a block stay with that stack.

It fills the grid as full as it'll go and shift-clicks the result, which crafts it all at once, so a full
inventory takes a couple of seconds. The recipe book shares out all of a material, including what it means to
keep, so it crafts all of it and then turns a few blocks back to get its stack back. If a recipe isn't in the
account's recipe book, or the recipe book doesn't do anything, it moves whole stacks into the grid by hand
instead, which takes about 20 seconds.

The bot uses a crafting table within 5 blocks, or places a carried one. Either way, it breaks the table
and collects it afterwards, even if crafting fails, so it has one for next time. That includes a table
that was already there, so don't count on one you've set up staying put. If it has no table at all, it
warns you and stores the items uncrafted. Turn crafting off with `blockminer craft off`.

### When a tool wears out

When the tool in hand has `toolDurability` or less left (5 by default), the bot:

1. Takes the shulker box with the best replacement from the ender chest.
2. Places it and swaps the worn tool for the fresh one, leaving the worn tool in the shulker box.
3. Moves the fresh one onto the hotbar if it isn't there already, as that's the only place the pathfinder
   looks. Putting the shulker box down can take up the slot the worn tool was in.
4. Breaks the shulker box, picks it back up, and puts it back in the ender chest.

It looks for the same tool with the same enchantments first, then the same tool, then the same kind
of tool (a diamond pickaxe for a netherite one, say), and takes whichever has the most durability. It
only takes one that's right for what you asked for, so a worn Fortune pickaxe won't be replaced with a
Silk Touch one when you're after diamonds. This covers pickaxes, axes, shovels and hoes.

A worn tool can't wait for anything else, so this also interrupts walking away, which can be 100 blocks of
digging. If the tool breaks on the way to the ender chest anyway, it still comes back with a fresh one.

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

### When something attacks it

Block Miner doesn't fight anything itself. That's up to ZenithProxy's kill aura, and the bot makes way for it:
while the kill aura has something in reach, the bot stops whatever it's doing and stands there, whether it's
mining, walking away or on an errand. It starts again 2 seconds after the last target, which gives anything
that got knocked back time to come back. Without this, the pathfinder gets a turn between the kill aura's hits,
and walks off, turns away, or swaps back to its pickaxe.

If a fight is still going after a minute, the bot goes back to work alongside it and logs a warning, in case
it's something the kill aura can never finish off, like a mob it can reach but not hurt.

Mining `nether_gold_ore` or `gilded_blackstone` makes piglins angry, and so does opening an ender chest or a
shulker box near them. The kill aura doesn't go after plain piglins unless you tell it to, as they count as
neutral, unlike piglin brutes and hoglins:

```
killAura on
killAura targetNeutralMobs on
killAura targetNeutralMobs onlyAggressive on
```

`onlyAggressive` leaves alone any that aren't after the bot, as hitting one angers the rest. Keep a sword on the
bot, because the kill aura otherwise hits with whatever's in hand. It reaches about 3 blocks and doesn't chase
anything, so it can't stop a piglin shooting a crossbow from further away.

### When something goes wrong

The bot stops mining, turns itself off and sends a notification when only you can fix the problem:

- no shulker boxes with room left in the ender chest
- no fresh tool left
- no tool of the kind that what you asked for needs
- no ender chest available
- the inventory is full of things it isn't allowed to store
- it's stuck, and can't walk away or climb out, or keeps getting stuck again

Anything else, such as a path it can't find, gets tried again after 30 seconds, up to three times in a
row before it stops the same way. Run `blockminer on` once it's sorted.

If the bot can't find a way to something, it tries these in order:

1. **Building.** It tries again, this time allowed to place blocks it's carrying, including what it's been
   mining. The pathfinder only builds with blocks on its `acceptableThrowawayItems` list, which doesn't include
   things like cobbled deepslate, so the bot adds common solid blocks it's carrying to that list until a path
   works, and then takes off what it added.
2. **Moving on.** What's left at the end of a level is often hard to get to, like blocks next to lava. After 3
   tries it gives up on what's left of that level and goes on to the next. That doesn't count as being stuck.
3. **Walking away.** If it can't get to anything at all, it walks 100 blocks in a straight line, trying each
   direction in turn, and starts a new section from where it ends up.
4. **Building up.** If it can't walk away in any direction, it builds its way 6 blocks up, which also gets it
   out of a pit in the bedrock.

**A walk that isn't getting anywhere counts as a path it can't find.** If it hasn't been anywhere new for a minute,
it gives up on it and carries on as above, so it can't spend hours on something like a vine it can't climb.

You'll get a notification saying where it is if none of that works, or if it gets stuck 6 times in a row
without getting any mining done in between. Each of these steps also goes in the log, so that's the place to
look if you want to know what happened while you were away.

**It only builds when it has to.** Left to build whenever it likes, the pathfinder climbs between levels on
pillars out in the open instead of digging stairs through the rock, and an arrow or a creeper up there is a
long fall with the whole inventory.

**It leaves the blocks it places alone**, even though they look just like what it's after, so it doesn't pull
down what it built. It only remembers them until ZenithProxy restarts, and the pathfinder can still break one
that's directly in its way when it's walking somewhere.

It also builds its way up if it can't mine out a spot to put something down.

If the bot disconnects or a player takes over with a shulker box still placed, or just after breaking
one, it remembers where and goes back for it when it next runs, whether it's still standing or lying on
the ground.

## Things to know

- **It's had very little testing on a live server.** The tests cover its decisions, and follow
  ZenithProxy's real paths through made-up worlds, but nothing there is a real server, with drops, mobs and
  lag. Expect some bugs at first.
- **It doesn't avoid wardens.** ZenithProxy's pathfinder ignores mobs and picks its own path when
  mining, so the bot can walk straight past or into a warden. A warden could kill it, and it would drop
  whatever it's carrying.
- Run this on its own. The pathfinder can only do one thing at a time, so another plugin moving the bot
  at the same time will conflict with it. If the sign plugin is installed, run both `signplacer off` and
  `restock off`, because the restocker starts by itself whenever the bot is low on signs.
- A drop that flies off somewhere can get left behind, as the bot doesn't go back for drops. Normally
  that's not an issue, because it walks into the blocks it mines, and the rest fall on it.
- **A section turns into a big open pit**, up to around 60 blocks deep in deepslate. It's dark, so mobs can
  spawn in it, and the bot doesn't fight them. See [When something attacks it](#when-something-attacks-it).
- Junk it leaves on the ground can land in a slot the moment one frees up, such as while it's storing
  things. That's harmless: it just gets thrown away again later.
- A placed ender chest stays where the bot put it, so it's left hanging once the level under it gets
  mined. It can also get broken if it ends up in the way of mining, which only drops obsidian without Silk
  Touch.

## Configuration

ZenithProxy creates `plugins/config/block-miner.json` after the plugin first loads. The commands above
change everything in it. To edit it by hand, stop ZenithProxy first.

## Building

```
./gradlew build
```

The jar goes to `build/libs/`. Pushing a `release/*` branch builds it on GitHub and publishes a release
for the version in `gradle.properties`, using `.github/release-notes.md` as the description.
