# task-111：projectBuildStatus 的 warnings 恒为空的根因与修复（W2）

## 根因（读码钉死，file:line）
PocPackaging.runBuild 的**顺序**问题：旧代码先 job.success(...)（把 status=success 暴露出去）**再**读 warnings 写进 job：
- 旧 542：job.success(outApk, sha256, …) → 里面立刻把 status 置成 "success"，size 也就位
- 旧 546-552：才 builder.getPackagingReport() → job.warnings(warningList)
⇒ projectBuildStatus 只要在**这两步之间**被轮询，就会读到 status=success + size 已有 + warnings:[] —— 与真机 JSON（status:"success"、size:30907025、warnings:[]）**逐字吻合**。

## 逐条回答 4 个查点
1. **是否同一实例/同一次构建**：是。PocPackaging 里 builder 只有一个实例（prepare/build/sign 全在它上面），getPackagingReport() 返回 final mPackagingReport。**不是实例问题**。
2. **report(...) 的位置**：ApkBuilder.java:525（build() 末尾），pruneDisabledPackagingAssets() 在同方法 **518**、mPackagingWarnings.add 在 **606** ⇒ build 返回时数组里**已经有**那条 OCR 警告 ⇒ 丢失发生在**消费侧顺序**，不是收集侧。
3. **getPackagingReport() 是否每次重建**：不是重建（返回同一对象）—— 但它**不刷新**：谁在最后一次 add 之前读它，谁就拿到旧快照 ⇒ 本次加固点。
4. **其它 warning 是否同样丢**：**是**。ABI（ApkBuilder.java:901）、keystore/插件/sjbc patch（:813）都进同一列表，全受同一窗口影响；你这次抓到 OCR 只是因为它最显眼。

## 修（3 处，默认行为不变）
1. **PocPackaging：先收 warnings，再置 success**（真修）：warnings 块移到 job.success(...) 之前（现在顺序：读报告 → job.warnings(...) → job.stage("success") → job.success(...)）；数据源**双保险** —— 报告里 packagingWarnings 为空时**回退**到 builder.getPackagingWarnings()（实时列表）。
2. **ApkBuilder.getPackagingReport()：每次读取都用实时 warnings 同步一次**（put(PACKAGING_WARNINGS, new JSONArray(mPackagingWarnings))）⇒ 消灭「读得早 / 读到旧快照」这一整类问题，消费方不必再关心读取时机。
3. **键名同源**：新增 PackagingReportFields.PACKAGING_WARNINGS；ApkBuilder 里不再出现 packagingWarnings 字面量（单测钉住）。

## 验证方式
· 新增 apps/app/src/test/java/com/jdkshen/aijspro/autojs/build/PackagingWarningsContractTest.kt（3 项）：
  ① PocPackaging 里 job.warnings(warningList) 必须**早于** job.success(outApk, sha256 …)，且存在 builder.getPackagingWarnings() 回退；
  ② getPackagingReport() 实现体里必须同时出现 PackagingReportFields.PACKAGING_WARNINGS 与 mPackagingWarnings，且 ApkBuilder 里该字面量出现次数为 **0**；
  ③ PackagingReportFields.PACKAGING_WARNINGS == packagingWarnings，且 all() 仍是那 5 个瘦身计数字段（不改既有口径）。
· 离线 kotlinc + JUnit（cwd=apps/app）：PackagingPolicyTest + RemoveOcrProjectConfigTest + AntlrResourceExclusionTest + OcrAssetDiscoveryTest + **PackagingWarningsContractTest** ⇒ **OK (27 tests)**。
· 未跑构建/装机/发布/git（等你出下一版）。

## 未验证边界
1. **没有真机**：由 Lead 复验（removeOcr 工程重打后 projectBuildStatus.warnings 至少含那条 OCR 文案 + UI「打包提示（N）」出现；默认工程仍 warnings:[] 且该块隐藏）。
2. 顺序修解决的是「success 已暴露但 warnings 未写入」的窗口；**构建进行中/失败**的轮询仍可能读到 warnings:[]（此时本来也没有最终 warnings）—— 属预期，不是缺陷。
3. getPackagingReport() 改成读时刷新：语义只更及时，字段名与默认值不变。
4. PocPackaging 属消费侧，本次只做「顺序 + 回退」，未改任何打包行为。
