# Jarvis Changelog

## v0.8.5 (2026-09-01) — three fixes from the first real build

v0.8.4 made the AI builder reachable for the first time. The first live build on
a real server promptly found three things, all in the path that had never run.

### Fixed

- **A non-block material killed the build mid-placement.** `parseBuildPlan`
  validated that a material *name* resolved, but not that the material can be
  placed. A model asking for `minecraft:brick` gets `Material.BRICK` — the clay
  brick **item**; the block is `BRICKS` — and `setType()` then threw
  *"Provided material must be a block"*, aborting the build part-way:

  ```
  java.lang.IllegalArgumentException: Provided material must be a block
    at BuildingAssistant.java:315   block.setType(placement.material)
  ```

  Non-block materials are now substituted with the configured fallback. The
  check runs in `executeBuild` rather than in the parse, deliberately:
  `Material#isBlock` resolves through Paper's registry, and the parse runs off
  the main thread.

  The old fallback only caught *unknown* names, which is why `minecraft:planks`
  and `minecraft:wood` — not real material names — degraded to dirt harmlessly
  while `brick` slipped through and crashed.

- **A build that crashed could still be recorded as a success.** The placement
  loop is now wrapped, and a build that throws goes to a new `failBuild` path
  that records the plan as `FAILED` instead of reaching `completeBuild`. A plan
  that crashes the placer is the clearest possible example of a plan that does
  not work; recording it as a success would teach the memory to produce more
  like it. Whatever was placed before the failure stays undoable.

- **Undo demoted the newest success, not the build actually reverted.**
  `markRecentBuildUndone` searched by player and recency, so undoing an older
  build out of order demoted the wrong row. Undo entries now carry the
  experience they produced — by reference, so the id filled in by the async
  insert is visible — and `markUndone(experience)` targets it directly. The
  by-player search remains as a fallback for entries with no id yet.

### Field notes

Verified on a live 26.2 server: situation capture works
(`{"dimension":"NORMAL","biome":"desert","y":67,"underground":false}`),
embeddings store 768 dimensions, hand-built shapes are correctly excluded from
memory, and undo demotes only AI builds.

Local-model build quality is another matter: `qwen2.5:7b` returned 13 blocks for
"a small oak cottage" and 4 for "a long wall". That is what
`memory.min-successes-for-reduced-mode-builds` (default 20) exists to gate —
lowering it trades build quality for getting the memory started.

## v0.8.4 (2026-08-31) — `/jarvis build` actually builds

Field report: *"I tried `/jarvis build oak cottage` and it built the same fan
each time, no matter what I asked for, even when I asked for a panic shelter."*

Four separate defects, and the AI builder was reachable from almost nowhere.

### Fixed

- **`/jarvis build` read only the first word.** It took `args[1]` and discarded
  the rest, so `/jarvis build a panic shelter` searched the library for a
  schematic called **`"a"`** — and `/jarvis build oak cottage` searched for
  `"oak"`.

- **Schematic matching returned the first arbitrary hit, not the best one.**
  `findSchematic` walked the map and returned the first entry whose name merely
  *contained* the query. A one-character query is a substring of nearly every
  name, so it returned whatever `HashMap` iteration yielded first — the same
  schematic every time, whatever was asked for. That is the fan.

  Matching is now scored: exact name wins outright, otherwise the score is the
  fraction of the *requested* words the name accounts for, with ties broken
  toward the shorter, more specific name. Keying on the request rather than the
  name stops a long schematic name matching everything.

- **`/jarvis build` never reached the AI builder.** It was a schematic-paste
  command; `BuildingAssistant.startBuild` was called from exactly one place,
  `ChatListener`, and only when the library returned no match at all. With a
  library of any size that branch effectively never fired, which made experience
  memory unreachable in practice. `/jarvis build <description>` now uses the
  library when it genuinely matches (score ≥ 50) and hands off to the AI builder
  when it does not.

- **`/jarvis build undo` did not exist**, even though a finished build prints
  "Use /jarvis build undo to revert". `undoLastBuild` and `buildSimpleStructure`
  had **no callers at all**. Now wired: `/jarvis build undo`, `/jarvis build
  cancel`, and `/jarvis build wall|floor|pillar|cube [size]`. Undo being
  unreachable also meant the memory's undo signal could never fire.

`/jarvis paste <name>` stays literal — name in, schematic out, no AI — so exact
schematic use is unchanged, as is `/jarvis paste <name> rotate <degrees>`.

### Added

- **`memory.embedding-endpoint`** — the embedding model no longer has to live on
  the same host as the chat model. Blank keeps the old behaviour of following
  `ai.ollama.endpoint`. A 274 MB embedder need not be pulled onto the box
  running a large chat model.

### Verified against a real library

Scored against the 68 schematics on a live server: `castle`, `mansion`,
`redstone door` and `fan` still resolve exactly as before; `a panic shelter` now
finds the `panic shelter` schematic it was always meant to (score 90, previously
unreachable); and `oak cottage` and `somewhere to store my loot` score 0 and go
to the AI builder.

## v0.8.3 (2026-08-31) — the Lamplighter merge

Merges a line of work that was built outside this repository on 2026-08-29 and
deployed to a live server as "0.8.2", but never pushed here. It had forked from
**v0.7.1**, so it predated the whole provider/cost line, and the two histories
had to be reconciled rather than one replacing the other.

Version numbering: that line used 0.8.0, 0.8.1 and 0.8.2 locally. None were ever
published, and 0.8.0 here already means Experience Memory, so the merged result
is 0.8.3. Its work is described below under the feature it added, not under the
numbers it used.

### Added — the Lamplighter

`/jarvis light [radius] [type] [spacing]`, and *"jarvis, light this place up"*
from chat.

- Grid-based area lighting built on the spawn rule: hostile mobs spawn at block
  light 0, so the placer targets that rather than guessing.
- Types: `torch` (default), `end_rod`, `lantern`, per command or via config.
- Ground placement by default; `lighting.placement: wall` puts torches on walls.
- Skips spots already bright enough (`lighting.skip-light-level`).
- **Underwater**: grid points in shallow water get sea lanterns, configurable
  via `lighting.underwater` (`sea_lantern` default, or `skip`). Placement works
  from the surface, and the placer hard-guards against setting a light into a
  cell that is not actually air or water.

### Added — he swims

Citizens' swim behaviour is enabled on the NPC, plus a lifeguard monitor that
checks once a second whether any Jarvis has his head underwater.

### Fixed — field and code-review fixes from that line

- **Item stacks no longer vanish** when picking up a stack larger than his bags
  could hold.
- **Full-chest infinite loop** when the deposit chest filled mid-job.
- **`/jarvis stop` actually stops a dig** — the block-breaker kept chewing.
- **Finished tasks unregister themselves**; a completed task used to linger.
- **Chat-parsing thread safety** — natural-language handling read world state
  off the main thread.
- **Summon after a failed spawn** no longer leaks a dead NPC registry entry.
- The sky-stare after a summon, follow-mode wedging with no stall detection,
  1-block stair squeeze in ore tunnels, the fishing wedge at a water's edge,
  death-recovery stalls on the junk filter, guard-post confusion, and the "kit
  tool" filter claiming the player's own tools.

### Not merged

That line's provider configuration was **left behind deliberately**. It still
had `claude-sonnet-4-20250514` as the Claude default with `claude` first in
`provider-priority` — the exact retired model ID that v0.7.2 was cut to remove,
and the opposite of the ollama-first posture restored in v0.7.4. Anything
running that build has been failing its first AI call on every request. The
merged tree keeps `claude-haiku-4-5` and `ollama` first.

Its hardcoded version constant was dropped too, in favour of the pom-derived
one from v0.8.0.

## v0.8.0.1 (2026-08-31)

Two defects in v0.8.0's experience memory, both found by running it against a
real Ollama box rather than a mock. Neither breaks anything outright — memory
records and retrieves either way — but the first made retrieval close to noise,
which is the whole point of the feature.

### Fixed

- **The relevance floor could never reject anything.** `nomic-embed-text` has a
  high similarity baseline: measured against *"build me a cozy oak cottage"*, a
  near-identical request scores 0.86, but completely unrelated text
  (*"kubernetes ingress controller config"*) still scores 0.34, and *"a nether
  portal out of obsidian"* scores 0.35. The floor was 0.25 applied to
  `0.7 x text + 0.3 x situation`, so for any same-situation match the minimum
  possible score was 0.54 — double the floor. Every stored build passed, and a
  strip-mine plan could be injected as a "similar example" for a cottage.

  The floor is now checked against the **request-similarity score alone, before
  the situation match is blended in**, because the situation term is a large
  constant offset that would otherwise rescue irrelevant matches. The two
  retrieval paths are on different scales and now have their own thresholds:
  `memory.min-text-relevance` (0.55, cosine) and `memory.min-keyword-relevance`
  (0.15, Jaccard fallback). `memory.min-relevance` is gone.

- **Cold-start stall on the first build after an idle period.** The embedding
  client never sent `keep_alive`, so Ollama unloaded the model on its default
  timer. Measured: **14.3 s cold, 16 ms warm** — the 20 s read timeout survived
  it only barely. It now sends `memory.embedding-keep-alive` (default `30m`;
  the model is 274 MB, so keeping it resident is cheap) and the default timeout
  is raised to 30 s for headroom.

### Setup

Unchanged from v0.8.0 — `ollama pull nomic-embed-text`. If you wrote a
`memory:` block by hand for v0.8.0, replace `min-relevance` with the two new
keys; the built-in defaults apply if you do nothing.

## v0.8.0 (2026-08-31) — Experience Memory

Jarvis now remembers how builds turned out and shows the AI the plans that
worked for similar requests. The labels are free: a build you let finish is a
success, one you cancel or undo inside the negative-signal window is not — no
rating prompt, no extra command.

### Added

- **`com.gadgetman.jarvis.memory`** — `BuildExperience`, `SituationSnapshot`,
  `EmbeddingClient`, `ExperienceMemory`, and a `build_experiences` table.
- **Two-stage retrieval.** Requests are matched on embedding similarity, then
  re-ranked on how much the world matched. "Build me a house" underground at
  y=12 and the same words on a plains surface retrieve different examples.
- **Embeddings are local-only.** `EmbeddingClient` talks to Ollama directly and
  deliberately does *not* route through `AIConnector`, so a call made on every
  build request can never fail over to a paid provider. If Ollama is down,
  retrieval degrades to keyword matching instead of failing. Requires
  `ollama pull nomic-embed-text`.
- **Reduced-mode unlock.** Ollama-only servers have freeform build planning
  disabled. Once `memory.min-successes-for-reduced-mode-builds` successes are
  stored (20 by default), the retrieved examples carry enough of the load and
  it turns on by itself.
- **`/jarvis debug`** now reports the stored success count, whether the
  reduced-mode unlock has fired, and embedding health.
- A `memory:` section in `config.yml`. As always, an existing
  `plugins/Jarvis/config.yml` is **not** overwritten — add the block by hand or
  the built-in defaults apply.

### Fixed

- **The plugin reported the wrong version.** `Jarvis.VERSION` was a hardcoded
  constant left at `0.7.0`, so every v0.7.x release announced itself as v0.7.0
  in the server log and in `/jarvis debug`. It now reads from plugin.yml, which
  already takes its value from the pom, so it cannot drift again.
- **`databases.yml` was never written to the data folder.** `onEnable` called
  only `saveDefaultConfig()`, which covers `config.yml` alone, so on a fresh
  install `DatabaseManager` loaded a file that did not exist, registered no data
  source, and every `getConnection()` threw *"No data source found with name:
  sqlite"*. Servers that have been running a while are unaffected — the file is
  already sitting in their data folder — so this bites new installs only, which
  is why it went unnoticed. It would have taken experience memory down with it.
  `onEnable` now calls `saveResource("databases.yml", false)`.

### Notes on what is *not* recorded

Two cases look like negative signals but are not, and recording them would
poison retrieval:

- A build aborted because the NPC despawned or the player logged out. That is
  infrastructure, not a bad plan, so it is dropped rather than stored.
- Undoing a hand-built shape. `/jarvis build wall|floor|pillar|cube` shares the
  undo stack with AI builds, so undo entries are tagged and only AI-planned
  builds can ever be recorded or demoted.

## v0.7.4 (2026-08-27)

### Changed

Restores the cost posture this project already decided on. v0.7.2 replaced a
retired Claude model ID but picked the most capable tier as the replacement,
which was the wrong default for a plugin that calls a model on every chat line.

- **Claude default is now `claude-haiku-4-5`** (was `claude-opus-5`).
  `config.yml` documents `claude-sonnet-5` and `claude-opus-5` for anyone who
  wants to trade cost for capability.
- **`ollama` moved to the front of `provider-priority`.** It is free and local,
  so a server running it pays nothing for routine parsing. If it is not
  installed the call fails fast and the provider goes on an exponential
  cooldown capped at 5 minutes, so servers without it are barely affected.

Both the shipped `config.yml` and the code defaults in `AIConnector` were
changed, so a fresh install and a config-less install agree.

### Known gap

The bigger cost work — a local fast path that resolves literal commands with no
model call, and `cache_control` on the system prompt — lives on a branch that is
not merged into `main` and is not in any release yet.

As always, an existing `plugins/Jarvis/config.yml` is **not** overwritten on
update; servers already running must edit their own file.

## v0.7.3 (2026-08-27)

### Fixed

Finishes what v0.7.2 started: every remaining provider default was checked
against its vendor's current model list. Two were broken, one was legacy.

- **Gemini was broken.** `gemini-1.5-flash` has been retired and no longer
  appears in Google's model list. Default is now `gemini-3.7-flash`, with
  `gemini-3.5-flash-lite` documented as the cheapest option.
- **Grok was broken.** `grok-4` is not a valid model ID — xAI ships versioned
  variants only. Default is now `grok-4.6`, which xAI recommends as its
  general-purpose model, with `grok-4.3` documented as cheaper.
- **OpenAI was legacy, not broken.** `gpt-4o-mini` still answers and is not on
  OpenAI's shutdown list, but it is no longer in the current lineup. Default is
  now `gpt-5.6-terra`, with `gpt-5.6-luna` (cost-optimised) and `gpt-5.6-sol`
  (flagship) documented.

Endpoints were verified unchanged and needed no edit: Chat Completions
(`/v1/chat/completions`) is not deprecated, and Gemini still serves
`v1beta/models/{model}:generateContent`. Only the model IDs were wrong.

As in v0.7.2, an existing `plugins/Jarvis/config.yml` is **not** overwritten on
update — servers already running must edit their own file.

## v0.7.2 (2026-08-27)

### Fixed

- **The Claude provider used a retired model ID.** Both the shipped
  `config.yml` and the code default in `AIConnector` asked for
  `claude-sonnet-4-20250514`, a dated snapshot that is no longer served. With
  `provider-priority` listing `claude` first, the default configuration failed
  its first call on every request and fell through to the next provider — or
  to none, if no other key was set. The default is now `claude-opus-5`, and
  `config.yml` documents `claude-sonnet-5` and `claude-haiku-4-5` as cheaper
  alternatives.

  The request itself was always well-formed (`x-api-key`,
  `anthropic-version: 2023-06-01`, `max_tokens`); only the model ID was wrong.

  Existing servers keep whatever is in their own `plugins/Jarvis/config.yml` —
  if that still names `claude-sonnet-4-20250514`, update it by hand.

### Changed

- **Docs consolidated into the README.** `FEATURES.md` and `INSTALLATION.md`
  both announced a "v3.0" that never existed and duplicated most of the README.
  What was genuinely useful — provider-by-provider API key setup, a
  troubleshooting section, update steps, performance characteristics and a
  first-run walkthrough — now lives in `README.md`, and both files are gone.
- The README's inline changelog was frozen at v0.0.9. It now points here.

## v0.7.1 (2026-08-27)

A housekeeping release. No new behaviour — v0.7.0 simply would not build.

### Fixed

- **The plugin compiles again.** The `com.yourname` → `com.gadgetman` package
  rename in v0.7.0 left the three `schematics` classes behind, while
  `Jarvis.java` still imported and constructed `SchematicManager`. Anyone
  building from the v0.7.0 tag hit `package com.gadgetman.jarvis.schematics
  does not exist`. `LitematicConverter`, `SchemReader` and `SchematicManager`
  are restored under the correct package, so schematic building works again.
- **Root cause: `.gitignore`.** The rule `schematics/` was meant to exclude
  bulky runtime schematic data, but unanchored it also matched the source
  package `src/main/java/**/schematics/`. The original files predated the
  rule so stayed tracked; the rename created new paths, which git silently
  skipped. The rule is now anchored to `/schematics/`.

- **`plugin.yml` no longer hardcodes the version.** It was pinned at `0.7.0`
  with no Maven resource filtering, so the jar reported a version that had
  to be remembered by hand on every release. It now reads
  `${project.version}` from the pom. Only `plugin.yml` is filtered —
  `config.yml` and the rest are copied verbatim.

### Removed

- Stale docs that no longer described the plugin: `DEPLOY_v0.0.5.md`,
  `CHANGES_SUMMARY.md` (both pinned to v0.0.5, seven releases back) and
  `IMPROVEMENT_PLAN.md` (internal debugging notes from January).

## v0.7.0 — "The Groundskeeper" (2026-08-27)

The estate expands: farming, forestry, fishing — and yes, the dance.

### 🌾 Farming — `/jarvis farm [crop]` and `/jarvis tend [crop]`

- **Sweep** (`farm`): hoe in hand, he walks the field, harvests every mature
  crop within 16 blocks (wheat, carrots, potatoes, beetroot, nether wart,
  melons, pumpkins — or one type: "jarvis, farm the carrots"), **replants
  from the seeds he just collected** (honest farming — no seed, no plant,
  and he says so), and reports the haul.
- **Tend** (`tend`): the standing farmhand — stays at the field, rescans as
  crops mature, delivers to your deposit chest when his bags fill, and comes
  back. Until `/jarvis stop`.

### 💃 The Dance — `/jarvis dance`

Piglin-victory-dance energy from butler primitives: spins on a beat, little
hops, alternating arm swings, note particles, and a note-block melody —
about six seconds, then composed again: *"I trust that was satisfactory,
sir."* Short **milestone celebrations** (two-second bop) also fire on big
moments: mine completed, recovery delivered, 10+ harvest, treasure catch.
(`steward.celebrations: false` to keep him dignified.)

### 🪓 Lumberjack — `/jarvis chop [n]`

Finds real trees (log columns on soil with leaves), walks to the base,
chops the trunk with the timed BlockBreaker — then the rest comes down in
a top-to-bottom timber cascade. Collects the logs, **replants the right
sapling species**, keeps going until the quota (default 5) or the woods
run out. Auto-deposits mid-job when his bags fill.

### 🎣 Fishing — `/jarvis fish`

He finds the water's edge, rod in hand, casts with the real bobber sound,
waits a realistic 10–25 seconds through idle ripples, then — splash — the
catch arcs out of the water to him. Vanilla-ish odds: 85% fish, 10% junk,
5% treasure (name tags, saddles, nautilus shells...). Treasure earns a
celebration; a full bag ends the session with a delivery.

### 🫡 Charm

Away for a couple of minutes? On your return he turns, waves, and greets
you ("Welcome back, sir. The estate stood ready."). Left idle nearby, he
occasionally glances at what you're doing. Pure personality, five-second
heartbeat, `steward.charm: false` to disable.

### 🚶 Patrol routes — `/jarvis patrol`

Night watch, extended: stand at each point and `/jarvis patrol add`
(persisted in data.yml), then `/jarvis patrol` — he walks the circuit as an
aggressive sentry, fights within the leash, and rejoins the route where he
left off. `/jarvis patrol clear` wipes it.

### Wiring

- His toolkit (pickaxe, sword, axe, hoe, rod) is now a protected set —
  never dropped, deposited, or handed over.
- New chat intents: farm/tend (with crop), chop, fish, dance, patrol.
- New config: `farming.field-radius`, `farming.replant-saplings`,
  `steward.charm`, `steward.celebrations`.

---

## v0.6.0 — "The Full Butler" (2026-08-26)

The polish wave: the three most-loved companion services, plus chat-event
housekeeping. This completes the original Butler Plan.

### ⚰️ Recovery service — `/jarvis recover`

The feature every companion mod lives or dies by. When you die with Jarvis
summoned, he remembers the spot and offers: *"My condolences, sir. Shall I
retrieve your effects?"* On command (or automatically, with
`steward.recovery.auto: true`) he travels to your death point, sweeps up
everything on the ground there, returns, and hands it all over — overflow
dropped neatly at your feet. Range-capped (`steward.recovery.max-distance`,
default 192), cross-world declined politely, and he hustles: death drops
despawn in five minutes and he knows it.

### 🏠 Escort home — `/jarvis home`

`/jarvis home set` saves your home point (persisted in data.yml).
`/jarvis home` (or "jarvis, take me home") has him lead the way — walking
ahead but never leaving you behind (he pauses and says "Do keep up, sir"),
**torch-lighting dark stretches of the road as he goes**. Arrival: "Home,
sir. No casualties — I do like a quiet walk."

### 🍞 Supply handoff (the valet service)

A quiet background watch on every summoned Jarvis's owner: hungry (≤4
drumsticks) and he's carrying food? He hands some over. Held tool above 90%
wear and he has a matching replacement in his bags? "Your pickaxe is on its
last legs, sir. A replacement." Strictly from his own inventory — no
conjuring — with a one-minute courtesy cooldown so he doesn't fuss.
(`steward.supply-handoff: false` to disable.)

### 🧹 Housekeeping

- ChatListener migrated from the deprecated `AsyncPlayerChatEvent` to
  Paper's `AsyncChatEvent` (Adventure-native, future-proof for 26.3+).
- New natural-language intents: "get my stuff back", "take me home",
  "remember this as home".

### Deferred (deliberately)

- Phase D dig-through pathing waits on field-test evidence that tunnel
  routes need it.
- 26.3 support waits on Mojang and Citizens shipping.

---

## v0.5.0 — "The House AI" (2026-08-26)

The Steward release: Jarvis runs the estate, not just the errands.

### 📋 The Briefing — `/jarvis report` (and on join)

"Good evening, sir. Day 142, clear skies." Server TPS and ms/tick with
health coloring (green ≥19, straining warning below 16 — proper SRE
instincts), players online, what Jarvis is carrying (with a deposit offer
if a chest is registered), pending item requests for admins, and standing
duties. A compact version greets you on join (`steward.report-on-join`);
chat works too: "jarvis, how are things?"

### ⏰ Standing duties — `/jarvis duties`

Persistent scheduled tasks that survive restarts (duties.yml):

- `/jarvis duty add <interval_minutes> <message...>` — repeating broadcast
- `/jarvis duty remove <id>` — strike it from the schedule
- Chat scheduling now persists: "jarvis, remind everyone about the restart
  every 30 minutes" creates a real duty instead of an in-memory timer that
  died with the server.

### 🏗️ Schematic-first building

"Jarvis, build me a house" now works the way the Butler Plan intended:
the AI **picks the best match from your schematic library** — a constrained
choice that even small local models handle reliably, so it works in
reduced mode — and pastes it. Freeform AI block-planning survives only as
the fallback when nothing in the library fits (and is off in Ollama-only
mode, with a polite suggestion to add schematics).

---

## v0.4.0 — "The Defender" (2026-08-26)

The bodyguard release. The old attack mode (wander at the nearest monster,
poke it) is replaced with a proper Defender built on Sentinel's proven
anchor-and-leash pattern: Jarvis stays with his anchor, engages by stance,
chases only so far, and always returns to his post.

### 🛡️ Bodyguard mode — `/jarvis guard [stance]`

Three stances, switchable live (also by chat: "protect me", "weapons free",
"stand down"):

- **passive** — never fights; stays with you, carries things, judges silently
- **defensive** (default) — engages only what attacks you or him, or is
  actively hunting you (retaliation memory: attackers stay marked for 30s)
- **aggressive** — clears any hostile inside the engage radius

Combat itself is rebuilt: he carries a **diamond sword** in guard mode
(swapped back to the pickaxe when mining), swings on a vanilla-ish cooldown
with real arm animations, **prioritises creepers** (they explode; everything
else can wait), breaks off pursuit at the leash range instead of chasing a
skeleton into the night, and catches up with a teleport only if you leave
him 30+ blocks behind.

### 👁️ Threat callouts

The famous butler move: hostiles approaching from outside your field of view
get announced — **"Creeper, behind you, sir!"** — with a soft note-block
ping, per-mob cooldown so he doesn't nag, and creepers always called
regardless of distance. (`defender.callouts: false` to disable.)

### 🏰 Night watch — `/jarvis watch`

Sentry mode: he holds his current position as a fixed post (aggressive by
default), clears spawns around it, and returns to the exact spot after every
fight. Park him at your base perimeter or mine entrance and sleep soundly.
Stays on duty until `/jarvis stop`.

### Wiring

- `/jarvis attack` still works — it's now an alias for aggressive guard
- New config section `defender:` (engage radius, leash, damage, cooldown,
  callouts)
- The sword, like the pickaxe, is never dropped or deposited

---

## v0.3.0 — "The Home Lab Release" (2026-08-25)

The AI layer gets the router you asked for: light work stays on your rack,
heavy work goes to the cloud, and Ollama-alone still works — with sacrifices.

### 🧠 Tiered AI routing (Ollama-first)

Every AI call now declares a workload tier, each with its own provider chain:

- **LIGHT** (chat intent parsing, butler banter, `/jarvis ask`) → **Ollama
  first** whenever an `ollama:` section exists in config, cloud fallback.
  Light calls get a strict timeout (`ai.light-timeout-seconds: 5`) — if the
  local box is slow, the call falls through instead of making chat laggy,
  and keyword matching remains the final fallback.
- **HEAVY** (freeform build planning) → **cloud first** (Claude/OpenAI/
  Grok/Gemini in your priority order), Ollama as last resort.
- Defaults are computed automatically; override explicitly with
  `ai.routing.light` / `ai.routing.heavy` lists in config.yml.
- Per-provider health tracking with exponential-backoff cooldowns carries
  over from auto mode and now spans both tiers.
- **Ollama JSON mode**: intent parsing and build planning sent to Ollama use
  its structured `format: json` output — small local models are far more
  reliable when constrained.

### 🔒 Reduced mode (Ollama-only)

When no cloud API key is configured and Ollama is, Jarvis enters reduced
mode and says so in the startup log:

- Freeform AI build planning is off (he suggests schematics instead —
  pick-from-a-list is exactly what small models are good at).
- High-risk steward actions parsed from chat (console commands, LuckPerms
  changes, gamerules) are politely declined —
  slash commands still work, and `ai.reduced-mode.allow-risky-actions: true`
  re-enables them if you trust your model.
- Butler memory context is shortened to fit small models.

### 📇 Butler memory

Jarvis now remembers the conversation: every chat exchange is stored in the
existing `chat_interactions` table, and the last few exchanges are folded
into the prompt. "Do that again", "same as before", and "no, the OTHER
chest" now have something to refer to. (5 turns of context normally, 2 in
reduced mode.)

### 📊 `/jarvis ai`

New status command, Grafana energy: current mode, light/heavy routes, which
provider answered the last request of each tier, and per-provider health
(available / cooldown / no key).

---

## v0.2.0 — "The Useful Butler" (2026-08-24)

The roadmap's second act: the branch mine, follow mode, and the pack-mule
chest service.

### ⛏️ The Branch Mine — `/jarvis mine here`

The headline feature. Instead of hunting exposed ores, Jarvis digs a proper
mine the way a player would — and the way MineColonies proved works for
NPCs: he only ever walks corridors he dug himself.

- **Staircase** down to Y=-54 (diamond level; `mining.branch.target-y`)
- **Main gallery** (32 blocks, configurable) with **branch pairs** every 3
  blocks — textbook branch mining, the grid statistically intersects veins
- **Torch-lit as he goes** (every 8 blocks) — the mine stays lit and
  walkable for you afterwards
- **Vein harvesting**: ores exposed in any tunnel wall are mined with
  vanilla timing, and he follows veins a short way from the tunnel
- **Lava/water sealing**: fluid pockets are walled off with cobblestone
  before he opens them; a truly flooded section is skipped, not fought
- **Bridges his own floor** over gaps, digs with full animations, and
  reports: "The mine is complete, sir. 412 blocks excavated, 37 ores
  recovered. Sealed 2 liquid pockets along the way."

### 🧳 Pack mule — chest deposits

- **`/jarvis chest`** — look at any chest or barrel and register it as
  your deposit chest (persisted across restarts in data.yml)
- **`/jarvis deposit`** — Jarvis walks his loot over and unloads it
  (nearest container works too if none is registered)
- **Auto-deposit**: when his bags fill during any mining, he runs the
  delivery himself and comes back to work (`mining.auto-deposit`)

### 🚶 Follow mode — `/jarvis follow`

Proper Alfred-in-the-field: trails a few blocks behind you, picks up
loot as you go, catches up if you elytra off or take a portal.
`/jarvis stop` or `/jarvis return` ends it.

### Natural language

New intents wired through the AI: "dig a mine here", "follow me",
"put your stuff in the chest", "use this chest".

### Known limitations

- A corridor blocked by heavy lava after sealing fails is skipped; in the
  worst case the mine completes partially (he says so).
- The mine plan lives in memory — a server restart mid-mine ends that
  mine (he keeps the loot; just start a new one).

---

## v0.1.1 — Field-test fixes (2026-08-24)

Fixes from the first 0.1.0 field test ("he mined with dirt and barely moved").

### Fixed

- **Dirt in hand instead of the pickaxe.** For player-type NPCs, Citizens
  inventory slot 0 IS the held item — the old "32 dirt for climbing" stack
  was overwriting the pickaxe. That made every block take ~7.5+ seconds
  (wrong-tool speed) and drop nothing (drops are calculated from the held
  item — which is why he "mined rocks" but nothing ever dropped). The
  pickaxe now lives in slot 0, nothing else is ever written there, and it's
  re-checked before/during every mining session.
- **Buried ores were unreachable, so he never went anywhere.** Pathfinding
  cannot path *into solid stone*, so navigation to any buried ore failed
  instantly and the old 2-block "dig what's in front" recovery went nowhere
  (that was the dig-two-blocks-then-freeze behavior). New **tunnel
  executor**: when walking fails, Jarvis digs a proper 1×2 tunnel toward
  the ore cell by cell — staircase descents and ascents included (no more
  digging straight down and getting stuck in his own hole), with lava/water
  and long-drop safety checks. Each cell: dig (vanilla timing, animations)
  → walk in → plan the next. Up to 64 cells per target before he politely
  gives up and picks another ore.
- **Break-speed modifier direction** was inverted (Citizens multiplies
  damage by it, so higher = faster). Was harmless at the default 1.0;
  `break-speed-modifier: 2.0` now correctly means twice as fast.
- Loot pickup, `clearloot`, and dismiss no longer touch hand slot 0.

---

## v0.1.0 — "The Butler Release" (2026-08-24)

The first release of the Butler Plan: mining that actually works, a leaner
feature set, and a new platform target.

### 🎯 Mining rework (the big one)

The old mining was teleport-based by design — v0.0.8 gave up on pathfinding
and warped the NPC around, and Citizens' own default "stuck action" teleports
NPCs to their goal on top of that. Both are gone.

- **Citizens A\* pathfinding, configured properly.** Targets are set once
  (no per-tick re-targeting churn), pathfinder range/repath rate/stationary
  detection are tuned, and the async pathfinder is used by default.
- **No more teleport-hopping.** Citizens' `TeleportStuckAction` is replaced
  with a custom stuck handler. When a path fails, Jarvis digs through the
  obstruction like a player would, or politely gives up on that ore and
  picks another. Teleporting only remains as a last resort for `return`
  across 40+ blocks.
- **Real block breaking.** Instant `breakNaturally()` is replaced with
  Citizens' `BlockBreaker`: vanilla per-tool break timing, arm swings, and
  the crack animation. Jarvis now visibly *mines*.
- **Async ore scanning.** The old code scanned ~15,000 blocks on the main
  thread every half-second. Scans now run once per target on chunk
  snapshots, off the main thread, with a bigger default radius (24).
- **Unreachable-ore memory.** Ores he can't reach get blacklisted for the
  session instead of being retried forever.
- Per-ore progress moved to the action bar; chat only gets milestones
  (every 10 ores) and the final tally. Less spam, same information.

### ✂️ Removed

- **Quest system** — Jarvis is a butler, not a quest board. All quest code,
  commands (`/jarvis quest`), config, and new-database quest tables removed.
  (Existing quest tables in your database file are left untouched; they're
  simply unused.)
- **PvP battle mode** (`/jarvis battle <player>`) — replaced by the proper
  Defender role planned for 0.4.0.

### 🖥️ Platform

- **New primary target: Minecraft 26.2** (Purpur/Paper), with support back
  to **1.21.11**. Built and compile-verified against both the 26.2 and
  1.21.8-era APIs.
- Requires **Citizens 2.0.43+**. Note Citizens only supports the latest
  patch of each Minecraft line (so "1.21" support means 1.21.11).
- 26.x servers require **Java 25** (the plugin itself still targets Java 17
  bytecode and runs on both).
- Maven: renamed package `com.yourname` → `com.gadgetman`, updated the
  Citizens repo URL (moved to maven.citizensnpcs.co), added a
  `legacy-check` build profile that compiles against the 1.21.8 API as a
  floor check.

### 🎩 Butler voice

- NPC messages rewritten in a proper butler voice and moved to Adventure
  Components ("Very good, sir. I shall see to the excavation.").
- New `mining` config section (`search-radius`, `navigator-range`,
  `use-async-pathfinder`, `timed-breaking`, `break-speed-modifier`, `debug`).

### Known notes

- `/jarvis mine` is still "hunt nearby exposed ores" — the deterministic
  branch mine (Jarvis digs his own torch-lit mine) is the headline feature
  of 0.2.0.
- Older files (schematics, building, UI) still use legacy ChatColor
  messages; they compile and run fine on 26.2 and will be migrated with
  their voice pass in a later release.

---

## v0.0.9 and earlier

See `CHANGES_SUMMARY.md` for the pre-butler history (admin actions,
player requests, schematics, quest system, natural language, etc.).
