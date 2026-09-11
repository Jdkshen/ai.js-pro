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

    @Test
    fun `packaging page choices map to level and storage`() {
        assertEquals(ScriptProtection.LEVEL_NONE, ScriptProtection.levelOfChoice(ScriptProtection.CHOICE_NONE))
        assertEquals(ScriptProtection.LEVEL_ENCRYPT, ScriptProtection.levelOfChoice(ScriptProtection.CHOICE_ENCRYPT))
        assertEquals(ScriptProtection.LEVEL_COMPILE, ScriptProtection.levelOfChoice(ScriptProtection.CHOICE_COMPILE))
        // 加密 so 也是「加密」，只是载荷换个地方放
        assertEquals(ScriptProtection.LEVEL_ENCRYPT, ScriptProtection.levelOfChoice(ScriptProtection.CHOICE_NATIVE))

        assertEquals(ScriptProtection.STORAGE_ASSETS, ScriptProtection.storageOfChoice(ScriptProtection.CHOICE_NONE))
        assertEquals(ScriptProtection.STORAGE_ASSETS, ScriptProtection.storageOfChoice(ScriptProtection.CHOICE_ENCRYPT))
        assertEquals(ScriptProtection.STORAGE_ASSETS, ScriptProtection.storageOfChoice(ScriptProtection.CHOICE_COMPILE))
        assertEquals(ScriptProtection.STORAGE_NATIVE, ScriptProtection.storageOfChoice(ScriptProtection.CHOICE_NATIVE))
    }

    @Test
    fun `choices round trip through project config values`() {
        for (choice in 0 until ScriptProtection.CHOICE_COUNT) {
            val level = ScriptProtection.levelOfChoice(choice)
            val storage = ScriptProtection.storageOfChoice(choice)
            assertEquals("档位 $choice 不能往返", choice, ScriptProtection.choiceOf(level, storage))
        }
    }

    @Test
    fun `out of range choice falls back to the closest supported one`() {
        assertEquals(ScriptProtection.CHOICE_NONE, ScriptProtection.normalizeChoice(-5))
        assertEquals(ScriptProtection.CHOICE_NATIVE, ScriptProtection.normalizeChoice(99))
    }

    @Test
    fun `storage only recognizes the native value`() {
        assertEquals(ScriptProtection.STORAGE_NATIVE, ScriptProtection.normalizeStorage("native"))
        assertEquals(ScriptProtection.STORAGE_NATIVE, ScriptProtection.normalizeStorage(" NATIVE "))
        assertEquals(ScriptProtection.STORAGE_ASSETS, ScriptProtection.normalizeStorage(null))
        assertEquals(ScriptProtection.STORAGE_ASSETS, ScriptProtection.normalizeStorage(""))
        assertEquals(ScriptProtection.STORAGE_ASSETS, ScriptProtection.normalizeStorage("lib"))
        assertTrue(ScriptProtection.usesNativeStorage("native"))
        assertFalse(ScriptProtection.usesNativeStorage(ScriptProtection.DEFAULT_STORAGE))
    }

    @Test
    fun `describeChoice names every option`() {
        assertEquals("不加密", ScriptProtection.describeChoice(ScriptProtection.CHOICE_NONE))
        assertEquals("加密（AES）", ScriptProtection.describeChoice(ScriptProtection.CHOICE_ENCRYPT))
        assertEquals("快照（编译）", ScriptProtection.describeChoice(ScriptProtection.CHOICE_COMPILE))
        assertEquals("加密 so", ScriptProtection.describeChoice(ScriptProtection.CHOICE_NATIVE))
    }
}
