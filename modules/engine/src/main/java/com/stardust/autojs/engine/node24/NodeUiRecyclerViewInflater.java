package com.stardust.autojs.engine.node24;

import android.util.Log;
import android.view.ViewGroup;

import com.stardust.autojs.core.ui.inflater.ProjectResources;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.ViewCreator;
import com.stardust.autojs.core.ui.inflater.inflaters.BaseViewInflater;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * grid/list 的节点属性：spanCount / orientation / itemLayout(itemLayoutId)；其余走 BaseViewInflater。
 *
 * <p>{@code itemLayout="@layout/grid_item"} / {@code itemLayoutId="2131427356"} 走工程资源表解析
 * （bug 2.4：layoutId 与名字都要能解析）；解析不出来时输出带「布局不存在」的可执行诊断，而不是静默丢属性。
 */
public class NodeUiRecyclerViewInflater extends BaseViewInflater<NodeUiRecyclerView> {

    private static final String TAG = "NodeUiRecyclerViewInfl";

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
        if ("itemLayout".equals(attr) || "itemLayoutId".equals(attr)) {
            setItemLayout(view, value);
            return true;
        }
        return super.setAttr(view, attr, value, parent, attrs);
    }

    /**
     * item 布局引用解析：{@code @layout/x}、{@code x}、{@code x.xml} 或数字 layoutId
     * （{@code ui.R.layout.x} / {@code @2131427356}）。读到的 XML 直接作为 item 模板；
     * 找不到时打「布局不存在」诊断（与 {@link NodeUiWidgetInflater#layoutMissingMessage} 一致）。
     */
    private void setItemLayout(NodeUiRecyclerView view, String value) {
        ProjectResources resources = getResourceParser() == null ? null : getResourceParser().getProjectResources();
        String name = NodeUiWidgetInflater.resolveLayoutName(value, resources);
        File file = name == null || resources == null
                ? null : resources.fileOf(ProjectResources.TYPE_LAYOUT, name);
        if (file == null || !file.isFile()) {
            Log.w(TAG, NodeUiWidgetInflater.layoutMissingMessage(value, resources));
            return;
        }
        try {
            view.setItemTemplate(readText(file));
        } catch (Exception error) {
            Log.w(TAG, "读取 item 布局失败：" + file, error);
        }
    }

    /** 读 res/layout xml 文本（不用 java.nio.file，低版本 Android 上没有）。 */
    private static String readText(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        int offset = 0;
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while (offset < bytes.length && (read = input.read(bytes, offset, bytes.length - offset)) > 0) {
                offset += read;
            }
        }
        return new String(bytes, 0, offset, StandardCharsets.UTF_8);
    }

    @Override
    public ViewCreator<? super NodeUiRecyclerView> getCreator() {
        return (context, attrs) -> grid ? new NodeUiGridView(context) : new NodeUiListView(context);
    }
}
