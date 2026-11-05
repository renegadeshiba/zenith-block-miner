## What's changed

**It mines what's above it now, and works from the top down.** It only ever broke the blocks at its feet and head, and only ever looked down once a level ran out, so it left a flat room 2 blocks high with everything above it untouched.

For a block that's everywhere, such as deepslate, stone or netherrack, it now mines one section at a time, a section being everything within 24 blocks of where it starts:

1. It goes to the top of the section, which is the highest layer that's mostly what you asked for, from wherever it is.
2. At each spot it mines everything it can reach straight above it without moving, which clears a room 7 blocks high. What it mines falls on it, so it collects it.
3. Once that level is used up, it digs down to the next one, 7 blocks lower.
4. Once the section is used up all the way down, it digs back up to the top of the next section along, and starts again. It carries on in a straight line, turning if the next one is already mined out.

**The lowest it goes is now Y -58**, or Y 6 in the Nether, rather than Y -50. That's the only limit on height. There's no upper limit for anything, so ores are still mined at whatever height they're found, nearest first, and aren't kept to a section.

**It won't go back up for an ender chest on a level it's finished with**, if it has one to place. The floor it stood on to reach it is gone.

## Worth knowing

- **Lava is common that far down.** The bot won't break anything touching lava, but it's the most dangerous place it goes.
- **It gets through more ender chests**, as it leaves one behind on each level it stores things from. That's up to about 9 for each section of deepslate, so give it a good supply.
- **Each section turns into a big open pit**, up to about 60 blocks deep in deepslate. It's dark, so mobs can spawn in it, and the bot doesn't fight them. Use ZenithProxy's own modules for that.
- **A few blocks get left behind**, about 4 to 5% of a section in the tests. It leaves anything that would fall on it, anything touching lava or water, containers, and blocks with a cave under them that it can't stand on.
- **Stray bits above the top of a section are skipped.** A layer only counts if it's mostly what you asked for and more than a few odd blocks. If you ask for stone, it won't dig through a layer of mostly soil for the bits of stone poking through it.

## How this was tested

The tests use ZenithProxy's real path search, reach check and chunk code over made-up worlds. A pretend bot followed the real paths through 11 whole sections of worlds with caves, huge caverns, lava, tuff, gravel, ores and hills. It never got stuck, never broke bedrock and never went below Y -58. It failed to find a path about once in 30,000 steps, and never twice in a row. About 96% of what it broke was what it was after.

The new code was also broken on purpose in 53 ways to check that the tests notice. They caught 51, and the other 2 don't change what the bot does.

**It hasn't been run on a live server yet.** The tests have no drops, mobs or lag. Please watch the first level or two: it should mine up to 7 blocks high at each spot and pick up what falls, and then drop down a level. If you see stacks of what it's mining left on the floor, check the log for `Dropped ... to free up an inventory slot` and send that if it's there.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.5/BlockMiner-1.1.5.jar
restart
```
