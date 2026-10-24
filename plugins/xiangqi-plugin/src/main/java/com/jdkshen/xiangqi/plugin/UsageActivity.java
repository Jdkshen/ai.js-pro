package com.jdkshen.xiangqi.plugin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 插件内置的使用教程页：安装 APK 后从桌面图标打开即可查看。
 *
 * <p>本页的接口示例与插件真实 API 一致（2026-10-24 修正：旧版误写 unwrap()/engineStart()，
 * 实际是 $plugins.load() 直接拿到对象，引擎在 xq.engine 下）。
 */
public final class UsageActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("象棋引擎插件 · 使用教程");
        setContentView(buildContent());
    }

    private View buildContent() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * dp);
        int gap = Math.round(8 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("象棋引擎插件");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(33, 33, 33));
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("包名：com.jdkshen.xiangqi.plugin    版本：" + versionText());
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        version.setTextColor(Color.rgb(120, 120, 120));
        version.setPadding(0, Math.round(6 * dp), 0, Math.round(10 * dp));
        root.addView(version);

        Button copy = new Button(this);
        copy.setText("复制下面「完整示例」代码");
        copy.setAllCaps(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("xiangqi-plugin-example", EXAMPLE_CODE));
                Toast.makeText(UsageActivity.this, "示例代码已复制，粘贴到 AutoJS 新脚本即可运行", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.bottomMargin = gap;
        root.addView(copy, copyLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView body = new TextView(this);
        body.setText(TUTORIAL);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setTextColor(Color.rgb(40, 40, 40));
        body.setLineSpacing(Math.round(5 * dp), 1.0f);
        body.setTextIsSelectable(true);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setPadding(0, 0, 0, Math.round(12 * dp));
        scroll.addView(body);
        root.addView(scroll);

        TextView footer = new TextView(this);
        footer.setText("说明：插件负责引擎/识别/工程内容；自动走棋流程在工程脚本里运行（本机无需额外下载模型）。");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(Color.rgb(140, 140, 140));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Math.round(8 * dp), 0, 0);
        root.addView(footer);

        return root;
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception e) {
            return "1.1.3 (6)";
        }
    }

    private static final String EXAMPLE_CODE =
            "\"quickjs\";\n" +
            "// 插件自带引擎/识别，无需安装其它东西；本示例可整段复制运行\n" +
            "var xq = $plugins.load(\"com.jdkshen.xiangqi.plugin\");\n" +
            "log(\"插件版本 \" + xq.version + \"，引擎可用 \" + xq.engine.available());\n" +
            "var st = xq.engine.start({ threads: 2, hashMb: 256, contempt: 40 });\n" +
            "log(\"start \" + JSON.stringify(st));\n" +
            "var fen = \"rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1\";\n" +
            "var r = xq.engine.analyze(fen, 1000, 8000, 1, 0);\n" +
            "log(\"bestmove \" + r.move + \" depth \" + r.depth + \" scoreCp \" + r.scoreCp);\n" +
            "xq.engine.quit();\n";

    private static final String TUTORIAL =
            "一、这个插件是什么\n" +
            "把象棋自动走棋需要的「引擎 / 识别 / 工程内容」封装成可独立安装的 Auto.js 插件：" +
            "自带 Pikafish 原生引擎与 NNUE 权重，宿主脚本 $plugins.load(包名) 即可使用。\n\n" +

            "二、加载方式（QuickJS / Node 都支持）\n" +
            "var xq = $plugins.load(\"com.jdkshen.xiangqi.plugin\");\n" +
            "加载结果就是插件对象，可直接用：\n" +
            "· xq.version                  插件版本字符串\n" +
            "· xq.capabilities             能力清单（对象：engineAvailable / nnue / uci / scriptDir…）\n" +
            "· xq.paths                    插件内脚本目录（工程内容都在里面）\n" +
            "· xq.engine / xq.board / xq.rules / xq.load / xq.installProject / xq.usePluginEngine\n\n" +

            "注意：不需要（也没有）unwrap()；旧教程里的 engineStart/engineAnalyze 也是错的，" +
            "正确入口是 xq.engine.*。\n\n" +

            "三、引擎（与宿主 $pikafish 同形状）\n" +
            "xq.engine.available()      // 引擎是否可用\n" +
            "xq.engine.uciInfo()        // protocol/binary/variant/evalFile\n" +
            "xq.engine.start({ threads: 2, hashMb: 256, contempt: 40 })\n" +
            "xq.engine.analyze(fen, movetimeMs, timeoutMs, multiPv, depth)\n" +
            "xq.engine.analyzePosition(\"position fen ... moves ...\", movetimeMs, timeoutMs, multiPv, depth)\n" +
            "xq.engine.isRunning() / stop() / quit()\n" +
            "返回：{ok, move, candidates:[{multipv,move,depth,scoreCp,…}], depth, scoreCp, …}\n\n" +

            "四、识别（懒加载，首次调用才装配视觉链路）\n" +
            "var image = xq.board.capture(\"full\", 2000);\n" +
            "var reading = xq.board.read(image);\n" +
            "xq.board.releaseFrame(image);\n\n" +

            "五、工程内容（插件 assets/scripts 里是整套工程）\n" +
            "· xq.paths.scriptDir + \"/src/...\"：直接 require 工程模块，如 xq.load(\"src/engine/uci.js\")\n" +
            "· xq.installProject(目标目录)：把整套工程复制到指定目录（默认 <脚本目录>/象棋自动走棋）\n" +
            "· xq.usePluginEngine()：让工程自己的算棋链路改用插件引擎（返回适配器）\n" +
            "· xq.rules：棋规/FEN/记谱（纯 JS；Rhino 宿主上若加载失败会给出 rulesError）\n\n" +

            "六、完整示例（点上方按钮可复制，已实测可运行）\n" +
            "\"quickjs\";\n" +
            "var xq = $plugins.load(\"com.jdkshen.xiangqi.plugin\");\n" +
            "log(\"插件版本 \" + xq.version + \"，引擎可用 \" + xq.engine.available());\n" +
            "var st = xq.engine.start({ threads: 2, hashMb: 256, contempt: 40 });\n" +
            "log(\"start \" + JSON.stringify(st));\n" +
            "var fen = \"rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1\";\n" +
            "var r = xq.engine.analyze(fen, 1000, 8000, 1, 0);\n" +
            "log(\"bestmove \" + r.move + \" depth \" + r.depth + \" scoreCp \" + r.scoreCp);\n" +
            "xq.engine.quit();\n\n" +

            "七、常见问题\n" +
            "· $plugins.load 返回 null：插件没安装/被禁用，或宿主不支持 Auto.js 插件 SDK；\n" +
            "· engine.available() = false：设备不是 arm64-v8a，或插件 APK 原生库缺失；\n" +
            "· 找不到 NNUE：确认 /sdcard/xiangqi-p4/pikafish.nnue 存在，或用 evalFile 指定；\n" +
            "· rules = null：多见于 Rhino 宿主（工程库是 QuickJS 代码），引擎/识别不受影响；\n" +
            "· 老教程的 xq.engineStart()/xq.unwrap() 已废弃——本页示例才是当前真实接口。\n\n" +

            "八、版本\n" +
            "1.1.3：修正使用教程里的接口示例（旧示例误用了 unwrap/engineStart，导致照抄无法运行），" +
            "新增「复制示例代码」按钮；引擎/识别能力与 1.1.2 相同。";

    static final String USAGE_PAGE_REVISION = "usage-page-r2";
}
