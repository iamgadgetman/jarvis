# Butler Events

The things Jarvis does without being asked: greeting you, handing you food,
briefing you on the estate, and remarking on what he sees. Most of it is under
`steward:` in the config, and the noisier parts are off by default.

---

## The briefing

```
/jarvis report
```

TPS and MSPT with health colouring, players online, what he is carrying,
pending item requests. He gives it a few seconds after you join, which is the
useful moment for it; `steward.report-on-join` is on by default, and `false`
stops it.

## Standing duties

```
/jarvis duty add 30 Restart at midnight!
/jarvis duties
```

Broadcasts on a timer that **survive restarts**. He reads them out on
schedule and keeps the list in `duties.yml` beside the config. Anyone can list
them; `duty add` and `duty remove <id>` need `jarvis.admin`, or operator on
the mods.

## Supply handoff

`steward.supply-handoff: true` — he offers food when you are hungry and a
replacement tool when your pickaxe is about to snap, out of his own bags,
without being asked.

## Charm

`steward.charm: true` — he waves, greets you after time away, and glances at
what you are doing while idle. It is also the master switch for the remarks
below. `steward.celebrations` adds a short victory bop after a big milestone.

---

## Idle remarks

**Off by default**, deliberately. Switched on
(`steward.remarks.enabled: true`, with `charm` on too) he remarks on what he
can see while standing about: what you are carrying, how deep you have got,
that it has started to thunder.

Everything he says there is read off a plain getter. No model call, no event
log, no storage, so a feature that fires on a timer costs nothing to run, and
the lines are written rather than generated.

The filter is the point: 72 iron ore is worth a sentence, 41 cobblestone is
not, and 384 cobblestone is worth a different sentence entirely. Cooldowns are
keyed on the **subject** rather than the sentence, so he cannot come back four
minutes later with the same observation in new words.

```yaml
steward:
  remarks:
    enabled: false
    interval-seconds: 90          # how often he considers saying something
    quiet-seconds: 180            # minimum gap between any two
    subject-cooldown-seconds: 900 # per subject, not per phrasing
    max-distance: 10.0            # he does not comment on what he cannot see
    require-idle: true            # never mid-task
```

`/jarvis quiet` mutes him for you alone, with no config edit. That is the
setting most people want.

---

## Death drops

```
/jarvis recover
```

He travels to where you died, collects everything, and brings it back.
`steward.recovery.auto: true` has him go without being asked.

---

## Threat callouts

While guarding, he calls out hostiles outside your field of view — *"Creeper,
behind you, sir!"* — rather than silently killing them. Tuning is under
`defender:` (`defender.callouts` switches the callouts off). The `combat:`
section in the file is not read.
