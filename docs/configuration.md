# Configuration

*[← README](../README.md) · [Features](features.md) · [Commands](commands.md) · [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md)*

---

```yaml
# plugins/Jarvis/config.yml

ai:
  provider: auto            # openai | claude | grok | gemini | ollama | auto
  provider-priority:        # auto-failover order; free first: ollama (local), gemini (free tier)
    - ollama
    - gemini
    - claude
    - openai
    - grok
  env-keys: true            # blank api-key reads GEMINI_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY / XAI_API_KEY (0.18.1)
  gemini:
    api-key: "env:GEMINI_API_KEY"   # a key, or env:NAME to read an environment variable
    model: gemini-3.7-flash
  claude:
    api-key: ""
    model: claude-haiku-4-5
  openai:
    api-key: ""
    model: gpt-5.6-terra
  ollama:
    endpoint: "http://localhost:11434"
    model: mistral
  context:                  # what each chat request knows about you (0.18.1)
    level: 2                # 0 none · 1 you · 2 + world and Jarvis · 3 + nearby mobs/players
    hostile-radius: 16
    player-radius: 32
    knowledge-file: knowledge.yml   # server facts, in plugins/Jarvis/
  streaming:                # replies appear sentence by sentence (0.18.1)
    enabled: true
    min-interval-ms: 400    # floor between chat lines
    providers: [openai, claude, gemini, grok, ollama]

build:
  guardrails:               # checked on every AI build before anything is placed (0.18.1)
    blocked-blocks: [tnt, lava, lava_bucket, command_block, chain_command_block,
                     repeating_command_block, structure_block, barrier, bedrock,
                     end_portal_frame, spawner, fire]
    max-blocks: 8000
    max-size: 48            # longest edge of the bounding box
    max-distance: 64        # player to the nearest edge of the build
    preview-seconds: 20     # particle outline + yes/no; 0 = build at once
  limits:                   # per permission group; only finished builds count (0.18.1)
    default: { cooldown-seconds: 120, daily-builds: 5, daily-blocks: 6000 }
    trusted: { cooldown-seconds: 30, daily-builds: 30, daily-blocks: 60000 }
    # jarvis.build.limit.trusted puts a player in "trusted"; jarvis.build.bypass (op) skips all limits

memory:                     # experience memory (0.8.0)
  enabled: true
  conversation:             # what he remembers of each player (0.18.1)
    enabled: true
    recent-turns: 8         # verbatim turns per request (3 on Ollama-only servers)
    summarize-after: 30     # unsummarized turns before the light model condenses them
    keep-days: 90           # summarized raw turns older than this are pruned (0 = keep)
    record-events: true     # deaths and portal trips become memory lines
  embedding-model: "nomic-embed-text"   # ollama pull nomic-embed-text
  max-examples-in-prompt: 3
  # Ollama-only servers unlock freeform builds after this many successes
  min-successes-for-reduced-mode-builds: 20
  # Undoing a build within this window marks its plan as failed
  negative-signal-window-minutes: 10

npc:
  name: "Jarvis"
  skin: "Jarvis"            # player name whose skin to use
  follow-distance: 3.0
  max-distance: 30.0

mining:
  search-radius: 20
  max-ores: 10
  tool: DIAMOND_PICKAXE

natural-language:
  enabled: true
  prefix: "jarvis"
  require-prefix: false     # if true, chat must start with "jarvis"

butler:
  auto-greet: true
  death-commentary: true
  tps-warn-threshold: 18.0  # warn admins if TPS drops below this

confirmation:
  timeout-seconds: 30       # how long dangerous action prompts stay active

discord:
  webhook-url: ""           # optional — for discord_broadcast action

permissions:
  admin-permission: "jarvis.admin"
```

### Server facts: knowledge.yml

`plugins/Jarvis/knowledge.yml` (written on first start, all commented out)
holds facts and places Jarvis treats as true. Every chat request includes it,
so keep it short.

```yaml
facts:
  - "PvP is off"
  - "Spawn is at 0, 64, 0"
locations:
  market: {x: 120, y: 70, z: -40, note: "player shops"}
  farm: "east of spawn, past the river"
```

Ask `jarvis where is the market?` and he quotes the coordinates. Run
`/jarvis reload` after editing. A missing or broken file just means no facts
(a broken one logs a warning).

### Getting an AI provider (free first)

On startup Jarvis logs one line saying which providers can answer, e.g.
`AI providers: ollama OK (mistral) · gemini no key · jarvis-cloud not activated · …`.
If none can, it prints the three quickest free ways to get one, and an op who
joins gets the same in chat. `/jarvis setup` checks again at any time.

| Provider | Cost to start | Where to get a key | Environment variable |
|---|---|---|---|
| **Ollama** | free, local | none: install [ollama.com](https://ollama.com), `ollama pull mistral` (`/jarvis setup pull` prints the command) | n/a |
| **Google Gemini** | free tier | [aistudio.google.com](https://aistudio.google.com/) | `GEMINI_API_KEY` |
| **Jarvis Cloud** | free trial | none: `/jarvis cloud activate` | n/a |
| **Claude** | paid | [console.anthropic.com](https://console.anthropic.com/) → API Keys (`sk-ant-...`) | `ANTHROPIC_API_KEY` |
| **OpenAI** | paid | [platform.openai.com](https://platform.openai.com/) → API Keys (`sk-...`) | `OPENAI_API_KEY` |
| **xAI Grok** | paid | [x.ai](https://x.ai/) | `XAI_API_KEY` |

A key goes in `ai.<provider>.api-key`, either as the key itself or as
`"env:NAME"` to read an environment variable. With `ai.env-keys: true` (the
default) a blank `api-key` reads the variable in the table, so exporting
`GEMINI_API_KEY` before starting the server is enough. In `auto` mode Jarvis
falls through `provider-priority` when one fails.

Ollama keeps everything on your own hardware, at the cost of slower and less
accurate answers than the cloud models.

---|---|---|
| **Claude** (recommended) | [console.anthropic.com](https://console.anthropic.com/) → API Keys | `sk-ant-...` |
| **OpenAI** | [platform.openai.com](https://platform.openai.com/) → API Keys | `sk-...` |
| **xAI Grok** | [x.ai](https://x.ai/) → request API access | — |
| **Google Gemini** | [aistudio.google.com](https://aistudio.google.com/) | — |
| **Ollama** | none — runs locally, see [ollama.ai](https://ollama.ai) | n/a |

Ollama needs no key and no account: install it, `ollama pull mistral`, and point
`ollama-url` at it. It is the cheapest option and keeps everything on your own
hardware, at the cost of slower and less accurate responses.

---

### Reward bundles

`bundles:` holds named lists of console commands that you write. Jarvis's model
sees each bundle's name and description and can ask for one (`trigger_bundle`)
when what a player says fits, say "I just found my first diamond!". It can only
pick a name; the commands are yours. `/jarvis bundle run <name> [player]` runs one
by hand.

```yaml
bundles:
  first-diamond:
    description: "Celebrate a player's first diamond"
    once-per-player: true
    commands:
      - "give {player} minecraft:cooked_beef 8"
      - "playsound minecraft:ui.toast.challenge_complete master {player}"
  shrine-blessing:
    description: "Reward for reaching the hidden shrine"
    cooldown-minutes: 1440
    commands: ["effect give {player} minecraft:regeneration 60 1"]
```

Placeholders: `{player}`, `{uuid}`, `{x}` `{y}` `{z}` (block position), `{world}`,
plus PlaceholderAPI's `%…%` when it's installed. `ai: false` keeps a bundle out of
the model's reach (admins only); `permission: some.node` limits who Jarvis gives it
to. Players can claim anything in chat, so AI bundles should be fun rewards with
`once-per-player` or a cooldown. Bundles work in reduced (Ollama-only) mode: the
model picks from your list and can't do more. Every trigger, given or refused, is
logged as `[bundle] <name> for <player> by ai|admin`.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `jarvis.use` | Basic Jarvis commands | `true` |
| `jarvis.admin` | Admin-only commands and actions | `op` |
