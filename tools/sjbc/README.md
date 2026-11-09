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
