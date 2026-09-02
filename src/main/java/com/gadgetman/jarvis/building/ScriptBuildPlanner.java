package com.gadgetman.jarvis.building;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs an LLM-written JavaScript build script in a sandbox and collects the
 * blocks it asks for.
 *
 * <p>Why a script at all: asking a model for a JSON list of every block spends
 * the entire output budget on a floor. v0.8.6's best measured run was 168
 * explicit block entries — enough for a footprint and nothing else. The same
 * structure expressed as {@code fill}/{@code setBlock} calls is about a dozen
 * lines, because a wall is one call rather than four hundred coordinates. The
 * model then spends its reasoning on the shape instead of on arithmetic.
 *
 * <p><b>The script never touches the world.</b> {@code fill} and {@code
 * setBlock} are host functions that only append to a map held here, so a plan
 * is a pure function from text to a block list. That is what makes running
 * model-written code acceptable: the sandbox surface is two functions that put
 * entries in a LinkedHashMap, not a Bukkit handle. Everything downstream —
 * placement throttling, undo, material repair, experience memory — is unchanged
 * and still sees an ordinary list of blocks.
 *
 * <p>Guardrails, all verified against GraalJS 25.3.4.1 Community on a stock
 * JDK 25:
 * <ul>
 *   <li>{@code allowAllAccess(false)} — {@code Java.type} is not defined, so
 *       the script cannot reach a single host class.</li>
 *   <li>A block budget enforced inside the host functions. Throwing from a
 *       binding unwinds the script, so a runaway loop stops at the budget
 *       rather than filling memory.</li>
 *   <li>A wall-clock watchdog that calls {@code close(true)} from another
 *       thread, which cancels even {@code while(true){}}.</li>
 *   <li>Bounds and per-call volume limits, so a fill cannot span the world.</li>
 * </ul>
 */
public class ScriptBuildPlanner {

    /** A block the script asked for, in coordinates relative to the build origin. */
    public static class PlannedBlock {
        public final int dx, dy, dz;
        /** Raw block spec, e.g. {@code oak_stairs[facing=north]}. Resolved on the main thread. */
        public final String spec;

        PlannedBlock(int dx, int dy, int dz, String spec) {
            this.dx = dx; this.dy = dy; this.dz = dz; this.spec = spec;
        }
    }

    /**
     * Absolute world build limits for one run.
     *
     * <p>Passed per call rather than held on the planner: the planner is shared
     * and two players can be planning at the same time, so anything describing
     * one build has to travel with it.
     *
     * <p>The other limits are measured from the origin, which says nothing
     * about whether the result fits in the world. A tower asked for on a
     * mountain at y=250 stays inside maxVertical and still runs off the top of
     * the world, where the blocks never place.
     */
    public record WorldBounds(int originY, int minY, int maxY) {
        /** Permissive bounds, for callers with no world in hand (tests). */
        public static WorldBounds unbounded() {
            return new WorldBounds(0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        /** Lowest usable offset from the origin. */
        public int relativeMinY() {
            return minY == Integer.MIN_VALUE ? Integer.MIN_VALUE : minY - originY;
        }

        /** Highest usable offset from the origin. */
        public int relativeMaxY() {
            return maxY == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxY - 1 - originY;
        }

        /**
         * True when the origin leaves little enough room to be worth saying up
         * front. 96 blocks, because a tower or a spire can easily want more than
         * that: a mountain top at y=250 has only 69 blocks of ceiling left, and a
         * model told nothing will happily design past it and burn a repair round.
         * Ordinary ground has hundreds either way and gets no note.
         */
        public boolean isTight() {
            return relativeMinY() > -96 || relativeMaxY() < 96;
        }
    }

    /** Outcome of a successful run. */
    public static class Result {
        public final List<PlannedBlock> blocks;
        public final int fillCalls;
        public final int setBlockCalls;

        Result(List<PlannedBlock> blocks, int fillCalls, int setBlockCalls) {
            this.blocks = blocks;
            this.fillCalls = fillCalls;
            this.setBlockCalls = setBlockCalls;
        }
    }

    /**
     * A script that would not run. The message is written to be fed straight
     * back to the model as a repair prompt, so it says what was wrong in terms
     * the model can act on.
     */
    public static class ScriptException extends Exception {
        private final boolean syntaxError;

        ScriptException(String message, boolean syntaxError) {
            super(message);
            this.syntaxError = syntaxError;
        }

        public boolean isSyntaxError() { return syntaxError; }
    }

    /** Thrown from a host binding to unwind the script when a limit is hit. */
    private static class LimitExceeded extends RuntimeException {
        LimitExceeded(String message) { super(message); }
    }

    private final Logger log;
    private final int maxBlocks;
    private final int timeoutMs;
    private final int maxHorizontal;
    private final int maxVertical;
    private final int maxFillVolume;

    /**
     * Every placeable block id the server knows, lowercase and unqualified.
     *
     * <p>Validating here rather than at placement time is the difference
     * between a build and a pile of dirt. A model asking for
     * {@code blackstone_bricks} -- which is not a real block; the real one is
     * {@code polished_blackstone_bricks} -- used to fall through to the
     * fallback material silently: one live build came out 186 of 347 blocks
     * DIRT, walls and roof included. Rejecting the id instead sends it back
     * through the repair retry, where the model simply corrects it.
     *
     * <p>Built on the main thread by the caller, because it reads the registry.
     * Null disables validation.
     */
    private final Set<String> validBlocks;

    /**
     * Shared across builds so each one does not pay engine start-up. Measured
     * at ~1.3s for the first context on a stock JDK; contexts after that are
     * cheap. Created lazily so a server that never runs a script build never
     * initialises GraalJS at all.
     */
    private volatile Engine engine;

    public ScriptBuildPlanner(Logger log, int maxBlocks, int timeoutMs,
                              int maxHorizontal, int maxVertical, int maxFillVolume,
                              Set<String> validBlocks) {
        this.log = log;
        this.validBlocks = validBlocks;
        this.maxBlocks = maxBlocks;
        this.timeoutMs = timeoutMs;
        this.maxHorizontal = maxHorizontal;
        this.maxVertical = maxVertical;
        this.maxFillVolume = maxFillVolume;
    }

    /**
     * True when GraalJS is actually on the classpath.
     *
     * <p>The engine arrives through Paper's {@code libraries:} loader rather
     * than being shaded, so it can legitimately be missing — an offline server
     * on first start, for one. Callers probe with this and fall back to the
     * JSON planner instead of the plugin failing to load.
     */
    public static boolean isAvailable() {
        try {
            Class.forName("org.graalvm.polyglot.Context", false,
                    ScriptBuildPlanner.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Engine engine() {
        Engine local = engine;
        if (local == null) {
            synchronized (this) {
                local = engine;
                if (local == null) {
                    // Interpreted mode is expected: this is a stock JDK, not
                    // GraalVM. The warning is noise on every context, and build
                    // scripts are a few thousand operations — the JIT would
                    // never pay for itself here.
                    local = Engine.newBuilder()
                            .option("engine.WarnInterpreterOnly", "false")
                            .build();
                    engine = local;
                }
            }
        }
        return local;
    }

    /** Releases the shared engine. Safe to call when one was never created. */
    public void shutdown() {
        Engine local = engine;
        engine = null;
        if (local != null) {
            try {
                local.close();
            } catch (Throwable t) {
                log.fine("Engine close failed: " + t.getMessage());
            }
        }
    }

    /**
     * Runs a model-written script and returns the blocks it asked for.
     *
     * <p>Call this off the main thread — it blocks for as long as the script
     * runs, up to the watchdog timeout.
     *
     * @param script the model's JavaScript, which must define {@code buildCreation}
     * @throws ScriptException if the script will not parse, will not run, or
     *                         exceeded a limit
     */
    public Result run(String script) throws ScriptException {
        return run(script, WorldBounds.unbounded());
    }

    public Result run(String script, WorldBounds bounds) throws ScriptException {
        // Keyed by packed coordinate so the last write to a block wins. This is
        // not just deduplication: it is what lets a script carve a doorway by
        // filling the wall and then setting air over it, and it means
        // overlapping fills cost the budget once rather than twice.
        Map<Long, PlannedBlock> blocks = new LinkedHashMap<>();
        int[] counters = new int[2]; // 0 = fill calls, 1 = setBlock calls

        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Jarvis-script-watchdog");
            t.setDaemon(true);
            return t;
        });

        Context ctx = Context.newBuilder("js")
                .engine(engine())
                .allowAllAccess(false)   // implies HostAccess.NONE: no Java.type, no IO, no threads
                .build();

        // close(true) cancels a script mid-execution from another thread. This
        // is the only thing that stops `while(true){}` — a budget check cannot,
        // because a spinning loop never calls back into a binding.
        watchdog.schedule(() -> {
            try {
                ctx.close(true);
            } catch (Throwable ignored) {
                // Racing a normal close; nothing to do.
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        try {
            Value bindings = ctx.getBindings("js");
            bindings.putMember("setBlock", (ProxyExecutable) args -> {
                if (args.length < 4) {
                    throw new LimitExceeded("setBlock needs (x, y, z, block)");
                }
                counters[1]++;
                put(blocks, args[0].asInt(), args[1].asInt(), args[2].asInt(), args[3].asString(), bounds);
                return null;
            });
            bindings.putMember("fill", (ProxyExecutable) args -> {
                if (args.length < 7) {
                    throw new LimitExceeded("fill needs (x1, y1, z1, x2, y2, z2, block[, mode])");
                }
                counters[0]++;
                String mode = args.length > 7 && !args[7].isNull() ? args[7].asString() : "solid";
                fill(blocks,
                        args[0].asInt(), args[1].asInt(), args[2].asInt(),
                        args[3].asInt(), args[4].asInt(), args[5].asInt(),
                        args[6].asString(), mode, bounds);
                return null;
            });

            ctx.eval("js", script);

            Value entry = bindings.getMember("buildCreation");
            if (entry == null || !entry.canExecute()) {
                throw new ScriptException(
                        "The script did not define a callable buildCreation function.", false);
            }
            // Called at the origin so the script's coordinates are relative,
            // matching how the JSON planner has always been anchored. A script
            // that also calls itself at the bottom is harmless — the same
            // coordinates simply get written twice and collapse in the map.
            entry.execute(0, 0, 0);

        } catch (PolyglotException e) {
            throw translate(e);
        } catch (ScriptException e) {
            throw e;
        } catch (Throwable t) {
            throw new ScriptException("Script failed: " + t.getMessage(), false);
        } finally {
            watchdog.shutdownNow();
            try {
                ctx.close(true);
            } catch (Throwable ignored) {
                // Already closed by the watchdog.
            }
        }

        if (blocks.isEmpty()) {
            throw new ScriptException("The script ran but placed no blocks.", false);
        }
        return new Result(new ArrayList<>(blocks.values()), counters[0], counters[1]);
    }

    /**
     * Turns a GraalJS failure into a message worth showing a model.
     *
     * <p>A limit breach surfaces as a host exception wrapped by the engine, so
     * the real message has to be dug out of the cause — otherwise the model is
     * told "HostException" and cannot act on it.
     */
    private ScriptException translate(PolyglotException e) {
        if (e.isCancelled()) {
            return new ScriptException("The script ran longer than "
                    + (timeoutMs / 1000) + "s and was stopped. Avoid unbounded loops.", false);
        }
        if (e.isSyntaxError()) {
            return new ScriptException("The script is not valid JavaScript: " + e.getMessage(), true);
        }
        // asHostException() throws outright unless isHostException() is true --
        // a guest-level error such as a ReferenceError is not a host exception.
        Throwable cause = e.isHostException() ? e.asHostException() : e.getCause();
        while (cause != null && !(cause instanceof LimitExceeded)) {
            cause = cause.getCause();
        }
        if (cause != null) {
            return new ScriptException(cause.getMessage(), false);
        }
        return new ScriptException("The script threw: " + e.getMessage(), false);
    }

    /** Expands a box into blocks. Modes match the vanilla /fill vocabulary the model already knows. */
    private void fill(Map<Long, PlannedBlock> out, int x1, int y1, int z1,
                      int x2, int y2, int z2, String spec, String mode, WorldBounds bounds) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > maxFillVolume) {
            throw new LimitExceeded("A single fill asked for " + volume + " blocks, over the "
                    + maxFillVolume + " limit. Build it from smaller pieces.");
        }

        boolean hollow = "hollow".equalsIgnoreCase(mode);
        boolean outline = "outline".equalsIgnoreCase(mode);
        // "walls" exists because vanilla has no mode for what a building
        // actually needs. Models reach for "hollow" to mean walls -- one live
        // script commented a hollow fill as "Walls (hollow box)" -- but hollow
        // is a full shell, so its bottom face paves the floor the player is
        // standing on and its top face caps the room. That is what put every
        // floor and door a block too high.
        boolean walls = "walls".equalsIgnoreCase(mode);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (walls) {
                        if (x != minX && x != maxX && z != minZ && z != maxZ) continue;
                    } else if (hollow || outline) {
                        // How many axes sit on a face of the box. One means a
                        // face (shell), two means an edge.
                        int onFace = 0;
                        if (x == minX || x == maxX) onFace++;
                        if (y == minY || y == maxY) onFace++;
                        if (z == minZ || z == maxZ) onFace++;
                        if (outline ? onFace < 2 : onFace < 1) continue;
                    }
                    put(out, x, y, z, spec, bounds);
                }
            }
        }
    }

    private void put(Map<Long, PlannedBlock> out, int x, int y, int z, String spec, WorldBounds bounds) {
        if (Math.abs(x) > maxHorizontal || Math.abs(z) > maxHorizontal) {
            throw new LimitExceeded("Coordinate (" + x + "," + y + "," + z + ") is more than "
                    + maxHorizontal + " blocks from the origin. Keep the build near 0,0,0.");
        }
        if (Math.abs(y) > maxVertical) {
            throw new LimitExceeded("Height " + y + " is more than " + maxVertical
                    + " from the origin. Keep the build near y=0 and build upward.");
        }
        int worldY = bounds.originY() + y;
        if (worldY < bounds.minY() || worldY >= bounds.maxY()) {
            // Naming the world range alone is not actionable: the script works in
            // coordinates relative to the origin and has no idea where that sits,
            // so it cannot convert. A live build failed twice with the identical
            // error for exactly that reason. Give it the relative range instead.
            throw new LimitExceeded("Height " + y + " puts a block at world y=" + worldY
                    + ", " + (worldY < bounds.minY() ? "below" : "above")
                    + " this world's limit. From this origin you may only use y between "
                    + bounds.relativeMinY() + " and " + bounds.relativeMaxY()
                    + ". Redesign to fit that range.");
        }
        if (spec == null || spec.isBlank()) {
            throw new LimitExceeded("A block id was empty at (" + x + "," + y + "," + z + ").");
        }
        if (validBlocks != null) {
            String base = baseName(spec);
            if (!validBlocks.contains(base)) {
                String hint = suggest(base);
                throw new LimitExceeded("'" + base + "' is not a real Minecraft block id."
                        + (hint.isEmpty() ? " Use only ids this server knows."
                                          : " Did you mean: " + hint + "?"));
            }
        }

        String base = baseName(spec);

        // A torch, button, lever, sign or ladder hangs on a block; it must never
        // replace one. Models write the torch at the wall's own coordinate --
        // `setBlock(x+1, y+2, z, "wall_torch[facing=south]")` where z IS the wall
        // -- and last-write-wins then deletes the wall, leaving a hole straight
        // through to the sky with a torch sitting in it.
        //
        // The fix follows from what the block needs: a wall torch is supported by
        // the block behind it, opposite its facing. Shifting it one block ALONG
        // its facing puts it in the open air of the room with the wall it was
        // meant to hang on now directly behind it.
        Long here = key(x, y, z);
        PlannedBlock existing = out.get(here);
        if (isAttachment(base) && existing != null && !"air".equals(baseName(existing.spec))) {
            int[] dir = facingOf(spec);
            if (dir == null) return; // nowhere sensible to move it; a missing torch beats a hole
            int tx = x + dir[0], ty = y + dir[1], tz = z + dir[2];
            Long target = key(tx, ty, tz);
            PlannedBlock occupant = out.get(target);
            if (occupant != null && !"air".equals(baseName(occupant.spec))) return;
            x = tx; y = ty; z = tz;
        }

        Long key = key(x, y, z);
        // Overwrites are free; only a new coordinate spends budget.
        if (!out.containsKey(key) && out.size() >= maxBlocks) {
            throw new LimitExceeded("The build exceeded the " + maxBlocks
                    + " block budget. Design something smaller or less solid.");
        }
        out.put(key, new PlannedBlock(x, y, z, spec.trim()));
    }

    /**
     * Nearest real block ids to one the model invented.
     *
     * <p>Saying only that an id is wrong is not enough to repair it. A live
     * build asked for {@code dark_oak_bed} twice in a row and failed both
     * times, because nothing in the error hinted that beds are coloured rather
     * than wood-typed. Ranking the real ids that share the invented one's last
     * word turns that into "did you mean red_bed, black_bed", which is
     * actionable in one round.
     */
    private String suggest(String base) {
        if (validBlocks == null || base.isEmpty()) return "";
        String[] parts = base.split("_");
        String noun = parts[parts.length - 1];
        Set<String> words = new HashSet<>(Arrays.asList(parts));

        List<String> hits = new ArrayList<>();
        // Singular/plural first, because it is the most common near-miss of all:
        // `brick` for `bricks`, `stone_brick` for `stone_bricks`.
        String plural = base + "s";
        String singular = base.endsWith("s") ? base.substring(0, base.length() - 1) : null;
        if (validBlocks.contains(plural)) hits.add(plural);
        if (singular != null && validBlocks.contains(singular)) hits.add(singular);

        for (String candidate : validBlocks) {
            if (hits.contains(candidate)) continue;
            if (candidate.equals(noun) || candidate.endsWith("_" + noun)) hits.add(candidate);
        }
        if (hits.isEmpty()) {
            // Nothing shares the noun, so fall back to anything sharing any word
            // -- "white_wool_slab" should still surface the wool blocks.
            for (String candidate : validBlocks) {
                for (String w : words) {
                    if (w.length() > 2 && candidate.contains(w)) { hits.add(candidate); break; }
                }
            }
        }
        // Most words in common first, then shortest, so the plainest form leads.
        // The singular/plural match, if there was one, stays pinned at the front.
        int pinned = 0;
        if (validBlocks.contains(plural)) pinned++;
        if (singular != null && validBlocks.contains(singular)) pinned++;
        List<String> ranked = hits.subList(pinned, hits.size());
        ranked.sort(Comparator
                .comparingInt((String c) -> -sharedWords(c, words))
                .thenComparingInt(String::length));
        return String.join(", ", hits.subList(0, Math.min(6, hits.size())));
    }

    private static int sharedWords(String candidate, Set<String> words) {
        int n = 0;
        for (String w : candidate.split("_")) if (words.contains(w)) n++;
        return n;
    }

    /** Block id with the namespace and any block states stripped. */
    private static String baseName(String spec) {
        String b = spec.trim().toLowerCase();
        int br = b.indexOf('[');
        if (br >= 0) b = b.substring(0, br);
        return b.startsWith("minecraft:") ? b.substring(10) : b;
    }

    /** True for blocks that hang on a neighbour rather than standing on their own. */
    private static boolean isAttachment(String base) {
        return base.equals("torch") || base.endsWith("_torch")
                || base.endsWith("_button") || base.equals("lever")
                || base.endsWith("_sign") || base.equals("ladder")
                || base.endsWith("_banner");
    }

    /** The offset named by a spec's {@code facing} state, or null if it has none. */
    private static int[] facingOf(String spec) {
        java.util.regex.Matcher m = FACING.matcher(spec);
        if (!m.find()) return null;
        return switch (m.group(1)) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "east"  -> new int[]{1, 0, 0};
            case "west"  -> new int[]{-1, 0, 0};
            case "up"    -> new int[]{0, 1, 0};
            case "down"  -> new int[]{0, -1, 0};
            default -> null;
        };
    }

    private static final java.util.regex.Pattern FACING =
            java.util.regex.Pattern.compile("facing=([a-z]+)");

    /** Packs a relative coordinate into one long. Ranges are bounded by the checks in {@link #put}. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }
}
