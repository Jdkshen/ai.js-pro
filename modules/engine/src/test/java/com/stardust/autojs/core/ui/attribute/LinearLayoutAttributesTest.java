package com.stardust.autojs.core.ui.attribute;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-59：{@code LinearLayoutAttributes} 的 JVM 侧回归（映射表 + 同源/注册不变量）。
 *
 * <p>为什么测试长这样：属性表的**行为**需要真 {@code LinearLayout}（android.jar 是 stub，JVM 里构造不出来）→
 * 真机行为由 Lead 用一段 ui.layout 脚本在 K40 上验；这里把**纯逻辑**（字符串映射）与**结构性不变量**
 * （名字清单与 XML inflater 同源、注册点唯一）钉住，避免以后改动漂移。
 */
public class LinearLayoutAttributesTest {

    private static final String ATTRIBUTE_SOURCE = "src/main/java/com/stardust/autojs/core/ui/attribute/LinearLayoutAttributes.java";
    private static final String INFLATER_SOURCE = "src/main/java/com/stardust/autojs/core/ui/inflater/inflaters/LinearLayoutInflater.java";
    private static final String FACTORY_SOURCE = "src/main/java/com/stardust/autojs/core/ui/attribute/ViewAttributesFactory.java";

    /** 脚本/XML 两个入口必须同源：映射值就是框架常量（LinearLayout 的 0/1 与 SHOW_DIVIDER_* 的位值）。 */
    @Test
    public void orientationAndShowDividersMappersUseFrameworkConstants() {
        assertEquals(Integer.valueOf(0), LinearLayoutAttributes.ORIENTATIONS.get("horizontal"));
        assertEquals(Integer.valueOf(1), LinearLayoutAttributes.ORIENTATIONS.get("vertical"));
        assertEquals(Integer.valueOf(0), LinearLayoutAttributes.SHOW_DIVIDERS.get("none"));
        assertEquals(Integer.valueOf(1), LinearLayoutAttributes.SHOW_DIVIDERS.get("beginning"));
        assertEquals(Integer.valueOf(2), LinearLayoutAttributes.SHOW_DIVIDERS.get("middle"));
        assertEquals(Integer.valueOf(4), LinearLayoutAttributes.SHOW_DIVIDERS.get("end"));
        // split 支持组合：middle|end = 6，beginning|middle|end = 7（与 LinearLayoutInflater 同一个 ValueMapper）
        assertEquals(6, LinearLayoutAttributes.SHOW_DIVIDERS.split("middle|end"));
        assertEquals(7, LinearLayoutAttributes.SHOW_DIVIDERS.split("beginning|middle|end"));
        assertEquals(0, LinearLayoutAttributes.SHOW_DIVIDERS.split("none"));
    }

    /** 名字清单必须与 XML 侧 inflater 的 switch 逐字同源（少一个就是「XML 能写、脚本不能写」）。 */
    @Test
    public void registeredNamesMatchTheXmlInflaterSwitch() throws Exception {
        List<String> registered = registeredNames();
        List<String> inflaterNames = inflaterCaseNames();
        // gravity 在基类 ViewAttributes 已注册（且语义不同），这里只对齐 LinearLayoutInflater 独有的名字。
        inflaterNames.remove("gravity");
        registered.sort(String::compareTo);
        inflaterNames.sort(String::compareTo);
        assertEquals("LinearLayoutAttributes 注册的名字必须与 LinearLayoutInflater 的 switch 一致",
                inflaterNames, registered);
    }

    /** 注册点唯一：只在 ViewAttributesFactory 里挂一次，且指向本类（不要散落到别处 new）。 */
    @Test
    public void registeredOnlyAtTheSingleFactoryPoint() throws Exception {
        String factory = read(FACTORY_SOURCE);
        int occurrences = count(factory, "sViewAttributesCreators.put(LinearLayout.class");
        assertEquals("LinearLayout 只能注册一次", 1, occurrences);
        assertTrue("注册的必须是 LinearLayoutAttributes",
                factory.contains("sViewAttributesCreators.put(LinearLayout.class, LinearLayoutAttributes::new)"));
        String attribute = read(ATTRIBUTE_SOURCE);
        assertTrue("属性类不能自己去动工厂表", !attribute.contains("ViewAttributesFactory"));
    }

    /** divider 在两个入口都必须走 Drawables 解析（脚本侧 registerDrawableAttr / XML 侧 getDrawables().parse）。 */
    @Test
    public void dividerIsParsedAsDrawableOnBothPaths() throws Exception {
        assertTrue("脚本侧：divider 走 registerDrawableAttr",
                read(ATTRIBUTE_SOURCE).contains("registerDrawableAttr(\"divider\""));
        assertTrue("XML 侧：divider 走 getDrawables().parse",
                read(INFLATER_SOURCE).contains("case \"divider\":"));
        assertTrue("XML 侧：divider 走 getDrawables().parse",
                read(INFLATER_SOURCE).contains("view.setDividerDrawable(getDrawables().parse(view, value))"));
    }

    /** 未知属性名不崩：脚本侧 attr() 对**未注册**名字是静默 no-op（attr(name) 给 undefined、attr(name,value) 不设置也不抛）。 */
    @Test
    public void unknownAttributeNamesAreSilentlyIgnored() throws Exception {
        String prototype = read("src/main/java/com/stardust/autojs/core/ui/nativeview/ViewPrototype.java");
        assertTrue("attr(name) 未命中 → undefined", prototype.contains("return Undefined.SCRIPTABLE_UNDEFINED;"));
        assertTrue("attr(name, value) 只在命中时才设置（未命中不抛）",
                prototype.contains("if (attribute != null) {"));
        // 本类只注册这 7 个名字：其它任何一个名字都会走上面的 no-op 分支。
        assertEquals(7, registeredNames().size());
    }

    // ------------------------------------------------------------------ helpers

    /** 本类 onRegisterAttrs 里 register* 的第一个字符串参数，按出现顺序。 */
    private static List<String> registeredNames() throws Exception {
        String source = read(ATTRIBUTE_SOURCE);
        Matcher matcher = Pattern.compile("register(?:Attr|BooleanAttr|DrawableAttr|IntPixelAttr|PixelAttr|IdAttr)\\(")
                .matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            int quote = source.indexOf('"', matcher.end());
            int end = source.indexOf('"', quote + 1);
            names.add(source.substring(quote + 1, end));
        }
        return names;
    }

    /** LinearLayoutInflater 的 switch 分支名（排除 default）。 */
    private static List<String> inflaterCaseNames() throws Exception {
        String source = read(INFLATER_SOURCE);
        Matcher matcher = Pattern.compile("case \"([^\"]+)\":").matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}