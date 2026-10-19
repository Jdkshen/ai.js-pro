package com.jdkshen.aijspro.sample;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.model.sample.SampleFiles;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机回归：示例的「落盘」和「导入」都必须带上伴生资源。
 *
 * <p>背景（真实踩过）：示例里的资源是相对路径 —— {@code images.read("./mario.png")}、
 * {@code ui.layout(files.read("./layout.xml"))}。运行前只把这些文件放到**同一目录**才成立：
 * <ul>
 * <li>直接把内置正文交给引擎跑，工作目录是脚本目录 → {@code images.read} 返回 null，
 * 下一行抛 {@code NullPointerException: image = null}；</li>
 * <li>「查看/编辑」只导入单个 .js → 同上（或 {@code Cannot find module: ./xxx.js}）。</li>
 * </ul>
 */
public class SampleFilesImportTest {

    /** 目录里有 project.json（是个工程），且示例本体依赖同目录的图片。 */
    private static final String PROJECT_SAMPLE =
            "sample/Rhino 引擎/图片与图色处理/找图/找出马里奥.js";
    /** 目录里有 project.json + jar/xml（QuickJS 工程示例）。 */
    private static final String JAR_PROJECT_SAMPLE =
            "sample/QuickJS 引擎/项目与打包/dsh 手机客户端/main.js";
    /** 普通示例：所在目录没有 project.json，也没有伴生资源。 */
    private static final String PLAIN_SAMPLE =
            "sample/Rhino 引擎/文字识别(OCR)/截图并识别文字(MLKitOCR).js";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void materializePutsCompanionFilesNextToSample() {
        File file = SampleFiles.materialize(context(), PROJECT_SAMPLE);
        assertNotNull("示例没能落盘", file);
        assertTrue(file.getPath(), file.isFile());
        File dir = file.getParentFile();
        assertTrue("缺伴生图片 super_mario.jpg：" + dir,
                new File(dir, "super_mario.jpg").isFile());
        assertTrue("缺伴生图片 mario.png：" + dir, new File(dir, "mario.png").isFile());
    }

    @Test
    public void importForEditCopiesWholeSampleDirectoryIntoScriptDir() throws Exception {
        File file = SampleFiles.importForEdit(context(), PROJECT_SAMPLE);
        assertNotNull("导入失败", file);
        assertTrue(file.getPath(), file.isFile());
        File dir = file.getParentFile();
        // 目录里有 project.json → 用该目录名（导入后仍是一个工程）
        assertEquals("找图", dir.getName());
        assertEquals(new File(Pref.getScriptDirPath()).getCanonicalPath(),
                dir.getParentFile().getCanonicalPath());
        assertTrue("伴生图片没跟着导入：" + dir, new File(dir, "mario.png").isFile());
        assertTrue("project.json 没跟着导入：" + dir, new File(dir, "project.json").isFile());
        assertEquals("找出马里奥.js", file.getName());
    }

    @Test
    public void importForEditKeepsProjectCompanions() throws Exception {
        File file = SampleFiles.importForEdit(context(), JAR_PROJECT_SAMPLE);
        assertNotNull("导入失败", file);
        File dir = file.getParentFile();
        assertEquals("dsh 手机客户端", dir.getName());
        assertTrue("layout.xml 没跟着导入：" + dir, new File(dir, "layout.xml").isFile());
        assertTrue("client-runtime.jar 没跟着导入：" + dir,
                new File(dir, "client-runtime.jar").isFile());
        assertTrue("project.json 没跟着导入：" + dir, new File(dir, "project.json").isFile());
    }

    @Test
    public void importForEditUsesSampleNameWhenDirectoryIsNotAProject() throws Exception {
        File file = SampleFiles.importForEdit(context(), PLAIN_SAMPLE);
        assertNotNull("导入失败", file);
        File dir = file.getParentFile();
        // 没有 project.json → 用示例自己的名字（避免不同目录下的同名 main.js 互相覆盖）
        assertEquals("截图并识别文字(MLKitOCR)", dir.getName());
        assertFalse(file.isDirectory());
    }
}
