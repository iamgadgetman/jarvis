package com.gadgetman.jarvis.memory;

import java.util.UUID;

/**
 * One remembered build: what was asked, where it was asked, what plan ran,
 * and whether the player kept it.
 *
 * The outcome is inferred from behaviour rather than a rating prompt — a build
 * that finished is a success, one the player cancelled or undid is not. That
 * makes the label free, which is the only reason a memory of any size ever
 * accumulates on a real server.
 */
public class BuildExperience {

    /** How a build ended, and what that is worth as a training signal. */
    public enum Outcome {
        /** Ran to completion and was left standing. */
        SUCCESS(1.0),
        /** The player stopped it part-way. */
        CANCELLED(0.0),
        /** The build never got off the ground (bad plan, no blocks, parse failure). */
        FAILED(0.0),
        /** Completed, then reverted inside the negative-signal window. */
        UNDONE(0.0);

        private final double signal;

        Outcome(double signal) {
            this.signal = signal;
        }

        public double signal() {
            return signal;
        }

        public boolean isPositive() {
            return this == SUCCESS;
        }

        public static Outcome parse(String name) {
            if (name == null) return FAILED;
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return FAILED;
            }
        }
    }

    private long id = -1;
    private final UUID playerId;
    private final String taskType;
    private final String requestText;
    private final String situation;   // JSON, see SituationSnapshot
    private final String plan;        // JSON, the block plan that ran
    private Outcome outcome;
    private double outcomeSignal;
    private final String provider;    // which model produced the plan
    private final long createdAt;
    private float[] embedding;        // of requestText; null until computed

    public BuildExperience(UUID playerId, String taskType, String requestText, String situation,
                           String plan, Outcome outcome, String provider, long createdAt) {
        this.playerId = playerId;
        this.taskType = taskType;
        this.requestText = requestText == null ? "" : requestText;
        this.situation = situation;
        this.plan = plan;
        this.outcome = outcome == null ? Outcome.FAILED : outcome;
        this.outcomeSignal = this.outcome.signal();
        this.provider = provider;
        this.createdAt = createdAt;
    }

    public static BuildExperience now(UUID playerId, String taskType, String requestText,
                                      String situation, String plan, Outcome outcome, String provider) {
        return new BuildExperience(playerId, taskType, requestText, situation, plan, outcome,
                provider, System.currentTimeMillis());
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public UUID getPlayerId() { return playerId; }
    public String getTaskType() { return taskType; }
    public String getRequestText() { return requestText; }
    public String getSituation() { return situation; }
    public String getPlan() { return plan; }
    public String getProvider() { return provider; }
    public long getCreatedAt() { return createdAt; }

    public Outcome getOutcome() { return outcome; }
    public double getOutcomeSignal() { return outcomeSignal; }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
        this.outcomeSignal = outcome.signal();
    }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
    public boolean hasEmbedding() { return embedding != null && embedding.length > 0; }

    @Override
    public String toString() {
        return "BuildExperience{id=" + id + ", type=" + taskType + ", outcome=" + outcome
                + ", request='" + requestText + "'}";
    }
}
