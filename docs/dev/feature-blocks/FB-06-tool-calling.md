# FB-06 — Native tool calling for actions

**Lane B · Size M · Branch `fb/06-tool-calling` · Depends on:** FB-05 merged. **Idea from:**
AI_NPC (function calling: give items, sell, start quests, pay rewards).

## Goal

Where a provider supports it (OpenAI `tools`, Anthropic `tools`/`tool_use`, Gemini
`functionDeclarations`), Jarvis sends his action vocabulary as **typed tool
definitions** instead of a text list in the system prompt, and reads the action back
from the provider's structured tool-call field instead of parsing JSON out of prose.
Result: far fewer malformed actions, correct parameter types, multiple actions in one
reply, and a shorter prompt. Ollama and models without tool support keep the current
text-JSON path untouched.

## Audit first

1. `AIConnector.java`: the action list string and the per-provider request builders.
   Note how parameters are documented today (free text like `params: {type:
   clear|rain|thunder}`). Confirm Jarvis Cloud proxies Anthropic's API unchanged, so
   `tool_use` passes through.
2. `commands/ActionExecutor.java`: every action name and the parameters it reads. This
   is the source of truth for the tool schemas.
3. `IntentPipeline.RESTRICTED_IN_REDUCED_MODE` — those must not be offered as tools in
   reduced mode either.

## Implementation

- New `ai/ActionSchema.java`: one place that declares each action's name, description,
  and JSON-schema parameters (`enum` for weather types, `integer` with min/max for
  counts, etc.). Generate **both** the legacy text list and the provider tool arrays from
  it, so the two paths can't drift. Mark actions `risky: true` to exclude in reduced mode.
- `AIConnector`: per provider, if `ai.tools.enabled` and the provider is in
  `ai.tools.providers`, attach tool definitions and parse the structured response
  (OpenAI `tool_calls[]`, Anthropic `content[].type == "tool_use"`, Gemini
  `functionCall`). Map each to the existing action JSON so `ActionExecutor` is
  unchanged. Multiple tool calls → execute in order (cap at 3 per reply).
- Streaming (FB-05) interplay: spoken text still streams; tool calls are collected and
  executed at the end.
- Unknown tool name or schema-invalid args from the model → log at debug, tell the
  player Jarvis "didn't quite catch that", never throw.
- Config:
  ```yaml
  ai:
    tools:
      enabled: true
      providers: [openai, claude, gemini]   # Grok if its API proves compatible; Ollama stays on text JSON
      max-actions-per-reply: 3
  ```

## Files

`ai/ActionSchema.java` (new), `ai/ToolCallParser.java` (new, pure), `ai/AIConnector.java`,
`config.yml`, `CHANGELOG.md`, `docs/configuration.md`.

## Unit tests

`ai/ActionSchemaTest.java`:
- Every action name in `ActionExecutor` (reflect or a shared constant list) has a schema;
  no schema names an action that doesn't exist.
- Reduced mode excludes risky actions from the tool array; full mode includes them.
- Generated legacy text list contains every action name (keeps the Ollama path honest).
`ai/ToolCallParserTest.java`:
- OpenAI `tool_calls` with two calls → two actions in order, args typed (int stays int).
- Anthropic `tool_use` block + text block → action + speech both captured.
- Gemini `functionCall` → action.
- Four calls with cap 3 → three executed, one dropped with a log line.
- Bad args (string where int expected) → action rejected, speech preserved.

## In-game test (owner)

1. Claude provider: say `set the weather to clear and give me a bread`. Expect both
   happen, in that order, one reply. Console (`log-usage`) shows a smaller prompt than
   before the block.
2. OpenAI and Gemini: repeat step 1.
3. Ollama: repeat step 1 — still works via the text path; console shows no tool usage.
4. Reduced mode (only Ollama configured): say `run /op me` → refused as before.
5. Say something with no action: `how are you?` → plain speech, no tool call.
6. Jarvis Cloud provider: repeat step 1.

## CHANGELOG line

`### Changed` — **Actions arrive as tool calls.** On OpenAI, Claude and Gemini, Jarvis's
actions are declared as typed tools and read back structurally, so parameters come
through correct and several actions can ride one reply. Ollama keeps the text format.
`ai.tools` in config.
