package com.stardust.view.accessibility

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import androidx.annotation.Keep
import android.view.accessibility.AccessibilityNodeInfo

import com.stardust.automator.UiObject

import java.util.ArrayList
import java.util.HashMap
import java.util.ArrayDeque
import java.util.HashSet

/**
 * Created by Stardust on 2017/3/10.
 */
@Keep
class NodeInfo private constructor() {

    var parent: NodeInfo? = null

    /** Set when a live capture stopped at a cooperative time/node/depth limit. */
    var truncated: Boolean = false

    private val children = ArrayList<NodeInfo>()
    val boundsInScreen = Rect()
    val boundsInParent = Rect()

    var id: String? = null
    private var resourcesLookup: (() -> Resources?)? = null
    private var idHexResolved = false
    var idHex: String? = null
        get() {
            if (!idHexResolved) {
                idHexResolved = true
                try {
                    val appResources = resourcesLookup?.invoke()
                    if (appResources != null && fullId != null) {
                        field = "0x" + Integer.toHexString(appResources.getIdentifier(fullId, null, null))
                    }
                } catch (error: RuntimeException) {
                    Log.d("NodeInfo", "Resource identifier unavailable for $packageName", error)
                } finally {
                    resourcesLookup = null
                }
            }
            return field
        }
        set(value) {
            idHexResolved = true
            resourcesLookup = null
            field = value
        }
    var fullId: String? = null
    var desc: String? = null
    var className: String? = null
    var packageName: String? = null
    var text: String = ""
    var depth: Int = 0
    var drawingOrder: Int = 0
    var accessibilityFocused: Boolean = false
    var checked: Boolean = false
    var clickable: Boolean = false
    var contextClickable: Boolean = false
    var dismissable: Boolean = false
    var editable: Boolean = false
    var enabled: Boolean = false
    var focusable: Boolean = false
    var longClickable: Boolean = false
    var row: Int = 0
    var column: Int = 0
    var rowCount: Int = 0
    var columnCount: Int = 0
    var rowSpan: Int = 0
    var columnSpan: Int = 0
    var selected: Boolean = false
    var scrollable: Boolean = false
    var bounds: String = ""
    var checkable: Boolean = false
    var focused: Boolean = false
    var visibleToUser: Boolean = false
    var indexInParent: Int = 0


    constructor(resources: Resources?, node: UiObject, parent: NodeInfo?) : this() {
        this.parent = parent
        resourcesLookup = { resources }
        fullId = node.viewIdResourceName
        id = simplifyId(fullId)
        desc = node.desc()
        className = node.className()
        packageName = node.packageName()
        text = node.text()

        depth = node.depth()
        drawingOrder = node.drawingOrder

        row = node.row()
        column = node.column()
        rowCount = node.rowCount()
        columnCount = node.columnCount()
        rowSpan = node.rowSpan()
        columnSpan = node.columnSpan()

        accessibilityFocused = node.isAccessibilityFocused
        checked = node.isChecked
        checkable = node.isCheckable
        clickable = node.isClickable
        contextClickable = node.isContextClickable
        dismissable = node.isDismissable
        enabled = node.isEnabled
        editable = node.isEditable
        focusable = node.isFocusable
        focused = node.focused()
        longClickable = node.isLongClickable
        selected = node.isSelected
        scrollable = node.isScrollable
        visibleToUser = node.visibleToUser()
        node.getBoundsInScreen(boundsInScreen)
        node.getBoundsInParent(boundsInParent)
        bounds = boundsToString(boundsInScreen)
        indexInParent = node.indexInParent()
    }

    private fun simplifyId(idResourceName: String?): String? {
        if (idResourceName == null)
            return null
        val i = idResourceName.indexOf('/')
        return idResourceName.substring(i + 1)
    }

    fun getChildren(): List<NodeInfo> {
        return children
    }

    /** Attach an offline snapshot. Live AccessibilityNodeInfo objects are never deserialized. */
    fun addSnapshotChild(child: NodeInfo) {
        require(child !== this && child.parent == null)
        child.parent = this
        children.add(child)
    }

    override fun toString(): String {
        return className + "{" +
                "childCount=" + children.size +
                ", mBoundsInScreen=" + boundsInScreen +
                ", mBoundsInParent=" + boundsInParent +
                ", id='" + id + '\''.toString() +
                ", desc='" + desc + '\''.toString() +
                ", packageName='" + packageName + '\''.toString() +
                ", text='" + text + '\''.toString() +
                ", depth=" + depth +
                ", drawingOrder=" + drawingOrder +
                ", accessibilityFocused=" + accessibilityFocused +
                ", checked=" + checked +
                ", clickable=" + clickable +
                ", contextClickable=" + contextClickable +
                ", dismissable=" + dismissable +
                ", editable=" + editable +
                ", enabled=" + enabled +
                ", focusable=" + focusable +
                ", longClickable=" + longClickable +
                ", row=" + row +
                ", column=" + column +
                ", rowCount=" + rowCount +
                ", columnCount=" + columnCount +
                ", rowSpan=" + rowSpan +
                ", columnSpan=" + columnSpan +
                ", selected=" + selected +
                ", scrollable=" + scrollable +
                ", bounds='" + bounds + '\''.toString() +
                ", checkable=" + checkable +
                ", focused=" + focused +
                ", visibleToUser=" + visibleToUser +
                ", parent=" + parent?.className +
                '}'.toString()
    }

    companion object {

        @JvmStatic
        fun createSnapshot(): NodeInfo = NodeInfo()

        fun boundsToString(rect: Rect): String {
            return "(${rect.left},${rect.top},${rect.right},${rect.bottom})"
        }


        /**
         * Captures a live tree with the legacy safety defaults (8 s, 15000 nodes, 256 levels).
         *
         * The timeout is cooperative: it is checked between node operations and cannot interrupt a
         * Binder call that is already blocked.
         */
        fun capture(context: Context, root: AccessibilityNodeInfo): NodeInfo {
            return capture(context, root, NodeCaptureBudget(
                    NodeCaptureBudget.DEFAULT_TIMEOUT_MILLIS,
                    NodeCaptureBudget.DEFAULT_MAX_NODES,
                    NodeCaptureBudget.DEFAULT_MAX_DEPTH))
        }

        /**
         * Captures [root] with a caller-provided budget. A budget may be shared across windows so
         * that [NodeCaptureBudget.getVisitedCount] and the node limit cover the whole capture run.
         *
         * [NodeCaptureBudget.CaptureLimitException] is a hard stop and is never treated as a
         * disappeared node.
         */
        fun capture(context: Context, root: AccessibilityNodeInfo, budget: NodeCaptureBudget): NodeInfo {
            val resourcesCache = HashMap<String, Resources?>()
            val pending = ArrayDeque<Pair<UiObject, NodeInfo?>>()
            val visited = HashSet<Any>()
            budget.check()
            pending.add(UiObject.createRoot(root) to null)
            var result: NodeInfo? = null
            try {
            // Pro also uses a queue: deep WebView/provider trees must not exhaust the stack.
            while (pending.isNotEmpty()) {
                budget.check()
                val (node, parent) = pending.removeFirst()
                budget.check()
                val key = node.unwrap()
                budget.check()
                if (!visited.add(key)) continue
                val nodeDepth = node.depth()
                budget.check()
                budget.enter(nodeDepth)
                budget.check()
                val snapshot = try {
                    NodeInfo(null, node, parent)
                } catch (error: NodeCaptureBudget.CaptureLimitException) {
                    throw error
                } catch (error: RuntimeException) {
                    if (parent == null) throw error
                    Log.d("NodeInfo", "Node disappeared during capture", error)
                    continue
                }
                budget.check()
                snapshot.resourcesLookup = {
                    val pkg = snapshot.packageName
                    if (pkg == null) null else {
                        if (!resourcesCache.containsKey(pkg)) {
                            resourcesCache[pkg] = try {
                                context.packageManager.getResourcesForApplication(pkg)
                            } catch (error: PackageManager.NameNotFoundException) {
                                null
                            }
                        }
                        resourcesCache[pkg]
                    }
                }
                if (parent == null) result = snapshot else parent.children.add(snapshot)
                budget.check()
                val childCount = try {
                    node.childCount
                } catch (error: NodeCaptureBudget.CaptureLimitException) {
                    throw error
                } catch (error: RuntimeException) {
                    Log.d("NodeInfo", "Child count unavailable during capture", error)
                    continue
                }
                budget.check()
                if (childCount < 0) {
                    Log.d("NodeInfo", "Invalid child count $childCount during capture")
                    continue
                }
                // Providers can report corrupt or enormous child counts. Bound the queue against
                // the shared node budget before requesting any child, rather than trusting it.
                budget.checkChildCapacity(pending.size, childCount)
                for (index in 0 until childCount) {
                    budget.check()
                    try {
                        node.child(index)?.let { pending.add(it to snapshot) }
                    } catch (error: NodeCaptureBudget.CaptureLimitException) {
                        throw error
                    } catch (error: RuntimeException) {
                        Log.d("NodeInfo", "Child unavailable during capture", error)
                    }
                    budget.check()
                }
            }
            } catch (limit: NodeCaptureBudget.CaptureLimitException) {
                if (result != null) {
                    result.truncated = true
                    return result
                }
                throw limit
            }
            return requireNotNull(result)
        }
    }
}
