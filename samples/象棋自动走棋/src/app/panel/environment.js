// @engine quickjs
"use strict";

/** environment：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /** 32 位 FNV-1a，避免依赖 crypto（QuickJS 里不一定有）。 */
    function cheapHash(text) {
        let hash = 0x811c9dc5;
        for (let i = 0; i < text.length; i++) {
            hash ^= text.charCodeAt(i) & 0xff;
            hash = (hash * 0x01000193) >>> 0;
        }
        return ("00000000" + hash.toString(16)).slice(-8);
    }

    /** 关键文件的「名字:指纹」，后面接在启动日志里（adb 那边比对本地副本即可）。 */
    function probeFingerprint() {
        const parts = [];
        for (let i = 0; i < panelState.PROBE_FILES.length; i++) {
            const name = panelState.PROBE_FILES[i];
            try {
                const text = String(
                    files.read(panelState.BASE_DIR === "." ? name : panelState.BASE_DIR + "/" + name) || ""
                );
                parts.push(name.split("/").pop() + ":" + (text ? cheapHash(text) : "缺失"));
            } catch (error) {
                parts.push(name.split("/").pop() + ":读取失败");
            }
        }
        return parts.join(" ");
    }

    function load(relative) {
        return require(panelState.BASE_DIR === "." ? relative : panelState.BASE_DIR + "/" + relative);
    }

    function panelPx(base) {
        return Math.max(1, Math.round(base * panelState.panelLayout.scale));
    }

    /**
     * 临时文件目录。**不要再写死 /sdcard/xiangqi-p4**，但也不能全放工程目录里：
     *
     *  · autorun 配置只有脚本自己读写 → 跟着工程走（工程目录下的 .floaty-panel/），
     *    应用内示例副本在 cacheDir/resource-run、独立 APK 在 filesDir/project、用户导入在脚本目录，
     *    放外部写死路径就会「面板和工程分家」；
     *  · 截图文件是**用 shell（uid 2000 / Shizuku）写的** → shell 写不进应用私有目录
     *    （/data/user/0/...）：写失败就静默退化成应用截屏通道（还要授权）。
     *    所以它必须落在 /sdcard 下的可写目录。
     */
    function ensureDir(dir) {
        try {
            if (typeof files.ensureDir === "function") {
                files.ensureDir(dir + "/");
            }
            return files.exists(dir) || files.isDir(dir);
        } catch (error) {
            return false;
        }
    }

    function pickScratchDir() {
        // 1) 应用缓存目录优先：一定可写，而且**不会被「打包（项目）」拷进产物** ——
        //    放工程目录里会在产物 assets 里多出 .floaty-panel/（一堆截图 + 空目录条目）。
        try {
            if (typeof context !== "undefined" && context && typeof context.getCacheDir === "function") {
                const cached = files.join(String(context.getCacheDir()), "floaty-panel");
                if (ensureDir(cached)) {
                    return cached;
                }
            }
        } catch (error) {
            /* 试下一个 */
        }
        // 2) 退而求其次：工程目录（脚本自己那层），至少「跟着工程走」
        const projectLocal = files.join(panelState.BASE_DIR, ".floaty-panel");
        if (ensureDir(projectLocal)) {
            return projectLocal;
        }
        // 3) 最后兜底：老路径（PC 侧脚本也认它）
        ensureDir("/sdcard/xiangqi-p4");
        return "/sdcard/xiangqi-p4";
    }

    function pickFrameDir() {
        let sdcard = "/sdcard";
        try {
            if (typeof files.getSdcardPath === "function") {
                sdcard = String(files.getSdcardPath());
            }
        } catch (error) {
            /* 用默认值 */
        }
        const dir = files.join(sdcard, ".aijspro-floaty");
        if (ensureDir(dir)) {
            return dir;
        }
        ensureDir("/sdcard/xiangqi-p4");
        return "/sdcard/xiangqi-p4";
    }

    function stageMark(name) {
        panelState.stageSeq += 1;
        console.log(
            "[stage] #" + panelState.stageSeq + " +" + (Date.now() - panelState.SCRIPT_STARTED_AT) + "ms " + name
        );
    }

    return {
        cheapHash: cheapHash,
        probeFingerprint: probeFingerprint,
        load: load,
        panelPx: panelPx,
        ensureDir: ensureDir,
        pickScratchDir: pickScratchDir,
        pickFrameDir: pickFrameDir,
        stageMark: stageMark
    };
}

module.exports = { create: create };
