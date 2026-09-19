# Jarvis platform interface (draft 1)

**Status:** step 1 done (module split). Steps 2 to 9 not started.
**Branch:** `claude/brave-wright-m8yhbm`.
**Baseline analysed:** commit `00af5c6` (v0.16.0), 68 files, ~22k lines.

## How to resume from this document

Hand this file to a future session with one of:

- "Implement step N of docs/dev/platform-interface.md" (see *Refactor steps*).
- "Revise the platform interface: <change>" to amend the design before starting.
- "Run the Fabric spike from docs/dev/platform-interface.md" (see *The spike*).

Everything below is self-contained. The numbers in *Why* were measured on the
baseline above and do not need re-measuring unless the code has moved a lot.

---

## Why

Goal: reach mod users and singleplayer players, who together outnumber Paper
server operators, without abandoning the working Paper plugin.

Decision: split Jarvis into a platform-free **core** and thin **adapters**.

- `jarvis-core`: the brain and the task logic. No Bukkit, Citizens, Paper,
  Fabric, or Minecraft imports. Enforced by the module simply not declaring
  those dependencies.
- `jarvis-paper`: the existing plugin, reduced to an adapter. NPC via Citizens.
- `jarvis-fabric`: a server-side Fabric mod. Works in singleplayer (integrated
  server) and on Fabric servers with vanilla clients. NPC via a vendored
  Carpet-style fake player plus our own pathfinder.
- NeoForge later, through Architectury or a third adapter.

What was measured on the baseline:

| Area | Count | Note |
|---|---|---|
| Files importing Bukkit | 46 of 68 | 325 import lines |
| Pure Java files (no server API) | 18 | ~2.3k lines, move untouched |
| BukkitRunnable uses | 151 | become `Scheduler` calls |
| Inventory GUI references | 138 | become the `Menu` model |
| Direct Citizens calls in JarvisNPC.java | 122 | become `Butler` calls |
| Distinct `Material` constants used | 170 | become namespaced string ids |
| Distinct config keys read | 67 | config parsing moves into core |
| `@EventHandler` methods | 12 | become `Events` subscriptions |

Most-used world calls in the behaviour classes, which is what `World` and
`Entity` below are sized for: `getType` (130), `getWorld` (100),
`getLocation` (90), `distance` (53), `sendMessage` (43), `getRelative` (28),
`playSound` (19), `isSolid` (18), `setType` (15), `getNearbyEntities` (7),
`breakNaturally` (7), `dropItemNaturally` (5), `getLightFromBlocks` (4),
`getHighestBlockYAt` (4), `spawnParticle` (3), `getChunkSnapshot` (2).

Why the NPC vocabulary is the fake-player vocabulary: a fake player can only
*look*, *hold attack*, *hold use*, *walk*, *jump*, and *select a slot*. Citizens
can implement every one of those. The reverse is not true, so the interface is
written at the fake-player level and Citizens' richer navigator sits behind it.

---

## Design rules for core

1. Core never names a platform type. Blocks, items, sounds, and particles are
   namespaced string ids (`minecraft:oak_log`). Positions are core records.
2. Core never touches a thread it did not get from `Scheduler`. Anything that
   ran in `runTaskAsynchronously` runs in `Scheduler.async`.
3. Navigation and block breaking are **leaf operations** on `Butler`. Core asks
   "go here, tell me when stuck" and "break this, tell me when done". Core does
   not contain a pathfinder. The Fabric adapter owns one; the Paper adapter
   delegates to Citizens.
4. Core owns config parsing (snakeyaml is already a dependency), the SQLite
   database, experience memory, progression, and all text. Adapters supply
   only a data directory and a logger for those.
5. Every callback into core from an adapter arrives on the server thread unless
   the method is documented as async.
6. Bytecode target stays Java 17 for core so one core jar serves both adapters.

---

## Module layout

```
jarvis/
  pom.xml                      (parent, packaging pom)
  jarvis-core/pom.xml          deps: org.json, snakeyaml, HikariCP, sqlite, junit
  jarvis-paper/pom.xml         deps: core, purpur-api, citizensapi, worldedit, voicechat-api
  jarvis-fabric/build.gradle   deps: core (as a jar-in-jar), fabric-loader, fabric-api, voicechat-api
  jarvis-nav/                  (optional, later) server-side A* shared by fabric and any
                               future Citizens-free Paper provider
```

GraalJS is a Paper-only concern for now: Paper's library loader fetches it,
Fabric has no equivalent. The Fabric adapter ships the JSON planner path only
(already the fallback), with Rhino (~1.5 MB) as the candidate if scripted
builds are wanted there.

---

## Value types (core, `com.gadgetman.jarvis.core.world`)

```java
/** Integer block position. */
public record BlockPos(int x, int y, int z) {
    public BlockPos offset(int dx, int dy, int dz) { ... }
    public BlockPos below()  { return offset(0, -1, 0); }
    public BlockPos above()  { return offset(0,  1, 0); }
    public BlockPos side(Facing f) { ... }
    public double distanceSq(BlockPos o) { ... }
    public Vec3 center() { return new Vec3(x + 0.5, y + 0.5, z + 0.5); }
    public long packed() { ... }            // for HashSet<Long> visited sets
}

/** Exact position, optionally with a facing. */
public record Vec3(double x, double y, double z) {
    public double distance(Vec3 o) { ... }
    public double distanceSq(Vec3 o) { ... }
    public Vec3 add(double dx, double dy, double dz) { ... }
    public Vec3 scale(double f) { ... }
    public Vec3 normalize() { ... }
    public BlockPos block() { ... }
}

public record Look(float yaw, float pitch) {}

public enum Facing { DOWN, UP, NORTH, SOUTH, WEST, EAST; public BlockPos delta(); public Facing opposite(); }

/** A world (dimension) handle is a string id: "minecraft:overworld", "minecraft:the_nether". */
public record WorldId(String id) {}

/** A block with its state. props carries only what core sets or reads:
 *  "facing", "age", "part", "half", "up", "north"... as strings. */
public record BlockState(String id, Map<String, String> props) {
    public static BlockState of(String id) { return new BlockState(id, Map.of()); }
    public BlockState with(String prop, String value) { ... }
    public boolean is(String id) { ... }
    public boolean isAir() { ... }
}

/** Block and item tags, e.g. "minecraft:logs", "minecraft:leaves", "minecraft:dirt".
 *  Replaces the name-suffix checks (endsWith("_LEAVES")) in the behaviour classes. */
public record Tag(String id) {}

/** An item stack as core sees it. Enough for kit, loot, deposits, and menus. */
public record Item(String id, int count, Map<String, Integer> enchants,
                   String displayName, List<String> lore, String marker) {
    public static Item of(String id) { return of(id, 1); }
    public static Item of(String id, int count) { ... }
    public Item withCount(int n) { ... }
    public Item enchant(String id, int level) { ... }
    public Item named(String name) { ... }
    /** marker: an opaque tag the adapter stores in PDC / custom data
     *  (used for the controller bell). */
    public Item marked(String marker) { ... }
    public boolean isEmpty() { return id == null || count <= 0; }
}

/** Ids for blocks, items, sounds and particles that core references.
 *  Generated once from the 170 Material constants in use; kept as plain
 *  strings so core needs no registry. */
public final class Ids {
    public static final String AIR = "minecraft:air";
    public static final String OAK_LOG = "minecraft:oak_log";
    public static final String DIAMOND_AXE = "minecraft:diamond_axe";
    public static final String SOUND_WOOD_BREAK = "minecraft:block.wood.break";
    public static final String PARTICLE_SPLASH = "minecraft:splash";
    // ...
}
```

---

## The interfaces (core, `com.gadgetman.jarvis.core.platform`)

### Platform (root)

```java
public interface Platform {
    String name();                       // "paper", "fabric"
    Scheduler scheduler();
    Log log();
    Path dataDir();                      // plugins/Jarvis or config/jarvis
    Players players();
    World world(WorldId id);             // null if not loaded
    Events events();
    Ui ui();
    Butlers butlers();                   // one Butler per owner
    Optional<Voice> voice();             // present when Simple Voice Chat is installed
    Optional<Schematics> schematics();   // present when WorldEdit (or a native reader) is available
    double tps();                        // for the duty scheduler's load check
}
```

### Scheduler and Log

```java
public interface Scheduler {
    Task every(long delayTicks, long periodTicks, Consumer<Task> body);   // body may call task.cancel()
    Task later(long delayTicks, Runnable body);
    void sync(Runnable body);            // next server tick
    void async(Runnable body);           // off-thread; use sync() to come back
    <T> void async(Supplier<T> work, Consumer<T> thenOnServerThread);
    boolean isServerThread();
}

public interface Task {
    void cancel();
    boolean isCancelled();
}

public interface Log {
    void info(String msg);
    void warn(String msg);
    void fine(String msg);
    void error(String msg, Throwable t);
}
```

*Paper:* `BukkitRunnable`, `runTaskTimer`, `runTaskAsynchronously`.
*Fabric:* a tick-counter scheduler on `ServerTickEvents.END_SERVER_TICK`; async
on a small executor; `sync` enqueues to the server thread via `server.execute`.

### Players

```java
public interface Players {
    Optional<Owner> byId(UUID id);
    Collection<Owner> online();
    Audience console();
}

/** A real player who owns a Jarvis. */
public interface Owner extends Audience {
    UUID id();
    String name();
    boolean isOnline();
    boolean isOp();
    boolean hasPermission(String node);
    WorldId world();
    Vec3 pos();
    Vec3 eyePos();
    Look look();
    double health();
    double maxHealth();
    boolean isSneaking();
    Item heldItem();
    List<Item> inventory();              // storage contents, snapshot
    void give(Item item);                // drops at feet if full
    Optional<BlockPos> targetBlock(double reach);
    void teleport(WorldId world, Vec3 pos, Look look);
    long lastSeenEpochMillis();
}

public interface Audience {
    void message(String text);           // plain text; core formats "Jarvis: " prefix itself
    void actionBar(String text);
    void sound(String soundId, float volume, float pitch);   // at the audience's own position
}
```

### World

```java
public interface World {
    WorldId id();
    Environment environment();           // NORMAL, NETHER, END
    long time();                         // ticks of day
    boolean isRaining();
    boolean isThundering();
    int minY();
    int maxY();
    boolean isChunkLoaded(int chunkX, int chunkZ);

    BlockState block(BlockPos pos);
    void setBlock(BlockPos pos, BlockState state);
    boolean isSolid(BlockPos pos);
    boolean isPassable(BlockPos pos);
    boolean isLiquid(BlockPos pos);
    boolean is(BlockPos pos, Tag tag);
    int highestY(int x, int z);
    int blockLight(BlockPos pos);
    int skyLight(BlockPos pos);

    /** Break as a tool would: drops per loot table, no animation. Used for the
     *  timber cascade and the branch miner's cleanup, not for the butler's own dig. */
    void breakNaturally(BlockPos pos, Item tool);
    List<Item> drops(BlockPos pos, Item tool);
    Entity dropItem(Vec3 at, Item item);

    /** Container access for deposit chests. */
    Optional<Container> container(BlockPos pos);

    List<Entity> nearby(Vec3 center, double radius);
    List<Entity> nearby(Vec3 center, double radius, EntityKind kind);
    Optional<Entity> entity(UUID id);

    void sound(Vec3 at, String soundId, float volume, float pitch);
    void particle(Vec3 at, String particleId, int count, double spread);

    /** Immutable copy of a region, safe to read from Scheduler.async. */
    BlockScan snapshot(BlockPos min, BlockPos max);
}

public interface BlockScan {
    BlockState block(BlockPos pos);       // AIR outside the copied region
    BlockPos min();
    BlockPos max();
}

public interface Container {
    List<Item> contents();
    /** Returns what did not fit. */
    List<Item> add(List<Item> items);
    int freeSlots();
}
```

*Paper:* `World.getBlockAt`, `Block.getBlockData` to/from `BlockState.props`
(Directional, Ageable, Bed, Wall, MultipleFacing map to named props),
`ChunkSnapshot` for `snapshot`.
*Fabric:* `ServerLevel.getBlockState`, `BlockState` property serialisation via
`Property.getName`, a copied `BlockState[]` for `snapshot`.

### Entities

```java
public enum EntityKind { PLAYER, BUTLER, HOSTILE, PASSIVE, ITEM, PROJECTILE, OTHER }

public interface Entity {
    UUID id();
    String typeId();                     // "minecraft:zombie"
    EntityKind kind();
    boolean isAlive();
    boolean isCreeper();                 // Defender special-cases it
    WorldId world();
    Vec3 pos();
    Vec3 velocity();
    void setVelocity(Vec3 v);
    double health();
    void damage(double amount, Butler by);
    Optional<Item> asItem();             // for ITEM entities
    void remove();
}
```

### Butler (the NPC)

This is the replacement for `INPCProvider`, `Navigator`, `BlockBreaker`,
`Equipment` and `Inventory` traits together. One instance per owner.

```java
public interface Butlers {
    Butler of(Owner owner);              // creates the handle; does not spawn
    Collection<Butler> all();
    void shutdown();                     // despawn everything on stop/reload
}

public interface Butler {
    Owner owner();
    UUID id();
    String name();

    // ---- lifecycle ----
    void spawn(WorldId world, Vec3 at, Look look);
    void despawn();
    boolean isSpawned();
    void setProtected(boolean invulnerable);
    Optional<Entity> entity();           // for lookAt / follow by others
    WorldId world();
    Vec3 pos();
    Vec3 eyePos();
    void teleport(WorldId world, Vec3 at, Look look);

    // ---- looking ----
    void lookAt(Vec3 target);
    void lookAt(Entity target);

    // ---- moving ----
    Navigation moveTo(Vec3 target, NavOptions options);
    Navigation follow(Entity target, double keepDistance, NavOptions options);
    Optional<Navigation> navigation();   // the active one, if any
    void stop();                         // cancel navigation and any held action
    void setSwimming(boolean swim);
    void jump();

    // ---- hands ----
    Item heldItem();
    void setHeldItem(Item item);
    Item equipment(Slot slot);
    void setEquipment(Slot slot, Item item);
    void swing();

    // ---- acting on the world (leaf operations) ----
    /** Face the block, play the crack animation at the right speed for the
     *  held tool, drop the loot, call back. false if it could not be broken. */
    void breakBlock(BlockPos pos, Consumer<Boolean> onDone);
    /** Right-click a block with the held item (plant, till, open, place). */
    void useItemOn(BlockPos pos, Facing face);
    /** Hold "use" for the given ticks, then release (bow draw, eat, cast). */
    void useHeldItem(int holdTicks);
    void attack(Entity target);          // one melee swing with the held item
    void fireAt(Entity target);          // ranged with the held bow/trident; adapter decides mechanics
    Fishing cast(BlockPos water);        // see below

    // ---- carrying ----
    List<Item> loot();                   // his bags, snapshot
    boolean addLoot(Item item);          // false if full
    void setLoot(List<Item> items);
    int lootSlotsUsed();
    int lootCapacity();
    void pickupNearby(double radius);

    // ---- presence ----
    void sound(String soundId, float volume, float pitch);   // at his position
    void particle(String particleId, int count);
}

public enum Slot { HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET }

public record NavOptions(float speed, double range, boolean allowSwim,
                         boolean allowDoors, int stuckAfterTicks) {
    public static NavOptions DEFAULT = new NavOptions(1.0f, 64, true, true, 60);
}

public interface Navigation {
    boolean isActive();
    boolean isPaused();
    void pause();
    void resume();
    void cancel();
    Navigation onArrive(Runnable r);
    Navigation onStuck(Runnable r);      // fired once; navigation is then cancelled
    Optional<Vec3> target();
}

public interface Fishing {
    boolean isActive();
    Fishing onBite(Runnable r);          // reel with reel(); loot goes to loot()
    void reel();
    void cancel();
}
```

*Paper (Citizens):* `moveTo` = `Navigator.setTarget` with the current
`NavigatorParameters` (async pathfinder, `SwimmingExaminer`, no-teleport
stuck action wired to `onStuck`). `breakBlock` = `BlockBreaker` as today.
`cast` = the current simulated bobber. `fireAt` = the current projectile code.
Loot = the Citizens `Inventory` trait.

*Fabric (fake player):* `moveTo` = pathfinder (see below) plus a per-tick
follower that sets forward/strafe/look and presses jump. `breakBlock` =
`lookAt` then hold attack until the block changes; the game does the timing,
drops, and durability. `cast` = hold use on water; the bobber is real.
`useItemOn` and `useHeldItem` are the action pack's use action. `fireAt` =
use for N ticks then release. Loot = the player's own inventory.

### Events

```java
public interface Events {
    <E extends Event> Subscription on(Class<E> type, Consumer<E> handler);
}
public interface Subscription { void cancel(); }
public interface Event {}

public record ChatEvent(Owner who, String text, Consumer<Boolean> cancel) implements Event {}   // may arrive async
public record JoinEvent(Owner who, boolean firstJoin) implements Event {}
public record QuitEvent(Owner who) implements Event {}
public record DeathEvent(Owner who, WorldId world, Vec3 at, List<Item> drops) implements Event {}
public record PortalEvent(Owner who, WorldId from, Vec3 fromPos, WorldId to, Vec3 toPos) implements Event {}
public record ButlerInteractEvent(Butler butler, Owner clicker, boolean sneaking) implements Event {}
public record ButlerDamagedEvent(Butler butler, Optional<Entity> attacker, double amount) implements Event {}
public record OwnerDamagedEvent(Owner who, Optional<Entity> attacker, double amount) implements Event {}
public record ItemUseEvent(Owner who, Item item, Consumer<Boolean> cancel) implements Event {}  // the controller bell
```

### Ui

```java
public interface Ui {
    void open(Owner viewer, Menu menu);
    void openLoot(Owner viewer, Butler butler);      // his bags, editable
    void close(Owner viewer);
}

public record Menu(String title, int rows, Map<Integer, MenuItem> slots, Item filler) {}
public record MenuItem(Item icon, Consumer<Owner> onClick, boolean closeOnClick) {}
```

*Paper:* `Bukkit.createInventory` with an `InventoryHolder` marker and an
`InventoryClickEvent` listener, as `UIManager` does now.
*Fabric:* a generic 9xN `ChestMenu` subclass whose `clicked` routes to
`onClick`; or the `sgui` library if you would rather not write it.

### Commands

Core owns parsing and behaviour. Adapters own registration.

```java
public interface CommandSink {
    /** Called by the adapter for "/jarvis <args...>". Returns completions when tab=true. */
    List<String> jarvis(Audience sender, Optional<Owner> asPlayer, List<String> args, boolean tab);
}
```

*Paper:* `plugin.yml` command + `CommandExecutor`/`TabCompleter`.
*Fabric:* one Brigadier literal `jarvis` with a greedy string argument and a
suggestion provider that calls `jarvis(..., tab=true)`.

### Voice

Simple Voice Chat's API is platform neutral. The only difference is
registration, so:

```java
public interface Voice {
    void register(de.maxhenkel.voicechat.api.VoicechatPlugin plugin);   // adapter does the lookup
}
```

*Paper:* `Bukkit.getServicesManager().load(BukkitVoicechatService.class).registerPlugin`.
*Fabric:* a `voicechat` entrypoint in `fabric.mod.json` that returns core's `VoiceBridge`.
`EntityAudioChannel` takes the butler's entity UUID on both.

### Schematics

```java
public interface Schematics {
    Optional<Schematic> load(Path file);          // .schem via the native reader; WorldEdit optional
    void paste(Schematic s, World world, BlockPos origin, Consumer<Integer> progress);
}
```

The native `.schem` reader and the `.litematic` converter are pure Java
already and move to core. WorldEdit becomes an optional Paper-side accelerator
or is dropped.

---

## The pathfinder (Fabric adapter, or `jarvis-nav`)

Not in core. Needed only where the NPC has no navigator of its own.

- A* over `BlockPos` with move types: walk, step up, drop (up to 3, more if
  water below), jump gap of 1, swim, climb ladder/vine, open door.
  Cost table favours flat ground and avoids lava, fire, cactus, magma, and
  block edges over long drops. Node budget per search, with partial paths
  toward the target when the budget runs out (long "take me home" trips are
  chained partial paths).
- Follower: every tick, aim at the next node, set forward = 1, strafe to
  correct lateral drift, jump when the next node is higher or when in water,
  sneak on edges when the drop is fatal, sprint on straight runs. Stuck when
  progress toward the next node is below a threshold for `stuckAfterTicks`.
- Reads the world through `World`/`BlockScan`, so it could be lifted into a
  shared module and used by a future Citizens-free Paper provider too.

---

## Migration map (baseline classes)

Destination: **C** = core unchanged, **C\*** = core after edits, **P** = Paper adapter, **F** = Fabric adapter, **split** = both.

| Class | Lines | Dest | Platform needs after the split |
|---|---|---|---|
| ai/AIConnector | 1423 | C\* | Config, Log, Scheduler.async |
| intent/IntentPipeline | 475 | C\* | Owner, Audience, Scheduler |
| memory/ExperienceMemory | 488 | C\* | Scheduler.async, dataDir |
| memory/EmbeddingClient, BuildExperience | | C | none |
| memory/SituationSnapshot | | C\* | World, Vec3 |
| memory/DatasetExporter | | C\* | Audience, Scheduler |
| DatabaseManager | 513 | C\* | dataDir, Item (replaces ItemStack serialisation) |
| ConfirmationManager, PlayerRequestManager | | C | none |
| progression/Rank, ServiceRecord, ProgressionManager | | C\* | Item ids and enchant ids instead of Material/Enchantment; Owner |
| steward/remarks/RemarkDoctrine, Observation, RemarkSubject | | C | none |
| steward/remarks/Observer | 126 | C\* | Owner.inventory, World.nearby |
| steward/remarks/Remarks | 176 | C\* | Scheduler, Owner |
| steward/DutyScheduler | 179 | C\* | Config, Scheduler, Players, Platform.tps |
| steward/MorningReport | 123 | C\* | Events.JoinEvent, World.time |
| npc/combat/WeaponDoctrine, Armament, Engagement | | C | none (already tested) |
| npc/portal/PortalLink, PortalSighting | | C | none |
| npc/portal/PortalScout | 289 | C\* | Events.PortalEvent, World.snapshot, Scheduler |
| npc/Compass | | C | none |
| npc/Lumberjack | 326 | C\* | Butler, World, Scheduler, Config. First task to move. |
| npc/Farmer | 339 | C\* | Butler.useItemOn for planting, BlockState "age" |
| npc/Fisherman | 276 | C\* | Butler.cast |
| npc/Lamplighter | 350 | C\* | World.blockLight, BlockState "facing" |
| npc/ShaftDigger, BranchMiner | 249, 673 | C\* | Butler.breakBlock, World |
| npc/EscortService | 223 | C\* | Butler.follow |
| npc/Defender | 653 | C\* | Butler.attack/fireAt, Entity, WeaponDoctrine |
| npc/DepositManager | 446 | C\* | World.container, Butler.loot, dataDir (its YAML) |
| npc/Entertainer | 112 | C\* | Butler.particle/sound |
| npc/RecoveryService | 266 | C\* | Events.DeathEvent, World.nearby(ITEM) |
| npc/JarvisNPC | 2393 | split | orchestration (task registry, say, credit, loot, safe-spot search) to core as `ButlerService`; every Citizens line to P |
| npc/provider/INPCProvider, NPCProviderFactory | | deleted | replaced by `Butler`/`Butlers` |
| npc/provider/CitizensNPCProvider | 650 | P | implements `Butler` |
| building/ScriptBuildPlanner | 567 | C | none (GraalJS optional at runtime already) |
| building/BuildingAssistant | 1106 | C\* | World.setBlock with BlockState props, Scheduler, Butler.moveTo |
| schematics/SchemReader, LitematicConverter, RequestFeatures, RequestDecomposer | | C | none |
| schematics/SchematicManager | 806 | split | native reader to core; WorldEdit lookup to P |
| recovery/TaskFailure, TaskRecoveryHandler | | C\* | Owner instead of Player |
| commands/JarvisCommands | 1168 | split | behaviour to core `CommandSink`; Bukkit wiring to P |
| ui/UIManager | 904 | split | menus as `Menu` models in core; rendering to P and F |
| ui/TaskMonitor | | C\* | Audience.actionBar, Butler |
| voice/VoiceBridge, VoiceResponder | | C\* | Voice.register; entity UUID from Butler |
| voice/SpeechService | | C | none |
| listeners/ChatListener, PlayerConnectionListener, PlayerEventListener | | P (+F) | translate platform events into `Events` records |
| Jarvis (main) | | P | bootstrap: build `PaperPlatform`, construct `JarvisCore`, wire listeners |

---

## Refactor steps

Each step is one PR that leaves the Paper plugin building and behaving as
before. Order matters: each introduces the interfaces the next needs.

1. **Modules.** Parent pom, `jarvis-core` (empty), `jarvis-paper` (all current
   code). Shade core into the Paper jar. Confirm the plugin still runs.
   *Done.* Root `pom.xml` is the parent; the old pom moved to
   `jarvis-paper/pom.xml` with its dependencies intact; core holds only a
   `package-info` stating the no-platform-imports rule. The plugin jar is now
   `jarvis-paper/target/jarvis-<version>.jar`. The Paper module could not be
   compiled in the session that made the split (its Maven repositories were
   blocked by the network policy there), so the first thing step 2 does is
   `mvn clean package` on a machine that can reach them.
2. **Pure files to core.** Move the 18 files with no server imports. No edits.
3. **Value types and Config.** Add `BlockPos`, `Vec3`, `Look`, `Facing`,
   `WorldId`, `BlockState`, `Tag`, `Item`, `Ids`. Add core `Config` backed by
   snakeyaml with the same keys. Move `AIConnector`, `DatabaseManager`,
   `ExperienceMemory`, progression, `ConfirmationManager`. Paper hands core its
   data directory; `config.yml` keeps its format.
4. **Scheduler, Log, Players, World, Entity, Events.** Add the interfaces and
   `PaperPlatform`. Move `Observer`, `Remarks`, `DutyScheduler`,
   `MorningReport`, `PortalScout`, `RecoveryService`, `SituationSnapshot`,
   `TaskFailure`, `IntentPipeline`.
5. **Butler.** Add `Butler`, `Butlers`, `Navigation`, `NavOptions`, `Fishing`.
   Make `CitizensNPCProvider` implement `Butler` (it already has most of the
   methods). Move tasks one at a time, smallest first: Lumberjack, Entertainer,
   Lamplighter, Fisherman, Farmer, ShaftDigger, EscortService, BranchMiner,
   DepositManager, Defender.
6. **Split JarvisNPC.** Orchestration to core `ButlerService`; Citizens to the
   Paper adapter. Delete `INPCProvider`. This is the largest single step.
7. **Building and schematics.** `BuildingAssistant` onto `World.setBlock`;
   `SchematicManager` split.
8. **Commands and UI.** `CommandSink` and `Menu` in core; thin Paper wiring.
   `Jarvis.java` becomes a bootstrap.
9. **Enforce.** Core's pom declares no platform dependency, so a stray import
   fails the build. Add a unit test or two per moved task class now that they
   can be driven by a fake `Platform`.

After step 8 the Fabric adapter is a fresh module against a stable core.

## The spike (do before or alongside step 5)

A throwaway Fabric mod, no core involved, to retire the one open-ended risk:

1. Vendor Carpet's `FakeClientConnection`, `EntityPlayerMPFake`, and
   `EntityPlayerActionPack` (MIT) under our package.
2. `/jspike spawn` creates the fake player at the caller.
3. `/jspike goto <x> <y> <z>` runs the A* and follower described above.
4. `/jspike dig <x> <y> <z>` walks to the block and holds attack until it breaks.
5. Test on 50 m of rough terrain, a two-block gap, a river, a ladder, a door.

Two weeks. If the follower is reliable, the Fabric adapter is straightforward.
If not, that is the thing to solve before spending anything on the port.

---

## Open questions

- **Item marker for the controller bell.** Paper uses a `PersistentDataContainer`
  key; Fabric uses a custom data component. `Item.marker` covers both, but the
  adapters must agree on the string so a bell made on one is recognised on the
  other only if worlds ever move between them (they will not, so a mismatch is
  fine).
- **ChatEvent threading.** Paper's chat event is async; Fabric's is on the
  server thread. Core must treat `ChatEvent` as possibly async and hop with
  `Scheduler.sync`, as `ChatListener` does today.
- **Who owns the pathfinder.** Fabric adapter for now. Promote to `jarvis-nav`
  if a second consumer appears.
- **GraalJS on Fabric.** Ship without it (JSON planner) and revisit with Rhino.
- **NeoForge.** Architectury from the start would cover it with the same
  adapter; decide when the Fabric adapter exists.
