---
name: feature-block
description: Run the full loop for one Jarvis feature block (FB-NN) from docs/dev/feature-blocks — branch, tests from the spec, implementation, review, changelog — using the test-engineer, feature-implementer and code-reviewer subagents. Use when the owner says "/feature-block FB-03", "work on block 3", or "start the next block".
---

# Feature block loop

Argument: a block id like `FB-03`. Find its spec at `docs/dev/feature-blocks/FB-03-*.md`.
No argument → open `docs/dev/feature-blocks/README.md`, pick the first `planned` block
whose dependencies are `tested` or `shipped`, and confirm the choice with the owner in
one line before starting.

## Steps

1. **Branch.** `git status` must be clean. `git checkout -b fb/NN-short-name` from
   `release/0.18.0` (or `main` once 0.18.0 is merged). Update the block's row in
   `README.md` to `in progress`.
2. **Tests first.** Launch the `test-engineer` subagent with the spec path. It writes
   the test classes from the spec and reports which symbols don't exist yet.
3. **Implement.** Launch the `feature-implementer` subagent with the spec path and the
   test-engineer's symbol list. It audits, implements, makes the tests pass, adds config
   and the CHANGELOG line under the next unreleased 0.18.x heading (never 0.19.0). If it reports the feature already exists, stop and tell the
   owner what it found.
4. **Verify.** Run `cd jarvis-core && mvn -q -o test` yourself. If a mod module's
   `core/platform/` interface changed, also run `./gradlew build` in `jarvis-fabric`
   and `jarvis-neoforge`.
5. **Review.** Launch the `code-reviewer` subagent. If it says NEEDS WORK, send the
   numbered items back to `feature-implementer` (same agent, continue its context) and
   re-review. At most two rounds; then escalate to the owner.
6. **Commit.** Stage named paths only (`git add path/to/File.java …`), never `-A`.
   Message: `FB-NN: <short name>` then a blank line, then three plain-words sentences on
   what changed and why. Do not push.
7. **Hand off** to the owner in this exact shape:

   ```
   FB-NN built on branch fb/NN-short-name (commit <sha>).
   What changed: <3 sentences>
   Tests: cd jarvis-core && mvn -q -o test → N passed
   Package: mvn -q -o package -pl jarvis-paper -am -DskipTests
   Deploy: back up live jar, copy to plugins/update/, run amp_restart.py
   In-game test: steps 1–K in docs/dev/feature-blocks/FB-NN-*.md
   Report results in the TESTING.md format.
   ```
   Update the block's row to `built`.

8. **On a FAIL report** from the owner: add a failing unit test that reproduces it when
   possible, fix on the same branch via `feature-implementer`, re-run review, hand off
   again with the new commit sha. When the owner says all steps pass, set the row to
   `tested`.

## Parallel lanes

If the owner asks to run several blocks at once, use one git worktree per lane
(`git worktree add ../jarvis-laneB fb/NN-name`) and one Claude Code session per
worktree, each running this skill for its lane's next block. Lanes are listed in
`README.md`; blocks in the same lane are sequential.
