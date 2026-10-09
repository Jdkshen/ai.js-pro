package com.jdkshen.aijspro.ui.codegeneration;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.jdkshen.aijspro.R;
import com.stardust.autojs.codegeneration.CodeGenerator;
import com.stardust.util.ClipboardUtil;
import com.stardust.view.accessibility.NodeInfo;

/** Advanced selector options and preview share one owned overlay dialog. */
public final class CodeGenerateDialog {
    private static final int GREEN = 0xff198b64;
    private final Context context;
    private final NodeInfo root, target;
    private final Dialog dialog;
    private final LinearLayout panel, options, actions;
    private final ScrollView scroll;
    private final TextView title, hint, primary, secondary, preview;
    private final CheckBox usingId, usingText, usingDesc;
    private final RadioGroup searchMode, action;
    private final EditText actionText;
    private boolean showingPreview;
    private int optionsScroll;
    private String code;

    public CodeGenerateDialog(Context context, NodeInfo root, NodeInfo target) {
        this.context = context; this.root = root; this.target = target;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel = column(); panel.setPadding(dp(14), dp(8), dp(14), dp(10));
        panel.setBackground(background(Color.WHITE, 12));
        LinearLayout heading = row();
        title = text(context.getString(R.string.text_generate_code), 14, Color.DKGRAY);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("×", GREEN, dialog::dismiss), new LinearLayout.LayoutParams(dp(40), dp(40)));
        panel.addView(heading);
        hint = text(nodeSummary(), 11, Color.GRAY); hint.setMaxLines(2);
        hint.setPadding(0, dp(2), 0, dp(8)); panel.addView(hint);
        scroll = new ScrollView(context); scroll.setFillViewport(false);
        options = column();
        LinearLayout conditions = column();
        usingId = check(context.getString(R.string.text_using_id_selector));
        usingText = check(context.getString(R.string.text_using_text_selector));
        usingDesc = check(context.getString(R.string.text_using_desc_selector));
        conditions.addView(usingId); conditions.addView(usingText); conditions.addView(usingDesc);
        section("定位条件", conditions, false);
        searchMode = new RadioGroup(context); searchMode.setOrientation(LinearLayout.VERTICAL);
        addRadio(searchMode, R.string.text_find_one, CodeGenerator.FIND_ONE);
        addRadio(searchMode, R.string.text_until_find, CodeGenerator.UNTIL_FIND);
        addRadio(searchMode, R.string.text_wait_for, CodeGenerator.WAIT_FOR);
        addRadio(searchMode, R.string.text_selector_exists, CodeGenerator.EXISTS);
        searchMode.check(searchId(CodeGenerator.FIND_ONE)); section("查找方式", searchMode, true);
        actions = column(); action = new RadioGroup(context); action.setOrientation(LinearLayout.VERTICAL);
        addRadio(action, "不执行动作", -1);
        addRadio(action, R.string.text_click, AccessibilityNodeInfoCompat.ACTION_CLICK);
        addRadio(action, R.string.text_long_click, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
        addRadio(action, R.string.text_set_text, AccessibilityNodeInfoCompat.ACTION_SET_TEXT);
        addRadio(action, R.string.text_scroll_forward, AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
        addRadio(action, R.string.text_scroll_backward, AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
        action.check(actionId(-1)); actions.addView(action);
        actionText = new EditText(context); actionText.setTextSize(12); actionText.setTextColor(Color.DKGRAY);
        actionText.setHint("要设置的文本（可留空）"); actionText.setHintTextColor(Color.GRAY);
        actionText.setMinLines(1); actionText.setMaxLines(3); actionText.setPadding(dp(12), dp(8), dp(12), dp(8));
        actionText.setBackground(background(0xfff3f4f5, 8)); actionText.setVisibility(View.GONE);
        actions.addView(actionText, new LinearLayout.LayoutParams(-1, -2)); section("动作", actions, true);
        action.setOnCheckedChangeListener((group, id) -> updateActionState());
        searchMode.setOnCheckedChangeListener((group, id) -> updateActionState());
        scroll.addView(options); panel.addView(scroll);
        preview = text("", 12, Color.DKGRAY); preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true); preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        preview.setBackground(background(0xfff3f4f5, 8));
        LinearLayout footer = row(); footer.setPadding(0, dp(10), 0, 0);
        secondary = button("返回", GREEN, () -> {
            if (showingPreview) showOptions(); else dialog.dismiss();
        });
        footer.addView(secondary, new LinearLayout.LayoutParams(0, dp(38), 1));
        primary = button("生成代码", Color.WHITE, () -> {
            if (showingPreview) { ClipboardUtil.setClip(context, code); toast("已复制代码"); }
            else generatePreview();
        });
        primary.setBackground(background(GREEN, 19));
        footer.addView(primary, new LinearLayout.LayoutParams(dp(108), dp(38))); panel.addView(footer);
        dialog.setContentView(panel);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK && showingPreview && event.getAction() == KeyEvent.ACTION_UP) {
                showOptions(); return true;
            }
            return key == KeyEvent.KEYCODE_BACK && showingPreview;
        });
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(Math.min(dp(340), context.getResources().getDisplayMetrics().widthPixels - dp(24)), -2);
            panel.getViewTreeObserver().addOnGlobalLayoutListener(this::fitBody);
            fitBody();
        });
    }

    public Dialog build() { return dialog; }

    private void generatePreview() {
        CodeGenerator generator = new CodeGenerator(root, target);
        generator.setUsingId(usingId.isChecked()); generator.setUsingText(usingText.isChecked());
        generator.setUsingDesc(usingDesc.isChecked());
        generator.setSearchMode(value(searchMode));
        generator.setAction(value(action)); generator.setActionText(actionText.getText().toString());
        code = generator.generateCode();
        if (code == null || code.isEmpty()) {
            toast("无法生成唯一定位代码，请调整定位条件，或在控件信息中勾选属性复制"); return;
        }
        InputMethodManager ime = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(actionText.getWindowToken(), 0);
        actionText.clearFocus();
        optionsScroll = scroll.getScrollY(); showingPreview = true;
        scroll.removeAllViews(); scroll.addView(preview); preview.setText(code);
        title.setText("生成结果"); hint.setText("长按可选择代码，或点击右下角复制");
        secondary.setText("返回修改"); primary.setText("复制代码");
        scroll.post(() -> { scroll.scrollTo(0, 0); fitBody(); });
    }

    private void showOptions() {
        showingPreview = false; scroll.removeAllViews(); scroll.addView(options);
        title.setText(R.string.text_generate_code); hint.setText(nodeSummary());
        secondary.setText("返回"); primary.setText("生成代码"); updateActionState();
        scroll.post(() -> { scroll.scrollTo(0, optionsScroll); fitBody(); });
    }

    private void updateActionState() {
        int mode = value(searchMode);
        boolean enabled = mode == CodeGenerator.FIND_ONE || mode == CodeGenerator.UNTIL_FIND;
        for (int i = 0; i < action.getChildCount(); i++) action.getChildAt(i).setEnabled(enabled);
        actions.setAlpha(enabled ? 1f : 0.45f);
        actionText.setVisibility(enabled && value(action) == AccessibilityNodeInfoCompat.ACTION_SET_TEXT ? View.VISIBLE : View.GONE);
        if (!showingPreview) hint.setText(enabled ? nodeSummary() : "此方式只等待或判断控件是否存在，不执行动作");
    }

    private void fitBody() {
        Window window = dialog.getWindow(); if (window == null) return;
        int available = context.getResources().getDisplayMetrics().heightPixels;
        int chrome = panel.getPaddingTop() + panel.getPaddingBottom();
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i); if (child != scroll) chrome += child.getMeasuredHeight();
        }
        android.graphics.Rect frame = new android.graphics.Rect();
        window.getDecorView().getWindowVisibleDisplayFrame(frame);
        if (frame.height() > 0) available = frame.height();
        int max = Math.max(dp(72), Math.min(dp(440), available - chrome - dp(36)));
        View body = showingPreview ? preview : options;
        body.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, scroll.getWidth()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(max, body.getMeasuredHeight());
        if (scroll.getLayoutParams().height != height) scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
    }

    private void section(String label, View content, boolean expanded) {
        TextView heading = text(label + (expanded ? "  ▾" : "  ▸"), 12, GREEN);
        heading.setTypeface(null, Typeface.BOLD); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinHeight(dp(36)); heading.setPadding(dp(4), 0, 0, 0);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        heading.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            heading.setText(label + (open ? "  ▾" : "  ▸")); fitBody();
        });
        options.addView(heading); options.addView(content);
        View divider = new View(context); divider.setBackgroundColor(0xffeeeeee);
        options.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private CheckBox check(String label) {
        CheckBox check = new CheckBox(context); styleChoice(check, label); check.setChecked(true); return check;
    }
    private void addRadio(RadioGroup group, int label, int value) { addRadio(group, context.getString(label), value); }
    private void addRadio(RadioGroup group, String label, int value) {
        RadioButton button = new RadioButton(context); styleChoice(button, label);
        button.setId(group == searchMode ? searchId(value) : actionId(value)); button.setTag(value);
        group.addView(button, new RadioGroup.LayoutParams(-1, -2));
    }
    private int searchId(int value) { return 100 + value; }
    private int actionId(int value) { return 10000 + value; }
    private int value(RadioGroup group) { return (Integer) group.findViewById(group.getCheckedRadioButtonId()).getTag(); }
    private void styleChoice(CompoundButton button, String label) {
        button.setText(label); button.setTextSize(12); button.setTextColor(Color.DKGRAY);
        button.setMinHeight(dp(40)); button.setPadding(dp(2), dp(4), dp(2), dp(4));
        button.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{GREEN, 0xff999999}));
    }
    private String nodeSummary() {
        String label = target.getText().isEmpty() ? target.getDesc() : target.getText();
        return target.getClassName() + (label == null || label.isEmpty() ? "" : " · " + label);
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String label, int size, int color) {
        TextView view = new TextView(context); view.setText(label); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private TextView button(String label, int color, Runnable callback) {
        TextView view = text(label, 12, color); view.setGravity(Gravity.CENTER); view.setOnClickListener(v -> callback.run()); return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private void toast(String message) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }
}
