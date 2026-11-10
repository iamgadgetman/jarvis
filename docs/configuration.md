# Configuration

*[← README](../README.md) · [Features](features.md) · [Commands](commands.md) · [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md)*

---

```yaml
# plugins/Jarvis/config.yml

ai:
  provider: auto            # openai | claude | grok | gemini | ollama | auto
  provider-priority:        # auto-failover order; ollama first is free and local
    - ollama
    - claude
    - openai
    - grok
    - gemini
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

memory:                     # experience memory (0.8.0)
  enabled: true
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

### Getting an AI API key

Pick one provider and put its key in `config.yml`. Jarvis will fall back through
the others in `fallback-order` if the primary fails.

| Provider | Where to get a key | Key looks like |
|---|---|---|
| **Claude** (recommended) | [console.anthropic.com](https://console.anthropic.com/) → API Keys | `sk-ant-...` |
| **OpenAI** | [platform.openai.com](https://platform.openai.com/) → API Keys | `sk-...` |
| **xAI Grok** | [x.ai](https://x.ai/) → request API access | — |
| **Google Gemini** | [aistudio.google.com](https://aistudio.google.com/) | — |
| **Ollama** | none — runs locally, see [ollama.ai](https://ollama.ai) | n/a |

Ollama needs no key and no account: install it, `ollama pull mistral`, and point
`ollama-url` at it. It is the cheapest option and keeps everything on your own
hardware, at the cost of slower and less accurate responses.

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `jarvis.use` | Basic Jarvis commands | `true` |
| `jarvis.admin` | Admin-only commands and actions | `op` |
