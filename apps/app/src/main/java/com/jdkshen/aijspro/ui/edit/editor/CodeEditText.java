/**
 * Copyright 2018 WHO<980008027@qq.com>
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p>
 * Modified by project: https://github.com/980008027/JsDroidEditor
 */
package com.jdkshen.aijspro.ui.edit.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.AppCompatEditText;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.LineHeightSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TimingLogger;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.TextView;
import android.widget.TextViewHelper;

import com.jdkshen.aijspro.ui.edit.theme.Theme;
import com.jdkshen.aijspro.ui.edit.theme.TokenMapping;

import com.stardust.util.TextUtils;

import org.mozilla.javascript.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.jdkshen.aijspro.ui.edit.editor.BracketMatching.UNMATCHED_BRACKET;

/**
 * Created by Administrator on 2018/2/11.
 */

public class CodeEditText extends AppCompatEditText {


    static final String LOG_TAG = "CodeEditText";
    private static final boolean DEBUG = false;

    // 文字范围
    protected HVScrollView mParentScrollView;

    private final CopyOnWriteArrayList<CodeEditor.CursorChangeCallback> mCursorChangeCallbacks = new CopyOnWriteArrayList<>();
    private volatile JavaScriptHighlighter.HighlightTokens mHighlightTokens;
    private Theme mTheme;
    private TimingLogger mLogger = new TimingLogger(LOG_TAG, "draw");
    private Paint mLineHighlightPaint = new Paint();
    private Paint mGuidePaint = new Paint();
    private Paint mBlockPaint = new Paint();
    private int mFirstLineForDraw = -1, mLastLineForDraw;
    private int[] mMatchingBrackets = {-1, -1};
    private int mUnmatchedBracket = -1;

    // Code folding (Auto.js Pro style): foldable regions detected from bracket pairs,
    // their collapsed state, and a dirty flag to rebuild after text changes.
    private final List<FoldRegion> mFoldRegions = new ArrayList<>();
    private final Set<Integer> mFoldedStarts = new HashSet<>();
    private final List<CollapsedLineSpan> mFoldSpans = new ArrayList<>();
    private boolean mFoldDirty = true;

    private static final class FoldRegion {
        final int startLine;
        final int endLine;
        FoldRegion(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    /** Keeps folded source in the Editable while removing its visual line height. */
    private static final class CollapsedLineSpan implements LineHeightSpan {
        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v,
                                 Paint.FontMetricsInt fm) {
            fm.top = 0;
            fm.ascent = 0;
            fm.descent = 0;
            fm.bottom = 0;
            fm.leading = 0;
        }
    }
    private LinkedHashMap<Integer, CodeEditor.Breakpoint> mBreakpoints = new LinkedHashMap<>();
    private int mDebuggingLine = -1;
    private CodeEditor.BreakpointChangeListener mBreakpointChangeListener;
    private ScaleGestureDetector mScaleDetector;


    public CodeEditText(Context context) {
        super(context);
        init();
    }

    public CodeEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setGravity(Gravity.START);
        // 设值背景透明
        setBackgroundColor(Color.TRANSPARENT);
        // 设置字体颜色
        setTextColor(Color.TRANSPARENT);
        // 设置字体
        setTypeface(Typeface.MONOSPACE);
        setHorizontallyScrolling(true);
        mTheme = Theme.getDefault(getContext());
        mLineHighlightPaint.setStyle(Paint.Style.FILL);
        // VS Code-like editor hints: indent guides + row separators (stroke) and
        // the current-line accent bar (fill).
        float density = getResources().getDisplayMetrics().density;
        mGuidePaint.setStyle(Paint.Style.STROKE);
        mGuidePaint.setStrokeWidth(Math.max(1f, density));
        mBlockPaint.setStyle(Paint.Style.FILL);
        mScaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    // Throttle: re-laying-out the whole code view on every MOVE is what
                    // makes pinch-zoom janky. Accumulate the scale delta and only apply
                    // a meaningful change (~6% of font size) to the text.
                    private float pending = 1f;

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        pending *= detector.getScaleFactor();
                        if (Math.abs(Math.log(pending)) < 0.06) {
                            return true;
                        }
                        float scaled = getTextSize() * pending;
                        float minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f,
                                getResources().getDisplayMetrics());
                        float maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f,
                                getResources().getDisplayMetrics());
                        setTextSize(TypedValue.COMPLEX_UNIT_PX,
                                Math.max(minPx, Math.min(maxPx, scaled)));
                        pending = 1f;
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        pending = 1f;
                    }
                });
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                clearFoldSpans();
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mFoldDirty = true;
                // Fold coordinates are line based.  Once the document changes, keeping the
                // old line numbers can hide unrelated code or leave the caret inside an
                // invisible region.  Rebuild from a clean state on the next draw.
                mFoldedStarts.clear();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getAutofillType() {
        return AUTOFILL_TYPE_NONE;
    }

    public LinkedHashMap<Integer, CodeEditor.Breakpoint> getBreakpoints() {
        return mBreakpoints;
    }

    public void setTheme(Theme theme) {
        mTheme = theme;
        invalidate();
    }

    // ---------- code folding ----------

    /** Rebuild foldable regions from balanced brackets ({}, [], ()). Called when dirty. */
    private void ensureFoldRegions() {
        if (!mFoldDirty) return;
        Layout layout = getLayout();
        if (layout == null) {
            // Layout not ready yet (first draw pass); keep dirty and retry on the next draw.
            return;
        }
        mFoldDirty = false;
        mFoldRegions.clear();
        CharSequence text = getText();
        if (text.length() > 128 * 1024) {
            // Very large files: skip the bracket scan (folding disabled) so typing
            // and first layout stay smooth. Fold arrows simply do not appear.
            return;
        }
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[' || c == '(') {
                stack.push(new int[]{c, i});
            } else if (c == '}' || c == ']' || c == ')') {
                if (!stack.isEmpty()) {
                    int[] open = stack.peek();
                    if (matchesBracket((char) open[0], c)) {
                        stack.pop();
                        int startLine = layout.getLineForOffset(open[1]);
                        int endLine = layout.getLineForOffset(i);
                        if (endLine > startLine) {
                            mFoldRegions.add(new FoldRegion(startLine, endLine));
                        }
                    }
                }
            }
        }
        mFoldRegions.sort((a, b) -> Integer.compare(a.startLine, b.startLine));
    }

    private static boolean matchesBracket(char open, char close) {
        return (open == '{' && close == '}') || (open == '[' && close == ']')
                || (open == '(' && close == ')');
    }

    private boolean isFoldStart(int line) {
        for (FoldRegion region : mFoldRegions) {
            if (region.startLine == line) return true;
            if (region.startLine > line) break;
        }
        return false;
    }

    /** If line is hidden by a collapsed region on the same or an ancestor line, returns end line. */
    private int foldedRegionEndAt(int line) {
        for (FoldRegion region : mFoldRegions) {
            if (mFoldedStarts.contains(region.startLine)
                    && line > region.startLine && line <= region.endLine) {
                return region.endLine;
            }
        }
        return -1;
    }

    private boolean toggleFold(int line) {
        ensureFoldRegions();
        if (mFoldedStarts.contains(line)) {
            mFoldedStarts.remove(line);
        } else if (isFoldStart(line)) {
            mFoldedStarts.add(line);
        } else {
            return false;
        }
        applyFoldSpans();
        return true;
    }

    /** Toggle the fold whose opening bracket is on the caret line. */
    public boolean toggleFoldAtSelection() {
        Layout layout = getLayout();
        if (layout == null) return false;
        int selection = Math.max(0, Math.min(length(), getSelectionStart()));
        return toggleFold(layout.getLineForOffset(selection));
    }

    /** Collapse the outermost blocks, matching the editor menu's "fold all" action. */
    public void foldAllTopLevel() {
        ensureFoldRegions();
        mFoldedStarts.clear();
        int coveredUntil = -1;
        for (FoldRegion region : mFoldRegions) {
            if (region.startLine > coveredUntil) {
                mFoldedStarts.add(region.startLine);
                coveredUntil = region.endLine;
            }
        }
        applyFoldSpans();
    }

    /** Expand every collapsed block without changing the document text. */
    public void unfoldAll() {
        if (mFoldedStarts.isEmpty() && mFoldSpans.isEmpty()) return;
        mFoldedStarts.clear();
        clearFoldSpans();
        requestLayout();
        invalidate();
    }

    private void clearFoldSpans() {
        Editable editable = getText();
        for (CollapsedLineSpan span : mFoldSpans) editable.removeSpan(span);
        mFoldSpans.clear();
    }

    /**
     * Collapses the visual height of hidden lines with spans. The underlying source and all
     * offsets remain intact, so saving, undo and syntax highlighting never see marker text.
     */
    private void applyFoldSpans() {
        Layout layout = getLayout();
        if (layout == null) {
            post(this::applyFoldSpans);
            return;
        }
        Editable editable = getText();
        clearFoldSpans();
        for (FoldRegion region : mFoldRegions) {
            if (!mFoldedStarts.contains(region.startLine)) continue;
            int firstHiddenLine = Math.min(region.startLine + 1, layout.getLineCount() - 1);
            int start = layout.getLineStart(firstHiddenLine);
            int end = Math.min(editable.length(), layout.getLineEnd(region.endLine));
            if (end <= start) continue;
            CollapsedLineSpan span = new CollapsedLineSpan();
            editable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mFoldSpans.add(span);
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mLogger.reset();
        if (mParentScrollView == null) {
            mParentScrollView = (HVScrollView) getParent();
        }
        if (getLayout() == null) {
            super.onDraw(canvas);
            invalidate();
            return;
        }
        updatePaddingForGutter();
        updateLineRangeForDraw(canvas);
        ensureFoldRegions();

        //绘制行高亮需要在绘制光标之前
        drawLineHighlights(canvas);

        //调用super.onDraw绘制光标和选择高亮。因为字体颜色被设置为透明因此super.onDraw()绘制的字体不显示
        // TODO: 2018/2/24 优化效率。不绘制透明字体。
        super.onDraw(canvas);
        mLogger.addSplit("super draw");

        canvas.save();
        canvas.translate(0, getExtendedPaddingTop());
        drawText(canvas);
        mLogger.addSplit("draw text");
        canvas.restore();

        mLogger.dumpToLog();
    }

    public int getDebuggingLine() {
        return mDebuggingLine;
    }

    public void setDebuggingLine(int debuggingLine) {
        mDebuggingLine = debuggingLine;
        invalidate();
    }

    private void drawLineHighlights(Canvas canvas) {
        int currentLine = getCurrentLine();
        int debugHighlightLine = mDebuggingLine;
        if (debugHighlightLine != currentLine) {
            //绘制当前行高亮
            mLineHighlightPaint.setColor(mTheme.getLineHighlightBackgroundColor());
            drawLineHighlight(canvas, mLineHighlightPaint, currentLine);
            drawCurrentLineBar(canvas, currentLine);
        }
        if (debugHighlightLine != -1) {
            mLineHighlightPaint.setColor(mTheme.getDebuggingLineBackgroundColor());
            drawLineHighlight(canvas, mLineHighlightPaint, debugHighlightLine);
        }

    }

    /** Thin accent bar on the left edge of the cursor line (VS Code style). */
    private void drawCurrentLineBar(Canvas canvas, int line) {
        if (line < 0 || line > getLineCount() - 1) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int lineTop = layout.getLineTop(line);
        int lineBottom = layout.getLineTop(line + 1);
        mBlockPaint.setColor(mTheme.getLineNumberColor());
        mBlockPaint.setAlpha(210);
        float barWidth = 2.5f * getResources().getDisplayMetrics().density;
        canvas.drawRect(0, lineTop, barWidth, lineBottom, mBlockPaint);
    }

    private void updateLineRangeForDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null)
            return;
        long lineRange = getLineRangeForDraw(layout, canvas);
        mFirstLineForDraw = LayoutHelper.unpackRangeStartFromLong(lineRange);
        mLastLineForDraw = LayoutHelper.unpackRangeEndFromLong(lineRange);
    }

    private void updatePaddingForGutter() {
        // 根据行号计算左边距padding 留出绘制行号的空间
        String max = Integer.toString(getLineCount());
        // Pro's editor keeps a wide, calm gutter for line numbers, fold markers and
        // breakpoints.  The old fixed 20 px margin became cramped on high-density phones.
        float gutterWidth = getPaint().measureText(max)
                + 28 * getResources().getDisplayMetrics().density;
        if (getPaddingLeft() != gutterWidth) {
            setPadding((int) gutterWidth, 0, 0, 0);
        }
    }

    //该方法中内联了很多函数来提高效率 但是 这是必要的吗？？？
    // 绘制文本着色
    private void drawText(Canvas canvas) {
        if (mFirstLineForDraw < 0) {
            return;
        }
        JavaScriptHighlighter.HighlightTokens highlightTokens = mHighlightTokens;
        //Log.d(LOG_TAG, "drawText: tokens = " + highlightTokens);
        Layout layout = getLayout();
        int lineCount = getLineCount();
        int textLength = highlightTokens == null ? 0 : highlightTokens.getText().length();
        Editable text = getText();
        int paddingLeft = getPaddingLeft();
        int scrollX = Math.max(getRealScrollX() - paddingLeft, 0);
        Paint paint = getPaint();
        int lineNumberColor = mTheme.getLineNumberColor();
        int breakPointColor = mTheme.getBreakpointColor();
        if (DEBUG)
            Log.d(LOG_TAG, "draw line: " + (mLastLineForDraw - mFirstLineForDraw + 1));
        mLogger.addSplit("before draw line");
        for (int line = mFirstLineForDraw; line <= mLastLineForDraw && line < lineCount; line++) {
            // Lines hidden by a collapsed region keep their source offsets but have zero height.
            FoldRegion hidden = foldedRegionContaining(line);
            if (hidden != null) {
                continue;
            }
            if (isFoldStart(line)) {
                drawFoldArrow(canvas, line, !mFoldedStarts.contains(line), paint);
            }
            int lineBottom = layout.getLineTop(line + 1);
            int lineTop = layout.getLineTop(line);
            int lineBaseline = lineBottom - layout.getLineDescent(line);

            //drawLineNumber
            String lineNumberText = Integer.toString(line + 1);
            // if there is a breakpoint at this line, draw highlight background for line number
            if (mBreakpoints.containsKey(line)) {
                paint.setColor(breakPointColor);
                canvas.drawRect(0, lineTop, paddingLeft - 10, lineBottom, paint);
            }
            paint.setColor(lineNumberColor);
            canvas.drawText(lineNumberText, 0, lineNumberText.length(), 10,
                    lineBaseline, paint);

            // VS Code-style indent guides and 1px row separators
            int guidesLineStart = layout.getLineStart(line);
            drawIndentGuidesAndRowSeparator(canvas, layout, text, line,
                    guidesLineStart, lineTop, lineBottom, paint);

            if (highlightTokens == null)
                continue;

            //drawCode
            int lineStart = layout.getLineStart(line);
            if (lineStart >= textLength) {
                return;
            }
            int lineEnd = Math.min(layout.getLineVisibleEnd(line), highlightTokens.colors.length);
            // Layout#getLineVisibleEnd normally excludes '\n', but some Android versions
            // can still leave the '\r' of a CRLF line in the visible range. Never send a
            // line separator to Canvas.drawText(): unsupported control characters are
            // rendered as a tofu square, which is especially noticeable on empty lines.
            while (lineEnd > lineStart) {
                char last = text.charAt(lineEnd - 1);
                if (last != '\r' && last != '\n') {
                    break;
                }
                lineEnd--;
            }
            if (lineEnd <= lineStart) {
                continue;
            }
            int visibleCharStart = getVisibleCharIndex(paint, scrollX, lineStart, lineEnd);
            int visibleCharEnd = Math.min(lineEnd,
                    getVisibleCharIndex(paint, scrollX + mParentScrollView.getWidth(), lineStart, lineEnd) + 1);
            int previousColorPos = visibleCharStart;
            int previousColor;
            if (previousColorPos == mUnmatchedBracket) {
                previousColor = mTheme.getColorForToken(Token.ERROR);
            } else if (previousColorPos == mMatchingBrackets[0] || previousColorPos == mMatchingBrackets[1]) {
                previousColor = mTheme.getColorForToken(TokenMapping.TOKEN_MATCHED_BRACKET);
            } else {
                previousColor = highlightTokens.colors[previousColorPos];
            }
            int i;
            for (i = visibleCharStart; i < visibleCharEnd; i++) {
                int color;
                if (i == mUnmatchedBracket) {
                    color = mTheme.getColorForToken(Token.ERROR);
                } else if (i == mMatchingBrackets[0] || i == mMatchingBrackets[1]) {
                    color = mTheme.getColorForToken(TokenMapping.TOKEN_MATCHED_BRACKET);
                } else {
                    color = highlightTokens.colors[i];
                }
                if (previousColor != color) {
                    paint.setColor(previousColor);
                    float offsetX = paint.measureText(text, lineStart, previousColorPos);
                    canvas.drawText(text, previousColorPos, i, paddingLeft + offsetX, lineBaseline, paint);
                    previousColor = color;
                    previousColorPos = i;
                }
            }
            paint.setColor(previousColor);
            float offsetX = paint.measureText(text, lineStart, previousColorPos);
            if (previousColorPos < 0 || visibleCharEnd > textLength || previousColorPos >= visibleCharEnd) {
                Log.e(LOG_TAG, "IndexOutOfBounds: previousColorPos = " + previousColorPos + ", visibleCharEnd = "
                        + visibleCharEnd + ", textLength = " + textLength);
                //postInvalidate();
                return;
            }
            canvas.drawText(text, previousColorPos, visibleCharEnd, paddingLeft + offsetX, lineBaseline, paint);
            if (mFoldedStarts.contains(line)) {
                float markerX = paddingLeft + paint.measureText(text, lineStart, lineEnd)
                        + 5f * getResources().getDisplayMetrics().density;
                paint.setColor(mTheme.getLineNumberColor());
                canvas.drawText(" …", markerX, lineBaseline, paint);
            }
            if (DEBUG) {
                mLogger.addSplit("draw line " + line + " (" + (visibleCharEnd - visibleCharStart) + ") ");
            }
        }
    }

    /** Returns the collapsed region hiding {code}line{/code}, or null. */
    private FoldRegion foldedRegionContaining(int line) {
        for (FoldRegion region : mFoldRegions) {
            if (mFoldedStarts.contains(region.startLine)
                    && line > region.startLine && line <= region.endLine) {
                return region;
            }
        }
        return null;
    }

    /** VS Code-like fold toggle arrow drawn on the right edge of the gutter. */
    private void drawFoldArrow(Canvas canvas, int line, boolean expanded, Paint paint) {
        Layout layout = getLayout();
        if (layout == null) return;
        int lineTop = layout.getLineTop(line);
        int lineBottom = layout.getLineTop(line + 1);
        float density = getResources().getDisplayMetrics().density;
        float cx = getPaddingLeft() - 11f * density;
        float cy = (lineTop + lineBottom) / 2f;
        mBlockPaint.setColor(mTheme.getLineNumberColor());
        mBlockPaint.setAlpha(255);
        float r = 4f * density;
        android.graphics.Path path = new android.graphics.Path();
        if (expanded) {
            // ▽ pointing down (collapse)
            float w = r, h = r * 0.86f;
            path.moveTo(cx - w, cy - h * 0.5f);
            path.lineTo(cx + w, cy - h * 0.5f);
            path.lineTo(cx, cy + h * 0.6f);
            path.close();
        } else {
            // ▸ pointing right (expand)
            float w = r * 0.86f, h = r;
            path.moveTo(cx - w * 0.5f, cy - h);
            path.lineTo(cx - w * 0.5f, cy + h);
            path.lineTo(cx + w * 0.6f, cy);
            path.close();
        }
        canvas.drawPath(path, mBlockPaint);
        paint.setColor(mTheme.getLineNumberColor());
    }

    private void drawLineHighlight(Canvas canvas, Paint paint, int line) {
        if (line < mFirstLineForDraw || line > mLastLineForDraw || mFirstLineForDraw < 0 || line < 0) {
            return;
        }
        Layout layout = getLayout();
        if (layout == null) {
            return;
        }
        int lineTop = layout.getLineTop(line);
        int lineBottom = layout.getLineTop(line + 1);
        canvas.drawRect(0, lineTop, canvas.getWidth(), lineBottom, paint);
    }

    /**
     * VS Code-style indent guides (semi-transparent vertical lines at each indentation
     * level) plus a 1px separator under every line, drawn in the gutter-right region.
     */
    private void drawIndentGuidesAndRowSeparator(Canvas canvas, Layout layout, Editable text,
                                                 int line, int lineStart, int lineTop, int lineBottom,
                                                 Paint paint) {
        int paddingLeft = getPaddingLeft();
        int lineEnd = Math.min(layout.getLineVisibleEnd(line), text.length());
        int visibleStart = getVisibleCharIndex(paint,
                Math.max(getRealScrollX() - paddingLeft, 0), lineStart, lineEnd);
        float codeStart = paddingLeft + paint.measureText(text, lineStart, visibleStart);
        float spaceWidth = paint.measureText(" ");
        int indentCols = 0;
        for (int i = lineStart; i < lineEnd; i++) {
            char c = text.charAt(i);
            if (c == ' ') indentCols++;
            else if (c == '\t') indentCols += 4;
            else break;
        }
        if (indentCols > 0) {
            mGuidePaint.setColor(mTheme.getLineNumberColor());
            mGuidePaint.setAlpha(64);
            // Auto.js Pro (and VS Code) style: dotted indent guides.
            float density = getResources().getDisplayMetrics().density;
            mGuidePaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{1.5f * density, 2.5f * density}, 0));
            float levelWidth = spaceWidth * 4;
            for (float col = levelWidth; col < indentCols * spaceWidth; col += levelWidth) {
                float x = codeStart + col;
                canvas.drawLine(x, lineTop, x, lineBottom, mGuidePaint);
            }
            mGuidePaint.setPathEffect(null);
        }
        // 1px separator between rows (dimmed line-number color)
        mGuidePaint.setColor(mTheme.getLineNumberColor());
        mGuidePaint.setAlpha(34);
        canvas.drawLine(paddingLeft, lineBottom, canvas.getWidth(), lineBottom, mGuidePaint);
    }

    private int getCurrentLine() {
        Layout layout = getLayout();
        if (layout == null)
            return -1;
        return LayoutHelper.getLineOfChar(getLayout(), getSelectionStart());
    }


    private int getVisibleCharIndex(Paint paint, int x, int lineStart, int lineEnd) {
        if (x == 0)
            return lineStart;
        int low = lineStart;
        int high = lineEnd - 1;
        while (low < high) {
            int mid = (high + low) >>> 1;
            float midX = paint.measureText(getText(), lineStart, mid + 1);
            if (x < midX) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private long getLineRangeForDraw(Layout layout, Canvas canvas) {
        canvas.save();
        int scrollY = getRealScrollY();
        float clipTop = (scrollY == 0) ? 0
                : getExtendedPaddingTop() + scrollY
                - mParentScrollView.getPaddingTop();
        canvas.clipRect(0, clipTop, getWidth(), scrollY
                + mParentScrollView.getHeight());
        long lineRangeForDraw = LayoutHelper.getLineRangeForDraw(layout, canvas);
        canvas.restore();
        return lineRangeForDraw;
    }

    private int getRealScrollY() {
        return mParentScrollView.getScrollY() + getScrollY();
    }


    private int getRealScrollX() {
        return mParentScrollView.getScrollX() + getScrollX();
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        //调用父类的onSelectionChanged时会发送一个AccessibilityEvent，当文本过大时造成异常
        //super.onSelectionChanged(selStart, selEnd);
        //父类构造函数会调用onSelectionChanged, 此时mCursorChangeCallbacks还没有初始化
        if (mCursorChangeCallbacks == null || mCursorChangeCallbacks.isEmpty() || selStart != selEnd) {
            return;
        }
        callCursorChangeCallback(getText(), selStart);
        matchesBracket(getText(), selStart);
    }

    private void matchesBracket(CharSequence text, int cursor) {
        if (checkBracketMatchingAt(text, cursor)) {
            return;
        }
        if (checkBracketMatchingAt(text, cursor - 1)) {
            return;
        }
        mMatchingBrackets[0] = -1;
        mMatchingBrackets[1] = -1;
        mUnmatchedBracket = -1;
    }

    private boolean checkBracketMatchingAt(CharSequence text, int cursor) {
        if (cursor < 0 || cursor >= text.length()) {
            return false;
        }
        int i = BracketMatching.bracketMatching(text, cursor);
        if (i >= 0) {
            mMatchingBrackets[0] = cursor;
            mMatchingBrackets[1] = i;
            mUnmatchedBracket = -1;
            return true;
        } else if (i == UNMATCHED_BRACKET) {
            mUnmatchedBracket = cursor;
            mMatchingBrackets[0] = -1;
            mMatchingBrackets[1] = -1;
            return true;
        }
        return false;

    }

    private void callCursorChangeCallback(CharSequence text, int sel) {
        if (text.length() == 0) {
            return;
        }
        if (mCursorChangeCallbacks.isEmpty())
            return;
        int lineStart = TextUtils.lastIndexOf(text, '\n', sel - 1) + 1;
        if (lineStart < 0) {
            lineStart = 0;
        }
        if (lineStart > text.length() - 1) {
            lineStart = text.length() - 1;
        }
        int lineEnd = TextUtils.indexOf(text, '\n', sel);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        if (lineEnd < lineStart || lineStart < 0 || lineEnd > text.length())
            return;
        String line = text.subSequence(lineStart, lineEnd).toString();
        int cursor = sel - lineStart;
        for (CodeEditor.CursorChangeCallback callback : mCursorChangeCallbacks) {
            callback.onCursorChange(line, cursor);
        }
    }

    public void addCursorChangeCallback(CodeEditor.CursorChangeCallback callback) {
        mCursorChangeCallbacks.add(callback);
    }

    public boolean removeCursorChangeCallback(CodeEditor.CursorChangeCallback callback) {
        return mCursorChangeCallbacks.remove(callback);
    }


    public void updateHighlightTokens(JavaScriptHighlighter.HighlightTokens highlightTokens) {
        if (mHighlightTokens != null && mHighlightTokens.getId() >= highlightTokens.getId()) {
            return;
        }
        mHighlightTokens = highlightTokens;
        Log.d(LOG_TAG, "updateHighlightTokens: tokens = " + highlightTokens);
        postInvalidate();
    }

    @Override
    public void setSelection(int index) {
        if (index < 0) {
            index = 0;
        }
        if (index > getText().length()) {
            index = getText().length();
        }
        super.setSelection(index);
    }


    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        Editable text = getText();
        TextView.SavedState savedState = (SavedState) super.onSaveInstanceState();
        if (text != null && text.length() > 50 * 1024) {
            // avoid TransactionTooLargeException
            TextViewHelper.setText(savedState, "");
        }
        bundle.putParcelable("super_data", savedState);
        bundle.putInt("debugging_line", mDebuggingLine);
        int[] breakpoints = new int[mBreakpoints.size()];
        int i = 0;
        for (CodeEditor.Breakpoint breakpoint : mBreakpoints.values()) {
            breakpoints[i++] = breakpoint.line;
        }
        bundle.putIntArray("breakpoints", breakpoints);
        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        Bundle bundle = (Bundle) state;
        Parcelable superData = bundle.getParcelable("super_data");
        mDebuggingLine = bundle.getInt("debugging_line", -1);
        int[] breakpoints = bundle.getIntArray("breakpoints");
        if (breakpoints != null) {
            for (int breakpoint : breakpoints) {
                mBreakpoints.put(breakpoint, new CodeEditor.Breakpoint(breakpoint));
            }
        }
        super.onRestoreInstanceState(superData);
    }

    private int mTouchedLine = -1;
    private boolean mTouchValid = true;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // While two fingers are down, stop the parent HVScrollView from stealing the
        // gesture for scrolling so pinch-to-zoom reaches the ScaleGestureDetector;
        // restore interception once fewer than two pointers remain.
        int actionMasked = event.getActionMasked();
        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if ((actionMasked == MotionEvent.ACTION_POINTER_UP
                || actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_CANCEL)
                && event.getPointerCount() <= 1 && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        if (mScaleDetector != null) {
            mScaleDetector.onTouchEvent(event);
            // While a two-finger pinch is in progress, do not forward to cursor/breakpoint logic.
            if (mScaleDetector.isInProgress() && event.getPointerCount() >= 2) {
                return true;
            }
        }
        //如果行号区域被按下
        if (event.getAction() == MotionEvent.ACTION_DOWN && event.getX() < getPaddingLeft()) {
            //则计算当前行，如果行号有效，记录起来
            int line = getLayout().getLineForVertical((int) event.getY());
            if (line >= 0) {
                // Only the small fold-arrow itself toggles folding (tight hit zone);
                // the rest of the gutter stays as the breakpoint toggle.
                float density = getResources().getDisplayMetrics().density;
                float arrowCenter = getPaddingLeft() - 11f * density;
                float hitRadius = 9f * density;
                if (isFoldStart(line) && Math.abs(event.getX() - arrowCenter) <= hitRadius) {
                    toggleFold(line);
                    return true;
                }
                mTouchedLine = line;
                mTouchValid = true;
                return true;
            }
        } else if (mTouchedLine >= 0) {
            //如果之前已经是行号区域被按下了，则之后的事件也要处理
            //如果之后的触摸区域超出行号区域，或者触摸的行号与第一次触摸事件时的不同，则这一系列的触摸无效
            if (event.getX() >= getPaddingLeft() || (getLayout().getLineForVertical((int) event.getY()) != mTouchedLine)) {
                mTouchValid = false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                //当触摸有效时，对那一行设置断点或取消断点
                if (mTouchValid) {
                    if (!removeBreakpoint(mTouchedLine)) {
                        addBreakpoint(mTouchedLine);
                    }
                    invalidate();
                }
                mTouchedLine = -1;
            }
            return true;
        }

        return super.onTouchEvent(event);
    }

    public boolean removeBreakpoint(int line) {
        boolean success = mBreakpoints.remove(line) != null;
        if (success && mBreakpointChangeListener != null) {
            mBreakpointChangeListener.onBreakpointChange(line, false);
            invalidate();
        }
        return success;
    }

    public void addBreakpoint(int line) {
        mBreakpoints.put(line, new CodeEditor.Breakpoint(line));
        if (mBreakpointChangeListener != null) {
            mBreakpointChangeListener.onBreakpointChange(line, true);
        }
        invalidate();
    }

    public void setBreakpointChangeListener(CodeEditor.BreakpointChangeListener listener) {
        mBreakpointChangeListener = listener;
    }

    public void removeAllBreakpoints() {
        int size = mBreakpoints.size();
        mBreakpoints.clear();
        if (mBreakpointChangeListener != null) {
            mBreakpointChangeListener.onAllBreakpointRemoved(size);
        }
        invalidate();

    }
}
