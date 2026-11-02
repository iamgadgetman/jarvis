package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.WorldId;
import com.gadgetman.jarvis.memory.BuildExperience;
import com.gadgetman.jarvis.memory.ExperienceMemory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One undo for everything Jarvis builds.
 *
 * <p>Every path that puts blocks down for the owner records into this: AI
 * builds and simple shapes ({@link BuildingAssistant}), schematic pastes
 * ({@code SchematicLibrary}), and on Paper the WorldEdit pastes, which are
 * recorded by comparing the target region before and after. A job is one
 * {@link Batch}: what each block was, what it became, the world, a label
 * ("schematic: barn", "AI build: a small cottage") and when.
 *
 * <p>History is per owner, newest first, {@code build.undo-history} deep, and
 * kept in the database so it survives a restart. Undo restores a few blocks
 * per tick, as building does, and leaves alone any block that has changed
 * since Jarvis placed it. One level of redo is kept in memory.
 *
 * <p>Mining is not recorded, and neither are the blocks the mining jobs set
 * down while they work (floor patches, fluid plugs, ladders, torches):
 * putting those back would reopen lava and water into a mine he dug.
 * Container contents are not recorded either.
 */
public class UndoService {

    /**
     * What a placement replaced, states and all. Restoring only the id would
     * turn a staircase the build covered into a stack of default-facing stairs
     * on undo. {@code newId} is what Jarvis put there, the test for whether a
     * player has changed the block since.
     */
    public record BuildAction(BlockPos pos, BlockState original, String newId) { }

    /** A block to set back on redo: what undo left there, and what to put back. */
    private record Change(BlockPos pos, BlockState expected, BlockState restore) { }

    /** One job's placements, filled while the job runs and then committed. */
    public static final class Recorder {
        private final UUID owner;
        private final WorldId world;
        private final String label;
        private final boolean aiGenerated;
        private final List<BuildAction> actions = new ArrayList<>();
        private BuildExperience experience;

        private Recorder(UUID owner, WorldId world, String label, boolean aiGenerated) {
            this.owner = owner;
            this.world = world;
            this.label = label;
            this.aiGenerated = aiGenerated;
        }

        /** Note one block before it is changed. */
        public void record(BlockPos pos, BlockState original, String newId) {
            actions.add(new BuildAction(pos, original, newId));
        }

        /** The memory row this build produced, so undoing it demotes that row and no other. */
        public void setExperience(BuildExperience experience) {
            this.experience = experience;
        }

        public boolean isEmpty() { return actions.isEmpty(); }
        public int size() { return actions.size(); }
    }

    /** One undoable job, as history holds it. */
    public static final class Batch {
        final String key;
        final UUID owner;
        final WorldId world;
        final String label;
        final long createdAt;
        final boolean aiGenerated;
        final int size;
        /** Live only: held by reference because the id is filled in by an async insert. */
        final BuildExperience experience;
        /** The experience id as it was stored; -1 when unknown. */
        final long experienceId;
        private List<BuildAction> actions;
        private byte[] encoded;

        private Batch(String key, UUID owner, WorldId world, String label, long createdAt, boolean aiGenerated,
                      int size, BuildExperience experience, long experienceId,
                      List<BuildAction> actions, byte[] encoded) {
            this.key = key;
            this.owner = owner;
            this.world = world;
            this.label = label;
            this.createdAt = createdAt;
            this.aiGenerated = aiGenerated;
            this.size = size;
            this.experience = experience;
            this.experienceId = experienceId;
            this.actions = actions;
            this.encoded = encoded;
        }

        /** The placements, decoded from storage the first time they are needed. */
        List<BuildAction> actions() {
            if (actions == null) {
                actions = decode(encoded);
                encoded = null;
            }
            return actions;
        }

        public String label() { return label; }
        public int size() { return size; }
        public long createdAt() { return createdAt; }
        public WorldId world() { return world; }
    }

    /**
     * A region as it was before something changed it that cannot be watched
     * block by block (a WorldEdit paste). {@link #diffInto} turns the
     * difference into a batch afterwards.
     */
    public static final class Snapshot {
        private final World world;
        private final BlockPos min;
        private final BlockPos max;
        private final BlockState[] before;

        private Snapshot(World world, BlockPos min, BlockPos max) {
            this.world = world;
            this.min = min;
            this.max = max;
            int dx = max.x() - min.x() + 1, dy = max.y() - min.y() + 1, dz = max.z() - min.z() + 1;
            this.before = new BlockState[dx * dy * dz];
            int i = 0;
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    for (int x = min.x(); x <= max.x(); x++) {
                        before[i++] = world.block(new BlockPos(x, y, z));
                    }
                }
            }
        }

        /** Record every block in the region that is not what it was. Returns how many. */
        public int diffInto(Recorder recorder) {
            int changed = 0, i = 0;
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    for (int x = min.x(); x <= max.x(); x++) {
                        BlockState was = before[i++];
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState now = world.block(pos);
                        if (!now.equals(was)) {
                            recorder.record(pos, was, now.id());
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** Above this many blocks a region is not snapshotted: reading it would stall the server. */
    public static final long MAX_SNAPSHOT_VOLUME = 1_000_000L;

    private final Platform platform;
    private final Log log;
    private final DatabaseManager database;
    private final ExperienceMemory memory;

    private final Map<UUID, Deque<Batch>> history = new ConcurrentHashMap<>();
    private final Map<UUID, List<Batch>> redoable = new ConcurrentHashMap<>();
    private final Map<UUID, List<List<Change>>> redoChanges = new ConcurrentHashMap<>();
    private final Map<UUID, Task> running = new ConcurrentHashMap<>();

    /** Database writes, run in the order they were made. */
    private final Queue<Runnable> writes = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile boolean persist;

    private boolean enabled = true;
    private int limit = 10;
    private int blocksPerTick = 50;

    public UndoService(Platform platform, DatabaseManager database, ExperienceMemory memory) {
        this.platform = platform;
        this.log = platform.log();
        this.database = database;
        this.memory = memory;
        loadConfig();
        load();
    }

    private void loadConfig() {
        Config cfg = platform.config();
        enabled = cfg.getBoolean("build.enable-undo", true);
        limit = Math.max(1, cfg.getInt("build.undo-history", 10));
        blocksPerTick = Math.max(1, cfg.getInt("build.blocks-per-tick", 50));
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== RECORDING ====================

    /** Start recording a job. Commit it when the job ends, however it ends. */
    public Recorder begin(Owner owner, World world, String label, boolean aiGenerated) {
        return new Recorder(owner.id(), world.id(), label, aiGenerated);
    }

    /** Snapshot a region before changing it wholesale; null when it is too big to read. */
    public Snapshot snapshot(World world, BlockPos min, BlockPos max) {
        long volume = (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
        if (volume <= 0 || volume > MAX_SNAPSHOT_VOLUME) return null;
        return new Snapshot(world, min, max);
    }

    /** Put a finished (or stopped) job into its owner's history. Empty jobs are dropped. */
    public void commit(Recorder r) {
        if (!enabled || r == null || r.isEmpty()) return;
        Batch b = new Batch(UUID.randomUUID().toString(), r.owner, r.world, r.label,
                System.currentTimeMillis(), r.aiGenerated, r.actions.size(), r.experience,
                -1, List.copyOf(r.actions), null);
        push(b);
    }

    private void push(Batch b) {
        Deque<Batch> h = history(b.owner);
        h.addFirst(b);
        // Something new was built; what was undone before it is no longer
        // the thing to redo.
        redoable.remove(b.owner);
        redoChanges.remove(b.owner);
        while (h.size() > limit) {
            Batch dropped = h.removeLast();
            write(() -> deleteRow(dropped.key));
        }
        write(() -> insertRow(b));
    }

    private Deque<Batch> history(UUID owner) {
        return history.computeIfAbsent(owner, k -> new ArrayDeque<>());
    }

    /** How many jobs the owner can undo. */
    public int historySize(Owner owner) {
        Deque<Batch> h = history.get(owner.id());
        return h == null ? 0 : h.size();
    }

    /** The owner's history, newest first. */
    public List<Batch> historyOf(Owner owner) {
        Deque<Batch> h = history.get(owner.id());
        return h == null ? List.of() : List.copyOf(h);
    }

    // ==================== UNDO / REDO ====================

    /** Undo the owner's last {@code count} jobs, newest first. */
    public void undo(Owner owner, int count) {
        if (!enabled) {
            owner.message(Colors.RED + "Undo is disabled in config.");
            return;
        }
        if (running.containsKey(owner.id())) {
            owner.message(Colors.YELLOW + "Jarvis: One moment, sir — I'm still putting the last lot back.");
            return;
        }
        Deque<Batch> h = history.get(owner.id());
        if (h == null || h.isEmpty()) {
            owner.message(Colors.GRAY + "No builds to undo.");
            return;
        }

        int n = Math.max(1, Math.min(count, h.size()));
        List<Batch> batches = new ArrayList<>(n);
        List<World> worlds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Batch b = h.peekFirst();
            World w = platform.world(b.world).orElse(null);
            if (w == null) {
                // Leave it, and everything older, where it is: undoing out of
                // order would skip a world rather than the newest job.
                owner.message(Colors.RED + "Jarvis: " + b.label + " is in " + b.world
                        + ", which isn't loaded; I've left it in the history.");
                break;
            }
            batches.add(h.removeFirst());
            worlds.add(w);
        }
        if (batches.isEmpty()) return;

        int total = batches.stream().mapToInt(Batch::size).sum();
        owner.message(Colors.YELLOW + "Jarvis: Putting back "
                + (batches.size() == 1 ? batches.get(0).label : batches.size() + " jobs")
                + Colors.GRAY + " (" + blocks(total) + ")...");

        List<List<Change>> redo = new ArrayList<>();
        int[] restored = {0}, skipped = {0};
        int[] batchIndex = {0}, actionIndex = {-1};
        List<Change> current = new ArrayList<>();

        Task task = platform.scheduler().every(1L, 1L, self -> {
            int done = 0;
            while (done < blocksPerTick && batchIndex[0] < batches.size()) {
                Batch b = batches.get(batchIndex[0]);
                List<BuildAction> actions = b.actions();
                if (actionIndex[0] < 0) actionIndex[0] = actions.size() - 1;   // newest placement first
                if (actionIndex[0] < 0) {                                       // an empty batch
                    finishBatch(owner, b, current, redo);
                    batchIndex[0]++;
                    actionIndex[0] = -1;
                    current.clear();
                    continue;
                }
                World w = worlds.get(batchIndex[0]);
                BuildAction a = actions.get(actionIndex[0]--);
                BlockState now = w.block(a.pos());
                if (now.id().equals(a.newId())) {
                    current.add(new Change(a.pos(), a.original(), now));
                    w.setBlock(a.pos(), a.original(), false);
                    restored[0]++;
                } else {
                    skipped[0]++;
                }
                done++;
                if (actionIndex[0] < 0) {
                    finishBatch(owner, b, current, redo);
                    batchIndex[0]++;
                    actionIndex[0] = -1;
                    current.clear();
                }
            }
            if (batchIndex[0] >= batches.size()) {
                self.cancel();
                running.remove(owner.id());
                redoable.put(owner.id(), List.copyOf(batches));
                redoChanges.put(owner.id(), redo);
                owner.message(Colors.GREEN + "Restored " + blocks(restored[0]) + "."
                        + (skipped[0] > 0 ? Colors.GRAY + " Skipped " + fmt(skipped[0])
                        + " changed since." : ""));
            }
        });
        running.put(owner.id(), task);
    }

    /** One job fully put back: demote its memory, forget its row, keep it for redo. */
    private void finishBatch(Owner owner, Batch b, List<Change> changes, List<List<Change>> redo) {
        redo.add(List.copyOf(changes));
        write(() -> deleteRow(b.key));
        // Reverting an AI build is the clearest "that plan was wrong" signal
        // there is. Schematics and hand shapes share this history, so the
        // flag matters.
        if (b.aiGenerated && !changes.isEmpty() && memory != null) {
            if (b.experience != null) {
                memory.markUndone(b.experience);
            } else if (b.experienceId > 0) {
                memory.markUndone(b.experienceId, owner.id());   // carried over from a restart
            } else {
                memory.markRecentBuildUndone(owner.id());
            }
        }
    }

    /** Put back what the last undo removed. One level. */
    public void redo(Owner owner) {
        if (!enabled) {
            owner.message(Colors.RED + "Undo is disabled in config.");
            return;
        }
        if (running.containsKey(owner.id())) {
            owner.message(Colors.YELLOW + "Jarvis: One moment, sir — I'm still at the last one.");
            return;
        }
        List<Batch> batches = redoable.remove(owner.id());
        List<List<Change>> changes = redoChanges.remove(owner.id());
        if (batches == null || changes == null) {
            owner.message(Colors.GRAY + "Nothing to redo.");
            return;
        }

        // Redo the oldest of what was undone first, so history ends up in
        // the order it was in.
        List<Integer> order = new ArrayList<>();
        for (int i = batches.size() - 1; i >= 0; i--) order.add(i);
        List<Recorder> recorders = new ArrayList<>();
        List<World> worlds = new ArrayList<>();
        for (int i : order) {
            Batch b = batches.get(i);
            World w = platform.world(b.world).orElse(null);
            worlds.add(w);
            Recorder r = new Recorder(b.owner, b.world, b.label, b.aiGenerated);
            r.setExperience(b.experience);
            recorders.add(r);
        }

        owner.message(Colors.YELLOW + "Jarvis: Putting it up again, sir...");
        int[] placed = {0}, skipped = {0};
        int[] k = {0}, idx = {-1};
        Task task = platform.scheduler().every(1L, 1L, self -> {
            int done = 0;
            while (done < blocksPerTick && k[0] < order.size()) {
                List<Change> list = changes.get(order.get(k[0]));
                World w = worlds.get(k[0]);
                if (idx[0] < 0) idx[0] = list.size() - 1;   // undo ran newest-first; reverse it
                if (idx[0] < 0 || w == null) {
                    k[0]++;
                    idx[0] = -1;
                    continue;
                }
                Change c = list.get(idx[0]--);
                BlockState now = w.block(c.pos());
                if (now.id().equals(c.expected().id())) {
                    recorders.get(k[0]).record(c.pos(), now, c.restore().id());
                    w.setBlock(c.pos(), c.restore(), false);
                    placed[0]++;
                } else {
                    skipped[0]++;
                }
                done++;
                if (idx[0] < 0) {
                    k[0]++;
                    idx[0] = -1;
                }
            }
            if (k[0] >= order.size()) {
                self.cancel();
                running.remove(owner.id());
                for (Recorder r : recorders) {
                    if (r.isEmpty()) continue;
                    push(new Batch(UUID.randomUUID().toString(), r.owner, r.world, r.label,
                            System.currentTimeMillis(), r.aiGenerated, r.actions.size(), r.experience,
                            -1, List.copyOf(r.actions), null));
                }
                owner.message(Colors.GREEN + "Replaced " + blocks(placed[0]) + "."
                        + (skipped[0] > 0 ? Colors.GRAY + " Skipped " + fmt(skipped[0])
                        + " changed since." : ""));
            }
        });
        running.put(owner.id(), task);
    }

    /** What the owner could undo, newest first. */
    public void list(Owner owner) {
        List<Batch> h = historyOf(owner);
        if (h.isEmpty()) {
            owner.message(Colors.GRAY + "No builds to undo.");
            return;
        }
        long now = System.currentTimeMillis();
        owner.message(Colors.GOLD + "Jarvis can undo, newest first:");
        int i = 1;
        for (Batch b : h) {
            owner.message(Colors.YELLOW + "  " + i++ + ". " + Colors.WHITE + b.label
                    + Colors.GRAY + " — " + blocks(b.size) + ", " + age(now - b.createdAt));
        }
        if (redoable.containsKey(owner.id())) {
            owner.message(Colors.GRAY + "  /jarvis redo puts back what was last undone.");
        }
        owner.message(Colors.GRAY + "  /jarvis undo [n] to undo the last n.");
    }

    /** True while an undo or redo is putting blocks back for this owner. */
    public boolean isRunning(Owner owner) {
        return running.containsKey(owner.id());
    }

    /** "1 block", "1,240 blocks". */
    static String blocks(int n) {
        return fmt(n) + (n == 1 ? " block" : " blocks");
    }

    static String fmt(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String age(long ms) {
        long min = ms / 60_000L;
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 48) return h + " h ago";
        return (h / 24) + " days ago";
    }

    // ==================== STORAGE ====================

    /** Queue a database write behind every earlier one. */
    private void write(Runnable op) {
        if (!persist) return;
        writes.add(op);
        platform.scheduler().async(this::drain);
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            Runnable op;
            while ((op = writes.poll()) != null) {
                try {
                    op.run();
                } catch (RuntimeException e) {
                    log.warn("Undo history write failed: " + e.getMessage());
                }
            }
        } finally {
            draining.set(false);
        }
        // A write queued while this one was finishing would otherwise wait
        // for the next.
        if (!writes.isEmpty()) drain();
    }

    /** Run every queued write now. Called at shutdown, before the database closes. */
    public void flush() {
        for (Task t : running.values()) t.cancel();
        running.clear();
        drain();
    }

    private void load() {
        if (database == null) return;
        Map<UUID, List<Batch>> byOwner = new HashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT batch_key, player_id, world, label, created_at, ai_generated, experience_id, "
                             + "block_count, actions FROM undo_batches ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID owner = UUID.fromString(rs.getString(2));
                long expId = rs.getLong(7);
                if (rs.wasNull()) expId = -1;
                Batch b = new Batch(rs.getString(1), owner, new WorldId(rs.getString(3)), rs.getString(4),
                        rs.getLong(5), rs.getInt(6) != 0, rs.getInt(8), null, expId, null, rs.getBytes(9));
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(b);
            }
            persist = true;
        } catch (SQLException | RuntimeException e) {
            log.warn("Undo history is in memory only this session: " + e.getMessage());
            return;
        }
        int loaded = 0;
        for (Map.Entry<UUID, List<Batch>> e : byOwner.entrySet()) {
            Deque<Batch> h = history(e.getKey());
            for (Batch b : e.getValue()) {
                if (h.size() < limit) {
                    h.addLast(b);
                    loaded++;
                } else {
                    write(() -> deleteRow(b.key));   // beyond the configured depth
                }
            }
        }
        if (loaded > 0) log.info("Undo history: " + loaded + " job(s) carried over from last session.");
    }

    private void insertRow(Batch b) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO undo_batches (batch_key, player_id, world, label, created_at, "
                             + "ai_generated, experience_id, block_count, actions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            long expId = b.experience != null ? b.experience.getId() : b.experienceId;
            ps.setString(1, b.key);
            ps.setString(2, b.owner.toString());
            ps.setString(3, b.world.id());
            ps.setString(4, b.label);
            ps.setLong(5, b.createdAt);
            ps.setInt(6, b.aiGenerated ? 1 : 0);
            if (expId > 0) ps.setLong(7, expId); else ps.setNull(7, java.sql.Types.BIGINT);
            ps.setInt(8, b.size);
            ps.setBytes(9, encode(b.actions()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not save undo history: " + e.getMessage());
        }
    }

    private void deleteRow(String key) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM undo_batches WHERE batch_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not trim undo history: " + e.getMessage());
        }
    }

    // ==================== ENCODING ====================

    /** One line per block, "x y z\toriginal\tnewId", gzipped. */
    static byte[] encode(List<BuildAction> actions) {
        StringBuilder sb = new StringBuilder(actions.size() * 40);
        for (BuildAction a : actions) {
            sb.append(a.pos().x()).append(' ').append(a.pos().y()).append(' ').append(a.pos().z())
              .append('\t').append(spec(a.original())).append('\t').append(a.newId()).append('\n');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);   // in memory; cannot happen
        }
        return out.toByteArray();
    }

    static List<BuildAction> decode(byte[] data) {
        if (data == null) return List.of();
        String text;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            text = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Corrupt undo history: " + e.getMessage(), e);
        }
        List<BuildAction> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] cols = line.split("\t");
            String[] xyz = cols[0].split(" ");
            out.add(new BuildAction(
                    new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])),
                    parseSpec(cols[1]), cols[2]));
        }
        return out;
    }

    /** {@code minecraft:oak_stairs[facing=north,half=top]}, properties sorted. */
    static String spec(BlockState s) {
        if (s.props().isEmpty()) return s.id();
        StringBuilder sb = new StringBuilder(s.id()).append('[');
        Iterator<Map.Entry<String, String>> it = new TreeMap<>(s.props()).entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            sb.append(e.getKey()).append('=').append(e.getValue());
            if (it.hasNext()) sb.append(',');
        }
        return sb.append(']').toString();
    }

    static BlockState parseSpec(String spec) {
        int open = spec.indexOf('[');
        if (open < 0) return BlockState.of(spec);
        Map<String, String> props = new HashMap<>();
        String body = spec.substring(open + 1, spec.endsWith("]") ? spec.length() - 1 : spec.length());
        for (String kv : body.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return new BlockState(spec.substring(0, open), props);
    }
}
