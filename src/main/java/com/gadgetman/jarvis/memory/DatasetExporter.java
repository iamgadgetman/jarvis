package com.gadgetman.jarvis.memory;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Dump what this server has taught Jarvis, as JSONL.
 *
 * <p>Two tables have been quietly accumulating supervised pairs the whole time.
 * {@code chat_interactions} holds (what the player said → the action that was
 * actually taken), which is intent parsing with free labels. {@code
 * build_experiences} holds (request → the plan that ran → whether it was kept),
 * which is build planning with an outcome attached.
 *
 * <p>No gameplay feature. It is the raw material for fine-tuning a small local
 * model later, which is where the Ollama tier's ceiling actually sits — the
 * paper's LLaMA2-13B result is the argument for bothering.
 *
 * <p>One line of JSON per row, written off the main thread to the plugin's data
 * folder. Player UUIDs are not written: the pairs are what has value, and a
 * dataset that leaves the server should not carry who said what.
 */
public class DatasetExporter {

    private final Jarvis plugin;

    public DatasetExporter(Jarvis plugin) {
        this.plugin = plugin;
    }

    /**
     * Write both datasets and report back to whoever asked. Returns
     * immediately; the query and the file write are async.
     */
    public void exportAsync(CommandSender requester) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File folder = new File(plugin.getDataFolder(), "datasets");

        new BukkitRunnable() {
            @Override
            public void run() {
                String result;
                try {
                    Files.createDirectories(folder.toPath());
                    File intents = new File(folder, "intents-" + stamp + ".jsonl");
                    File builds = new File(folder, "builds-" + stamp + ".jsonl");
                    int intentRows = exportIntents(intents);
                    int buildRows = exportBuilds(builds);
                    result = ChatColor.GREEN + "Dataset exported, sir: "
                            + ChatColor.WHITE + intentRows + " intent pairs, "
                            + buildRows + " build plans"
                            + ChatColor.GRAY + " → plugins/Jarvis/datasets/";
                    plugin.getLogger().info("Dataset export: " + intentRows + " intents to "
                            + intents.getName() + ", " + buildRows + " builds to " + builds.getName());
                } catch (Exception e) {
                    result = ChatColor.RED + "Dataset export failed: " + e.getMessage();
                    plugin.getLogger().warning("Dataset export failed: " + e.getMessage());
                }

                final String message = result;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        requester.sendMessage(message);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * (player_message → action_taken), the intent-parsing set.
     *
     * <p>Rows with no action are skipped: an unlabelled example teaches nothing,
     * and those are the ones where the parse failed.
     */
    private int exportIntents(File out) throws IOException, SQLException {
        int written = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8);
             Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_message, ai_response, action_taken, timestamp "
                     + "FROM chat_interactions WHERE action_taken IS NOT NULL "
                     + "AND action_taken <> '' ORDER BY timestamp");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String request = rs.getString("player_message");
                String action = rs.getString("action_taken");
                if (request == null || request.isBlank()) continue;

                JSONObject row = new JSONObject();
                row.put("request", request);
                row.put("action", action);
                String response = rs.getString("ai_response");
                if (response != null && !response.isBlank()) row.put("response", response);
                row.put("at", rs.getLong("timestamp"));
                w.write(row.toString());
                w.newLine();
                written++;
            }
        }
        return written;
    }

    /**
     * (request → plan → outcome), the build-planning set.
     *
     * <p>Failures are exported alongside successes and labelled as such. A
     * fine-tune wants both; retrieval, which only ever reads positives, is the
     * one that does not.
     */
    private int exportBuilds(File out) throws IOException, SQLException {
        int written = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8);
             Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT task_type, request_text, situation, plan, outcome, provider, created_at "
                     + "FROM build_experiences ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String request = rs.getString("request_text");
                String plan = rs.getString("plan");
                if (request == null || request.isBlank() || plan == null || plan.isBlank()) continue;

                JSONObject row = new JSONObject();
                row.put("task", rs.getString("task_type"));
                row.put("request", request);
                row.put("plan", plan);
                row.put("outcome", rs.getString("outcome"));
                String situation = rs.getString("situation");
                if (situation != null && !situation.isBlank()) row.put("situation", situation);
                String provider = rs.getString("provider");
                if (provider != null && !provider.isBlank()) row.put("provider", provider);
                row.put("at", rs.getLong("created_at"));
                w.write(row.toString());
                w.newLine();
                written++;
            }
        }
        return written;
    }
}
