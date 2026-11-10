package com.jdkshen.aijspro.autojs.build

import com.stardust.autojs.project.ProjectConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-101 钉子：optimization.removeOcr 是**只读**开关（真值在打包侧），
 * 且它的存在/不存在都**不能改变写路径**（ProjectConfig.toJson() 逐字不变）。
 */
class RemoveOcrProjectConfigTest {
    private val base = """
        {
          "name": "demo",
          "packageName": "com.demo.app",
          "versionName": "1.0.0",
          "versionCode": 1,
          "main": "main.js",
          "optimization": { "unusedResources": false, "removeOpenCv": false }
        }
    """.trimIndent()

    private val withRemoveOcr = """
        {
          "name": "demo",
          "packageName": "com.demo.app",
          "versionName": "1.0.0",
          "versionCode": 1,
          "main": "main.js",
          "optimization": { "unusedResources": false, "removeOpenCv": false, "removeOcr": true }
        }
    """.trimIndent()

    @Test fun removeOcrIsReadButNeverWritten() {
        val without = ProjectConfig.fromJson(base)
        val with = ProjectConfig.fromJson(withRemoveOcr)

        assertFalse("未声明时必须是 false（默认行为不变）", without.isRemoveOcr)
        assertTrue("声明 removeOcr=true 时读得到", with.isRemoveOcr)

        // 写路径逐字不变：两者（以及和基准串）序列化结果必须一致，且都不含 removeOcr
        assertEquals(ProjectConfig.fromJson(base).toJson(), without.toJson())
        assertEquals("removeOcr 是只读开关，不得进 toJson：" + with.toJson(), without.toJson(), with.toJson())
        assertFalse("toJson 里不能出现 removeOcr", with.toJson().contains("removeOcr"))
        assertFalse("toJson 里不能出现 removeOcr", without.toJson().contains("removeOcr"))
    }

    @Test fun optimizationBlockIsNotPartOfTheWritePath() {
        // 事实：引擎的写路径本来就不序列化 optimization（真值在打包侧 + UI 侧）。
        // 所以 removeOcr 的存在绝不可能影响写路径 —— 这里把它钉成断言，避免以后有人误加字段。
        val plain = ProjectConfig.fromJson(base).toJson()
        val withOcr = ProjectConfig.fromJson(withRemoveOcr).toJson()
        assertFalse("engine 的 toJson 不应输出 optimization：" + plain, plain.contains("optimization"))
        assertFalse("engine 的 toJson 不应输出 removeOpenCv：" + plain, plain.contains("removeOpenCv"))
        assertEquals(plain, withOcr)
        assertEquals("只读开关不得影响写回", plain, ProjectConfig.fromJson(withRemoveOcr).toJson())
    }
}
