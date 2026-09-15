
module.exports = function(__runtime__, scope){
    var rtEngines = __runtime__.engines;
    var execArgv = null;

    var engines = {};

    engines.execScript = function(name, script, config){
        return rtEngines.execScript(name, script, fillConfig(config));
    }

    engines.execScriptFile = function(path, config){
        return rtEngines.execScriptFile(path, fillConfig(config));
    }

    engines.execAutoFile = function(path, config){
        return rtEngines.execAutoFile(path, fillConfig(config));
    }

    engines.myEngine = function(){
        return rtEngines.myEngine();
    }

    // PDF §2.12（Pro 9.1.10 新增）：悬浮控制器（启动/暂停/日志按钮），
    // options.runImmediately 控制是否立即启动脚本引擎。
    engines.startFloatingController = function(path, config, options){
        if(typeof(path) !== 'string'){
            throw new TypeError('path 必须是字符串');
        }
        options = options || {};
        return rtEngines.startFloatingController(path, fillConfig(config), !!options.runImmediately);
    }

    engines.all = function(){
        return rtEngines.all();
    }

    engines.stopAll = rtEngines.stopAll.bind(rtEngines);
    engines.stopAllAndToast = rtEngines.stopAllAndToast.bind(rtEngines);

    // PDF §2.12：config.path 可以是 string 或 Array（模块查找路径）。
    // ExecutionConfig.path(String[]) 就是文档里 ScriptConfig.getPath() 返回的那个数组。
    function toPathArray(path){
        var list = (path instanceof Array) ? path : [path];
        var array = java.lang.reflect.Array.newInstance(java.lang.String, list.length);
        for(var i = 0; i < list.length; i++){
            array[i] = String(list[i]);
        }
        return array;
    }

    function fillConfig(c){
        var config = new com.stardust.autojs.execution.ExecutionConfig();
        c = c || {};
        c.path = c.path || files.cwd();
        if(c.path){
           var paths = toPathArray(c.path);
           config.path = paths;
           if(paths.length > 0){
               config.workingDirectory = String(paths[0]);
           }
        }
        config.delay = c.delay || 0;
        config.interval = c.interval || 0;
        config.loopTimes = (c.loopTimes === undefined)? 1 : c.loopTimes;
        if(c.arguments){
            var arguments = c.arguments;
            for(var key in arguments){
                if(arguments.hasOwnProperty(key)){
                    config.setArgument(key, arguments[key]);
                }
            }
        }
        return config;
    }

    var engine = engines.myEngine();
    var execArgv = {};
    var iterator = engine.getTag("execution.config").arguments.entrySet().iterator();
    while(iterator.hasNext()){
        var entry = iterator.next();
        execArgv[entry.getKey()] = entry.getValue();
    }
    engine.execArgv = execArgv;

    return engines;
}