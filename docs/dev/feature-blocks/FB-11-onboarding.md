# FB-11 — First-join onboarding and interactive tutorial

**Lane F · Size M · Branch `fb/11-onboarding` · Depends on:** FB-09 merged (provider probe
gives the "is the AI working?" answer). Can start before it with a simpler check.
**Idea from:** AI Engine's "tutorial helper for new players", Player2's zero-setup first
run, and the research finding that time-to-first-answer decides whether people keep a
mod.

## Goal

Three moments, each handled once and never nagged about again:

1. **A player's first join** — Jarvis hands them the controller bell, greets them, and
   offers a five-minute interactive tutorial. Second join onward: nothing, unless they
   ask (`/jarvis tutorial`).
2. **An operator's first join when the AI isn't working** — Jarvis says so in one line
   with clickable choices: free local Ollama, a free Gemini key, or Jarvis Cloud trial,
   each a button that runs the matching command or opens the matching link.
3. **Connecting to Jarvis Cloud** — one click from that message, or from the bell menu,
   lands on the activation link (`/jarvis cloud activate` already does the heavy lifting
   in 0.18.0; this block just puts it where a new admin will see it).

## Audit first

1. `steward/Courtesies.java`: `onJoin`, how `firstJoin` is used, the greeting flow and the
   `steward.charm` switch. The welcome bell and tutorial offer hook in here.
2. `core/platform/events/JoinEvent.java`: `firstJoin` comes from the platform. On Paper
   that is `hasPlayedBefore()`, which is false again if the server's player data is
   wiped — so persist "welcomed" in Jarvis's own DB rather than trusting it alone.
3. `ui/ControllerBell.java` (`create()`, `isController()`), `commands/CommandService.java`
   `case "bell"`: reuse the exact item so it's recognized.
4. `ui/Prompts.java`: private question → answer, already keeps prompts off public chat.
   `core/text/RichLine.java`: clickable `command` runs and, since 0.18.0, `url`.
5. `ai/CloudActivation.java` and `ai/ProviderProbe.java` (FB-09): what "AI is working"
   and "Cloud is active" look like programmatically.
6. `DatabaseManager.java` / `UndoService.java` for the persistence pattern.

## Implementation

### Part 1 — first-join welcome (players)

- New `onboarding/Welcome.java`: on `JoinEvent`, if `onboarding.welcome.enabled` and the
  player has no row in `onboarding_state`, then (after `steward.report-join-delay-ticks`):
  - put a `ControllerBell.create()` in their inventory if they don't already hold one
    (drop at feet if full); respect `onboarding.welcome.give-bell`.
  - Jarvis speaks two written lines (no model call): who he is, and "right-click the bell
    to see what I can do".
  - a `RichLine` with two buttons: **[Start the tutorial]** → `/jarvis tutorial start`,
    **[Maybe later]** → `/jarvis tutorial later`. Later = we don't ask again; `/jarvis
    tutorial` always works.
  - write `onboarding_state(player_uuid, welcomed_ts, tutorial_step, tutorial_done_ts)`.
- `JoinEvent.firstJoin` is a hint only; the DB row is the truth. Admins can reset a player
  with `/jarvis tutorial reset <player>`.

### Part 2 — interactive tutorial

Driven by real events, not timers: each step says what to do, watches for it to happen,
confirms in character, and moves on. Steps (each skippable with `/jarvis tutorial skip`):

| # | Jarvis says | Completes when |
|---|---|---|
| 1 | "Ring the bell." | `ButlerInteractEvent`/menu open from the bell |
| 2 | "Press **Summon**, or type `/jarvis summon`." | butler present for this owner |
| 3 | "Now just talk to me. Try saying *follow me* in chat." | intent `follow` executed for them (fast path or AI) |
| 4 | "Look at a chest and say *this is my chest*, or `/jarvis chest`." | deposit chest registered |
| 5 | "Ask me for something. *Mine 5 iron* will do." | a mining task starts |
| 6 | "Say *stop*, then *deposit*." | deposit task runs |
| 7 | "Last one: ask me to *build a small hut*, then `/jarvis undo`." (only if `building.enabled`) | an undo batch restored |
| — | "That's the lot. `/jarvis help` lists everything; the bell has it all as buttons." | `tutorial_done_ts` written |

- New `onboarding/Tutorial.java`: a step list, current step persisted per player, event
  subscriptions through `platform.events()` for detection, lines via the owner's
  `Responder` so voice players hear them. Steps that need a working AI (3, 5, 7) are
  skipped automatically with a note if `ProviderProbe` says nothing is usable, so the
  tutorial never dead-ends on a server with no key yet.
- Commands: `/jarvis tutorial` (start or resume), `start`, `skip`, `later`, `stop`,
  `reset <player>` (admin). Bell menu: a **Tutorial** button on the main page, shown only
  until `tutorial_done_ts` is set, then moves into the Help page.
- Each step has a 10-minute idle reminder, once; then the tutorial goes quiet until
  `/jarvis tutorial`.

### Part 3 — operator quick start

- In `Welcome`, for a player with `jarvis.admin` (or op) whose first-join row is empty
  **and** `ProviderProbe.usable()` is empty: instead of the player greeting, one line —
  "I'm here, but I can't think yet: no AI is set up." — followed by three `RichLine`
  buttons:
  - **[Use a free local model]** → `/jarvis setup ollama` (prints `ollama pull mistral`
    and the config line; FB-09's `/jarvis setup`)
  - **[Use a free Gemini key]** → `/jarvis setup gemini` (prints where to get it and
    the `env:GEMINI_API_KEY` config line)
  - **[Try Jarvis Cloud free]** → `/jarvis cloud activate`
- Repeats once per restart, only to admins, only while nothing is usable
  (`onboarding.admin-nag: once-per-restart | never`). The moment any provider works, the
  normal player welcome applies to them too.
- Bell menu → Admin → **AI setup** page (exists in 0.18.0 for Cloud) gains the same three
  buttons.

### Config

```yaml
onboarding:
  welcome:
    enabled: true
    give-bell: true            # Hand the controller bell on a player's first join
    offer-tutorial: true       # Ask once; /jarvis tutorial always works regardless
  tutorial:
    enabled: true
    reminder-minutes: 10       # One nudge per step if they wander off
  admin-nag: once-per-restart  # Tell admins how to get the AI running while it isn't
```

## Files

`onboarding/Welcome.java`, `onboarding/Tutorial.java`, `onboarding/TutorialStep.java`
(new), `steward/Courtesies.java` (hook or move the join hook), `commands/CommandService.java`
(`tutorial` subcommand), `ui/Menus.java` (Tutorial button, AI setup buttons),
`DatabaseManager.java` (`onboarding_state`), `config.yml`, `plugin.yml`
(`jarvis.tutorial.reset`), `CHANGELOG.md`, `docs/commands.md`, `wiki/Getting-Started.md`
(the written tutorial — a draft is already in `wiki/`; verify every command against
`docs/commands.md` and finish it), `wiki/The-Bell-Menu.md`, `wiki/Installation-and-Setup.md`.

## Unit tests

`onboarding/WelcomeTest.java` (Fixture fakes + in-memory SQLite):
- First join, no row → bell given once, greeting sent, row written. Second join → nothing.
- First join but player already holds a controller bell → no second bell.
- `give-bell: false` → greeting but no item. `enabled: false` → nothing, no row.
- Admin, no usable provider → the three-button line, no bell/tutorial offer; same admin
  with a usable provider → normal welcome.
- Player data wiped (`firstJoin` true again) but DB row exists → nothing.
`onboarding/TutorialTest.java`:
- Steps advance on the right fake event and not on others; current step persists across a
  simulated restart.
- No usable provider → steps 3, 5, 7 skipped with the note, tutorial still completes.
- `skip`, `later`, `stop`, `reset` do what they say; `done` hides the menu button.
- Reminder fires once per step after `reminder-minutes` on `FakeScheduler`, never twice.

## In-game test (owner)

1. New account (or `/jarvis tutorial reset <you>` + delete your row), join → bell in
   hand, two greeting lines, two buttons. Click **Maybe later**. Leave and rejoin → silence.
2. `/jarvis tutorial` → step 1. Ring the bell → step 2 confirms. Summon → step 3. Say
   `follow me` → step 4. Look at a chest, `/jarvis chest` → step 5. `mine 5 iron` → step 6.
   `stop`, `deposit` → step 7. `build a small hut`, `/jarvis undo` → done line. Bell menu no
   longer shows **Tutorial** on the main page; it's under Help.
3. Wander off mid-step for 10 minutes → one reminder, then quiet.
4. Fresh server, no AI configured, join as op → the three-button line. Click **Try Jarvis
   Cloud free** → activation link appears (0.18.0 flow). Finish it, rejoin → normal
   welcome, no nag.
5. Same fresh server, non-op player joins → player welcome, tutorial skips the AI steps
   with the note.
6. Voice player: tutorial lines are spoken, not only printed.

## CHANGELOG line

`### Added` — **A proper welcome.** New players get the controller bell and an offer of a
five-minute interactive tutorial that walks them through the bell, summoning, talking to
Jarvis, chests, mining and undo, reacting to what they actually do. Admins joining a
server with no AI set up get three clickable ways to fix that — free local Ollama, a free
Gemini key, or Jarvis Cloud — right in chat. `onboarding:` in config; `/jarvis tutorial`.

## Audit findings (2026-11-12)

1. **`steward/Courtesies.java`** greets every join with an AI line (`butler.auto-greet`,
   on by default) and uses `firstJoin` only to tell the model "make it special". There is no
   `steward.charm` check in it (charm gates the butler's waves and the remarks). Left as is:
   the welcome is a separate, written, once-only message, so a first join with auto-greet on
   gets both. Turning auto-greet off leaves only the welcome.
2. **`JoinEvent(who, firstJoin)`**: on Paper `firstJoin` is `!hasPlayedBefore()`. Confirmed
   it comes back after a player-data wipe, so the `onboarding_state` row is the truth.
3. **`ControllerBell.create()` / `isController()`** recognise the bell by its item marker;
   `case "bell"` gives it with `player.give`, which on Paper drops what won't fit at the
   player's feet. The welcome uses the same two calls, and checks held item + inventory first.
4. **`Prompts`** is for a private question with a typed answer; buttons are `RichLine`
   (`link` runs a command as the clicker, `url` opens a page). The welcome and the admin line
   are `RichLine`s; nothing needed a typed answer.
5. **`ProviderProbe` (FB-09)** runs at boot, on reload and on `/jarvis setup`;
   `JarvisCore.providerStatus()` is null until the first probe finishes. FB-09 also added a
   one-line op join notice in `JarvisCore`; FB-11's three-button line **replaces** it (same
   condition, now once per restart and switchable). `CloudActivation.isActive()` is the
   "Cloud is on" check; `/jarvis cloud activate` already does the whole flow.
6. **Persistence**: `DatabaseManager.createTables` + small blocking JDBC calls off the
   server thread (`BuildQuota`, FB-07, is the closest pattern). `UndoService` queues writes;
   not needed for one row per player.
7. **Spec vs code**:
   - `/jarvis setup ollama` and `/jarvis setup gemini` didn't exist (FB-09 has `setup` and
     `setup pull`). Added both; they print the steps and config keys, nothing is run.
   - The bell has no **Help** page. The Tutorial button lives on the main page until done,
     then on the **Steward** page.
   - `building.enabled` isn't a key. The build step runs when an AI can answer and
     `build.enable-undo` is on.
   - There is no core event for "intent follow executed", "chest registered", "mining
     started", "deposit ran" or "undo restored". Added `onboarding/Milestones`: `ButlerService`
     (summon, `beginTask` follow/mine/branch_mine), `DepositManager` (setChest, deposit),
     `UndoService` (undo starts) and `Menus` (bell/butler/menu open) report to it. Any path
     counts: slash command, button, or chat.
   - "Steps 3, 5 and 7 need a working AI": since FB-01 the fast path answers "follow me" and
     "mine 5 iron" with no model, so only step 7 (build) is skipped without one.
   - "this is my chest" is not a fast-path phrase (needs a model), so step 4 says
     `/jarvis chest` or the bell's **Set deposit chest**.
   - Voice: no per-owner voice responder exists in core (voice replies only answer voice
     input). Added `VoiceStatus.speak(owner, line)` (no-op default); the Simple Voice Chat
     plugin speaks the line if that player is connected to voice chat. Lines are always
     written in chat as well.
   - The deposit step completes once a deposit is asked for with a chest to go to, even if
     his bags are empty (a new player may have mined nothing).

## Built

- `onboarding/Milestone.java`, `Milestones.java`, `OnboardingStore.java`
  (`onboarding_state`: `player_id`, `welcomed_at`, `tutorial_step`, `tutorial_active`,
  `tutorial_done_at`; one extra column over the spec, so "stopped at step 3" and "running
  step 3" differ), `TutorialStep.java`, `Tutorial.java`, `Welcome.java`.
- `JarvisCore`: builds them, `setAiCheck` (tests), `milestones()`, `tutorial()`; FB-09's join
  line removed in favour of `Welcome`.
- Hooks: `ButlerService`, `DepositManager`, `UndoService`, `ui/Menus` (+ Tutorial button on
  the main page / Steward page, three quick-start buttons on Admin > AI setup).
- `CommandService`: `/jarvis tutorial [start|skip|later|stop|reset <player>]` (reset works
  from the console), `/jarvis setup ollama|gemini`, tab completion, help lines, KNOWN and
  COMPLETIONS lists.
- `VoiceStatus.speak` + `SvcVoicePlugin.speak`.
- `config.yml` `onboarding:`; `plugin.yml` `jarvis.tutorial.reset` (op).
- Docs: CHANGELOG, `docs/commands.md`, `wiki/Getting-Started.md` (draft note removed; commands
  checked against `docs/commands.md` and the code), `wiki/The-Bell-Menu.md`,
  `wiki/Installation-and-Setup.md`.

Tests: `WelcomeTest` (7) and `TutorialTest` (8); full suite 394 core + 26 paper,
`mvn -o clean test`. Fabric and NeoForge build (Gradle 9.5.1).

## Results

Run 2026-11-12 on GadgetCraft with the headless bot (an op, an existing player), FB-08 + FB-11
combined, AI working (Jarvis Cloud + Ollama), `onboarding.tutorial.reminder-minutes: 1` for the
run only.

| Step | Result |
|---|---|
| existing player joins (no row yet) | no welcome, no bell: only the usual greeting and briefing (the review fix) |
| 1. `/jarvis tutorial reset callmegadget` (RCON), rejoin | "Good day, callmegadget. I'm Jarvis…", "That bell is my controls…", "[Start the tutorial] [Maybe later]" |
| 1. `/jarvis tutorial later`, rejoin | "Whenever you like, sir…"; next join silent |
| 2. `/jarvis tutorial start` | "seven steps…", step 1 (ring the bell): skipped, the bot can't right-click |
| step 2 `/jarvis summon` | "At your service. That's me." → step 3 |
| step 3 `/jarvis follow` | "Right behind you. You can say most things in plain words." → step 4 |
| 3. idle 140 s at step 4 (reminder 1 min) | one reminder, then quiet |
| step 4 (chest) | skipped: the bot can't aim at a chest |
| step 5 `/jarvis mine iron` | "Off I go…" → step 6 |
| step 6 `stop`, `deposit` | no chest on record (step 4 was skipped), so not counted; skipped |
| step 7 build a small hut → yes → `/jarvis undo` | 212 blocks placed, 212 restored, "Gone again…", then "That's the lot…" |
| `/jarvis setup ollama`, `/jarvis setup gemini` (the admin buttons' commands) | both print their steps |
| 4. admin with no AI (three buttons), 5. non-op on a server with no AI, 6. voice | not run: need every provider off, a second account, a voice player. Unit tests cover 4 and 5 |

Not checked live: the bell itself arriving (my inventory check used `clear`, which on that
server is EssentialsX's clear-inventory and emptied the bot's inventory) and the bell menu's
Tutorial button. Note: the greeting says "Good day" at any hour.

### Review fix (2026-11-12): existing servers

"No row in `onboarding_state`" is also true for every regular the first time a server runs
this version, so each of them would have got a welcome and a bell. Now a player with no row
whose platform says they have played before is noted quietly (row written, no welcome) unless
`onboarding.welcome.existing-players: true`. The wiped-player-data case is unchanged (a row
exists, so nothing). `/jarvis tutorial reset` therefore leaves a row with `welcomed_ts = 0`
("welcome again") instead of deleting it, so a reset regular is still welcomed, and the reset
survives a restart. Tests: `WelcomeTest.existingPlayerQuiet`, `resetWelcomesAgain`.

**Owner, 2026-11-12:** accepted the headless results; marked `tested`.
