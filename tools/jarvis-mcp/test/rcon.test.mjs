// node --test: the RCON client against a fake RCON server on localhost.
import { test } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import { Rcon, decode, encode } from "../dist/rcon.js";

function fakeServer(password, answer) {
  const seen = [];
  const server = net.createServer((sock) => {
    let buf = Buffer.alloc(0);
    sock.on("data", (chunk) => {
      const { packets, rest } = decode(Buffer.concat([buf, chunk]));
      buf = rest;
      for (const p of packets) {
        if (p.type === 3) sock.write(encode(p.body === password ? p.id : -1, 2, ""));
        else { seen.push(p.body); sock.write(encode(p.id, 0, answer(p.body))); }
      }
    });
  });
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port, seen })));
}

test("encode/decode round trip, including a split packet", () => {
  const a = encode(7, 2, "jarvis summon");
  const b = encode(8, 2, "list");
  const both = Buffer.concat([a, b]);
  const first = decode(both.subarray(0, a.length + 3));
  assert.equal(first.packets.length, 1);
  assert.equal(first.packets[0].body, "jarvis summon");
  const second = decode(Buffer.concat([first.rest, both.subarray(a.length + 3)]));
  assert.deepEqual(second.packets.map((p) => [p.id, p.body]), [[8, "list"]]);
});

test("authenticates once and runs commands in order", async () => {
  const { server, port, seen } = await fakeServer("pw", (cmd) => `ran ${cmd}`);
  const r = new Rcon("127.0.0.1", port, "pw", 2000);
  assert.equal(await r.run("jarvis ai"), "ran jarvis ai");
  assert.equal(await r.run("list"), "ran list");
  assert.deepEqual(seen, ["jarvis ai", "list"]);
  r.close();
  server.close();
});

test("commands asked for at once share one connection and keep their order", async () => {
  let connections = 0;
  const { server, port, seen } = await fakeServer("pw", (cmd) => `ran ${cmd}`);
  server.on("connection", () => connections++);
  const r = new Rcon("127.0.0.1", port, "pw", 2000);
  const out = await Promise.all([r.run("jarvis ai"), r.run("list"), r.run("tps")]);
  assert.deepEqual(out, ["ran jarvis ai", "ran list", "ran tps"]);
  assert.deepEqual(seen, ["jarvis ai", "list", "tps"]);
  assert.equal(connections, 1);
  r.close();
  server.close();
});

test("a wrong password is an error, not a hang", async () => {
  const { server, port } = await fakeServer("pw", () => "");
  const r = new Rcon("127.0.0.1", port, "nope", 2000);
  await assert.rejects(r.run("list"), /authentication failed/);
  r.close();
  server.close();
});
