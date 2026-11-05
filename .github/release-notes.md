## What's fixed

**The bot turned itself off with `No room to place shulker_box beside the bot`.** In a tunnel one block wide, the only open spot beside the bot is the one behind it. The ender chest went there, which left nowhere for the shulker box, so the errand failed three times and the bot stopped.

Now it mines out a spot if there's none ready: the block next to it, and the one above that only if it's in the way, so that a shulker box has room to open. The block underneath is left alone. It uses a spot that's already open if there is one, and otherwise goes for whichever needs the least mined.

It won't mine bedrock, anything with something in it, such as a chest, a shulker box, the ender chest or a sign, a crafting table, gravel or sand, or anything touching lava or water.

**Placing could fail if the bot was still partly in the spot.** A walk ends as soon as the bot's feet cross into a block, so it's often still overlapping the block behind it. Placing there failed after a 10 second wait. It now skips any spot it's standing partly in.

## Worth knowing

- It can still turn itself off if there's nowhere at all it can mine out and it can't climb away, which takes something like bedrock on every side.
- If placing fails for some other reason, the console log now says why, even though the notification still says `No room`. Please send that if you see it.

## How this was tested

The tests run over ZenithProxy's real chunks and block rules, and its own checks confirm that the bot can reach what it mines, can place in the spot it mined out, and that a dropped block left in that spot doesn't get in the way. The new code was also broken on purpose in 16 ways to check that the tests notice.

**It hasn't been run on a live server yet.** In the tests, the mining and placing themselves are pretend. Please watch the first storing trip in a narrow tunnel: the ender chest and shulker box should each get a spot, mining one out if they need to.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.4/BlockMiner-1.1.4.jar
restart
```
