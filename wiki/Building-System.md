# Building System

Describe a structure and Jarvis designs it, then builds it block by block.
He can also paste schematics from a library, on every platform.

---

## Freeform building

```
/jarvis build a small stone cottage with a wooden roof
/jarvis build a 5x5 cobblestone tower
```

or in chat, or **Building → Custom build** in the bell menu, which asks for
the description and runs the same thing.

The model is not asked for four hundred coordinates. There are two planners,
chosen by `build.planner`:

- **`script`** (the shipped default) — the model writes a short JavaScript
  program that calls `fill()` and `setBlock()`, run in a sandbox with a block
  budget and a time limit. It needs a cloud model and GraalJS. Paper fetches
  GraalJS itself: `plugin.yml` lists it as a library, so the first start
  downloads about 60 MB from Maven into the server's `libraries/` folder. If
  GraalJS cannot be loaded, Paper falls back to the json planner rather than
  failing.
- **`json`** — the model returns a short list of **shapes** — fill, walls,
  hollow, clear, set, door, bed, roof — which the core expands. A wall is
  therefore whole by construction, a door has both halves, a bed has its
  head, and a pitched roof is stairs stepping in to a ridge with its gables
  closed. Fabric and NeoForge carry no GraalJS, so the mods always use this
  one.

Either way the model spends its effort on the design instead of the
arithmetic.

**Builds come furnished.** A dwelling gets a bed, a chest, a crafting table,
light and something on the walls; a larger building gets rooms furnished for
their purpose. Both planners' prompts require it rather than leave it to taste,
because the first house built without that rule arrived with no door and a
wall missing.

Mistakes are repaired rather than reported:

- Block ids are checked against the server's own registry.
- A door or window pane placed a block off the wall's line is moved into it.
- Glass panes and fences are joined to their neighbours; bed halves are made
  to agree.
- Anything that is not a block — a painting, an item frame — is left out and
  named in one warning line, rather than becoming a stray dirt block.

Underground requests carve their own space first.

```
/jarvis build undo       revert the last build
/jarvis build cancel     stop the one in progress
/jarvis build wall|floor|pillar|cube [size]    simple shapes, no AI
```

Build planning is the **heavy** AI tier: it prefers your cloud provider and
gets a long timeout, because a plan runs to thousands of tokens. See
[AI Providers](AI-Providers).

---

## The schematic library

Drop `.schem` or `.schematic` files into `plugins/Jarvis/schematics/` on
Paper, or `config/jarvis/schematics/` on Fabric and NeoForge, and ask for one
by name — matching is fuzzy, so exact filenames are not needed. Jarvis reads
and pastes these files himself, so pasting works on every platform.

```
/jarvis schematic list
/jarvis schematic paste <name>         also: /jarvis paste <name>
/jarvis schematic scan                 rescan the folder
/jarvis schematic folder               where the folder is
/jarvis schematic save <name>          save your WorldEdit clipboard
/jarvis schematic rotate <name> 90
```

`save` and `rotate`, and pasting JSON-format schematics, need
WorldEdit, so those are Paper only.

Litematica files convert in place:

```
/jarvis schematic litematic            list .litematic files
/jarvis schematic convert <name>
/jarvis schematic convertall
```

"jarvis, build me a house" picks the best match from the library when one
fits, which is also the path a local-only server uses for large builds.

---

## He learns from what worked

Builds that went well are remembered and retrieved when you ask for something
similar, matched on the request *and* on where you were standing — "build me a
house" underground at y 12 is not the same request as on a plains surface.
When something goes wrong he says why, in a sentence, rather than failing
silently. See **Experience memory** in [AI Providers](AI-Providers).

---

## Upgrading

`build.fallback-material` is retired and ignored if it is still in your
config. A block the server does not recognise is now skipped and named in the
log instead of becoming dirt.
