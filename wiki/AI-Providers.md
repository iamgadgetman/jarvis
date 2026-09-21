# AI Providers

Jarvis speaks to five backends: **Ollama** (local, free), **Claude**,
**OpenAI**, **Grok** and **Gemini**. Configure as many as you like; he routes
between them and fails over when one is unavailable.

Nothing here needs the config file. `/jarvis bell` → **Admin → AI setup** does
all of it, and so does `/jarvis ai`.

---

## Setting one up

**From the menu.** Ring the bell, **Admin → AI setup**. A row of providers:
right-click toggles one on or off, left-click opens its page. There you set the
key or the address, choose the model — picked from what your Ollama server
actually offers, or typed — and run a test that goes past the routing to the
provider itself. A key typed here is captured from chat without being shown or
written to the chat log.

**From the console.**

```
/jarvis ai                          which providers are on, and their health
/jarvis ai enable claude
/jarvis ai key claude sk-ant-...
/jarvis ai endpoint ollama http://your-box:11434
/jarvis ai models                   what your Ollama server has pulled
/jarvis ai model ollama qwen2.5:7b
/jarvis ai test claude
```

Both take effect at once. No restart.

---

## Tiered routing

Jarvis sorts work into two tiers and sends them different ways:

| Tier | What it is | Goes to |
|---|---|---|
| **Light** | Chat intents, banter — frequent, small | **Ollama first**, cloud as fallback |
| **Heavy** | Build planning — rare, large | **Cloud first**, Ollama as fallback |

That is the point of the design: the thing you do a hundred times an hour is
free and local, and the thing you do twice an evening gets the better model.

```yaml
ai:
  provider: auto
  provider-priority: [ollama, claude, openai, grok, gemini]
  light-timeout-seconds: 5      # a slow local box falls through quickly
  heavy-timeout-seconds: 240    # a build runs to thousands of tokens
  # routing:
  #   light: [ollama, claude]
  #   heavy: [claude, ollama]
```

Setting `ai.provider` to a single provider name uses that one exclusively.

A provider that fails goes on an exponential cooldown up to five minutes, so a
box with no Ollama installed costs the next caller nothing. Providers with no
API key are skipped, Ollama excepted.

---

## Ollama, local and free

1. Install [Ollama](https://ollama.com/download) on any machine on your
   network.
2. Pull a model. Small instruction-following models handle chat parsing well:

```bash
ollama pull qwen2.5:7b
ollama pull mistral
ollama pull nomic-embed-text     # for experience memory; recommended
```

3. Point Jarvis at it: **Admin → AI setup → Ollama**, or
   `/jarvis ai endpoint ollama http://your-box:11434`.

```yaml
ai:
  ollama:
    endpoint: "http://localhost:11434"
    model: qwen2.5:7b
    keep-alive: "5m"
    timeout-seconds: 60
```

`keep-alive` holds the model in VRAM between requests, which is most of the
difference between a snappy butler and a thoughtful one.

### Running only Ollama

Fully supported, and Jarvis enters a **reduced mode**: constrained JSON
parsing that small models handle reliably, schematic-based building instead of
freeform AI designs on Paper, and risky console actions refused unless
`ai.reduced-mode.allow-risky-actions` is on.

---

## The cloud providers

Keys are set from the menu or with `/jarvis ai key <provider> <key>`.

**Claude** — [console.anthropic.com](https://console.anthropic.com)

```yaml
ai:
  claude:
    model: claude-haiku-4-5       # the sane default: cheapest, fastest
    # claude-sonnet-5             stronger, mid cost
    # claude-opus-5               most capable, most expensive
```

Model ids are complete as written; do not append a date.

**OpenAI** — [platform.openai.com](https://platform.openai.com/api-keys)

```yaml
ai:
  openai:
    model: gpt-5.6-terra          # balanced (default)
    # gpt-5.6-luna                cost-optimised, good for busy servers
    # gpt-5.6-sol                 flagship
```

**Grok** — [console.x.ai](https://console.x.ai) ·
**Gemini** — [aistudio.google.com](https://aistudio.google.com/app/apikey)

Because a server parsing every chat line makes a great many calls, the cheap
fast model is the right default everywhere. Reach for the big one only for
building.

---

## Checking it works

```
/jarvis ai            provider health, models, cooldowns
/jarvis ai test claude
/jarvis debug         provider, model, memory and subsystem status
```

`/jarvis ai test` sends one small request straight to that provider, past the
routing, so a failure tells you about the key rather than the fallback chain.

---

## Experience memory

He remembers builds that went well and retrieves them when you ask for
something similar, matching on what you asked *and* where you were standing.
With `nomic-embed-text` pulled on your Ollama box the matching is semantic;
without it, memory still works on a weaker keyword path.

```yaml
memory:
  enabled: true
  embedding-model: nomic-embed-text
```
