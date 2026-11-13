// node --test: the tool rules and the log tailer. No server, no RCON.
import { test } from "node:test";
import assert from "node:assert/strict";
import { anyCommandAllowed, jarvisOnly, oneLine, playerName, recentLines } from "../dist/tools.js";

test("jarvis_command refuses op me, accepts jarvis summon", () => {
  assert.throws(() => jarvisOnly("op me"));
  assert.throws(() => jarvisOnly("/op Gadget"));
  assert.equal(jarvisOnly("jarvis summon"), "jarvis summon");
  assert.equal(jarvisOnly("/jarvis summon"), "jarvis summon");
  assert.throws(() => jarvisOnly("ai test ollama"), "the jarvis prefix is required");
  assert.throws(() => jarvisOnly("jarvisx summon"));
  assert.throws(() => jarvisOnly("jarvis summon\nop Gadget"));
});

test("server_command refuses unless JARVIS_MCP_ALLOW_ANY=1", () => {
  assert.equal(anyCommandAllowed({}), false);
  assert.equal(anyCommandAllowed({ JARVIS_MCP_ALLOW_ANY: "true" }), false);
  assert.equal(anyCommandAllowed({ JARVIS_MCP_ALLOW_ANY: "1" }), true);
});

test("say-as arguments: player names and one line of text", () => {
  assert.equal(playerName(" Gadget "), "Gadget");
  assert.throws(() => playerName("op Gadget"));
  assert.throws(() => playerName("ab"));
  assert.equal(oneLine("mine 10\niron"), "mine 10 iron");
  assert.throws(() => oneLine("  \n "));
});

const LOG = [
  "[11:59:50] [Server thread/INFO]: old line",
  "[12:00:05] [Server thread/INFO]: [Jarvis] [say-as Gadget] follow me",
  "java.lang.RuntimeException: boom",
  "\tat Somewhere.java:1",
  "[12:00:20] [Server thread/INFO]: [Jarvis] [say-as Gadget] Jarvis: Right behind you, sir.",
  "[12:00:25] [Server thread/INFO]: Gadget joined the game",
].join("\n");

test("log tailer: only lines newer than since_seconds, and matching grep", () => {
  const now = new Date(2026, 10, 11, 12, 0, 30);
  const written = new Date(2026, 10, 11, 12, 0, 25).getTime();   // the newest line
  const last30 = recentLines(LOG, 30, undefined, now, written);
  assert.equal(last30.length, 5, "the stack trace lines take the stamp above them");
  assert.ok(!last30.some((l) => l.includes("old line")));
  const said = recentLines(LOG, 30, "say-as", now, written);
  assert.equal(said.length, 2);
  assert.ok(said[0].endsWith("[say-as Gadget] follow me"));
  assert.ok(said[1].endsWith("Jarvis: Right behind you, sir."));
  assert.equal(recentLines(LOG, 8, undefined, now, written).length, 1);
});

test("log tailer: going back over midnight", () => {
  const now = new Date(2026, 10, 11, 0, 0, 10);
  const written = new Date(2026, 10, 11, 0, 0, 5).getTime();
  const log = "[23:59:55] [Server thread/INFO]: before midnight\n[00:00:05] [Server thread/INFO]: after";
  assert.equal(recentLines(log, 30, undefined, now, written).length, 2);
  assert.equal(recentLines(log, 6, undefined, now, written).length, 1);
});

test("log tailer: a server in another time zone (stamps 8 hours ahead of us)", () => {
  const now = new Date(2026, 10, 12, 0, 12, 0);          // 00:12 here
  const written = now.getTime() - 30_000;                 // last line half a minute ago
  const log = [
    "[08:01:00] [Server thread/INFO]: an hour of nothing ago",
    "[08:11:26] [Server thread/INFO]: [Jarvis] [say-as callmegadget] what time of day is it?",
    "[08:11:30] [Server thread/INFO]: [Jarvis] [say-as callmegadget] Jarvis: The time is daybreak, sir.",
  ].join("\n");
  const said = recentLines(log, 60, "say-as", now, written);
  assert.equal(said.length, 2);
  assert.equal(recentLines(log, 60, undefined, now, written).length, 2, "the 08:01 line is ten minutes older");
});
