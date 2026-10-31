package com.stardust.autojs.runtime.api.remote;

import android.os.SystemClock;
import android.util.Log;

import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.regexp.NativeRegExp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * :script 进程里的远程选择器（Rhino 选择器全局函数/链式查询的载体）。
 *
 * <p>为什么需要它：Rhino 的 {@code __selector__.js} 把 {@code __runtime__.selector()} 返回对象的
 * 所有方法安装成全局函数（text/id/desc/.../findOne/...）。{@code :script} 进程没有
 * AccessibilityService，无法用本地 {@code UiSelector}（会得到空结果），因此
 * {@code ScriptRuntime.selector()} 在远程模式下返回本类实例：
 * <ul>
 *   <li>筛选条件通过 {@link RemoteAutomationBridge.Backend#findNodes(String)}（主进程用 UiSelector
 *       匹配）或 {@link RemoteAutomationBridge.Backend#getLatestNodeDump()}（本地过滤）完成；</li>
 *   <li>find/findOne/untilFind/exists 返回 {@link RemoteUiObject} / {@code List<RemoteUiObject>}；</li>
 *   <li>click()/longClick()/setText() 直接对第一个匹配节点执行（动作经主进程）。</li>
 * </ul>
 *
 * <p><b>R05 快照完整性/分页</b>：走 {@code getLatestNodeDump} 的查询会把 DFS 快照按
 * {@code path}/{@code childCount} 重新校验（{@link Snapshot}），结果集合携带
 * {@code complete/truncated/firstMissingPath/generation/nextCursor/seen}；
 * {@link #query(int)} 提供稳定分页（同一个快照内不丢不重）与 {@link Query#cancel() 取消}。
 * 截断的快照永不冒充完整结果。
 *
 * <p><b>R06 真正的 BFS</b>：宿主 findNodes 支持原生 BFS，纯可下发条件仍走快通道；
 * 一旦有本地谓词/RegExp/范围条件，就必须在快照上遍历。此时只有<b>完整</b>快照才能用
 * path 结构重建真正的层序遍历；截断的 DFS 快照排序会被明确拒绝，不再冒充 BFS。
 *
 * <p><b>R07 身份</b>：{@link RemoteUiObject} 携带 token/generation，动作仍用 path+属性保护
 * 选择器重新定位，永远不做坐标兜底。
 */
public final class RemoteUiSelector {

    private static final String TAG = "RemoteUiSelector";
    private static final long POLL_INTERVAL_MILLIS = 50L;
    /** 与宿主/本地 UiGlobalSelector 对齐的规范算法名。 */
    private static final String ALGORITHM_BFS = "BFS";
    private static final String ALGORITHM_DFS = "DFS";
    /**
     * 宿主 findNodes 的单次返回上限（AutomationHostBackend.MAX_FIND_RESULTS）。返回正好触达
     * 这个数量时无法判断是否被宿主截断，按「未证明完整」处理。
     */
    private static final int HOST_FIND_NODE_LIMIT = 500;
    /** 截断快照上的 BFS 明确拒绝文案（R06：不允许用排序冒充完整 BFS）。 */
    private static final String TRUNCATED_BFS_MESSAGE =
            "BFS 需要完整快照：宿主返回的 DFS 树被节点/JSON/时间预算截断（缺少子节点），"
                    + "截断结果排序无法等价于真正 BFS；请缩小范围、改用默认 DFS，或重试拿到完整树";

    /** 每次向宿主取树自增；跨快照的身份/游标用它判断过期。 */
    private static final AtomicInteger sGeneration = new AtomicInteger();

    /** 可下发给主进程的契约选择器字段（IAutomationHost 文档 §3）。 */
    private final JSONObject mRemote = new JSONObject();
    /** 契约不支持的本地过滤条件（在 :script 侧对快照结果再过滤）。 */
    private final List<LocalFilter> mLocalFilters = new ArrayList<>();
    public interface NodePredicate { boolean test(RemoteUiObject node); }
    private final List<NodePredicate> mNodePredicates=new ArrayList<>();
    public RemoteUiSelector filter(NodePredicate predicate) {
        if(predicate==null) throw new IllegalArgumentException("filter 需要函数");
        mNodePredicates.add(predicate);return this;
    }
    public RemoteUiSelector addFilter(NodePredicate predicate) {return filter(predicate);}


    /** 本地过滤条件：对节点 JSON 做断言。 */
    private interface LocalFilter {
        boolean matches(JSONObject node);
    }

    // ---- R05：最近一次快照的分页缓存（只被 query() 使用） --------------------
    private JSONArray mCachedNodes;
    private int[] mCachedOrder;
    private Snapshot mCachedSnapshot;
    private int mCachedGeneration;
    private String mCachedSelectorJson;
    private Snapshot mLastSnapshot;

    public RemoteUiSelector() {
    }

    // ---- 第一类筛选（契约字段，可下发主进程） ----------------------------

    public RemoteUiSelector id(String value) {
        return putString("id", value);
    }

    public RemoteUiSelector text(String value) {
        return putString("text", value);
    }

    public RemoteUiSelector textContains(String value) {
        return putString("textContains", value);
    }

    public RemoteUiSelector textStartsWith(String value) {
        return putString("textStartsWith", value);
    }

    public RemoteUiSelector textMatches(String regex) {
        return putString("textMatches", regex);
    }

    public RemoteUiSelector textMatches(Object regex) {
        return textMatches(regexSource(regex));
    }

    public RemoteUiSelector desc(String value) {
        return putString("desc", value);
    }

    public RemoteUiSelector descContains(String value) {
        return putString("descContains", value);
    }

    public RemoteUiSelector descMatches(Object regex) {
        return descMatches(regexSource(regex));
    }

    public RemoteUiSelector className(String value) {
        return putString("className", value);
    }

    public RemoteUiSelector packageName(String value) {
        return putString("packageName", value);
    }

    public RemoteUiSelector clickable(boolean value) {
        return putBoolean("clickable", value);
    }

    public RemoteUiSelector enabled(boolean value) {
        return putBoolean("enabled", value);
    }

    public RemoteUiSelector scrollable(boolean value) {
        return putBoolean("scrollable", value);
    }

    public RemoteUiSelector clickable() { return clickable(true); }
    public RemoteUiSelector enabled() { return enabled(true); }
    public RemoteUiSelector scrollable() { return scrollable(true); }

    public RemoteUiSelector depth(int value) {
        return putInt("depth", value);
    }

    public RemoteUiSelector bounds(int left, int top, int right, int bottom) {
        try {
            mRemote.put("bounds", new JSONObject()
                    .put("left", left).put("top", top)
                    .put("right", right).put("bottom", bottom));
        } catch (Throwable error) {
            throw new ScriptException("构造 bounds 条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    // ---- 本地过滤条件（快照里有的字段，:script 侧过滤） -------------------

    public RemoteUiSelector idContains(final String value) {
        return localString("id", value, MatchMode.CONTAINS);
    }

    public RemoteUiSelector idStartsWith(final String value) {
        return localString("id", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector idEndsWith(final String value) {
        return localString("id", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector idMatches(String regex) {
        return localString("id", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector idMatches(Object regex) {
        return idMatches(regexSource(regex));
    }

    public RemoteUiSelector textEndsWith(final String value) {
        return localString("text", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector descStartsWith(final String value) {
        return localString("desc", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector descEndsWith(final String value) {
        return localString("desc", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector descMatches(String regex) {
        return localString("desc", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector classNameContains(final String value) {
        return localString("className", value, MatchMode.CONTAINS);
    }

    public RemoteUiSelector classNameStartsWith(final String value) {
        return localString("className", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector classNameEndsWith(final String value) {
        return localString("className", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector classNameMatches(String regex) {
        return localString("className", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector classNameMatches(Object regex) {
        return classNameMatches(regexSource(regex));
    }

    public RemoteUiSelector packageNameContains(final String value) {
        return localString("packageName", value, MatchMode.CONTAINS);
    }

    public RemoteUiSelector packageNameStartsWith(final String value) {
        return localString("packageName", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector packageNameEndsWith(final String value) {
        return localString("packageName", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector packageNameMatches(String regex) {
        return localString("packageName", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector packageNameMatches(Object regex) {
        return packageNameMatches(regexSource(regex));
    }

    private static String regexSource(Object regex) {
        if (regex instanceof org.mozilla.javascript.Wrapper) {
            regex = ((org.mozilla.javascript.Wrapper) regex).unwrap();
        }
        if (regex instanceof NativeRegExp) {
            NativeRegExp value = (NativeRegExp) regex;
            Object flagValue = value.get("flags", value);
            String display = value.toString();
            String flags = flagValue instanceof CharSequence ? flagValue.toString()
                    : display.substring(display.lastIndexOf('/') + 1);
            String modifiers = (flags.contains("i") ? "iu" : "")
                    + (flags.contains("m") ? "m" : "")
                    + (flags.contains("s") ? "s" : "");
            return (modifiers.isEmpty() ? "" : "(?" + modifiers + ")")
                    + value.get("source", value);
        }
        if (regex instanceof Pattern) {
            Pattern value = (Pattern) regex;
            String modifiers = ((value.flags() & Pattern.CASE_INSENSITIVE) != 0 ? "i" : "")
                    + ((value.flags() & Pattern.UNICODE_CASE) != 0 ? "u" : "")
                    + ((value.flags() & Pattern.MULTILINE) != 0 ? "m" : "")
                    + ((value.flags() & Pattern.DOTALL) != 0 ? "s" : "")
                    + ((value.flags() & Pattern.COMMENTS) != 0 ? "x" : "")
                    + ((value.flags() & Pattern.UNIX_LINES) != 0 ? "d" : "")
                    + ((value.flags() & Pattern.UNICODE_CHARACTER_CLASS) != 0 ? "U" : "");
            String source = (value.flags() & Pattern.LITERAL) != 0
                    ? Pattern.quote(value.pattern()) : value.pattern();
            if ((value.flags() & Pattern.CANON_EQ) != 0) {
                throw new IllegalArgumentException("远程正则暂不支持 Pattern.CANON_EQ");
            }
            return (modifiers.isEmpty() ? "" : "(?" + modifiers + ")") + source;
        }
        return String.valueOf(regex);
    }

    /**
     * 选择器算法：只接受 BFS/DFS（大小写不敏感），与本地 {@code UiGlobalSelector.algorithm}
     * 一致；保存规范大写值，由宿主按所选算法真实遍历。未知算法立即失败，不再静默退回 DFS。
     *
     * <p>默认不下发 {@code algorithm} 字段（宿主默认 DFS，保持旧行为）。
     */
    public RemoteUiSelector algorithm(String algorithm) {
        final String canonical;
        if (ALGORITHM_BFS.equalsIgnoreCase(algorithm)) {
            canonical = ALGORITHM_BFS;
        } else if (ALGORITHM_DFS.equalsIgnoreCase(algorithm)) {
            canonical = ALGORITHM_DFS;
        } else {
            throw new IllegalArgumentException("unknown algorithm: " + algorithm);
        }
        try {
            mRemote.put("algorithm", canonical);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器 algorithm 条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    // ---- 查询 ------------------------------------------------------------

    /** task-159：auto.root/rootInActiveWindow 的远程只读根（来自主进程当前窗口 dump）。 */
    public RemoteUiObject root() {
        RemoteUiObjectCollection roots = windowRoots();
        return roots.size() > 0 ? roots.get(0) : null;
    }

    /** task-159：当前窗口根集合（主进程 dump 只有活动窗口；多窗口暂不可对齐）。 */
    public RemoteUiObjectCollection windowRoots() {
        RemoteAutomationBridge.ensureAccessibility();
        RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
        List<RemoteUiObject> roots = new ArrayList<>();
        try {
            int generation = sGeneration.incrementAndGet();
            JSONArray nodes = new JSONArray(backend.getLatestNodeDump());
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) { continue; }
                JSONArray path = node.optJSONArray("path");
                if (path != null && path.length() == 0) {
                    roots.add(new RemoteUiObject(backend, "{\"path\":[]}", 0, node, true, generation));
                }
            }
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            throw new ScriptException("远程节点根查询失败：" + messageOf(error), error);
        }
        return new RemoteUiObjectCollection(roots);
    }

    /** Query this live root and its descendants, matching native UiObject.find semantics. */
    RemoteUiObjectCollection findWithin(RemoteUiObject root) {
        return root.exists() ? find(Integer.MAX_VALUE,root) : new RemoteUiObjectCollection(new ArrayList<>());
    }

    public RemoteUiObjectCollection find() {
        return find(Integer.MAX_VALUE);
    }

    public RemoteUiObjectCollection find(int max) {
        return find(max,null);
    }

    private RemoteUiObjectCollection find(int max, RemoteUiObject root) {
        RemoteAutomationBridge.ensureAccessibility();
        RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
        List<RemoteUiObject> result = new ArrayList<>();
        String selectorJson = mRemote.toString();
        boolean local = !mLocalFilters.isEmpty() || !mNodePredicates.isEmpty();
        boolean bfs = ALGORITHM_BFS.equalsIgnoreCase(mRemote.optString("algorithm", ALGORITHM_DFS));
        // A BFS query cannot be answered from the host's full dump: that dump is DFS-ordered.
        // Re-sorting it after the host's 500-node cap (and any future pagination) is not a true
        // level-order traversal. BFS is answered by the host's native queue traversal when only
        // wire conditions are present; once a local predicate forces the snapshot path, the
        // snapshot must be provably complete before it can be re-leveled.
        boolean proxyable = !local && root == null;
        boolean snapshotComplete = true;
        String firstMissing = null;
        int generation = 0;
        int seen = 0;
        try {
            WireMatcher wire = null;
            JSONArray nodes;
            int[] order;
            if (proxyable) {
                nodes = new JSONArray(backend.findNodes(selectorJson));
                generation = sGeneration.incrementAndGet();
                order = identityOrder(nodes.length());
                // findNodes 触达宿主上限时无法证明完整；触达本地上限 max 同样不完整。
                snapshotComplete = nodes.length() < HOST_FIND_NODE_LIMIT;
            } else {
                nodes = new JSONArray(backend.getLatestNodeDump());
                generation = sGeneration.incrementAndGet();
                Snapshot snapshot = Snapshot.analyze(nodes, generation);
                mLastSnapshot = snapshot;
                snapshotComplete = snapshot.complete();
                firstMissing = snapshot.firstMissingPath();
                if (bfs && !snapshotComplete) {
                    throw new ScriptException(TRUNCATED_BFS_MESSAGE);
                }
                wire = new WireMatcher(mRemote);
                order = snapshot.order(bfs);
            }
            for (int oi = 0; oi < order.length && result.size() < max; oi++) {
                int i = order[oi];
                seen++;
                JSONObject node = nodes.optJSONObject(i);
                if (node == null || (wire != null && !wire.matches(node)) || !matchesLocal(node)) {
                    continue;
                }
                RemoteUiObject object = new RemoteUiObject(
                        backend, selectorJson, result.size(), node, proxyable, generation);
                if (root != null && !object.isDescendantOf(root)
                        && !(object.path().equals(root.path()) && object.packageName().equals(root.packageName()))) {
                    continue;
                }
                boolean accepted = true;
                for (NodePredicate predicate : mNodePredicates) {
                    if (!predicate.test(object)) { accepted = false; break; }
                }
                if (accepted) result.add(object);
            }
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            throw new ScriptException("远程选择器查询失败：" + messageOf(error), error);
        }
        boolean complete = snapshotComplete && result.size() < max;
        String nextCursor = complete ? null
                : encodeCursor(generation, selectorJson, seen);
        return new RemoteUiObjectCollection(result, complete, snapshotComplete, firstMissing,
                generation, nextCursor, seen);
    }

    public RemoteUiObject findOnce() {
        return findOnce(0);
    }

    public RemoteUiObject findOnce(int index) {
        RemoteUiObjectCollection nodes = find(index + 1);
        return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
    }

    /** 与本地 UiSelector.findOne() 一致：阻塞直到找到（脚本被停止时抛 ScriptInterruptedException）。 */
    public RemoteUiObject findOne() {
        return untilFindOne();
    }

    public RemoteUiObject findOne(long timeout) {
        return findOne(timeout, 0);
    }

    public RemoteUiObject findOne(long timeout, long interval) {
        ensureNonUiThread();
        long start = SystemClock.elapsedRealtime();
        while (true) {
            RemoteUiObject node = findOnce();
            if (node != null) {
                return node;
            }
            checkInterrupted();
            long now = SystemClock.elapsedRealtime();
            if (timeout > 0 && now - start > timeout) {
                return null;
            }
            sleep(timeout > 0 ? Math.min(interval > 0 ? interval : POLL_INTERVAL_MILLIS,
                    Math.max(1, timeout - (now - start))) : (interval > 0 ? interval : POLL_INTERVAL_MILLIS));
        }
    }

    public RemoteUiObject untilFindOne() {
        return findOne(-1);
    }

    public RemoteUiObjectCollection untilFind() {
        return untilFind(0);
    }

    public RemoteUiObjectCollection untilFind(long timeout) {
        return untilFind(timeout, POLL_INTERVAL_MILLIS);
    }

    public RemoteUiObjectCollection untilFind(long timeout, long interval) {
        ensureNonUiThread();
        long start = SystemClock.elapsedRealtime();
        while (true) {
            RemoteUiObjectCollection nodes = find();
            if (nodes.nonEmpty()) {
                return nodes;
            }
            checkInterrupted();
            long now = SystemClock.elapsedRealtime();
            if (timeout > 0 && now - start > timeout) {
                return nodes;
            }
            sleep(timeout > 0 ? Math.min(interval > 0 ? interval : POLL_INTERVAL_MILLIS,
                    Math.max(1, timeout - (now - start))) : (interval > 0 ? interval : POLL_INTERVAL_MILLIS));
        }
    }

    public boolean exists() {
        return find(1).nonEmpty();
    }

    public void waitFor() {
        untilFind();
    }

    // ---- R05：快照预算 / 稳定分页 -----------------------------------------

    /** 最近一次走快照的查询是否拿到了完整树（没有查询过时返回 true）。 */
    public boolean lastSnapshotComplete() {
        return mLastSnapshot == null || mLastSnapshot.complete();
    }

    /** 最近一次快照第一个缺失子节点的 path（JSON 数组字符串）；完整或未查询时为 null。 */
    public String lastSnapshotFirstMissingPath() {
        return mLastSnapshot == null ? null : mLastSnapshot.firstMissingPath();
    }

    /** 最近一次快照的节点数与代次（预算可观察）。 */
    public int lastSnapshotNodes() {
        return mLastSnapshot == null ? 0 : mLastSnapshot.nodeCount();
    }

    public int lastSnapshotGeneration() {
        return mLastSnapshot == null ? 0 : mLastSnapshot.generation;
    }

    /** 稳定分页：同一快照内按遍历顺序逐页取，不丢不重；{@link Query#cancel() 可取消}。 */
    public Query query(int pageSize) {
        return new Query(pageSize, null);
    }

    /**
     * 用上一页拿到的 {@link RemoteUiObjectCollection#nextCursor()} 继续分页。
     *
     * <p>游标绑定快照代次；宿主树已经刷新（代次不一致）或换了选择器时明确失败，
     * 不会把新树上的不同节点当成上一页的延续。
     */
    public Query query(int pageSize, String cursor) {
        return new Query(pageSize, cursor);
    }

    /** 分页迭代器：每次 next() 返回一页 {@link RemoteUiObjectCollection}。 */
    public final class Query {

        private final int mPageSize;
        private final String mCursorInput;
        private boolean mCaptured;
        private boolean mCancelled;
        private boolean mFinished;
        private JSONArray mNodes;
        private Snapshot mSnapshot;
        private int[] mOrder;
        private int mOffset;
        private int mSeen;
        private int mPages;
        private int mGeneration;
        private boolean mProxyable;
        private boolean mSnapshotComplete = true;
        private String mFirstMissing;
        private WireMatcher mWire;

        Query(int pageSize, String cursor) {
            if (pageSize <= 0) {
                throw new IllegalArgumentException("分页大小必须是正整数");
            }
            mPageSize = pageSize;
            mCursorInput = cursor;
        }

        public boolean hasNext() {
            return !mCancelled && !mFinished;
        }

        public void cancel() {
            mCancelled = true;
        }

        public boolean cancelled() {
            return mCancelled;
        }

        public int pages() {
            return mPages;
        }

        public int seen() {
            return mSeen;
        }

        public int generation() {
            return mGeneration;
        }

        public boolean snapshotComplete() {
            return mSnapshotComplete;
        }

        public String firstMissingPath() {
            return mFirstMissing;
        }

        /** 下一页游标；已经取完或取消时为 null。 */
        public String cursor() {
            if (mCancelled) { return null; }
            if (!mCaptured) { capture(); }
            if (mFinished) { return null; }
            return encodeCursor(mGeneration, mRemote.toString(), mOffset);
        }

        public RemoteUiObjectCollection next() {
            if (mCancelled || mFinished) {
                return emptyPage();
            }
            capture();
            RemoteAutomationBridge.ensureAccessibility();
            RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
            String selectorJson = mRemote.toString();
            List<RemoteUiObject> page = new ArrayList<>();
            while (mOffset < mOrder.length && page.size() < mPageSize) {
                if (mCancelled || Thread.currentThread().isInterrupted()) {
                    mCancelled = true;
                    break;
                }
                int i = mOrder[mOffset++];
                mSeen++;
                JSONObject node = mNodes.optJSONObject(i);
                if (node == null || (mWire != null && !mWire.matches(node)) || !matchesLocal(node)) {
                    continue;
                }
                RemoteUiObject object = new RemoteUiObject(
                        backend, selectorJson, page.size(), node, mProxyable, mGeneration);
                boolean accepted = true;
                for (NodePredicate predicate : mNodePredicates) {
                    if (!predicate.test(object)) { accepted = false; break; }
                }
                if (accepted) { page.add(object); }
            }
            mPages++;
            boolean exhausted = mOffset >= mOrder.length;
            if (exhausted || mCancelled) { mFinished = true; }
            boolean complete = exhausted && mSnapshotComplete;
            String nextCursor = complete ? null : encodeCursor(mGeneration, selectorJson, mOffset);
            return new RemoteUiObjectCollection(page, complete, mSnapshotComplete, mFirstMissing,
                    mGeneration, nextCursor, mSeen);
        }

        private RemoteUiObjectCollection emptyPage() {
            return new RemoteUiObjectCollection(new ArrayList<RemoteUiObject>(), true,
                    mSnapshotComplete, mFirstMissing, mGeneration, null, mSeen);
        }

        private void capture() {
            if (mCaptured) { return; }
            mCaptured = true;
            String selectorJson = mRemote.toString();
            boolean local = !mLocalFilters.isEmpty() || !mNodePredicates.isEmpty();
            mProxyable = !local;
            boolean bfs = ALGORITHM_BFS.equalsIgnoreCase(
                    mRemote.optString("algorithm", ALGORITHM_DFS));
            if (mCursorInput != null) {
                int[] parts = decodeCursor(mCursorInput);
                int selectorHash = selectorJson.hashCode();
                if (parts[0] != mCachedGeneration || parts[1] != selectorHash
                        || mCachedOrder == null || !selectorJson.equals(mCachedSelectorJson)) {
                    throw new ScriptException("分页游标已过期：宿主快照已被新的查询/树刷新覆盖，"
                            + "请从第一页重新开始");
                }
                mNodes = mCachedNodes;
                mOrder = mCachedOrder;
                mSnapshot = mCachedSnapshot;
                mGeneration = parts[0];
                mOffset = parts[2];
                mSeen = mOffset;
                mSnapshotComplete = mSnapshot == null || mSnapshot.complete();
                mFirstMissing = mSnapshot == null ? null : mSnapshot.firstMissingPath();
                mWire = mProxyable ? null : buildWireMatcher();
                return;
            }
            RemoteAutomationBridge.ensureAccessibility();
            RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
            try {
                JSONArray nodes;
                if (mProxyable) {
                    nodes = new JSONArray(backend.findNodes(selectorJson));
                    mGeneration = sGeneration.incrementAndGet();
                    mOrder = identityOrder(nodes.length());
                    mSnapshot = null;
                    mSnapshotComplete = nodes.length() < HOST_FIND_NODE_LIMIT;
                    mWire = null;
                } else {
                    nodes = new JSONArray(backend.getLatestNodeDump());
                    mGeneration = sGeneration.incrementAndGet();
                    mSnapshot = Snapshot.analyze(nodes, mGeneration);
                    mLastSnapshot = mSnapshot;
                    mSnapshotComplete = mSnapshot.complete();
                    mFirstMissing = mSnapshot.firstMissingPath();
                    if (bfs && !mSnapshotComplete) {
                        throw new ScriptException(TRUNCATED_BFS_MESSAGE);
                    }
                    mWire = buildWireMatcher();
                    mOrder = mSnapshot.order(bfs);
                }
                mNodes = nodes;
                mOffset = 0;
                mSeen = 0;
                mCachedNodes = nodes;
                mCachedOrder = mOrder;
                mCachedSnapshot = mSnapshot;
                mCachedGeneration = mGeneration;
                mCachedSelectorJson = selectorJson;
            } catch (ScriptException error) {
                throw error;
            } catch (Throwable error) {
                throw new ScriptException("远程选择器分页失败：" + messageOf(error), error);
            }
        }

        private WireMatcher buildWireMatcher() {
            try {
                return new WireMatcher(mRemote);
            } catch (ScriptException error) {
                throw error;
            } catch (Throwable error) {
                throw new ScriptException("远程选择器条件无法在快照上应用：" + messageOf(error), error);
            }
        }
    }

    // ---- 选择器级动作（对第一个匹配节点执行） -----------------------------

    public boolean click() {
        return untilFind().click();
    }

    public boolean longClick() {
        return untilFind().longClick();
    }

    public boolean setText(String text) {
        return untilFind().setText(text);
    }

    public boolean scrollForward() {
        return untilFind().scrollForward();
    }

    public boolean scrollBackward() {
        return untilFind().scrollBackward();
    }

    // ---- Local predicates over the wire-filtered candidate set ---------------

    private RemoteUiSelector localBoolean(String key, boolean expected) {
        mLocalFilters.add(node -> node.optBoolean(key, false) == expected); return this;
    }
    private RemoteUiSelector localInt(String key, int expected) {
        mLocalFilters.add(node -> node.optInt(key, -1) == expected); return this;
    }
    public RemoteUiSelector checkable(boolean value) { return localBoolean("checkable", value); }
    public RemoteUiSelector checkable() { return checkable(true); }
    public RemoteUiSelector checked(boolean value) { return localBoolean("checked", value); }
    public RemoteUiSelector checked() { return checked(true); }
    public RemoteUiSelector focusable(boolean value) { return localBoolean("focusable", value); }
    public RemoteUiSelector focusable() { return focusable(true); }
    public RemoteUiSelector focused(boolean value) { return localBoolean("focused", value); }
    public RemoteUiSelector focused() { return focused(true); }
    public RemoteUiSelector selected(boolean value) { return localBoolean("selected", value); }
    public RemoteUiSelector selected() { return selected(true); }
    public RemoteUiSelector visibleToUser(boolean value) { return localBoolean("visibleToUser", value); }
    public RemoteUiSelector visibleToUser() { return visibleToUser(true); }
    public RemoteUiSelector editable(boolean value) { return localBoolean("editable", value); }
    public RemoteUiSelector editable() { return editable(true); }
    public RemoteUiSelector longClickable(boolean value) { return localBoolean("longClickable", value); }
    public RemoteUiSelector longClickable() { return longClickable(true); }
    public RemoteUiSelector password(boolean value) { return localBoolean("password", value); }
    public RemoteUiSelector password() { return password(true); }
    public RemoteUiSelector accessibilityFocused(boolean value) { return localBoolean("accessibilityFocused", value); }
    public RemoteUiSelector accessibilityFocused() { return accessibilityFocused(true); }
    public RemoteUiSelector contextClickable(boolean value) { return localBoolean("contextClickable", value); }
    public RemoteUiSelector contextClickable() { return contextClickable(true); }
    public RemoteUiSelector dismissable(boolean value) { return localBoolean("dismissable", value); }
    public RemoteUiSelector dismissable() { return dismissable(true); }
    public RemoteUiSelector contentInvalid(boolean value) { return localBoolean("contentInvalid", value); }
    public RemoteUiSelector contentInvalid() { return contentInvalid(true); }
    public RemoteUiSelector multiLine(boolean value) { return localBoolean("multiLine", value); }
    public RemoteUiSelector multiLine() { return multiLine(true); }
    public RemoteUiSelector drawingOrder(int value) { return localInt("drawingOrder", value); }
    public RemoteUiSelector row(int value) { return localInt("row", value); }
    public RemoteUiSelector column(int value) { return localInt("column", value); }
    public RemoteUiSelector rowCount(int value) { return localInt("rowCount", value); }
    public RemoteUiSelector columnCount(int value) { return localInt("columnCount", value); }
    public RemoteUiSelector rowSpan(int value) { return localInt("rowSpan", value); }
    public RemoteUiSelector columnSpan(int value) { return localInt("columnSpan", value); }
    public RemoteUiSelector indexInParent(int value) { return localInt("indexInParent", value); }
    public RemoteUiSelector boundsInside(int left, int top, int right, int bottom) {
        mLocalFilters.add(node -> { JSONArray b = node.optJSONArray("bounds");
            return b != null && b.length() == 4 && b.optInt(0) >= left && b.optInt(1) >= top
                && b.optInt(2) <= right && b.optInt(3) <= bottom; }); return this;
    }
    public RemoteUiSelector boundsContains(int left, int top, int right, int bottom) {
        mLocalFilters.add(node -> { JSONArray b = node.optJSONArray("bounds");
            return b != null && b.length() == 4 && b.optInt(0) <= left && b.optInt(1) <= top
                && b.optInt(2) >= right && b.optInt(3) >= bottom; }); return this;
    }

    @Override
    public String toString() {
        return "RemoteUiSelector" + mRemote + (mLocalFilters.isEmpty() ? "" : " +" + mLocalFilters.size() + " local");
    }

    // ---- 内部辅助 --------------------------------------------------------

    public boolean clickCenter() { return untilFind().clickCenter(); }
    public boolean scrollUp() { return untilFind().scrollUp(); }
    public boolean scrollDown() { return untilFind().scrollDown(); }
    public boolean scrollLeft() { return untilFind().scrollLeft(); }
    public boolean scrollRight() { return untilFind().scrollRight(); }
    public boolean copy() { return untilFind().copy(); }
    public boolean cut() { return untilFind().cut(); }
    public boolean paste() { return untilFind().paste(); }
    public boolean select() { return untilFind().select(); }
    public boolean collapse() { return untilFind().collapse(); }
    public boolean expand() { return untilFind().expand(); }
    public boolean dismiss() { return untilFind().dismiss(); }
    public boolean show() { return untilFind().show(); }
    public boolean contextClick() { return untilFind().contextClick(); }
    public boolean focus() { return untilFind().focus(); }
    public boolean clearFocus() { return untilFind().clearFocus(); }
    public boolean accessibilityFocus() { return untilFind().accessibilityFocus(); }
    public boolean clearAccessibilityFocus() { return untilFind().clearAccessibilityFocus(); }
    public boolean setSelection(int start, int end) { return untilFind().setSelection(start, end); }
    public boolean setProgress(float value) { return untilFind().setProgress(value); }
    public boolean scrollTo(int row, int column) { return untilFind().scrollTo(row, column); }

    private RemoteUiObject firstNodeOrThrow(String action) {
        RemoteUiObjectCollection nodes = find(1);
        if (nodes.isEmpty()) {
            throw new ScriptException("远程选择器没有匹配到节点，无法执行 " + action + "()");
        }
        return nodes.get(0);
    }

    private RemoteUiSelector putString(String key, String value) {
        try {
            mRemote.put(key, value == null ? "" : value);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    private RemoteUiSelector putBoolean(String key, boolean value) {
        try {
            mRemote.put(key, value);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    private RemoteUiSelector putInt(String key, int value) {
        try {
            mRemote.put(key, value);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    private enum MatchMode {
        CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES
    }

    private RemoteUiSelector localString(final String key, final String value, final MatchMode mode) {
        final String expected = value == null ? "" : value;
        final Pattern pattern = mode == MatchMode.MATCHES ? Pattern.compile(expected) : null;
        mLocalFilters.add(node -> {
            String actual = node.optString(key, "");
            switch (mode) {
                case CONTAINS:
                    return actual.contains(expected);
                case STARTS_WITH:
                    return actual.startsWith(expected);
                case ENDS_WITH:
                    return actual.endsWith(expected);
                case MATCHES:
                    return pattern.matcher(actual).matches();
                default:
                    return false;
            }
        });
        return this;
    }

    private boolean matchesLocal(JSONObject node) {
        for (LocalFilter filter : mLocalFilters) {
            if (!filter.matches(node)) {
                return false;
            }
        }
        return true;
    }

    // ---- 游标编码（不透明字符串；老调用方不会看到它） ---------------------

    private static String encodeCursor(int generation, String selectorJson, int offset) {
        return "r05:1:" + generation + ":" + selectorJson.hashCode() + ":" + offset;
    }

    private static int[] decodeCursor(String cursor) {
        String[] parts = cursor == null ? new String[0] : cursor.split(":", -1);
        if (parts.length != 5 || !"r05".equals(parts[0]) || !"1".equals(parts[1])) {
            throw new ScriptException("分页游标格式无效：" + cursor);
        }
        try {
            return new int[]{Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4])};
        } catch (NumberFormatException error) {
            throw new ScriptException("分页游标格式无效：" + cursor, error);
        }
    }

    // ---- R05/R06：快照完整性分析与真正的层序遍历 -------------------------

    private static int[] identityOrder(int length) {
        int n = Math.max(0, length);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) { order[i] = i; }
        return order;
    }

    /**
     * 把宿主 DFS 快照重新校验成一棵树：path 的父子关系、兄弟下标连续性、childCount 自洽。
     *
     * <p>判定依据（与宿主 {@code nodeJson} 契约一致）：DFS 前序快照里每个节点都带
     * {@code path} 与 {@code childCount}。完整快照满足「每个已出现节点的子节点数 == childCount」，
     * 此时按 path 重建的树就是真树，可以做真正的层序遍历；任何缺子节点/缺 childCount/重复
     * path/孤儿节点都判为截断（{@code complete=false}）并给出第一个缺失子节点的 path。
     */
    static final class Snapshot {

        private final JSONArray nodes;
        final int generation;
        private final boolean complete;
        private final String firstMissingPath;
        private final int[] bfsOrder;

        private Snapshot(JSONArray nodes, int generation, boolean complete,
                         String firstMissingPath, int[] bfsOrder) {
            this.nodes = nodes;
            this.generation = generation;
            this.complete = complete;
            this.firstMissingPath = firstMissingPath;
            this.bfsOrder = bfsOrder;
        }

        boolean complete() {
            return complete;
        }

        String firstMissingPath() {
            return firstMissingPath;
        }

        int nodeCount() {
            return nodes == null ? 0 : nodes.length();
        }

        /** dfs=true 返回快照原顺序；dfs=false 返回重建后的真正层序。 */
        int[] order(boolean bfs) {
            return bfs ? bfsOrder : identityOrder(nodeCount());
        }

        static Snapshot analyze(JSONArray nodes, int generation) {
            int n = nodes == null ? 0 : nodes.length();
            int[] identity = identityOrder(n);
            if (n == 0) {
                return new Snapshot(nodes, generation, true, null, identity);
            }
            JSONArray[] paths = new JSONArray[n];
            Map<String, Integer> byPath = new HashMap<>();
            boolean duplicate = false;
            for (int i = 0; i < n; i++) {
                JSONObject node = nodes.optJSONObject(i);
                JSONArray path = node == null ? null : node.optJSONArray("path");
                if (path == null) { duplicate = true; continue; }
                paths[i] = path;
                String key = pathKey(path, path.length());
                if (byPath.containsKey(key)) { duplicate = true; } else { byPath.put(key, i); }
            }
            Map<String, List<Integer>> children = new HashMap<>();
            int rootIndex = -1;
            boolean orphan = false;
            String firstOrphan = null;
            for (int i = 0; i < n; i++) {
                JSONArray path = paths[i];
                if (path == null) { continue; }
                int length = path.length();
                if (length == 0) {
                    if (rootIndex < 0) { rootIndex = i; } else { duplicate = true; }
                    continue;
                }
                String parentKey = pathKey(path, length - 1);
                if (!byPath.containsKey(parentKey)) {
                    orphan = true;
                    if (firstOrphan == null) { firstOrphan = jsonPath(childKey(parentKey, lastIndex(path))); }
                    continue;
                }
                List<Integer> list = children.get(parentKey);
                if (list == null) { list = new ArrayList<>(); children.put(parentKey, list); }
                list.add(i);
            }
            String firstMissing = null;
            boolean gap = false;
            for (Map.Entry<String, List<Integer>> entry : children.entrySet()) {
                List<Integer> list = entry.getValue();
                list.sort((a, b) -> Integer.compare(lastIndex(paths[a]), lastIndex(paths[b])));
                int expected = 0;
                for (int index : list) {
                    int last = lastIndex(paths[index]);
                    if (last != expected) {
                        gap = true;
                        if (firstMissing == null) { firstMissing = jsonPath(childKey(entry.getKey(), expected)); }
                        expected = last;
                    }
                    expected = last + 1;
                }
            }
            boolean unknownChildCount = false;
            boolean childCountMismatch = false;
            for (int i = 0; i < n; i++) {
                JSONObject node = nodes.optJSONObject(i);
                JSONArray path = paths[i];
                if (node == null || path == null) { continue; }
                String key = pathKey(path, path.length());
                int actual = children.containsKey(key) ? children.get(key).size() : 0;
                if (!node.has("childCount") || node.isNull("childCount")) {
                    unknownChildCount = true;
                    continue;
                }
                int declared = node.optInt("childCount", -1);
                if (declared < 0) {
                    unknownChildCount = true;
                    continue;
                }
                if (declared != actual) {
                    childCountMismatch = true;
                    if (firstMissing == null) { firstMissing = jsonPath(childKey(key, actual)); }
                }
            }
            boolean complete = !duplicate && !orphan && !gap && !unknownChildCount
                    && !childCountMismatch && rootIndex >= 0;
            if (!complete && firstMissing == null && firstOrphan != null) {
                firstMissing = firstOrphan;
            }
            int[] bfs = buildBfs(n, paths, children, rootIndex);
            return new Snapshot(nodes, generation, complete, complete ? null : firstMissing, bfs);
        }

        /** 真正的层序遍历：从根出发按子下标顺序入队；未挂上的节点按原顺序补在末尾（不丢）。 */
        private static int[] buildBfs(int n, JSONArray[] paths,
                                      Map<String, List<Integer>> children, int rootIndex) {
            Integer[] order = new Integer[n];
            boolean[] visited = new boolean[n];
            int count = 0;
            if (rootIndex >= 0) {
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(rootIndex);
                visited[rootIndex] = true;
                while (!queue.isEmpty()) {
                    int i = queue.poll();
                    order[count++] = i;
                    JSONArray path = paths[i];
                    if (path == null) { continue; }
                    List<Integer> kids = children.get(pathKey(path, path.length()));
                    if (kids != null) {
                        List<Integer> sorted = new ArrayList<>(kids);
                        sorted.sort((a, b) -> Integer.compare(lastIndex(paths[a]), lastIndex(paths[b])));
                        for (int kid : sorted) {
                            if (!visited[kid]) { visited[kid] = true; queue.add(kid); }
                        }
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (!visited[i]) { visited[i] = true; order[count++] = i; }
            }
            int[] result = new int[n];
            for (int i = 0; i < n; i++) { result[i] = order[i] == null ? i : order[i]; }
            return result;
        }
    }

    private static int lastIndex(JSONArray path) {
        return path == null || path.length() == 0 ? -1 : path.optInt(path.length() - 1, -1);
    }

    private static String pathKey(JSONArray path, int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) { builder.append('/'); }
            builder.append(path.optInt(i, -1));
        }
        return builder.toString();
    }

    private static String childKey(String parentKey, int index) {
        return parentKey == null || parentKey.isEmpty() ? String.valueOf(index)
                : parentKey + "/" + index;
    }

    private static String jsonPath(String key) {
        if (key == null || key.isEmpty()) { return "[]"; }
        return "[" + key.replace('/', ',') + "]";
    }

    /**
     * Snapshot-side equivalent of {@code AutomationHostService.SelectorSpec}: every condition in
     * {@link #mRemote} must be applied to the same node before the local filters run.
     *
     * <p>Compiled once per query (only when local filters force the snapshot path); the wire
     * {@code textMatches} regex is compiled here, not per node. Unknown wire fields fail loudly
     * instead of being silently ignored.
     *
     * <p>{@code algorithm} 只影响遍历顺序，不影响单节点匹配；真正的层序由
     * {@link Snapshot#order(boolean)} 决定，所以这里不再因为 BFS 而失败（R06）。
     */
    private static final class WireMatcher {

        private String id;
        private String text;
        private String textContains;
        private String textStartsWith;
        private Pattern textMatches;
        private String desc;
        private String descContains;
        private String className;
        private String packageName;
        private Boolean clickable;
        private Boolean enabled;
        private Boolean scrollable;
        private Integer depth;
        /** left/top/right/bottom, null when the selector has no bounds condition. */
        private int[] bounds;

        WireMatcher(JSONObject wire) throws org.json.JSONException {
            for (Iterator<String> keys = wire.keys(); keys.hasNext(); ) {
                String key = keys.next();
                switch (key) {
                    case "id": id = wire.optString(key, ""); break;
                    case "text": text = wire.optString(key, ""); break;
                    case "textContains": textContains = wire.optString(key, ""); break;
                    case "textStartsWith": textStartsWith = wire.optString(key, ""); break;
                    case "textMatches": textMatches = Pattern.compile(wire.optString(key, "")); break;
                    case "desc": desc = wire.optString(key, ""); break;
                    case "descContains": descContains = wire.optString(key, ""); break;
                    case "className": className = wire.optString(key, ""); break;
                    case "packageName": packageName = wire.optString(key, ""); break;
                    case "clickable": clickable = wire.getBoolean(key); break;
                    case "enabled": enabled = wire.getBoolean(key); break;
                    case "scrollable": scrollable = wire.getBoolean(key); break;
                    case "depth": depth = wire.getInt(key); break;
                    case "algorithm": {
                        String algorithm = wire.optString(key, "");
                        if (!ALGORITHM_BFS.equalsIgnoreCase(algorithm)
                                && !ALGORITHM_DFS.equalsIgnoreCase(algorithm)) {
                            throw new ScriptException("远程选择器 algorithm 取值无效：" + algorithm);
                        }
                        break;
                    }
                    case "bounds": {
                        JSONObject value = wire.optJSONObject(key);
                        if (value == null) {
                            throw new ScriptException("远程选择器 bounds 条件格式无效");
                        }
                        bounds = new int[]{
                                value.optInt("left", 0), value.optInt("top", 0),
                                value.optInt("right", 0), value.optInt("bottom", 0)};
                        break;
                    }
                    default:
                        throw new ScriptException("远程选择器无法在快照节点上应用字段：" + key);
                }
            }
        }

        boolean matches(JSONObject node) {
            if (id != null) {
                String fullId = node.optString("id", "");
                // The dump collapses a null view id to "". SelectorSpec rejects null ids, so an
                // absent id must not satisfy id("").
                if (fullId.isEmpty()) {
                    return false;
                }
                if (!fullId.equals(id) && !simplifyId(fullId).equals(id)) {
                    return false;
                }
            }
            String nodeText = node.optString("text", "");
            if (text != null && !nodeText.equals(text)) return false;
            if (textContains != null && !nodeText.contains(textContains)) return false;
            if (textStartsWith != null && !nodeText.startsWith(textStartsWith)) return false;
            if (textMatches != null && !textMatches.matcher(nodeText).matches()) return false;
            String nodeDesc = node.optString("desc", "");
            if (desc != null && !nodeDesc.equals(desc)) return false;
            if (descContains != null && !nodeDesc.contains(descContains)) return false;
            if (className != null && !className.equals(node.optString("className", ""))) return false;
            if (packageName != null && !packageName.equals(node.optString("packageName", ""))) {
                return false;
            }
            if (clickable != null && node.optBoolean("clickable", false) != clickable) return false;
            if (enabled != null && node.optBoolean("enabled", false) != enabled) return false;
            if (scrollable != null && node.optBoolean("scrollable", false) != scrollable) return false;
            if (depth != null && node.optInt("depth", -1) != depth) return false;
            if (bounds != null) {
                JSONArray nodeBounds = node.optJSONArray("bounds");
                if (nodeBounds == null || nodeBounds.length() != 4) {
                    return false;
                }
                for (int i = 0; i < bounds.length; i++) {
                    if (nodeBounds.optInt(i, Integer.MIN_VALUE) != bounds[i]) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Same normalization as AutomationHostService.SelectorSpec.simplifyId. */
        private static String simplifyId(String resourceName) {
            int slash = resourceName.indexOf('/');
            return slash >= 0 ? resourceName.substring(slash + 1) : resourceName;
        }
    }

    private static boolean isUiThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    private void ensureNonUiThread() {
        if (isUiThread()) {
            throw new IllegalThreadStateException(
                    "不能在ui线程执行阻塞操作, 请在子线程或子脚本执行findOne()或untilFind()");
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptInterruptedException();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ScriptInterruptedException();
        }
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private ScriptException unsupported(String api) {
        return new ScriptException("隔离进程暂不支持 Selector." + api + "()（批一未代理）");
    }

    private ScriptException unsupportedCondition(String condition) {
        return new ScriptException(
                "隔离进程暂不支持选择器条件：" + condition + "()（批一快照里没有该字段，"
                        + "请改用 text/id/desc/className/packageName/clickable/enabled/scrollable/depth/bounds）");
    }
}
