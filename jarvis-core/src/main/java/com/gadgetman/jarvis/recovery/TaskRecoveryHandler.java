package com.gadgetman.jarvis.recovery;

import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.text.Colors;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-explain: when a task dies, ask the model why before giving up.
 *
 * <p>The hardcoded handlers stay exactly where they are and remain the fast
 * path -- lava sealing, stuck-teleports, vein following. This is the catch-all
 * underneath them, for the failures nobody wrote a handler for. It is the
 * provider-failover idea applied one level up, to tasks.
 *
 * <p>Three things keep it from being a liability:
 * <ul>
 *   <li>It runs on the LIGHT tier, which is Ollama-first, so a diagnosis costs
 *       nothing and works on a server with no cloud keys at all.</li>
 *   <li>The model may only pick a move the failing task itself offered
 *       ({@link TaskFailure.Option}). It cannot invent an action. A site that
 *       offers none still gets its failure explained -- half of these failures
 *       have no way out, and "why" was the part that was missing.</li>
 *   <li>Attempts are capped per task. Past the cap the task says what it
 *       always said and stops -- the pre-0.11 behaviour, unchanged.</li>
 * </ul>
 *
 * <p>The job's own message is said immediately, every time, before anything is
 * asked of a model. A diagnosis is a follow-up, never a substitute: on a slow
 * local box the LIGHT tier can burn its whole timeout before answering, and a
 * player who has just watched his butler stop should not sit in silence for it.
 * Measured against a reference Ollama box running a 70B on CPU, a trivial
 * request took over two minutes -- far past the five-second light-tier timeout,
 * so on that hardware every failure falls back and nothing is lost by it.
 *
 * <p>Threading: the model call is async, the chosen move runs back on the server
 * thread. A task is never left waiting on the network mid-tick.
 */
public class TaskRecoveryHandler {

    /** Per-player attempt bookkeeping. Reset whenever a new task starts. */
    private static final class Attempts {
        String taskType;
        int used;
        boolean inFlight;
        /**
         * Bumped every time the player's task register is cleared. A diagnosis
         * takes a second or two to come back, and in that time the player may
         * have run /jarvis stop or asked for something else entirely -- a
         * recovery move that fires into that would hijack the new job.
         */
        long generation;
    }

    private final Config config;
    private final Log log;
    private final Scheduler scheduler;
    private final AIConnector ai;
    private final Map<UUID, Attempts> attempts = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private int maxAttempts = 2;
    private boolean logDiagnosis = true;

    public TaskRecoveryHandler(Config config, Log log, Scheduler scheduler, AIConnector ai) {
        this.config = config;
        this.log = log;
        this.scheduler = scheduler;
        this.ai = ai;
        loadConfig();
    }

    private void loadConfig() {
        this.enabled = config.getBoolean("self-explain.enabled", true);
        this.maxAttempts = config.getInt("self-explain.max-attempts", 2);
        this.logDiagnosis = config.getBoolean("self-explain.log-diagnosis", true);
    }

    public void reload() {
        loadConfig();
        attempts.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Called when a task starts, so its recovery budget is fresh. A player who
     * re-issues a command that just failed gets a new pair of attempts; a task
     * that fails repeatedly on its own does not.
     */
    public void taskStarted(Owner player, String taskType) {
        if (player == null) return;
        Attempts a = attempts.computeIfAbsent(player.id(), k -> new Attempts());
        a.taskType = taskType;
        a.used = 0;
        a.inFlight = false;
    }

    /**
     * Whatever this player was doing has been cancelled or replaced. Any
     * diagnosis still on the wire may speak, but must not act.
     */
    public void taskSuperseded(Owner player) {
        if (player == null) return;
        Attempts a = attempts.get(player.id());
        if (a != null) {
            synchronized (a) { a.generation++; }
        }
    }

    /** Drop a player's budget on disconnect, so the map does not grow forever. */
    public void forget(Owner player) {
        if (player != null) attempts.remove(player.id());
    }

    /** How many attempts this player has left on the task they are running. */
    public int remainingAttempts(Owner player) {
        Attempts a = attempts.get(player.id());
        return a == null ? maxAttempts : Math.max(0, maxAttempts - a.used);
    }

    /**
     * Diagnose a dead task and, if the model picks one of the offered moves,
     * run it. Returns immediately either way.
     *
     * <p>{@link TaskFailure#getDefaultMessage()} is said first, before anything
     * is asked of a model, so the caller can hand this its existing failure
     * message and delete nothing. Every path that does not end in a successful
     * diagnosis therefore leaves the player with exactly what the job said
     * before, and a diagnosis that does arrive adds a sentence rather than
     * replacing one.
     */
    public void handle(TaskFailure failure) {
        Owner player = failure.getOwner();
        if (player == null) return;

        // Said first and unconditionally. Everything below is additive.
        say(player, failure.getDefaultMessage());

        if (!enabled) return;

        Attempts a = attempts.computeIfAbsent(player.id(), k -> new Attempts());
        synchronized (a) {
            // A different task than the one we were counting -- start its budget.
            if (!failure.getTaskType().equals(a.taskType)) {
                a.taskType = failure.getTaskType();
                a.used = 0;
                a.inFlight = false;
            }
            if (a.inFlight || a.used >= maxAttempts) {
                // Out of budget, or one diagnosis is already on the wire. The
                // player has his message; let it rest.
                return;
            }
            a.used++;
            a.inFlight = true;
        }
        final long generation;
        synchronized (a) { generation = a.generation; }

        final String prompt = describe(failure);
        final List<String> allowed = new ArrayList<>();
        for (TaskFailure.Option o : failure.getOptions()) allowed.add(o.name());

        scheduler.async(() -> {
            String response = null;
            try {
                response = ai.diagnoseTaskFailure(prompt, allowed);
            } catch (Exception e) {
                log.fine("Failure diagnosis unavailable: " + e.getMessage());
            }
            final String raw = response;
            scheduler.sync(() -> {
                Attempts a2 = attempts.get(player.id());
                if (a2 != null) {
                    synchronized (a2) { a2.inFlight = false; }
                }
                apply(failure, raw, generation);
            });
        });
    }

    /** Main thread. Deliver the diagnosis and run the chosen move, or fall back. */
    private void apply(TaskFailure failure, String raw, long generation) {
        Owner player = failure.getOwner();
        if (player == null || !player.isOnline()) return;

        // Nothing came back -- the box is down, or slower than the tier's
        // timeout. The player was told what the job said; that is the whole of
        // the old behaviour, so there is nothing to add.
        if (raw == null || raw.isBlank()) return;

        String diagnosis;
        String action;
        String message;
        try {
            JSONObject json = new JSONObject(raw);
            diagnosis = json.optString("diagnosis", "").trim();
            action = json.optString("action", "").trim();
            message = json.optString("message", "").trim();
        } catch (Exception e) {
            log.fine("Failure diagnosis was not JSON: " + e.getMessage());
            return;
        }

        // Logged on the way out for the same reason the site note is: there was
        // no way, afterwards, to tell a diagnosis that was ignored from one that
        // never arrived.
        if (logDiagnosis) {
            // Asked for explicitly, but a smaller model still drops it now and
            // then, and the log line is the point of the exercise -- fall back
            // to what it told the player rather than losing the record.
            String logged = !diagnosis.isEmpty() ? diagnosis
                    : (!message.isEmpty() ? "(no diagnosis given) " + message : "");
            if (!logged.isEmpty()) {
                log.info("Task " + failure.getTaskType() + " failed for "
                        + player.name() + " (" + failure.getReason() + "). Diagnosis: "
                        + logged + " -> action: " + (action.isEmpty() ? "none" : action));
            }
        }

        TaskFailure.Option chosen = failure.findOption(action);
        // A follow-up, not a replacement -- the job's own line was said the
        // moment it failed.
        say(player, message);
        // The model is free to say "abort", and an unrecognised action is
        // treated as one. Either way nothing runs.
        if (chosen == null) return;

        // The player did something else while this was in flight. He has had
        // his say; acting now would take over whatever they asked for instead.
        Attempts a = attempts.get(player.id());
        if (a != null) {
            long now;
            synchronized (a) { now = a.generation; }
            if (now != generation) {
                log.fine("Recovery move '" + chosen.name() + "' dropped — "
                        + player.name() + " moved on to something else.");
                return;
            }
        }

        try {
            chosen.action().run();
        } catch (Exception e) {
            log.warn("Recovery move '" + chosen.name() + "' for "
                    + failure.getTaskType() + " threw: " + e.getMessage());
            say(player, "That did not work either, sir. I shall leave it.");
        }
    }

    /** The self-explain prompt body: what he was doing, what broke, what he can do. */
    private String describe(TaskFailure failure) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have already told the player: \"")
          .append(failure.getDefaultMessage()).append("\"\n");
        sb.append("Task: ").append(failure.getTaskType()).append('\n');
        sb.append("Step: ").append(failure.getStep()).append('\n');
        sb.append("What went wrong: ").append(failure.getReason()).append('\n');
        if (!failure.getState().isEmpty()) {
            sb.append("State:\n");
            for (Map.Entry<String, String> e : failure.getState().entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        sb.append("Moves available to you:\n");
        for (TaskFailure.Option o : failure.getOptions()) {
            sb.append("  ").append(o.name()).append(" — ").append(o.description()).append('\n');
        }
        sb.append("  abort — stop the task and explain to the player\n");
        if (failure.getOptions().isEmpty()) {
            // The earlier wording of this said the message was what mattered,
            // and the model duly stopped writing a diagnosis at all -- empty in
            // every explain-only answer measured. Ask for both.
            sb.append("Only 'abort' is listed, so that is the action. Still write the diagnosis "
                    + "and the message: they are the only record of why this job stopped.\n");
        }
        return sb.toString();
    }

    private void say(Owner player, String text) {
        if (player.isOnline() && text != null && !text.isBlank()) {
            player.message(Colors.jarvis(text));
        }
    }
}
