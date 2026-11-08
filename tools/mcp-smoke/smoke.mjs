#!/usr/bin/env node
// AI.js Pro MCP · 冒烟/回归脚手架（零依赖，Node >= 18 的 fetch 即可）。
//
// 为什么存在：`.4` 每轮用 21 个 smoke 脚本手工重放 218 个工具；我们把自己最容易被改坏的不变量
// 固化成这个脚本，改完 MCP 一条命令就能回归（配合 K40 + `adb forward tcp:18768 tcp:8765`）。
//
// 用法：
//   node tools/mcp-smoke/smoke.mjs
//   node tools/mcp-smoke/smoke.mjs --base http://192.168.10.4:8765/mcp
//   node tools/mcp-smoke/smoke.mjs --only shape --skip apk
//   node tools/mcp-smoke/smoke.mjs --json > smoke-result.json
//
// 退出码：0=全过（跳过不算失败）；1=有 FAIL。

const argv = process.argv.slice(2);
function argValue(name, fallback) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
}
const BASE = argValue("--base", process.env.MCP_BASE || "http://127.0.0.1:18768/mcp");
const ONLY = argValue("--only", null);
const SKIP = argValue("--skip", null);
const AS_JSON = argv.includes("--json");
const DEFAULT_TIMEOUT = Number(argValue("--timeout", "60000"));
const LONG_TIMEOUT = Number(argValue("--long-timeout", "180000"));

let nextId = 1;
async function rpc(method, params, timeoutMs = DEFAULT_TIMEOUT) {
  const body = { jsonrpc: "2.0", id: nextId++, method, params: params || {} };
  const res = await fetch(BASE, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 200)}`);
  let payload;
  try { payload = JSON.parse(text); } catch (e) { throw new Error(`响应不是 JSON：${text.slice(0, 200)}`); }
  if (payload.error) throw new Error(`JSON-RPC 错误 ${payload.error.code}: ${payload.error.message}`);
  return payload.result;
}

/** 调工具并解析 content[0].text（我们的统一形状：text 永远是 JSON 信封，失败时 isError=true）。 */
async function call(name, args, timeoutMs = DEFAULT_TIMEOUT) {
  const result = await rpc("tools/call", { name, arguments: args || {} }, timeoutMs);
  const text = Array.isArray(result.content) && result.content[0] ? String(result.content[0].text || "") : "";
  let body = null;
  try { body = JSON.parse(text); } catch (e) { /* 纯文本结果（workspace_diff / learn_autojs_skill） */ }
  return { result, text, body, isError: result.isError === true };
}

// —— 断言助手 ——
function fail(msg) { throw new Error(msg); }
function eq(actual, expected, what) { if (actual !== expected) fail(`${what}: 期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`); }
function ok(cond, what) { if (!cond) fail(what); }
function has(obj, key, what) { ok(obj && Object.prototype.hasOwnProperty.call(obj, key), `${what || "对象"}缺少字段 ${key}`); }
/** 我们的核心不变量：失败结果 = content 信封 + isError + 顶层 errorCode（dev-1394/1398 固化的形状）。 */
function assertFailureEnvelope(r, expectedCode, what) {
  ok(r.isError, `${what}: 应该是 isError=true`);
  ok(r.body && typeof r.body === "object", `${what}: content[0].text 应该是 JSON 信封`);
  eq(r.body.ok, false, `${what}: 信封 ok`);
  has(r.body, "error", `${what}: 信封`);
  const code = r.body.error && r.body.error.code;
  if (expectedCode) eq(code, expectedCode, `${what}: error.code`);
  eq(r.result.errorCode, code, `${what}: 顶层 errorCode 应与信封一致`);
  has(r.result, "structuredContent", `${what}: 结果`);
  return code;
}

const CHECKS = [];
function check(id, title, run, opts = {}) { CHECKS.push({ id, title, run, optional: !!opts.optional }); }

// ======================= 协议 / 目录 =======================
let initResult = null;
check("init.serverInfo", "initialize 回 serverInfo（含 build/versionCode）", async () => {
  initResult = await rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "mcp-smoke", version: "1" } });
  const info = initResult.serverInfo || {};
  eq(info.name, "AI.js Pro MCP", "serverInfo.name");
  ok(info.version && info.build, "serverInfo 需要 version/build");
  ok(Number.isFinite(info.versionCode), "serverInfo.versionCode 应该是数字");
});
check("init.capabilities", "capabilities.tools.listChanged=true（升级后宿主知道要刷新）", async () => {
  eq(initResult.capabilities.tools.listChanged, true, "capabilities.tools.listChanged");
});
check("init.instructions", "instructions 是字符串（Gson 里是 JsonPrimitive，不是数组）", async () => {
  eq(typeof initResult.instructions, "string", "instructions 类型");
  ok(initResult.instructions.length > 500, "instructions 太短，可能被裁空");
});
check("init.toolCategories", "initialize 暴露 toolCategories（供 tools/list 按组裁剪）", async () => {
  const cats = initResult.toolCategories;
  ok(Array.isArray(cats) && cats.length >= 8, `toolCategories 应该有 >=8 组，实际 ${cats && cats.length}`);
  for (const c of cats) { has(c, "id", "分类"); has(c, "count", `分类 ${c.id}`); }
});

let toolsList = null;
check("tools.list.shape", "tools/list 每个工具都有 name/description/object inputSchema", async () => {
  toolsList = (await rpc("tools/list", {})).tools;
  ok(toolsList.length >= 100, `工具数过少：${toolsList.length}`);
  for (const t of toolsList) {
    ok(t.name && t.description, `工具缺少 name/description：${JSON.stringify(t).slice(0, 80)}`);
    eq(t.inputSchema && t.inputSchema.type, "object", `${t.name}.inputSchema.type`);
  }
});
check("tools.list.filterDefault", "不带参数返回全部且 toolFilter.applied=false", async () => {
  const r = await rpc("tools/list", {});
  const f = r.toolFilter || {};
  eq(f.applied, false, "toolFilter.applied");
  eq(f.returned, r.tools.length, "toolFilter.returned");
  eq(r.tools.length, toolsList.length, "两次 tools/list 数量应一致");
});
check("tools.list.filterGroups", "tools/list{groups:[toolkit]} 按组裁剪", async () => {
  const r = await rpc("tools/list", { groups: ["toolkit"] });
  eq(r.toolFilter.applied, true, "toolFilter.applied");
  ok(r.tools.length > 0 && r.tools.length < toolsList.length, `裁剪后数量异常：${r.tools.length}/${toolsList.length}`);
  ok(Array.isArray(r.toolFilter.availableGroups) && r.toolFilter.availableGroups.length >= 8, "应给出 availableGroups");
});
check("tools.list.filterTierCore", "tools/list{tier:core} 只回高频精简集", async () => {
  const r = await rpc("tools/list", { tier: "core" });
  eq(r.toolFilter.applied, true, "toolFilter.applied");
  ok(r.tools.length >= 10 && r.tools.length <= 60, `core 数量异常：${r.tools.length}`);
});

// ======================= 结果形状不变量 =======================
check("shape.success", "成功结果：content + structuredContent（有 ok 字段就必须为 true）", async () => {
  const r = await call("get_status", {});
  ok(!r.isError, "get_status 不应该失败");
  has(r.result, "content", "结果");
  has(r.result, "structuredContent", "结果");
  ok(r.body && typeof r.body === "object", "content[0].text 应该是 JSON");
  if (Object.prototype.hasOwnProperty.call(r.body, "ok")) eq(r.body.ok, true, "信封 ok");
  if (r.result.structuredContent && Object.prototype.hasOwnProperty.call(r.result.structuredContent, "ok")) {
    eq(r.result.structuredContent.ok, true, "structuredContent.ok");
  }
});

/** 形状清单（信息性）：主进程工具仍是扁平载荷，`.4` 已接受；这里只统计并打印，不判失败。 */
check("shape.inventory", "形状清单：ok/data 家族 vs 扁平载荷（信息性）", async () => {
  const samples = ["get_status", "file_list", "apk_list_available_apks", "workspace_list", "list_scripts"];
  const flat = []; const envelope = [];
  for (const name of samples) {
    try {
      const r = await call(name, {});
      const sc = r.result.structuredContent;
      if (sc && Object.prototype.hasOwnProperty.call(sc, "ok")) envelope.push(name); else flat.push(name);
    } catch (e) { /* 参数不全的工具跳过 */ }
  }
  return `NOTEok/data: ${envelope.join(",") || "无"} ｜ 扁平: ${flat.join(",") || "无"}`;
});
check("shape.failure.mainProcess", "失败（主进程）：信封 + isError + 顶层 errorCode", async () => {
  assertFailureEnvelope(await call("read_script", { path: "__smoke_missing__.txt" }), null, "read_script");
});
check("shape.failure.bridged", "失败（桥接 :tools 进程）：顶层 errorCode 也要有", async () => {
  assertFailureEnvelope(await call("file_read_text", { path: "__smoke_missing__.txt" }), "TOOL_FAILED", "file_read_text");
});
check("shape.failure.zip", "失败（zip 工具）：同样形状", async () => {
  assertFailureEnvelope(await call("zip_list", { path: "__smoke_missing__.zip" }), "TOOL_FAILED", "zip_list");
});
check("shape.failure.toolkitException", "失败（ToolkitException 路径）：也要有 structuredContent", async () => {
  const r = await call("terminal_write", { jobId: "terminal-smoke-none", input: "x" });
  assertFailureEnvelope(r, "JOB_NOT_FOUND", "terminal_write");
  ok(Array.isArray(r.result.structuredContent.nextActions), "structuredContent.nextActions 应该是数组");
});
check("shape.failure.workspaceExpired", "失败：过期工作区给 WORKSPACE_EXPIRED + nextActions", async () => {
  const r = await call("apk_search", {
    workspaceId: "11111111-2222-3333-4444-555555555555",
    editSessionId: "66666666-7777-8888-9999-000000000000",
    query: "smoke",
  });
  assertFailureEnvelope(r, "WORKSPACE_EXPIRED", "apk_search");
  ok(Array.isArray(r.body.nextActions), "信封 nextActions 应该是数组");
});
check("arg.hint", "参数类报错要自带「可用参数/必填」（`.4` P0-1）", async () => {
  const r = await call("native_decompile", { path: "/x", address: "0x1", sha256: "dead" });
  ok(r.isError, "应该失败");
  const message = (r.body && r.body.error && r.body.error.message) || "";
  ok(/可用参数/.test(message), `参数报错应给可用参数列表，实际：${message.slice(0, 120)}`);
  return "NOTE" + message.split("\n")[1];
});

check("shape.failure.cursor", "失败：无效游标是信封而不是纯文本", async () => {
  assertFailureEnvelope(await call("continue_result", { cursor: "smoke-bogus-cursor" }), "TOOL_FAILED", "continue_result");
});

// ======================= 具体回归（历次修复） =======================
check("coordinate.hasContent", "P0-1：coordinate_convert 也走统一结果（有 content）", async () => {
  const r = await call("coordinate_convert", { action: "state" });
  ok(!r.isError, `coordinate_convert 失败：${r.text.slice(0, 120)}`);
  has(r.result, "content", "结果");
  has(r.result, "structuredContent", "结果");
});
check("coordinate.unknownSpace", "未知空间名要报 INVALID_ARGUMENT（不再静默当 panel）", async () => {
  const r = await call("coordinate_convert", { action: "convert", from: "__bad__", to: "window", point: [1, 2] });
  assertFailureEnvelope(r, "INVALID_ARGUMENT", "coordinate_convert(from=__bad__)");
  ok(/可用/.test(r.body.error.message), "错误应列出可用空间名");
});
check("coordinate.calibrationScope", "标定只对它自己的 from/to 生效，不匹配要说明", async () => {
  try {
    await call("coordinate_convert", { action: "calibrate", from: "panel", to: "window", windowPoint1: [0, 0], screenshotPoint1: [10, 20], windowPoint2: [100, 100], screenshotPoint2: [110, 120] });
    const st = await call("coordinate_convert", { action: "state" });
    eq(st.body.data.calibration && st.body.data.calibration.from, "panel", "state.calibration.from");
    eq(st.body.data.calibration.to, "window", "state.calibration.to");
    const hit = await call("coordinate_convert", { action: "convert", from: "panel", to: "window", point: [50, 50] });
    eq(hit.body.data.affineSource, "calibrated", "匹配的一对应使用标定");
    const miss = await call("coordinate_convert", { action: "convert", from: "window", to: "screenshot", point: [50, 50] });
    ok(miss.body.data.affineSource !== "calibrated", "不匹配的一对不应该用标定");
    ok(typeof miss.body.data.calibrationNote === "string", "不匹配时应给 calibrationNote");
  } finally {
    await call("coordinate_convert", { action: "clear" });
  }
});
check("edit.writeTargetWithoutMatchText", "P1-C：write_target 不传 matchText 也能写（示例不再照抄必失败）", async () => {
  const path = "MCP测试/__smoke__/shape.txt";
  try {
    await call("file_create_directory", { path: "MCP测试/__smoke__", organize: false }).catch(() => {});
    const w = await call("file_edit_text", { path, targetVersion: "missing", encoding: "utf-8", organize: false, edits: [{ mode: "write_target", writeText: "smoke-ok" }] });
    ok(!w.isError, `file_edit_text 失败：${w.text.slice(0, 160)}`);
    eq(w.body.ok, true, "写结果 ok");
    const rd = await call("file_read_text", { path });
    ok(rd.body.ok && String(rd.body.data.text).includes("smoke-ok"), "读回内容应包含写入值");
  } finally {
    await call("file_delete", { path, organize: false }).catch(() => {});
    await call("file_delete_directory", { path: "MCP测试/__smoke__", organize: false }).catch(() => {});
  }
});
check("status.baseline", "get_status.baseline 基线自检在", async () => {
  const r = await call("get_status", {});
  const b = (r.body.data || r.body).baseline;
  has(b, "root", "baseline");
  has(b, "workspaces", "baseline");
  has(b, "outputRule", "baseline");
});
check("build.tools", "get_build_tools 给 ready/missing/present 三件套", async () => {
  const r = await call("get_build_tools", {});
  const payload = r.body.data || r.body;
  for (const k of ["ready", "missing", "present"]) has(payload, k, "get_build_tools");
  ok(Array.isArray(payload.missing), "missing 应该是数组");
});

// ======================= 需要真实 APK 的（找不到就跳过） =======================
async function apkCandidates(limit = 1) {
  const r = await call("apk_list_available_apks", {}, LONG_TIMEOUT);
  const payload = (r.body && (r.body.data || r.body)) || {};
  const items = payload.items || payload.available || [];
  return items.map((i) => String(i.path || i.name || "")).filter((p) => /\.apk$/i.test(p)).slice(0, limit);
}

async function firstApkPath() {
  const r = await call("apk_list_available_apks", {}, LONG_TIMEOUT);
  const items = (r.body && r.body.data && (r.body.data.items || r.body.data.available)) || [];
  const hit = items.find((i) => /\.apk$/i.test(String(i.path || i.name || "")));
  return hit ? String(hit.path || hit.name) : null;
}
check("apk.readTextMinimal", "apk_read_text 最小调用（只给 3 个必填）可用", async () => {
  const apk = await firstApkPath();
  if (!apk) return "SKIP: 设备上没有可用 APK";
  const ws = await call("apk_workspace_open", { path: apk, temporary: true }, LONG_TIMEOUT);
  const wid = ws.body.data.workspaceId;
  const se = await call("apk_session_create", { workspaceId: wid }, LONG_TIMEOUT);
  const sid = se.body.data.editSessionId;
  const rd = await call("apk_read_text", { workspaceId: wid, editSessionId: sid, locator: "zip_entry:AndroidManifest.xml" }, LONG_TIMEOUT);
  ok(!rd.isError, `apk_read_text 失败：${rd.text.slice(0, 160)}`);
  ok(rd.body.data.text.includes("manifest") || rd.body.data.text.includes("<?xml"), "应读回可读 XML");
  await call("apk_session_release", { editSessionId: sid }).catch(() => {});
  await call("apk_workspace_delete", { workspaceId: wid }).catch(() => {});
});
check("apk.axmlLocator", "P1-F：axml: 定位符能读回解码 XML（跳过明文 XML 的样本）", async () => {
  const candidates = await apkCandidates(4);
  if (!candidates.length) return "SKIP: 设备上没有可用 APK";
  const tried = [];
  for (const apk of candidates) {
    const ws = await call("apk_workspace_open", { path: apk, temporary: true }, LONG_TIMEOUT);
    const wid = ws.body.data.workspaceId;
    const se = await call("apk_session_create", { workspaceId: wid }, LONG_TIMEOUT);
    const sid = se.body.data.editSessionId;
    try {
      const rd = await call("apk_read_text", { workspaceId: wid, editSessionId: sid, locator: "axml:AndroidManifest.xml" }, LONG_TIMEOUT);
      if (rd.isError && /不是 AXML/.test(rd.text)) { tried.push(apk); continue; } // 明文 XML 的 fixture，换下一个
      ok(!rd.isError, `axml 读取失败：${rd.text.slice(0, 160)}`);
      ok(/<\?xml/.test(rd.body.data.text), "应返回解码后的 XML 文本");
      return `NOTE样本：${apk.split("/").pop()}`;
    } finally {
      await call("apk_session_release", { editSessionId: sid }).catch(() => {});
      await call("apk_workspace_delete", { workspaceId: wid }).catch(() => {});
    }
  }
  return `SKIP: ${tried.length} 个候选 APK 的 AndroidManifest.xml 本来就是明文 XML（没有 AXML 可测）`;
});
check("apk.cursorScope", "P1-E：dex 游标带 cursorTool，跨工具调用要点名归属", async () => {
  const apk = await firstApkPath();
  if (!apk) return "SKIP: 设备上没有可用 APK";
  const ws = await call("apk_workspace_open", { path: apk, temporary: true }, LONG_TIMEOUT);
  const wid = ws.body.data.workspaceId;
  const se = await call("apk_session_create", { workspaceId: wid }, LONG_TIMEOUT);
  const sid = se.body.data.editSessionId;
  const lc = await call("apk_dex_list_classes", { workspaceId: wid, editSessionId: sid, limit: 1 }, LONG_TIMEOUT);
  ok(!lc.isError, `apk_dex_list_classes 失败：${lc.text.slice(0, 160)}`);
  const nc = lc.body.data.nextCursor;
  if (!nc) return "SKIP: 该类清单没有下一页（样本太小）";
  eq(lc.body.data.cursorTool, "apk_dex_list_classes", "cursorTool");
  const wrong = await call("apk_continue", { nextCursor: String(nc) }, LONG_TIMEOUT);
  assertFailureEnvelope(wrong, "INVALID_CURSOR", "apk_continue(跨工具游标)");
  ok(/apk_dex_list_classes/.test(wrong.body.error.message), "错误应点名游标归属工具");
  await call("apk_session_release", { editSessionId: sid }).catch(() => {});
  await call("apk_workspace_delete", { workspaceId: wid }).catch(() => {});
});

// ======================= 运行器 =======================
async function main() {
  const picked = CHECKS.filter((c) => (!ONLY || c.id.includes(ONLY) || c.title.includes(ONLY)) && (!SKIP || !(c.id.includes(SKIP) || c.title.includes(SKIP))));
  const results = [];
  if (!AS_JSON) console.log(`MCP smoke @ ${BASE}  (${picked.length}/${CHECKS.length} 项)\n`);
  for (const c of picked) {
    const started = Date.now();
    let status = "PASS", detail = "";
    try {
      const out = await c.run();
      if (typeof out === "string" && out.startsWith("SKIP")) { status = "SKIP"; detail = out.slice(5).trim(); }
      else if (typeof out === "string" && out.startsWith("NOTE")) { detail = out.slice(5).trim(); }
    } catch (e) { status = c.optional ? "SKIP" : "FAIL"; detail = e.message; }
    const ms = Date.now() - started;
    results.push({ id: c.id, title: c.title, status, detail, ms });
    if (!AS_JSON) {
      const mark = status === "PASS" ? "✔" : status === "SKIP" ? "○" : "✘";
      console.log(`${mark} ${c.id.padEnd(32)} ${String(ms).padStart(6)}ms  ${detail}`);
    }
  }
  const pass = results.filter((r) => r.status === "PASS").length;
  const fail = results.filter((r) => r.status === "FAIL").length;
  const skip = results.filter((r) => r.status === "SKIP").length;
  if (AS_JSON) console.log(JSON.stringify({ base: BASE, pass, fail, skip, results }, null, 2));
  else console.log(`\nPASS ${pass} / FAIL ${fail} / SKIP ${skip}`);
  process.exit(fail ? 1 : 0);
}
main().catch((e) => { console.error("脚手架自身出错：" + (e && e.stack || e)); process.exit(2); });
