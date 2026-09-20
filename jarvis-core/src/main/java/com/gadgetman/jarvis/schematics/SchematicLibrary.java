package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.npc.ButlerService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The schematic folder: what is in it, which file answers a request, and
 * pasting one through the platform's world with the native reader.
 *
 * <p>Formats the native reader cannot place (the old JSON ones) go to an
 * {@link Accelerator} when the adapter supplies one; on Paper that is
 * WorldEdit. Without one they are reported as unsupported.
 */
public class SchematicLibrary {

    /** A backend that can paste formats the native reader cannot. */
    public interface Accelerator {
        /** The name shown to players: "WorldEdit". */
        String name();

        void pasteJson(Owner player, SchematicInfo info);
    }

    private final Platform platform;
    private final Log log;
    private final ButlerService butlers;
    private final Path schematicFolder;
    private final Map<String, SchematicInfo> availableSchematics = new HashMap<>();
    private final LitematicConverter litematicConverter;
    private final SchemReader schemReader;
    private Accelerator accelerator;

    public SchematicLibrary(Platform platform, ButlerService butlers) {
        this.platform = platform;
        this.log = platform.log();
        this.butlers = butlers;
        this.schematicFolder = platform.dataDir().resolve("schematics");
        initializeFolder();
        this.litematicConverter = new LitematicConverter(log, platform.scheduler(), schematicFolder, this::scanFolder);
        this.schemReader = new SchemReader(log);
        scanFolder();

        // Check for unconverted litematic files
        int litematicCount = litematicConverter.listLitematicFiles().size();
        log.info("Schematic library initialized with " + availableSchematics.size() + " schematics"
                + (litematicCount > 0 ? " [" + litematicCount + " .litematic files available for conversion]" : ""));
    }

    /** Give the library a backend for the formats it cannot place itself. */
    public void setAccelerator(Accelerator accelerator) {
        this.accelerator = accelerator;
    }

    public boolean hasAccelerator() {
        return accelerator != null;
    }

    private void initializeFolder() {
        try {
            Files.createDirectories(schematicFolder);
        } catch (IOException e) {
            log.warn("Could not create schematics folder: " + e.getMessage());
        }
    }

    // ==================== DATA STRUCTURES ====================

    public enum Format {
        WORLDEDIT_SCHEM,     // .schem (Sponge format)
        WORLDEDIT_SCHEMATIC, // .schematic (MCEdit format)
        JSON                 // .json (Jarvis format)
    }

    public record SchematicInfo(String name, String fileName, Path file, Format format, long fileSize) { }

    // ==================== FOLDER SCANNING ====================

    /** Scan the schematics folder for all supported formats. */
    public void scanFolder() {
        availableSchematics.clear();

        List<Path> files;
        try (Stream<Path> s = Files.list(schematicFolder)) {
            files = s.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not list schematics folder: " + e.getMessage());
            return;
        }

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String lower = fileName.toLowerCase(Locale.ROOT);
            Format format;
            String name;

            if (lower.endsWith(".schem")) {
                format = Format.WORLDEDIT_SCHEM;
                name = fileName.substring(0, fileName.length() - ".schem".length());
            } else if (lower.endsWith(".schematic")) {
                format = Format.WORLDEDIT_SCHEMATIC;
                name = fileName.substring(0, fileName.length() - ".schematic".length());
            } else if (lower.endsWith(".json")) {
                format = Format.JSON;
                name = fileName.substring(0, fileName.length() - ".json".length());
            } else {
                continue; // Skip unsupported formats
            }

            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                size = 0;
            }
            availableSchematics.put(name.toLowerCase(Locale.ROOT),
                    new SchematicInfo(name, fileName, file, format, size));
        }

        log.info("Found " + availableSchematics.size() + " schematics in folder");
    }

    // ==================== LISTING ====================

    /** List available schematics. */
    public void listSchematics(Owner player) {
        scanFolder(); // Refresh list

        if (availableSchematics.isEmpty()) {
            player.message(Colors.YELLOW + "No schematics available.");
            player.message(Colors.GRAY + "Add .schem or .schematic files to:");
            player.message(Colors.WHITE + schematicFolder.toString());
            return;
        }

        player.message("");
        player.message(Colors.GREEN + "======== Available Schematics ========");

        for (SchematicInfo info : availableSchematics.values()) {
            String formatStr = switch (info.format()) {
                case WORLDEDIT_SCHEM -> Colors.AQUA + "[SCHEM]";
                case WORLDEDIT_SCHEMATIC -> Colors.AQUA + "[SCHEMATIC]";
                case JSON -> Colors.YELLOW + "[JSON]";
            };

            String sizeStr = formatFileSize(info.fileSize());
            player.message(formatStr + " " + Colors.GOLD + info.name() + Colors.GRAY + " (" + sizeStr + ")");
        }

        player.message("");
        player.message(Colors.GRAY + "Use: /jarvis schematic paste <name>");
        if (accelerator != null) {
            player.message(Colors.GREEN + accelerator.name() + ": " + Colors.WHITE + "Enabled");
        } else {
            player.message(Colors.RED + "WorldEdit: " + Colors.WHITE + "Not found (limited features)");
        }
        player.message(Colors.GREEN + "====================================");
    }

    // ==================== MATCHING ====================

    /**
     * Score how well a schematic name answers a query, 0 (no match) to 100.
     *
     * Replaces the old "return the first map entry whose key contains the
     * query" walk, which was order-dependent: a short query like "a" is a
     * substring of nearly every name, so it returned whatever HashMap
     * iteration happened to yield first — the same schematic every time,
     * regardless of what was asked for.
     */
    static int scoreMatch(String key, String query) {
        if (key == null || query == null) return 0;
        if (key.equals(query)) return 100;

        Set<String> qt = meaningfulWords(query);
        Set<String> kt = meaningfulWords(key);
        if (qt.isEmpty() || kt.isEmpty()) return 0;

        int overlap = 0;
        for (String q : qt) {
            for (String k : kt) {
                if (k.equals(q) || k.contains(q) || q.contains(k)) { overlap++; break; }
            }
        }
        if (overlap == 0) return 0;

        // Fraction of what was ASKED for that the name accounts for. Keying on
        // the query, not the name, stops a long name matching everything.
        int score = (int) Math.round(90.0 * overlap / qt.size());
        if (key.contains(query)) score += 9;
        return Math.min(99, score);
    }

    private static Set<String> meaningfulWords(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !SCHEMATIC_STOPWORDS.contains(w)) out.add(w);
        }
        return out;
    }

    private static final Set<String> SCHEMATIC_STOPWORDS = Set.of(
            "the", "and", "for", "with", "build", "make", "create", "please", "one");

    /** Best-scoring schematic for a query, or null if nothing scores at all. */
    private Map.Entry<String, SchematicInfo> bestMatch(String query) {
        String lower = query.toLowerCase(Locale.ROOT).trim();
        Map.Entry<String, SchematicInfo> best = null;
        int bestScore = 0;
        for (Map.Entry<String, SchematicInfo> e : availableSchematics.entrySet()) {
            int sc = scoreMatch(e.getKey(), lower);
            // Tie-break toward the shorter (more specific) name.
            if (sc > bestScore || (sc == bestScore && sc > 0 && best != null
                    && e.getKey().length() < best.getKey().length())) {
                bestScore = sc;
                best = e;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /**
     * Score a schematic name against a request that has been decomposed,
     * taking whichever of the two readings is stronger.
     *
     * <p>Measured: "somewhere to store my loot" against {@code storage_shed}
     * scores <b>zero</b> on the raw wording -- "store" is not a substring of
     * "storage" in either direction, so the schematic is not merely ranked low,
     * it is invisible. Decomposed to purpose {@code storage} and kind
     * {@code shed}, it scores 90.
     *
     * <p>The parts are not worth the same, and finding that out is what this
     * scoring is built on. Against llama3.2:3b, weighting a flat tag list
     * equally let a style word landing on a name by coincidence -- "small"
     * hitting {@code small_warehouse} -- beat the purpose that answered the
     * request. So: the <b>kind</b> is worth most, because naming a structure is
     * more specific than naming a use; the <b>purpose</b> is worth less; and
     * the <b>style</b> cannot carry a match at all, only break a tie between
     * two names that already matched. Kind-weighting scored 18/21 against the
     * flat 16/21 on the same decompositions.
     *
     * <p>The raw score is still consulted, because a player who names a
     * schematic outright should get that schematic.
     */
    static int scoreWithFeatures(String key, String query, RequestFeatures features) {
        int raw = scoreMatch(key, query);
        if (features == null || features.isEmpty()) return raw;

        Set<String> kt = meaningfulWords(key);
        if (kt.isEmpty()) return raw;

        boolean purposeHit = hits(features.purpose(), kt);
        boolean kindHit = hits(features.kind(), kt);
        if (!purposeHit && !kindHit) return raw;

        int score = 55 + (purposeHit ? 15 : 0) + (kindHit ? 20 : 0);
        if (hits(features.style(), kt)) score += 4;
        // The ceiling stays below the 100 reserved for an exact name.
        return Math.max(raw, Math.min(99, score));
    }

    /** Does one feature word answer any word of a schematic name? */
    private static boolean hits(String feature, Set<String> nameWords) {
        if (feature == null || feature.isEmpty()) return false;
        for (String w : nameWords) {
            if (w.equals(feature) || w.contains(feature) || feature.contains(w)) return true;
        }
        return false;
    }

    /**
     * Best schematic for a decomposed request: the name, or null if nothing
     * scores at all. {@code features} may be empty, in which case this is
     * exactly the raw-wording match that came before it.
     */
    public String bestMatchName(String query, RequestFeatures features) {
        String lower = query.toLowerCase(Locale.ROOT).trim();
        String best = null;
        int bestScore = 0;
        for (String key : availableSchematics.keySet()) {
            int sc = scoreWithFeatures(key, lower, features);
            // Tie-break toward the shorter (more specific) name.
            if (sc > bestScore || (sc == bestScore && sc > 0 && best != null
                    && key.length() < best.length())) {
                bestScore = sc;
                best = key;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /** Score of the best decomposed match, 0 if none. */
    public int bestMatchScore(String query, RequestFeatures features) {
        String lower = query.toLowerCase(Locale.ROOT).trim();
        int bestScore = 0;
        for (String key : availableSchematics.keySet()) {
            bestScore = Math.max(bestScore, scoreWithFeatures(key, lower, features));
        }
        return bestScore;
    }

    /** Score of the best match, 0 if none. */
    public int bestMatchScore(String query) {
        String lower = query.toLowerCase(Locale.ROOT).trim();
        int bestScore = 0;
        for (String key : availableSchematics.keySet()) {
            bestScore = Math.max(bestScore, scoreMatch(key, lower));
        }
        return bestScore;
    }

    /** Name of the best match, or null. */
    public String bestMatchName(String query) {
        Map.Entry<String, SchematicInfo> e = bestMatch(query);
        return e == null ? null : e.getKey();
    }

    /** Exact name, then a rescan, then the best-scoring match. Null when nothing fits. */
    public SchematicInfo findSchematic(String name) {
        String lower = name.toLowerCase(Locale.ROOT).trim();

        // 1. Exact match (already lowercased key in map)
        SchematicInfo info = availableSchematics.get(lower);
        if (info != null) return info;

        // Refresh and retry exact
        scanFolder();
        info = availableSchematics.get(lower);
        if (info != null) return info;

        // 2. Best scoring match, rather than the first arbitrary containment hit
        Map.Entry<String, SchematicInfo> best = bestMatch(lower);
        return best == null ? null : best.getValue();
    }

    /** Tell the player a name was not found, with a few names that start the same way. */
    public void reportNotFound(Owner player, String name) {
        player.message(Colors.RED + "Schematic not found: " + name);
        String n = name.toLowerCase(Locale.ROOT);
        String stem = n.substring(0, Math.min(3, n.length()));
        List<String> suggestions = availableSchematics.keySet().stream()
                .filter(k -> k.contains(stem))
                .sorted()
                .limit(5)
                .collect(Collectors.toList());
        if (!suggestions.isEmpty()) {
            player.message(Colors.GRAY + "Did you mean: " + Colors.YELLOW
                    + String.join(Colors.GRAY + ", " + Colors.YELLOW, suggestions) + "?");
        } else {
            player.message(Colors.GRAY + "Use /jarvis schematic list to see available schematics");
        }
    }

    // ==================== PASTING ====================

    /**
     * Paste a schematic at the player's location.
     * Accepts fuzzy names — e.g. "castle" matches "gadgets_castle_v2".
     */
    public void pasteSchematic(Owner player, String name) {
        SchematicInfo info = findSchematic(name);

        if (info == null) {
            reportNotFound(player, name);
            return;
        }

        // Check if NPC is summoned
        if (!butlers.exists(player)) {
            player.message(Colors.RED + "Summon Jarvis first with /jarvis summon");
            return;
        }

        // Use native reader for .schem and .schematic files
        if (info.format() == Format.WORLDEDIT_SCHEM || info.format() == Format.WORLDEDIT_SCHEMATIC) {
            player.message(Colors.YELLOW + "Jarvis: Pasting schematic: " + Colors.WHITE + info.name());
            pasteNative(player, info.file());
            return;
        }

        // For JSON format, only an accelerator can help
        if (info.format() == Format.JSON) {
            if (accelerator != null) {
                player.message(Colors.YELLOW + "Jarvis: Pasting JSON schematic via " + accelerator.name() + "...");
                accelerator.pasteJson(player, info);
            } else {
                player.message(Colors.RED + "JSON schematics require WorldEdit.");
                player.message(Colors.GRAY + "Convert to .schem format or install WorldEdit.");
            }
            return;
        }

        player.message(Colors.RED + "Unsupported schematic format.");
    }

    /** Load a .schem off-thread, then place it where the player stands, a few blocks per tick. */
    private void pasteNative(Owner player, Path schematicFile) {
        player.message(Colors.YELLOW + "Jarvis: Loading schematic: " + schematicFile.getFileName());

        platform.scheduler().async(() -> {
            try {
                SchemReader.SchematicData data = schemReader.read(schematicFile);
                platform.scheduler().sync(() -> placeSchematic(player, data));
            } catch (Exception e) {
                log.error("Failed to load schematic: " + e.getMessage(), e);
                platform.scheduler().sync(() ->
                        player.message(Colors.RED + "Error loading schematic: " + e.getMessage()));
            }
        });
    }

    /** Place schematic blocks in the world. Server thread. */
    private void placeSchematic(Owner player, SchemReader.SchematicData data) {
        World world = platform.world(player.world()).orElse(null);
        if (world == null) return;
        BlockPos origin = player.pos().block();
        int blocksPerTick = platform.config().getInt("build.blocks-per-tick", 50);

        // Resolve the palette once, through the registry, rather than every block.
        BlockState[] palette = new BlockState[data.palette.length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = resolve(data.palette[i]);
        }

        final int total = data.volume();
        player.message(Colors.GREEN + "Placing " + total + " blocks ("
                + data.width + "x" + data.height + "x" + data.length + ")...");

        final int[] placed = {0};
        final int[] index = {0};
        final long startTime = System.currentTimeMillis();

        platform.scheduler().every(1L, 1L, self -> {
            if (!player.isOnline()) {
                self.cancel();
                return;
            }

            int thisTickPlaced = 0;

            while (index[0] < total && thisTickPlaced < blocksPerTick) {
                // Calculate position (Y, Z, X order in sponge schematic)
                int y = index[0] / (data.width * data.length);
                int remainder = index[0] % (data.width * data.length);
                int z = remainder / data.width;
                int x = remainder % data.width;

                int paletteIndex = data.blocks[index[0]];

                if (paletteIndex >= 0 && paletteIndex < palette.length) {
                    BlockState state = palette[paletteIndex];
                    if (state != null && !state.isAir()) {
                        try {
                            world.setBlock(origin.offset(x + data.offsetX, y + data.offsetY, z + data.offsetZ),
                                    state, false);
                            placed[0]++;
                        } catch (Exception e) {
                            // Skip invalid blocks
                        }
                    }
                }

                index[0]++;
                thisTickPlaced++;
            }

            // Progress update every 500 blocks
            if (placed[0] > 0 && placed[0] % 500 == 0) {
                int percent = (index[0] * 100) / total;
                player.message(Colors.YELLOW + "Progress: " + percent + "% (" + placed[0] + " blocks placed)");
            }

            // Check completion
            if (index[0] >= total) {
                long duration = (System.currentTimeMillis() - startTime) / 1000;
                player.message("");
                player.message(Colors.GREEN + "========================================");
                player.message(Colors.GOLD + "  Schematic Complete!");
                player.message(Colors.GREEN + "========================================");
                player.message(Colors.WHITE + "  File: " + data.fileName);
                player.message(Colors.WHITE + "  Blocks placed: " + placed[0]);
                player.message(Colors.WHITE + "  Time: " + duration + " seconds");
                player.message(Colors.GREEN + "========================================");
                player.sound(Ids.SOUND_ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                self.cancel();
            }
        });
    }

    /**
     * A palette entry as a placeable state: the full spec, then the bare id
     * when the properties do not apply on this server, then null.
     */
    private BlockState resolve(String blockState) {
        if (blockState == null) return null;
        String spec = blockState.contains(":") ? blockState : "minecraft:" + blockState;
        BlockState full = platform.blockTypes().parse(spec).orElse(null);
        if (full != null) return full;
        int bracket = spec.indexOf('[');
        if (bracket > 0) {
            return platform.blockTypes().parse(spec.substring(0, bracket)).orElse(null);
        }
        return null;
    }

    // ==================== UTILITY ====================

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ==================== GETTERS ====================

    public Path getSchematicFolder() {
        return schematicFolder;
    }

    public Collection<SchematicInfo> getSchematics() {
        return availableSchematics.values();
    }

    public SchematicInfo getSchematic(String name) {
        return availableSchematics.get(name.toLowerCase(Locale.ROOT));
    }

    public int getSchematicCount() {
        return availableSchematics.size();
    }

    // ==================== LITEMATIC CONVERSION ====================

    /** Convert a .litematic file to .schem format. */
    public void convertLitematic(Owner player, String name) {
        litematicConverter.convert(player, name);
    }

    /** Convert all .litematic files in the folder. */
    public void convertAllLitematics(Owner player) {
        litematicConverter.convertAll(player);
    }

    /** List available .litematic files. */
    public List<String> listLitematicFiles() {
        return litematicConverter.listLitematicFiles();
    }

    /** Show litematic files to player. */
    public void showLitematicFiles(Owner player) {
        List<String> files = litematicConverter.listLitematicFiles();

        if (files.isEmpty()) {
            player.message(Colors.YELLOW + "No .litematic files found.");
            player.message(Colors.GRAY + "Place .litematic files in: " + schematicFolder);
            return;
        }

        player.message("");
        player.message(Colors.GOLD + "======== Litematic Files ========");
        for (String file : files) {
            player.message(Colors.YELLOW + "  [LITEMATIC] " + Colors.WHITE + file);
        }
        player.message("");
        player.message(Colors.GRAY + "Convert one: /jarvis schematic convert <name>");
        player.message(Colors.GRAY + "Convert all: /jarvis schematic convertall");
        player.message(Colors.GOLD + "================================");
    }
}
