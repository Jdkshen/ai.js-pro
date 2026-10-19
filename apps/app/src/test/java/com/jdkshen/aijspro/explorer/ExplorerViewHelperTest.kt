package com.jdkshen.aijspro.explorer

import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.explorer.ExplorerItem
import com.jdkshen.aijspro.model.explorer.ExplorerPage
import com.jdkshen.aijspro.model.script.ScriptFile
import com.jdkshen.aijspro.ui.explorer.ExplorerViewHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExplorerViewHelper] 的图标/文案选择规则。
 *
 * <p>为什么值得测：旧 `ExplorerView` 与 Compose 版 `MiuixScriptListHost` **共用**这一套规则
 * （新实现直接调这些静态方法）。它是「两份列表长得一样」的关键契约之一，但此前没有任何测试；
 * 一旦有人改这里的图标映射或首字母规则，两个列表不会同时坏——而是**看起来不一致**，极难发现。
 *
 * <p>能在 JVM 上跑的原因：这些分支只读 `item.getName()` / `item.getType()`。
 * 只有两条路径会碰 Android（示例目录名走 `GlobalAppContext.getString`、`TYPE_AUTO_FILE` 的颜色走
 * `ContextCompat.getColor`），本测试有意避开它们，用 `TYPE_AUTO_FILE` 之外的类型覆盖逻辑。
 */
class ExplorerViewHelperTest {

    /** 只实现本测试用得到的读取方法；其余成员不在被测路径上。 */
    private class FakeItem(
        private val name: String,
        private val type: String
    ) : ExplorerItem {
        override fun getName() = name
        override fun getType() = type
        override fun getSize() = 0L
        override fun lastModified() = 0L
        override fun getParent(): ExplorerPage? = null
        override fun getPath() = name
        override fun canDelete() = false
        override fun canRename() = false
        override fun toScriptFile(): ScriptFile? = null
        override fun isEditable() = false
        override fun isExecutable() = false
    }

    private fun item(name: String, type: String = ExplorerItem.TYPE_UNKNOWN) = FakeItem(name, type)

    // ---- isJavaScript：只看 type，不看扩展名 ---------------------------------

    @Test
    fun `isJavaScript keys off the item type not the file name`() {
        assertTrue(ExplorerViewHelper.isJavaScript(item("main.js", ExplorerItem.TYPE_JAVASCRIPT)))
        // 名字像 JS 但类型不是 -> 不算 JS（旧实现同样如此）
        assertFalse(ExplorerViewHelper.isJavaScript(item("main.js", ExplorerItem.TYPE_UNKNOWN)))
        // 类型是 JS 但名字怪 -> 仍算 JS
        assertTrue(ExplorerViewHelper.isJavaScript(item("noext", ExplorerItem.TYPE_JAVASCRIPT)))
    }

    // ---- getFileIconRes：JS 优先于扩展名 -------------------------------------

    @Test
    fun `javascript type wins over a json suffix`() {
        // 旧实现先判 isJavaScript，所以 type=js 的 .json 文件拿的是代码图标
        assertEquals(
            R.drawable.ic_code_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("data.json", ExplorerItem.TYPE_JAVASCRIPT))
        )
    }

    @Test
    fun `suffix based icons ignore the item type`() {
        // 这三种都靠文件名后缀决定，与 type 无关
        assertEquals(
            R.drawable.ic_json_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("data.json", ExplorerItem.TYPE_UNKNOWN))
        )
        assertEquals(
            R.drawable.ic_markdown_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("readme.md", ExplorerItem.TYPE_UNKNOWN))
        )
        assertEquals(
            R.drawable.ic_apk_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("app.apk", ExplorerItem.TYPE_UNKNOWN))
        )
    }

    @Test
    fun `suffix match is case insensitive`() {
        assertEquals(
            R.drawable.ic_json_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("DATA.JSON", ExplorerItem.TYPE_UNKNOWN))
        )
        assertEquals(
            R.drawable.ic_markdown_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("README.MD", ExplorerItem.TYPE_UNKNOWN))
        )
        assertEquals(
            R.drawable.ic_apk_file_24dp,
            ExplorerViewHelper.getFileIconRes(item("App.APK", ExplorerItem.TYPE_UNKNOWN))
        )
    }

    @Test
    fun `unknown suffix has no dedicated icon`() {
        assertEquals(0, ExplorerViewHelper.getFileIconRes(item("notes.txt", ExplorerItem.TYPE_UNKNOWN)))
        assertEquals(0, ExplorerViewHelper.getFileIconRes(item("archive.zip", ExplorerItem.TYPE_UNKNOWN)))
        // 只有 "json" 结尾不算：".jsonx" 不匹配，因为检查的是 endsWith(".json")
        assertEquals(0, ExplorerViewHelper.getFileIconRes(item("data.jsonx", ExplorerItem.TYPE_UNKNOWN)))
    }

    // ---- usesCodeIcon 与 getIconText 互为补集 --------------------------------

    @Test
    fun `usesCodeIcon mirrors getFileIconRes`() {
        assertTrue(ExplorerViewHelper.usesCodeIcon(item("main.js", ExplorerItem.TYPE_JAVASCRIPT)))
        assertFalse(ExplorerViewHelper.usesCodeIcon(item("notes.txt", ExplorerItem.TYPE_UNKNOWN)))
    }

    @Test
    fun `icon text is empty whenever a dedicated icon is shown`() {
        // 有专属图标时不能再叠首字母，否则图标下面会透出半个字母（旧实现的注释就是这么写的）
        assertEquals("", ExplorerViewHelper.getIconText(item("main.js", ExplorerItem.TYPE_JAVASCRIPT)))
        assertEquals("", ExplorerViewHelper.getIconText(item("data.json", ExplorerItem.TYPE_UNKNOWN)))
        assertEquals("", ExplorerViewHelper.getIconText(item("readme.md", ExplorerItem.TYPE_UNKNOWN)))
        assertEquals("", ExplorerViewHelper.getIconText(item("app.apk", ExplorerItem.TYPE_UNKNOWN)))
    }

    // ---- getIconText：取的是【类型】首字母，不是文件名首字母 -------------------

    @Test
    fun `icon text comes from the type not the file name`() {
        // 这是最容易记错的一条：main.txt 显示的是 "T"（type=txt），不是 "M"
        assertEquals("T", ExplorerViewHelper.getIconText(item("main.txt", "txt")))
        assertEquals("A", ExplorerViewHelper.getIconText(item("notes", "abc")))
    }

    @Test
    fun `icon text is upper cased`() {
        // 注意不能用 type="js"：它是 TYPE_JAVASCRIPT，会走专属图标分支返回 ""
        assertEquals("X", ExplorerViewHelper.getIconText(item("a.xml", "xml")))
        assertEquals("Z", ExplorerViewHelper.getIconText(item("b.zip", "zip")))
    }

    @Test
    fun `empty type falls back to the unknown marker`() {
        assertEquals(ExplorerItem.TYPE_UNKNOWN, ExplorerViewHelper.getIconText(item("mystery", "")))
    }

    @Test
    fun `unknown type uses the question mark as its initial`() {
        assertEquals("?", ExplorerViewHelper.getIconText(item("mystery", ExplorerItem.TYPE_UNKNOWN)))
    }

    // ---- getIconColor：**本测试覆盖不到，见下方说明** --------------------------
    //
    // `ExplorerViewHelper.getIconColor` 内部直接调用 `android.graphics.Color.rgb(...)`，
    // 而本地单元测试里 `android.jar` 的方法是 not-mocked 的桩，调用即抛 RuntimeException。
    // 因此这条路径无法在 JVM 上断言，除非引入 Robolectric（为一个色值判断不值得）。
    //
    // 现状与替代证据：颜色映射（JS/JSON 近黑、MD 蓝、APK 亮绿、未知灰、auto 走资源色）已在
    // 真机截图里逐项确认，见 `docs/plans/UI_统一到 Compose(Miuix) 迁移方案.md` 的 S3.5 节：
    // 开关 ON/OFF 两张同位置截图逐像素一致，其中就包含这几种图标的底色。
    // 若将来要自动化它，正确做法是把「后缀/类型 -> 色值」抽成不依赖 android.graphics 的纯函数。

    // ---- getDisplayName：非页面项直接返回名字 --------------------------------

    @Test
    fun `display name of a file item is its name`() {
        assertEquals("main.js", ExplorerViewHelper.getDisplayName(item("main.js", ExplorerItem.TYPE_JAVASCRIPT)))
        assertEquals("readme.md", ExplorerViewHelper.getDisplayName(item("readme.md", ExplorerItem.TYPE_UNKNOWN)))
    }
}
