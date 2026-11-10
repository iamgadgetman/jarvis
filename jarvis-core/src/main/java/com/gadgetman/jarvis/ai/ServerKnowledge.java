package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-written facts about the server, from {@code knowledge.yml} in the data
 * folder (FB-03). They go into the chat system prompt as things to treat as
 * true, so Jarvis stops inventing server rules and can say where the market is.
 *
 * <pre>
 * facts:
 *   - "PvP is off"
 * locations:
 *   market: {x: 120, y: 70, z: -40, note: "player shops"}
 *   farm: "east of spawn, past the river"
 * </pre>
 *
 * A missing or broken file is an empty block; it never stops Jarvis.
 */
public final class ServerKnowledge {

    public static final ServerKnowledge EMPTY = new ServerKnowledge(List.of(), Map.of());

    /** Caps so a huge file can't swamp a small local model's context. */
    static final int MAX_FACTS = 40;
    static final int MAX_LOCATIONS = 40;
    static final int MAX_LINE = 200;

    private final List<String> facts;
    private final Map<String, String> locations;
    /** Location names in file order (Map.copyOf loses it). */
    private final List<String> order;

    private ServerKnowledge(List<String> facts, Map<String, String> locations) {
        this.facts = List.copyOf(facts);
        this.locations = Map.copyOf(locations);
        this.order = new ArrayList<>(locations.keySet());
    }

    /** Load a file. Missing or unreadable → {@link #EMPTY}, with a warning for a broken one. */
    public static ServerKnowledge load(Path file, Log log) {
        if (file == null || !Files.exists(file)) return EMPTY;
        try {
            return from(YamlConfig.load(file));
        } catch (Exception e) {
            if (log != null) log.warn("Could not read " + file.getFileName() + ": " + e.getMessage()
                    + " - server facts are off until it is fixed.");
            return EMPTY;
        }
    }

    /** Parse YAML text. Broken text → {@link #EMPTY}. */
    public static ServerKnowledge parse(String yaml) {
        try {
            return from(YamlConfig.parse(yaml));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static ServerKnowledge from(Config c) {
        List<String> facts = new ArrayList<>();
        for (String f : c.getStringList("facts")) {
            if (f == null || f.isBlank()) continue;
            if (facts.size() >= MAX_FACTS) break;
            facts.add(clip(f.trim()));
        }
        Map<String, String> locations = new LinkedHashMap<>();
        if (c.isSection("locations")) {
            Config locs = c.section("locations");
            for (String name : locs.keys()) {
                if (locations.size() >= MAX_LOCATIONS) break;
                String text = describe(locs, name);
                if (text != null) locations.put(name, clip(text));
            }
        }
        return facts.isEmpty() && locations.isEmpty() ? EMPTY : new ServerKnowledge(facts, locations);
    }

    /** "x 120, y 70, z -40 (player shops)" for a section, or the plain string as written. */
    private static String describe(Config locs, String name) {
        if (!locs.isSection(name)) {
            String plain = locs.getString(name, null);
            return plain == null || plain.isBlank() ? null : plain.trim();
        }
        Config l = locs.section(name);
        String note = l.getString("note", "");
        boolean hasCoords = l.contains("x") && l.contains("z");
        String where = hasCoords
                ? "x " + l.getInt("x", 0) + (l.contains("y") ? ", y " + l.getInt("y", 0) : "") + ", z " + l.getInt("z", 0)
                : "";
        String world = l.getString("world", "");
        if (!world.isBlank()) where = where.isEmpty() ? "in " + world : where + " in " + world;
        if (where.isEmpty()) return note.isBlank() ? null : note;
        return note.isBlank() ? where : where + " (" + note + ")";
    }

    private static String clip(String s) {
        return s.length() <= MAX_LINE ? s : s.substring(0, MAX_LINE) + "...";
    }

    public List<String> facts() {
        return facts;
    }

    public Map<String, String> locations() {
        return locations;
    }

    public boolean isEmpty() {
        return facts.isEmpty() && locations.isEmpty();
    }

    /** The prompt block, or "" when there is nothing to say. */
    public String render() {
        if (isEmpty()) return "";
        StringBuilder sb = new StringBuilder("SERVER FACTS (written by the server admin; treat as true, never contradict):\n");
        for (String f : facts) sb.append("  - ").append(f).append('\n');
        if (!locations.isEmpty()) {
            sb.append("NAMED LOCATIONS:\n");
            for (String name : order) sb.append("  - ").append(name).append(": ").append(locations.get(name)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
