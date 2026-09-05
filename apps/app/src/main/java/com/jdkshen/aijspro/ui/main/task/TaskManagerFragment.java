package com.jdkshen.aijspro.ui.main.task;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.ui.main.ViewPagerFragment;
import com.jdkshen.aijspro.ui.widget.SimpleAdapterDataObserver;

import com.jdkshen.aijspro.ui.widget.SimpleAdapterDataObserver;

/**
 * Created by Stardust on 2017/3/24.
 */
public class TaskManagerFragment extends ViewPagerFragment {

    TaskListRecyclerView mTaskListRecyclerView;

    View mNoRunningScriptNotice;

    SwipeRefreshLayout mSwipeRefreshLayout;


    public TaskManagerFragment() {
        super(45);
        setArguments(new Bundle());
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_task_manager, container, false);
        mTaskListRecyclerView = view.findViewById(R.id.task_list);
        mNoRunningScriptNotice = view.findViewById(R.id.notice_no_running_script);
        mSwipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        setUpViews();
        return view;
    }

    void setUpViews() {
        init();
        final boolean noRunningScript = mTaskListRecyclerView.getAdapter().getItemCount() == 0;
        mNoRunningScriptNotice.setVisibility(noRunningScript ? View.VISIBLE : View.GONE);
    }

    private void init() {
        mTaskListRecyclerView.getAdapter().registerAdapterDataObserver(new SimpleAdapterDataObserver() {

            @Override
            public void onSomethingChanged() {
                final boolean noRunningScript = mTaskListRecyclerView.getAdapter().getItemCount() == 0;
                mTaskListRecyclerView.postDelayed(() -> {
                    if (mNoRunningScriptNotice == null)
                        return;
                    mNoRunningScriptNotice.setVisibility(noRunningScript ? View.VISIBLE : View.GONE);
                }, 150);
            }

        });
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            mTaskListRecyclerView.refresh();
            mTaskListRecyclerView.postDelayed(() -> {
                if (mSwipeRefreshLayout != null)
                    mSwipeRefreshLayout.setRefreshing(false);
            }, 800);
        });
    }

    @Override
    protected void onFabClick(FloatingActionButton fab) {
        AutoJs.getInstance().getScriptEngineService().stopAll();
    }

    @Override
    public boolean onBackPressed(Activity activity) {
        return false;
    }
}
