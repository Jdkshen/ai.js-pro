// Focused Node24 prelude contract for require('events'):
// - the native EventEmitter constructor + all native static helpers stay available;
// - node:events itself is not patched or replaced;
// - the legacy Auto.js domain emit(type, payload) keeps falsy payloads intact.
// Run: node modules/engine/src/test/js/node24-events-contract.js
'use strict';

const assert = require('assert');
const path = require('path');

const preludePath = path.resolve(__dirname, '../../main/assets/node24/prelude.js');
const domainCalls = [];

globalThis.__ajsProjectDir = process.cwd();
globalThis.__ajsScriptFile = path.join(process.cwd(), 'events-contract.js');
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
  if (request.kind === 'events') {
    domainCalls.push(request);
    return JSON.stringify({ ok: true, value: true });
  }
  if (request.kind === 'keepalive') {
    return JSON.stringify({ ok: true, value: !!request.running });
  }
  return JSON.stringify({ ok: false, error: 'unexpected host request: ' + request.kind });
};

require(preludePath);

const events = require('events');
const nativeEvents = require('node:events');

function payloadFor(type) {
  const matches = domainCalls.filter(function (call) {
    return call.kind === 'events' && call.action === 'emit'
      && call.args && call.args.type === type;
  });
  assert.strictEqual(matches.length, 1, 'exactly one domain emit expected for ' + type);
  return matches[0].args.payload;
}

(async function main() {
  // ---- 1. require('events') is a callable/constructible EventEmitter ----
  assert.strictEqual(typeof events, 'function', 'require("events") must be a constructor function');
  assert.strictEqual(events.name, 'EventEmitter', 'constructor name must stay EventEmitter');
  assert.strictEqual(events.length, nativeEvents.length, 'constructor arity must match node:events');
  assert.strictEqual(events.prototype, nativeEvents.prototype,
    'events and node:events must share the native EventEmitter prototype');
  assert.strictEqual(events.EventEmitter, events,
    'events.EventEmitter must be the same constructor (native Node invariant)');

  const emitter = new events();
  assert.ok(emitter instanceof events, 'new require("events")() must be an EventEmitter');
  assert.ok(emitter instanceof nativeEvents, 'constructed emitter must be a native EventEmitter');

  const FromProperty = events.EventEmitter;
  assert.strictEqual(FromProperty, events, 'destructured EventEmitter must be the module constructor');
  const propertyEmitter = new FromProperty();
  assert.ok(propertyEmitter instanceof nativeEvents);
  assert.strictEqual(typeof propertyEmitter.emit, 'function');

  class DerivedEmitter extends events {}
  const derived = new DerivedEmitter();
  assert.ok(derived instanceof DerivedEmitter, 'require("events") must be usable as a base class');
  assert.ok(derived instanceof nativeEvents, 'derived emitter must stay a native EventEmitter');

  let nativeValue = null;
  emitter.on('native-event', function (value) { nativeValue = value; });
  const callsBeforeNativeEmit = domainCalls.length;
  assert.strictEqual(emitter.emit('native-event', 0), true, 'instance emit must use native EventEmitter');
  assert.strictEqual(nativeValue, 0, 'instance listeners must receive native emit payloads');
  assert.strictEqual(domainCalls.length, callsBeforeNativeEmit,
    'instance EventEmitter.emit must never be routed to the Auto.js domain bridge');

  // ---- 2. Every native static helper remains reachable ----
  const nativeStatics = [
    'addAbortListener', 'once', 'on', 'getEventListeners', 'getMaxListeners',
    'listenerCount', 'EventEmitterAsyncResource', 'setMaxListeners', 'init'
  ];
  nativeStatics.forEach(function (name) {
    assert.strictEqual(typeof events[name], typeof nativeEvents[name],
      'static events.' + name + ' must keep the native type');
  });
  assert.strictEqual(events.captureRejectionSymbol, nativeEvents.captureRejectionSymbol);
  assert.strictEqual(events.errorMonitor, nativeEvents.errorMonitor);
  assert.strictEqual(events.defaultMaxListeners, nativeEvents.defaultMaxListeners);

  events.setMaxListeners(17, emitter);
  assert.strictEqual(events.getMaxListeners(emitter), 17, 'setMaxListeners/getMaxListeners must work');
  assert.strictEqual(events.listenerCount(emitter, 'native-event'), 1, 'listenerCount must work');
  assert.strictEqual(events.getEventListeners(emitter, 'native-event').length, 1,
    'getEventListeners must work');

  const oncePromise = events.once(emitter, 'ready');
  emitter.emit('ready', 'payload');
  assert.deepStrictEqual(await oncePromise, ['payload'],
    'events.once(emitter, name) must resolve with the emitted arguments');

  assert.strictEqual(Object.prototype.hasOwnProperty.call(events, 'emit'), true,
    'legacy module-level emit must remain an own property of require("events")');
  assert.strictEqual('emit' in events, true, 'legacy emit must remain detectable with in');

  // ---- 3. node:events must remain untouched ----
  assert.strictEqual(nativeEvents.EventEmitter, nativeEvents,
    'node:events must keep its native self-reference');
  assert.strictEqual(typeof nativeEvents.emit, 'undefined',
    'the Auto.js module-level emit must NOT leak into node:events');
  assert.strictEqual(nativeEvents.defaultMaxListeners, events.defaultMaxListeners,
    'wrapper must not change native static state');

  // ---- 4. Legacy domain emit, including 0 / false / "" payloads ----
  assert.strictEqual(events.emit('object-payload', { answer: 42 }), true);
  assert.deepStrictEqual(payloadFor('object-payload'), { answer: 42 });

  assert.strictEqual(events.emit('zero-payload', 0), true);
  assert.strictEqual(payloadFor('zero-payload'), 0, 'payload 0 must not become {}');

  assert.strictEqual(events.emit('false-payload', false), true);
  assert.strictEqual(payloadFor('false-payload'), false, 'payload false must not become {}');

  assert.strictEqual(events.emit('empty-string-payload', ''), true);
  assert.strictEqual(payloadFor('empty-string-payload'), '', 'payload "" must not become {}');

  assert.strictEqual(events.emit('null-payload', null), true);
  assert.strictEqual(payloadFor('null-payload'), null, 'explicit null payload must stay null');

  assert.strictEqual(events.emit('omitted-payload'), true);
  assert.deepStrictEqual(payloadFor('omitted-payload'), {},
    'omitted payload keeps the legacy {} default');

  assert.strictEqual($autojs.events, events, '$autojs.events must expose the same module');
  assert.strictEqual(globalThis.__ajsBuiltinModules.events, events,
    '__ajsBuiltinModules.events must expose the same module');
  assert.strictEqual($autojs.events.emit('autojs-zero', 0), true);
  assert.strictEqual(payloadFor('autojs-zero'), 0,
    '$autojs.events.emit must keep falsy payloads');

  console.log('NODE24_EVENTS_CONTRACT_OK payloads=' + domainCalls.length
    + ' nativeConstructor=' + events.name);
})().catch(function (error) {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
