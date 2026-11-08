# Testing a feature block

Two layers, every block: unit tests the agent runs, and an in-game script the owner runs.
A block is `built` when the first is green and `tested` when the second is.

## Layer 1 — unit tests (agent)

```bash
cd jarvis-core && mvn -q -o test            # whole suite, offline, ~238 tests today
cd jarvis-core && mvn -q -o test -Dtest=FastPathParserTest
```

Rules:
- Each block's spec names its test classes. Write them from the **spec**, not from the
  implementation — the `test-engineer` subagent does this on purpose with the spec and
  the public method signatures only.
- Use the fakes in `core/testing/` (`Fixture`, `FakePlatform`, `FakeWorld`, `FakeOwner`,
  `FakeScheduler`, `FakeUi`). Anything that needs SQLite uses `jdbc:sqlite::memory:`.
  Anything that needs a clock takes one as a parameter.
- No network. Provider HTTP paths are tested through a pure parsing/assembling class
  (`StreamAssembler`, `ToolCallParser`), never through a live request.
- A test that reproduces an in-game failure report is added **before** the fix and kept.

## Layer 2 — in-game (owner)

Build, deploy, run the block's numbered **In-game test** steps, report.

```bash
cd ~/apps/claude/jarvis-dev
git checkout fb/NN-short-name
mvn -q -o package -pl jarvis-paper -am -DskipTests
sha256sum jarvis-paper/target/jarvis-paper-*.jar       # note it; it goes in the report
# back up the live jar, copy the new one to plugins/update/, then:
python3 ~/apps/claude/jarvis/build-fixtures/amp_restart.py
```

Useful console settings while testing: `ai.log-usage: true` (shows every AI call with
tier, provider and tokens — the quickest way to prove a fast-path hit made no call),
and a slow local model when a block is about latency.

### Reporting

One message per test run, this shape, so the agent can act without asking:

```
FB-01 test — jar 0a411d9… — Paper 1.21.8, Purpur
1 PASS
2 PASS
3 FAIL — "mine 10 iron": Jarvis said "I'll get right on it" but console shows
         AI usage [LIGHT/ollama] in=612 out=40; expected no AI call
4 PASS
5 SKIP — reload didn't pick up the key change; needed a restart (note for docs)
6 FAIL — with no providers, "follow me" → "I can't reach my AI right now"
```

Paste console lines verbatim for a FAIL. The agent fixes on the same branch, re-runs
layer 1, and hands back a new jar hash. Repeat until all PASS or SKIP-with-reason, then
flip the block to `tested` in `README.md`.

### Driving tests from Claude Code (after FB-10)

Once `tools/jarvis-mcp` exists, the agent can run most steps itself over RCON: summon,
say a line as the player, tail the log for the `AI usage` line or its absence. The owner
still does anything that needs eyes (particles, animation, chat pacing) and anything
destructive. `headlessmc` (a Minecraft skill the owner wants to try) may later let a
session join as a client and look at builds directly.

## Mods

Blocks are written for Paper first. A block that touches `core/platform/` must keep
`jarvis-fabric` and `jarvis-neoforge` compiling (`./gradlew build` in each) with stub
implementations; full mod behavior is a follow-up unless the spec says otherwise.
