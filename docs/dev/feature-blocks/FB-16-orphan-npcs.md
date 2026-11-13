# FB-16 — Remove leftover butler NPCs at startup

**Lane G (`jarvis-paper` `npc/provider/`) · Size S · Branch `fb/16-orphan-npcs` (stacked on FB-15) ·
Depends on:** — · **Source:** GadgetCraft 2026-11-11: `/npc list` showed three Jarvis NPCs (ids 17,
19, 20) that no player owned, while `last-created-npc-id` was 33.

## Problem

Citizens saves every NPC to `plugins/Citizens/saves.yml` and loads them again on start.
Jarvis keeps its owner → NPC map in memory only, and destroys its NPCs in `onDisable`
(`ButlerService.dismissAll`). When that doesn't run (a crash, a hard kill, a stop that times
out), the saved butlers come back on the next start with no owner, and Jarvis spawns a fresh
one beside them. They stand in the world for good and confuse `@e[name=Jarvis]`.

## Change

- `CitizensNPCProvider.spawn` tags each new NPC with persistent metadata `jarvis-owner` = the
  owner's UUID.
- `CitizensNPCProvider.sweepOrphans(name)` destroys every tagged NPC that isn't in the current
  map, and logs the count. Untagged NPCs named "Jarvis" (made before FB-16, or by an admin) are
  not touched; their ids are logged with a `/npc remove <id>` hint.
- The sweep runs on `CitizensEnableEvent` (Citizens has loaded `saves.yml`) and once a tick after
  Jarvis enables (covers a plugin reload, when Citizens is already loaded).

Paper only: the Fabric/NeoForge butlers are not Citizens NPCs and aren't saved. No config keys,
no `core/platform/` changes.

## Tests

No unit test: this needs a real Citizens registry, and unit tests never start a server.
`mvn -q -o package -pl jarvis-paper -am` compiles it against citizensapi 2.0.43.

## In-game test (GadgetCraft, callmegadget)

| # | Do | Expect |
|---|---|---|
| 1 | `/jarvis summon`, then `/npc list` | One Jarvis. |
| 2 | Leave the butler out and hard-stop the server (AMP kill), start it | Log: "Removed 1 leftover Jarvis NPC(s) from an earlier run." `/npc list` is empty until summoned. |
| 3 | Normal restart with the butler out | No leftovers; `/npc list` empty after start. |

Step 2 needs a hard kill; skip it if a kill isn't wanted on the live server. The sweep can also
be seen by checking `saves.yml` for `jarvis-owner` after step 1 (Citizens autosaves).

## Results

Run 2026-11-11 on GadgetCraft. Before the block, the three untagged orphans (17, 19, 20) were
removed by hand with `/npc remove` (backup `saves.yml.bak-20261111-orphans`).

| # | Result | Notes |
|---|---|---|
| 1 | PASS | `/jarvis summon` → `/npc list` shows one Jarvis (id 35); after `/citizens save`, `saves.yml` has `jarvis-owner: d0b28b71-…` (callmegadget). |
| 2 | PASS | Simulated a crash without a kill: AMP Stop, put back the `saves.yml` saved with the butler out (`saves.yml.fb16-crashsim`), AMP Start. Log: "Removed 1 leftover Jarvis NPC(s) from an earlier run."; `/npc list` empty after joining. |
| 3 | PASS | Normal restarts (03:40, butler dismissed) logged no leftovers and no "named Jarvis" warning. Not re-run with the butler out. |

**Owner, 2026-11-12:** accepted the headless results; marked `tested`.
