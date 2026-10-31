package com.stardust.autojs.runtime.api.remote;

import com.stardust.autojs.runtime.exception.ScriptException;
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
    private JSONObject lastFindQuery;
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
                        JSONObject q=new JSONObject((String)args[0]);lastFindQuery=q;JSONArray out=new JSONArray();
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
    private void installBackend(RemoteAutomationBridge.Backend value) throws Exception {
        backend=value;
        java.lang.reflect.Field field=RemoteAutomationBridge.class.getDeclaredField("sBackend");field.setAccessible(true);field.set(null,value);
        java.lang.reflect.Field isolated=RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");isolated.setAccessible(true);isolated.setBoolean(null,true);
    }
    private boolean matches(JSONObject n, JSONObject q) throws Exception {
        for(Iterator<String> it=q.keys();it.hasNext();) {
            String key=it.next();
            if(key.equals("textContains")) {if(!n.optString("text").contains(q.getString(key)))return false;}
            else if(key.equals("algorithm")) {if(!q.optString(key).equals("BFS")&&!q.optString(key).equals("DFS"))return false;}
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
    @Test public void mixedFiltersUseFullDumpAndKeepWireIdCondition() throws Exception {
        setup();tree.getJSONObject(2).put("checked",true);tree.getJSONObject(4).put("checked",true);
        RemoteUiObjectCollection values=new RemoteUiSelector().id("a").checked().find();
        assertEquals(1,values.size());assertEquals("a",values.get(0).id());
        assertTrue(calls.contains("getLatestNodeDump"));assertFalse(calls.contains("findNodes"));
    }
    @Test public void localOnlyFilterUsesFullDumpNotCappedFindNodes() throws Exception {
        setup();tree.getJSONObject(2).put("checked",true);calls.clear();
        RemoteUiObjectCollection values=new RemoteUiSelector().checked().find();
        assertEquals(1,values.size());assertTrue(calls.contains("getLatestNodeDump"));
        tree.getJSONObject(2).remove("checked");
    }
    @Test public void scopedFindIncludesRootLikeNativeAndKeepsSubtree() throws Exception {
        RemoteUiObject n=setup();assertEquals(3,n.find(new RemoteUiSelector().className("View")).size());
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

    private JSONObject checkedNode(String id,String text) throws Exception {
        return new JSONObject().put("path",new JSONArray("[0]"))
            .put("id",id).put("text",text).put("desc","")
            .put("className","android.view.View").put("packageName","demo")
            .put("bounds",new JSONArray("[0,0,100,100]"))
            .put("clickable",false).put("enabled",true).put("scrollable",false)
            .put("depth",1).put("checked",true);
    }
    private JSONObject dumpNode(String id,String text,String desc,String className,String packageName,
                                boolean clickable,boolean enabled,boolean scrollable,int depth,String bounds) throws Exception {
        return new JSONObject().put("path",new JSONArray("[0]"))
            .put("id",id).put("text",text).put("desc",desc)
            .put("className",className).put("packageName",packageName)
            .put("bounds",new JSONArray(bounds))
            .put("clickable",clickable).put("enabled",enabled).put("scrollable",scrollable)
            .put("depth",depth).put("checked",true);
    }
    private JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private void installSnapshotBackend(final JSONArray nodes,final int findNodesCap) throws Exception {
        tree=nodes;
        installBackend((RemoteAutomationBridge.Backend) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{RemoteAutomationBridge.Backend.class},(p,m,args)->{
                calls.add(m.getName());
                switch(m.getName()) {
                    case "isReady": case "isAccessibilityServiceEnabled": return true;
                    case "getLatestNodeDump": return tree.toString();
                    case "findNodes": {
                        JSONObject q=new JSONObject((String)args[0]);JSONArray out=new JSONArray();
                        for(int i=0;i<tree.length()&&out.length()<findNodesCap;i++)
                            if(matches(tree.getJSONObject(i),q))out.put(tree.getJSONObject(i));
                        return out.toString();
                    }
                    default: throw new AssertionError("Unexpected "+m.getName());
                }
            }));
    }
    private RemoteUiObjectCollection findOnDump(JSONArray nodes,RemoteUiSelector selector) throws Exception {
        installSnapshotBackend(nodes,500);
        return selector.find();
    }

    @Test public void mixedQueryFindsLateTargetBeyondWireCapAndAppliesLocalFilters() throws Exception {
        JSONArray nodes=new JSONArray();
        for(int i=0;i<520;i++) {
            nodes.put(new JSONObject().put("path",new JSONArray().put(i))
                .put("id","pkg:id/early").put("text","item early "+i).put("desc","")
                .put("className","android.view.View").put("packageName","demo")
                .put("bounds",new JSONArray("[0,0,10,10]"))
                .put("clickable",false).put("enabled",true).put("scrollable",false)
                .put("depth",1));
        }
        nodes.put(dumpNode("pkg:id/target","item late 520","","android.widget.TargetView","demo",
            false,true,false,1,"[10,20,30,40]"));
        nodes.put(dumpNode("pkg:id/target2","item late 521","","android.widget.TargetView","demo",
            false,true,false,1,"[10,20,30,40]"));
        RemoteUiSelector selector=new RemoteUiSelector()
            .textContains("item").packageName("demo")
            .idContains("target").boundsInside(10,20,30,40)
            .classNameMatches(".*TargetView").packageNameContains("de");
        calls.clear();
        RemoteUiObjectCollection values=findOnDump(nodes,selector);
        assertEquals(2,values.size());
        assertEquals("pkg:id/target",values.get(0).id());
        assertEquals("pkg:id/target2",values.get(1).id());
        assertEquals(0,Collections.frequency(calls,"findNodes"));
        assertEquals(1,Collections.frequency(calls,"getLatestNodeDump"));

        RemoteUiObjectCollection limited=selector.find(1);
        assertEquals(1,limited.size());
        assertEquals("pkg:id/target",limited.get(0).id());
        assertEquals(0,Collections.frequency(calls,"findNodes"));
        assertEquals(2,Collections.frequency(calls,"getLatestNodeDump"));
    }

    @Test public void wireOnlyQueryKeepsFindNodesFastPath() throws Exception {
        setup();calls.clear();
        RemoteUiObjectCollection values=new RemoteUiSelector().id("b").find();
        assertEquals(1,values.size());assertEquals("b",values.get(0).id());
        assertEquals(1,Collections.frequency(calls,"findNodes"));
        assertFalse(calls.contains("getLatestNodeDump"));
    }

    @Test public void algorithmIsForwardedCanonicallyAndUnknownRejected() throws Exception {
        setup();calls.clear();
        RemoteUiObjectCollection bfs=new RemoteUiSelector().id("b").algorithm("bfs").find();
        assertEquals(1,bfs.size());assertEquals("b",bfs.get(0).id());
        assertEquals(1,Collections.frequency(calls,"findNodes"));
        assertFalse(calls.contains("getLatestNodeDump"));
        assertEquals("BFS",lastFindQuery.getString("algorithm"));

        new RemoteUiSelector().id("b").algorithm("DfS").find();
        assertEquals("DFS",lastFindQuery.getString("algorithm"));

        new RemoteUiSelector().id("b").find();
        assertFalse(lastFindQuery.has("algorithm"));

        calls.clear();
        try { new RemoteUiSelector().algorithm("spiral"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("spiral")); }
        assertTrue(calls.isEmpty());
    }

    @Test public void bfsWithLocalPredicatesRefusesTruncatedSnapshotInsteadOfFakeSorting() throws Exception {
        setup();calls.clear();
        // 这棵 fake 树没有 childCount，无法证明快照完整；R06 要求截断时明确拒绝，
        // 而不是对 DFS 结果排序后冒充完整 BFS。
        try { new RemoteUiSelector().checked().algorithm("BFS").find(); fail(); }
        catch (ScriptException expected) {
            assertTrue(expected.getMessage().contains("BFS"));
            assertTrue(expected.getMessage().contains("截断"));
        }
        assertTrue(calls.contains("getLatestNodeDump"));
        assertFalse(calls.contains("findNodes"));
    }

    @Test public void dfsWithLocalPredicatesStillUsesFullDump() throws Exception {
        setup();tree.getJSONObject(2).put("checked",true);calls.clear();
        RemoteUiObjectCollection values=new RemoteUiSelector()
            .textContains("Alpha").checked().algorithm("DFS").find();
        assertEquals(1,values.size());assertEquals("a",values.get(0).id());
        assertTrue(calls.contains("getLatestNodeDump"));assertFalse(calls.contains("findNodes"));
    }

    @Test public void mixedSnapshotAppliesEveryWireFieldAndIdNormalization() throws Exception {
        JSONObject target=dumpNode("pkg:id/target","late item exact","target description",
            "android.widget.TextView","demo.app",true,true,true,1,"[10,20,30,40]");
        JSONArray nodes=new JSONArray().put(target);
        nodes.put(copy(target).put("id","pkg:id/other"));
        nodes.put(copy(target).put("text","late item exact extra"));
        nodes.put(copy(target).put("desc","other description"));
        nodes.put(copy(target).put("className","android.widget.Button"));
        nodes.put(copy(target).put("packageName","demo.other"));
        nodes.put(copy(target).put("clickable",false));
        nodes.put(copy(target).put("enabled",false));
        nodes.put(copy(target).put("scrollable",false));
        nodes.put(copy(target).put("depth",2));
        nodes.put(copy(target).put("bounds",new JSONArray("[11,20,30,40]")));
        nodes.put(copy(target).put("id",""));
        RemoteUiSelector selector=new RemoteUiSelector()
            .id("target").text("late item exact")
            .desc("target description").descContains("get desc")
            .className("android.widget.TextView").packageName("demo.app")
            .clickable(true).enabled(true).scrollable(true).depth(1)
            .bounds(10,20,30,40).checked();
        RemoteUiObjectCollection values=findOnDump(nodes,selector);
        assertEquals(1,values.size());
        assertEquals("pkg:id/target",values.get(0).id());

        RemoteUiObjectCollection fullId=findOnDump(new JSONArray().put(copy(target)),
            new RemoteUiSelector().id("pkg:id/target").checked());
        assertEquals(1,fullId.size());
        RemoteUiObjectCollection anonymous=findOnDump(new JSONArray().put(checkedNode("","any")),
            new RemoteUiSelector().id("").checked());
        assertEquals(0,anonymous.size());
    }

    @Test public void snapshotTextFamilyUsesWireMatchingSemantics() throws Exception {
        RemoteUiObjectCollection contains=findOnDump(new JSONArray()
            .put(checkedNode("pkg:id/a","prefix middle suffix"))
            .put(checkedNode("pkg:id/b","prefix suffix")),
            new RemoteUiSelector().textContains("middle").checked());
        assertEquals(1,contains.size());assertEquals("pkg:id/a",contains.get(0).id());

        RemoteUiObjectCollection starts=findOnDump(new JSONArray()
            .put(checkedNode("pkg:id/a","prefix middle suffix"))
            .put(checkedNode("pkg:id/b","xprefix middle suffix")),
            new RemoteUiSelector().textStartsWith("prefix").checked());
        assertEquals(1,starts.size());assertEquals("pkg:id/a",starts.get(0).id());

        RemoteUiObjectCollection regex=findOnDump(new JSONArray()
            .put(checkedNode("pkg:id/a","BEGIN ONLY"))
            .put(checkedNode("pkg:id/b","begin only extra")),
            new RemoteUiSelector().textMatches("(?i)^BEGIN\\s+ONLY$").checked());
        assertEquals(1,regex.size());assertEquals("pkg:id/a",regex.get(0).id());
    }


}
