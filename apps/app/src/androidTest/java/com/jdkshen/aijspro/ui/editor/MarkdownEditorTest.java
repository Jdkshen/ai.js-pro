package com.jdkshen.aijspro.ui.editor;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class MarkdownEditorTest {
 @Test public void markdownFileOpensAndLoadsTextInInternalEditor() throws Exception {
  Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  Context context=instrumentation.getTargetContext();
  File file=new File(context.getCacheDir(),"README.MD");
  Files.write(file.toPath(),"# Markdown QA\ncontent".getBytes(StandardCharsets.UTF_8));
  ProCodeEditorActivity activity=null;
  try {
   activity=(ProCodeEditorActivity)instrumentation.startActivitySync(ProCodeEditorActivity.intent(context,file).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
   final ProCodeEditorActivity target=activity; instrumentation.waitForIdleSync();
   long deadline=SystemClock.uptimeMillis()+8000; boolean found=false;
   while(SystemClock.uptimeMillis()<deadline && !(found=contains(target.getWindow().getDecorView(),"# Markdown QA"))) SystemClock.sleep(100);
   assertTrue("Markdown content was not loaded in internal editor",found);
  } finally { if(activity!=null){ final ProCodeEditorActivity target=activity; instrumentation.runOnMainSync(target::finish); } file.delete(); }
 }
 private boolean contains(View view,String text){
  if(view instanceof EditText && ((EditText)view).getText().toString().contains(text)) return true;
  if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) if(contains(((ViewGroup)view).getChildAt(i),text)) return true;
  return false;
 }
}
