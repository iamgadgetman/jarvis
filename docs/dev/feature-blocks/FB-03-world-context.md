# FB-03 — Rich world context in every prompt

**Lane B · Size S · Branch `fb/03-world-context` · Idea from:** AI_NPC (time, weather,
biome, your health, held item, who's nearby), ArchGPT (server "Knowledge" block treated
as canonical facts), MCChatGPT (context levels 0–3 with token costs shown).

## Goal

Every AI request carries a compact, structured snapshot of the player's situation and
Jarvis's own state, plus an admin-written "server facts" block. Jarvis can then answer
"what should I do?" sensibly, warn about nightfall or a creeper behind you, and stop
inventing server rules.

## Audit first

1. `ai/AIConnector.java`: find the system prompt builder (around the action list) and
   whatever context it already appends. `memory/SituationSnapshot.java` already captures
   dimension, Y, biome, underground flag and time — reuse it.
2. `core/platform/World.java`, `Owner.java`, `Entity.java`: what's readable today
   (weather? light level? nearby entities? held item? health/food?). List gaps.
3. Check `config.yml` for any prompt/persona section.

## Implementation

- New `ai/WorldContext.java` (platform-free record + builder): player health/food,
  held item, armor summary, Y/dimension/biome, time of day as words ("dusk"),
  weather, light level at feet, hostile mobs within 16 blocks (count by type, nearest
  distance), players within 32 blocks (names), Jarvis's own: current task, health, tool
  in hand, inventory highlights (ores, food, torches). Rendered as ~12 short lines, not
  JSON prose, to keep tokens low. Build it **on the server thread** right before the async
  AI call (the pipeline already has that seam).
- New `ai/ServerKnowledge.java`: loads `plugins/Jarvis/knowledge.yml` (free-form
  `facts:` list + `locations:` name → coords/description). Appended to the system prompt
  under "Server facts (treat as true)". Hot-reload on `/jarvis reload`.
- Add missing reads to `core/platform/` (e.g. `World.weather()`, `World.lightLevel(pos)`,
  `World.nearbyHostiles(pos, radius)`, `Owner.health()/food()/heldItem()`), implement in
  Paper first; stub-return sensible defaults in Fabric/NeoForge so they compile.
- Config:
  ```yaml
  ai:
    context:
      level: 2            # 0 none · 1 player only · 2 player+world · 3 +nearby entities
      hostile-radius: 16
      player-radius: 32
      knowledge-file: knowledge.yml
  ```
  Log the context token estimate when `ai.log-usage` is on.

## Files

`ai/WorldContext.java` (new), `ai/ServerKnowledge.java` (new), `ai/AIConnector.java`,
`core/platform/World.java`, `core/platform/Owner.java`, Paper platform impls,
mod impl stubs, `config.yml`, new `jarvis-paper/src/main/resources/knowledge.yml`
(example with comments), `CHANGELOG.md`, `docs/configuration.md`.

## Unit tests

`ai/WorldContextTest.java` using `FakeWorld`/`FakeOwner`:
- Level 0 renders nothing; level 1 has health/held item but no biome; level 2 adds
  weather/time/biome; level 3 adds "2 zombies, nearest 6m".
- Time 13000 renders "night"; 0 renders "dawn".
- Rendered text stays under 600 characters at level 3 with 5 mob types.
`ai/ServerKnowledgeTest.java`: parses facts and locations; missing file → empty block,
no exception.
Extend `AiSettingsTest` for the new keys' defaults.

## In-game test (owner)

1. Write `knowledge.yml`: `facts: ["Spawn is at 0,64,0", "PvP is off"]`,
   `locations: {market: {x: 120, y: 70, z: -40, note: "player shops"}}`.
2. Say `where is the market?` → Jarvis quotes the coordinates from the file.
3. At night, holding nothing, 3 hearts: say `what should I do?` → reply mentions night,
   low health, and no weapon.
4. Stand near 2+ zombies: say `anything around?` → reply counts them.
5. `level: 0`, reload, repeat step 3 → generic answer with no situational detail.
6. With `ai.log-usage: true`, confirm the context adds well under ~300 tokens.

## As built (deviations)

- **No `core/platform/` changes.** Every read the spec lists already existed (weather,
  sky/block light, `nearby(…, HOSTILE)`, health/food/held item), so the Fabric and NeoForge
  mods are untouched.
- **No armor summary.** `Owner` has no armor-slot read; adding one means a platform change in
  three adapters for one line of context. Left out.
- Level 2 also carries Jarvis's line (task, health, tool, cargo highlights); level 3 adds
  hostiles and players. The world line includes the world name so `knowledge.yml` locations
  with `world:` can be matched.
- Defaults of the new keys are tested in `WorldContextTest.settingsDefaults`, not
  `AiSettingsTest`: the keys live in `WorldContext.Settings`, not `AiSettings`.
- `buildContext` catches a world read that throws and sends no context rather than no reply.
- `ai.log-usage` logs both the context and the server-facts size per request.
- The example `knowledge.yml` is written on first start (Paper only), all commented out.

## Results

Owner, GadgetCraft (Purpur 26.3), qwen2.5:7b on Ollama.

| Step | Result |
|---|---|
| 1 knowledge.yml | Written with the spec's facts and market; `/jarvis reload` logged "2 facts, 1 locations". |
| 2 where is the market | **First try FAIL** (build `5c72dd5d`): the model picked `teleport` and moved the owner to spawn. Fixed in `7eea931` (move actions need a move word) and `d4c6308` (teleport/warp ask Confirm/Cancel). **Retest PASS** (build `e6aab3e0`). |
| 3 night, empty hand, 3 hearts | Run from the headless client (callmegadget). **First try FAIL** (build `e6aab3e0`): "Gather some wood for a simple hut… a nice, clear night", nothing on health. `6c41a00` adds a WARNINGS line in words. **Retest PARTIAL** (build `c7f42df6`): "Tend to your health first; that low light isn't good. Build a shelter or find some torches." Health and darkness yes; no weapon not mentioned. |
| 4 two zombies | **PARTIAL.** "Low light and zombies nearby? Gather torches or build a quick camp before those zombies get curious." Context said "2 zombies, nearest 4m"; he named them but didn't count. |
| 5 level 0 | **PASS.** Context 0 chars; "Consider starting a farm… some basic shelter is in order." No situational detail. |
| 6 token cost | **PASS.** Level 3 context 68–99 tokens (269–396 chars), server facts 45 tokens (177 chars), per `ai.log-usage`. |

The partials are qwen2.5:7b taking the facts loosely, not missing context: the counts and the
no-weapon warning were in the prompt (`ai.log-usage` sizes match). A larger model should do better.
Live config is back to `level: 2`, `log-usage: false`.

**Owner, 2026-11-10:** accepted steps 3–4 as partial (model limit, context correct) and marked FB-03 `tested`.

## CHANGELOG line

`### Added` — **Jarvis knows where you are.** Prompts now carry your health, held item,
time, weather, biome and nearby hostiles, plus a `knowledge.yml` of server facts and
named locations he treats as true. `ai.context.level` sets how much.
