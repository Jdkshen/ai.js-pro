package com.stardust.autojs.runtime.api.remote;
import org.json.*;import org.junit.*;import java.lang.reflect.*;import java.util.*;
import static org.junit.Assert.*;
public class RemoteUiSelectorPredicateTest {
    private Object previous; private boolean isolated; private JSONArray tree;private RemoteAutomationBridge.Backend backend;
    private JSONObject raw(String path,String id,String text) throws Exception {
        return new JSONObject().put("path",new JSONArray(path)).put("id",id).put("text",text)
                .put("className","View").put("packageName","pkg").put("desc","").put("bounds",new JSONArray("[0,0,10,10]"));
    }
    private boolean matches(JSONObject n,JSONObject q) throws Exception {
        for(String k:new String[]{"id","text","className","packageName","desc","path"})
            if(q.has(k)&&!Objects.equals(n.opt(k).toString(),q.opt(k).toString()))return false;
        return true;
    }
    @Before public void setup() throws Exception {
        tree=new JSONArray().put(raw("[]","root","root")).put(raw("[0]","a","Alpha")).put(raw("[1]","b","Beta"));
        backend=(RemoteAutomationBridge.Backend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RemoteAutomationBridge.Backend.class},(p,m,a)->{
            if(m.getName().equals("isReady")||m.getName().equals("isAccessibilityServiceEnabled"))return true;
            if(m.getName().equals("getLatestNodeDump"))return tree.toString();
            if(m.getName().equals("getNodeInfo")){JSONObject q=new JSONObject((String)a[0]);for(int i=0;i<tree.length();i++)if(matches(tree.getJSONObject(i),q))return tree.getJSONObject(i).toString();return "";}
            throw new AssertionError("unexpected backend "+m.getName());
        });
        Field f=RemoteAutomationBridge.class.getDeclaredField("sBackend");f.setAccessible(true);previous=f.get(null);f.set(null,backend);
        f=RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");f.setAccessible(true);isolated=f.getBoolean(null);f.setBoolean(null,true);
    }
    @After public void cleanup() throws Exception {
        Field f=RemoteAutomationBridge.class.getDeclaredField("sBackend");f.setAccessible(true);f.set(null,previous);
        f=RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");f.setAccessible(true);f.setBoolean(null,isolated);
    }
    @Test public void predicatesComposeAndUseRemoteMethodNodes() {
        int[] calls={0};RemoteUiObjectCollection found=new RemoteUiSelector().className("View")
            .filter(n->{calls[0]++;return n.text().startsWith("A");}).addFilter(n->n.id().equals("a")).find();
        assertEquals(1,found.size());assertEquals("Alpha",found.get(0).text());assertEquals(3,calls[0]);
        assertTrue(new RemoteUiSelector().filter(n->false).find().empty());
    }
    @Test public void predicateErrorsAndUnsupportedBfsAreNotIgnored() {
        try {new RemoteUiSelector().filter(n->{throw new IllegalArgumentException("predicate-error");}).find();fail();}
        catch(com.stardust.autojs.runtime.exception.ScriptException e){assertTrue(e.getMessage().contains("predicate-error"));}
        try {new RemoteUiSelector().algorithm("BFS").filter(n->true).find();fail();}
        catch(com.stardust.autojs.runtime.exception.ScriptException expected){}
    }
    @Test public void scopedQueryIncludesRootAndIsNotGloballyCappedBeforeScope() throws Exception {
        tree=new JSONArray();for(int i=0;i<520;i++)tree.put(raw("["+i+"]","outside-"+i,"outside"));
        JSONObject root=raw("[600]","scope","scope");tree.put(root).put(raw("[600,0]","inside","inside"));
        RemoteUiObject node=new RemoteUiObject(backend,"{}",0,root,false);
        RemoteUiObjectCollection found=node.find(new RemoteUiSelector().className("View"));
        assertEquals(2,found.size());assertEquals("scope",found.get(0).id());assertEquals("inside",found.get(1).id());
    }
}
