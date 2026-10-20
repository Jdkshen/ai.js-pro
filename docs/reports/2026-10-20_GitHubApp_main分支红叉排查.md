# GitHub App 分支显示 main ✕ 排查记录（修订版）

- 仓库：https://github.com/Jdkshen/ai.js-pro
- 现象：仓库页「当前频道/分支」显示 main，后面带红色 ✕
- 记录时间：2026-10-20（本次会话实测修订）
- 修订说明：原记录把「分支已删除/改名」列为大概率原因，与实际不符；本文按实测结果重排结论与步骤。

---

## 一、结论先行

红色 ✕ 不是「删除分支」按钮，也不是仓库里没有 main。

它是 App 侧的状态标记：当前选中的分支引用在本地缓存/接口返回里校验失败（App 认为这个 ref「不存在、拿不到或与远端不一致」），所以把 main 标成无效（✕）。网页版和 API 都正常时，问题基本都在 App 端。

实测（2026-10-20，GitHub REST API）：

| 检查项 | 结果 |
|---|---|
| 仓库可访问 | 200 OK，公开仓库，未归档 |
| 默认分支 | default_branch = main（没有被改名/删除） |
| 分支列表 | 含 main（sha 3013e67c）、sandbox/node24-image-xq、两个 chore 分支 |
| 最新推送 | pushed_at = 2026-10-20T04:36:10Z（北京时间 12:36） |

结论：远端 main 完全正常，✕ 是客户端缓存/引用问题。

---

## 二、可能原因（按实际概率排序）

| # | 原因 | 典型表现 | 判定方式 |
|---|---|---|---|
| 1 | 本地缓存/引用过期（App 缓存了旧 ref 哈希） | 下拉刷新或重进页面仍红叉 | 杀进程重开、清 App 缓存后恢复 |
| 2 | force-push / 重建历史后引用对不上 | 分支存在但 sha 变了；部分客户端把旧引用标红 | 重新 fetch / 重新选择分支后恢复 |
| 3 | 当前选中分支被删或改名（不是 main 本身） | ✕ 出现在旧分支名旁，或切不到别的分支 | 查 branches 列表确认 |
| 4 | 本地处于 detached HEAD / 本地分支与远端不同名 | 显示 main 但实际不是远端 ref | git status 是否 HEAD detached |
| 5 | 网络/fetch 失败、代理拦截 | 弱网下出现，换网恢复 | 切 Wi-Fi/流量重试 |
| 6 | Token 权限不足或已过期（私有 fork / 未授权） | 只能看不能拉，分支校验失败 | 网页版能否打开、API 是否 401/403 |
| 7 | App 版本 bug | 同账号其他仓库正常，只有该仓库如此 | 升级 App / 换客户端验证 |
| 8 | 仓库为空（0 commit） | 新仓库常见 | 本仓库已有提交，排除 |

原记录把 #1（默认分支改名）和 #3（无权限）排在最前，实际本仓库两项都不成立，容易误导排查方向。

---

## 三、5 分钟定位步骤

1. 先网页验证：手机浏览器打开 https://github.com/Jdkshen/ai.js-pro ，能打开且默认分支是 main，排除仓库/权限问题。
2. App 内刷新：下拉刷新页面；再进分支选择器切到 sandbox/node24-image-xq，切回 main（强制重建引用）。
3. 清缓存：系统设置 → 应用 → GitHub → 存储 → 清除缓存（不是清除数据）；重进仓库。
4. 验证 Token（若登录过时）：退出重新登录一次。
5. 仍红叉：看 App 是否把分支当成本地 checkout（detached HEAD）——在 App 的代码页/终端执行 git status，出现 HEAD detached 就切回分支：git checkout main。
6. 升级 App：同账号换一台手机/网页端对照，确认是否该版本 bug。

### AI.js Pro 内可直接跑的验证脚本（Rhino / QuickJS 均可）

    function api(path) {
      var r = http.get("https://api.github.com" + path, {
        headers: { "User-Agent": "ai-js-pro-probe", "Accept": "application/vnd.github+json" }
      });
      return { status: r.statusCode, body: r.body.string() };
    }

    var repo = api("/repos/Jdkshen/ai.js-pro");
    log("repo: " + repo.status + " " + repo.body.slice(0, 300));

    var branches = api("/repos/Jdkshen/ai.js-pro/branches");
    log("branches: " + branches.status + " " + branches.body.slice(0, 500));

判定：
- repo 返回 200 且 "default_branch":"main"，branches 列表包含 main，说明仓库正常，红叉是 App 端问题；
- 返回 401/403/404，才需要查权限/仓库名/代理。

---

## 四、修复办法（按代价从低到高）

1. 下拉刷新 + 重新进入仓库（解决大部分缓存问题）；
2. 分支选择器切走再切回 main；
3. 清 App 缓存 / 强制停止后重开；
4. 退出登录再登录（刷新 token 与 ref 缓存）；
5. 在本地仓库执行 git fetch --all --prune；若是 detached HEAD，git checkout main；
6. 升级 GitHub App（或换客户端对照）；
7. 最后手段：卸载重装、重新 clone 该仓库。

---

## 五、防复发建议（仓库维护侧）

- 不要对 main 做 force-push：本次红叉高概率与历史里有过 force/rewrite 有关；日常用 git pull --ff-only / git merge。
- 保持默认分支稳定为 main，不要随意改名/删除；需要实验走 sandbox/* 分支。
- 合并前先 git fetch origin 看 ahead/behind，避免本地与远端分叉后再强推。
- 若确实要重写历史，提前通知所有客户端用户重新同步。

---

## 六、附：本次实测命令与原始返回（摘）

    GET https://api.github.com/repos/Jdkshen/ai.js-pro -> 200
    { "full_name": "Jdkshen/ai.js-pro", "default_branch": "main", "pushed_at": "2026-10-20T04:36:10Z", "private": false }

    GET https://api.github.com/repos/Jdkshen/ai.js-pro/branches -> 200
    [ "chore/reorganize-project-layout"(2b33e31b), "chore/upgrade-gradle-8"(45fae285), "main"(3013e67c), "sandbox/node24-image-xq"(b7877e05) ]
