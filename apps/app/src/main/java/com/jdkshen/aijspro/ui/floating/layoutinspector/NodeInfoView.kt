package com.jdkshen.aijspro.ui.floating.layoutinspector

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DividerItemDecoration
import com.jdkshen.aijspro.R
import com.stardust.util.ClipboardUtil
import com.stardust.view.accessibility.NodeInfo

/** Select properties to compose a selector; long press a row to copy its raw value. */
class NodeInfoView : RecyclerView {
    private var values: Map<String, Any?> = emptyMap()
    private val selected = linkedSetOf<String>()
    private var changeListener: Runnable? = null
    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, style: Int) : super(context, attrs, style) { init() }
    private fun init() {
        layoutManager = LinearLayoutManager(context)
        addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        adapter = PropertyAdapter()
    }
    fun setNodeInfo(node: NodeInfo) {
        values = NodeProperties.read(node)
        selected.clear()
        if (node.id != null) selected.add("id")
        else if (node.text.isNotBlank()) selected.add("text")
        else if (node.className != null) selected.add("className")
        if (node.packageName != null) selected.add("packageName")
        adapter!!.notifyDataSetChanged()
        changeListener?.run()
    }
    fun setOnSelectionChangeListener(listener: Runnable) { changeListener = listener }
    fun getSelectedCount(): Int = selected.size
    fun getSelectorCode(): String = NodeSelectorCode.generate(values, selected)
    fun getSelectedProperties(): Set<String> = selected.toSet()
    fun setSelectedProperties(names: Set<String>) {
        selected.clear()
        selected.addAll(names.filter { NodeSelectorCode.selectable(it, values[it]) })
        adapter!!.notifyDataSetChanged()
        changeListener?.run()
    }

    private inner class PropertyAdapter : Adapter<PropertyHolder>() {
        override fun getItemCount() = NodeProperties.NAMES.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): PropertyHolder = PropertyHolder(
            LayoutInflater.from(context).inflate(if (type == 0) R.layout.node_info_view_header else R.layout.node_info_view_item, parent, false))
        override fun onBindViewHolder(holder: PropertyHolder, position: Int) {
            val name = if (position == 0) "属性" else NodeProperties.NAMES[position - 1]
            holder.name.text = name
            holder.value.text = if (position == 0) "值（长按复制）" else values[name]?.toString() ?: "(无)"
            holder.check?.apply {
                setOnCheckedChangeListener(null)
                isEnabled = NodeSelectorCode.selectable(name, values[name])
                isChecked = selected.contains(name)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(name) else selected.remove(name)
                    changeListener?.run()
                }
            }
            holder.itemView.setOnClickListener { if (holder.check?.isEnabled == true) holder.check.toggle() }
            holder.itemView.setOnLongClickListener {
                if (position == 0) false else {
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
}
