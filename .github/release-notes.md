## What's fixed

**The proxy could run out of memory while mining.** You'd see `OutOfMemoryError: Java heap space` from `MineProcess` in the pathfinder, repeating every few seconds, with the bot not mining. It happened when mining a block that's everywhere, such as deepslate, stone or netherrack, with the bot more than 10 blocks above or below any of it. ZenithProxy's search then collects every one in the loaded chunks, which can be millions. Since 1.1.0, asking for `cobbled_deepslate` has meant mining `deepslate`, which made this easy to hit.

The bot now checks first. While that search would be too big, it walks to the nearest blocks itself, mining them on the way, and the status shows `Walking to the nearest ...`. Once it's among them, it hands back to ZenithProxy's miner.

## Worth knowing

**The bot digs from where it stands.** If it's above the layer it's after, it digs its way down, so don't start it anywhere it shouldn't dig, such as a base or stash.

**It goes looking for blocks by itself.** The README used to say it doesn't, which was wrong. If none of your blocks are loaded, ZenithProxy's miner digs down to Y -59 and tunnels away from where it started. This isn't new, and there's no setting to stop it.

## How this was tested

On a 300 MB heap, ZenithProxy's default for the `java` release channel, with 441 chunks loaded, ZenithProxy's real search runs out of memory at the same line as the reported error, and the new code doesn't. The new count of what that search would collect matched the real search everywhere it was tested, and ZenithProxy's real path search found a way from the surface down to the deepslate in a test world.

**It hasn't been run on a live server yet.** In the tests the bot never actually moves, so please watch the walk down and the hand-over the first time.

A small risk remains. The check runs every half second and ZenithProxy searches every 2.5 seconds, so if the bot leaves the layer between checks, one oversized search could still start before it switches.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.2/BlockMiner-1.1.2.jar
```
