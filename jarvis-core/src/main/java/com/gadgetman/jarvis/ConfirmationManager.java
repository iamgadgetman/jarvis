package com.gadgetman.jarvis;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks dangerous actions that are waiting for player confirmation.
 * Actions expire after a configurable timeout.
 */
public class ConfirmationManager {

    private static class PendingAction {
        final String actionType;
        final JSONObject parameters;
        final String description;
        final long timestamp;

        PendingAction(String actionType, JSONObject parameters, String description) {
            this.actionType  = actionType;
            this.parameters  = parameters;
            this.description = description;
            this.timestamp   = System.currentTimeMillis();
        }

        boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }
    }

    private final Map<UUID, PendingAction> pending = new HashMap<>();
    private final long timeoutMs;

    public ConfirmationManager(long timeoutSeconds) {
        this.timeoutMs = timeoutSeconds * 1000L;
    }

    public void setPending(UUID playerId, String actionType, JSONObject parameters, String description) {
        pending.put(playerId, new PendingAction(actionType, parameters, description));
    }

    /** Returns the pending action type, or null if none / expired. */
    public String getPendingAction(UUID playerId) {
        PendingAction pa = pending.get(playerId);
        if (pa == null) return null;
        if (pa.isExpired(timeoutMs)) { pending.remove(playerId); return null; }
        return pa.actionType;
    }

    public JSONObject getPendingParameters(UUID playerId) {
        PendingAction pa = pending.get(playerId);
        if (pa == null || pa.isExpired(timeoutMs)) return null;
        return pa.parameters;
    }

    public String getPendingDescription(UUID playerId) {
        PendingAction pa = pending.get(playerId);
        if (pa == null || pa.isExpired(timeoutMs)) return null;
        return pa.description;
    }

    public boolean hasPending(UUID playerId) {
        return getPendingAction(playerId) != null;
    }

    public void clearPending(UUID playerId) {
        pending.remove(playerId);
    }

    public long getTimeoutSeconds() {
        return timeoutMs / 1000L;
    }
}
