package com.jdkshen.aijspro.engine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.stardust.autojs.engine.node24.NodeUiBridgeFacade;
import com.stardust.autojs.engine.node24.NodeUiProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Transparent dialog Activity in :nodeui (:node24 dialogs call it through Binder).
 *
 * <p>两种结果通道：
 * <ul>
 *   <li>一次性方法（alert/confirm/prompt/select/single/multi/input）：用户操作后通过
 *       {@link NodeUiBridgeService#reportDialogResult(String, String)} 回一个最终 JSON；</li>
 *   <li>buildDialog/showDialog：{@code async:true} 时先回 {@code {shown:true}} 让 Node 侧
 *       Promise 立即 resolve，之后的事件通过 {@link NodeUiBridgeFacade#ACTION_DIALOG_EVENT}
 *       广播回 :node24；{@code setProgress}/{@code dismiss} 则走
 *       {@link NodeUiBridgeFacade#ACTION_DIALOG_CONTROL} 控制广播。</li>
 * </ul>
 */
public class NodeUiDialogActivity extends Activity {

    private static final String TAG = "NodeUiDialog";
    private static final java.util.Set<NodeUiDialogActivity> ACTIVE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static void closeAll() {
        for (NodeUiDialogActivity activity : new java.util.ArrayList<>(ACTIVE)) activity.finish();
    }
    public static void closeOwner(String owner) {
        for (NodeUiDialogActivity activity : new java.util.ArrayList<>(ACTIVE)) {
            if (owner.equals(activity.mOwner)) activity.finish();
        }
    }
    private String mOwner = "";
    private boolean mFinished;
    private String mSession = "";
    private String mToken = "";
    private boolean mAsync;
    private String mDialogType = "alert";
    private AlertDialog mDialog;
    private BroadcastReceiver mControlReceiver;
    private ProgressBar mProgressBar;
    private TextView mProgressText;
    private int mProgressMax = 100;
    private int mProgressValue;
    private boolean mShowMinMax;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ACTIVE.add(this);
        mSession = getIntent().getStringExtra(NodeUiProtocol.K_SESSION);
        if (mSession == null) {
            mSession = "";
        }
        String raw = getIntent().getStringExtra(NodeUiProtocol.K_REQUEST);
        try {
            JSONObject request = new JSONObject(raw == null ? "{}" : raw);
            mOwner = request.optString("owner", "");
            mToken = request.optString("token", "");
            mAsync = request.optBoolean("async", false);
            if (mAsync && mToken.isEmpty()) {
                throw new IllegalArgumentException("异步对话框缺少 token");
            }
            show(request);
            if (mDialog != null) mDialog.setOnKeyListener((dialog, keyCode, event) -> stopForVolume(event));
        } catch (Throwable error) {
            Log.w(TAG, "show dialog failed", error);
            fail(error);
        }
    }

    private void show(JSONObject request) throws Exception {
        mDialogType = resolveDialogType(request);
        String title = request.optString("title", "");
        String message = request.optString("content", request.optString("message", ""));
        boolean cancelable = request.optBoolean("cancelable", true);
        String positive = request.optString("positive", "");
        String negative = request.optString("negative", "");
        String neutral = request.optString("neutral", "");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (!title.isEmpty()) {
            builder.setTitle(title);
        }
        if (!message.isEmpty()) {
            builder.setMessage(message);
        }
        builder.setCancelable(cancelable);

        // task-161：XML 布局描述协议的自定义内容区（复用浮窗 inflateInlineLayout 管线）。
        String customViewXml = request.optString("customView",
                request.optString("customViewXml", "")).trim();
        final boolean hasCustomView = !customViewXml.isEmpty();
        if (hasCustomView) {
            try {
                android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(
                        this, android.R.style.Theme_Material_Light_NoActionBar);
                android.view.View content =
                        com.stardust.autojs.engine.node24.NodeUiHost.inflateInlineLayout(
                                themed, customViewXml);
                if (content == null) {
                    throw new IllegalStateException("inflateInlineLayout 返回空 View");
                }
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(content, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                builder.setView(scroll);
                bindCustomViewEvents(content, customViewXml);
            } catch (Throwable error) {
                throw new IllegalArgumentException("dialogs.customView 布局失败："
                        + (error.getMessage() == null ? error.toString() : error.getMessage()), error);
            }
        }

        final EditText input;
        if ("input".equals(mDialogType) || "prompt".equals(mDialogType)) {
            input = buildInputView(request);
            builder.setView(input);
        } else {
            input = null;
        }

        final String[] labels;
        final int[] singleSelected;
        final boolean[] multiChecked;
        if (isChoiceType(mDialogType)) {
            labels = requireItems(request);
            singleSelected = new int[]{readSingleSelectedIndex(request, labels.length)};
            multiChecked = readMultiSelectedIndices(request, labels.length);
        } else {
            labels = new String[0];
            singleSelected = new int[]{-1};
            multiChecked = new boolean[0];
        }

        if ("select".equals(mDialogType)) {
            builder.setItems(labels, (dialog, which) -> handleItemSelected(which, labels));
        } else if ("single_choice".equals(mDialogType)) {
            builder.setSingleChoiceItems(labels, singleSelected[0],
                    (dialog, which) -> singleSelected[0] = which);
        } else if ("multi_choice".equals(mDialogType)) {
            builder.setMultiChoiceItems(labels, multiChecked,
                    (dialog, which, isChecked) -> multiChecked[which] = isChecked);
        } else if ("progress".equals(mDialogType)) {
            builder.setView(buildProgressView(request));
        }

        boolean showPositive = !"select".equals(mDialogType);
        if ("progress".equals(mDialogType)) {
            showPositive = request.has("positive");
        }
        if (hasCustomView && !request.has("positive")) {
            showPositive = false;
        }
        if (showPositive) {
            builder.setPositiveButton(positive.isEmpty() ? "确定" : positive,
                    (dialog, which) -> handlePositive(input, labels, singleSelected, multiChecked));
        }

        boolean showNegative = request.has("negative")
                || "confirm".equals(mDialogType)
                || "single_choice".equals(mDialogType)
                || "multi_choice".equals(mDialogType)
                || "select".equals(mDialogType)
                || "input".equals(mDialogType)
                || "prompt".equals(mDialogType);
        if ("progress".equals(mDialogType)) {
            showNegative = request.has("negative");
        }
        if (showNegative) {
            builder.setNegativeButton(negative.isEmpty() ? "取消" : negative,
                    (dialog, which) -> handleNegative());
        }
        if (!neutral.isEmpty()) {
            builder.setNeutralButton(neutral, (dialog, which) -> handleNeutral());
        }
        builder.setOnCancelListener(dialog -> handleCancel());

        AlertDialog dialog = builder.create();
        mDialog = dialog;
        dialog.show();
        dialog.setCanceledOnTouchOutside(cancelable
                && request.optBoolean("canceledOnTouchOutside", true));

        if (mAsync) {
            registerControlReceiver();
            reportAck();
        }
        Log.i(TAG, "dialog shown session=" + mSession + " type=" + mDialogType
                + " async=" + mAsync + " title=" + title);
    }

    private String resolveDialogType(JSONObject request) {
        if (request.has("customView") || request.has("customViewXml")) {
            return "custom";
        }
        String type = request.optString("dialogType", "");
        if (type.isEmpty()) {
            type = request.optString("type", "");
        }
        if ("alert".equals(type) || "confirm".equals(type) || "prompt".equals(type)
                || "select".equals(type) || "single_choice".equals(type)
                || "singleChoice".equals(type) || "multi_choice".equals(type)
                || "multiChoice".equals(type) || "input".equals(type)
                || "progress".equals(type)) {
            if ("singleChoice".equals(type)) {
                return "single_choice";
            }
            if ("multiChoice".equals(type)) {
                return "multi_choice";
            }
            return type;
        }
        if (request.has("progress")) {
            return "progress";
        }
        if (request.has("items")) {
            String mode = request.optString("itemsSelectMode", "");
            if ("multi".equals(mode) || "multiChoice".equals(mode)) {
                return "multi_choice";
            }
            if ("single".equals(mode) || "singleChoice".equals(mode)) {
                return "single_choice";
            }
            return "select";
        }
        if (request.has("inputPrefill") || request.has("inputHint")) {
            return "input";
        }
        if (request.has("positive") && request.has("negative")) {
            return "confirm";
        }
        return "alert";
    }

    private static boolean isChoiceType(String type) {
        return "select".equals(type) || "single_choice".equals(type)
                || "multi_choice".equals(type);
    }

    private String[] requireItems(JSONObject request) {
        JSONArray array = request.optJSONArray("items");
        if (array == null || array.length() == 0) {
            throw new IllegalArgumentException(mDialogType + " 对话框缺少 items");
        }
        String[] labels = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            labels[i] = array.optString(i, "");
        }
        return labels;
    }

    private int readSingleSelectedIndex(JSONObject request, int count) {
        Object raw = request.opt("itemsSelectedIndex");
        int index = -1;
        if (raw instanceof Number) {
            index = ((Number) raw).intValue();
        } else if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            if (array.length() > 0) {
                index = array.optInt(0, -1);
            }
        }
        return index >= 0 && index < count ? index : -1;
    }

    private boolean[] readMultiSelectedIndices(JSONObject request, int count) {
        boolean[] checked = new boolean[count];
        Object raw = request.opt("itemsSelectedIndex");
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) {
                int index = array.optInt(i, -1);
                if (index >= 0 && index < count) {
                    checked[index] = true;
                }
            }
        } else if (raw instanceof Number) {
            int index = ((Number) raw).intValue();
            if (index >= 0 && index < count) {
                checked[index] = true;
            }
        }
        return checked;
    }

    private EditText buildInputView(JSONObject request) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        String prefill = request.optString("inputPrefill",
                request.optString("defaultValue", ""));
        input.setText(prefill);
        input.setSelection(input.getText().length());
        String hint = request.optString("inputHint", request.optString("hint", ""));
        if (!hint.isEmpty()) {
            input.setHint(hint);
        }
        return input;
    }

    private ViewGroup buildProgressView(JSONObject request) {
        JSONObject progress = request.optJSONObject("progress");
        int max = progress == null ? 100 : progress.optInt("max", 100);
        boolean horizontal = progress != null && progress.optBoolean("horizontal", false);
        mShowMinMax = progress != null && progress.optBoolean("showMinMax", false);
        mProgressMax = max;
        mProgressValue = progress == null ? 0 : progress.optInt("value", 0);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        if (max < 0) {
            mProgressBar = new ProgressBar(this, null,
                    horizontal ? android.R.attr.progressBarStyleHorizontal
                            : android.R.attr.progressBarStyle);
            mProgressBar.setIndeterminate(true);
        } else {
            mProgressBar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            mProgressBar.setIndeterminate(false);
            mProgressBar.setMax(Math.max(1, max));
            mProgressBar.setProgress(clampProgress(mProgressValue));
        }
        layout.addView(mProgressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mProgressText = new TextView(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = Math.max(1, padding / 2);
        layout.addView(mProgressText, textParams);
        updateProgressText();
        return layout;
    }

    private int clampProgress(int value) {
        if (mProgressMax <= 0) {
            return Math.max(0, value);
        }
        return Math.max(0, Math.min(mProgressMax, value));
    }

    private void updateProgressText() {
        if (mProgressText == null) {
            return;
        }
        if (mProgressMax <= 0) {
            mProgressText.setText("处理中…");
        } else if (mShowMinMax) {
            mProgressText.setText(mProgressValue + " / " + mProgressMax);
        } else {
            int percent = Math.round(mProgressValue * 100f / mProgressMax);
            mProgressText.setText(percent + "%");
        }
    }

    private void applyProgress(int value) {
        if (mProgressBar == null || mProgressMax <= 0) {
            return;
        }
        mProgressValue = clampProgress(value);
        mProgressBar.setProgress(mProgressValue);
        updateProgressText();
    }

    private void handlePositive(EditText input, String[] labels, int[] singleSelected,
                                boolean[] multiChecked) {
        if (mFinished) {
            return;
        }
        if ("input".equals(mDialogType) || "prompt".equals(mDialogType)) {
            String text = input == null ? "" : input.getText().toString();
            if (mAsync) {
                dispatchAndFinish("input", payload("value", text));
            } else {
                reportFinal(result("input", false, text));
            }
            return;
        }
        if ("multi_choice".equals(mDialogType)) {
            JSONArray indices = new JSONArray();
            JSONArray texts = new JSONArray();
            for (int i = 0; i < multiChecked.length; i++) {
                if (multiChecked[i]) {
                    indices.put(i);
                    texts.put(labels[i]);
                }
            }
            if (mAsync) {
                dispatchAndFinish("multi_choice", multiPayload(indices, texts));
            } else {
                reportFinal(multiResult(indices, texts, false));
            }
            return;
        }
        if ("single_choice".equals(mDialogType)) {
            int index = singleSelected[0];
            if (mAsync) {
                dispatchAndFinish("single_choice", choicePayload(index,
                        index >= 0 && index < labels.length ? labels[index] : null));
            } else {
                reportFinal(result("single_choice", false, index));
            }
            return;
        }
        if (mAsync) {
            dispatchAndFinish("positive", new JSONObject());
        } else {
            reportFinal(result("positive", false, true));
        }
    }

    private void handleNegative() {
        if (mFinished) {
            return;
        }
        if (mAsync) {
            dispatchAndFinish("negative", new JSONObject());
            return;
        }
        if ("multi_choice".equals(mDialogType)) {
            reportFinal(multiResult(new JSONArray(), new JSONArray(), true));
        } else if ("single_choice".equals(mDialogType)) {
            reportFinal(result("single_choice", true, -1));
        } else if ("select".equals(mDialogType)) {
            reportFinal(result("item_select", true, -1));
        } else if ("input".equals(mDialogType) || "prompt".equals(mDialogType)) {
            reportFinal(result("input", true, JSONObject.NULL));
        } else if ("confirm".equals(mDialogType)) {
            reportFinal(result("negative", true, false));
        } else {
            reportFinal(result("negative", true, true));
        }
    }

    private void handleNeutral() {
        if (mFinished) {
            return;
        }
        if (mAsync) {
            dispatchAndFinish("neutral", new JSONObject());
        } else {
            reportFinal(result("neutral", false, JSONObject.NULL));
        }
    }

    private void handleCancel() {
        if (mFinished) {
            return;
        }
        if (mAsync) {
            dispatchAndFinish("cancel", new JSONObject());
            return;
        }
        if ("multi_choice".equals(mDialogType)) {
            reportFinal(multiResult(new JSONArray(), new JSONArray(), true));
        } else if ("single_choice".equals(mDialogType)) {
            reportFinal(result("single_choice", true, -1));
        } else if ("select".equals(mDialogType)) {
            reportFinal(result("item_select", true, -1));
        } else if ("input".equals(mDialogType) || "prompt".equals(mDialogType)) {
            reportFinal(result("input", true, JSONObject.NULL));
        } else if ("confirm".equals(mDialogType)) {
            reportFinal(result("negative", true, false));
        } else {
            reportFinal(result("cancel", true, JSONObject.NULL));
        }
    }

    private void handleItemSelected(int index, String[] labels) {
        if (mFinished) {
            return;
        }
        if (mAsync) {
            dispatchAndFinish("item_select",
                    choicePayload(index, index >= 0 && index < labels.length ? labels[index] : null));
        } else {
            reportFinal(result("item_select", false, index));
        }
    }

    private void reportAck() {
        try {
            JSONObject value = new JSONObject();
            value.put("shown", true);
            value.put("async", true);
            value.put("token", mToken);
            if (mProgressMax > 0) {
                value.put("max", mProgressMax);
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("value", value);
            NodeUiBridgeService.reportDialogResult(mSession, result.toString());
        } catch (Throwable error) {
            Log.w(TAG, "report dialog shown ack failed", error);
            fail(error);
        }
    }

    private void registerControlReceiver() {
        if (mControlReceiver != null) {
            return;
        }
        IntentFilter filter = new IntentFilter(NodeUiBridgeFacade.ACTION_DIALOG_CONTROL);
        mControlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !NodeUiBridgeFacade.ACTION_DIALOG_CONTROL.equals(intent.getAction())) {
                    return;
                }
                if (!mToken.equals(intent.getStringExtra(NodeUiBridgeFacade.EXTRA_TOKEN))) {
                    return;
                }
                String op = intent.getStringExtra(NodeUiBridgeFacade.EXTRA_OP);
                if ("progress".equals(op)) {
                    try {
                        applyProgress(Integer.parseInt(intent.getStringExtra(
                                NodeUiBridgeFacade.EXTRA_VALUE)));
                    } catch (Throwable error) {
                        Log.w(TAG, "忽略非法进度值", error);
                    }
                } else if ("dismiss".equals(op)) {
                    dispatchAndFinish("dismiss", new JSONObject());
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mControlReceiver, filter);
        }
    }

    /**
     * task-161：把 XML 自定义内容区里带 id 的控件事件回传给 Node。
     * 点击事件 -> custom_view_click {id, text}；输入框文字变化 -> custom_view_text {id, text}。
     * 两者都是非终止事件，不会关闭对话框。
     */
    private void bindCustomViewEvents(android.view.View content, String xml) {
        try {
            JSONObject views = com.stardust.autojs.engine.node24.NodeUiHost.buildInlineViews(content, xml);
            java.util.Iterator<String> keys = views.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                int id = views.optInt(name, 0);
                if (id == 0) {
                    continue;
                }
                final android.view.View target = content.findViewById(id);
                if (target == null) {
                    continue;
                }
                final String viewName = name;
                target.setOnClickListener(v -> {
                    if (mFinished) {
                        return;
                    }
                    try {
                        JSONObject eventPayload = new JSONObject().put("id", viewName);
                        if (v instanceof TextView) {
                            eventPayload.put("text", String.valueOf(((TextView) v).getText()));
                        }
                        sendEvent("custom_view_click", eventPayload);
                    } catch (Throwable ignored) {
                    }
                });
                if (target instanceof EditText) {
                    ((EditText) target).addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                        @Override public void afterTextChanged(android.text.Editable editable) {
                            if (mFinished) {
                                return;
                            }
                            try {
                                sendEvent("custom_view_text", new JSONObject()
                                        .put("id", viewName)
                                        .put("text", editable == null ? "" : editable.toString()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
            Log.i(TAG, "customView bound ids=" + views);
        } catch (Throwable error) {
            Log.w(TAG, "bind customView events failed", error);
        }
    }

    private void sendEvent(String type, JSONObject payload) {
        try {
            Intent intent = new Intent(NodeUiBridgeFacade.ACTION_DIALOG_EVENT);
            intent.setPackage(getPackageName());
            intent.putExtra(NodeUiBridgeFacade.EXTRA_TOKEN, mToken);
            intent.putExtra(NodeUiBridgeFacade.EXTRA_EVENT, type);
            intent.putExtra(NodeUiBridgeFacade.EXTRA_PAYLOAD,
                    payload == null ? "{}" : payload.toString());
            sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "发送对话框事件失败 type=" + type, error);
        }
    }

    private void dispatchAndFinish(String type, JSONObject payload) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        sendEvent(type, payload);
        finish();
    }

    private void reportFinal(JSONObject result) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        NodeUiBridgeService.reportDialogResult(mSession, result.toString());
        finish();
    }

    private void fail(Throwable error) {
        if (mDialog != null) {
            try {
                mDialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        if (!mFinished) {
            mFinished = true;
            NodeUiBridgeService.reportDialogResult(mSession,
                    errorResult(String.valueOf(error == null ? "未知错误" : error.getMessage())));
        }
        finish();
    }

    private static JSONObject result(String which, boolean canceled, Object value) {
        try {
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("canceled", canceled);
            if (which != null && !which.isEmpty()) {
                result.put("which", which);
            }
            result.put("value", value == null ? JSONObject.NULL : value);
            return result;
        } catch (Throwable error) {
            return new JSONObject();
        }
    }

    private static JSONObject multiResult(JSONArray indices, JSONArray texts, boolean canceled) {
        JSONObject result = result("multi_choice", canceled, indices);
        try {
            result.put("indices", indices);
            result.put("texts", texts);
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static JSONObject payload(String key, Object value) {
        try {
            return new JSONObject().put(key, value == null ? JSONObject.NULL : value);
        } catch (Throwable error) {
            return new JSONObject();
        }
    }

    private static JSONObject choicePayload(int index, String text) {
        try {
            return new JSONObject().put("index", index)
                    .put("text", text == null ? JSONObject.NULL : text);
        } catch (Throwable error) {
            return new JSONObject();
        }
    }

    private static JSONObject multiPayload(JSONArray indices, JSONArray texts) {
        try {
            return new JSONObject().put("indices", indices).put("texts", texts);
        } catch (Throwable error) {
            return new JSONObject();
        }
    }

    private static String errorResult(String message) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(message)) + "}";
    }

    @Override protected void onDestroy() {
        ACTIVE.remove(this);
        if (mControlReceiver != null) {
            try {
                unregisterReceiver(mControlReceiver);
            } catch (Throwable error) {
                Log.w(TAG, "unregister dialog control receiver failed", error);
            }
            mControlReceiver = null;
        }
        if (!mFinished) {
            mFinished = true;
            if (mAsync) {
                sendEvent("dismiss", new JSONObject());
            } else {
                NodeUiBridgeService.reportDialogResult(mSession,
                        errorResult("对话框已关闭"));
            }
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        return stopForVolume(event) || super.dispatchKeyEvent(event);
    }
    private boolean stopForVolume(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
                com.jdkshen.aijspro.external.HostScripts.stopAllScripts(this);
            }
            return true;
        }
        return false;
    }
}
