package com.jdkshen.aijspro.packaging

import com.stardust.autojs.project.ScriptProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本保护等级的纯逻辑单测（不依赖 Android 运行时）。
 *
 * 打包端 `ApkBuilder` 与打包出的 App 都通过 [ScriptProtection] 判定行为，
 * 这里的用例锁住「等级 → 行为」的映射，避免两端语义漂移。
 */
class ScriptProtectionTest {

    @Test
    fun `normalize clamps out of range levels`() {
        assertEquals(ScriptProtection.LEVEL_NONE, ScriptProtection.normalize(-1))
        assertEquals(ScriptProtection.LEVEL_NONE, ScriptProtection.normalize(0))
        assertEquals(ScriptProtection.LEVEL_ENCRYPT, ScriptProtection.normalize(1))
        assertEquals(ScriptProtection.LEVEL_COMPILE, ScriptProtection.normalize(2))
        assertEquals(ScriptProtection.MAX_LEVEL, ScriptProtection.normalize(99))
    }

    @Test
    fun `level zero is the only level without encryption`() {
        assertFalse(ScriptProtection.shouldEncrypt(0))
        assertTrue(ScriptProtection.shouldEncrypt(1))
        assertTrue(ScriptProtection.shouldEncrypt(2))
        // 非法负值按 0 处理，不能变成「加密」以外的意外行为
        assertFalse(ScriptProtection.shouldEncrypt(ScriptProtection.normalize(-3)))
    }

    @Test
    fun `compile starts at level two`() {
        assertFalse(ScriptProtection.shouldCompile(0))
        assertFalse(ScriptProtection.shouldCompile(1))
        assertTrue(ScriptProtection.shouldCompile(2))
    }

    @Test
    fun `describe follows the level`() {
        assertEquals("不加密", ScriptProtection.describe(0))
        assertEquals("加密（AES）", ScriptProtection.describe(1))
        assertEquals("编译 + 加密", ScriptProtection.describe(2))
    }

    @Test
    fun `missing encryptLevel keeps encrypting like before`() {
        // 老工程没有这个字段时走默认值，必须仍是「加密」：以前是无条件加密。
        assertEquals(ScriptProtection.LEVEL_ENCRYPT, ScriptProtection.DEFAULT_LEVEL)
        assertTrue(ScriptProtection.shouldEncrypt(ScriptProtection.DEFAULT_LEVEL))
    }
}
