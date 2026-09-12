
module.exports = function (__runtime__, scope) {
    var threads = Object.create(__runtime__.threads);


    scope.sync = function (func, lock) {
        lock = lock || null;
        return new org.mozilla.javascript.Synchronizer(func, lock);
    }

    /**
     * Auto.js Pro 的 $threads.pool({corePoolSize, maxPoolSize})
     * 任务函数在脚本线程上执行，因此 log / sleep / threads.currentThread() 都能直接用。
     */
    threads.pool = function (options) {
        options = options || {};
        var core = Number(options.corePoolSize);
        var max = Number(options.maxPoolSize);
        if (isNaN(core) || core < 0) {
            core = 0;
        }
        if (isNaN(max) || max < core) {
            max = core;
        }
        var javaPool = __runtime__.threads.createPool(core, max);
        var pool = {
            __javaPool: javaPool,
            execute: function (task) {
                if (typeof task !== 'function') {
                    throw new TypeError('线程池任务必须是函数');
                }
                javaPool.execute(task);
                return pool;
            },
            shutdown: function () {
                javaPool.shutdown();
            },
            shutdownNow: function () {
                javaPool.shutdownNow();
            },
            isShutdown: function () {
                return javaPool.isShutdown();
            }
        };
        Object.defineProperty(pool, 'corePoolSize', { get: function () { return javaPool.getCorePoolSize(); } });
        Object.defineProperty(pool, 'maxPoolSize', { get: function () { return javaPool.getMaximumPoolSize(); } });
        Object.defineProperty(pool, 'poolSize', { get: function () { return javaPool.getPoolSize(); } });
        Object.defineProperty(pool, 'activeCount', { get: function () { return javaPool.getActiveCount(); } });
        Object.defineProperty(pool, 'completedTaskCount', { get: function () { return javaPool.getCompletedTaskCount(); } });
        return pool;
    }

    global.Promise.prototype.wait = function () {
        var disposable = threads.disposable();
        this.then(result => {
            disposable.setAndNotify({ result: result });
        }).catch(error => {
            disposable.setAndNotify({ error: error });
        });
        var r = disposable.blockedGet();
        if (r.error) {
            throw r.error;
        }
        return r.result;
    }

    return threads;
}