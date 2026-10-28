package com.stardust.autojs.runtime.api.remote;
import org.json.JSONObject;
import org.junit.Test;
import com.stardust.autojs.runtime.exception.ScriptException;
import static org.junit.Assert.*;
public class RemoteUiRangeInfoTest {
    private RemoteUiObject node(JSONObject raw) { return new RemoteUiObject(null,"{}",0,raw,false); }
    @Test public void missingRangeIsNull() { assertNull(node(new JSONObject()).getRangeInfo()); }
    @Test public void explicitNullRangeIsNull() throws Exception {
        assertNull(node(new JSONObject().put("rangeInfo",JSONObject.NULL)).getRangeInfo());
    }
    @Test public void rangeValuesPreserveNumbersAndType() throws Exception {
        RemoteUiObject.RangeInfo r=node(new JSONObject().put("rangeInfo",new JSONObject()
                .put("type",0).put("min",-1.5).put("max",100).put("current",66.25))).getRangeInfo();
        assertEquals(0,r.getType());assertEquals(-1.5f,r.getMin(),0);assertEquals(100f,r.getMax(),0);assertEquals(66.25f,r.getCurrent(),0);
    }
    @Test public void malformedRangeDoesNotMasqueradeAsZero() throws Exception {
        for(Object bad:new Object[]{"66",true,JSONObject.NULL,1e100}) {
            try { node(new JSONObject().put("rangeInfo",new JSONObject().put("type",0).put("min",0)
                    .put("max",100).put("current",bad))).getRangeInfo(); fail(); }
            catch(ScriptException expected) { assertTrue(expected.getMessage().contains("rangeInfo")); }
        }
        try { node(new JSONObject().put("rangeInfo",new JSONObject().put("type",1.000000001)
                .put("min",0).put("max",100).put("current",1))).getRangeInfo();fail(); }
        catch(ScriptException expected) {}
    }
}
