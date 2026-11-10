package com.jdkshen.aijspro.autojs.build

/**
 * task-104 ③：打包报告里「瘦身开关」相关字段名的**唯一真值**（单测钉住，避免改名漂移）。
 *
 * <p>两态都要稳定出现：开关关时写 0（而不是缺省），这样报告消费方不必区分「没有」与「0」。</p>
 */
object PackagingReportFields {
    const val OCR_REMOVED_FILES = "ocrRemovedFiles"
    const val OCR_REMOVED_BYTES = "ocrRemovedBytes"
    const val OCR_REMOVED_PREFIX = "ocrRemovedPrefix"
    const val NODE24_SKIPPED_ENTRIES = "node24SkippedEntries"
    const val NODE24_SKIPPED_BYTES = "node24SkippedBytes"
    /** task-111：warnings 数组的键名（PocPackaging 与 ApkBuilder 共用，避免第二份字面量）。 */
    const val PACKAGING_WARNINGS = "packagingWarnings"

    /** 全部字段名（测试与 UI 可遍历）。 */
    @JvmStatic
    fun all(): List<String> = listOf(
        OCR_REMOVED_FILES, OCR_REMOVED_BYTES, OCR_REMOVED_PREFIX,
        NODE24_SKIPPED_ENTRIES, NODE24_SKIPPED_BYTES
    )
}
