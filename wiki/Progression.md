# Progression

Jarvis earns his kit. He starts with an iron pickaxe and works up to
**Without Equal**, and the ranks are earned from what he has actually done for
you — per player, persisted across restarts. `/jarvis rank`, or **Service
record** in the bell menu, shows where he stands and what is next.

---

## The ladder

| Rank | Service | What it buys |
|---|---|---|
| Hired | 0 | Iron pickaxe |
| Acquainted | 25 | Efficiency I |
| Reliable | 75 | Diamond pickaxe |
| Practised | 150 | Efficiency III, Sharpness I |
| Trusted | 300 | Fortune I, Looting I |
| Seasoned | 500 | Efficiency V, Sharpness III |
| Valued | 800 | Fortune II, Looting II |
| Indispensable | 1500 | Netherite, Sharpness IV, Fortune III, Looting III, **archery** (Power III) |
| Peerless | 2500 | Sharpness V, Power IV, Punch I, **wide bore** — the 3×3 tunnelling capability |
| Without Equal | 4000 | Fire Aspect II, Power V, Punch II, Flame, **the returning trident** |

Two ranks buy a capability rather than a better metal: **Indispensable** gives
him a bow and the doctrine to use it, and **Peerless** unlocks
`/jarvis tunnel`, the 3×3 passage. **Without Equal** adds the trident that
comes back.

---

## Earning it

Service points come from work done: ore mined, trees felled, crops harvested,
fish caught, threats felled in your defence, and blocks placed. Building is
divided down hard, so one large build cannot skip most of the ladder. The
service record lists what he has done and what the next rank costs.

Tuning lives under `progression:` in the config:

```yaml
progression:
  enabled: true      # off: he has the full kit from the start
  op-bypass: true    # operators skip the ladder and get the top kit
  op-rank: ""        # what rank a bypassed operator counts as; blank = the top
  rate: 1.0          # multiplier on everything credited
```

`op-bypass` is on by default, so every operator's Jarvis is handed the top
kit without the climb. On Fabric and NeoForge, where admin means operator,
that is every admin — and in a singleplayer world with cheats allowed it is
you. Set `op-bypass: false` if you want to earn the ladder.

An admin can place their own Jarvis at a rank, or clear their own service
record (reconnect afterwards to reload it):

```
/jarvis rank set Peerless      or a number, 1-10
/jarvis rank reset
```

---

## His gear stays his

Issued kit carries a marker. A dismissal hands back everything **except**
issued gear, and his inventory screen refuses to let you take it, on Paper and
on the mods alike. Loot he has collected for you moves freely.
