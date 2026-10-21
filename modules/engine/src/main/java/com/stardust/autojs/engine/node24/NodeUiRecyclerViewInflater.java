package com.stardust.autojs.engine.node24;

import android.view.ViewGroup;

import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.ViewCreator;
import com.stardust.autojs.core.ui.inflater.inflaters.BaseViewInflater;

import java.util.Map;

/** grid/list 的节点属性：spanCount / orientation；其余走 BaseViewInflater。 */
public class NodeUiRecyclerViewInflater extends BaseViewInflater<NodeUiRecyclerView> {

    private final boolean grid;

    public NodeUiRecyclerViewInflater(ResourceParser parser, boolean grid) {
        super(parser);
        this.grid = grid;
    }

    @Override
    public boolean setAttr(NodeUiRecyclerView view, String attr, String value, ViewGroup parent,
                           Map<String, String> attrs) {
        if ("spanCount".equals(attr) && view instanceof NodeUiGridView) {
            try {
                ((NodeUiGridView) view).setSpanCount(Integer.parseInt(value));
            } catch (Throwable ignored) {
                // 非法 spanCount 保持默认 1，不影响整页 inflate。
            }
            return true;
        }
        if ("orientation".equals(attr)) {
            int orientation = "horizontal".equalsIgnoreCase(value)
                    ? android.widget.LinearLayout.HORIZONTAL
                    : android.widget.LinearLayout.VERTICAL;
            view.setOrientation(orientation);
            return true;
        }
        return super.setAttr(view, attr, value, parent, attrs);
    }

    @Override
    public ViewCreator<? super NodeUiRecyclerView> getCreator() {
        return (context, attrs) -> grid ? new NodeUiGridView(context) : new NodeUiListView(context);
    }
}
