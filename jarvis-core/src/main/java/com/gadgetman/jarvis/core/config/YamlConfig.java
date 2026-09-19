package com.gadgetman.jarvis.core.config;

import com.gadgetman.jarvis.core.platform.Config;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Config} read from a YAML document with snakeyaml.
 *
 * <p>This is core's own reader, so an adapter without a config API of its
 * own (Fabric) can load config.yml exactly as the plugin does. An optional
 * fallback supplies defaults for anything the document leaves out, the way
 * the plugin layers the bundled config.yml under the operator's copy.
 */
public final class YamlConfig implements Config {

    private final Map<String, Object> root;
    private final Config fallback;

    private YamlConfig(Map<String, Object> root, Config fallback) {
        this.root = root;
        this.fallback = fallback;
    }

    public static YamlConfig empty() {
        return new YamlConfig(Map.of(), null);
    }

    /** Parse a document. An empty or non-mapping document is an empty config. */
    public static YamlConfig parse(String yaml) {
        return parse(yaml, null);
    }

    public static YamlConfig parse(String yaml, Config fallback) {
        Yaml loader = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object doc = loader.load(yaml == null ? "" : yaml);
        return new YamlConfig(asMap(doc), fallback);
    }

    /** Load a file. A missing file is an empty config, as the plugin treats it. */
    public static YamlConfig load(Path file) throws IOException {
        return load(file, null);
    }

    public static YamlConfig load(Path file, Config fallback) throws IOException {
        if (file == null || !Files.exists(file)) return new YamlConfig(Map.of(), fallback);
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Yaml loader = new Yaml(new SafeConstructor(new LoaderOptions()));
            return new YamlConfig(asMap(loader.load(in)), fallback);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            // snakeyaml gives String keys for ordinary documents; anything else
            // (an integer key, say) is coerced so dotted paths still work.
            if (m.keySet().stream().allMatch(k -> k instanceof String)) {
                return (Map<String, Object>) m;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    /** Walk a dotted path. Returns null when any segment is missing. */
    private Object lookup(String path) {
        if (path == null || path.isEmpty()) return root;
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
            if (cur == null) return null;
        }
        return cur;
    }

    @Override
    public String getString(String path, String def) {
        Object v = lookup(path);
        if (v == null || v instanceof Map || v instanceof List) {
            return fallback != null ? fallback.getString(path, def) : def;
        }
        return String.valueOf(v);
    }

    @Override
    public int getInt(String path, int def) {
        Object v = lookup(path);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback != null ? fallback.getInt(path, def) : def;
    }

    @Override
    public long getLong(String path, long def) {
        Object v = lookup(path);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback != null ? fallback.getLong(path, def) : def;
    }

    @Override
    public double getDouble(String path, double def) {
        Object v = lookup(path);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback != null ? fallback.getDouble(path, def) : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object v = lookup(path);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return fallback != null ? fallback.getBoolean(path, def) : def;
    }

    @Override
    public List<String> getStringList(String path) {
        Object v = lookup(path);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        return fallback != null ? fallback.getStringList(path) : Collections.emptyList();
    }

    @Override
    public boolean contains(String path) {
        return lookup(path) != null || (fallback != null && fallback.contains(path));
    }

    @Override
    public boolean isSection(String path) {
        return lookup(path) instanceof Map || (fallback != null && fallback.isSection(path));
    }

    @Override
    public Set<String> keys() {
        Set<String> out = new LinkedHashSet<>(root.keySet());
        if (fallback != null) out.addAll(fallback.keys());
        return out;
    }

    @Override
    public Config section(String path) {
        Object v = lookup(path);
        Config sub = fallback != null ? fallback.section(path) : null;
        Map<String, Object> m = v instanceof Map ? asMap(v) : Map.of();
        return new YamlConfig(m, sub);
    }
}
