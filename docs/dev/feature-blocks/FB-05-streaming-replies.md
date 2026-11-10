# FB-05 — Streaming replies

**Lane B · Size M · Branch `fb/05-streaming-replies` · Depends on:** FB-03 merged (same
file: `AIConnector`). **Idea from:** AI_NPC (replies appear sentence by sentence as the
model types).

## Goal

Jarvis's spoken replies appear in chat sentence by sentence while the model is still
generating, instead of after the whole response. Perceived latency drops a lot on slow
local models. The JSON *action* part of a response is still parsed only once complete.

SSE = Server-Sent Events, the line-by-line `data: {...}` format OpenAI, Anthropic and
Gemini use for streaming; Ollama uses NDJSON (one JSON object per line). Both are
"read lines until done".

## Audit first

1. `AIConnector.java`: `payload.put("stream", false)` and the `HttpURLConnection`
   request path. Note how each provider's response is parsed and how the action JSON is
   separated from the spoken text. Note how the Jarvis Cloud provider (`gc_` keys,
   `x-jarvis-server` header) rides the same path — streaming must keep that header.
2. `IntentPipeline.Responder`: `speak(player, line)` is one-shot today.
3. Check how the reply format is prompted: does the model return `{"say": "...",
   "action": {...}}` or text + JSON? Streaming the `say` field needs the format to put
   spoken text first or in a separable field.

## Implementation

- Prompt format: require `SAY: <text>` lines first, then a fenced `ACTION` JSON block
  (or keep the current JSON but with `say` as the first key — pick whichever the audit
  shows is less disruptive; document the choice in the commit).
- `AIConnector`: add a streaming path per provider (`stream: true`), reading the
  connection line by line on the async thread, accumulating text. Emit a
  `Chunk(String text)` callback each time a sentence boundary (`.`, `!`, `?`, newline)
  lands inside the SAY part. Stop emitting once the ACTION block begins. On completion,
  parse the action as today. Fallback: if a provider's stream errors mid-way, retry once
  non-streaming (existing path), so streaming can never lose a request.
- `IntentPipeline.Responder`: add `default void speakPartial(Owner, String sentence)`
  that calls `speak`. Chat responder prints each sentence as its own chat line prefixed
  "Jarvis:". Voice responder (jarvis-voice) can speak sentence by sentence — leave it
  defaulting to buffered speech unless trivial.
- Guard: minimum gap between partial lines (`min-interval-ms`) so a fast cloud model
  doesn't spam 6 lines in 200 ms; buffer and flush.
- Config:
  ```yaml
  ai:
    streaming:
      enabled: true
      min-interval-ms: 400   # Floor between chat lines so fast models don't spam
      providers: [openai, claude, gemini, grok, ollama]  # Remove one to force buffered mode
  ```

## Files

`ai/AIConnector.java`, `ai/StreamAssembler.java` (new, pure), `intent/IntentPipeline.java`
(Responder), `config.yml`, `CHANGELOG.md`. Possibly `ai/AiSettings.java`.

## Unit tests

`ai/StreamAssemblerTest.java` (pure class, no HTTP):
- Feed OpenAI-style `data:` chunks spelling "Hello there. Let me check. ACTION {…}" →
  partials ["Hello there.", "Let me check."], final action parsed.
- Ollama NDJSON chunks → same result.
- Sentence split across two chunks → emitted once, whole.
- Text after ACTION begins → never emitted as speech.
- Malformed trailing JSON → action empty, speech still delivered, no exception.
`IntentPipelineTest`: streaming on → `speakPartial` called N times then no duplicate
final `speak`; streaming off → single `speak`.

## In-game test (owner)

1. Ollama (slow): say `tell me a short story about a creeper`. Expect sentences appearing
   one at a time, action (if any) executing after the last.
2. Cloud provider: same; lines arrive at ≥ 400 ms spacing, nothing duplicated.
3. Ask for an action with speech: `follow me and tell me a joke` → joke streams, follow
   starts when the reply completes.
4. Stop Ollama mid-reply: Jarvis finishes via the non-streaming retry or reports the
   failure; no half-printed garbage lines stay.
5. `enabled: false` → one reply block as before.
6. Jarvis Cloud provider active: `/jarvis cloud status` still counts the request once.

## As built (deviations)

- **Format kept as JSON.** The reply stays `{"action","parameters","response"}` and only the
  `response` string is streamed. A `SAY:`/`ACTION` format would have meant dropping Ollama's
  `format: json`, which is what keeps qwen2.5:7b's output valid. The prompt already puts
  `action` and `parameters` before `response`, so the action is known before any words arrive.
- **Guarded replies aren't streamed.** A reply is streamed only if `action` was complete before
  `response` began and `IntentPipeline.unaskedAction` would not rewrite it. An unasked
  broadcast, status report or teleport is still shown once, whole, after the guard runs.
- **Jarvis Cloud is never streamed.** The Worker forces `stream: false` so it can meter the
  final usage block; a `gc_` key always takes the buffered path, so step 6 counts once by design.
- **Voice stays buffered.** `Responder.streams()` defaults to false; only the chat responder
  opts in. Voice speaks the whole reply as before.
- **Failure:** only a stream that breaks after the provider accepted it (dropped connection,
  no end marker, an error event mid-stream) is retried once without streaming on the same
  provider. A refused request (429, 5xx), a connect failure or a timeout goes straight to the
  next provider, as before, so nothing is sent twice. If the whole `response` had already
  arrived, the retry is used for its action only and nothing is said twice; if only part had,
  the retry's reply is shown whole after it. A stream is also cut off once the whole reply
  takes longer than the tier timeout. `unknown` is never streamed, so the keyword fallback
  still answers it.
- Only chat intent parsing streams; builds and other calls are unchanged.
- `ai.log-usage` reports streamed Anthropic and Ollama token counts, tagged "(streamed)".

## Results

| Step | Result |
|---|---|
| 1–6 | Not run yet. |

## CHANGELOG line

`### Changed` — **Replies stream.** Jarvis's sentences appear as the model writes them
instead of all at once; actions still wait for the full reply. `ai.streaming` to tune
or disable.
