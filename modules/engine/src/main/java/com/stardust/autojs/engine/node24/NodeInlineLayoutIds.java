package com.stardust.autojs.engine.node24;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Both Auto.js id="name" and Android id="@+id/name" declare a binding. */
public final class NodeInlineLayoutIds {
 private static final Pattern ID = Pattern.compile("(?:\\s|^)(?:android:)?id\\s*=\\s*[\"'](?:@\\+?id/)?([A-Za-z_][A-Za-z0-9_]*)[\"']");
 private NodeInlineLayoutIds() {}
 public static Set<String> names(String xml) {
  Set<String> result=new LinkedHashSet<>();
  Matcher matcher=ID.matcher(xml==null?"":xml);
  while(matcher.find())result.add(matcher.group(1));
  return result;
 }
}
