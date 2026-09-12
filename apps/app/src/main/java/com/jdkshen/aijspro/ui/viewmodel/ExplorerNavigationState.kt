package com.jdkshen.aijspro.ui.viewmodel

/**
 * 页面栈：不依赖任何 View 的纯状态。
 *
 * <p>文件列表（`ExplorerView`）与后续的 Compose 实现共用同一套"进入子页 / 返回上一页"语义：
 * 当前页单独保存，历史栈只存被压入的上一页；返回时若栈空则当前页保持不变（调用方据此判断是否刷新）。
 *
 * <p>泛型是为了让状态层可以脱离 Android 模型单测（测试里用 String 当页面即可）。
 */
class ExplorerNavigationState<T>(initial: T) {

    private val history = ArrayDeque<T>()

    /** 当前页。 */
    var current: T = initial
        private set

    /** 是否还能返回上一页（写成方法，Java 侧调用更直白）。 */
    fun canGoBack(): Boolean = history.isNotEmpty()

    /** 清空历史但保留当前页（旧实现里 `Stack.clear()` 的位置）。 */
    fun clearHistory() {
        history.clear()
    }

    /** 当前深度（历史栈长度），根页为 0。 */
    val depth: Int
        get() = history.size

    /** 进入子页：把当前页压栈，当前页换成 [next]。 */
    fun push(next: T) {
        history.addLast(current)
        current = next
    }

    /**
     * 直接替换当前页但**保留历史**（例如从搜索结果跳到某个目录、或把首页换成另一个根）。
     * 与 [push] 的区别：不把旧页压栈；与 [reset] 的区别：不清空历史。
     */
    fun replaceCurrent(page: T) {
        current = page
    }

    /** 返回上一页；栈空时保持当前页并原样返回。 */
    fun back(): T {
        if (history.isNotEmpty()) {
            current = history.removeLast()
        }
        return current
    }

    /** 回到指定页并清空历史（例如从根目录重新开始）。 */
    fun reset(root: T) {
        history.clear()
        current = root
    }

    /**
     * 裁掉深层历史，只保留最近 [maxDepth] 层（防止长时间浏览后栈无限增长）。
     */
    fun trimTo(maxDepth: Int) {
        while (history.size > maxDepth) {
            history.removeFirst()
        }
    }

    /** 历史栈快照（从最早到最近），用于调试与测试。 */
    fun historySnapshot(): List<T> = history.toList()
}
