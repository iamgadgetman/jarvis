# Mining System

Jarvis mines the way a player mines. He pathfinds, breaks blocks with vanilla
timing, arm swings and crack animations, and digs through obstructions rather
than warping past them. There is no teleport-cheating and no instant block
deletion.

---

## Hunting ores

```
/jarvis mine                 whatever is most valuable nearby
/jarvis mine diamond         a specific ore
/jarvis mine ancient_debris
```

or in chat: *"jarvis, go mine me some diamonds"*.

He understands diamond, emerald, gold, iron, copper, redstone, lapis, quartz,
coal and netherite/ancient debris. Ore scanning happens off the main thread on
chunk snapshots, so a search costs no TPS. He digs a real 1×2 tunnel to reach
buried ore, staircasing down and back up, follows the vein when he finds it,
collects the drops, and filters the junk (cobblestone, dirt, gravel) out of
what he carries back.

## Branch mines

```
/jarvis mine here
```

A complete mine, not a hole: a staircase down to diamond level, a torch-lit
main gallery, branch tunnels on a grid, lava pockets sealed with cobblestone.
Every ore the tunnels expose is taken. The mine stays lit and walkable for you
afterwards, which is the point.

## Wide bore

At **Peerless** rank and above:

```
/jarvis tunnel north 64
```

A 3×3 passage you can ride through. See [Progression](Progression).

---

## Carrying the spoils

```
/jarvis chest        register the chest you are looking at
/jarvis deposit      carry it over and unload
/jarvis loot         open his inventory
```

With a chest registered he delivers automatically when his bags fill mid-job
and goes back to work.

---

## Tuning

Under `mining:` in the config, with the reasoning in the comments. The keys
that 0.17.0 actually reads:

- **`search-radius`** — how far he looks for ore
- **`timed-breaking`** and **`break-speed-modifier`** — vanilla-speed breaking
  with animations, and how much faster or slower than that
- **`auto-deposit`** — deliver to the registered chest when his bags fill
- **`navigator-range`** — the longest path he will attempt (Paper)
- **`branch.*`** — branch-mine depth, gallery and branch length, spacing,
  torches
- **`shaft.*`** — `/jarvis dig` depth, ladders, torches
- **`tunnel.*`** — `/jarvis tunnel` default and maximum length
- **`debug: true`** — logs every decision, for when he does something you did
  not expect

**Known issue in 0.17.0:** the file also carries `place-torches`,
`torch-spacing`, `pickup-radius`, `enable-vein-mining`, auto-return and the
`safety.*` hazard keys, and the bell menu's **Settings** page (and the torch
buttons on its **Mining** page) write several of them, but nothing reads them
yet. Changing them is saved, server-wide rather than per player, and has no
effect. Torches in branch mines and shafts are set under `branch.*` and
`shaft.*`.
