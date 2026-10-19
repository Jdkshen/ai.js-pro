package com.stardust.autojs.engine.node24;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mozilla.javascript.*;
import static org.junit.Assert.*;
public class NodeRhinoEventArgumentsTest {
 @Test public void objectsAreRealRhinoObjectsWithNestedArraysAndUnicode() throws Exception {
  Context cx=Context.enter();
  try {
   Scriptable scope=cx.initStandardObjects();
   JSONArray wire=new JSONArray().put(new JSONObject().put("content","计时😀").put("items",new JSONArray().put(2))).put(JSONObject.NULL);
   Object[] args=NodeRhinoEventArguments.decode(scope,wire);
   assertTrue(args[0] instanceof NativeObject);assertNull(args[1]);
   ScriptableObject.putProperty(scope,"payload",args[0]);
   assertEquals("计时😀:2:true",Context.toString(cx.evaluateString(scope,"payload.content+':'+payload.items[0]+':'+Array.isArray(payload.items)","event",1,null)));
  } finally {Context.exit();}
 }
}
