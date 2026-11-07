## What's fixed

**It stood on top of an ender chest all night, logging `Pathing complete` over and over.** An ender chest is a little under a block high. Standing on one, ZenithProxy's pathfinder counts the bot's feet as being in the block above it, but Block Miner went by the block below. So it kept sending the bot to the spot it was already standing on, each walk was over as soon as it started, and that counted as getting there. Soul sand, which is all over the Nether, mud and slabs could do the same.

It now goes by where the pathfinder says its feet are. On top of that, there are two new backstops:

- **Arriving 3 times in a row without moving** counts as a path it can't find, so it moves on.
- **3 minutes without going anywhere or picking anything up** makes it walk away, whatever the reason. That's going less than 8 blocks from where it was, while it's meant to be mining, so errands and fights don't count. It's logged if it happens.

**It mined tuff from the bottom up, then spent ages getting to what was left in the roof.** It started a section at the first layer that was mostly what you asked for, so anything above that was left until the end, by which time the floor under it was gone. A smaller pocket that wasn't enough of a section got mined from wherever the bot happened to be, which was often the bottom. Now:

- **A section starts at the top of everything joined to that layer**, so a heap or a dome of it on top is included.
- **A clump taller than it can reach from the bottom of it**, like a pocket of tuff, gets mined from the top down. Ore veins shorter than that are mined as before.

In a test pocket of tuff, it used to stay near the bottom, with the top 9 to 25 blocks above it. Now it stays about a block under the highest tuff left, all the way down, whether it starts below the pocket or above it, and never has to build.

**Building took a long time over each block.** The building itself was quick. But building switched off after every walk, so each block it had to place first needed a search to fail, which takes up to 2 seconds, and then a wait before trying again with blocks. Now, after a walk that had to build, it keeps building for the next one if that's within 12 blocks. In a test, getting to 40 blocks left in a roof went from 85 seconds of path search to 3.5.

## Worth knowing

- **Deepslate now starts a bit higher.** It's mixed in with stone up to Y 8, and that's all joined, so it starts from a level that can reach up to Y 8.
- **It still only builds when a path has failed**, and then for what's close by. Anything further than 12 blocks away and it stops building, so it doesn't climb pillars out in the open.
- **Why it was by the ender chest in the first place** isn't known for sure. In the tests, ore at the very top of its reach above a chest it had put down was enough to make it go and stand on the chest.

## How this was tested

The tests use ZenithProxy's real path search, goals and idea of where the bot's feet are, over made-up worlds. Standing on an ender chest, soul sand and mud was tested against the real thing. A tuff pocket was mined from start to finish with the real path search, and so was the roof left over from mining it the old way. Across six test worlds of coal, it leaves 22 ores showing to air against 27 for 1.1.8.

All 416 tests pass, including with the CPU overloaded. The new code was broken on purpose in 31 ways to check that the tests notice, and they catch all of them.

**None of this has been run on a live server yet**, and the tests have no mobs, lag or real walking. If it stops or does something odd, the Discord message or the lines from the log around that time will show what happened, so please send those.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.9/BlockMiner-1.1.9.jar
restart
```
