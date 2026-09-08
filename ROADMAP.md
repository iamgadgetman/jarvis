# Jarvis — roadmap

Things decided but not built. Kept here rather than in an issue tracker so the
reasoning survives alongside the code that will have to honour it.

## Weaponry expertise

The service ladder ended at a trident. Combat is meant to broaden into a
discipline of its own rather than a single escalating melee stat.

**Archery shipped in v0.14.0.** `WeaponDoctrine` now decides weapon and stance
together as one pure function of the situation, `Armament` carries per-weapon
reach and stand-off band, and `Defender` asks the doctrine rather than
branching. The two things that were flagged as hard both had to be solved:
arrows are led by the target's own velocity over the flight time and raised by
the drop that flight accrues, and the stand-off policy gives ground rather than
closing. Ammunition went the way the note predicted — conjured, not drawn from a
quiver, with pickup disallowed so it can never become finite loot.

### Spears

Reach weapons between sword and bow. Minecraft has no native spear, so this
means either a modelled trident variant or a custom item with an extended
attack reach.

The prerequisite is now in place: **reach belongs to the `Armament`, not to a
constant shared by every weapon.** A spear is a new enum value with a longer
`reach()` and its own band, plus whatever item modelling is chosen — not a new
branch in the tick loop.

The remaining question is the item itself, which is a modelling decision rather
than a combat one, and has not been made.

### Still open

- The doctrine picks from (distance, terrain, water, target type, whether he
  can give ground). It does not consider **crowding** — the case spears are
  supposed to answer — and has no notion of being flanked by several things at
  once.
- `Armament` bands are chosen, not fitted. Nobody has measured whether 8–28 m
  is the right window for a bow in practice.

## Progression

### Schematic pastes do not count toward Construction

`BuildingAssistant` credits blocks placed; `SchemReader.pasteSchematic` does
not. It returns `void` and never counts what it places, so pasting a
2000-block castle earns nothing while improvising a hut earns its full worth.
Threading a count back out of that async paste is the work.

### Enchantment forks should be choices, not a checklist

The ladder currently grants every enchantment at its rank. The original design
had Fortune and Silk Touch as a **mutually exclusive fork** — the point being
that a rank should occasionally ask something of you rather than just hand over
more. Same shape would suit Looting versus Fire Aspect.

### The pace is unmeasured

Rank thresholds (25 / 75 / 150 / 300 / 500 / 800 / 1500 / 2500 / 4000) were
chosen to be front-loaded, not fitted to observed play. `progression.rate`
scales the whole curve without a rebuild; tune it once there is real data on
how long the early ranks actually take.

## Voice

### GPU inference

Speech runs on CPU. Measured on the reference box: transcription 1.1 s for a
2.7 s clip (`base.en`, int8), Piper synthesis 0.17 s warm. A GPU is present and
idle but has no `nvidia-container-toolkit`, and installing it restarts the
Docker daemon and everything alongside it.

Worth it for two things: transcription would drop to roughly 0.2 s, and the
**Kokoro** voices become usable. Kokoro sounds markedly better than Piper and
has proper British male voices, but measured **4.3 s to generate 2.3 s of
audio** on CPU — slower than realtime, so it was rejected on latency alone,
not on quality.

### No barge-in

Speech is queued and plays to completion. There is no way to interrupt him
mid-sentence, which is noticeable when he is halfway through a long reply and
you have already changed your mind. Needs a stop path that cancels the active
`AudioPlayer` and clears the queue on a new utterance.

## Remarks

**Shipped in v0.15.0.** He remarks on what he can see while standing about —
tier 1 only, and that turned out to be enough to carry the feature. No
dependency, no action log, nothing on disk, and no model call: `Observer` reads
plain getters, `RemarkDoctrine` decides as a pure function, `Remarks` runs the
timer. The judgement the note called the real work — seventy-two iron ore is
worth a line, forty-one cobblestone is not — is a threshold table that can be
read and argued with, and is pinned by tests.

Cooldowns are keyed on the subject rather than the wording, which is what stops
the cousin remark about sixty-eight iron ore. Hunger and tool wear were left out
on purpose: the valet already speaks to both, and two subsystems noticing the
same fact is how this becomes tiresome.

### Still open

- **Tier 2 — change, which is diffing.** "You have been at that hole for twenty
  minutes." "That is the third pickaxe today." One previous `Observation` per
  player held in memory, compared against the current one. Still no plugin and
  still nothing on disk. The shape is already there; nothing holds the previous
  sample yet.
- **Tier 3 — moments that exist only as they happen.** A block placed, a mob
  killed, an item crafted, a death. Perhaps five `@EventHandler` methods feeding
  a bounded ring buffer per player, discarded on logout. Not an audit log, and
  nothing about it wants a database.
- **The model as garnish.** Deliberately absent. If it is ever added it wants
  pinning to the local tier in `ai.provider-priority` and never allowed to fall
  through to a paid provider — the way `memory.embedding-model` already is —
  because this is the only thing he does on a timer rather than on an order.
- **Speech.** Remarks are text. Once `voice.speak-replies` is on they would
  become a man talking at you every ninety seconds, and what is charming to read
  is grating to hear; assume a far slower cadence and a separate switch.
- **The thresholds are chosen, not fitted.** 32 for ore, 128 for copper, 384 for
  cobblestone, ninety seconds and fifteen minutes for the cooldowns. Nobody has
  played a session with them yet.

## Interface

### The task label is coarse

`describeCurrentTask` derives from live state rather than a per-task label, so
most work reports as a flat "Working". The boss bar is correspondingly vague
for farming, chopping, fishing and building — only mining and the guard stances
say anything specific. Richer labels mean a label field each task sets, with
the stale-value risk that was deliberately avoided first time round.

### The queue takes commands, not sentences

`/jarvis queue` stores slash commands and replays them. A natural-language
entry would cost a model call per queued item at dispatch time, which is why it
was left out — but "chop twenty trees then deposit" is the obvious way to want
to use it.

## Untested

These are known-unverified rather than known-broken.

- **Stuck recovery inside a 3x3 tunnel.** Nine cells per step is far more
  block-breaking per advance than the engine was built for.
- **Two or more players at once.** Progression is per-player by design, but
  concurrent NPCs, voice channels and audio players have only ever been
  exercised by one.
- **Remarks in a live session.** The decision table is pinned by tests and the
  timer is trivial, but nobody has yet played for an hour with them on. The
  thing to watch for is not a wrong line — it is the cadence being wearing.
- **Looting on projectile kills.** It applies via the killer's held item;
  whether that survives a thrown trident is unconfirmed.

## Elsewhere

- Nothing outstanding here. `Defender.ATTACK_REACH` — one constant for all
  weapons — is gone; reach moved onto `Armament` in v0.14.0.
