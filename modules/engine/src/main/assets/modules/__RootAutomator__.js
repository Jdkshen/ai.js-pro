module.exports = function(__runtime__, scope){
    function RootAutomator(inputDevice, nonBlockingForReady){
        inputDevice = inputDevice == undefined ? null : inputDevice;
        this.__ra__ = Object.create(new com.stardust.autojs.core.inputevent.RootAutomator(scope.context, inputDevice, !nonBlockingForReady));
        var methods = ["sendEvent", "touch", "setScreenMetrics", "touchX", "touchY", "sendSync",  "sendMtSync", "tap",
            "swipe", "press", "longPress", "touchDown", "touchUp", "touchMove", "getDefaultId", "setDefaultId", "exit"];
        for(var i = 0; i < methods.length; i++){
            var method = methods[i];
            this[method] = this.__ra__[method].bind(this.__ra__);
        }
        return this;
   }
    var p = RootAutomator.prototype;

    // PDF §2.2.3（第 40~45 页）：RootAutomator2 —— 可以选 root 或 adb(Shizuku) 通道，
    // 兼容性更好。选项：adb 默认 false；不指定 adb 时 root 默认 true。
    function RootAutomator2(options){
        options = options || {};
        var useAdb = options.adb === true;
        var useRoot = options.root === undefined ? true : options.root === true;
        if(useAdb && useRoot && options.root === true){
            throw new Error('adb 与 root 不能同时启用');
        }
        if(!useAdb && !useRoot){
            throw new Error('必须指定 adb 或 root 通道之一');
        }
        this.__ra2__ = new com.stardust.autojs.core.inputevent.RootAutomator2(scope.context, useAdb);
        // 只绑定“没有 JS 包装”的方法：包装方法写在原型上，不能在这里被遮蔽
        var methods = ["setScreenMetrics", "isAdb"];
        for(var i = 0; i < methods.length; i++){
            var method = methods[i];
            this[method] = this.__ra2__[method].bind(this.__ra2__);
        }
    }

    var p2 = RootAutomator2.prototype;

    // 文档：touchDown([{x, y, id}...]) / touchMove([...]) / touchUp([...]) 数组形式。
    // 数组在 JS 层展开成单指调用（Java 侧只有单指重载）；adb 通道不支持多指。
    function eachPointer(self, args, name, call){
        var first = args[0];
        if(first != undefined && typeof(first) === 'object' && first.length != undefined){
            if(self.__ra2__.isAdb() && first.length > 1){
                throw new Error(name + " 的数组形式需要多点触控，adb 通道不支持；请用 new RootAutomator2({root: true})");
            }
            for(var i = 0; i < first.length; i++){
                var point = first[i];
                call(point.x, point.y, point.id == undefined ? 0 : point.id);
            }
            return;
        }
        call(first, args[1], args[2] == undefined ? 0 : args[2]);
    }

    p2.touchDown = function(){
        var self = this;
        eachPointer(this, arguments, 'touchDown', function(x, y, id){
            self.__ra2__.touchDown(x, y, id);
        });
    }

    p2.touchMove = function(){
        var self = this;
        eachPointer(this, arguments, 'touchMove', function(x, y, id){
            self.__ra2__.touchMove(x, y, id);
        });
    }

    p2.touchUp = function(){
        var self = this;
        eachPointer(this, arguments, 'touchUp', function(x, y, id){
            self.__ra2__.touchUp(id);
        });
    }

    p2.tap = function(x, y){
        this.__ra2__.tap(x, y);
    }

    p2.longPress = function(x, y, id){
        if(id == undefined){
            this.__ra2__.longPress(x, y);
        }else{
            this.__ra2__.longPress(x, y, id);
        }
    }

    p2.press = function(x, y, duration, id){
        if(id == undefined){
            this.__ra2__.press(x, y, duration);
        }else{
            this.__ra2__.press(x, y, duration, id);
        }
    }

    p2.swipe = function(x1, y1, x2, y2, duration, id){
        if(duration == undefined){
            this.__ra2__.swipe(x1, y1, x2, y2);
        }else if(id == undefined){
            this.__ra2__.swipe(x1, y1, x2, y2, duration);
        }else{
            this.__ra2__.swipe(x1, y1, x2, y2, duration, id);
        }
    }

    p2.flush = function(){
        this.__ra2__.flush();
    }

    p2.exit = function(){
        this.__ra2__.exit();
    }

    // 文档里 RootAutomator2 是全局名（`new RootAutomator2(...)`）
    scope.RootAutomator2 = RootAutomator2;

    return RootAutomator;
}