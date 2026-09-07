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
- **Looting on projectile kills.** It applies via the killer's held item;
  whether that survives a thrown trident is unconfirmed.

## Elsewhere

- Nothing outstanding here. `Defender.ATTACK_REACH` — one constant for all
  weapons — is gone; reach moved onto `Armament` in v0.14.0.
