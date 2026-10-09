module.exports = function (runtime, scope) {
    function plugins(){
    }

    plugins.load = function(packageName){
        var plugin;
        try {
            plugin = runtime.plugins.load(packageName);
        } catch (error) {
            // The official legacy ML Kit plugin can use our bundled offline model when absent.
            if (String(packageName) === 'org.autojs.autojspro.plugin.mlkit.ocr'
                    && String(error).indexOf('NameNotFoundException') >= 0) {
                return BuiltInMLKitOCR;
            }
            throw error;
        }
        var index = require(plugin.mainScriptPath);
        return index(wrapPlugin(plugin));
    }

    /**
     * 插件对象：官方文档里给入口脚本的就是这一层（unwrap/getVersion/getAssetsScriptDir/
     * getMainScriptPath），所以按文档写的插件在本仓库也能直接跑。
     * 这里传过的是宿主 Plugin 包装对象；原生/胶水实例仍然只从 unwrap() 拿。
     */
    function wrapPlugin(plugin){
        return {
            mainScriptPath: plugin.mainScriptPath,
            getMainScriptPath: function(){ return plugin.mainScriptPath; },
            getAssetsScriptDir: function(){ return plugin.getAssetsScriptDir(); },
            getVersion: function(){
                try {
                    if (typeof plugin.getVersion === 'function') return plugin.getVersion();
                } catch (ignore) { /* 宿主包装对象没有版本号 */ }
                try {
                    var instance = plugin.unwrap();
                    if (instance && typeof instance.getVersion === 'function') return instance.getVersion();
                } catch (ignore2) { /* 插件自己的 SDK 也没报版本号 */ }
                return '';
            },
            unwrap: function(){ return plugin.unwrap(); }
        };
    }

    function BuiltInMLKitOCR() {
        var session = scope.$ocr.create({ backend: 'mlkit' });
        this.detect = function(image, options) { return session.detect(image, options); };
        this.release = function() { return session.release(); };
        this.isReleased = function() { return session.isReleased(); };
    }

    return plugins;
}
