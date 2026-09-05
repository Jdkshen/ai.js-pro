package com.jdkshen.aijspro.ui.explorer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.color.MaterialColors;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.stardust.pio.PFiles;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.model.explorer.Explorer;
import com.jdkshen.aijspro.model.explorer.ExplorerChangeEvent;
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage;
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem;
import com.jdkshen.aijspro.model.explorer.ExplorerItem;
import com.jdkshen.aijspro.model.explorer.ExplorerPage;
import com.jdkshen.aijspro.model.explorer.ExplorerProjectPage;
import com.jdkshen.aijspro.model.explorer.ExplorerSampleItem;
import com.jdkshen.aijspro.model.explorer.ExplorerSamplePage;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.model.script.ScriptFile;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.tool.Observers;
import com.jdkshen.aijspro.ui.project.BuildActivity;
import com.jdkshen.aijspro.ui.common.ScriptLoopDialog;
import com.jdkshen.aijspro.ui.common.ScriptOperations;
import com.jdkshen.aijspro.ui.viewmodel.ExplorerItemList;
import com.jdkshen.aijspro.ui.widget.BindableViewHolder;
import com.jdkshen.aijspro.theme.widget.ThemeColorSwipeRefreshLayout;

import com.jdkshen.aijspro.workground.WrapContentGridLayoutManger;
import org.greenrobot.eventbus.Subscribe;

import java.text.SimpleDateFormat;
import java.io.File;
import java.util.Date;
import java.util.Locale;
import java.util.Stack;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Stardust on 2017/8/21.
 */

public class ExplorerView extends ThemeColorSwipeRefreshLayout implements SwipeRefreshLayout.OnRefreshListener, PopupMenu.OnMenuItemClickListener {

    private static final String LOG_TAG = "ExplorerView";

    public interface OnItemClickListener {
        void onItemClick(View view, ExplorerItem item);
    }

    public interface OnItemOperatedListener {
        void OnItemOperated(ExplorerItem item);
    }

    protected static final int VIEW_TYPE_ITEM = 0;
    protected static final int VIEW_TYPE_PAGE = 1;
    //category是类别，也即"文件", "文件夹"那两个
    protected static final int VIEW_TYPE_CATEGORY = 2;

    private static final int positionOfCategoryDir = 0;

    private ExplorerItemList mExplorerItemList = new ExplorerItemList();
    private RecyclerView mExplorerItemListView;
    private ExplorerProjectToolbar mProjectToolbar;
    private ExplorerAdapter mExplorerAdapter = new ExplorerAdapter();
    protected OnItemClickListener mOnItemClickListener;
    private Function<ExplorerItem, Boolean> mFilter;
    private OnItemOperatedListener mOnItemOperatedListener;
    protected ExplorerItem mSelectedItem;
    private Explorer mExplorer;
    private Stack<ExplorerPageState> mPageStateHistory = new Stack<>();
    private ExplorerPageState mCurrentPageState = new ExplorerPageState();
    private int mDirectorySpanSize = 2;
    private final SimpleDateFormat mItemTimestampFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private CharSequence buildBreadcrumbTitle() {
        File storage = Environment.getExternalStorageDirectory();
        File current = new File(mCurrentPageState.page.getPath());
        String storagePath = storage.getAbsolutePath();
        String currentPath = current.getAbsolutePath();
        StringBuilder title = new StringBuilder(getResources().getString(R.string.text_internal_storage));
        if (currentPath.startsWith(storagePath)) {
            String relativePath = currentPath.substring(storagePath.length());
            for (String segment : relativePath.split("[/\\\\]+")) {
                if (!segment.isEmpty()) {
                    title.append("  ›  ").append(segment);
                }
            }
        } else if (!current.getName().isEmpty()) {
            title.append("  ›  ").append(current.getName());
        }
        SpannableString breadcrumb = new SpannableString(title.toString());
        int lastSeparator = title.lastIndexOf("›");
        if (lastSeparator > 0) {
            breadcrumb.setSpan(new ForegroundColorSpan(MaterialColors.getColor(
                            this, com.google.android.material.R.attr.colorOnSurfaceVariant)),
                    0, lastSeparator + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return breadcrumb;
    }

    public ExplorerView(Context context) {
        super(context);
        init();
    }

    public ExplorerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ExplorerPage getCurrentPage() {
        return mCurrentPageState.page;
    }

    public void setRootPage(ExplorerPage page) {
        mPageStateHistory.clear();
        setCurrentPageState(new ExplorerPageState(page));
        loadItemList();
    }

    private void setCurrentPageState(ExplorerPageState currentPageState) {
        mCurrentPageState = currentPageState;
        if (mCurrentPageState.page instanceof ExplorerProjectPage) {
            mProjectToolbar.setVisibility(VISIBLE);
            mProjectToolbar.setProject(currentPageState.page.toScriptFile());
        } else {
            mProjectToolbar.setVisibility(GONE);
        }
    }

    private void saveScrollPosition() {
        if (mExplorerItemListView == null || mExplorerItemListView.getLayoutManager() == null) {
            return;
        }
        int position = ((LinearLayoutManager) mExplorerItemListView.getLayoutManager())
                .findFirstCompletelyVisibleItemPosition();
        if (position >= 0) {
            mCurrentPageState.scrollY = position;
            Log.d(LOG_TAG, "saveScrollPosition=" + position + " page=" + mCurrentPageState.page.getPath());
        }
    }

    protected void enterDirectChildPage(ExplorerPage childItemGroup) {
        saveScrollPosition();
        mPageStateHistory.push(mCurrentPageState);
        setCurrentPageState(new ExplorerPageState(childItemGroup));
        loadItemList();
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
    }

    public void setSortConfig(ExplorerItemList.SortConfig sortConfig) {
        mExplorerItemList.setSortConfig(sortConfig);
    }

    public ExplorerItemList.SortConfig getSortConfig() {
        return mExplorerItemList.getSortConfig();
    }

    public void setExplorer(Explorer explorer, ExplorerPage rootPage) {
        if (mExplorer != null)
            mExplorer.unregisterChangeListener(this);
        mExplorer = explorer;
        setRootPage(rootPage);
        mExplorer.registerChangeListener(this);
    }

    public void setExplorer(Explorer explorer, ExplorerPage rootPage, ExplorerPage currentPage) {
        if (mExplorer != null)
            mExplorer.unregisterChangeListener(this);
        mExplorer = explorer;
        mPageStateHistory.clear();
        setCurrentPageState(new ExplorerPageState(rootPage));
        mExplorer.registerChangeListener(this);
        enterChildPage(currentPage);
    }

    public void enterChildPage(ExplorerPage childPage) {
        saveScrollPosition();
        ScriptFile root = mCurrentPageState.page.toScriptFile();
        ScriptFile dir = childPage.toScriptFile();
        Stack<ScriptFile> dirs = new Stack<>();
        while (!dir.equals(root)) {
            dir = dir.getParentFile();
            if (dir == null) {
                break;
            }
            dirs.push(dir);
        }
        ExplorerDirPage parent = null;
        while (!dirs.empty()) {
            dir = dirs.pop();
            ExplorerDirPage dirPage = new ExplorerDirPage(dir, parent);
            mPageStateHistory.push(new ExplorerPageState(dirPage));
            parent = dirPage;
        }
        setCurrentPageState(new ExplorerPageState(childPage));
        loadItemList();
    }

    public void setOnItemOperatedListener(OnItemOperatedListener onItemOperatedListener) {
        mOnItemOperatedListener = onItemOperatedListener;
    }

    public boolean canGoBack() {
        return !mPageStateHistory.empty();
    }

    public void goBack() {
        setCurrentPageState(mPageStateHistory.pop());
        loadItemList();
    }

    public void setDirectorySpanSize(int directorySpanSize) {
        mDirectorySpanSize = directorySpanSize;
    }

    public void setFilter(Function<ExplorerItem, Boolean> filter) {
        mFilter = filter;
        reload();
    }

    public void reload() {
        saveScrollPosition();
        loadItemList();
    }

    private void init() {
        Log.d(LOG_TAG, "item bg = " + Integer.toHexString(ContextCompat.getColor(getContext(), R.color.item_background)));
        setOnRefreshListener(this);
        inflate(getContext(), R.layout.explorer_view, this);
        mExplorerItemListView = findViewById(R.id.explorer_item_list);
        mProjectToolbar = findViewById(R.id.project_toolbar);
        initExplorerItemListView();
    }

    private void initExplorerItemListView() {
        mExplorerItemListView.setAdapter(mExplorerAdapter);
        // 滚动停止时实时记录当前位置，任何时刻重载/返回都能恢复（不再依赖“离开时保存”）。
        mExplorerItemListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    saveScrollPosition();
                }
            }
        });
        WrapContentGridLayoutManger manager = new WrapContentGridLayoutManger(getContext(), 2);
        manager.setDebugInfo("ExplorerView");
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                //For directories
                if (position > positionOfCategoryDir
                        && position <= mExplorerItemList.groupCount()) {
                    return mDirectorySpanSize;
                }
                //For files and category
                return 2;
            }
        });
        mExplorerItemListView.setLayoutManager(manager);
    }

    @SuppressLint("CheckResult")
    private void loadItemList() {
        setRefreshing(true);
        mExplorer.fetchChildren(mCurrentPageState.page)
                .subscribeOn(Schedulers.io())
                .flatMapObservable(page -> {
                    mCurrentPageState.page = page;
                    return Observable.fromIterable(page);
                })
                .filter(f -> mFilter == null ? true : mFilter.apply(f))
                .collectInto(mExplorerItemList.cloneConfig(), ExplorerItemList::add)
                .observeOn(Schedulers.computation())
                .doOnSuccess(ExplorerItemList::sort)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> {
                    mExplorerItemList = list;
                    mExplorerAdapter.notifyDataSetChanged();
                    setRefreshing(false);
                    int scrollY = mCurrentPageState.scrollY;
                    Log.d(LOG_TAG, "loadItemList done, restore scrollY=" + scrollY);
                    if (scrollY > 0) {
                        // 立即恢复；再延迟一次兜底（等 RecyclerView 完成首次布局）
                        post(() -> mExplorerItemListView.scrollToPosition(scrollY));
                        postDelayed(() -> mExplorerItemListView.scrollToPosition(scrollY), 120);
                    }
                });
    }

    @Subscribe
    public void onExplorerChange(ExplorerChangeEvent event) {
        Log.d(LOG_TAG, "on explorer change: " + event);
        if ((event.getAction() == ExplorerChangeEvent.ALL)) {
            reload();
            return;
        }
        String currentDirPath = mCurrentPageState.page.getPath();
        String changedDirPath = event.getPage().getPath();
        ExplorerItem item = event.getItem();
        String changedItemPath = item == null ? null : item.getPath();
        if (currentDirPath.equals(changedItemPath) || (currentDirPath.equals(changedDirPath) &&
                event.getAction() == ExplorerChangeEvent.CHILDREN_CHANGE)) {
            reload();
            return;
        }
        if (currentDirPath.equals(changedDirPath)) {
            int i;
            switch (event.getAction()) {
                case ExplorerChangeEvent.CHANGE:
                    i = mExplorerItemList.update(item, event.getNewItem());
                    if (i >= 0) {
                        mExplorerAdapter.notifyItemChanged(item, i);
                    }
                    break;
                case ExplorerChangeEvent.CREATE:
                    mExplorerItemList.insertAtFront(event.getNewItem());
                    mExplorerAdapter.notifyItemInserted(event.getNewItem(), 0);
                    break;
                case ExplorerChangeEvent.REMOVE:
                    i = mExplorerItemList.remove(item);
                    if (i >= 0) {
                        mExplorerAdapter.notifyItemRemoved(item, i);
                    }
                    break;
            }
        }
    }

    @Override
    public void onRefresh() {
        mExplorer.notifyChildrenChanged(mCurrentPageState.page);
        mProjectToolbar.refresh();
    }


    public ScriptFile getCurrentDirectory() {
        return getCurrentPage().toScriptFile();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.rename:
                new ScriptOperations(getContext(), this, getCurrentPage())
                        .rename((ExplorerFileItem) mSelectedItem)
                        .subscribe(Observers.emptyObserver());
                break;
            case R.id.delete:
                new ScriptOperations(getContext(), this, getCurrentPage())
                        .delete(mSelectedItem.toScriptFile());
                break;
            case R.id.run_repeatedly:
                new ScriptLoopDialog(getContext(), mSelectedItem.toScriptFile())
                        .show();
                notifyOperated();
                break;
            case R.id.create_shortcut:
                new ScriptOperations(getContext(), this, getCurrentPage())
                        .createShortcut(mSelectedItem.toScriptFile());
                break;
            case R.id.open_by_other_apps:
                Scripts.INSTANCE.openByOtherApps(mSelectedItem.toScriptFile());
                notifyOperated();
                break;
            case R.id.send:
                Scripts.INSTANCE.send(mSelectedItem.toScriptFile());
                notifyOperated();
                break;
            case R.id.timed_task:
                new ScriptOperations(getContext(), this, getCurrentPage())
                        .timedTask(mSelectedItem.toScriptFile());
                notifyOperated();
                break;
            case R.id.action_build_apk:
                Intent intent = new Intent(getContext(), BuildActivity.class);
                intent.putExtra(BuildActivity.EXTRA_SOURCE, mSelectedItem.getPath());
                if (!(getContext() instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                getContext().startActivity(intent);
                notifyOperated();
                break;
            case R.id.action_sort_by_date:
                sort(ExplorerItemList.SORT_TYPE_DATE);
                break;
            case R.id.action_sort_by_type:
                sort(ExplorerItemList.SORT_TYPE_TYPE);
                break;
            case R.id.action_sort_by_name:
                sort(ExplorerItemList.SORT_TYPE_NAME);
                break;
            case R.id.action_sort_by_size:
                sort(ExplorerItemList.SORT_TYPE_SIZE);
                break;
            case R.id.reset:
                Explorers.Providers.workspace().resetSample(mSelectedItem.toScriptFile())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(ignored -> {
                            Snackbar.make(this, R.string.text_reset_succeed, Snackbar.LENGTH_SHORT).show();
                        }, Observers.toastMessage());
                break;
            default:
                return false;
        }
        return true;
    }

    protected void notifyOperated() {
        if (mOnItemOperatedListener != null) {
            mOnItemOperatedListener.OnItemOperated(mSelectedItem);
        }
    }

    @SuppressLint("CheckResult")
    private void sort(final int sortType) {
        setRefreshing(true);
        Observable.fromCallable(() -> {
            mExplorerItemList.sortAll(sortType);
            return mExplorerItemList;
        })

                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(o -> {
                    mExplorerAdapter.notifyDataSetChanged();
                    setRefreshing(false);
                });
    }


    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mExplorer != null)
            mExplorer.registerChangeListener(this);

    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mExplorer.unregisterChangeListener(this);
    }


    protected BindableViewHolder<?> onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ITEM) {
            return new ExplorerItemViewHolder(inflater.inflate(R.layout.script_file_list_file, parent, false));
        } else if (viewType == VIEW_TYPE_PAGE) {
            return new ExplorerPageViewHolder(inflater.inflate(R.layout.script_file_list_directory, parent, false));
        } else {
            return new CategoryViewHolder(inflater.inflate(R.layout.script_file_list_category, parent, false));
        }
    }

    protected RecyclerView getExplorerItemListView() {
        return mExplorerItemListView;
    }

    private class ExplorerAdapter extends RecyclerView.Adapter<BindableViewHolder<?>> {

        @Override
        public BindableViewHolder<?> onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            return ExplorerView.this.onCreateViewHolder(inflater, parent, viewType);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(BindableViewHolder<?> holder, int position) {
            BindableViewHolder bindableViewHolder = (BindableViewHolder) holder;
            if (position == positionOfCategoryDir) {
                bindableViewHolder.bind(true, position);
                return;
            }
            if (position <= mExplorerItemList.groupCount()) {
                bindableViewHolder.bind(mExplorerItemList.getItemGroup(position - 1), position);
                return;
            }
            bindableViewHolder.bind(mExplorerItemList.getItem(
                    position - mExplorerItemList.groupCount() - 1), position);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == positionOfCategoryDir) {
                return VIEW_TYPE_CATEGORY;
            } else if (position <= mExplorerItemList.groupCount()) {
                return VIEW_TYPE_PAGE;
            } else {
                return VIEW_TYPE_ITEM;
            }
        }

        int getItemPosition(ExplorerItem item, int i) {
            if (item instanceof ExplorerPage) {
                return i + positionOfCategoryDir + 1;
            }
            return i + mExplorerItemList.groupCount() + 1;
        }

        public void notifyItemChanged(ExplorerItem item, int i) {
            notifyItemChanged(getItemPosition(item, i));
        }

        public void notifyItemRemoved(ExplorerItem item, int i) {
            notifyItemRemoved(getItemPosition(item, i));
        }

        public void notifyItemInserted(ExplorerItem item, int i) {
            notifyItemInserted(getItemPosition(item, i));
        }

        @Override
        public int getItemCount() {
            return mExplorerItemList.count() + 1;
        }
    }

    protected class ExplorerItemViewHolder extends BindableViewHolder<ExplorerItem> {

        TextView mName;
        TextView mFirstChar;
        TextView mDesc;
        View mOptions;
        View mRun;

        GradientDrawable mFirstCharBackground;
        private ExplorerItem mExplorerItem;

        ExplorerItemViewHolder(View itemView) {
            super(itemView);
            mName = itemView.findViewById(R.id.name);
            mFirstChar = itemView.findViewById(R.id.first_char);
            mDesc = itemView.findViewById(R.id.desc);
            mOptions = itemView.findViewById(R.id.more);
            mRun = itemView.findViewById(R.id.run);
            mFirstCharBackground = (GradientDrawable) mFirstChar.getBackground();
            itemView.setOnClickListener(v -> onItemClick());
            mRun.setOnClickListener(v -> run());
            mOptions.setOnClickListener(v -> showOptionMenu());
        }

        @Override
        public void bind(ExplorerItem item, int position) {
            mExplorerItem = item;
            mName.setText(ExplorerViewHelper.getDisplayName(item));
            mDesc.setText(getResources().getString(R.string.text_file_modified,
                    PFiles.getHumanReadableSize(item.getSize()),
                    mItemTimestampFormat.format(new Date(item.lastModified()))));
            mFirstChar.setText(ExplorerViewHelper.getIconText(item));
            mFirstCharBackground.setColor(ExplorerViewHelper.getIconColor(item));
            itemView.findViewById(R.id.file_code_icon).setVisibility(
                    ExplorerViewHelper.isJavaScript(item) ? VISIBLE : GONE);
            mRun.setVisibility(item.isExecutable() ? VISIBLE : GONE);
        }

        void onItemClick() {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(itemView, mExplorerItem);
            }
            notifyOperated();
        }

        void run() {
            Scripts.INSTANCE.run(new ScriptFile(mExplorerItem.getPath()));
            notifyOperated();
        }

        void showOptionMenu() {
            mSelectedItem = mExplorerItem;
            PopupMenu popupMenu = new PopupMenu(getContext(), mOptions);
            popupMenu.inflate(R.menu.menu_script_options);
            Menu menu = popupMenu.getMenu();
            if (!mExplorerItem.isExecutable()) {
                menu.removeItem(R.id.run_repeatedly);
                menu.removeItem(R.id.more);
            }
            if (!mExplorerItem.canDelete()) {
                menu.removeItem(R.id.delete);
            }
            if (!mExplorerItem.canRename()) {
                menu.removeItem(R.id.rename);
            }
            if (!(mExplorerItem instanceof ExplorerSampleItem)) {
                menu.removeItem(R.id.reset);
            }
            popupMenu.setOnMenuItemClickListener(ExplorerView.this);
            popupMenu.show();
        }
    }

    protected class ExplorerPageViewHolder extends BindableViewHolder<ExplorerPage> {

        public TextView mName;

        public TextView mDesc;

        public View mOptions;

        public ImageView mIcon;

        private ExplorerPage mExplorerPage;

        ExplorerPageViewHolder(View itemView) {
            super(itemView);
            mName = itemView.findViewById(R.id.name);
            mDesc = itemView.findViewById(R.id.desc);
            mOptions = itemView.findViewById(R.id.more);
            mIcon = itemView.findViewById(R.id.icon);
            itemView.setOnClickListener(v -> onItemClick());
            mOptions.setOnClickListener(v -> showOptionMenu());
        }

        @Override
        public void bind(ExplorerPage data, int position) {
            mName.setText(ExplorerViewHelper.getDisplayName(data));
            mDesc.setText(getResources().getString(R.string.text_directory_modified,
                    mItemTimestampFormat.format(new Date(data.lastModified()))));
            boolean isProject = data instanceof ExplorerProjectPage
                    || data instanceof ExplorerSamplePage;
            mIcon.setBackgroundResource(isProject
                    ? R.drawable.circle_project
                    : R.drawable.circle_folder);
            mIcon.setImageResource(isProject
                    ? R.drawable.ic_project_compass_24dp
                    : R.drawable.ic_folder_outline_24dp);
            mOptions.setVisibility(data instanceof ExplorerSamplePage ? GONE : VISIBLE);
            mExplorerPage = data;

        }

        void onItemClick() {
            enterDirectChildPage(mExplorerPage);
        }

        void showOptionMenu() {
            mSelectedItem = mExplorerPage;
            PopupMenu popupMenu = new PopupMenu(getContext(), mOptions);
            popupMenu.inflate(R.menu.menu_dir_options);
            popupMenu.setOnMenuItemClickListener(ExplorerView.this);
            popupMenu.show();
        }
    }

    class CategoryViewHolder extends BindableViewHolder<Boolean> {

        TextView mTitle;

        ImageView mSort;

        ImageView mSortOrder;

        ImageView mGoBack;

        ImageView mArrow;

        private boolean mIsDir;

        CategoryViewHolder(View itemView) {
            super(itemView);
            mTitle = itemView.findViewById(R.id.title);
            mSort = itemView.findViewById(R.id.sort);
            mSortOrder = itemView.findViewById(R.id.order);
            mGoBack = itemView.findViewById(R.id.back);
            mArrow = itemView.findViewById(R.id.collapse);
            mSortOrder.setOnClickListener(v -> changeSortOrder());
            mSort.setOnClickListener(v -> showSortOptions());
            mGoBack.setOnClickListener(v -> back());
            itemView.findViewById(R.id.title_container).setOnClickListener(v -> collapseOrExpand());
        }

        @Override
        public void bind(Boolean isDirCategory, int position) {
            if (isDirCategory) {
                mTitle.setText(buildBreadcrumbTitle());
            } else {
                mTitle.setText(R.string.text_file);
            }
            mIsDir = isDirCategory;
            mArrow.setVisibility(isDirCategory ? GONE : VISIBLE);
            mGoBack.setVisibility(isDirCategory ? VISIBLE : GONE);
            mGoBack.setEnabled(canGoBack());
            mGoBack.setAlpha(canGoBack() ? 1f : 0.38f);
            if (isDirCategory) {
                mArrow.setRotation(mCurrentPageState.dirsCollapsed ? -90 : 0);
                mSortOrder.setImageResource(mExplorerItemList.isDirSortedAscending() ?
                        R.drawable.ic_sort_ascending_24dp : R.drawable.ic_sort_descending_24dp);
            } else {
                mArrow.setRotation(mCurrentPageState.filesCollapsed ? -90 : 0);
                mSortOrder.setImageResource(mExplorerItemList.isFileSortedAscending() ?
                        R.drawable.ic_sort_ascending_24dp : R.drawable.ic_sort_descending_24dp);
            }
        }

        void changeSortOrder() {
            boolean ascending = !mExplorerItemList.isDirSortedAscending();
            mExplorerItemList.setAllSortedAscending(ascending);
            mSortOrder.setImageResource(ascending
                    ? R.drawable.ic_sort_ascending_24dp
                    : R.drawable.ic_sort_descending_24dp);
            sort(mExplorerItemList.getDirSortType());
        }

        void showSortOptions() {
            PopupMenu popupMenu = new PopupMenu(getContext(), mSort);
            popupMenu.inflate(R.menu.menu_sort_options);
            popupMenu.setOnMenuItemClickListener(ExplorerView.this);
            popupMenu.show();

        }

        void back() {
            if (canGoBack()) {
                goBack();
            }
        }

        void collapseOrExpand() {
            // The Pro-style explorer uses a single combined list: directories first, then files.
        }
    }

    private static class ExplorerPageState {

        ExplorerPage page;

        boolean dirsCollapsed;

        boolean filesCollapsed;

        int scrollY;

        ExplorerPageState() {
        }

        ExplorerPageState(ExplorerPage page) {
            this.page = page;
        }
    }
}
