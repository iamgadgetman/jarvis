# FB-15 — No unasked time, weather, summons or clears

**Lane A (`intent/IntentPipeline`) · Size S · Branch `fb/15-unasked-world-changes` · Depends on:** FB-14 ·
**Source:** open item after FB-14 (2026-11-11): the ask-guard covered gifts, not world changes.

## Problem

FB-14 stopped free items and heals for questions, but the same small model can answer
"what should I do next?" or "it's dark out here" by setting the time to day, clearing the
weather or summoning something. Those change the world for every player.

## Change

`IntentPipeline.WORLD_ACTIONS` maps each world-changing action to the words that count as
asking; `unaskedAction` checks it the same way it checks `GIFT_ACTIONS`.

| Action | Asking words (any of) |
|---|---|
| `set_time` | time, day…, night…, noon, midnight, morning, sunrise, sunset, dawn, dusk, evening |
| `set_weather` | weather, rain…, storm…, thunder…, lightning, sunny, sunshine, clear sky/skies, skies |
| `set_gamemode` | game mode, gamemode, creative, survival, adventure, spectator, gm 0–3 |
| `set_gamerule` | gamerule(s), game rule(s), rule(s) |
| `set_difficulty` | difficulty, peaceful, easy, normal, hard |
| `summon` | summon…, spawn…, conjure, create a/an, make a/an |
| `clear_mobs` | clear, kill, remove, get rid, purge, wipe, despawn, butcher |
| `clear_drops` | clear, remove, clean…, purge, wipe, pick up |

An unasked one runs as chat, and the model's reply ("Daylight it is.") is replaced by an
offer (`worldOffer`): "Shall I make it day, sir? Say "make it day" and it's done." The stream
sink already skips replies `unaskedAction` will rewrite. Asked-for actions keep their existing
Confirm/permission handling. No config keys, no platform interface changes.

`GiftGuardTest.askWords` used `summon` as its "untouched" example; it now uses `build`.

Found in the first live run: `/jarvis stop the rain` ran the `stop` subcommand and dropped
"the rain". `CommandService.BARE_SUBCOMMANDS` (summon, dismiss, return, attack, stop, follow,
fish, dance, chest, deposit, loot, confirm, cancel, requests, quiet, hush) take no words; with
words after them the whole input now goes to the pipeline as a sentence. Bare `/jarvis stop`
is unchanged.

## Tests

`WorldGuardTest`: a `set_time` reply to "what should I do next?" leaves the time and offers
day; "make it day please" sets it; an unasked summon is offered and "spawn a cow" summons;
the asking words per action; offer text ("set the weather to clear", "summon an iron golem").
`CommandServiceTest.bareSubcommandWithWordsIsASentence`: "/jarvis stop the rain" goes to the
pipeline; bare "/jarvis stop" doesn't.

## In-game test (GadgetCraft, callmegadget, **`/jarvis <sentence>` only**)

| # | Do | Expect |
|---|---|---|
| 1 | At night: `/jarvis it's dark and scary out here, what should I do?` (a few times) | Advice or an offer ("Shall I make it day…"); the time does not change. |
| 2 | `/jarvis make it day` | Time set to day (Confirm first if the server asks for it). |
| 3 | `/jarvis stop the rain` (with `/weather rain` first) | Weather clears. |

## Results

Run 2026-11-11 with callmegadget (all `/jarvis <sentence>`), qwen2.5:7b on Ollama.

| # | Result | Notes |
|---|---|---|
| 1 | PASS | Time set to midnight; asked three times. Advice each time ("Equip that sword and stay alert"); the time never changed. The second answer was an FB-14 gift offer ("I'd suggest a stone sword, sir…"), the first time that guard fired live. |
| 2 | PASS | "make it day" → "[Jarvis] Set time to day in world." |
| 3 | FAIL → PASS | First jar (`882e3cf0`): "stop the rain" ran the `stop` subcommand ("Standing down, sir."). Fixed (`BARE_SUBCOMMANDS`); on jar `4f00378a`: "Rain has ceased." + "[Jarvis] Set weather to clear in world." |

qwen didn't pick an unasked world change in step 1, so the new guard didn't fire live;
`WorldGuardTest` covers it.
