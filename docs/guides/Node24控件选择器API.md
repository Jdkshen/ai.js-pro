# Node24 控件查询、点击与文字输入

适用主 App 的 flutterPoc 构建。先开启本应用的无障碍服务，再在 Node 引擎中运行脚本。

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
