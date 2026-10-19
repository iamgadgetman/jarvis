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

## Nether portals

**Shipped in v0.16.0** — sighting, memory, the 1:8 arithmetic and the escort.
What was deliberately left out, and why:

- **Searching beyond loaded chunks.** A sweep sees what the server has in
  memory. Reaching further means force-loading terrain, which is a server hitch
  in exchange for a convenience, and a synchronous one at that. If this is ever
  wanted, the shape is an async chunk load of a ring at a time with a hard
  budget — not a bigger radius.
- **Following you through.** Citizens NPCs do not change dimension with the
  player. Doing it properly means despawning him on this side and respawning at
  the counterpart position, with his inventory carried across and the escort
  resumed — a piece of work in its own right, and one that wants the arithmetic
  above to decide *where* to reappear.
- **Building a portal.** He has the obsidian question already solved elsewhere
  (`BuildingAssistant` places blocks, the ladder grants him tools); what is
  missing is a decision about whether a butler who conjures a way home is still
  playing the same game.
- **Unlit frames.** A scan finds `NETHER_PORTAL` blocks, which means lit
  portals only. An obsidian ring standing dark is invisible to him, and
  distinguishing "a portal frame" from "someone's obsidian wall" is a shape
  problem rather than a block problem.

## Building

Both ideas below are borrowed from [AgentCraft](https://github.com/bytewife/agentcraft),
a 2021 GDMC settlement generator. That repository carries no licence, so this is
the idea and not the code.

### He builds where you are standing, not where it fits

Every build starts at the player's feet. `BuildingAssistant` takes
`player.pos().block()` as the origin, and so do both schematic paths — the
native paste in `SchematicLibrary` and the WorldEdit one in `SchematicManager`.
`SiteSurvey` then reports on that one spot, and the only thing it can say is
whether the spot is enclosed. Whether the ground is level, or the site
backs onto a pond, or a corner sits on lava, never comes up. Neither does a
spot ten blocks away that would have been better.

AgentCraft's `check_build_spot` shows the alternative. It checks each
candidate footprint and rejects it if:

- the ground height across the footprint varies by more than a tolerance;
- any tile is water or lava;
- it borders something already built.

It tries about twenty-five random candidates. When it keeps failing, it raises
the tolerance a step instead of giving up.

The shape for Jarvis is a `SiteDoctrine`, a pure function in the same family as
`WeaponDoctrine` and `RemarkDoctrine`. It takes a height sample and a footprint
and returns either a spot or a reason there is none. Sampling stays on the
server thread with the rest of the survey; the decision does not need to be
there, and can be pinned by tests against a grid the way `GridTerrain` already
pins navigation.

Two stages, the first worth doing on its own:

1. **Tell the model.** Add the height range under the footprint to
   `SiteSurvey` and say it in `siteNote` when it is large enough to matter.
   "The ground falls six blocks from east to west across this footprint" is
   something a script can answer with a stepped floor or a terrace, and
   nothing currently tells it. No movement, no new behaviour, one more line in
   the site note, and it is logged like the others.
2. **Move the origin.** When the spot underfoot fails, search outwards for one
   that passes, and say where he is going ("Not here, sir — the ground is
   uneven. Ten blocks north will do."). This needs a footprint *before*
   planning. A schematic has one. A scripted build does not until the script
   has run. The plan can be run once, its bounding box taken, and the box
   placed. Or it can be planned at the chosen spot with an assumed footprint
   and re-checked afterwards.

Open questions:

- **Is moving it rude?** "Build me a house *here*" is sometimes exactly what
  was meant. The likely answer is that stage 2 suggests and asks rather than
  silently relocating — the confirmation path already exists — and that an
  explicit "here" skips it.
- **The tolerance is not chosen.** AgentCraft began at 4 and relaxed toward 5
  because its first building took too long to place. Those numbers were fitted
  to its terrain and its building sizes, not to these. Start with something
  readable, log the measured range on every build, and fit it later.
- **Underground.** On an enclosed site the "ground" is a cave floor and the
  footprint check means something different. The first version can skip the
  search when `SiteSurvey.enclosed()` is true and keep today's behaviour there.

### Builds on a slope are left floating

Schematic pastes skip air (`schematics.paste-air: false`), and a scripted
build places only what the script draws. On a hillside, the downhill edge of
anything with a flat floor ends up over a gap, held up by nothing. That happens
whenever the ground drops away under part of the build.

AgentCraft's `set_platform` fills each footprint column downwards from the
floor until it meets the ground. It uses scaffolding over water and dirt over
roads. For Jarvis that means a foundation pass after placement and before the
build is reported finished:

- For each column under the build's lowest layer, place a foundation block
  from the floor down to the first occluding block, with a depth cap. A
  build cantilevered over a ravine should stay a cantilever rather than
  growing a forty-block pillar.
- The block follows the build's own lowest layer (stone under stone,
  planks under planks), with cobblestone as the fallback.
- On a scripted build it goes into the same `BuildAction` list as the rest of
  the build, so `/jarvis undo` takes the foundation with the building.
  Schematic pastes have no Jarvis undo today: the WorldEdit path points the
  player at `//undo` and the native paste records nothing. So the
  foundation under a paste is permanent unless it joins the WorldEdit edit
  session, and that is a reason to fix paste undo alongside it.
- It counts toward Construction. That fixes it for one more source of blocks
  rather than adding a second instance of the schematic-paste gap in
  *Progression* above.

This should land **with or before** stage 2 of the site search. Moving
the origin to the best nearby spot means building on more uneven ground than
a player standing on a flat patch usually picks. Without a foundation, a
better spot would still come out as a worse-looking build.

Still open: should it be a switch? Some players will want a building to sit in
the terrain untouched. Probably a config key defaulting on, plus a way to
say "no foundation" in the request, rather than a model decision.

## Untested

These are known-unverified rather than known-broken.

- **Stuck recovery inside a 3x3 tunnel.** Nine cells per step is far more
  block-breaking per advance than the engine was built for.
- **Two or more players at once.** Progression is per-player by design, but
  concurrent NPCs, voice channels and audio players have only ever been
  exercised by one.
- **The portal sweep's cost on a busy server.** One async pass over a
  96x96x80 box per summoned player every twenty seconds, skipped when nobody
  has moved. Measured nowhere; the guard that matters is the move check.
- **Remarks in a live session.** The decision table is pinned by tests and the
  timer is trivial, but nobody has yet played for an hour with them on. The
  thing to watch for is not a wrong line — it is the cadence being wearing.
- **Looting on projectile kills.** It applies via the killer's held item;
  whether that survives a thrown trident is unconfirmed.

## Elsewhere

- Nothing outstanding here. `Defender.ATTACK_REACH` — one constant for all
  weapons — is gone; reach moved onto `Armament` in v0.14.0.
