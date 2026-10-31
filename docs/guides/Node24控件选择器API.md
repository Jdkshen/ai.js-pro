# Node24 控件查询、点击与文字输入

> 当前实现：dev-1294（R01–R08 节点对齐集成）。主进程与 `:script` 分开验收；节点分页/BFS/身份与 Java varargs 见 `docs/reports/2026-10-31_节点对齐R01-R08集成发布.md`，早期复核见 `docs/reports/2026-10-28_三引擎节点API复核与修复.md`。

适用主 App 的 flutterPoc 构建。先开启本应用的无障碍服务，再在 Node 引擎中运行脚本。

> **三引擎差异（重要）**：Node24 的 `select()` 查询是异步的，`firstOrNull()/findFirst()/all()/root()` 返回 Promise；节点动作仍是同步 boolean。Rhino/QuickJS 的 `selector()`/`findOne()`/`untilFind()` 是同步阻塞调用。不要把 Node24 的 Promise 当同步返回值，也不要在 Rhino/QuickJS 里写 `await select(...)`。

```js
"nodejs";
const { accessibility, select } = require('accessibility');

async function main() {
  await accessibility.enableService();
  const input = await select({ desc: '搜索输入框', className: /EditText$/ }).firstOrNull();
  if (input) {
    console.log(input.text, input.bounds, input.childCount);
    console.log('输入结果', input.setText('测试内容'));
  }
}
main().catch(console.error);
```

## 查询方式

面向内置 Node 示例的查询返回属性式节点：`node.text` 是字符串，`node.clickable` 是布尔值，
`node.childCount` 是 Android 报告的直接子节点数。动作 `click()/longClick()/setText(string)` 同步返回实际成功与否。

```js
// 只查一次，没有匹配返回 null。
const node = await select({ text: '日志' }).firstOrNull();

// 定时重新查询，让出 Node 事件循环；超时返回 null。
const later = await select({ text: '加载完成' }).findFirst({ timeout: 2000, maxRetries: 10 });

// JS 谓词收到属性式节点；最多取 12 项。
const texts = (await select().where(n => n.text !== '').atMost(12).all()).map(n => n.text);

// 只搜索指定父节点的后代，排除父节点本身。
const children = await select({ className: /TextView$/, desc: /Symbol/ })
  .from(select({ id: 'symbolBar' })).all();

const root = await accessibility.root();
if (root) console.log(root.tree().map(n => n.text));
```

`root.tree()` 是包含根节点的树快照；`parent()/children()/child(index)` 查询层级关系。
不要把快照当作持续更新的 Android 对象。界面内容变化后重新查询，动作可能返回 `false`。

原有方法式接口继续保留：`select().find()/findOnce(index)/findOne(timeout)/exists()/waitFor(timeout)`。
这些接口返回的节点用 `text()/bounds()/path()` 读取字段。同步等待会阻塞 Node 事件循环；需要
Node 定时器或 UI 回调配合时，使用 `firstOrNull()/findFirst()/all()`。

## 条件与错误

条件以 AND 组合。支持 `id/text/textContains/textStartsWith/textMatches/desc/descContains/className/packageName`、
`clickable/enabled/scrollable/depth/index/path/bounds`；`index` 条件表示父节点内的子下标，
`findOnce(index)` 参数表示查询结果中的序号。`path([])` 可查根节点。

字符串属性可使用 JS `RegExp` 做本地筛选。`textMatches` 字符串使用 Java 正则的整串匹配，
与 JS 正则的搜索语义不同。`childCount` 支持数值和比较条件，如 `{ childCount: { '>': 0 } }`。
`textMatches` 与本地谓词混用时仍由 Java 匹配，再按路径与快照取交集；支持 Java 的内联标志和引用语法。

异步轮询间隔为 250ms，与宿主 dump 缓存时间相配合。`timeout(0)` 只查一次，
`maxRetries(n)` 表示首次查询后最多再查 n 次。`atLeast(n)` 耗尽等待时间后返回当时已找到的数组，
数量可能少于 n；`findFirst` 超时返回 `null`。

未知条件名、错误类型和非法范围会报错，避免条件被忽略后扩大匹配。
路径最多 63 层，`depth` 为 0..63，父内子下标为 0..8191，`findOnce(index)` 为 0..255。
`bounds` 须含四个整坐标，绝对值不超过 10000000，且右/下边界不能小于左/上边界。
`waitFor` 仅把未找到处理为 `false`，服务或宿主协议错误继续抛出。

节点动作携带查询时的路径和文本、描述、类名等条件，避免在筛选后用错误的结果序号操作节点。
动作失败不会自动改用屏幕坐标。`setText('')` 可清空输入框，非可编辑节点可以返回 `false`。

## 宿主通道与范围

查询、动作统一经过每个 Node 任务的 `Node24AutomationBridge`，任务结束统一释放连接。
扩展模块不再自己反射并绑定第二个客户端。请求示例：

```json
{"kind":"automation","action":"setTextNode","selector":{"path":[0,1],"desc":"搜索输入框"},"index":0,"text":"测试"}
```

`findNodes/getNodeInfo/getLatestNodeDump/clickNode/longClickNode/setTextNode` 使用既有主进程后端，
返回统一 `{ok,value}` / `{ok:false,error}`。JS 正则、谓词和父节点筛选在节点快照上计算。

后端存在结果数量、深度、响应大小限制，大窗口的 `all()/tree()` 可能不是完整树。
本批未增加无障碍事件订阅、锁屏、当前焦点自动输入，也不覆盖 inrt 打包运行时。

本地契约验证：`node tools/test-node24-accessibility-selectors.cjs`。
这些检查验证适配层和协议，实际点击与输入仍需 Android 真机验证。


## 三引擎节点 API 全量对照（dev-1275）

同一套 UiObject 概念在三个引擎里都能用；区别只是“怎么拿到节点”和“是否 Promise”：

| 能力 | Rhino | QuickJS | Node24 |
|---|---|---|---|
| 取节点 | `text(...).findOne()` 等同步选择器 | 同 Rhino | `await select({...}).firstOrNull()` |
| click | `node.click()` | 同 Rhino | `node.click()` |
| clickCenter | `node.clickCenter()` | 同 Rhino | `node.clickCenter()` |
| longClick/setText | 同步 | 同步 | 同步 |
| copy/cut/paste/select | 同步 | 同步 | 同步（经主进程 nodeAction） |
| collapse/expand/dismiss/show | 同步 | 同步 | 同步（经主进程 nodeAction） |
| scrollForward/Backward/Up/Down/Left/Right | 同步 | 同步 | 同步（经主进程 nodeAction） |
| contextClick/focus/clearFocus/accessibilityFocus | 同步 | 同步 | 同步（经主进程 nodeAction） |
| setSelection/scrollTo/setProgress | 同步 | 同步 | 同步（经主进程 nodeAction） |
| children/childCount/child/parent | 同步 | 同步 | 同步 |
| findByText/find/findOne | 同步 | 同步 | 同步（Node 节点方法基于 select） |
| bounds/boundsInParent/drawingOrder/属性 | 同步 | 同步 | 同步 |

Node24 完整动作见下面示例；无参动作返回 boolean，参数动作如下：

```js
"nodejs";
(async () => {
  const { select } = require('accessibility');
  const node = await select({ text: '推荐' }).firstOrNull();
  if (!node) return;
  node.click();
  node.clickCenter();
  node.longClick();
  node.copy(); node.cut(); node.paste(); node.select();
  node.collapse(); node.expand(); node.dismiss(); node.show();
  node.scrollForward(); node.scrollBackward();
  node.scrollUp(); node.scrollDown(); node.scrollLeft(); node.scrollRight();
  node.contextClick();
  node.focus(); node.clearFocus();
  node.accessibilityFocus(); node.clearAccessibilityFocus();
  node.setSelection(0, 0);
  node.scrollTo(0, 0);
  node.setProgress(0.5);
})().catch(console.error);
```

> 这些动作在页面变化、节点不支持对应 AccessibilityAction 或 bounds 失效时返回 false；不会用坐标点击冒充语义动作。`clickCenter()` 例外，它按当前 bounds 中点做坐标点击，越界返回 false。

内置示例见下节「三引擎完整 PASS/FAIL 示例」；三份示例逐项输出 PASS/FAIL/SKIP。

MCP 侧新增 `ui_node_action`，按 `text/resourceId/contentDesc/className/bounds` 定位后执行同名动作，参数 `{"action":"scrollDown","args":{}}`。

## 三引擎完整 PASS/FAIL 示例（安全操作版）

下面三份示例逐项验证完整节点 API，并实际观察动作返回值：

| 引擎 | 示例文件 | 查询方式 |
|---|---|---|
| Rhino | `apps/app/src/main/assets/sample/Rhino 引擎/三引擎统一示例/节点UiObject全量示例.js` | 全局 `selector()/findOne()/untilFind()`，同步 |
| QuickJS | `apps/app/src/main/assets/sample/QuickJS 引擎/三引擎统一示例/节点UiObject全量示例.js` | 同一套 Java `UiObject`，同步 |
| Node24 | `apps/app/src/main/assets/sample/Node 引擎/Node.js/无障碍/节点UiObject全量示例.js` | `require('accessibility').select()`，**异步查询** + 同步动作 |

这三份示例都遵循同一套安全策略：

- 只操作脚本自己弹出的 `SAFE_CLOSE` 对话框（标题 `NODE_API_UI_SURFACE`），不点击真实应用按钮。
- `setText` 只写对话框自己的输入框，写入 `NODE_API_SET_TEXT` 后立即恢复 `NODE_API_INPUT`。
- `copy/cut/paste` 只在不可编辑的标题节点上调用，不读取或覆盖用户剪贴板。
- `dismiss` 只关闭脚本自己的对话框；对话框不可见时改为当前窗口只读检查，并跳过真实点击/滚动。

### 属性与遍历对照

| 能力 | Rhino / QuickJS `UiObject` | Node24 `NodeView` / `Node` |
|---|---|---|
| 文本/描述/id/类名/包名 | `text()/desc()/id()/className()/packageName()` | 属性 `text/desc/id/className/packageName`；`Node` 用同名方法 |
| 边界 | `bounds()/boundsInParent()` | `bounds/boundsInParent`（`Node` 用方法） |
| 绘制顺序 | `drawingOrder()` | `drawingOrder`（`Node` 用方法） |
| 层级/下标/子数 | `depth()/indexInParent()/childCount()` | 属性 `depth/indexInParent/childCount`（`Node` 用方法） |
| 布尔属性 | `clickable()/enabled()/scrollable()/checkable()/checked()/focusable()/focused()/selected()/longClickable()/visibleToUser()/password()` | 同名属性；`Node` 用同名方法 |
| 遍历 | `parent()/children()/child(i)/findByText(t)/find(selector)` | `parent()/children()/child(i)/findByText(t)/find(selector)/findOne(selector)` |
| 路径 | 无公开等价属性 | `path` 属性 / `path()` 方法 |

> 属性式 `NodeView` 与字段式 `Node` 不要混用：`node.text` 是字符串，`node.text()` 是方法。
> Rhino/QuickJS 的 `UiObject` 是方法式，所有字段都要加括号。

每项输出 `[PASS]` / `[FAIL]` / `[SKIP]`，末尾输出：

```text
NODE_UI_API_RESULT={"engine":"rhino","passed":...,"failed":0,"skipped":...,"checks":[...]}
RESULT=PASS|PARTIAL|FAIL
```

- `PASS`：没有 FAIL，也没有 SKIP。
- `PARTIAL`：没有 FAIL，但有 SKIP（例如当前窗口没有带 desc/id 的节点，或 Node 后端尚未实现某个统一动作）。
- `FAIL`：至少一项 FAIL，查看对应行与 `NODE_UI_API_RESULT.checks` 的 detail。

> Node24 的 `select()` 查询返回 Promise；节点动作返回同步 boolean。示例会在调用前用 `typeof` 检查方法，未实现时输出 SKIP，不伪造 PASS。
> 动作返回 `true` 只表示宿主接受了动作；控件不支持、窗口变化或权限不足时返回 `false`，这仍是 PASS 的“契约结果”，不是脚本异常。


## dev-1279 节点接口复核与约束

Rhino、QuickJS 的普通脚本经过 `:script` 时，返回 `RemoteUiObject`，不等于主进程的 Java `UiObject`。本轮补齐常用属性、节点动作、父子遍历和范围查询；不能再以主进程示例通过推断隔离进程通过。

- 布尔读取补齐 `editable/checkable/checked/focusable/focused/selected/longClickable/visibleToUser/password/accessibilityFocused/contextClickable/dismissable/contentInvalid/multiLine/isHierarchically`；集合字段为 `row/column/rowCount/columnCount/rowSpan/columnSpan`，另有 `drawingOrder`。
- `parent()/children()/child(i)` 按真实 Android 子节点下标查找；遇到稀疏子节点不会把后面的节点挪到空缺下标。范围查找包括 `find/findOne/findByText/findByViewId`，`findByText` 使用包含匹配。Node 属性式节点的查询结果继续保持属性式。
- 动作包括点击、中心点击、长按、文本写入、选择范围、进度、行列滚动、复制/剪切/粘贴、选择、展开/收起、关闭/显示、六种滚动、上下文点击、焦点与无障碍焦点。能调用不代表目标支持；`false` 表示目标消失或 Android 拒绝该动作，不是成功。
- `select(...).find()/all()` 和节点 `children()` 仍返回 Array，同时提供 `size/empty/nonEmpty/get/toArray` 和集合动作。集合动作遍历全部成员，全部返回 `true` 才返回 `true`；空集合的无参动作沿用全部成立语义返回 `true`，不是已操作节点。参数化动作在空集合上也校验参数。
- Node 传统选择器补齐 `untilFind(timeout?)` 和集合动作快捷入口。`untilFind()` 无参会持续等待；现代异步 `findFirst({timeout})` 更适合需要让出事件循环的脚本。原有 Node 方法式 `findOne(timeout)` 超时抛 `NotFoundError`，默认 10 秒；Rhino/QuickJS 的同步接口语义不同，现有 Node 行为保留。
- 参数规则：`setSelection(start,end)` 要求非负 32 位整数且 `start <= end`；`scrollTo(row,column)` 要求非负 32 位整数；`setProgress(value)` 要求有限且不超过 float 范围。不能把字符串、小数或溢出值默默变成 0。
- 动作使用节点路径及身份条件重新定位，过期节点不自动改点旧坐标。`clickCenter()` 使用重新获取的合法边界。读取到的是快照，界面变化后应重新查询；显式保留的树适合离线遍历，不证明节点仍在线。
- 密码节点的 `text` 为 `""`，写入后也不把明文保存在公开快照或动作身份条件中。
- Node24 与隔离进程的单次 `setText` 传输上限为 32,768 字符和 65,536 UTF-8 字节，任一超限明确报错，不截断。长文本身份条件、超大树和查询结果仍受既有消息/数量预算限制。
- 通信断连和主线程请求超时会抛宿主错误；正常未找到仍是空结果，正常不支持的动作仍是 `false`。不要对超时动作盲目自动重试。

### MCP 定位和输入

`index` 是匹配结果中的 **0 起序号**，范围 `0..255`。不写取第一个；不是 `indexInParent`。`exact` 默认 `true`，设为 `false` 时文本和描述使用包含匹配；`x/y` 必须一起给出。错误类型、非法边界和缺失的动作参数会在定位前拒绝。

输入新文本与目标旧文本应分开：

```json
{"selector":{"className":"android.widget.EditText","text":"旧内容"},"text":"新内容","index":0}
```

也可用平铺的 `resourceId` 定位，此时顶层 `text` 只表示新值：

```json
{"resourceId":"search_input","text":"新内容"}
```

`ui_node_action` 的参数对象示例：

```json
{"selector":{"resourceId":"search_input"},"action":"setSelection","args":{"start":0,"end":2}}
```

`setProgress` 使用 `args.value`；`scrollTo` 使用 `args.row/args.column`。嵌套选择器与顶层只能有一处 `index`。MCP schema 同时公开嵌套 selector 和边界格式，避免工具声明与实际解析脱节。

### 仍需按能力区分

自定义 JavaScript 谓词、查询算法、正则选项、Node Promise/方法式返回类型以及集合二次过滤，并不能仅按同名函数判断等价。本轮的跨进程实测结论以报告里的具体用例为准；尚未逐项验证的接口不标记为完全兼容高级版。


## 节点 API 后续对齐（dev-1280 候选）

- `getRangeInfo()` 在三引擎均提供 `getMin()/getMax()/getCurrent()/getType()`。Node 还可读取属性式 `node.rangeInfo.min/max/current/type`。非范围控件返回 `null`。它是查询时的快照；执行 `setProgress()` 后重新查询再读取，可核对真正的进度。
- `filter(fn)/addFilter(fn)` 在 Rhino、QuickJS 的隔离进程补齐，回调收到方法式节点，例如 `n.text()`；Node 的同名方法也接收方法式节点，原有 `where(fn)` 继续接收属性式 `NodeView`。谓词在脚本引擎内对快照运行，不向主进程传递任意 JS 函数；抛错会向调用方传播。
- `algorithm("BFS"/"DFS")` 大小写不敏感。基本条件查询由宿主真正按层遍历/深度遍历，影响第一个匹配结果。未知算法报错。BFS 暂不能与本地 JS 谓词、JS RegExp、范围快照组合；这些组合会明确报错，不会伪装成 DFS 结果。
- 宿主条件与本地条件组合、以及 `node.find(...)`，不再先取 500 个全局候选再过滤。`textMatches + where` 使用批量 Java Pattern 完整匹配，保留 `(?i)`、`\Q...\E` 等 Java 语法。每批最多 256 个文本、共 65,536 字符，正则长度最多 32,768 字符。
- `node.find/findOne/findByText/findByViewId` 的范围包含节点自身，以对齐原生 UiObject；`select(...).from(parent)` 仍只搜索严格后代。
- Node/QuickJS/MCP 在桥接入口拒绝错误 JSON 参数类型；Rhino 保留 Java 互操作的参数转换。数值范围统一校验：选择区间非负且 start<=end、滚动行列非负、progress 必须为有限 float。QuickJS 不再先把字符串/布尔值转为 progress 数值。

```js
// Rhino / QuickJS 同步查询；Node 可用 require('accessibility').select().desc(...).
const slider = desc('音量').findOnce();
if (slider && slider.setProgress(66)) {
  const current = desc('音量').findOnce().getRangeInfo();
  console.log(current ? current.getCurrent() : '目标不提供范围信息');
}
```

查询仍受宿主遍历预算约束（节点/深度/5 秒/约 200 KiB JSON；直接查询最多 500 项）。本轮不把极大树的部分快照称为完整结果；有序快照和分页仍待完善。三引擎实测与具体边界见后续复核报告。

三套同名只读示例位于“示例 → 各引擎 → 三引擎统一示例 → 节点查询进阶_只读.js”。它们展示 DFS/BFS、自定义过滤和范围读取，空结果会如实显示，不把没有目标控件当作动作成功。

## dev-1294 新增：分页/BFS/身份与 Java varargs

### Node 快照与分页

- `accessibility.snapshotInfo()` → `{nodes, complete, truncated, firstMissingPath, generation}`
- `accessibility.treePage(limit[, cursor][, {signal}])` → `{nodes, complete, truncated, snapshotComplete, firstMissingPath, generation, seen, cursor}`
- `select(spec).findPage(limit[, cursor][, {signal}])`：同一快照内稳定分页；游标绑定代次，过期/畸形明确报错；`signal.aborted` 时抛“已取消”
- 完整性由宿主 DFS dump 的 path/childCount 重新校验；宿主单次预算（约 200KiB/500 节点/5s/5000 访问）未变，截断时只如实报告，不返回被截断的节点

### 跨布局身份

- `node.token` / `node.generation` / `node.isStale()`；插入/删除/列表复用后同一 path 换成别的节点 → `isStale()=true`，动作返回 `false`，不做坐标兜底
- `index`/`findOnce(index)` 仍是 0..255；更大位置用 path + 身份条件定位

### Java 反射可变参数

- `Class.getMethod(name)`（省略空参数数组）现在可用；`getMethod(name, [])` 与省略写法一致
- 支持零个/一个/多个 varargs；基本类型按组件类型收窄；固定参数重载优先于 varargs

### QuickJS（R04）

- QuickJS 公开 `ui.inflate(xml, parent?, attachToParent?)`，返回真实 View（Java 代理）；`$ui.inflate` 仍未暴露（C++ 白名单）
