package com.stardust.autojs.engine.node24;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class NodeInlineLayoutIdsTest {
 @Test public void acceptsActualAutoJsSampleShorthandAndAndroidIds() {
  assertEquals(new LinkedHashSet<>(Arrays.asList("status","field","btn","android_name")),NodeInlineLayoutIds.names("<text id=\"status\"/><input id='field'/><button id=\"btn\"/><text android:id='@+id/android_name'/>"));
 }
 @Test public void deduplicatesAndDoesNotMatchOtherAttributeSuffixes() {
  assertEquals(Collections.singleton("btn"),NodeInlineLayoutIds.names("<text custom:id='bad' invalid='bad' id='btn'/><button id='@id/btn'/>"));
 }
}
