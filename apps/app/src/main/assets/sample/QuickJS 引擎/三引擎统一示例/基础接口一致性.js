"quickjs";
// Same source in all three engines. Generated copies differ only in their engine directive.
(function () {
    var checks = [];
    function check(name, fn) {
        try { if (!fn()) throw new Error('assertion returned false'); checks.push({name: name, ok: true}); }
        catch (error) { checks.push({name: name, ok: false, error: String(error)}); }
    }
    function equal(a, b) { return JSON.stringify(a) === JSON.stringify(b); }
    var root = '/sdcard/脚本/__aijs_common_contract__/' + Date.now();
    var text = root + '/目录/中文.txt';
    var storageName = '__aijs_common_contract__' + Date.now();
    var store;
    check('aliases.files', function () { return files === $files; });
    check('aliases.device', function () { return device === $device; });
    check('aliases.storages', function () { return storages === $storages; });
    check('files.api', function () { return __aijsCommonApi.files.every(function (name) { return typeof files[name] === 'function'; }); });
    check('files.cwd', function () { return String(files.cwd()).charAt(0) === '/'; });
    check('files.path', function () { return String(files.path('./api-test.txt')).indexOf(String(files.cwd())) === 0; });
    check('files.join', function () { return String(files.join(root, 'a', 'b')) === root + '/a/b'; });
    check('files.ensureDir', function () { return files.ensureDir(text) && files.isDir(root + '/目录'); });
    check('files.create', function () { return files.create(text) && files.isFile(text); });
    check('files.createIfNotExists', function () { return !files.createIfNotExists(text) && files.createIfNotExists(root + '/new.txt') && files.isFile(root + '/new.txt'); });
    check('files.write_read_unicode', function () { files.write(text, '中文✓\n'); return String(files.read(text)) === '中文✓\n'; });
    check('files.append', function () { files.append(text, 'second'); return String(files.read(text)) === '中文✓\nsecond'; });
    check('files.encoding', function () {
        files.write(text, '中文编码', 'GBK');
        var bytes = files.readBytes(text);
        return String(files.read(text, 'GBK')) === '中文编码' && (Number(bytes[0]) & 255) === 0xd6 && (Number(bytes[1]) & 255) === 0xd0;
    });
    check('files.readText_writeText', function () { files.writeText(text, '别名'); return String(files.readText(text)) === '别名'; });
    check('files.binary_roundtrip', function () {
        var bytes = files.readBytes(text);
        files.writeBytes(root + '/bytes.bin', bytes);
        files.appendBytes(root + '/bytes.bin', bytes);
        var result = files.readBytes(root + '/bytes.bin');
        return Number(result.length) === Number(bytes.length) * 2 && (Number(result[0]) & 255) === (Number(bytes[0]) & 255);
    });
    check('files.open', function () {
        var writer = files.open(text, 'w', 'UTF-8');
        try { writer.write('流写入'); } finally { writer.close(); }
        var reader = files.open(text, 'r', 'UTF-8');
        try { return String(reader.read()) === '流写入'; } finally { reader.close(); }
    });
    check('files.open_mixed_cursor', function () {
        files.write(text, '第一行\n第二行\n');
        var stream = files.open(text, 'r');
        try { return String(stream.readline()) === '第一行' && String(stream.read(2)) === '第二' && String(stream.read()) === '行\n' && String(stream.read(1)) === ''; }
        finally { stream.close(); files.write(text, '流写入'); }
    });
    check('files.open_readlines', function () {
        files.write(text, 'a\nb');
        var stream = files.open(text, 'r');
        try { var lines = stream.readlines(); return Number(lines.length) === 2 && String(lines[0]) === 'a' && String(lines[1]) === 'b'; }
        finally { stream.close(); files.write(text, '流写入'); }
    });
    check('files.copy', function () { return files.copy(text, root + '/copy.txt') && String(files.read(root + '/copy.txt')) === '流写入'; });
    check('files.rename', function () { return files.rename(root + '/copy.txt', 'rename.txt') && files.exists(root + '/rename.txt'); });
    check('files.renameWithoutExtension', function () { return files.renameWithoutExtension(root + '/rename.txt', 'new') && files.exists(root + '/new.txt'); });
    check('files.move', function () { return files.move(root + '/new.txt', root + '/moved.txt') && !files.exists(root + '/new.txt'); });
    check('files.listDir_filter', function () {
        var result = files.listDir(root, function (name) { return String(name) === 'moved.txt'; });
        return Number(result.length) === 1 && String(result[0]) === 'moved.txt';
    });
    check('files.remove_does_not_recurse', function () { return !files.remove(root) && files.exists(text); });
    check('files.remove', function () { return files.remove(root + '/moved.txt') && !files.exists(root + '/moved.txt'); });
    check('files.getName', function () { return String(files.getName(text)) === '中文.txt'; });
    check('files.getExtension', function () { return String(files.getExtension(text)) === 'txt'; });
    check('files.getNameWithoutExtension', function () { return String(files.getNameWithoutExtension(text)) === '中文'; });
    check('files.dirname', function () { return String(files.dirname(text)) === root + '/目录'; });
    check('files.stat', function () { var stat = files.stat(text); return Number(stat.size) > 0 && String(stat.path).indexOf('中文.txt') >= 0; });
    check('files.getSdcardPath', function () { return String(files.getSdcardPath()).charAt(0) === '/'; });
    check('files.getHumanReadableSize', function () { return String(files.getHumanReadableSize(1024)).length > 1; });
    check('files.getSimplifiedPath', function () { return String(files.getSimplifiedPath('/storage/emulated/0/a')).indexOf('a') >= 0; });
    check('files.readAssets', function () { return String(files.readAssets('modules/common-api.js')).indexOf('Shared API v1 adapters') >= 0; });
    check('storages.create', function () { store = storages.create(storageName); return !!store; });
    [null, false, 0, '', '中文', [1, false, null], {text: '中文', nested: {zero: 0}}].forEach(function (value, index) {
        check('storages.json.' + index, function () { store.put('value', value); return equal(store.get('value'), value); });
    });
    check('storages.contains', function () { return store.contains('value') && !store.contains('missing'); });
    check('storages.default', function () { return equal(store.get('missing', {fallback: 1}), {fallback: 1}); });
    check('storages.undefined_rejected', function () { try { store.put('bad', undefined); return false; } catch (error) { return error instanceof TypeError; } });
    check('storages.circular_rejected', function () { var value = {}; value.self = value; try { store.put('bad', value); return false; } catch (error) { return true; } });
    check('storages.reopen', function () { return equal(storages.create(storageName).get('value'), {text: '中文', nested: {zero: 0}}); });
    check('storages.remove_key', function () { store.remove('value'); return !store.contains('value'); });
    check('storages.remove_store', function () { store.put('a', 1); storages.remove(storageName); return store.get('a', 99) === 99; });
    check('device.api', function () { return __aijsCommonApi.device.every(function (name) { return typeof device[name] === 'function'; }); });
    check('device.dimensions', function () { return Number(device.width) > 0 && Number(device.height) > 0; });
    check('device.build', function () { return Number(device.sdkInt) >= 21 && String(device.model).length > 0 && String(device.product).length > 0; });
    check('device.brightness', function () { return Number(device.getBrightness()) >= 0; });
    check('device.brightnessMode', function () { return [0, 1].indexOf(Number(device.getBrightnessMode())) >= 0; });
    check('device.androidId', function () { return String(device.getAndroidId()).length > 0; });
    check('device.battery', function () { var n = Number(device.getBattery()); return n >= 0 && n <= 100; });
    check('device.memory', function () { return Number(device.getTotalMem()) > Number(device.getAvailMem()) && Number(device.getAvailMem()) > 0; });
    check('device.screen', function () { return typeof device.isScreenOn() === 'boolean' && [0, 90, 180, 270].indexOf(Number(device.getScreenRotation())) >= 0; });
    check('device.orientation', function () { return ['portrait', 'landscape'].indexOf(String(device.getScreenOrientation())) >= 0; });
    check('device.volumes', function () { return Number(device.getMusicVolume()) >= 0 && Number(device.getMusicMaxVolume()) >= Number(device.getMusicVolume()); });
    check('device.charging', function () { return typeof device.isCharging() === 'boolean'; });
    check('cleanup.files', function () { return files.removeDir(root) && !files.exists(root); });
    var failed = checks.filter(function (row) { return !row.ok; });
    console.log('COMMON_API_RESULT=' + JSON.stringify({checks: checks, passed: checks.length - failed.length, failed: failed}));
})();
