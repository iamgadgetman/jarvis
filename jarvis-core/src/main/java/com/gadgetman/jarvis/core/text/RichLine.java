package com.gadgetman.jarvis.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * A line of chat with clickable parts, for prompts such as
 * "[Confirm] [Cancel]". Adapters that can render links do; the plain form
 * is the text alone.
 */
public final class RichLine {

    /**
     * One run of text. {@code command} is run as the viewer when clicked, as
     * typed with its leading slash; null for plain text. {@code url} opens a
     * web page instead (the client asks first); null for none. {@code hover}
     * is shown on mouse-over, or null.
     */
    public record Part(String text, String command, String hover, String url) {
        public Part(String text, String command, String hover) {
            this(text, command, hover, null);
        }
    }

    private final List<Part> parts = new ArrayList<>();

    public RichLine text(String text) {
        parts.add(new Part(text, null, null));
        return this;
    }

    public RichLine link(String text, String command, String hover) {
        parts.add(new Part(text, command, hover));
        return this;
    }

    /** A part that opens {@code url} in the viewer's browser when clicked. */
    public RichLine url(String text, String url, String hover) {
        parts.add(new Part(text, null, hover, url));
        return this;
    }

    public List<Part> parts() {
        return List.copyOf(parts);
    }

    /** The line with the links flattened to their text. */
    public String plain() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) sb.append(p.text());
        return sb.toString();
    }
}
