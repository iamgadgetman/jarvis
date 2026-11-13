package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.world.Vec3;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Reward bundles (FB-08): named lists of console commands the admin writes in
 * {@code bundles:}. Jarvis's model may ask for a bundle by name
 * ({@code trigger_bundle}), and an admin may run one with
 * {@code /jarvis bundle run}; nobody but the admin ever writes the commands, so
 * the model gets a way to reward players without a way to compose commands.
 *
 * <p>Each bundle can be once per player and can have a cooldown, both kept in
 * SQLite ({@code bundle_uses}) so a restart doesn't reset them. Every trigger,
 * refused or not, is logged as {@code [bundle] <name> for <player> by <who>}.
 */
public final class BundleRegistry {

    /** One bundle as read from config. */
    public record Bundle(String name, String description, boolean oncePerPlayer, int cooldownMinutes,
                         List<String> commands, boolean ai, String permission) { }

    /** What happened: ran or not, and the line to tell the player. */
    public record Result(boolean ran, String message) { }

    /** Who asked for the bundle, for the log and the AI's extra rules. */
    public enum By { AI, ADMIN }

    /** Minecraft player names: no spaces, nothing a command could split on. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** Bundle names in config: what a model can be trusted to spell back. */
    private static final Pattern BUNDLE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    private final Platform platform;
    private final DatabaseManager db;
    private final Log log;
    private final LongSupplier clock;
    private volatile Map<String, Bundle> bundles = new LinkedHashMap<>();

    public BundleRegistry(Platform platform, DatabaseManager db, LongSupplier clock) {
        this.platform = platform;
        this.db = db;
        this.log = platform.log();
        this.clock = clock;
        reload(platform.config());
    }

    public void reload(Config cfg) {
        Map<String, Bundle> m = new LinkedHashMap<>();
        if (cfg.isSection("bundles")) {
            for (String key : cfg.keys("bundles")) {
                String name = key.toLowerCase(Locale.ROOT);
                String base = "bundles." + key + ".";
                List<String> commands = cfg.getStringList(base + "commands");
                if (!BUNDLE_NAME.matcher(name).matches()) {
                    log.warn("Bundle '" + key + "' skipped: names are lower-case letters, digits, - and _.");
                    continue;
                }
                if (commands == null || commands.isEmpty()) {
                    log.warn("Bundle '" + key + "' skipped: it has no commands.");
                    continue;
                }
                List<String> clean = new ArrayList<>();
                for (String c : commands) {
                    String s = c.trim();
                    if (s.startsWith("/")) s = s.substring(1);
                    if (!s.isEmpty()) clean.add(s);
                }
                m.put(name, new Bundle(name,
                        cfg.getString(base + "description", ""),
                        cfg.getBoolean(base + "once-per-player", false),
                        Math.max(0, cfg.getInt(base + "cooldown-minutes", 0)),
                        List.copyOf(clean),
                        cfg.getBoolean(base + "ai", true),
                        cfg.getString(base + "permission", "")));
            }
        }
        bundles = m;
    }

    public Map<String, Bundle> all() {
        return bundles;
    }

    public Optional<Bundle> get(String name) {
        return Optional.ofNullable(name == null ? null : bundles.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * The prompt lines that tell the model which bundles exist (only those it
     * may trigger). Empty when there are none, so a server without bundles pays
     * nothing for this.
     */
    public String promptSection() {
        StringBuilder sb = new StringBuilder();
        Bundle first = null;
        for (Bundle b : bundles.values()) {
            if (!b.ai()) continue;
            if (first == null) first = b;
            sb.append("  ").append(b.name());
            if (!b.description().isBlank()) sb.append(" - ").append(b.description().trim());
            sb.append('\n');
        }
        if (first == null) return "";
        // Small local models follow examples far more than rules, so give one
        // built from a real bundle (seen live: without it qwen2.5 7B answered
        // "I found my first diamond" with give_item or chat).
        String hint = first.description().isBlank() ? first.name().replace('-', ' ') : first.description().trim();
        return "REWARD BUNDLES (the admin's rewards). When what the player says or did matches one of these "
                + "descriptions, answer with trigger_bundle and that name, never give_item or chat. "
                + "Use only these names; you can't write commands yourself:\n" + sb
                + "  Example: the player's words fit \"" + hint + "\" -> "
                + "{\"action\":\"trigger_bundle\",\"parameters\":{\"name\":\"" + first.name() + "\"},"
                + "\"response\":\"Well earned, sir.\"}\n";
    }

    /**
     * Run a bundle for a player: check the name, the bundle's permission, the
     * once-per-player and cooldown rules, then run every command as the console
     * and record the use. Must run on the server thread (commands do).
     */
    public Result trigger(Owner player, String name, By by) {
        String who = by == By.AI ? "ai" : "admin";
        String asked = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        Bundle b = bundles.get(asked);
        if (b == null) {
            log.info("[bundle] " + clip(asked) + " for " + player.name() + " by " + who + ": refused, no such bundle");
            return new Result(false, "I have no reward called '" + clip(asked) + "', sir. The admin sets those up.");
        }
        if (by == By.AI && !b.ai()) {
            log.info("[bundle] " + b.name() + " for " + player.name() + " by ai: refused, admin-only");
            return new Result(false, "That one's for the admin to hand out, sir.");
        }
        if (by == By.AI && !b.permission().isBlank() && !player.hasPermission(b.permission())) {
            log.info("[bundle] " + b.name() + " for " + player.name() + " by ai: refused, no " + b.permission());
            return new Result(false, "That reward isn't one I can give you, sir.");
        }
        if (!SAFE_NAME.matcher(player.name()).matches()) {
            log.warn("[bundle] " + b.name() + " for " + player.name() + " by " + who + ": refused, odd player name");
            return new Result(false, "I can't put your name in a command safely, sir.");
        }
        long now = clock.getAsLong();
        Use use = use(player, b.name());
        if (b.oncePerPlayer() && use.count() > 0) {
            log.info("[bundle] " + b.name() + " for " + player.name() + " by " + who + ": refused, already given");
            return new Result(false, "We've already celebrated that one, sir.");
        }
        if (b.cooldownMinutes() > 0 && use.lastAt() > 0) {
            long readyAt = use.lastAt() + b.cooldownMinutes() * 60_000L;
            if (now < readyAt) {
                log.info("[bundle] " + b.name() + " for " + player.name() + " by " + who + ": refused, cooldown");
                return new Result(false, "Not again so soon, sir. That one's ready in "
                        + com.gadgetman.jarvis.building.BuildQuota.duration(readyAt - now) + ".");
            }
        }
        int ran = 0;
        for (String template : b.commands()) {
            String command = fill(template, player);
            try {
                platform.runConsoleCommand(command);
                ran++;
            } catch (RuntimeException e) {
                log.warn("[bundle] " + b.name() + ": '" + command + "' failed: " + e.getMessage());
            }
        }
        record(player, b.name(), now);
        log.info("[bundle] " + b.name() + " for " + player.name() + " by " + who
                + " (" + ran + "/" + b.commands().size() + " commands)");
        return new Result(true, ran == b.commands().size() ? "Done, sir." : "Done, sir, though "
                + (b.commands().size() - ran) + " of its commands failed. The console log has the details.");
    }

    /** {player} {uuid} {x} {y} {z} {world}, then PlaceholderAPI where the platform has it. */
    String fill(String template, Owner player) {
        Vec3 p = player.pos();
        String s = template
                .replace("{player}", player.name())
                .replace("{uuid}", player.id().toString())
                .replace("{x}", Integer.toString((int) Math.floor(p.x())))
                .replace("{y}", Integer.toString((int) Math.floor(p.y())))
                .replace("{z}", Integer.toString((int) Math.floor(p.z())))
                .replace("{world}", player.world().id());
        return platform.expandPlaceholders(player.id(), s);
    }

    private static String clip(String s) {
        String t = s.replaceAll("[^A-Za-z0-9_ -]", "");
        return t.length() > 32 ? t.substring(0, 32) : t;
    }

    record Use(int count, long lastAt) { }

    Use use(Owner player, String bundle) {
        if (db == null) return new Use(0, 0);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uses, last_at FROM bundle_uses WHERE player_id = ? AND bundle = ?")) {
            ps.setString(1, player.id().toString());
            ps.setString(2, bundle);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Use(rs.getInt(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            log.warn("Could not read bundle uses: " + e.getMessage());
        }
        return new Use(0, 0);
    }

    private void record(Owner player, String bundle, long now) {
        if (db == null) return;
        try (Connection c = db.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bundle_uses SET uses = uses + 1, last_at = ? WHERE player_id = ? AND bundle = ?")) {
                ps.setLong(1, now);
                ps.setString(2, player.id().toString());
                ps.setString(3, bundle);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bundle_uses (player_id, bundle, uses, last_at) VALUES (?, ?, 1, ?)")) {
                    ps.setString(1, player.id().toString());
                    ps.setString(2, bundle);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not record a bundle use: " + e.getMessage());
        }
    }
}
