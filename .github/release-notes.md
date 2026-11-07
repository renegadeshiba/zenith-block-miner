## What's fixed

**The kill aura stopped working, and the bot just stood there getting hit.** Since 1.1.8, the bot stands still whenever the kill aura has something in reach, so that the pathfinder doesn't get in its way between hits. But a mob at the edge of the kill aura's reach, like a piglin with a crossbow keeping its distance, can give it a target for a moment over and over without it ever landing a hit. Each time, the bot stood still for another 2 seconds, so it stood there being shot.

Now it only stands still while the kill aura is actually getting hits in, which the bot can tell from the attacks it sends. It gives the kill aura 3 seconds to get a hit in, at the start of a fight and after each hit. If it hasn't, the bot carries on with what it was doing and logs this, at most once every 5 minutes:

> The kill aura has something to fight but hasn't hit it in 3 seconds, so carrying on.

**The kill aura couldn't always get its sword into hand.** To switch weapons, the kill aura closes whatever's open and moves the sword into hand, through the inventory. If that happened in the middle of an errand, like storing things, crafting or swapping a tool, Block Miner carried on moving things around in the inventory at the same time, and could even carry on clicking in a screen the kill aura had closed, where every slot means something else.

Now, while the kill aura fights, errands keep their hands off the inventory. If a fight starts in the middle of one, the bot finishes moving anything it's halfway through moving, since the kill aura throws away whatever's on the cursor. Then it closes anything it has open, so the kill aura can switch weapons, and starts the errand again once the fight's over. That doesn't count as an errand failing. And it never clicks in a screen something else has closed.

Block Miner doesn't change any of the kill aura's own settings.

## Worth knowing

- **If the bot still stands there without the kill aura hitting anything**, check `blockminer`. If its status doesn't start with `Leaving it to the kill aura`, it isn't Block Miner keeping it still. The kill aura holds the bot still itself while it's trying to get its weapon into hand. `killAura` shows its settings, including the weapon type and material it looks for.
- **Plain piglins still need `killAura targetNeutralMobs on`**, as they count as neutral.

## Also in this release

Everything from 1.1.9, released just before this: it no longer stands on an ender chest all night logging `Pathing complete`, it mines tuff and anything like it from the top down, and building no longer takes a failed search before every block. See the [1.1.9 release](https://github.com/renegadeshiba/zenith-block-miner/releases/tag/v1.1.9) for the details.

## How this was tested

The tests use the real kill aura's "has a target" state and feed the real module the same attack packets the kill aura sends. They cover the bot standing still while hits keep coming, carrying on when none do, and right-clicking something not counting as a hit. Errands were run against a pretend server, with fights starting with the ender chest, a shulker box or a crafting table open, and halfway through moving a tool. Nothing got lost or thrown away. All 427 tests pass, including with the CPU overloaded. The new code was broken on purpose in 22 ways to check that the tests notice, and they catch all of them. One part isn't covered by a test: an errand that fails after a fight not counting as a failure.

**It hasn't been run on a live server yet**, and the tests have no real fights. If it does something odd in a fight, the lines from the log around that time will show what happened, so please send those.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.10/BlockMiner-1.1.10.jar
restart
```
