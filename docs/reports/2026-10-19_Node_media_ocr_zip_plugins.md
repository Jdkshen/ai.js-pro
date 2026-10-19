# Node media / ocr / zip / plugins 最小可用实现（task-158）

日期：2026-10-19。执行：t1-native-services。验证机：K40（cccc62c7）。

## 1. 结论

- zip / ocr：审计确认**已有真实实现**（task-146），已补 K40 真机冒烟（PASS）；
- media：新增宿主 kind="media"（MediaPlayer / AudioManager / 本地音频扫描）+ JS 模块，K40 PASS；
- plugins：新增宿主 kind="plugins"（列出/查询/启动已装插件，缺失中文提示）+ JS 模块，K40 PASS；
- 4 个模块各一个单工程 + JSON 冒烟（结果见第 6 节）。

## 2. zip（已有实现，审计）

- 文件：modules/engine/src/main/assets/node24/modules/zip.js（13KB）；
- 底层：com.stardust.autojs.runtime.api.Zips（zip4j 1.3.2）与 ZipFile 会话，不是 JS 编码；
- API：zipDir/zipFile/zipFiles/unzip/open + 会话 addFile/removeFile/extractAll/extractFile/getFileHeaders/isValidZipFile 等；
- 错误带 AutoJsZipError + code；本轮冒烟：目录递归压缩 → 解压 → 中文内容/子目录校验。

## 3. ocr（已有实现，审计）

- 文件：modules/engine/src/main/assets/node24/modules/ocr.js（16.9KB）；
- 底层：宿主 kind="ocr"，PPOcr（PP-OCRv6 tiny，assets/models/ppocrv6）或 ML Kit；
- API：createOCR({backend:'ppocr'|'mlkit'}) -> session.detect(image|path[,options]) -> [{text,confidence,bounds}]；release/liveCount；模型档位别名映射到内置模型，未知档位中文报错；
- 本轮冒烟：对 /sdcard/node158/ocr-input.png（打包进度截图）识别文本块。

## 4. media（新增）

### 4.1 宿主协议

    {kind:'media', action:'play', path, volume?, looping?}
    {kind:'media', action:'pause'|'resume'|'stop'|'isPlaying'|'duration'|'position'|'seek'}
    {kind:'media', action:'getVolume'|'setVolume', stream?:'music'|'alarm'|'ring'|'notification'|'system'|'voice_call', volume?}
    {kind:'media', action:'scanAudio', dir?, limit?}

实现：Node24HostBridge.handleMedia（:node24 进程内 MediaPlayer + AudioManager，任务结束释放播放器）。

两个真机踩坑（均已修）：

1) **MediaPlayer 必须在带 Looper 的线程创建/释放**：第一版在宿主 bridge 线程上 new/prepare/release，脚本 steps 全部成功但随后 :node24 SIGABRT（native 回调线程池崩溃）。现在全部 MediaPlayer 操作走专用 `HandlerThread("node24-media")`，`close()` 时 release + quitSafely。
2) **scanAudio 不能直接 File.listFiles() 扫 /sdcard**：K40/LineageOS 的 /sdcard 上存在非法 UTF-8 文件名（形如「象棋\uFFFD」），framework 的 `UnixFileSystem.list0` 在 NewStringUTF 时直接 `JNI DETECTED ERROR: input is not valid Modified UTF-8` 让 :node24 SIGABRT（系统 bug，sanitize 在 listFiles 之后来不及）。现在默认走 **MediaStore.Audio**（provider 返回已解码 DATA/DISPLAY_NAME），只有显式 `{dir}` 时才做受控目录递归。

### 4.2 JS API

    const media = require('media');
    media.playMusic(path[, volume]) / stopMusic() / pauseMusic() / resumeMusic() / isMusicPlaying();
    media.getMusicDuration() / getMusicPosition() / seekToMusic(ms);
    media.playSound(path[, volume, loop]) / stopSound();
    media.getVolume([stream]) / setVolume(volume[, stream]) / getMaxVolume([stream]);
    media.scanAudio([{dir, limit}]) -> [{path,name,size}];
    media.music = {play,pause,resume,stop,isPlaying,getVolume,setVolume,scan,...}

## 5. plugins（新增）

    {kind:'plugins', action:'list'|'isInstalled'|'info'|'load'|'launch', packageName?}

已知插件包：com.jdkshen.apkbuilder.plugin、com.jdkshen.xiangqi.plugin、com.jdkshen.xiangqi.quickjs。
info 返回 label/versionName/versionCode/launcher/exportedServices；未安装抛中文「插件未安装：xxx（可从「插件」页安装…）」；launch 对没有 launcher 的能力插件给中文提示。

## 6. K40 冒烟（PASS）

设备：Redmi K40（cccc62c7），最终验证版本 dev-1107（zip/ocr/plugins 在 dev-1104 亦已通过，改动只涉及 media 扫描方式）。每个模块一个单工程：/sdcard/脚本/node158-{zip,media,ocr,plugins}/（project.json + main.js），结果 JSON 在 /sdcard/node158/。

### 6.1 zip PASS

    steps: ["zipped size=503", "extracted aPath=.../out/src/a.txt bPath=.../out/src/sub/b.txt",
            "roundtrip a=hello zip 中文内容 b=nested 文件", "OK=true"]
    extra: {ok:true, a:"hello zip 中文内容", b:"nested 文件", zipSize:503}

注：zip4j 的 includeRootFolder 默认 true，解压产物在 out/src/...，冒烟按递归查找校验。

### 6.2 ocr PASS

    session=[OCR ppocr#1]，blocks=20（打包进度截图）
    样例: 21:45(0.997) / 打包应用(0.9996) / 配置(0.9999) / 包名(0.9998) / 运行配置(0.9999)
    extra: {ok:true, count:20, sample:[...]}

### 6.3 plugins PASS

    installed count=2
    com.jdkshen.xiangqi.plugin   label=象棋引擎插件  version=1.0.0
    com.jdkshen.xiangqi.quickjs  label=象棋自动走棋  version=0.6.45 (launcher=.../SplashActivity)
    apkbuilder installed=false
    missing hint=插件未安装：com.jdkshen.apkbuilder.plugin（可从「插件」页安装，或把插件 APK 发到设备后重试）
    extra: {ok:true, count:2, plugins:[...], apkBuilderInstalled:false, missingMessage:"..."}

注：错误传递已收敛，能力域自己的中文提示不再被包成「宿主桥内部错误」。

### 6.4 media PASS（dev-1107）

    wav size=8044（脚本生成 0.5s 440Hz 8kHz mono WAV）
    volume before={"stream":3,"volume":5,"max":15} -> after={"stream":3,"volume":6,"max":15}
    played={"playing":true,"path":"/sdcard/node158/media/tone.wav","duration":500,"looping":false}
    isPlaying=true / paused=true / resumed=true / stopped=true stillPlaying=false
    scanAudio mediaStore count=0（设备 Music 库未索引音频，属正常）
    scanAudio dir count=1 first=tone.wav
    extra.ok=true；无 Fatal signal / JNI DETECTED / SIGABRT，:node24 进程存活，result JSON 正常写出。

证据：.artifacts/node158-verify/（各 result JSON、media-logcat*.txt、4 个工程源码）。

## 7. 修改文件

- modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java：kind=media / kind=plugins 处理、MediaPlayer/AudioManager、扫描与插件查询；
- modules/engine/src/main/java/org/autojs/source/engine/android/Node24Prelude.java：MODULE_ASSETS 追加 media.js/plugins.js；
- modules/engine/src/main/assets/node24/modules/media.js（新增）；
- modules/engine/src/main/assets/node24/modules/plugins.js（新增）。

编译验证：`:engine:compileDebugJavaWithJavac` BUILD SUCCESSFUL（未跑完整 gradle）。

## 8. 已知限制

- media 默认扫描用 MediaStore：设备 Music 库没索引音频时返回 0 条；需要覆盖未索引目录时用 `scanAudio({dir})`（显式目录走文件系统遍历，调用方注意该目录内非法文件名在部分 ROM 上会让进程崩溃，见第 4 节）；
- media 播放使用应用内 MediaPlayer（非系统播放器通知），不提供播放进度通知/耳机按键控制；
- plugins 目前覆盖已知包名（apkbuilder / xiangqi*）；`launch` 只启动插件 launcher，纯能力插件（无 launcher）会中文提示；
- ocr 仍是同步宿主桥调用（Promise 只是 async 包装，不是后台线程），大图识别会占 Node 线程；
- zip 的 includeRootFolder 默认 true 是 zip4j 行为，需要平铺时传 options.
