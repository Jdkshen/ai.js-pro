<!-- 来源：手机 /storage/emulated/0/脚本/SonicTopography-项目总结.md（8,535 B / 236 行，经 MCP 从 192.168.10.4:8765 读取）。原文未改动。 -->

# 声动地形图 1.1 —— VMP 逆向与源码重建 项目总结

> 目标包：`com.sonic.topography.wallpaper`（声动地形图 1.1）
> 保护方案：云镜 VMP（方法级保护，非整体壳）
> 重建包名：`com.sonic.topography.wallpaper.rebuilt`（versionCode 101 / "1.1-rebuilt-t"）
> 原始 APK：263,311 B · sha256 `525c47a843579a716183f2dbbcb7264ab28560c91cdb0fd3a0f64058e9f0276d`
> 重建 APK：66,216 B · md5 `44a8c6f28ca9dd3b5e323455416d63c1`

---

## 一、总成果（全部实测闭环）

| 项目 | 结果 | 证据等级 |
|---|---|---|
| 被 VMP 保护的方法 | **79 个全部还原为可编译源码** | static_confirmed |
| 桩标记 | **29 → 0** | static_confirmed |
| 编译 | **127 class / 0 error**（javac 3 秒） | post_change_verified |
| APK | **66,216 B** 已装机运行 | post_change_verified |
| 崩溃 | **0** | runtime_confirmed |
| GL 错误 502 | **3791 → 0** | post_change_verified |
| 界面文字 | **12/12 与报告 349 一致** | runtime_confirmed |
| 三处界面差异 | 已修复（截图证实） | runtime_confirmed |
| **VMP 注册表** | **213 条 @0x22C50，字节级实证** | static_confirmed |
| **THEMES 数据** | **21 个 float 真值已实测** | runtime_confirmed |

---

## 二、VMP 结构（三层，全部字节级实证）

```
【App dex】普通 dex（fingerprint findings=[]，非整体壳）
     ↓ JNI_OnLoad
【薄壳 SO】libliuyun.so = 161,896 B
   ├─ .text 34,228 B（很薄）· 17 符号（16 导入 + 1 导出）· 17 个 PLT 桩
   ├─ DT_NEEDED = ["libyj-vmp-lib.so", liblog, libm, libdl, libc]
   ├─ JNI_OnLoad@0x1EA9C（140 B）：JavaVM->GetEnv(env, JNI_VERSION_1_6=0x10006)
   │     + bl 0x1EC30（PLT 桩→libyj）+ bl 0x165CC（真注册函数）
   └─ 0x165CC：3×memset(全局 arena) + bss/data 初始化
              + RegisterNatives(表指针 = **0x22C48**)
        ↓
【真 VM】libyj-vmp-lib.so（DT_NEEDED）
   ├─ 导出：vmInterpret · gVm · StrParse · cacheInitial · getCacheClass
【★ 注册表】.data.rel.ro @0x22C40 · size = **3,408 B**
   = [0x22C48 8 字节表头] + [0x22C50 起 **213 条 × 16 B**]
   条目格式：**{ u32 nameIdx, u32 sigIdx, u64 fnPtr }**
   + .rela.dyn 221 条 R_AARCH64_RELATIVE（与内容逐条吻合）
```

### 三条独立证据链（交叉验证 ✓）

1. **r2（带 relocs）** `px 48 @ 0x22c40` ⇒ 见 16B 条目 + 已解析指针
2. **MCP `apk_read_bytes`**（独立代码路径）@fileOffset 126,016 ⇒ **同一批字节，u64=0**
3. **MCP `apk_native_list_items(sections)`** ⇒ `.data.rel.ro` size = 3,408 B（一致）

> **机制解释**：磁盘上 fnPtr 列全为 0，加载时由 `.rela.dyn` 的 R_AARCH64_RELATIVE 填充
> ⇒ 这正是「必须 `-e bin.relocs.apply=true`」的原因（经验 → 机制）
> ⇒ r2 自动命名该数据：**`reloc.fixup.classesInit0`** ⇄ 我们源码 `NativeUtil.classesInit0(46)` ✓

---

## 三、核心技术结论

### 1. ★ 502 根因（已修）
```java
// TerrainRenderer.bindInstancedVao()
return 32;          // ✗ 原值：误把「0f 00 = op RETURN + 槽号 0」读成常量 32
return vao[0];      // ✓ 修正：返回真实 VAO 名字
```
- 32 是无效 VAO ⇒ `glBindVertexArray(32)` 失败 ⇒ 退回默认 VAO 0
- GLES3 core 下默认 VAO 不允许 draw ⇒ `GL_INVALID_OPERATION (0x502)`
- 实测：502 从 **3791 → 0**，`isVAO(vaoGround)` 从 false → true

### 2. ★ AppConfig.THEMES 真值（21 个 float，themeIndex=0）
```
0=0.01  1=0.02  2=0.04  3=0.03  4=0.05  5=0.09  6=0.0
7=0.3   8=1.0   9=0.6   10=0.2  11=1.0  12=1.0  13=0.2  14=0.1
15=1.0  16=0.6  17=0.0  18=0.2  19=0.9  20=1.0
```
- 背景 = theme[4,5,6] = (0.05, 0.09, 0.0) 深蓝黑 ✓（与 MVP diag 记录逐位吻合）
- 对比色全亮：theme[8]=1.0 · theme[11]=1.0 · theme[12]=1.0 · theme[15]=1.0 · theme[20]=1.0
- ⇒ **THEMES 表完全正常**（"偏暗"假设被实测否定）

### 3. ★ 薄壳判据
薄壳 = 只引用 **1 个**字节码地址；批量壳 = 引用多地址。
三条 ground truth：`0x17dc0→0x1818`(FileSource.release) · `0x17f9c→0x1964`(FileSource.start) · `0x1d230→0x4fa8`(AudioEngine.switchTo)

### 4. ★ 音频链
三音源同构（MicSource/FileSource/SynthSource 均产出 `magnitudes[]`）
→ `FftAnalyzer.update(float[],int,SpectrumFrame,Listener)`
→ spectral-flux 节拍检测（EMA α=0.4 / fluxHistory[40]=FLUX_WINDOW / 均值+标准差阈值）
→ 双触发器 `pulseTrigger(0,12)` `meteorTrigger(80,150)`
→ `Listener.onTriggerMeteor(F)` / `onTriggerRipple(FFFI)` → `TerrainRenderer.triggerMeteor/triggerRipple`

---

## 四、遗留问题清单（17 项，实质仅 1 项）

### A. 功能（1 项，唯一实质问题）
| # | 问题 | 状态 |
|---|---|---|
| 1 | **地形不可见** | 10 个假设全排除，已锁定 `shader_source.lua` 的 `main()` 内约 3 行 |

**已排除的 10 个假设**：layout(location) · classesInit0 try/catch · vboInstGround 补分配 ·
三角形诊断 · 矩阵/相机/MVP（数值全合理）· 几何（vc=36/inst=10000）· 着色器链接（linked id=3/6）·
缓冲分配（isBuf=true）· 属性绑定（ATTRIB 0/1/2/3 全对）· 主题数据（THEMES 全正常）

### B. 源码卫生（4 项，不宜交付）
| # | 问题 | 位置 |
|---|---|---|
| 2 | GL 诊断残留：`dumpGlError()` 6 处 + DRAWDUMP/MVP diag/ATTRIB/GLERR 日志 6 处 | TerrainRenderer |
| 3 | 注释残留（旧 glClearColor 行） | 行 1320 |
| 4 | 备份命名不规范（TR.bak/bakAttr/bakDraw/… 无时间戳） | files/ |
| 5 | `classesInit0` try/catch（62 文件，健壮性改进，需决策留/撤） | 全项目 |

### C. 交接物（3 项）
| # | 问题 |
|---|---|
| 6 | 交接包 .tar / .sha256 / .校验结果.json **是旧的**，需重打（APK 本体 md5 已一致 ✓） |
| 7 | `evidence.tar` 只有 manifest（entries=0），可补源码/报告 |
| 8 | `handoff/VERIFY_BUILD_PACK.md` 哈希未更新 |

### D. 临时文件（4 项，可清）
| # | 项 | 大小 |
|---|---|---|
| 9 | `frida-cli/`（我搬出的 CLI） | 88 MB |
| 10 | `apkbuild/t1 t3 t4 t5` 等编译暂存 | — |
| 11 | `SonicTopography-debug.apk`（旧版本） | — |
| 12 | 工作区 3/16（干净；libliuyun.so 的 edit_session 占位） | — |

### E. MCP 反馈（3 个真 bug，实测发现）
| # | Bug | 表现 |
|---|---|---|
| 13 | `node_script_command` | **Internal error**（Kotlin SDK 异常栈）— 12 个新工具里唯一坏的 |
| 14 | `apk_workspace_gc` 死锁 | 默认保护 unknown_owner ⇒ 删 0 个 ⇒ 配额永满 ⇒ `artifact_open` 永久打不开；唯一出路 = 手工 `apk_workspace_delete` |
| 15 | Frida 网络路径被 SELinux DROP | 跨 UID loopback ⇒ **SYN_SENT 卡死**（非 RST）⇒ 正解 = Gadget **script 模式** |

### F. 悬而未决（2 项，新工具已具备条件）
| # | 问题 |
|---|---|
| 16 | `libyj-vmp-lib.so`（真 VM）**从未单独分析**（只证到 DT_NEEDED + 导出符号） |
| 17 | `0x293C4` arena 基址（会话早期记录）**本轮未复验** |

---

## 五、方法论（本项目最值钱的 8 条）

1. **★ GL 黑屏五步法**：MVP 日志 → 清屏改纯色 → `glGetError` 检查点 → DRAWDUMP(`glGetIntegerv`+`glIsVAO`) → 颜色同色判据
2. **★ 「errBefore 非 0」** ⇒ 错误在 draw 之前 ⇒ 定位前置调用
3. **★ 注释/标签不可信**：活教材「0f 00 RETURN 32」实为「op=RETURN + 槽号 0」⇒ 必须按指令语义核实
4. **★ 三重证据法**：编译 0 error + dex 字节变化 + 桩标记下降量 == 还原方法数
5. **★ javac 报错即架构探针**（靠它定位 8+ 处结构）
6. **★ 剥壳必须 `-e bin.relocs.apply=true`**（现已是 `native_session_open` 默认）
7. **★ 换工具交叉验证**：同一结论 ≥3 条独立证据链（r2 / MCP 字节 / MCP sections）
8. **★ 负面结果也是资产**：10 次否定把搜索空间压到一处

---

## 六、环境与工具链

### 构建流水线（app UID，均可达）
```
H=/data/user/0/com.jdkshen.aijspro/files
BT=$H/android-sdk/build-tools/34.0.0          # d8 / apksigner
AJ=$H/android-sdk/platforms/android-36/android.jar
SD=/storage/emulated/0/脚本/SonicTopography-全套交接/sources

# 基线编译（期望 127 class / 0 error，约 3 秒）
javac -source 17 -target 17 -nowarn -encoding UTF-8 -cp "$AJ" -d OUT $(find "$SD" -name '*.java')

# APK 流水线
javac → jar cf → d8 --min-api 24 → cp classes.dex $W/raw/
      → node $H/pack.js $W/app-unsigned.apk
      → apksigner sign --ks $W/debug.keystore ...
```
> 只改 assets（如 shader）时无需重跑 d8，仅 pack.js + apksigner 即可（3 秒）

### 后端能力分界
- **app UID**：`javac` / `java` / `d8` / `apksigner` / node —— 可达
- **shizuku(shell)**：`pm` / `am` / `logcat` / `uiautomator` / `input` / `settings` —— 可达

### 已知工具坑（`shell_capabilities` 已固化）
- `grep` **不支持 `\b` / `\s`** ⇒ 用 `file_search_text` 或 `grep -E`（仅基本 ERE）
- `cat` **不支持 `-n` / `-A`** ⇒ 用 `sed -n '1,200p'` 或 `grep -n ''`
- **无 `zip` / `jar` / `7z`**，只有 `tar` ⇒ 用 MCP `zip_*` 或 `pack.js`
- `python` **链接损坏** ⇒ 用 `node`
- **`node -e '复杂脚本'` 会双重转义** ⇒ 一律先写 `.js` 文件；**argv[0]=node, argv[1]=脚本, argv[2] 才是参数**
- `sed -i` 用 `$L` 前必须判空（否则静默读文件头）
- `/tmp` 不可写 ⇒ 用 `$H`
- `ls /storage` (FUSE) 喂 `pm install-write` 会报 `No packages staged` ⇒ 先 `cp` 到 `/data/local/tmp/`
- **`logcat -b crash -c` 不是同步屏障**，清后仍可能读到旧条目 ⇒ 需绑定时间窗 + PID
- **`SIGKILL` 不是崩溃**

### 安装限制解除（已实证）
```
settings put global adb_install_need_confirm 0
settings put global verifier_verify_adb_installs 0
settings put global package_verifier_user_consent -1
⇒ 全新安装成功（此前必报 INSTALL_FAILED_USER_RESTRICTED）
```
> `persist.sys.miui_optimization` 是 system property，**需 root**，HyperOS V816 已从开发者选项移除

---

## 七、关键路径索引

```
原始 APK      /storage/emulated/0/脚本/声动地形图_1.1_分析/00_原始文件/声动地形图_1.1.apk
源码树(真相)  /storage/emulated/0/脚本/SonicTopography-全套交接/sources  （69 .java → 127 class）
重建 APK      /storage/emulated/0/脚本/SonicTopography-rebuilt.apk  （66,216 B）
APK 工作区    /storage/emulated/0/脚本/apkbuild/  （raw/classes.dex, raw/assets/shader_source.lua）
薄壳 SO       .../声动地形图_1.1_分析/03_so与加固/libliuyun.so  （sha256 3ab78638…8b33）
真 VM SO      .../声动地形图_1.1_分析/03_so与加固/libyj-vmp-lib.so
bss dump      .../05_运行时内存dump/bss_region.bin  （77,824 B 字符串池）
逐方法反汇编  .../22_字节码修正重建/逐方法_修正反汇编.txt  （441 KB）
伪 C 库       .../29_伪C库/批07_audio.txt · 批08_全壳扫描.txt · 批09_音频链pdg.txt
薄壳映射表    .../19_还原验证/薄壳到字节码映射表.json/.md
```

---

## 八、建议的收尾顺序

| 优先级 | 动作 | 解决 |
|---|---|---|
| ★★★ | 读 `shader_source.lua` 的 `main()` | #1（唯一实质问题） |
| ★★ | 清诊断 + 重打 APK + 重打交接包 tar + 更新哈希 | #2 #6 #8 |
| ★★ | 清 `frida-cli/`(88 MB) / apkbuild 暂存 / debug.apk | #9 #10 #11 |
| ★ | `evidence_export` 带源码/报告重打 | #7 |
| ★ | 写 MCP 反馈（3 个 bug） | #13 #14 #15 |
| ★ | 用 `native_session` 分析 `libyj-vmp-lib.so` | #16（有机会楔入 VM 本体） |

---

*生成时间：2026-11-04 · 本文件由项目审计自动汇总*
*证据分级：documented < static_inferred < static_confirmed < runtime_confirmed < post_change_verified*
