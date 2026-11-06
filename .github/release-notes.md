## What's fixed

**It crafted one block at a time, and stopped after about a stack and a half.** It couldn't recognise recipes the way a real server sends them, so it always used its slow backup: placing nine items by hand for each single craft, up to 100 times per recipe. The rest got stored uncrafted. Now it fills the grid and shift-clicks the result. In the tests, a full inventory of coal went from 100 blocks in 135 seconds to all 206 blocks in about 2 seconds, still keeping a stack of coal to fill slots with.

**It turned itself off saying the ender chest had no shulker boxes, when it did.** A container's contents arrive just after its screen opens, and until then it looks empty. It could read the ender chest in that moment, decide there were no shulker boxes, and stop. Now it waits for the contents, and if it still sees none it gives it a few seconds before turning off. If it does stop for this, the message says how many shulker boxes it can see.

**It got stuck overnight, and had to be moved by hand.** This is the most likely cause, though it hasn't been confirmed from your logs. Blocks left at the end of a level next to lava can't always be reached, and 3 failures in a row counted as being stuck, even with plenty of other rock around. It then built itself 6 blocks up, forgot what it couldn't reach, tried the same blocks again, and turned itself off after a few rounds. Now it just moves on to the next level.

**It left a lot of ore behind.** Ores didn't get the "mine what's above it" treatment, and it couldn't get to ore in a ceiling at all. In the tests, it left about 50 ore in view for every 400 it mined. Now it's about 2, for no extra digging.

## What's new

**When it can't find a way, it tries these in order:**

1. **Building:** it tries again, this time allowed to place blocks it's carrying, including what it's been mining.
2. **Moving on:** what's left of a level that it can't get to doesn't count as being stuck. After 3 tries, it goes on to the next level.
3. **Walking away:** if it really can't get to anything, it walks 100 blocks in a straight line, trying each direction in turn, and starts a new section there. That's what `.pathfinder thisway` was doing by hand.
4. **Building up:** if it can't walk away in any direction, it builds 6 blocks up.

You only get a notification if none of that works, or if it gets stuck 6 times in a row without any mining in between. Each step goes in the log, so that's the place to look if you want to know what happened while you were away.

**It leaves the blocks it places alone**, even though they look just like what it's after, so it doesn't pull down what it built.

**For ores, it clears what's around it before moving on.** It mines anything within reach straight above it without moving, digging through what's in the way, and for ore in a ceiling it stands underneath. It only goes for the next 4 nearest at a time.

## Worth knowing

- **It only builds when a path has failed.** Allowed to build all the time, it climbed between levels on pillars up to 34 blocks high over the open pit instead of digging stairs through the rock, and an arrow or a creeper up there is a long fall with the whole inventory. Building all the time didn't stop it getting stuck either.
- **It remembers what it placed until ZenithProxy restarts.** The pathfinder can also still break one that's directly in its way when it's walking somewhere.
- **A path can pass just below Y -58** if there's no other way past something, like lava all around it. In the tests that happened in 1 of the 4 long runs, going 2 blocks below. It never goes for anything down there.

## How this was tested

The tests use ZenithProxy's real path search, reach check, click handling and chunk code. The real module also ran tick by tick, with a pretend bot doing what it asked, through 170 sections of a world with lots of caves and lava near the bottom. It never got stuck, walked away or turned itself off. In a coal-like world it mined about 4,700 ore per run the same way, without getting stuck. Crafting ran against a pretend crafting table following vanilla's rules, using recipe data shaped as a real server is expected to send it.

The new code was broken on purpose in 76 ways to check that the tests notice. They caught 73, and the other 3 make no real difference to what the bot ends up doing.

**It still hasn't been run on a live server**, and the tests have no drops, mobs or lag. If it stops or does something odd, the Discord message or the lines from the log around that time will show what happened, so please send those.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.6/BlockMiner-1.1.6.jar
restart
```
