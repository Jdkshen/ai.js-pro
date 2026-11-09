package com.stardust.autojs.core.image;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.opencv.Mat;
import com.stardust.autojs.core.opencv.MatOfPoint;
import com.stardust.util.ResourceMonitor;

import org.opencv.core.Point;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 作用域图像资源池：**借出 → 归还 → 上限 → 回收时机**。
 *
 * <p>行为对齐 Pro 9.3.11 的 {@code com.stardust.autojs.core.image.ImagePool}（其用法见
 * {@code ColorFinder}：中间 Mat 全部登记进池，作用域结束一次性 release），但字段与实现按行为重写：
 *
 * <ul>
 *   <li><b>借</b>：{@link #mat()} / {@link #mat(int, int, int)} / {@link #mat(Mat, Rect)} /
 *       {@link #matOfPoint(Mat)} / {@link #matOfPoint(Point...)} / {@link #make}。
 *       任何经池登记的资源都会被记住（含分配点，便于护栏报出「谁没还」）。</li>
 *   <li><b>还</b>：{@link #recycle} 立刻 release 并摘除（幂等：重复归还不会二次 release）。</li>
 *   <li><b>上限</b>：未归还数超过 {@link #maxTracked()} 时**报警一次**并计数，继续登记
 *       （不静默丢弃，也不在作用域内强制 release 正在使用的资源）。</li>
 *   <li><b>回收时机</b>：{@link #release()}（Pro 同名方法，释放后池仍可用）与 {@link #close()}
 *       （AutoCloseable，配合 try-with-resources）；两者都把「还没归还」的资源强制 release，
 *       计入 {@link #forced()}。</li>
 *   <li><b>未回收护栏</b>：{@link ImagePools#drainAll(String)} 在脚本结束（引擎既有时序点）把所有
 *       仍打开池的未归还资源强制回收 + 打印警告（带分配点与可抄提示）。</li>
 * </ul>
 *
 * <p>与 Pro 的差异（有意，且更安全）：
 * <ol>
 *   <li>Pro 的 {@code release()} 不判重，会把同一 Mat 释放两次；这里 release/close 幂等。</li>
 *   <li>Pro 的 {@code make(T)} 直接按具体类型（Mat / MatOfPoint）泛型约束，因为 Kotlin 侧那两个类
 *       共有一个 {@code OpenCvMat} 接口。我们的 {@code Mat} / {@code MatOfPoint} 没有共同父接口，
 *       所以这里的 {@link #make} 额外收一个 {@link Releaser}（工厂方法已经替你传好），
 *       池本身仍按 {@link ResourceMonitor.Resource} 记账（这样护栏能报出资源 id）。</li>
 * </ol>
 */
public class ImagePool implements AutoCloseable {

    /** 未归还资源的默认上限（超过只报警一次，不丢弃）。 */
    public static final int DEFAULT_MAX_TRACKED = 64;

    /** 释放动作：{@code Mat} / {@code MatOfPoint} 的 {@code release()} 形状。 */
    public interface Releaser {
        void release();
    }

    private static final class Entry {
        final ResourceMonitor.Resource resource;
        final Releaser releaser;
        final String site;

        Entry(ResourceMonitor.Resource resource, Releaser releaser, String site) {
            this.resource = resource;
            this.releaser = releaser;
            this.site = site;
        }
    }

    private final Object mLock = new Object();
    private final ArrayList<Entry> mEntries = new ArrayList<>();
    private final int mMaxTracked;
    private boolean mClosed;
    private boolean mOverflowReported;
    private long mBorrowed;
    private long mReturned;
    private long mForced;
    private long mOverflow;

    public ImagePool() {
        this(DEFAULT_MAX_TRACKED);
    }

    public ImagePool(int maxTracked) {
        mMaxTracked = Math.max(1, maxTracked);
        ImagePools.register(this);
    }

    // ------------------------------------------------------------------
    // 借
    // ------------------------------------------------------------------

    /**
     * 登记一个资源（Pro 的同名入口）。
     *
     * @param resource 受 {@link ResourceMonitor} 观测的资源（Mat / MatOfPoint 都满足）
     * @param releaser 归还/强制回收时调用的释放动作（通常就是 {@code resource::release}）
     * @return 同一个 {@code resource}，便于链式写法
     */
    public <T extends ResourceMonitor.Resource> T make(T resource, Releaser releaser) {
        if (resource == null) {
            return null;
        }
        String site = ImagePools.allocationSite();
        boolean overflow = false;
        int pending;
        synchronized (mLock) {
            if (mClosed) {
                throw new IllegalStateException("ImagePool 已经 close()，不能再登记新资源");
            }
            mEntries.add(new Entry(resource, releaser, site));
            mBorrowed++;
            pending = mEntries.size();
            if (pending > mMaxTracked) {
                mOverflow++;
                if (!mOverflowReported) {
                    mOverflowReported = true;
                    overflow = true;
                }
            }
        }
        if (overflow) {
            ImagePools.report("[ImagePool] 上限：未归还资源已有 " + pending + " 个，超过上限 "
                    + mMaxTracked + "（继续登记，不丢弃）；请检查是否忘了 recycle()。"
                    + "可用 ImagePools.stats() 看池统计。");
        }
        return resource;
    }

    public Mat mat() {
        Mat mat = new Mat();
        make(mat, mat::release);
        return mat;
    }

    public Mat mat(int rows, int cols, int type) {
        Mat mat = new Mat(rows, cols, type);
        make(mat, mat::release);
        return mat;
    }

    public Mat mat(Mat source, Rect roi) {
        Mat mat = new Mat(source, roi);
        make(mat, mat::release);
        return mat;
    }

    public MatOfPoint matOfPoint(Mat source) {
        MatOfPoint points = new MatOfPoint(source);
        make(points, points::release);
        return points;
    }

    public MatOfPoint matOfPoint(Point... points) {
        MatOfPoint mat = new MatOfPoint(points);
        make(mat, mat::release);
        return mat;
    }

    // ------------------------------------------------------------------
    // 还 / 回收时机
    // ------------------------------------------------------------------

    /**
     * 归还一个资源：立刻 release 并从池里摘除。
     *
     * @return 该资源是否由本池登记且本次真的归还了（重复归还 / 不属于本池 → {@code false}，
     *         且不会二次 release）
     */
    public boolean recycle(@Nullable ResourceMonitor.Resource resource) {
        if (resource == null) {
            return false;
        }
        Entry found = null;
        synchronized (mLock) {
            for (int i = 0; i < mEntries.size(); i++) {
                if (mEntries.get(i).resource == resource) {
                    found = mEntries.remove(i);
                    mReturned++;
                    break;
                }
            }
        }
        if (found == null) {
            return false;
        }
        releaseQuietly(found);
        return true;
    }

    /** Pro 的 {@code release()}：把还没归还的资源全部强制 release；池之后仍可继续使用。 */
    public void release() {
        drainEntries(false);
    }

    /** try-with-resources / 明确结束作用域用；幂等。 */
    @Override
    public void close() {
        boolean firstClose;
        synchronized (mLock) {
            firstClose = !mClosed;
            mClosed = true;
        }
        if (!firstClose) {
            return;
        }
        release();
        ImagePools.unregister(this);
    }

    /** 护栏用：强制释放未归还资源并返回它们的分配点（供报警文案使用）。 */
    List<String> drainUnreturned() {
        return drainEntries(true);
    }

    private List<String> drainEntries(boolean collectSites) {
        List<Entry> drained;
        synchronized (mLock) {
            if (mEntries.isEmpty()) {
                return new ArrayList<>();
            }
            drained = new ArrayList<>(mEntries);
            mEntries.clear();
            mForced += drained.size();
        }
        List<String> sites = new ArrayList<>(drained.size());
        for (Entry entry : drained) {
            sites.add(entry.site != null
                    ? entry.site
                    : ("resourceId=" + entry.resource.getResourceId()));
            releaseQuietly(entry);
        }
        return collectSites ? sites : new ArrayList<>();
    }

    private static void releaseQuietly(Entry entry) {
        if (entry.releaser == null) {
            return;
        }
        try {
            entry.releaser.release();
        } catch (Throwable error) {
            // 一个资源释放失败不能挡住其余资源（否则护栏反而制造泄漏）
            ImagePools.report("[ImagePool] release 失败（继续释放其余资源）：" + error);
        }
    }

    // ------------------------------------------------------------------
    // 统计 / 验收口径
    // ------------------------------------------------------------------

    /** 池里还没归还的资源数。 */
    public int pendingCount() {
        synchronized (mLock) {
            return mEntries.size();
        }
    }

    public int maxTracked() {
        return mMaxTracked;
    }

    public boolean isClosed() {
        synchronized (mLock) {
            return mClosed;
        }
    }

    /** 累计登记（借出）数。 */
    public long borrowed() {
        synchronized (mLock) {
            return mBorrowed;
        }
    }

    /** 累计经 {@link #recycle} 归还数。 */
    public long returned() {
        synchronized (mLock) {
            return mReturned;
        }
    }

    /** 累计由池/护栏/close 强制释放数（正常归还的不计）。 */
    public long forced() {
        synchronized (mLock) {
            return mForced;
        }
    }

    /** 超过上限的次数（每次登记只 +1；报警只发一次）。 */
    public long overflow() {
        synchronized (mLock) {
            return mOverflow;
        }
    }

    /** 单行统计（日志/验收可抄）。 */
    public String stats() {
        synchronized (mLock) {
            StringBuilder builder = new StringBuilder(96);
            builder.append('{');
            appendStat(builder, "pending", mEntries.size(), false);
            appendStat(builder, "borrowed", mBorrowed, true);
            appendStat(builder, "returned", mReturned, true);
            appendStat(builder, "forced", mForced, true);
            appendStat(builder, "overflow", mOverflow, true);
            appendStat(builder, "maxTracked", mMaxTracked, true);
            appendStat(builder, "closed", mClosed, true);
            return builder.append('}').toString();
        }
    }

    private static void appendStat(StringBuilder builder, String key, long value, boolean comma) {
        if (comma) {
            builder.append(',');
        }
        builder.append('"').append(key).append('"').append(':').append(value);
    }

    private static void appendStat(StringBuilder builder, String key, boolean value, boolean comma) {
        if (comma) {
            builder.append(',');
        }
        builder.append('"').append(key).append('"').append(':').append(value);
    }
}
