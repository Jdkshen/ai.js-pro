package com.stardust.autojs.runtime.tool;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class ToolchainComponentStoreTest {
 @Rule public TemporaryFolder temp = new TemporaryFolder();
 @Test public void independentComponentStoresKeepTheirIdentity() throws Exception {
  assertEquals("cpp",new ToolchainStore(temp.newFolder("cpp")).status().getString("id"));
  assertEquals("jadx",new ToolchainStore(temp.newFolder("jadx"),"jadx").status().getString("id"));
  assertEquals("apk-resources",new ToolchainStore(temp.newFolder("resources"),"apk-resources").status().getString("id"));
 }
 @Test public void invalidComponentNamesCannotDefineStores() throws Exception {
  for(String id:new String[]{"../jadx","a/b","","JADX"}) {
   try {new ToolchainStore(temp.getRoot(),id);fail(id);}catch(IllegalArgumentException expected){}
  }
 }
}
