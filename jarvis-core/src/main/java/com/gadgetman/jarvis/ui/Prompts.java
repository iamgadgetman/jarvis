package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.text.Colors;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A question asked in chat, answered by the next line the player types.
 *
 * <p>A chest menu has no text box, so anything that needs typing (a
 * server address, an API key) is asked this way. The answer line is kept
 * out of public chat and off the server's chat log, which is why it is
 * not a command. Sixty seconds, or "cancel", ends the question.
 */
public final class Prompts {

    private static final long TIMEOUT_MS = 60_000;

    private record Pending(String question, Consumer<String> answer, long deadline) { }

    private final Platform platform;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public Prompts(Platform platform) {
        this.platform = platform;
    }

    /** Ask, and hand the reply to {@code answer} on the server thread. */
    public void ask(Owner who, String question, Consumer<String> answer) {
        pending.put(who.id(), new Pending(question, answer, System.currentTimeMillis() + TIMEOUT_MS));
        who.message(Colors.YELLOW + "Jarvis: " + question);
        who.message(Colors.GRAY + "Type it in chat. Only I will see it. Say " + Colors.WHITE + "cancel"
                + Colors.GRAY + " to leave it.");
    }

    public boolean isWaiting(Owner who) {
        Pending p = pending.get(who.id());
        return p != null && p.deadline() > System.currentTimeMillis();
    }

    public void cancel(Owner who) {
        pending.remove(who.id());
    }

    /**
     * A chat line from a player. True when it answered a question and must
     * not reach chat. May be called off the server thread; the answer is
     * delivered on it.
     */
    public boolean offer(Owner who, String text) {
        Pending p = pending.get(who.id());
        if (p == null) return false;
        if (p.deadline() <= System.currentTimeMillis()) {
            pending.remove(who.id());
            return false;
        }
        pending.remove(who.id());
        String line = text == null ? "" : text.trim();
        if (line.equalsIgnoreCase("cancel")) {
            who.message(Colors.GRAY + "Jarvis: Very good, sir. Left as it was.");
            return true;
        }
        platform.scheduler().sync(() -> p.answer().accept(line));
        return true;
    }
}
