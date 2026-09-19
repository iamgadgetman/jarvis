package com.gadgetman.jarvis.core.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writing small YAML data files, the counterpart of {@link YamlConfig}. */
public final class YamlFiles {

    private YamlFiles() { }

    /**
     * Write a nested map as a block-style YAML document, creating parent
     * directories as needed. Values may be maps, lists, strings, numbers and
     * booleans.
     */
    public static void write(Path file, Map<String, Object> data) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            yaml.dump(data, out);
        }
    }
}
