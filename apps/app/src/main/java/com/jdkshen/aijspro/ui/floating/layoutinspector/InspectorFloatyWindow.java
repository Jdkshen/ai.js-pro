package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.Theme;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.ui.codegeneration.CodeGenerateDialog;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.floating.FullScreenFloatyWindow;
import com.stardust.app.DialogUtils;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.util.ClipboardUtil;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;

/** Shared controls keep search, selection and snapshots consistent in both inspector views. */
public abstract class InspectorFloatyWindow extends FullScreenFloatyWindow {
    final InspectorSession session;
    final boolean hierarchy;
    Context context;
    FrameLayout content;
    private PopupWindow menu;
    private LinearLayout searchPanel;
    private EditText query;
    private CheckBox matchCase;
    private TextView resultCount, selectedDetail;
    private ListView resultList;
    private final List<NodeInfo> matches;
    private int matchIndex = -1;
    private final List<Dialog> dialogs = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean closed;
    private boolean refreshingWindows;
    protected AppThemePalette palette;
    private LayoutInspector.CaptureRequest windowsRequest;
    private long windowGeneration, searchGeneration;
    private Runnable windowTimeout;
    private Future<?> searchTask;
    private final ThreadPoolExecutor searchWorker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
                Thread thread = new Thread(r, "inspector-search"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    final boolean isClosedForFallback() { return closed; }

    public final boolean openIsolated(Context owner) {
        return InspectorProcessBridge.open(owner, session.root, new ArrayList<>(session.windows), hierarchy, this);
    }

    InspectorFloatyWindow(NodeInfo root, boolean hierarchy) { this(new InspectorSession(root), hierarchy); }
    InspectorFloatyWindow(InspectorSession session, boolean hierarchy) {
        this.session = session; this.hierarchy = hierarchy; matches = session.matches;
    }
    abstract View createInspectorView();
    abstract void renderRoot();
    abstract void renderSelection(NodeInfo node);

    @Override protected View onCreateView(Context original) {
        context = new ContextThemeWrapper(original, R.style.AppTheme);
        palette = AppThemeRepository.get(original).getPalette();
        content = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (query != null && query.hasFocus()) hideKeyboard();
                    else if (searchPanel != null) hideSearch(); else close();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        content.setFocusableInTouchMode(true);
        content.addView(createInspectorView(), new FrameLayout.LayoutParams(-1, -1));
        return content;
    }
    @Override protected void onViewCreated(View view) {
        renderRoot();
        if (session.searchOpen) showSearch();
        content.getViewTreeObserver().addOnGlobalLayoutListener(this::clampSearch);
    }
    @Override protected WindowManager.LayoutParams onCreateWindowLayoutParams() {
        WindowManager.LayoutParams params = super.onCreateWindowLayoutParams();
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return params;
    }
    public void setSelectedNode(NodeInfo node) {
        selectionChanged(node);
        if (content != null) renderSelection(node);
    }
    final void selectionChanged(NodeInfo node) {
        session.selected = node;
        matchIndex = session.matchIndex = matches.indexOf(node);
        if (resultList != null) {
            resultCount.setText("节点搜索 · " + (matchIndex + 1) + "/" + matches.size() + " 个");
            if (resultList.getAdapter() != null) ((ArrayAdapter<?>) resultList.getAdapter()).notifyDataSetChanged();
        }
        updateSelectedDetail();
    }
    final void selectedByTouch(NodeInfo node, View anchor) {
        setSelectedNode(node);
        showMenuAt(anchor, node);
    }
    private void replaceRoot(NodeInfo root, String source) {
        boolean reopen = searchPanel != null;
        hideSearch();
        session.root = root;
        session.selected = null;
        session.source = source;
        session.indexRoot();
        renderRoot();
        toast(source);
        if (reopen) { showSearch(); if (!session.query.trim().isEmpty()) search(); }
    }
    private void ensureMenu() {
        if (menu != null) return;
        LinearLayout panel = card(); panel.setOrientation(LinearLayout.VERTICAL);
        String[] items = {"查看控件信息", hierarchy ? "在范围图中查看" : "在布局层次中查看",
                "节点搜索", "窗口选择", "保存布局到文件", "读取布局文件", "退出布局分析"};
        Runnable[] callbacks = {this::showProperties, this::switchView, this::showSearch,
                this::showWindows, this::saveLayout, this::showFiles, this::close};
        for (int i = 0; i < items.length; i++) {
            final Runnable callback = callbacks[i];
            TextView item = action(items[i], () -> { menu.dismiss(); callback.run(); });
            item.setTextSize(13); item.setTextColor(i == items.length - 1 ? palette.danger : palette.textPrimary);
            panel.addView(item, new LinearLayout.LayoutParams(-1, dp(38)));
        }
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true); scroll.addView(panel);
        scroll.setBackground(roundBackground(palette.surfaceElevated, 12)); scroll.setClipToOutline(true);
        menu = new PopupWindow(scroll, dp(216), -2, true);
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); menu.setOutsideTouchable(true);
        menu.setElevation(dp(6)); menu.setWindowLayoutType(FloatyWindowManger.getWindowType());
    }
    private void showMenuAtPosition(int x, int y) {
        ensureMenu(); menu.dismiss();
        int width = Math.min(dp(216), content.getWidth() - dp(16));
        int height = Math.min(dp(266), content.getHeight() - dp(16));
        menu.setWidth(width); menu.setHeight(height);
        menu.showAtLocation(content, Gravity.TOP | Gravity.LEFT,
                Math.max(0, Math.min(content.getWidth() - width, x)),
                Math.max(0, Math.min(content.getHeight() - height, y)));
    }
    private void showMenuAt(View anchor, NodeInfo node) {
        int[] location = new int[2]; content.getLocationOnScreen(location);
        Rect bounds = node.getBoundsInScreen();
        showMenuAtPosition(bounds.centerX() - dp(108), bounds.bottom - location[1]);
    }
    private NodeInfo selected() { return session.selected != null ? session.selected : session.root; }
    private void switchView() {
        boolean reopen = searchPanel != null;
        InspectorFloatyWindow next = hierarchy ? new LayoutBoundsFloatyWindow(session) : new LayoutHierarchyFloatyWindow(session);
        if (InspectorProcessBridge.isRendererProcess(context)
                && InspectorHostService.replaceInspectorWindow(this, next)) {
            return;
        }
        close(); session.searchOpen = reopen; FloatyService.addWindow(next);
    }
    private void showAdvancedCode() {
        Dialog dialog = new CodeGenerateDialog(context, session.root, selected()).build();
        show(dialog);
    }
    private void showProperties() {
        NodeInfoView properties = new NodeInfoView(context);
        LinearLayout panel = card(); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundBackground(palette.surfaceElevated, 12));
        Dialog dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout heading = new LinearLayout(context);
        heading.addView(label("控件信息 · #" + session.indices.get(selected()), 12), new LinearLayout.LayoutParams(0, dp(36), 1));
        heading.addView(action("更多", this::showAdvancedCode));
        heading.addView(action("×", dialog::dismiss)); panel.addView(heading);
        panel.addView(properties, new LinearLayout.LayoutParams(-1,
                Math.min(dp(430), context.getResources().getDisplayMetrics().heightPixels - dp(180))));
        LinearLayout footer = new LinearLayout(context); footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = label("", 11);
        count.setMaxLines(2);
        footer.addView(count, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView copy = pill("复制代码", palette.accent, palette.fabForeground, () -> {
            String code = properties.getSelectorCode();
            if (code.isEmpty()) { toast("请勾选定位属性"); return; }
            ClipboardUtil.setClip(context, code); toast("已复制定位代码");
        });
        footer.addView(copy, new LinearLayout.LayoutParams(dp(96), dp(36))); panel.addView(footer);
        properties.setNodeInfo(selected());
        if (session.properties != null) properties.setSelectedProperties(session.properties);
        Runnable update = () -> {
            session.properties = properties.getSelectedProperties();
            count.setText("已选 " + properties.getSelectedCount() + " 项\n点击右侧复制定位代码");
            copy.setAlpha(properties.getSelectedCount() == 0 ? 0.45f : 1f);
        };
        properties.setOnSelectionChangeListener(update); update.run();
        dialog.setContentView(panel);
        show(dialog);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(Math.min(dp(340), content.getWidth() - dp(24)), -2);
    }
    private void showWindows() {
        if (refreshingWindows) return;
        refreshingWindows = true; hideKeyboard();
        final long generation = ++windowGeneration;
        content.setVisibility(View.GONE);
        windowTimeout = () -> {
            if (closed || generation != windowGeneration) return;
            ++windowGeneration;
            if (windowsRequest != null) windowsRequest.cancel();
            windowsRequest = null; refreshingWindows = false;
            content.setVisibility(View.VISIBLE);
            toast("窗口刷新超时，已恢复分析界面，可重试");
        };
        main.postDelayed(windowTimeout, 20000);
        main.postDelayed(() -> {
            if (closed || generation != windowGeneration) return;
            LayoutInspector.WindowsAvailableListener callback = windows -> main.post(() -> {
                if (closed || generation != windowGeneration) return;
                if (windowTimeout != null) main.removeCallbacks(windowTimeout);
                windowsRequest = null; refreshingWindows = false;
                content.setVisibility(View.VISIBLE);
                if (windows.isEmpty()) { toast("未获取到窗口，请检查无障碍服务后重试"); return; }
                session.windows.clear(); session.windows.addAll(windows); showWindowPicker();
            });
            if (InspectorProcessBridge.isRendererProcess(context))
                windowsRequest = InspectorProcessBridge.refreshWindows(callback);
            else windowsRequest = AutoJs.getInstance().getLayoutInspector().captureVisibleWindowsRequest(callback);
        }, 180);
    }
    private void showWindowPicker() {
        final List<LayoutInspector.CapturedWindow> windows = new ArrayList<>(session.windows);
        List<String> items = new ArrayList<>();
        for (LayoutInspector.CapturedWindow window : windows) {
            NodeInfo root = window.getRoot();
            items.add("窗口 " + window.getId() + " · type=" + window.getType() + " · layer=" + window.getLayer()
                    + (window.getActive() ? " · 活动" : "") + "\n" + root.getPackageName() + "\n"
                    + root.getClassName() + " · " + root.getBounds() + " · " + flatten(root).size() + " 节点");
        }
        Dialog dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card(); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundBackground(palette.surfaceElevated, 12));
        LinearLayout heading = new LinearLayout(context);
        heading.addView(label("选择当前窗口", 14), new LinearLayout.LayoutParams(0, dp(40), 1));
        heading.addView(action("×", dialog::dismiss)); panel.addView(heading);
        ListView list = new ListView(context);
        list.setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(11); view.setTextColor(palette.textPrimary); view.setPadding(dp(8), dp(10), dp(8), dp(10));
                return view;
            }
        });
        list.setOnItemClickListener((p, view, index, id) -> {
            dialog.dismiss(); LayoutInspector.CapturedWindow window = windows.get(index);
            replaceRoot(window.getRoot(), "窗口 " + window.getId() + " · " + window.getRoot().getPackageName());
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, Math.min(dp(320), dp(windows.size() * 96))));
        dialog.setContentView(panel); show(dialog);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(Math.min(dp(340), content.getWidth() - dp(24)), -2);
    }
    private void showSearch() {
        if (searchPanel != null) { searchPanel.bringToFront(); return; }
        session.searchOpen = true;
        searchPanel = card(); searchPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(context);
        resultCount = label("节点搜索", 12);
        heading.addView(resultCount, new LinearLayout.LayoutParams(0, dp(36), 1));
        heading.addView(action("×", this::hideSearch));
        searchPanel.addView(heading);
        query = new EditText(context); query.setSingleLine(true); query.setTextSize(13);
        query.setTextColor(palette.textPrimary); query.setHintTextColor(palette.textSecondary);
        query.setHint("输入节点关键字");
        query.setBackground(roundBackground(palette.surfaceSecondary, 8));
        query.setPadding(dp(8), 0, dp(8), 0);
        query.setText(session.query);
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { session.query = s.toString(); }
            public void afterTextChanged(Editable s) {}
        });
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                search(); return true;
            }
            return false;
        });
        searchPanel.addView(query, new LinearLayout.LayoutParams(-1, dp(36)));
        LinearLayout controls = new LinearLayout(context);
        controls.setPadding(0, dp(6), 0, dp(4));
        TextView previous = pill("上一个", palette.surfaceSecondary, palette.textPrimary, () -> cycle(-1));
        TextView next = pill("下一个", palette.surfaceSecondary, palette.textPrimary, () -> cycle(1));
        TextView find = pill("搜索", palette.accent, palette.fabForeground, this::search);
        for (TextView button : Arrays.asList(previous, next, find)) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(32), 1); p.setMargins(dp(2), 0, dp(2), 0);
            controls.addView(button, p);
        }
        TextView clear = action("清除", () -> { query.setText(""); clearMatches(); setSelectedNode(null); });
        clear.setTextColor(palette.danger); controls.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(32)));
        searchPanel.addView(controls);
        LinearLayout options = new LinearLayout(context);
        TextView hint = label("文字 / 描述 / id / 类名 / 包名", 10);
        options.addView(hint, new LinearLayout.LayoutParams(0, dp(30), 1));
        matchCase = new CheckBox(context); matchCase.setText("区分大小写"); matchCase.setTextSize(12);
        matchCase.setChecked(session.matchCase);
        matchCase.setTextColor(palette.textPrimary);
        matchCase.setButtonTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        matchCase.setPadding(0, 0, 0, 0); matchCase.setMinHeight(0);
        matchCase.setOnCheckedChangeListener((v, checked) -> { session.matchCase = checked; if (!session.query.trim().isEmpty()) search(); });
        options.addView(matchCase, new LinearLayout.LayoutParams(-2, dp(30)));
        searchPanel.addView(options);
        selectedDetail = label("未选中节点 · 拖动标题可移动面板", 10); selectedDetail.setMaxLines(2);
        searchPanel.addView(selectedDetail, new LinearLayout.LayoutParams(-1, dp(34)));
        resultList = new ListView(context); resultList.setDividerHeight(dp(1));
        resultList.setOnItemClickListener((p, v, index, id) -> selectMatch(index));
        resultList.setOnItemLongClickListener((p, v, index, id) -> { selectMatch(index); showProperties(); return true; });
        searchPanel.addView(resultList, new LinearLayout.LayoutParams(-1, 0, 1));
        int width = Math.min(dp(320), context.getResources().getDisplayMetrics().widthPixels - dp(16));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, dp(280), Gravity.TOP | Gravity.LEFT);
        params.leftMargin = session.searchX < 0 ? dp(8) : (int) session.searchX;
        params.topMargin = session.searchY < 0 ? dp(66) : (int) session.searchY;
        content.addView(searchPanel, params);
        installDrag(resultCount, searchPanel);
        matchIndex = session.matchIndex;
        refreshResults(); updateSelectedDetail();
        searchPanel.post(this::clampSearch);
        content.requestFocus();
    }
    private void installDrag(View handle, View panel) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = panel.getX(); startY = panel.getY(); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    panel.setX(startX + event.getRawX() - downX);
                    panel.setY(startY + event.getRawY() - downY);
                    clampSearch();
                }
                return true;
            }
        });
    }
    private void search() {
        final long generation = ++searchGeneration;
        if (searchTask != null) searchTask.cancel(true);
        searchWorker.getQueue().clear();
        final String value = query.getText().toString();
        final boolean caseSensitive = matchCase.isChecked();
        final List<NodeInfo> nodes = new ArrayList<>(session.nodes);
        matches.clear(); matchIndex = session.matchIndex = -1; refreshResults();
        if (value.trim().isEmpty()) { setSelectedNode(null); return; }
        resultCount.setText("节点搜索 · 正在搜索…");
        searchTask = searchWorker.submit(() -> {
            List<NodeInfo> found = new ArrayList<>();
            for (NodeInfo node : nodes) {
                if (Thread.currentThread().isInterrupted()) return;
                if (NodeSelectorCode.matches(value, caseSensitive, node.getText(), node.getDesc(), node.getId(),
                        node.getFullId(), node.getClassName(), node.getPackageName())) found.add(node);
            }
            main.post(() -> {
                if (closed || searchPanel == null || generation != searchGeneration) return;
                matches.addAll(found); refreshResults();
                if (matches.isEmpty()) { setSelectedNode(null); resultCount.setText("节点搜索 · 0/0 个 · 无匹配"); }
                else selectMatch(0);
                searchPanel.post(this::clampSearch);
            });
        });
    }
    private void refreshResults() {
        if (resultList == null) return;
        resultList.setAdapter(resultAdapter());
        resultCount.setText("节点搜索 · " + (matchIndex + 1) + "/" + matches.size() + " 个");
    }
    private String nodeSummary(NodeInfo node) {
        return "#" + session.indices.get(node) + " " + shortText(node.getClassName())
                + "\nid: " + shortText(node.getId()) + "  text: " + shortText(node.getText())
                + (node.getDesc() == null || node.getDesc().isEmpty() ? "" : "\ndesc: " + shortText(node.getDesc()));
    }
    private void updateSelectedDetail() {
        if (selectedDetail != null) selectedDetail.setText(session.selected == null ?
                "未选中节点 · 拖动标题可移动面板" : nodeSummary(session.selected));
    }
    private void clampSearch() {
        if (searchPanel == null || content.getWidth() == 0) return;
        Rect visible = new Rect(); content.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2]; content.getLocationOnScreen(location);
        int height = Math.min(content.getHeight(), visible.bottom - location[1]);
        if (height <= 0) height = content.getHeight();
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) searchPanel.getLayoutParams();
        int desired = Math.min(dp(matches.isEmpty() ? 190 : 300), height - dp(8));
        int width = Math.min(dp(320), content.getWidth() - dp(16));
        if (p.height != desired || p.width != width) { p.height = desired; p.width = width; searchPanel.setLayoutParams(p); }
        searchPanel.setX(Math.max(0, Math.min(content.getWidth() - width, searchPanel.getX())));
        searchPanel.setY(Math.max(0, Math.min(height - desired, searchPanel.getY())));
        session.searchX = searchPanel.getX(); session.searchY = searchPanel.getY();
    }
    private ArrayAdapter<NodeInfo> resultAdapter() {
        return new ArrayAdapter<NodeInfo>(context, android.R.layout.simple_list_item_1, matches) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(context);
                view.setText(nodeSummary(getItem(position)));
                view.setTextSize(10); view.setTextColor(palette.textPrimary);
                view.setPadding(dp(6), dp(5), dp(6), dp(5));
                view.setBackgroundColor(position == matchIndex ? palette.accentMuted : Color.TRANSPARENT);
                return view;
            }
        };
    }
    private void clearMatches() {
        ++searchGeneration;
        if (searchTask != null) searchTask.cancel(true);
        searchWorker.getQueue().clear();
        matches.clear(); matchIndex = session.matchIndex = -1;
        refreshResults(); clampSearch();
    }
    private void cycle(int offset) {
        if (matches.isEmpty()) { search(); return; }
        selectMatch((matchIndex + offset + matches.size()) % matches.size());
    }
    private void selectMatch(int index) {
        matchIndex = session.matchIndex = index; setSelectedNode(matches.get(index));
        resultCount.setText("节点搜索 · " + (index + 1) + "/" + matches.size() + " 个");
        ((ArrayAdapter<?>) resultList.getAdapter()).notifyDataSetChanged();
        resultList.smoothScrollToPosition(index);
    }
    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && content != null) manager.hideSoftInputFromWindow(content.getWindowToken(), 0);
        if (query != null) query.clearFocus();
        if (content != null) content.requestFocus();
    }
    private void hideSearch() {
        ++searchGeneration;
        if (searchTask != null) searchTask.cancel(true);
        if (searchPanel == null) return;
        hideKeyboard(); content.removeView(searchPanel); searchPanel = null; session.searchOpen = false;
        query = null; matchCase = null; selectedDetail = null; resultCount = null; resultList = null;
    }
    private File directory() {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), "layouts");
    }
    private void saveLayout() {
        NodeInfo snapshot = session.root;
        File directory = directory();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        File file = new File(directory, "layout-" + stamp + ".json");
        toast("正在保存布局…");
        io.execute(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建布局目录");
                LayoutSnapshot.save(file, snapshot);
                main.post(() -> { if (!closed) toast("布局已保存：" + file.getName()); });
            } catch (Exception e) { error("保存失败", e); }
        });
    }
    private void showFiles() {
        io.execute(() -> {
            File[] files = directory().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) files = new File[0];
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            final File[] entries = files;
            main.post(() -> { if (!closed) showFilePicker(entries); });
        });
    }
    private void showFilePicker(File[] entries) {
        Dialog dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card(); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundBackground(palette.surfaceElevated, 12));
        LinearLayout heading = new LinearLayout(context);
        heading.addView(label("读取布局文件", 14), new LinearLayout.LayoutParams(0, dp(40), 1));
        heading.addView(action("×", dialog::dismiss)); panel.addView(heading);
        if (entries.length == 0) {
            TextView empty = label("还没有保存的布局，可以输入已有文件的路径。", 12);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, dp(70)));
        } else {
            ListView list = new ListView(context);
            list.setAdapter(new ArrayAdapter<File>(context, android.R.layout.simple_list_item_1, entries) {
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    File file = getItem(position);
                    LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView name = label(file.getName() + "\n" + (file.length() / 1024) + " KB", 11);
                    name.setOnClickListener(v -> { dialog.dismiss(); loadLayout(file); });
                    name.setOnLongClickListener(v -> { ClipboardUtil.setClip(context, file.getAbsolutePath()); toast("已复制文件路径"); return true; });
                    row.addView(name, new LinearLayout.LayoutParams(0, dp(56), 1));
                    row.addView(pill("分享", palette.surfaceSecondary, palette.textPrimary, () -> shareLayout(file)),
                            new LinearLayout.LayoutParams(dp(48), dp(32)));
                    return row;
                }
            });
            panel.addView(list, new LinearLayout.LayoutParams(-1, Math.min(dp(300), dp(entries.length * 56))));
        }
        panel.addView(label("点击文件读取 · 长按名称复制路径", 10), new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout footer = new LinearLayout(context);
        footer.addView(action("输入路径", () -> { dialog.dismiss(); show(new MaterialDialog.Builder(context).theme(Theme.LIGHT)
                .title("读取布局 JSON").input("文件绝对路径", directory().getAbsolutePath() + "/", false,
                        (d, input) -> loadLayout(new File(input.toString()))).negativeText("取消").build()); }),
                new LinearLayout.LayoutParams(0, dp(40), 1));
        footer.addView(action("复制目录", () -> { ClipboardUtil.setClip(context, directory().getAbsolutePath()); toast("已复制布局目录"); }),
                new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(footer); dialog.setContentView(panel); show(dialog);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(Math.min(dp(340), content.getWidth() - dp(24)), -2);
    }
    private void shareLayout(File file) {
        try {
            Uri uri = AppFileProvider.getUriForFile(context, file);
            Intent intent = new Intent(Intent.ACTION_SEND).setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri(file.getName(), uri));
            context.startActivity(Intent.createChooser(intent, "分享布局文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // The full-screen inspection overlay must not cover Android's share targets.
            close();
        } catch (Exception error) { error("分享失败", error); }
    }
    private void loadLayout(File file) {
        toast("正在读取布局…");
        io.execute(() -> {
            try {
                NodeInfo root = LayoutSnapshot.load(file);
                main.post(() -> { if (!closed) replaceRoot(root, "文件快照 · " + file.getName()); });
            } catch (Exception e) { error("读取失败", e); }
        });
    }
    private void error(String message, Exception error) {
        android.util.Log.w("LayoutInspector", message, error);
        main.post(() -> { if (!closed) toast(message + "：" + error.getMessage()); });
    }
    private void show(Dialog dialog) {
        if (dialog.getWindow() != null) dialog.getWindow().setType(FloatyWindowManger.getWindowType());
        dialogs.removeIf(existing -> !existing.isShowing());
        dialogs.add(dialog);
        // Dialog controllers may own onDismiss for worker cancellation; do not replace it.
        DialogUtils.showDialog(dialog);
    }
    @Override public void close() {
        if (closed) return;
        closed = true; ++windowGeneration; ++searchGeneration;
        if (windowsRequest != null) windowsRequest.cancel();
        windowsRequest = null;
        if (windowTimeout != null) main.removeCallbacks(windowTimeout);
        searchWorker.shutdownNow();
        if (menu != null) menu.dismiss();
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        if (content != null) hideSearch();
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.close();
        InspectorHostService.onInspectorWindowClosed(this);
    }
    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(context);
        panel.setBackground(roundBackground(palette.popupBackground, 12)); panel.setElevation(dp(6)); panel.setPadding(dp(8), 0, dp(8), 0);
        panel.setClipToOutline(true);
        return panel;
    }
    private TextView label(String text, int size) {
        TextView label = new TextView(context); label.setText(text); label.setTextColor(palette.textPrimary);
        label.setTextSize(size); label.setGravity(Gravity.CENTER_VERTICAL); label.setPadding(dp(6), 0, dp(6), 0); return label;
    }
    private TextView action(String text, Runnable callback) {
        TextView button = label(text, 13); button.setTextColor(palette.accent);
        button.setGravity(Gravity.CENTER); button.setMinWidth(dp(44)); button.setMinHeight(dp(42));
        button.setContentDescription(text); button.setOnClickListener(v -> callback.run()); return button;
    }
    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius));
        return background;
    }
    private TextView pill(String text, int background, int color, Runnable callback) {
        TextView button = action(text, callback); button.setTextSize(12); button.setMinHeight(0);
        button.setTextColor(color); button.setBackground(roundBackground(background, 18)); return button;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(context, value, Toast.LENGTH_SHORT).show(); }
    private String shortText(String text) {
        if (text == null) return "";
        return text.replace('\n', ' ').substring(0, Math.min(80, text.length()));
    }
    private static List<NodeInfo> flatten(NodeInfo root) {
        List<NodeInfo> result = new ArrayList<>(); ArrayDeque<NodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) { NodeInfo node = queue.removeFirst(); result.add(node); queue.addAll(node.getChildren()); }
        return result;
    }
}
