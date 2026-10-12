package com.jdkshen.aijspro.ui.editor;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import static org.junit.Assert.*;

/** Real WebView regressions: native multitouch -> character hit testing and save barriers. */
public class AceCodeEditorTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private ProCodeEditorActivity activity;
    private AceCodeEditor editor;
    private WebView webView;
    private File file;
    private SharedPreferences preferences;
    private Map<String, ?> originalPreferences;

    @Before public void openEditor() throws Exception {
        Context context=instrumentation.getTargetContext();
        Field prefsName=ProCodeEditorActivity.class.getDeclaredField("EDITOR_PREFS");prefsName.setAccessible(true);
        preferences=context.getSharedPreferences((String)prefsName.get(null),Context.MODE_PRIVATE);
        originalPreferences=new HashMap<>(preferences.getAll());
        File dir=new File(context.getCacheDir(), "ace-test-"+System.nanoTime());
        assertTrue(dir.mkdirs());file=new File(dir,"main.js");
        StringBuilder source=new StringBuilder();
        for(int i=0;i<60;i++)source.append("const value").append(i).append(" = 'text';\n");
        FileUtils.writeStringToFile(file,source.toString(),"UTF-8");
        activity=(ProCodeEditorActivity)instrumentation.startActivitySync(
                ProCodeEditorActivity.intent(context,file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        boolean[] focused={false};long focusDeadline=SystemClock.uptimeMillis()+5000;
        do{
            instrumentation.runOnMainSync(()->focused[0]=activity.hasWindowFocus());
            if(focused[0])break;SystemClock.sleep(20);
        }while(SystemClock.uptimeMillis()<focusDeadline);
        assertTrue("The real editor window must receive focus before UI input",focused[0]);
        long deadline=SystemClock.uptimeMillis()+15000;
        while(editor==null && SystemClock.uptimeMillis()<deadline){
            instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
            SystemClock.sleep(50);
        }
        assertNotNull(editor);snapshot();
        instrumentation.runOnMainSync(()->{
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            editor.setTextSizeSp(17);
        });snapshot();
        Field field=AceCodeEditor.class.getDeclaredField("webView");field.setAccessible(true);
        webView=(WebView)field.get(editor);
    }
    @After public void closeEditor(){
        if(activity!=null)finishAndWaitForDestroy();
        if(originalPreferences!=null){
            SharedPreferences.Editor restore=preferences.edit().clear();
            originalPreferences.forEach((key,value)->{
                if(value instanceof String)restore.putString(key,(String)value);
                else if(value instanceof Integer)restore.putInt(key,(Integer)value);
                else if(value instanceof Long)restore.putLong(key,(Long)value);
                else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);
                else if(value instanceof Float)restore.putFloat(key,(Float)value);
                else if(value instanceof Set)restore.putStringSet(key,(Set<String>)value);
            });restore.commit();
        }
    }
    private Object call(String name,Class<?>[] types,Object... args){try{
        Method method=ProCodeEditorActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);
        return method.invoke(activity,args);
    }catch(Exception e){throw new AssertionError(e);}}
    private void snapshot() throws Exception{
        CountDownLatch latch=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->editor.withSnapshot(latch::countDown));
        assertTrue("Editor snapshot timed out",latch.await(15,TimeUnit.SECONDS));
    }
    private String eval(String source) throws Exception{
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        instrumentation.runOnMainSync(()->webView.evaluateJavascript(source,result->{value.set(result);latch.countDown();}));
        assertTrue("JS evaluation timed out",latch.await(15,TimeUnit.SECONDS));return value.get();
    }
    @Test public void tabPopupClosesRightAndProtectsDirtyTabsWhenClosingOthers() throws Exception {
        File second = new File(file.getParentFile(), "second.js");
        File third = new File(file.getParentFile(), "third.js");
        FileUtils.writeStringToFile(second, "const second = 2;\n", "UTF-8");
        FileUtils.writeStringToFile(third, "const third = 3;\n", "UTF-8");
        instrumentation.runOnMainSync(() -> {
            call("attachFileTab", new Class<?>[]{File.class, String.class}, second, "const second = 2;\n");
            call("attachFileTab", new Class<?>[]{File.class, String.class}, third, "const third = 3;\n");
        });
        Object[] tabs = editorTabs().toArray();
        Field label = tabs[0].getClass().getDeclaredField("label"); label.setAccessible(true);
        Field tabEditor = tabs[0].getClass().getDeclaredField("editor"); tabEditor.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            call("selectTab", new Class<?>[]{tabs[1].getClass()}, tabs[1]);
            try { ((android.view.View)label.get(tabs[1])).performLongClick(); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        clickVisibleText("关闭右侧标签页");
        waitForTabCount(2);
        String original = FileUtils.readFileToString(file, "UTF-8");
        instrumentation.runOnMainSync(() -> {
            call("selectTab", new Class<?>[]{tabs[0].getClass()}, tabs[0]);
            editor.insert("// retained draft\n");
        });
        snapshot();
        String draft = editor.getText();
        assertNotEquals(original, draft);
        instrumentation.runOnMainSync(() -> {
            call("selectTab", new Class<?>[]{tabs[1].getClass()}, tabs[1]);
            try { ((android.view.View)label.get(tabs[1])).performLongClick(); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        clickVisibleText("关闭其他标签页");
        clickVisibleText("取消");
        assertEquals(2, editorTabs().size());
        assertEquals(original, FileUtils.readFileToString(file, "UTF-8"));
        assertEquals(draft, ((AceCodeEditor)tabEditor.get(tabs[0])).getText());
        instrumentation.runOnMainSync(() -> {
            try { ((android.view.View)label.get(tabs[1])).performLongClick(); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        clickVisibleText("关闭其他标签页");
        clickVisibleText("全部保存");
        waitForTabCount(1);
        assertEquals(draft, FileUtils.readFileToString(file, "UTF-8"));
        assertEquals("const second = 2;\n", ((AceCodeEditor)tabEditor.get(tabs[1])).getText());
    }
    @Test public void closeButtonTargetsInactiveTabAndPreservesUnsavedChanges() throws Exception {
        File second = new File(file.getParentFile(), "second.js");
        FileUtils.writeStringToFile(second, "const second = 2;\n", "UTF-8");
        String original = FileUtils.readFileToString(file, "UTF-8");
        instrumentation.runOnMainSync(() -> editor.insert("// retained close-button draft\n"));
        snapshot();
        String draft = editor.getText();
        instrumentation.runOnMainSync(() -> call("attachFileTab", new Class<?>[]{File.class, String.class},
                second, "const second = 2;\n"));
        Object[] tabs = editorTabs().toArray();
        Field closeField = tabs[0].getClass().getDeclaredField("closeButton"); closeField.setAccessible(true);
        Field editorField = tabs[0].getClass().getDeclaredField("editor"); editorField.setAccessible(true);
        AceCodeEditor first = (AceCodeEditor) editorField.get(tabs[0]);
        AceCodeEditor secondEditor = (AceCodeEditor) editorField.get(tabs[1]);
        instrumentation.runOnMainSync(() -> call("selectTab", new Class<?>[]{tabs[1].getClass()}, tabs[1]));
        android.view.View close = (android.view.View) closeField.get(tabs[0]);
        assertEquals("关闭 " + file.getName(), close.getContentDescription());
        tapNativeView(close);
        clickVisibleText("取消");
        assertEquals(2, editorTabs().size());
        assertEquals(draft, first.getText());
        assertEquals(original, FileUtils.readFileToString(file, "UTF-8"));
        tapNativeView(close);
        clickVisibleText("保存");
        waitForTabCount(1);
        assertEquals(draft, FileUtils.readFileToString(file, "UTF-8"));
        assertEquals("const second = 2;\n", secondEditor.getText());
    }

    @Test public void workspaceThemeColorsFollowExistingAndNewRowsWithoutLosingDraft() throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences themePrefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> beforePrefs = new HashMap<>(themePrefs.getAll());
        com.jdkshen.aijspro.theme.AppThemeRepository repository = com.jdkshen.aijspro.theme.AppThemeRepository.get(context);
        String original = FileUtils.readFileToString(file, "UTF-8");
        File folder = new File(file.getParentFile(), "theme-folder"); assertTrue(folder.mkdir());
        File nested = new File(folder, "nested.js");
        FileUtils.writeStringToFile(nested, "const nested = 1;\n", "UTF-8");
        instrumentation.runOnMainSync(() -> call("switchWorkspace", new Class<?>[]{File.class}, file.getParentFile()));
        instrumentation.runOnMainSync(() -> editor.insert("// theme draft\n")); snapshot();
        String draft = editor.getText();
        Field drawerField = ProCodeEditorActivity.class.getDeclaredField("mWorkspaceDrawer"); drawerField.setAccessible(true);
        Field rootField = ProCodeEditorActivity.class.getDeclaredField("mTreeRootLabel"); rootField.setAccessible(true);
        Field treeField = ProCodeEditorActivity.class.getDeclaredField("mTreeContainer"); treeField.setAccessible(true);
        Field tabsField = editorTabs().get(0).getClass().getDeclaredField("closeButton"); tabsField.setAccessible(true);
        Object firstTab = editorTabs().get(0);
        File second = new File(file.getParentFile(), "second.js");
        FileUtils.writeStringToFile(second, "const second = 2;\n", "UTF-8");
        instrumentation.runOnMainSync(() -> {
            call("attachFileTab", new Class<?>[]{File.class, String.class}, second, "const second = 2;\n");
            call("selectTab", new Class<?>[]{firstTab.getClass()}, firstTab);
        });
        try {
            for (int mode : new int[]{1, 2, 1}) {
                instrumentation.runOnMainSync(() -> {
                    repository.setThemeMode(mode); repository.refreshFromLegacyTheme();
                    call("openWorkspaceDrawer", new Class<?>[0]);
                });
                instrumentation.waitForIdleSync(); SystemClock.sleep(300);
                com.jdkshen.aijspro.theme.AppThemePalette palette = repository.getPalette();
                assertEquals(mode == 2, palette.isDark);
                android.view.View drawer = (android.view.View) drawerField.get(activity);
                android.widget.TextView root = (android.widget.TextView) rootField.get(activity);
                android.view.ViewGroup tree = (android.view.ViewGroup) treeField.get(activity);
                assertEquals(palette.textPrimary, root.getCurrentTextColor());
                android.widget.TextView folderRow = tree.findViewWithTag(folder.getAbsolutePath()); assertNotNull(folderRow);
                assertEquals(palette.textPrimary, folderRow.getCurrentTextColor());
                android.widget.TextView nestedRow = tree.findViewWithTag(nested.getAbsolutePath());
                if (nestedRow == null) {
                    instrumentation.runOnMainSync(folderRow::performClick);
                    instrumentation.waitForIdleSync();
                    nestedRow = tree.findViewWithTag(nested.getAbsolutePath());
                }
                assertNotNull("A newly expanded row must inherit the current palette", nestedRow);
                assertEquals(palette.textPrimary, nestedRow.getCurrentTextColor());
                android.widget.TextView close = (android.widget.TextView) tabsField.get(editorTabs().get(0));
                assertEquals("关闭 main.js", close.getContentDescription());
                assertEquals(palette.textPrimary, close.getCurrentTextColor());
                Field labelField = firstTab.getClass().getDeclaredField("label"); labelField.setAccessible(true);
                android.widget.TextView inactive = (android.widget.TextView) labelField.get(editorTabs().get(1));
                android.widget.TextView inactiveClose = (android.widget.TextView) tabsField.get(editorTabs().get(1));
                assertEquals(palette.textSecondary, inactive.getCurrentTextColor());
                assertEquals(palette.textSecondary, inactiveClose.getCurrentTextColor());
                int[] location = new int[2];
                instrumentation.runOnMainSync(() -> drawer.getLocationOnScreen(location));
                android.graphics.Bitmap picture = instrumentation.getUiAutomation().takeScreenshot();
                assertNotNull(picture);
                assertEquals("The visible drawer must use the app surface", palette.surfacePrimary,
                        picture.getPixel(location[0] + 3, location[1] + drawer.getHeight() / 2));
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(context.getCacheDir(), "workspace-theme-" + mode + ".png"))) {
                    picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } finally { picture.recycle(); }
                snapshot(); assertEquals(draft, editor.getText());
                assertEquals("true", eval("editor.session.getUndoManager().hasUndo()"));
                assertEquals(original, FileUtils.readFileToString(file, "UTF-8"));
                Field layoutField = ProCodeEditorActivity.class.getDeclaredField("mDrawerLayout"); layoutField.setAccessible(true);
                androidx.drawerlayout.widget.DrawerLayout layout = (androidx.drawerlayout.widget.DrawerLayout) layoutField.get(activity);
                instrumentation.runOnMainSync(() -> layout.closeDrawer(androidx.core.view.GravityCompat.START));
                instrumentation.waitForIdleSync(); SystemClock.sleep(300);
                Field toolField = ProCodeEditorActivity.class.getDeclaredField("mToolRow"); toolField.setAccessible(true);
                android.view.ViewGroup tools = (android.view.ViewGroup) ((android.view.ViewGroup) toolField.get(activity)).getChildAt(0);
                android.widget.TextView toolLabel = (android.widget.TextView) ((android.view.ViewGroup) tools.getChildAt(0)).getChildAt(1);
                assertEquals(palette.textSecondary, toolLabel.getCurrentTextColor());
                Field redoField = ProCodeEditorActivity.class.getDeclaredField("mRedoTool"); redoField.setAccessible(true);
                android.view.View redo = (android.view.View) redoField.get(activity);
                assertFalse(redo.isEnabled()); assertEquals(0.38f, redo.getAlpha(), 0.001f);
                JSONObject actual = new JSONObject();
                actual.put("dark", mode == 2);
                actual.put("drawer", ((android.graphics.drawable.ColorDrawable) drawer.getBackground()).getColor());
                actual.put("file", nestedRow.getCurrentTextColor());
                actual.put("toolbarLabel", toolLabel.getCurrentTextColor());
                actual.put("activeTab", ((android.widget.TextView) labelField.get(firstTab)).getCurrentTextColor());
                actual.put("inactiveTab", inactive.getCurrentTextColor());
                actual.put("activeClose", close.getCurrentTextColor());
                actual.put("inactiveClose", inactiveClose.getCurrentTextColor());
                actual.put("disabledAlpha", redo.getAlpha());
                actual.put("ace", new JSONObject(eval("({theme:editor.getTheme(),background:getComputedStyle(editor.container).backgroundColor,gutter:getComputedStyle(editor.container.querySelector('.ace_gutter')).backgroundColor})")));
                for (String name : new String[]{"mToolRow", "mTabRow", "mShortcutBar"}) {
                    Field field = ProCodeEditorActivity.class.getDeclaredField(name); field.setAccessible(true);
                    actual.put(name, ((android.graphics.drawable.ColorDrawable) ((android.view.View) field.get(activity)).getBackground()).getColor());
                }
                FileUtils.writeStringToFile(new File(context.getCacheDir(), "workspace-colors-" + mode + ".json"), actual.toString(), "UTF-8");
                android.graphics.Bitmap editorPicture = instrumentation.getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(context.getCacheDir(), "workspace-editor-theme-" + mode + ".png"))) {
                    editorPicture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } finally { editorPicture.recycle(); }
            }
            eval("editor.undo();true"); snapshot(); assertEquals(original, editor.getText());
        } finally {
            instrumentation.runOnMainSync(() -> {
                SharedPreferences.Editor restore = themePrefs.edit();
                for (String key : new String[]{"app_theme_mode", "follow_system_theme", "key_night_mode"}) {
                    Object value = beforePrefs.get(key);
                    if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                    else restore.remove(key);
                }
                restore.commit(); repository.refreshFromLegacyTheme();
            });
        }
    }

    @Test public void functionsPanelTracksThemeAndKeepsDraft() throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences themePrefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> beforePrefs = new HashMap<>(themePrefs.getAll());
        com.jdkshen.aijspro.theme.AppThemeRepository repository = com.jdkshen.aijspro.theme.AppThemeRepository.get(context);
        String original = FileUtils.readFileToString(file, "UTF-8");
        instrumentation.runOnMainSync(() -> editor.insert("// function-panel draft\n")); snapshot();
        String draft = editor.getText();
        try {
            instrumentation.runOnMainSync(() -> {
                repository.setThemeMode(1); repository.refreshFromLegacyTheme();
                call("toggleFunctionsPanel", new Class<?>[0]);
            });
            Field field = ProCodeEditorActivity.class.getDeclaredField("mFunctionsKeyboard"); field.setAccessible(true);
            com.jdkshen.aijspro.ui.edit.keyboard.FunctionsKeyboardView functions =
                    (com.jdkshen.aijspro.ui.edit.keyboard.FunctionsKeyboardView) field.get(activity);
            assertNotNull(functions);
            Field propertiesField = functions.getClass().getDeclaredField("mPropertiesView"); propertiesField.setAccessible(true);
            Field modulesField = functions.getClass().getDeclaredField("mModulesView"); modulesField.setAccessible(true);
            androidx.recyclerview.widget.RecyclerView properties = (androidx.recyclerview.widget.RecyclerView) propertiesField.get(functions);
            androidx.recyclerview.widget.RecyclerView modules = (androidx.recyclerview.widget.RecyclerView) modulesField.get(functions);
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (properties.getChildCount() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertTrue(properties.getChildCount() > 0);
            for (com.jdkshen.aijspro.model.indices.Module module : com.jdkshen.aijspro.model.indices.Modules.getInstance().getModules(context).blockingFirst())
                for (com.jdkshen.aijspro.model.indices.Property property : module.getProperties())
                    assertNotEquals("微信公众号", property.getKey());
            for (int mode : new int[]{1, 2, 1}) {
                instrumentation.runOnMainSync(() -> {
                    repository.setThemeMode(mode); repository.refreshFromLegacyTheme();
                });
                instrumentation.waitForIdleSync(); SystemClock.sleep(300);
                com.jdkshen.aijspro.theme.AppThemePalette palette = repository.getPalette();
                JSONObject actual = new JSONObject(); actual.put("dark", mode == 2);
                actual.put("content", ((android.graphics.drawable.ColorDrawable) properties.getBackground()).getColor());
                actual.put("modules", ((android.graphics.drawable.ColorDrawable) modules.getBackground()).getColor());
                actual.put("text", ((android.widget.TextView) properties.getChildAt(0)).getCurrentTextColor());
                assertEquals(palette.surfaceSecondary, actual.getInt("content"));
                assertEquals(palette.surfaceElevated, actual.getInt("modules"));
                assertEquals(palette.textPrimary, actual.getInt("text"));
                for (int i=0; i<modules.getChildCount(); i++) {
                    android.widget.TextView row = (android.widget.TextView) modules.getChildAt(i);
                    assertEquals(palette.textPrimary, row.getCurrentTextColor());
                    assertEquals(row.isSelected() ? palette.surfaceSecondary : palette.surfaceElevated,
                            ((android.graphics.drawable.ColorDrawable) row.getBackground()).getColor());
                }
                FileUtils.writeStringToFile(new File(context.getCacheDir(), "functions-colors-"+mode+".json"), actual.toString(), "UTF-8");
                android.graphics.Bitmap picture = instrumentation.getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(context.getCacheDir(), "functions-theme-"+mode+".png"))) {
                    picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } finally { picture.recycle(); }
                snapshot(); assertEquals(draft, editor.getText());
                assertEquals(original, FileUtils.readFileToString(file, "UTF-8"));
                assertEquals("true", eval("editor.session.getUndoManager().hasUndo()"));
            }
            instrumentation.runOnMainSync(() -> call("toggleFunctionsPanel", new Class<?>[0]));
            eval("editor.undo();true"); snapshot(); assertEquals(original, editor.getText());
        } finally {
            instrumentation.runOnMainSync(() -> {
                SharedPreferences.Editor restore = themePrefs.edit();
                for (String key : new String[]{"app_theme_mode", "follow_system_theme", "key_night_mode"}) {
                    Object value = beforePrefs.get(key);
                    if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                    else restore.remove(key);
                }
                restore.commit(); repository.refreshFromLegacyTheme();
            });
        }
    }

    private void tapNativeView(android.view.View view) {
        instrumentation.waitForIdleSync();
        int[] location = new int[2];
        instrumentation.runOnMainSync(() -> {
            view.getLocationOnScreen(location);
            location[0] += view.getWidth() / 2;
            location[1] += view.getHeight() / 2;
        });
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, location[0], location[1], 0);
        MotionEvent up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, location[0], location[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.getUiAutomation().injectInputEvent(down, true);
        instrumentation.getUiAutomation().injectInputEvent(up, true);
        down.recycle(); up.recycle();
    }

    private List<?> editorTabs() throws Exception {
        Field field = ProCodeEditorActivity.class.getDeclaredField("mTabs"); field.setAccessible(true);
        return (List<?>)field.get(activity);
    }
    private void waitForTabCount(int count) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (editorTabs().size() != count && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30);
        assertEquals(count, editorTabs().size());
    }
    private void clickVisibleText(String text) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                boolean clicked = false;
                for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                    boolean dialogSave = !"保存".equals(text) || (node.getClassName() != null
                            && node.getClassName().toString().endsWith("Button"));
                    if (dialogSave && text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser()) {
                        android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                        long now = SystemClock.uptimeMillis();
                        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                        MotionEvent up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        instrumentation.getUiAutomation().injectInputEvent(down, true);
                        instrumentation.getUiAutomation().injectInputEvent(up, true);
                        down.recycle(); up.recycle(); clicked = true;
                    }
                    node.recycle();
                }
                root.recycle();
                if (clicked) return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Visible menu/dialog item not found: " + text);
    }
    @Test public void pinchBothWaysThenTapLandsOnTheSameCharacter() throws Exception{
        String original=editor.getText();
        float initial=editor.getTextSizeSp();
        pinch(100,200);snapshot();assertTrue("Pinch must enlarge the font",editor.getTextSizeSp()>initial+2);
        verifyTap(4,6);verifyTap(10,9);
        pinch(200,75);snapshot();assertTrue("Pinch must shrink the font",editor.getTextSizeSp()<initial);
        verifyTap(3,5);verifyTap(12,8);
        assertEquals("Pinch/tap must not edit text",original,editor.getText());
        assertEquals("Page scale must stay at one","1",eval("visualViewport.scale"));
    }
    private void verifyTap(int row,int column) throws Exception{
        verifyTap(row,column,0);
    }
    @Test public void repeatedNativePinchesOnOneMiBFileKeepFramesAndHitTestingResponsive() throws Exception {
        StringBuilder content=new StringBuilder("const head = (1 + 2);\n");
        for(int i=0;content.length()<1024*1024;i++)content.append("const row").append(i)
                .append(" = {left: 12345, right: 'a longer line to exercise wrapping while zooming'};\n");
        String text=content.toString();
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        for(boolean wrap:new boolean[]{false,true}) {
            instrumentation.runOnMainSync(()->editor.setTextSizeSp(17));snapshot();
            eval("editor.session.setUseWrapMode("+wrap+");editor.session.setScrollTop(0);"
                    +"editor.session.setScrollLeft(0);editor.selection.moveCursorTo(0,14);true");
            SystemClock.sleep(250);
            eval("window.pinchProbe={running:true,gaps:[],last:0,fontMs:[],fontMeasures:0,forcedResizes:0};"
                    +"window.pinchOriginal={setFontSize:editor.setFontSize,measure:editor.renderer.updateFontSize,resize:editor.renderer.onResize};"
                    +"editor.setFontSize=function(s){const t=performance.now();const r=pinchOriginal.setFontSize.call(this,s);pinchProbe.fontMs.push(performance.now()-t);return r;};"
                    +"editor.renderer.updateFontSize=function(){pinchProbe.fontMeasures++;return pinchOriginal.measure.apply(this,arguments);};"
                    +"editor.renderer.onResize=function(force){if(force)pinchProbe.forcedResizes++;return pinchOriginal.resize.apply(this,arguments);};"
                    +"requestAnimationFrame(function tick(t){if(!pinchProbe.running)return;if(pinchProbe.last)pinchProbe.gaps.push(t-pinchProbe.last);pinchProbe.last=t;requestAnimationFrame(tick);});true");
            JSONObject result;
            try {
                for(int i=0;i<3;i++){pinch(90,210);pinch(210,40);}
                SystemClock.sleep(150);
                result=new JSONObject(eval("(()=>{pinchProbe.running=false;const g=pinchProbe.gaps.slice().sort((a,b)=>a-b),f=pinchProbe.fontMs.slice().sort((a,b)=>a-b);"
                        +"return {wrap:"+wrap+",frames:g.length,maxGap:g[g.length-1]||0,p95Gap:g[Math.floor(g.length*.95)]||0,"
                        +"fontChanges:f.length,fontP95:f[Math.floor(f.length*.95)]||0,fontMeasures:pinchProbe.fontMeasures,forcedResizes:pinchProbe.forcedResizes};})()"));
            } finally {
                eval("pinchProbe.running=false;editor.setFontSize=pinchOriginal.setFontSize;editor.renderer.updateFontSize=pinchOriginal.measure;editor.renderer.onResize=pinchOriginal.resize;true");
            }
            Log.i("AcePerformance","nativePinch="+result);
            assertTrue("Pinching must keep producing frames: "+result,result.getInt("frames")>10);
            assertTrue("Most pinch frames must stay under 50ms: "+result,result.getDouble("p95Gap")<50);
            assertTrue("Pinching must avoid a 300ms stall: "+result,result.getDouble("maxGap")<300);
            snapshot();assertEquals(text,editor.getText());assertEquals("1",eval("visualViewport.scale"));
            assertEquals("Pinch preview must be cleared before single-finger taps","\"\"",eval("editor.container.style.transform"));
            verifyTap(4,6);verifyTap(10,9);
        }
    }
    private void verifyTap(int row,int column,int holdMs) throws Exception{
        eval("editor.resize(true);editor.gotoLine("+(row+1)+",0,true);"
                +"editor.session.setScrollLeft(0);editor.session.setScrollTop(Math.max(0,"
                +row+"*editor.renderer.lineHeight-innerHeight/2));editor.resize(true)");
        SystemClock.sleep(100);
        JSONObject point=new JSONObject(eval("(()=>{const p=editor.renderer.textToScreenCoordinates("+row+","+column+");return {x:p.pageX,y:p.pageY+editor.renderer.lineHeight/2};})()"));
        assertTrue("Test tap must stay inside the visible editor after IME resize",
                point.getDouble("y")>0 && point.getDouble("y")<Double.parseDouble(eval("innerHeight")));
        assertTrue("Test tap must stay inside the editor after horizontal scrolling",
                point.getDouble("x")>0 && point.getDouble("x")<Double.parseDouble(eval("innerWidth")));
        Log.i("AcePinch","beforeTap row="+row+" column="+column+" point="+point+" state="+eval(
                "JSON.stringify({font:editor.getFontSize(),line:editor.renderer.lineHeight,char:editor.renderer.characterWidth,"
                +"scrollX:editor.session.getScrollLeft(),scrollY:editor.session.getScrollTop(),width:innerWidth,height:innerHeight,"
                +"density:devicePixelRatio,scale:visualViewport.scale,zoom:document.body.style.zoom,cursor:editor.getCursorPosition()})"));
        int[] location=new int[2];instrumentation.runOnMainSync(()->webView.getLocationOnScreen(location));
        float density=instrumentation.getTargetContext().getResources().getDisplayMetrics().density;
        float x=location[0]+(float)point.getDouble("x")*density;
        float y=location[1]+(float)point.getDouble("y")*density;
        long time=SystemClock.uptimeMillis();touch(time,MotionEvent.ACTION_DOWN,new float[]{x},new float[]{y});
        if(holdMs>0)SystemClock.sleep(holdMs);
        touch(time,MotionEvent.ACTION_UP,new float[]{x},new float[]{y});SystemClock.sleep(150);
        JSONObject position=new JSONObject(eval("editor.getCursorPosition()"));
        Log.i("AcePinch","afterTap row="+row+" column="+column+" physical="+x+","+y+" actual="+position);
        assertEquals("Tap row after pinch",row,position.getInt("row"));
        assertEquals("Tap column after pinch",column,position.getInt("column"));
    }
    private void pinch(float from,float to){
        int[] loc=new int[2];instrumentation.runOnMainSync(()->webView.getLocationOnScreen(loc));
        float center=loc[0]+webView.getWidth()/2f,y=loc[1]+Math.min(300,webView.getHeight()/2f);
        long time=SystemClock.uptimeMillis();
        touch(time,MotionEvent.ACTION_DOWN,new float[]{center-from},new float[]{y});
        touch(time,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{center-from,center+from},new float[]{y,y});
        for(int i=1;i<=12;i++){
            float half=from+(to-from)*i/12;
            touch(time,MotionEvent.ACTION_MOVE,new float[]{center-half,center+half},new float[]{y,y});SystemClock.sleep(16);
        }
        touch(time,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{center-to,center+to},new float[]{y,y});
        touch(time,MotionEvent.ACTION_UP,new float[]{center-to},new float[]{y});SystemClock.sleep(150);
    }
    private void touch(long down,int action,float[] x,float[] y){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[x.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[x.length];
        for(int i=0;i<x.length;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=x[i];coords[i].y=y[i];coords[i].pressure=1;coords[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x.length,properties,coords,
                0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue(instrumentation.getUiAutomation().injectInputEvent(event,true));}finally{event.recycle();}
    }
    @Test public void immediateSaveWaitsForQueuedTextAndKeepsUtf8() throws Exception{
        String text="// 最后一笔不能丢\nconst answer = 42;\n";
        CountDownLatch complete=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{
            editor.setText(text);
            call("saveActive",new Class<?>[]{boolean.class},false);
            editor.withSnapshot(complete::countDown);
        });
        assertTrue(complete.await(15,TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        assertEquals(text,FileUtils.readFileToString(file,"UTF-8"));
        assertFalse(editor.isTextChanged());
    }
    @Test public void foldAndUndoPreserveTheFullDocument() throws Exception{
        String text="function example() {\n    const inner = 1;\n    return inner;\n}\n";
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        instrumentation.runOnMainSync(editor::foldAllTopLevel);snapshot();
        assertTrue(Integer.parseInt(eval("editor.session.getAllFolds().length"))>0);
        assertEquals(text,editor.getText());
        instrumentation.runOnMainSync(()->{editor.unfoldAll();editor.jumpToEnd();editor.insert("// edit");});snapshot();
        instrumentation.runOnMainSync(editor::undo);snapshot();assertEquals(text,editor.getText());
    }
    @Test public void matchingBracketGlyphsFollowTabsWrapFoldZoomAndTheme() throws Exception {
        String text="// 中文😀\nfunction sample() {\n\tconst value = (1 + 2);\n\treturn [value];\n}\n";
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        eval("editor.selection.moveCursorTo(2,16);SimpleBracketHighlighter.refresh();true");
        for(int size:new int[]{8,17,32}) {
            instrumentation.runOnMainSync(()->editor.setTextSizeSp(size));snapshot();
            assertBracketGlyphs(2);
        }
        eval("editor.session.setUseWrapMode(true);editor.session.setWrapLimitRange(12,12);true");
        assertBracketGlyphs(2);
        eval("editor.session.setUseWrapMode(false);editor.selection.moveCursorTo(1,19);"
                +"editor.session.foldAll(1,5);SimpleBracketHighlighter.refresh();true");
        assertTrue("The bracket fixture must actually be folded",Integer.parseInt(eval("editor.session.getAllFolds().length"))>0);
        assertBracketGlyphs(2);
        eval("editor.setTheme('ace/theme/textmate');true");
        long deadline=SystemClock.uptimeMillis()+3000;
        while(!eval("editor.container.classList.contains('ace_dark')").equals("false") && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
        assertBracketGlyphs(2);
        assertEquals("true",eval("Array.from(document.querySelectorAll('.ace_matching-bracket-character')).every(e=>getComputedStyle(e).color==='rgb(255, 0, 0)')"));
        eval("editor.setTheme('ace/theme/tomorrow_night');true");
        deadline=SystemClock.uptimeMillis()+3000;
        while(!eval("editor.container.classList.contains('ace_dark')").equals("true") && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
        assertBracketGlyphs(2);
        assertEquals("true",eval("Array.from(document.querySelectorAll('.ace_matching-bracket-character')).every(e=>getComputedStyle(e).color==='rgb(118, 255, 2)')"));
        snapshot();assertEquals("Highlighting must never change the document",text,editor.getText());
        assertFalse("Highlighting must not create undo entries",editor.canUndo());
    }
    private void assertBracketGlyphs(int count) throws Exception {
        SystemClock.sleep(80);
        JSONObject result=new JSONObject(eval("(()=>{SimpleBracketHighlighter.refresh();editor.renderer.updateFull(true);"
                +"const p=SimpleBracketHighlighter.status().positions,els=Array.from(document.querySelectorAll('.ace_matching-bracket-character'));"
                +"const expected=editor.session.getMatchingBracketRanges(editor.getCursorPosition())||[];"
                +"return {count:els.length,matched:JSON.stringify(p.map(x=>[x.row,x.column]))===JSON.stringify(expected.map(x=>[x.start.row,x.start.column])),"
                +"aligned:els.every((el,i)=>{const r=el.getBoundingClientRect(),s=editor.renderer.textToScreenCoordinates(p[i].row,p[i].column);"
                +"return Math.abs(r.left-s.pageX)<2 && Math.abs(r.top-s.pageY)<2 && el.dataset.bracket===p[i].character;})};})()"));
        assertEquals("Both visible matching bracket glyphs must be drawn: "+result,count,result.getInt("count"));
        assertTrue("Matching must agree with Ace's syntax-aware bracket matcher",result.getBoolean("matched"));
        assertTrue("Glyph positions must agree with real Ace character hit testing",result.getBoolean("aligned"));
    }
    @Test public void bracketHighlightSwitchesSessionsAndDoesNotRescanWhileScrolling() throws Exception {
        eval("window.bracketFirst=editor.session;window.bracketOther=ace.createEditSession('const other = [3];','ace/mode/javascript');"
                +"editor.setSession(bracketOther);editor.selection.moveCursorTo(0,15);SimpleBracketHighlighter.refresh();true");
        assertBracketGlyphs(2);
        assertEquals("true",eval("Object.values(bracketFirst.getMarkers(true)).every(m=>m!==editor.$simpleBracketHighlighter) && "
                +"Object.values(bracketFirst.getMarkers(true)).filter(m=>!m.range).length===0"));
        eval("editor.setSession(bracketFirst);bracketOther.destroy();true");
        StringBuilder large=new StringBuilder("function huge() {\n");
        while(large.length()<1024*1024)large.append("    const padding = 123456789;\n");
        String text=large.toString();
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        eval("editor.selection.moveCursorTo(0,17);SimpleBracketHighlighter.refresh();true");
        assertEquals("true",eval("SimpleBracketHighlighter.status().limited"));
        JSONObject before=new JSONObject(eval("SimpleBracketHighlighter.status()"));
        eval("for(let i=0;i<30;i++){editor.session.setScrollTop(i*20);editor.renderer.updateFull(true);}true");
        SystemClock.sleep(100);
        JSONObject after=new JSONObject(eval("SimpleBracketHighlighter.status()"));
        assertEquals("Scrolling must reuse the cached match instead of scanning the file",before.getInt("scans"),after.getInt("scans"));
        assertTrue("A large unmatched block must not scan the entire document",after.getDouble("scanMs")<100);
        snapshot();assertEquals(text,editor.getText());
        Log.i("AcePerformance","bracketHighlightScanMs="+after.getDouble("scanMs")+" scrollRescans=0");
    }
    @Test public void workerCompletionUsesInferredTypesAndUnsavedSiblingTab() throws Exception{
        File helper=new File(file.getParentFile(),"helper.js");
        FileUtils.writeStringToFile(helper,"exports.savedProperty = 1;","UTF-8");
        instrumentation.runOnMainSync(()->editor.setInitialText("const helper = require('./helper');\nhelper."));snapshot();
        AceCodeEditor main=editor;
        instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},helper));
        long deadline=SystemClock.uptimeMillis()+10000;
        do{
            instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
            if(editor!=main)break;SystemClock.sleep(50);
        }while(SystemClock.uptimeMillis()<deadline);
        assertNotSame(main,editor);
        instrumentation.runOnMainSync(()->editor.setText("exports.unsavedProperty = 2;"));snapshot();
        File otherWorkspace=new File(file.getParentFile(),"other-workspace");assertTrue(otherWorkspace.mkdir());
        instrumentation.runOnMainSync(()->call("switchWorkspace",new Class<?>[]{File.class},otherWorkspace));
        instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},file));
        editor=main;snapshot();
        Field field=AceCodeEditor.class.getDeclaredField("webView");field.setAccessible(true);webView=(WebView)field.get(editor);
        eval("window.aceTestResult=null;port.languageRequest('textDocument/completion',{textDocument:{uri:'main.js',version:version},position:{line:1,character:7}}).then(r=>window.aceTestResult=r);true");
        String result="null";deadline=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<deadline){result=eval("window.aceTestResult");if(!result.equals("null"))break;SystemClock.sleep(100);}
        assertFalse("Language request did not return: "+eval("port.languageStatus()"),result.equals("null"));
        JSONArray items=new JSONArray(result);boolean found=false;
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("label").equals("unsavedProperty"))found=true;
        assertTrue("Actual worker must resolve the sibling tab's unsaved text: "+result,found);
        assertEquals("exports.savedProperty = 1;",FileUtils.readFileToString(helper,"UTF-8"));
    }
    @Test public void immediateRunExecutesTheLatestQueuedText() throws Exception{
        File output=new File(file.getParentFile(),"run-result.txt");
        String source="files.write("+JSONObject.quote(output.getAbsolutePath())+", 'latest queued edit');\n";
        instrumentation.runOnMainSync(()->{editor.setText(source);call("runCurrent",new Class<?>[0]);});
        long deadline=SystemClock.uptimeMillis()+10000;
        while(!output.isFile() && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        assertTrue("Editor run must execute after its save barrier",output.isFile());
        assertEquals("latest queued edit",FileUtils.readFileToString(output,"UTF-8"));
        assertEquals(source,FileUtils.readFileToString(file,"UTF-8"));
    }
    @Test public void restoringTwelveTabsOnlyLoadsTheSelectedEditor() throws Exception {
        finishAndWaitForDestroy();
        JSONArray paths=new JSONArray();
        StringBuilder content=new StringBuilder();
        for(int i=0;i<714;i++)content.append("const value").append(i).append(" = 'restored file';\n");
        String text=content.toString();
        FileUtils.writeStringToFile(file,text,"UTF-8");
        for(int i=0;i<11;i++){
            File sibling=new File(file.getParentFile(),"restored-"+i+".js");
            FileUtils.writeStringToFile(sibling,text,"UTF-8");paths.put(sibling.getAbsolutePath());
        }
        paths.put(file.getAbsolutePath());
        Field openTabs=ProCodeEditorActivity.class.getDeclaredField("PREF_OPEN_TABS");openTabs.setAccessible(true);
        preferences.edit().putString((String)openTabs.get(null),paths.toString()).commit();
        long started=SystemClock.uptimeMillis();
        activity=(ProCodeEditorActivity)instrumentation.startActivitySync(
                ProCodeEditorActivity.intent(instrumentation.getTargetContext(),file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        editor=null;long deadline=started+15000;
        while(editor==null && SystemClock.uptimeMillis()<deadline){
            instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
            SystemClock.sleep(20);
        }
        assertNotNull(editor);snapshot();
        Field tabsField=ProCodeEditorActivity.class.getDeclaredField("mTabs");tabsField.setAccessible(true);
        Field webViewField=AceCodeEditor.class.getDeclaredField("webView");webViewField.setAccessible(true);
        int[] counts=new int[2];
        instrumentation.runOnMainSync(()->{try{
            List<?> tabs=(List<?>)tabsField.get(activity);counts[0]=tabs.size();
            for(Object tab:tabs){Field f=tab.getClass().getDeclaredField("editor");f.setAccessible(true);
                if(webViewField.get(f.get(tab))!=null)counts[1]++;}
        }catch(Exception error){throw new AssertionError(error);}});
        Log.i("AcePerformance","restoredTabs="+counts[0]+" loadedWebViews="+counts[1]
                +" selectedFileReadyMs="+(SystemClock.uptimeMillis()-started));
        assertEquals("All previous tabs remain available",12,counts[0]);
        assertEquals(text,editor.getText());
        assertEquals("Hidden restored tabs must not load WebViews",1,counts[1]);
    }
    @Test public void foldArrowFitsItsLineAtSmallAndLargeFontSizes() throws Exception {
        String text="function example() {\n    return 1;\n}\n";
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        for(int size:new int[]{8,17,40}){
            instrumentation.runOnMainSync(()->editor.setTextSizeSp(size));snapshot();SystemClock.sleep(100);
            JSONObject shape=new JSONObject(eval("(()=>{const x=document.querySelector('.ace_fold-widget');"
                    +"const g=getComputedStyle(x,'::before');return {line:editor.renderer.lineHeight,"
                    +"width:parseFloat(g.width),height:parseFloat(g.height),color:g.backgroundColor};})()"));
            assertTrue("Arrow must stay inside its row at font "+size,shape.getDouble("height")<=shape.getDouble("line"));
            assertTrue("Arrow must not cover line numbers",shape.getDouble("width")<=11);
            assertEquals("rgb(0, 215, 222)",shape.getString("color"));
        }
        instrumentation.runOnMainSync(editor::foldAllTopLevel);snapshot();
        assertEquals(1,Integer.parseInt(eval("editor.session.getAllFolds().length")));
        assertEquals(text,editor.getText());
        instrumentation.runOnMainSync(editor::unfoldAll);snapshot();
        assertEquals(0,Integer.parseInt(eval("editor.session.getAllFolds().length")));
    }
    @Test public void oneMegabyteFileOpensAndEditsWithoutStartingFullSemanticAnalysis() throws Exception {
        instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();
        StringBuilder content=new StringBuilder();
        for(int i=0;content.length()<1024*1024;i++)content.append("function value").append(i)
                .append("() { return '").append(i).append("'; }\n");
        String text=content.toString();FileUtils.writeStringToFile(file,text,"UTF-8");
        preferences.edit().putString("open_tabs","").commit();
        long started=SystemClock.uptimeMillis();
        activity=(ProCodeEditorActivity)instrumentation.startActivitySync(
                ProCodeEditorActivity.intent(instrumentation.getTargetContext(),file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        editor=null;long deadline=started+15000;
        while(editor==null && SystemClock.uptimeMillis()<deadline){
            instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
            SystemClock.sleep(20);
        }
        assertNotNull(editor);snapshot();
        long opened=SystemClock.uptimeMillis()-started;
        Log.i("AcePerformance","oneMiBFileReadyMs="+opened+" characters="+text.length());
        assertEquals(text,editor.getText());
        Field f=AceCodeEditor.class.getDeclaredField("webView");f.setAccessible(true);webView=(WebView)f.get(editor);
        instrumentation.runOnMainSync(()->{editor.jumpToEnd();editor.insert("// 大文件末尾😀\n");});snapshot();
        assertEquals(text+"// 大文件末尾😀\n",editor.getText());
        instrumentation.runOnMainSync(editor::undo);snapshot();assertEquals(text,editor.getText());
        JSONObject language=new JSONObject(eval("port.languageStatus()"));
        assertFalse("One MiB files must not start full TypeScript analysis",language.getBoolean("worker"));
        instrumentation.runOnMainSync(()->call("saveActive",new Class<?>[]{boolean.class},false));snapshot();
        assertEquals(text,FileUtils.readFileToString(file,"UTF-8"));
        assertTrue("One MiB file must become editable within five seconds: "+opened,opened<5000);
    }
    @Test public void incrementalNativeTextKeepsCrLfAndEmojiBeforeSaving() throws Exception {
        String text="// 中文😀\r\nconst first = 1;\r\nconst second = 2;\r\n";
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        String result=eval("editor.session.replace(new (ace.require('ace/range').Range)(0,3,2,6),"
                +"'替换😀\\r\\nconst ');editor.getValue()");
        String changed=new JSONArray("["+result+"]").getString(0);
        assertEquals("Native cache must contain unsaved multiline edits without a full snapshot",changed,editor.getText());
        assertTrue(editor.isTextChanged());
        result=eval("editor.undo();editor.getValue()");
        assertEquals(new JSONArray("["+result+"]").getString(0),editor.getText());
    }
    @Test public void oneMegabyteSingleLineUsesPlainTextAndKeepsItsContents() throws Exception {
        StringBuilder source=new StringBuilder("const payload = '");
        for(int i=0;i<1024*1024;i++)source.append('x');source.append("';");
        String text=source.toString();
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();
        JSONObject language=new JSONObject(eval("port.languageStatus()"));
        assertTrue(language.getBoolean("plainText"));assertFalse(language.getBoolean("worker"));
        assertEquals(text,editor.getText());
        instrumentation.runOnMainSync(()->{editor.jumpToEnd();editor.insert("// 😀");});snapshot();
        assertEquals(text+"// 😀",editor.getText());
    }
    @Test public void smallFontLargeFileKeepsScrollingAndDoesNotFloodTheNativeBridge() throws Exception {
        StringBuilder source=new StringBuilder();
        String wide="abcdefghijklmnopqrstuvwxyz0123456789";
        for(int i=0;source.length()<1024*1024;i++)source.append("const row").append(i)
                .append(" = '").append(wide).append(wide).append(wide).append(wide).append("';\n");
        String text=source.toString();
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();SystemClock.sleep(150);
        eval("window.scrollProbe={updates:0,fullText:0,frames:0,maxGap:0,last:performance.now(),running:true,errors:[]};"
                +"addEventListener('error',e=>scrollProbe.errors.push(e.message));"
                +"const originalSnapshot=snapshot;snapshot=function(withText){scrollProbe.updates++;"
                +"if(withText!==false)scrollProbe.fullText++;return originalSnapshot(withText);};"
                +"requestAnimationFrame(function frame(now){const p=scrollProbe;if(!p.running)return;"
                +"p.frames++;p.maxGap=Math.max(p.maxGap,now-p.last);p.last=now;requestAnimationFrame(frame);});true");
        pinch(200,90);eval("port.snapshot(false)");
        assertTrue("Actual pinch must shrink the font",editor.getTextSizeSp()<10);
        for(int i=0;i<6;i++) swipeCode(false);
        SystemClock.sleep(500);
        double vertical=Double.parseDouble(eval("editor.session.getScrollTop()"));
        for(int i=0;i<4;i++) swipeCode(true);
        SystemClock.sleep(500);
        double horizontal=Double.parseDouble(eval("editor.session.getScrollLeft()"));
        JSONObject probe=new JSONObject(eval("scrollProbe.running=false;scrollProbe"));
        Log.i("AcePerformance","smallFontScroll="+probe+" scrollY="+vertical+" scrollX="+horizontal);
        assertTrue("Single-finger vertical drag must keep moving after pinch",vertical>100);
        assertTrue("Single-finger horizontal drag must keep moving after pinch",horizontal>20);
        assertEquals("Touch handling must not throw browser errors: "+probe,0,probe.getJSONArray("errors").length());
        assertEquals("Pinch and scrolling must not send the full large document",0,probe.getInt("fullText"));
        assertTrue("Native scroll updates must stay near the display frame rate: "+probe,
                probe.getInt("updates")<=probe.getInt("frames")+8);
        assertTrue("Scrolling must keep producing frames without a long stall: "+probe,probe.getDouble("maxGap")<500);
        snapshot();assertEquals(text,editor.getText());
        verifyTap(12,8);
    }
    private void swipeCode(boolean horizontal) {
        int[] loc=new int[2];instrumentation.runOnMainSync(()->webView.getLocationOnScreen(loc));
        float width=webView.getWidth(),height=webView.getHeight();
        float x=loc[0]+width*0.8f,y=loc[1]+height*0.7f;
        float dx=horizontal?-width*0.5f:0,dy=horizontal?0:-height*0.35f;
        long time=SystemClock.uptimeMillis();touch(time,MotionEvent.ACTION_DOWN,new float[]{x},new float[]{y});
        for(int i=1;i<=15;i++){
            touch(time,MotionEvent.ACTION_MOVE,new float[]{x+dx*i/15},new float[]{y+dy*i/15});SystemClock.sleep(16);
        }
        touch(time,MotionEvent.ACTION_UP,new float[]{x+dx},new float[]{y+dy});SystemClock.sleep(100);
    }
    @Test public void twelveLargeVisitedTabsShareOneRendererAndRestoreUnsavedUndo() throws Exception {
        StringBuilder content=new StringBuilder("function outer() {\n    return 1;\n}\n");
        for(int i=0;content.length()<1024*1024;i++)content.append("const value").append(i).append(" = 'large tab';\n");
        String text=content.toString();
        instrumentation.runOnMainSync(()->{editor.setInitialText(text);editor.jumpToEnd();editor.insert("// 未保存😀\n");});
        snapshot();AceCodeEditor first=editor;
        WebView shared=webView;
        instrumentation.runOnMainSync(()->{first.addOrRemoveBreakpointAtCurrentLine();first.jumpToStart();first.foldAllTopLevel();});
        snapshot();
        Field viewField=AceCodeEditor.class.getDeclaredField("webView");viewField.setAccessible(true);
        long started=SystemClock.uptimeMillis();
        for(int i=1;i<12;i++){
            File sibling=new File(file.getParentFile(),"large-tab-"+i+".js");
            FileUtils.writeStringToFile(sibling,text,"UTF-8");AceCodeEditor previous=editor;
            instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},sibling));
            long deadline=SystemClock.uptimeMillis()+10000;
            do{instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
                if(editor!=previous)break;SystemClock.sleep(20);
            }while(SystemClock.uptimeMillis()<deadline);
            assertNotSame(previous,editor);snapshot();
        }
        Field tabsField=ProCodeEditorActivity.class.getDeclaredField("mTabs");tabsField.setAccessible(true);
        int[] count={Integer.MAX_VALUE};long deadline=SystemClock.uptimeMillis()+10000;
        do{instrumentation.runOnMainSync(()->{try{
            count[0]=0;for(Object tab:(List<?>)tabsField.get(activity)){
                Field f=tab.getClass().getDeclaredField("editor");f.setAccessible(true);
                if(viewField.get(f.get(tab))!=null)count[0]++;
            }
        }catch(Exception error){throw new AssertionError(error);}});
            if(count[0]<=1)break;SystemClock.sleep(30);
        }while(SystemClock.uptimeMillis()<deadline);
        Log.i("AcePerformance","visitedLargeTabs=12 loadedWebViews="+count[0]+" allTabsReadyMs="+(SystemClock.uptimeMillis()-started));
        assertEquals("All document sessions must share one attached Chromium view",1,count[0]);
        assertNull("The oldest tab renderer must have been released",viewField.get(first));
        assertEquals(text+"// 未保存😀\n",first.getText());
        assertTrue(first.isTextChanged());assertTrue(first.canUndo());
        instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},file));
        editor=first;snapshot();webView=(WebView)viewField.get(editor);
        assertSame("Returning to a document must reuse the renderer",shared,webView);
        assertEquals(text+"// 未保存😀\n",editor.getText());
        assertTrue("Collapsed regions survive renderer replacement",Integer.parseInt(eval("editor.session.getAllFolds().length"))>0);
        assertEquals(1,editor.getBreakpoints().size());
        instrumentation.runOnMainSync(editor::undo);snapshot();assertEquals(text,editor.getText());
        instrumentation.runOnMainSync(editor::redo);snapshot();assertEquals(text+"// 未保存😀\n",editor.getText());
        assertNotEquals("Unsaved edits must not be written to disk when switching tabs",editor.getText(),FileUtils.readFileToString(file,"UTF-8"));
    }
    @Test public void ordinaryTapsReopenTheKeyboardAndTypeAfterItWasDismissed() throws Exception {
        InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        for(int size:new int[]{17,8}){
            instrumentation.runOnMainSync(()->editor.setTextSizeSp(size));snapshot();
            instrumentation.runOnMainSync(()->keyboard.hideSoftInputFromWindow(webView.getWindowToken(),0));
            waitForKeyboard(false);
            verifyTap(4,6,200);waitForKeyboard(true);
            String before=editor.getText();int offset=editor.getSelectionStart();
            typeAndWaitForText("x",before.substring(0,offset)+"x"+before.substring(offset));
            instrumentation.runOnMainSync(editor::undo);snapshot();
            Log.i("AcePerformance","keyboardTapHoldMs=200 font="+size+" visible=true typed=true");
        }
    }
    private void waitForKeyboard(boolean visible) throws Exception {
        boolean[] actual={false};long deadline=SystemClock.uptimeMillis()+3000;
        do{
            instrumentation.runOnMainSync(()->{
                WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
                actual[0]=insets!=null && insets.isVisible(WindowInsets.Type.ime());
            });
            if(actual[0]==visible)return;SystemClock.sleep(30);
        }while(SystemClock.uptimeMillis()<deadline);
        assertEquals("Actual Android IME visibility",visible,actual[0]);
    }
    private void typeAndWaitForText(String value,String expected) throws Exception {
        Log.i("AceKeyboardProbe",eval("JSON.stringify({beforeInput:true,font:editor.getFontSize(),"
                +"range:editor.getSelectionRange(),start:editor.textInput.getElement().selectionStart,"
                +"end:editor.textInput.getElement().selectionEnd,textarea:editor.textInput.getElement().value})"));
        boolean[] committed={false};
        instrumentation.runOnMainSync(()->{
            android.view.inputmethod.EditorInfo info=new android.view.inputmethod.EditorInfo();
            android.view.inputmethod.InputConnection connection=webView.onCreateInputConnection(info);
            assertNotNull("The visible WebView must expose its native IME input connection",connection);
            // A real IME can mark the word under the caret as composing. Begin
            // this independent committed test input outside that composition.
            connection.finishComposingText();
            committed[0]=connection.commitText(value,1);
        });
        assertTrue("The native input connection must accept keyboard text",committed[0]);
        long deadline=SystemClock.uptimeMillis()+3000;
        while(!expected.equals(editor.getText()) && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
        snapshot();
        if(!expected.equals(editor.getText()))Log.i("AceKeyboardProbe",eval("JSON.stringify({focused:editor.isFocused(),"
                +"active:document.activeElement&&document.activeElement.className,textarea:editor.textInput.getElement().value,"
                +"source:editor.getValue(),cursor:editor.getCursorPosition()})"));
        assertEquals("Native keyboard input must reach the selected document",expected,editor.getText());
    }
    private void finishAndWaitForDestroy() {
        ProCodeEditorActivity previous=activity;
        instrumentation.runOnMainSync(previous::finish);
        boolean[] destroyed={false};long deadline=SystemClock.uptimeMillis()+5000;
        do{
            instrumentation.runOnMainSync(()->destroyed[0]=previous.isDestroyed());
            if(destroyed[0])return;SystemClock.sleep(20);
        }while(SystemClock.uptimeMillis()<deadline);
        assertTrue("Previous editor must finish before writing restoration fixtures",destroyed[0]);
    }
    @Test public void switchingSmallTabsKeepsKeyboardWorkerAndHiddenSnapshotIndependent() throws Exception {
        String main="const item = { mainProperty: 1 };\nitem.";
        instrumentation.runOnMainSync(()->editor.setInitialText(main));snapshot();
        eval("window.completionDone=false;port.languageRequest('textDocument/completion',"
                +"{textDocument:{uri:path,version:version},position:{line:1,character:5}})"
                +".then(r=>{window.completionResult=r;window.completionDone=!!r && r.some(x=>x.label==='mainProperty');});true");
        long deadline=SystemClock.uptimeMillis()+20000;
        while(!eval("window.completionDone").equals("true") && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50);
        assertEquals("Completion result and service state: "+eval("JSON.stringify({language:port.languageStatus(),completion:window.completionResult})"),
                "true",eval("window.completionDone"));eval("window.workerBefore=worker;true");
        verifyTap(0,5,200);waitForKeyboard(true);
        int composingOffset=editor.getSelectionStart();
        String composedMain=main.substring(0,composingOffset)+"draft"+main.substring(composingOffset);
        instrumentation.runOnMainSync(()->{
            android.view.inputmethod.InputConnection connection=webView.onCreateInputConnection(
                    new android.view.inputmethod.EditorInfo());
            assertNotNull(connection);connection.finishComposingText();
            assertTrue("The real IME connection must accept composing text",connection.setComposingText("draft",1));
        });
        long composingDeadline=SystemClock.uptimeMillis()+3000;
        while(!composedMain.equals(editor.getText()) && SystemClock.uptimeMillis()<composingDeadline)SystemClock.sleep(20);
        snapshot();assertEquals(composedMain,editor.getText());
        AceCodeEditor first=editor;WebView shared=webView;
        File helper=new File(file.getParentFile(),"keyboard-helper.js");String helperText="const helper = 1;\n";
        FileUtils.writeStringToFile(helper,helperText,"UTF-8");
        instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},helper));
        do{instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
            if(editor!=first)break;SystemClock.sleep(20);
        }while(SystemClock.uptimeMillis()<deadline);
        assertNotSame(first,editor);snapshot();
        Field view=AceCodeEditor.class.getDeclaredField("webView");view.setAccessible(true);webView=(WebView)view.get(editor);
        assertSame(shared,webView);waitForKeyboard(true);assertEquals("true",eval("editor.isFocused()"));
        JSONObject inputContext=new JSONObject(eval("(()=>{const t=editor.textInput.getElement();"
                +"return {text:t.value,start:t.selectionStart,end:t.selectionEnd};})()"));
        assertTrue("The keyboard surrounding text must belong to the selected file",
                inputContext.getString("text").contains("const helper = 1;"));
        assertFalse("The keyboard must not retain another tab's word suggestions",
                inputContext.getString("text").contains("mainProperty"));
        assertEquals(0,inputContext.getInt("start"));assertEquals(0,inputContext.getInt("end"));
        CountDownLatch hidden=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->first.withSnapshot(hidden::countDown));
        assertTrue(hidden.await(15,TimeUnit.SECONDS));
        assertTrue(eval("port.languageStatus().path").contains("keyboard-helper.js"));
        waitForKeyboard(true);int helperOffset=editor.getSelectionStart();
        typeAndWaitForText("k",helperText.substring(0,helperOffset)+"k"+helperText.substring(helperOffset));
        instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},file));
        editor=first;snapshot();webView=(WebView)view.get(editor);waitForKeyboard(true);
        assertEquals("The compiler worker must survive same-workspace tab switches","true",eval("worker===workerBefore"));
        assertEquals("Finishing an old tab's composition must retain its text",composedMain,editor.getText());
        int offset=editor.getSelectionStart();
        typeAndWaitForText("m",composedMain.substring(0,offset)+"m"+composedMain.substring(offset));
        Log.i("AcePerformance","cachedSmallTabs keyboardRetained=true workerReused=true hiddenSnapshotIndependent=true imeContextCurrent=true composingTextRetained=true");
    }
    @Test public void cachedLargeTabsSwitchWithoutReloadingTheEditorPage() throws Exception {
        StringBuilder source=new StringBuilder();
        for(int i=0;source.length()<1024*1024;i++)source.append("const value").append(i).append(" = 'cached document';\n");
        String text=source.toString();FileUtils.writeStringToFile(file,text,"UTF-8");
        instrumentation.runOnMainSync(()->editor.setInitialText(text));snapshot();WebView shared=webView;
        File[] files={file,new File(file.getParentFile(),"cached-b.js"),new File(file.getParentFile(),"cached-c.js")};
        Field view=AceCodeEditor.class.getDeclaredField("webView");view.setAccessible(true);
        Field ready=AceCodeEditor.class.getDeclaredField("ready");ready.setAccessible(true);
        for(int i=1;i<files.length;i++){
            FileUtils.writeStringToFile(files[i],text,"UTF-8");File next=files[i];
            instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},next));
            AceCodeEditor previous=editor;long deadline=SystemClock.uptimeMillis()+10000;
            do{instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
                if(editor!=previous)break;SystemClock.sleep(20);
            }while(SystemClock.uptimeMillis()<deadline);
            snapshot();webView=(WebView)view.get(editor);
        }
        long max=0,total=0;long[] durations=new long[30];boolean same=true;
        for(int i=0;i<30;i++){
            File next=files[i%files.length];long started=SystemClock.uptimeMillis();
            instrumentation.runOnMainSync(()->call("openFile",new Class<?>[]{File.class},next));
            instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));
            boolean[] loaded={false};long deadline=SystemClock.uptimeMillis()+10000;
            do{instrumentation.runOnMainSync(()->{try{loaded[0]=ready.getBoolean(editor);}catch(Exception e){throw new AssertionError(e);}});
                if(loaded[0])break;SystemClock.sleep(5);
            }while(SystemClock.uptimeMillis()<deadline);
            assertTrue(loaded[0]);webView=(WebView)view.get(editor);
            JSONObject visible=new JSONObject(eval("({path:port.languageStatus().path,first:editor.getValue().slice(0,20)})"));
            assertTrue("The visible document must match the selected tab",visible.getString("path").endsWith(next.getName()));
            assertEquals(text.substring(0,20),visible.getString("first"));
            long elapsed=SystemClock.uptimeMillis()-started;durations[i]=elapsed;max=Math.max(max,elapsed);total+=elapsed;
            same&=webView==shared;
            assertEquals(text,editor.getText());
        }
        java.util.Arrays.sort(durations);long p95=durations[28],average=total/30;
        Log.i("AcePerformance","cachedLargeSwitches=30 averageMs="+average+" p95Ms="+p95+" maxMs="+max+" sameWebView="+same);
        assertTrue("Switching cached files must retain the attached WebView and input connection",same);
        // This measures native callbacks and WebView scheduling as well as switching.
        // The released baseline occasionally reaches 180-220 ms on the Mi 8;
        // guard sustained latency and retain a separate bound on a single pause.
        assertTrue("Cached 1MiB average switching latency: "+average,average<180);
        assertTrue("Cached 1MiB p95 switching latency: "+p95,p95<180);
        assertTrue("Cached 1MiB switching must avoid half-second pauses: "+max,max<500);
    }
    @Test public void reopeningRestoresCursorAndScrollInTheSharedAttachedView() throws Exception {
        instrumentation.runOnMainSync(()->editor.jumpTo(40,6));snapshot();
        int cursor=editor.getSelectionStart();int scroll=editor.getViewportScrollY();
        assertTrue(cursor>0);assertTrue(scroll>0);
        finishAndWaitForDestroy();
        activity=(ProCodeEditorActivity)instrumentation.startActivitySync(
                ProCodeEditorActivity.intent(instrumentation.getTargetContext(),file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        editor=null;long deadline=SystemClock.uptimeMillis()+15000;
        while(editor==null && SystemClock.uptimeMillis()<deadline){
            instrumentation.runOnMainSync(()->editor=(AceCodeEditor)call("activeEditor",new Class<?>[0]));SystemClock.sleep(20);
        }
        assertNotNull(editor);snapshot();
        Field view=AceCodeEditor.class.getDeclaredField("webView");view.setAccessible(true);webView=(WebView)view.get(editor);
        assertEquals(cursor,editor.getSelectionStart());
        JSONObject position=new JSONObject(eval("editor.getCursorPosition()"));
        assertEquals(40,position.getInt("row"));assertEquals(6,position.getInt("column"));
        assertEquals(scroll,editor.getViewportScrollY());
    }
}
