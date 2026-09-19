package com.gadgetman.jarvis.recovery;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything known about a task that just died, packaged for diagnosis.
 *
 * <p>The important field is {@link #getOptions()}. A failing task does not hand
 * the model a free hand over the server -- it declares the moves it is able to
 * make from where it stands, and the model may only pick one of those. Same
 * allowlist reasoning the console-command path already uses: the model chooses,
 * the plugin decides what "choose" can mean.
 *
 * <p>{@link #getDefaultMessage()} is what the task would have said on its own.
 * It is the fallback whenever diagnosis is off, capped out, or unusable, so a
 * server that never configures this feature behaves exactly as it did before.
 */
public final class TaskFailure {

    /**
     * A recovery move the failing task is offering.
     *
     * @param name        short id the model returns, e.g. {@code "resume"}
     * @param description what it does, in plain English, for the prompt
     * @param action      run on the main thread if the model picks it
     */
    public record Option(String name, String description, Runnable action) {}

    private final Player player;
    private final String taskType;
    private final String step;
    private final String reason;
    private final String defaultMessage;
    private final Map<String, String> state;
    private final List<Option> options;

    private TaskFailure(Builder b) {
        this.player = b.player;
        this.taskType = b.taskType;
        this.step = b.step;
        this.reason = b.reason;
        this.defaultMessage = b.defaultMessage;
        this.state = Collections.unmodifiableMap(new LinkedHashMap<>(b.state));
        this.options = Collections.unmodifiableList(new ArrayList<>(b.options));
    }

    public Player getPlayer() { return player; }
    public String getTaskType() { return taskType; }
    public String getStep() { return step; }
    public String getReason() { return reason; }
    public String getDefaultMessage() { return defaultMessage; }
    public Map<String, String> getState() { return state; }
    public List<Option> getOptions() { return options; }

    /** The option the model named, or null if it named one that was not offered. */
    public Option findOption(String name) {
        if (name == null) return null;
        for (Option o : options) {
            if (o.name().equalsIgnoreCase(name.trim())) return o;
        }
        return null;
    }

    public static Builder of(Player player, String taskType) {
        return new Builder(player, taskType);
    }

    public static final class Builder {
        private final Player player;
        private final String taskType;
        private String step = "unknown";
        private String reason = "unknown";
        private String defaultMessage = "Something has gone wrong, sir. Stopping here.";
        private final Map<String, String> state = new LinkedHashMap<>();
        private final List<Option> options = new ArrayList<>();

        private Builder(Player player, String taskType) {
            this.player = player;
            this.taskType = taskType;
        }

        /** Where in the task it died -- "returning to the mine", "felling tree 3 of 5". */
        public Builder step(String step) {
            this.step = step;
            return this;
        }

        /** Why, in mechanical terms. Not player-facing. */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /** What the task says today when this happens. Used verbatim as the fallback. */
        public Builder say(String defaultMessage) {
            this.defaultMessage = defaultMessage;
            return this;
        }

        public Builder state(String key, Object value) {
            this.state.put(key, String.valueOf(value));
            return this;
        }

        /** Records dimension, coordinates and the light level -- the usual suspects. */
        public Builder where(Location loc) {
            if (loc == null || loc.getWorld() == null) return this;
            state.put("dimension", loc.getWorld().getEnvironment().name().toLowerCase());
            state.put("position", loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            state.put("biome", loc.getBlock().getBiome().toString().toLowerCase());
            return this;
        }

        public Builder option(String name, String description, Runnable action) {
            this.options.add(new Option(name, description, action));
            return this;
        }

        public TaskFailure build() {
            return new TaskFailure(this);
        }
    }
}
