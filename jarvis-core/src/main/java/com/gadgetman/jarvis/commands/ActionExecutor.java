package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.core.platform.Owner;
import org.json.JSONObject;

import java.util.Set;

/**
 * The admin-flavoured actions the AI can ask for: give an item, set the
 * time, run a console command. They are server administration rather than
 * butlering, and every one of them reaches for the server directly, so the
 * adapter implements them and core only routes to them.
 */
public interface ActionExecutor {

    /** True when this executor knows the action. */
    boolean handles(String actionType);

    /** Actions the player must confirm before they run. */
    Set<String> dangerousActions();

    /**
     * Run an action on the server thread.
     *
     * @return a human-readable result, or null when the action is unknown
     */
    String execute(String actionType, JSONObject params, Owner requester);

    /** A short description of what an action will do, for the confirmation prompt. */
    String describe(String actionType, JSONObject params);
}
