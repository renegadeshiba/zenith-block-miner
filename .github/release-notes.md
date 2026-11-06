## What's fixed

**It could fail to get a new pickaxe.** There was no message from the bot to go on, so which of these you ran into isn't known. Nothing had ever tested the swap from start to finish, and running it against a pretend server turned up all of these:

- **The new pickaxe could end up off the hotbar.** With a full hotbar, which is how it normally is, putting the shulker box down took the old pickaxe's slot. The new one then landed in the main inventory, where the pathfinder can't use it, and when any tool would do, nothing moved it back. Now it goes on the hotbar straight away.
- **A slow ender chest looked like it had no tools.** The bot would turn itself off saying there was no fresh replacement. This is the same kind of problem as the shulker boxes it couldn't see before. Now it waits longer, and looks again before giving up.
- **A pickaxe breaking on the way failed the errand.** It still comes back with a new one now.
- **Walking away never stopped for a worn pickaxe.** That can be 100 blocks of digging, enough to break it and carry on bare-handed. Now it swaps the pickaxe first, then carries on the way it was going.
- **A slow ender chest could leave it carrying the tool shulker box** until its next errand, instead of putting it back.
- **With no tool at all, it could mine by hand for nothing.** If you asked for something where any tool will do, like `obsidian`, and it had no pickaxe on the hotbar, it just carried on without one. Now it fetches one, or tells you if there isn't one.

## What's new

**It stands still while the kill aura fights.** ZenithProxy's kill aura outranks mining, but it only acts when a hit is ready, about every half second. In between, the pathfinder would walk off, leaving what it was fighting out of reach, or try to swap back to its pickaxe.

Now, while the kill aura has something in reach, the bot stops mining and errands. It picks up where it left off 2 seconds after the last target, including walking away. If a fight is still going after a minute, it goes back to work alongside it and logs a warning, so something the kill aura can't finish off can't stop it for good.

**Plain piglins need a setting.** ZenithProxy counts them as neutral, and the kill aura skips neutral mobs by default. It only goes for piglin brutes and hoglins unless you turn this on:

```
killAura on
killAura targetNeutralMobs on
killAura targetNeutralMobs onlyAggressive on
```

`onlyAggressive` leaves alone any that aren't after the bot, as hitting one angers the rest. Keep a sword on the bot, or the kill aura hits with whatever's in hand.

## Worth knowing

- **Crossbows:** the kill aura reaches about 3 blocks and doesn't chase anything, so a piglin shooting from further away can keep hitting the bot.
- **It doesn't run away.** It stands and fights, however many there are.
- **Errands can anger piglins too.** As far as I know from vanilla, opening an ender chest or a shulker box near them does the same as mining their gold.
- **The right pickaxe for nether gold:** `gold_nugget` takes a Fortune pickaxe and `nether_gold_ore` takes a Silk Touch one. If the tool shulker box doesn't have that kind, the bot turns off and says so.
- **It can now turn off for a missing tool when any tool will do**, if the block gives nothing to a bare hand. Before, it would have mined it by hand and got nothing.

## How this was tested

The tool swap ran from start to finish using the real errand and ZenithProxy's real click handling, against a pretend server following vanilla's rules for an inventory, an ender chest and a placed shulker box. That included slow connections, containers that take several seconds to fill, and fights breaking out between steps. Stopping for a fight was tested against the real kill aura's "has a target" state and the real module, but not a real fight.

The new code was broken on purpose in 36 ways to check that the tests notice, and they catch all of them.

**None of this has been run on a live server yet**, and the tests have no mobs, real walking or real combat. If it stops or does something odd, the Discord message or the lines from the log around that time will show what happened, so please send those.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.8/BlockMiner-1.1.8.jar
restart
```
