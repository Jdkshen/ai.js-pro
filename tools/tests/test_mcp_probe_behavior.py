#!/usr/bin/env python3
"""Execute the real McpEngineApi.probe template in Node, not just string matching.

The Kotlin object embeds the probe as a raw template with __ROOT__/__MODULE__/__NAME__/
__INHERITED__ placeholders. This test extracts that exact template from the Kotlin source,
renders it the same way McpEngineApi.probe() does, and runs it against JS objects, getters,
Java-like descriptor-hostile proxies, and a real CommonJS module.

It does not use Gradle, a device, or ADB. It also guards the important safety contract:
the shape probe must not execute JS getters or JavaBean getters, and an unknown proxy must
be reported as unverified instead of being guessed as missing.
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "apps/app/src/main/java/com/jdkshen/aijspro/mcp/McpEngineApi.kt"


def load_template() -> str:
    text = SOURCE.read_text(encoding="utf-8")
    match = re.search(r'private const val PROBE_TEMPLATE = """(.*?)"""', text, re.S)
    if not match:
        raise AssertionError("PROBE_TEMPLATE raw string not found in McpEngineApi.kt")
    template = match.group(1)
    for placeholder in ("__ROOT__", "__MODULE__", "__NAME__", "__INHERITED__"):
        if placeholder not in template:
            raise AssertionError(f"probe template is missing {placeholder}")
    return template


NODE_HARNESS = r"""'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const template = fs.readFileSync(process.argv[2], 'utf8');
const work = process.argv[3];
const modulePath = process.argv[4];

function render(moduleName, memberName, inherited) {
  const root = moduleName
    ? `require(${JSON.stringify(moduleName)})`
    : "(typeof globalThis!=='undefined'?globalThis:this)";
  const values = {
    '__ROOT__': root,
    '__MODULE__': JSON.stringify(moduleName),
    '__NAME__': JSON.stringify(memberName),
    '__INHERITED__': inherited ? 'true' : 'false',
  };
  return template.replace(/__[A-Z]+__/g, token =>
    Object.prototype.hasOwnProperty.call(values, token) ? values[token] : token);
}

function run(setup, moduleName, memberName, inherited) {
  let output = null;
  const sandbox = {
    console: { log(line) { output = String(line); }, warn() {}, error() {} },
    require,
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(setup, sandbox);
  vm.runInContext(render(moduleName, memberName, inherited), sandbox, { filename: 'probe.js' });
  if (!output) throw new Error('probe produced no output');
  const marker = output.indexOf('MCP_API_PROBE_RESULT=');
  if (marker < 0) throw new Error('probe result marker missing: ' + output);
  return { result: JSON.parse(output.slice(marker + 'MCP_API_PROBE_RESULT='.length)), sandbox };
}

const setup = `
globalThis.getterCalls = 0;
globalThis.tagCalls = 0;
globalThis.tagged = {};
Object.defineProperty(globalThis.tagged, Symbol.toStringTag, {get() { globalThis.tagCalls++; return 'JavaFake'; }});
globalThis.spoofedJava = {[Symbol.toStringTag]: 'JavaFake'};
Object.defineProperty(globalThis.spoofedJava, 'getClass', {get() { globalThis.tagCalls++; throw new Error('getter'); }});
globalThis.fixture = {
  nested: { ok: true },
  method: function(a, b) { return a + b; },
  get value() { globalThis.getterCalls++; return { x: 1 }; }
};
globalThis.javaGetterCalls = 0;
globalThis.javaMethodCalls = 0;
globalThis.javaProxy = (function() {
  const target = {};
  Object.defineProperty(target, Symbol.toStringTag, { value: 'JavaList' });
  const fakeClass = {
    getMethods() { return [
      { getName() { return 'add'; }, getParameterTypes() { return [{}, {}]; } },
      { getName() { return 'size'; }, getParameterTypes() { return []; } }
    ]; },
    getFields() { return []; }
  };
  return new Proxy(target, {
    get(t, p) {
      if (p === 'getClass') return () => fakeClass;
      if (p === Symbol.toStringTag) return 'JavaList';
      if (p === 'add') return function() { globalThis.javaMethodCalls++; };
      if (p === 'size') return function() { return 0; };
      if (p === 'value') { globalThis.javaGetterCalls++; return 'bad'; }
      return undefined;
    },
    has(t, p) { return p === 'add' || p === 'size' || p === 'value' || Reflect.has(t, p); },
    getOwnPropertyDescriptor() { throw new Error('descriptor blocked'); }
  });
})();
globalThis.dynamic = new Proxy({ __handle: 1 }, {
  get(t, p) { if (typeof p === 'symbol') return undefined; globalThis.dynamicGets++; return 'bad'; },
  has() { return false; }
});
globalThis.dynamicGets = 0;
globalThis.unreadable = new Proxy({}, {
  getOwnPropertyDescriptor() { throw new Error('unreadable descriptor'); },
  has() { return false; }
});
`;

const scenarios = {};
function add(name, setupSource, moduleName, memberName, inherited) {
  scenarios[name] = run(setupSource, moduleName, memberName, inherited);
}

add('terminalGetter', setup, '', 'fixture.value', false);
add('tagged', setup, '', 'tagged', false);
add('spoofedJava', setup, '', 'spoofedJava', false);
add('intermediateGetter', setup, '', 'fixture.value.x', false);
add('missing', setup, '', 'fixture.nope', false);
add('nested', setup, '', 'fixture.nested', false);
add('function', setup, '', 'fixture.method', false);
add('getterMembers', setup, '', 'fixture', false);
add('javaMethod', setup, '', 'javaProxy.add', false);
add('javaBean', setup, '', 'javaProxy.value', false);
add('javaMissing', setup, '', 'javaProxy.nope', false);
add('dynamicUnknown', setup, '', 'dynamic.anything', false);
add('unreadable', setup, '', 'unreadable.anything', false);
add('moduleNested', setup, modulePath, 'nested', false);
add('moduleMissing', setup, path.join(work, 'does-not-exist.js'), '', false);

const output = {};
for (const [name, value] of Object.entries(scenarios)) {
  output[name] = { result: value.result, counters: {
    getterCalls: value.sandbox.getterCalls,
    javaGetterCalls: value.sandbox.javaGetterCalls,
    javaMethodCalls: value.sandbox.javaMethodCalls,
    dynamicGets: value.sandbox.dynamicGets,
    tagCalls: value.sandbox.tagCalls,
  } };
}
console.log('PROBE_RESULT_JSON=' + JSON.stringify(output));"""


class McpProbeBehaviorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.template = load_template()

    def run_harness(self) -> dict:
        # The host sandbox permits files directly under the git-ignored build/ directory
        # but not in newly created nested temp directories.
        work_root = ROOT / "build"
        work_root.mkdir(parents=True, exist_ok=True)
        suffix = uuid.uuid4().hex
        template_path = work_root / f"mcp-probe-template-{suffix}.js"
        harness_path = work_root / f"mcp-probe-harness-{suffix}.js"
        module_path = work_root / f"mcp-probe-module-{suffix}.js"
        try:
            template_path.write_text(self.template, encoding="utf-8")
            harness_path.write_text(NODE_HARNESS, encoding="utf-8")
            module_path.write_text(
                "module.exports={nested:{ok:true},value:41,method:function(a,b){return a+b;}};\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                ["node", str(harness_path), str(template_path), str(work_root), str(module_path)],
                text=True,
                capture_output=True,
                timeout=30,
            )
            if completed.returncode != 0:
                self.fail(f"node probe harness failed:\n{completed.stdout}\n{completed.stderr}")
            marker = "PROBE_RESULT_JSON="
            line = next((line for line in completed.stdout.splitlines() if line.startswith(marker)), None)
            if line is None:
                self.fail(f"node probe harness produced no JSON:\n{completed.stdout}\n{completed.stderr}")
            return json.loads(line[len(marker):])
        finally:
            for path in (template_path, harness_path, module_path):
                try:
                    path.unlink()
                except OSError:
                    pass

    def test_shape_states_are_explicit_and_getters_are_not_executed(self) -> None:
        data = self.run_harness()
        self.assertEqual(0, data['tagged']['counters']['tagCalls'])
        self.assertEqual(0, data['spoofedJava']['counters']['tagCalls'])

        terminal = data["terminalGetter"]
        self.assertEqual("accessor", terminal["result"]["presence"])
        self.assertIs(True, terminal["result"]["present"])
        self.assertEqual("accessor", terminal["result"]["type"])
        self.assertEqual(0, terminal["counters"]["getterCalls"])

        intermediate = data["intermediateGetter"]
        self.assertEqual("accessor", intermediate["result"]["presence"])
        self.assertEqual("fixture.value", intermediate["result"]["failureAt"])
        self.assertEqual(0, intermediate["counters"]["getterCalls"])

        missing = data["missing"]["result"]
        self.assertEqual("missing", missing["presence"])
        self.assertIs(False, missing["present"])
        self.assertEqual("undefined", missing["type"])

        nested = data["nested"]["result"]
        self.assertEqual("present", nested["presence"])
        self.assertIs(True, nested["present"])
        self.assertEqual("object", nested["type"])

        member_probe = data["getterMembers"]
        self.assertEqual(0, member_probe["counters"]["getterCalls"])
        self.assertTrue(any(
            member["name"] == "value" and member["presence"] == "accessor"
            for member in member_probe["result"]["members"]
        ))
        self.assertTrue(any(
            member["name"] == "nested" and member["presence"] == "present"
            for member in member_probe["result"]["members"]
        ))

    def test_java_proxy_is_not_all_missing_and_bean_read_is_avoided(self) -> None:
        data = self.run_harness()
        method = data["javaMethod"]
        self.assertEqual("present", method["result"]["presence"])
        self.assertEqual("function", method["result"]["type"])
        self.assertEqual(0, method["result"]["arity"])
        self.assertEqual(0, method["counters"]["javaMethodCalls"])

        bean = data["javaBean"]
        self.assertEqual("accessor", bean["result"]["presence"])
        self.assertEqual(0, bean["counters"]["javaGetterCalls"])

        missing = data["javaMissing"]["result"]
        self.assertEqual("missing", missing["presence"])
        self.assertIs(False, missing["present"])

        dynamic = data["dynamicUnknown"]
        self.assertEqual("unverified", dynamic["result"]["presence"])
        self.assertIsNone(dynamic["result"]["present"])
        self.assertEqual(0, dynamic["counters"]["dynamicGets"])

        unreadable = data["unreadable"]["result"]
        self.assertEqual("unreadable", unreadable["presence"])
        self.assertIsNone(unreadable["present"])

    def test_modules_are_resolved_or_reported_without_hiding_the_error(self) -> None:
        data = self.run_harness()
        nested = data["moduleNested"]["result"]
        self.assertEqual("present", nested["presence"])
        self.assertIs(True, nested["present"])
        self.assertEqual("object", nested["type"])

        missing = data["moduleMissing"]["result"]
        self.assertEqual("unreadable", missing["presence"])
        self.assertIsNone(missing["present"])
        self.assertIn("Cannot find module", missing["error"])


if __name__ == "__main__":
    unittest.main()
