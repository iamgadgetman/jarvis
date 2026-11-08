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
