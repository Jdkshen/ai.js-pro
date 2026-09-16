package com.stardust.autojs.core.ui.inflater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.stardust.autojs.core.ui.inflater.util.Ids;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 工程模式资源表（{@code project.json} 的 {@code androidResources}）的单元测试。
 *
 * <p>用的是仓库里那份 {@code 使用Android原生界面} 示例的形状：values 里一个 style + 三个 color、
 * 一个 layout、一个 shape drawable、一个 anim、一个 menu。
 */
public class ProjectResourcesTest {

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private static final String STYLES_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<resources xmlns:tools=\"http://schemas.android.com/tools\">\n"
                    + "    <style name=\"MainTheme\" parent=\"Theme.AppCompat.Light.NoActionBar\">\n"
                    + "        <item name=\"colorPrimary\">@color/mainColorPrimary</item>\n"
                    + "        <item name=\"colorPrimaryDark\">@color/mainColorPrimaryDark</item>\n"
                    + "    </style>\n"
                    + "    <color name=\"mainColorAccent\">#FF5252</color>\n"
                    + "    <color name=\"mainColorPrimaryDark\">#0097A7</color>\n"
                    + "    <color name=\"mainColorPrimary\">#00BCD4</color>\n"
                    + "    <dimen name=\"screenMargin\">16dp</dimen>\n"
                    + "    <string name=\"appName\">使用Android原生界面</string>\n"
                    + "    <item name=\"onlyById\" type=\"id\"/>\n"
                    + "</resources>\n";

    private static final String MAIN_LAYOUT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                    + "    android:layout_width=\"match_parent\"\n"
                    + "    android:layout_height=\"match_parent\">\n"
                    + "    <com.stardust.autojs.core.ui.widget.JsViewPager\n"
                    + "        android:id=\"@+id/viewPager\"\n"
                    + "        android:layout_width=\"wrap_content\"\n"
                    + "        android:layout_height=\"wrap_content\"/>\n"
                    + "    <TextView\n"
                    + "        android:id=\"@+id/ball\"\n"
                    + "        android:background=\"@drawable/circle\"\n"
                    + "        android:textColor=\"@android:color/white\"/>\n"
                    + "</FrameLayout>\n";

    private static final String CIRCLE_DRAWABLE =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                    + "    android:shape=\"oval\">\n"
                    + "    <solid android:color=\"#FF00BCD4\"/>\n"
                    + "    <corners android:radius=\"50dp\"/>\n"
                    + "</shape>\n";

    private static final String BALL_ANIM =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<set xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                    + "    <translate android:fromXDelta=\"0\" android:toXDelta=\"200\"/>\n"
                    + "</set>\n";

    private static final String NAVIGATION_MENU =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                    + "    <item android:id=\"@+id/navigation_home\" android:title=\"主页\"/>\n"
                    + "</menu>\n";

    private File write(String relativePath, String content) throws Exception {
        File file = new File(mTemp.getRoot(), relativePath);
        assertTrue("创建父目录失败：" + file.getParentFile(),
                file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), content.getBytes(Charset.forName("UTF-8")));
        return file;
    }

    /** 建一份和示例同形状的工程，返回资源表。 */
    private ProjectResources loadProject() throws Exception {
        write("project.json", "{\n"
                + "  \"name\": \"使用Android原生界面\",\n"
                + "  \"packageName\": \"com.example.ui.android\",\n"
                + "  \"versionName\": \"1.0.0\",\n"
                + "  \"versionCode\": 1,\n"
                + "  \"main\": \"main.js\",\n"
                + "  \"androidResources\": { \"resDir\": \"res\", \"manifest\": \"AndroidManifest.xml\" }\n"
                + "}\n");
        write("res/values/styles.xml", STYLES_XML);
        write("res/layout/main.xml", MAIN_LAYOUT);
        write("res/drawable/circle.xml", CIRCLE_DRAWABLE);
        write("res/anim/ball.xml", BALL_ANIM);
        write("res/menu/navigation.xml", NAVIGATION_MENU);
        ProjectResources resources = ProjectResources.load(mTemp.getRoot(), "res");
        assertNotNull("应该能扫出资源表", resources);
        return resources;
    }

    private static List<String> asList(String[] values) {
        return Arrays.asList(values);
    }

    @Test
    public void registersEveryResourceType() throws Exception {
        ProjectResources resources = loadProject();

        List<String> types = asList(resources.types());
        assertTrue("缺少 color：" + types, types.contains(ProjectResources.TYPE_COLOR));
        assertTrue("缺少 style：" + types, types.contains(ProjectResources.TYPE_STYLE));
        assertTrue("缺少 layout：" + types, types.contains(ProjectResources.TYPE_LAYOUT));
        assertTrue("缺少 drawable：" + types, types.contains(ProjectResources.TYPE_DRAWABLE));
        assertTrue("缺少 anim：" + types, types.contains(ProjectResources.TYPE_ANIM));
        assertTrue("缺少 menu：" + types, types.contains(ProjectResources.TYPE_MENU));
        assertTrue("缺少 dimen：" + types, types.contains(ProjectResources.TYPE_DIMEN));
        assertTrue("缺少 string：" + types, types.contains(ProjectResources.TYPE_STRING));
        assertTrue("缺少 id：" + types, types.contains(ProjectResources.TYPE_ID));
        assertFalse(resources.isEmpty());
    }

    @Test
    public void collectsIdsFromLayoutsAndValues() throws Exception {
        ProjectResources resources = loadProject();

        List<String> ids = asList(resources.ids());
        assertTrue("布局里的 id 没收集到：" + ids, ids.contains("viewPager"));
        assertTrue("布局里的 id 没收集到：" + ids, ids.contains("ball"));
        assertTrue("values 里 type=id 的条目没收集到：" + ids, ids.contains("onlyById"));
        assertTrue("menu 里的 id 没收集到：" + ids, ids.contains("navigation_home"));
    }

    @Test
    public void idResourcesShareTheInflaterNameTable() throws Exception {
        ProjectResources resources = loadProject();

        // ui.R.id.viewPager 必须与布局里 android:id="@+id/viewPager" 落到控件上的是同一个值，
        // 否则 ui.viewPager 取不到控件。
        assertEquals(Ids.parse("viewPager"), resources.idOf(ProjectResources.TYPE_ID, "viewPager"));
        assertTrue("id 类型不该走合成 id 段", !resources.isProjectId(resources.idOf(ProjectResources.TYPE_ID, "viewPager")));
    }

    @Test
    public void syntheticIdsAreInTheirOwnPackageSegment() throws Exception {
        ProjectResources resources = loadProject();

        int colorId = resources.idOf(ProjectResources.TYPE_COLOR, "mainColorPrimaryDark");
        assertTrue("颜色应该有 id", colorId != 0);
        assertTrue("合成 id 应该落在 0x7e 段", resources.isProjectId(colorId));
        assertFalse("app 的资源 id 不该被判成工程资源", resources.isProjectId(0x7f010001));
        assertFalse("framework 的资源 id 不该被判成工程资源", resources.isProjectId(android.R.attr.alpha));
    }

    @Test
    public void resolvesColorReferenceToLiteral() throws Exception {
        ProjectResources resources = loadProject();

        Integer color = resources.colorOf("mainColorPrimaryDark");
        assertEquals(Integer.valueOf(0xFF0097A7), color);
        assertEquals("#FF0097A7", resources.resolveReference("@color/mainColorPrimaryDark"));
        assertEquals("#FF00BCD4", resources.resolveReference("@color/mainColorPrimary"));
        assertEquals("#FFFF5252", resources.resolveReference("@color/mainColorAccent"));
    }

    @Test
    public void resolvesStyleItemsThatReferenceColors() throws Exception {
        ProjectResources resources = loadProject();

        ProjectResources.Style style = resources.styleOf("MainTheme");
        assertNotNull("MainTheme 应该解析出来", style);
        assertEquals("Theme.AppCompat.Light.NoActionBar", style.getParent());
        assertEquals("@color/mainColorPrimaryDark", style.getItem("colorPrimaryDark"));
        assertEquals(0xFF0097A7, resources.colorOf("mainColorPrimaryDark").intValue());
        assertEquals("style 也要能按 id 反查",
                "MainTheme", resources.styleById(
                        resources.idOf(ProjectResources.TYPE_STYLE, "MainTheme")).getName());
    }

    @Test
    public void resolvesOtherValueTypes() throws Exception {
        ProjectResources resources = loadProject();

        assertEquals("16dp", resources.resolveReference("@dimen/screenMargin"));
        assertEquals("使用Android原生界面", resources.resolveReference("@string/appName"));
    }

    @Test
    public void resolvesDrawableToFileUrl() throws Exception {
        ProjectResources resources = loadProject();

        String resolved = resources.resolveReference("@drawable/circle");
        assertNotNull("@drawable/circle 应该被改写", resolved);
        assertTrue("应该改写成 file:// 路径，实际是 " + resolved, resolved.startsWith("file://"));
        assertTrue("路径应该指向 res/drawable/circle.xml，实际是 " + resolved, resolved.endsWith("circle.xml"));
        assertTrue(new File(resolved.substring("file://".length())).isFile());
    }

    @Test
    public void layoutFileResolvesByName() throws Exception {
        ProjectResources resources = loadProject();

        String path = resources.layoutFile("main");
        assertNotNull("ui.layoutFile(\"main\") 应该解析到 res/layout/main.xml", path);
        assertTrue(path.endsWith(File.separator + "main.xml"));
        assertTrue(path.replace('\\', '/').endsWith("res/layout/main.xml"));
        assertNull("不是资源名（比如是路径）时应该返回 null，交给原来的路径语义", resources.layoutFile("./whatever.xml"));
    }

    /** {@code app:menu="@menu/navigation"} 靠这个把菜单项建出来。 */
    @Test
    public void parsesMenuItems() throws Exception {
        ProjectResources resources = loadProject();

        File menu = resources.fileOf(ProjectResources.TYPE_MENU, "navigation");
        assertNotNull("菜单资源应该能找到", menu);

        List<ProjectResources.MenuEntry> entries = ProjectResources.parseMenu(menu);
        assertEquals(1, entries.size());
        assertEquals("navigation_home", entries.get(0).getIdName());
        assertEquals("主页", entries.get(0).getTitle());
        assertNull("示例里的菜单项没写 icon", entries.get(0).getIcon());
        // 菜单项的 id 与 ui.R.id.* 走同一张名字表，脚本里 item.getItemId() 才能和它对上。
        assertEquals(Ids.parse("navigation_home"),
                resources.idOf(ProjectResources.TYPE_ID, "navigation_home"));
    }

    @Test
    public void parsesMenuIconsAndNestedItemsAreSkipped() throws Exception {
        write("res/menu/with_icons.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <item android:id=\"@+id/a\" android:title=\"A\" android:icon=\"@drawable/circle\"/>\n"
                        + "    <group android:id=\"@+id/g\"/>\n"
                        + "    <item android:id=\"@+id/b\" android:title=\"B\"/>\n"
                        + "</menu>\n");
        ProjectResources resources = ProjectResources.load(mTemp.getRoot(), "res");
        assertNotNull(resources);

        List<ProjectResources.MenuEntry> entries =
                ProjectResources.parseMenu(resources.fileOf(ProjectResources.TYPE_MENU, "with_icons"));
        assertEquals("子标签应该被跳过，只留两个 item：" + entries, 2, entries.size());
        assertEquals("@drawable/circle", entries.get(0).getIcon());
        assertEquals("a", entries.get(0).getIdName());
        assertEquals("b", entries.get(1).getIdName());
    }

    @Test
    public void stripsResourceReferencePrefix() {
        assertEquals("navigation", ProjectResources.nameOfReference("@menu/navigation"));
        assertEquals("circle", ProjectResources.nameOfReference("@drawable/circle"));
        assertEquals("main.xml", ProjectResources.nameOfReference("main.xml"));
        assertNull(ProjectResources.nameOfReference(null));
    }

    @Test
    public void leavesFrameworkAndUnsupportedReferencesAlone() throws Exception {
        ProjectResources resources = loadProject();

        // framework 资源：原来的解析路径能处理，不要抢。
        assertNull(resources.resolveReference("@android:color/white"));
        // layout/anim/menu/style 有各自的调用点，这里不改写。
        assertNull(resources.resolveReference("@layout/main"));
        assertNull(resources.resolveReference("@anim/ball"));
        assertNull(resources.resolveReference("@menu/navigation"));
        assertNull(resources.resolveReference("@style/MainTheme"));
        // 不存在的资源
        assertNull(resources.resolveReference("@color/notThere"));
        assertNull(resources.resolveReference("@color"));
        assertNull(resources.resolveReference("#FF5252"));
    }

    @Test
    public void parsesColorsInAllCommonForms() {
        assertEquals(Integer.valueOf(0xFFFF0000), ProjectResources.parseColorLiteral("#FF0000"));
        assertEquals(Integer.valueOf(0xFF00FF00), ProjectResources.parseColorLiteral("#0F0"));
        // #ARGB 四位：A=7→0x77、R=F→0xFF、G=0、B=F→0xFF
        assertEquals(Integer.valueOf(0x77FF00FF), ProjectResources.parseColorLiteral("#7F0F"));
        assertEquals(Integer.valueOf(0x8000FF00), ProjectResources.parseColorLiteral("#8000FF00"));
        assertEquals(Integer.valueOf(0xFFFFFFFF), ProjectResources.parseColorLiteral("white"));
        assertEquals(Integer.valueOf(0x00000000), ProjectResources.parseColorLiteral("transparent"));
        assertEquals(Integer.valueOf(0xFFFF5252), ProjectResources.parseColorLiteral("#FF5252"));
        assertNull(ProjectResources.parseColorLiteral("notAColor"));
        assertNull(ProjectResources.parseColorLiteral(""));
        assertNull(ProjectResources.parseColorLiteral("#12345"));
    }

    @Test
    public void survivesColorReferenceCycles() throws Exception {
        write("res/values/colors.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <color name=\"a\">@color/b</color>\n"
                        + "    <color name=\"b\">@color/a</color>\n"
                        + "</resources>\n");
        ProjectResources resources = ProjectResources.load(mTemp.getRoot(), "res");

        assertNotNull(resources);
        // 循环引用按「解析不出来」处理，不能把栈递归爆。
        assertNull(resources.colorOf("a"));
        assertNull(resources.resolveReference("@color/a"));
    }

    @Test
    public void returnsNullWithoutResDirectory() throws Exception {
        write("project.json", "{}");
        assertNull(ProjectResources.load(mTemp.getRoot(), "res"));
        assertNull(ProjectResources.load(null, "res"));
        assertNull(ProjectResources.load(mTemp.getRoot(), null));
    }

    @Test
    public void defaultResDirIsRes() throws Exception {
        write("res/values/styles.xml", STYLES_XML);
        ProjectResources resources = ProjectResources.load(mTemp.getRoot(), null);
        assertNotNull("resDir 为空时应该退回默认的 res", resources);
        assertEquals("res", resources.getResDir().getName());
    }
}
