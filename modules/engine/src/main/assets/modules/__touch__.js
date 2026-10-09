module.exports = function(runtime) {
    function integer(value, name) {
        if (typeof value !== 'number' || !isFinite(value) || Math.floor(value) !== value || value < 0 || value > 2147483647) {
            throw new TypeError(name + ' 必须是非负整数');
        }
        return value;
    }
    return {
        create: function(options) {
            options = options || {};
            var backend = options.backend === undefined ? 'root' : String(options.backend);
            if (backend !== 'root' && backend !== 'adb' && backend !== 'shizuku') throw new Error('未知触控通道: ' + backend);
            var session = new com.stardust.autojs.runtime.api.TouchSession(runtime, backend !== 'root');
            return {
                down: function(id, x, y) { session.down(integer(id, 'id'), integer(x, 'x'), integer(y, 'y')); },
                move: function(id, x, y) { session.move(integer(id, 'id'), integer(x, 'x'), integer(y, 'y')); },
                up: function(id) { session.up(integer(id, 'id')); },
                pointerCount: function() { return session.pointerCount(); },
                isClosed: function() { return session.isClosed(); },
                isAdb: function() { return session.isAdb(); },
                exit: function() { session.exit(); }
            };
        }
    };
};
