package com.jdkshen.aijspro.ui.floating.layoutinspector

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AppThemePalette
import com.jdkshen.aijspro.theme.AppThemeRepository
import com.stardust.util.ClipboardUtil
import com.stardust.view.accessibility.NodeInfo
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Select properties to compose a selector; long press a row to copy its raw value. */
class NodeInfoView : RecyclerView {
    private val palette: AppThemePalette = AppThemeRepository.get(context).getPalette()
    private val main = Handler(Looper.getMainLooper())
    private val divider = DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
    private var values: Map<String, Any?> = emptyMap()
    private val selected = linkedSetOf<String>()
    private var changeListener: Runnable? = null
    private var node: NodeInfo? = null
    private var valuesLoaded = false
    private var pendingSelection: Set<String>? = null
    private var selectionTouched = false
    private var readTask: Future<*>? = null
    private var generation = 0

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, style: Int) : super(context, attrs, style) { init() }

    private fun init() {
        layoutManager = LinearLayoutManager(context)
        divider.setDrawable(GradientDrawable().apply {
            setColor(palette.divider)
            setSize(0, (resources.displayMetrics.density * 1).toInt())
        })
        addItemDecoration(divider)
        // In light mode the inspector host paints a light card behind this list; in dark
        // mode paint our own popup surface so the themed text stays readable.
        setBackgroundColor(palette.popupBackground)
        adapter = PropertyAdapter()
    }

    fun setNodeInfo(node: NodeInfo) {
        generation++
        cancelRead()
        this.node = node
        values = emptyMap()
        valuesLoaded = false
        pendingSelection = null
        selectionTouched = false
        selected.clear()
        if (node.id != null) selected.add("id")
        else if (node.text.isNotBlank()) selected.add("text")
        else if (node.className != null) selected.add("className")
        if (node.packageName != null) selected.add("packageName")
        adapter!!.notifyDataSetChanged()
        changeListener?.run()
        startRead()
    }

    fun setOnSelectionChangeListener(listener: Runnable) { changeListener = listener }
    fun getSelectedCount(): Int = selected.size

    fun getSelectorCode(): String {
        ensureValuesReady()
        return NodeSelectorCode.generate(values, selected)
    }

    fun getSelectedProperties(): Set<String> = selected.toSet()

    fun setSelectedProperties(names: Set<String>) {
        selectionTouched = false
        pendingSelection = names.toSet()
        if (valuesLoaded) {
            pendingSelection = null
            applySelection(names)
        }
    }

    private fun applySelection(names: Set<String>) {
        selected.clear()
        selected.addAll(names.filter { NodeSelectorCode.selectable(it, values[it]) })
        adapter!!.notifyDataSetChanged()
        changeListener?.run()
    }

    /** Only the row state lives on the main thread; the resource-heavy idHex lookup runs here. */
    private fun startRead() {
        val current = node ?: return
        if (valuesLoaded || readTask != null) return
        val taskGeneration = generation
        try {
            readTask = READ_EXECUTOR.submit(Runnable {
                val read = try {
                    NodeProperties.read(current)
                } catch (error: Throwable) {
                    Log.w("NodeInfoView", "读取节点属性失败", error)
                    null
                }
                main.post {
                    if (taskGeneration == generation) applyRead(read)
                }
            })
        } catch (ignored: RejectedExecutionException) {
            readTask = null
        }
    }

    private fun applyRead(read: Map<String, Any?>?) {
        readTask = null
        if (read == null) return
        values = read
        valuesLoaded = true
        val pending = pendingSelection
        pendingSelection = null
        if (pending != null && !selectionTouched) {
            selected.clear()
            selected.addAll(pending.filter { NodeSelectorCode.selectable(it, values[it]) })
        }
        adapter!!.notifyDataSetChanged()
        changeListener?.run()
    }

    /**
     * Copy is a deliberate, rare user action. If the async read has not finished yet,
     * resolve it before composing so copying never silently loses the selected selector.
     */
    private fun ensureValuesReady() {
        if (valuesLoaded) return
        val current = node ?: return
        generation++
        cancelRead()
        val read = try {
            NodeProperties.read(current)
        } catch (error: Throwable) {
            Log.w("NodeInfoView", "读取节点属性失败", error)
            null
        }
        applyRead(read)
    }

    private fun cancelRead() {
        val task = readTask ?: return
        readTask = null
        task.cancel(true)
        // A queued (not yet started) task keeps its queue slot until the worker reaches it.
        // Remove it explicitly so repeated node switches cannot fill the bounded queue.
        if (task is Runnable) READ_EXECUTOR.remove(task)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!valuesLoaded) startRead()
    }

    override fun onDetachedFromWindow() {
        generation++
        cancelRead()
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun choiceColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled),
            intArrayOf()
        ),
        intArrayOf(palette.accent, palette.textDisabled, palette.textSecondary, palette.textDisabled)
    )

    private inner class PropertyAdapter : Adapter<PropertyHolder>() {
        override fun getItemCount() = NodeProperties.NAMES.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): PropertyHolder = PropertyHolder(
            LayoutInflater.from(context).inflate(
                if (type == 0) R.layout.node_info_view_header else R.layout.node_info_view_item, parent, false))
        override fun onBindViewHolder(holder: PropertyHolder, position: Int) {
            val header = position == 0
            val name = if (header) "属性" else NodeProperties.NAMES[position - 1]
            holder.name.setTextColor(if (header) palette.textSecondary else palette.textPrimary)
            holder.value.setTextColor(palette.textSecondary)
            holder.name.text = name
            holder.value.text = if (header) "值（长按复制）" else values[name]?.toString() ?: "(无)"
            holder.check?.apply {
                setOnCheckedChangeListener(null)
                setButtonTintList(choiceColors())
                isEnabled = NodeSelectorCode.selectable(name, values[name])
                isChecked = selected.contains(name)
                setOnCheckedChangeListener { _, checked ->
                    selectionTouched = true
                    pendingSelection = null
                    if (checked) selected.add(name) else selected.remove(name)
                    changeListener?.run()
                }
            }
            holder.itemView.setOnClickListener {
                if (holder.check?.isEnabled == true) holder.check.toggle()
            }
            holder.itemView.setOnLongClickListener {
                if (header) false
                else {
                    ensureValuesReady()
                    ClipboardUtil.setClip(context, "$name = ${values[name]}")
                    android.widget.Toast.makeText(context, "已复制属性", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }

    private class PropertyHolder(view: View) : ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val value: TextView = view.findViewById(R.id.value)
        val check: CheckBox? = view.findViewById(R.id.node_property_check)
    }

    private companion object {
        /**
         * One bounded daemon reader serves every inspector property dialog. At most one task
         * runs and the queue is capped, so detached/rapidly switched views cannot pile up work.
         */
        val READ_EXECUTOR: ThreadPoolExecutor = ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(2),
            ThreadFactory { runnable -> Thread(runnable, "node-properties-read").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
