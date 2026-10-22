// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Node24 执行前的 JS 注入（任务 task-58/59 对齐）：assets {@code node24/prelude.js} + 可选 {@code node24/ui.js}。
 *
 * <p>注入顺序（同一次 Node 源码，前段执行完才跑用户脚本）：
 * <pre>
 *   globalThis.__ajsScriptFile = "...";
 *   globalThis.__ajsProjectDir = "...";
 *   &lt;prelude.js&gt;
 *   &lt;ui.js&gt;            // 存在则拼接（B(task-59) 提供，调用 __ajsRegisterModule('ui', factory)）
 *   &lt;用户脚本&gt;
 * </pre>
 *
 * <p>prelude.js 负责：把 native 桥的 {@code __autojsHost} 包成安全函数、require 包装
 * （{@code __ajsBuiltinModules}：rhino/app）、{@code __ajsRegisterModule}、{@code __autojsPoll()} 事件轮询、
 * {@code $autojs.java/androidContext/keepRunning} 等。缺少 prelude 资产时退化为只执行用户脚本（保持旧行为）。
 */
public final class Node24Prelude {

    private static final String TAG = "Node24Prelude";
    private static final String ASSET_DIR = "node24";
    private static final String PRELUDE_ASSET = ASSET_DIR + "/prelude.js";
    private static final String UI_ASSET = ASSET_DIR + "/ui.js";
    /**
     * task-146：imgui-re 的 Pro 语义模块（独立于 prelude.js，加载即 __ajsRegisterModule 并合并旧对象）。
     * 数组顺序即生产加载顺序；zip/ocr 放在最后，不改变已有模块的注册顺序。
     */
    private static final String[] MODULE_ASSETS = {
            "modules/common-color.js",
            "node24/modules/floating_window.js",
            "node24/modules/accessibility_extra.js",
            "node24/modules/zip.js",
            "node24/modules/ocr.js",
            "node24/modules/media.js",
            "node24/modules/plugins.js",
            "node24/modules/root_automator.js",
            "node24/modules/console.js",
            "modules/common-api.js",
            "modules/common-device.js",
            "modules/common-lang.js",
            "node24/modules/legacy-runtime.js"
    };

    private static volatile String sPreludeCache;

    private Node24Prelude() {
    }

    /** prelude.js 是否存在（缺失时 Service 退化执行，不影响纯 Node 脚本）。 */
    public static boolean available(Context context) {
        try {
            context.getAssets().open(PRELUDE_ASSET).close();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 组装完整 Node 源码；prelude 缺失时返回原脚本。 */
    public static String wrap(Context context, String source, String absoluteFilename) {
        String prelude;
        try {
            prelude = prelude(context);
        } catch (Throwable error) {
            Log.w(TAG, "load prelude failed, run script without Auto.js bridge", error);
            return source;
        }
        String projectDir = projectDir(absoluteFilename);
        StringBuilder builder = new StringBuilder(prelude.length() + source.length() + 512);
        // 冻结接口：prelude/ui.js 通过这两个全局拿到脚本与项目目录。
        builder.append("globalThis.__ajsScriptFile = ").append(JSONObject.quote(
                absoluteFilename == null ? "" : absoluteFilename)).append(";\n");
        builder.append("globalThis.__ajsProjectDir = ").append(JSONObject.quote(projectDir)).append(";\n");
        builder.append(prelude).append("\n;\n");
        String ui = optionalAsset(context, UI_ASSET);
        if (ui != null && !ui.isEmpty()) {
            builder.append(ui).append("\n;\n");
        }
        // task-146：按 modules/REGISTER-PATCH.md 方式 A，在 prelude/ui 之后、用户源码之前逐个加载可选模块。
        for (String moduleAsset : MODULE_ASSETS) {
            String moduleCode = optionalAsset(context, moduleAsset);
            if (moduleCode == null && (moduleAsset.startsWith("modules/common-")
                    || moduleAsset.equals("node24/modules/legacy-runtime.js"))) {
                throw new IllegalStateException("Required engine API asset missing: " + moduleAsset);
            }
            if (moduleCode != null && !moduleCode.isEmpty()) {
                builder.append(moduleCode).append("\n;\n");
            }
        }
        // task-132 P0①：入口 CJS 包装——module/exports/require/__filename/__dirname 与 Node 一致，
        // 支持 module.exports、顶层 return、require.cache/require.resolve（原生 require 已提供）。
        String parent = absoluteFilename == null || absoluteFilename.isEmpty()
                ? "" : new File(absoluteFilename).getParent();
        builder.append(";(function(){var __ajsMainModule={id:'.',filename:")
                .append(JSONObject.quote(absoluteFilename == null ? "" : absoluteFilename))
                .append(",loaded:false,exports:{},children:[],paths:[]};")
                .append("globalThis.module=__ajsMainModule;globalThis.exports=__ajsMainModule.exports;")
                .append("(function(module,exports,require,__filename,__dirname){\n")
                .append(source)
                .append("\n}).call(__ajsMainModule.exports,__ajsMainModule,__ajsMainModule.exports,require,")
                .append(JSONObject.quote(absoluteFilename == null ? "" : absoluteFilename))
                .append(",")
                .append(JSONObject.quote(parent == null ? "" : parent))
                .append(");\n__ajsMainModule.loaded=true;globalThis.__ajsMainModule=__ajsMainModule;})();");
        return builder.toString();
    }

    /**
     * 脚本项目目录：从脚本父目录向上找 project.json（B 的 NodeUiHost 按 projectDir 找工程资源，
     * 入口在 src/ 子目录时也要能定位工程根）；找不到时回退脚本父目录。
     */
    public static String projectDir(String absoluteFilename) {
        if (absoluteFilename == null || absoluteFilename.isEmpty()) {
            return "";
        }
        int slash = absoluteFilename.lastIndexOf('/');
        String directory = slash > 0 ? absoluteFilename.substring(0, slash) : "";
        if (directory.isEmpty()) {
            return "";
        }
        File current = new File(directory);
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (new File(current, "project.json").isFile()) {
                return current.getPath();
            }
            current = current.getParentFile();
        }
        return directory;
    }

    private static String prelude(Context context) throws IOException {
        String cached = sPreludeCache;
        if (cached != null) {
            return cached;
        }
        String value = readAsset(context, PRELUDE_ASSET);
        if (value == null) {
            throw new IOException("缺少 assets/" + PRELUDE_ASSET);
        }
        sPreludeCache = value;
        return value;
    }

    private static String optionalAsset(Context context, String path) {
        try {
            return readAsset(context, path);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String readAsset(Context context, String path) throws IOException {
        try (InputStream input = context.getAssets().open(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int size;
            while ((size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 当前 assets 里的可选模块文件（诊断用：返回 prelude/ui 是否存在）。 */
    public static List<String> describeAssets(Context context) {
        List<String> result = new ArrayList<>();
        if (available(context)) {
            result.add(PRELUDE_ASSET);
        }
        if (optionalAsset(context, UI_ASSET) != null) {
            result.add(UI_ASSET);
        }
        Collections.sort(result);
        return result;
    }
}
