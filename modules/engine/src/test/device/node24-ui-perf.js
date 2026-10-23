"ui"; "nodejs";
// Node24 UI performance probe (task-232).
// Measures ui.inflate total wall time for plain XML, custom Widget XML and repeated inflates.
// Outputs one line "NODE24_UI_PERF_RESULT <json>" to console (MCP get_execution captures logs)
// and a fallback file /sdcard/脚本/node24-ui-perf-last.json so the host runner can pull it.
const ui = require('ui');
const fs = require('fs');

const FALLBACK = '/sdcard/脚本/node24-ui-perf-last.json';
const ROUNDS = 5;
const result = {
  schema: 1,
  name: 'node24-ui-perf',
  engine: 'node',
  generatedAt: new Date().toISOString(),
  rounds: ROUNDS,
  device: {},
  phases: {},
  inflate: {},
  checks: {},
  errors: [],
  startedAt: Date.now()
};
let benchStarted = false;
let finished = false;
let setMainActivityStart = 0;

function nowMs() {
  try { return Number(java.lang.System.nanoTime()) / 1e6; } catch (error) { return Date.now(); }
}
function round(value) { return Math.round(value * 1000) / 1000; }
function stats(samples) {
  const sorted = samples.slice().sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  const mid = Math.floor(sorted.length / 2);
  return {
    samples: sorted.map(round),
    min: round(sorted[0]),
    median: round(sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2),
    max: round(sorted[sorted.length - 1]),
    avg: round(sum / sorted.length)
  };
}
function measure(fn) {
  const start = nowMs();
  const value = fn();
  return { ms: nowMs() - start, value: value };
}
function record(name, stat) {
  result.inflate[name] = stat;
  console.log('NODE24_UI_PERF_STAGE', name, JSON.stringify(stat));
}
function fail(error) {
  if (finished) return;
  finished = true;
  result.ok = false;
  result.errors.push(String(error && error.stack ? error.stack : error));
  result.elapsedMs = Date.now() - result.startedAt;
  const text = JSON.stringify(result);
  try { fs.writeFileSync(FALLBACK, text); } catch (writeError) {}
  console.error('NODE24_UI_PERF_FAIL', text);
  try { ui.close(); } catch (closeError) {}
}
function finish() {
  if (finished) return;
  finished = true;
  result.ok = result.errors.length === 0;
  result.elapsedMs = Date.now() - result.startedAt;
  result.phases.elapsedSinceStartMs = result.elapsedMs;
  const text = JSON.stringify(result);
  try { fs.writeFileSync(FALLBACK, text); } catch (error) { console.error('NODE24_UI_PERF_WRITE_FAIL', String(error)); }
  console.log('NODE24_UI_PERF_RESULT ' + text);
  try { ui.close(); } catch (error) { console.error('NODE24_UI_PERF_CLOSE_FAIL', String(error)); }
}

class PerfCard extends ui.Widget {
  render() { return '<frame padding="8"><column id="slot"/></frame>'; }
}
ui.Widget.register('perf-card', PerfCard);

const plainXml = '<column padding="8"><text text="perf-plain"/><button text="ok" w="120" h="48"/></column>';
const customXml = '<column padding="8"><perf-card id="card"><text text="child"/></perf-card></column>';
const listXml = '<vertical padding="8">' + Array.from({ length: 20 }, (_, i) =>
  '<frame padding="6"><text text="row ' + i + '"/></frame>').join('') + '</vertical>';

class PerfActivity extends ui.Activity {
  get layoutXml() { return '<frame id="root" w="*" h="*"><column id="host" w="*" h="*"/></frame>'; }
  onContentViewSet(view) {
    if (benchStarted) return;
    benchStarted = true;
    const entry = nowMs();
    try {
      if (setMainActivityStart) {
        result.phases.setMainActivityCallbackEnterMs = round(nowMs() - setMainActivityStart);
        if (result.phases.setMainActivityReturnMs == null) {
          result.phases.setMainActivityReturnMs = result.phases.setMainActivityCallbackEnterMs;
        }
      }
      result.checks.uiThread = ui.isUiThread();
      result.checks.rootBinding = !!(view && view.binding && view.binding.root);
      result.checks.hostBinding = !!(view && view.binding && view.binding.host);
      // 同步在 UI 回调内测量，保证 inflate 与真实 onContentViewSet 同线程；耗时算进 callback 总耗时。
      runBenchmark(view, entry);
    } catch (error) { fail(error); }
  }
}

function runBenchmark(view, callbackEntry) {
  try {
    const benchStart = nowMs();
    const host = view.binding.host;
    result.checks.uiThreadInBench = ui.isUiThread();
    result.checks.hostIsView = host != null;
    // 1) plain detached (expand/overall)
    const plainDetached = [];
    for (let i = 0; i < ROUNDS; i++) {
      const one = measure(() => ui.inflate(plainXml));
      result.checks.plainBinding = !!(one.value && one.value.binding);
      plainDetached.push(one.ms);
    }
    record('plainDetached', stats(plainDetached));
    // 2) plain hosted but not attached (approx host)
    const plainHosted = [];
    for (let i = 0; i < ROUNDS; i++) {
      const one = measure(() => ui.inflate(plainXml, host, false));
      result.checks.hostedLayoutParams = !!(one.value && one.value.getLayoutParams && one.value.getLayoutParams() != null);
      plainHosted.push(one.ms);
      try { host.removeAllViews(); } catch (error) {}
    }
    record('plainHostedNoAttach', stats(plainHosted));
    // 3) plain attached (approx attach)
    const plainAttached = [];
    for (let i = 0; i < ROUNDS; i++) {
      const one = measure(() => ui.inflate(plainXml, host, true));
      const attached = !!(one.value && one.value.getParent && one.value.getParent() != null);
      result.checks.attachedToHost = attached;
      plainAttached.push(one.ms);
      try { host.removeAllViews(); } catch (error) {}
    }
    record('plainAttached', stats(plainAttached));
    // 4) custom Widget XML detached
    const customDetached = [];
    for (let i = 0; i < ROUNDS; i++) {
      const one = measure(() => ui.inflate(customXml));
      result.checks.customBinding = !!(one.value && one.value.binding && one.value.binding.card);
      customDetached.push(one.ms);
    }
    record('customWidgetDetached', stats(customDetached));
    // 5) repeated plain inflate (dedicated evidence; 5 >= 3)
    const repeated = [];
    for (let i = 0; i < ROUNDS; i++) repeated.push(measure(() => ui.inflate(plainXml)).ms);
    record('repeatedPlainDetached', stats(repeated));
    // 6) synthetic M3-style list inflate
    const list = measure(() => ui.inflate(listXml));
    result.phases.syntheticListInflateMs = round(list.ms);
    result.checks.listBinding = !!(list.value && list.value.binding);
    result.phases.benchTotalMs = round(nowMs() - benchStart);
    result.phases.onContentViewSetCallbackMs = round(nowMs() - callbackEntry);
    try { result.device.model = String(device.model); result.device.sdkInt = Number(device.sdkInt); } catch (error) {}
    result.checks.allRounds = result.inflate.plainDetached.samples.length === ROUNDS &&
      result.inflate.customWidgetDetached.samples.length === ROUNDS &&
      result.inflate.repeatedPlainDetached.samples.length === ROUNDS;
    if (!result.checks.allRounds) throw Error('PERF_ROUNDS_NOT_COMPLETE');
    // 让 onContentViewSet 先返回，再 close；避免在 UI 回调内直接销毁 Activity。
    setTimeout(finish, 50);
  } catch (error) { fail(error); }
}

process.on('uncaughtException', fail);
process.on('unhandledRejection', fail);
setMainActivityStart = nowMs();
ui.setMainActivity(PerfActivity);
const setReturnMs = round(nowMs() - setMainActivityStart);
if (result.phases.setMainActivityReturnMs == null) result.phases.setMainActivityReturnMs = setReturnMs;
console.log('NODE24_UI_PERF_START');
