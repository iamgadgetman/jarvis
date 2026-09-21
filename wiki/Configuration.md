# Configuration

The config lives at `plugins/Jarvis/config.yml` on Paper and
`config/jarvis/config.yml` on Fabric and NeoForge. It is written on first run,
fully commented, and the comments explain the reasoning as well as the values.
**That file is the reference**; this page is the map.

Most of it never needs touching. The AI and voice sections are editable from
the bell menu and the console, and writes from there keep the file's comments.
`/jarvis reload` re-reads it without a restart.

---

## The sections

| Section | What is in it |
|---|---|
| `ai` | Providers, keys, models, tiered routing, timeouts — see [AI Providers](AI-Providers) |
| `memory` | Experience memory and the embedding model |
| `schematics` | The library folder and matching, Paper only |
| `self-explain` | What he says when a task fails |
| `mining` | Ore priority, vein mining, hazards, branch-mine layout |
| `combat` | Stances, leash range, callouts |
| `build` | Freeform building limits and undo |
| `progression` | The service ladder — see [Progression](Progression) |
| `ui` | The bell, menus, chat formatting |
| `voice` | Listening and speaking — see [Voice](Voice) |
| `natural-language` | Chat parsing, prefix, cooldown |
| `databases` | Which database file to use |
| `features` | Feature switches for whole subsystems |
| `performance` | Caching, tick budgets |
| `defender` | Threat response |
| `steward` | Briefings and standing duties |
| `portals` | Portal sighting and memory |
| `farming`, `lighting` | Groundskeeping tuning |

---

## The ones worth knowing

**Natural language.** On a busy server, make him answer only when addressed:

```yaml
natural-language:
  enabled: true
  prefix: "jarvis"
  require-prefix: false     # true on a 50+ player server
  cooldown-ms: 2000
```

**Features.** Whole subsystems, off in one line:

```yaml
features:
  npc-system: true
  building-system: true
  schematic-system: true
  voice-commands: false
```

**Reduced mode.** On an Ollama-only server, risky console and permission
actions are refused unless you opt in:

```yaml
ai:
  reduced-mode:
    allow-risky-actions: false
```

**Token logging.** To see what builds actually cost:

```yaml
ai:
  log-usage: true
```

which prints a line per call, tagged with tier and provider:

```
AI usage [HEAVY/claude] in=1834 out=7074 cache_write=0 cache_read=0
```

---

## Editing it

Three ways, and they agree:

1. **The bell menu.** Admin → AI setup or Voice setup. No restart.
2. **The console.** `/jarvis ai ...` and `/jarvis voice ...`. No restart.
3. **The file.** Edit, then `/jarvis reload`.

A value changed from the menu or the console is written into the existing
text, so your comments and layout survive. New keys added by an upgrade appear
with their own comments; your settings are kept.

---

## Where the data lives

| File | What it holds |
|---|---|
| `config.yml` | Everything above |
| `databases.yml` | Which database to use; rarely edited |
| `jarvis.db` | Service records, memory, duties, requests |
| `data.yml` | Homes, deposit chests, portal sightings, patrol routes |
| `models/` | Speech models, once voice has been turned on |
| `schematics/` | The library, Paper only |
