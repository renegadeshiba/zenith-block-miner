## Before you install

**Check your keep list.** Anything the bot is carrying that you didn't ask for now gets thrown away, unless it's gear, food, a shulker box or on the keep list. That includes valuable things: if you only asked for `cobbled_deepslate`, a diamond it digs through counts as junk. To hold on to something, ask for it too, or use `blockminer keep add <item>`.

## What's fixed

**The bot dug straight down until it got stuck in the bedrock.** Going down means breaking one block and going sideways means breaking two, so ZenithProxy's miner always went for the block under its feet. It ended up in a pit with bedrock walls, with `No path found` repeating in the log. `cobbled_deepslate` isn't on the list of blocks the pathfinder builds with, so it couldn't climb out even with a full inventory.

**Junk was getting into shulker boxes.** Anything not on a fixed list of junk got stored, such as coal or raw iron it dug through. Now only what you asked for goes in, along with what that crafts into, such as diamond blocks when you asked for diamonds. An item you've just taken off your list still gets stored while the bot has some, so switching items doesn't throw away what it mined.

**Shulker boxes got left part-full.** Each trip started a new one. Now it tops up the fullest one it has already started before taking an empty one. A box only counts as started if it holds nothing but what you asked for, so your tool shulker box is never touched.

**It could drop what it had crafted to make room.** If it ever has to drop something to free up a slot, junk now goes first, ahead of items like your crafted diamond blocks.

## What's changed

**It never goes below Y -50**, whatever it's after, which keeps it clear of bedrock and lava lakes. That's 14 blocks above the bottom of the world, so it's Y 14 in the Nether. It climbs up first if it starts lower, and it won't use an ender chest below that.

**It does its own mining now**, rather than using ZenithProxy's miner, which can't be told to stay above a height. That also removes the search that could run the whole proxy out of memory.

**Blocks that are everywhere**, such as deepslate, stone or netherrack, are mined on one flat level, within 24 blocks of where it started, so that one ender chest stays in reach. When the level is used up it goes down two levels, and when the area is used up all the way to Y -50 it starts a new area next to it.

**Rarer blocks** aren't limited to that area. It goes for the nearest ones wherever they are. If none are loaded it heads off in a straight line until some come into range, and the status says `Looking for ...`.

**If it gets stuck**, it builds its way out with common blocks it's carrying, adding them to the pathfinder's list just until it's out. If it can't, it stops and sends a notification saying where it is, instead of retrying forever. That should also get it out of a pit it's already in.

## Worth knowing

- It digs from where it's standing, so don't start it anywhere it shouldn't dig.
- Junk already in your shulker boxes stays there, and the bot won't add to those boxes.
- Expect a `Pathing complete` line in the log for about every step it mines.

## How this was tested

A pretend bot followed ZenithProxy's real paths through a test world, breaking blocks as it went. It mined a whole area for over 10,000 blocks at about 2 per step, never touching bedrock or going below Y -50, and it reached 25 out of 25 ores through solid rock. Storing ran against a pretend server following vanilla's rules for shift-clicks. The code was also broken on purpose in 43 ways to check that the tests notice.

**It hasn't been run on a live server yet.** Please watch the first time: it should climb out if it's stuck, settle on a flat level, pick up what it breaks, and at its first storing trip top up a part-full box with only your item.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.3/BlockMiner-1.1.3.jar
restart
```
