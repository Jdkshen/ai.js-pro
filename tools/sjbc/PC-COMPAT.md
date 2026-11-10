# tools/sjbc PC 兼容说明（PC-COMPAT）

本文件只讲**在电脑上跑通 tools/sjbc** 需要的三件事，以及如何自检它们没被打回。
（工具来源/版本/字节表见 README.md；本文件与 README 不重叠。）

## 三处 PC 兼容点（都是 2026-11-10 本地 E2E 踩出来的，已固化在 tools/sjbc/build-all.mjs）

| # | 现象 | 处理（build-all.mjs 内） |
| --- | --- | --- |
| ① | --cert 给 **.apk** 时，keytool -printcert -jarfile 只认 **v1(JAR)** 签名；我们自己的候选包是 **v2/v3-only** → 报「不是签名的 jar 文件」 | 先 keytool，失败则遍历 apksigner verify --print-certs 解析 SHA-256 digest；**关键：Windows 上 apksigner.bat 不能由 execFileSync 直接执行 → 走 cmd.exe /c**。两条路都拿不到就给可抄的错误（直接给 64 位 hex） |
| ② | 上游 build_all.js 的配置是**一个 CFG 对象**（const CFG = { srcDir, outDir, certSha, deployTo }），不是四个顶级 const | replaceCfgObject：整块替换 CFG；若上游退回四顶级 const 形态则自动走 replaceConfig 兜底 |
| ③ | 上游 require 写的是**手机绝对路径**（/storage/emulated/0/脚本/sjbc-tools/*.js），PC 上必然找不到 | localizeRequires：把这些 require 重写到 build-all.mjs 所在目录（生成脚本在临时目录里跑，所以用绝对路径） |
**算法与容器格式一行未改**：包装只做「参数解析 + 上面三处文本重写 + 执行」。

## 自检（跑它就知道三处有没有被打回）

~~~
node tools/tests/sjbc-build-all.test.mjs
~~~

覆盖：上游仍是 CFG 对象 + 手机绝对路径 require（形状变了要先改包装）；包装里三处修复仍在（replaceCfgObject/localizeRequires/apksigner/cmd.exe/SHA-256 digest 解析）；**CLI 冒烟**：--cert 64 位 hex 能跑完并写出 manifest，--cert .apk（v2/v3-only）能取到指纹 —— 本机没有 apksigner/keytool 时**按任务要求 SKIP**（打印原因，不算失败）。

## 上游从设备重新拷贝后怎么办

1. 先把设备上的 8 个文件原样覆盖回 tools/sjbc/（**不要**覆盖 build-all.mjs —— 它是我们自己的包装）；
2. 跑上面的自检：若 ① 形状断言红了，说明上游 CFG 结构变了 → 更新 replaceCfgObject；
3. 若 ② 只红了 apk 冒烟那条，通常是本机缺 apksigner（SKIP 属正常）；
4. 自检全绿后再用真 .src 跑一次一键（node tools/sjbc/build-all.mjs --src … --out … --cert …）。
