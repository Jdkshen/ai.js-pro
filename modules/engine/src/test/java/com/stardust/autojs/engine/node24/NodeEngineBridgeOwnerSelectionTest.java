package com.stardust.autojs.engine.node24;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Owner selection contract for the two-process engine bridge (main + :script), JVM-only.
 *
 * <p>This is the pure logic the :node24 client uses after querying
 * {@link NodeEngineBridgeProtocol#OP_STATUS} from both candidate services. Tests pin the
 * "never default to main when no Node engine is running" and "pick the actually active
 * process" rules without needing Android.
 */
public class NodeEngineBridgeOwnerSelectionTest {

    private static JSONObject status(boolean hasNode, long startedAt) throws Exception {
        return new JSONObject().put("hasNode", hasNode).put("startedAt", startedAt);
    }

    @Test
    public void noActiveCandidateReturnsMinusOne() throws Exception {
        JSONArray statuses = new JSONArray();
        statuses.put(status(false, 0L));
        statuses.put(status(false, 0L));
        assertEquals(-1, NodeEngineBridgeProtocol.selectOwner(statuses));
        assertEquals(-1, NodeEngineBridgeProtocol.selectOwner(null));
        assertEquals(-1, NodeEngineBridgeProtocol.selectOwner(new JSONArray()));
    }

    @Test
    public void selectsScriptOwnerWhenOnlyScriptHasRunningNode() throws Exception {
        JSONArray statuses = new JSONArray();
        statuses.put(status(false, 0L));
        statuses.put(status(true, 1234L));
        assertEquals(1, NodeEngineBridgeProtocol.selectOwner(statuses));
    }

    @Test
    public void selectsMainOwnerWhenLegacyMainProcessRunsNode() throws Exception {
        JSONArray statuses = new JSONArray();
        statuses.put(status(true, 1234L));
        statuses.put(status(false, 0L));
        assertEquals(0, NodeEngineBridgeProtocol.selectOwner(statuses));
    }

    @Test
    public void multipleActiveOwnersChooseMostRecentlyStarted() throws Exception {
        JSONArray statuses = new JSONArray();
        statuses.put(status(true, 100L));
        statuses.put(status(true, 200L));
        assertEquals(1, NodeEngineBridgeProtocol.selectOwner(statuses));
        statuses.put(status(true, 300L));
        statuses.put(status(false, 0L));
        assertEquals(2, NodeEngineBridgeProtocol.selectOwner(statuses));
    }

    @Test
    public void failedCandidateSlotIsIgnoredButActivePeerStillWins() throws Exception {
        JSONArray statuses = new JSONArray();
        statuses.put(JSONObject.NULL);
        statuses.put(status(true, 10L));
        assertEquals(1, NodeEngineBridgeProtocol.selectOwner(statuses));
    }

    @Test
    public void equalStartTimesStayDeterministicByCandidateOrder() throws Exception {
        JSONArray statuses = new JSONArray();
        statuses.put(status(true, 100L));
        statuses.put(status(true, 100L));
        assertEquals(0, NodeEngineBridgeProtocol.selectOwner(statuses));
    }
}
