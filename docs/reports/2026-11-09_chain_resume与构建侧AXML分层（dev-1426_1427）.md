# chain_resume（一条链）+ 构建侧 AXML 分层修复（dev-1426 / dev-1427）

> 日期：2026-11-09 11:4x–12:1x · 起点 HEAD fdbd3505 · K40=cccc62c7（dev-1427）

## 1. `chain_resume`：checkpoint → 回编 → 对齐/签名 → 验签（dev-1426，W2 task-30）

- **恢复**：给了 `checkpoint` 就复用 `ToolkitApkSessions.rollback`（同 `apk_session_rollback` 语义），`expectedRevision` 显式给就用（过期即拒），没给取当前 revision 并回显 `restoredFrom.expectedRevisionSource`；检查点不存在/过期/非法 id → 抛 `CHAIN_RESTORE_FAILED`（**链未开始、无任何产物**）。
- **固定顺序**：`build → align+sign → verify`；build 用 `apk_build` 的真实实现，签名是 build 内部结果（`derivedFrom:"build"` + evidence），verify 真调 `ToolkitApkVerifier`。
- **任一步失败立即停**：`ok=false` + `stoppedAt` + `failure{where,phase,code,message}`，后续步骤 `skipped`；**绝不整链标成功**。
- **产物与结论**：`finalArtifact{path,bytes,sha256}`（磁盘实测）+ `verified`（只来自 verifier 逐方案结论）+ `runtimeVerified`；`steps` 是依赖闭包（verify⇒sign⇒build），跳过什么就说明不能声称什么。
- gradle：**98 套件 / 761 项 / 0 失败**（`McpChainResumeTest` 9 项，其中完整链路注入真实 apksig → `verified=true`）。

## 2. K40 真机跑出一个**真 bug**（不是 chain_resume 的）

```
apk_build{workspaceId, editSessionId, overwrite:true}  →  ok=false / INVALID_ARGUMENT
会话检查失败：[{locator:"zip_entry:AndroidManifest.xml", errorCode:"BUILD_CHECK_FAILED",
                message:"Can not decode without package"}]
```

**对照组**：同一调用换成真实 APK（1.7MB `fixture.apk`）→ **成功**。也就是说：**只要 APK 的 AXML 需要资源上下文，构建前检查就硬失败**（.4 的加固包很可能就是这种），而同一文件 `apk_verify` **本来就能分层回退** —— 能力已有、构建检查没用上。这正是设备4 §4.1 在**构建侧**的残留。

## 3. 修复：构建前 AXML 检查分层（dev-1427，W2 task-31）

- `check()` 的 Manifest 分支改分层：完整解码失败 → 改用 **`apk_verify` 同一条**独立 AXML 解析（`ToolkitAxmlLite.parseManifest`，无第二份实现）；兜底成功 → **warning** `MANIFEST_PRIMARY_DECODER_FALLBACK`（同源 `failureKind`/`primaryError` + 基础字段），**构建继续**；兜底也失败 → 失败但码可区分：`MANIFEST_UNREADABLE` / `MANIFEST_FORMAT_UNSUPPORTED` / `MANIFEST_BACKEND_ERROR`（保留 `primaryError`）。
- 检查返回新增 `warnings[]`/`warningCount`/`nextActions`，`apk_build` 成功返回里原样带出；`ToolkitApkVerifier.manifestFailureKind` 改 `internal` —— **两处共用唯一一份分类**，并有测试断言二者对同一文件 `failureKind/primaryError` 逐字相等。
- 描述写明边界：构建检查只关心「能不能安全回编」；产物依赖资源引用时请用 `apk_verify` 复核资源级完整性。

## 4. K40 真机验证（dev-1427，**同一 workspace/会话，前后对照**）

| 检查 | 结果 |
|---|---|
| `apk_build`（原先失败的 probe.apk） | **成功**：`probe_mcp_…_unsigned.apk` 1788B；`warnings=[{code:MANIFEST_PRIMARY_DECODER_FALLBACK, failureKind:resource_context_missing, primaryError:"Can not decode without package", packageName:com.aijspro.dtclinit, minSdk:21, elementCount:7, attributeCount:16}]` |
| `chain_resume{checkpoint, steps:"all"}` | **`ok=true` / `stoppedAt=null` / `verified=true` / `runtimeVerified=true`**；`chain=[build ok 292ms, align+sign ok, verify ok]`；`restoredFrom{checkpoint:a797bc83…, source:checkpoint}`；`finalArtifact{…_sign.apk, 8593B, sha256:e7cf525d…}`；`schemes V1/V2/V3 = valid/verified:true` |
| **独立核对**（不采信工具自报） | 设备 shell `sha256sum` = `e7cf525d1cfbb65445586aeefbc253ebc32ee3f0e0acd6e46e75d97de3e3e22d` **与 `finalArtifact.sha256` 逐位一致** ✓ |
| 全量冒烟 | **PASS 29 / FAIL 0 / SKIP 0**；`toolCount=223` |

## 5. 发布与提交

- dev-1426：candidate 144,418,885B / SHA-256 `9bbf4cbf…49c1`，LAN 已发布；提交 `f856e6a7`。
- dev-1427：candidate 144,419,777B / SHA-256 `068f65f3…04a5`，LAN 已发布（`update.json`=1427，手机拉取 HTTP 200）；提交 `fb4cc674`。
- 工作树干净；**200 ahead，未 push**。

## 6. 未做 / 边界

1. **`warning` 只覆盖 Manifest 这一条路径**：其它被编辑的 AXML 条目（`res/**/*.xml`）仍是 `decodeXml` 失败即 `BUILD_CHECK_FAILED`（本轮未动，是否需要一并分层待定）。
2. `chain_resume` **签名不是独立调用**：对齐+签名发生在 `build(sign=true)` 内部，所以 `align+sign` 步标 `derivedFrom:"build"`；签名阶段失败时标的是 **build** 步 + `failure.phase=sign`。
3. 链不重编资源、不安装、不覆盖源包；`finalArtifact` ≠ 可安装证明；`verified` 只是静态验签结论。
4. **失败时返回 payload（`ok=false`）而不是抛异常**，客户端必须看 `ok`（已写进描述与 notes）；只有恢复失败才抛 `CHAIN_RESTORE_FAILED`。
5. 不传 `expectedRevision` 等于「调用时的当前 revision」（弱并发保护）；要严格保护请显式传。
6. 兜底解析**不解码资源引用/组件级 XML**，故构建检查看不到资源级损坏（由 warning 文案引导去 `apk_verify`）。
7. 随包文档与 `tools/mcp-smoke` 未同步 `chain_resume`（W3/Lead scope）。
