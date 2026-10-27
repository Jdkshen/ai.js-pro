package com.jdkshen.aijspro.external.foreground;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunningTaskStateTest {
    private JSONObject task(String token,String engine,long started)throws Exception{return new JSONObject().put("token",token).put("name","同名脚本.js").put("engine",engine).put("startedAt",started);}
    @Test public void samePathExecutionsStayDistinctAndLatestSelectionIsStable()throws Exception{
        RunningTaskState state=new RunningTaskState();JSONObject a=task("script:1","Rhino",100),b=task("script:2","Node",200),c=task("script:3","QuickJS",150);
        state.update(new JSONArray().put(a).put(b).put(c).put(b),300);
        assertEquals(3,state.tasks().size());assertEquals("script:2",state.tasks().get(0).token);assertEquals(100,state.oldestStart(300));
        state.update(new JSONArray().put(c).put(b).put(a),400);assertEquals("script:2",state.tasks().get(0).token);
        assertTrue(state.engines().contains("Rhino"));assertTrue(state.engines().contains("QuickJS"));assertTrue(state.engines().contains("Node"));
    }
    @Test public void finishedAndScheduledItemsNeverKeepAnIslandAlive()throws Exception{
        RunningTaskState state=new RunningTaskState();state.update(new JSONArray().put(task("script:1","Node",0)),1000);
        assertTrue(state.contains("script:1"));
        state.update(new JSONArray().put(task("timed:1","Rhino",0)).put(task("intent:2","Rhino",0)),2000);
        assertTrue(state.tasks().isEmpty());assertFalse(state.contains("script:1"));
        state.update(new JSONArray().put(task("script:1","Node",0)),3000);assertEquals(3000,state.tasks().get(0).startedAt);
    }
    @Test public void unknownStartTimesRemainStableAndRemovedTokensCannotBeStopped()throws Exception{
        RunningTaskState state=new RunningTaskState();JSONArray jobs=new JSONArray().put(task("script:9","Node",0));
        state.update(jobs,1000);state.update(jobs,9000);assertEquals(1000,state.oldestStart(9000));
        state.update(new JSONArray(),10000);assertFalse(state.contains("script:9"));
    }
    @Test public void elapsedHandlesClockMovementAndLongRuns(){assertEquals("00:00",RunningTaskState.elapsed(-1));assertEquals("01:05",RunningTaskState.elapsed(65000));assertEquals("25:01:01",RunningTaskState.elapsed(90061000));}
}
