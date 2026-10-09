package com.jdkshen.aijspro.sample;

import android.content.Context;
import android.content.res.Configuration;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.Drawables;
import com.stardust.autojs.core.ui.inflater.util.Ids;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import static org.junit.Assert.*;

/** Uses production XML exported by panel-load-smoke (XQ_LAYOUT_EXPORT), not duplicated layouts. */
public class FloatyLayoutAuditTest {
    @Test public void quickJsLogScrollFollowsAfterTextRelayout() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        android.view.WindowManager wm = (android.view.WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        ScrollView[] scroll = new ScrollView[1];
        TextView[] text = new TextView[1];
        Class<?> bridge = Class.forName("com.stardust.autojs.engine.QuickJsHostBridge");
        java.lang.reflect.Method read = bridge.getDeclaredMethod(
                "readViewAttribute", View.class, String.class, ResourceParser.class);
        java.lang.reflect.Method write = bridge.getDeclaredMethod(
                "writeViewAttribute", View.class, String.class, String.class, ResourceParser.class);
        read.setAccessible(true);
        write.setAccessible(true);
        ResourceParser parser = new ResourceParser(new Drawables());
        try {
            instrumentation.runOnMainSync(() -> {
                scroll[0] = new ScrollView(context);
                text[0] = new TextView(context);
                text[0].setText("first line");
                scroll[0].addView(text[0]);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        500, 120, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                wm.addView(scroll[0], lp);
            });
            instrumentation.waitForIdleSync();
            for (int count : new int[]{30, 80}) {
                StringBuilder content = new StringBuilder();
                for (int n=0; n<count; n++) content.append("complete log event ").append(n).append('\n');
                instrumentation.runOnMainSync(() -> {
                    text[0].setText(content.toString());
                    try { assertEquals(true, write.invoke(null,scroll[0],"scrollToEnd","true",parser)); }
                    catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                });
                instrumentation.waitForIdleSync();
                // Allow a display traversal as well as main-looper callbacks to complete.
                android.os.SystemClock.sleep(80);
                instrumentation.runOnMainSync(() -> {
                    try {
                        assertEquals("false",read.invoke(null,scroll[0],"canScrollDown",parser));
                        assertTrue("newest log line must be visible",scroll[0].getScrollY()>0);
                    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                });
            }
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (scroll[0] != null && scroll[0].isAttachedToWindow()) wm.removeViewImmediate(scroll[0]);
            });
        }
    }

    @Test public void productionWindowsHaveNoClippedText() throws Exception {
        JSONArray fixtures = new JSONArray(new String(Files.readAllBytes(
                Paths.get("/sdcard/Download/floaty-audit.json")), StandardCharsets.UTF_8));
        List<String> errors = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                for (int i = 0; i < fixtures.length(); i++) {
                    JSONObject f = fixtures.getJSONObject(i);
                    for (float fontScale : new float[]{1f, 1.3f, 2f}) {
                        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
                        Configuration config = new Configuration(base.getResources().getConfiguration());
                        config.densityDpi = Math.round(f.getInt("width") * 440f / 1080f);
                        config.fontScale = fontScale;
                        Context context = new android.view.ContextThemeWrapper(
                                base.createConfigurationContext(config), androidx.appcompat.R.style.Theme_AppCompat_Light);
                        DynamicLayoutInflater inflater = new DynamicLayoutInflater(new ResourceParser(new Drawables()));
                        inflater.setContext(context);
                        android.widget.FrameLayout parent = new android.widget.FrameLayout(context);
                        View root = inflater.inflate(f.getString("xml"), parent, false);
                        JSONObject texts = f.getJSONObject("texts");
                        for (Iterator<String> keys = texts.keys(); keys.hasNext();) {
                            String key = keys.next();
                            View view = root.findViewById(Ids.parse(key));
                            if (view instanceof TextView) ((TextView)view).setText(texts.getString(key));
                        }
                        setText(root, "thinkValue", "20.0s ±1s");
                        setText(root, "openingValue", "开局 12");
                        setText(root, "hashBtn", "哈希 512M");
                        setText(root, "contemptBtn", "避和200");
                        setText(root, "winSeekBtn", "求胜开");
                        setText(root, "modeBtn", "自动走棋");
                        setText(root, "compactMode", "自动走棋");
                        setText(root, "compactStatus", "等待应手");
                        setText(root, "logTitle", "运行日志 · 恢复中（重新定位）");
                        setText(root, "logMeta", "回到最新 ↓");
                        setText(root, "status", "几何 v123 偏移(120,120)｜运行中｜99999s｜前台 游戏✓｜页面：未开始｜Pikafish");
                        setText(root, "step", "恢复中（重新定位）｜观察 9999 次｜未知格 0｜本帧 999ms\n轮次 接着刚才的推断继续观察，保留完整原因\n历史 部分｜确认 CONFIRMED（与预期落子后局面一致，完整确认信息尾部）");
                        ViewGroup.LayoutParams lp = root.getLayoutParams();
                        for (int pass=0; pass<3; pass++) {
                            root.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
                            root.layout(0,0,lp.width,lp.height);
                        }
                        audit(root, "fixture="+i+" width="+f.getInt("width")+" font="+fontScale, errors);
                        if (f.getInt("width") == 1080) {
                            android.graphics.Bitmap preview = android.graphics.Bitmap.createBitmap(
                                    lp.width, lp.height, android.graphics.Bitmap.Config.ARGB_8888);
                            try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                                    "/sdcard/Download/floaty-audit-"+i+"-"+fontScale+".png")) {
                                root.draw(new android.graphics.Canvas(preview));
                                preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                            } finally { preview.recycle(); }
                        }
                    }
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private static void setText(View root, String id, String value) {
        View view = root.findViewById(Ids.parse(id));
        if (view instanceof TextView) ((TextView)view).setText(value);
    }

    private static void audit(View view, String label, List<String> errors) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            Layout layout = text.getLayout();
            String at = label+" text="+text.getText();
            if (layout == null) errors.add(at+" missing layout");
            else {
                int h = text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
                if (layout.getHeight()>h+1) errors.add(at+" clipped vertically "+layout.getHeight()+">"+h);
                int w = text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();
                for (int line=0;line<layout.getLineCount();line++) {
                    if (layout.getLineWidth(line)>w+1 || layout.getEllipsisCount(line)>0)
                        errors.add(at+" clipped horizontally "+layout.getLineWidth(line)+">"+w);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                View child=group.getChildAt(i);
                if (!(group instanceof ScrollView) && !(group instanceof HorizontalScrollView)) {
                    if (child.getRight()>group.getWidth()+1 || child.getBottom()>group.getHeight()+1)
                        errors.add(label+" child exceeds "+group.getClass().getSimpleName());
                }
                audit(child,label,errors);
            }
        }
    }
}
