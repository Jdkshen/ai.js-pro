# 2026-10-17 ImGui 桥 native 上下文定位（选项 1：静态逆向）

> 目标：解释第三方 `libImGuiBridge.so` 在 `:native` 进程里首次 Button 时 `GImGui == NULL`（崩溃 pc `0x12bc4c`，fault addr `0x15e0`）的根因，并给出宿主侧候选修复。
> 范围：纯静态分析 + 已有日志取证；**未改代码、未跑 gradle/adb**，不修改 .so/脚本。
> 材料：`output/imgui-probe/auto_jspro_imgui/{main.js,src/js/imgui.js,lib/arm64-v8a/libImGuiBridge.so,lib/ImGuiBridge.dex,build_dex/classes/.../ImGuiBridge.class}`；
> 反汇编工具：`D:\VisualStudio\Shared\Android\AndroidNDK\android-ndk-r27c\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-{readelf,objdump,nm}.exe`；
> 运行证据：`output/imgui-probe/dev947-imgui-full.log`（2026-10-17 21:28 :native）、`dev949-imgui-full.log`、
> `dropbox-native.log`（10-17 20:40 / dev-938）、`.artifacts/qjs-crash/imgui2m.log`（10-01 主进程）。

## 0. 结论（一句话）

**GImGui 不是“没创建”，而是在首帧 `NewFrame` 之后、Button 之前被 `ImGui::DestroyContext` 清成了 NULL**：整个 .so 只有 5 条指令能写 `GImGui` 变量（§3.4），其中**唯一能写 0 的是 `ImGui::DestroyContext`**（`csel x8, xzr, x21`；0x84e8c），而它在本库内**只有一个调用点**——`nativeShutdown`（0x7b4ac）；`nativeShutdown` 只由脚本 `src/js/imgui.js:189` 的 `events.on("exit")` 处理器调用。因此根因类别是：**退出清理（销毁 ImGui 上下文）与渲染帧序列之间没有串行约束，`DestroyContext` 插进了 `nativeNewFrame → nativeButton` 之间**。
符号抢占、CreateContext 失败、prev_ctx 恢复、单次运行内的双 .so 副本均被实测排除（§2、§3.4、§4）。
附带确认第二个独立缺陷：`nativeInit` 在 **main 线程**执行 CreateContext + `eglMakeCurrent`，而帧循环在 **Script-0**；首帧 `eglMakeCurrent` 返回 **EGL_BAD_ACCESS(3002)** 且代码不检查返回值（§5.1）——这是桥“单线程假设”被打破的第二个表现，也是即使不崩画面也出不来的直接原因。

---

## 1. 二进制与加载事实（实测）

    ELF64 AArch64 DYN (Shared object), 6,543,208 B, 未 strip（带 .symtab 与 debug_*）
    SONAME: libImGuiBridge.so
    NEEDED: libandroid.so libEGL.so libGLESv3.so liblog.so libm.so libdl.so libc.so
    .dynamic: FLAGS = BIND_NOW, FLAGS_1 = NOW      （没有 DF_SYMBOLIC；无符号版本脚本）

关键 section / 段：

| 项 | 地址/大小 | 说明 |
| --- | --- | --- |
| `.text` | `0x7b1e0` + `0xf8d28` | 代码 |
| `.plt` | `0x173f10` + `0x4c90` | PLT0 32B，之后 1223 条 ×16B，与 `.rela.plt` 一一对应 |
| `.rela.plt` | `0x2b3f8` + `0x72a8` | 1223 条 `R_AARCH64_JUMP_SLOT` |
| `.rela.dyn` | `0x1fcb0` + `0xb748` | 含 `GImGui` 的 `R_AARCH64_GLOB_DAT` |
| `.got` | `0x17e110` + `0x180` | 起点恰为 `0x17e110` |
| `.got.plt` | `0x17e290` + `0x2650` | 1223 个 JUMP_SLOT 槽 |
| `.bss` | `0x186000` + `0x7de0` | 桥自己的全局（g_Initialized / EGL 句柄 / GImGui） |
| GNU_RELRO | `[0x179ba0, 0x181000)` | 覆盖 `.got/.got.plt`，加载后只读（运行期无法篡改 GOT） |

- **ImGui 版本 = 1.92.8 WIP**：`nativeInit` 把 `.rodata@0x497d2` 的字符串与布局尺寸传给 `DebugCheckVersionAndDataLayout`（`sizeof(ImGuiIO)=0xc18=3096`、`sizeof(ImGuiStyle)=0x53c=1340`、`ImVec2=8`、`ImVec4=16`、`ImDrawVert=20`、`ImDrawIdx=2`）。
- `.dynsym` 1928 条，其中 `_ZN5ImGui*` 的 **GLOBAL DEFAULT FUNC 有 850 条** —— 整套 ImGui C++ API 都是导出符号。

---

## 2. GOT / relocation / PLT 实测（任务第 1 点）

### 2.1 0x17e110 这个 GOT 槽的真实绑定

    $ llvm-readelf -r -W libImGuiBridge.so | findstr 17e110
    000000000017e110  000003eb00000401 R_AARCH64_GLOB_DAT   0000000000186028 GImGui + 0

- `0x17e110` = `.got` 的第一项；重定位类型 **`R_AARCH64_GLOB_DAT`**（不是 COPY/JUMP_SLOT），绑定符号 **`GImGui`**，其定义为 `.bss:0x186028`（8 字节）。
- `--dyn-syms` 第 1003 条：`0000000000186028 8 OBJECT GLOBAL DEFAULT 23 GImGui` —— **GLOBAL、非弱、未版本化**，即“可被同名符号抢占”的形态（但见 2.3）。
- 加载器把 `&GImGui` 写进该 GOT 槽；库内所有上下文访问都读同一槽：
  - `ImGui::GetCurrentContext()`（0x8154c）：`adrp x8,0x17e000; ldr x8,[x8,#0x110]; ldr x0,[x8]; ret`
  - `ImGui::SetCurrentContext(ImGuiContext*)`（0x84728）：同上 + `str x0,[x8]; ret`
  - `ImGui::ButtonEx` 开头（0x12bc0c+0x30…+0x44）：`adrp x9,0x17e000; ldr x9,[x9,#0x110]; ldr x26,[x9]; ldr x24,[x26,#0x15e0]`
- 与 tombstone 完全对上：`x9 = base + 0x186028 = &GImGui`、`x26 = *(x9) = GImGui = 0`、fault addr `0x15e0` = `GImGui->CurrentWindow` 偏移。

### 2.2 CreateContext / NewFrame / Button 相关的 PLT 条目

PLT 条目 = 0x173f30 + 16×idx，与 `.rela.plt` 顺序一致；以下均由 `llvm-objdump` 的 `@plt` 注释直接读出（非推测）：

| PLT 地址 | 符号 | 本库内实现地址 |
| --- | --- | --- |
| 0x174010 | `_ZN5ImGui13CreateContextEP11ImFontAtlas` | 0x84778 |
| 0x174020 | `_ZN5ImGui5GetIOEv` | 0x9041c |
| 0x174030 | `_ZN5ImGui15StyleColorsDarkEP10ImGuiStyle` | — |
| 0x174040 | `_Z22ImGui_ImplAndroid_InitP13ANativeWindow` | 0x1539e0 |
| 0x174050 | `_Z22ImGui_ImplOpenGL3_InitPKc` | 0x153b50 |
| 0x174090 | `_ZN5ImGui14DestroyContextEP12ImGuiContext` | 0x84e58 |
| 0x1740e0 | `_Z26ImGui_ImplOpenGL3_NewFramev` | 0x153ee8 |
| 0x1740f0 | `_Z26ImGui_ImplAndroid_NewFramev` | 0x153a5c |
| 0x174110 | `_ZN5ImGui8NewFrameEv` | 0x92280 |
| 0x174120 | `_ZN5ImGui6RenderEv` | 0xa077c |
| 0x1741c0 | `_ZN5ImGui6ButtonEPKcRK6ImVec2` | 0x12be34 |
| 0x174850 | `_ZN5ImGui17GetCurrentContextEv` | 0x8154c |
| 0x174bc0 | `_ZN5ImGui17SetCurrentContextEP12ImGuiContext` | 0x84728 |
| 0x177ee0 | `_ZN5ImGui8ButtonExEPKcRK6ImVec2i` | 0x12bc0c |

**要点**：这些函数虽然都定义在同一个 .so 里，**本库对自己的调用仍全部走 PLT**（PIC 默认可见符号，链接器不能假设不被抢占）。所以“会不会被进程里其它同名符号抢走”完全取决于动态链接器的查找域。

### 2.3 是否可能被进程内其它同名符号抢占：已排除

- **运行时实测**（dev947，`:native` 进程 pid 21860）：

      D/nativeloader: Configuring clns-10 for other apk . target_sdk_version=28,
          uses_libraries=ALL, library_path=/data/user/0/com.jdkshen.aijspro/files/so_d0a0f5e9,
          permitted_path=/data:/mnt/expand
      D/nativeloader: Load /data/user/0/.../files/so_d0a0f5e9/libImGuiBridge.so
          using isolated ns clns-10 (caller=/data/user/0/.../native-staging/1ab54f88a3af/lib/ImGuiBridge.dex): ok

  该 `clns-10` 是脚本 `DexClassLoader` 的 classloader namespace，`library_path` 只有 `files/so_xxx`；宿主引擎的 `libquickjs_jni.so` 不在其查找域（且加载发生在 **main 线程**触发 <clinit> 时，见 §5.1）。
- **实验侧证（dev-940）**：引擎用 `-fvisibility=hidden -fvisibility-inlines-hidden` 隐藏全部非 JNI 符号（含 `GImGui`）后，崩溃点逐字节不变（仍 `0x12bc4c` / `0x7b6d4` / `0x15e0`）。
- **RELRO**：`.got` 落在 `GNU_RELRO` 内，加载后为只读，运行期不可能被改写。
- 结论：**当前进程/命名空间下没有抢占源**。GOT 槽 `0x17e110` 绑定的就是本 .so 自己的 `.bss:0x186028`（tombstone 寄存器 `x9` 也证实）。

---

## 3. 静态数据流（任务第 2 点）

### 3.1 nativeInit（0x7b248–0x7b484）：CreateContext 之后**没有**显式 SetCurrentContext

反汇编要点（节选，`llvm-objdump -d`）：

    7b264  adrp  x9, 0x186000
    7b270  ldrb  w8, [x9]              ; g_Initialized
    7b274  tbnz  w8, #0, 0x7b41c      ; 已初始化 -> 直接 return（二次 nativeInit 是 no-op）
    7b27c  bl    ANativeWindow_fromSurface@plt
    7b28c  bl    eglGetDisplay@plt
    7b2a4  bl    eglInitialize@plt          ; 以下所有 EGL 返回值都不检查
    7b2e0  bl    eglChooseConfig@plt
    7b2f4  bl    eglCreateWindowSurface@plt
    7b324  bl    eglCreateContext@plt
    7b338  bl    eglMakeCurrent@plt         ; 在调用线程（main）把 ctx 设为 current，之后不释放
    7b374  bl    __android_log_print@plt    ; "EGL %dx%d"  -> logcat: I ImGuiBridge: EGL 880x1155
    7b398  bl    DebugCheckVersionAndDataLayout@plt ("1.92.8 WIP", 3096,1340,8,16,20,2)
    7b39c  mov   x0, xzr
    7b3a0  bl    ImGui::CreateContext@plt    ; 返回值被丢弃；本库没有紧跟其后的 SetCurrentContext
    7b3a4  bl    ImGui::GetIO@plt
    7b3a8  str   xzr, [x0, #0x20]           ; io.IniFilename = nullptr（若 GImGui 为 NULL 这里就会崩）
    7b3b8  bl    ImGui_ImplAndroid_Init@plt  ; 把 ANativeWindow 存进 backend 全局 g_Window(0x18d2b0)
    7b3c4  bl    ImGui_ImplOpenGL3_Init@plt
    7b3c8  bl    ImGui::GetStyle@plt         ; 样式定制
    7b3f8  strb  w24, [x23]                  ; g_Initialized = 1（最后一步）
    7b418  bl    __android_log_print@plt    ; "ImGui ready"

- **只靠 CreateContext 内部那次 SetCurrentContext**，桥自己没有再调 `ImGui::SetCurrentContext`。
- CreateContext 之后紧接着的 `GetIO` 就是 `GImGui->IO`（`GetIO` 无空指针检查：0x9041c 只有 5 条指令，直接 `GImGui+0x30`）；实测 `ImGui ready` 已打印且没有在 init 内崩，**证明 CreateContext 已把本库的 GImGui 写成非 0**。
- EGL/ImGui 后端 init 的返回值全部忽略；即使初始化失败，`g_Initialized` 仍会被置 1（`nativeIsReady` 只读这个 byte，见 0x7b520）。

### 3.2 nativeNewFrame（0x7b52c–0x7b558）：无任何早退/守卫

    7b534  adrp  x8, 0x186000
    7b538  add   x8, x8, #0x8
    7b53c  ldp   x0, x1, [x8]          ; g_EGLDisplay / g_EGLSurface
    7b540  ldr   x3, [x8, #0x10]
    7b544  mov   x2, x1
    7b548  bl    eglMakeCurrent@plt      ; 不检查返回值（实测返回 EGL_BAD_ACCESS 3002）
    7b54c  bl    ImGui_ImplOpenGL3_NewFrame@plt
    7b550  bl    ImGui_ImplAndroid_NewFrame@plt
    7b554  ldp   x29, x30, [sp], #0x10
    7b558  b     ImGui::NewFrame@plt     ; 尾调用，直接返回；无 g_Initialized / 上下文判空

- 这三步（OpenGL3_NewFrame / Android_NewFrame / NewFrame）第一件事都会解引用 GImGui：
  - `ImGui_ImplOpenGL3_NewFrame`：`bl GetCurrentContext@plt; bl GetIO@plt; ldr x8,[x0,#0xa8]`（0x153ef0–8）
  - `ImGui_ImplAndroid_NewFrame`：`bl GetIO@plt; ...` 读 backend g_Window 取宽高（0x153a7c 起）
  - `ImGui::NewFrame`：`ldr x24,[GOT]; ldr x23,[x24]; ldr w8,[x23,#0x2970]`（0x922b8–c4）
- 因此**只要 nativeNewFrame 跑完而不崩，GImGui 在那时必非 NULL** —— 这是后面“为什么只可能是中途被清空”的硬约束。

### 3.3 nativeButton（0x7b67c）与崩溃指令

    7b69c  mov  x21, x2
    7b6bc  ldr  x8, [x0]; ldr x8,[x8,#0x548]; blr x8   ; JNIEnv->GetStringUTFChars(label)
    7b6cc  mov  x22, x0
    7b6d0  stp  s9, s8, [sp]                            ; size = (w,h)
    7b6d4  bl   ImGui::Button@plt                       ; 返回地址 0x7b6d8（tombstone #01 = 0x7b6d4 即此调用点）
    7b6ec  ldr  x8,[x8,#0x550]; blr x8                  ; ReleaseStringUTFChars
    7b704  and  w0, w19, #1; ret                        ; 返回 bool

ImGui::Button 尾调用 ButtonEx（自身也在 PLT 0x177ee0），ButtonEx 开头：

    12bc30  adrp x9, 0x17e000
    12bc38  ldr  x8, [x25,#0x28]        ; stack canary(TLS)
    12bc3c  ldr  x9, [x9,#0x110]        ; x9 = &GImGui
    12bc40  stur x8, [x29,#-0x18]
    12bc44  mov  w8, #1
    12bc48  ldr  x26, [x9]              ; x26 = GImGui
    12bc4c  ldr  x24, [x26,#0x15e0]     ; <-- 崩溃指令：x24 = g.CurrentWindow
    12bc50  ldrb w9, [x24,#0x10e]       ; window->SkipItems
    12bc54  strb w8, [x24,#0x10b]

`GImGui = 0` 时 `ldr` 正好在 `0 + 0x15e0` 处触发 `SEGV_MAPERR`，与所有 tombstone 的 `fault addr = 0x15e0`、`pc = 0x12bc4c`、`#01 = 0x7b6d4` 同时吻合。

### 3.4 【决定性】全库只有 5 条指令能写 GImGui，唯一能写 0 的是 DestroyContext

对整份反汇编做数据流扫描：识别 `adrp xM,0x17e000` + `ldr xN,[xM,#0x110]`（得到 `&GImGui`），跟踪寄存器直到出现“以该寄存器为基址、偏移恰为 0 的存储”。结果（完整枚举）：

    [ImGui::SetCurrentContext]   84730: str  x0,  [x8]      ; GImGui = ctx
    [ImGui::CreateContext]       84858: str  x20, [x21]     ; GImGui = new_ctx
    [ImGui::CreateContext]       84864: str  x22, [x21]     ; if (prev_ctx) GImGui = prev_ctx
    [ImGui::DestroyContext]      84e7c: str  x19, [x20]     ; GImGui = ctx（待销毁对象）
    [ImGui::DestroyContext]      84e8c: str  x8,  [x20]     ; GImGui = (prev==ctx ? NULL : prev)  <-- 唯一能写 0

逐条排除：

1. **CreateContext 的 prev_ctx 逻辑**不会留下 NULL：0x847a8 先把 `prev = GetCurrentContext()` 读进 x22，写完新 ctx 后 `cbz x22 -> skip`，**prev 为 NULL 时跳过恢复**（首次初始化就是这种情况，GImGui 保持新 ctx）；prev 非空时恢复的也是非空指针。→ “CreateContext 恢复 prev 把 GImGui 变 NULL”不成立。
2. **SetCurrentContext 只有 2 个调用点**，都在 `ImFontAtlas::ClearFonts()` 调用的 `ImFontAtlasBuildNotifySetFont`（0x107490）里，是成对的 save/restore：`prev = GetCurrentContext(); if (fontCtx != prev) { SetCurrentContext(fontCtx); SetCurrentFont(...); SetCurrentContext(prev); }`。prev 非空且必然恢复 → 不会留 NULL。
3. **DestroyContext 的唯一调用点**是 `nativeShutdown`：`7b4a0 bl ImGui_ImplOpenGL3_Shutdown@plt; 7b4a4 bl ImGui_ImplAndroid_Shutdown@plt; 7b4a8 mov x0,xzr; 7b4ac bl DestroyContext@plt`。
4. **nativeShutdown 的唯一调用来源**是脚本 `src/js/imgui.js` 的 `events.on("exit")` 处理器（189 行；另 191/251/328 行的 `ImGui.stop()` 同源），即 `_invoke("nativeShutdown", [])`。

→ **逻辑闭环：Button 处 GImGui==NULL <=> 在 NewFrame 之后、Button 之前 nativeShutdown 执行过**。而 `nativeShutdown` 是先 `DestroyContext` 再 `g_Initialized=0`（0x7b4b0），不存在“半个 shutdown”的空档。

---

## 4. 双 .so 副本 / 双 DexClassLoader 分析（任务第 3 点）

### 4.1 加载机制（实测，不是 README 所说的 android.os.Build 注入）

- `com/imgui/bridge/ImGuiBridge.class` 的常量池里与加载有关的只有 `java/lang/System` + `loadLibrary` + `(Ljava/lang/String;)V`：**`<clinit>` 只做 `System.loadLibrary("ImGuiBridge")`**，没有 `System.load(绝对路径)`。
- `JNI_OnLoad`（0x7c110）只有 3 条指令：`mov w0,#6; movk w0,#1,lsl#16; ret`，即返回 `JNI_VERSION_1_6`，**没有任何 RegisterNatives**。
- native 方法全部是 name-based JNI 导出：`Java_com_imgui_bridge_ImGuiBridge_native{Init,Shutdown,IsReady,NewFrame,Render,TouchEvent,Button,...}`（dynsym 里 30+ 个）。ART 的查找域 = **该 class 所属 ClassLoader 的 nativeLibraries 列表**，不存在“注册到另一套 C++ 全局”的机制；单次运行内所有 native 调用必然命中同一个库文件。

### 4.2 每次 _load() 都会产生新的副本路径

- `src/js/imgui.js` 的 `_copySo()`：每次都复制到 `context.getFilesDir()/so_<8位随机hex>/`，随后 `_cleanOldDirs()` 删旧目录；`_load()` 每次都 `new DexClassLoader(dexPath, optDir, soDir, ...)`。
- 日志侧证：每次运行 `Configuring clns-N ... library_path=/data/user/0/.../files/so_XXX` 的目录名都不同（10-01: `so_6ec541eb`；10-17: `so_d0a0f5e9`；旧样本还有 `so_58aaf0d2`/`so_359cacea`/`so_bd399e1b`）。
- **旧 .so 是否被 unmap 取决于其 ClassLoader 是否被回收**；若同一个 :native 进程里先后跑多次，理论上会并存两份 .so，各自独立 `.bss`（即两份 GImGui）。

### 4.3 本次崩溃样本：单副本

- dev947/10-01/20:40 三次崩溃的日志里，**每次运行都只有一条 `Load ... libImGuiBridge.so using isolated ns clns-N`**；且 `Configuring clns-N` 每次都是新目录 → 单次运行只有一份副本。
- 单副本 + name-based JNI 绑定下，nativeInit 与 nativeButton 一定落到同一份 C++ 全局。**因此“init/Button 落到不同 GImGui”不是本次根因**；
- 但这是宿主应当收口的隐患（进程复用 + 随机目录 + 未确认卸载）：一旦出现两份副本，任何跨轮次调用都会看到另一份的 NULL，症状与现在完全一样。参见 §6 修复 #3。

---

## 5. 运行时证据链：为什么是“销毁与渲染并发”

### 5.1 线程分裂（dev947，2026-10-17 21:28，:native 进程 pid 21860）

| 时间 | tid / 线程 | 日志/事件 |
| --- | --- | --- |
| 06.437 | 21860 **main** | `nativeloader: Configuring clns-10 ... library_path=.../files/so_d0a0f5e9` |
| 06.439 | 21860 **main** | `nativeloader: Load .../so_d0a0f5e9/libImGuiBridge.so using isolated ns clns-10 (caller=.../native-staging/.../lib/ImGuiBridge.dex): ok` |
| 06.453 | 21860 **main** | `I ImGuiBridge: EGL 880x1155` / `I ImGuiBridge: ImGui ready`（= nativeInit 完成，CreateContext 已写 GImGui） |
| 06.453–.454 | 21860 **main** | `ContextFactory: onContextCreated/Released`（surfaceCreated 的 JS 回调收尾，说明 nativeInit 确实在主线程被调用） |
| **06.456** | **21885 Script-0** | `E libEGL: eglMakeCurrentImpl:1106 error 3002 (EGL_BAD_ACCESS)`（= nativeNewFrame 的 eglMakeCurrent） |
| **06.465** | **21885 Script-0** | SIGSEGV，`fault addr 0x15e0`，`#00 pc 0x12bc4c`(ButtonEx) / `#01 pc 0x7b6d4`(nativeButton)，`x9=base+0x186028`、`x26=0` |
| 之后 | — | `Zygote: Process 21860 exited due to signal 11`；`PocNativeRunner: 隔离脚本进程 binder 已死亡`（主进程存活） |

- **nativeInit 在 main 线程，渲染与崩溃在 Script-0**；从 “ImGui ready” 到崩溃只有 **12ms**，即首帧窗口（setTimeout 16ms 链），说明崩在首帧。
- **EGL_BAD_ACCESS(3002) 的原因已可精确解释**：nativeInit 在 main 线程 `eglMakeCurrent(display, surface, surface, ctx)` 后**从未释放** current（nativeShutdown 里才有一次 makeCurrent(null)）；Script-0 再用同一个 EGLContext 做 current 时，EGL 规范要求返回 3002（context 已被另一线程占用）。而 `nativeNewFrame` **不检查返回值**，继续执行后面的 ImGui 调用 —— 这就是“即使不崩也画不出来”的直接原因。
- 同一进程里 `I ImGuiBridge: EGL/ImGui ready` 打印在 main，而 `libEGL` 报错与 SIGSEGV 在 Script-0：**桥的单线程假设（init + input + render + destroy 全在一个 Looper 线程）在宿主里被打破**。

### 5.2 退出清理由谁触发、为什么能插进帧中间

宿主引擎侧代码链路（均为仓库现有代码）：

| 文件:行 | 行为 |
| --- | --- |
| `modules/engine/.../execution/RunnableScriptExecution.java:70-72` | `finally { Log.d(TAG,"Engine destroy"); engine.destroy(); }`（在**脚本执行线程**） |
| `modules/engine/.../engine/JavaScriptEngine.java:57-61` | `destroy() { mRuntime.onExit(); ... }` |
| `modules/engine/.../runtime/ScriptRuntime.java:436-450` | `onExit() { ...; events.emit("exit"); ... }` |
| `src/js/imgui.js:189`（项目里） | `events.on("exit", () => { clearTimeout(renderTimer); _invoke("nativeShutdown"); win.close(); })` |
| `libImGuiBridge.so:0x7b484` | `nativeShutdown -> DestroyContext(nullptr) -> GImGui=NULL` |

- dev949 日志实测了这条退出路径：`运行结束 -> D RunnableJSExecution: Engine destroy -> D ScriptRuntime: on exit -> D RhinoJavaScriptEngine: on destroy`（同步发生）。
- **它与帧循环之间没有任何互斥/join**：帧循环在 Script-0（或 main，随模式而变），退出清理由引擎执行线程/宿主线程触发；两者可以落在同一时刻，于是 `DestroyContext` 完全可能插在 `ImGui::NewFrame` 返回之后、`nativeButton` 之前——这正是 tombstone 所示“NewFrame 成功、Button 见 NULL”的唯一形态。
- 另一个同样符合寄存器/栈的自触发形态：`main.js:44` 第一个按钮是「X 退出」，若它返回 true 就会**同步**执行 `events.emit("exit")`（同线程）→ 同一线程立刻 nativeShutdown 清 0 → 再回到 cb 继续 `main.js:47` 的第二个 Button("Click Me!") → 在第二个 Button 上崩。12ms 内无人为点击，故更可能是引擎退出路径；但**两种形态的修法完全相同**（都不允许在帧序列未结束时销毁上下文）。
- dev947 崩溃窗口的 logcat 里没有抓到 `ScriptRuntime: on exit` 行（可能是 logd 丢行，也可能走了自触发形态）；这不影响结论——§3.4 的 5 条写指令唯一确定了“必定发生过 DestroyContext”。

### 5.3 三处 tombstone 完全一致 → 与进程隔离无关

| 样本 | 进程 | 线程 | pc | #01 | fault |
| --- | --- | --- | --- | --- | --- |
| `.artifacts/qjs-crash/imgui2m.log`（10-01） | 主进程 pid 29873 | main | 0x12bc4c | 0x7b6d4 | 0x15e0 |
| `dropbox-native.log` 20:40:59（dev-938） | 主进程 pid 15897 | main | 0x12bc4c | 0x7b6d4 | 0x15e0 |
| `dev947-imgui-full.log` 21:28（:native） | pid 21860 | Script-0 | 0x12bc4c | 0x7b6d4 | 0x15e0 |

→ 说明这是**桥固有的生命周期缺陷**，与“主进程/隔离进程”无关；dev-940（隐藏宿主 ImGui 符号）、dev-945（建窗线程）都没有触及“销毁与渲染不串行”这一层，因此崩溃点不变。

---

## 6. 候选修复（宿主侧，≤3，按可能性排序；不改脚本、不改 .so）

### #1（最贴合根因，建议先做）把退出清理与渲染帧序列串行化

- **改哪里**：
  - `modules/engine/src/main/java/com/stardust/autojs/runtime/ScriptRuntime.java`（`onExit()`）
  - `modules/engine/src/main/java/com/stardust/autojs/engine/JavaScriptEngine.java`（`destroy() -> onExit()`）
  - `modules/engine/src/main/java/com/stardust/autojs/execution/RunnableScriptExecution.java`（`finally` 中 destroy 之前）
- **怎么改**：不要在执行线程上立刻 `events.emit("exit")`。改为把 emit 投递到**脚本自己的 Looper 队列**并在 destroy 前等待队列空闲（项目已有基础设施：`Loopers.getMainLooper()/getServantLooper()`、`Loopers.waitWhenIdle()/doNotWaitWhenIdle()`、`Timer` 的 Looper 绑定；`Loopers.shouldQuitLooper()` 已做 timer 检查，可扩展一个“帧序列未完成不许 emit exit”的约束）。目标：`nativeShutdown/DestroyContext` 与 `nativeNewFrame/nativeButton/nativeRender` **永不重叠**。
- **验证**：`. .\tools\jdk17.ps1 | Out-Null; .\build-miuix-debug.ps1 -NoPublish` → 装机 → 运行 `AutoJS_ImGui` 项目 → `adb logcat` 过滤 `ImGuiBridge|ButtonEx|SIGSEGV|NativeScriptService`，要求：连续 10 次运行不再出现 `fault addr 0x15e0` / `pc 0x12bc4c`；临时加桩确认 “exit 一定晚于该帧 nativeRender”。
- **风险**：改变所有脚本 exit 回调的相对时间（通常推迟 < 一帧 16ms）；需回归 quickjs 冒烟 + 一个普通 floaty 示例。

### #2 让 SurfaceHolder 回调回到脚本线程（init/render/shutdown 同线程；顺带修 EGL_BAD_ACCESS、让画面真正出来）

- **改哪里**：`modules/engine/src/main/java/com/stardust/autojs/core/floaty/`（RawWindow/Floaty 的 `SurfaceHolder.Callback` 代理；即 dev-945 改过的建窗路径的“回调分发”部分）。dev-945 只改了建窗线程，但 `surfaceCreated` 仍由 ViewRootImpl 在 **main** 触发，所以 init 仍在 main。
- **怎么改**：在 `holder.addCallback(...)` 的代理里把 `surfaceCreated/surfaceChanged/surfaceDestroyed` 的 JS 回调 **post 到脚本线程的 Handler**（脚本线程有 Looper）。这样 `nativeInit` 里的 `eglMakeCurrent` 与 `nativeNewFrame/nativeRender` 在同一线程，EGL context 不再跨线程 current；配合 #1 后 init/render/exit 全同线程。
- **验证**：logcat 不再出现 `eglMakeCurrentImpl:1106 error 3002 (EGL_BAD_ACCESS)`；ImGui 窗口内容出现（截图比对 `built-apk2.png`）；连跑 10 次无 SIGSEGV。
- **风险**：Surface 回调跨线程需要处理窗口销毁顺序与 Handler 生命周期；建议只在 `:native`/headless + floaty 路径开启，限制影响面。

### #3（兜底）一次性 :native 进程 + so 单实例 + 停止时进程级清理

- **改哪里**：`apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/nativeproc/NativeScriptService.java`、`PocNativeRunner.java`、`NativeProjectStaging.java`（脚本侧 `files/so_<rand>` 由脚本复制，宿主无法直接改，但可约束进程生命周期/单任务）
- **怎么改**：:native 里同一时间只跑一个 native 项目；任务结束/被 STOP 时**直接结束 :native 进程**（或在 engine destroy 之前先屏蔽脚本 exit 回调），避免脚本在退出路径里执行 `nativeShutdown`；同时保证“同名 .so 在进程内只加载一次”（进程级单实例/固定的 staging 路径）。
- **验证**：连续运行/停止 10 次，`adb shell run-as ... ls files` 无 `so_*` 堆积、无 SIGSEGV；主进程 `PocNativeRunner` 只见“隔离进程已退出/重启”，不见 binder death 崩溃。
- **风险**：进程重启约 0.5–1s；对“停止后立刻再跑”的交互有延迟；不解决渲染/EGL 问题（仍需 #1+#2 才能出画面）。

---

## 7. 残余不确定点（如实标注）

1. dev947 崩溃窗口内 logcat 未抓到 `ScriptRuntime: on exit`（logd 丢行，或走了脚本 `main.js:44` 的自触发 exit 分支）。“nativeShutdown 由哪个线程、在哪一刻调用”无法从日志唯一确定；但**“必定发生过 DestroyContext”由 §3.4 的 5 条写指令唯一确定**，这不影响修复方向（两条分支都靠 #1 消除）。
2. `eglMakeCurrent` 的 EGL_BAD_ACCESS 与 SIGSEGV 是两个后果：前者是“画面出不来”的直接原因，后者是崩溃；修 #2 只保证 GL 可用，**仍必须配合 #1** 才能不崩。
3. §4 的“双副本”在当前样本中被排除，但宿主进程复用时仍是隐性风险，建议一并按 #3 收口。

---

## 附录 A：关键地址表（libImGuiBridge.so 内偏移）

| 项 | 地址 |
| --- | --- |
| GImGui 的 GOT 槽（GLOB_DAT） | 0x17e110 -> `&GImGui` |
| GImGui 变量（.bss，GLOBAL DEFAULT） | 0x186028 |
| g_Initialized（byte） | 0x186000 |
| g_EGLDisplay / g_EGLSurface / g_EGLContext | 0x186008 / 0x186010 / 0x186018 |
| 桥自己的 ANativeWindow | 0x186020 |
| backend g_Window（imgui_impl_android） | 0x18d2b0 |
| `Java_..._nativeInit` | 0x7b248 |
| `Java_..._nativeShutdown` | 0x7b484 |
| `Java_..._nativeIsReady` | 0x7b520 |
| `Java_..._nativeNewFrame` | 0x7b52c |
| `Java_..._nativeRender` | 0x7b55c |
| `Java_..._nativeButton` | 0x7b67c |
| `JNI_OnLoad` | 0x7c110（仅返回 JNI_VERSION_1_6） |
| `ImGui::CreateContext` | 0x84778（GImGui 写入 0x84858/0x84864） |
| `ImGui::DestroyContext` | 0x84e58（写 0 在 0x84e8c） |
| `ImGui::SetCurrentContext` / `GetCurrentContext` | 0x84728 / 0x8154c |
| `ImGui::GetIO` | 0x9041c（`GImGui+0x30`，无判空） |
| `ImGui::NewFrame` | 0x92280 |
| `ImGui::ButtonEx` / 崩溃指令 | 0x12bc0c / 0x12bc4c |
| 崩溃调用点（nativeButton 内 bl ImGui::Button） | 0x7b6d4 |

## 附录 B：复现命令（只读）

    $NDK = "D:\VisualStudio\Shared\Android\AndroidNDK\android-ndk-r27c\toolchains\llvm\prebuilt\windows-x86_64\bin"
    $SO  = "output\imgui-probe\auto_jspro_imgui\lib\arm64-v8a\libImGuiBridge.so"
    & "$NDK\llvm-readelf.exe"  -r -W $SO | Select-String "17e110"          # GLOB_DAT -> GImGui
    & "$NDK\llvm-readelf.exe"  --dyn-syms -W $SO | Select-String "GImGui"  # GLOBAL DEFAULT OBJECT
    & "$NDK\llvm-objdump.exe"  -d --no-show-raw-insn --start-address=0x12bc0c --stop-address=0x12bc60 $SO   # ButtonEx 崩溃点
    & "$NDK\llvm-objdump.exe"  -d --no-show-raw-insn --start-address=0x7b248  --stop-address=0x7b528 $SO    # nativeInit
    & "$NDK\llvm-objdump.exe"  -d --no-show-raw-insn --start-address=0x84728  --stop-address=0x84778 $SO    # SetCurrentContext
