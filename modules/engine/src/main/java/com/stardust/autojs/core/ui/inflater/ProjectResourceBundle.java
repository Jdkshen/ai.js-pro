package com.stardust.autojs.core.ui.inflater;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.ui.inflater.util.Drawables;
import com.stardust.autojs.project.ProjectConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 工程模式（{@code project.json} 的 {@code androidResources}）资源的统一加载入口。
 *
 * <p>Rhino 与 Node UI 两条路都必须走同一个入口，避免「引擎自己能跑、Node 宿主桥却要重写一遍」：
 * <ul>
 *   <li>读 {@code project.json} 的 {@code androidResources.resDir/manifest}；</li>
 *   <li>{@link ProjectResources#load} 扫 {@code res/} 生成合成 id 表（layout/color/style/menu/id…）；</li>
 *   <li>如果工程下有 PC 编好的 {@code build/project-res.apk}，用 {@link CompiledProjectResources}
 *       挂上真 resource id（Activity 的 Resources 合并后 {@code AnimationUtils}/theme 等原生可用）；</li>
 *   <li>{@link ProjectCodeCache} 加载 {@code .codecache/*.dex}（工程自定义类的动态 dex）；</li>
 *   <li>统计 {@code build/res_generated/*.flat}：设备端没有 aapt2，无法 link 这些 flat，只做诊断上报。</li>
 * </ul>
 *
 * <p><b>Node UI 调用方式</b>（:node24 进程的宿主桥/NodeUiHost，Java 侧）：
 * <pre>
 * ProjectResourceBundle bundle = ProjectResourceBundle.load(context, new File(projectDir));
 * String json = bundle.describeJson();          // 给 Node 侧：R 表/布局 id/布局文件/诊断
 * DynamicLayoutInflater inflater = bundle.newLayoutInflater(context);
 * View view = inflater.inflate(new String(Files.readAllBytes(Paths.get(bundle.layoutFile("activity_main")))));
 * </pre>
 * {@code describeJson()} 里 {@code ok=false} 时看 {@code error}；{@code layouts} 是
 * {@code {activity_main: {id: 2113929216, file: "/sdcard/.../res/layout/activity_main.xml"}}}。
 */
public final class ProjectResourceBundle {

    private static final String LOG_TAG = "ProjectResourceBundle";
    private static final String BUILD_DIR = "build";
    private static final String COMPILED_RES_NAME = "project-res.apk";
    private static final String FLAT_DIR = "res_generated";

    private final File mProjectDir;
    private final String mResDir;
    private final String mManifest;
    private final ProjectResources mResources;
    private final CompiledProjectResources mCompiled;
    private final ProjectCodeCache mCodeCache;
    private final int mFlatFiles;
    private final boolean mProjectResApk;
    private final String mError;

    private ProjectResourceBundle(File projectDir, String resDir, String manifest,
                                  ProjectResources resources, CompiledProjectResources compiled,
                                  ProjectCodeCache codeCache, int flatFiles, boolean projectResApk,
                                  String error) {
        mProjectDir = projectDir;
        mResDir = resDir;
        mManifest = manifest;
        mResources = resources;
        mCompiled = compiled;
        mCodeCache = codeCache;
        mFlatFiles = flatFiles;
        mProjectResApk = projectResApk;
        mError = error;
    }

    /**
     * 加载工程资源。{@code context} 可以为 null（纯诊断/单测），此时不挂 project-res.apk、不加载动态 dex。
     */
    public static ProjectResourceBundle load(@Nullable Context context, File projectDir) {
        if (projectDir == null) {
            return error(null, "工程目录为空");
        }
        ProjectConfig config;
        try {
            config = ProjectConfig.fromProjectDir(projectDir.getPath());
        } catch (Throwable error) {
            return error(projectDir, "读 project.json 失败：" + error);
        }
        if (config == null) {
            return error(projectDir, "project.json 不存在或格式错误：" + projectDir);
        }
        ProjectConfig.AndroidResources androidResources = config.getAndroidResources();
        if (androidResources == null) {
            return error(projectDir, "project.json 里没有 androidResources 字段：" + projectDir);
        }
        ProjectResources resources = ProjectResources.load(projectDir, androidResources.getResDir());
        if (resources == null) {
            return error(projectDir, "找不到资源目录：" + new File(projectDir, androidResources.getResDir()));
        }
        CompiledProjectResources compiled = null;
        if (context != null) {
            compiled = CompiledProjectResources.load(context,
                    new File(projectDir, BUILD_DIR + File.separator + COMPILED_RES_NAME));
        }
        resources.setCompiled(compiled);
        ProjectCodeCache codeCache = ProjectCodeCache.load(context, projectDir);
        int flatFiles = countFlatFiles(new File(projectDir, BUILD_DIR + File.separator + FLAT_DIR));
        boolean projectResApk = new File(projectDir, BUILD_DIR + File.separator + COMPILED_RES_NAME).isFile();
        if (flatFiles > 0 && compiled == null) {
            Log.w(LOG_TAG, "工程有 build/res_generated/*.flat（" + flatFiles + " 个）但设备端没有 aapt2 可 link；"
                    + "本次走 res/ 合成 id 路线（布局可 inflate，真 resource id API 不可用）。"
                    + "需要真 id 请在 PC 跑 tools/build-project-res.ps1 生成 build/project-res.apk。");
        }
        return new ProjectResourceBundle(projectDir, androidResources.getResDir(),
                androidResources.getManifest(), resources, compiled, codeCache, flatFiles,
                projectResApk, null);
    }

    /** 纯诊断（不传 Context）：只看 project.json/res/文件系统，不挂资源包、不加载 dex。 */
    public static ProjectResourceBundle load(File projectDir) {
        return load(null, projectDir);
    }

    /** 一行拿到诊断 JSON（写日志用）。 */
    public static String describeJson(File projectDir) {
        return load(projectDir).describeJson();
    }

    private static ProjectResourceBundle error(File projectDir, String error) {
        return new ProjectResourceBundle(projectDir, null, null, null, null, null, 0, false, error);
    }

    private static int countFlatFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        int count = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                count += countFlatFiles(child);
            } else if (child.getName().endsWith(".flat")) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 访问器

    public String getError() {
        return mError;
    }

    public File getProjectDir() {
        return mProjectDir;
    }

    public String getResDir() {
        return mResDir;
    }

    public String getManifest() {
        return mManifest;
    }

    public ProjectResources getResources() {
        return mResources;
    }

    @Nullable
    public CompiledProjectResources getCompiled() {
        return mCompiled;
    }

    public boolean isCompiled() {
        return mCompiled != null;
    }

    @Nullable
    public ProjectCodeCache getCodeCache() {
        return mCodeCache;
    }

    public int getFlatFiles() {
        return mFlatFiles;
    }

    public boolean hasProjectResApk() {
        return mProjectResApk;
    }

    public boolean isEmpty() {
        return mResources == null || mResources.isEmpty();
    }

    public String[] getTypes() {
        return mResources == null ? new String[0] : mResources.types();
    }

    /** {@code ui.R.layout.activity_main} 的值（合成 id；有 project-res.apk 时是真 id）；没有返回 0。 */
    public int layoutId(String name) {
        return mResources == null ? 0 : mResources.idOf(ProjectResources.TYPE_LAYOUT, name);
    }

    /** {@code idOf("color","mainColorPrimary")} / {@code idOf("menu","menu_main")} … 没有返回 0。 */
    public int idOf(String type, String name) {
        return mResources == null ? 0 : mResources.idOf(type, name);
    }

    /** 布局名 → res/layout/x.xml 绝对路径；没有返回 null。 */
    public String layoutFile(String name) {
        return mResources == null ? null : mResources.layoutFile(name);
    }

    /**
     * 布局 id → res 文件路径。Node UI 的 {@code ui.Activity.layoutId} 返回 id，inflate 前需要它；
     * 合成 id 直接反查，真 id（project-res.apk）则按名字扫一遍匹配。
     */
    public String layoutFileById(int id) {
        if (mResources == null) {
            return null;
        }
        String path = mResources.layoutFileById(id);
        if (path != null) {
            return path;
        }
        for (String name : mResources.names(ProjectResources.TYPE_LAYOUT)) {
            if (mResources.idOf(ProjectResources.TYPE_LAYOUT, name) == id) {
                return mResources.layoutFile(name);
            }
        }
        return null;
    }

    /** 布局 id → 布局名（诊断/日志用）；找不到返回 null。 */
    public String layoutNameById(int id) {
        if (mResources == null) {
            return null;
        }
        String name = mResources.layoutNameOfId(id);
        if (name != null) {
            return name;
        }
        for (String candidate : mResources.names(ProjectResources.TYPE_LAYOUT)) {
            if (mResources.idOf(ProjectResources.TYPE_LAYOUT, candidate) == id) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 给当前进程/Context 建一个已接好工程资源的 inflater（含动态 dex ClassLoader）。
     * Rhino 路径用的是 {@code runtime.ui.layoutInflater}（带脚本 widget 支持）；Node 宿主桥用这个即可。
     */
    public DynamicLayoutInflater newLayoutInflater(Context context) {
        Drawables drawables = new Drawables();
        ResourceParser parser = new ResourceParser(drawables);
        parser.setProjectResources(mResources);
        DynamicLayoutInflater inflater = new DynamicLayoutInflater(parser);
        inflater.setContext(context);
        // dev-988：必须显式串好 ClassLoader 链，不能依赖「宿主类只在 caller ClassLoader 里」的假设。
        // codecache loader 的 parent 已是 context.getClassLoader()；没有 codecache 时直接给宿主 loader。
        ClassLoader hostLoader = context.getClassLoader();
        inflater.setClassLoader(mCodeCache != null && mCodeCache.getClassLoader() != null
                ? mCodeCache.getClassLoader() : hostLoader);
        return inflater;
    }

    // ------------------------------------------------------------------ JSON（Node 宿主桥用）

    /** {@code ui.R} 的形状：{@code {"layout":{"activity_main":<id>}, "color":{...}, "id":{...}}}。 */
    public String getRJson() {
        try {
            return buildR().toString();
        } catch (Throwable error) {
            Log.w(LOG_TAG, "build R json failed", error);
            return "{}";
        }
    }

    /**
     * 完整诊断 JSON（Node 宿主桥可以整包透传给 JS，或写日志定位问题）：
     * {@code {ok,error,projectDir,resDir,manifest,compiled,projectResApk,flatFiles,codeCacheDex,
     * types,layouts,ids,r}}。
     */
    public String describeJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", mError == null);
            if (mError != null) {
                json.put("error", mError);
            }
            json.put("projectDir", mProjectDir == null ? JSONObject.NULL : mProjectDir.getAbsolutePath());
            json.put("resDir", mResDir == null ? JSONObject.NULL : mResDir);
            json.put("manifest", mManifest == null ? JSONObject.NULL : mManifest);
            json.put("compiled", isCompiled());
            json.put("projectResApk", mProjectResApk);
            json.put("flatFiles", mFlatFiles);
            JSONArray dex = new JSONArray();
            for (File file : ProjectCodeCache.listDexFiles(mProjectDir)) {
                dex.put(file.getName());
            }
            json.put("codeCacheDex", dex);
            json.put("codeCacheLoaded", mCodeCache != null);
            if (mResources == null) {
                json.put("types", new JSONArray());
                json.put("layouts", new JSONObject());
                json.put("ids", new JSONArray());
                json.put("r", new JSONObject());
                return json.toString();
            }
            JSONArray types = new JSONArray();
            for (String type : mResources.types()) {
                types.put(type);
            }
            json.put("types", types);
            JSONObject layouts = new JSONObject();
            for (String name : mResources.names(ProjectResources.TYPE_LAYOUT)) {
                JSONObject layout = new JSONObject();
                layout.put("id", mResources.idOf(ProjectResources.TYPE_LAYOUT, name));
                layout.put("file", mResources.layoutFile(name));
                layouts.put(name, layout);
            }
            json.put("layouts", layouts);
            JSONArray ids = new JSONArray();
            for (String id : mResources.ids()) {
                ids.put(id);
            }
            json.put("ids", ids);
            json.put("r", buildR());
            return json.toString();
        } catch (Throwable error) {
            Log.w(LOG_TAG, "describe json failed", error);
            return "{\"ok\":false,\"error\":\"describeJson failed: " + error + "\"}";
        }
    }

    private JSONObject buildR() throws Exception {
        JSONObject r = new JSONObject();
        if (mResources == null) {
            return r;
        }
        for (String type : mResources.types()) {
            JSONObject table = new JSONObject();
            for (String name : mResources.names(type)) {
                table.put(name, mResources.idOf(type, name));
            }
            r.put(type, table);
        }
        return r;
    }

    @Override
    public String toString() {
        if (mError != null) {
            return "ProjectResourceBundle{error=" + mError + "}";
        }
        return "ProjectResourceBundle{" + mProjectDir + ", resDir=" + mResDir
                + ", compiled=" + isCompiled() + ", flat=" + mFlatFiles + ", " + mResources + "}";
    }
}
