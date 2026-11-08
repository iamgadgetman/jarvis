package com.gadgetman.jarvis.ai;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanLimitTest {
    private static JSONObject reply(String stop, int out) {
        return new JSONObject().put("stop_reason", stop)
                .put("usage", new JSONObject().put("output_tokens", out));
    }

    @Test
    void cloudBuildCutOffByPlanIsReported() {
        String m = AIConnector.planLimitMessage(reply("max_tokens", 1024), "gc_abc", 16000);
        assertNotNull(m);
        assertTrue(m.contains("1024") && m.contains("/jarvis cloud upgrade"), m);
    }

    @Test
    void finishedReplyIsFine() {
        assertNull(AIConnector.planLimitMessage(reply("end_turn", 1024), "gc_abc", 16000));
    }

    @Test
    void ownKeyIsNotAPlanLimit() {
        assertNull(AIConnector.planLimitMessage(reply("max_tokens", 1024), "sk-ant-x", 16000));
    }

    @Test
    void chatRepliesAreLeftAlone() {
        assertNull(AIConnector.planLimitMessage(reply("max_tokens", 300), "gc_abc", 2000));
    }

    @Test
    void hittingTheRequestedCeilingIsNotThePlan() {
        assertNull(AIConnector.planLimitMessage(reply("max_tokens", 16000), "gc_abc", 16000));
    }
}
