package com.stardust.autojs.core.ui.inflater;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;

import com.stardust.autojs.core.ui.inflater.util.Ids;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 工程模式（{@code project.json} 的 {@code androidResources}）的资源表。
 *
 * <p>Auto.js Pro 会把工程的 {@code res/} 交给 aapt2 编译成真正的 Android 资源，脚本里因此能写
 * {@code ui.R.xxx}，布局里能写 {@code @color/xxx}。本引擎**没有资源编译器**（打包链只是改写模板
 * APK 的二进制清单，见 {@code third-party/ApkBuilder}；设备上也没有可执行的 aapt2），所以这里走另一条路：
 *
 * <ul>
 *     <li>扫 {@code res/} 目录，把资源按「类型 + 名字」登记进表，并分配一个自增的<b>合成 id</b>
 *         （{@code 0x7e******} 段，与 app 的 {@code 0x7f******}、framework 的 {@code 0x01******} 不冲突）；</li>
 *     <li>{@code ui.R} 暴露的就是这些合成 id；{@code id} 类型复用 inflater 的 {@link Ids} 名字表，
 *         所以 {@code ui.R.id.xxx} 与布局里 {@code android:id="@+id/xxx"}</b> 拿到的是同一个值，
 *         {@code ui.xxx} 能直接取到控件（见 {@code modules/__ui__.js} 的 {@code __proxy__} getter）；</li>
 *     <li>inflater 套用属性之前，把 {@code @color/x}、{@code @string/x}、{@code @dimen/x}、
 *         {@code @drawable/x} 就地改写成引擎自己能解析的值（{@code #AARRGGBB} / 字面量 / {@code file:// 路径}）。</li>
 * </ul>
 *
 * <p><b>合成 id 不是真 id</b>：把它交给真正的 framework API（{@code Resources.getColor(id)}、
 * {@code AnimationUtils.loadAnimation(ctx, id)}）依然会抛 {@code NotFoundException} —— 那只有 aapt2 能给。
 * 这类调用点由 {@code modules/__ui__.js} 里的 activity 垫片兜住。
 */
public class ProjectResources {

    private static final String LOG_TAG = "ProjectResources";

    public static final String DEFAULT_RES_DIR = "res";
    public static final String TYPE_LAYOUT = "layout";
    public static final String TYPE_DRAWABLE = "drawable";
    public static final String TYPE_MIPMAP = "mipmap";
    public static final String TYPE_ANIM = "anim";
    public static final String TYPE_MENU = "menu";
    public static final String TYPE_COLOR = "color";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_DIMEN = "dimen";
    public static final String TYPE_STYLE = "style";
    public static final String TYPE_RAW = "raw";
    public static final String TYPE_ID = "id";

    /** 合成 id 的包段：0x7e（app 是 0x7f，framework 是 0x01）。 */
    private static final int ID_BASE = 0x7e000000;

    private static final Pattern ID_REFERENCE = Pattern.compile("@\\+?id/([A-Za-z_][A-Za-z0-9_]*)");

    private static final Map<String, Integer> NAMED_COLORS = new HashMap<>();

    static {
        NAMED_COLORS.put("black", 0xFF000000);
        NAMED_COLORS.put("darkgray", 0xFF444444);
        NAMED_COLORS.put("gray", 0xFF888888);
        NAMED_COLORS.put("lightgray", 0xFFCCCCCC);
        NAMED_COLORS.put("white", 0xFFFFFFFF);
        NAMED_COLORS.put("red", 0xFFFF0000);
        NAMED_COLORS.put("green", 0xFF00FF00);
        NAMED_COLORS.put("blue", 0xFF0000FF);
        NAMED_COLORS.put("yellow", 0xFFFFFF00);
        NAMED_COLORS.put("cyan", 0xFF00FFFF);
        NAMED_COLORS.put("aqua", 0xFF00FFFF);
        NAMED_COLORS.put("magenta", 0xFFFF00FF);
        NAMED_COLORS.put("fuchsia", 0xFFFF00FF);
        NAMED_COLORS.put("lime", 0xFF00FF00);
        NAMED_COLORS.put("maroon", 0xFF800000);
        NAMED_COLORS.put("navy", 0xFF000080);
        NAMED_COLORS.put("olive", 0xFF808000);
        NAMED_COLORS.put("purple", 0xFF800080);
        NAMED_COLORS.put("silver", 0xFFC0C0C0);
        NAMED_COLORS.put("teal", 0xFF008080);
        NAMED_COLORS.put("transparent", 0x00000000);
    }

    private final File mResDir;
    /** {@code "type/name"} → 合成 id。 */
    private final Map<String, Integer> mIds = new LinkedHashMap<>();
    /** 合成 id → {@code "type/name"}（反查，给 activity 垫片用）。 */
    private final Map<Integer, String> mKeys = new HashMap<>();
    /** {@code "type/name"} → 原始值（values 里的文本）。 */
    private final Map<String, Object> mRawValues = new HashMap<>();
    /** {@code "style/name"} → 解析后的 style。 */
    private final Map<String, Style> mStyles = new LinkedHashMap<>();
    /** {@code "type/name"} → 文件。 */
    private final Map<String, File> mFiles = new LinkedHashMap<>();
    /** 颜色名 → 解析后的颜色（延迟求值后缓存；同名只解析一次）。 */
    private final Map<String, Integer> mColors = new HashMap<>();
    private final Set<String> mResolvingColors = new HashSet<>();
    /** 布局里出现过的 {@code @+id/xxx} 名字（顺序 = 遇到顺序，便于诊断）。 */
    private final List<String> mIdNames = new ArrayList<>();
    private final Set<String> mIdNameSet = new HashSet<>();
    private CompiledProjectResources mCompiled;
    private int mNextId = 1;

    private ProjectResources(File resDir) {
        mResDir = resDir;
    }

    static ProjectResources fromCompiled(File projectDir, CompiledProjectResources compiled) {
        ProjectResources resources = new ProjectResources(new File(projectDir, DEFAULT_RES_DIR));
        resources.setCompiled(compiled);
        return resources;
    }

    /**
     * 读工程目录下的资源表。
     *
     * @param projectDir 工程根目录（通常是脚本的工作目录）
     * @param resDirName {@code project.json} 里的 {@code androidResources.resDir}，为空时用 {@code res}
     * @return 资源表；没有资源目录时返回 {@code null}（调用方按「不支持」处理）
     */
    public static ProjectResources load(File projectDir, String resDirName) {
        if (projectDir == null) {
            return null;
        }
        File resDir = new File(projectDir, resDirName == null || resDirName.isEmpty() ? DEFAULT_RES_DIR : resDirName);
        if (!resDir.isDirectory()) {
            return null;
        }
        ProjectResources resources = new ProjectResources(resDir);
        try {
            resources.scan();
        } catch (Exception e) {
            Log.w(LOG_TAG, "扫描工程资源失败：" + resDir, e);
            return null;
        }
        return resources;
    }

    public File getResDir() {
        return mResDir;
    }

    /**
     * 挂上用 PC aapt2 编好的资源包（{@code tools/build-project-res.ps1} 的产物）。
     *
     * <p>挂上之后 {@code ui.R.*} 返回的是**真 resource id**，{@code @color/x} 这类引用也不再改写
     * （交给 framework 自己按名字查），与 Pro 的行为一致；没挂上时走原本的「合成 id」那条路。
     */
    public void setCompiled(CompiledProjectResources compiled) {
        mCompiled = compiled;
    }

    /** 是否已挂载编译好的资源包（= 有真 resource id 可用）。 */
    public boolean isCompiled() {
        return mCompiled != null;
    }

    /** 编译包里的真 id；没挂载或查不到时返回 0。 */
    public int compiledIdOf(String type, String name) {
        return mCompiled == null ? 0 : mCompiled.identifier(type, name);
    }

    public boolean isEmpty() {
        return mIds.isEmpty() && mIdNames.isEmpty();
    }

    // ---------------------------------------------------------------- 扫描

    private void scan() {
        File[] children = mResDir.listFiles();
        if (children == null) {
            return;
        }
        List<File> dirs = new ArrayList<>(Arrays.asList(children));
        // 不带限定符的目录优先：同名资源以默认目录为准（aapt 的选择规则要复杂得多，这里是够用的简化版）。
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Integer.compare(qualifierRank(left.getName()), qualifierRank(right.getName()));
            }
        });
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            String folder = dir.getName();
            if (folder.equals("values") || folder.startsWith("values-")) {
                parseValuesDir(dir);
            } else {
                parseFileDir(folder, dir);
            }
        }
    }

    private static int qualifierRank(String folder) {
        return folder.indexOf('-') < 0 ? 0 : 1;
    }

    private static String typeOfFolder(String folder) {
        int i = folder.indexOf('-');
        return i < 0 ? folder : folder.substring(0, i);
    }

    private void parseFileDir(String folder, File dir) {
        String type = typeOfFolder(folder);
        if (type.isEmpty() || TYPE_ID.equals(type)) {
            // id 不是目录型资源：只从布局/values 里的 @+id 收集。
            return;
        }
        for (File file : listFiles(dir)) {
            String name = stripExtension(file.getName());
            if (name.isEmpty()) {
                continue;
            }
            String key = type + "/" + name;
            if (mIds.containsKey(key)) {
                continue;
            }
            register(key);
            mFiles.put(key, file);
            if (TYPE_LAYOUT.equals(type) || TYPE_MENU.equals(type)) {
                // 布局与菜单 xml 里的 @+id/xxx 也是资源（aapt 同样会收），
                // 收了才能让 ui.R.id.xxx / ui.xxx 与它们对上。
                collectIdsIn(file);
            }
        }
    }

    private void parseValuesDir(File dir) {
        for (File file : listFiles(dir)) {
            if (!file.getName().endsWith(".xml")) {
                continue;
            }
            Document document = parseXml(file);
            if (document == null || document.getDocumentElement() == null) {
                continue;
            }
            NodeList nodes = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                String tag = node.getNodeName();
                String name = attributeOf(node, "name");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if ("style".equals(tag)) {
                    parseStyle(node, name);
                } else if ("item".equals(tag) && TYPE_ID.equals(attributeOf(node, "type"))) {
                    // res/values/ids.xml：<item name="x" type="id"/>
                    collectIdName(name);
                } else {
                    String key = tag + "/" + name;
                    if (!mIds.containsKey(key)) {
                        register(key);
                        mRawValues.put(key, textOf(node));
                    }
                }
            }
        }
    }

    private void parseStyle(Node node, String name) {
        String key = TYPE_STYLE + "/" + name;
        if (mIds.containsKey(key)) {
            return;
        }
        Style style = new Style(name, attributeOf(node, "parent"));
        NodeList items = node.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            if (item.getNodeType() != Node.ELEMENT_NODE || !"item".equals(item.getNodeName())) {
                continue;
            }
            String itemName = attributeOf(item, "name");
            if (itemName != null) {
                style.addItem(itemName, textOf(item));
            }
        }
        register(key);
        mStyles.put(key, style);
    }

    /** 收集 xml 里声明的 id（{@code android:id="@+id/xxx"} / {@code android:id="@id/xxx"}）。 */
    private void collectIdsIn(File xml) {
        String text = readText(xml);
        if (text == null) {
            return;
        }
        Matcher matcher = ID_REFERENCE.matcher(text);
        while (matcher.find()) {
            collectIdName(matcher.group(1));
        }
    }

    private void collectIdName(String name) {
        if (mIdNameSet.add(name)) {
            mIdNames.add(name);
        }
    }

    private void register(String key) {
        int id = ID_BASE | mNextId++;
        mIds.put(key, id);
        mKeys.put(id, key);
    }

    // ---------------------------------------------------------------- 查询（脚本侧 ui.R 用）

    /** 所有已登记的资源类型（如 {@code color}、{@code layout}、{@code id} 等）。 */
    public String[] types() {
        List<String> types = new ArrayList<>();
        for (String key : mIds.keySet()) {
            String type = typeOfKey(key);
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        if (!mIdNames.isEmpty()) {
            types.add(TYPE_ID);
        }
        return types.toArray(new String[0]);
    }

    /** 某个类型下的所有资源名。 */
    public String[] names(String type) {
        if (TYPE_ID.equals(type)) {
            return mIdNames.toArray(new String[0]);
        }
        String prefix = type + "/";
        List<String> names = new ArrayList<>();
        for (String key : mIds.keySet()) {
            if (key.startsWith(prefix)) {
                names.add(key.substring(prefix.length()));
            }
        }
        return names.toArray(new String[0]);
    }

    /**
     * 取资源的 id。
     *
     * <p>{@code id} 类型返回的是 inflater 自己的名字表（{@link Ids}）里的值 —— 它和布局里
     * {@code android:id} 落到控件上的是同一个数，所以 {@code ui.R.id.foo} 能直接与
     * {@code MenuItem.getItemId()} / {@code ui.foo} 对上。
     */
    public int idOf(String type, String name) {
        int compiled = compiledIdOf(type, name);
        if (compiled != 0) {
            return compiled;
        }
        if (TYPE_ID.equals(type)) {
            return Ids.parse(name);
        }
        Integer id = mIds.get(type + "/" + name);
        return id == null ? 0 : id;
    }

    /** 布局里声明过的所有 id 名（给「automatically bind ui.<id>」之类的用法）。 */
    public String[] ids() {
        return mIdNames.toArray(new String[0]);
    }

    /** 合成 id 是否属于本资源表。 */
    public boolean isProjectId(int id) {
        return (id & 0xff000000) == ID_BASE;
    }

    public Integer colorById(int id) {
        String key = mKeys.get(id);
        if (key == null || !key.startsWith(TYPE_COLOR + "/")) {
            return null;
        }
        return colorOf(key.substring(TYPE_COLOR.length() + 1));
    }

    /** 合成 id → 字符串资源；不是字符串资源时返回 {@code null}。 */
    public String stringById(int id) {
        String key = mKeys.get(id);
        if (key == null || !key.startsWith(TYPE_STRING + "/")) {
            return null;
        }
        return stringOf(key.substring(TYPE_STRING.length() + 1));
    }

    /** 合成 id → 尺寸原文（如 {@code "16dp"}）；不是尺寸资源时返回 {@code null}。 */
    public String dimensionById(int id) {
        String key = mKeys.get(id);
        if (key == null || !key.startsWith(TYPE_DIMEN + "/")) {
            return null;
        }
        return dimensionOf(key.substring(TYPE_DIMEN.length() + 1));
    }

    public Style styleById(int id) {
        return mStyles.get(mKeys.get(id));
    }

    public Style styleOf(String name) {
        return mStyles.get(TYPE_STYLE + "/" + name);
    }

    public Integer colorOf(String name) {
        String key = TYPE_COLOR + "/" + name;
        if (!mIds.containsKey(key)) {
            return null;
        }
        Integer cached = mColors.get(name);
        if (cached != null) {
            return cached;
        }
        if (!mResolvingColors.add(name)) {
            // 循环引用（a -> b -> a）：按「解析不出来」处理，别把自己递归死。
            return null;
        }
        try {
            Object raw = mRawValues.get(key);
            Integer color = raw instanceof String ? parseColorValue(((String) raw).trim()) : null;
            if (color != null) {
                mColors.put(name, color);
            }
            return color;
        } finally {
            mResolvingColors.remove(name);
        }
    }

    public String stringOf(String name) {
        Object raw = mRawValues.get(TYPE_STRING + "/" + name);
        return raw instanceof String ? (String) raw : null;
    }

    public String dimensionOf(String name) {
        Object raw = mRawValues.get(TYPE_DIMEN + "/" + name);
        return raw instanceof String ? (String) raw : null;
    }

    public File fileOf(String type, String name) {
        return mFiles.get(type + "/" + name);
    }

    /**
     * {@code ui.layoutFile("main")} 传资源名时的解析。
     *
     * @return {@code res/layout/main.xml} 的绝对路径；不是本表里的名字（估计是路径）时返回 {@code null}
     */
    public String layoutFile(String nameOrPath) {
        if (nameOrPath == null) {
            return null;
        }
        File file = fileOf(TYPE_LAYOUT, stripExtension(nameOrPath));
        return file == null ? null : file.getAbsolutePath();
    }

    /**
     * 合成 id → {@code "type/name"}（诊断/反查用）；未知 id 返回 {@code null}。
     *
     * <p>Node UI（:node24 宿主桥）把 {@code ui.R.layout.activity_main} 的 id 交给引擎后，需要靠它
     * 反查出对应的 res 文件；Rhino 路径走 {@code ui.layoutFile("main")} 按名字查，不需要这一步。
     */
    public String nameOfId(int id) {
        return mKeys.get(id);
    }

    /** id → 对应资源文件（layout/menu/drawable/anim/…）；不是文件型资源或未知 id 时返回 {@code null}。 */
    public File fileById(int id) {
        String key = mKeys.get(id);
        return key == null ? null : mFiles.get(key);
    }

    /** 布局 id → 绝对路径（Node UI {@code ui.Activity.layoutId} → inflate 用）；不是布局时返回 {@code null}。 */
    public String layoutFileById(int id) {
        File file = fileById(id);
        return file == null ? null : file.getAbsolutePath();
    }

    /** 布局 id → 资源名（{@code @layout/activity_main} 的 {@code activity_main}）；不是布局时返回 {@code null}。 */
    public String layoutNameOfId(int id) {
        String key = nameOfId(id);
        if (key == null || !key.startsWith(TYPE_LAYOUT + "/")) {
            return null;
        }
        return key.substring(TYPE_LAYOUT.length() + 1);
    }

    private static String typeOfKey(String key) {
        int i = key.indexOf('/');
        return i < 0 ? key : key.substring(0, i);
    }

    // ---------------------------------------------------------------- 引用解析（inflater 用）

    /**
     * 把 {@code @color/x} 这类<b>工程资源</b>引用改写成引擎自己能解析的字面量。
     *
     * @return 改写后的值；不是工程资源、或该类型暂不支持时返回 {@code null}（调用方保持原值）
     */
    public String resolveReference(String value) {
        if (value == null || value.length() < 3 || value.charAt(0) != '@') {
            return null;
        }
        if (mCompiled != null) {
            // 有真资源表：让原来的解析路径按名字查（它用的 context.getResources() 就是合并后的表），
            // 这样能拿到编译过的 drawable/color（比如 shape 由 framework 自己解析，保真度更高）。
            return null;
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String type = value.substring(1, slash);
        String name = value.substring(slash + 1);
        if (type.startsWith("android:")) {
            // @android:color/white 这类 framework 资源：原来的解析路径已经能处理。
            return null;
        }
        if (TYPE_COLOR.equals(type)) {
            Integer color = colorOf(name);
            return color == null ? null : String.format("#%08X", color);
        }
        if (TYPE_STRING.equals(type)) {
            return stringOf(name);
        }
        if (TYPE_DIMEN.equals(type)) {
            return dimensionOf(name);
        }
        if (TYPE_DRAWABLE.equals(type) || TYPE_MIPMAP.equals(type)) {
            File file = fileOf(type, name);
            return file == null ? null : "file://" + file.getAbsolutePath();
        }
        // layout / anim / menu / style：各自的调用点自己走资源表（见 ui.layoutFile、菜单解析等），
        // 这里不返回，避免把名字改成调用方不认识的形状。
        return null;
    }

    private Integer parseColorValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.charAt(0) == '@') {
            String resolved = resolveReference(raw);
            return resolved == null ? null : parseColorLiteral(resolved);
        }
        return parseColorLiteral(raw);
    }

    /**
     * 解析颜色字面量。
     *
     * <p>故意自己实现 {@code #RGB/#ARGB/#RRGGBB/#AARRGGBB} 与常用色名，而不是直接调
     * {@code android.graphics.Color.parseColor} —— 这样资源表在纯 JVM 单测里也能跑。
     */
    static Integer parseColorLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String text = value.trim();
        if (text.charAt(0) == '#') {
            String hex = text.substring(1);
            try {
                switch (hex.length()) {
                    case 3:
                        return 0xFF000000
                                | (Integer.parseInt(hex.substring(0, 1), 16) * 0x11) << 16
                                | (Integer.parseInt(hex.substring(1, 2), 16) * 0x11) << 8
                                | (Integer.parseInt(hex.substring(2, 3), 16) * 0x11);
                    case 4:
                        return (Integer.parseInt(hex.substring(0, 1), 16) * 0x11) << 24
                                | (Integer.parseInt(hex.substring(1, 2), 16) * 0x11) << 16
                                | (Integer.parseInt(hex.substring(2, 3), 16) * 0x11) << 8
                                | (Integer.parseInt(hex.substring(3, 4), 16) * 0x11);
                    case 6:
                        return 0xFF000000 | Integer.parseInt(hex, 16);
                    case 8:
                        return (int) Long.parseLong(hex, 16);
                    default:
                        return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return NAMED_COLORS.get(text.toLowerCase());
    }

    // ---------------------------------------------------------------- xml drawable

    /**
     * 把 {@code res/drawable} 下的 XML 画出来。
     *
     * <p>真资源要靠 aapt2 编译，这里手写一个够用子集：{@code <shape>}（{@code solid} / {@code corners} /
     * {@code stroke} / {@code gradient} / {@code size}）。识别不了就返回 {@code null}，交给调用方原来的路径。
     */
    public static Drawable createXmlDrawable(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        Document document = parseXml(file);
        if (document == null || document.getDocumentElement() == null) {
            return null;
        }
        Element root = document.getDocumentElement();
        if (!"shape".equals(root.getNodeName())) {
            Log.w(LOG_TAG, "暂不支持这种 xml drawable：" + root.getNodeName() + "（" + file + "）");
            return null;
        }
        GradientDrawable drawable = new GradientDrawable();
        String shape = attributeOf(root, "shape");
        if ("oval".equals(shape)) {
            drawable.setShape(GradientDrawable.OVAL);
        } else if ("line".equals(shape)) {
            drawable.setShape(GradientDrawable.LINE);
        } else if ("ring".equals(shape)) {
            // ring 的半径/环厚要按 View 的实际尺寸算，而动态 inflate 阶段拿不到尺寸，
            // 直接交给 GradientDrawable 的 RING 会画不出来（实测：示例里的球就是一片空白）。
            // 但 thicknessRatio<=2 意味着「环厚 ≥ 半径」—— 那本来就是实心圆，按 oval 画正合适
            // （示例的 circle.xml 正是 thicknessRatio="2"）。细环仍走 RING。
            Float thicknessRatio = parseFloatOrNull(attributeOf(root, "thicknessRatio"));
            Float innerRadiusRatio = parseFloatOrNull(attributeOf(root, "innerRadiusRatio"));
            int thickness = parseDp(attributeOf(root, "thickness"));
            boolean solid = thicknessRatio != null && thicknessRatio <= 2
                    && innerRadiusRatio == null && thickness <= 0;
            if (solid) {
                drawable.setShape(GradientDrawable.OVAL);
            } else {
                drawable.setShape(GradientDrawable.RING);
                if (thicknessRatio != null && thicknessRatio > 0) {
                    drawable.setThicknessRatio(thicknessRatio);
                }
                if (innerRadiusRatio != null && innerRadiusRatio > 0) {
                    drawable.setInnerRadiusRatio(innerRadiusRatio);
                }
                if (thickness > 0) {
                    drawable.setThickness(thickness);
                }
            }
        } else {
            drawable.setShape(GradientDrawable.RECTANGLE);
        }
        int solid = 0;
        boolean hasSolid = false;
        int strokeWidth = -1;
        int strokeColor = 0;
        Float radius = null;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = node.getNodeName();
            if ("solid".equals(tag)) {
                Integer color = parseColorLiteral(attributeOf(node, "color"));
                if (color != null) {
                    solid = color;
                    hasSolid = true;
                }
            } else if ("corners".equals(tag)) {
                radius = firstDimension(node, "radius", "topLeftRadius", "bottomLeftRadius",
                        "topRightRadius", "bottomRightRadius");
            } else if ("stroke".equals(tag)) {
                String width = attributeOf(node, "width");
                strokeWidth = parseDp(width);
                Integer color = parseColorLiteral(attributeOf(node, "color"));
                if (color != null) {
                    strokeColor = color;
                }
            }
        }
        if (hasSolid) {
            drawable.setColor(solid);
        }
        if (radius != null) {
            drawable.setCornerRadius(radius);
        }
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private static Float firstDimension(Node node, String... names) {
        for (String name : names) {
            Float value = parseDimension(attributeOf(node, name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 只处理 {@code dp}（以及无单位 = px），足够画圆角；其余单位返回 null 由调用方忽略。 */
    private static int parseDp(String value) {
        Float px = parseDimension(value);
        return px == null ? -1 : (int) (px * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }

    private static Float parseFloatOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Float parseDimension(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String text = value.trim();
        int end = 0;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            float number = Float.parseFloat(text.substring(0, end));
            String unit = text.substring(end).trim();
            if (unit.isEmpty() || unit.equals("px")) {
                return number;
            }
            if (unit.equals("dp") || unit.equals("dip")) {
                return number * android.content.res.Resources.getSystem().getDisplayMetrics().density;
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static List<File> listFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return list;
    }

    public static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * 取属性值。
     *
     * <p>{@code res/} 里的 xml 基本都是 {@code android:xxx}（少数是 {@code app:xxx}）这种带前缀写法，
     * 而 {@code getAttributes()} 的键就是带前缀的原名，所以先直接查、再按本地名匹配。
     */
    private static String attributeOf(Node node, String name) {
        NamedNodeMap attributes = node.getAttributes();
        if (attributes == null) {
            return null;
        }
        Node attribute = attributes.getNamedItem(name);
        if (attribute == null) {
            for (int i = 0; i < attributes.getLength(); i++) {
                Node candidate = attributes.item(i);
                if (candidate.getNodeName().endsWith(":" + name)) {
                    attribute = candidate;
                    break;
                }
            }
        }
        return attribute == null ? null : attribute.getNodeValue();
    }

    private static String textOf(Node node) {
        String text = node.getTextContent();
        return text == null ? "" : text.trim();
    }

    static Document parseXml(File file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            try (InputStream input = new FileInputStream(file)) {
                return builder.parse(input);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "解析 xml 失败：" + file, e);
            return null;
        }
    }

    /**
     * {@code @menu/navigation} / {@code @drawable/x} → 去掉 {@code @type/} 之后的名字。
     *
     * <p>不是引用（例如直接是个文件名）时原样返回。
     */
    public static String nameOfReference(String value) {
        if (value == null) {
            return null;
        }
        int slash = value.indexOf('/');
        if (slash >= 0 && value.charAt(0) == '@') {
            return value.substring(slash + 1);
        }
        return value;
    }

    /**
     * 解析 {@code res/menu/*.xml}。
     *
     * <p>真资源要靠 aapt2 编成 Menu 资源才能 {@code inflateMenu(int)}，这里按名字读 xml 自己建项。
     * 只处理一层的 {@code <item>}（带子 {@code <menu>} 的记一条日志跳过）。
     */
    public static List<MenuEntry> parseMenu(File file) {
        List<MenuEntry> entries = new ArrayList<>();
        Document document = parseXml(file);
        if (document == null || document.getDocumentElement() == null) {
            return entries;
        }
        collectMenuEntries(document.getDocumentElement(), entries, file);
        return entries;
    }

    private static void collectMenuEntries(Node node, List<MenuEntry> entries, File file) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = child.getNodeName();
            if ("item".equals(tag)) {
                String id = attributeOf(child, "id");
                entries.add(new MenuEntry(id == null ? null : Ids.parseIdName(id),
                        attributeOf(child, "title"), attributeOf(child, "icon")));
            } else if ("group".equals(tag) || "menu".equals(tag)) {
                // <group> 只是分组容器（它的 id 不影响子项），展开继续收。
                collectMenuEntries(child, entries, file);
            } else {
                Log.w(LOG_TAG, "菜单里暂不支持这种子标签，已跳过：" + tag + "（" + file.getName() + "）");
            }
        }
    }

    /** {@code res/menu/*.xml} 里的一个菜单项。 */
    public static final class MenuEntry {

        private final String mIdName;
        private final String mTitle;
        private final String mIcon;

        MenuEntry(String idName, String title, String icon) {
            mIdName = idName;
            mTitle = title;
            mIcon = icon;
        }

        /** {@code @+id/navigation_home} → {@code navigation_home}；没写 id 时为 null。 */
        public String getIdName() {
            return mIdName;
        }

        public String getTitle() {
            return mTitle;
        }

        /** 图标引用原文（如 {@code @drawable/ic_home_black_48dp}），没写时为 null。 */
        public String getIcon() {
            return mIcon;
        }

        @Override
        public String toString() {
            return "MenuEntry{" + mIdName + ", " + mTitle + ", " + mIcon + "}";
        }
    }

    private static String readText(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new String(bytes, 0, offset, "UTF-8");
        } catch (Exception e) {
            Log.w(LOG_TAG, "读取失败：" + file, e);
            return null;
        }
    }

    /** {@code res/values/*.xml} 里的 {@code <style>}。 */
    public static final class Style {

        private final String mName;
        private final String mParent;
        private final Map<String, String> mItems = new LinkedHashMap<>();

        Style(String name, String parent) {
            mName = name;
            mParent = parent;
        }

        public String getName() {
            return mName;
        }

        public String getParent() {
            return mParent;
        }

        public String getItem(String item) {
            return mItems.get(item);
        }

        public String[] items() {
            return mItems.keySet().toArray(new String[0]);
        }

        void addItem(String name, String value) {
            mItems.put(name, value);
        }

        @Override
        public String toString() {
            return "Style{" + mName + ", parent=" + mParent + ", items=" + mItems + "}";
        }
    }

    /** 只是给单元测试与诊断用的可读描述。 */
    @Override
    public String toString() {
        return "ProjectResources{" + mResDir + ", types=" + Arrays.toString(types())
                + ", ids=" + mIdNames.size() + "}";
    }
}
