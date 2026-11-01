(function (global) {
    'use strict';
    function create(scope) {
        function backend() { return scope.Packages.com.stardust.autojs.runtime.api.shizuku.ShizukuFacade; }
        function timeout(value) {
            if (value === undefined) return 60000;
            if (typeof value !== 'number' || !isFinite(value) || Math.floor(value) !== value || value < 1000 || value > 120000)
                throw new TypeError('授权timeout必须为1000..120000毫秒整数');
            return value;
        }
        return Object.freeze({
            isAvailable: function () { return !!backend().isAvailable(); },
            hasPermission: function () { return !!backend().hasPermission(); },
            getState: function () { return JSON.parse(String(backend().stateJson())); },
            requestPermission: function (value) { return !!backend().requestPermission(timeout(value)); },
            shell: function (command, options) {
                if (typeof command !== 'string') throw new TypeError('command必须为字符串');
                if (options !== undefined && (options === null || typeof options !== 'object' || Array.isArray(options)))
                    throw new TypeError('options必须为对象');
                return JSON.parse(String(backend().executeJson(command, JSON.stringify(options || {}))));
            }
        });
    }
    // QuickJS loads this asset after its Java package bridge; Rhino uses the factory.
    if (typeof __aiNativeShellIsShizukuAvailable === 'function') global.shizuku = create(global);
    else if (typeof module !== 'undefined') module.exports = create;
})(typeof globalThis !== 'undefined' ? globalThis : this);
