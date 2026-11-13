# FB-08 — Reward tags → admin-defined command bundles

**Lane D · Size S · Branch `fb/08-reward-tags` · Depends on:** FB-07 merged (same lane;
avoids `config.yml` churn). **Idea from:** AI Chat Mod (model emits `!grant_diamond_protocol!`,
plugin runs a pre-defined command set).

## Goal

Admins define named bundles of server commands in config. The model can trigger a bundle
by name (as an action `trigger_bundle {name}`) — e.g. a quest reward, a VIP welcome, a
"you found the hidden shrine" effect — but can **never** compose raw commands itself.
This gives quests and roleplay real teeth without widening console access.

## Audit first

1. `commands/ActionExecutor.java`: how `console_command` executes and what permission
   gate it has; the quest reward path in `quests/` (grep `reward`).
2. `IntentPipeline.RESTRICTED_IN_REDUCED_MODE`: `trigger_bundle` must **not** be in it
   (bundles are admin-authored, so they're safe in reduced mode) — document why in a
   code comment.

## Implementation

- Config:
  ```yaml
  bundles:
    first-diamond:
      description: "Celebrate a player's first diamond"      # Shown to the model
      once-per-player: true
      commands:
        - "give {player} minecraft:cooked_beef 8"
        - "title {player} title {\"text\":\"Diamonds!\",\"color\":\"aqua\"}"
        - "playsound minecraft:ui.toast.challenge_complete master {player}"
    shrine-blessing:
      description: "Reward for reaching the hidden shrine"
      cooldown-minutes: 1440
      commands: ["effect give {player} minecraft:regeneration 60 1"]
  ```
  Placeholders: `{player}`, `{uuid}`, `{x}` `{y}` `{z}`, `{world}`. PlaceholderAPI
  placeholders are passed through if the plugin is present.
- `commands/BundleRegistry.java`: loads bundles, tracks once-per-player and cooldowns in
  SQLite, runs commands as console on the server thread, logs every trigger
  (`[bundle] first-diamond for Gadget by ai`).
- New action `trigger_bundle {name}` in `ActionExecutor`; the system prompt (and
  FB-06 tool schema if merged) lists available bundle names with descriptions. Unknown
  name → polite refusal, logged.
- Also usable without AI: `/jarvis bundle run <name> [player]` (op) for testing, and
  from the quest system as a reward type `bundle: <name>`.

## Files

`commands/BundleRegistry.java` (new), `commands/ActionExecutor.java`,
`ai/AIConnector.java` (prompt list; or `ai/ActionSchema.java` if FB-06 exists),
`commands/JarvisCommands.java`, `quests/` reward handling, `DatabaseManager.java`,
`config.yml`, `CHANGELOG.md`, `docs/configuration.md`, `docs/commands.md`.

## Unit tests

`commands/BundleRegistryTest.java`:
- Placeholders substituted; `{player}` with a space-free name.
- `once-per-player` fires once, second trigger refused and logged.
- Cooldown honored with a fake clock; different players independent.
- Unknown bundle → refused, no command run.
- Commands run via the platform's console sink (assert on `FakePlatform`), never via
  the player.

## In-game test (owner)

1. Add the `first-diamond` bundle above. `/jarvis bundle run first-diamond` → beef,
   title, sound.
2. Say `I just found my first diamond!` → model triggers the bundle; second time it
   refuses ("already celebrated").
3. Say `run the shrine-blessing bundle` → regeneration; again within a day → cooldown
   message.
4. Say `run bundle op-me` (doesn't exist) → polite refusal, console log line.
5. Reduced mode (Ollama only): step 1 still works.

## CHANGELOG line

`### Added` — **Reward bundles.** Define named command bundles in config
(`bundles:`); Jarvis and the quest system can trigger them by name, with once-per-player
and cooldown rules, and the model can't compose raw commands. `/jarvis bundle run <name>`
to test.

## Audit findings (2026-11-12)

- `console_command`/`console_commands` live in the adapters' executors
  (`JarvisActionExecutor`, `VanillaActionExecutor`), are in `dangerousActions()` (always
  confirmed) and in `RESTRICTED_IN_REDUCED_MODE`. Core had no way to run a console command, so
  this block adds `Platform.runConsoleCommand` (Paper: console sender; mods: the server's
  command source), documented as admin-written commands only. The model still has no new way to
  compose a command: it names a bundle, and the text comes from `config.yml`.
- There is no `quests/` package (the only "reward" is the rank system's wording in
  `progression/Rank.java`), so "reward type `bundle: <name>`" has nothing to hook into. Left
  out; a future quest system can call `core.bundles().trigger(player, name, By.ADMIN)`.
- FB-06 (tool schema) isn't merged, so the bundle list goes into the prompt as text. It rides
  on the server-facts slot that `parseNaturalLanguage` already takes, so `AIConnector` is
  unchanged and a server without bundles pays nothing for it.
- `trigger_bundle` is not in `RESTRICTED_IN_REDUCED_MODE` (comment beside the set says why).

## Built

- `commands/BundleRegistry.java`: loads `bundles:` (names `[a-z0-9_-]`, skipped with a warning
  otherwise or when a bundle has no commands), placeholders `{player} {uuid} {x} {y} {z}
  {world}` + PlaceholderAPI by reflection on Paper (no new dependency), once-per-player and
  cooldown in SQLite (`bundle_uses`), every trigger logged as `[bundle] <name> for <player> by
  ai|admin`. Player names are checked against `[A-Za-z0-9_]{1,16}` before they go into a
  command.
- Two keys beyond the spec: `ai: false` (only `/jarvis bundle run` can give it; hidden from the
  model) and `permission: <node>` (Jarvis gives it only to players with that node), because
  players can claim anything in chat.
- `IntentPipeline`: `trigger_bundle {name}`; on success the model's own reply is the
  celebration, a refusal is said in plain words. `/jarvis bundle [list]`, `/jarvis bundle run
  <name> [player]` (admin or console; the console must name the player).
- Tests: `BundleRegistryTest` (9): loading, placeholders, once-per-player, cooldown with a fake
  clock and across a new registry, unknown name, `ai: false`, empty prompt without bundles,
  through the pipeline with a fake Ollama (reduced mode, console sink only, prompt lists the
  bundles, second time refused), and the command's permission and console rules.
  388 + 26 Maven tests; Fabric and NeoForge build.

## Results

Run 2026-11-12 on GadgetCraft with the headless bot (an op), Jarvis Cloud + Ollama
(qwen2.5:7b on the light route), the spec's two bundles added to `config.yml` for the run and
removed afterwards.

| Step | Result |
|---|---|
| `/jarvis bundle list` (over RCON) | both bundles, with "once per player" / "every 1440 min" |
| 2. `/jarvis I just found my first diamond!` | **first try: no bundle** (the model answered with give_item, which FB-14 turned into an offer, then with chat). The prompt section now says to answer with trigger_bundle and carries an example built from the first bundle. Then: 8 steak, title, sound, log `[bundle] first-diamond for callmegadget by ai (3/3 commands)` |
| 2. same again | "We've already celebrated that one, sir.", log `refused, already given` |
| 3. `/jarvis run the shrine-blessing bundle` | Regeneration applied, log `(1/1 commands)` |
| 3. same again | "Not again so soon, sir. That one's ready in 23h 59m." |
| 4. `/jarvis run bundle op-me` | the model named shrine-blessing instead (refused, cooldown). The unknown-name path checked with `/jarvis bundle run op-me`: "I have no reward called 'op-me', sir. The admin sets those up." + log line |
| 1. `/jarvis bundle run first-diamond` (after step 2) | refused, already given: the admin path keeps the rules |
| 5. reduced mode | not run live (would mean switching off Jarvis Cloud); covered by `throughThePipeline` (Ollama-only fixture) |

Fixed from the run: the prompt section above, and the model's celebrating reply is now said
only when the bundle ran (live, "Well earned, sir." came before the refusal). With
`ai.streaming` on the reply is out before the action is known, so there the refusal still
follows it.
