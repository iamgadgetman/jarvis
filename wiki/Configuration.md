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
| `schematics` | How build requests are matched against the library |
| `self-explain` | What he says when a task fails |
| `mining` | Search radius, block breaking, branch-mine, shaft and tunnel layout — see [Mining System](Mining-System) |
| `build` | The planner (`script` or `json`), its limits, and undo — see [Building System](Building-System) |
| `progression` | The service ladder — see [Progression](Progression) |
| `ui` | The task bar and the order queue (`task-bar`, `task-queue`) |
| `voice` | Listening and speaking — see [Voice](Voice) |
| `natural-language` | Chat parsing, prefix, cooldown |
| `databases` | Which database file to use |
| `defender` | Combat: engage radius, leash range, damage, callouts, archery |
| `steward` | Briefings, supply handoff, charm, idle remarks, death-drop recovery |
| `portals` | Portal sighting and memory |
| `farming`, `lighting` | Groundskeeping tuning |

The shipped file also carries `combat`, `features`, `performance`,
`config-copies`, `dev`, and a second `ui` block of colours and a message
prefix. Nothing in 0.17.0 reads them; changing them does nothing. Combat
tuning is under `defender`.

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
text, so your comments and layout survive. New keys added by an upgrade are
**not** written into your file: they take their defaults silently, and your
settings are kept. To change one, copy it in from the shipped `config.yml`.

---

## Where the data lives

| File | What it holds |
|---|---|
| `config.yml` | Everything above |
| `databases.yml` | Which database to use; rarely edited |
| `database.db` | SQLite: service records, experience memory, build and chat history |
| `duties.yml` | Standing duties |
| `data.yml` | Homes, deposit chests, portal sightings, patrol routes |
| `bells.txt` | Placed controller bells (Fabric and NeoForge only) |
| `models/` | Speech models, once voice has been turned on |
| `schematics/` | The schematic library, on every platform |

Player item requests are held in memory only and are lost on a restart.
