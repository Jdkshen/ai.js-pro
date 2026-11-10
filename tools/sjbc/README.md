# tools/sjbc —— SJBC（L3 自定义 VM）PC 侧工具链

**来源**：device4（192.168.10.4）/storage/emulated/0/脚本/sjbc-tools/，2026-11-10 01:23 版本，**逐字节原样入库**（未改算法）。
对照：device4 Download/DSH-Go/AIjsPro-挂载SJBC到手机打包.md（一页设计说明：§三 三件必须对齐的事、§四 与打包界面的对应关系）。

| 文件 | 作用 | 字节 |
| --- | --- | --- |
| ajs2sjbc.js | 编译器：Auto.js 源码 → SJBC 字节码 | 27127 |
| opcodes.js | 指令表 | 1105 |
| pack_v5.js | v5 容器（随机 IV + HKDF 双层 AEAD） | 3137 |
| keyderiv.js | 从签名证书 SHA-256 派生主密钥 | 671 |
| vm.js | 参考 VM（自测用） | 5450 |
| sjbc-pack.mjs | 单文件打包（PC 版，参数 --cert <apk 或 sha256> --check） | 3545 |
| sjbc-pack.js | 单文件打包（手机/JS 版） | 2885 |
| build_all.js | 一键：扫目录 → 全部编译+加密 → 出 manifest（顶部 4 行配置） | 2533 |

**不入库**：samples/ 与 out/（属运行产物）；如需样例请从设备取。

## 一键脚本（只做参数化包装，不改算法）

~~~
# --src 源码目录  --out 产出目录  --cert 签名证书（apk 路径 或 直接给 sha256 hex）
node tools/sjbc/build-all.mjs --src ./脚本源码 --out ./sjbc-out --cert ./app-release.apk
node tools/sjbc/build-all.mjs --src ./脚本源码 --out ./sjbc-out --cert 3f1a...(64 位 hex)
~~~

- --cert 只支持两种形态：64 位 hex（直接给指纹）或 .apk（用 keytool -printcert -jarfile 取指纹，失败会明确报错）；
- --deploy-to <dir> 可选（对应 build_all.js 的 deployTo）；--check 透传给 sjbc-pack 自检；
- 实现方式：读 build_all.js → 只替换顶部 4 行配置（srcDir/outDir/certSha/deployTo）→ 写临时文件执行。
  **算法与容器格式一行未改**；build_all.js 本身仍可单独运行（手机内 node /storage/emulated/0/脚本/sjbc-tools/build_all.js）。

## PC 侧使用与已知坑（2026-11-10，Lead 本地 E2E 修过的三处）

> 详细的 PC 兼容说明与**自检脚本**另见 [`PC-COMPAT.md`](PC-COMPAT.md)（`node tools/tests/sjbc-build-all.test.mjs`：断言上游仍是 `CFG` 对象 + 手机绝对路径 `require`，且包装里三处修复仍在）。本节是「使用入口 + 速查」，与它不冲突。

### 一条可复跑命令

~~~bash
# --src 源码目录（*.src）  --out 产出目录  --cert 签名证书（.apk 或 64 位 sha256 hex）
node tools/sjbc/build-all.mjs --src ./脚本源码 --out ./sjbc-out --cert ./app-release.apk --check
~~~

预期：每个 `.src` 出一个 `.sjbc` + 一份 `manifest.json`（含 `cert`），并打印 `sjbc: src=… out=… certSha=…`；加 `--check` 时透传给 `sjbc-pack` 做「解密成功 + 篡改被拒」自检。
（`--deploy-to <目录>` 对应 `build_all.js` 的 `deployTo`，用于热加载投放。）

### 三处已知坑（都在 `build-all.mjs` 里处理了，改动**不涉及算法/容器格式**）

| # | 坑 | 现象 | 处理（`build-all.mjs`） |
|---|---|---|---|
| 1 | **v2/v3-only 的包 `keytool` 不认** | `keytool -printcert -jarfile x.apk` 报「不是签名的 jar 文件」（我们的候选包就是 v2/v3-only，没有 v1 签名） | 先试 `keytool`，失败**回退 `apksigner verify --print-certs`**（`resolveCertSha()`，`:50-68`）；Windows 下 `apksigner.bat` 不能直接被 `execFileSync` 执行 ⇒ **走 `cmd /c`**（`tryExec()`，`:70-81`）。apksigner 候选顺序：PATH → `ANDROID_HOME`/`ANDROID_SDK_ROOT` → `C:\Android`/`D:\Android` 下 `build-tools`（版本倒序）。都拿不到就让你直接给 64 位 hex |
| 2 | **上游配置是 `CFG` 对象** | `build_all.js` 顶部是 `const CFG = { srcDir, outDir, certSha, deployTo }`（**不是四个顶级 `const`**），照老写法替换会「改了但没生效」 | `replaceCfgObject()`（`:116-137`）重写整个 `CFG` 块；找不到 `CFG` 时**兼容**四常量变体逐个替换；两者都找不到 ⇒ 明确抛「上游可能改过结构，请人工核对」 |
| 3 | **`require` 是手机绝对路径** | `build_all.js` 里 `require('/storage/emulated/0/脚本/sjbc-tools/*.js')` 在 PC 上必然失败 | `localizeRequires()`（`:139-143`）把这些 require **改写到本目录**（`tools/sjbc/*.js`）后再执行；原文件**一字不改**（只改写到临时目录的副本，`:148-153`） |

### 设计约束（别顺手「优化」掉）
- `build-all.mjs` **只做参数化包装**：读 `build_all.js` → 替换上面那些配置/路径 → 写临时文件执行 ⇒ **算法与 v5 容器格式一行未改**，`build_all.js` 仍可单独在手机里跑（`node /storage/emulated/0/脚本/sjbc-tools/build_all.js`）。
- 宿主函数表 `HOST = ['print','len','push','slice0']` **必须与宿主实现一致**，少一个就会把 `push(...)` 当「未定义函数」编译失败（见 `pc-tools/README.md` §6.1 与本目录 `README` 上文）。
