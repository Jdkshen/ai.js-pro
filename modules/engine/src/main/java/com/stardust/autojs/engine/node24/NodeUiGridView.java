package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;

/** &lt;grid spanCount="…"&gt; 的真实 RecyclerView；不依赖 ScriptRuntime。 */
public class NodeUiGridView extends NodeUiRecyclerView {

    public NodeUiGridView(Context context) {
        super(context);
        setLayoutManager(new GridLayoutManager(context, 1));
    }

    public NodeUiGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutManager(new GridLayoutManager(context, 1));
    }

    public NodeUiGridView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutManager(new GridLayoutManager(context, 1));
    }

    public void setSpanCount(int spanCount) {
        LayoutManager manager = getLayoutManager();
        if (manager instanceof GridLayoutManager) {
            ((GridLayoutManager) manager).setSpanCount(Math.max(1, spanCount));
        }
    }

    public int getSpanCount() {
        LayoutManager manager = getLayoutManager();
        return manager instanceof GridLayoutManager ? ((GridLayoutManager) manager).getSpanCount() : 1;
    }
}
