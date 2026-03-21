package com.yourname.jarvis;

import java.util.*;

/**
 * Manages item requests submitted by non-admin players.
 * Admins approve or deny requests via /jarvis approve <id> / /jarvis deny <id>.
 */
public class PlayerRequestManager {

    public static class ItemRequest {
        public final int id;
        public final UUID playerUUID;
        public final String playerName;
        public final String item;
        public final int amount;
        public final String reason;
        public final long timestamp;

        ItemRequest(int id, UUID playerUUID, String playerName,
                    String item, int amount, String reason) {
            this.id        = id;
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.item      = item;
            this.amount    = amount;
            this.reason    = reason;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private int nextId = 1;
    private final Map<Integer, ItemRequest> pending = new LinkedHashMap<>();

    /** Add a new request; returns the assigned integer ID. */
    public int addRequest(UUID playerUUID, String playerName,
                          String item, int amount, String reason) {
        int id = nextId++;
        pending.put(id, new ItemRequest(id, playerUUID, playerName, item, amount, reason));
        return id;
    }

    public ItemRequest getRequest(int id) {
        return pending.get(id);
    }

    public boolean removeRequest(int id) {
        return pending.remove(id) != null;
    }

    public Collection<ItemRequest> getAllRequests() {
        return Collections.unmodifiableCollection(pending.values());
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Remove requests older than 15 minutes. */
    public void cleanOld() {
        long cutoff = System.currentTimeMillis() - 900_000;
        pending.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
    }
}
