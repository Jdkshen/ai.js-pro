package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.annotation.Nullable;

import com.stardust.autojs.core.graphics.ScriptCanvasView;
import com.stardust.autojs.core.ui.inflater.CompiledProjectResources;
import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.ProjectResourceBundle;
import com.stardust.autojs.core.ui.inflater.ProjectResources;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.inflaters.CanvasViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.JsGridViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.JsImageViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.JsListViewInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.JsWebViewInflater;
import com.stardust.autojs.core.ui.widget.JsGridView;
import com.stardust.autojs.core.ui.widget.JsImageView;
import com.stardust.autojs.core.ui.widget.JsListView;
import com.stardust.autojs.core.ui.widget.JsWebView;
import com.stardust.autojs.rhino.ProxyObject;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.autojs.runtime.ScriptRuntime;

import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by Stardust on 2017/5/14.
 */

public class UI extends ProxyObject {

    /**
     * 工程模式资源表的懒加载入口，名字与 {@code modules/__ui__.js} 里的 {@code ui.__androidResources__} 对应。
     *
     * <p>刻意做成「第一次被脚本读到时才扫 {@code res/}」：所有 UI 脚本都会碰 {@code ui}，
     * 但只有真要用 Android 资源的工程才应该付这份 IO。
     */
    public static final String PROP_ANDROID_RESOURCES = "__androidResources__";

    /** 编译好的工程资源包位置，与 {@code tools/build-project-res.ps1} 的默认输出一致。 */
    private static final String COMPILED_RES_DIR = "build";
    private static final String COMPILED_RES_NAME = "project-res.apk";

    private Context mContext;
    private Map<String, Object> mProperties = new ConcurrentHashMap<>();
    private DynamicLayoutInflater mDynamicLayoutInflater;
    private ScriptRuntime mRuntime;
    private ResourceParser mResourceParser;
    private boolean mAndroidResourcesLoaded;

    public UI(Context context, ScriptRuntime runtime) {
        mContext = context;
        mRuntime = runtime;
        mResourceParser = new ResourceParser(new Drawables());
        mDynamicLayoutInflater = new DynamicLayoutInflater(mResourceParser);
        mDynamicLayoutInflater.setContext(context);
        mDynamicLayoutInflater.registerViewAttrSetter(JsImageView.class.getName(),
                new JsImageViewInflater(mResourceParser));
        mDynamicLayoutInflater.registerViewAttrSetter(JsListView.class.getName(),
                new JsListViewInflater(mResourceParser, runtime));
        mDynamicLayoutInflater.registerViewAttrSetter(JsGridView.class.getName(),
                new JsGridViewInflater(mResourceParser, runtime));
        mDynamicLayoutInflater.registerViewAttrSetter(ScriptCanvasView.class.getName(),
                new CanvasViewInflater(mResourceParser, runtime));
        // <webview> 需要脚本运行时才能提供 $ui.web.events / $ui.web.jsBridge（Pro 兼容层）。
        mDynamicLayoutInflater.registerViewAttrSetter(JsWebView.class.getName(),
                new JsWebViewInflater<JsWebView>(mResourceParser, runtime));
        mProperties.put("layoutInflater", this.mDynamicLayoutInflater);
    }

    public DynamicLayoutInflater getDynamicLayoutInflater() {
        return mDynamicLayoutInflater;
    }

    public ResourceParser getResourceParser() {
        return mResourceParser;
    }

    public Object getBindingContext() {
        return mProperties.get("bindingContext");
    }

    public void setBindingContext(Object context) {
        if (context == null)
            mProperties.remove("bindingContext");
        else
            mProperties.put("bindingContext", context);
    }


    public DynamicLayoutInflater getLayoutInflater() {
        return mDynamicLayoutInflater;
    }

    @Override
    public String getClassName() {
        return UI.class.getSimpleName();
    }


    @Override
    public Object get(String name, Scriptable start) {
        if (PROP_ANDROID_RESOURCES.equals(name) && !mAndroidResourcesLoaded) {
            mAndroidResourcesLoaded = true;
            AndroidResourcesInfo info = loadAndroidResources();
            mProperties.put(PROP_ANDROID_RESOURCES, info);
            mResourceParser.setProjectResources(info.resources);
        }
        Object value = mProperties.get(name);
        if (value != null)
            return value;
        return super.get(name, start);
    }

    /**
     * 从脚本的工作目录找 {@code project.json}，再扫它声明的资源目录。
     *
     * <p>脚本的工作目录就是工程根（编辑器运行、外部 Intent 启动都会把脚本所在目录设成 cwd）。
     * 只有声明了 {@code androidResources} 的工程才会返回资源表；其余情况 {@code resources} 为 null，
     * 真正的原因放在 {@code error} 里，让 {@code ui.useAndroidResources()} 能报出可读的错误。
     */
    private AndroidResourcesInfo loadAndroidResources() {
        String cwd;
        try {
            cwd = mRuntime.files.cwd();
        } catch (Exception e) {
            return AndroidResourcesInfo.notProject("取不到脚本工作目录：" + e);
        }
        if (cwd == null || cwd.isEmpty()) {
            return AndroidResourcesInfo.notProject("脚本没有工作目录");
        }
        File dir = new File(cwd);
        ProjectConfig config = ProjectConfig.fromProjectDir(dir.getPath());
        if (config == null) {
            return AndroidResourcesInfo.notProject("脚本目录下没有有效的 project.json：" + cwd);
        }
        ProjectConfig.AndroidResources androidResources = config.getAndroidResources();
        if (androidResources == null) {
            return AndroidResourcesInfo.notProject("project.json 里没有 androidResources 字段");
        }
        // 与 Node UI（:node24 宿主桥的 NodeUiHost）共用同一条资源加载链路：
        // ProjectResourceBundle 负责 project.json/androidResources、合成 id 表、project-res.apk 真 id、
        // .codecache/*.dex 动态类；Rhino 的 ui.R / inflater / activity 垫片都从这里拿数据。
        ProjectResourceBundle bundle = ProjectResourceBundle.load(mContext, dir);
        if (bundle.getError() != null) {
            return AndroidResourcesInfo.notProject(bundle.getError());
        }
        return new AndroidResourcesInfo(bundle.getResources(), null);
    }

    /** {@code ui.__androidResources__} 的形状：{@code resources} 为 null 时看 {@code error}。 */
    public static class AndroidResourcesInfo {

        public final ProjectResources resources;
        public final String error;

        AndroidResourcesInfo(ProjectResources resources, String error) {
            this.resources = resources;
            this.error = error;
        }

        static AndroidResourcesInfo notProject(String error) {
            return new AndroidResourcesInfo(null, error);
        }
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if (mProperties.containsKey(name)) {
            if (value == null) {
                mProperties.remove(name);
            } else {
                mProperties.put(name, value);
            }
        } else {
            super.put(name, start, value);
        }
    }

    public void recycle(){
        mDynamicLayoutInflater.setContext(null);
    }

    private class Drawables extends com.stardust.autojs.core.ui.inflater.util.Drawables {

        @Override
        public Drawable decodeImage(String path) {
            return super.decodeImage(mRuntime.files.path(path));
        }
    }


}
