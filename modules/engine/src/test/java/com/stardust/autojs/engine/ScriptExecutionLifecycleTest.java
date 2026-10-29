package com.stardust.autojs.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScriptExecutionLifecycleTest {
    @Test public void lateStartCannotReopenStoppedOrReleasedExecution() {
        ScriptExecutionLifecycle state=new ScriptExecutionLifecycle();
        state.requestStop(); state.running();
        assertEquals(ScriptExecutionLifecycle.Phase.STOP_REQUESTED,state.phase());
        state.cancelling(); state.releasingResources(); state.running();
        assertEquals(ScriptExecutionLifecycle.Phase.RELEASING,state.phase());
        state.released(); state.requestStop(); state.running();
        assertEquals(ScriptExecutionLifecycle.Phase.RELEASED,state.phase());
        assertEquals(1,state.stopRequests());
    }
    @Test public void repeatedStopRequestsRemainAvailableForEscalation() {
        ScriptExecutionLifecycle state=new ScriptExecutionLifecycle(); state.running();
        for(int i=1;i<=4;i++) assertEquals(i,state.requestStop());
        assertTrue(state.stopRequested()); assertFalse(state.releasing());
    }
}
