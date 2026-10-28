package com.stardust.autojs.runtime.api.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteUiObjectParityTest {
    private RemoteAutomationBridge.Backend backend;
    private final List<String> calls = new ArrayList<>();
    private final List<JSONObject> queries = new ArrayList<>();
    private JSONArray tree;
    private boolean actionResult = true;
    private boolean missing;
    private int x, y;

    private JSONObject node(String path, String id, String text) throws Exception {
        return new JSONObject().put("path",new JSONArray(path)).put("id",id).put("text",text)
            .put("className","View").put("packageName","demo").put("bounds",new JSONArray("[0,0,100,100]"))
            .put("indexInParent",new JSONArray(path).length()==0 ? -1 : new JSONArray(path).getInt(new JSONArray(path).length()-1));
    }
    private RemoteUiObject setup() throws Exception {
        tree = new JSONArray().put(node("[]","root", ""))
            .put(node("[0]","panel", "old").put("childCount",3))
            .put(node("[0,0]","a","Alpha"))
            .put(node("[0,2]","b","Beta"))
            .put(node("[1]","other","Alpha"));
        backend=(RemoteAutomationBridge.Backend) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{RemoteAutomationBridge.Backend.class},(p,m,args)->{
                calls.add(m.getName());
                switch(m.getName()) {
                    case "isReady": case "isAccessibilityServiceEnabled": return true;
                    case "getLatestNodeDump": return tree.toString();
                    case "getNodeInfo": {
                        if(missing) return "";
                        JSONObject q=new JSONObject((String)args[0]);queries.add(q);
                        for(int i=0;i<tree.length();i++)if(matches(tree.getJSONObject(i),q))return tree.getJSONObject(i).toString();
                        return "";
                    }
                    case "findNodes": {
                        JSONObject q=new JSONObject((String)args[0]);JSONArray out=new JSONArray();
                        for(int i=0;i<tree.length();i++)if(matches(tree.getJSONObject(i),q))out.put(tree.getJSONObject(i));return out.toString();
                    }
                    case "clickNode": case "longClickNode": case "setTextNode": case "nodeAction": {
                        JSONObject q=new JSONObject((String)args[0]);queries.add(q);
                        if(m.getName().equals("nodeAction")) {
                            calls.add("action:"+args[2]); calls.add("args:"+args[3]);
                        }
                        return actionResult && !missing && matches(tree.getJSONObject(1),q);
                    }
                    case "click": x=(int)args[0];y=(int)args[1];return true;
                    default: throw new AssertionError("Unexpected "+m.getName());
                }
            });
        java.lang.reflect.Field field=RemoteAutomationBridge.class.getDeclaredField("sBackend");field.setAccessible(true);field.set(null,backend);java.lang.reflect.Field isolated=RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");isolated.setAccessible(true);isolated.setBoolean(null,true);
        return new RemoteUiObject(backend,"{}",0,new JSONObject(tree.getJSONObject(1).toString()),false);
    }
    private boolean matches(JSONObject n, JSONObject q) throws Exception {
        for(Iterator<String> it=q.keys();it.hasNext();) {
            String key=it.next();
            if(key.equals("textContains")) {if(!n.optString("text").contains(q.getString(key)))return false;}
            else if(!Objects.equals(n.opt(key)==null?null:n.opt(key).toString(),q.opt(key).toString()))return false;
        }
        return true;
    }
    @After public void cleanup() throws Exception {java.lang.reflect.Field f=RemoteAutomationBridge.class.getDeclaredField("sBackend");f.setAccessible(true);f.set(null,null);java.lang.reflect.Field isolated=RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");isolated.setAccessible(true);isolated.setBoolean(null,false);}
    @Test public void genericActionsForwardIdentityAndRealFailure() throws Exception {
        RemoteUiObject n=setup();
        for(String name:Arrays.asList("copy","cut","paste","select","collapse","expand","dismiss","show","scrollForward","scrollBackward","scrollUp","scrollDown","scrollLeft","scrollRight","contextClick","focus","clearFocus","accessibilityFocus","clearAccessibilityFocus")) {
            assertEquals(true,n.getClass().getMethod(name).invoke(n));
            assertTrue(calls.contains("action:"+name));
            assertEquals("[0]",queries.get(queries.size()-1).getJSONArray("path").toString());
        }
        actionResult=false;assertFalse(n.focus());
    }
    @Test public void actionsNeverRetryOrFallBackToCoordinates() throws Exception {
        RemoteUiObject n=setup();missing=true;assertFalse(n.click());assertFalse(n.longClick());assertFalse(n.setText("x"));
        assertEquals(1,Collections.frequency(calls,"clickNode"));assertFalse(calls.contains("click"));
    }
    @Test public void centerUsesCurrentGeometryAndRejectsGoneTarget() throws Exception {
        RemoteUiObject n=setup();tree.getJSONObject(1).put("bounds",new JSONArray("[20,30,60,90]"));
        assertTrue(n.clickCenter());assertEquals(40,x);assertEquals(60,y);
        missing=true;calls.clear();assertFalse(n.clickCenter());assertFalse(calls.contains("click"));
    }
    @Test public void changingTextDoesNotRedirectAnActionToReplacementNode() throws Exception {
        RemoteUiObject n=setup();tree.getJSONObject(1).put("text","replacement");assertFalse(n.click());assertTrue(n.exists());
    }
    @Test public void navigationUsesActualSparseChildIndexes() throws Exception {
        RemoteUiObject n=setup();assertEquals("root",n.parent().id());assertEquals(2,n.children().size());
        assertEquals("a",n.child(0).id());assertNull(n.child(1));assertEquals("b",n.child(2).id());assertEquals(3,n.childCount());
    }
    @Test public void disappearedRootHasNoFreshRelatives() throws Exception {
        RemoteUiObject n=setup();missing=true;assertNull(n.parent());assertTrue(n.children().empty());
    }
    @Test public void localFiltersMustNotDropWireIdCondition() throws Exception {
        setup();tree.getJSONObject(2).put("checked",true);tree.getJSONObject(4).put("checked",true);
        RemoteUiObjectCollection values=new RemoteUiSelector().id("a").checked().find();
        assertEquals(1,values.size());assertEquals("a",values.get(0).id());
        assertFalse(calls.contains("getLatestNodeDump"));
    }
    @Test public void localOnlyFilterUsesFullDumpNotCappedFindNodes() throws Exception {
        setup();tree.getJSONObject(2).put("checked",true);calls.clear();
        RemoteUiObjectCollection values=new RemoteUiSelector().checked().find();
        assertEquals(1,values.size());assertTrue(calls.contains("getLatestNodeDump"));
        tree.getJSONObject(2).remove("checked");
    }
    @Test public void scopedFindReturnsCollectionAndKeepsDescendantsOnly() throws Exception {
        RemoteUiObject n=setup();assertEquals(2,n.find(new RemoteUiSelector().className("View")).size());
        assertEquals("a",n.findOne(new RemoteUiSelector().textContains("Al")).id());
        assertEquals(1,n.findByViewId("b").size());
    }
    @Test public void parameterizedActionsPreserveArguments() throws Exception {
        RemoteUiObject n=setup();assertTrue(n.setSelection(1,3));assertTrue(calls.contains("args:[1,3]"));
        assertTrue(n.setProgress(0.5f));assertTrue(calls.contains("args:[0.5]"));
        assertTrue(n.scrollTo(2,3));assertTrue(calls.contains("args:[2,3]"));
        int before=calls.size();try{n.setSelection(2,1);fail();}catch(IllegalArgumentException expected){}
        try{n.setProgress(Float.POSITIVE_INFINITY);fail();}catch(IllegalArgumentException expected){}
        assertEquals(before,calls.size());
    }
    @Test public void collectionActionsAggregateAllMembersRatherThanAny() throws Exception {
        RemoteUiObject n=setup();RemoteUiObjectCollection c=new RemoteUiObjectCollection(Arrays.asList(n,n));
        assertTrue(c.click());assertEquals(2,Collections.frequency(calls,"clickNode"));
        actionResult=false;assertFalse(c.click());
    }
    @Test public void passwordSetterDoesNotExposePlaintextOrUseItAsIdentity() throws Exception {
        RemoteUiObject n=setup();JSONObject raw=tree.getJSONObject(1);raw.put("password",true).put("text","secret");
        n=new RemoteUiObject(backend,"{}",0,new JSONObject(raw.toString()),false);
        assertEquals("",n.text());assertTrue(n.setText("new-secret"));assertEquals("",n.text());
        assertFalse(queries.get(queries.size()-1).has("text"));assertEquals("",n.text());
    }
    @Test public void emptyCollectionStillRejectsInvalidArguments() {
        RemoteUiObjectCollection c=new RemoteUiObjectCollection(Collections.emptyList());
        assertTrue(c.click());
        try { c.setSelection(2,1); fail(); } catch (IllegalArgumentException expected) {}
        try { c.scrollTo(-1,0); fail(); } catch (IllegalArgumentException expected) {}
        try { c.setProgress(Float.NaN); fail(); } catch (IllegalArgumentException expected) {}
    }


}
