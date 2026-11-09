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
