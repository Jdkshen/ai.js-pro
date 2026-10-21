// @engine quickjs
// QuickJS detail regression probe.
//
// Covers the four audited gaps plus the engines.all robustness contract:
//   1. setTimeout/setInterval/setImmediate forward extra callback arguments.
//   2. engines.execScript/execScriptFile preserve nested config.arguments and
//      the config.path string-or-array contract.
//   3. files.open(path, 'r') throws for a missing path or a directory.
//   4. files.listDir(path, filter) filters bare entry names.
//   5. engines.all() keeps the current engine and other entries when a child
//      slot is empty/destroyed.
//
// The probe is deliberately finite (8s watchdog) and prints exactly one final
// JSON marker before calling exit(); do not rely on implicit timer/engine exit.
var RESULT_MARKER = 'QUICKJS_DETAIL_RESULT=';
var details = { engine: 'quickjs', checks: [], startedAt: Date.now() };
var finished = false;
var watchdog = null;
var activeChild = null;
var nestedValue = { arr: [1, null, '中文'], obj: { deep: true }, zero: 0 };

function assert(condition, message) {
    if (!condition) throw new Error(message || 'assertion failed');
}

function record(name, ok, error) {
    var item = { name: name, ok: !!ok };
    if (error !== undefined && error !== null) {
        item.error = String(error.stack || error);
    }
    details.checks.push(item);
}

function fail(name, error) {
    record(name, false, error);
}

function detail(name, action) {
    try {
        var value = action();
        assert(value === true, 'expected true, got ' + String(value));
        record(name, true);
    } catch (error) {
        record(name, false, error);
    }
}

function normPath(path) {
    return String(path === undefined || path === null ? '' : path).replace(/\/+$/, '');
}

function finish(reason) {
    if (finished) return;
    finished = true;
    if (watchdog !== null) {
        try { clearTimeout(watchdog); } catch (ignored) {}
        watchdog = null;
    }
    if (reason && activeChild) {
        try { activeChild.forceStop(); } catch (ignored) {}
    }
    details.durationMs = Date.now() - details.startedAt;
    details.total = details.checks.length;
    details.failed = 0;
    for (var i = 0; i < details.checks.length; i++) {
        if (!details.checks[i].ok) details.failed++;
    }
    details.passed = details.total - details.failed;
    details.ok = details.failed === 0 && !reason;
    if (reason) details.abortReason = String(reason);
    console.log(RESULT_MARKER + JSON.stringify(details));
    // Give the host console a moment to flush before exit() throws the sentinel.
    try { sleep(20); } catch (ignored) {}
    exit();
}

function waitForFile(path, timeout, onReady, onTimeout) {
    var started = Date.now();
    var timer = setInterval(function () {
        if (finished) {
            clearInterval(timer);
            return;
        }
        var exists;
        try {
            exists = files.exists(path);
        } catch (error) {
            clearInterval(timer);
            onTimeout(error);
            return;
        }
        if (exists) {
            clearInterval(timer);
            onReady();
            return;
        }
        if (Date.now() - started >= timeout) {
            clearInterval(timer);
            onTimeout(new Error('timeout waiting for ' + path));
        }
    }, 25);
}

function assertNested(value, label) {
    assertNestedShape(value, label);
    assert(value.obj.deep === true, label + '.obj.deep should be true');
    assert(value.zero === 0, label + '.zero should be 0');
}

function assertNestedShape(value, label) {
    assert(value && typeof value === 'object', label + ' is missing');
    assert(Array.isArray(value.arr), label + '.arr should be an array');
    assert(value.arr.length === 3, label + '.arr length should be 3');
    assert(value.arr[0] === 1, label + '.arr[0] should be 1');
    assert(value.arr[1] === null, label + '.arr[1] should stay null');
    assert(value.arr[2] === '中文', label + '.arr[2] should stay 中文');
}

function timerState() {
    return {
        timeoutArgs: null,
        intervalArgs: [],
        immediateArg: undefined,
        cancelled: false
    };
}

function startTimers(state) {
    setTimeout(function (a, b, c) {
        state.timeoutArgs = [a, b, c];
    }, 20, 1, 'two', { n: 3 });

    var intervalCount = 0;
    var intervalId = setInterval(function (a, b) {
        state.intervalArgs.push([a, b]);
        intervalCount++;
        if (intervalCount === 2) clearInterval(intervalId);
    }, 20, 'x', 9);

    setImmediate(function (value) {
        state.immediateArg = value;
    }, 77);

    var cancelId = setTimeout(function () {
        state.cancelled = true;
    }, 30);
    clearTimeout(cancelId);
}

function checkTimers(state) {
    detail('timers.setTimeout_extra_args', function () {
        assert(state.timeoutArgs !== null, 'setTimeout callback did not run');
        assert(state.timeoutArgs.length === 3, 'setTimeout callback argc should be 3');
        assert(state.timeoutArgs[0] === 1 && state.timeoutArgs[1] === 'two',
                'setTimeout extra args were not forwarded');
        assert(state.timeoutArgs[2] && state.timeoutArgs[2].n === 3,
                'setTimeout object extra arg was not forwarded');
        return true;
    });
    detail('timers.setInterval_extra_args_and_clear', function () {
        assert(state.intervalArgs.length === 2,
                'interval should fire exactly twice, got ' + state.intervalArgs.length);
        assert(state.intervalArgs[0][0] === 'x' && state.intervalArgs[0][1] === 9,
                'setInterval extra args were not forwarded');
        assert(state.intervalArgs[1][0] === 'x' && state.intervalArgs[1][1] === 9,
                'setInterval lost extra args on repeat');
        return true;
    });
    detail('timers.setImmediate_extra_args', function () {
        assert(state.immediateArg === 77,
                'setImmediate extra arg was not forwarded');
        return true;
    });
    detail('timers.clearTimeout', function () {
        assert(state.cancelled === false, 'cleared timeout still fired');
        return true;
    });
}

function checkFileDetails(base, alpha, beta, gamma) {
    detail('files.open_read_existing', function () {
        var file = files.open(alpha, 'r');
        try {
            assert(file.read() === 'alpha', 'read existing file');
            assert(file.isClosed() === false, 'file should start open');
            file.close();
            assert(file.isClosed() === true, 'file.close state');
        } finally {
            if (!file.isClosed()) file.close();
        }
        return true;
    });
    detail('files.open_missing_throws', function () {
        var threw = false;
        try {
            files.open(base + 'missing.txt', 'r');
        } catch (error) {
            threw = true;
        }
        assert(threw, 'files.open(missing, r) must throw');
        return true;
    });
    detail('files.open_directory_throws', function () {
        var threw = false;
        try {
            files.open(base, 'r');
        } catch (error) {
            threw = true;
        }
        assert(threw, 'files.open(directory, r) must throw');
        return true;
    });
    detail('files.listDir_all', function () {
        var names = files.listDir(base);
        names.sort();
        assert(names.length === 3,
                'expected 3 entries, got ' + JSON.stringify(names));
        assert(names.join(',') === 'alpha.txt,beta.log,gamma.txt',
                'unexpected entries: ' + JSON.stringify(names));
        return true;
    });
    detail('files.listDir_filter', function () {
        var calls = [];
        var rows = files.listDir(base, function (name) {
            calls.push({ name: name, argc: arguments.length });
            return /\.txt$/.test(name);
        });
        rows.sort();
        assert(rows.length === 2 && rows[0] === 'alpha.txt' && rows[1] === 'gamma.txt',
                'filtered entries: ' + JSON.stringify(rows));
        assert(calls.length === 3, 'filter should see all 3 names');
        for (var i = 0; i < calls.length; i++) {
            assert(calls[i].argc === 1, 'filter should receive exactly one argument');
            assert(calls[i].name.indexOf('/') < 0 && calls[i].name.indexOf('\\') < 0,
                    'filter should receive a bare name, got ' + calls[i].name);
        }
        return true;
    });
    detail('files.listDir_filter_type', function () {
        var threw = false;
        try {
            files.listDir(base, 42);
        } catch (error) {
            threw = true;
        }
        assert(threw, 'a non-function listDir filter must throw');
        return true;
    });
    // Keep this as a sanity check that beta/gamma were really written; the
    // listDir assertions above must not pass because of a shared temp dir.
    detail('files.fixture_written', function () {
        assert(files.read(beta) === 'beta' && files.read(gamma) === 'gamma',
                'probe fixture files are not intact');
        return true;
    });
}

function startChildA(base, onDone) {
    var readyPath = base + 'child-a.ready';
    var releasePath = base + 'child-a.release';
    var outPath = base + 'child-a.json';
    var childSource = [
        '// @engine quickjs',
        'var argv = engines.myEngine().execArgv;',
        'files.write(argv.ready, "ready");',
        'var waited = 0;',
        'while (!files.exists(argv.release) && waited < 3000) { sleep(25); waited += 25; }',
        'files.write(argv.out, JSON.stringify({ cwd: files.cwd(), argv: argv }));'
    ].join('\n');

    try {
        activeChild = engines.execScript('quickjs-detail-child-a', childSource, {
            path: [base, base + 'second'],
            arguments: {
                out: outPath,
                ready: readyPath,
                release: releasePath,
                nested: nestedValue,
                flag: true,
                num: 7
            }
        });
    } catch (error) {
        fail('engines.child_a_launch', error);
        finish('child-a-launch');
        return;
    }

    waitForFile(readyPath, 2500, function () {
        detail('engines.all_lists_current_and_running_child', function () {
            var list = engines.all();
            assert(Array.isArray(list), 'engines.all() must return an array');
            var current = null;
            var childInfo = null;
            for (var i = 0; i < list.length; i++) {
                if (Number(list[i].handle) === 0) current = list[i];
                if (Number(list[i].handle) === Number(activeChild.handle)) childInfo = list[i];
            }
            assert(current !== null, 'engines.all() lost the current engine');
            assert(childInfo !== null,
                    'engines.all() lost the running child: ' + JSON.stringify(list));
            assert(list.length >= 2,
                    'engines.all() should expose current + child, got ' + list.length);
            assertNestedShape(childInfo.execArgv && childInfo.execArgv.nested,
                    'engines.all().execArgv.nested');
            assert(childInfo.execArgv.flag === true && childInfo.execArgv.num === 7,
                    'engines.all().execArgv scalar arguments');
            return true;
        });

        try {
            files.write(releasePath, 'go');
        } catch (error) {
            fail('engines.child_a_release', error);
            finish('child-a-release');
            return;
        }

        waitForFile(outPath, 3500, function () {
            detail('engines.execScript_path_array_and_nested_arguments', function () {
                var report = JSON.parse(files.read(outPath));
                assert(normPath(report.cwd) === normPath(base),
                        'child cwd should be the first config.path entry, got ' + report.cwd);
                assert(report.argv && report.argv.out === outPath,
                        'child lost literal string argument');
                assertNested(report.argv.nested, 'child argv.nested');
                assert(report.argv.flag === true && report.argv.num === 7,
                        'child lost scalar arguments');
                return true;
            });
            onDone();
        }, function (error) {
            fail('engines.child_a_report', error);
            finish('child-a-report');
        });
    }, function (error) {
        fail('engines.child_a_ready', error);
        finish('child-a-ready');
    });
}

function startChildFile(base, onDone) {
    var outPath = base + 'child-file.json';
    var scriptPath = base + 'child-file.js';
    var childSource = [
        '// @engine quickjs',
        'var argv = engines.myEngine().execArgv;',
        'files.write(argv.out, JSON.stringify({ cwd: files.cwd(), argv: argv }));'
    ].join('\n');

    try {
        files.write(scriptPath, childSource);
        activeChild = engines.execScriptFile(scriptPath, {
            arguments: {
                out: outPath,
                nested: nestedValue,
                file: true
            }
        });
    } catch (error) {
        fail('engines.child_file_launch', error);
        finish('child-file-launch');
        return;
    }

    waitForFile(outPath, 3500, function () {
        detail('engines.execScriptFile_parent_cwd_and_arguments', function () {
            var report = JSON.parse(files.read(outPath));
            assert(normPath(report.cwd) === normPath(base),
                    'execScriptFile without path should use the script parent as cwd, got '
                    + report.cwd);
            assert(report.argv && report.argv.file === true,
                    'execScriptFile child lost boolean argument');
            assertNested(report.argv.nested, 'execScriptFile argv.nested');
            return true;
        });
        onDone();
    }, function (error) {
        fail('engines.child_file_report', error);
        finish('child-file-report');
    });
}

function runProbe() {
    var base = '/sdcard/Download/quickjs-detail-regression/'
            + Date.now() + '-' + Math.floor(Math.random() * 100000) + '/';
    var alpha = base + 'alpha.txt';
    var beta = base + 'beta.log';
    var gamma = base + 'gamma.txt';
    var state = timerState();

    var baseReady = false;
    try {
        baseReady = files.ensureDir(base);
    } catch (error) {
        fail('probe.base_dir', error);
        finish('setup');
        return;
    }
    if (!baseReady) {
        fail('probe.base_dir', new Error('cannot create ' + base));
        finish('setup');
        return;
    }

    try {
        files.write(alpha, 'alpha');
        files.write(beta, 'beta');
        files.write(gamma, 'gamma');
    } catch (error) {
        fail('probe.fixtures', error);
        finish('setup');
        return;
    }

    startTimers(state);
    checkFileDetails(base, alpha, beta, gamma);

    try {
        files.ensureDir(base + 'second');
    } catch (error) {
        fail('probe.second_path', error);
        finish('setup');
        return;
    }

    startChildA(base, function () {
        startChildFile(base, function () {
            // Let the 20ms timer/interval callbacks finish before asserting.
            setTimeout(function () {
                checkTimers(state);
                detail('engines.all_keeps_current_after_children', function () {
                    var list = engines.all();
                    var current = null;
                    for (var i = 0; i < list.length; i++) {
                        if (Number(list[i].handle) === 0) current = list[i];
                    }
                    assert(current !== null,
                            'engines.all() lost the current engine after children finished');
                    return true;
                });
                finish(null);
            }, 60);
        });
    });
}

watchdog = setTimeout(function () {
    details.timedOut = true;
    finish('watchdog-timeout');
}, 8000);

runProbe();
