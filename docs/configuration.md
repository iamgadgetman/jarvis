# Jarvis Configuration

See also: [README](../README.md) • [Troubleshooting](troubleshooting.md)

## Installation

### Requirements
- Paper / Purpur 1.21+ (or any fork with Bukkit API)
- Java 17+
- [Citizens 2 on spigot](https://www.spigotmc.org/resources/citizens.13811/) or [Citizens 2 Jenkins build](https://ci.citizensnpcs.co/job/citizens2/)
- [WorldEdit](https://enginehub.org/worldedit/) (optional — for schematic pasting)
- At least one AI API key (or local Ollama)
- An embedding model for experience memory (optional but recommended):
  `ollama pull nomic-embed-text` — without it memory still works, but on the
  weaker keyword-matching path

### Steps
1. Download **`Jarvis-<version>.jar`** from the
   [latest release](https://github.com/iamgadgetman/jarvis/releases/latest)
   and drop it into your `plugins/` folder
2. Drop `Citizens.jar` and `WorldEdit.jar` into `plugins/` (if not already present)
3. Start the server — Jarvis will generate `plugins/Jarvis/config.yml`
4. Add your AI API key(s) to `config.yml` (see below)
5. Restart or `/reload confirm`

### First run

```text
/jarvis bell        # get the controller bell
/jarvis summon      # summon Jarvis
/jarvis debug       # provider, model and experience-memory status
/jarvis loot        # see what he is carrying
```

Then just talk to him in chat: `jarvis start mining`.

## Reference `config.yml`

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

## Getting an AI API key

Pick one provider and put its key in `config.yml`. Jarvis will fall back through
the others in `ai.provider-priority` if the primary fails.

| Provider | Where to get a key | Key looks like |
|---|---|---|
| **Claude** (recommended) | [console.anthropic.com](https://console.anthropic.com/) → API Keys | `sk-ant-...` |
| **OpenAI** | [platform.openai.com](https://platform.openai.com/) → API Keys | `sk-...` |
| **xAI Grok** | [x.ai](https://x.ai/) → request API access | — |
| **Google Gemini** | [aistudio.google.com](https://aistudio.google.com/) | — |
| **Ollama** | none — runs locally, see [ollama.ai](https://ollama.ai) | n/a |

Ollama needs no key and no account: install it, `ollama pull mistral`, and point
`ollama.endpoint` at it. It is the cheapest option and keeps everything on your own
hardware, at the cost of slower and less accurate responses.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `jarvis.use` | Basic Jarvis commands | `true` |
| `jarvis.admin` | Admin-only commands and actions | `op` |
