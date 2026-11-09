
module.exports = function (runtime, global) {
    importClass(android.content.Intent);
    var app = Object.create(runtime.app);
    var context = global.context;

    /**
     * Auto.js Pro 的 app.getInstalledPackages() 返回可以 forEach/map 的数组。
     * 直接返回 Java List 的话，`list.forEach(pkg => ...)` 会走 Java 的 forEach(Consumer)，
     * 而 Rhino 1.7.7 无法把箭头函数转成 Java 接口 —— 所以这里转成真正的 JS 数组。
     */
    app.getInstalledPackages = function () {
        var list = runtime.app.getInstalledPackageList();
        var result = [];
        for (var i = 0; i < list.size(); i++) {
            result.push(list.get(i));
        }
        return result;
    }


    /**
     * task-54：脚本侧广播接收（挂到脚本真正拿到的 app 对象上；Rhino 的 app = Object.create(runtime.app)）。
     * 默认取更安全的 RECEIVER_NOT_EXPORTED；exported:true 才用 RECEIVER_EXPORTED（其它应用可达，慎用）。
     * 注销：unregisterBroadcastReceiver(句柄或 id) / unregisterAllBroadcastReceivers()；退出/线程结束也会清理。
     */
    var BroadcastReceivers = Packages.com.stardust.autojs.core.content.ScriptBroadcastReceivers;
    var broadcastHandle = function (meta) {
        var handle = {
            id: meta.id, actions: meta.actions, categories: meta.categories, schemes: meta.schemes,
            permission: meta.permission, exported: meta.exported, registeredAt: meta.registeredAt,
            exportFlag: meta.exportFlag, active: true
        };
        handle.unregister = function () {
            handle.active = app.unregisterBroadcastReceiver(handle.id);
            return handle.active;
        };
        handle.toString = function () { return handle.id; };
        return handle;
    };
    app.registerBroadcastReceiver = function (actionOrFilter, callback, options) {
        var spec = broadcastSpec(actionOrFilter, callback && typeof callback === 'object' ? callback : options);
        var onIntent = typeof callback === 'function' ? callback
            : (callback && typeof callback.onReceive === 'function' ? callback.onReceive : null);
        if (typeof onIntent !== 'function') throw new TypeError('registerBroadcastReceiver: callback 必须是函数');
        var onError = (callback && typeof callback.onError === 'function') ? callback.onError
            : (options && typeof options.onError === 'function' ? options.onError : null);
        var json = String(BroadcastReceivers.register(context, spec.actions, spec.categories, spec.schemes,
            spec.permission, spec.exported, onIntent, onError, this));
        return broadcastHandle(JSON.parse(json));
    };
    app.unregisterBroadcastReceiver = function (idOrHandle) {
        var id = idOrHandle && typeof idOrHandle === 'object' && idOrHandle.id != null ? idOrHandle.id : idOrHandle;
        if (id == null || String(id).trim() === '') return false;
        return !!BroadcastReceivers.unregister(String(id).trim());
    };
    app.unregisterAllBroadcastReceivers = function () {
        return Number(BroadcastReceivers.unregisterAll());
    };
    /** 归一化 actionOrFilter（字符串/数组/{action,actions,categories,schemes,permission,exported}）。 */
    function broadcastSpec(actionOrFilter, options) {
        var spec = { actions: [], categories: [], schemes: [], permission: null, exported: false };
        function push(target, value) {
            if (value == null) return;
            (value instanceof Array ? value : [value]).forEach(function (item) {
                if (item == null) return;
                var text = String(item).trim();
                if (text) target.push(text);
            });
        }
        if (typeof actionOrFilter === 'string' || actionOrFilter instanceof Array) push(spec.actions, actionOrFilter);
        else if (actionOrFilter && typeof actionOrFilter === 'object') {
            push(spec.actions, actionOrFilter.actions != null ? actionOrFilter.actions : actionOrFilter.action);
        } else if (actionOrFilter != null) {
            throw new TypeError('registerBroadcastReceiver: actionOrFilter 必须是字符串、字符串数组或 filter 对象');
        }
        [actionOrFilter, options && typeof options === 'object' ? options : {}].forEach(function (source) {
            if (!source || typeof source !== 'object') return;
            push(spec.categories, source.categories != null ? source.categories : source.category);
            push(spec.schemes, source.schemes != null ? source.schemes : source.scheme);
            if (source.permission != null && String(source.permission).trim()) spec.permission = String(source.permission).trim();
            if (source.exported != null) spec.exported = !!source.exported;
        });
        if (!spec.actions.length) throw new TypeError('registerBroadcastReceiver: actionOrFilter 至少要有一个非空 action');
        return spec;
    }

    // app.viewFile(path, {fresh:true}) 不需要 JS 包装：AppUtils 已有 viewFile(String,Object) 重载（返回 ViewerResult）。
    // 曾经在这里赋值覆盖 Java 方法 → Rhino 报 `Java method "viewFile" cannot be assigned to`（K40 实测 dev-1372）。

    app.intent = function (i) {
        var intent = new android.content.Intent();
        if (i.className && i.packageName) {
            intent.setClassName(i.packageName, i.className);
        }
        if (i.extras) {
            for (var key in i.extras) {
                intent.putExtra(key, i.extras[key]);
            }
        }
        if (i.category) {
            if (i.category instanceof Array) {
                for (var j = 0; j < i.category.length; j++) {
                    intent.addCategory(i.category[j]);
                }
            } else {
                intent.addCategory(i.category);
            }
        }
        if (i.action) {
            if (i.action.indexOf(".") == -1) {
                i.action = "android.intent.action." + i.action;
            }
            intent.setAction(i.action);
        }
        if (i.flags) {
            let flags = 0;
            if (Array.isArray(i.flags)) {
                for (let j = 0; j < i.flags.length; j++) {
                    flags |= parseIntentFlag(i.flags[j]);
                }
            } else {
                flags = parseIntentFlag(i.flags);
            }
            intent.setFlags(flags);
        }
        if (i.type) {
            if (i.data) {
                intent.setDataAndType(app.parseUri(i.data), i.type);
            } else {
                intent.setType(i.type);
            }
        } else if (i.data) {
            intent.setData(android.net.Uri.parse(i.data));
        }
        return intent;
    }

    app.startActivity = function (i) {
        if (typeof (i) == "string") {
            if (runtime.getProperty("class." + i)) {
                context.startActivity(new Intent(context, runtime.getProperty("class." + i))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } else {
                throw new Error("class " + i + " not found");
            }
        }
        if(i instanceof android.content.Intent){
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if(i && i.root) {
            shell("am start " + app.intentToShell(i), true);
        }else{
            context.startActivity(app.intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

    }

    app.sendBroadcast = function (i) {
        if (typeof (i) == "string") {
            if (runtime.getProperty("broadcast." + i)) {
                app.sendLocalBroadcastSync(app.intent({ action: runtime.getProperty("broadcast." + i) }));
            }
        }
        if(i && i.root) {
            shell("am broadcast " + app.intentToShell(i), true);
        }else{
            context.sendBroadcast(app.intent(i));
        }
    }

    app.startService = function(i) {
        if(i && i.root) {
            shell("am startservice " + app.intentToShell(i), true);
        }else{
            context.startService(app.intent(i));
        }
    }

    app.sendEmail = function (options) {
        options = options || {};
        var i = new Intent(Intent.ACTION_SEND);
        if (options.email) {
            i.putExtra(Intent.EXTRA_EMAIL, toArray(options.email));
        }
        if (options.cc) {
            i.putExtra(Intent.EXTRA_CC, toArray(options.cc));
        }
        if (options.bcc) {
            i.putExtra(Intent.EXTRA_BCC, toArray(options.bcc));
        }
        if (options.subject) {
            i.putExtra(Intent.EXTRA_SUBJECT, options.subject);
        }
        if (options.text) {
            i.putExtra(Intent.EXTRA_TEXT, options.text);
        }
        if (options.attachment) {
            i.putExtra(Intent.EXTRA_STREAM, app.parseUri(options.attachment));
        }
        i.setType("message/rfc822");
        context.startActivity(Intent.createChooser(i, "发送邮件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    function toArray(arg) {
        if (typeof (arg) == 'string') {
            arg = [arg];
        }
        let arr = util.java.array("string", arg.length);
        for (let i = 0; i < arg.length; i++) {
            arr[i] = arg[i];
        }
        return arr;
    }

    app.parseUri = function (uri) {
        if (uri.startsWith("file://")) {
            return app.getUriForFile(uri);
        }
        return android.net.Uri.parse(uri);
    }

    app.getUriForFile = function (path) {
        if (path.startsWith("file://")) {
            path = path.substring(7);
        }
        let file = new java.io.File(files.path(path));
        if (app.fileProviderAuthority == null) {
            return android.net.Uri.fromFile(file);
        }
        return Packages["androidx"].core.content.FileProvider.getUriForFile(context,
            app.fileProviderAuthority, file);
    };

    app.launch = app.launchPackage;

    app.versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
    app.versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;

    app.autojs = {
        versionCode: com.jdkshen.aijspro.BuildConfig.VERSION_CODE,
        versionName: com.jdkshen.aijspro.BuildConfig.VERSION_NAME
    };

    app.intentToShell = function(i) {
        var cmd = "";
        function quoteStr(str) {
            return "'" + str.replace("'", "\\'") + "'";
        }
        function isInt(value) {
            return Number.isInteger(value) && value <= java.lang.Integer.MAX_VALUE && value >= java.lang.Integer.MIN_VALUE;
        }
        function typeChar(value){
            if(typeof(value) == 'boolean'){
                return 'z';
            }
            if(typeof(value) == 'number'){
                if(Number.isInteger(value)){
                    if(isInt(value)){
                        return 'i';
                    }else{
                        return 'l';
                    }
                }else{
                    return 'f';
                }
            }
            throw new TypeError("unknown type: " + value);
        }
        function addOption(option, param, quote) {
            if(quote == undefined || quote === true){
                param = quoteStr(param);
            }
            cmd += " -" + option + " " + param;
        }
        if (i.className && i.packageName) {
           addOption("n", i.packageName + "/" + i.className);
        }
        if (i.extras) {
            for (var key in i.extras) {
                let value = i.extras[key];
                if(typeof(value) == 'string'){
                    addOption("-es",  quoteStr(key) + ' ' + quoteStr(value), false);
                }else if(Array.isArray(value)){
                    if(value.length == 0){
                        throw new Error('Empty array: ' + key);
                    }
                    var e = value[0];
                    if(typeof(e) == 'string'){
                        cmd += ' --esa ' + quoteStr(key) + ' ';
                        for(let str of value){
                            cmd += quoteStr(str) + ',';
                        }
                        cmd = cmd.substring(0, cmd.length - 1);
                    }else{
                        addOption('-e' + typeChar(e) + 'a', quoteStr(key) + ' ' + value, false);
                    }
                }else {
                    addOption('-e' + typeChar(value), quoteStr(key) + ' ' + value, false);
                }
            }
        }
        if (i.category) {
            if (i.category instanceof Array) {
                for (var j = 0; j < i.category.length; j++) {
                    addOption('c', i.category[j], false);
                }
            } else {
                addOption('c', i.category, false);
            }
        }
        if (i.action) {
            if (i.action.indexOf(".") == -1) {
                i.action = "android.intent.action." + i.action;
            }
            addOption('a', i.action);
        }
        if (i.flags) {
            let flags = 0;
            if (Array.isArray(i.flags)) {
                for (let j = 0; j < i.flags.length; j++) {
                    flags |= parseIntentFlag(i.flags[j]);
                }
            } else {
                flags = parseIntentFlag(i.flags);
            }
            addOption('f', flags, false);
        }
        if (i.type) {
            addOption('t', i.type, false);
        }
        if (i.data) {
            addOption('d', i.data, false);
        }
        return cmd;
    }

    

    global.__asGlobal__(app, ['launchPackage', 'launch', 'launchApp', 'getPackageName', 'getAppName', 'openAppSetting']);

    function parseIntentFlag(flag) {
        if (typeof (flag) == 'string') {
            return android.content.Intent["FLAG_" + flag.toUpperCase()];
        }
        return flag;
    }

    return app;
}

