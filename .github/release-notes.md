## What's changed

**You now ask for the item you want, and the bot works out what to mine and with what tool.**

| You ask for | It mines | With |
|---|---|---|
| `diamond` | `diamond_ore`, `deepslate_diamond_ore` | a Fortune tool |
| `diamond_ore` | `diamond_ore` | a Silk Touch tool |
| `cobbled_deepslate` | `deepslate`, `cobbled_deepslate` | a tool without Silk Touch |
| `deepslate` | `deepslate` | a Silk Touch tool |
| `ancient_debris` | `ancient_debris` | any tool |

The [README](https://github.com/renegadeshiba/zenith-block-miner#choosing-what-to-mine) has the full list.

- **It gets the right tool.** The pathfinder uses whichever tool on the hotbar is fastest, so the bot now moves wrong tools off the hotbar and the right one onto it. If it isn't carrying the right one, it fetches it from a tool shulker box in the ender chest. If there isn't one there either, it stops and tells you why.
- **Worn tools get replaced with the right kind.** A worn Fortune pickaxe won't be replaced with a Silk Touch one when you're after diamonds.
- **It only keeps what you asked for.** Ask for `diamond`, and the cobbled deepslate from mining down to it stays on the ground.
- **It tells you what it will do.** `blockminer add` says what it will mine and with what, and tells you if an item needs a different tool from the ones you've already chosen.

## You need to do this

**Choose your items again**, because the list has been replaced and what the names mean has changed. For example, `diamond_ore` now means the ore block itself, which takes Silk Touch. For diamonds, use `diamond`.

```
blockminer add diamond
```

If the sign plugin is installed, run both `signplacer off` and `restock off` before mining. The restocker starts by itself whenever the bot is low on signs.

## How this was tested

The new tests run ZenithProxy's real tool choice over made-up hotbars, and check the rules for every item.

**The moving and fetching of tools hasn't been run on a live server yet.** Please watch it the first time it has to sort out the hotbar or fetch a tool.

## Install

```
plugins download https://github.com/renegadeshiba/zenith-block-miner/releases/download/v1.1.0/BlockMiner-1.1.0.jar
```
