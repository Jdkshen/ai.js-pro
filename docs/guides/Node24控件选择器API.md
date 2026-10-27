# Node24 控件查询、点击与文字输入

> 当前验证基线：dev-1112（Node 21/21、Node UI basic 8 steps；`:script` 自动化代理为常用面子集）。

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
