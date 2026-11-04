## What it does

**Mines the blocks you choose** around the bot and keeps going until you stop it.

**Keeps junk out.** It fills every empty inventory slot with one of the item it's mining, so nothing else can be picked up and junk stays on the ground.

**Stores what it mines.** When its stacks fill up, it crafts items like diamonds into blocks, takes an empty shulker box from the ender chest, fills it and puts it back. A stack of the material stays with the bot to fill the slots again.

**Swaps worn tools.** When the tool in hand has 5 durability left, it swaps it for a fresh one from a tool shulker box in the ender chest and leaves the worn one in that shulker box.

See the [README](https://github.com/renegadeshiba/zenith-block-miner#getting-started) for what to put in the ender chest before turning it on.

## Before you run it

**This hasn't been run on a live server yet.** The tests only cover its decisions, such as which recipe to use or when to swap a tool, not the mining, crafting and inventory handling themselves. Try it with nothing valuable on the bot, and watch it the first few times it stores things and swaps a tool.

**It doesn't avoid wardens.** It can walk straight past or into them, and a warden could kill it, so keep it well away from any.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.0.0/BlockMiner-1.0.0.jar
```
