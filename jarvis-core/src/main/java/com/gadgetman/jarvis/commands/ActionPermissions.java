package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.core.platform.Owner;

import java.util.Set;

/**
 * Who may have Jarvis run an {@link ActionExecutor} action on their say-so.
 *
 * <p>These actions are server administration: console commands, items out of
 * thin air, game rules, time, weather, announcements. The model picks them
 * from whatever the player typed, so the player's own permissions decide,
 * not the model and not the confirmation prompt (a player can always confirm
 * their own request).
 *
 * <p>An action is allowed when the player has {@code jarvis.admin}, or the
 * per-action node {@code jarvis.admin.actions.<action>} (for example
 * {@code jarvis.admin.actions.heal}, to let a group use just that one), or the
 * action is in {@link #OPEN}. On Fabric and NeoForge, which have no
 * permission nodes, both nodes mean op.
 */
public final class ActionPermissions {

    /** Actions any player may ask for: they only ask an admin for something. */
    public static final Set<String> OPEN = Set.of("request_item");

    public static final String ADMIN = "jarvis.admin";
    public static final String PREFIX = "jarvis.admin.actions.";

    private ActionPermissions() {}

    public static boolean allowed(Owner player, String actionType) {
        if (actionType == null) return false;
        return OPEN.contains(actionType)
                || player.hasPermission(ADMIN)
                || player.hasPermission(PREFIX + actionType);
    }

    /** The line a refused player sees. */
    public static String refusal(String actionType) {
        return "Jarvis: I'm afraid " + actionType.replace('_', ' ')
                + " needs an administrator's say-so, sir. Ask a staff member, or ask me to request an item from them.";
    }
}
