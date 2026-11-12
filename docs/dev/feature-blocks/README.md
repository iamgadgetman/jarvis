# Feature blocks

The AI-landscape research (Nov 2026) produced 18 ideas. Jarvis already has eight of
them: the brain/body split, async pipeline, provider failover with cooldowns, keyword
fallback, light/heavy model tiers, one undo for every build (`UndoService`, 0.18.0),
WorldGuard respect and build-experience memory. This folder holds the ten that remain,
cut into blocks that one Claude Code agent can finish, one unit test suite can cover,
and one in-game session can verify.

**Start these after 0.18.0 is cut** (ladder-hatch fix, release checklist in
`~/apps/claude/jarvis/Jarvis 0.18.0 Fixes.md`). Until then, branch blocks off
`release/0.18.0` and rebase when it lands on `main`. Commits stay local until the owner
says push.

## The blocks

| Block | Name | Lane | Depends on | Size | Status |
|---|---|---|---|---|---|
| [FB-01](FB-01-parser-first.md) | Parser-first fast path | A | — | S | tested |
| [FB-02](FB-02-thinking-feedback.md) | "Thinking" feedback while the model works | A | — | S | tested |
| [FB-03](FB-03-world-context.md) | Rich world context in every prompt | B | — | S | tested |
| [FB-04](FB-04-conversation-memory.md) | Per-player conversation memory | C | — | M | planned |
| [FB-05](FB-05-streaming-replies.md) | Streaming replies | B | FB-03 merged | M | tested |
| [FB-06](FB-06-tool-calling.md) | Native tool calling for actions | B | FB-05 merged | M | planned |
| [FB-07](FB-07-build-guardrails.md) | Build guardrails: blocked blocks, limits, preview | D | — | M | built |
| [FB-08](FB-08-reward-tags.md) | Reward tags → admin-defined command bundles | D | FB-07 merged | S | planned |
| [FB-09](FB-09-first-run.md) | Free-tier-first first run | D | — | S | planned |
| [FB-10](FB-10-mcp-dev-server.md) | Jarvis MCP server for development | E | — | M | planned |
| [FB-11](FB-11-onboarding.md) | First-join welcome, interactive tutorial, admin quick start | F | FB-09 merged | M | planned |
| [FB-12](FB-12-build-polish.md) | Build polish from the GadgetCraft playtests | B + D | — | S | tested |
| [FB-13](FB-13-empty-replies.md) | Guards for empty model replies | A | — | S | tested |
| [FB-14](FB-14-unasked-gifts.md) | No unasked items, heals or food | A | — | S | tested |

Size: S = an afternoon, M = a day or two. Status values: `planned`, `in progress`,
`built` (branch ready, unit tests green), `tested` (owner verified in-game), `shipped`.

## Lanes: how to run more than one agent

Blocks in different lanes touch different files, so they can run at the same time
without merge conflicts. Blocks in the same lane run in order.

```
Lane A  intent/ + npc/ButlerService ......... FB-01 → FB-02
Lane B  ai/AIConnector + prompts ............ FB-03 → FB-05 → FB-06
Lane C  memory/ + DatabaseManager ........... FB-04
Lane D  building/ + commands/ + config.yml .. FB-07 → FB-08, FB-09
Lane E  tools/ (new, no Java) ............... FB-10
Lane F  onboarding/ (new) + steward/ + wiki . FB-11 (after FB-09)
```

Three ways to split the work, from simplest to most parallel:

1. **One agent, one block at a time.** Open Claude Code in the repo and run
   `/feature-block FB-01`. Test it, mark it, move on. Zero coordination cost. Start here.
2. **Subagents inside one session.** `.claude/agents/` defines three roles:
   `feature-implementer` (writes the code), `test-engineer` (writes and runs the tests
   against the spec, not against the implementer's code), and `code-reviewer` (checks
   the diff against the spec and the Don'ts in `CLAUDE.md`). The `/feature-block` skill
   invokes them in that order so the code is never grading itself.
3. **Parallel lanes with git worktrees.** One terminal per lane:
   ```bash
   git worktree add ../jarvis-laneB fb/03-world-context
   cd ../jarvis-laneB && claude          # then: /feature-block FB-03
   ```
   Each worktree is its own checkout and its own Claude Code session. Merge each lane's
   branch as it is tested; lanes don't touch each other's files, so merges are clean.
   Rebase a lane before starting its next block.

Config keys are the one shared file (`jarvis-paper/src/main/resources/config.yml`).
Each block adds its keys under its own top-level section named in the spec, so two
lanes editing config.yml still merge without conflict.

## The loop for every block

1. `/feature-block FB-NN` in Claude Code (or hand it the spec file by path).
2. The agent audits what already exists, implements, tests, updates CHANGELOG, commits.
3. Gadget builds the jar from the branch (`mvn -q -o package -pl jarvis-paper -am
   -DskipTests`), backs up the live jar, stages the new one in `plugins/update/`, restarts
   with `amp_restart.py`, and runs the block's **In-game test** script. Results go in
   `TESTING.md`'s format.
4. Report results in chat as "FB-NN: step 3 failed, Jarvis said X instead of Y". The
   agent fixes on the same branch. Repeat until every step passes.
5. Mark the block `tested` in the table above, merge, delete the branch.

Versioning: 0.18.0 stays the Jarvis Cloud release. These blocks ship as **patch releases
on the 0.18 line** — 0.18.1, 0.18.2, … — one per batch of `tested` blocks, however many
that is. Never 0.19.0 for this work. Cut each release with `docs/dev/releasing.md`;
shipping one block at a time is fine.

## Deferred ideas (not blocks yet)

- **Task skill library.** Extend `ExperienceMemory` from builds to any multi-step task
  (Voyager pattern). Big; design it after FB-04 shows how memory retrieval feels in play.
- **Voice push-to-talk polish.** `jarvis-voice` exists; park until the 26.x Simple Voice
  Chat API settles.
- **Companion survival stats** (hunger, tool speed parity). Check what `ButlerService`
  already does for hunger before scoping.
- **Repair-as-diff, Haiku trial, cleaner ports to the mods** — carried over from
  `NEXT-STEPS-builds.md`; they belong to the build pipeline, not to these blocks.
