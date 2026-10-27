# AI.js Pro API 细节：7 个高频入口

本页把 7 个高频入口按用途、参数、返回值、权限、示例和清理方式展开，供人阅读，也供 MCP 查阅后写脚本。内容依据源码核对；文档标注不代表所有手机和全部调用组合已通过验证。示例的文件、资源包和系统权限前提写在正文及注释中；MCP 的文档检索只读取文本，不会执行示例。

## files.read / files.write（Rhino / QuickJS / Node24）

### 用途

`files.read` 一次读入整个文本文件并返回字符串，`files.write` 覆盖写入（或创建）一个文本文件，二者都是同步入口。它们只处理文件路径，不处理 `content://` URI、目录遍历或网络流；目录操作、二进制和追加写入分别用 `ensureDir`、`readBytes/writeBytes`、`append`。

### 引擎与调用

三引擎共享 `modules/common-api.js` 安装的全局 `files`：Rhino 的 `init.js`、QuickJS 的 `QuickJsHostBridge`、Node 的 `Node24Prelude` 都会加载 `common-api.js`，把 `read/write` 等名称转发到宿主的 `com.stardust.autojs.runtime.api.Files`。Node 还可以用 `require('files')`，同样的方法名可用；Rhino/QuickJS 示例直接使用全局 `files`，不要据此推断 `require('files')` 在所有引擎都能解析。

共同的是方法契约，不是路径基准。Rhino 的 `Files.cwd()` 取 `runtime.engines.myEngine().cwd()`；QuickJS 的共享层用 `g.files.cwd()`；Node 的 `Files` 工作目录是 `__ajsProjectDir`（从脚本位置向上找到 `project.json` 的工程根；找不到时是脚本父目录）。所以同一句 `files.read('a.txt')` 在 Node 与 `image.readImage('./a.png')` 的相对基准不同，后者按脚本文件目录解析。

### 参数

| 调用 | 参数 | 当前行为 |
| --- | --- | --- |
| `files.read(path[, encoding])` | `path` 字符串；`encoding` 可选字符串，默认 UTF-8 | 按 `Files.path()` 归一化后读取；空文件返回 `""`；文件不存在、无权限或编码不支持时抛错 |
| `files.write(path, text[, encoding])` | `path`、`text` 必填字符串；`encoding` 可选，默认 UTF-8 | 用 `FileOutputStream` 覆盖写入/创建文件；不自动创建父目录；不追加；返回 `undefined` |

`path` 可以是绝对路径或当前引擎工作目录下的相对路径。源码没有把文本内容做换行、BOM 或编码猜测；要写二进制、追加内容或按行读写，应使用 `readBytes/writeBytes/append/open`，而不是给这两个方法传非字符串。

### 返回值

`files.read` 返回 `String`；`files.write` 的 Java 方法是 `void`，在 JS 中不要依赖 `true/false` 返回值。两者都是同步调用，异常会直接抛出而不是返回 `null`；`read` 按 `available()` 预分配后一次读取，不是流式接口，超大文件会占内存，需要稳定流式读取时用 `files.open`。

### 权限与前置条件

这两个方法只负责按路径访问文件，不会授予权限。应用私有目录通常无需额外授权；`/sdcard`、共享目录、SAF/MediaStore 路径仍受 Android 存储权限、分区存储和用户授权约束，Node 子进程与应用主进程使用同一应用 UID，不会因此多出权限。写入共享路径前应先用 `files.exists` 或 `ensureDir` 检查，并给失败留出提示；源码没有原子写或事务语义，重要数据应先写临时文件再重命名。

### 示例

```js
"nodejs"; // 换引擎时仅首行指令不同，读写方法仍用全局 files

(function () {
  var file = files.path('api-details-demo-' + Date.now() + '/note.txt');
  var dir = files.dirname(file);
  try {
    files.ensureDir(file);            // write 不创建父目录，这里显式创建
    files.write(file, '第一行\n第二行\n');
    var text = files.read(file);
    console.log(JSON.stringify(text));
  } finally {
    files.remove(file);
    files.removeDir(dir);
  }
})();
```

示例用 `files.path` 得到当前引擎工作目录下的绝对路径，再用 `files.dirname` 清理演示目录；如果换成 Rhino/QuickJS，保留全局 `files` 的写法即可。示例没有检查 `remove/removeDir` 的布尔返回值，生产脚本应检查清理是否成功。

### 常见错误与清理

最常见的是父目录不存在：`write` 只创建文件本身，父目录缺失会抛 `FileNotFoundException` 包装的异常，先 `files.ensureDir(path)`。其次是拿 `read` 读二进制或编码不匹配的文本，得到乱码或解码异常；二进制改用 `readBytes`，非 UTF-8 文本显式传编码。文件不存在时 `read` 会抛错而不是返回空串，需要先用 `exists` 判断；写入后也不要假设其他进程立刻看到内容。

Node 的相对路径落在工程根而不是脚本目录，这是与 `image.readImage` 最容易混淆的地方；不确定时使用 `files.path()` 或绝对路径。没有 `write` 的原子保证，中断可能留下半个文件；清理时只删自己创建的文件，目录用 `removeDir`，不要对用户目录做递归删除。

### 源码依据

- `modules/engine/src/main/assets/modules/common-api.js`：`nativeFiles()`、`fileMethods`、全局 `files` 的转发；这是三引擎共同层的入口。
- `modules/engine/src/main/java/com/stardust/autojs/runtime/api/Files.java`：`path()`、`cwd()`、`read(String, String)`、`write(String, String, String)`。
- `modules/common/src/main/java/com/stardust/pio/PFiles.java`：`read(...)`、`write(...)`、`ensureDir(...)`；默认编码和是否创建目录以这里为准。
- 加载点：`modules/engine/src/main/assets/init.js`（Rhino）、`QuickJsHostBridge.java`（`common-api.js` 资产列表）、`Node24Prelude.java`（`MODULE_ASSETS`）。
- 以上只证明入口和实现形状；示例与真实设备上的存储权限/分区存储行为仍需单独验证。

## storages.create / get / put（Rhino / QuickJS / Node24）

### 用途

`storages.create(name)` 取得一个按名称隔离的持久化键值存储，`put` 把一个 JSON 可序列化值写入键，`get` 读出解析后的 JS 值。它适合保存配置、少量记录和跨脚本共享的小对象；不适合大文件、二进制、密钥管理或需要事务的多键更新。

### 引擎与调用

三引擎的全局 `storages` 都由 `modules/common-api.js` 的 `createStorage(name)` 提供了同一套 JSON 契约：Rhino、QuickJS、Node 都通过 `CommonAPI` 安装到全局，Node 还把 `.create` 补到 `require('storages')` 上。底层是 `com.stardust.autojs.core.storage.LocalStorage`；命名空间由存储名决定，所有引擎共享同一个应用内命名空间。

Node 有一个必须区分的旧接口：`$autojs.storages`（以及 `require('storages')` 的旧平铺方法）是 `get(key, name)` / `put(key, value, name)` 的模块级字符串接口，不是本条的 `store.get(key, fallback)` / `store.put(key, value)`。写新代码时统一用 `storages.create(name)` 返回的 store 对象，不要把两套参数混用。

当前实现会优先通过 `${applicationId}.node24.storage` ContentProvider 读写，Node 标签页与主进程因此看到同一份数据；若宿主没有注册该 provider，`LocalStorage` 会退回本进程的 `SharedPreferences`，此时不要假设跨进程一致。数据最终存放在应用私有的 `SharedPreferences` 中，键值明文，不应存密码或密钥。

### 参数

| 调用 | 参数 | 当前行为 |
| --- | --- | --- |
| `storages.create(name)` | `name` 非空字符串 | 返回 store；空串/非字符串抛 `TypeError`；同名 store 指向同一命名空间 |
| `store.put(key, value)` | `key` 会转成字符串；`value` 必须是 JSON 可序列化值，不能是 `undefined` | 用 `JSON.stringify` 写入；返回 `undefined`；循环引用、过深嵌套或顶层不可序列化时抛错 |
| `store.get(key, fallback?)` | `key` 字符串；`fallback` 可选 | 返回 `JSON.parse` 后的值；仅“键不存在”时返回 `fallback`，不是按值的真假判断 |

`null`、`false`、`0`、`""` 都会被原样保留；如果存进去的值本身是 JSON `null`，`get` 返回 `null`，不会改用 fallback，需要区分时用 `contains`。`NaN`/`Infinity` 经 `JSON.stringify` 会变成 `null`；对象里的函数属性会被忽略，顶层函数、`BigInt`、循环引用会失败，嵌套超过源码限制会抛 `RangeError`。

### 返回值

`create` 返回带 `put/get/contains/remove/clear` 的 store 对象；`put` 没有业务返回值；`get` 返回任意 JSON 值或 fallback。没有异步回调，也不返回 Promise。`put` 返回时表示已经发起持久化调用；底层 provider 使用 `commit`，但多键批量写入没有事务，多个脚本并发写同一键时以最后一次写入为准。

### 权限与前置条件

命名存储位于应用私有数据目录，正常不需要申请系统存储权限；它也不是跨应用共享存储。`clear()` 和 `storages.remove(name)` 会清空整个命名空间且不可恢复，生产脚本应使用明确命名、必要时先备份。Node 的 pool 进程与主进程通过 provider 共享数据；如果运行在未注册 provider 的打包运行环境，需按“本进程 SharedPreferences”理解，不能承诺跨进程同步。

### 示例

```js
"nodejs";

(function () {
  var name = 'api-details-demo-' + Date.now(); // 避免清掉已有的演示数据
  var store = storages.create(name);
  try {
    store.put('user', {name: '甲', tags: ['a', 'b'], active: true});
    var user = store.get('user', null);
    console.log(user.name, user.tags.length, user.active);
    console.log(store.get('missing', 'fallback'));
  } finally {
    store.clear(); // 清理本次创建的唯一命名空间
  }
})();
```

示例只使用 JSON 可序列化值，并在 `finally` 中清理；`clear` 是删除整个 namespace，不要对用户已有的存储名调用。Rhino/QuickJS 使用同样的全局 `storages`，但示例的首行引擎指令要按实际引擎替换。

### 常见错误与清理

把 `undefined` 或函数直接放进 `put` 会抛 `TypeError`；对象内部的函数会被 JSON 省略，读取时不会恢复。循环引用、深层嵌套（源码在 256 层保护处报错）、`BigInt` 也不是可存储值；需要保留类型信息时自己加类型字段并显式序列化。`get` 的 fallback 只处理“没有这个键”，不处理“存的是 `null`”或“解析失败”；解析失败会抛错，而不是静默返回 fallback。

Node 中最常见的错误是用了旧平铺接口：`$autojs.storages.put(key, value, name)` 会把值转成字符串，不是把对象存进命名 store。另一个风险是 `clear`/`remove` 清空整个 namespace；如果只是删除一个键，用 `store.remove(key)`。并发写没有事务和锁，跨脚本共享数据时尽量让单次写入只包含一个键，或自行设计版本号/合并策略。

### 源码依据

- `modules/engine/src/main/assets/modules/common-api.js`：`createStorage(name)`、`storages.create/remove`、Node 的 `require('storages').create` 补丁。
- `modules/engine/src/main/java/com/stardust/autojs/core/storage/LocalStorage.java`：命名空间、JSON 字符串存取、provider 与 `SharedPreferences` 分支。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24StorageProvider.java`：`call()` 的 `get/put/contains/remove/clear`、`commit`。
- `modules/engine/src/main/assets/modules/__storages__.js`：Rhino 旧模块；`node24/prelude.js` 的 `storagesModule`：Node 旧平铺接口。它们不等于新 store 对象的参数形状。
- `modules/engine/src/main/AndroidManifest.xml`：`${applicationId}.node24.storage` provider 注册。

## Node select().firstOrNull()

### 用途

`select(spec).firstOrNull()` 在当前无障碍节点快照里做**一次**查询，命中就返回属性式节点，没命中就返回 `null`；它不等待、不重试、不轮询。需要“等到出现”时用 `findFirst({timeout, maxRetries})`，需要全部结果时用 `all()`。

### 引擎与调用

这是 Node24 专用入口，来自 `node24/modules/accessibility_extra.js`，通过 `require('accessibility')` 暴露 `select` 与 `accessibility`。`select()` 构造 `UiSelector`；`firstOrNull()` 先固定查询状态，再在 Promise 微任务里执行 `executeFirst`：没有 JS 本地条件时调用 `automation('getNodeInfo', {selector, index: 0})`，有 `RegExp`、`where`、`from`、`childCount` 等本地条件时拉一次 `getLatestNodeDump`，在 JS 侧按同一棵树过滤。查询失败会 reject，未匹配才 resolve `null`。

`firstOrNull()` 返回的是 `NodeView`（属性式），不是 `Node`/`UiObject`（方法式）：读字段用 `node.text`、`node.bounds`，动作仍用 `node.click()`。`findOnce()` 才返回方法式的 `Node`，字段要写成 `node.text()`。同一个页面上不要把两种节点的字段访问方式混用。

### 参数

| 调用 | 参数 | 说明 |
| --- | --- | --- |
| `select(initial?)` | 对象，或已有的 `UiSelector`；省略表示匹配全部 | 未知字段直接抛错，不会静默忽略；条件之间是 AND |
| `firstOrNull()` | 无 | 只查一次；不读取 `.timeout()`/`.maxRetries()` 的等待语义 |

`select` 的常用字段包括 `id/text/desc/className/packageName`（字符串或 `RegExp`）、`textContains/textStartsWith`、`textMatches`（Java 正则整串匹配）、`descContains`、`clickable/enabled/scrollable/editable`（布尔）、`depth`、`index`、`indexInParent`、`path`、`bounds`、`childCount`（数字或比较对象，如 `{">": 0}`）。`where(fn)` 接收属性式节点，`from(parent)` 只看后代；`path([])` 可用于根节点。源码会校验类型和范围，非法条件不会被忽略。

### 返回值

`firstOrNull()` 返回 `Promise<NodeView | null>`。`NodeView` 提供 `text/desc/id/className/packageName`（字符串）、`bounds/boundsInParent`（`Rect`，含 `left/top/right/bottom/width/height`）、`drawingOrder`、`clickable/enabled/scrollable/editable/checkable/checked/focusable/focused/selected/longClickable/visibleToUser/password`（布尔）、`depth/indexInParent/childCount/path`，以及 `parent()/children()/child(index)/tree()/raw()/find(selector)/findOne(selector)/findByText(text)`。

动作 `click()/clickCenter()/longClick()/setText(string)/copy()/cut()/paste()/select()/collapse()/expand()/dismiss()/show()/scrollForward()/scrollBackward()/scrollUp()/scrollDown()/scrollLeft()/scrollRight()/contextClick()/focus()/clearFocus()/accessibilityFocus()/clearAccessibilityFocus()` 同步返回布尔值；`setSelection(start,end)/scrollTo(row,column)/setProgress(value)` 带参数。通用动作经主进程 `nodeAction` 下发；动作按快照的 `path` 加身份字段重新确认，失败返回 `false`，不会自动改用屏幕坐标。

### 权限与前置条件

必须先在系统设置开启本应用的无障碍服务，并且服务处于运行状态；`accessibility.enableService()` 只负责打开设置并轮询等待，不能代替用户授权。`firstOrNull()` 不自己启动服务；服务未开启/未就绪时，宿主查询会抛错，Promise 随之 reject。查询和动作不要求 root，但受 Android 无障碍可见性、窗口和厂商限制影响。Node 的查询/动作走 `Node24AutomationBridge` 和主进程后端，不覆盖 inrt 独立打包运行时。

### 示例

```js
"nodejs";
const { accessibility, select } = require('accessibility');

(async () => {
  await accessibility.enableService({timeout: 30000});
  const node = await select({text: '设置', className: /TextView$/}).firstOrNull();
  if (!node) {
    console.log('当前界面没有匹配节点');
    return; // 无资源句柄需要释放
  }
  console.log(node.text, node.bounds.left, node.clickable);
  console.log('clicked=', node.click());
})().catch((error) => console.error(error));
```

示例只查一次；若目标页面还没加载完，应该改用 `findFirst({timeout: 3000, maxRetries: 10})`，而不是在外面反复 `sleep`。`firstOrNull` 没有需要显式关闭的句柄，界面变化后应重新查询。

### 常见错误与清理

最常见的错误是把 `NodeView` 当 `Node` 用：`await select(...).firstOrNull()` 后调用 `node.text()` 会失败，正确写法是 `node.text`；反过来，`findOnce()` 返回的节点才用 `text()`。`firstOrNull()` 没有等待语义，页面尚未加载时得到 `null` 是正常结果，不要把它当成“服务坏了”；需要等待就用 `findFirst`。

节点是快照，不是持续更新的 Android 对象；界面切换后继续点击旧节点可能返回 `false`，应重新查询。无障碍服务未开启、服务已开启但未运行、窗口不可见或后端结果限制都会让查询/动作失败；`enableService` 的授权动作也不能在后台静默完成。查询返回的节点不需要 `recycle`，但要避免长期缓存旧快照。

### 源码依据

- `modules/engine/src/main/assets/node24/modules/accessibility_extra.js`：`UiSelector.firstOrNull()`、`executeFirst()`、`NodeView`、`Node`、`identitySelector()`、`performNodeAction()`。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24AutomationBridge.java`：`findNodes/getNodeInfo/getLatestNodeDump` 与节点点击/输入动作。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Prelude.java`：`accessibility_extra.js` 的加载顺序。
- `docs/guides/Node24控件选择器API.md`：属性式/方法式节点、轮询与限制的现有说明。
- 源码只能证明适配层和宿主协议形状；实际点击、输入和不同厂商窗口仍需真机验证。

## Node accessibility.takeScreenshot

### 用途

`accessibility.takeScreenshot({maxWidth, maxHeight})` 用无障碍服务截取当前屏幕，返回一个可像素读取、必须手动回收的 `Image` 句柄；可选的宽高上限用于缩小输出，降低传输和内存开销。它走无障碍截图通道，不占用 `media_projection` 的录屏授权会话。

### 引擎与调用

这是 Node24 专用入口；`require('accessibility')` 的 `takeScreenshot` 由 `node24/prelude.js` 创建为 Promise 包装，宿主请求是 `kind:'automation', action:'takeScreenshot'`。宿主先要求 Android 11（API 30）及以上，再按 `maxWidth/maxHeight` 归一化，然后依次尝试像素管道、Raw 临时文件和 PNG 临时文件的三级回退；调用方只看到成功返回的 `Image` 或最终异常。

它是异步方法，没有 `takeScreenshotSync`。不能把 `media_projection.requestScreenCapture()` 的授权流程套到这里，也不要期望它返回文件路径或 Bitmap 原始对象；脚本拿到的只有 `Image` 句柄。

### 参数

| 参数 | 类型/范围 | 当前行为 |
| --- | --- | --- |
| `options` | 可选对象；非对象按空对象处理 | 当前只读取 `maxWidth`、`maxHeight`，其他字段忽略 |
| `options.maxWidth` | 数字/可转数字；正整数为上限 | `<=0`、`NaN`、未传按 `0`＝全分辨率；小数向下取整；超大值由宿主最终限制到 16384 |
| `options.maxHeight` | 同上 | 同上；宽高是“上限”，不是强制输出尺寸 |

`maxWidth`/`maxHeight` 是缩放约束，实际宽高由系统截图和缩放逻辑决定；不要用传入值反推像素坐标，应该用返回的 `image.width/height`。`0` 表示全分辨率，不是 0 像素。

### 返回值

返回 `Promise<Image>`。`Image` 有 `id/width/height`、`pixel(x, y)`（返回 `Color`，可读 `red/green/blue/alpha`）、`recycle()/close()/isRecycled()`。`recycle()` 后所有像素/尺寸访问都会抛错，重复 `recycle()` 返回 `true`。截图内部产生的临时文件由宿主清理；脚本侧需要清理的只有 `Image` 句柄。

### 权限与前置条件

需要 Android 11（API 30）及以上、已开启并正在运行的无障碍服务；低于 API 30 会直接抛“无障碍截图需要 Android 11（API 30）及以上”。Android 的无障碍截图还受系统频率限制、窗口可见性、`FLAG_SECURE` 和安全页面影响；这类限制不能靠重复调用这个接口绕过，本接口也没有 root 分支。它不使用 MediaProjection，所以不需要录屏授权，但也不等于任何页面都能截到内容。

### 示例

```js
"nodejs";
const { accessibility } = require('accessibility');

(async () => {
  await accessibility.enableService({timeout: 30000});
  const shot = await accessibility.takeScreenshot({maxWidth: 720});
  try {
    console.log('size=', shot.width, shot.height);
    const color = shot.pixel(0, 0);
    console.log('top-left=', color.red, color.green, color.blue, color.alpha);
  } finally {
    shot.recycle(); // 必须回收，否则句柄会占内存
  }
})().catch((error) => console.error(error));
```

示例假设当前页面允许无障碍截图；如果系统返回黑图，先检查页面是否设置了安全窗口，再考虑其他截图通道。`maxWidth: 720` 只是请求上限，输出可能是 720 或更小/更高，以 `shot.width` 为准。

### 常见错误与清理

低于 Android 11 会失败；服务未开启和服务已开启但未运行是两类不同错误，前者需要去设置授权，后者需要回到应用重新启动无障碍服务。把 `maxWidth=0` 理解成“不截图”也是常见错误，它是全分辨率。页面受 `FLAG_SECURE` 保护时可能拿到黑图或直接失败，这不是脚本参数能修复的。

`Image` 不回收会持续占用宿主 Bitmap；回收后再读 `width/height/pixel` 会抛错。不要长期缓存截图句柄，也不要在 `finally` 之外提前 return 而漏掉回收；如果使用模板匹配，源图和模板图都要分别回收。

### 源码依据

- `modules/engine/src/main/assets/node24/prelude.js`：`screenshotLimit()`、`accessibilityTakeScreenshot()`、`accessibilityModule.takeScreenshot`。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24AutomationBridge.java`：API 30 检查、`normalizeScreenshotLimit()`、三级回退与临时文件清理。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Images.java`：Raw 截图解码、图片句柄登记与回收。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java`：`kind:'automation'` 的分发与图片句柄回传。
- 系统截图频率、`FLAG_SECURE` 和厂商限制属于平台行为，源码不能证明所有页面都可截。

## Node image.readImage / findImage

### 用途

`image.readImage(path)` 把图片文件解码成 `Image` 句柄；`image.findImage(image, template, options)` 在源图中做模板匹配，返回命中位置或 `null`。两者都有同步版本；异步版只是用 Promise 包装同一段宿主调用，方便在 Node 事件循环里按顺序写代码。

### 引擎与调用

这是 Node24 专用入口，来自 `node24/prelude.js` 的 `imageModule`。`readImage` 的相对路径按**脚本文件所在目录**解析（不是 `files` 的工程根）；绝对路径会规范化。`findImage` 的源图和模板都必须是已打开的 `Image` 句柄，宿主用模板匹配算法返回源图坐标，Region 偏移会加回结果；图像本身不会被回收。

`readImageSync/findImageSync` 是同步阻塞版本；`Image.prototype.findImage` 也是同步方法，并带 `findImageSync` 别名。在 Node 中如需让事件循环继续，优先用 `await image.readImage()` 和 `await image.findImage()`；同步版适合已经在工作线程或明确要阻塞的短脚本。

### 参数

| 调用 | 参数 | 说明 |
| --- | --- | --- |
| `readImage(path)` / `readImageSync(path)` | `path` 非空字符串 | 绝对路径或相对脚本目录；文件不存在、为空、超过 256 MiB、无法解码或超出尺寸预算时抛错 |
| `findImage(image, template, options?)` / `findImageSync(...)` | `image`、`template` 为 `Image`；`options` 对象，或一个数字表示 `threshold` | 返回 `{x, y}` 或 `null`；模板大于搜索区域时抛错 |

`options` 的字段：`threshold` 为 0 到 1 的相似度，默认 0.9；`weakThreshold` 也是 0 到 1，默认 0.6；`level` 为 -1 到 8 的整数，默认 -1；`region` 支持 `[x, y]`、`[x, y, width, height]` 或 `{x, y, width, height}`，边界必须在源图内。源码没有把 `90` 当百分比；阈值 90 会直接超出范围报错。

### 返回值

`readImage` 返回 `Promise<Image>`，`readImageSync` 返回 `Image`；句柄有 `id/width/height`、`pixel()`、`recycle()` 等。`findImage` 返回 `Promise<{x:number, y:number} | null>`，`findImageSync` 同步返回同样的对象或 `null`；`{x, y}` 是匹配模板左上角在源图中的坐标，不是 `Rect`，也不包含匹配分数。没有命中不是异常，只有参数非法、模板过大、解码/匹配失败才抛错。

### 权限与前置条件

读取共享目录中的图片仍需文件权限/SAF 授权；应用私有目录通常不需要。单文件上限是 256 MiB，宿主还会检查解码尺寸和图片登记预算；大图/多图未回收时可能因内存或句柄预算失败。`findImage` 依赖 OpenCV 模板匹配路径，模板和源图都不能已回收。没有源码级“所有格式都支持”的承诺，实际解码能力由 BitmapFactory/OpenCV 决定。

### 示例

```js
"nodejs";
const { readImage, findImage } = require('image');

(async () => {
  // 两个文件与脚本放在同一目录；换成绝对路径也可以
  const frame = await readImage('./screen.png');
  try {
    const template = await readImage('./template.png');
    try {
      const point = await findImage(frame, template, {
        threshold: 0.9,
        weakThreshold: 0.6,
        level: -1
      });
      console.log(point ? `命中左上角: (${point.x}, ${point.y})` : '未找到模板');
    } finally {
      template.recycle();
    }
  } finally {
    frame.recycle();
  }
})().catch((error) => console.error(error));
```

示例只做文件读取和模板匹配，不需要截图权限；实际运行时把两张图替换成自己的素材。若模板来自缩放后的截图，模板尺寸与源图比例必须匹配，否则阈值再低也可能匹配不到。

### 常见错误与清理

路径基准错误最常见：`image.readImage('./tpl.png')` 从脚本目录找，`files.read('./tpl.png')` 从工程工作目录找；混用会得到“图片文件不存在”。忘记 `await` 会把 `Promise` 传给 `findImage`，后者会把它当作非法图片句柄并抛错。模板大于源图或 region 时会抛错，不会返回 `null`；阈值写成 `90` 也会被范围校验拒绝。

`readImage` 返回的是句柄而不是文件字节或 data URL；显示/保存要用 `Image` 的方法。两个源图在匹配后仍然有效，但调用方必须分别 `recycle()`；重复回收返回 `true`，回收后访问宽高或像素会抛错。截图缩放过的坐标不是原始屏幕坐标，做点击前要按实际截图比例换算；跨窗口/多图批处理时注意宿主的结果数量和内存上限。

### 源码依据

- `modules/engine/src/main/assets/node24/prelude.js`：`resolveImagePath()`、`imageRead()`、`imageFindImageSync()`、`imageModule` 的异步/同步导出、`Image` 的 `recycle()`。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Images.java`：`read()` 的文件/尺寸/256 MiB 检查，`findImage()` 的阈值、Region、模板过大和 OpenCV 匹配。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java`：`kind:'image'` 请求分发和句柄返回。
- `docs/guides/API_REFERENCE.md` 的图像章节只用于交叉对照入口；实际参数边界以源码方法为准。

## Node ui.setMainActivity / ui.Activity

### 用途

`ui.setMainActivity(ActivityClass, options?)` 创建一个 Node 脚本界面：Activity 类用 `layoutXml` 或编译资源 `layoutId` 描述内容，`ui` 在宿主显示后派发生命周期回调。它适合需要真实 Android Activity、`View` 绑定和编译资源（主题、动画、drawable）的 Node UI；传统 Rhino/QuickJS 的 `ui.layout` 不是这套实现。

### 引擎与调用

这是 Node24 专用入口，`node24/ui.js` 定义 `Activity` 类和 `setMainActivity`，由 `Node24Prelude` 在 Node 脚本前注入。类应继承 `ui.Activity`；`setMainActivity` 会 `new Ctor()`，读取 `layoutXml`/`layoutId`，同步调用宿主 `show`，注册 120ms 的 `pollEvents` 生命周期轮询，并调用 `$autojs.keepRunning()` 保持脚本存活。返回值是同步结果对象，不是 Promise。

`layoutXml` 优先于 `layoutId`：`layoutXml` 是非空字符串时走 XML inflate；为空或未定义时才读取 `layoutId`。两者都没有会直接抛错。`layoutXml` 返回非字符串（例如对象或数字）会抛 `TypeError`。字符串 XML 不要求先编译资源；`layoutId` 则需要 `ui.R` 能解析到非 0 的布局 ID（通常先 `ctx.loadResources()` 或 `ui.loadAndroidResources()`）。

### 参数

| 参数 | 类型/范围 | 当前行为 |
| --- | --- | --- |
| `ActivityClass` | 函数/`ui.Activity` 子类 | 无参 `new`；不是函数抛 `TypeError`；实例需要有 `layoutXml` 或非 0 `layoutId` |
| `get layoutXml()` | 字符串 | 优先使用；会 `trim()`；空串回退到 `layoutId`；可用自定义 Widget，但最终必须能 inflate |
| `get layoutId()` | 非 0 整数 | `ui.R.layout.name` 或真实资源 ID；Android 包 ID 可能为负，源码只排除 0/空 |
| `options.flags` | 整数，可选 | 当前实现只读取这个键，作为启动本地 UI Activity 的 Intent flags；其他 options 键忽略 |

`options` 不是用来传布局参数的；布局内容由 Activity 实例的 getter/回调决定。`flags` 为 0 时不额外添加 Intent flag。不要依赖未在源码中读取的 options 字段。

### 返回值

`setMainActivity` 返回同步对象：`instance` 是脚本 Activity 实例，`layout` 是实际使用的布局描述（inline/layoutId/name），`host` 是宿主原始返回，`real` 表示是否进入真实 Node Activity，`inline` 表示是否使用 XML 字符串。`host` 只适合诊断，不应作为稳定 API。宿主 `show` 失败或布局不合法时直接抛错，不会返回半成品。

`Activity` 还提供 `onCreate/onStart/onResume/onPause/onStop/onDestroy`、`onContentViewSet(view)`、`onBackPressed()`、`onCreateOptionsMenu/onOptionsItemSelected`、`on(name, handler)`、`findViewById()`（经代理到真实 Activity）、`finish()`、`recreate()`、`startActivity()`。`onContentViewSet` 是绑定 `view.binding.<id>` 的入口；`onBackPressed` 被覆盖时，宿主把返回键交给脚本，脚本应自行决定是否 `ui.close()`。`ui.close()` 关闭当前界面并释放 UI 保活；关闭后不要继续操作旧实例，`recreate()` 也需要界面仍在显示。

### 权限与前置条件

真实 Node Activity 不需要悬浮窗权限；如果宿主无法建立真实 Activity 而回退到 overlay，则回退路径需要悬浮窗权限。UI 需要的主题/布局资源要通过 `ctx.loadResources()` 或 `ui.loadAndroidResources()` 准备好；`layoutXml` 字符串模式可以不走编译资源，但复杂主题、动画和 drawable 仍依赖真实资源表。生命周期回调由 Node 侧轮询事件派发，回调异常写入日志；耗时操作不要阻塞 UI 回调，需要 Android 主线程时用 `ui.runOnUiThread(fn)`，`ui.run` 本身不等于切到主线程。

### 示例

```js
"ui nodejs";
require('rhino').install();
const ui = require('ui');

(async () => {
  class Main extends ui.Activity {
    get layoutXml() {
      return '<vertical padding="16"><text id="title" text="Hello"/><button id="close" text="关闭"/></vertical>';
    }
    onContentViewSet(view) {
      const close = view.binding.close;
      if (close) close.setOnClickListener(() => ui.close()); // 真实 View 监听
    }
    onDestroy() {
      console.log('UI destroyed');
    }
  }

  const result = ui.setMainActivity(Main); // 同步，不存在 await
  console.log('real=', result.real, 'instance=', !!result.instance);
})().catch((error) => {
  console.error(error);
  ui.close();
});
```

示例用字符串 XML，不需要编译资源；`view.binding.close` 由宿主返回的视图映射生成，找不到控件时返回 `undefined`，所以先判空。脚本会因 keepalive 持续到用户点“关闭”或调用 `ui.close()`；生产环境如果还有定时器、服务器或工作线程，要在关闭路径里主动清理它们。

### 常见错误与清理

把 Activity 实例而不是类传给 `setMainActivity` 会抛 `TypeError`；`layoutXml` 返回对象、`layoutId` 为 0、资源表未加载都会失败。`ui.R.layout.foo` 不存在时返回 0，不要拿 0 当布局 ID；先确认 `ctx.loadResources` 成功或改用 XML。`layoutXml` 的标签/属性不受支持时，宿主 inflate 会报错；应先用最小布局排查，不要假设所有 Android 控件属性都能直接映射。

在 UI 回调里做同步网络、长时间 `sleep`、死循环会卡住页面；需要等待时用 `await` 和定时器，需要真正主线程时用 `ui.runOnUiThread`。`ui.close()` 后旧 `View`/Activity 引用不能再使用，`recreate()` 也会因界面已关闭而抛错。回退 overlay 时没有真实 Activity 的资源/主题能力，且需要悬浮窗授权；`result.real` 可以用于 UI 诊断，但不要把它当作所有设备上必然为 `true` 的保证。

### 源码依据

- `modules/engine/src/main/assets/node24/ui.js`：`class Activity`、`setMainActivity()`、`close()`、`cleanupUiLifecycle()`、`pollEvents()`、`runOnUiThread` 和导出表。
- `modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiLocalSession.java`：`show` 的 `flags` Intent 参数、Activity 回调与 `recreate`。
- `modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiHost.java`：UI `show`、`layoutXml`/`layoutId` 与主题/资源路由。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Prelude.java`：`node24/ui.js` 的注入顺序。
- `docs/guides/NODE_UI_API.md`：现有 UI 生命周期说明；仍以当前源码分支为准。

## Node ctx.loadResources

### 用途

`$autojs.androidContext.loadResources(path)` 给当前 Node 任务挂载一个已编译的 Android 资源包，使 `ui.R`、Activity 的 `getResources()`/`getTheme()` 和 `LayoutInflater` 能看到工程布局、id、主题、drawable 等真实资源。它只加载编译产物，不编译 `res/` 目录，也不替代电脑端/工具端的资源编译步骤。

### 引擎与调用

这是 Node24 专用入口。`$autojs.androidContext` 的 getter 通过宿主 `kind:'java', op:'context'` 取得每个 job 独立的 `ScriptResourceContext`；`loadResources(String)` 是它的同步 Java 方法，由 JS 桥直接调用，不返回 Promise。相对路径按 Node 工程目录解析（`Node24HostBridge` 构造时的 `projectDir`，即 `Node24Prelude.projectDir(filename)` 找到的工程根），绝对路径直接规范化。

加载成功后，这个 Context 的 `Resources/AssetManager/Theme/LayoutInflater` 都切到新的资源表；`ui.R` 会通过 `layoutId`/`resourceId` 查询同一张表。`ui.setMainActivity()` 在创建界面时会检查资源状态，因此一般不需要再调用 `ui.loadAndroidResources()`；后者是带 Promise 的旧入口，按 `project.json` 的 `androidResources` 找默认 `build/project-res.apk`，不要把两者重复调用当成刷新界面。

### 参数

| 参数 | 类型/范围 | 当前行为 |
| --- | --- | --- |
| `path` | 非空字符串；绝对路径或相对工程目录 | 文件必须存在且是含 `resources.arsc` 的编译包（常见 `.apk`/`.ap_`）；`res/` 目录、空文件、损坏包、没有资源表都会抛错 |
| 返回 | `void` | 同步成功/失败；失败抛 `IllegalArgumentException` 或 `IllegalStateException`，没有 `{ok:false}` 返回值 |

资源包还要和当前宿主匹配：源码会检查 `project-res.json` sidecar 的 `appResourcesSha256`（旧包回退到 `appApkSize`），不匹配时抛“资源包与宿主资源表不匹配，请重新编译”。源码没有限制扩展名，关键是资源表可解析；实际工具产物通常是 `tools/build-project-res.ps1` 或 `tools/build-bundled-project-res.py` 生成的包。

### 返回值

成功时没有返回值；`ScriptResourceContext` 的 `revision` 递增，`compiled` 指向新表，主题和 inflater 缓存清空。加载失败时旧表保留（赋值发生在完整加载之后），所以不能把异常当成“已清空”；修复路径后可以重新调用。同一资源包名再次加载会替换旧包，其他已加载的资源包路径继续保留；已经显示的 Activity 不会自动重建，需要调用 `recreate()`（仅当前 Node UI Activity）或重新 `setMainActivity`。

### 权限与前置条件

需要能读取资源包文件的权限（应用私有目录通常不需要额外授权）；资源包必须由与当前宿主 APK 匹配的资源编译工具生成，且包含 `resources.arsc`。每个 Node job 有独立的 `ScriptResourceContext`，不会覆盖主应用资源表；任务结束时宿主调用 `release()`，之后不能再 `loadResources`。主题切换只影响这个脚本的资源 Context，不是系统级主题；`setTheme(styleId)` 也要求 style 资源真实存在。

### 示例

```js
"ui nodejs";
require('rhino').install();
const path = require('node:path');
const ui = require('ui');
const ctx = $autojs.androidContext;

(async () => {
  // 需先用项目资源编译工具生成 build/project-res.apk
  ctx.loadResources(path.join(__dirname, 'build', 'project-res.apk')); // 同步，失败会抛错

  if (!ui.R.layout.activity_main) throw new Error('资源表中没有 layout/activity_main');

  class Main extends ui.Activity {
    get layoutId() { return ui.R.layout.activity_main; }
    onContentViewSet(view) {
      const title = view.binding.title;
      if (title) title.setText('资源已加载');
      const close = view.binding.close;
      if (close) close.setOnClickListener(() => ui.close());
    }
    onDestroy() { console.log('UI closed'); }
  }

  const result = ui.setMainActivity(Main);
  if (!result.real) {
    ui.close();
    throw new Error('未建立真实 Node Activity，资源 UI 可能回退到 overlay');
  }
})().catch((error) => {
  console.error(error);
  ui.close();
});
```

示例假设编译包里有 `activity_main` 布局以及 `title`/`close` 两个 id；没有这些资源时应在自己的工程里替换名字。`ctx.loadResources` 先于 `ui.R` 和 `setMainActivity` 调用，路径按脚本所在工程的 `build/` 目录组织；真正运行前要确认 APK 与当前宿主版本匹配。

### 常见错误与清理

把 `res/` 源目录直接传给 `loadResources` 会失败：这里没有 aapt2，必须传已经编译的资源包。相对路径不是按脚本目录，而是按 Node 工程根；如果脚本在 `src/` 子目录，路径应相对工程根或直接用 `__dirname` 拼绝对路径。资源包与当前宿主不匹配时源码会报告“请重新编译”，不要改扩展名或跳过 sidecar 检查来绕过。

加载失败后旧资源表仍在，所以界面可能继续显示旧资源；应显式捕获异常并停止后续 UI 创建，而不是静默继续。`ui.R` 查不到资源时返回 0，拿 0 去 inflate/设主题会报错；先检查 `layoutId`。已经显示的 Activity 需要 `recreate()` 或重新创建才能看到新资源；`recreate()` 只在当前界面仍显示时可用。任务结束/Context 被 `release()` 后不要再调用 `loadResources`，也不要把 `ctx` 传到其他 Node job 复用。

### 源码依据

- `modules/engine/src/main/java/com/stardust/autojs/core/ui/inflater/ScriptResourceContext.java`：`loadResources()`、`getResources()`、`getAssets()`、`getTheme()/setTheme()`、`release()` 和失败保留旧表的行为。
- `modules/engine/src/main/java/com/stardust/autojs/core/ui/inflater/CompiledProjectResources.java`：`loadIsolated()`、`archivePackageName()`、`resources.arsc`/sidecar 校验。
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java`：`mAndroidContext` 的创建、`op:'context'` 返回、job 结束 `release()`。
- `modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiHost.java`：`ui.loadAndroidResources()` 与 `ScriptResourceContext` 的衔接；`node24/ui.js`：`ui.R` 的 `layoutId`/`resourceId` 查询。
- `docs/guides/NODE_UI_API.md` 第 2 节：资源 Context 的既有说明；本文没有新增真机结论。
