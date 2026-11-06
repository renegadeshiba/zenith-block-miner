## What's fixed

**It spent hours trying to climb a vine.** ZenithProxy's pathfinder treats vines like ladders, and climbing is far cheaper than digging, so it picks them whenever it can. When a climb didn't work, it just planned the same climb again, and Block Miner had no limit on how long a walk could take.

Now it keeps off regular, weeping, twisting and cave vines for all its walking, including trips to the ender chest and collecting drops. It digs around them, and only goes on one if there's no other way at all.

## What's new

**A walk that isn't getting anywhere gets given up on.** If it reaches nowhere new for a minute, that now counts as a path it couldn't find, so it moves on to other blocks or walks away as it does for any failed path. Going back and forth between the same few spots doesn't count as getting somewhere. This covers anything else that traps it the same way, not just vines. A long walk or tunnel is fine as long as it keeps reaching new places.

## Worth knowing

- **Ladders work as before.** It still uses them.
- **Your own `.pathfinder` commands aren't affected.** This lives in the plugin, not in ZenithProxy's settings, so those commands can still climb vines.
- **Why the climb failed on your server isn't known.** The tests show the path search choosing vines, but not what stopped the climb. The one-minute limit is there for that reason, as it doesn't depend on the cause.

## How this was tested

The tests use ZenithProxy's real path search. Before the fix it climbed regular, weeping and twisting vines in a test world. The pathfinder doesn't count cave vines as climbable, but they're avoided too, as all vines were asked for. Now it digs around all of them, and still climbs one if that's the only way out. The walking limit ran against the real module with a pretend bot.

The new code was broken on purpose in 22 ways to check that the tests notice, and they caught all of them.

**It still hasn't been run on a live server**, and the tests have no drops, mobs or lag. If it stops or does something odd, the Discord message or the lines from the log around that time will show what happened, so please send those.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.7/BlockMiner-1.1.7.jar
restart
```
