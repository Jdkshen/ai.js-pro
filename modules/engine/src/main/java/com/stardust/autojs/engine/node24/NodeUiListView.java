package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

/** &lt;list&gt; 的真实 RecyclerView；不依赖 ScriptRuntime。 */
public class NodeUiListView extends NodeUiRecyclerView {

    public NodeUiListView(Context context) {
        super(context);
        setLayoutManager(new LinearLayoutManager(context));
    }

    public NodeUiListView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutManager(new LinearLayoutManager(context));
    }

    public NodeUiListView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutManager(new LinearLayoutManager(context));
    }
}
