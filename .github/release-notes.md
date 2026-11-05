## What's fixed

**With nothing chosen, the bot emptied its inventory and then stood still.** 1.1.0 dropped the old block list when it was installed, and with no items chosen, the bot treated everything it was carrying as junk. It stored it all as soon as it connected, kept nothing to fill its slots with, and then had nothing to mine. Now, with nothing chosen, it leaves the inventory alone, sends you a notification, and waits for `blockminer add <item>`.

**Spreading items through the inventory failed on a laggy connection.** The bot clicked faster than the server could reply, so a click could be refused, the errand would fail, and after three failures the bot would stop itself. Each click now waits for the server to confirm it. On a slow connection this takes a little longer, so filling all the slots can take several seconds.

**It no longer starts errands the moment it connects.** It waits 5 seconds for the world and inventory to arrive first. That's what caused `No room to place ender_chest beside the bot` straight after connecting.

## What's changed

**Junk gets thrown away instead of stored.** It was taking up space in your shulker boxes and causing extra trips to the ender chest. Only a fixed list of known junk gets thrown, such as `tuff`, `gravel`, `dirt`, `andesite`, `netherrack` and `cobblestone`. The [README](https://github.com/renegadeshiba/zenith-block-miner#throwing-junk-away) has the full list. Anything else still gets stored, so nothing valuable can be thrown away by mistake.

It never throws away an item you've asked for, anything on the keep list, or anything at all if no items are chosen. An item you've just removed from your list gets stored instead, while the bot is still carrying some, so switching from `cobbled_deepslate` to `diamond` doesn't throw away what it mined. To hold on to something on the junk list, use `blockminer keep add <item>`. Thrown items are gone after about 5 minutes.

## You need to do this

If you installed 1.1.0, choose your items again:

```
blockminer add cobbled_deepslate
```

Anything stored by mistake is in a shulker box in the ender chest, so nothing was lost. Giving the bot a stack of what it's after keeps junk out from the start.

## How this was tested

The new tests run the bot's real slot filling and ZenithProxy's real click handling against a pretend server that follows vanilla's rules, with and without lag.

**It hasn't been run on a live server yet.** Please watch the first time it stores items: it should keep one stack and spread it through the empty slots.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.1/BlockMiner-1.1.1.jar
```
