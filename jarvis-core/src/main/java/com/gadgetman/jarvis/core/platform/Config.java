package com.gadgetman.jarvis.core.platform;

import java.util.List;
import java.util.Set;

/**
 * Read access to Jarvis's configuration, with the dotted-path semantics the
 * plugin's config.yml has always had.
 *
 * <p>Paths are relative to this view. {@link #section(String)} returns a view
 * rooted deeper in the tree, never null: a missing section is an empty view
 * whose getters return their defaults. Callers that need to know whether a
 * section is present ask {@link #isSection(String)} first.
 */
public interface Config {

    String getString(String path, String def);

    default String getString(String path) {
        return getString(path, null);
    }

    int getInt(String path, int def);

    long getLong(String path, long def);

    double getDouble(String path, double def);

    boolean getBoolean(String path, boolean def);

    /** The list at the path, or an empty list. Never null. */
    List<String> getStringList(String path);

    /** True if anything at all is set at the path. */
    boolean contains(String path);

    /** True if the path holds a mapping rather than a value or nothing. */
    boolean isSection(String path);

    /** The immediate child keys of this view, or an empty set. */
    Set<String> keys();

    /** The immediate child keys of the section at the path, or an empty set. */
    default Set<String> keys(String path) {
        return section(path).keys();
    }

    /** A view rooted at the path. Never null. */
    Config section(String path);
}
