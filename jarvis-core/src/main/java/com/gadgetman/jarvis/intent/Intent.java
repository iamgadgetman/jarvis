package com.gadgetman.jarvis.intent;

import org.json.JSONObject;

/**
 * One order, already understood: the action name and its parameters, in the
 * same vocabulary the model's JSON uses. {@link FastPathParser} produces these
 * locally; params is never null.
 */
public record Intent(String action, JSONObject params) {

    public Intent {
        if (params == null) params = new JSONObject();
    }

    public static Intent of(String action) {
        return new Intent(action, new JSONObject());
    }
}
