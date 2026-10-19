# Node 特征匹配 drawMatches 与 viewFile 路径修复（task-184）

日期：2026-10-20。执行：t1-native-services。验证机：K40（cccc62c7，dev-1131 待填）。

## 1. 问题

示例 #34「特征匹配(全分辨率找图).js」跑完后 `app.viewFile('./matches.jpg')` 报文件不存在。

## 2. 根因

- 相对路径基准本身一致：prelude `image.matchFeatures` 的 `drawMatches` 与 `app.viewFile` 都经 `resolveImagePath` 按**入口脚本所在目录**解析为绝对路径，再交给宿主；宿主 `viewFile` 只接受已存在文件。
- 真正问题：`Node24Images.matchFeatures` 在**未匹配（corners == null）时提前 return null**，没有执行 `FeatureMatcher.drawMatches`；而示例在最后一轮无论是否匹配都调用 `viewFile('./matches.jpg')` → 文件不存在报错。

## 3. 实现

- `Node24Images.matchFeatures`：未匹配时若指定了 `drawMatches`，仍把场景图画出来（只画场景特征点，不画四边形），保证调试图存在后再返回 null；匹配成功路径保持原有绘制；
- 相对路径解析保持 prelude 统一 `resolveImagePath`（脚本目录基准），宿主只接收绝对路径；
- 未修改示例（API 修复即可让示例末尾不再报错）。

## 4. 修改文件

- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Images.java`；

编译：`:engine:compileDebugJavaWithJavac` + `:app:compileMiuixCompatDebugJavaWithJavac -PflutterPoc=true` BUILD SUCCESSFUL（15s）。

## 5. K40 验证（dev-1131）PASS

设备 K40 cccc62c7；验证脚本 /sdcard/脚本/node184/main.js（项目目录运行，drawMatches 使用 ./task184-nomatch.jpg / ./task184-match.jpg）。

| 用例 | 结果 |
|---|---|
| 未匹配 + drawMatches | NOMATCH_RESULT null、NOMATCH_EXISTS true（文件写在脚本目录 /sdcard/脚本/node184/task184-nomatch.jpg，795 KB） |
| 未匹配后 app.viewFile 相对路径 | VIEWFILE_OK true，不再报文件不存在 |
| 匹配 + drawMatches | MATCH_RESULT center=387,685、MATCH_EXISTS true（task184-match.jpg，271 KB） |
| 相对路径基准 | 两个文件都落在入口脚本目录，与 prelude resolveImagePath 规则一致 |

结论：示例末尾文件不存在根因（未匹配不写调试图）已修复；示例无需改动。
## 6. 限制

1. 未匹配时的调试图只画场景特征点，没有匹配连线/四边形（符合「调试用」语义）；
2. 若场景特征为空（sourceMat 为空）仍无法写图，此时会记录 warning，viewFile 仍会报不存在（极端情况）。
