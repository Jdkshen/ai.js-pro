// @engine quickjs
"use strict";

/** QQ 象棋成绩页：两侧按钮同时匹配两帧；不把棋盘缺子/缩放本身当作结束。 */
function create(options) {
    const api = options.images, host = options.host;
    let originals = null, scaled = null, width = 0, streak = 0, closed = false;
    function releaseScaled() {
        if (scaled && scaled !== originals) scaled.forEach(function (image) { image.recycle(); });
        scaled = null;
    }
    function templates(nextWidth) {
        if (!originals) {
            originals = [];
            ["share", "replay"].forEach(function (name) {
                const image = api.read(options.baseDir + "/assets/game-over/" + name + ".png");
                if (!image) throw new Error("缺少结算页模板：" + name);
                originals.push(image);
            });
        }
        if (width !== nextWidth) {
            releaseScaled();
            scaled = nextWidth === 1080 ? originals : [];
            if (scaled !== originals) originals.forEach(function (image) {
                const size = host.sizeOf(image), ratio = nextWidth / 1080;
                scaled.push(api.resize(image, [Math.max(1, Math.round(size.width * ratio)),
                    Math.max(1, Math.round(size.height * ratio))]));
            });
            width = nextWidth;
        }
        return scaled;
    }
    return {
        detect: function (image) {
            if (closed || !image) return false;
            const size = host.sizeOf(image);
            if (!(size.height > size.width && size.width >= 480)) { streak = 0; return false; }
            const scanWidth = Math.min(540, size.width), ratio = scanWidth / size.width;
            const samples = templates(scanWidth), y = Math.floor(size.height * 0.84), half = Math.floor(scanWidth / 2);
            const bandHeight = Math.max(1, Math.round((size.height - y) * ratio));
            let crop = null, band = null;
            try {
                crop = api.clip(image, 0, y, size.width, size.height - y);
                band = ratio === 1 ? crop : api.resize(crop, [scanWidth, bandHeight]);
                const hits = samples.map(function (sample, index) {
                    const result = api.matchTemplate(band, sample, { threshold: 0.86, max: 1,
                        region: [index ? half : 0, 0, index ? scanWidth - half : half, bandHeight] });
                    return result && result.matches && result.matches[0];
                });
                const found = hits[0] && hits[1] && Math.abs(hits[0].point.y - hits[1].point.y) < size.height * ratio * 0.04;
                streak = found ? streak + 1 : 0;
                return streak >= 2;
            } finally {
                if (band && band !== crop) band.recycle();
                if (crop) crop.recycle();
            }
        },
        candidate: function () { return streak > 0; },
        close: function () {
            if (closed) return;
            closed = true;
            releaseScaled();
            if (originals) originals.forEach(function (image) { image.recycle(); });
            originals = null;
        }
    };
}

module.exports = { create: create };
