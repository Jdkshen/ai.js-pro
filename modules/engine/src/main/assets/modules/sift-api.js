// Shared real-SIFT API. Input images stay owned by their caller; path inputs are decoded by OpenCV.
(function (root) {
    function install(images, scope, javaImages) {
        var nativeApi = javaImages;
        function api() {
            if (!nativeApi) nativeApi = scope.Java.type('com.stardust.autojs.engine.QuickJsApiExtensions')
                .forEngine(Number(scope.engines.myEngine().id)).getImages();
            return nativeApi;
        }
        function withInput(input, callback) {
            if (typeof input === 'string') return callback(scope.files.path(input));
            if (!input) throw new TypeError('SIFT 输入不能为空，请传图片或本地路径');
            var borrowed = typeof input.getMat === 'function';
            if (!borrowed && typeof images.__siftNativeInput === 'function') {
                var nativeInput = images.__siftNativeInput(input);
                try { return callback(nativeInput); }
                finally { images.__releaseSiftInput(nativeInput); }
            }
            var mat = borrowed ? input.getMat() : images.toMat(input);
            try { return callback(mat); }
            finally { if (!borrowed) mat.release(); }
        }
        function optionsJson(options) {
            if (options == null) return '{}';
            if (typeof options !== 'object' || Array.isArray(options)) throw new TypeError('SIFT options 必须是对象');
            var copy = {}, numeric = ['scale', 'templateScale', 'maxFeatures', 'templateMaxFeatures',
                'threshold', 'minMatches', 'ransacThreshold', 'minAngle', 'maxAngle', 'trackPadding'];
            for (var key in options) if (Object.prototype.hasOwnProperty.call(options, key)
                    && options[key] !== undefined) copy[key] = options[key];
            for (var i = 0; i < numeric.length; i++) {
                var name = numeric[i];
                if (Object.prototype.hasOwnProperty.call(copy, name)) {
                    if (copy[name] === null || !isFinite(Number(copy[name]))) throw new TypeError('无效的 SIFT 参数：' + name);
                    copy[name] = Number(copy[name]);
                }
            }
            if (copy.region !== undefined && (!Array.isArray(copy.region)
                    || (copy.region.length !== 0 && copy.region.length !== 4))) {
                throw new TypeError('region 格式为 [x,y,width,height]');
            }
            if (copy.drawMatches !== undefined) {
                if (typeof copy.drawMatches !== 'string' || !copy.drawMatches) throw new TypeError('drawMatches 必须是非空路径');
                copy.drawMatches = scope.files.path(copy.drawMatches);
            }
            return JSON.stringify(copy);
        }
        images.matchSift = function (scene, template, options) {
            var json = optionsJson(options);
            return withInput(scene, function (sceneInput) {
                return withInput(template, function (templateInput) {
                    return JSON.parse(String(api().matchSift(sceneInput, templateInput, json)));
                });
            });
        };
        images.createSiftMatcher = function (template, options) {
            var json = optionsJson(options);
            var matcher = withInput(template, function (input) { return api().createSiftMatcher(input, json); });
            var closed = false;
            return {
                templateFeatureCount: Number(matcher.getTemplateFeatureCount()),
                templatePrepareMs: Number(matcher.getTemplatePrepareMs()),
                match: function (scene, options) {
                    if (closed) throw new Error('SIFT matcher 已回收');
                    var json = optionsJson(options);
                    return withInput(scene, function (input) { return JSON.parse(String(matcher.match(input, json))); });
                },
                recycle: function () { if (!closed) { closed = true; matcher.close(); } },
                close: function () { this.recycle(); },
                isRecycled: function () { return closed || matcher.isClosed(); }
            };
        };
        return images;
    }
    var quickJsBootstrap = root.images && typeof root.images.toMat === 'function';
    if (!quickJsBootstrap && typeof module !== 'undefined' && module.exports) module.exports = install;
    // QuickJS also defines a main CommonJS module during bootstrap. Register
    // independently of that module; Rhino calls the exported installer itself.
    if (quickJsBootstrap) {
        // Native/compat images is frozen; extend a fresh public wrapper.
        var extended = Object.assign({}, root.images);
        install(extended, root, null);
        root.images = root.$images = Object.freeze(extended);
        var modern = root.__aiModernModules && root.__aiModernModules.images;
        if (modern) root.__aiModernModules.images = Object.freeze(Object.assign({}, modern, {
            matchSift: extended.matchSift, createSiftMatcher: extended.createSiftMatcher
        }));
    }
})(typeof globalThis !== 'undefined' ? globalThis : this);
