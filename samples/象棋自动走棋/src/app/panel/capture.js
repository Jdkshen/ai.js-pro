// @engine quickjs
"use strict";

/** capture：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function beginFrameCapture(options) {
        if (typeof panelActions.prepareContinuousMoveCapture === "function") panelActions.prepareContinuousMoveCapture();
        const visibility = panelActions.beginCaptureVisibility(options);
        try {
            if (visibility.hidden || visibility.clearedDrawing) sleep(90);
            return visibility;
        } catch (error) {
            visibility.release();
            throw error;
        }
    }

    function diffGeometryKey() {
        return panelState.grid
            ? [panelState.grid.originX, panelState.grid.originY, panelState.grid.stepX, panelState.grid.stepY].join(",")
            : "";
    }
    /**
     * 抓一帧（尊重截图偏好；auto 由适配器负责系统截屏 ↔ shell 故障切换）。
     *
     * 抓帧期间可能要先把面板藏起来（面板浮在棋盘上会把上几行子盖掉）——
     * 但**藏+等 90ms+恢复** 每帧要花约 100ms，所以默认 "auto"：面板不在棋盘上就不藏。
     *
     * 自动走棋的连续箭头使用窗口截图：保留绘制内容与窗口，不逐帧清屏。
     */
    function grabFrame() {
        const visibility = beginFrameCapture();
        try {
            return rememberRecentFrame(panelActions.grabFrameRaw());
        } finally {
            visibility.release();
        }
    }

    /** 全屏粗采样（无几何时用；返回灰度数组，采样不可用返回 null）。 */
    function fullFrameSamples(image) {
        if (!image || typeof panelState.adapter.rgbAt !== "function") {
            return null;
        }
        let size = null;
        try {
            size = panelState.adapter.sizeOf(image);
        } catch (error) {
            size = null;
        }
        if (!size || !size.width || !size.height) {
            return null;
        }
        const step = 80;
        const out = [];
        for (let y = Math.floor(step / 2); y < size.height; y += step) {
            for (let x = Math.floor(step / 2); x < size.width; x += step) {
                const px = panelState.adapter.rgbAt(image, x, y);
                if (px) {
                    out.push((Number(px[0]) + Number(px[1]) + Number(px[2])) / 3);
                }
            }
        }
        return out.length ? out : null;
    }

    /** 稳定帧采样：有几何走棋盘区域，没有走全屏。 */
    function stableGrabSamples(image) {
        if (panelState.grid) {
            try {
                const s = panelActions.boardRegionSamples(image, panelState.grid);
                if (s && s.length) {
                    return s;
                }
            } catch (error) {
                // 采样失败退回全屏
            }
        }
        return fullFrameSamples(image);
    }

    /**
     * 抓一帧，并等到「画面静止」才返回。
     * @param options { maxTries?（默认 4）, gapMs?（默认 130）, maxMeanDiff?（默认 6）, strict?（等不到返回 null）, hide? }
     * @return { image, path, channel, stable, meanDiff, tries } 或 null
     */
    function grabStableFrame(options) {
        const opts = options || {};
        const maxTries = opts.maxTries || panelState.STABLE_GRAB_TRIES;
        const gapMs = opts.gapMs == null ? panelState.STABLE_GRAB_GAP_MS : opts.gapMs;
        const maxDiff = opts.maxMeanDiff == null ? panelState.STABLE_GRAB_DIFF : opts.maxMeanDiff;
        const visibility = beginFrameCapture({ hide: opts.hide });
        let frame = null;
        let prevSamples = null;
        let meanDiff = null;
        let comparisons = 0;
        let tries = 0;
        let lastSamples = null;
        try {
            for (tries = 1; tries <= maxTries; tries++) {
                if (tries > 1 && gapMs > 0) {
                    sleep(gapMs);
                }
                const next = panelActions.grabFrameRaw();
                if (!next) {
                    break;
                }
                frame = rememberRecentFrame(next); // 每帧都进缓存：自动释放上一帧，不漏句柄
                const samples = stableGrabSamples(next.image);
                lastSamples = samples;
                if (prevSamples && samples) {
                    comparisons += 1;
                    meanDiff = panelState.frameGateModule.meanAbsDiff(prevSamples, samples);
                    if (meanDiff != null && isFinite(meanDiff) && meanDiff <= maxDiff) {
                        break; // 就这一帧：稳了
                    }
                }
                prevSamples = samples;
            }
        } finally {
            visibility.release();
        }
        if (!frame) {
            return null;
        }
        const stable = meanDiff != null && isFinite(meanDiff) && meanDiff <= maxDiff;
        if (opts.strict && comparisons > 0 && !stable) {
            // 严格模式：宁可不返回，也不把「还在动」的画面交给定位/校准（16:16 的教训）
            panelActions.logLine(
                "等画面稳定：" +
                    maxTries +
                    " 帧内一直在动（最后平均差 " +
                    (meanDiff == null ? "?" : meanDiff.toFixed(1)) +
                    "）→ 这一帧不用（稍后重试）"
            );
            return null;
        }
        const now = Date.now();
        if (!stable && comparisons > 0 && tries > 1 && now - panelState.lastStableGrabNoteAt > 4000) {
            panelState.lastStableGrabNoteAt = now;
            panelActions.logLine(
                "等画面稳定：" +
                    tries +
                    " 帧内没完全稳住（最后平均差 " +
                    (meanDiff == null ? "?" : meanDiff.toFixed(1)) +
                    "，先按这帧用）"
            );
        } else if (stable && tries >= 3 && now - panelState.lastStableGrabNoteAt > 4000) {
            panelState.lastStableGrabNoteAt = now;
            panelActions.logLine("等画面稳定：第 " + tries + " 帧才稳住（平均差 " + meanDiff.toFixed(1) + "）");
        }
        if (lastSamples) {
            panelState.lastSettledSamples = { samples: lastSamples, key: panelActions.diffGeometryKey() }; // 给「等变化」当参照
        }
        return {
            image: frame.image,
            path: frame.path,
            channel: frame.channel,
            captureMetadata: frame.captureMetadata || null,
            stable: stable,
            meanDiff: meanDiff,
            tries: tries
        };
    }

    /**
     * @param options { changeBudgetMs?（默认 800，变化探测预算）, settleTries?（默认 4）,
     *                   gapMs?（默认 120）, maxMeanDiff?（默认 6，与稳定帧同一把尺）,
     *                   allowAbort?（ true 时用户点停止可打断等待）, hide? }
     * @return { image, path, channel, changed（看到变化没）, stable（变化后稳住了没）,
     *           meanDiff, tries, aborted } 或 null
     */
    function grabSettledFrame(options) {
        const opts = options || {};
        const changeBudgetMs = opts.changeBudgetMs == null ? panelState.SETTLED_CHANGE_BUDGET_MS : opts.changeBudgetMs;
        const settleTries = opts.settleTries || panelState.SETTLED_SETTLE_TRIES;
        const gapMs = opts.gapMs == null ? panelState.SETTLED_CHANGE_GAP_MS : opts.gapMs;
        const maxDiff = opts.maxMeanDiff == null ? panelState.STABLE_GRAB_DIFF : opts.maxMeanDiff;
        const visibility = beginFrameCapture({ hide: opts.hide });
        const key = panelActions.diffGeometryKey();
        const reference =
            panelState.lastSettledSamples && panelState.lastSettledSamples.key === key
                ? panelState.lastSettledSamples.samples
                : null;
        const startedAt = Date.now();
        const deadline = startedAt + changeBudgetMs;
        let frame = null;
        let lastSamples = null;
        let changed = false;
        let stable = false;
        let meanDiff = null;
        let tries = 0;
        let settleFrames = 0;
        let aborted = false;
        let lastProgressAt = startedAt;
        try {
            // ① 探测变化：任何一帧与「上一次观察到的画面」差异明显 ⇒ 画面动过了
            let prevSamples = null;
            for (;;) {
                if (tries > 0 && gapMs > 0) {
                    sleep(gapMs);
                }
                if (opts.allowAbort === true && !panelState.running) {
                    aborted = true; // 用户点了停止：别把这次等待拖过停止
                    break;
                }
                const next = panelActions.grabFrameRaw();
                if (!next) {
                    break;
                }
                frame = rememberRecentFrame(next);
                const samples = stableGrabSamples(next.image);
                lastSamples = samples;
                tries += 1;
                const against = reference || prevSamples;
                if (against && samples && against.length === samples.length) {
                    meanDiff = panelState.frameGateModule.meanAbsDiff(against, samples);
                    if (meanDiff != null && isFinite(meanDiff) && meanDiff >= maxDiff) {
                        changed = true;
                        break;
                    }
                }
                if (!reference) {
                    prevSamples = prevSamples || samples; // 还没有参照：第一帧当参照
                }
                if (Date.now() >= deadline) {
                    break;
                }
                if (tries % 4 === 0 && Date.now() - lastProgressAt >= 5000) {
                    lastProgressAt = Date.now();
                    panelActions.logLine(
                        "等画面动：已等 " + Math.round((Date.now() - startedAt) / 1000) + "s（轮到对方，等其落子）"
                    );
                }
            }
            // ② 等静止：变化发生后，等到连续两帧基本无差异（动画结束）
            if (changed && frame) {
                let stillPrev = lastSamples;
                for (let s = 0; s < settleTries; s++) {
                    if (gapMs > 0) {
                        sleep(gapMs);
                    }
                    const next2 = panelActions.grabFrameRaw();
                    if (!next2) {
                        break;
                    }
                    frame = rememberRecentFrame(next2);
                    const samples2 = stableGrabSamples(next2.image);
                    lastSamples = samples2;
                    settleFrames += 1;
                    if (stillPrev && samples2) {
                        const d = panelState.frameGateModule.meanAbsDiff(stillPrev, samples2);
                        if (d != null && isFinite(d) && d <= maxDiff) {
                            stable = true;
                            break;
                        }
                    }
                    stillPrev = samples2;
                }
            }
        } finally {
            visibility.release();
        }
        if (!frame) {
            return null;
        }
        if (lastSamples) {
            panelState.lastSettledSamples = { samples: lastSamples, key: key }; // 下一次的参照
        }
        const now = Date.now();
        if (changed) {
            if (now - panelState.lastChangedGrabNoteAt > 2000) {
                panelState.lastChangedGrabNoteAt = now;
                panelActions.logLine(
                    "等画面动：第 " +
                        tries +
                        " 帧看到变化（等了 " +
                        Math.round((now - startedAt) / 1000) +
                        "s）" +
                        (stable ? "，又等 " + settleFrames + " 帧稳住" : "（动画还没停，照常返回）")
                );
            }
        } else if (!aborted && tries > 1 && now - panelState.lastChangedGrabNoteAt > 4000) {
            panelState.lastChangedGrabNoteAt = now;
            panelActions.logLine("等画面动：等满 " + Math.round((now - startedAt) / 1000) + "s 画面没动（照常返回）");
        }
        return {
            image: frame.image,
            path: frame.path,
            channel: frame.channel,
            captureMetadata: frame.captureMetadata || null,
            changed: changed,
            stable: stable,
            meanDiff: meanDiff,
            tries: tries,
            aborted: aborted
        };
    }

    /**
     * 清掉最近帧并释放原生句柄。
     * AI.js Pro 的 NativeFrame 存在 C++ FrameStore；丢掉 JS 引用不会自动 recycle，长循环必须显式释放。
     */
    function clearRecentFrame() {
        const cached = panelState.recentFrame;
        panelState.recentFrame = null; // 先断开引用，避免 recycle 报错时留下半失效缓存
        if (cached && cached.image) {
            try {
                panelState.adapter.releaseFrame(cached.image);
            } catch (error) {
                // 缓存释放失败不能中断识别循环；原生帧统计会暴露持续增长。
            }
        }
    }

    function rememberRecentFrame(frame) {
        if (frame && frame.image) {
            // grabFrame() 的调用方只借用 image；recentFrame 是唯一所有者。
            // 下一帧接管缓存前先释放旧帧，避免 NativeFrameStore 随观察次数线性增长。
            if (!panelState.recentFrame || panelState.recentFrame.image !== frame.image) {
                clearRecentFrame();
            }
            panelState.recentFrame = { image: frame.image, at: Date.now(), geometry: panelActions.diffGeometryKey() };
        }
        return frame;
    }

    function recentFrameImage() {
        if (!panelState.recentFrame) {
            return null;
        }
        if (Date.now() - panelState.recentFrame.at > panelState.FRAME_REUSE_MS) {
            clearRecentFrame();
            return null;
        }
        if (panelState.recentFrame.geometry !== panelActions.diffGeometryKey()) {
            clearRecentFrame();
            return null;
        }
        return panelState.recentFrame.image;
    }

    function nativeFrameStatsText() {
        try {
            if (typeof __aiNativeFrameStats === "function") {
                const stats = __aiNativeFrameStats();
                return (
                    "｜原生帧 活跃 " +
                    stats.activeFrames +
                    "，池 " +
                    stats.poolCount +
                    "（" +
                    Math.round(stats.poolBytes / 1048576) +
                    "MB）"
                );
            }
        } catch (error) {
            // 统计能力不可用不影响业务。
        }
        return "";
    }

    /** 帧生命周期探针：读一眼当前活跃原生帧数（能力不可用返回 -1）。 */
    function frameProbeActive() {
        try {
            if (typeof __aiNativeFrameStats === "function") {
                return __aiNativeFrameStats().activeFrames;
            }
        } catch (error) {
            // 探针不可用不影响业务。
        }
        return -1;
    }

    return {
        diffGeometryKey: diffGeometryKey,
        grabFrame: grabFrame,
        fullFrameSamples: fullFrameSamples,
        stableGrabSamples: stableGrabSamples,
        grabStableFrame: grabStableFrame,
        grabSettledFrame: grabSettledFrame,
        clearRecentFrame: clearRecentFrame,
        rememberRecentFrame: rememberRecentFrame,
        recentFrameImage: recentFrameImage,
        nativeFrameStatsText: nativeFrameStatsText,
        frameProbeActive: frameProbeActive
    };
}

module.exports = { create: create };
