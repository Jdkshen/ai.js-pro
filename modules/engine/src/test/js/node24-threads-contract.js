// Focused Node24 prelude contract for threads lifecycle and real task exit.
//
// Termination is deliberately exercised in spawned child processes: loading the
// production prelude patches process.exit, so calling exit()/interrupt() in this
// runner would kill the test itself. The unbounded join is also isolated so a
// regression shows up as a bounded child timeout instead of a hung test runner.
//
// Run: node modules/engine/src/test/js/node24-threads-contract.js
'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const preludePath = path.resolve(__dirname, '../../main/assets/node24/prelude.js');

function installFakeHost() {
  globalThis.__ajsProjectDir = process.cwd();
  globalThis.__ajsScriptFile = path.join(process.cwd(), 'threads-contract.js');
  globalThis.__autojsHost = function (requestJson) {
    let request;
    try {
      request = JSON.parse(requestJson);
    } catch (error) {
      return JSON.stringify({ ok: false, error: 'invalid host JSON: ' + error.message });
    }
    if (request.kind === 'poll') {
      return JSON.stringify({ ok: true, value: { $events: [], $more: false } });
    }
    if (request.kind === 'keepalive') {
      return JSON.stringify({ ok: true, value: !!request.running });
    }
    return JSON.stringify({ ok: true, value: true });
  };
}

function runChildMode(mode) {
  installFakeHost();
  require(preludePath);

  if (mode === '--child-unbounded-join') {
    const threads = require('threads');
    const startedAt = Date.now();
    const thread = threads.start(function () {
      // Deliberately exit before the generated worker shim can return a value.
      process.exit(9);
    });
    // No timeout: the pre-fix worker never signals the SAB on process.exit, so
    // this blocks forever and only the parent timeout can stop it.
    const joined = thread.join();
    const output = {
      joined: joined,
      alive: thread.isAlive(),
      error: thread.error,
      getError: typeof thread.getError === 'function' ? thread.getError() : 'missing',
      elapsedMs: Date.now() - startedAt
    };
    fs.writeFileSync(process.env.AJS_TEST_RESULT, JSON.stringify(output), 'utf8');
    return;
  }

  if (mode === '--child-exit') {
    // Keep both the prelude keep-alive and an independent interval alive. The
    // old implementation only stopped keepalive, leaving this interval running.
    $autojs.keepRunning();
    setInterval(function () {
      fs.appendFileSync(process.env.AJS_TEST_AFTER, 'tick', 'utf8');
    }, 20);
    fs.writeFileSync(process.env.AJS_TEST_BEFORE, 'before-exit', 'utf8');
    const exitMode = process.env.AJS_TEST_EXIT_MODE;
    if (exitMode === 'global-exit') {
      exit(7);
    } else if (exitMode === 'lang-exit') {
      require('lang').exit(3);
    } else if (exitMode === 'current-thread-interrupt') {
      require('threads').currentThread().interrupt();
    } else {
      throw new Error('unknown AJS_TEST_EXIT_MODE: ' + exitMode);
    }
    // Must never be reached: exit/interrupt terminate this Node task runtime.
    fs.writeFileSync(process.env.AJS_TEST_CONTINUED, 'continued', 'utf8');
    return;
  }

  throw new Error('unknown child mode: ' + mode);
}

function spawnChild(mode, extraEnv, timeoutMs) {
  // DSH's confined Windows sandbox cannot create a pipe for spawnSync's stdio
  // capture (EPERM). Inherit the test's own stdout/stderr instead; assertions
  // rely on exit status and result files, and the child remains a real process.
  return spawnSync(process.execPath, [__filename, mode], {
    timeout: timeoutMs,
    stdio: 'inherit',
    env: Object.assign({}, process.env, extraEnv)
  });
}

function childFailure(result) {
  return 'status=' + result.status + ' signal=' + result.signal
    + ' error=' + (result.error ? (result.error.code || result.error.message) : 'none');
}

function runTerminationChildren(workDir) {
  // 1. An unbounded join must terminate even when the Worker exits early.
  const joinResultPath = path.join(workDir, 'unbounded-join.json');
  let child = spawnChild('--child-unbounded-join', {
    AJS_TEST_RESULT: joinResultPath
  }, 5000);
  assert.strictEqual(child.error, undefined,
    'unbounded join must not hang after Worker process.exit: ' + childFailure(child));
  assert.strictEqual(child.status, 0,
    'unbounded join child must succeed: ' + childFailure(child));
  const joinResult = JSON.parse(fs.readFileSync(joinResultPath, 'utf8'));
  assert.strictEqual(joinResult.joined, true, 'unbounded join must return true after early Worker exit');
  assert.strictEqual(joinResult.alive, false, 'isAlive() must be false after early Worker exit');
  assert.strictEqual(typeof joinResult.error, 'string',
    'error must expose the early Worker exit reason');
  assert.ok(/exit|退出|9/i.test(joinResult.error),
    'early Worker exit error should name the exit, got: ' + joinResult.error);
  assert.strictEqual(joinResult.getError, joinResult.error,
    'getError() must expose the same terminal error');

  // 2. exit()/lang.exit()/currentThread.interrupt() must end the script even
  // when an unrelated interval would otherwise keep the event loop alive.
  const modes = [
    { name: 'global-exit', expectedCode: 7 },
    { name: 'lang-exit', expectedCode: 3 },
    { name: 'current-thread-interrupt', expectedCode: 0 }
  ];
  modes.forEach(function (item) {
    const before = path.join(workDir, item.name + '-before.txt');
    const after = path.join(workDir, item.name + '-after.txt');
    const continued = path.join(workDir, item.name + '-continued.txt');
    child = spawnChild('--child-exit', {
      AJS_TEST_EXIT_MODE: item.name,
      AJS_TEST_BEFORE: before,
      AJS_TEST_AFTER: after,
      AJS_TEST_CONTINUED: continued
    }, 5000);
    assert.strictEqual(child.error, undefined,
      item.name + ' must terminate the Node task runtime: ' + childFailure(child));
    assert.strictEqual(child.status, item.expectedCode,
      item.name + ' must exit with the requested code: ' + childFailure(child));
    assert.strictEqual(fs.existsSync(before), true,
      item.name + ' child must reach the exit call');
    assert.strictEqual(fs.existsSync(after), false,
      item.name + ' must stop timers immediately (no post-exit interval tick)');
    assert.strictEqual(fs.existsSync(continued), false,
      item.name + ' must not continue executing statements after termination');
  });
}

function runThreadLifecycleChecks() {
  installFakeHost();
  require(preludePath);
  const threads = require('threads');

  // Normal result + args.
  const ok = threads.start(function (a, b) { return { sum: a + b }; }, 2, 3);
  assert.strictEqual(ok.join(3000), true, 'normal Worker must join');
  assert.deepStrictEqual(ok.result, { sum: 5 }, 'normal Worker must return its value');
  assert.strictEqual(ok.isAlive(), false, 'normal Worker must not stay alive');
  assert.strictEqual(ok.error, null, 'normal Worker must not report an error');

  // A thrown task error is still reported by the normal result shim.
  const failed = threads.start(function () { throw new Error('task-boom-24'); });
  assert.strictEqual(failed.join(3000), true, 'throwing Worker must join');
  assert.strictEqual(failed.isAlive(), false, 'throwing Worker must not stay alive');
  assert.strictEqual(typeof failed.error, 'string', 'throwing Worker must expose an error');
  assert.ok(failed.error.indexOf('task-boom-24') >= 0,
    'throwing Worker error must contain the original failure, got: ' + failed.error);
  assert.strictEqual(failed.getError(), failed.error, 'getError() must mirror error');

  // Worker process.exit() before returning a value.
  const early = threads.start(function () { process.exit(9); });
  assert.strictEqual(early.join(3000), true,
    'Worker process.exit must wake join instead of leaving the state RUNNING');
  assert.strictEqual(early.isAlive(), false, 'Worker process.exit must end isAlive()');
  assert.strictEqual(typeof early.error, 'string', 'Worker process.exit must expose an error');
  assert.ok(/exit|退出|9/i.test(early.error),
    'Worker process.exit error should mention the exit, got: ' + early.error);
  assert.strictEqual(early.getError(), early.error,
    'getError() must report the process.exit failure');

  // Uncaught asynchronous throw while the task promise is still pending.
  const late = threads.start(function () {
    setTimeout(function () { throw new Error('late-uncaught-24'); }, 10);
    return new Promise(function () {});
  });
  assert.strictEqual(late.join(3000), true,
    'uncaught Worker exception must wake join instead of hanging');
  assert.strictEqual(late.isAlive(), false, 'uncaught Worker exception must end isAlive()');
  assert.strictEqual(typeof late.error, 'string', 'uncaught Worker exception must expose an error');
  assert.ok(late.error.indexOf('late-uncaught-24') >= 0,
    'uncaught Worker error must contain the thrown failure, got: ' + late.error);

  // A pending Promise does not keep a Worker's event loop alive. The worker can
  // exit naturally before writing a result; join must still terminate.
  const stalled = threads.start(function () {
    return new Promise(function () {});
  });
  assert.strictEqual(stalled.join(3000), true,
    'natural Worker exit before result must wake join instead of hanging');
  assert.strictEqual(stalled.isAlive(), false, 'naturally exited Worker must not stay alive');
  assert.strictEqual(typeof stalled.error, 'string', 'naturally exited Worker must expose an error');
  assert.strictEqual(stalled.getError(), stalled.error,
    'getError() must report the natural-exit failure');

  // Bounded join semantics and interrupt still work.
  const pending = threads.start(function () {
    return new Promise(function (resolve) { setTimeout(function () { resolve('late-ok'); }, 300); });
  });
  assert.strictEqual(pending.join(10), false, 'join(timeout) must return false while running');
  assert.strictEqual(pending.isAlive(), true, 'pending Worker must be alive after a short join');
  assert.strictEqual(pending.join(3000), true, 'pending Worker must finish normally');
  assert.strictEqual(pending.result, 'late-ok', 'pending Worker result must be preserved');

  const interrupted = threads.start(function () {
    return new Promise(function () {});
  });
  assert.strictEqual(interrupted.join(10), false, 'interrupt target must still be running');
  interrupted.interrupt();
  assert.strictEqual(interrupted.join(3000), true, 'interrupt must wake join');
  assert.strictEqual(interrupted.isAlive(), false, 'interrupt must end isAlive()');
  assert.strictEqual(typeof interrupted.error, 'string', 'interrupt must expose an error');
}

function runContract() {
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'node24-threads-contract-'));
  try {
    runTerminationChildren(workDir);
    runThreadLifecycleChecks();
    console.log('NODE24_THREADS_CONTRACT_OK unboundedJoin=done exitModes=3 workers=7');
  } finally {
    try {
      fs.rmSync(workDir, { recursive: true, force: true });
    } catch (ignored) {
    }
  }
}

if (process.argv[2] && process.argv[2].indexOf('--child-') === 0) {
  try {
    runChildMode(process.argv[2]);
  } catch (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
  }
} else {
  try {
    runContract();
  } catch (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
  }
}
