# FB-09 — Free-tier-first first run

**Lane D · Size S · Branch `fb/09-first-run` · Idea from:** Personal Helper Friend (146K
downloads, free Gemini/Groq keys), AIConstructor (Gemini free tier listed first, Ollama
no key), VoxelMind ("no API key" was the whole pitch). Download counts in the research
tracked closely with how little a new user had to pay or configure before Jarvis
answered once.

## Goal

A fresh install gets a working Jarvis within five minutes with zero spend: the plugin
detects what's available (Ollama on localhost? any key in env? Jarvis Cloud activated?),
tells the operator exactly what to do in the console and in-game, and ships with
Gemini's free tier and Ollama as the suggested first providers. No feature changes to
the AI itself. This is also where the owner's stated preference lands: the free
bring-your-own-key path is shown first, Jarvis Cloud second.

## Audit first

1. `Jarvis.java` / `JarvisCore.java` startup: what's logged when no provider is
   configured today; `ai/OllamaModels.java` for Ollama detection; `ai/CloudActivation.java`
   for how an activated Cloud key is detected.
2. `config.yml` header and `ai.providers` order; `README.md` quick start.
3. How `env:VAR` key references are resolved (grep `env:`).

## Implementation

- Startup check `ai/ProviderProbe.java`: for each provider, is a key present (config or
  env)? Is Ollama reachable at its endpoint and does it have a chat model pulled? Is a
  Jarvis Cloud key active? Log one block:
  ```
  [Jarvis] AI providers: ollama OK (mistral) · gemini OK · jarvis-cloud not activated · openai no key · claude no key
  ```
  If **none** usable, log a boxed message with the three fastest paths, in this order:
  1. `ollama pull mistral` (free, local) 2. a Gemini key from aistudio.google.com (free
  tier) → `GEMINI_API_KEY` env var or `ai.gemini.api-key` 3. `/jarvis cloud activate`
  (free trial, no key to manage).
- In-game: an op who joins while no provider works gets one chat line with the same
  three options and `/jarvis setup` to re-run the probe and print status.
- `/jarvis setup` (op): runs the probe, prints the status block, and for Ollama offers
  `/jarvis setup pull <model>` which shells out nothing — it just prints the exact
  command to run on the host.
- Config defaults: move `gemini` and `ollama` to the top of the auto-mode priority
  list; add `# Free options first` comments; keep everything else as is. Do not change
  where Cloud activation inserts itself (it puts Claude-via-Cloud first on activation,
  by design).
- README quick start: rewrite the first 10 lines around "zero-cost start", BYOK first,
  Cloud trial second.

## Files

`ai/ProviderProbe.java` (new), `JarvisCore.java` or `Jarvis.java` (startup hook),
`commands/JarvisCommands.java` (`/jarvis setup`), `config.yml`, `README.md`,
`docs/configuration.md`, `CHANGELOG.md`.

## Unit tests

`ai/ProviderProbeTest.java`:
- No keys, Ollama unreachable, no Cloud key → `usable()` empty, message lists the three
  paths in order.
- `GEMINI_API_KEY` set (inject env map, don't read real env) → gemini usable.
- Ollama reachable but no models → reported as "running, no model pulled".
- Cloud key present → "jarvis-cloud OK (<plan>)".
- Status line format stable (snapshot string) so the console block doesn't drift.

## In-game test (owner)

1. Fresh `plugins/Jarvis/` (move the old folder aside), no env keys, Ollama stopped.
   Start server → console shows the boxed "no provider" message with three options.
2. Join as op → one chat line with the same options.
3. `ollama serve` + `ollama pull mistral`, then `/jarvis setup` → "ollama OK (mistral)".
   Say `hello` → Jarvis answers.
4. Export `GEMINI_API_KEY`, restart → status shows gemini OK and auto mode uses it for
   heavy work.
5. `/jarvis cloud activate`, finish in the browser → `/jarvis setup` shows jarvis-cloud OK.
6. Restore the real config folder; confirm startup log is one tidy status line.

## CHANGELOG line

`### Added` — **Five-minute free start.** On boot Jarvis reports which AI providers are
usable and, if none are, prints the three fastest ways to get one (Ollama, a free
Gemini key, the Jarvis Cloud trial). `/jarvis setup` re-checks. Free providers now lead
the default priority list.


## Audit findings (2026-11-11)

- No startup check existed: with no provider, Jarvis logged nothing about AI at boot.
- **`env:VAR` keys were not implemented.** The draft `wiki/Getting-Started.md` and the project
  guide both say `api-key: "env:GEMINI_API_KEY"` works; nothing resolved it. FB-09 adds
  `ai/ApiKeys.java`: `env:NAME` reads that variable, and a blank key reads the provider's usual
  variable (`GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `XAI_API_KEY`) unless the new
  `ai.env-keys: false`. The implicit read can spend money if those variables are set for something
  else, hence the switch and the "(key from GEMINI_API_KEY)" in the status line.
- Jarvis Cloud is detected by a `gc_` key on the claude provider (`CloudActivation.isActive`).
  The status line says "jarvis-cloud OK" without the plan: the plan needs a network call, and
  `/jarvis cloud status` already shows it.
- `ollama` was already first in the default priority; `gemini` moves from last to second
  (`config.yml` and `AIConnector.KNOWN_PROVIDERS`, which also seeds the hard-coded default list).
  Existing installs keep their own `provider-priority`.
- The spec's `commands/JarvisCommands.java` is `commands/CommandService.java` in this tree.

## Built

- `ai/ApiKeys.java`, `ai/ProviderProbe.java` (status per provider, status line, no-provider box,
  op join line), `JarvisCore.probeProviders` (startup, `/jarvis reload`, `/jarvis setup`), op join
  line when nothing is usable, `/jarvis setup` and `/jarvis setup pull [model]` (admin, console, or
  singleplayer), help and docs.
- Tests: `ProviderProbeTest` (9). `Fixture` now gives the connector an empty environment so no
  test reads real keys. `AiSettingsTest` follows the new default order.

## Results

Run 2026-11-11 on GadgetCraft, jar `b0246d98` (a test build of FB-09 + FB-15 + FB-16), with
callmegadget. GadgetCraft's real providers were left alone (it runs Jarvis Cloud then Ollama, and
other people use that Ollama box), so the "nothing usable" steps were not run live; the unit tests
cover them.

| # | Result | Notes |
|---|---|---|
| 1 | not run | Needs a fresh config and no providers. `ProviderProbeTest.nothingUsable` covers the box. |
| 2 | not run | Same; the op join line only shows when nothing is usable. |
| 3 | PASS (adapted) | Startup log: "AI providers: jarvis-cloud OK · ollama OK (qwen2.5:7b) · openai no key · grok no key · gemini no key". `/jarvis setup` listed the same with ✔/✘ and "Ready to talk, sir."; `/jarvis setup pull` printed `ollama pull qwen2.5:7b`. "hello, how are you today?" got a normal reply. |
| 4 | not run | No Gemini key here. `ProviderProbeTest.geminiFromEnv` and `connectorReadsEnv` cover it. |
| 5 | PASS (adapted) | Cloud already active: shown as "jarvis-cloud OK". |
| 6 | PASS | The startup log has the one status line. |

Steps 1, 2 and 4 want a throwaway server (or Chance's say-so to switch providers off on
GadgetCraft for a few minutes).
